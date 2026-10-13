package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.inkread.weekread.R;
import com.inkread.weekread.core.NoteStore;
import com.inkread.weekread.core.PagePrefs;
import com.inkread.weekread.ui.InkTheme;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🆕 TASK-089a：「阅读指纹」渲染引擎 —— **离线几何 + 运行时排版**。
 *
 * <p>唯一进 APK 的离线产物是 {@code res/raw/fp_paths.txt}（= {@code 阅读指纹/_lab/out/fp_paths.txt}
 * 逐字节拷贝，9,394 B / 530 行 / 22 条线 / 485 顶点，坐标 3 位小数）。它**只存几何**：
 * 每条折线的顶点。**不预存字位槽、不预存容量** —— 字位与容量都在运行时按卡面公式现算
 * （{@code 验证记录/244 §8} 的自证脚本 {@code _probe/t096/ref_formula.py} 已证明这套公式
 * 能逐值重出离线四档表）。
 *
 * <p>运行时口径（缺一不可，逐条都有离线证据）：
 * <ul>
 *   <li><b>字步长</b> {@code step = f × sp}；<b>容量</b> {@code cap_i = floor(L_i / step)}。</li>
 *   <li><b>字位</b>（spread 均布）：{@code cap_i >= 2} 时
 *       {@code d_k = f×0.5 + (L_i − f)·k/(cap_i−1)}；{@code cap_i == 1} 时 {@code d_0 = L_i×0.5}。</li>
 *   <li><b>字向</b> = 弧长 {@code d} 处 ±9px 窗口的切线角，wrap 进 {@code (−90°, 90°]}。</li>
 *   <li><b>净空过滤</b>：只保留「字心到**其它**线条折线的最小距离 ≥ cd」的字位；线条几何一点不动。
 *       丢掉的字位 = 表里的**跳过**（18px 档 = 2）。</li>
 *   <li><b>缺字</b>：该字位**留白**，不替换、不换字（与「净空跳过」是两回事，两个计数器分开）。</li>
 *   <li><b>居中</b>：{@code Align.CENTER} + {@code y_baseline = y_center − (ascent+descent)/2}
 *       —— <b>运行时现算</b>，不硬编码（离线实测：不修正会偏 6~8px，修正后残差 0.0 px）。</li>
 *   <li><b>墨量</b>：离线口径是字形覆盖度的 {@code αγ=0.5} 提升（{@code v' = round(255·(v/255)^0.5)}，
 *       笔画更实更黑、面积不扩张），<b>不是</b> {@code Paint.setAlpha(128)}（那是整体减淡，方向相反）。
 *       本类用 {@code ALPHA_8} 字形位图 + 256 项 LUT 复刻，按 (字, 字号) 缓存。</li>
 * </ul>
 *
 * <p>🔴 本卡**不碰** {@code DeskPageView}/{@code CardA11yService}/{@code DeskOverlay}/
 * {@code MainActivity}/{@code AndroidManifest}；不做触摸选书、不做保存分享（那两项是 TASK-089b）。
 */
final class FingerprintPoster implements InsightRenderer.Section {

    // ══════════════════════════════════════════════════════════════════════
    // 一、画布与四档常数表
    // ══════════════════════════════════════════════════════════════════════

    /** 离线画布（几何坐标系恒为 480×800；离线所有读数都在这个坐标系里量的）。 */
    static final int CANVAS_W = 480;
    static final int CANVAS_H = 800;

    /** 两端各留半个字（= 卡面 spread 公式里的 {@code f×0.5}）。 */
    private static final float MARGIN_HALF_GLYPH = 0.5f;

    /** 字向的切线窗口（±px，= 离线 {@code render3.point_at(..., win=9.0)}）。 */
    private static final float TANGENT_WIN = 9.0f;

    /**
     * 四档字距 {@code sp}（索引 = 字号 − 16）。
     * 🔴 来自 {@code 阅读指纹/_lab/out/四档常数表.txt}：16→1.00 / 17→1.00 / 18→0.95 / 19→1.00。
     */
    private static final float[] TIER_SP = { 1.00f, 1.00f, 0.95f, 1.00f };

    /**
     * 四档净空 {@code cd}（px，索引 = 字号 − 16）。
     * 🔴 来自同一张表：16→8.0 / 17→8.5 / 18→14.4 / 19→9.5。
     * ⚠️ {@code cd} 有**三个口径**（离线 base·sp=1.00 的 18px 是 14.1；本表 18px 档是
     * spread×0.95 的 **14.4**）——**不可混填**。16/17/19 三档的 cd 恰好等于搜索下限
     * {@code 0.5×字号}（这三档原始叠字本来就是 0，无需过滤）；只有 18px×0.95 真丢了 2 个字位。
     */
    private static final float[] TIER_CD = { 8.0f, 8.5f, 14.4f, 9.5f };

    /** 离线四档「原始落字（Σcap）」（只作注释参照，运行时不读；证明见 ref_formula.py）。 */
    // 16→548 / 17→516 / 18→512 / 19→460

    /** 离线四档「净空过滤后落字」（同上，只作参照）。 */
    // 16→548 / 17→516 / 18→510 / 19→460

    /** 离线四档「净空跳过」（同上，只作参照）。18px 档 = 2，其余 = 0。 */
    // 16→0 / 17→0 / 18→2 / 19→0

    /** 离线灰阶调色板 {@code render3.GRAY_PALETTE}（2 项：纯黑 + 1 档灰 128）。 */
    private static final int[] GRAY_PALETTE = { 0xFF000000, 0xFF808080 };

    /** 纸色（离线画布底 = 纯白）。 */
    private static final int PAPER = 0xFFFFFFFF;

    // ── 正文小尺（与 DeskModules / InsightRenderer 同尺，保证墨台各分区目视一致）──
    private static final float SZ_BODY = 15f;
    private static final float LINE_UNITS = 2.1f;

    // ── 文案（空态 / 生成中；文案待用户拍板，但不阻塞本卡）──
    private static final String HINT_EMPTY = "在上方选书以生成指纹";
    private static final String HINT_GENERATING = "生成中…";
    private static final String HINT_NO_TEXT = "选中的书还没有划线";

    // ── 🆕 TASK-089b：控制行（字号 4 档 / 选书 / 生成壁纸）的尺与色 ──
    /** 控制行高（单位倍数）。 */
    private static final float CTRL_H_UNITS = 30f;
    /** 控制行字号（单位倍数下的 px），与墨单/资产抬头同一档。 */
    private static final float SZ_SMALL = 12f;
    private static final int INK = 0xFF000000;
    /** 「生成壁纸」按钮尺寸（与墨单/资产同款：62 × 1.85 × SZ_SMALL）。 */
    private static final float WALL_W_UNITS = 62f;
    private static final float WALL_H_UNITS = 1.85f;
    /** 控制行内各控件之间的横向间距（单位倍数）。 */
    private static final float CTRL_GAP_UNITS = 8f;
    /** 控件内边距（单位倍数）。 */
    private static final float CTRL_PAD_UNITS = 6f;
    /** 「生成壁纸」装不下时的最小收缩比（与 `AssetSection.WALL_MAX_SHRINK` 同量级）。 */
    private static final float CTRL_MAX_SHRINK = 0.55f;

    /** 出图缓存上限（字符集有限，防异常输入把缓存撑爆）。 */
    private static final int TILE_CACHE_MAX = 4096;

    /**
     * 墨量 LUT 开关（默认开 = 复刻离线 {@code αγ=0.5}）。
     * 留成常量是为了 TASK-089b 真机上「字重够不够」的定档：关掉即退回普通抗锯齿字形。
     */
    static boolean fpAlphaLut = true;

    /** 墨量 LUT 的伽马（= 离线实际用值 0.5）。 */
    private static final double LUT_GAMMA = 0.5;

    /** 256 项 LUT：{@code v' = round(255·(v/255)^0.5)}。 */
    private static final byte[] LUT = buildLut(LUT_GAMMA);

    private static byte[] buildLut(double g) {
        byte[] t = new byte[256];
        for (int v = 0; v < 256; v++) t[v] = (byte) Math.round(255.0 * Math.pow(v / 255.0, g));
        return t;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 二、常驻实例（DeskModules.fpSection() 拿的就是它）
    // ══════════════════════════════════════════════════════════════════════

    static final FingerprintPoster SECTION = new FingerprintPoster();

    // ── 几何（进程内解析一次；`_lab/recompute/geom.py` 已证明文件本身自洽）──
    private static float[][] gX;      // [线][顶点]
    private static float[][] gY;
    private static float[] gLen;      // 每条线总长
    private static float[][] gCum;    // 每条线累积弧长（[k] = 到第 k 个顶点的弧长）
    private static boolean gLoaded;

    // ── 字形位图缓存（键 = (字号 << 16) | 字符）──
    private static final Map<Integer, Bitmap> TILE_CACHE = new HashMap<Integer, Bitmap>();

    /** 缺字判据用的 Paint（{@code hasGlyph} 只认 typeface，字号无关）。 */
    private static final Paint PROBE = newPaintWithSerif();

    private static Paint newPaintWithSerif() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(InkTheme.serif());
        return p;
    }

    // ── 本帧状态（由 refresh(Context) 刷新）──
    private Context appCtx;
    private final List<String> books = new ArrayList<String>();
    private int fontPx = PagePrefs.FP_FONT_DEFAULT;
    private String stateKey = "";
    private Slot[] slots;
    private Bitmap frame;          // 480×800 成品（懒生成 + 缓存）

    // ── 本帧诊断计数（ref_formula.py 是权威口径，这里是运行时实测）──
    private int sumCap;            // Σcap = 原始落字数
    private int clearDrop;         // 净空过滤丢掉的（= 表里的「跳过」）
    private int glyphMiss;         // 字体无此字形 ⇒ 该字位留白
    private int textShort;         // 文本长度不足 ⇒ 尾部字位留白
    private int textLen;           // 可用字符流长度
    private int[] streamCol;       // 逐字符颜色索引（与字符流等长；同一句共用一个索引）
    private final List<String> pending = new ArrayList<String>();   // 尚未同步的书

    private FingerprintPoster() {
    }

    // ══════════════════════════════════════════════════════════════════════
    // 三、InsightRenderer.Section
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public String title() {
        return "阅读指纹";
    }

    @Override
    public float height(float w, float vh, float unit) {
        float head = InsightRenderer.secHeadH(unit) + ctrlH(unit);
        if (hint() != null) return head + hintH(unit) + unit * 5f;
        float pad = w * InsightRenderer.PAD_X_RATIO;
        float dstW = w - 2f * pad;
        return head + dstW * CANVAS_H / (float) CANVAS_W + unit * 5f;
    }

    @Override
    public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
        float y = InsightRenderer.drawSectionHead(c, w, top, unit, p, title());
        // 🆕 TASK-089b：本模块自有的控制行（字号 4 档 / 选书 / 生成壁纸）。
        //    🔴 用它**自己的** Paint，绝不改 `InsightRenderer` 的共享 `p`。
        y = drawCtrl(c, w, unit, y);

        String hint = hint();
        if (hint != null) {
            InsightRenderer.drawCenteredIn(c, w, y, hintH(unit), unit, p, hint);
            return;
        }

        Bitmap f = ensureFrame();
        if (f == null) {   // 兜底：理论上 hint() 已覆盖，留一条不炸的路
            InsightRenderer.drawCenteredIn(c, w, y, hintH(unit), unit, p, HINT_GENERATING);
            return;
        }

        float pad = w * InsightRenderer.PAD_X_RATIO;
        float dstW = w - 2f * pad;
        float dstH = dstW * CANVAS_H / (float) CANVAS_W;
        // 🔴 这里的 `p` 是 InsightRenderer 的**共享** Paint，用完必须把改过的标志复位。
        p.setFilterBitmap(true);
        c.drawBitmap(f, null, new RectF(pad, y, pad + dstW, y + dstH), p);
        p.setFilterBitmap(false);
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 三·B、控制行（🆕 TASK-089b）：字号 4 档 / 选书 / 生成壁纸
    // ══════════════════════════════════════════════════════════════════════

    /** 「选书」文字按钮文案。 */
    private static final String PICK_LABEL = "选书";

    /**
     * 控制行高（单位倍数）。
     *
     * <p>🔴 放在**分区抬头下方独立一行**，而不是像 {@code AssetSection} 那样塞进抬头行右端：
     * 本行有 4 个字号码 + 「选书」+「生成壁纸」共 6 个矩形，塞进抬头行在 480 宽下会与
     * 「阅读指纹」标题相撞。独占一行 ⇒ 任一矩形互不相交（点字号绝不会误触「生成壁纸」）。
     */
    static float ctrlH(float unit) {
        return CTRL_H_UNITS * unit;
    }

    /** 控制行的画与命中**共用同一份几何**（纪律同 `AssetSection.sortRects`）。 */
    private final RectF[] fontRects = { new RectF(), new RectF(), new RectF(), new RectF() };
    private final RectF pickRect = new RectF();
    private final RectF wallRect = new RectF();
    private final Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * 控制行矩形。{@code rowTop} = **控制行顶边**（屏幕坐标；调用方已扣过滚动）。
     * 字号 4 档**左对齐**自 `pad` 起；「选书」右对齐到「生成壁纸」左侧。
     */
    private void ctrlRects(float w, float unit, float rowTop) {
        final float pad = w * InsightRenderer.PAD_X_RATIO;
        final float cy = rowTop + ctrlH(unit) * 0.5f;
        final float half = unit * SZ_SMALL * 0.95f;
        final float gap = unit * CTRL_GAP_UNITS;
        final float cpad = unit * CTRL_PAD_UNITS;
        cp.setTextSize(SZ_SMALL * unit);
        cp.setTypeface(null);
        // ── 最右端：「生成壁纸」（与墨单/资产同款尺寸 62 × 1.85 × SZ_SMALL）──
        final float wallHalf = SZ_SMALL * unit * WALL_H_UNITS * 0.5f;
        wallRect.set(w - pad - WALL_W_UNITS * unit, cy - wallHalf, w - pad, cy + wallHalf);
        // ── 「选书」：右对齐排在它左侧 ──
        final float pw = cp.measureText(PICK_LABEL) + cpad * 2f;
        pickRect.set(wallRect.left - gap - pw, cy - half, wallRect.left - gap, cy + half);
        // ── 字号 4 档：左对齐 ──
        float x = pad;
        for (int i = 0; i < fontRects.length; i++) {
            final float cw = cp.measureText(fontLabel(i)) + cpad * 2f;
            fontRects[i].set(x, cy - half, x + cw, cy + half);
            x += cw + gap;
        }
    }

    /** 画控制行，返回**下一行**的 y。 */
    private float drawCtrl(Canvas c, float w, float unit, float rowTop) {
        ctrlRects(w, unit, rowTop);
        final float sz = SZ_SMALL * unit;
        final int cur = fontIndex(fontPx);
        cp.setTypeface(null);
        cp.setTextAlign(Paint.Align.CENTER);
        cp.setStyle(Paint.Style.FILL);
        for (int i = 0; i < fontRects.length; i++) {
            RectF r = fontRects[i];
            final boolean on = (i == cur);
            cp.setTextSize(sz);
            cp.setFakeBoldText(on);
            cp.setColor(on ? INK : InkTheme.INK2);
            Paint.FontMetrics fm = cp.getFontMetrics();
            c.drawText(fontLabel(i), r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, cp);
            if (on) {   // 选中下划线（与资产三键同款：`InkTheme.RULE` 1px）
                cp.setStyle(Paint.Style.FILL);
                cp.setColor(InkTheme.RULE);
                final float ly = Math.round(r.bottom);
                c.drawRect(r.left, ly, r.right, ly + 1f, cp);
            }
        }
        cp.setFakeBoldText(false);
        cp.setTextSize(sz);
        cp.setColor(INK);
        Paint.FontMetrics f1 = cp.getFontMetrics();
        c.drawText(PICK_LABEL, pickRect.centerX(), pickRect.centerY() - (f1.ascent + f1.descent) / 2f, cp);
        // ── 「生成壁纸」：装不下就按 0.4f 步长逐次缩小（与 `AssetSection` 同法）──
        final String wl = wallLabel();
        float wsz = sz;
        final float limit = sz * CTRL_MAX_SHRINK;
        cp.setTextSize(wsz);
        while (wsz > limit && cp.measureText(wl) > wallRect.width()) {
            wsz -= 0.4f;
            cp.setTextSize(wsz);
        }
        Paint.FontMetrics f2 = cp.getFontMetrics();
        c.drawText(wl, wallRect.centerX(), wallRect.centerY() - (f2.ascent + f2.descent) / 2f, cp);
        cp.setTextAlign(Paint.Align.LEFT);
        return rowTop + ctrlH(unit);
    }

    /** 命中的字号档下标（0..3）；-1 = 未命中。{@code top} = 分区顶边（屏幕坐标）。 */
    int hitFont(float x, float y, float w, float unit, float top) {
        ctrlRects(w, unit, top + InsightRenderer.secHeadH(unit));
        for (int i = 0; i < fontRects.length; i++) {
            if (fontRects[i].contains(x, y)) return i;
        }
        return -1;
    }

    /** 命中「选书」。 */
    boolean hitPick(float x, float y, float w, float unit, float top) {
        ctrlRects(w, unit, top + InsightRenderer.secHeadH(unit));
        return near(pickRect, x, y, unit);
    }

    /** 命中「生成壁纸」。 */
    boolean hitWall(float x, float y, float w, float unit, float top) {
        ctrlRects(w, unit, top + InsightRenderer.secHeadH(unit));
        return near(wallRect, x, y, unit);
    }

    /** 与 `AssetSection.hitWall` 同一宽容度（字小、手指点不准）：四周各外扩 `6 × unit`。 */
    private static boolean near(RectF r, float x, float y, float unit) {
        if (r.isEmpty()) return false;
        final float m = unit * 6f;
        return x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m;
    }

    /** 第 {@code i} 档的字号码文案（越界回落默认档）。 */
    private static String fontLabel(int i) {
        final int[] ch = PagePrefs.FP_FONT_CHOICES;
        return String.valueOf((i >= 0 && i < ch.length) ? ch[i] : PagePrefs.FP_FONT_DEFAULT);
    }

    /** 字号 px → 档位下标；不在档内回落默认档。 */
    private static int fontIndex(int px) {
        final int[] ch = PagePrefs.FP_FONT_CHOICES;
        for (int i = 0; i < ch.length; i++) {
            if (ch[i] == px) return i;
        }
        for (int i = 0; i < ch.length; i++) {
            if (ch[i] == PagePrefs.FP_FONT_DEFAULT) return i;
        }
        return -1;
    }

    /** 「生成壁纸」文案（复用墨单同一字符串键 ⇒ 三处入口文案一致）。 */
    private String wallLabel() {
        return (appCtx != null) ? appCtx.getString(R.string.menu_gen_wallpaper) : "生成壁纸";
    }

    /**
     * 未就绪（本地无划线）的书 id —— 给 TASK-089b 触发 {@code NoteSync} 用。
     *
     * <p>🔴 只读快照，不改内部状态。
     */
    List<String> pendingBooks() {
        return new ArrayList<String>(pending);
    }

    /**
     * 当前提示文案（{@code null} = 有图可画）—— 给 TASK-089b 的「生成壁纸」失败回显用。
     *
     * <p>🔴 只读，不触发任何渲染 / 网络。
     */
    String hintText() {
        return hint();
    }

    private static float hintH(float unit) {
        return SZ_BODY * unit * LINE_UNITS;
    }

    /** 本帧该显示哪条提示；{@code null} = 有图可画。 */
    private String hint() {
        if (books.isEmpty()) return HINT_EMPTY;
        if (slots == null || slots.length == 0) {
            return pending.isEmpty() ? HINT_NO_TEXT : HINT_GENERATING;
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 四、刷新（DeskModules.FpModule.build() 每帧调一次）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 重读「字号 / 选中书」并在**状态键变化时**重建排版。
     *
     * <p>状态键含 {@code NoteStore.markFetchedAt(book)} ⇒ 某本书的划线刚同步下来时会自动重建
     * （不需要宿主额外通知）。键不变 ⇒ 每帧只花几次内存态 prefs 读，不重解析几何、不重排版。
     *
     * <p>🔴 契约：这里只读**本地**缓存（{@code NoteStore.marks} 读落盘 JSON），**永不发请求**。
     */
    void refresh(Context c) {
        if (c != null) appCtx = c.getApplicationContext();
        if (appCtx == null) return;

        List<String> bs = PagePrefs.fpBooks(appCtx);
        int f = PagePrefs.fpFontPx(appCtx);

        StringBuilder sb = new StringBuilder();
        sb.append(f);
        for (int i = 0; i < bs.size(); i++) {
            sb.append('|').append(bs.get(i)).append(':')
              .append(NoteStore.markFetchedAt(appCtx, bs.get(i)));
        }
        String key = sb.toString();
        if (key.equals(stateKey)) return;      // 无变化 ⇒ 保留现成的 frame / slots

        stateKey = key;
        books.clear();
        books.addAll(bs);
        fontPx = f;
        rebuild();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 五、排版（纯几何 + 净空过滤 + 填字）
    // ══════════════════════════════════════════════════════════════════════

    private void rebuild() {
        frame = null;
        slots = null;
        sumCap = 0;
        clearDrop = 0;
        glyphMiss = 0;
        textShort = 0;
        textLen = 0;
        pending.clear();

        if (appCtx == null || books.isEmpty()) return;
        loadPaths(appCtx);
        if (gLen == null || gLen.length == 0) return;

        char[] stream = collectStream();
        textLen = stream.length;
        if (textLen == 0) return;             // 未同步 / 该书没有划线 ⇒ 走提示行

        int tier = tierIndex(fontPx);
        float sp = TIER_SP[tier];
        float cd = TIER_CD[tier];

        ArrayList<Slot> out = new ArrayList<Slot>();
        float step = fontPx * sp;
        for (int i = 0; i < gLen.length; i++) {
            float L = gLen[i];
            int cap = (int) Math.floor(L / step);
            if (cap <= 0) continue;
            for (int k = 0; k < cap; k++) {
                float d;
                if (cap >= 2) d = fontPx * MARGIN_HALF_GLYPH + (L - fontPx) * k / (float) (cap - 1);
                else d = L * MARGIN_HALF_GLYPH;
                float[] pa = pointAt(i, d);
                if (minClearToOthers(i, pa[0], pa[1]) >= cd) {
                    out.add(new Slot(pa[0], pa[1], pa[2]));
                } else {
                    clearDrop++;
                }
            }
            sumCap += cap;
        }
        if (out.isEmpty()) return;

        // ── 填字：按字位顺序消费字符流；缺字的字位**留白**（不替换、不换字）──
        int gi = 0;
        for (int k = 0; k < out.size(); k++) {
            if (gi >= stream.length) { textShort = out.size() - k; break; }
            char ch = stream[gi];
            out.get(k).col = streamCol[gi];      // 同一句 / 同一条划线共用一个颜色
            gi++;
            if (PROBE.hasGlyph(String.valueOf(ch))) {
                out.get(k).ch = ch;
            } else {
                glyphMiss++;
            }
        }
        slots = out.toArray(new Slot[out.size()]);
    }

    /** 四档索引（字号 − 16）；非法字号已由 {@code PagePrefs.fpFontPx} 拦掉，这里只兜底钳一次。 */
    private static int tierIndex(int f) {
        int t = f - 16;
        if (t < 0) t = 0;
        if (t >= TIER_SP.length) t = TIER_SP.length - 1;
        return t;
    }

    /**
     * 按「句（= 一条划线）在字符流中的 0 基序号」做的**确定性散布**取色，得到不规则散点，
     * 与离线 {@code render3.color_idx_of}（按句内容取模）**观感等价、实现手段不同**。
     *
     * <p>手段 = 32 位乘法混合取第 31 位（黄金比常数 2654435761 = ⌊2³²/φ⌋）。
     * 纯 framework 算术：**不用摘要/Hash 类、不用 {@code Random}**，且跨帧稳定可复现。
     *
     * <p>⚠️ 残留差异（已登记在 {@code 验证记录/248 §6.1}）：离线是「按句内容散列」，同样的句子
     * 恒得同色、不同书之间也可复现；本实现按**流内序号**散布，改选书单会重排颜色。真机观感若
     * 需要「按内容」的稳定性，由 TASK-089b 拍板。
     */
    private static int spreadColor(int ordinal) {
        // 等价于取 (ordinal × 2654435761) 的 32 位乘积的第 31 位（0 / 1）。
        long h = ((long) ordinal) * 2654435761L;
        return (int) ((h >>> 31) & 1L);
    }

    /** 一个已落定的字位。{@code ch == 0} = 留白。 */
    private static final class Slot {
        final float x, y, ang;
        int col;
        char ch;

        Slot(float x, float y, float ang) {
            this.x = x;
            this.y = y;
            this.ang = ang;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 六、几何：解析 / 采样 / 距离
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 解析 {@code res/raw/fp_paths.txt}（进程内只做一次）。
     *
     * <p>格式（{@code _lab/out/README.md} §3 有完整说明）：
     * <pre>
     *   # 注释行
     *   L &lt;line&gt; &lt;len_px&gt; &lt;npts&gt;     ← 起一条新线
     *   x y                       ← 顶点（3 位小数），共 npts 行
     * </pre>
     */
    private static synchronized void loadPaths(Context c) {
        if (gLoaded) return;
        gLoaded = true;   // 失败也不重试（坏资源就一直是空几何，不影响其它分区）

        ArrayList<float[]> xs = new ArrayList<float[]>();
        ArrayList<float[]> ys = new ArrayList<float[]>();
        InputStream in = null;
        BufferedReader br = null;
        try {
            in = c.getResources().openRawResource(R.raw.fp_paths);
            br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            float[] cx = null;
            float[] cy = null;
            int n = 0;
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.length() == 0 || s.charAt(0) == '#') continue;
                String[] t = s.split("\\s+");
                if (s.charAt(0) == 'L') {
                    if (t.length < 4) continue;
                    int npts = Integer.parseInt(t[3]);
                    if (npts <= 0) { cx = null; continue; }
                    cx = new float[npts];
                    cy = new float[npts];
                    n = 0;
                    xs.add(cx);
                    ys.add(cy);
                } else if (cx != null && n < cx.length && t.length >= 2) {
                    cx[n] = Float.parseFloat(t[0]);
                    cy[n] = Float.parseFloat(t[1]);
                    n++;
                }
            }
        } catch (Throwable ignore) {
            // 资源缺失 / 解析失败 ⇒ 保持空几何（调用方走提示行）
        } finally {
            closeQuietly(br);
            closeQuietly(in);
        }

        int m = xs.size();
        gX = new float[m][];
        gY = new float[m][];
        gLen = new float[m];
        gCum = new float[m][];
        for (int i = 0; i < m; i++) {
            float[] px = xs.get(i);
            float[] py = ys.get(i);
            int n = px.length;
            gX[i] = px;
            gY[i] = py;
            float[] cum = new float[n];
            float tot = 0f;
            for (int k = 1; k < n; k++) {
                float dx = px[k] - px[k - 1];
                float dy = py[k] - py[k - 1];
                tot += (float) Math.sqrt(dx * dx + dy * dy);
                cum[k] = tot;
            }
            gCum[i] = cum;
            gLen[i] = tot;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignore) {
        }
    }

    /** 弧长 {@code d} 处的点（{@code d} 自动钳进 {@code [0, L]}）。 */
    private static float[] segAt(int i, float d) {
        float[] px = gX[i];
        float[] py = gY[i];
        float[] cum = gCum[i];
        int n = px.length;
        if (n == 1) return new float[] { px[0], py[0] };
        float L = gLen[i];
        if (d <= 0f) return new float[] { px[0], py[0] };
        if (d >= L) return new float[] { px[n - 1], py[n - 1] };
        // 累积弧长单调 ⇒ 二分
        int lo = 0, hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] <= d) lo = mid;
            else hi = mid;
        }
        float seg = cum[hi] - cum[lo];
        float t = (seg > 1e-9f) ? (d - cum[lo]) / seg : 0f;
        return new float[] { px[lo] + (px[hi] - px[lo]) * t, py[lo] + (py[hi] - py[lo]) * t };
    }

    /** 字位（{@code x,y}）与字向：±9px 窗口切线角 + wrap 进 (−90°, 90°]。 */
    private static float[] pointAt(int i, float d) {
        float[] p = segAt(i, d);
        float[] a = segAt(i, d - TANGENT_WIN);
        float[] b = segAt(i, d + TANGENT_WIN);
        double ang = Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
        return new float[] { p[0], p[1], wrapAngle((float) ang) };
    }

    /**
     * 角度归一 —— 🔴 与离线 {@code render3.wrap_angle} 逐字一致：
     * 先 {@code fmod(360)}；{@code >90 ⇒ −180}；{@code <=−90 ⇒ +180}。
     * 结果恒在 {@code (−90°, 90°]} ⇒ 离线那列「倒置」**恒为 0 是结构性结果**，不是标定性质。
     */
    static float wrapAngle(float a) {
        a = a % 360f;
        if (a > 90f) a -= 180f;
        else if (a <= -90f) a += 180f;
        return a;
    }

    /** 字心到**其它**线条折线的最小距离（净空判据）。 */
    private static float minClearToOthers(int pi, float x, float y) {
        float best = Float.MAX_VALUE;
        for (int j = 0; j < gLen.length; j++) {
            if (j == pi) continue;
            float[] px = gX[j];
            float[] py = gY[j];
            for (int k = 1; k < px.length; k++) {
                float d = distToSeg(x, y, px[k - 1], py[k - 1], px[k], py[k]);
                if (d < best) best = d;
            }
        }
        return best;
    }

    /** 点到线段的精确距离。 */
    private static float distToSeg(float px, float py, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float l2 = dx * dx + dy * dy;
        float t = 0f;
        if (l2 > 1e-12f) {
            t = ((px - x0) * dx + (py - y0) * dy) / l2;
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;
        }
        float qx = x0 + t * dx;
        float qy = y0 + t * dy;
        float ex = px - qx;
        float ey = py - qy;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 七、文本：从 NoteStore 的本地划线拼字符流（零网络）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 按 {@code fp_books} 顺序把各书的划线原文（{@code markText}）拼成一条字符流。
     *
     * <p>🔴 只读 {@code NoteStore.marks()} 的**落盘缓存**（`/book/bookmarklist` 的结果），
     * 渲染期零网络；未同步的书记进 {@code pending}（宿主据此显示「生成中」），拉取由既有
     * 同步链路异步做。
     *
     * <p>控制符与空白**不占字位**（直接丢），剩下的字符才进流 —— 这样「字位留白」只可能由
     * **缺字**或**文本不足**引起，与「净空跳过」三者互不混淆。
     *
     * <p>顺带产出 {@link #streamCol}：**同一句 / 同一条划线**的所有字共用一个颜色索引，
     * 索引由 {@link #spreadColor(int)} 按「该句在流中的 0 基序号」确定性散布（散点，非 ABAB）。
     */
    private char[] collectStream() {
        ArrayList<Character> cs = new ArrayList<Character>();
        ArrayList<Integer> cols = new ArrayList<Integer>();
        int runOrd = 0;
        for (int bi = 0; bi < books.size(); bi++) {
            String bid = books.get(bi);
            JSONArray a = NoteStore.marks(appCtx, bid);
            if (a == null) {
                pending.add(bid);      // 该书还没同步过划线（本地无缓存）
                continue;
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("markText", "");
                if (t == null) continue;
                int cIdx = spreadColor(runOrd);
                int before = cs.size();
                for (int k = 0; k < t.length(); k++) {
                    char ch = t.charAt(k);
                    if (ch <= ' ') continue;                 // 空白 / 换行 / 控制符：不占字位
                    if (ch == '\uFFFD') continue;            // 替换符：不占字位
                    cs.add(Character.valueOf(ch));
                    cols.add(Integer.valueOf(cIdx));
                }
                if (cs.size() > before) runOrd++;            // 只给「真的有字进流」的句推进序号
            }
        }
        char[] out = new char[cs.size()];
        for (int i = 0; i < out.length; i++) out[i] = cs.get(i).charValue();
        streamCol = new int[out.length];
        for (int i = 0; i < out.length; i++) streamCol[i] = cols.get(i).intValue();
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 八、绘制：ALPHA_8 字形 + LUT 提实（复刻离线 αγ=0.5）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 把已排好的字位画到 480×800 的位图上（= 离线 {@code render3.render(ss=1, gamma=1.0,
     * alpha=0.5, palette=GRAY_PALETTE)} 的等价路径）。
     *
     * <p>离线做法：把字形 mask（覆盖度）经 `v'=255·(v/255)^0.5` 提升后，用**整体色块 + mask**
     * 贴到白底上 ⇒ 覆盖度当**不透明度**用（边缘半透、两次重叠会更黑）。
     * Android 等价做法：{@code ALPHA_8} 位图（覆盖度）+ 带色 Paint 的 {@code drawBitmap}
     * ⇒ 同样是「色 × 覆盖度」合成到白底。
     */
    private void paintInto(Canvas cv, Slot[] ss) {
        Paint p = new Paint();
        p.setFilterBitmap(false);
        for (int i = 0; i < ss.length; i++) {
            Slot s = ss[i];
            if (s.ch == 0) continue;                       // 留白
            Bitmap t = tile(s.ch, fontPx);
            if (t == null) continue;
            int sz = t.getWidth();
            p.setColor(GRAY_PALETTE[s.col % GRAY_PALETTE.length]);
            cv.save();
            // 🔴 离线是 `mask.rotate(-ang)`（图像 y 向下）⇒ 走向 = +ang；
            //    Android `Canvas.rotate(+ang, x, y)` 在 y 向下的坐标系里同为顺时针 ⇒ 等价。
            //    （`_probe/t091/checks.py` 的 R9 已实测：PIL rotate(−ang) 的走向 = +ang。）
            cv.rotate(s.ang, s.x, s.y);
            cv.drawBitmap(t, s.x - sz / 2f, s.y - sz / 2f, p);
            cv.restore();
        }
    }

    /**
     * 取 (字, 字号) 的字形位图：{@code ALPHA_8} + 居中 + LUT 提实，带缓存。
     *
     * <p>尺寸取 {@code int(size × 2.2)}、字画在**正中心**（= 离线 {@code _glyph} 的
     * {@code anchor="mm"}）；居中的基线修正式就是本卡 R7：
     * {@code baseline = sz/2 − (ascent+descent)/2}（运行时现算，不硬编码）。
     */
    private static Bitmap tile(char ch, int size) {
        int key = (size << 16) | (ch & 0xFFFF);
        Bitmap hit = TILE_CACHE.get(Integer.valueOf(key));
        if (hit != null) return hit;

        int sz = (int) (size * 2.2f);
        if (sz < 4) sz = 4;
        Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ALPHA_8);
        } catch (Throwable t) {
            return null;
        }
        Canvas cv = new Canvas(bmp);
        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
        gp.setTypeface(InkTheme.serif());
        gp.setTextSize(size);
        gp.setTextAlign(Paint.Align.CENTER);
        gp.setColor(Color.WHITE);      // ALPHA_8：颜色只贡献 alpha=255
        Paint.FontMetrics fm = gp.getFontMetrics();
        float baseline = sz / 2f - (fm.ascent + fm.descent) / 2f;
        cv.drawText(String.valueOf(ch), sz / 2f, baseline, gp);

        if (fpAlphaLut) applyLutInPlace(bmp, sz * sz);

        if (TILE_CACHE.size() >= TILE_CACHE_MAX) TILE_CACHE.clear();
        TILE_CACHE.put(Integer.valueOf(key), bmp);
        return bmp;
    }

    /** 把 {@code ALPHA_8} 位图的每个覆盖度字节过一遍 LUT（原地）。 */
    private static void applyLutInPlace(Bitmap bmp, int n) {
        try {
            ByteBuffer bb = ByteBuffer.allocate(n);
            bmp.copyPixelsToBuffer(bb);
            byte[] a = bb.array();
            for (int i = 0; i < n; i++) a[i] = LUT[a[i] & 0xFF];
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(a));
        } catch (Throwable ignore) {
            // LUT 失败 ⇒ 退回未提实的字形（不阻断出图）
        }
    }

    /** 懒生成 + 缓存 480×800 成品。 */
    private Bitmap ensureFrame() {
        if (frame != null) return frame;
        if (slots == null || slots.length == 0) return null;
        Bitmap b = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(PAPER);
        paintInto(cv, slots);
        frame = b;
        return b;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 九、出图 API（给 TASK-089b 的保存 / 分享用）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 出图：{@code w×h} 画布上按 **480×800 几何坐标系**画一张指纹。
     *
     * <p>几何坐标系是固定的（离线底稿就在 480×800 里量的）；传别的 {@code w,h} 只是换画布尺寸
     * （超出部分裁掉、不足部分留白），**不会**拉伸几何。
     *
     * @param books 要画的书 id（{@code null} = 用当前 {@code fp_books}）；不受 50 本上限约束
     * @return 位图；无书 / 无文本 / 未初始化 ⇒ {@code null}
     */
    Bitmap renderToBitmap(int w, int h, List<String> books) {
        if (appCtx == null || w <= 0 || h <= 0) return null;
        final List<String> keep = new ArrayList<String>(this.books);
        try {
            if (books != null) {
                this.books.clear();
                for (int i = 0; i < books.size(); i++) {
                    String b = books.get(i);
                    if (b != null && b.trim().length() > 0) this.books.add(b.trim());
                }
            }
            rebuild();
            if (slots == null || slots.length == 0) return null;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            cv.drawColor(PAPER);
            paintInto(cv, slots);
            return out;
        } finally {
            if (books != null) {                 // 还原成 prefs 里的那批
                this.books.clear();
                this.books.addAll(keep);
                rebuild();
            }
        }
    }

    /**
     * 一行诊断（给 TASK-089b 的探针 / 真机核对用；权威口径仍是 {@code _probe/t096/ref_formula.py}）。
     *
     * <p>🔴 三个计数**互不相等也别惊讶**：{@code drop} 是净空过滤丢的（表里的「跳过」），
     * {@code miss} 是字体缺字，{@code short} 是文本不够长。
     */
    String statsLine() {
        return "fp f=" + fontPx + " books=" + books.size() + " pending=" + pending.size()
                + " text=" + textLen + " sumCap=" + sumCap + " slots=" + (slots == null ? 0 : slots.length)
                + " drop=" + clearDrop + " miss=" + glyphMiss + " short=" + textShort
                + " lut=" + fpAlphaLut;
    }
}
