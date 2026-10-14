package com.inkread.weekread.feature;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;

import com.inkread.weekread.core.Bill;
import com.inkread.weekread.core.BillMoney;
import com.inkread.weekread.core.BillStore;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.MenuPrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.ui.InkTheme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 🆕 2026-10-10（用户诉求 ⑤）：把一期**墨单**渲染成一张「账单海报」位图 —— 供墨台内
 * **预览 + 保存**（原 `TASK-079` 的壁纸管家已随 `feature/DreamWallpaper` 分流删除，
 * 本类把它的渲染器**原样搬回**并改造成"返回 Bitmap + 另走保存"两步）。
 *
 * <h3>范式（照 {@link NoteExport} 的 C2 纯后台阶段）</h3>
 * <pre>
 *   纯 Canvas → Bitmap.createBitmap(W,H,RGB_565) → （预览留在内存 / 保存时 compress(PNG,100)）
 * </pre>
 * 🔴 <b>全程不碰 UI</b>（调用方丢到工作线程）；🔴 **渲染期零网络** —— 只读
 * {@link BillStore} 落盘的快照 + {@link MenuPrefs} 的显示配置。
 *
 * <h3>尺寸</h3>
 * **本机屏幕尺寸**（{@code DisplayMetrics}，取不到退回 480×800）—— 用户诉求原文
 * 「用墨单生成**本机屏幕尺寸**的壁纸」，所以不做内容自适应高度，就是一屏一张。
 *
 * <h3>画面</h3>
 * 纯白底 + 双线票据框 + 抬头（标题 / 单号）+ 副行（周结 · 区间 · N 本 · 时长）
 * + 表头 + 逐本（NO.xx 书名 ¥价 / 作者 · 时长 / 摘录）+ 整单备注 + 账单合计 + 落款。
 * 🔴 纯黑白 + 三档灰，无彩色 / 无渐变 / 无阴影（墨水屏铁律）。
 *
 * <h3>🔴 已知上限（如实登记，不假装没有）</h3>
 * 屏幕上放不下的部分（书目多且摘录长时）**按整块截断**并在末尾画一行
 * {@code 余 n 本未列出} —— 而不是把内容挤成看不清的小字。
 */
public final class BillWallpaper {

    private BillWallpaper() {
    }

    /** 基准画布：真实屏尺寸优先，取不到就退回 480×800（S4 的实际规格，见 docs/01）。 */
    private static final int BASE_W = 480;
    private static final int BASE_H = 800;

    private static final int INK   = 0xFF000000;
    private static final int GRAY  = 0xFF3C3C3C;
    /**
     * 🆕 task-39（用户第四批 #1）：提示小字 / 禁用态色。
     *
     * <p>旧值 `#A8A8A8`（白底 2.38:1）—— 与墨单 `feature/BillSection.LIGHT` **同一个 token**。
     * 本轮两边同值改为既有 token {@link InkTheme#INK2}（`#6B6459`，白底 5.85:1）：
     * **A11「海报与墨单同形制」要求同口径**，海报侧同步。
     * 🔴 只换颜色：字号 / 位置 / 字族 / 字重一律不动。
     */
    private static final int LIGHT = InkTheme.INK2;
    private static final int LINE  = 0xFFD8D8D8;

    /** 落盘目录名（在 App 私有 {@code files/} 下）—— 保存失败时的兜底目录。 */
    private static final String DIR = "wallbill";

    // ══════════════════════ ① 渲染（纯后台，返回位图） ══════════════════════

    /** 渲染结果。{@code bmp} 非空 = 成功（**调用方负责 recycle**）；否则看 {@code err}。 */
    public static final class Poster {
        public Bitmap bmp;
        public String err;
        public int width;
        public int height;
        public int itemDrawn;    // 实际画进去的书目数（供验证记录留痕）
        public int itemTotal;
    }

    /**
     * 🔴 **纯后台**：算版面 + 分配位图 + 绘制。**不碰任何 UI、不落盘**（保存见 {@link #save}）。
     *
     * @param mode        {@link PeriodRange#WEEKLY} / {@link PeriodRange#MONTHLY}
     * @param periodStart 该期起点（秒）
     * @param view        🆕 2026-10-10 第四轮（用户诉求 ①）：**当前菜单类别** ——
     *                    {@code MenuPrefs.VIEW_EXCERPT}（摘录菜单）/ {@code VIEW_READING}（读书菜单）。
     *                    🔴 **在哪个类别点「生成壁纸」就出哪个类别的海报**
     *                    （此前恒按摘录口径渲染，与用户所见脱节）。
     */
    public static Poster render(Context c, String mode, long periodStart, int view) {
        Poster r = new Poster();
        if (c == null || periodStart <= 0) {
            r.err = "参数不合法";
            return r;
        }
        final String m = PeriodRange.MONTHLY.equals(mode) ? PeriodRange.MONTHLY : PeriodRange.WEEKLY;

        Bill b = BillStore.load(c, m, periodStart);
        if (b == null) {
            r.err = "这一期墨单读不出来";
            return r;
        }

        // 视图口径（🆕 task-40：月历已下架 ⇒ 只剩摘录 / 读书两档）
        final int v = (view == MenuPrefs.VIEW_READING) ? MenuPrefs.VIEW_READING : MenuPrefs.VIEW_EXCERPT;

        int[] size = screenSize(c);
        final int w = size[0], h = size[1];
        r.width = w;
        r.height = h;

        try {
            r.bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            draw(ctx(c), new Canvas(r.bmp), w, h, b, r, v);
        } catch (Throwable t) {
            if (r.bmp != null) {
                try { r.bmp.recycle(); } catch (Throwable ignored) { }
                r.bmp = null;
            }
            r.err = "渲染失败：" + t.getClass().getSimpleName();
            CardDebug.note(c, "wallbill: " + r.err);
        }
        return r;
    }

