package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.inkread.weekread.R;
import com.inkread.weekread.core.BgImageUtil;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.PagePrefs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 🆕 TASK-075：**墨台**的内容容器 —— 纯 `Canvas` 自绘 + 单指纵向拖动滚动（照 {@link InsightPageView}
 * 的"量高 → 钳位 → 单指纵向拖动"语义），外加**左滑返回**。
 *
 * <p>为什么不复用 {@code InsightPageView}：那个类是本页**并列**的、且其滚动语义耦合了"排行区内滚"的
 * 手势分区；墨台是**第三窗**（模态覆盖层），语义不同（横滑 = 返回）。两者共享的是
 * {@link DeskRenderer} 对 {@code InsightRenderer.Section} 契约与静态工具的**复用**。
 *
 * <p>🔴 <b>模态语义</b>：墨台是"被呼出的整页"，铺满全屏 ⇒ 本视图**恒吃掉触摸**（与 {@code LockOverlay}
 * 一致），否则横滑会穿透到桌面。纵向拖动仅在内容溢出时真正位移（装得下 ⇒ `maxScroll == 0` ⇒
 * 钳住在 0，**不出现"空滚"**）—— 这是对 TASK-075 卡面「装得下不吞手势」在**模态层**下的正确落法。
 *
 * <p>🆕 **TASK-076**：分区**由模块注册表装配**（{@link PagePrefs} 的顺序/开关 + {@link DeskModules}）
 * ——「顺序即渲染顺序、关掉的模块不渲染不占高、总开关关掉 ⇒ 只剩空态」。
 *
 * <p>🔴 **无惯性 / 无回弹 / 无动画**（墨水屏铁律）；一帧一次重绘。
 */
public final class DeskPageView extends View {

    /** 与周卡 / 洞察页同尺 —— 同一 App 同一观感。 */
    private static final float UNIT_RATIO = 0.0015f;

    /** 手势轴判定阈值（px）：超过它才锁定"横"或"纵"。 */
    private static final float SLOP_PX = 14f;
    /** 左滑返回：横向位移须 ≥ 视宽 × 本值 才判定为"返回"。 */
    private static final float BACK_RATIO = 0.12f;

    /** 关闭回调（左滑 / 点 `‹ 返回` 都走它）。 */
    public interface Listener {
        void onDeskClose();
    }

    private final DeskRenderer renderer = new DeskRenderer();

    /**
     * 「读书排行」分区常驻实例（TASK-076）—— 内含内滚位移，装配时复用同一份。
     * 🔴 与 {@link InsightPageView} 同做法：换料只调 `setItems`，不 new（new 会把内滚位移丢掉）。
     */
    private final InsightRenderer.RankSection rankSection = DeskModules.rankSection();

    /**
     * 🆕 TASK-077：「阅读账单」分区常驻实例 —— 顶部页签条（摘录菜单 / 读书菜单）的命中判定要用它。
     * 与 {@link #rankSection} 同理：装配时复用同一份，不 new。
     */
    private final BillSection billSection = DeskModules.billSection();

    private Listener listener;

    private float unit;

    private float scrollY = 0f;
    private float maxScroll = 0f;

    private Bitmap bg;
    private String bgKey = null;

    private String updatedLabel = "";

    // ── 手势 ──
    private float downX, downY, lastY;
    private float totDx, totDy;
    private int axis = AXIS_NONE;
    /** 🆕 TASK-076：本次手势是否**落在排行区视口内**（手势分区 —— 决定这次滑动谁吃）。 */
    private boolean dragRank = false;

    private static final int AXIS_NONE = 0;
    private static final int AXIS_VERT = 1;
    private static final int AXIS_HORIZ = 2;

    public DeskPageView(Context c) { this(c, null); }

