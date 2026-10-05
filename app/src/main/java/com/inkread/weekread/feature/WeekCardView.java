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

    /**
     * 1 号 = 屏高 × 该系数（480×800 屏上 = 1.2px）。
     *
     * ⚠️ 本常量是"号 → px"的**唯一真源**：设置页要按它把"17 号"显示成"20.4px"，
     * 所以改成 public —— 别在别处再抄一份 0.0015（TASK-016）。
     */
    public static final float UNIT_RATIO = 0.0015f;

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
     * 「待办」形态要展示的**未完成**清单（V1.0.3-beta，TASK-024）。
     *
     * 由 `CardContentController` 从 `TodoStore.pending()` 取好塞进来 ——
     * 🔴 渲染函数**不读存储 / 不读 prefs**（docs/03 §3，与 {@link #note} / {@link #book} 同款口径）。
     * null = 还没取到（画「暂无待办」）。
     */
    java.util.List<com.inkread.weekread.core.TodoItem> todo = null;
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
    /**
     * 成就行文案（v0.9，TASK-013）。
     *
     * 非 null 且当前周期时，卡片**贴底位**画这行非加粗居中小字（原底部统计行的位置），
     * 原统计行上移一行、周卡柱状图压短（几何见 {@code CardRenderer}，TASK-015 实测定死）。
     * 文案由 {@code CardContentController#applyAchievement} 按偏好与数据**算好塞进来** ——
     * 🔴 渲染函数不读 prefs / 不发请求（docs/03 §3），与 {@link #noteHint} 同款口径。
     * null = 不画，且全部版面几何走原值 —— 与关闭态逐像素一致。
     * App 内（MainActivity）不塞此字段，App 版面不变（拍板：成就行只上桌面卡片）。
     */
    String achievementText = null;

    /**
     * 本记正文的字号（**号**，不是 px）—— **卡片档 / 桌面悬浮卡**（v0.9，TASK-016）。
     *
     * 以前是"按字数动态分档"（≤70 字用 18 号、否则 14 号）：字数一多字就变小，观感跳。
     * 现在改成**固定 + 可调**，默认 17 号 = 桌面应用名称的字号（`验证记录/59` 项①实测
     * 字形 20px ≈ 16.7 号）。超出的部分：卡片档靠限行 + 省略号（TASK-017 起有「展开▽」）。
     *
     * 值由 {@code CardContentController#applyNoteFont} 按 {@code CardPrefs} 算好塞进来 ——
     * 🔴 渲染函数不读 prefs、不读文件（docs/03 §3），与 {@link #monthHeatmap} 同款口径。
     */
    float noteCardSize = com.inkread.weekread.core.CardPrefs.NOTE_CARD_SIZE_DEFAULT;

    /**
     * 本记正文的字号（号）—— **App 全屏档**（v0.9，TASK-016）。
     *
     * 由 `NoteExport.sizeTier`（小/中/大 = 15/17/19 号）决定，与导出图**同一个档位选择**。
     * 长文靠已有的正文滚动消化，不再缩字号。
     * 值由 {@code MainActivity#showNoteItem} 塞进来（同上的"渲染不读 prefs"口径）。
     */
    float noteFullSize = NoteExport.TIER_NUMS[NoteExport.SIZE_TIER_DEFAULT];

    /**
     * 本月呈现方式：**true = 热力图**（5 档黑度网点）/ **false = 打卡网格**（现状）。
     *
     * 只影响**本月**形态的日期格怎么画（{@code CardRenderer.drawMonthBody} /
     * {@code drawMonthFullBody} 各分流一次）；周卡 / 本书 / 本记形态不受影响。
     * 由 {@code CardContentController#applyMonthStyle} 按偏好
     * （{@code CardPrefs.getMonthStyle}）算好塞进来 ——
     * 🔴 渲染函数不读 prefs / 不发请求（docs/03 §3），与 {@link #achievementText} 同款口径。
     *
     * 🔴 v0.8.1（TASK-014）：默认值由偏好决定，**默认 = 热力图**（用户 2026-09-27 拍板）。
     */
    boolean monthHeatmap = false;
    /**
     * 「本记」形态「更新于 HH:MM」的时间来源（毫秒）。
     *
     * 🔴 v0.8.1 修复：以前这里是 `setNote()` 里的 `System.currentTimeMillis()` ——
     * 那是**渲染当下**，每次重绘（含 App 静默刷新、切形态回来）都变，表现为"时钟"，
     * 且离线也一直在变（它根本不是数据获取时刻）。
     * 现在由 {@link #setNote} 取**该条内容所属书的真实落盘时刻**
     * （{@code NoteStore.noteFetchedAt}：划线/想法两路缓存 fetchedAt 取较新者）。
     * 0 = 没有时间戳（老数据 / 没同步过）→ 不画「更新于」（与周/月/本书口径一致）。
     */
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
    /**
     * 🆕 TASK-057（K11）本记形态**「选书」格**的矩形（App 全屏档专属，桌面卡片档恒为空）。
     *
     * 四格 → 五格后排在「换一条」与「导出」之间；点它 ⇒ 宿主（{@code MainActivity}）
     * 弹半屏选书列表。桌面卡片路径**不读**它（也不画、不设），故卡片形态零影响（卡面 A6）。
     */
    final RectF pickBox = new RectF();
    /** 本记形态左下角「上一条」按钮的矩形（v0.4.2） */
    final RectF prevBox = new RectF();
    /**
     * 本记**行末**「展开▽ / 收起△」的矩形（v0.9，TASK-017）。
     *
     * 🔴 **本 View 内部坐标**，且是**绘制时算出来回写的**（位置取决于末行落在第几行，
     * 不同长度的划线落点不同）—— 所以触摸窗不能写死坐标，必须等画完再摆
     * （见 {@link #publishExpandBox} 与 {@link ExpandListener#onExpandBox}）。
     * 空矩形 = 这次没有按钮（短文本收起态 / 非本记形态 / App 全屏档）。
     */
    final RectF expandBox = new RectF();
    /**
     * 本记正文是否处于**展开态**（v0.9，TASK-017）。
     *
     * 只影响**桌面悬浮卡**（`fullscreen == false`）：App 全屏档正文本来就能滚动，
     * 不需要也不画这个按钮 —— 判据统一写成 `!host.fullscreen`，无需额外开关。
     *
     * 🔴 **不记住展开态**（用户拍板）：切形态 / 换一条 / 上一条 / 被让位 **一律复位收起**，
     * 复位点全部收在 {@link #collapseNote()} 一个方法里。
     */
    boolean noteExpanded = false;
    OpenListener openListener;
    NoteListener noteListener;
    ExpandListener expandListener;
    /** 上一次回写出去的按钮矩形（判"有没有变"，避免重复回调 ⇒ 墨水屏少闪） */
    private final RectF expandBoxSent = new RectF();
    /**
     * 待办形态每一条勾选框的矩形（**View 内坐标**，绘制时重建；V1.0.3-beta，TASK-024）。
     *
     * 🔴 与 {@link #expandBox} 同款：位置只有绘制时才定得下来（条目增减会让整列位移），
     * 所以桌面那个「点勾选框」的透明小窗必须等画完再摆（见 {@link #publishTodoBoxes}）。
     */
    final java.util.List<RectF> todoBoxes = new java.util.ArrayList<RectF>();
    /** 上一次回写出去的勾选框列表（判"有没有变"，避免重复回调 ⇒ 墨水屏少闪） */
    private final java.util.List<RectF> todoBoxesSent = new java.util.ArrayList<RectF>();
    TodoBoxListener todoBoxListener;

    // ── v0.4.4：本记正文的滚动与导出模式 ──

    /**
     * 本记正文的滚动量（px）。**只滚正文段**——抬头、署名、末行、按钮全部固定在原位。
     *
     * 为什么不用 ScrollView 包一层：① 按钮会跟着滚走（滑到中间就点不到「换一条」了）；
     * ② 墨水屏上惯性滑动会拖出一串残影，而自绘滚动可以做到"跟手、松手即停"。
     */
    float noteScrollY = 0f;

    // ── 🆕 TASK-054（K9）：本月全屏页的滚动（**整页滚**，不是局部区滚）──

    /**
     * 「本月」全屏页的滚动量（px）。**只滚正文**（分隔线以下的：信息块 + 日历 + 摘要行 + 排名区），
     * 抬头固定在原位 —— 与 {@link #noteScrollY} 同款取舍，用户始终知道自己在看哪一页。
     *
     * 🔴 **只对 App 全屏档 + 本月形态生效**（判据 `fullscreen && isMonthly()`）；
     * 桌面卡片月形态走 {@code CardRenderer.drawMonthBody} 那条路径，**本字段一个字节都不影响**
     * （卡面 A3 的逐像素对照就靠这条隔离）。未溢出时恒 0 ⇒ 版面与加本卡之前逐像素一致。
     */
    float monthScrollY = 0f;
    /** 本月页可滚上限（px，绘制时算）= 内容总高 − 视口高；**0 = 装得下**（不吃手势、无位移） */
    float monthScrollMax = 0f;
    float monthDownY = 0f, monthDownScroll = 0f;
    boolean monthDrag = false;
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
    /**
     * 🆕 TASK-057（K11）：进度行/池子规模取哪个**槽位**（`NoteStore.SLOT_*`：0 默认 / 1 只看想法 / 2 选书）。
     *
     * 与 {@link #noteIdeasSlot} 的分工：后者只管「筛选」格的**外观**（App 专属，桌面恒 false）；
     * 本字段是**数据口径**，渲染层的进度行（`NoteStore.progress(ctx, noteSlot)`）读它。
     *
     * 🔴 **桌面卡片恒 0**（`CardContentController` 从不设它）⇒ 桌面的「第 N / 共 M 条」
     * 永远按全库算，App 里选书改不动它（卡面 A6）。
     * App 本记页由 `MainActivity.showNoteItem` 用 `NoteStore.slotFor(this)` 注入。
     */
    int noteSlot = 0;

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
        /**
         * 🆕 TASK-057（K11）点了「选书」格 —— 宿主弹半屏选书列表（App 全屏档专属）。
         *
         * 本回调**只有 App 全屏档会触发**（卡片档不画这一格、{@code pickBox} 恒为空）。
         */
        void onPickNote();
    }

    /**
     * 「展开▽ / 收起△」的宿主回调（v0.9，TASK-017）—— **只有桌面悬浮卡需要实现**。
     *
     * 为什么必须有回调：卡片主体窗口是 `FLAG_NOT_TOUCHABLE`（手势要穿透给桌面），
     * 按钮是**画**在卡上的，点它靠 `OverlayController` 另开的透明小窗；
     * 而窗口高度与按钮位置只有本 View 知道 ⇒ 变化时必须通知外面改 `WindowManager`。
     *
     * 两个方法是**两件事**，别合并：
     * · {@link #onExpanded} —— **先**改窗口高度（下一步绘制用的 `h` 才是对的）；
     * · {@link #onExpandBox} —— **后**摆触摸小窗（矩形要等那一帧画完才有）。
     */
    public interface ExpandListener {
        /** 展开态变了 ⇒ 窗口高度要跟着改（true = 用 {@code CardSpec.cardHeightExpanded()}） */
        void onExpanded(boolean expanded);
        /** 行末按钮矩形画出来了 / 没了。{@code box} 是**副本**（View 内坐标）；空 = 没有按钮 */
        void onExpandBox(RectF box);
    }

    /**
     * 待办**逐条勾选框**矩形的宿主回调（V1.0.3-beta，TASK-024）—— **只有桌面悬浮卡需要实现**。
     *
     * 与 {@link ExpandListener} 同款的取舍：卡片主体 `FLAG_NOT_TOUCHABLE`（手势要穿透给桌面），
     * 勾选框是**画**在卡上的，点它靠 `OverlayController` 另开的一组透明小窗；
     * 而每条的位置只有本 View 知道 ⇒ 变化时必须通知外面重建窗口。
     */
    public interface TodoBoxListener {
        /** 本帧所有勾选框矩形（**View 内坐标副本**）；空列表 = 非待办形态 / 没有条目 */
        void onTodoBoxes(java.util.List<RectF> boxes);
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
                : (PeriodRange.NOTE.equals(m) ? PeriodRange.NOTE
                : (PeriodRange.TODO.equals(m) ? PeriodRange.TODO : PeriodRange.WEEKLY)));
        if (v.equals(mode)) return;
        mode = v;
        noteHint = null;            // 换形态了，上一个形态的空态提示不再适用（v0.5.3）
        achievementText = null;     // 换形态了，成就行作废；controller 随 setStats 按新形态重算（v0.9）
        collapseNote();             // 切形态 ⇒ 展开态复位（TASK-017 拍板：不记住展开态）
        monthScrollY = 0f;          // 🆕 TASK-054：切形态 ⇒ 月页滚动复位（与展开态同规）
        monthScrollMax = 0f;
        invalidate();
    }

    public void setOpenListener(OpenListener l) { openListener = l; }

    /** 设「本记」两个页内按钮的回调（v0.4.1） */
    public void setNoteListener(NoteListener l) { noteListener = l; }

    /**
     * 设「展开▽ / 收起△」的宿主回调（v0.9，TASK-017）—— 只有桌面悬浮卡需要（{@code OverlayController}）。
     * App 全屏档与设置页预览不设，此时按钮要么不画（App）、要么画出来也点不动（预览，本来就是预览）。
     */
    public void setExpandListener(ExpandListener l) { expandListener = l; }

    /** 设「待办勾选框」的宿主回调（V1.0.3-beta，TASK-024）—— 只有桌面悬浮卡需要 */
    public void setTodoBoxListener(TodoBoxListener l) { todoBoxListener = l; }

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
        monthScrollY = 0f;      // 🆕 TASK-054：换了一份统计（换月 / 刷新 / 切周月）⇒ 月页滚回顶部
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
        // 🔴 v0.8.1：取该条内容所属书的**真实落盘时刻**，不再用"渲染当下"
        // （后者每次重绘都变 → 「更新于」像时钟，离线也变；详见字段 javadoc）。
        // n == null 时无从查书 → 0；两路缓存都没时间戳 → 0（不画「更新于」）。
        noteFetchedAt = (n == null) ? 0L
                : com.inkread.weekread.core.NoteStore.noteFetchedAt(getContext(), n.bookId);
        // 换了一条划线 ⇒ 展开态复位（TASK-017 拍板）—— 「换一条 / 上一条」都走这里，
        // 所以复位点只有这一个，不会漏路径（CardContentController 无绕过 setNote 的换条路）。
        collapseNote();
        invalidate();
    }

    public NoteStats getNote() {
        return note;
    }

    /**
     * 设「待办」清单（V1.0.3-beta，TASK-024）。传 null = 还没取到。
     *
     * 传进来的**应当只有未完成项**（`TodoStore.pending()`）—— 卡片只显示未完成（验收 A5）。
     * 换形态 / 勾选后条目变化都靠重设这个列表触发重绘。
     */
    public void setTodo(java.util.List<com.inkread.weekread.core.TodoItem> list) {
        todo = list;
        errorText = null;
        refreshing = false;
        phMain = null;
        emptyNote = null;
        collapseNote();     // 与 setNote 同规：换数据 ⇒ 展开态复位（TASK-017 拍板）
        invalidate();
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

    /**
     * 设成就行文案（v0.9，TASK-013）。传 null = 不画（关闭 / 无有效目标 / 非当前周期）。
     * same-check + invalidate 与 {@link #setNoteHint} 同款 —— 文案没变就不重绘，
     * 墨水屏上每次全屏刷新都是肉眼可见的，能省则省。
     */
    public void setAchievementText(String s) {
        boolean same = (s == null) ? (achievementText == null) : s.equals(achievementText);
        if (same) return;
        achievementText = s;
        invalidate();
    }

    /**
     * 设本月呈现方式（TASK-014）：true = 热力图 / false = 打卡网格。
     *
     * same-check + invalidate 与 {@link #setAchievementText} 同款 —— 模式没变就不重绘
     *（墨水屏每次全屏刷新肉眼可见，能省则省）。切模式本身 = 一次整卡重绘（拍板可接受）。
     */
    public void setMonthHeatmap(boolean v) {
        if (monthHeatmap == v) return;
        monthHeatmap = v;
        invalidate();
    }

    /**
     * 设桌面卡片本记正文的字号（号，TASK-016）。
     *
     * same-check + invalidate 与 {@link #setMonthHeatmap} 同款 —— 值没变就不重绘
     *（墨水屏每次全屏刷新肉眼可见，能省则省）。
     */
    public void setNoteCardSize(float v) {
        if (Math.abs(v - noteCardSize) < 0.01f) return;
        noteCardSize = v;
        invalidate();
    }

    /**
     * 设 App 全屏档本记正文的字号（号，TASK-016）。同 {@link #setNoteCardSize} 的 same-check。
     *
     * ⚠️ 桌面卡片**不要**调这个 —— 它只影响 App 内本记页；桌面走 {@link #setNoteCardSize}。
     */
    public void setNoteFullSize(float v) {
        if (Math.abs(v - noteFullSize) < 0.01f) return;
        noteFullSize = v;
        invalidate();
    }

    /**
     * 进度行/池子用哪个槽位（v0.4.4 起是 boolean「只看想法」；🆕 TASK-057 扩成三槽位 int）。
     *
     * App 在设内容前调（传 {@code NoteStore.slotFor(this)}）；**桌面卡片不调**（默认 0 = 全量池）。
     * 顺带把「筛选」格的外观标记 {@link #noteIdeasSlot} 对齐 —— 只有 App 会传 1。
     */
    public void setNoteSlot(int slot) {
        if (noteSlot == slot) return;
        noteSlot = slot;
        noteIdeasSlot = (slot == 1);        // 1 = 只看想法（NoteStore.SLOT_IDEA）
        invalidate();
    }

    // ══════════════════════ 本记「展开▽ / 收起△」（v0.9，TASK-017）══════════════════════

    /** 当前是否展开（包级 —— CardRenderer 判定按钮文案要用） */
    boolean isNoteExpanded() {
        return noteExpanded && !fullscreen;
    }

    /**
     * 翻转展开态（触摸小窗 / App 内命中测试都走这里）。
     *
     * 🔴 顺序不能反：**先**通知宿主改窗口高度，**再** invalidate ——
     * 因为下一步绘制用的 `h` 来自 `getHeight()`（CardRenderer#draw），
     * 高度没先改，那一帧画出来的还是旧高度的版面（观感上就是"点了没反应"）。
     * 两次请求（updateViewLayout + invalidate）落在同一次 traversal 里 ⇒ **只闪一次**。
     */
    public void toggleNoteExpanded() {
        setNoteExpanded(!noteExpanded);
    }

    /** 直接设展开态（{@link #collapseNote} 与 {@link #toggleNoteExpanded} 共用） */
    private void setNoteExpanded(boolean v) {
        if (noteExpanded == v) return;
        noteExpanded = v;
        if (expandListener != null) expandListener.onExpanded(v);
        invalidate();
    }

    /**
     * **强制收起**（切形态 / 换一条 / 上一条 / 被让位，TASK-017 拍板）。
     *
     * 已收起时直接返回 ⇒ 频繁调用（让位路径每次状态变化都会过一遍）不会产生多余重绘。
     */
    public void collapseNote() {
        setNoteExpanded(false);
    }

    /**
     * 行末按钮矩形回写后**通知宿主**（包级，由 {@link #onDraw} 在画完那一帧调）。
     *
     * 用 `post` 而不是同步回调：本方法在 `onDraw` 里执行，同步调 `WindowManager` 会在绘制过程中
     * 改动窗口，风险不可控；post 到消息队列 ⇒ 绘制结束后再改，且**矩形没变就完全不回调**
     * （墨水屏每一次多余的全屏刷新都肉眼可见）。
     */
    private void publishExpandBox() {
        if (expandBox.equals(expandBoxSent)) return;
        expandBoxSent.set(expandBox);
        if (expandListener == null) return;
        final RectF copy = expandBox.isEmpty() ? new RectF() : new RectF(expandBox);
        post(new Runnable() {
            @Override
            public void run() {
                if (expandListener != null) expandListener.onExpandBox(copy);
            }
        });
    }

    /**
     * 待办勾选框列表回写后**通知宿主**（包级，由 {@link #onDraw} 在画完那一帧调）。
     * 与 {@link #publishExpandBox} 完全同款：post 到消息队列（不在绘制过程中动窗口），
     * 且**列表没变就完全不回调**（墨水屏每一次多余的全屏刷新都肉眼可见）。
     */
    private void publishTodoBoxes() {
        if (todoBoxesSent.equals(todoBoxes)) return;
        todoBoxesSent.clear();
        for (RectF r : todoBoxes) todoBoxesSent.add(new RectF(r));
        if (todoBoxListener == null) return;
        final java.util.ArrayList<RectF> copy = new java.util.ArrayList<RectF>();
        for (RectF r : todoBoxesSent) copy.add(new RectF(r));
        post(new Runnable() {
            @Override
            public void run() {
                if (todoBoxListener != null) todoBoxListener.onTodoBoxes(copy);
            }
        });
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

    /** 当前是否在显示"待办"形态（V1.0.3-beta，TASK-024） */
    public boolean isTodo() {
        return PeriodRange.TODO.equals(mode);
    }

    /**
     * 「更新于 HH:MM」的时间来源：周/月看周期缓存，本书看 BookStats#fetchedAt，
     * 本记看取出的时刻。（包级 —— CardLayout 的 updatedLabel 要用）
     */
    long dataTime() {
        if (isBook()) return book == null ? 0L : book.fetchedAt;
        if (isNote()) return noteFetchedAt;
        if (isTodo()) return 0L;              // 待办是本地清单，没有"更新于"概念
        return stats == null ? 0L : stats.fetchedAt;
    }

    // ══════════════════════ 委托（TASK-007：绘制 / 触摸各归其主）══════════════════════

    @Override
    protected void onDraw(Canvas c) {
        renderer.draw(c);
        // 行末「展开/收起」按钮的位置是这一帧才算出来的 ⇒ 画完再通知宿主摆触摸窗。
        // （renderer 每帧开头都会先把 expandBox 清空，所以这里拿到的一定是本次的真实结果）
        publishExpandBox();
        publishTodoBoxes();     // TASK-024：待办逐条勾选框（同上，等画完再摆小窗）
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
