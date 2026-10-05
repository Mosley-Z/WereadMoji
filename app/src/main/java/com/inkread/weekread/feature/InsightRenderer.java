package com.inkread.weekread.feature;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/**
 * 🆕 TASK-048（K3）：洞察页的**分区渲染器** —— 只管「怎么排、怎么画」，**不管滚动**（滚动在
 * {@link InsightPageView}）。
 *
 * 结构 = 一小段上边距 + 若干 {@link Section}，自上而下依次排布。
 * 🔴 **没有大标题**（用户 2026-10-06 当场要求删掉「阅读洞察」—— 下拉里已显示「洞察▽」，重复且占高）。
 * 本卡只注册 5 个**占位分区**（各自带一句专属空态文案）；K4~K8/K10 各自新增一个 {@code Section}
 * 实现、在 {@link InsightPageView#rebuildSections()} 里把对应的占位行换掉即可 ——
 * **容器与滚动逻辑零改动**（分区边界是稳定的扩展点）。
 *
 * 🔴 墨水屏铁律（`docs/03`）：纯黑白 + 三档灰（INK / GRAY / LIGHT），**无动画、无阴影、无渐变**。
 *    分区之间只用 1px 实线分隔；不做卡片投影、不做圆角。
 *
 * 🔴 为什么要「视口裁剪」（{@link #draw} 里那段 if）：墨水屏一次全刷很贵，
 *    把完全滚出屏幕的分区直接跳过，能明显缩小重绘面积。
 */
final class InsightRenderer {

    /**
     * 一个分区：标题 + 自量高度 + 自绘内容。
     *
     * @param vh 视口高（像素）—— 排行区这类「按屏高百分比占位」的分区要用它定高
     * @param top 该分区顶边的 y（**已含滚动位移**，可为负 = 有一部分在屏幕上方）
     */
    interface Section {
        /** 分区标题（画在分区左上）。 */
        String title();

        /** 该分区占的纵向高度（含标题行与下边距）。 */
        float height(float w, float vh, float unit);

        /** 从 {@code top} 起画。 */
        void draw(Canvas c, float w, float vh, float top, float unit, Paint p);
    }

    // ── 尺（与 CardRenderer 的同名字号对齐，保证两页目视一致）──
    private static final float SZ_SEC   = 17f;   // 分区标题（加粗）
    private static final float SZ_BODY  = 15f;   // 分区正文 / 空态

    /**
     * 抬头区高 —— 🔴 **只留一小段上边距**。
     *
     * 原来这里是一行大标题「阅读洞察」（`SZ_TITLE=19` 加粗）+ 一条 1px 分隔线，共 `19×unit×3.2 ≈ 73px`。
     * 用户 2026-10-06 当场要求**去掉大标题**（下拉里已经写着「洞察▽」，再顶一行大字纯属重复占高），
     * 所以整个抬头块缩成这一段留白。
     */
    private static final float HEAD_PAD_UNITS = 12f;   // ×unit ⇒ ≈14px @480×800

    private static final int INK   = 0xFF000000;
    private static final int GRAY  = 0xFF3C3C3C;
    private static final int LIGHT = 0xFFA8A8A8;

    /** 左右内边距比例 —— 与主页阅读区同尺（`WeekCardView.padXRatio` 默认 0.05）。 */
    static final float PAD_X_RATIO = 0.05f;

    private final List<Section> secs = new ArrayList<Section>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── 分区注册（给 InsightPageView 用）──

    void clear() { secs.clear(); }
    void add(Section s) { secs.add(s); }
    /** 分区数 —— 验收项 A2 要断言「5 个」。 */
    int count() { return secs.size(); }
    /** 第 i 个分区的标题（给自动化断言用）。 */
    String titleOf(int i) { return secs.get(i).title(); }

    // ── 量高 ──

    /** 抬头区高（只有一小段上边距 —— 大标题已删，见 {@link #HEAD_PAD_UNITS}）。 */
    float headerHeight(float unit) { return HEAD_PAD_UNITS * unit; }

