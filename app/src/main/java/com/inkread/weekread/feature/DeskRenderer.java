package com.inkread.weekread.feature;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

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

    void setEmptyText(String s) { emptyText = (s == null) ? "" : s; }

    /** 注入顶栏文案（标题 / 返回）。 */
    void setLabels(String title, String back) {
        titleText = (title == null) ? "" : title;
        backText = (back == null) ? "" : back;
    }

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
        drawBackground(c, w, vh, bg, veilPct);
        drawHeader(c, w, unit, updated);

        float left = w * PAD_X_RATIO, right = w - left;
        float y = headerHeight(unit) - scrollY;

        if (secs.isEmpty()) {
            InsightRenderer.drawCenteredIn(c, w, y, emptyHeight(unit), unit, p, emptyText);
            return;
        }

        for (int i = 0; i < secs.size(); i++) {
            InsightRenderer.Section s = secs.get(i);
            float sh = s.height(w, vh, unit);
            if (y + sh > 0f && y < vh) s.draw(c, w, vh, y, unit, p);
            y += sh;
            // 分区之间的 1px 分隔线（只在"线真的在屏上"时画 —— 同 InsightRenderer 纪律）
            if (y > 0f && y < vh) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(LINE);
                c.drawRect(left, y - 1f, right, y, p);
            }
        }
        p.setTextAlign(Paint.Align.LEFT);
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

    /** 顶栏：`‹ 返回`（左）+ 衬线「墨台」（居中）+ 「更新于 HH:MM」（右）+ 底部 1px 线。 */
    private void drawHeader(Canvas c, float w, float unit, String updated) {
        float barH = headerHeight(unit);
        float cy = barH * 0.5f;
        float pad = w * PAD_X_RATIO;

        // 左：`‹ 返回`
        p.setStyle(Paint.Style.FILL);
        p.setColor(GRAY);
        p.setFakeBoldText(false);
        p.setTypeface(null);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(SZ_BACK * unit);
        c.drawText(backText, pad, baseline(cy), p);

        // 中：衬线「墨台」（墨色）
        p.setTypeface(InkTheme.serif());
        p.setColor(INK);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(SZ_TITLE * unit);
        c.drawText(titleText, w / 2f, baseline(cy), p);
        p.setTypeface(null);

        // 右：「更新于 HH:MM」
        if (updated != null && updated.length() > 0) {
            p.setColor(LIGHT);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(SZ_UPD * unit);
            c.drawText(updated, w - pad, baseline(cy), p);
        }
        p.setTextAlign(Paint.Align.LEFT);

        // 底部分隔线
        p.setStyle(Paint.Style.FILL);
        p.setColor(LINE);
        c.drawRect(0f, barH - 1f, w, barH, p);
    }

    /** 让文字基线落在纵向中线（`cy`）上。 */
    private float baseline(float cy) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return cy - (fm.ascent + fm.descent) / 2f;
    }

    /**
     * 触点是否命中顶栏的 `‹ 返回`（触摸区的唯一权威定义 —— 与 {@link #drawHeader} 同尺）。
     * 🔴 只圈住"那一小段文字 + 一点余量"，不吞整条顶栏（免得点标题也返回）。
     */
    boolean hitBack(float x, float y, float w, float unit) {
        float barH = headerHeight(unit);
        if (y < 0f || y > barH) return false;
        p.setTextSize(SZ_BACK * unit);
        float tw = p.measureText(backText);
        float pad = w * PAD_X_RATIO;
        float m = 8f * unit;
        return x >= pad - m && x <= pad + tw + m;
    }
}