    // ══════════════════════ ② 保存（纯后台） ══════════════════════

    /** 保存结果：`uri` 进相册 / `fallbackPath` 应用目录兜底 / `err` 非空 = 失败。 */
    public static final class Saved {
        public Uri uri;
        public String fallbackPath;
        public String err;
    }

    /** 🆕 TASK-087-W0：墨单海报的落盘名基（用户可见文件名前缀，**行为一字不变**）。 */
    public static final String PREFIX_BILL = "modan_";
    /** 🆕 TASK-087-W0：阅读资产海报的落盘名基（用户 2026-10-13 指令「资产海报保存文件名用 yuezi_* 前缀」）。 */
    public static final String PREFIX_ASSET = "yuezi_";
    /** 🆕 TASK-089b：阅读指纹海报的落盘名基（`fp_*`；与墨单 `modan_` / 资产 `yuezi_` 并列，互不回归）。 */
    public static final String PREFIX_FP = "fp_";

    /**
     * 把已渲染好的海报存成 PNG（**墨单海报**，名基 {@link #PREFIX_BILL}）。
     *
     * <p>🔴 **保存口径与「本记导出」逐字一致**（{@link NoteExport#renderAndSave}）：
     * MediaStore → {@code Pictures/微读墨记}（targetSdk 30 分区存储的正规姿势，**零新增权限**）；
     * 失败再退回 App 私有 {@code files/wallbill}（同样零权限）。
     */
    public static Saved save(Context c, Bitmap bmp, String mode, long periodStart) {
        return save(c, bmp, mode, periodStart, PREFIX_BILL);
    }

