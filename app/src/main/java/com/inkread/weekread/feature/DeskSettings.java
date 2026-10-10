package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.inkread.weekread.R;
import com.inkread.weekread.core.PagePrefs;
import com.inkread.weekread.ui.InkTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * 🆕 2026-10-10（用户诉求 ①）：**墨台的设置态** —— 纯 `Canvas` 自绘 + 命中。
 *
 * <h3>为什么自绘、不复用设置页</h3>
 * ① 用户的诉求是「把墨台的设置**放进墨台页面右上角**，从设置-自定义中移除」⇒ 它必须活在
 * **墨台这层覆盖窗内**；② 墨台窗是 {@code FLAG_NOT_FOCUSABLE} 的（{@code a11y/DeskOverlay}）
 * ⇒ **拿不到输入法**，原设置页里的 {@code EditText}（背景图路径）在这里根本没法用；
 * ③ S4 ROM 缺 {@code com.android.documentsui} ⇒ 系统文件选择器也弹不出来（已实测登记）。
 * <p>⇒ 结论：只能用**自绘控件**（本项目已有先例：软锁 PIN 键盘 {@code ui/KeyPadView}
 * 就是在同类覆盖窗里自绘 + 命中的，已验证可行）。
 *
 * <h3>内容（老设置页「墨台」分区的等价搬运 + 两轮用户拍板）</h3>
 * <pre>
 *   墨台
 *     [✓] 启用墨台
 *   内容模块
 *     [✓] 墨单                    [↑] [↓]
 *     …（按 PagePrefs 顺序，6 行）
 *     （提示：勾选决定是否显示；↑↓ 调整顺序…）
 * </pre>
 * 🔴 2026-10-11（TASK-18 · 用户 2026-10-10 指令①第 6 条「放弃墨台的壁纸，改为纯白色，
 * 删除相关设置」）：原先的**「背景」整组已删除** —— 纯白 / 自定义（固定路径
 * `Pictures/墨台背景.jpg`）、「应用」按钮、结果状态行、`label_desk_bg_tip`，以及
 * **「白纱不透明度」文字行 + 滑条**（白纱的语义是"把自定义背景压淡"，背景恒纯白后它
 * 是**失效控件** ⇒ 一并删除，不留在设置页里当摆设）。墨台背景自本卡起恒为纯白。
 * <p>旧值残留 ⇒ **保留不读**（{@code PagePrefs} 已无任何读取路径；不崩、不读图、不申请存储权限）。
 * <p>🔴 与「生成壁纸」的界线：本类只管**墨台设置态**；墨单海报的
 * {@code BillWallpaper} + {@code DeskPageView.MODE_WALLPAPER} 与本卡无关，未动。
 *
 * <h3>🔴 两条纪律</h3>
 * <ul>
 *   <li><b>画与命中共用同一份几何</b>：{@link #layout} 只算一次，`draw` 与 `hit` 都吃它 ⇒
 *       不会"看着点中了、其实没中"（与 {@code BillSection.tabStrip} 同做法）。</li>
 *   <li><b>只写偏好、不发请求</b>：本类只碰 {@link PagePrefs}（本地 prefs）与"固定路径能不能读到"
 *       这一次本地文件检查 ⇒ 零网络、零权限新增。</li>
 * </ul>
 *
 * <p>🔴 墨水屏铁律：纯黑白 + 三档灰、**无圆角 / 阴影 / 渐变 / 动画**。
 */
final class DeskSettings {

    // ── 尺（全部 ×unit，与墨台其余部分同尺）──
    private static final float SZ_HEAD  = 15f;
    private static final float SZ_ROW   = 14f;
    private static final float SZ_SMALL = 12f;

    private static final float HEAD_H_UNITS = 3.0f;   // ×SZ_HEAD×unit
    private static final float ROW_H_UNITS  = 2.5f;   // ×SZ_ROW×unit
    private static final float NOTE_LH      = 1.7f;   // 提示行行距（×字号）
    private static final float BOX_UNITS    = 20f;    // 勾选框边长
    private static final float BTN_UNITS    = 24f;    // ↑/↓ 按钮边长
    private static final float GAP_UNITS    = 6f;     // 小块之间
    private static final float PAD_TOP_UNITS = 6f;
    private static final float PAD_BOT_UNITS = 14f;

    private static final int INK   = 0xFF000000;
    private static final int GRAY  = 0xFF3C3C3C;
    private static final int LIGHT = 0xFFA8A8A8;
    private static final int LINE  = 0xFFD8D8D8;

    /** 候选图最多列几张 —— 🔴 2026-10-10 第二轮已随「固定路径」改造整体删除（不再扫目录）。 */

    // ── 行种类 ──
    // 🔴 2026-10-11（TASK-18）：原有 K_BG=5 / K_VEILBG=6 / K_VEIL=7 / K_APPLY=8 四种行已随
    //    「背景」整组删除；编号不回收、不复用（避免与旧日志/截图里的 kind 值混淆）。
    private static final int K_SEP    = 0;   // 1px 分隔线（不可点）
    private static final int K_HEAD   = 1;   // 分组大标题（不可点）
    private static final int K_TOGGLE = 2;   // 「启用墨台」整行可点
    private static final int K_MOD    = 3;   // 模块行左侧标签区（可点 ⇒ 切换勾选）
    private static final int K_MOD_BT = 4;   // 模块行的 ↑ / ↓ 小按钮（可点）

    /** 行（画与命中共用）。{@code r} 为**内容局部坐标**（y 从内容顶算，不含滚动）。 */
    static final class Item {
        int kind;
        RectF r = new RectF();
        String a;        // 主文本
        String b;        // 右端次文本（目录名 / ↑ / ↓）
        String id;       // 模块 id
        String tip;      // 提示正文（K_SEP 的 gap 位复用；见 buildNotes）
        float h;         // 该行高（px，已乘 unit）
        boolean on;      // 勾选 / 选中
        boolean enabled = true;   // ↑ 在顶 / ↓ 在底 ⇒ false（置灰且不响应）
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Context ctx;

    DeskSettings(Context c) {
        this.ctx = (c == null) ? null : c.getApplicationContext();
    }

    private List<Item> items = null;     // 布局缓存
    private float cw = -1f, cu = -1f;

    /** 进入设置态时调一次：重读偏好 + 失效布局缓存。 */
    void reload(Context c) {
        this.ctx = (c == null) ? null : c.getApplicationContext();
        invalidateCache();
    }

    /** 偏好改过 ⇒ 布局里那些"勾/选/序"的快照全废，重算。 */
    void invalidateCache() {
        items = null;
        cw = -1f;
        cu = -1f;
    }

    /** 内容总高（px，不含墨台顶栏）。 */
    float height(float w, float unit) {
        List<Item> ls = layout(w, unit);
        float h = PAD_TOP_UNITS * unit;
        for (int i = 0; i < ls.size(); i++) h += ls.get(i).h;
        return h + PAD_BOT_UNITS * unit;
    }

    // ══════════════════════ 布局（画与命中共用） ══════════════════════

    private List<Item> layout(float w, float unit) {
        if (items != null && cw == w && cu == unit) return items;
        cw = w;
        cu = unit;
        if (ctx == null) { items = new ArrayList<Item>(); return items; }   // 防御：未 reload 过

        final float pad = w * InsightRenderer.PAD_X_RATIO;
        final float right = w - pad;
        final float headH = SZ_HEAD * unit * HEAD_H_UNITS;
        final float rowH = SZ_ROW * unit * ROW_H_UNITS;

        List<Item> out = new ArrayList<Item>();
        float y = PAD_TOP_UNITS * unit;

        // ── ① 墨台 ──
        y = head(out, y, headH, "墨台", w, unit);
        Item tg = row(out, K_TOGGLE, pad, right, y, rowH);
        tg.a = ctx.getString(R.string.label_desk_enabled);
        tg.on = PagePrefs.isDeskEnabled(ctx);
        y += rowH;
        y = sep(out, y, unit);

        // ── ② 内容模块 ──
        y = head(out, y, headH, ctx.getString(R.string.label_desk_modules), w, unit);
        String[] order = PagePrefs.getDeskOrder(ctx);
        int mask = PagePrefs.getDeskOnMask(ctx);
        final float btn = BTN_UNITS * unit;
        final float gap = GAP_UNITS * unit;
        for (int i = 0; i < order.length; i++) {
            String id = order[i];
            int bit = PagePrefs.moduleBit(id);
            // 左侧标签区（含勾选框）—— 可点 ⇒ 切换勾选
            Item lab = row(out, K_MOD, pad, right - btn * 2f - gap * 2f, y, rowH);
            lab.id = id;
            lab.a = PagePrefs.moduleName(id);
            lab.on = (bit != 0 && (mask & bit) != 0);
            // ↑ / ↓
            Item up = row(out, K_MOD_BT, right - btn * 2f - gap, right - btn - gap, y, rowH);
            up.id = id;
            up.b = "↑";
            up.enabled = (i > 0);
            Item dn = row(out, K_MOD_BT, right - btn, right, y, rowH);
            dn.id = id;
            dn.b = "↓";
            dn.enabled = (i < order.length - 1);
            y += rowH;
        }
        y = note(out, ctx.getString(R.string.label_desk_modules_tip), y, w, unit);

        // 🔴 2026-10-11（TASK-18）：原「③ 背景」整组（纯白 / 自定义 / 「应用」按钮 / 结果状态行 /
        //    白纱文字行 + 滑条）已按用户指令①第 6 条删除 —— 墨台背景恒纯白，设置页不再有这一组。
        //    ⇒ 设置页现在只有两组：① 墨台（总开关）② 内容模块。本方法末尾不再 `sep()` 收尾，
        //      避免留一条"分组线下面什么都没有"的视觉尾巴（那正是验收要防的空白分组）。
        items = out;
        return out;
    }

    private static Item row(List<Item> out, int kind, float l, float r, float y, float h) {
        Item it = new Item();
        it.kind = kind;
        it.r.set(l, y, r, y + h);
        it.h = h;
        out.add(it);
        return it;
    }

    private static float head(List<Item> out, float y, float h, String text, float w, float unit) {
        Item it = row(out, K_HEAD, 0f, w, y, h);
        it.a = text;
        return y + h;
    }

    private static float sep(List<Item> out, float y, float unit) {
        Item it = new Item();
        it.kind = K_SEP;
        it.h = unit * 16f;
        // 🔴 分隔线的 y 从 `r.top` 取（绘制侧用它定位）⇒ 这里必须把 r 也填上，
        //    否则 r 停在 (0,0,0,0)、线会画到内容最顶上（真机上第一次写就踩过）。
        it.r.set(0f, y, 0f, y + it.h);
        out.add(it);
        return y + it.h;
    }

    /** 提示段落：按实测宽度折行（中文/数字宽度差很大 ⇒ 不能按字数估）。 */
    private float note(List<Item> out, String text, float y, float w, float unit) {
        if (text == null || text.length() == 0) return y;
        final float pad = w * InsightRenderer.PAD_X_RATIO;
        final float maxW = w - pad * 2f;
        final float sz = SZ_SMALL * unit;
        p.setTextSize(sz);
        p.setTypeface(null);
        List<String> ls = wrap(text, maxW);
        for (int i = 0; i < ls.size(); i++) {
            Item it = row(out, K_SEP, pad, w - pad, y, sz * NOTE_LH);
            it.tip = ls.get(i);
            y += it.h;
        }
        return y + unit * 6f;
    }

    private List<String> wrap(String s, float maxW) {
        List<String> out = new ArrayList<String>();
        String rest = s.replace('\n', ' ').trim();
        float[] mw = new float[1];
        int guard = 0;
        while (rest.length() > 0 && guard++ < 16) {
            int n = p.breakText(rest, true, maxW, mw);
            if (n <= 0) n = 1;
            if (n >= rest.length()) { out.add(rest); return out; }
            out.add(rest.substring(0, n).trim());
            rest = rest.substring(n).trim();
        }
        if (rest.length() > 0) out.add(rest);
        return out;
    }

    /** 背景提示（旧版：无候选时补充"多为没给存储读权限"）—— 🔴 随固定路径改造删除。
     *  🆕 诉求 ② 起：状态说明改由 `desk_bg_apply_hint` 与 `desk_bg_applied_{ok,none,fail}` 三条
     *  结果资源承担。🔴 2026-10-11（TASK-18）：**这四条资源与整个「背景」组也已删除** —— 墨台背景
     *  已恒纯白，不再有"应用背景图"这件事，故也不再需要任何状态说明。 */

    // ══════════════════════ 画 ══════════════════════

    /**
     * @param top 内容顶边在屏幕上的 y（= 墨台顶栏高 − 滚动位移）
     */
    void draw(Canvas c, float w, float unit, float top) {
        List<Item> ls = layout(w, unit);
        final float pad = w * InsightRenderer.PAD_X_RATIO;
        final float right = w - pad;

        c.save();
        c.translate(0f, top);

        for (int i = 0; i < ls.size(); i++) {
            Item it = ls.get(i);
            float cy = it.r.top + it.h / 2f;
            switch (it.kind) {
                case K_SEP:
                    if (it.tip != null) {
                        p.setStyle(Paint.Style.FILL);
                        p.setTypeface(null);
                        p.setFakeBoldText(false);
                        p.setColor(GRAY);
                        p.setTextAlign(Paint.Align.LEFT);
                        p.setTextSize(SZ_SMALL * unit);
                        Paint.FontMetrics fm = p.getFontMetrics();
                        c.drawText(it.tip, it.r.left, cy - (fm.ascent + fm.descent) / 2f, p);
                    } else {
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(LINE);
                        c.drawRect(pad, it.r.top + it.h / 2f, right, it.r.top + it.h / 2f + 1f, p);
                    }
                    break;

                case K_HEAD:
                    p.setStyle(Paint.Style.FILL);
                    p.setTypeface(InkTheme.serif());
                    p.setFakeBoldText(true);
                    p.setColor(INK);
                    p.setTextAlign(Paint.Align.LEFT);
                    p.setTextSize(SZ_HEAD * unit);
                    Paint.FontMetrics hm = p.getFontMetrics();
                    c.drawText(it.a, pad, cy - (hm.ascent + hm.descent) / 2f, p);
                    p.setTypeface(null);
                    break;

                case K_TOGGLE: {
                    text(it, c, pad + BOX_UNITS * unit + GAP_UNITS * unit, cy, SZ_ROW * unit, INK, false);
                    checkBox(c, pad, cy, BOX_UNITS * unit, it.on);
                    break;
                }

                case K_MOD: {
                    text(it, c, pad + BOX_UNITS * unit + GAP_UNITS * unit, cy, SZ_ROW * unit,
                            it.on ? INK : GRAY, it.on);
                    checkBox(c, pad, cy, BOX_UNITS * unit, it.on);
                    break;
                }

                case K_MOD_BT:
                    arrowBtn(c, it, unit, cy);
                    break;

                default:
                    break;
            }
        }
        c.restore();
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(false);
        p.setTypeface(null);
    }

    private void text(Item it, Canvas c, float x, float cy, float size, int color, boolean bold) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(null);
        p.setFakeBoldText(bold);
        p.setColor(color);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(size);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(it.a, x, cy - (fm.ascent + fm.descent) / 2f, p);
        p.setFakeBoldText(false);
    }

    /**
     * 🔴 2026-10-11（TASK-18）：原先这里还有 `textClipped`（候选图长文件名截断）——
     * 只服务被删的「自定义背景」行，已随之删除。
     */

    /** 勾选框：1px 直角描边 + 勾（🔴 禁圆角，墨水屏铁律）。 */
    private void checkBox(Canvas c, float x, float cy, float side, boolean on) {
        float t = cy - side / 2f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(on ? INK : LIGHT);
        c.drawRect(x + 0.5f, t + 0.5f, x + side - 0.5f, t + side - 0.5f, p);
        if (on) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(INK);
            c.drawLine(x + side * 0.24f, t + side * 0.52f, x + side * 0.44f, t + side * 0.72f, p);
            c.drawLine(x + side * 0.44f, t + side * 0.72f, x + side * 0.78f, t + side * 0.28f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /**
     * 🔴 2026-10-11（TASK-18）：原先这里还有 `radioBox`（单选）与 `outlineBtn`（「应用」按钮）——
     * 都只服务被删的「背景」组，已随之删除。滑条 `drawVeil` 同理。
     */

    /** ↑ / ↓ 小按钮：1px 直角描边 + 居中箭头（与 `btn_ink` 同一套视觉语言）。 */
    private void arrowBtn(Canvas c, Item it, float unit, float cy) {
        float side = Math.min(it.r.width(), BTN_UNITS * unit);
        float x = it.r.left + (it.r.width() - side) / 2f;
        float t = cy - side / 2f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(it.enabled ? INK : LINE);
        c.drawRect(x + 0.5f, t + 0.5f, x + side - 0.5f, t + side - 0.5f, p);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(null);
        p.setFakeBoldText(false);
        p.setColor(it.enabled ? INK : LIGHT);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(SZ_ROW * unit * 0.9f);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(it.b, x + side / 2f, cy - (fm.ascent + fm.descent) / 2f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ══════════════════════ 命中 ══════════════════════

    /**
     * 触点落在哪一项；没命中 ⇒ null。{@code y} 传**内容局部坐标**（屏幕 y − 内容顶边）。
     *
     * <p>🔴 纯算术查表，**不做任何副作用**：动作由 {@link #activate} 执行 ⇒ 拖动滑条那种
     * "按住后每次 move 都要重复触发"的场景才不会重复执行"改结构"的动作。
     */
    Item hitItem(float x, float y, float w, float unit) {
        List<Item> ls = layout(w, unit);
        for (int i = 0; i < ls.size(); i++) {
            Item it = ls.get(i);
            if (it.kind == K_SEP || it.kind == K_HEAD) continue;
            // 置灰的行一律不响应：如今只剩模块 ↑↓ 在顶/底会置灰
            if (!it.enabled && it.kind == K_MOD_BT) continue;
            if (it.r.contains(x, y)) return it;
        }
        return null;
    }

    /**
     * 取走本次动作要提示的一句（取后即清，宿主拿去弹 Toast）。
     *
     * <p>🆕 诉求 ②：墨屏「点击 → 重绘」要好几秒，光靠面板里的状态行反馈太慢 ⇒
     * 「应用」的结果同时走一条 Toast，点完立刻能看见。
     *
     * <p>🔴 2026-10-11（TASK-18）：唯一会 `pendingToast` 的「应用」按钮已随「背景」组删除 ⇒
     * 本方法现在**恒返回 null**；保留签名是因为宿主（{@code DeskPageView}）仍在调用它，
     * 删掉会牵动卡外文件，收益为零。
     */
    String consumeToast() {
        return null;
    }

    /**
     * 执行一项动作。@return true = 需要重画 + 重算滚动范围（含"只改了面板文案"的情形）。
     */
    boolean activate(Item it) {
        if (it == null) return false;
        switch (it.kind) {
            case K_TOGGLE:
                PagePrefs.setDeskEnabled(ctx, !it.on);
                invalidateCache();
                return true;

            case K_MOD:
                PagePrefs.setModuleOn(ctx, it.id, !it.on);
                invalidateCache();
                return true;

            case K_MOD_BT:
                PagePrefs.moveModule(ctx, it.id, "↑".equals(it.b) ? -1 : +1);
                invalidateCache();
                return true;

            default:
                return false;
        }
    }
}
