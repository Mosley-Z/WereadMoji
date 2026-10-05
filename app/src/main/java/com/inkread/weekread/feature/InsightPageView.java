package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.inkread.weekread.core.PeriodStats;

import java.util.List;

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

    private float unit;

    private final InsightRenderer renderer = new InsightRenderer();

    /**
     * 🆕 K8（TASK-053）：排行分区**常驻实例**（内含内滚位移）。
     * 🔴 为什么不让 `rebuildSections()` 每次 new：那会把内滚位移一并丢掉 ——
     * 换料时只调 {@link InsightRenderer.RankSection#setItems} 即可。
     */
    private final InsightRenderer.RankSection rankSection = new InsightRenderer.RankSection();

    /** K8：排行区的料（= 年度 `longest[]`；🔴 与分区②年度**同源**，零新增请求）。 */
    private List<PeriodStats.Longest> rankItems;

    /** K8：本次手势是否**落在排行区内**（手势分区 —— 决定这次滑动谁吃）。 */
    private boolean dragRank = false;

    /** 滚动位移（像素，0 = 顶部）。 */
    private float scrollY = 0f;
    /** 当前可滚上限（= 内容总高 − 视口高，≥0）。容器两次计算：尺寸变化时 + 每帧自纠。 */
    private float maxScroll = 0f;

    private float lastTouchY = 0f;
    private boolean dragging = false;

    // ── K4（TASK-049）+/ K5（TASK-050）：兴趣雷达 / 偏好作者 / 偏好时段的料（shell 层喂入）──
    private List<PeriodStats.PreferCat> interestCats;
    private List<PeriodStats.PreferCat> interestAuthors;
    private int[] interestTimes;
    private String interestScope;

    // ── K10（TASK-055）：画像判定用的两个**非偏好类**量（shell 层算好喂入）──
    /** 全库**想法正文**字数（`NoteStore.totalIdeaChars`）；≤0 = 未知 */
    private int interestNoteChars;
    /** 累计年数（`registTime` 手算）；-1 = 未知 */
    private int interestYears;

    // ── K6（TASK-051）：年度视图的料（null ⇒ 分区画空态）──
    private PeriodStats annualStats;

    // ── K7（TASK-052）：累计视图的料（null ⇒ 分区画空态）──
    private PeriodStats overallStats;
    /** K7 顺带（TASK-055）：累计视图②行要显示的「想法 N 字」（≤0 ⇒ 不显示该段） */
    private int overallNoteChars;

    public InsightPageView(Context c) { this(c, null); }

    public InsightPageView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFFFFFFFF);                 // 墨水屏：白底，不做任何背景装饰
        unit = c.getResources().getDisplayMetrics().heightPixels * UNIT_RATIO;
        rebuildSections();
    }

    // ══════════════════════ 料入口（后序卡按分区继续加） ══════════════════════

    /**
     * 🆕 K4（TASK-049）+ K5（TASK-050）：设定「画像」分区**三件套**的料。
     *
     * @param cats    某一档的 `preferCategory` 原始列表（**未收拢、未过滤** —— 收拢过滤在
     *                {@link InsightRenderer#aggregateCategories} 里做）；null/空 ⇒ 不画雷达子块
     * @param authors 同一档的 `preferAuthor` 原始列表；null/空 ⇒ 不画作者子块
     * @param times   同一档的 `preferTime`（24 桶原序）；全 0/null ⇒ 不画时段子块
     * @param scope   口径范围词（累计 / 今年 / 本月 / 本周），拼进各子块标题
     */
    public void setProfile(List<PeriodStats.PreferCat> cats,
                           List<PeriodStats.PreferCat> authors,
                           int[] times, String scope,
                           int noteChars, int years) {
        interestCats = cats;
        interestAuthors = authors;
        interestTimes = times;
        interestScope = scope;
        interestNoteChars = noteChars;
        interestYears = years;
        rebuildSections();
        invalidate();
    }

    /**
     * 🆕 K4：给「取数方」（{@code MainActivity}）判断**某一档有没有兴趣雷达料**用。
     *
     * 为什么要这个静态口：`InsightRenderer` 是**包内可见**的，shell 层引用不到它的类型；
     * 而"有没有料"必须用**收拢过滤后**的判据（`preferCategory` 可能整列表全是 `readingTime=0`，
     * 只看 `isEmpty()` 会误判成"有料"）。
     */
    public static boolean hasInterest(PeriodStats st) {
        return st != null
                && !InsightRenderer.aggregateCategories(st.preferCategory, 1).isEmpty();
    }

    /**
     * 🆕 K5（TASK-050）：判断**某一档有没有"画像三件套"里的任一料**（雷达 / 作者 / 时段）。
     *
     * 口径链（累计→年度→本月→本周）用它选档：只要该档**任一件**有料就用它，
     * 比只看雷达（{@link #hasInterest}）更全 —— 时段只有累计档有，作者年度/累计都有。
     */
    public static boolean hasProfile(PeriodStats st) {
        if (st == null) return false;
        if (!InsightRenderer.aggregateCategories(st.preferCategory, 1).isEmpty()) return true;
        if (!InsightRenderer.aggregateAuthors(st.preferAuthor, 1).isEmpty()) return true;
        return InsightRenderer.hasTime(st.preferTime);
    }

    /**
     * 🆕 K6（TASK-051）：设定「年度视图」分区（②）的料。
     *
     * 🆕 K8（TASK-053）：🔴 **顺带喂分区④「读书排行」** —— 排行统计的是**全年**（Q3 拍板），
     * 料就是同一份年度回包的 `longest[]` ⇒ **同源、零新增请求**（`MainActivity` 无需改动）。
     *
     * @param st 当年（`mode=annually`）的统计；null ⇒ ②④ 都画空态
     */
    public void setAnnual(PeriodStats st) {
        annualStats = st;
        rankItems = (st == null) ? null : st.longest;   // ⚠️ 只借引用，不排序、不过滤
        rebuildSections();
        invalidate();
    }

    /**
     * 🆕 K7（TASK-052）：设定「累计视图」分区（③）的料。
     *
     * @param st 累计（`mode=overall`）的统计；null ⇒ 画空态
     */
    public void setOverall(PeriodStats st, int noteChars) {
        overallStats = st;
        overallNoteChars = noteChars;
        rebuildSections();
        invalidate();
    }

    // ══════════════════════ 分区注册（后序卡的唯一改动点） ══════════════════════

    /**
     * 注册洞察页的 5 个分区。
     *
     * 🔴 未落地的分区仍是**占位**（只画空态文案）。后序卡（K10）落码时：
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
        // ② 年度（🆕 TASK-051 K6：**已落地** —— 12 桶按月柱图 + 汇总行 + readStat）
        renderer.add(InsightRenderer.annualSection(annualStats, "暂无年度数据"));
        // ③ 累计（🆕 TASK-052 K7：**已落地** —— 汇总行 + 陪伴年数 + 勋章计数 + readStat）
        renderer.add(InsightRenderer.overallSection(overallStats, "暂无累计数据", overallNoteChars));
        // ④ 排行（🆕 TASK-053 K8：**已落地** —— 有界高 42% + 内部独立滚动；料 = 年度 longest[]）
        rankSection.setItems(rankItems);
        renderer.add(rankSection);
        // ⑤ 画像（🆕 TASK-049 K4 兴趣雷达 + TASK-050 K5 偏好作者/时段：**三件套已落地**；
        //         K10 画像判定继续往这一格加）
        //         🆕 TASK-055 K10：判定块（判定句 + 依据）恒在分区最上，免责声明恒在最下
        renderer.add(InsightRenderer.profileSection(
                InsightRenderer.aggregateCategories(interestCats, InsightRenderer.RADAR_TOP_N),
                InsightRenderer.aggregateAuthors(interestAuthors, InsightRenderer.AUTHOR_TOP_N),
                interestTimes, interestScope, "暂无阅读画像",
                InsightRenderer.judgePortrait(interestCats, interestNoteChars, interestYears)));
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

    /**
     * 切换回洞察页时把位置复位（避免"上次滚到一半"的错位观感）。
     * 🆕 K8：排行区的**内滚位移一并复位**（否则会停在"看不到第 1 名"的怪位置）。
     */
    public void resetScroll() {
        scrollY = 0f;
        rankSection.resetInner();
        invalidate();
    }

    // ══════════════════════ 🆕 K8（TASK-053）：排行区手势命中 ══════════════════════

    /**
     * 排行区**内容视口**在屏幕坐标里的顶边 y；-1 = 排行区不在分区表里（理论上不会）。
     * 换算 = 分区在内容坐标的顶边 − 整页滚动位移 + 分区标题行高。
     */
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

    // ══════════════════════ 触控：单指纵向拖动（🆕 K8：与排行区内滚做手势分区）══════════════════════

    /**
     * 手势分区（方案甲）：
     * · **按下点在排行区视口内** ⇒ 本次滑动**只**驱动排行区内滚，**绝不**带动整页；
     * · 其它位置 ⇒ 走原来的整页滚动；
     * · 🔴 **到顶/到底不穿透**（内滚夹住后不把余量交给外层）—— 避免「想翻排行却整页乱跳」（卡面 A4/R1）。
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean outerCan = maxScroll > 0f;             // 整页装不下 ⇒ 可滚
        boolean rankCan = rankScrollable();            // 排行装不下 ⇒ 可内滚
        // 🔴 两者都不可滚才不吃手势（让上层拿到这次触摸）；任一可滚就得接住。
        if (!outerCan && !rankCan) return false;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchY = e.getY();
                dragging = true;
                // 🔴 命中判定**只在按下那一刻做一次** —— 之后整段滑动都归它，避免半途改判导致跳变。
                dragRank = rankCan && hitRank(e.getY());
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return true;
                float dy = e.getY() - lastTouchY;
                lastTouchY = e.getY();
                // 手指上滑（dy < 0）⇒ 内容上移 ⇒ 位移增大
                if (dragRank) {
                    if (rankSection.scrollBy(-dy, getHeight(), unit)) invalidate();
                } else if (outerCan) {
                    float next = scrollY - dy;
                    if (next < 0f) next = 0f;
                    if (next > maxScroll) next = maxScroll;
                    if (next != scrollY) {
                        scrollY = next;
                        invalidate();                    // 🔴 一帧一次重绘（墨水屏：不做局部/多次失效）
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                dragRank = false;
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }
}