    /**
     * 同上，但显式指定落盘**文件名基**。
     *
     * <p>🆕 TASK-087-W0（用户 2026-10-13 指令）：墨单海报 = {@link #PREFIX_BILL}（`modan_*`，
     * 语义与旧 4 参版本完全一致）；阅读资产海报 = {@link #PREFIX_ASSET}（`yuezi_*`）。
     * 目录口径不变（{@code Pictures/微读墨记}；失败回退 {@code files/wallbill}，**回退时用同一个 name**）。
     *
     * @param prefix 名基；null 或空串 ⇒ 退回 {@link #PREFIX_BILL}
     */
    public static Saved save(Context c, Bitmap bmp, String mode, long periodStart, String prefix) {
        Saved r = new Saved();
        if (c == null || bmp == null) {
            r.err = "没有可保存的图片";
            return r;
        }
        final String m = PeriodRange.MONTHLY.equals(mode) ? PeriodRange.MONTHLY : PeriodRange.WEEKLY;
        final String base = (prefix == null || prefix.length() == 0) ? PREFIX_BILL : prefix;
        final String name = base + m + "_" + periodStart + "_" + System.currentTimeMillis() + ".png";

        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/微读墨记");
            Uri uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                OutputStream os = c.getContentResolver().openOutputStream(uri);
                if (os != null) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    os.flush();
                    os.close();
                    r.uri = uri;
                }
            }
        } catch (Throwable t) {
            r.uri = null;
        }
        if (r.uri != null) return r;

        // 兜底：写进 App 私有外部目录（不用任何权限），至少别让用户白等
        try {
            File dir = new File(c.getFilesDir(), DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                r.err = "建目录失败";
                return r;
            }
            File f = new File(dir, name);
            FileOutputStream fo = null;
            try {
                fo = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.flush();
                r.fallbackPath = f.getAbsolutePath();
            } finally {
                try { if (fo != null) fo.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            r.err = "保存失败：" + t.getClass().getSimpleName();
        }
        return r;
    }

    private static Context ctx(Context c) {
        return c.getApplicationContext();
    }

    /**
     * 真实屏尺寸（取不到就退回 480×800）；同时把过小的值钳掉（防 0 宽位图）。
     *
     * <p>🔴 TASK-111（2026-10-14）：**短边恒作宽、长边恒作高**（480×800 竖屏设计），与当前旋转无关。
     * 旧实现直接抄 `dm.widthPixels / heightPixels`，而本函数是**一次性取尺**（壁纸与资产海报都按
     * `s = H / 800f` 换算全部字号与几何、位图尺寸也随它）：若那次调用恰好落在横屏配置上
     * （服务重启 / 开机时设备是横的），整幅竖屏壁纸会被画成 800×480 且字号缩到 60%，并一直沿用。
     */
    static int[] screenSize(Context c) {
        int w = BASE_W, h = BASE_H;
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            if (dm != null) {
                int shortEdge = Math.min(dm.widthPixels, dm.heightPixels);   // 480 → 宽
                int longEdge = Math.max(dm.widthPixels, dm.heightPixels);    // 800 → 高
                if (shortEdge > 0) w = shortEdge;
                if (longEdge > 0) h = longEdge;
            }
        } catch (Throwable ignored) {
        }
        if (w < 240) w = BASE_W;
        if (h < 320) h = BASE_H;
        return new int[] { w, h };
    }

    // ══════════════════════ 绘制 ══════════════════════

    private static void draw(Context c, Canvas cv, int W, int H, Bill b, Poster r, int view) {
        // 基础字号 = 屏高 / 800（S4 ⇒ 1.0）；全部尺跟着它走 ⇒ 换分辨率不失衡
        final float s = H / 800f;
        final float sTitle  = BillSection.SZ_TITLE  * s;  // 抬头标题 —— 旧 30f*s，逐值对齐墨单 SZ_TITLE
        final float sSerial = BillSection.SZ_SERIAL * s;  // 抬头单号 —— 旧 18f*s，逐值对齐墨单 SZ_SERIAL
        final float sSub    = BillSection.SZ_BODY   * s;  // 副行 —— 旧 20f*s，逐值对齐墨单副行的 SZ_BODY
        /**
         * 🔴 2026-10-12（第七批 W2 / task-66 ⑤④，用户拍板**方案 B**）：正文族**收敛到墨单尺** ——
         * 直接复用同包墨单的三条常量（`feature/BillSection.java:68-69` `SZ_BODY = 15f` /
         * `SZ_SMALL = 12f`、`:71` `LH = 1.7f`、`:144` `PRICE_COL_W = 62f`）⇒ 两个表面**共用同一份尺**。
         *
         * <p>改前：正文 22f、元信息 16f、小字 15f、合计 18f、表头沿用元信息 16f、`lineH 1.55f`
         * （旧值见 `验证记录/232_…§5.1` 换算表）。
         *
         * <p>A11「与墨单口径逐字一致」**不受影响**：文字 / 金额 / 算法 / 口径全部来自同一批
         * `BillMoney`，改的只是字号与行距。表头与合计在墨单侧同为 `SZ_BODY`（表头 `:976`、
         * 合计行 `:1103`）⇒ 这里也用 `SZ_BODY`（新增 `sHead` 以免与元信息 12f 混用）。
         *
         * <p>🔴 2026-10-12 同日追加（Lead 裁定：抬头族**按「与墨台同名常量逐一对齐」**，不用
         * ×0.682 的整页比例）：抬头三行各自锚到墨单同名常量，海报侧**不再留自带字号数字** ——
         * <ul>
         *   <li>{@code sTitle} 30f → {@link BillSection#SZ_TITLE}（18f）：墨单抬头标题行 `:959`；</li>
         *   <li>{@code sSerial} 18f → {@link BillSection#SZ_SERIAL}（12f）：墨单单号常量（该常量在
         *       本轮之前**零引用** —— 墨单 masthead 实为单号+标题同行、同用 `SZ_TITLE`；取 12f 与
         *       `SZ_SMALL` 同值 ⇒ 与「单号也走小字档」的观感一致）；</li>
         *   <li>{@code sSub} 20f → {@link BillSection#SZ_BODY}（15f）：**逐字同源**的依据 —— 本行字符串
         *       （`kindLabel + " · " + rangeLabel + " · " + bookCount + " 本" + 时长`，见本文件 `:318-320`）
         *       与墨单 `feature/BillSection.java:964-966` **完全相同**，墨单以 `Row.line(sub, null,
         *       SZ_BODY * scB)` 绘制（`:967`）⇒ 海报副行取 `SZ_BODY`；</li>
         *   <li>落款（{@code footer()}）18f → {@code SZ_SMALL}（12f）：该行含
         *       {@code kindLabel + " " + serialOf}（**单号**）⇒ 属裁定所说的「提示小字/单号」类。</li>
         * </ul>
         *
         * <p>⚠️ 左右边距 `pad = 34f * s` **本轮不动**（墨单侧为 `w * PAD_X_RATIO`）：收它会连带改
         * 装饰与条码几何、超出用户诉求 ⇒ 232 记「预留」：日后仍觉书名不够完整时把 `pad` 收到与墨单
         * 同值（24 ⇒ 可用宽 412→432px，约 +2 字），**下一轮再议**。
         */
        final float sBody   = BillSection.SZ_BODY  * s;   // 正文 / 书名 / 金额（墨单 SZ_BODY）
        final float sHead   = BillSection.SZ_BODY  * s;   // 表头（墨单表头同为 SZ_BODY）
        final float sMeta   = BillSection.SZ_SMALL * s;   // 元信息 · 作者 · 时长（墨单 SZ_SMALL）
        final float sSmall  = BillSection.SZ_SMALL * s;   // 摘录 / 备注 / 提示 / 落款（墨单 SZ_SMALL）
        final float sTotal  = BillSection.SZ_BODY  * s;   // 合计块（墨单合计行同为 SZ_BODY）
        final float pad     = 34f * s;
        final float lineH   = BillSection.LH;             // 行距照墨单（原海报自有 1.55f）

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.LEFT);

        // ① 纯白底 + 双线票据框
        cv.drawColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(INK);
        p.setStrokeWidth(3f * s);
        cv.drawRect(14f * s, 14f * s, W - 14f * s, H - 14f * s, p);
        p.setStrokeWidth(1f * s);
        p.setColor(LINE);
        cv.drawRect(22f * s, 22f * s, W - 22f * s, H - 22f * s, p);
        p.setStyle(Paint.Style.FILL);

        final float left = pad;
        final float right = W - pad;
        final float avail = right - left;

        String title  = nz(MenuPrefs.title(c));
        if (title.length() == 0) title = MenuPrefs.DEFAULT_TITLE;
        String serial = Bill.serialOf(b.mode, b.periodStart);
        String unitPref = MenuPrefs.unit(c);

        // 🆕 2026-10-10 第四轮 ①：本张海报按**哪个菜单类别**出 —— 摘录菜单带价 / 表头 / 合计；
        //    读书菜单不显示价格，书名带《》、meta 追加进度%。
        final boolean excerpt = (view != MenuPrefs.VIEW_READING);

        // 🆕 2026-10-10 第五轮（TASK-086）：摘录菜单的**价格两列**（原价 │ 收入）与收入算法。
        //    🔴 海报与墨台屏幕**同一口径**（同一个 `MenuPrefs.paidAlgo` + 同一个 `BillMoney`）
        //    ⇒ 同一期墨单在屏幕上和壁纸上逐字一致（A11）。
        final String algo = MenuPrefs.paidAlgo(c);
        final boolean paidCol = excerpt && !MenuPrefs.PAID_OFF.equals(algo);
        /**
         * 单列宽（原价 / 收入各占一格；右对齐）—— ⑤④ 后**直接取墨单列宽** `PRICE_COL_W`（62f）。
         *
         * <p>旧值 `88f * s`。改后与墨单的比例关系**逐值相同**：海报 `62 / 15 = 4.13`，
         * 墨台 `(62·unit) / (15·unit) = 4.13` ⇒ 金额列能容纳的字形数与墨台一致，不会因为列变窄截断金额。
         */
        final float pCol = BillSection.PRICE_COL_W * s;

        float y = 22f * s + pad;

        // ② 抬头：标题（左，衬线加粗） + 单号（右，等宽）
        p.setTypeface(Typeface.SERIF);
        p.setFakeBoldText(true);
        p.setColor(INK);
        p.setTextSize(sTitle);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText(fit(p, title, avail * 0.55f), left, y + sTitle, p);
        p.setFakeBoldText(false);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(sSerial);
        p.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(serial, right, y + sTitle * 0.78f, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(null);
        y += sTitle * 1.36f;

        // ③ 副行：周结 · 区间 · N 本 · 时长
        p.setTypeface(Typeface.MONOSPACE);
        p.setColor(GRAY);
        p.setTextSize(sSub);
        String sub = Bill.kindLabel(b.mode) + " · " + Bill.rangeLabel(b.mode, b.periodStart)
                + " · " + b.bookCount() + " 本"
                + (b.placeholder ? "" : (" · " + dur(b.totalSec, unitPref)));
        cv.drawText(fit(p, sub, avail), left, y + sSub, p);
        y += sSub * lineH;

        y = sep(cv, p, left, right, y, s);

        // ④ 占位账（Q10）：只写一句，绝不产 0 值海报
        if (b.placeholder) {
            p.setTypeface(Typeface.SERIF);
            p.setColor(GRAY);
            p.setTextSize(sBody);
            p.setTextAlign(Paint.Align.CENTER);
            cv.drawText("本期无阅读记录", (left + right) / 2f, y + sBody * 3f, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(null);
            footer(cv, p, W, H, s, b);
            return;
        }

        // ⑤ 表头（沿用菜单隐喻：🆕 TASK-086 ⇒ 4 列 `序号 │ 品类 │ 原价 │ 收入`）+ 分隔线
        //    —— 🔴 **只在摘录菜单画**（读书菜单不显示价格列）
        //    🆕 TASK-12：收入列关（`paidCol == false`）时「原价」移到**最右列**（与收入列同 x），
        //    与 `BillSection.Row#draw` 改后的锚点**同一规则**（A11：海报必须与墨台屏幕逐字一致）。
        //    🔴 2026-10-11（TASK-086 第六批B ⑥）：用户可见「收入」统一改叫**收入**（Java 标识符不动）。
        //    🆕 第七批 W2（task-66 · 用户「只改表头文字」）：左列「品类」⇒**序号**、中列「主厨」⇒**品类**
        //    （与墨台 `BillSection` 表头同步；**只改字，不动任何坐标**）。
        //    🆕 第七批 W2（task-66 ⑤④ 方案 B）：表头字号由 `sMeta`(16f) 换成 **`sHead`**(15f) ——
        //    墨单表头用的就是 `SZ_BODY`（`BillSection.java:976`）⇒ 同尺。
        if (excerpt) {
            p.setTypeface(Typeface.MONOSPACE);
            p.setColor(GRAY);
            p.setTextSize(sHead);
            cv.drawText("序号", left, y + sHead, p);
            p.setTextAlign(Paint.Align.RIGHT);
            if (paidCol) cv.drawText("收入", right, y + sHead, p);
            cv.drawText("原价", paidCol ? (right - pCol) : right, y + sHead, p);
            p.setTextAlign(Paint.Align.LEFT);
            cv.drawText("品类", left + avail * 0.34f, y + sHead, p);
            y += sHead * lineH;
            y = sep(cv, p, left, right, y, s);
        }

        // ⑥ 底部保留区（分隔线 + 备注 + 合计 + 落款），先算出来 ⇒ 书目区不会压住它
        String wholeNote = nz(MenuPrefs.footerNote(c));
        // 🆕 task-48（用户第五批 #4）：合计块由 **1 行**变成 **1–3 行 + 一条装饰条形码** ⇒
        //   这里的预留高度必须**同步重算**，否则「先备料再判放得下」的机制会被压穿
        //   （书目区画到合计/落款上）。读书菜单不画合计 ⇒ 仍按原来的 1 行预留（版面不变）。
        final String[] tparts = excerpt ? totalMoneyParts(c, b, algo) : new String[] { null, null };
        final int tLines = excerpt ? (1 + (tparts[0] != null ? 1 : 0) + (tparts[1] != null ? 1 : 0)) : 1;
        float tailH = 0f;
        if (wholeNote.length() > 0) tailH += sSmall * lineH;
        tailH += sTotal * lineH * tLines;           // 账单合计（摘录 1–3 行 / 读书菜单 1 行）
        // 🔴 2026-10-11（第六批B ⑦）：条形码高度 ×2 ⇒ `barsH(s)` 已翻倍，这里**无需再乘**（它自己重算）。
        if (excerpt) tailH += barsH(s);             // 🆕 #4：合计下方铺满内容宽的装饰条形码一行
        //    🆕 第七批 W2（task-66 · 用户⑤）：底部小字「由「墨台」生成 · 仅在本机使用」已按用户指令
        //    **整行删除** ⇒ 这里只剩「落款」1 行（原来按 2 行预留，多留的那行会把书目区提前挤掉）。
        tailH += sSmall * lineH * 1f;               // 落款（水印行已删）
        tailH += s * 30f;
        final float limit = H - 22f * s - pad - tailH;

        // ⑦ 逐本：**先备料（含折行）再判放得下** —— 避免"画到一半才发现超界"
        r.itemTotal = b.items.size();
        final boolean showAuthor = MenuPrefs.showAuthor(c);
        final boolean showDuration = MenuPrefs.showDuration(c);
        int drawn = 0;
        for (int i = 0; i < b.items.size(); i++) {
            Bill.Item it = b.items.get(i);

            // 摘录：每条最多折 2 行（再长就截断加省略号）
            List<String> exLines = new ArrayList<String>();
            List<String> ex = excerptLines(c, it);
            if (!ex.isEmpty()) {
                p.setTypeface(Typeface.SERIF);
                p.setTextSize(sSmall);
                for (int k = 0; k < ex.size(); k++) {
                    exLines.addAll(wrap(p, ex.get(k), avail - s * 12f, 2));
                }
                p.setTypeface(null);
            }

            // 作者 · 时长（有才画 —— 不再"宽进严出"地白占一行）
            StringBuilder meta = new StringBuilder();
            if (showAuthor && nz(it.author).length() > 0) meta.append(it.author);
            if (showDuration) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(dur(it.readTimeSec, unitPref));
            }
            // 🆕 第四轮 ①：读书菜单在 meta 末尾追加进度%（摘录菜单不显示 —— 与墨台屏幕同口径）
            if (!excerpt && MenuPrefs.showProgress(c) && it.progressPct >= 0) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(it.progressPct).append("%");
            }

            float need = sBody * lineH;
            if (meta.length() > 0) need += sMeta * lineH;
            need += exLines.size() * sSmall * lineH;
            if (y + need > limit) break;

            if (i > 0) y += s * 8f;                 // 条目之间一口气

            // NO.xx 书名 ……… ¥价（摘录菜单）/ NO.xx 《书名》（读书菜单：不显示价格）
            p.setTypeface(Typeface.MONOSPACE);
            p.setFakeBoldText(true);
            p.setColor(INK);
            p.setTextSize(sBody);
            String no = String.format(java.util.Locale.US, "NO.%02d", i + 1);
            if (excerpt) {
                // 🆕 TASK-086：右端两列（收入贴右、原价在其左），列宽固定 ⇒ 与表头严格成列
                // 🆕 TASK-12：收入关（`dtxt == null`）时原价锚点 = `right`（占最右列），
                //    与 `BillSection.Row#draw` 同规则；收入开着时锚点仍是 `right − pCol` ⇒ 版面不变。
                final String ptxt = BillMoney.priceText(c, it);
                final String dtxt = paidCol ? BillMoney.paidText(c, it, algo) : null;
                p.setTextAlign(Paint.Align.RIGHT);
                // 🆕 TASK-086 第三批 · task-35：与 `feature/BillSection.Row#draw` **同口径** ——
                //    金额列改为「按需轻微横向压缩」而不是直接截断（预算 `pCol - 4s = 58s` ÷ 0.6em/字形
                //    = 6.44 字形 ⇒ 7 字形以上必被压缩）。A11 硬要求两表面同形制 ⇒ 上下两处规则一字不差。
                //    🆕 第七批 W2（task-66 ⑤④）：列宽收到墨单 `PRICE_COL_W`(62f)、字号收到 15f 后，
                //    本预算**逐值等同墨台**（墨台 62·unit − 4·unit = 69.6px ÷ 10.8px = 6.44 字形，
                //    旧海报 84s ÷ 13.2 = 6.36 字形）⇒ 金额可容纳量不降反微升。
                final float moneyMax = pCol - s * 4f;
                final float scPay = (dtxt != null) ? InkTheme.moneyScaleX(p, dtxt, moneyMax) : 1f;
                final String dfit = (dtxt != null) ? (scPay < 1f ? dtxt : fit(p, dtxt, moneyMax)) : null;
                if (dfit != null) {
                    p.setTextScaleX(scPay);
                    cv.drawText(dfit, right, y + sBody, p);
                    p.setTextScaleX(1f);
                }
                final float scPrice = InkTheme.moneyScaleX(p, ptxt, moneyMax);
                final String pfit = (scPrice < 1f) ? ptxt : fit(p, ptxt, moneyMax);
                final float priceX = (dtxt != null) ? (right - pCol) : right;   // 🔴 TASK-12：收入关 ⇒ 原价贴右
                p.setTextScaleX(scPrice);
                cv.drawText(pfit, priceX, y + sBody, p);
                p.setTextScaleX(1f);
                p.setTextAlign(Paint.Align.LEFT);
                // 🔴 同 `BillSection.Row#draw` 的上机教训：标题可用宽 = min(列式界, 原价**实际墨迹**左沿 − 间隔)。
                //    只按列宽推会在「收入关」时把标题放进价格列里 ⇒ 画出来糊成乱码（A11 要求两边一致）。
                //    🆕 第七批 W2（task-66 ⑤①「书名更完整，尽量和墨台屏幕对齐」）：两处间隔由 `s*10f`
                //    收紧到与 `BillSection.Row#draw` **同值**（列式界 `−8f*s`、墨迹界 `−6f*s`，原来各多留 2–4f*s）
                //    ⇒ 同样的列宽能多显示 0.3–0.5 个字。
                //    🔴 2026-10-12（Lead 复核**订正我的错误判断**）：书名显示少与序号前缀**无关** ——
                //    两侧前缀逐字相同（此处与墨台 `BillSection.java:1028` 都是 `no + "  " + title`，
                //    `no` 也都是 `String.format(Locale.US, "NO.%02d", i + 1)`）⇒ 真因是**字号**：
                //    可用宽 412px 下 22f ≈ 31 字符/行、15f ≈ 46 字符/行。⑤④ 方案 B 已按此收敛字号
                //    （见本方法开头 `sBody` 注释与 `验证记录/232_…§5.1` 换算表）。
                final float used = pCol * ((dtxt != null) ? 2f : 1f);
                float titleMax = avail - used - s * 8f;
                final float titleMaxInk = priceX - p.measureText(pfit) - left - s * 6f;
                if (titleMaxInk < titleMax) titleMax = titleMaxInk;
                cv.drawText(fit(p, no + "  " + nz(it.title), titleMax), left, y + sBody, p);
            } else {
                cv.drawText(fit(p, no + "  《" + nz(it.title) + "》", avail), left, y + sBody, p);
            }
            p.setFakeBoldText(false);
            y += sBody * lineH;

            // 作者 · 时长
            if (meta.length() > 0) {
                p.setTypeface(Typeface.MONOSPACE);
                p.setColor(GRAY);
                p.setTextSize(sMeta);
                cv.drawText(fit(p, meta.toString(), avail), left, y + sMeta, p);
                y += sMeta * lineH;
            }

            // 摘录（🔴 本人划线**不加前缀**；公开热门必须标「热门：」，TASK-077/077b 口径）
            if (!exLines.isEmpty()) {
                p.setTypeface(Typeface.SERIF);
                p.setColor(GRAY);
                p.setTextSize(sSmall);
                for (int k = 0; k < exLines.size(); k++) {
                    cv.drawText(exLines.get(k), left + s * 12f, y + sSmall, p);
                    y += sSmall * lineH;
                }
                p.setTypeface(null);
            }
            drawn++;
        }
        r.itemDrawn = drawn;

        // 放不下的部分：如实说明，不硬挤
        if (drawn < b.items.size()) {
            p.setTypeface(Typeface.MONOSPACE);
            p.setColor(LIGHT);
            p.setTextSize(sSmall);
            cv.drawText("余 " + (b.items.size() - drawn) + " 本未列出（一屏放不下）",
                    left, y + sSmall, p);
            p.setTypeface(null);
            y += sSmall * lineH;
        }

        // ⑧ 票据尾：紧接书目（分隔线 / 备注 / 合计），落款另钉在页底
        float ty = y + s * 10f;
        if (excerpt || wholeNote.length() > 0) ty = sep(cv, p, left, right, ty, s);
        if (wholeNote.length() > 0) {
            p.setTypeface(Typeface.MONOSPACE);
            p.setColor(GRAY);
            p.setTextSize(sSmall);
            List<String> nl = wrap(p, "整单备注：" + wholeNote, avail, 2);
            for (int i = 0; i < nl.size(); i++) {
                cv.drawText(fit(p, nl.get(i), avail), left, ty + sSmall, p);
                ty += sSmall * lineH;
            }
            p.setTypeface(null);
        }
        if (excerpt) {                       // 🔴 读书菜单不显示价格 ⇒ 也不画合计行
            p.setTypeface(Typeface.MONOSPACE);
            p.setFakeBoldText(true);
            p.setColor(INK);
            p.setTextSize(sTotal);
            // 🆕 task-48（用户第五批 #3/#4）：合计由「单行 + 压缩字距」改为
            //   **左标签 + 右对齐 1–3 行**，再在下方压一条**铺满内容宽**的装饰条形码（票据观感）。
            //   🔴 金额仍**只来自 `BillMoney`**（同一套 `paidCount/totalPaidFen/totalEstimated`）
            //   ⇒ A11「与墨单同源同词」不变：差的只是**版面**（墨单 1 行 / 海报 2–3 行）。
            //   第 1 行：左「合计」+ 右「(本期)阅读 H 小时」；第 2/3 行：右对齐金额段（可缺）。
            //   🔴 2026-10-11（第六批B ⑦）：三行字号曾由 `sBody`(22) 收到 `sTotal`(18)，行距同步收紧。
            //   🆕 2026-10-12（第七批 W2 · task-66 ⑤④ 方案 B）：`sTotal` 与 `sBody` 现同取墨单
            //   `SZ_BODY`(15f) ⇒ 合计块与正文同尺（墨单合计行同为 `SZ_BODY`，`BillSection.java:1103`）；
            //   首行基线由 `sBody` 改写本块自己的 `sTotal`（改后两值相同，纯粹为不再跨常量耦合）。
            p.setTextAlign(Paint.Align.LEFT);          // 显式声明（不依赖上一段的状态）
            cv.drawText("合计", left, ty + sTotal, p);
            p.setTextAlign(Paint.Align.RIGHT);
            String hoursTxt = totalHours(b, algo);
            cv.drawText(fit(p, hoursTxt, avail * 0.72f), right, ty + sTotal, p);
            ty += sTotal * lineH;
            if (tparts[0] != null) {
                cv.drawText(fit(p, tparts[0], avail), right, ty + sTotal, p);
                ty += sTotal * lineH;
                if (tparts[1] != null) {
                    cv.drawText(fit(p, tparts[1], avail), right, ty + sTotal, p);
                    ty += sTotal * lineH;
                }
            }
            p.setTextAlign(Paint.Align.LEFT);
            p.setFakeBoldText(false);
            p.setTypeface(null);
            ty = bars(cv, p, left, right, ty, s);      // 🆕 #4：装饰条形码一行（纯装饰、不编码数据）
        }

        footer(cv, p, W, H, s, b);
    }

    /**
     * 落款：`微读墨记 · 周结 2026-W40`（居中）。
     *
     * <p>🆕 第七批 W2（task-66 · 用户⑤「底部小字…确认删除」）：原先落款下方还有一行
     * {@code 由「墨台」生成 · 仅在本机使用} 的小字，**已整行删除**（含它用到的
     * {@code setFakeBoldText(false)/MONOSPACE/LIGHT/13f*s} 设置块）。
     *
     * <p>🔴 2026-10-12（第七批 W2 / task-66 ⑤④ · Lead 裁定「抬头族与落款逐项对齐墨台同名常量」）：
     * 字号由海报自带的 {@code 18f * s} 换成墨单同名的 {@link BillSection#SZ_SMALL}（**12f**）——
     * 本行含「单号」（{@code Bill.serialOf}）⇒ 属裁定所说的「提示小字/单号」类。若日后要恢复
     * 署名层级的视觉重量，只需把这一行改回 {@link BillSection#SZ_TITLE}（18f）。
     */
    static void footer(Canvas cv, Paint p, int W, int H, float s, Bill b) {
        float base = H - 22f * s - 34f * s;
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.SERIF);
        p.setFakeBoldText(true);
        p.setColor(INK);
        p.setTextSize(BillSection.SZ_SMALL * s);
        cv.drawText("微读墨记 · " + Bill.kindLabel(b.mode) + " " + Bill.serialOf(b.mode, b.periodStart),
                W / 2f, base, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(null);
        p.setFakeBoldText(false);
    }

    /** 画一条 1px 分隔线并推进 y（返回新的 y）。🆕 task-48（#5）：与墨单同族 ⇒ 用票据横线色
     *  {@link InkTheme#RULE}（旧 `LINE 0xFFD8D8D8` 白底 1.43:1 几乎不可见）+ 整数行对齐。 */
    static float sep(Canvas cv, Paint p, float left, float right, float y, float s) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(InkTheme.RULE);
        final float ly = Math.round(y + s * 6f);
        cv.drawRect(left, ly, right, ly + 1f, p);
        return y + s * 16f;
    }

    // ══════════════════════ 摘录（与 BillSection 同一口径） ══════════════════════

    /**
     * 「摘录策略」4 档 → 要画的文本行。
     * 🔴 **个人划线不加前缀**（直接给原文）；**公开热门必须标「热门：」** —— 绝不让用户以为是自己划的。
     */
    private static List<String> excerptLines(Context c, Bill.Item it) {
        List<String> out = new ArrayList<String>();
        String mode = MenuPrefs.excerptMode(c);
        String mine = nz(it.mineText);
        String hot = nz(it.hotText);
        if (MenuPrefs.EX_MINE.equals(mode)) {
            if (mine.length() > 0) out.add(mine);
        } else if (MenuPrefs.EX_HOT_ONLY.equals(mode)) {
            if (hot.length() > 0) out.add("热门：" + hot);
        } else if (MenuPrefs.EX_HOT_FILL.equals(mode)) {
            if (mine.length() > 0) out.add(mine);
            else if (hot.length() > 0) out.add("热门：" + hot);
        } else {   // EX_ANY：个人优先，其后热门
            if (mine.length() > 0) out.add(mine);
            if (hot.length() > 0) out.add("热门：" + hot);
        }
        return out;
    }

    // ══════════════════════ 文本工具 ══════════════════════

    /**
     * 🆕 TASK-086（Q5）→ 🆕 task-39（用户第四批 #5）→ 🆕 task-48（用户第五批 #3）：
     * 合计块**第 1 行右半** —— 增额档 `阅读 16.9 小时` / 累计档 `本期阅读 16.9 小时`。
     *
     * <p>🔴 与 `feature/BillSection#billTotalLine` **同一套拼法**（同一句中文、同一个取整口径、
     * 同一处「本期阅读 → 阅读」的简化、累计档加「本期」二字）—— A11 靠这条守住：
     * **改一处必须改另一处**。金额段由 {@link #totalMoneyParts} 提供（同墨单的同一套口径）。
     */
    private static String totalHours(Bill b, String algo) {
        String lead = MenuPrefs.PAID_CUMULATIVE.equals(algo) ? "本期阅读 " : "阅读 ";
        return lead + BillMoney.hoursText(b.totalSec) + " 小时";
    }

    /**
     * 🆕 task-48（用户第五批 #3/#4）：合计块**第 2/3 行** —— `{读回段, 折合段}`（各可为 null）。
     *
     * <p>🔴 与 `feature/BillSection#billTotalNote` **同一套拼法**（同一套"不写"判据、同一个 `≈`
     * 前缀规则、同一个"只累加算得出的行"），只在**版面上**拆成两行：
     * <ul>
     *   <li>增额档：`(≈)读回 ¥X` + `折合 ¥Y/小时`（`H = 0` 时折合为 null）；</li>
     *   <li>累计档：`(≈)累计读回 ¥X` + **null**（累计额 ÷ 本期时长 = 跨口径混算 ⇒ 不写折合）。</li>
     * </ul>
     * 返回 `{null, null}` = 这一段不写（收入关 / 一行都算不出来）。
     */
    static String[] totalMoneyParts(Context c, Bill b, String algo) {
        if (MenuPrefs.PAID_OFF.equals(algo)) return new String[] { null, null };
        if (BillMoney.paidCount(c, b.items, algo) <= 0) return new String[] { null, null };
        final boolean cum = MenuPrefs.PAID_CUMULATIVE.equals(algo);
        long pay = BillMoney.totalPaidFen(c, b.items, algo);
        // 🆕 TASK-086 第三批（A18）：合计含估算行 ⇒ 整段前缀 `≈`（与 `BillSection#billTotalNote`
        //    **同一套拼法** —— A11 靠这条守住：改一处必须改另一处）。
        String first = (BillMoney.totalEstimated(c, b.items, algo) ? "≈" : "")
                + (cum ? "累计读回 " : "读回 ") + BillMoney.yuan(pay);
        String second = null;
        float h = b.totalSec / 3600f;
        if (!cum && h > 0f) second = "折合 " + BillMoney.yuan((int) Math.round(pay / h)) + "/小时";
        return new String[] { first, second };
    }

    /**
     * 🆕 task-48（#4）：装饰条形码一行的推进量（见 {@link #bars}）。
     *
     * <p>🔴 2026-10-11（第六批B ⑦）：用户要求「条形码高度 ×2」⇒ 推进量一并 **22s → 44s**
     * （高度翻倍而不加推进量就会与下一行叠字）。`tailH` 用的就是本方法 ⇒ **自动重算**，不留两处数。
     */
    static float barsH(float s) {
        return s * 44f;
    }

    /**
     * 🆕 task-48（用户第五批 #4）：**装饰条形码一行** —— 循环铺满 `left→right`。
     *
     * <p>🔴 **纯自绘、不编码任何真实数据**（沿用 `feature/BillSection.Row#drawBars` 的同一图样
     * `pat` 与同一画法；那边是固定 `92×unit` 宽 + 右侧 `墨单 · 期号` 说明，这边是**铺满内容宽**
     * 的纯装饰条）。画法与 `s` 同尺 ⇒ 换分辨率不失衡。
     *
     * @return 推进后的 y
     */
    static float bars(Canvas cv, Paint p, float left, float right, float y, float s) {
        // 🔴 2026-10-11（第六批B ⑦）：条高 11s → **22s**（×2）；`cy` 取半高 ⇒ 与 `barsH` 的 44s 对齐。
        final float cy = y + s * 22f;
        final float top = cy - s * 11f, bot = cy + s * 11f;
        final int[] pat = { 3, 1, 1, 2, 1, 3, 1, 2, 2, 1, 3, 1, 1, 2, 3, 1, 2, 1, 1, 3, 2, 1, 1, 2, 1 };
        p.setStyle(Paint.Style.FILL);
        p.setColor(INK);
        float x = left;
        int i = 0;
        while (x < right - 0.5f && i < 400) {
            final int v = pat[i % pat.length];
            float bw = s * (v * 0.9f + 0.4f);
            if (x + bw > right) bw = right - x;
            if (bw > 0.5f) cv.drawRect(x, top, x + bw, bot, p);
            x += bw + s * (1.1f + (v % 2));
            i++;
        }
        return y + barsH(s);
    }

    /** 时长文案：小时档 `3h39m` / 分钟档 `219m`（与墨单**同一个口径**）。 */
    private static String dur(int sec, String unit) {        if (sec <= 0) return MenuPrefs.UNIT_M.equals(unit) ? "0m" : "0h00m";
        if (MenuPrefs.UNIT_M.equals(unit)) return (sec / 60) + "m";
        int h = sec / 3600, m = (sec % 3600) / 60;
        return h + "h" + (m < 10 ? "0" + m : String.valueOf(m)) + "m";
    }

    /** 折行（按实测宽度；最多 {@code maxLines} 行，末行截断加省略号）。 */
    static List<String> wrap(Paint p, String sIn, float maxW, int maxLines) {
        List<String> out = new ArrayList<String>();
        String s = nz(sIn).replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() == 0 || maxW <= 0f) return out;
        float[] mw = new float[1];
        int guard = 0;
        while (s.length() > 0 && guard++ < 16) {
            if (maxLines > 0 && out.size() >= maxLines - 1) { out.add(fit(p, s, maxW)); return out; }
            int n = p.breakText(s, true, maxW, mw);
            if (n <= 0) n = 1;
            if (n >= s.length()) { out.add(s); return out; }
            out.add(s.substring(0, n).trim());
            s = s.substring(n).trim();
        }
        if (s.length() > 0) out.add(fit(p, s, maxW));
        return out;
    }

    /** 按实测宽度截断 + 省略号。 */
    static String fit(Paint p, String s, float maxW) {
        if (s == null) return "";
        if (maxW <= 0f) return "";
        if (p.measureText(s) <= maxW) return s;
        for (int i = s.length() - 1; i > 0; i--) {
            String t = s.substring(0, i) + "…";
            if (p.measureText(t) <= maxW) return t;
        }
        return "…";
    }

    static String nz(String s) { return s == null ? "" : s; }
}
