package com.inkread.weekread.feature;

import com.inkread.weekread.core.BookStats;
import com.inkread.weekread.core.CardSpec;
import com.inkread.weekread.core.NoteStats;
import com.inkread.weekread.core.NoteStore;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PeriodStats;
import com.inkread.weekread.core.StatsStore;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 黑白统计卡片（墨水屏友好：纯黑白、无动画、整页一次性绘制）。
 *
 * 同一套绘制被三处复用：
 *   ① 桌面悬浮卡 —— 窗口由 {@link CardSpec} 定位，横向内边距为 0，
 *      于是分隔线 / 柱状图两端正好压在桌面 3×2 图标的左右外沿上；
 *   ② 主页整屏       —— 保留 5% 的横向内边距，文字不贴屏幕边；
 *   ③ 设置页预览     —— 同 ②。
 *
 * **一套代码两个形态**（设计方案 §3.4）：内部按 {@link #mode} 分两条渲染分支 ——
 *   · `weekly`  → 沿用原有的 7 根柱状图（**一个字都没改**）；
 *   · `monthly` → 日历打卡网格（实心黑方块 + 白勾 / 细描边空框 / 未来留白）。
 * 两条分支共用抬头、主数字、底部统计行的排版代码，只换中间那块图形。
 *
 * 在此之上还有**两个尺寸档**（{@link #setFullscreen}）：
 *   · 卡片档（默认，桌面悬浮卡与设置页预览）—— 本月版是"左文字 + 右日历"左右分栏，格 20px；
 *   · 全屏档（App 内）—— 本月版纵向铺开，格边长按可用高度自适应（目标 52px）。
 * 之所以要分档：卡片只有 368×346，全屏有 480×510，硬用一套排版必然有一头难看。
 *
 * 字号约定：1「号」= 屏幕高度 × 0.0015（480×800 屏上 = 1.2px）。
 * 以屏幕高度为基准而不是本 View 高度，这样卡片只占 370px 高时字号不会被连带缩小。
 */
public class WeekCardView extends View {

    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF3C3C3C;
    private static final int LIGHT = 0xFFA8A8A8;

    /** 1 号 = 屏高 * 该系数 */
    private static final float UNIT_RATIO = 0.0015f;

    // ── 字号表（单位：号）──
    private static final float SZ_TITLE = 19f;      // 卡片抬头（加粗）
    private static final float SZ_UPDATED = 17f;    // 右上「更新于」
    private static final float SZ_BIG = 38f;        // 周期总时长（46 → 42 → 38）
    private static final float SZ_BAR = 16f;        // 柱顶数值
    private static final float SZ_DAY = 16f;        // 星期标签
    private static final float SZ_BOTTOM = 17f;     // 底部统计行（加粗）
    private static final float SZ_BOTTOM_MIN = 12f; // 底部统计行自动缩小的下限
    private static final float SZ_EMPTY = 21f;      // 空态/错误大字
    private static final float SZ_SUB = 17f;        // 空态/错误小字 / 本月左栏副标题
    private static final float SZ_CAL_HEAD = 12f;   // 卡片版日历表头「一…日」

    // ── App 内「本月」全屏大版的字号/尺寸（设计方案 §2.3 / §3.2）──
    // 卡片版是"信息密度优先"，全屏版是"看得清优先"：主数字 38→46 号，表头 12→16 号。
    private static final float SZ_BIG_FULL = 46f;    // 全屏主数字（卡片版是 38）
    private static final float SZ_SUB_FULL = 19f;    // 全屏副信息（日均 / 较上月 / 阅读天数）
    private static final float SZ_EMPTY_FULL = 22f;  // 全屏空态（历史周期没数据）
    private static final float SZ_CAL_HEAD_FULL = 16f; // 全屏日历表头（≈19px）
    /** 全屏格子边长**目标值**；实际会按可用高度自适应缩小（保证一定装得下）*/
    private static final float MONTH_FULL_CELL = 52f;
    private static final float MONTH_FULL_GAP = 8f;
    private static final float MONTH_FULL_ROW_GAP = 8f;

    // ── v0.3.5.1「本书」版式（方案 A 左封右文）──
    // 封面左立（比例 250:360，t6 规格实测）；书名右区折行两行（不再缩字）；
    // 章节名灰字；时长(左)+百分比(右)；进度条全宽贴近「打开」。
    private static final float SZ_BOOK_TITLE = 15f;        // 书名（卡片档，折行两行）
    private static final float SZ_BOOK_TITLE_FULL = 21f;   // 书名（App 全屏档）
    private static final float SZ_BOOK_AUTHOR = 12f;       // 作者（卡片档，书名下一行小灰字）
    private static final float SZ_BOOK_AUTHOR_FULL = 15f;
    private static final float SZ_BOOK_CH = 13f;           // 章节名（卡片档）
    private static final float SZ_BOOK_CH_FULL = 17f;
    private static final float SZ_BOOK_DUR = 17f;          // 时长/百分比行（卡片档）
    private static final float SZ_BOOK_DUR_FULL = 22f;
    private static final float BOOK_COVER_W = 90f;         // 封面宽（号）→ 108px
    private static final float BOOK_COVER_W_FULL = 120f;   // → 144px
    private static final float BOOK_COVER_GAP = 14f;       // 封面与右区文字的间距
    private static final float BOOK_COVER_GAP_FULL = 18f;
    /** 进度条高度（号）。卡片档 ≈15.6px，全屏档 ≈21.6px */
    private static final float BOOK_BAR_H = 13f;
    private static final float BOOK_BAR_H_FULL = 18f;
    /** 右下角「打开」按钮里的文字 */
    private static final float SZ_OPEN = 14f;
    private static final String OPEN_LABEL = "打开";
    /** 本记形态下同一位置的按钮文字（v0.4.0） */
    private static final String NOTE_LABEL = "换一条";
    /** 本记形态左下角按钮（v0.4.2）—— 与「换一条」左右对称 */
    private static final String PREV_LABEL = "上一条";
    /** 本记形态「导出」按钮（v0.4.1：只画在 App 全屏档，桌面卡片放不下） */
    private static final String EXPORT_LABEL = "导出";

    // ── v0.4.0「本记」版式 ──
    // 引号 + 正文折行（衬线体）+ 署名行（书名粗/作者灰）+ 末行（章节·日期）。
    // 正文行数不写死：**按可用高度算**（长句多给几行，短句自然留白），杜绝压到署名。
    private static final float SZ_NOTE_QUOTE = 30f;       // 开头的大引号
    private static final float SZ_NOTE_QUOTE_FULL = 40f;
    private static final float SZ_NOTE_TEXT = 14f;        // 划线正文（卡片档）
    private static final float SZ_NOTE_TEXT_FULL = 19f;   // 划线正文（App 全屏档）
    private static final float SZ_NOTE_TITLE = 14f;       // 署名：书名
    private static final float SZ_NOTE_TITLE_FULL = 17f;
    private static final float SZ_NOTE_AUTHOR = 12f;      // 署名：作者
    private static final float SZ_NOTE_AUTHOR_FULL = 15f;
    private static final float SZ_NOTE_META = 11.5f;      // 末行：章节 · 日期
    private static final float SZ_NOTE_META_FULL = 14f;
    /** 正文行数上下限（算出来是动态值，钳在这里面）。上限 16 是给 App 档小字号
     *  长文的（14号需 16 行才能装下 373 字）；卡片档自然算出的行数 ≤7，碰不到上限 */
    private static final int NOTE_LINES_MIN = 3;
    private static final int NOTE_LINES_MAX = 16;
    /** 卡片档两档分界（v0.4.1 拍板①）：≤70 字（实测 80% 划线）用 18 号大字 */
    private static final int NOTE_CARD_BIG_CHARS = 70;
    private static final float SZ_NOTE_CARD_BIG = 18f;
    /** App 档自适应分档（v0.4.1 拍板②）：19→17→15→14 号，容量 188/250/304/373 字 */
    private static final int[] NOTE_FULL_STEPS = {188, 250, 304};
    private static final float[] NOTE_FULL_SIZES = {19f, 17f, 15f, 14f};

    // ── v0.4.4「本记」两段式（原文 + 想法）──
    // 分档口径改成「原文 + 想法」的**合计字数**（{@link NoteStats#displayChars()}），
    // 分界仍是用户拍板的 188/250/304（沿用），只是喂进去的数字变大了。
    /** 想法段上方的小标（把"书里的话"和"我写的话"分开） */
    private static final String IDEA_TAG = "想法";
    private static final float SZ_NOTE_IDEA_TAG = 11f;         // 卡片档
    private static final float SZ_NOTE_IDEA_TAG_FULL = 13.5f;  // App 全屏档 / 导出
    /**
     * 卡片档的两段限行数（v0.4.4 起先按「原文 2 行 / 想法 4 行」写死；
     * **v0.4.5 改为按可用高度动态分配**，见 {@link #drawNoteBody} 段② —— 写死的行数会让
     * 没有想法的划线（占全库 96%）只占 2 行，剩下三分之一张卡片是空的）。
     * 这两个值现在只作为「两段各自的**下限**」，思路是：宁可截断也不能压到署名。
     */
    private static final int NOTE_CARD_QUOTE_MIN = 1;
    private static final int NOTE_CARD_IDEA_MIN = 2;
    /** 滑动判定阈值（px）—— 位移不超过它就算"点击"，不算滚动 */
    private static final float NOTE_SCROLL_SLOP = 12f;
    /**
     * App 全屏档本记按钮行的几何（v0.4.5）：**四格等宽**（筛选 / 上一条 / 换一条 / 导出）。
     *
     * 50 + 3×8 = 424 ≤ 可用宽 432，两侧各余 4px 居中。
     * 为什么不排五格（两个筛选项各占一格）：格宽要压到 84px 且字号得从 17 号降到 15 号，
     * 墨水屏上五个小格子挨在一起误触明显；并成一格切换，格宽 100px、字号不变。
     */
    private static final float NOTE_BOX_W = 100f;
    private static final float NOTE_BOX_GAP = 8f;
    /** 筛选格里的文字字号（与全屏档按钮同号） */
    private static final float SZ_NOTE_FILTER = 17f;
    private static final String FILTER_ALL = "全部";
    private static final String FILTER_IDEA = "想法";
    /** 导出长图的宽度（与屏幕同宽，等比；高度按内容算）—— {@link NoteExport} 用 */
    public static final int EXPORT_W = 480;
    /** 导出长图的高度上限 —— 超过就截断（RGB_565 下 480×8000 ≈ 7.3MB，安全） */
    public static final int EXPORT_H_MAX = 8000;

    private static final String[] DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    /** 日历表头：**恒为「一…日」**，不随月份/语言变化（周一起算） */
    private static final String[] CAL_HEAD = {"一", "二", "三", "四", "五", "六", "日"};

    private PeriodStats stats;
    /** weekly / monthly —— 决定用哪条渲染分支（见 {@link PeriodRange}） */
    private String mode = PeriodRange.WEEKLY;
    private boolean refreshing = false;
    private String errorText = null;
    private float unit;   // 1 号 的像素值
    /** 横向内边距占 View 宽的比例。桌面卡片设为 0，让内容两端与图标外沿对齐 */
    private float padXRatio = 0.05f;
    /** true = App 内全屏大版（本月页纵向铺开）；桌面卡片恒为 false */
    private boolean fullscreen = false;
    /**
     * 非 null 时**不画图形，改画这行字**。
     *
     * 只给"历史周期没数据"用（设计方案 §6.4）：一张全空的日历会被误认为"加载失败"，
     * 所以宁可明说「这一周没有阅读记录」。当前周期**不用**这个（"这周还没读"用空网格表达更直观）。
     */
    private String emptyNote = null;
    /** 非空时整页显示占位内容 */
    private String phMain = null;
    private String phSub = null;

    /** 「本书」形态的数据。与 {@link #stats} 互斥，由 {@link #mode} 决定用哪个 */
    private BookStats book;
    /** 「本记」当前展示的那条划线（v0.4.0） */
    private NoteStats note;
    /**
     * 「本记」空态的自定义提示行（v0.5.3，R02/R08）。
     *
     * 为什么要它：空态有两种性质完全不同的原因，必须让用户分得清 ——
     *   · **失败**（离线 / Key 失效）→ 「同步失败」+ 可重试；
     *   · **真的没有**（新账号、只看想法但一条想法都没写过）→ 讲清"为什么空"，
     *     而不是笼统地画一句「本记还没有准备好」让用户反复点刷新。
     * null = 用默认文案。
     */
    private String noteHint = null;
    private long noteFetchedAt;
    /** 当前已解码的封面（{@code coverBmpId} 标明它属于哪本书，防止切书后串图） */
    private android.graphics.Bitmap coverBmp;
    private String coverBmpId = null;
    /** 封面绘制专用画笔（双线性过滤，缩小到 ~110px 时不糊成马赛克） */
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bmpDst = new RectF();
    /** 右下角「打开」按钮的矩形（**本 View 内部坐标**），供命中测试用 */
    private final RectF openBox = new RectF();
    /** 本记形态「导出」按钮的矩形（只在 App 全屏档有效；卡片档恒为空） */
    private final RectF exportBox = new RectF();
    /**
     * 本记形态**左下角「筛选」格**的矩形（v0.4.5，App 全屏档专属）。
     * 一格两态：「全部」（描边）/「只看想法」（反白），点一下切换。
     */
    private final RectF filterBox = new RectF();
    /** 本记形态左下角「上一条」按钮的矩形（v0.4.2） */
    private final RectF prevBox = new RectF();
    private OpenListener openListener;
    private NoteListener noteListener;

    // ── v0.4.4：本记正文的滚动与导出模式 ──

    /**
     * 本记正文的滚动量（px）。**只滚正文段**——抬头、署名、末行、按钮全部固定在原位。
     *
     * 为什么不用 ScrollView 包一层：① 按钮会跟着滚走（滑到中间就点不到「换一条」了）；
     * ② 墨水屏上惯性滑动会拖出一串残影，而自绘滚动可以做到"跟手、松手即停"。
     */
    private float noteScrollY = 0f;
    /**
     * 本记正文的**行高 / 内容总高 / 可视高 / 滚动上限**（onDraw 里算，触摸与滚动用）。
     *
     * `noteLineH` 是滚动与裁剪的**对齐单位**：所有正文行都落在它的整数倍上
     * （见 {@link #layoutNote} 里 `tagBlock = lineH`），可视高与滚动量也取整到它，
     * 于是裁剪边界永远压在行边界上 —— 一行字不会被拦腰切断。
     * 这么做还顺带解决了"最后一行与署名挤在一起"的观感问题（墨水上尤其明显）。
     */
    private float noteLineH = 0f;
    private float noteScrollMax = 0f;
    private float noteContentH = 0f, noteViewH = 0f;
    private float noteDownY = 0f, noteDownScroll = 0f;
    private boolean noteDrag = false;
    /**
     * 进度行取哪套序号空间（v0.4.4）。
     *
     * App 本记页有「全部 / 只看想法」两个模式，两套模式的池子与序号是**分开存的**
     * （见 {@link NoteStore#pick(Context, boolean, boolean)}）；桌面卡片恒为 false。
     * 由 {@code MainActivity.showNoteItem} 在设内容前设进来。
     */
    private boolean noteIdeasSlot = false;

    public interface OpenListener {
        /** 点了「打开」—— 跳微信读书 */
        void onOpen();
    }

    /**
     * 「本记」形态的页内按钮回调（v0.4.1 起；v0.4.2 加「上一条」）。
     *
     * 之前 App 内的「换一条」是个**假按钮**：{@link #onTouchEvent} 开头一句
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

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public WeekCardView(Context c) { this(c, null); }

    public WeekCardView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFFFFFFFF);
        unit = c.getResources().getDisplayMetrics().heightPixels * UNIT_RATIO;
    }

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
     * 但**不是周期**：没有起止、不能步进，数据走 {@link BookStore} 而不是周期缓存。
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

    /** 当前是否在显示"本月"形态 */
    private boolean isMonthly() {
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

    /** 「更新于 HH:MM」的时间来源：周/月看周期缓存，本书看 {@link BookStats#fetchedAt}，本记看取出的时刻 */
    private long dataTime() {
        if (isBook()) return book == null ? 0L : book.fetchedAt;
        if (isNote()) return noteFetchedAt;
        return stats == null ? 0L : stats.fetchedAt;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFFFFFFFF);
        float w = getWidth(), h = getHeight();
        if (phMain != null) {
            drawCentered(c, w / 2f, h * 0.42f, phMain, SZ_EMPTY * unit, INK);
            if (phSub != null) {
                wrapCentered(c, w / 2f, h * 0.42f + SZ_EMPTY * unit * 2.0f, w * 0.8f, phSub,
                        SZ_SUB * unit, GRAY);
            }
            return;
        }
        float ruleY = drawHeader(c, w, h);
        float padY = h * 0.055f;
        float left = w * padXRatio;
        float right = w - w * padXRatio;

        String key = StatsStore.getKey(getContext());
        if (key.length() == 0) {
            drawCentered(c, (left + right) / 2f, h * 0.46f, "还未配置微信读书 API Key", SZ_EMPTY * unit, INK);
            drawCentered(c, (left + right) / 2f, h * 0.46f + SZ_EMPTY * unit * 1.7f,
                    "点下方「设置」按引导粘贴 Key", SZ_SUB * unit, GRAY);
            return;
        }

        // 「本书」形态走自己的分支：数据是 BookStats，没有周期、没有柱状图/日历
        if (isBook()) {
            drawBookBody(c, w, h, left, right, padY, ruleY);
            return;
        }

        // 「本记」形态（v0.4.0）：一条随机划线，数据走 NoteStore
        if (isNote()) {
            drawNoteBody(c, w, h, left, right, padY, ruleY);
            return;
        }

        if (errorText != null && stats == null) {
            drawCentered(c, (left + right) / 2f, h * 0.40f, "获取失败", SZ_EMPTY * unit, INK);
            wrapCentered(c, (left + right) / 2f, h * 0.50f, (right - left) * 0.96f, errorText,
                    SZ_SUB * unit, GRAY);
            drawCentered(c, (left + right) / 2f, h * 0.66f, "点下方「刷新」重试", SZ_SUB * unit, GRAY);
            return;
        }

        if (stats == null) {
            drawCentered(c, (left + right) / 2f, h * 0.46f,
                    refreshing ? ("正在获取" + (isMonthly() ? "本月" : "本周") + "数据…")
                               : "暂无数据，点下方「刷新」",
                    SZ_SUB * unit, GRAY);
            return;
        }

        // 历史周期没数据 → 明说一句，**不画全空网格**（否则会被当成加载失败，设计方案 §6.4）。
        // 抬头（含周期名与「更新于」）已经由上面 drawHeader 画好，用户仍知道自己在看哪一段。
        if (emptyNote != null) {
            drawCentered(c, (left + right) / 2f, h * 0.50f, emptyNote, SZ_EMPTY_FULL * unit, INK);
            return;
        }

        if (isMonthly()) {
            if (fullscreen) drawMonthFullBody(c, w, h, left, right, padY, ruleY);
            else            drawMonthBody(c, w, h, left, right, padY, ruleY);
        } else {
            drawWeekBody(c, w, h, left, right, padY, ruleY);
        }
    }

    /**
     * 抬头 + 右上更新时间 + 分隔线。三条分支（周 / 月 / 本书）共用。
     *
     * 抬头文字随形态变化（拍板 A）：`本周阅读时长` / `9月阅读` / `本书阅读进度`。
     *
     * v0.3.4 起两头各多一个小图标（拍板⑤）：
     * · **左**：两个反向小箭头 —— 表示"抬头这里点一下可以切换形态"；
     * · **右**：一段带箭头的圆弧 —— 表示"点这里刷新"。
     * 为什么要图标：墨水屏上没有涟漪、没有变色，光秃秃一行字用户不知道哪里能点。
     * 图标一律用 {@link Path} / 圆弧**画出来**，不用字符 —— 本机字体不一定带这些码位。
     *
     * @return 分隔线的 y（正文从这里往下排）
     */
    private float drawHeader(Canvas c, float w, float h) {
        float padX = w * padXRatio;
        float padY = h * 0.055f;
        float left = padX;
        float right = w - padX;

        float titleSize = SZ_TITLE * unit;
        float upSize = SZ_UPDATED * unit;
        String title = titleText();
        String up = refreshing ? "刷新中…" : updatedLabel();

        // 图标尺寸：跟着抬头字号走，再钳进 [10, 20] px —— 太小看不清，太大压过文字
        float icon = titleSize * 0.62f;
        if (icon < 10f) icon = 10f;
        if (icon > 20f) icon = 20f;
        float gapI = Math.max(3f, icon * 0.25f);

        p.setStyle(Paint.Style.FILL);
        p.setColor(INK);
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(true);
        p.setTextSize(titleSize);
        float titleW = p.measureText(title);

        float upW = 0f;
        // 抬头与右上时间不许打架：宽度不够就把时间字缩小（要扣掉两个图标占的位置）
        if (up.length() > 0) {
            p.setTextSize(upSize);
            upW = p.measureText(up);
            float avail = (right - left) - (icon + gapI) - titleW - (icon + gapI * 2f) - 10f;
            if (avail > 0 && upW > avail) {
                upSize = Math.max(SZ_UPDATED * unit * 0.62f, upSize * avail / upW);
                p.setTextSize(upSize);
                upW = p.measureText(up);
            }
        }

        float titleY = padY + titleSize;
        float iconTop = titleY - titleSize * 0.36f - icon / 2f;

        // 左：切换图标 + 抬头文字
        drawSwitchIcon(c, left, iconTop, icon);
        p.setTextSize(titleSize);
        c.drawText(title, left + icon + gapI, titleY, p);
        p.setFakeBoldText(false);

        // 右：更新时间（贴右沿）+ 刷新图标（在它左边）
        float iconX = right - icon;
        if (up.length() > 0) {
            p.setColor(GRAY);
            p.setTextSize(upSize);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(up, right, titleY, p);
            iconX = right - upW - gapI * 2f - icon;
        }
        drawRefreshIcon(c, iconX, iconTop, icon);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(INK);

        // 抬头下的分隔线：两端就是卡片的左右边缘
        float ruleY = titleY + padY * 0.30f;
        p.setStrokeWidth(2f);
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(left, ruleY, right, ruleY, p);
        p.setStyle(Paint.Style.FILL);
        return ruleY;
    }

    // ══════════════════════ 抬头两侧的小图标（v0.3.4） ══════════════════════

    /**
     * 「可切换」图标：上下两个反向的小箭头（⇄ 的意思），画在抬头**左边**。
     *
     * @param x 图标左边界，@param y 上边界，@param s 边长（正方形）
     */
    private void drawSwitchIcon(Canvas c, float x, float y, float s) {
        float cy1 = y + s * 0.30f;
        float cy2 = y + s * 0.72f;
        float head = s * 0.30f;          // 三角箭头的长度
        float half = s * 0.20f;          // 三角箭头的半高
        float x0 = x + s * 0.06f;
        float x1 = x + s * 0.94f;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.4f, s * 0.11f));
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(INK);

        // 上：指向右
        c.drawLine(x0, cy1, x1 - head * 0.6f, cy1, p);
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(x1, cy1);
        path.lineTo(x1 - head, cy1 - half);
        path.lineTo(x1 - head, cy1 + half);
        path.close();
        c.drawPath(path, p);

        // 下：指向左
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(x1, cy2, x0 + head * 0.6f, cy2, p);
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(x0, cy2);
        path.lineTo(x0 + head, cy2 - half);
        path.lineTo(x0 + head, cy2 + half);
        path.close();
        c.drawPath(path, p);

        p.setStyle(Paint.Style.FILL);
    }

    /**
     * 「刷新」图标：一段开口的圆弧 + 箭头，画在右上角「更新于…」**左边**。
     *
     * 圆弧从 30° 扫到 330°（顺时针，缺口留在正右方），箭头画在终点 330° 处、
     * 朝向切线方向 —— 这样缺口和箭头不会叠在一起。
     */
    private void drawRefreshIcon(Canvas c, float x, float y, float s) {
        float sw = Math.max(1.4f, s * 0.11f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setColor(INK);

        RectF arc = new RectF(x + sw / 2f, y + sw / 2f, x + s - sw / 2f, y + s - sw / 2f);
        c.drawArc(arc, 30f, 300f, false, p);

        // 终点 330° 处的箭头（顺时针方向的切线 = 角度 +90°）
        double a = Math.toRadians(330);
        double t = Math.toRadians(330 + 90);
        float cx = x + s / 2f, cy = y + s / 2f, r = s / 2f - sw / 2f;
        float px = cx + (float) (r * Math.cos(a));
        float py = cy + (float) (r * Math.sin(a));
        float dx = (float) Math.cos(t), dy = (float) Math.sin(t);
        float len = s * 0.30f, half = s * 0.19f;

        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(px + dx * len * 0.5f, py + dy * len * 0.5f);
        path.lineTo(px - dy * half - dx * len * 0.5f, py + dx * half - dy * len * 0.5f);
        path.lineTo(px + dy * half - dx * len * 0.5f, py - dx * half - dy * len * 0.5f);
        path.close();
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    // ══════════════════════ 周：7 根柱状图（原有实现，未改动） ══════════════════════

    private void drawWeekBody(Canvas c, float w, float h, float left, float right,
                              float padY, float ruleY) {
        // 「今天」只在**当前周**里存在。看历史周时 todayIdx 必须取 -1 ——
        // 否则 PeriodStats.todayIndex() 会把"整周已过去"钳成 6，周日被误画成今天（实心黑柱）。
        boolean isCur = stats.isCurrentPeriod();
        int todayIdx = isCur ? stats.todayIndex() : -1;

        // ── 主数字：本周总时长 ──
        float bigSize = SZ_BIG * unit;
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(bigSize);
        p.setFakeBoldText(true);
        String big = fmtTotal(stats.totalSec);
        float bigY = ruleY + bigSize + padY * 0.45f;
        c.drawText(big, (left + right) / 2f - p.measureText(big) / 2f, bigY, p);
        p.setFakeBoldText(false);

        // ── 7 天柱状图 ──
        float chartTop = h * 0.40f;
        float chartBottom = h * 0.78f;
        float chartH = chartBottom - chartTop;
        float slot = (right - left) / 7f;
        float barW = slot * 0.5f;

        int maxSec = 3600;
        int upto = (todayIdx >= 0) ? todayIdx : 6;         // 历史周：7 根柱一起参与定标
        for (int i = 0; i <= upto; i++) maxSec = Math.max(maxSec, stats.daySec[i]);

        float daySize = SZ_DAY * unit;

        for (int i = 0; i < 7; i++) {
            float cx = left + slot * i + slot / 2f;
            boolean future = isCur && i > todayIdx;        // 历史周：没有"未来"，7 天全画
            int sec = stats.daySec[i];

            if (!future) {
                float bh = chartH * (sec / (float) maxSec);
                if (sec > 0 && bh < h * 0.008f) bh = h * 0.008f;
                RectF bar = new RectF(cx - barW / 2f, chartBottom - bh, cx + barW / 2f, chartBottom);
                if (i == todayIdx) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(INK);
                    c.drawRect(bar, p);
                } else if (sec > 0) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xFFFFFFFF);
                    c.drawRect(bar, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(INK);
                    c.drawRect(bar, p);
                }
                if (sec > 0) {
                    String label = fmtShort(sec);
                    float barSize = SZ_BAR * unit;
                    p.setTextSize(barSize);
                    float lw = p.measureText(label);
                    float maxW = slot * 0.98f;
                    if (lw > maxW) {                  // 槽很窄，标长了就让一让，别压到邻槽
                        barSize = Math.max(SZ_BAR * unit * 0.7f, barSize * maxW / lw);
                        p.setTextSize(barSize);
                    }
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setColor(GRAY);
                    p.setStyle(Paint.Style.FILL);
                    c.drawText(label, cx, chartBottom - bh - barSize * 0.35f, p);
                }
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setColor(LIGHT);
                c.drawLine(cx - barW / 2f, chartBottom, cx + barW / 2f, chartBottom, p);
            }

            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(daySize);
            p.setColor(i == todayIdx ? INK : (future ? LIGHT : GRAY));
            c.drawText(DAY_NAMES[i], cx, chartBottom + daySize * 1.5f, p);
        }

        // ── 底部统计行（加粗；宽度不够时自动缩字号，绝不出框）──
        StringBuilder sb = new StringBuilder();
        sb.append("阅读 ").append(stats.readDays).append("/7 天");
        sb.append("  ·  日均 ").append(fmtShort(stats.avgSec));
        if (stats.compare != null && !stats.compare.isNaN()) {
            double v = stats.compare;
            sb.append("  ·  较上周 ").append(v >= 0 ? "↑" : "↓")
              .append(Math.round(Math.abs(v) * 100)).append("%");
        }
        drawBottomLine(c, (left + right) / 2f, h, padY, left, right, sb.toString());
    }

    // ══════════════════════ 月：日历打卡网格（v0.3.3 新增） ══════════════════════

    /**
     * 本月版式 = **左侧文字列 + 右侧日历打卡网格**（设计方案 §2.2）。
     *
     * 左侧（拍板 B：四行全留）：抬头（在 {@link #drawHeader}）/ 主数字 / 日均阅读 / 较上月。
     * 右侧：7 列 × 4–6 行的日历，格子三态（§3.1）：
     *   · 读过          → **实心黑方块 + 白色对勾**
     *   · 未读但已过去  → **细描边空框**（拍板 C：要画）
     *   · 未来          → **不画**（留白）
     * 今天的位置不用额外标记 —— "画到哪儿就停"本身就是进度（边界即今天）。
     */
    private void drawMonthBody(Canvas c, float w, float h, float left, float right,
                               float padY, float ruleY) {
        int dayCount = stats.dayCount;
        if (dayCount <= 0 || dayCount > 31) dayCount = 31;
        int todayIdx = stats.todayIndex();

        // ── 网格几何 ──
        float cell = CardSpec.MONTH_CELL;
        float gap = CardSpec.MONTH_GAP;
        float rowGap = CardSpec.MONTH_ROW_GAP;
        float gridW = CardSpec.MONTH_GRID_W;
        float gridRight = w - CardSpec.MONTH_GRID_RIGHT_PAD;
        float gridLeft = gridRight - gridW;
        float headSize = SZ_CAL_HEAD * unit;
        float headH = headSize * 1.35f;

        int firstOffset = PeriodRange.firstWeekdayIndex(stats.baseTime);
        int rows = (firstOffset + dayCount + 6) / 7;            // 上取整
        float gridH = headH + gap + rows * cell + (rows - 1) * rowGap;

        float contentTop = ruleY + padY * 0.55f;
        float contentBottom = h - padY * 0.90f;
        float gridTop = contentTop + Math.max(0f, (contentBottom - contentTop - gridH) / 2f);

        // ── 表头（一…日，不随月份变化）──
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(headSize);
        p.setColor(GRAY);
        float headY = gridTop + headSize;
        for (int i = 0; i < 7; i++) {
            float cx = gridLeft + i * (cell + gap) + cell / 2f;
            c.drawText(CAL_HEAD[i], cx, headY, p);
        }

        // ── 日期格 ──
        float cellTop0 = gridTop + headH + gap;
        for (int i = 0; i < dayCount; i++) {
            int slot = firstOffset + i;
            float x = gridLeft + (slot % 7) * (cell + gap);
            float y = cellTop0 + (slot / 7) * (cell + rowGap);
            boolean past = todayIdx >= 0 && i <= todayIdx;

            if (!past) continue;                                 // 未来 → 留白
            drawCalCell(c, x, y, cell, stats.daySec[i] > 0);
        }

        // ── 左栏：主数字 / 日均阅读 / 较上月 ──
        float leftW = CardSpec.MONTH_LEFT_W;
        String big = fmtTotal(stats.totalSec);
        float bigSize = SZ_BIG * unit;
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(INK);
        p.setFakeBoldText(true);
        p.setTextSize(bigSize);
        while (p.measureText(big) > leftW && bigSize > SZ_BIG * unit * 0.55f) {
            bigSize -= 0.5f;
            p.setTextSize(bigSize);
        }

        String l2 = "日均阅读 " + fmtTotal(stats.avgSec);
        String l3 = null;
        if (stats.compare != null && !stats.compare.isNaN()) {
            double v = stats.compare;
            l3 = "较上月 " + (v >= 0 ? "↑" : "↓") + Math.round(Math.abs(v) * 100) + "%";
        }
        float subSize = SZ_SUB * unit;
        float subLine = subSize * 1.45f;
        int lines = (l3 == null) ? 2 : 3;
        float totalH = bigSize + subLine * (lines - 1);
        float top = contentTop + Math.max(0f, (contentBottom - contentTop - totalH) / 2f);

        float baseBig = top + bigSize * 0.82f;
        c.drawText(big, left, baseBig, p);
        p.setFakeBoldText(false);
        p.setTextSize(subSize);
        p.setColor(GRAY);
        float base2 = baseBig + subLine;
        c.drawText(l2, left, base2, p);
        if (l3 != null) c.drawText(l3, left, base2 + subLine, p);

        // ── 底部统计行：本月只说"读了多少天"（日均/较上月已在左栏）──
        drawBottomLine(c, (left + right) / 2f, h, padY, left, right,
                "阅读 " + stats.readDays + "/" + dayCount + " 天");

        p.setColor(INK);
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ══════════════════════ 月：App 内全屏大版（v0.3.3 新增） ══════════════════════

    /**
     * App 内「本月」页 —— **纵向铺开的大版**（设计方案 §2.3 / §3.2）。
     *
     * 与桌面卡片版 {@link #drawMonthBody}（左右分栏、格 20px）的区别：
     * · **信息块在上**：主数字 46 号占左边，右列竖排「日均阅读 / 较上月 / 阅读 x/y 天」；
     * · **大号日历在下**：7 列占满可用宽度，格边长**目标 52px**（≈19px 表头）；
     * · 没有单独一行的底部统计（那条已经并进右列，把空间全让给日历）。
     *
     * ⚠️ 格边长是**算出来的**，不是写死 52：App 内这张卡片上方还有选项卡 + 选择器、
     * 下方还有刷新/设置按钮，实际可用高只有 ~510px（屏 800 − 状态栏 54 − 三条控件 ~290）。
     * 所以先按可用高度反算，再按宽度兜底，最后才受 {@link #MONTH_FULL_CELL} 上限约束 ——
     * 这样 5 行的月份能吃到 ~47px，6 行的月份自动降到 ~38px，**永远不会溢出**。
     */
    private void drawMonthFullBody(Canvas c, float w, float h, float left, float right,
                                   float padY, float ruleY) {
        int dayCount = stats.dayCount;
        if (dayCount <= 0 || dayCount > 31) dayCount = 31;
        int todayIdx = stats.todayIndex();
        float availW = right - left;

        // ── ① 信息块（上方）──
        String big = fmtTotal(stats.totalSec);
        float bigSize = SZ_BIG_FULL * unit;
        float top = ruleY + padY * 1.05f;

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(INK);
        p.setFakeBoldText(true);
        p.setTextSize(bigSize);
        float bigMaxW = availW * 0.52f;              // 右列要占剩下那半
        while (p.measureText(big) > bigMaxW && bigSize > SZ_BIG_FULL * unit * 0.45f) {
            bigSize -= 1f;
            p.setTextSize(bigSize);
        }
        c.drawText(big, left, top + bigSize, p);
        p.setFakeBoldText(false);

        // 右列竖排：日均阅读 / 较上月 / 阅读 x/y 天
        float subSize = SZ_SUB_FULL * unit;
        float subPitch = subSize * 1.34f;
        p.setTextSize(subSize);
        p.setColor(GRAY);
        float subX = left + availW * 0.54f;
        float subBase = top + subSize * 1.05f;
        c.drawText("日均阅读 " + fmtTotal(stats.avgSec), subX, subBase, p);
        int subLines = 1;
        if (stats.compare != null && !stats.compare.isNaN()) {
            double v = stats.compare;
            c.drawText("较上月 " + (v >= 0 ? "↑" : "↓") + Math.round(Math.abs(v) * 100) + "%",
                    subX, subBase + subPitch, p);
            subLines++;
        }
        c.drawText("阅读 " + stats.readDays + "/" + dayCount + " 天",
                subX, subBase + subPitch * subLines, p);
        subLines++;

        float blockBottom = Math.max(top + bigSize, subBase + subPitch * (subLines - 1));

        // ── ② 大号日历（自适应，永远装得下）──
        float gap = MONTH_FULL_GAP;
        float rowGap = MONTH_FULL_ROW_GAP;
        float headSize = SZ_CAL_HEAD_FULL * unit;
        float headH = headSize * 1.45f;

        int firstOffset = PeriodRange.firstWeekdayIndex(stats.baseTime);
        // 行数**只按"真正要画出来的那些天"排**（画到今天为止，未来是留白）。
        // 为什么不按整月行数：整月要 5 行、但今天才走到第 4 行时，末行整排是空的 ——
        // 既白留一条缝，格子还被生生压小（同一块高度，4 行能吃到 52px、5 行只剩 46px）。
        // 日历在这套设计里本来就是"进度条"（边界即今天），画到哪儿就排到哪儿更自洽。
        int lastDrawn = (todayIdx >= 0) ? Math.min(dayCount - 1, todayIdx) : 0;
        int rows = (firstOffset + lastDrawn + 1 + 6) / 7;            // 上取整
        int monthRows = (firstOffset + dayCount + 6) / 7;
        if (rows < 1) rows = 1;
        if (rows > monthRows) rows = monthRows;

        float calTop = blockBottom + padY * 1.05f;
        float calBottom = h - padY * 0.85f;
        float availH = calBottom - calTop;

        float cell = (availH - headH - gap - (rows - 1) * rowGap) / rows;   // 高度约束
        float byW = (availW - (7 - 1) * gap) / 7f;                          // 宽度约束
        if (cell > byW) cell = byW;
        if (cell > MONTH_FULL_CELL) cell = MONTH_FULL_CELL;
        if (cell < 20f) cell = 20f;                                         // 兜底下限

        float gridW = 7f * cell + 6f * gap;
        float gridH = headH + gap + rows * cell + (rows - 1) * rowGap;
        float gridLeft = left + (availW - gridW) / 2f;
        float gridTop = calTop + Math.max(0f, (availH - gridH) / 2f);

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(headSize);
        p.setColor(GRAY);
        float headY = gridTop + headSize;
        for (int i = 0; i < 7; i++) {
            float cx = gridLeft + i * (cell + gap) + cell / 2f;
            c.drawText(CAL_HEAD[i], cx, headY, p);
        }

        float cellTop0 = gridTop + headH + gap;
        for (int i = 0; i < dayCount; i++) {
            if (!(todayIdx >= 0 && i <= todayIdx)) continue;         // 未来 → 留白
            int slot = firstOffset + i;
            float x = gridLeft + (slot % 7) * (cell + gap);
            float y = cellTop0 + (slot / 7) * (cell + rowGap);
            drawCalCell(c, x, y, cell, stats.daySec[i] > 0);
        }

        p.setColor(INK);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * 日历格里"读过 / 未读但已过去"这两态的画法。卡片版与全屏版共用。
     *
     * 半径一律**按格子尺寸缩放**，所以两处的观感一致：
     * · 卡片 20px → 描边 1.5px、内缩 0.75px（与 v0.3.3 第一版**逐像素相同**，没动）；
     * · 全屏 47px → 描边 2.6px、内缩 1.6px。
     */
    private void drawCalCell(Canvas c, float x, float y, float cell, boolean read) {
        if (read) {                                   // 读过 → 实心黑方块 + 白色对勾
            p.setStyle(Paint.Style.FILL);
            p.setColor(INK);
            c.drawRect(x, y, x + cell, y + cell, p);
            drawCheck(c, x, y, cell);
        } else {                                      // 未读（已过去）→ 细描边空框
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, cell * 0.055f));
            p.setColor(GRAY);
            float in = Math.max(0.75f, cell * 0.035f);
            c.drawRect(x + in, y + in, x + cell - in, y + cell - in, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    /** 格子里的白色对勾。20px 的格子只能用细笔画，否则糊成一团（§3.2） */
    private void drawCheck(Canvas c, float x, float y, float cell) {
        float sw = Math.max(1.6f, cell * 0.10f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0xFFFFFFFF);
        path.reset();
        path.moveTo(x + cell * 0.24f, y + cell * 0.52f);
        path.lineTo(x + cell * 0.44f, y + cell * 0.72f);
        path.lineTo(x + cell * 0.78f, y + cell * 0.30f);
        c.drawPath(path, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.MITER);
        p.setStyle(Paint.Style.FILL);
    }

    /** 底部统计行（加粗居中，宽度不够自动缩字号，绝不出框）。两条分支共用 */
    private void drawBottomLine(Canvas c, float cx, float h, float padY,
                                float left, float right, String text) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(INK);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        float bSize = SZ_BOTTOM * unit;
        p.setTextSize(bSize);
        float availW = right - left;
        while (p.measureText(text) > availW && bSize > SZ_BOTTOM_MIN * unit) {
            bSize -= 0.4f;
            p.setTextSize(bSize);
        }
        c.drawText(text, cx, h - padY * 0.85f, p);
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ══════════════════════ 本书：阅读进度（v0.3.4 新增） ══════════════════════

    /**
     * 「本书」版式 —— **方案 A「左封右文」**（v0.3.5.1 用户拍板，替代 v0.3.4 的
     * "书名一行 + 三列小字 + 进度条"纯文字版）：
     *
     * <pre>
     *   ┌────────────────────────────────┐
     *   │ ⇄ 本书阅读进度        ⟳ 更新于… │   ← 抬头（两个小图标，见 drawHeader）
     *   ├────────────────────────────────┤
     *   │ ┌──────┐  卡拉马佐夫兄弟        │   ← 封面左立（比例 250:360）
     *   │ │ 封面  │  （套装上下册）…      │   ← 书名折行两行，不再缩字
     *   │ │      │  第三卷 酒色之徒      │   ← 章节名灰字
     *   │ └──────┘  9小时30分       16%  │   ← 时长左 / 百分比右
     *   │ ▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░  │   ← 进度条全宽，贴近「打开」
     *   │                       ┌──────┐ │
     *   │                       │ 打开 │ │   ← 右下角描边按钮（跳微信读书）
     *   └────────────────────────────────┘
     * </pre>
     *
     * 封面来自 {@link CoverStore}（书架数据自带 CDN 直链，按书落盘缓存）；
     * 没拿到时画描边占位框，不阻塞渲染。
     */
    private void drawBookBody(Canvas c, float w, float h, float left, float right,
                              float padY, float ruleY) {
        float availW = right - left;

        // ── 没数据 / 出错 ──
        if (book == null) {
            if (errorText != null) {
                drawCentered(c, (left + right) / 2f, h * 0.40f, "获取失败", SZ_EMPTY * unit, INK);
                wrapCentered(c, (left + right) / 2f, h * 0.50f, availW * 0.96f, errorText,
                        SZ_SUB * unit, GRAY);
                drawCentered(c, (left + right) / 2f, h * 0.66f,
                        "点" + (fullscreen ? "下方「刷新」" : "右上角") + "重试", SZ_SUB * unit, GRAY);
            } else {
                drawCentered(c, (left + right) / 2f, h * 0.46f,
                        refreshing ? "正在获取本书进度…" : "暂无数据，点右上角刷新",
                        SZ_SUB * unit, GRAY);
            }
            return;
        }

        // ══ 方案 A「左封右文」（v0.3.5.1，用户拍板）══
        //   ┌────────────────────────────────┐
        //   │ ⇄ 本书阅读进度        ⟳ 更新于… │
        //   ├────────────────────────────────┤
        //   │ ┌──────┐  卡拉马佐夫兄弟        │   ← 封面左立；书名右侧折行两行（不再缩字）
        //   │ │ 封面  │  （套装上下册）…      │
        //   │ │      │  陀思妥耶夫斯基        │   ← 作者，一行小灰字（过长截断）
        //   │ │      │                      │
        //   │ └──────┘  第三卷 酒色之徒      │   ← 章节名灰字，贴文字区底部
        //   │ └──────┘  9小时30分       16%  │   ← 时长(左) + 百分比(右)，粗体
        //   │ ▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░  │   ← 进度条全宽，下移贴近「打开」
        //   │                       ┌──────┐ │
        //   │                       │ 打开 │ │
        //   └────────────────────────────────┘
        // 封面宽高比 250:360（t6 规格实测）。尺寸用「号」，两档（卡片/全屏）各自取值。

        float covW = (fullscreen ? BOOK_COVER_W_FULL : BOOK_COVER_W) * unit;
        float covH = covW * 360f / 250f;
        float covY = ruleY + padY * 0.6f;
        float covGap = (fullscreen ? BOOK_COVER_GAP_FULL : BOOK_COVER_GAP) * unit;
        float tx = left + covW + covGap;               // 右侧文字区左缘
        float textW = right - tx;
        float covBottom = covY + covH;
        drawCover(c, left, covY, covW, covH);

        // ── ① 书名：右区最多两行，逐字折行，放不下加省略号 ──
        float tSize = (fullscreen ? SZ_BOOK_TITLE_FULL : SZ_BOOK_TITLE) * unit;
        String name = (book.title == null || book.title.length() == 0) ? "(未命名)" : book.title;
        String[] lines = wrapLines(name, textW, 2, tSize);
        p.setStyle(Paint.Style.FILL);
        p.setColor(INK);
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(true);
        p.setTextSize(tSize);
        float by = covY + tSize * 1.05f;
        c.drawText(lines[0], tx, by, p);
        float lastBase = by;
        if (lines[1] != null) {
            lastBase = by + tSize * 1.45f;
            c.drawText(lines[1], tx, lastBase, p);
        }
        p.setFakeBoldText(false);

        // ── ② 作者：书名正下方一行小灰字（v0.3.5.2）──
        // 只画一行、过长截断加「…」。没有作者数据（早期缓存/接口缺失）就整行跳过，
        // 后面的章节名照常顶上去 —— 宁可少一行，也不留一个空行把版面撑散。
        String author = (book.author == null) ? "" : book.author.trim();
        float aSize = (fullscreen ? SZ_BOOK_AUTHOR_FULL : SZ_BOOK_AUTHOR) * unit;
        float authY = lastBase + aSize * 1.9f;
        if (author.length() > 0) {
            // 撞车保险：作者行不许压到章节名上（章节名基线在 covBottom - dSize*1.75）
            float limit = (covBottom - (fullscreen ? SZ_BOOK_DUR_FULL : SZ_BOOK_DUR) * unit * 1.75f)
                    - aSize * 1.3f;
            if (authY > limit) authY = limit;
            p.setColor(GRAY);
            p.setTextSize(aSize);
            c.drawText(ellipsize(author, textW), tx, authY, p);
        }

        // ── ③ 章节名：灰字，贴文字区底部（上方留出时长行）──
        float dSize = (fullscreen ? SZ_BOOK_DUR_FULL : SZ_BOOK_DUR) * unit;
        float chSize = (fullscreen ? SZ_BOOK_CH_FULL : SZ_BOOK_CH) * unit;
        String ch = (book.chapterTitle == null || book.chapterTitle.length() == 0)
                ? "—" : book.chapterTitle;
        p.setColor(GRAY);
        p.setTextSize(chSize);
        if (p.measureText(ch) > textW) ch = ellipsize(ch, textW);
        c.drawText(ch, tx, covBottom - dSize * 1.75f, p);

        // ── ③ 时长（左，灰）+ 百分比（右，黑粗）—— 与封面底对齐 ──
        String dur = fmtTotal(book.readingSec);
        String pct = book.percent() + "%";
        p.setTextSize(dSize);
        c.drawText(dur, tx, covBottom, p);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setColor(INK);
        p.setFakeBoldText(true);
        c.drawText(pct, right, covBottom, p);
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.LEFT);

        // ── ⑤ 进度条：全宽，横贯封面下方，贴近「打开」按钮收底 ──
        float barH = (fullscreen ? BOOK_BAR_H_FULL : BOOK_BAR_H) * unit;
        float barTop = h - CardSpec.OPEN_BOX_H - CardSpec.OPEN_BOX_MARGIN_B
                - padY * 0.9f - barH;
        float sw = Math.max(1.6f, barH * 0.13f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setColor(INK);
        float o = sw / 2f;
        c.drawRect(left + o, barTop + o, right - o, barTop + barH - o, p);
        p.setStyle(Paint.Style.FILL);

        float pad = Math.max(2f, barH * 0.20f);
        float x0 = left + sw + pad;
        float x1 = right - sw - pad;
        float yA = barTop + sw + pad;
        float yB = barTop + barH - sw - pad;
        if (x1 > x0 && yB > yA) {
            float fillW = (x1 - x0) * book.percent() / 100f;
            if (fillW > 0.5f) {
                p.setColor(INK);
                c.drawRect(x0, yA, x0 + fillW, yB, p);
                // 填充分界线：进度 100% 时不画（整条都是黑的，画了反而像多一条缝）
                if (fillW < x1 - x0 - 1f) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    c.drawLine(x0 + fillW, yA - pad, x0 + fillW, yB + pad, p);
                    p.setStyle(Paint.Style.FILL);
                }
            }
        }

        // ── ⑤ 右下角「打开」按钮（跳微信读书）──
        drawOpenBox(c, left, right, h, OPEN_LABEL);
    }

    // ══════════════════════ 本记（v0.4.0）══════════════════════

    /**
     * 「本记」版式：
     *
     * <pre>
     *   ┌────────────────────────────────┐
     *   │ ⇄ 本记 · 今日一签      ⟳ 更新于 │
     *   ├────────────────────────────────┤
     *   │ “ 美这个东西不但可怕，而且神秘。  │   ← 衬线正文，行数按可用高度算
     *   │   围绕着这事儿，上帝与魔鬼在那里  │
     *   │   搏斗，战场便在人们心中。       │
     *   │                              │
     *   │ 卡拉马佐夫兄弟  陀思妥耶夫斯基   │   ← 书名(粗) + 作者(灰)
     *   │ 第三卷 酒色之徒 · 2026-09-22   │   ← 章节 · 划线日期
     *   │ ┌────────┐   第3 / 共320条 ┌────────┐│  ← v0.4.2 进度塞在按钮之间
     *   │ │ 上一条  │                │ 换一条 ││
     *   │ └────────┘                └────────┘│
     *   └────────────────────────────────┘
     * </pre>
     *
     * 字数溢出三步走：① 按可用高度决定最多几行 → ② 逐字折行（{@link #wrapLines}）
     * → ③ 最后一行仍放不下就截断加「…」。长划线最多看不全，但绝不会压到署名。
     */
    /**
     * 「本记」正文（v0.4.0 起；v0.4.4 改成**两段式**并支持滚动）。
     *
     * 一条内容可能是三种形态：
     *   · **纯划线** —— 只有原文；
     *   · **带想法的划线** —— 原文 + 用户自己写的那段（{@link NoteStore#pool} 按
     *     (bookId, range) 把两份记录配对合并成了一条）；
     *   · **独立想法** —— 只有想法（整本书评 / 章节点评，接口没给对应原文，占 8%）。
     *
     * 两段之间画一个小字「想法」，把"书里的话"和"我的话"分开 ——
     * 墨水屏是纯黑白，靠颜色区分不可能，靠字号又会读起来跳，加个标记最省事。
     *
     * 字号分档的用户拍板分界是 188/250/304（沿用 v0.4.1），但**字数口径变了**：
     * 取 {@link NoteStats#displayChars()}（原文 + 想法 + 小标开销），
     * 否则带想法的条目会被分到过大的字号、一屏塞不下。
     *
     * ── 滚动（v0.4.4）──
     * 卡片档（桌面）：两段各自限行（原文 2 行 / 想法 4 行），**装不下就逐级往下收紧**，
     * 保证永远不压到署名。桌面卡片是"瞥一眼"，不给它滚动这种需要手指的动作。
     * App 全屏档：不限行数，正文段**可纵向滚动**（自绘，见 {@link #noteTouch}）；
     * 抬头、署名、末行、进度、按钮全部**固定**，滑到哪儿都点得到「换一条」。
     */
    private void drawNoteBody(Canvas c, float w, float h, float left, float right,
                              float padY, float ruleY) {
        float availW = right - left;

        if (note == null) {
            // 空态分三种，必须让用户分得清"在忙 / 出错了 / 真的没有"（v0.5.3，R02/R08）——
            // 上一版只有"正在同步"与"本记还没有准备好"两种，导致"Key 失效"与"新账号"
            // 都只能得到同一句含糊的话，用户唯一的动作是反复点刷新（而旧逻辑会把它打成循环）。
            String what = noteIdeasSlot ? "想法" : "划线笔记";
            String main, sub;
            if (refreshing) {
                main = "正在同步" + what + "…";
                sub = noteIdeasSlot
                        ? "第一次要拉 55 本书的想法，几秒就好"
                        : "第一次要同步 228 本书的笔记，稍等一会儿";
            } else if (noteHint != null) {
                main = noteHint;
                sub = "点右上角 ⟳ 或下方「刷新」再试一次";
            } else {
                main = "本记还没有准备好";
                sub = "点右上角 ⟳ 开始同步";
            }
            drawCentered(c, (left + right) / 2f, h * 0.42f, main, SZ_EMPTY * unit, INK);
            wrapCentered(c, (left + right) / 2f, h * 0.42f + SZ_EMPTY * unit * 1.9f,
                    availW * 0.9f, sub, SZ_SUB * unit, GRAY);
            noteContentH = 0f;
            noteViewH = 0f;
            noteScrollMax = 0f;
            // 空态也要画按钮行 —— 「只看想法」没内容时，用户唯一的出路是点「筛选」切回「全部」
            drawNoteButtons(c, h, left, right);
            return;
        }

        float qSize = (fullscreen ? SZ_NOTE_QUOTE_FULL : SZ_NOTE_QUOTE) * unit;
        // ── 字号分档：短句大字、长句小字；字数口径 = 原文 + 想法（v0.4.4）──
        int chars = note.displayChars();
        float tNum;
        if (fullscreen) {
            tNum = NOTE_FULL_SIZES[NOTE_FULL_SIZES.length - 1];      // 最长尾档 14 号
            for (int i = 0; i < NOTE_FULL_STEPS.length; i++) {
                if (chars <= NOTE_FULL_STEPS[i]) { tNum = NOTE_FULL_SIZES[i]; break; }
            }
        } else {
            tNum = (chars <= NOTE_CARD_BIG_CHARS) ? SZ_NOTE_CARD_BIG : SZ_NOTE_TEXT;
        }
        float tSize = tNum * unit;
        float tagSize = (fullscreen ? SZ_NOTE_IDEA_TAG_FULL : SZ_NOTE_IDEA_TAG) * unit;
        float tiSize = (fullscreen ? SZ_NOTE_TITLE_FULL : SZ_NOTE_TITLE) * unit;
        float auSize = (fullscreen ? SZ_NOTE_AUTHOR_FULL : SZ_NOTE_AUTHOR) * unit;
        float mSize = (fullscreen ? SZ_NOTE_META_FULL : SZ_NOTE_META) * unit;

        // 底部信息行的位置：贴着底部按钮行往上排，短句也不会飘。
        // v0.4.2：App 全屏档多一行进度（第 N / 共 M 条），所以 meta/sign 再往上让一行；
        // 卡片档把进度塞进左右两个按钮之间的空隙，不额外占行。
        float boxTop = h - boxH() - boxMarginB();
        float progY = 0f;
        float metaY;
        if (fullscreen) {
            progY = boxTop - padY * 0.35f;
            metaY = progY - mSize * 1.9f;
        } else {
            metaY = boxTop - padY * 0.35f;
        }
        float signY = metaY - mSize * 1.9f;

        // 正文区：抬头分隔线以下 ~ 署名以上
        float textTop = ruleY + padY * 0.75f;
        float bodyBottom = signY - tSize * 1.4f;
        float bodyH = bodyBottom - textTop;
        if (bodyH < tSize * 1.6f) bodyH = tSize * 1.6f;

        boolean hasQuote = note.markText != null && note.markText.trim().length() > 0;

        // ── ① 大引号（只在有原文时画 —— 独立想法没有"引文"可引）──
        p.setStyle(Paint.Style.FILL);
        p.setColor(LIGHT);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(android.graphics.Typeface.SERIF);
        p.setTextSize(qSize);
        float quoteW = p.measureText("\u201c");
        if (hasQuote) {
            c.drawText("\u201c", left, textTop + qSize * 0.78f, p);
        }

        // ── ② 排版（原文段 + 想法段），两段共用同一套折行 ──
        float tx = hasQuote ? left + quoteW * 0.85f : left;
        float textW = right - tx;
        int maxQ, maxI;
        if (fullscreen) {
            maxQ = 0;
            maxI = 0;                       // 不限行数：超出屏幕的部分靠滚动看
        } else {
            // 卡片档：**先算这块地方能放几行，再按两段各自的需要量分配**（v0.4.5）。
            //
            // ⚠️ 不能再用固定的「原文 2 行 / 想法 4 行」：全库 5162 条划线里绝大多数
            // 根本没有想法，固定 2 行会让正文只占 2×33 ≈ 67px，而正文区可用高 177px
            // —— **110px（卡片高度的三分之一）白空着**。真机截图逐行量过：
            // 正文两行落在卡片内 y 64…121，下一处墨迹要等到 y 254 的署名行。
            NoteBody nat = layoutNote(note.markText, note.ideaText, textW, tSize, tagSize, 0, 0);
            int qn = nat.quote.length, iv = nat.idea.length;
            int fit = (int) (Math.floor(bodyH / nat.lineH + 1e-3));
            if (fit < 1) fit = 1;
            if (iv == 0) {
                // 纯划线（绝大多数）：能放几行就放几行，把这一整块填满
                maxQ = Math.max(NOTE_CARD_QUOTE_MIN, Math.min(qn, fit));
                maxI = 0;
            } else if (qn == 0) {
                // 没有原文的整本书评/章节点评：整块都给想法（「想法」小标仍占一行）
                maxQ = 0;
                maxI = Math.max(NOTE_CARD_IDEA_MIN, Math.min(iv, fit - 1));
            } else {
                int budget = fit - 1;                 // 「想法」小标占一整行
                if (budget < 2) budget = 2;
                if (qn + iv <= budget) {
                    maxQ = qn;                        // 两段都放得下 → 不截断
                    maxI = iv;
                } else {
                    // 按两段各自的需要量按比例分；想法至少留 2 行 —— 那是用户亲手写的字
                    int iCap = Math.round(budget * (float) iv / (qn + iv));
                    if (iCap < NOTE_CARD_IDEA_MIN) iCap = NOTE_CARD_IDEA_MIN;
                    if (iCap > budget - 1) iCap = budget - 1;
                    if (iCap < 1) iCap = 1;
                    maxI = Math.min(iv, iCap);
                    maxQ = Math.max(NOTE_CARD_QUOTE_MIN, Math.min(qn, budget - maxI));
                    if (maxQ + maxI > budget) maxI = Math.max(1, budget - maxQ);
                }
            }
        }
        NoteBody body = layoutNote(note.markText, note.ideaText, textW, tSize, tagSize, maxQ, maxI);

        // 卡片档：限行后仍可能超高（长句配大字号），逐级收紧到装得下为止
        if (!fullscreen) {
            int guard = 0;
            while (body.height > bodyH && guard++ < 8 && (maxQ > 1 || maxI > 1)) {
                if (maxI > 1) maxI--;
                else maxQ--;
                body = layoutNote(note.markText, note.ideaText, textW, tSize, tagSize, maxQ, maxI);
            }
        }

        // ── ③ 画正文：App 全屏档超出一屏时可滚动（clip + 位移）──
        // 可视高与滚动量都**取整到整行**：裁剪边界因此永远落在行边界上，两端都不会切字。
        noteContentH = body.height;
        noteLineH = body.lineH;
        // 可视高向下取整（宁可少显示一整行，也不能把一行切一半）。
        // +1e-3 是防浮点：恰好整除时 floor(7.999999) 会少算一整行。
        noteViewH = (float) (Math.floor(bodyH / body.lineH + 1e-3) * body.lineH);
        if (noteViewH < body.lineH) noteViewH = body.lineH;
        float maxScroll = noteContentH - noteViewH;
        if (maxScroll < 0f) maxScroll = 0f;
        // 滚动量则必须**四舍五入**到整行，绝不能向下取整：
        // contentH 与 viewH 都是 lineH 的整数倍，差天然是整数倍，但浮点除法会算出
        // 2.9999999，floor 掉就变成 2 行 —— 窗口底边停在内容最后一行的上方，
        // 那一行**永远滚不出来**（v0.4.4 首包实测：「》十三章」看不到）。
        int scrollLines = Math.round(maxScroll / body.lineH);
        if (scrollLines < 0) scrollLines = 0;
        maxScroll = scrollLines * body.lineH;
        noteScrollMax = maxScroll;
        if (noteScrollY > maxScroll) noteScrollY = maxScroll;
        if (noteScrollY < 0f) noteScrollY = 0f;
        float sy = noteScrollY;

        c.save();
        c.clipRect(left - 2f, textTop - 2f, right + 2f, textTop + noteViewH + 2f);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(android.graphics.Typeface.SERIF);
        p.setColor(INK);
        p.setTextSize(tSize);
        float ly = textTop + tSize - sy;
        for (int i = 0; i < body.quote.length; i++) {
            c.drawText(body.quote[i], tx, ly, p);
            ly += body.lineH;
        }
        if (body.idea.length > 0) {
            float segEnd = textTop + body.quote.length * body.lineH - sy;
            // 「想法」小标：小字灰色，坐在两段之间的间隔里
            p.setTypeface(android.graphics.Typeface.DEFAULT);
            p.setColor(GRAY);
            p.setTextSize(tagSize);
            c.drawText(IDEA_TAG, tx, segEnd + body.lineH * 0.75f, p);
            // 想法正文
            p.setTypeface(android.graphics.Typeface.SERIF);
            p.setColor(INK);
            p.setTextSize(tSize);
            float iy = segEnd + body.tagBlock + tSize;
            for (int i = 0; i < body.idea.length; i++) {
                c.drawText(body.idea[i], tx, iy, p);
                iy += body.lineH;
            }
        }
        p.setTypeface(android.graphics.Typeface.DEFAULT);
        c.restore();

        // ── ④ 滚动条：只在真的超出一屏时出现，贴最右侧，不抢文字 ──
        if (maxScroll > 0f) {
            float barH = Math.max(24f, noteViewH * noteViewH / noteContentH);
            float barY = textTop + (noteViewH - barH) * (noteScrollY / maxScroll);
            p.setColor(0xFFB8B8B8);
            c.drawRect(right - 3f, barY, right, barY + barH, p);
        }

        // ── ⑤ 署名：书名(粗) + 作者(灰)。书名放不下作者时（v0.4.1 拍板④）改两行：
        //     书名独占署名行，作者下移与章节·日期同排 ──
        String title = (note.title == null || note.title.length() == 0) ? "(未命名)" : note.title;
        String author = (note.author == null) ? "" : note.author.trim();
        boolean dropAuthor = false;                      // 作者是否下移到末行
        if (author.length() > 0) {
            p.setFakeBoldText(true);
            p.setTextSize(tiSize);
            float titleW = p.measureText(title);
            p.setFakeBoldText(false);
            p.setTextSize(auSize);
            float authorW = p.measureText(author);
            if (titleW + auSize * 0.8f + authorW > availW) {
                dropAuthor = true;
                if (titleW > availW) title = ellipsize(title, availW);
            }
        }
        p.setColor(INK);
        p.setFakeBoldText(true);
        p.setTextSize(tiSize);
        c.drawText(title, left, signY, p);
        p.setFakeBoldText(false);
        if (author.length() > 0 && !dropAuthor) {
            float keep = p.measureText(title);           // 书名当前实际宽度
            p.setTextSize(auSize);
            p.setColor(GRAY);
            c.drawText(author, left + keep + auSize * 0.7f, signY, p);
            p.setColor(INK);
        }

        // ── ⑥ 末行：作者(下移时) · 章节 · 日期 ──
        String ch = (note.chapterTitle == null || note.chapterTitle.length() == 0)
                ? "第 " + (note.chapterIdx + 1) + " 章" : note.chapterTitle;
        String meta = (dropAuthor ? author + " · " : "")
                + ch + (note.dateText().length() > 0 ? " · " + note.dateText() : "");
        // 想法的来源标记：让人一眼看出这条是"我说的话"而不是"我划的线"
        if (note.hasIdea()) meta = meta + " · 想法";
        p.setColor(GRAY);
        p.setTextSize(mSize);
        if (p.measureText(meta) > availW) meta = ellipsize(meta, availW);
        c.drawText(meta, left, metaY, p);
        p.setColor(INK);

        // ── ⑦ 底部按钮行 ──
        // 左下「上一条」↔ 右下「换一条」，左右对称。
        // App 全屏档空间宽裕 → 三个按钮等分排开（上一条 / 换一条 / 导出）；
        // 卡片档按钮区只有 368 宽 → 保持左右各一个（导出只在 App 里出现）
        drawNoteButtons(c, h, left, right);

        // ── ⑧ 进度：第 N / 共 M 条（v0.4.2，池内序号 —— 与"换一条/上一条"同步增减）──
        int[] pr = NoteStore.progress(getContext(), noteIdeasSlot);
        if (pr != null) {
            String ps = "第 " + pr[0] + " / 共 " + pr[1] + " 条";
            p.setStyle(Paint.Style.FILL);
            p.setColor(GRAY);
            p.setTextSize(mSize);
            p.setTextAlign(Paint.Align.CENTER);
            if (fullscreen) {
                c.drawText(ps, (left + right) / 2f, progY, p);
            } else {
                // 卡片档：塞进左右两个按钮之间的空隙，与按钮垂直同轴
                Paint.FontMetrics fmP = p.getFontMetrics();
                c.drawText(ps, (left + right) / 2f,
                        boxTop + boxH() / 2f - (fmP.descent + fmP.ascent) / 2f, p);
            }
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(INK);
        }
    }

    /**
     * 画封面（v0.3.5.1 方案 A）。
     *
     * 有位图 → drawBitmap（源图 250×360 经下采样 + 双线性过滤缩到目标框）；
     * 没拿到（离线 / 下载中 / 无 cover 字段）→ 描边占位框 + 灰色「封面」二字，
     * **绝不因此阻塞卡片渲染** —— CoverStore 异步拿到图后会再回调一次重绘。
     * 外围统一加一圈黑描边，让浅色封面在白卡上有明确边界。
     */
    private void drawCover(Canvas c, float x, float y, float cw, float ch) {
        boolean has = coverBmp != null && book != null
                && book.bookId != null && book.bookId.equals(coverBmpId);
        if (has) {
            bmpDst.set(x, y, x + cw, y + ch);
            c.drawBitmap(coverBmp, null, bmpDst, bmpPaint);
        } else {
            p.setStyle(Paint.Style.FILL);
            p.setColor(LIGHT);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(13f * unit);
            c.drawText("封面", x + cw / 2f, y + ch / 2f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(INK);
        c.drawRect(x + 0.8f, y + 0.8f, x + cw - 0.8f, y + ch - 0.8f, p);
        p.setStyle(Paint.Style.FILL);
    }

    /**
     * 逐字折行（中文友好：不用空格断词）。最多 maxLines 行，
     * 最后还放不下的部分截断加「…」。返回数组长度恒为 maxLines，空位为 null。
     * 调用前 p 的字号会被本方法设为 size。
     */
    private String[] wrapLines(String text, float maxW, int maxLines, float size) {
        p.setTextSize(size);
        String[] out = new String[maxLines];
        if (p.measureText(text) <= maxW) {
            out[0] = text;
            return out;
        }
        float ellW = p.measureText("…");
        int n = text.length(), start = 0, line = 0;
        for (int i = 1; i <= n && line < maxLines; i++) {
            if (p.measureText(text, start, i) <= maxW) continue;
            if (line == maxLines - 1) {
                // 已是最后一行：剩余文字放不下 → 回退几个字换省略号
                int e = i - 1;
                while (e > start && p.measureText(text, start, e) + ellW > maxW) e--;
                out[line] = text.substring(start, e) + "…";
                return out;
            }
            out[line++] = text.substring(start, i - 1);
            start = i - 1;
        }
        if (line < maxLines && start < n) out[line] = text.substring(start);
        return out;
    }

    // ══════════════════════ 本记正文排版（v0.4.4，屏幕与导出共用）══════════════════════

    /**
     * 本记正文的排版结果：**原文段 + 想法段**两段。
     *
     * 屏幕绘制（{@link #drawNoteBody}）与长图导出（{@link NoteExport}）**共用**这个结果 ——
     * 折行口径只要有一处不同，导出的图就会和在屏幕上看到的不一样。
     */
    static final class NoteBody {
        /** 原文行（已按需截断） */
        String[] quote = new String[0];
        /** 想法行 */
        String[] idea = new String[0];
        float lineH;        // 行高（两段同字号）
        float tagSize;      // 「想法」小标的字号
        float tagBlock;     // 两段之间的竖向间隔（含小标那一行）
        float height;       // 正文总高
    }

    /**
     * 排版本记正文（包级可见 —— {@link NoteExport} 复用同一套折行）。
     *
     * @param quote  原文（可为空：整本书评/章节点评没有原文）
     * @param idea   想法（可为空：纯划线）
     * @param textW  正文可用宽度（px，已扣掉大引号）
     * @param tSize  正文字号（px）
     * @param tagSize「想法」小标字号（px）
     * @param maxQ   原文最多几行（≤0 = 不限）
     * @param maxI   想法最多几行（≤0 = 不限）
     */
    static NoteBody layoutNote(String quote, String idea, float textW, float tSize,
                               float tagSize, int maxQ, int maxI) {
        NoteBody b = new NoteBody();
        b.lineH = tSize * 1.55f;
        b.tagSize = tagSize;
        // 段间间隔**取一整行**（而不是 tagSize 的倍数）—— 这样"想法"段的每一行
        // 与原文段一样落在 lineH 的整数倍上，滚动裁剪才能永远压在行边界。
        // 「想法」小标就画在这一行的空位里。
        b.tagBlock = b.lineH;

        boolean hasQ = quote != null && quote.trim().length() > 0;
        boolean hasI = idea != null && idea.trim().length() > 0;
        float y = 0f;
        if (hasQ) {
            b.quote = (maxQ <= 0) ? wrapAll(quote.trim(), textW, tSize)
                    : wrapMax(quote.trim(), textW, maxQ, tSize);
            y += b.quote.length * b.lineH;
        }
        if (hasI) {
            // 🔴 **只要画想法，就先占下「想法」小标那一行**（v0.5.3，R09）——
            // 上一版写成 `if (hasQ) y += b.tagBlock;`：没有原文的独立想法（整本书评 /
            // 章节点评，占全库想法的约 59%）排版时少算一整行，而绘制侧
            // （{@link #drawNoteBody} 的 `segEnd + tagBlock + tSize`）**总是**先留出小标行。
            // 结果：屏幕滚动上限少一行 → 长独立想法的最后一行滚不出来；导出图矮一截、
            // 末行被页脚压住。判定必须与绘制同源：**有没有想法**，而不是有没有原文。
            y += b.tagBlock;
            b.idea = (maxI <= 0) ? wrapAll(idea.trim(), textW, tSize)
                    : wrapMax(idea.trim(), textW, maxI, tSize);
            y += b.idea.length * b.lineH;
        }
        b.height = y;
        return b;
    }

    /** 折行，**不限行数**（返回实际行数组，无 null 尾）—— 给 App 全屏档与长图导出用 */
    static String[] wrapAll(String text, float maxW, float size) {
        Paint mp = measurePaint(size);
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        int n = text.length(), start = 0;
        while (start < n) {
            int i = Math.min(start + 1, n);
            while (i <= n && mp.measureText(text, start, i) <= maxW) i++;
            int end = i - 1;
            if (end <= start) end = start + 1;        // 单个超宽字符也硬放
            out.add(text.substring(start, end));
            start = end;
        }
        return out.toArray(new String[out.size()]);
    }

    /**
     * 折行，最多 {@code maxLines} 行，末行放不下就截断加「…」。
     * 返回**实际行数**的数组（无 null 尾）—— 卡片档的"上文下想法都限行数"靠它。
     */
    static String[] wrapMax(String text, float maxW, int maxLines, float size) {
        Paint mp = measurePaint(size);
        if (maxLines <= 0) return new String[]{text};
        if (mp.measureText(text) <= maxW) return new String[]{text};
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        float ellW = mp.measureText("…");
        int n = text.length(), start = 0;
        while (start < n && out.size() < maxLines) {
            int i = Math.min(start + 1, n);
            while (i <= n && mp.measureText(text, start, i) <= maxW) i++;
            int end = i - 1;
            if (end <= start) end = start + 1;
            boolean last = (out.size() == maxLines - 1) || (end >= n);
            if (last && end < n) {
                while (end > start && mp.measureText(text, start, end) + ellW > maxW) end--;
                out.add(text.substring(start, end) + "…");
                break;
            }
            out.add(text.substring(start, end));
            start = end;
        }
        return out.toArray(new String[out.size()]);
    }

    /** 折行测量用的画笔（衬线体，与正文一致）。静态方法里用，不动实例的 {@link #p} */
    private static Paint measurePaint(float size) {
        Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
        mp.setTypeface(android.graphics.Typeface.SERIF);
        mp.setTextSize(size);
        return mp;
    }

    /**
     * 右下角的描边「打开」按钮。
     *
     * 位置与 {@link CardSpec#openBoxLeft()} / {@link CardSpec#openBoxTop()} **共用同一组常量** ——
     * 桌面那个透明触摸窗就是按它们摆的，两边必须严格重合，否则用户会点在框上却没反应。
     * 矩形存进 {@link #openBox}，App 内的命中测试也用它。
     */
    private void drawOpenBox(Canvas c, float left, float right, float h, String label) {
        drawBoxAt(c, right - boxW() - CardSpec.OPEN_BOX_MARGIN_R, h, label, openBox);
    }

    /** 页内按钮尺寸：App 全屏档放大一号（屏幕宽、手指更好按）；桌面卡片沿用 {@link CardSpec} */
    private float boxW() { return fullscreen ? 88f : CardSpec.OPEN_BOX_W; }

    private float boxH() { return fullscreen ? 38f : CardSpec.OPEN_BOX_H; }

    private float boxMarginB() { return fullscreen ? 18f : CardSpec.OPEN_BOX_MARGIN_B; }

    /**
     * 画一个描边按钮，矩形回写进 {@code box} —— 命中测试与桌面那个透明触摸窗共用同一组常量，
     * 两边必须严格重合，否则用户点在框上却没反应。
     */
    private void drawBoxAt(Canvas c, float bx, float h, String label, RectF box) {
        drawBoxAt(c, bx, h, label, box, boxW());
    }

    /** 同上，但格宽由调用方给（v0.4.5 的 App 本记按钮行是四格等宽，与其它页不同） */
    private void drawBoxAt(Canvas c, float bx, float h, String label, RectF box, float bw) {
        float bh = boxH();
        float by = h - bh - boxMarginB();
        box.set(bx, by, bx + bw, by + bh);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(INK);
        c.drawRect(bx + 1f, by + 1f, bx + bw - 1f, by + bh - 1f, p);
        p.setStyle(Paint.Style.FILL);

        p.setColor(INK);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize((fullscreen ? 17f : SZ_OPEN) * unit);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(label, bx + bw / 2f, by + bh / 2f - (fm.descent + fm.ascent) / 2f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * 本记页底部按钮行（v0.4.5 抽成一个方法）。
     *
     * **空态也要画**：`只看想法` 池子没内容时，用户唯一的出路就是点「筛选」切回「全部」——
     * 如果空态不画按钮行，就会卡在一个什么都没有、也点不动的页面上（触摸矩形是画的时候回写的）。
     */
    private void drawNoteButtons(Canvas c, float h, float left, float right) {
        if (fullscreen) {
            // 四格等宽居中：「筛选」/ 上一条 / 换一条 / 导出
            float total = NOTE_BOX_W * 4f + NOTE_BOX_GAP * 3f;
            float x0 = left + ((right - left) - total) / 2f;
            if (x0 < left) x0 = left;
            drawFilterBox(c, x0, h);
            drawBoxAt(c, x0 + (NOTE_BOX_W + NOTE_BOX_GAP), h, PREV_LABEL, prevBox, NOTE_BOX_W);
            drawBoxAt(c, x0 + (NOTE_BOX_W + NOTE_BOX_GAP) * 2f, h, NOTE_LABEL, openBox, NOTE_BOX_W);
            drawBoxAt(c, x0 + (NOTE_BOX_W + NOTE_BOX_GAP) * 3f, h, EXPORT_LABEL, exportBox, NOTE_BOX_W);
        } else {
            // 桌面卡片：按钮区只有 368 宽，放不下四格 —— 保持左右各一个
            drawBoxAt(c, left + CardSpec.PREV_BOX_MARGIN_L, h, PREV_LABEL, prevBox);
            drawBoxAt(c, right - boxW() - CardSpec.OPEN_BOX_MARGIN_R, h, NOTE_LABEL, openBox);
            exportBox.setEmpty();
            filterBox.setEmpty();          // 卡片档没有筛选格（也不开触摸窗）
        }
    }

    /**
     * 「筛选」格（v0.4.5，App 本记页专属）：**一格两态**，点一下在「全部 / 只看想法」之间切换。
     *
     * 视觉上要一眼看出当前在哪一档，所以「只看想法」用**反白填充**（黑底白字）、
     * 「全部」（默认档）用描边 —— 墨水屏没有高亮色，反白是唯一足够醒目的状态差。
     * 右侧那个小三角（表示"可切换"）用 {@link Path} 画，不写字形：
     * 本机字体不保证带 ▾ 这类码位（这条教训见 {@link #drawSwitchIcon} 的注释）。
     */
    private void drawFilterBox(Canvas c, float bx, float h) {
        float bw = NOTE_BOX_W, bh = boxH();
        float by = h - bh - boxMarginB();
        filterBox.set(bx, by, bx + bw, by + bh);

        boolean on = noteIdeasSlot;
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? INK : 0xFFFFFFFF);
        c.drawRect(bx, by, bx + bw, by + bh, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(INK);
        c.drawRect(bx + 1f, by + 1f, bx + bw - 1f, by + bh - 1f, p);

        String label = on ? FILTER_IDEA : FILTER_ALL;
        float size = SZ_NOTE_FILTER * unit;
        float tri = size * 0.42f;
        float gap = size * 0.42f;
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.LEFT);
        float tw = p.measureText(label);
        float tx = bx + (bw - (tw + gap + tri)) / 2f;
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = by + bh / 2f - (fm.descent + fm.ascent) / 2f;
        p.setColor(on ? 0xFFFFFFFF : INK);
        c.drawText(label, tx, baseline, p);

        float cx = tx + tw + gap + tri / 2f;
        float cy = by + bh / 2f;
        path.reset();
        path.moveTo(cx - tri / 2f, cy - tri * 0.30f);
        path.lineTo(cx + tri / 2f, cy - tri * 0.30f);
        path.lineTo(cx, cy + tri * 0.34f);
        path.close();
        c.drawPath(path, p);

        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(INK);
        p.setStyle(Paint.Style.FILL);
    }

    /** 把字号缩到刚好装得下 maxW（下限 minS），返回实际字号 */
    private float fit(String text, float size, float minS, float maxW) {
        p.setTextSize(size);
        while (p.measureText(text) > maxW && size > minS) {
            size -= 0.5f;
            p.setTextSize(size);
        }
        return size;
    }

    /** 超宽就砍到加省略号。**调用前必须先把 p 的字号设成最终字号** */
    private String ellipsize(String text, float maxW) {
        if (p.measureText(text) <= maxW) return text;
        String tail = "…";
        float tailW = p.measureText(tail);
        int n = text.length();
        while (n > 1 && p.measureText(text.substring(0, n)) + tailW > maxW) n--;
        return text.substring(0, n) + tail;
    }

    // ── 公共小件 ──

    /** 抬头文字（拍板 A）：本周阅读时长 / 9月阅读 / 2026年3月阅读 */
    private String titleText() {
        long start = (stats != null && stats.baseTime > 0)
                ? stats.baseTime : PeriodRange.startOf(mode, 0);
        return PeriodRange.title(mode, start, PeriodRange.isCurrent(mode, start));
    }

    private String updatedLabel() {
        if (dataTime() <= 0) return "";
        String t = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(dataTime()));
        return "更新于 " + t;
    }

    /**
     * 只有「本书」/「本记」形态需要接收触摸。
     *
     * 本书 = 右下角「打开」；本记 = 左下「上一条」+ 右下「换一条」（App 全屏档另有「导出」），
     * 且 v0.4.4 起本记态还要区分**滑动**（滚正文）与**点击**（按按钮）。
     *
     * v0.4.1 修的正是这里：原来开头一句 `if (!isBook()) return false;` 把本记态的
     * 触摸全放掉了，App 内的「换一条」因此是个**画上去的假按钮**（点它没有任何反应）。
     *
     * 周/月形态保持原样（不处理触摸）—— 桌面卡片那边整张卡是 NOT_TOUCHABLE，
     * App 里也用不到卡片自身的点击。
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isBook() && !isNote()) return false;
        if (isNote()) return noteTouch(e);          // v0.4.4：本记态有滚动，手势要先分流
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            if (book != null && hit(openBox, x, y)) {
                if (openListener != null) openListener.onOpen();
            }
            return true;
        }
        // 这两个形态下要把 DOWN/MOVE 也接住，否则收不到后面的 UP
        return true;
    }

    /**
     * 本记态的触摸（v0.4.4）：**先判滑动、再判点击**。
     *
     * 为什么顺序重要：正文区现在可滚了，如果按下就判按钮，用户想滚动时手指落在正文上
     * 会顺手触发按钮（或者想点按钮却因轻微手抖被判成滚动）。规则很简单 ——
     * 动作内纵向位移超过 {@link #NOTE_SCROLL_SLOP} 就**锁定为滚动**，松手时不再判按钮。
     *
     * 松手后**不做惯性**：墨水屏上一次滑动要几百毫秒才刷干净，惯性滑行只会拖出一串残影。
     */
    private boolean noteTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                noteDownY = e.getY();
                noteDownScroll = noteScrollY;
                noteDrag = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (noteScrollMax <= 0f) return true;            // 内容没超过一屏（整行口径）→ 没得滚
                float dy = noteDownY - e.getY();
                if (!noteDrag && Math.abs(dy) > NOTE_SCROLL_SLOP) noteDrag = true;
                if (noteDrag) setNoteScroll(noteDownScroll + dy);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (noteDrag) {
                    noteDrag = false;
                    return true;                                  // 这一下是滚动，不算点击
                }
                float x = e.getX(), y = e.getY();
                // 「筛选」格放在 note == null 之前判：空态下它是唯一的出路（切回「全部」）
                if (fullscreen && hit(filterBox, x, y)) {
                    if (noteListener != null) noteListener.onToggleIdeas();
                    return true;
                }
                if (note == null) return true;
                if (hit(prevBox, x, y)) {
                    if (noteListener != null) noteListener.onPrevNote();
                } else if (hit(openBox, x, y)) {
                    if (noteListener != null) noteListener.onNextNote();
                } else if (fullscreen && hit(exportBox, x, y)) {
                    if (noteListener != null) noteListener.onExportNote();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                noteDrag = false;
                return true;
            default:
                return true;
        }
    }

    /**
     * 滚动正文：钳制到 [0, noteScrollMax] + **吸附到整行**。
     *
     * 吸附不是"手感优化"而是正确性要求：裁剪窗的上下边界固定，内容按 scrollY 位移，
     * 只有 scrollY 是 lineH 的整数倍时两条边界才同时压在行边界上。否则手指停在半行，
     * 顶端会漏出上一行的一小条墨迹、底端会把下一行切一半 —— 正是要修的那个毛病。
     * 副作用是"按行步进"的手感，在墨水屏上反而更省刷新、更不容易留残影。
     */
    private void setNoteScroll(float v) {
        float max = noteScrollMax;      // 已在绘制时取整到整行，这里直接用，别再算一次
        if (v < 0f) v = 0f;
        if (v > max) v = max;
        if (noteLineH > 0f) {
            float snapped = Math.round(v / noteLineH) * noteLineH;
            if (snapped < 0f) snapped = 0f;
            if (snapped > max) snapped = max;
            v = snapped;
        }
        if (Math.abs(v - noteScrollY) < 1f) return;
        noteScrollY = v;
        invalidate();
    }

    /** 正文是否超出一屏（还能滚） */
    public boolean noteScrollable() {
        return noteScrollMax > 0f;
    }

    private static boolean hit(RectF r, float x, float y) {
        return !r.isEmpty() && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
    }

    private void drawCentered(Canvas c, float cx, float baselineY, String text, float size, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, cx, baselineY, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(INK);
    }

    private void wrapCentered(Canvas c, float cx, float startY, float maxW, String text, float size, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float y = startY;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            line.append(text.charAt(i));
            if (p.measureText(line.toString()) > maxW || i == text.length() - 1) {
                c.drawText(line.toString(), cx, y, p);
                y += lineH * 1.2f;
                line.setLength(0);
            }
        }
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(INK);
    }

    private static String fmtTotal(int sec) {
        int hh = sec / 3600, mm = (sec % 3600) / 60;
        if (hh > 0) return hh + "小时" + mm + "分";
        if (mm > 0) return mm + "分钟";
        return sec + "秒";
    }

    private static String fmtShort(int sec) {
        if (sec >= 3600) {
            float v = sec / 3600f;
            return String.format(Locale.CHINA, "%.1f时", v);
        }
        int m = sec / 60;
        if (m < 1) m = 1;
        return m + "分";
    }
}
