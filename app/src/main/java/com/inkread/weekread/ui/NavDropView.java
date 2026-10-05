package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * 阅读页顶部的「界面下拉选择器」（TASK-044 / V1.2.0-beta）。
 *
 * <p>职责单一：**收起态**是一行（左对齐显示当前界面名 + 右侧 {@code ▽}）；
 * **点击后原地向下展开**一个 5 行浮层（本周 / 本月 / 本书 / 本记 / 洞察），
 * 选中即收起并回调 {@link Listener#onPicked(int)}；点浮层/扳机行以外任意处收起（不改选择）。
 *
 * <p>🔴 **为什么把展开态做成 {@code MATCH_PARENT} 的透明叠加层**：
 * 下拉要求「覆盖下方内容、**不推挤**」—— 若把本控件当普通行撑高，会把 picker / 卡片顶下去。
 * 所以展开时把自身高度改成铺满父容器（{@code page_reader} 的 {@code FrameLayout}），
 * <b>只在顶部画白底</b>（扳机行 + 列表），余下区域透明但**照常吃触摸**
 * ⇒ 天然实现「点浮层外收起」，且完全不参与下方视图的布局。
 *
 * <p>🔴 **墨水屏适配**：无动画、无阴影、无渐变（不用 {@code elevation}/{@code shadow}）；
 * 列表用不透明白底 + 1px 黑边框；当前项用**实心圆点**标记（点阵表状态，与全项目一致）；
 * 一次 {@link #invalidate()} 重绘完成，无逐帧刷新。
 *
 * <p>⚠️ 本控件**不做深色**：深色只在手机端（{@code install_role=phone}）启用，
 * 而手机端**不走本导航**（见 TASK-044 §非目标）⇒ 默认亮色即可，行为与其余 App 内控件一致。
 */
public class NavDropView extends View {

    /** 选中回调（index 从 0 起，与 {@link #setLabels} 顺序一致）。 */
    public interface Listener {
        void onPicked(int index);
    }

    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF9A9A9A;
    private static final int LINE = 0xFFD8D8D8;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 1 号 = 屏幕高度 * 0.0015（与全局字号口径一致，见 {@code SegTabView}）。 */
    private final float unit;
    private final float density;

    /** 扳机行高（40dp）与每个选项行高（36dp）—— 换算成 px 后固定。 */
    private final float headerH;
    private final float rowH;

    /** 五个可选界面；默认对齐 {@link com.inkread.weekread.core.PeriodRange} 的取数形态。 */
    private String[] labels = {"本周", "本月", "本书", "本记", "洞察"};
    private int selected = 0;
    /** 是否处于「展开」态（展开时自身高度 = MATCH_PARENT，只画顶部）。 */
    private boolean expanded = false;
    /**
     * 🆕 TASK-044-R1：**无扳机行模式** —— 扳机行搬到大标签① 本体上（显示成「本周▽」），
     * 本控件退化为「只在展开时存在的浮层」：收起态高度 = 0（既不占位也不绘制），
     * 展开只能由外部 {@link #expand()} / {@link #toggle()} 触发。
     */
    private boolean triggerless = false;
    private Listener listener;

    public NavDropView(Context c) { this(c, null); }

    public NavDropView(Context c, AttributeSet a) {
        super(c, a);
        unit = c.getResources().getDisplayMetrics().heightPixels * 0.0015f;
        density = c.getResources().getDisplayMetrics().density;
        headerH = 40f * density;
        rowH = 36f * density;
    }

    /** 设置标签（在 Activity 里用 {@code getString} 装配，便于统一改文案）。 */
    public void setLabels(String[] l) {
        if (l != null && l.length > 0) {
            labels = l;
            if (selected > labels.length - 1) selected = labels.length - 1;
            invalidate();
        }
    }

    public void setListener(Listener l) { listener = l; }

    public int getSelected() { return selected; }

    /** 外部同步当前项（切形态时由 Activity 调）—— 会顺带收起（若正展开）。 */
    public void setSelected(int i) {
        if (i < 0) i = 0;
        if (i > labels.length - 1) i = labels.length - 1;
        boolean changed = (i != selected);
        selected = i;
        if (expanded) setExpanded(false);
        if (changed) invalidate();
    }

    public boolean isExpanded() { return expanded; }

    /** 收起（若未展开则无事发生）—— 切大标签离开阅读页时由 Activity 调。 */
    public void collapse() { setExpanded(false); }

    /**
     * 展开 / 收起：改自身 LayoutParams 高度（收起 40dp / 展开 MATCH_PARENT）后重绘。
     * 🔴 只改自己的高度，父容器是 {@code FrameLayout} ⇒ **不影响任何兄弟视图的布局**。
     *
     * <p>🆕 TASK-044-R1：{@link #triggerless} 模式下收起态高度改为 <b>0</b>。
     */
    private void setExpanded(boolean on) {
        if (expanded == on) return;
        expanded = on;
        applyHeight();
        invalidate();
    }

    /** 按「当前展开态 + 是否 triggerless」重设自身高度 —— **唯一的高度写入口**。 */
    private void applyHeight() {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) return;
        lp.height = expanded ? ViewGroup.LayoutParams.MATCH_PARENT
                : (triggerless ? 0 : (int) headerH);
        setLayoutParams(lp);
    }

    /**
     * 🆕 TASK-044-R1：无扳机行模式开关（默认 false ⇒ 与改造前逐像素一致）。
     *
     * <p>开启后：收起态高度 = 0（不占位、不绘制扳机行），展开态列表从 {@code y=0} 直接列 5 项；
     * 展开/收起只能由外部（大标签① 本体）调 {@link #expand()} / {@link #toggle()}。
     */
    public void setTriggerless(boolean on) {
        if (triggerless == on) return;
        triggerless = on;
        if (on) expanded = false;      // 进入无扳机模式 ⇒ 必然收起（绕过 setExpanded 的同值早退）
        applyHeight();
        invalidate();
    }

    public boolean isTriggerless() { return triggerless; }

    /** 🆕 TASK-044-R1：外部展开（大标签① 本体被点击时调）。 */
    public void expand() { setExpanded(true); }

    /** 🆕 TASK-044-R1：展开 ⇄ 收起；返回切换后的展开状态。 */
    public boolean toggle() {
        setExpanded(!expanded);
        return expanded;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float size = 16f * unit;
        // 🔴 TASK-044-R1：triggerless 时没有扳机行 ⇒ 列表从 y=0 开始（否则从 headerH 开始）。
        final float listTop = triggerless ? 0f : headerH;

        if (!expanded) {
            if (triggerless) return;              // 无扳机行 ⇒ 收起态什么都不画（高度也是 0）
            // 收起态：整行白底 + 扳机行内容（自身高度恰好 40dp）
            c.drawColor(0xFFFFFFFF);
            drawHeader(c, w, size, false);
            return;
        }

        // 展开态：**只把顶部白底画出来**，下方保持透明（父容器/卡片照常可见）
        final float listBottom = listTop + rowH * labels.length;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawRect(0f, 0f, w, listBottom, p);

        if (!triggerless) drawHeader(c, w, size, true);   // triggerless 时扳机行不在本控件上

        // 5 个选项行
        for (int i = 0; i < labels.length; i++) {
            float top = listTop + i * rowH;
            if (i > 0) {                       // 行间细线
                p.setStyle(Paint.Style.FILL);
                p.setColor(LINE);
                c.drawRect(0f, top, w, top + 1f, p);
            }
            p.setTextSize(size);
            p.setTextAlign(Paint.Align.LEFT);
            p.setFakeBoldText(i == selected);
            p.setColor(INK);
            Paint.FontMetrics fm = p.getFontMetrics();
            float baseline = top + (rowH - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            c.drawText(labels[i], 16f * density, baseline, p);

            if (i == selected) {               // 当前项：右侧实心圆点
                p.setStyle(Paint.Style.FILL);
                p.setColor(INK);
                c.drawCircle(w - 24f * density, top + rowH / 2f, 4f * density, p);
            }
        }
        p.setFakeBoldText(false);

        // 整个下拉区的 1px 黑边框（不透明白底之上；不用 elevation/shadow）
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(INK);
        c.drawRect(0.5f, 0.5f, w - 0.5f, listBottom - 0.5f, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** 画顶部的「扳机行」（当前项 + ▽/△ + 底部分隔线）。 */
    private void drawHeader(Canvas c, float w, float size, boolean open) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawRect(0f, 0f, w, headerH, p);

        p.setTextSize(size);
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(false);
        p.setColor(INK);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = (headerH - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        c.drawText(labels[selected], 16f * density, baseline, p);

        // 右侧指示：收起 ▽ / 展开 △
        p.setTextAlign(Paint.Align.RIGHT);
        p.setColor(GRAY);
        c.drawText(open ? "△" : "▽", w - 16f * density, baseline, p);
        p.setTextAlign(Paint.Align.LEFT);

        // 底部分隔灰线
        p.setStyle(Paint.Style.FILL);
        p.setColor(LINE);
        c.drawRect(0f, headerH - 1f, w, headerH, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;   // 只认抬起（墨水屏无长按/滑动需求）
        final float y = e.getY();

        if (!expanded) {
            if (triggerless) return false;         // 高 0 ⇒ 本不该收到触摸；兜底放行
            setExpanded(true);                    // 收起态：点一下原地展开
            return true;
        }

        final float listTop = triggerless ? 0f : headerH;
        final float listBottom = listTop + rowH * labels.length;
        if (y >= listTop && y < listBottom) {     // 命中某个选项 ⇒ 选中并收起
            int idx = (int) ((y - listTop) / rowH);
            if (idx < 0) idx = 0;
            if (idx > labels.length - 1) idx = labels.length - 1;
            selected = idx;
            setExpanded(false);
            if (listener != null) listener.onPicked(idx);
            return true;
        }
        // 扳机行 / 列表区以外 ⇒ 仅收起，不改选择
        setExpanded(false);
        return true;
    }
}
