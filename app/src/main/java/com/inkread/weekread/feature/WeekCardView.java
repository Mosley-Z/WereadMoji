package com.inkread.weekread.feature;

import com.inkread.weekread.core.BookStats;
import com.inkread.weekread.core.NoteStats;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PeriodStats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 黑白统计卡片（墨水屏友好：纯黑白、无动画、整页一次性绘制）。
 *
 * 同一套绘制被三处复用：
 *   ① 桌面悬浮卡 —— 窗口由 com.inkread.weekread.core.CardSpec 定位，横向内边距为 0，
 *      于是分隔线 / 柱状图两端正好压在桌面 3×2 图标的左右外沿上；
 *   ② 主页整屏       —— 保留 5% 的横向内边距，文字不贴屏幕边；
 *   ③ 设置页预览     —— 同 ②。
 *
 * **一套代码两个形态**：内部按 {@link #mode} 分渲染分支 ——
 *   · `weekly`  → 7 根柱状图；
 *   · `monthly` → 日历打卡网格（实心黑方块 + 白勾 / 细描边空框 / 未来留白）。
 * 在此之上还有**两个尺寸档**（{@link #setFullscreen}）：卡片档（桌面悬浮卡与设置页预览）
 * 与全屏档（App 内）。
 *
 * ── TASK-007 拆分（2026-09）──
 * 原先 2004 行的"绘制 + 排版 + 触摸"单体按职责拆成三件套，本类退回**状态壳**：
 *   · {@link CardLayout}     —— 排版与几何（fit / ellipsize / wrapLines / 本记正文排版 /
 *                              按钮几何 / 抬头文案 / 时长格式化），静态部分与 NoteExport 共用；
 *   · {@link CardRenderer}   —— 全部绘制（原 onDraw 体 + 17 个绘制方法）；
 *   · {@link CardInteraction}—— 触摸与滚动（原 onTouchEvent / noteTouch / setNoteScroll）。
 *
 * 🔴 拆分手法与 TASK-006 一致：**状态字段全部留在本壳**（含画笔 p / path / bmpPaint /
 * bmpDst 与各命中矩形），三个协作类持 host 引用按包级可见访问；代码逐行平移零改动 ——
 * 拆分前后像素级行为必须完全一致（TASK-007 像素基线 9 场景逐张比对把关）。
 *
 * 字号约定：1「号」= 屏幕高度 × 0.0015（480×800 屏上 = 1.2px）。
 * 以屏幕高度为基准而不是本 View 高度，这样卡片只占 370px 高时字号不会被连带缩小。
 */
public class WeekCardView extends View {

    /** 1 号 = 屏高 * 该系数 */
    private static final float UNIT_RATIO = 0.0015f;

    /** 导出长图的宽度（与屏幕同宽，等比；高度按内容算）—— {@link NoteExport} 用 */
    public static final int EXPORT_W = 480;
    /** 导出长图的高度上限 —— 超过就截断（RGB_565 下 480×8000 ≈ 7.3MB，安全） */
    public static final int EXPORT_H_MAX = 8000;

    // ══════════════════════ 状态字段（全部留壳，三件套按包级可见访问）══════════════════════

    PeriodStats stats;
    /** weekly / monthly —— 决定用哪条渲染分支（见 {@link PeriodRange}） */
    String mode = PeriodRange.WEEKLY;
    boolean refreshing = false;
    String errorText = null;
    float unit;   // 1 号 的像素值
    /** 横向内边距占 View 宽的比例。桌面卡片设为 0，让内容两端与图标外沿对齐 */
    float padXRatio = 0.05f;
    /** true = App 内全屏大版（本月页纵向铺开）；桌面卡片恒为 false */
    boolean fullscreen = false;
    /**
     * 非 null 时**不画图形，改画这行字**。
     *
     * 只给"历史周期没数据"用（设计方案 §6.4）：一张全空的日历会被误认为"加载失败"，
     * 所以宁可明说「这一周没有阅读记录」。当前周期**不用**这个（"这周还没读"用空网格表达更直观）。
     */
    String emptyNote = null;
    /** 非空时整页显示占位内容 */
    String phMain = null;
    String phSub = null;

    /** 「本书」形态的数据。与 {@link #stats} 互斥，由 {@link #mode} 决定用哪个 */
    BookStats book;
    /** 「本记」当前展示的那条划线（v0.4.0） */
    NoteStats note;
    /**
     * 「本记」空态的自定义提示行（v0.5.3，R02/R08）。
     *
     * 为什么要它：空态有两种性质完全不同的原因，必须让用户分得清 ——
     *   · **失败**（离线 / Key 失效）→ 「同步失败」+ 可重试；
     *   · **真的没有**（新账号、只看想法但一条想法都没写过）→ 讲清"为什么空"，
     *     而不是笼统地画一句「本记还没有准备好」让用户反复点刷新。
     * null = 用默认文案。
     */
    String noteHint = null;
    long noteFetchedAt;
    /** 当前已解码的封面（{@code coverBmpId} 标明它属于哪本书，防止切书后串图） */
    android.graphics.Bitmap coverBmp;
    String coverBmpId = null;
    /** 封面绘制专用画笔（双线性过滤，缩小到 ~110px 时不糊成马赛克） */
    final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final RectF bmpDst = new RectF();
    /** 右下角「打开」按钮的矩形（**本 View 内部坐标**），供命中测试用 */
    final RectF openBox = new RectF();
    /** 本记形态「导出」按钮的矩形（只在 App 全屏档有效；卡片档恒为空） */
    final RectF exportBox = new RectF();
    /**
     * 本记形态**左下角「筛选」格**的矩形（v0.4.5，App 全屏档专属）。
     * 一格两态：「全部」（描边）/「只看想法」（反白），点一下切换。
     */
    final RectF filterBox = new RectF();
    /** 本记形态左下角「上一条」按钮的矩形（v0.4.2） */
    final RectF prevBox = new RectF();
    OpenListener openListener;
    NoteListener noteListener;

    // ── v0.4.4：本记正文的滚动与导出模式 ──

    /**
     * 本记正文的滚动量（px）。**只滚正文段**——抬头、署名、末行、按钮全部固定在原位。
     *
     * 为什么不用 ScrollView 包一层：① 按钮会跟着滚走（滑到中间就点不到「换一条」了）；
     * ② 墨水屏上惯性滑动会拖出一串残影，而自绘滚动可以做到"跟手、松手即停"。
     */
    float noteScrollY = 0f;
    /**
     * 本记正文的**行高 / 内容总高 / 可视高 / 滚动上限**（绘制时算，触摸与滚动用）。
     *
     * `noteLineH` 是滚动与裁剪的**对齐单位**：所有正文行都落在它的整数倍上
     * （见 CardLayout#layoutNote 里 `tagBlock = lineH`），可视高与滚动量也取整到它，
     * 于是裁剪边界永远压在行边界上 —— 一行字不会被拦腰切断。
     * 这么做还顺带解决了"最后一行与署名挤在一起"的观感问题（墨水上尤其明显）。
     */
    float noteLineH = 0f;
    float noteScrollMax = 0f;
    float noteContentH = 0f, noteViewH = 0f;
    float noteDownY = 0f, noteDownScroll = 0f;
    boolean noteDrag = false;
    /**
     * 进度行取哪套序号空间（v0.4.4）。
     *
     * App 本记页有「全部 / 只看想法」两个模式，两套模式的池子与序号是**分开存的**
     * （见 NoteStore#pick）；桌面卡片恒为 false。
     * 由 {@code MainActivity.showNoteItem} 在设内容前设进来。
     */
    boolean noteIdeasSlot = false;

    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();

    /** TASK-007 拆分出的三个协作类（与宿主同包，构造时绑定， thereafter 不可变） */
    final CardLayout layout;
    final CardRenderer renderer;
    final CardInteraction interaction;

    public interface OpenListener {
        /** 点了「打开」—— 跳微信读书 */
        void onOpen();
    }

    /**
     * 「本记」形态的页内按钮回调（v0.4.1 起；v0.4.2 加「上一条」）。
     *
     * 之前 App 内的「换一条」是个**假按钮**：onTouchEvent 开头一句
     * `if (!isBook()) return false;` 把本记态的触摸全部放掉了（那段代码是 v0.3.4
     * 为「本书·打开」写的，v0.4.0 加本记态时没跟上）。这里把回调拆开，
     * 导出也从底部通用按钮区搬进页面内 —— 它本来就只对这一页的内容有意义。
     */
    public interface NoteListener {
        /** 点了「换一条」—— 手动插队抽下一条 */
        void onNextNote();
        /** 点了「上一条」—— 沿来时的路退回去（v0.4.2） */
        void onPrevNote();
        /** 点了「导出」—— 把当前这条导出成图片 */
        void onExportNote();
        /** 点了左下角的「筛选」格（v0.4.5）—— 在「全部 / 只看想法」之间切换 */
        void onToggleIdeas();
    }

    public WeekCardView(Context c) { this(c, null); }

    public WeekCardView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFFFFFFFF);
        unit = c.getResources().getDisplayMetrics().heightPixels * UNIT_RATIO;
        layout = new CardLayout(this);
        renderer = new CardRenderer(this);
        interaction = new CardInteraction(this);
    }

    // ══════════════════════ public API（行为与拆分前逐字节一致）══════════════════════

    /** 横向内边距比例：主页 0.05（默认），桌面悬浮卡 0 */
    public void setPadXRatio(float r) {
        padXRatio = r < 0f ? 0f : r;
        invalidate();
    }

    /** 切"全屏大版"（App 内本月页纵向铺开）。桌面卡片**不要**开这个 */
    public void setFullscreen(boolean f) {
        if (fullscreen == f) return;
        fullscreen = f;
        invalidate();
    }

    /**
     * 切形态：weekly / monthly / **book**（只换一帧内容，不影响任何显隐逻辑）。
     *
     * book 是 v0.3.4 起的第三态 —— 它在 {@link PeriodRange} 里和前两者并列，
     * 但**不是周期**：没有起止、不能步进，数据走 BookStore 而不是周期缓存。
     */
    public void setMode(String m) {
        String v = PeriodRange.MONTHLY.equals(m) ? PeriodRange.MONTHLY
                : (PeriodRange.BOOK.equals(m) ? PeriodRange.BOOK
                : (PeriodRange.NOTE.equals(m) ? PeriodRange.NOTE : PeriodRange.WEEKLY));
        if (v.equals(mode)) return;
        mode = v;
        noteHint = null;            // 换形态了，上一个形态的空态提示不再适用（v0.5.3）
        invalidate();
    }

    public void setOpenListener(OpenListener l) { openListener = l; }

    /** 设「本记」两个页内按钮的回调（v0.4.1） */
    public void setNoteListener(NoteListener l) { noteListener = l; }

    public String getMode() {
        return mode;
    }

    public void setStats(PeriodStats s) { setStats(s, null); }

    /**
     * @param note 非 null = 该周期没有阅读记录（只有"历史周期"会走到这一步），
     *             此时不画图形，改画这行字。见 {@link #emptyNote}
     */
    public void setStats(PeriodStats s, String note) {
        stats = s;
        errorText = null;
        refreshing = false;
        phMain = null;
        emptyNote = note;
        invalidate();
    }

    /** 设「本书」数据。传 null = 还没拿到（会画"正在获取…"或错误文案） */
    public void setBook(BookStats b) {
        book = b;
        errorText = null;
        refreshing = false;
        phMain = null;
        emptyNote = null;
        invalidate();
    }

    /** 设「本记」数据（v0.4.0）。传 null = 池子还没就绪（画引导文案） */
    public void setNote(NoteStats n) {
        note = n;
        errorText = null;
        refreshing = false;
        phMain = null;
        emptyNote = null;
        noteFetchedAt = System.currentTimeMillis();
        invalidate();
    }

    public NoteStats getNote() {
        return note;
    }

    /** 当前「本书」形态显示的那本（v0.5.3，R06：「打开」要跟着屏幕上的这本走，不是落盘的那本） */
    public BookStats getBook() {
        return book;
    }

    /**
     * 设「本记」空态的提示行（v0.5.3）。传 null = 回到默认文案。
     * 只在 {@link #note} 为空时有意义 —— 有内容时这一行不画。
     */
    public void setNoteHint(String s) {
        boolean same = (s == null) ? (noteHint == null) : s.equals(noteHint);
        if (same) return;
        noteHint = s;
        invalidate();
    }

    /** 进度行用哪套序号空间（v0.4.4「只看想法」）—— App 在设内容前调用；桌面卡片不调（默认全量池） */
    public void setNoteSlot(boolean ideasOnly) {
        if (noteIdeasSlot == ideasOnly) return;
        noteIdeasSlot = ideasOnly;
        invalidate();
    }

    /**
     * 设封面位图（v0.3.5.1，方案 A 左封右文）。
     * 传 null = 没拿到（画描边占位框）。异步下载完成后再调一次即可，自动重绘。
     */
    public void setCoverBitmap(android.graphics.Bitmap bmp, String bookId) {
        coverBmp = bmp;
        coverBmpId = bookId;
        if (isBook()) invalidate();
    }

    public void setRefreshing(boolean r) { refreshing = r; invalidate(); }

    public void setError(String msg) {
        errorText = msg; refreshing = false; phMain = null; emptyNote = null; invalidate();
    }

    /** 切到占位页 */
    public void setPlaceholder(String main, String sub) {
        phMain = main;
        phSub = sub;
        emptyNote = null;
        invalidate();
    }

    public boolean isPlaceholder() { return phMain != null; }

    /** 当前是否在显示"本月"形态（包级 —— CardRenderer 的分流要用） */
    boolean isMonthly() {
        return PeriodRange.MONTHLY.equals(mode);
    }

    /** 当前是否在显示"本书"形态 */
    public boolean isBook() {
        return PeriodRange.BOOK.equals(mode);
    }

    /** 当前是否在显示"本记"形态 */
    public boolean isNote() {
        return PeriodRange.NOTE.equals(mode);
    }

    /**
     * 「更新于 HH:MM」的时间来源：周/月看周期缓存，本书看 BookStats#fetchedAt，
     * 本记看取出的时刻。（包级 —— CardLayout 的 updatedLabel 要用）
     */
    long dataTime() {
        if (isBook()) return book == null ? 0L : book.fetchedAt;
        if (isNote()) return noteFetchedAt;
        return stats == null ? 0L : stats.fetchedAt;
    }

    // ══════════════════════ 委托（TASK-007：绘制 / 触摸各归其主）══════════════════════

    @Override
    protected void onDraw(Canvas c) {
        renderer.draw(c);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return interaction.touch(e);
    }

    /** 正文是否超出一屏（还能滚）—— 一行转发到交互类 */
    public boolean noteScrollable() {
        return interaction.scrollable();
    }
}