    /** 全部内容总高（含抬头）。容器据此决定「能不能滚」。 */
    float contentHeight(float w, float vh, float unit) {
        float y = headerHeight(unit);
        for (int i = 0; i < secs.size(); i++) y += secs.get(i).height(w, vh, unit);
        return y;
    }

    // ── 画一帧 ──

    /**
     * @param scrollY 已滚过的像素（0 = 顶部）。容器负责先钳进 [0, contentHeight − vh]。
     */
    void draw(Canvas c, float w, float vh, float unit, float scrollY) {
        float pad = w * PAD_X_RATIO;
        float left = pad, right = w - pad;
        float y = -scrollY;

        // ── 抬头：**只剩留白**（大标题「阅读洞察」+ 抬头分隔线已按用户要求删除）──
        y += headerHeight(unit);

        // ── 各分区（视口外的直接跳过）──
        for (int i = 0; i < secs.size(); i++) {
            Section s = secs.get(i);
            float sh = s.height(w, vh, unit);
            if (y + sh > 0f && y < vh) s.draw(c, w, vh, y, unit, p);
            y += sh;

            // 分区之间的分隔线：只有「这条线真的在屏上」才画
            if (y - scrollY > 0f && y - scrollY < vh) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(LIGHT);
                c.drawRect(left, y - 1f, right, y, p);
            }
        }

        p.setTextAlign(Paint.Align.LEFT);
    }

    // ── 分区绘制的小工具（各 Section 实现共用）──

    /** 分区标题行高。 */
    static float secHeadH(float unit) { return SZ_SEC * unit * 2.2f; }

    /**
     * 画分区标题（左对齐加粗 INK），返回正文起始 y。
     * 所有 Section 都从它起手 ⇒ 标题的字号/基线在 5 个分区之间天然一致。
     */
    static float drawSectionHead(Canvas c, float w, float top, float unit, Paint p, String title) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(INK);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(SZ_SEC * unit);
        c.drawText(title, w * PAD_X_RATIO, top + SZ_SEC * unit * 1.5f, p);
        p.setFakeBoldText(false);
        return top + secHeadH(unit);
    }

    /** 在 [top, top + boxH] 这个盒子里画一句居中的灰字（空态 / 占位文案）。 */
    static void drawCenteredIn(Canvas c, float w, float top, float boxH, float unit, Paint p, String text) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(GRAY);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(false);
        float size = SZ_BODY * unit;
        p.setTextSize(size);
        // 盒子高度都装不下时按比例缩字（和卡片页的底部统计行同一套兜底思路）
        float maxW = w * (1f - 2f * PAD_X_RATIO);
        while (p.measureText(text) > maxW && size > 11f * unit) {
            size -= 0.4f;
            p.setTextSize(size);
        }
        c.drawText(text, w / 2f, top + boxH * 0.5f + size * 0.36f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ── 占位分区工厂（本卡用；后序卡用真实 Section 顶替）──

    /** 占位分区：正文高度 = `lines` 行（按正文字号 ×1.9 行距算）。 */
    static Section placeholderLines(final String title, final String empty, final float lines) {
        return new Section() {
            public String title() { return title; }
            public float height(float w, float vh, float unit) {
                return secHeadH(unit) + SZ_BODY * unit * 1.9f * lines;
            }
            public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
                float bodyTop = drawSectionHead(c, w, top, unit, p, title);
                drawCenteredIn(c, w, bodyTop, SZ_BODY * unit * 1.9f * lines, unit, p, empty);
            }
        };
    }

    /**
     * 占位分区：正文高度 = **视口高的 `ratio` 倍**。
     * 排行区（K8/`TASK-053`）要按「40~45% 屏高」预留，就是走这条 ——
     * 让它的高度**只由屏高决定**，不随后序卡改文案而漂移。
     */
    static Section placeholderRatio(final String title, final String empty, final float ratio) {
        return new Section() {
            public String title() { return title; }
            public float height(float w, float vh, float unit) {
                return secHeadH(unit) + vh * ratio;
            }
            public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
                float bodyTop = drawSectionHead(c, w, top, unit, p, title);
                drawCenteredIn(c, w, bodyTop, vh * ratio, unit, p, empty);
            }
        };
    }
}
