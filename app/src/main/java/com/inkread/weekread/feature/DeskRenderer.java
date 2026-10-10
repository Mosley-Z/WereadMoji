package com.inkread.weekread.feature;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.inkread.weekread.core.BgImageUtil;
import com.inkread.weekread.ui.InkTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * 🆕 TASK-075：**墨台**的内容渲染器（分区排版 + 背景 + 顶栏）—— 照 {@link InsightRenderer} 的范式。
 *
 * <p>结构 = 顶栏（`‹ 返回` + 居中衬线「墨台」+ 右「更新于 HH:MM」+ 1px 分隔线）
 * + 若干 {@link InsightRenderer.Section}（自上而下）；背景先画（自选图 `centerCrop` + 全屏白纱，
 * 未设图 ⇒ 浅色底 + 细网点）。🔴 **只管"怎么排、怎么画"，不管滚动**（滚动在 {@link DeskPageView}）。
 *
 * <p>🔴 墨水屏铁律（`docs/03`）：纯黑白 + 三档灰，**无圆角 / 阴影 / 渐变 / 动画**；
 * 分区之间只用 1px 实线分隔。背景取色/网点半径与软锁**同款**（{@link BgImageUtil#drawCenterCrop}）。
 *
 * <p>🔴 与 {@link InsightRenderer}：本类**不动**其任何既有行为，只**复用**其静态工具
 * （{@code secHeadH} / {@code drawSectionHead} / {@code drawCenteredIn}）与 {@code Section} 契约。
 *
 * <p>🆕 TASK-076：分区**不再写死** —— 由 {@link DeskPageView} 按 {@link DeskModules} 注册表
 * （「顺序即渲染序、关掉的不出现」）逐帧装配进来；本类只负责"怎么排、怎么画"。
 */
final class DeskRenderer {

    // ── 尺（顶栏：标题衬线大字 / 返回 / 更新于；正文与洞察页同尺 SZ_BODY=15）──
    private static final float SZ_TITLE = 18f;   // ×unit ⇒ ≈21.6px @480×800（衬线「墨台」）
    private static final float SZ_BACK  = 14f;   // ×unit ⇒ ≈16.8px（`‹ 返回`）
    private static final float SZ_UPD   = 12f;   // ×unit ⇒ ≈14.4px（「更新于 HH:MM」）
    private static final float SZ_EMPTY = 15f;   // ×unit（空态）

    private static final float BAR_H_UNITS = 3.0f;   // 顶栏高 = SZ_TITLE × 本值 × unit

    /** 🆕 诉求 ④：齿轮与动作文字之间的横向间隙（×unit） */
    private static final float GEAR_GAP_UNITS = 5f;
    /** 🆕 诉求 ④：「更新于」与右端 `返回 ›` 之间的横向间隙（×unit） */
    private static final float BACK_GAP_UNITS = 8f;

    private static final int INK   = 0xFF000000;
    private static final int GRAY  = 0xFF3C3C3C;
    private static final int LIGHT = 0xFF9A9A9A;
    private static final int LINE  = 0xFFD8D8D8;

    /** 左右内边距比例 —— 与洞察页同尺（`InsightRenderer.PAD_X_RATIO`）。 */
    static final float PAD_X_RATIO = InsightRenderer.PAD_X_RATIO;

    private final List<InsightRenderer.Section> secs = new ArrayList<InsightRenderer.Section>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 空态文案（由 {@link DeskPageView} 从资源注入；无分区时居中显示）。 */
    private String emptyText = "";
    /** 顶栏文案（由 {@link DeskPageView} 从资源注入）。 */
    private String titleText = "";
    private String backText = "";
    /** 🆕 顶栏**右端的可点动作**（如「设置」）；空 = 不画、也不命中。 */
    private String actionText = "";

    void setEmptyText(String s) { emptyText = (s == null) ? "" : s; }

    /** 注入顶栏文案（标题 / 返回）。 */
    void setLabels(String title, String back) {
        titleText = (title == null) ? "" : title;
        backText = (back == null) ? "" : back;
    }

    /**
     * 🆕 注入顶栏右端动作（如「设置」）—— 与 `‹ 返回` 对称：**画与命中共用 {@link #actionBox}**
     * ⇒ 不会"看着能点、其实点不中"。
     */
    void setActionText(String s) { actionText = (s == null) ? "" : s; }

    // ── 分区注册（由 {@link DeskPageView} 按模块注册表装配）──

    void clear() { secs.clear(); }
    void add(InsightRenderer.Section s) { secs.add(s); }
    int count() { return secs.size(); }
    /** 第 i 个分区的标题（给自动化断言用）；越界 ⇒ 空串。 */
    String titleOf(int i) { return (i < 0 || i >= secs.size()) ? "" : secs.get(i).title(); }

    /** 分区实例的序号（按**引用**查；找不到 ⇒ -1）—— 手势分区用。 */
    int indexOf(InsightRenderer.Section s) {
        for (int i = 0; i < secs.size(); i++) if (secs.get(i) == s) return i;
        return -1;
    }

    /**
     * 第 {@code i} 个分区在**内容坐标系**里的顶边 y（含页眉，🔴 **不含滚动位移**）。
     *
     * <p>给「排行区内滚」的手势命中换算用（与 {@link InsightRenderer#sectionTop} 同口径）。
     */
    float sectionTop(int i, float w, float vh, float unit) {
        float y = headerHeight(unit);
        for (int k = 0; k < i && k < secs.size(); k++) y += secs.get(k).height(w, vh, unit);
        return y;
    }

    // ── 量高 ──

    /** 顶栏高（含底部分隔线）。 */
    float headerHeight(float unit) { return SZ_TITLE * unit * BAR_H_UNITS; }

    /** 空态块高（无任何分区时占位）。 */
    private float emptyHeight(float unit) { return SZ_EMPTY * unit * 6f; }

    /** 全部内容总高（含顶栏）。容器据此决定「能不能滚」。 */
    float contentHeight(float w, float vh, float unit) {
        float y = headerHeight(unit);
        if (secs.isEmpty()) return y + emptyHeight(unit);
        for (int i = 0; i < secs.size(); i++) y += secs.get(i).height(w, vh, unit);
        return y;
    }

    // ── 画一帧 ──

    /**
     * @param scrollY  已滚过的像素（0 = 顶部）；容器负责先钳进 [0, contentHeight − vh]
     * @param bg       背景位图（null ⇒ 浅色底 + 细网点）
     * @param veilPct  全屏白纱不透明度（%，0~100）
     * @param updated  「更新于」右侧文案（如 `14:32`）；null/空 ⇒ 不画
     */
    void draw(Canvas c, float w, float vh, float unit, float scrollY,
              Bitmap bg, int veilPct, String updated) {
        drawChrome(c, w, vh, unit, bg, veilPct, updated);

        float left = w * PAD_X_RATIO, right = w - left;
        float barH = headerHeight(unit);
        float y = barH - scrollY;

        // 🔴🔴 2026-10-10 修（用户报告）：顶栏**不透明**、而分区是从 `barH − scrollY` 起画的，
        //    内容上滑时会**压到顶栏文字上**（标题「墨台」/「更新于」与正文叠字）。
        //    修法 = 把内容**裁剪**到顶栏分隔线以下 —— 顶栏自身照旧显示背景图 + 白纱，
        //    只是正文再也不会越界进来（等价于"吸顶"）。空态同样落在裁区内。
        c.save();
        c.clipRect(0f, barH, w, vh);

        if (secs.isEmpty()) {
            InsightRenderer.drawCenteredIn(c, w, y, emptyHeight(unit), unit, p, emptyText);
            c.restore();
            return;
        }

        for (int i = 0; i < secs.size(); i++) {
            InsightRenderer.Section s = secs.get(i);
            float sh = s.height(w, vh, unit);
            if (y + sh > barH && y < vh) s.draw(c, w, vh, y, unit, p);
            y += sh;
            // 分区之间的 1px 分隔线（只在"线真的在屏上、且在裁区内"时画 —— 同 InsightRenderer 纪律）
            if (y > barH && y < vh) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(LINE);
                c.drawRect(left, y - 1f, right, y, p);
            }
        }
        c.restore();
        p.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * 只画**背景 + 顶栏**（不画任何分区）。
     *
     * <p>🆕 2026-10-10：墨台的「设置态 / 壁纸预览态」由各自的自绘件负责正文，但**背景与顶栏**
     * 必须与列表态同一套 chrome（不然进出设置会"背景/顶栏跳一下"）⇒ 抽成这个方法，
     * 与 {@link #draw} 共用同一份实现（`draw` 现在是"chrome + 分区"）。
     */
    void drawChrome(Canvas c, float w, float vh, float unit, Bitmap bg, int veilPct, String updated) {
        drawBackground(c, w, vh, bg, veilPct);
        drawHeader(c, w, unit, updated);
    }

    /**
     * 背景：自选图 `centerCrop` 铺满 + 全屏白纱（默认 90% 白）；未设图 ⇒ 浅色底 + 细网点。
     * 🔴 与软锁同款（网点 `step = h × 0.03`、半径 1.2px；白纱为**全屏**而非锁屏那种面板块）。
     */
    private void drawBackground(Canvas c, float w, float h, Bitmap bg, int veilPct) {
        if (bg == null) {
            c.drawColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFECECEC);
            float step = h * 0.03f;
            for (float yy = step; yy < h; yy += step) {
                for (float xx = step; xx < w; xx += step) {
                    c.drawCircle(xx, yy, 1.2f, p);
                }
            }
            return;
        }
        BgImageUtil.drawCenterCrop(c, bg, w, h, p);
        int a = veilPct * 255 / 100;
        p.setStyle(Paint.Style.FILL);
        p.setColor((a << 24) | 0xFFFFFF);
        c.drawRect(0f, 0f, w, h, p);
    }

    /**
     * 顶栏：左端动作簇〔可选：齿轮 + 动作文字（如「设置」）〕+ 衬线标题（居中）
     * + 右端簇〔「更新于 HH:MM」+ `返回 ›`〕+ 底部 1px 线。
     *
     * <p>🆕 2026-10-10 第二轮（用户诉求 ④）：
     * <ul>
     *   <li><b>两键互换位次</b>：「设置」由右端搬到**左端**，「返回」由左端搬到**右端** ——
     *       返回箭头随之**翻转**（`‹ 返回` ⇒ `返回 ›`，文案在 `strings.xml` 里改，本类只管画）；</li>
     *   <li><b>设置键去框</b>：不再画 1px 直角描边（那是 `btn_ink` 的按钮语言），改「齿轮符号 + 加粗文字」——
     *       与「返回」同属"顶栏文字键"，不再像颗按钮；</li>
     *   <li>🔴 齿轮**自己画**（{@link #drawGear}），不用字符 `⚙` —— 本项目铁律：
     *       墨水屏本机字体不一定带这些码位（`CardRenderer` 的 ⟳ / ⇄ 同理，一律 Path / 圆弧画出来）。</li>
     * </ul>
     */
    private void drawHeader(Canvas c, float w, float unit, String updated) {
        float barH = headerHeight(unit);
        float cy = barH * 0.5f;
        float pad = w * PAD_X_RATIO;

        // 左端：可选动作（`⚙ 设置`）—— 无描边框 + 文字加粗（用户诉求 ④）
        boolean hasAction = actionText.length() > 0;
        if (hasAction) {
            RectF box = actionBox(w, unit);                 // 画与命中共用同一份几何
            float icon = gearSize(unit);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(null);
            p.setFakeBoldText(true);
            p.setColor(INK);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(SZ_BACK * unit);
            drawGear(c, box.left + icon / 2f, cy, icon, INK);
            c.drawText(actionText, box.left + icon + unit * GEAR_GAP_UNITS, baseline(cy), p);
            p.setFakeBoldText(false);
        }

        // 中：衬线标题（墨色）
        p.setTypeface(InkTheme.serif());
        p.setColor(INK);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(SZ_TITLE * unit);
        c.drawText(titleText, w / 2f, baseline(cy), p);
        p.setTypeface(null);

        // 右端簇：先量 `返回 ›`（它定"更新于"的右边界）
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(null);
        p.setFakeBoldText(false);
        p.setTextSize(SZ_BACK * unit);
        float backW = p.measureText(backText);

        p.setColor(GRAY);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(backText, w - pad, baseline(cy), p);

        // 「更新于 HH:MM」在「返回」左侧
        if (updated != null && updated.length() > 0) {
            p.setColor(LIGHT);
            p.setTextSize(SZ_UPD * unit);
            c.drawText(updated, w - pad - backW - unit * BACK_GAP_UNITS, baseline(cy), p);
        }
        p.setTextAlign(Paint.Align.LEFT);

        // 底部分隔线
        p.setStyle(Paint.Style.FILL);
        p.setColor(LINE);
        c.drawRect(0f, barH - 1f, w, barH, p);
    }

    /**
     * 顶栏「齿轮」图标（用户诉求 ④「设置键前面加一个齿轮符号」）。
     *
     * <p>🔴 <b>画出来而不是打字符</b>：本机字体不一定带 `⚙`(U+2699)，缺字形就是豆腐块；
     * 且字符版的字重 / 基线在 `setFakeBoldText` 下不可控。本类与 {@code CardRenderer} 的
     * ⟳ / ⇄ 同一条纪律 —— **图标一律 Path / 圆弧自绘**。
     *
     * <p>形状：内环（描边圆）+ 8 根短齿（自 `0.62R` 到 `0.98R` 的径向短线）。
     * 尺寸只给 ≈16px（= `SZ_BACK × unit`），细节再多也会糊成一团 ⇒ 到"看得出是个齿轮"为止。
     *
     * @param cx/cy 圆心（与同行文字的纵向中线同高）
     * @param size  外接正方形边长
     */
    private void drawGear(Canvas c, float cx, float cy, float size, int color) {
        float r = size * 0.5f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.4f, size * 0.115f));
        p.setColor(color);
        c.drawCircle(cx, cy, r * 0.60f, p);
        float r0 = r * 0.62f, r1 = r * 0.98f;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2.0 * i / 8.0;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * r1, cy + sa * r1, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** 齿轮边长（= 顶栏返回字号 × 本值）—— 与 {@link #actionBox} 共用，画与命中不许各算一份。 */
    private float gearSize(float unit) { return SZ_BACK * unit * 0.98f; }

    /** 让文字基线落在纵向中线（`cy`）上。 */
    private float baseline(float cy) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return cy - (fm.ascent + fm.descent) / 2f;
    }

    /**
     * 触点是否命中顶栏**右端**的 `返回 ›`（触摸区的唯一权威定义 —— 与 {@link #drawHeader} 同尺）。
     * 🔴 只圈住"那一小段文字 + 一点余量"，不吞整条顶栏（免得点标题也返回）。
     * 🆕 诉求 ④：返回键由左端搬到右端 ⇒ 命中区改按**右对齐**算（`w − pad − 文字宽`）。
     */
    boolean hitBack(float x, float y, float w, float unit) {
        float barH = headerHeight(unit);
        if (y < 0f || y > barH) return false;
        p.setTextSize(SZ_BACK * unit);
        float tw = p.measureText(backText);
        float pad = w * PAD_X_RATIO;
        float m = 8f * unit;
        return x >= w - pad - tw - m && x <= w - pad + m;
    }

    /**
     * 触点是否命中顶栏**左端**的 `⚙ 设置`（触摸区的唯一权威定义 —— 与 {@link #drawHeader} 同尺）。
     * 🔴 只圈住"齿轮 + 文字 + 一点余量"，不吞整条顶栏（免得点标题也进设置）。
     */
    boolean hitAction(float x, float y, float w, float unit) {
        if (actionText.length() == 0) return false;
        float barH = headerHeight(unit);
        if (y < 0f || y > barH) return false;
        RectF r = actionBox(w, unit);
        float m = unit * 6f;
        return x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m;
    }

    /**
     * 顶栏**左端**动作键的矩形（**画与命中共用这一份**）。
     * 🔴 触摸区在 {@link #hitAction} 里再放宽 `unit × 6`（文字本身矮，手指点不准）。
     * 🆕 诉求 ④：改**左对齐**且**去掉描边框**后，宽度 = 齿轮 + 间隙 + 文字（不再留按钮内衬）。
     */
    private RectF actionBox(float w, float unit) {
        p.setTextSize(SZ_BACK * unit);
        float tw = p.measureText(actionText);
        float bw = gearSize(unit) + unit * GEAR_GAP_UNITS + tw;
        float bh = SZ_BACK * unit * 1.80f;
        float left = w * PAD_X_RATIO;
        float cy = headerHeight(unit) * 0.5f;
        return new RectF(left, cy - bh / 2f, left + bw, cy + bh / 2f);
    }
}