    public DeskPageView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFFFFFFFF);                 // 墨屏：白底，不做任何背景装饰
        unit = c.getResources().getDisplayMetrics().heightPixels * UNIT_RATIO;
        renderer.setEmptyText(c.getString(R.string.desk_empty));
        renderer.setLabels(c.getString(R.string.desk_title), c.getString(R.string.desk_back));
    }

    public void setListener(Listener l) { listener = l; }

    /** 每次呼出都从干净状态开始：滚动归顶 + 背景重读 + 模块重装 + 「更新于」= 现在。 */
    public void reset() {
        scrollY = 0f;
        axis = AXIS_NONE;
        dragRank = false;
        bg = null;               // 每次呼出重新读背景图（用户可能换了同名文件，缓存不能赖着）
        bgKey = null;
        updatedLabel = "更新于 " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        buildSections();
        remeasure(getWidth(), getHeight());
        invalidate();
    }

    /**
     * 🆕 TASK-076：按 {@code PagePrefs} 的**顺序 + 开关**装配分区。
     *
     * <pre>
     *   总开关关 ⇒ 不装任何分区（只剩空态）
     *   否则     ⇒ 遍历「已开启模块（按顺序）」，逐个 build 一个 Section 装进来
     * </pre>
     *
     * 🔴 关掉的模块**根本不进分区表** ⇒ 既不渲染也不占高（这是卡面「关掉不占高」的落法）。
     */
    private void buildSections() {
        renderer.clear();
        Context c = getContext();
        if (!PagePrefs.isDeskEnabled(c)) return;         // 总开关关 ⇒ 空态
        List<String> ids = PagePrefs.enabledInOrder(c);
        for (int i = 0; i < ids.size(); i++) {
            DeskModule m = DeskModules.byId(ids.get(i));
            if (m == null) {
                // 只可能是"注册表漏登记"（`PagePrefs.MODULE_*` 加了、`DeskModules.ALL` 没加）；
                // 正常路径永不触发 ⇒ 静默跳过 + dev 留痕，绝不因此崩。
                CardDebug.noteV(c, "desk: 模块无实现，已跳过 id=" + ids.get(i));
                continue;
            }
            InsightRenderer.Section s = m.build(c);
            if (s != null) renderer.add(s);
        }
    }

    /** 内容总高（像素）—— 给自动化断言 / 调试用。 */
    public float contentHeightPx() {
        return renderer.contentHeight(getWidth(), getHeight(), unit);
    }

    /** 当前滚动位移（给断言用）。 */
    public float scrollYPx() { return scrollY; }

    /** 🆕 TASK-076：当前装配进来的分区数（给自动化断言用）。 */
    public int sectionCount() { return renderer.count(); }

    /** 🆕 TASK-076：第 i 个分区的标题（给自动化断言用；越界 ⇒ 空串）。 */
    public String sectionTitle(int i) { return renderer.titleOf(i); }

    private void remeasure(int w, int h) {
        if (w <= 0 || h <= 0) return;
        maxScroll = renderer.contentHeight(w, h, unit) - h;
        if (maxScroll < 0f) maxScroll = 0f;
        if (scrollY > maxScroll) scrollY = maxScroll;
        if (scrollY < 0f) scrollY = 0f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        remeasure(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        String key = PagePrefs.getDeskBgPath(getContext());
        if (bgKey == null || !bgKey.equals(key)) {
            bgKey = key;
            bg = BgImageUtil.load(getContext(), key, w, h);
        }
        remeasure(w, h);     // 每帧自纠：分区高可能随字号 / 文案变化
        renderer.draw(c, w, h, unit, scrollY, bg, PagePrefs.getDeskBgVeil(getContext()), updatedLabel);
    }

    // ══════════════════════ 🆕 TASK-076：排行区手势分区 ══════════════════════

    /** 排行区**内容视口**在屏幕坐标里的顶边 y；-1 = 排行区不在分区表里（模块关了 / 没料）。 */
    private float rankBodyTop() {
        int idx = renderer.indexOf(rankSection);
        if (idx < 0) return -1f;
        float top = renderer.sectionTop(idx, getWidth(), getHeight(), unit) - scrollY;
        return top + InsightRenderer.secHeadH(unit);
    }

    /** 触点是否落在排行区的**视口矩形**内（🔴 手势分区的唯一判据）。 */
    private boolean hitRank(float y) {
        float bt = rankBodyTop();
        if (bt < 0f) return false;
        float vp = getHeight() * InsightRenderer.RANK_VIEWPORT_RATIO;
        return y >= bt && y <= bt + vp;
    }

    /** 排行区当前是否需要内滚（装得下 ⇒ 不吃手势，让整页滚）。 */
    private boolean rankScrollable() { return rankSection.canScroll(getHeight(), unit); }

    // ══════════════════════ 🆕 TASK-077：账单页签条命中 ══════════════════════

    /** 账单分区**顶边**的屏幕坐标 y；-1 = 账单分区不在分区表里（模块关了）。 */
    private float billTop() {
        int idx = renderer.indexOf(billSection);
        if (idx < 0) return -1f;
        return renderer.sectionTop(idx, getWidth(), getHeight(), unit) - scrollY;
    }

    /**
     * 点一下账单页签条 ⇒ 在「摘录菜单 / 读书菜单 / 月历」之间**循环**切换
     * （🆕 TASK-077b：月账单 3 档、周账单 2 档 —— 由 `visibleViews()` 决定）。
     *
     * <p>🔴 切换**零额外请求**：各视图共用同一份 {@code Bill}（`BillStore`），只是画法不同；
     * 分会变高变矮 ⇒ 切完要 remeasure + 重绘。
     *
     * @return true = 这次点击被页签条吃掉了（调用方不要再当"返回"处理）
     */
    private boolean hitBillTab(float x, float y) {
        float top = billTop();
        if (top < 0f) return false;
        if (!billSection.hitTab(x, y, getWidth(), unit, top)) return false;
        int[] vis = billSection.visibleViews();
        int cur = billSection.view();
        int pos = 0;
        for (int i = 0; i < vis.length; i++) {
            if (vis[i] == cur) pos = i;
        }
        int next = vis[(pos + 1) % vis.length];
        billSection.setView(getContext(), next);
        remeasure(getWidth(), getHeight());
        invalidate();
        return true;
    }

    /** 🆕 TASK-077b：点月历格子 ⇒ 选中该日（只影响绘制 ⇒ 只需重画，不必 remeasure）。 */
    private boolean hitCalCell(float x, float y) {
        float top = billTop();
        if (top < 0f) return false;
        if (!billSection.hitCalCell(x, y, getWidth(), unit, top)) return false;
        invalidate();
        return true;
    }

    // ══════════════════════ 触控：单指纵滚 + 左滑返回（模态，恒吃手势）══════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastY = downY;
                totDx = 0f;
                totDy = 0f;
                axis = AXIS_NONE;
                // 🔴 命中判定**只在按下那一刻做一次** —— 之后整段滑动都归它，避免半途改判导致跳变。
                dragRank = rankScrollable() && hitRank(downY);
                return true;

            case MotionEvent.ACTION_MOVE: {
                totDx = e.getX() - downX;
                totDy = e.getY() - downY;
                if (axis == AXIS_NONE) {
                    if (Math.max(Math.abs(totDx), Math.abs(totDy)) > SLOP_PX) {
                        axis = (Math.abs(totDx) > Math.abs(totDy)) ? AXIS_HORIZ : AXIS_VERT;
                    }
                }
                if (axis == AXIS_VERT) {
                    float mv = lastY - e.getY();     // 手指上滑 ⇒ mv > 0 ⇒ 内容上移
                    if (dragRank) {
                        // 🆕 TASK-076：落在排行区 ⇒ 本次滑动只驱动**区内**滚动，不带整页
                        if (rankSection.scrollBy(mv, getHeight(), unit)) invalidate();
                    } else {
                        float next = scrollY + mv;
                        if (next < 0f) next = 0f;
                        if (next > maxScroll) next = maxScroll;     // 到顶/到底夹住，不穿透、不回弹
                        if (next != scrollY) {
                            scrollY = next;
                            invalidate();
                        }
                    }
                }
                lastY = e.getY();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (axis == AXIS_HORIZ
                        && totDx <= -Math.max(SLOP_PX, getWidth() * BACK_RATIO)) {
                    if (listener != null) listener.onDeskClose();       // 左滑返回
                } else if (axis == AXIS_NONE
                        && e.getActionMasked() == MotionEvent.ACTION_UP
                        && hitBillTab(e.getX(), e.getY())) {
                    // 🆕 TASK-077：点账单页签条 ⇒ 切菜单（**优先于"返回"判定**，两者区域不重叠）
                } else if (axis == AXIS_NONE
                        && e.getActionMasked() == MotionEvent.ACTION_UP
                        && hitCalCell(e.getX(), e.getY())) {
                    // 🆕 TASK-077b：点月历格子 ⇒ 选中该日（同上，优先于"返回"）
                } else if (axis == AXIS_NONE
                        && e.getActionMasked() == MotionEvent.ACTION_UP
                        && renderer.hitBack(e.getX(), e.getY(), getWidth(), unit)) {
                    if (listener != null) listener.onDeskClose();       // 点 `‹ 返回`
                }
                axis = AXIS_NONE;
                dragRank = false;
                return true;

            default:
                return true;     // 模态：其余动作也吃掉，绝不穿透到桌面
        }
    }
}
