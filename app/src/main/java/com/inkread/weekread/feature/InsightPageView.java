package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 🆕 TASK-048（K3）：**洞察页容器** —— 纯 `Canvas` 自绘 + 单指纵向拖动滚动。
 *
 * 为什么自绘而不是用 `ScrollView`（卡面设计要点 1 给了两个选项，取"新写独立类"）：
 * · 卡片区是 `WeekCardView`（自绘），整页塞一个 `ScrollView` 会出现两套滚动语义；
 * · 墨水屏**没有惯性和回弹动画**（`docs/03` 无动画铁律）——`ScrollView` 的 fling/edge-effect
 *   反而要一个个关掉，不如自己管一个 `scrollY` 干净；
 * · 后序卡（K8 排行）要做**分区内独立滚动**，外层自己控制才好做「谁吃这次手势」的判定。
 *
 * 数据与排版全在 {@link InsightRenderer} 里 ⇒ 本类只管三件事：
 * **量总高 → 钳 scrollY → 把事件/画布转交**。
 *
 * 🔴 **内容装得下就不吃手势**（卡面设计要点 3：不出现"空滚"）：
 * `maxScroll == 0` 时 {@link #onTouchEvent} 直接返回 `false`，事件继续往上传。
 */
public final class InsightPageView extends View {

    /** 与 {@link WeekCardView#UNIT_RATIO} 同尺 —— 两个页面字号必须一致，否则视觉上"换页就变了"。 */
    private static final float UNIT_RATIO = 0.0015f;

    /** 排行区（K8）预留高度 = 视口高的 42%（卡面要求 40~45%，取中位）。 */
    private static final float RANK_VIEWPORT_RATIO = 0.42f;

    private float unit;

    private final InsightRenderer renderer = new InsightRenderer();

    /** 滚动位移（像素，0 = 顶部）。 */
    private float scrollY = 0f;
    /** 当前可滚上限（= 内容总高 − 视口高，≥0）。容器两次计算：尺寸变化时 + 每帧自纠。 */
    private float maxScroll = 0f;

    private float lastTouchY = 0f;
    private boolean dragging = false;

    public InsightPageView(Context c) { this(c, null); }

    public InsightPageView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFFFFFFFF);                 // 墨水屏：白底，不做任何背景装饰
        unit = c.getResources().getDisplayMetrics().heightPixels * UNIT_RATIO;
        rebuildSections();
    }

    // ══════════════════════ 分区注册（后序卡的唯一改动点） ══════════════════════

    /**
     * 注册洞察页的 5 个分区。
     *
     * 🔴 **本卡全部是占位**（只画空态文案）。K4~K8/K10 落码时：
     * 把自己那个 `placeholderXxx(...)` 换成一个真实 `Section` 实现即可，
     * **不要动本类的滚动/量高逻辑**。
     *
     * 分区顺序（与卡面 ASCII 图一致，且 = 下拉里「洞察页」的阅读顺序）：
     * 摘要 → 年度 → 累计 → 排行 → 画像。
     */
    private void rebuildSections() {
        renderer.clear();
        // ① 摘要（TASK-047 已实现渲染，但那是"周/月全屏页"的底部行；洞察页这一格待后序卡接）
        renderer.add(InsightRenderer.placeholderLines("阅读摘要", "暂无阅读摘要", 1f));
        // ② 年度（→ TASK-051 K6）
        renderer.add(InsightRenderer.placeholderLines("年度视图", "暂无年度数据", 2f));
        // ③ 累计（→ TASK-052 K7）
        renderer.add(InsightRenderer.placeholderLines("累计视图", "暂无累计数据", 2f));
        // ④ 排行（→ TASK-053 K8）── 🔴 高度按视口百分比预留（40~45%），避免后序卡返工（卡面 R3）
        renderer.add(InsightRenderer.placeholderRatio("读书排行", "暂无阅读排行", RANK_VIEWPORT_RATIO));
        // ⑤ 画像（→ TASK-049 K4 / TASK-050 K5 / TASK-055 K10）
        renderer.add(InsightRenderer.placeholderLines("阅读画像", "暂无阅读画像", 2f));
    }

    // ══════════════════════ 量高 / 钳位 ══════════════════════

    private void remeasure(int w, int h) {
        if (w <= 0 || h <= 0) return;
        maxScroll = renderer.contentHeight(w, h, unit) - h;
        if (maxScroll < 0f) maxScroll = 0f;
        if (scrollY > maxScroll) scrollY = maxScroll;
        if (scrollY < 0f) scrollY = 0f;
    }

    /** 内容总高（像素）—— 给自动化断言 / 调试用。 */
    public float contentHeightPx() { return renderer.contentHeight(getWidth(), getHeight(), unit); }

    /** 分区数（验收 A2 断言「= 5」）。 */
    public int sectionCount() { return renderer.count(); }

    /** 第 i 个分区的标题（验收 A2 断言标题文案）。 */
    public String sectionTitle(int i) { return renderer.titleOf(i); }

    /** 切换回洞察页时把位置复位（避免"上次滚到一半"的错位观感）。 */
    public void resetScroll() {
        if (scrollY == 0f) return;
        scrollY = 0f;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        remeasure(w, h);
    }

    // ══════════════════════ 绘制 ══════════════════════

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        remeasure(w, h);                                 // 每帧自纠：分区高可能随字号/文案变化
        renderer.draw(c, w, h, unit, scrollY);
    }

    // ══════════════════════ 触控：单指纵向拖动 ══════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // 🔴 装得下就不吃手势（不出现空滚）。也让上层（如有）能拿到这次触摸。
        if (maxScroll <= 0f) return false;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchY = e.getY();
                dragging = true;
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return true;
                float dy = e.getY() - lastTouchY;
                lastTouchY = e.getY();
                // 手指上滑（dy < 0）⇒ 内容上移 ⇒ scrollY 增大
                float next = scrollY - dy;
                if (next < 0f) next = 0f;
                if (next > maxScroll) next = maxScroll;
                if (next != scrollY) {
                    scrollY = next;
                    invalidate();                        // 🔴 一帧一次重绘（墨水屏：不做局部/多次失效）
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }
}
