package com.inkread.weekread.feature;

import com.inkread.weekread.core.CardSpec;
import com.inkread.weekread.core.NoteStore;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PeriodStats;
import com.inkread.weekread.core.RankFilter;
import com.inkread.weekread.core.StatsStore;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * 卡片的绘制（TASK-007 从 {@link WeekCardView} 整段平移而来）：
 *
 * · {@link #draw} 承接原 onDraw 体 —— 占位页 / Key 引导 / 空错态 / 四种形态的分流骨架；
 * · 抬头（drawHeader）与两侧小图标（drawSwitchIcon / drawRefreshIcon）；
 * · 四种形态的正文：周柱状图（drawWeekBody）/ 月日历（drawMonthBody / drawMonthFullBody）/
 *   本书进度（drawBookBody）/ 本记两段式（drawNoteBody，含滚动状态回写）；
 * · 页内按钮（drawOpenBox / drawBoxAt / drawNoteButtons / drawFilterBox）与公共小件
 *   （drawCentered / wrapCentered）。
 *
 * 状态字段与画笔（p / path / bmpPaint / bmpDst / 各命中矩形 / 滚动状态）全部留在壳里，
 * 本类持 {@link #host} 引用按包级可见访问；排版度量在 {@link CardLayout}、
 * 触摸在 {@link CardInteraction}。
 *
 * 🔴 手法与 TASK-006 一致：代码逐行平移，除 `host.` 前缀外零改动 ——
 * 拆分前后像素级行为必须完全一致（TASK-007 像素基线 9 场景逐张比对）。
 */
final class CardRenderer {

    final WeekCardView host;

    CardRenderer(WeekCardView host) { this.host = host; }

    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF3C3C3C;
    private static final int LIGHT = 0xFFA8A8A8;

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

    // ── 成就行开启时，「周柱状图」版面的重排（TASK-013-R2，2026-09-27 用户拍板「方案 A · 43/57」）──
    //
    // 背景：成就行一开，贴底位让给它、统计行上移一行 ⇒ 上方必须让出一块高度 D。
    // 现状（R1）是 **D 全由柱状图扛**（柱底 0.78 → 0.685），观感上柱子被压得偏矮。
    // 用户改判：**主数字字号同时缩小，与柱状图分摊** —— D 的 43% 由上方（字号）出、
    // 剩下 57% 才由柱状图出。
    //
    // 🔴 为什么不是用户最初要的 70/30：D 只有 `h×0.095 ≈ 32.9px`，70% 意味着字号要
    // 从 38 号砍到 18.8 号，柱顶被抬高 23px、而柱状图只回退 9.9px ⇒ 柱高反而比成就行
    // 关闭时还高 13px，净空 −11.9px **必然重叠**。43/57 是"主数字明显变小"里能装下的
    // 上限（净空 7.0px）。详见 `.workbuddy/artifacts/TASK-013R2_成就行版面70-30可行性.md`。

    // ⚠️ 前提：`achvOn` 只在**桌面悬浮卡**上为真 —— `achievementText` 全项目只有
    // `CardContentController.applyAchievement` 一处注入，MainActivity 不塞（拍板：成就行只上桌面卡片）。
    // 所以 `h` 恒为卡片高 346、`give` 恒 ≈14.07px（远小于主数字 45.6px），不会把字号减穿。
    // 若将来把成就行搬进 App 全屏周视图（h≈800），**必须重新标定**这组系数。

    /** 成就行开启时，上方需要让出的高度 D（相对 h）= 柱底 0.78 压到 0.685 的差值。 */
    private static final float ACHV_GIVE_TOTAL = 0.095f;
    /** D 里由**上方**（主数字字号 + 柱顶上移）承担的比例；柱状图承担 `1 − 此值` = 57%。 */
    private static final float ACHV_UP_SHARE = 0.428f;
    /** 成就行开启时的星期标签行距系数（原 1.5）—— 柱底回退后靠这条把净空补回 7px 量级。 */
    private static final float ACHV_DAY_OFF = 1.30f;
    /** 成就行开启时统计行上移的行距系数（原 1.6）—— 与 {@link #ACHV_DAY_OFF} 配套收紧。 */
    private static final float ACHV_SHIFT_MUL = 1.30f;

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

    // ── 🆕 TASK-054（K9）「本月各书籍阅读排名」（月全屏页**下方**新开的一段）──
    // 版面：标题一行 + 每书一行（`名次 | 书名 | 时长`），行高按卡面定死 ≈32px。
    // 🔴 这一段**只在 App 全屏档**画（`drawMonthFullBody` 专用）—— 桌面卡片月形态零影响。
    /** 标题与行内文字字号（号）—— 17 号 = 20.4px，与页内其它副信息同档 */
    private static final float RANK_SZ = 17f;
    /** 行高（号）—— 26.67 号 × 1.2 = 32.0px（卡面「行高约 32px」）*/
    private static final float RANK_ROW_H = 26.67f;
    /** 名次槽宽（号）—— 两位数「10」也放得下，书名起点因此对齐 */
    private static final float RANK_NUM_W = 34f;
    /** 书名与右侧时长之间的最小净空（号）*/
    private static final float RANK_DUR_GAP = 12f;
    private static final String MONTH_RANK_TITLE = "本月各书籍阅读排名";
    private static final String MONTH_RANK_EMPTY = "本月还没有读完 / 在读的书";
    private static final String MONTH_RANK_UNKNOWN = "（未知书名）";

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
    /** 🆕 TASK-057（K11）本记形态「选书」格（App 全屏档专属）—— 点它弹半屏选书列表 */
    private static final String PICK_LABEL = "选书";

    // ── V1.0.3-beta（TASK-024）「待办」版式 ──
    // 每行 = 勾选框（最左）| 内容（超出省略号）| 日期时间（最右，无则不画该列）。
    /** 内容字号（号）—— 🔴 R2：16 → 17.6（用户拍板整体 ×1.1；勾选框 boxS 由本值派生，随之 ×1.1） */
    private static final float SZ_TODO = 17.6f;
    /** 日期时间字号（号）—— 小一号的灰字，不与内容抢视线；🔴 R2：12 → 13.2（用户拍板 ×1.1） */
    private static final float SZ_TODO_WHEN = 13.2f;
    /** 空态文案 */
    private static final String TODO_EMPTY = "暂无待办";

    // ── v0.4.0「本记」版式 ──
    // 引号 + 正文折行（衬线体）+ 署名行（书名粗/作者灰）+ 末行（章节·日期）。
    // 正文行数不写死：**按可用高度算**（长句多给几行，短句自然留白），杜绝压到署名。
    private static final float SZ_NOTE_QUOTE = 30f;       // 开头的大引号
    private static final float SZ_NOTE_QUOTE_FULL = 40f;
    // ⚠️ 正文**没有**字号常量了（TASK-016）：字号是**固定 + 可调**的用户偏好，
    //    卡片档来自 `WeekCardView#noteCardSize`（默认 17 号，可调 12–24，见 CardPrefs），
    //    App 档来自 `noteFullSize`（sizeTier 三档 15/17/19，见 NoteExport#TIER_NUMS）。
    //    以前写在这里的 SZ_NOTE_TEXT / SZ_NOTE_TEXT_FULL / SZ_NOTE_CARD_BIG /
    //    NOTE_CARD_BIG_CHARS / NOTE_FULL_STEPS / NOTE_FULL_SIZES 属于"按字数动态分档"，
    //    已随该机制一并删除（用户 2026-09-27 拍板 D1）。
    private static final float SZ_NOTE_TITLE = 14f;       // 署名：书名
    private static final float SZ_NOTE_TITLE_FULL = 17f;
    private static final float SZ_NOTE_AUTHOR = 12f;      // 署名：作者
    private static final float SZ_NOTE_AUTHOR_FULL = 15f;
    private static final float SZ_NOTE_META = 11.5f;      // 末行：章节 · 日期
    private static final float SZ_NOTE_META_FULL = 14f;
    /**
     * 正文行数下限（卡片档）。🔴 **是口径记录，不是开关**：真正的行数由"可用高度 ÷ 行高"
     * 算出来（`bodyH / lineH`），这里只记"卡片档至少要能放 3 行"这条设计底线 ——
     * `CardPrefs.NOTE_CARD_SIZE_MAX = 24` 这个上限正是按"24 号时仍 = 3 行"反推出来的。
     */
    private static final int NOTE_LINES_MIN = 3;
    /** 上限（历史值）。App 档早已不限行数（超出靠滚动），这里仅作口径记录 */
    private static final int NOTE_LINES_MAX = 16;

    // ── v0.4.4「本记」两段式（原文 + 想法）──
    // ⚠️ 「按合计字数（NoteStats#displayChars）分档」这套机制已随 v0.9（TASK-016）**取消**：
    //    字号改成固定 + 可调，不再看字数。两段式排版本身照旧（它决定行数与截断，不是字号）。
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
    /**
     * App 全屏档本记按钮行的几何：**五格等宽**（筛选 / 上一条 / 换一条 / 选书 / 导出）。
     *
     * 🆕 TASK-057（K11）：由四格 → 五格，「选书」插在「换一条」与「导出」之间。
     * 重算格宽（R4）：可用宽 = `w − 2·padX = 480 − 48 = 432px`（`padXRatio = 0.05`）；
     * `5×80 + 4×8 = 432` **恰好铺满**，两侧零余量 ⇒ 旧值 `NOTE_BOX_W = 100` 放不下五格，
     * 这里降到 **80**（8 号缩放后单格 96px，三字标签 ~61px，仍有 ~17px 边距，墨水屏上不挤）。
     * ⚠️ 旧注释担心的"五格压到 84px 且要降到 15 号"那种情况**不会发生** ——
     * 那是"两个筛选项各占一格"（共 6 格）才会有的问题；这里 5 格 + 17 号不动。
     */
    private static final float NOTE_BOX_W = 80f;
    private static final float NOTE_BOX_GAP = 8f;
    /** 筛选格里的文字字号（与全屏档按钮同号） */
    private static final float SZ_NOTE_FILTER = 17f;
    private static final String FILTER_ALL = "全部";
    private static final String FILTER_IDEA = "想法";

    // ── 行末「展开▽ / 收起△」（v0.9，TASK-017）──
    //
    // 用户拍板 **E3 = 无边框**：不像「换一条」那样描个框，而是"文字 + 小三角"，
    // 像一个链接 —— 理由是它挂在正文末行的行末，描边框会把读者的视线从正文拽走，
    // 而且末行右侧本来就紧，框的 2px 描边 + 内缩会把字挤得更窄。
    //
    // 🔴 三角用 **Path 画**，不写字形 ▽/△：本机字体不保证带这两个码位
    // （既有教训见 {@link #drawSwitchIcon} 与 {@link #drawFilterBox} 的注释）。

    /** 「展开▽」/「收起△」两个文案（等宽，两字） */
    private static final String EXPAND_LABEL_DOWN = "展开";
    private static final String EXPAND_LABEL_UP = "收起";
    /** 按钮小字的字号（号）—— 与末行「章节 · 日期」同号（SZ_NOTE_META），观感一家人 */
    private static final float SZ_EXPAND = 11.5f;
    /** 三角的宽 / 高 / 与文字的间距（号） */
    private static final float EXPAND_TRI_W = 7f;
    private static final float EXPAND_TRI_H = 5f;
    private static final float EXPAND_TRI_GAP = 3f;
    /** 按钮与正文末行尾部之间的留白（号）—— 别让「…」和「展开」贴在一起 */
    private static final float EXPAND_GAP = 5f;
    /** 触摸框相对按钮外扩（号）—— 墨水屏上手指按得准，框要比字大一圈 */
    private static final float EXPAND_BOX_PAD = 4f;

    /**
     * 周形态 7 根柱的横坐标标签。**2026-09-27 用户拍板：由「周一…周日」简化为单字「一…日」**
     * （柱宽 ~26px，两个字的「周一」在墨水屏上偏挤、且与下方日格视觉重复）。
     * 与 {@link #CAL_HEAD} 同形但**语义独立**：那是月日历网格的列头，这是柱状图横轴，别合并。
     * 桌面卡与 App 内周视图共用 {@link #drawWeekBody} ⇒ 改这里即双端生效。
     */
    private static final String[] DAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};
    /** 日历表头：**恒为「一…日」**，不随月份/语言变化（周一起算） */
    private static final String[] CAL_HEAD = {"一", "二", "三", "四", "五", "六", "日"};

    /** 承接原 onDraw 体（440–506 行逐行平移）。壳的 onDraw 只剩一行委托。 */
    void draw(Canvas c) {
        c.drawColor(0xFFFFFFFF);
        // 行末「展开/收起」按钮每帧重新判定：先清空，本记分支里若判定要画再回写。
        // 这样"上一帧有按钮、这一帧没了"（切形态 / 换一条 / 收起）也能正确撤掉触摸窗。
        host.expandBox.setEmpty();
        host.todoBoxes.clear();        // 待办勾选框矩形每帧重建（非待办形态 ⇒ 保持空 ⇒ 桌面小窗收走）
        // 🆕 TASK-071：左下「选书」框同样每帧重建 —— 只有「本书」分支（{@link #drawBookBody}）
        // 会回写它。不清的话，切出本书形态后这个矩形会**停留在上一帧的位置**，
        // App 全屏档的命中测试就会在空白处响应一次「选书」（桌面档不受影响：那边走 a11y 小窗，
        // 显隐由形态门控）。
        host.pickBox.setEmpty();
        // 🆕 TASK-054：月页可滚量每帧重算 —— 先清零，只有真走「月全屏」分支时才回填。
        // 这样切到别的形态 / 桌面卡片（fullscreen=false）⇒ 恒 0 ⇒ 不吃手势、零位移。
        host.monthScrollMax = 0f;
        float w = host.getWidth(), h = host.getHeight();
        if (host.phMain != null) {
            drawCentered(c, w / 2f, h * 0.42f, host.phMain, SZ_EMPTY * host.unit, INK);
            if (host.phSub != null) {
                wrapCentered(c, w / 2f, h * 0.42f + SZ_EMPTY * host.unit * 2.0f, w * 0.8f, host.phSub,
                        SZ_SUB * host.unit, GRAY);
            }
            return;
        }
        float ruleY = drawHeader(c, w, h);
        float padY = h * 0.055f;
        float left = w * host.padXRatio;
        float right = w - w * host.padXRatio;

        String key = StatsStore.getKey(host.getContext());
        if (key.length() == 0) {
            drawCentered(c, (left + right) / 2f, h * 0.46f, "还未配置微信读书 API Key", SZ_EMPTY * host.unit, INK);
            drawCentered(c, (left + right) / 2f, h * 0.46f + SZ_EMPTY * host.unit * 1.7f,
                    "点下方「设置」按引导粘贴 Key", SZ_SUB * host.unit, GRAY);
            return;
        }

        // 「本书」形态走自己的分支：数据是 BookStats，没有周期、没有柱状图/日历
        if (host.isBook()) {
            drawBookBody(c, w, h, left, right, padY, ruleY);
            return;
        }

        // 「本记」形态（v0.4.0）：一条随机划线，数据走 NoteStore
        if (host.isNote()) {
            drawNoteBody(c, w, h, left, right, padY, ruleY);
            return;
        }

        // 「待办」形态（V1.0.3-beta，TASK-024）：未完成清单，数据走 TodoStore
        if (host.isTodo()) {
            drawTodoBody(c, w, h, left, right, padY, ruleY);
            return;
        }

        if (host.errorText != null && host.stats == null) {
            drawCentered(c, (left + right) / 2f, h * 0.40f, "获取失败", SZ_EMPTY * host.unit, INK);
            wrapCentered(c, (left + right) / 2f, h * 0.50f, (right - left) * 0.96f, host.errorText,
                    SZ_SUB * host.unit, GRAY);
            drawCentered(c, (left + right) / 2f, h * 0.66f, "点下方「刷新」重试", SZ_SUB * host.unit, GRAY);
            return;
        }

        if (host.stats == null) {
            drawCentered(c, (left + right) / 2f, h * 0.46f,
                    host.refreshing ? ("正在获取" + (host.isMonthly() ? "本月" : "本周") + "数据…")
                               : "暂无数据，点下方「刷新」",
                    SZ_SUB * host.unit, GRAY);
            return;
        }

        // 历史周期没数据 → 明说一句，**不画全空网格**（否则会被当成加载失败，设计方案 §6.4）。
        // 抬头（含周期名与「更新于」）已经由上面 drawHeader 画好，用户仍知道自己在看哪一段。
        if (host.emptyNote != null) {
            drawCentered(c, (left + right) / 2f, h * 0.50f, host.emptyNote, SZ_EMPTY_FULL * host.unit, INK);
            return;
        }

        if (host.isMonthly()) {
            if (host.fullscreen) drawMonthFullBody(c, w, h, left, right, padY, ruleY);
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
     * 图标一律用 Path / 圆弧**画出来**，不用字符 —— 本机字体不一定带这些码位。
     *
     * @return 分隔线的 y（正文从这里往下排）
     */
    private float drawHeader(Canvas c, float w, float h) {
        float padX = w * host.padXRatio;
        // 上沿定位基准：桌面卡片展开态锚定在收起态高度（346），标题/分隔线不随展开下移
        //（用户 2026-09-28 拍板「展开时上半部分保持不动」）。其余形态本式与旧版逐位等价：
        // 周/月/本书 h 恒 = cardHeight()，App 全屏档 isNoteExpanded() 恒 false。
        float padY = (host.isNoteExpanded() ? CardSpec.cardHeight() : h) * 0.055f;
        float left = padX;
        float right = w - padX;

        float titleSize = SZ_TITLE * host.unit;
        float upSize = SZ_UPDATED * host.unit;
        String title = host.layout.titleText();
        String up = host.refreshing ? "刷新中…" : host.layout.updatedLabel();

        // 图标尺寸：跟着抬头字号走，再钳进 [10, 20] px —— 太小看不清，太大压过文字
        float icon = titleSize * 0.62f;
        if (icon < 10f) icon = 10f;
        if (icon > 20f) icon = 20f;
        float gapI = Math.max(3f, icon * 0.25f);

        host.p.setStyle(Paint.Style.FILL);
        host.p.setColor(INK);
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setFakeBoldText(true);
        host.p.setTextSize(titleSize);
        float titleW = host.p.measureText(title);

        float upW = 0f;
        // 抬头与右上时间不许打架：宽度不够就把时间字缩小（要扣掉两个图标占的位置）
        if (up.length() > 0) {
            host.p.setTextSize(upSize);
            upW = host.p.measureText(up);
            float avail = (right - left) - (icon + gapI) - titleW - (icon + gapI * 2f) - 10f;
            if (avail > 0 && upW > avail) {
                upSize = Math.max(SZ_UPDATED * host.unit * 0.62f, upSize * avail / upW);
                host.p.setTextSize(upSize);
                upW = host.p.measureText(up);
            }
        }

        float titleY = padY + titleSize;
        float iconTop = titleY - titleSize * 0.36f - icon / 2f;

        // 左：切换图标 + 抬头文字
        drawSwitchIcon(c, left, iconTop, icon);
        host.p.setTextSize(titleSize);
        c.drawText(title, left + icon + gapI, titleY, host.p);
        host.p.setFakeBoldText(false);

        // 右：更新时间（贴右沿）+ 刷新图标（在它左边）
        float iconX = right - icon;
        if (up.length() > 0) {
            host.p.setColor(GRAY);
            host.p.setTextSize(upSize);
            host.p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(up, right, titleY, host.p);
            iconX = right - upW - gapI * 2f - icon;
        }
        drawRefreshIcon(c, iconX, iconTop, icon);
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setColor(INK);

        // 抬头下的分隔线：两端就是卡片的左右边缘
        float ruleY = titleY + padY * 0.30f;
        host.p.setStrokeWidth(2f);
        host.p.setStyle(Paint.Style.STROKE);
        c.drawLine(left, ruleY, right, ruleY, host.p);
        host.p.setStyle(Paint.Style.FILL);
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

        host.p.setStyle(Paint.Style.STROKE);
        host.p.setStrokeWidth(Math.max(1.4f, s * 0.11f));
        host.p.setStrokeCap(Paint.Cap.BUTT);
        host.p.setColor(INK);

        // 上：指向右
        c.drawLine(x0, cy1, x1 - head * 0.6f, cy1, host.p);
        host.p.setStyle(Paint.Style.FILL);
        host.path.reset();
        host.path.moveTo(x1, cy1);
        host.path.lineTo(x1 - head, cy1 - half);
        host.path.lineTo(x1 - head, cy1 + half);
        host.path.close();
        c.drawPath(host.path, host.p);

        // 下：指向左
        host.p.setStyle(Paint.Style.STROKE);
        c.drawLine(x1, cy2, x0 + head * 0.6f, cy2, host.p);
        host.p.setStyle(Paint.Style.FILL);
        host.path.reset();
        host.path.moveTo(x0, cy2);
        host.path.lineTo(x0 + head, cy2 - half);
        host.path.lineTo(x0 + head, cy2 + half);
        host.path.close();
        c.drawPath(host.path, host.p);

        host.p.setStyle(Paint.Style.FILL);
    }

    /**
     * 「刷新」图标：一段开口的圆弧 + 箭头，画在右上角「更新于…」**左边**。
     *
     * 圆弧从 30° 扫到 330°（顺时针，缺口留在正右方），箭头画在终点 330° 处、
     * 朝向切线方向 —— 这样缺口和箭头不会叠在一起。
     */
    private void drawRefreshIcon(Canvas c, float x, float y, float s) {
        float sw = Math.max(1.4f, s * 0.11f);
        host.p.setStyle(Paint.Style.STROKE);
        host.p.setStrokeWidth(sw);
        host.p.setColor(INK);

        RectF arc = new RectF(x + sw / 2f, y + sw / 2f, x + s - sw / 2f, y + s - sw / 2f);
        c.drawArc(arc, 30f, 300f, false, host.p);

        // 终点 330° 处的箭头（顺时针方向的切线 = 角度 +90°）
        double a = Math.toRadians(330);
        double t = Math.toRadians(330 + 90);
        float cx = x + s / 2f, cy = y + s / 2f, r = s / 2f - sw / 2f;
        float px = cx + (float) (r * Math.cos(a));
        float py = cy + (float) (r * Math.sin(a));
        float dx = (float) Math.cos(t), dy = (float) Math.sin(t);
        float len = s * 0.30f, half = s * 0.19f;

        host.p.setStyle(Paint.Style.FILL);
        host.path.reset();
        host.path.moveTo(px + dx * len * 0.5f, py + dy * len * 0.5f);
        host.path.lineTo(px - dy * half - dx * len * 0.5f, py + dx * half - dy * len * 0.5f);
        host.path.lineTo(px + dy * half - dx * len * 0.5f, py - dx * half - dy * len * 0.5f);
        host.path.close();
        c.drawPath(host.path, host.p);
        host.p.setStyle(Paint.Style.FILL);
    }

    // ══════════════════════ 周：7 根柱状图（原有实现，未改动） ══════════════════════

    private void drawWeekBody(Canvas c, float w, float h, float left, float right,
                              float padY, float ruleY) {
        // 「今天」只在**当前周**里存在。看历史周时 todayIdx 必须取 -1 ——
        // 否则 PeriodStats.todayIndex() 会把"整周已过去"钳成 6，周日被误画成今天（实心黑柱）。
        boolean isCur = host.stats.isCurrentPeriod();
        int todayIdx = isCur ? host.stats.todayIndex() : -1;

        // ── 成就行开启判定（v0.9，TASK-013）──
        // 成就行一开，贴底位让给它、统计行上移一行 ⇒ 上方要让出一块高度。
        // 让法（TASK-013-R2 拍板「43/57」）：让出的总高度 D 里，上方（主数字字号 + 柱顶）
        // 承担 43%，柱状图只承担剩下的 57% —— R1 是柱状图 100% 独扛。
        boolean achvOn = host.achievementText != null && isCur;
        float give = achvOn ? h * ACHV_GIVE_TOTAL * ACHV_UP_SHARE : 0f;

        // ── 主数字：本周总时长 ──
        // 字号缩小 give ⇒ 字块底部（= 基线 bigY）随之上移 give，与下方 chartTop 的上移量一致
        // （字块顶部 `ruleY + padY×0.45` 不动，只有底边上收）。
        float bigSize = SZ_BIG * host.unit - give;
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setTextSize(bigSize);
        host.p.setFakeBoldText(true);
        String big = CardLayout.fmtTotal(host.stats.totalSec);
        float bigY = ruleY + bigSize + padY * 0.45f;
        c.drawText(big, (left + right) / 2f - host.p.measureText(big) / 2f, bigY, host.p);
        host.p.setFakeBoldText(false);

        // ── 7 天柱状图 ──
        // 成就行开启：柱底由 0.78 压到 0.685（0.685 = TASK-015 实测定死，0.70 时净空仅 7px 偏紧），
        // 再把上方让出的 give 还回来 ⇒ 柱状图**实际只压 (D − give)**，即总让出量的 57%。
        // 柱顶同步上移 give，贴住缩小后的主数字底边。
        // 关闭（achievementText == null）时 give = 0，走原值 —— 与 v0.8.0 逐像素一致。
        float chartTop = h * 0.40f - give;
        float chartBottom = achvOn ? h * 0.685f + give : h * 0.78f;
        float chartH = chartBottom - chartTop;
        float slot = (right - left) / 7f;
        float barW = slot * 0.5f;

        int maxSec = 3600;
        int upto = (todayIdx >= 0) ? todayIdx : 6;         // 历史周：7 根柱一起参与定标
        for (int i = 0; i <= upto; i++) maxSec = Math.max(maxSec, host.stats.daySec[i]);

        float daySize = SZ_DAY * host.unit;

        for (int i = 0; i < 7; i++) {
            float cx = left + slot * i + slot / 2f;
            boolean future = isCur && i > todayIdx;        // 历史周：没有"未来"，7 天全画
            int sec = host.stats.daySec[i];

            if (!future) {
                float bh = chartH * (sec / (float) maxSec);
                if (sec > 0 && bh < h * 0.008f) bh = h * 0.008f;
                RectF bar = new RectF(cx - barW / 2f, chartBottom - bh, cx + barW / 2f, chartBottom);
                if (i == todayIdx) {
                    host.p.setStyle(Paint.Style.FILL);
                    host.p.setColor(INK);
                    c.drawRect(bar, host.p);
                } else if (sec > 0) {
                    host.p.setStyle(Paint.Style.FILL);
                    host.p.setColor(0xFFFFFFFF);
                    c.drawRect(bar, host.p);
                    host.p.setStyle(Paint.Style.STROKE);
                    host.p.setColor(INK);
                    c.drawRect(bar, host.p);
                }
                if (sec > 0) {
                    String label = CardLayout.fmtShort(sec);
                    float barSize = SZ_BAR * host.unit;
                    host.p.setTextSize(barSize);
                    float lw = host.p.measureText(label);
                    float maxW = slot * 0.98f;
                    if (lw > maxW) {                  // 槽很窄，标长了就让一让，别压到邻槽
                        barSize = Math.max(SZ_BAR * host.unit * 0.7f, barSize * maxW / lw);
                        host.p.setTextSize(barSize);
                    }
                    host.p.setTextAlign(Paint.Align.CENTER);
                    host.p.setColor(GRAY);
                    host.p.setStyle(Paint.Style.FILL);
                    c.drawText(label, cx, chartBottom - bh - barSize * 0.35f, host.p);
                }
            } else {
                host.p.setStyle(Paint.Style.STROKE);
                host.p.setColor(LIGHT);
                c.drawLine(cx - barW / 2f, chartBottom, cx + barW / 2f, chartBottom, host.p);
            }

            host.p.setStyle(Paint.Style.FILL);
            host.p.setTextAlign(Paint.Align.CENTER);
            host.p.setTextSize(daySize);
            host.p.setColor(i == todayIdx ? INK : (future ? LIGHT : GRAY));
            // 成就行开启时行距 1.5 → 1.30：柱底已回退 give，靠这条把「星期底 → 统计行顶」
            // 的净空补回 7px 量级（43/57 能装下的前提，见 ACHV_UP_SHARE 注释）。
            c.drawText(DAY_NAMES[i], cx,
                    chartBottom + daySize * (achvOn ? ACHV_DAY_OFF : 1.5f), host.p);
        }

        // ── 底部统计行（加粗；宽度不够时自动缩字号，绝不出框）──
        StringBuilder sb = new StringBuilder();
        sb.append("阅读 ").append(host.stats.readDays).append("/7 天");
        sb.append("  ·  日均 ").append(CardLayout.fmtShort(host.stats.avgSec));
        if (host.stats.compare != null && !host.stats.compare.isNaN()) {
            double v = host.stats.compare;
            sb.append("  ·  较上周 ").append(v >= 0 ? "↑" : "↓")
              .append(Math.round(Math.abs(v) * 100)).append("%");
        }
        // 成就行开启时统计行上移一行，贴底位让给成就行（v0.9）。
        // 1.6 → 1.30（R2）：与 ACHV_DAY_OFF 配套收紧，给回退后的柱底腾出净空。
        float lineY = h - padY * 0.85f - (achvOn ? ACHV_SHIFT_MUL * SZ_SUB * host.unit : 0f);
        drawBottomLine(c, (left + right) / 2f, lineY, left, right, sb.toString());
        // 🔴 **TASK-058**：原 TASK-047（K2）的「阅读摘要行」已删除 —— 周页该行本就因上游
        //     `mode=weekly` 回包不含 `readStat` 而**恒空**（TASK-047 的 A1 已定论）⇒
        //     周页版面回到「柱图 / 星期 / 统计行」三件套，与加 K2 之前逐像素等价。
        if (achvOn) drawAchievementLine(c, (left + right) / 2f, h, padY, host.achievementText);
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
        int dayCount = host.stats.dayCount;
        if (dayCount <= 0 || dayCount > 31) dayCount = 31;
        int todayIdx = host.stats.todayIndex();

        // ── 网格几何 ──
        float cell = CardSpec.MONTH_CELL;
        float gap = CardSpec.MONTH_GAP;
        float rowGap = CardSpec.MONTH_ROW_GAP;
        float gridW = CardSpec.MONTH_GRID_W;
        float gridRight = w - CardSpec.MONTH_GRID_RIGHT_PAD;
        float gridLeft = gridRight - gridW;
        float headSize = SZ_CAL_HEAD * host.unit;
        float headH = headSize * 1.35f;

        int firstOffset = PeriodRange.firstWeekdayIndex(host.stats.baseTime);
        int rows = (firstOffset + dayCount + 6) / 7;            // 上取整
        float gridH = headH + gap + rows * cell + (rows - 1) * rowGap;

        // 成就行开启（v0.9，TASK-013）：左栏与网格整体上收一行，贴底位让给成就行；
        // 关闭走原值 —— 逐像素一致（TASK-015 实测月卡富余 56px，上收一行很安全）
        boolean achvOn = host.achievementText != null && host.stats.isCurrentPeriod();
        float contentTop = ruleY + padY * 0.55f;
        float contentBottom = h - padY * 0.90f - (achvOn ? 1.6f * SZ_SUB * host.unit : 0f);
        float gridTop = contentTop + Math.max(0f, (contentBottom - contentTop - gridH) / 2f);

        // ── 表头（一…日，不随月份变化）──
        host.p.setStyle(Paint.Style.FILL);
        host.p.setTextAlign(Paint.Align.CENTER);
        host.p.setTextSize(headSize);
        host.p.setColor(GRAY);
        float headY = gridTop + headSize;
        for (int i = 0; i < 7; i++) {
            float cx = gridLeft + i * (cell + gap) + cell / 2f;
            c.drawText(CAL_HEAD[i], cx, headY, host.p);
        }

        // ── 日期格 ──
        float cellTop0 = gridTop + headH + gap;
        for (int i = 0; i < dayCount; i++) {
            int slot = firstOffset + i;
            float x = gridLeft + (slot % 7) * (cell + gap);
            float y = cellTop0 + (slot / 7) * (cell + rowGap);
            boolean past = todayIdx >= 0 && i <= todayIdx;

            if (!past) continue;                                 // 未来 → 留白
            if (host.monthHeatmap) drawHeatCell(c, x, y, cell, host.stats.daySec[i]);
            else                   drawCalCell(c, x, y, cell, host.stats.daySec[i] > 0);
        }

        // ── 左栏：主数字 / 日均阅读 / 较上月 ──
        float leftW = CardSpec.MONTH_LEFT_W;
        String big = CardLayout.fmtTotal(host.stats.totalSec);
        float bigSize = SZ_BIG * host.unit;
        host.p.setStyle(Paint.Style.FILL);
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setColor(INK);
        host.p.setFakeBoldText(true);
        host.p.setTextSize(bigSize);
        while (host.p.measureText(big) > leftW && bigSize > SZ_BIG * host.unit * 0.55f) {
            bigSize -= 0.5f;
            host.p.setTextSize(bigSize);
        }

        String l2 = "日均阅读 " + CardLayout.fmtTotal(host.stats.avgSec);
        String l3 = null;
        if (host.stats.compare != null && !host.stats.compare.isNaN()) {
            double v = host.stats.compare;
            l3 = "较上月 " + (v >= 0 ? "↑" : "↓") + Math.round(Math.abs(v) * 100) + "%";
        }
        float subSize = SZ_SUB * host.unit;
        float subLine = subSize * 1.45f;
        int lines = (l3 == null) ? 2 : 3;
        float totalH = bigSize + subLine * (lines - 1);
        float top = contentTop + Math.max(0f, (contentBottom - contentTop - totalH) / 2f);

        float baseBig = top + bigSize * 0.82f;
        c.drawText(big, left, baseBig, host.p);
        host.p.setFakeBoldText(false);
        host.p.setTextSize(subSize);
        host.p.setColor(GRAY);
        float base2 = baseBig + subLine;
        c.drawText(l2, left, base2, host.p);
        if (l3 != null) c.drawText(l3, left, base2 + subLine, host.p);

        // ── 底部统计行：本月只说"读了多少天"（日均/较上月已在左栏）──
        float lineY = h - padY * 0.85f - (achvOn ? 1.6f * SZ_SUB * host.unit : 0f);
        drawBottomLine(c, (left + right) / 2f, lineY, left, right,
                "阅读 " + host.stats.readDays + "/" + dayCount + " 天");
        if (achvOn) drawAchievementLine(c, (left + right) / 2f, h, padY, host.achievementText);

        host.p.setColor(INK);
        host.p.setTextAlign(Paint.Align.LEFT);
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
        int dayCount = host.stats.dayCount;
        if (dayCount <= 0 || dayCount > 31) dayCount = 31;
        int todayIdx = host.stats.todayIndex();
        float availW = right - left;

        // 🆕 TASK-054（K9）：本函数从"边算边画"改成**「先算几何 → 再统一绘制」**，
        // 因为「整页可滚」要求**在落笔之前**就知道内容总高（才知道滚多远、要不要滚）。
        // 🔴 几何公式与加 K9 之前逐字一致（TASK-058 之后连 K2 的让位/缩格分支也已移除），
        //    只在末尾多了 ③ 排名区。

        // ── ① 信息块（上方）[几何]：主数字字号自适应仍在这里做，绘制段直接用结果 ──
        String big = CardLayout.fmtTotal(host.stats.totalSec);
        float bigSize = SZ_BIG_FULL * host.unit;
        float top = ruleY + padY * 1.05f;

        host.p.setStyle(Paint.Style.FILL);
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setColor(INK);
        host.p.setFakeBoldText(true);
        host.p.setTextSize(bigSize);
        float bigMaxW = availW * 0.52f;              // 右列要占剩下那半
        while (host.p.measureText(big) > bigMaxW && bigSize > SZ_BIG_FULL * host.unit * 0.45f) {
            bigSize -= 1f;
            host.p.setTextSize(bigSize);
        }

        // 右列竖排：日均阅读 / 较上月 / 阅读 x/y 天（文案先算好，绘制段统一画）
        float subSize = SZ_SUB_FULL * host.unit;
        float subPitch = subSize * 1.34f;
        float subX = left + availW * 0.54f;
        float subBase = top + subSize * 1.05f;
        boolean hasCmp = host.stats.compare != null && !host.stats.compare.isNaN();
        int subLines = hasCmp ? 2 : 1;               // 「日均」1 行 +（可选）「较上月」1 行
        String cmpText = hasCmp
                ? "较上月 " + (host.stats.compare >= 0 ? "↑" : "↓")
                        + Math.round(Math.abs(host.stats.compare) * 100) + "%"
                : null;
        String daysText = "阅读 " + host.stats.readDays + "/" + dayCount + " 天";

        float blockBottom = Math.max(top + bigSize, subBase + subPitch * subLines);

        // ── ② 大号日历（几何；自适应，🔴 格边长公式一字未改）──
        float gap = MONTH_FULL_GAP;
        float rowGap = MONTH_FULL_ROW_GAP;
        float headSize = SZ_CAL_HEAD_FULL * host.unit;
        float headH = headSize * 1.45f;

        int firstOffset = PeriodRange.firstWeekdayIndex(host.stats.baseTime);
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

        // 🔴 **TASK-058**：原 TASK-047（K2）的「摘要行」与它的**让位/缩格分支**已一并删除。
        //     实测口径：该分支在 2/5/6 行月**从未触发过**（可用下沿取卡片真实下沿 `h` 后，
        //     日历下方那 `padY×0.85 ≈ 34px` 白给的内边距本就够放一行）⇒ 删掉后日历几何
        //     与加 K2 之前**逐像素等价**。日历仍按「可用高反算 → 宽度兜底 → MONTH_FULL_CELL 封顶」
        //     居中摆放；某个月真挤不下时由 ③ 排名区的**整页滚动**（K9）容纳，不再缩格。

        // ── ③ 🆕 TASK-054（K9）「本月各书籍阅读排名」（几何）──
        // 紧贴**日历下沿**（TASK-058 之后摘要行已不在，排名区整体上移一行）。
        java.util.List<PeriodStats.Longest> rankItems = monthRankItems();
        float rankRowH = RANK_ROW_H * host.unit;            // ≈32px
        float rankTitleH = RANK_SZ * host.unit * 1.9f;
        // 与日历格底留 **半个字高** 净空（沿用 TASK-047 摘要行「不贴格底」的观感口径）。
        float rankTop = gridTop + gridH + rankTitleH * 0.5f;
        int rankRows = Math.max(1, rankItems.size());       // 无数据也占一行（那行画空态文案）
        float rankH = rankTitleH + rankRows * rankRowH;
        float contentH = rankTop + rankH + padY * 0.85f;    // 内容总高（含底部内边距）

        // 🔴 **装得下就不滚**：monthScrollMax = 0 ⇒ 下面的 translate 恒为 0
        //    ⇒ 本页与加本卡之前**逐像素等价**（卡面 R2 的「保守做法」：格边长不缩，靠滚动容纳）。
        host.monthScrollMax = Math.max(0f, contentH - h);
        float sy = host.monthScrollY;
        if (sy > host.monthScrollMax) sy = host.monthScrollMax;
        if (sy < 0f) sy = 0f;

        // 抬头（含分隔线）**固定不动**：只把分隔线以下的**正文区**整体上移 sy 并裁剪到视口内。
        c.save();
        c.clipRect(0f, ruleY + 1f, w, h);
        c.translate(0f, -sy);

        // ══ ① [绘制] 信息块 ══
        c.drawText(big, left, top + bigSize, host.p);
        host.p.setFakeBoldText(false);
        host.p.setTextSize(subSize);
        host.p.setColor(GRAY);
        c.drawText("日均阅读 " + CardLayout.fmtTotal(host.stats.avgSec), subX, subBase, host.p);
        if (cmpText != null) c.drawText(cmpText, subX, subBase + subPitch, host.p);
        c.drawText(daysText, subX, subBase + subPitch * subLines, host.p);

        // ══ ② [绘制] 大号日历 ══
        host.p.setStyle(Paint.Style.FILL);
        host.p.setTextAlign(Paint.Align.CENTER);
        host.p.setTextSize(headSize);
        host.p.setColor(GRAY);
        float headY = gridTop + headSize;
        for (int i = 0; i < 7; i++) {
            float cx = gridLeft + i * (cell + gap) + cell / 2f;
            c.drawText(CAL_HEAD[i], cx, headY, host.p);
        }

        float cellTop0 = gridTop + headH + gap;
        for (int i = 0; i < dayCount; i++) {
            if (!(todayIdx >= 0 && i <= todayIdx)) continue;         // 未来 → 留白
            int slot = firstOffset + i;
            float x = gridLeft + (slot % 7) * (cell + gap);
            float y = cellTop0 + (slot / 7) * (cell + rowGap);
            if (host.monthHeatmap) drawHeatCell(c, x, y, cell, host.stats.daySec[i]);
            else                   drawCalCell(c, x, y, cell, host.stats.daySec[i] > 0);
        }

        // ══ ③ [绘制] 排名区（🆕 TASK-054 K9）══
        //     （原 ②.5「摘要行」阶段随 TASK-058 删除 —— 本月页版面 = 信息块 + 日历 + 排名区）
        drawMonthRank(c, left, right, rankTop, rankTitleH, rankRowH, rankItems);

        c.restore();

        host.p.setColor(INK);
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setStyle(Paint.Style.FILL);
    }

    /**
     * 🆕 TASK-054（K9）：月全屏页的「**本月各书籍阅读排名**」。
     *
     * 版面（一行一本书）：`名次 | 书名（缩字 → 省略号） | 时长`，行高 = {@link #RANK_ROW_H}（≈32px）。
     *
     * 🔴 **零排序** —— 收到的列表已是服务端 `readTime` 降序、且已滤掉 ≤5 分钟的项（卡面 E4）⇒
     * App 侧不写一行排序代码。（🆕 TASK-059 的**书架过滤**发生在**取料处** {@link #monthRankItems()}，
     * 只做"剔除"、不改顺序 ⇒ 名次始终是服务端口径。）
     *
     * 缺书名（回包只有 `albumInfo`、没有 `book`）的条目**不丢行** —— 画「（未知书名）」占位，
     * 否则名次会整体上移、与回包原序对不上（与 TASK-053 K8 同款处理）。
     */
    private void drawMonthRank(Canvas c, float left, float right, float top,
                               float titleH, float rowH,
                               java.util.List<PeriodStats.Longest> items) {
        float sz = RANK_SZ * host.unit;

        host.p.setStyle(Paint.Style.FILL);
        host.p.setFakeBoldText(false);
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setColor(INK);
        host.p.setTextSize(sz);
        c.drawText(MONTH_RANK_TITLE, left, top + sz * 0.95f, host.p);

        int n = (items == null) ? 0 : items.size();
        if (n == 0) {
            // 空态（A7）：本月一条 ranking 都没有 ⇒ 明说一句，别留一片空白让人误判为"画漏了"
            host.p.setColor(GRAY);
            c.drawText(MONTH_RANK_EMPTY, left, top + titleH + sz * 1.25f, host.p);
            return;
        }

        float numW = RANK_NUM_W * host.unit;
        float durGap = RANK_DUR_GAP * host.unit;
        for (int i = 0; i < n; i++) {
            PeriodStats.Longest it = items.get(i);
            float base = top + titleH + (i + 0.74f) * rowH;

            // 名次（灰；固定槽宽 ⇒ 所有书名起点对齐）
            host.p.setTextSize(sz);
            host.p.setColor(GRAY);
            host.p.setTextAlign(Paint.Align.LEFT);
            c.drawText(String.valueOf(i + 1), left, base, host.p);

            // 时长（右对齐；先量宽，倒推书名可用宽）
            String dur = CardLayout.fmtTotal(it == null ? 0 : it.readTime);
            host.p.setTextAlign(Paint.Align.RIGHT);
            float durW = host.p.measureText(dur);
            c.drawText(dur, right, base, host.p);

            // 书名（INK 主色；先缩字、仍放不下才「…」）
            String title = (it == null || it.title == null || it.title.length() == 0)
                    ? MONTH_RANK_UNKNOWN : it.title;
            host.p.setTextAlign(Paint.Align.LEFT);
            host.p.setColor(INK);
            float avail = (right - durW - durGap) - (left + numW);
            float ts = sz;
            host.p.setTextSize(ts);
            while (host.p.measureText(title) > avail && ts > sz * 0.62f) {
                ts -= 0.4f;
                host.p.setTextSize(ts);
            }
            String show = (host.p.measureText(title) > avail)
                    ? host.layout.ellipsize(title, avail) : title;   // 用当前字号量，口径一致
            c.drawText(show, left + numW, base, host.p);
        }
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setColor(INK);
    }

    /**
     * 🆕 TASK-054（K9）：本月排名的料 —— 取 {@code stats.longest}（**零排序**）。
     *
     * <p>🆕 **TASK-059**：在"原序"之上加一道**可选**的书架过滤（「只统计书架上的书」开关）——
     * 开关关 / 无全量书架清单时 {@link RankFilter#apply} 原样返回 ⇒ 与改造前**零差异**。
     * 过滤只**剔除**、**不重排**（名次仍是服务端给的原始降序）。
     *
     * <p>null 安全：老缓存 / 非 monthly 回包 ⇒ 空集合 ⇒ 排名区画空态。
     */
    private java.util.List<PeriodStats.Longest> monthRankItems() {
        if (host.stats == null || host.stats.longest == null) {
            return java.util.Collections.emptyList();
        }
        return RankFilter.apply(host.getContext(), host.stats.longest);
    }

    /** 2×2 Bayer 序，索引 = `(row % 2) * 2 + (col % 2)`。正交 2px 点阵的 rank 依据。 */
    private static final int[] sBayer2 = { 0, 2, 3, 1 };

    /**
     * 方案 D（正交 2px 点阵）的**点序**：把 inner×inner 的每个像素按 rank 升序排列
     * （rank-order dithering）。取该序前 k 个涂黑 ⇒ 黑度**精确** = k/inner²，
     * 且黑点按 2px 正交节奏均匀铺开 ⇒ 观感是均匀细密的灰，而非能"数出格/纹"的粗网点。
     *
     * rank 判据 = **2×2 Bayer 序**（{@link #sBayer2}），它与"正交 2px 点阵"等价：
     * · 25% 档取 rank=0 ⇒ 每 2×2 块 1 个黑点（2px 单点阵）；
     * · 50% 档取 rank≤1 ⇒ 2×2 块内黑 **对角**两点（2px 棋盘，最均匀）；
     * · 75% 档取 rank≤2 ⇒ 2×2 块内黑 3 点。
     * 4 个 rank 在块内各占 1/4，故 inner 为偶数时黑点数 = `round(inner²·pct/100)` **逐点精确**。
     *
     * 🔴 **为什么不用"离最近格点距离"排序**（那是方案 C 的做法）：
     * 正交 2px 点阵下，2×2 块内 4 个点到最近格点的距离只有 {0,1,1,2} 三档，且两个 d=1 的点
     * 恰在**对角** —— 若按距离序拔点，50% 档会取到"左上 + 右上/左下"⇒ **竖/横条纹**
     * （方向偏置）。改用 Bayer 序后 50% 取**对角**两点 ⇒ 各向同性（R4 离线样图已对比）。
     *
     * 🔴 **为什么按目标像素直接生成，而不做"小位图拉伸"**：
     * 卡片格 20px 对 16×16 是 `20 ÷ 16 = 1.25` 非整除，最近邻拉伸必然产生横向条纹
     * （TASK-014-R2 样图已证）。直接按目标尺寸生成 ⇒ 无拉伸畸变，两种格尺寸都成立。
     *
     * 只在首格绘制时算一次，之后走 {@link #sHtmOrder} 缓存。
     */
    private static int[] htmOrder(int inner) {
        synchronized (sHtmOrder) {
            int[] hit = sHtmOrder.get(inner);
            if (hit != null) return hit;
        }
        final int pitch = CardSpec.HEAT_HTM_PITCH;    // 2（正交点阵周期）
        final int n = inner * inner;
        long[] key = new long[n];
        Integer[] idx = new Integer[n];
        for (int r = 0; r < inner; r++) {
            for (int cc = 0; cc < inner; cc++) {
                // pitch=2 → 用 2×2 Bayer 序；其它 pitch 退化为"块内顺序 rank"（仍可用）
                int rank = (pitch == 2)
                        ? sBayer2[(r % 2) * 2 + (cc % 2)]
                        : (r % pitch) * pitch + (cc % pitch);
                key[r * inner + cc] = rank;
                idx[r * inner + cc] = r * inner + cc;
            }
        }
        java.util.Arrays.sort(idx, (a, b) -> Long.compare(key[a], key[b]));  // 稳定排序
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = idx[i];
        synchronized (sHtmOrder) { sHtmOrder.put(inner, out); }
        return out;
    }

    /** 缓存的正交点阵点序（按 inner 边长缓存，只在首格绘制时算一次）。 */
    private static final java.util.HashMap<Integer, int[]> sHtmOrder = new java.util.HashMap<>();

    /**
     * 各档图案位图（懒加载，只建一次）。key = `inner×100 + pct`（pct = 25/50/75/100）。
     * 位图尺寸 = 内区边长 inner（**1:1 绘制，不做拉伸**）；0 档不建（空心框）、4 档走实心。
     *
     * 🔴 必须 `setAntiAlias(false)` + `setFilterBitmap(false)`：
     * 否则会插值出**灰边**，违反 `docs/03` §1.3「只用纯黑白」硬规则。
     */
    private final java.util.HashMap<Integer, android.graphics.Bitmap> heatPatterns =
            new java.util.HashMap<>();

    /**
     * TASK-014 热力图格：按当日秒数定档 → 内区画图案（方案 D 正交 2px 点阵）→ 覆 1px **黑框**。
     *
     * 与 {@link #drawCalCell}（打卡格）并列、互斥：热力图模式下**不再画对勾**。
     *
     * 档位由 {@link CardSpec#HEAT_TIERS_SEC} 决定，图案区黑度由 {@link CardSpec#HEAT_LEVELS} 决定：
     * · 0 档（0 分钟）→ **空心黑框**（框内纯白，让"这天存在"可见，用户 2026-09-27 改）；
     * · 1..3 档 → 正交 2px 点阵（25 / 50 / 75%）；
     * · 4 档（> 3 小时）→ 实心黑方块（{@link CardSpec#HEAT_TOP_TIER_SOLID}）。
     *
     * 🔴 图案画在**内区**（扣掉 1px 框后的 `inner×inner`），且 **1:1 不拉伸** ——
     * 这样内区黑度精确等于档位（验收口径同此），也彻底避免"小图拉伸出条纹"。
     * 🔴 **每格都画框**（含 0 档）：参考同类墨水屏热力图，黑框形成"方块阵列"的秩序感。
     */
    private void drawHeatCell(Canvas c, float x, float y, float cell, int sec) {
        int level = heatLevel(sec);

        // 整数像素对齐（否则关闭抗锯齿后仍会有 1px 抖动）
        int ix = Math.round(x), iy = Math.round(y);
        int isz = Math.round(cell);
        int bw = CardSpec.HEAT_CELL_BORDER_PX;
        int inner = isz - bw * 2;                     // 图案区边长（扣框）

        if (level >= 4 && CardSpec.HEAT_TOP_TIER_SOLID) {
            host.p.setStyle(Paint.Style.FILL);
            host.p.setColor(INK);
            c.drawRect(ix, iy, ix + isz, iy + isz, host.p);       // 实心（连框位一起全黑）
            return;
        }

        if (level >= 1 && inner > 0) {                // 1..3 档：内区画正交 2px 点阵（1:1）
            android.graphics.Bitmap pat = heatPattern(inner, CardSpec.HEAT_LEVELS[level]);
            if (pat != null) {
                host.p.setStyle(Paint.Style.FILL);
                host.p.setColor(INK);
                // 关抗锯齿 + 关过滤，保证只有纯黑白（硬规则）
                boolean aa = host.p.isAntiAlias(), fl = host.p.isFilterBitmap();
                host.p.setAntiAlias(false);
                host.p.setFilterBitmap(false);
                c.drawBitmap(pat, ix + bw, iy + bw, host.p);       // 1:1，不缩放
                host.p.setAntiAlias(aa);
                host.p.setFilterBitmap(fl);
            }
        }
        // level == 0：内区纯白（不填），只留下面画的框

        // ── 每格描边：**内描边** ⇒ 格子外沿尺寸不变（不侵占 gap，用户要求"间距不变"）──
        if (bw > 0 && isz > bw * 2 + 2) {
            host.p.setStyle(Paint.Style.STROKE);
            host.p.setStrokeWidth(bw);
            host.p.setColor(CardSpec.HEAT_CELL_BORDER_INK ? INK : 0xFFFFFFFF);
            host.p.setAntiAlias(false);
            float half = bw / 2f;
            c.drawRect(ix + half, iy + half, ix + isz - half, iy + isz - half, host.p);
            host.p.setStyle(Paint.Style.FILL);
            host.p.setAntiAlias(true);
        }
    }

    /**
     * 秒数 → 档位（0..4）。对照 {@link CardSpec#HEAT_TIERS_SEC} 的语义表。
     * 独立成方法便于单点调参与日志核对。
     */
    static int heatLevel(int sec) {
        int[] tiers = CardSpec.HEAT_TIERS_SEC;
        if (sec <= tiers[0]) return 0;
        int lvl = 1;
        for (int k = 1; k < tiers.length; k++) {
            if (sec > tiers[k]) lvl = k + 1;
        }
        return lvl;
    }

    /**
     * 取/建某档图案位图（尺寸 = 内区边长 inner，1:1 使用，**不做拉伸**）。
     *
     * 黑点数 = `round(pct × inner² / 100)`，取自 {@link #htmOrder(int)} 的**前 k 个点** ⇒
     * **图案区黑度精确等于目标 pct**（这是验收口径能收紧到 ±5% 的前提）。
     * 位图最大 50×50 像素，创建成本极低；按 `inner×100+pct` 缓存，只建一次。
     *
     * 🔴 **参数语义 = 百分数 pct（1..100），不是档位 0..4**：
     * 调用方传的是 {@link CardSpec#HEAT_LEVELS}[level]（= 25 / 50 / 75 / 100）。
     * 早前 R3 重构把签名从 `heatPattern(int level)` 改成 `heatPattern(int inner, int level)` 时，
     * 漏改了函数内守卫（仍是 `level < 1 || level > 4`）⇒ 传入 25/50/75 全部被 `return null`
     * 拦掉，**1/2/3 档图案完全不画**（只有 4 档走实心分支不受影响）。
     * 该 bug 由 TASK-014-R5 上机复测暴露（离线 Python 复现无此守卫，是自检盲区）；
     * 现按"百分数"语义修正守卫上界为 100。
     */
    private android.graphics.Bitmap heatPattern(int inner, int pct) {
        if (inner <= 0 || pct < 1 || pct > 100) return null;
        int key = inner * 100 + pct;
        android.graphics.Bitmap hit = heatPatterns.get(key);
        if (hit != null) return hit;

        int[] order = htmOrder(inner);
        int n = inner * inner;
        int black = Math.round(n * pct / 100f);       // 25/50/75/100% ⇒ 内区黑点数
        int[] px = new int[n];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        for (int i = 0; i < black && i < n; i++) px[order[i]] = INK;

        android.graphics.Bitmap bmp =
                android.graphics.Bitmap.createBitmap(inner, inner,
                        android.graphics.Bitmap.Config.ARGB_8888);
        bmp.setPixels(px, 0, inner, 0, 0, inner, inner);
        heatPatterns.put(key, bmp);
        return bmp;
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
            host.p.setStyle(Paint.Style.FILL);
            host.p.setColor(INK);
            c.drawRect(x, y, x + cell, y + cell, host.p);
            drawCheck(c, x, y, cell);
        } else {                                      // 未读（已过去）→ 细描边空框
            host.p.setStyle(Paint.Style.STROKE);
            host.p.setStrokeWidth(Math.max(1.5f, cell * 0.055f));
            host.p.setColor(GRAY);
            float in = Math.max(0.75f, cell * 0.035f);
            c.drawRect(x + in, y + in, x + cell - in, y + cell - in, host.p);
            host.p.setStyle(Paint.Style.FILL);
        }
    }

    /** 格子里的白色对勾。20px 的格子只能用细笔画，否则糊成一团（§3.2） */
    private void drawCheck(Canvas c, float x, float y, float cell) {
        float sw = Math.max(1.6f, cell * 0.10f);
        host.p.setStyle(Paint.Style.STROKE);
        host.p.setStrokeWidth(sw);
        host.p.setStrokeCap(Paint.Cap.ROUND);
        host.p.setStrokeJoin(Paint.Join.ROUND);
        host.p.setColor(0xFFFFFFFF);
        host.path.reset();
        host.path.moveTo(x + cell * 0.24f, y + cell * 0.52f);
        host.path.lineTo(x + cell * 0.44f, y + cell * 0.72f);
        host.path.lineTo(x + cell * 0.78f, y + cell * 0.30f);
        c.drawPath(host.path, host.p);
        host.p.setStrokeCap(Paint.Cap.BUTT);
        host.p.setStrokeJoin(Paint.Join.MITER);
        host.p.setStyle(Paint.Style.FILL);
    }

    /**
     * 底部统计行（加粗居中，宽度不够自动缩字号，绝不出框）。两条分支共用。
     * 基线 y 由调用方传入：成就行开启时贴底位让给成就行，统计行上移一行（v0.9，TASK-013）。
     */
    private void drawBottomLine(Canvas c, float cx, float lineY,
                                float left, float right, String text) {
        host.p.setStyle(Paint.Style.FILL);
        host.p.setColor(INK);
        host.p.setTextAlign(Paint.Align.CENTER);
        host.p.setFakeBoldText(true);
        float bSize = SZ_BOTTOM * host.unit;
        host.p.setTextSize(bSize);
        float availW = right - left;
        while (host.p.measureText(text) > availW && bSize > SZ_BOTTOM_MIN * host.unit) {
            bSize -= 0.4f;
            host.p.setTextSize(bSize);
        }
        c.drawText(text, cx, lineY, host.p);
        host.p.setFakeBoldText(false);
        host.p.setTextAlign(Paint.Align.LEFT);
    }

    // 🔴 **TASK-058**：原 TASK-047（K2）的 `drawStatLine` / `hasStatLine` **已删除**。
    //
    // 删除理由（用户 2026-10-06 拍板「两处都删」）：① 周页该行恒空（上游 `mode=weekly`
    // 无 `readStat`）；② 月页那行与洞察页「年度视图 / 累计视图」各自的 `readStat` 行**重复**，
    // 而洞察页的分区①「阅读摘要」占位格也一并下架 ⇒ 全 App 不再有"单独成行的四段摘要"。
    //
    // ⚠️ **注意**：{@code host.stats.readStat}（TASK-046 的解析结果）**没有被删** ——
    // 「年度视图 / 累计视图」两处仍在用（那两处走的是 `InsightRenderer.drawStatLine`，
    // **不是**本类的同名函数）。本类删掉的只是"周/月全屏页底部那一行"。
    //
    // 连带删除：{@link PeriodRange#statRangeWord}（该方法唯一的调用点就在下面这段里）。

    /**
     * 成就行（v0.9，TASK-013）：接管原统计行的贴底位（h − padY×0.85）。
     * 与统计行反着来 —— **非加粗**、副字号（SZ_SUB）、居中，一眼分清主次。
     * 文案由 controller 按偏好算好（{@code AchievementPrefs} / WeekCardView#achievementText），
     * 这里不再判 isCurrentPeriod —— 调用方的 achvOn 已含（controller 侧还过滤了一轮）。
     */
    private void drawAchievementLine(Canvas c, float cx, float h, float padY, String text) {
        host.p.setStyle(Paint.Style.FILL);
        host.p.setColor(INK);
        host.p.setTextAlign(Paint.Align.CENTER);
        host.p.setFakeBoldText(false);
        host.p.setTextSize(SZ_SUB * host.unit);
        c.drawText(text, cx, h - padY * 0.85f, host.p);
        host.p.setTextAlign(Paint.Align.LEFT);
    }

    // ══════════════════════ 本书：阅读进度（v0.3.4 新增） ══════════════════════

    /**
     * 「本书」版式 —— **方案 A「左封右文」**（v0.3.5.1 用户拍板，替代 v0.3.4 的
     * "书名一行 + 三列小字 + 进度条"纯文字版）：
     *
     * <pre>
     *   ┌────────────────────────────────┐
     *   │ ⇄ 本书阅读进度        ⟳ 更新于 │   ← 抬头（两个小图标，见 drawHeader）
     *   ├────────────────────────────────┤
     *   │ ┌──────┐  卡拉马佐夫兄弟        │   ← 封面左立（比例 250:360）
     *   │ │ 封面  │  （套装上下册）…      │   ← 书名折行两行，不再缩字
     *   │ │      │  第三卷 酒色之徒      │   ← 章节名灰字
     *   │ └──────┘  9小时30分       16%  │   ← 时长左 / 百分比右
     *   │ ▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░  │   ← 进度条全宽，贴近底部按钮行
     *   │ ┌──────┐            ┌──────┐ │
     *   │ │ 选书 │            │ 打开 │ │   ← 🆕 TASK-071：左下「选书」/ 右下「打开」
     *   └────────────────────────────────┘
     * </pre>
     *
     * 封面来自 CoverStore（书架数据自带 CDN 直链，按书落盘缓存）；
     * 没拿到时画描边占位框，不阻塞渲染。
     */
    private void drawBookBody(Canvas c, float w, float h, float left, float right,
                              float padY, float ruleY) {
        float availW = right - left;

        // ── 没数据 / 出错 ──
        if (host.book == null) {
            if (host.errorText != null) {
                drawCentered(c, (left + right) / 2f, h * 0.40f, "获取失败", SZ_EMPTY * host.unit, INK);
                wrapCentered(c, (left + right) / 2f, h * 0.50f, availW * 0.96f, host.errorText,
                        SZ_SUB * host.unit, GRAY);
                drawCentered(c, (left + right) / 2f, h * 0.66f,
                        "点" + (host.fullscreen ? "下方「刷新」" : "右上角") + "重试", SZ_SUB * host.unit, GRAY);
            } else {
                drawCentered(c, (left + right) / 2f, h * 0.46f,
                        host.refreshing ? "正在获取本书进度…" : "暂无数据，点右上角刷新",
                        SZ_SUB * host.unit, GRAY);
            }
            return;
        }

        // ══ 方案 A「左封右文」（v0.3.5.1，用户拍板）══
        //   ┌────────────────────────────────┐
        //   │ ⇄ 本书阅读进度        ⟳ 更新于 │
        //   ├────────────────────────────────┤
        //   │ ┌──────┐  卡拉马佐夫兄弟        │   ← 封面左立；书名右侧折行两行（不再缩字）
        //   │ │ 封面  │  （套装上下册）…      │
        //   │ │      │  陀思妥耶夫斯基        │   ← 作者，一行小灰字（过长截断）
        //   │ │      │                      │
        //   │ └──────┘  第三卷 酒色之徒      │   ← 章节名灰字，贴文字区底部
        //   │ └──────┘  9小时30分       16%  │   ← 时长(左) + 百分比(右)，粗体
        //   │ ▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░  │   ← 进度条全宽，下移贴近底部按钮行
        //   │ ┌──────┐            ┌──────┐ │
        //   │ │ 选书 │            │ 打开 │ │   ← 🆕 TASK-071：左下「选书」/ 右下「打开」，同一行
        //   └────────────────────────────────┘
        // 封面宽高比 250:360（t6 规格实测）。尺寸用「号」，两档（卡片/全屏）各自取值。

        float covW = (host.fullscreen ? BOOK_COVER_W_FULL : BOOK_COVER_W) * host.unit;
        float covH = covW * 360f / 250f;
        float covY = ruleY + padY * 0.6f;
        float covGap = (host.fullscreen ? BOOK_COVER_GAP_FULL : BOOK_COVER_GAP) * host.unit;
        float tx = left + covW + covGap;               // 右侧文字区左缘
        float textW = right - tx;
        float covBottom = covY + covH;
        drawCover(c, left, covY, covW, covH);

        // ── ① 书名：右区最多两行，逐字折行，放不下加省略号 ──
        float tSize = (host.fullscreen ? SZ_BOOK_TITLE_FULL : SZ_BOOK_TITLE) * host.unit;
        String name = (host.book.title == null || host.book.title.length() == 0) ? "(未命名)" : host.book.title;
        String[] lines = host.layout.wrapLines(name, textW, 2, tSize);
        host.p.setStyle(Paint.Style.FILL);
        host.p.setColor(INK);
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setFakeBoldText(true);
        host.p.setTextSize(tSize);
        float by = covY + tSize * 1.05f;
        c.drawText(lines[0], tx, by, host.p);
        float lastBase = by;
        if (lines[1] != null) {
            lastBase = by + tSize * 1.45f;
            c.drawText(lines[1], tx, lastBase, host.p);
        }
        host.p.setFakeBoldText(false);

        // ── ② 作者：书名正下方一行小灰字（v0.3.5.2）──
        // 只画一行、过长截断加「…」。没有作者数据（早期缓存/接口缺失）就整行跳过，
        // 后面的章节名照常顶上去 —— 宁可少一行，也不留一个空行把版面撑散。
        String author = (host.book.author == null) ? "" : host.book.author.trim();
        float aSize = (host.fullscreen ? SZ_BOOK_AUTHOR_FULL : SZ_BOOK_AUTHOR) * host.unit;
        float authY = lastBase + aSize * 1.9f;
        if (author.length() > 0) {
            // 撞车保险：作者行不许压到章节名上（章节名基线在 covBottom - dSize*1.75）
            float limit = (covBottom - (host.fullscreen ? SZ_BOOK_DUR_FULL : SZ_BOOK_DUR) * host.unit * 1.75f)
                    - aSize * 1.3f;
            if (authY > limit) authY = limit;
            host.p.setColor(GRAY);
            host.p.setTextSize(aSize);
            c.drawText(host.layout.ellipsize(author, textW), tx, authY, host.p);
        }

        // ── ③ 章节名：灰字，贴文字区底部（上方留出时长行）──
        float dSize = (host.fullscreen ? SZ_BOOK_DUR_FULL : SZ_BOOK_DUR) * host.unit;
        float chSize = (host.fullscreen ? SZ_BOOK_CH_FULL : SZ_BOOK_CH) * host.unit;
        String ch = (host.book.chapterTitle == null || host.book.chapterTitle.length() == 0)
                ? "—" : host.book.chapterTitle;
        host.p.setColor(GRAY);
        host.p.setTextSize(chSize);
        if (host.p.measureText(ch) > textW) ch = host.layout.ellipsize(ch, textW);
        c.drawText(ch, tx, covBottom - dSize * 1.75f, host.p);

        // ── ③ 时长（左，灰）+ 百分比（右，黑粗）—— 与封面底对齐 ──
        String dur = CardLayout.fmtTotal(host.book.readingSec);
        String pct = host.book.percent() + "%";
        host.p.setTextSize(dSize);
        c.drawText(dur, tx, covBottom, host.p);
        host.p.setTextAlign(Paint.Align.RIGHT);
        host.p.setColor(INK);
        host.p.setFakeBoldText(true);
        c.drawText(pct, right, covBottom, host.p);
        host.p.setFakeBoldText(false);
        host.p.setTextAlign(Paint.Align.LEFT);

        // ── ⑤ 进度条：全宽，横贯封面下方，贴近「打开」按钮收底 ──
        float barH = (host.fullscreen ? BOOK_BAR_H_FULL : BOOK_BAR_H) * host.unit;
        float barTop = h - CardSpec.OPEN_BOX_H - CardSpec.OPEN_BOX_MARGIN_B
                - padY * 0.9f - barH;
        float sw = Math.max(1.6f, barH * 0.13f);
        host.p.setStyle(Paint.Style.STROKE);
        host.p.setStrokeWidth(sw);
        host.p.setColor(INK);
        float o = sw / 2f;
        c.drawRect(left + o, barTop + o, right - o, barTop + barH - o, host.p);
        host.p.setStyle(Paint.Style.FILL);

        float pad = Math.max(2f, barH * 0.20f);
        float x0 = left + sw + pad;
        float x1 = right - sw - pad;
        float yA = barTop + sw + pad;
        float yB = barTop + barH - sw - pad;
        if (x1 > x0 && yB > yA) {
            float fillW = (x1 - x0) * host.book.percent() / 100f;
            if (fillW > 0.5f) {
                host.p.setColor(INK);
                c.drawRect(x0, yA, x0 + fillW, yB, host.p);
                // 填充分界线：进度 100% 时不画（整条都是黑的，画了反而像多一条缝）
                if (fillW < x1 - x0 - 1f) {
                    host.p.setStyle(Paint.Style.STROKE);
                    host.p.setStrokeWidth(sw);
                    c.drawLine(x0 + fillW, yA - pad, x0 + fillW, yB + pad, host.p);
                    host.p.setStyle(Paint.Style.FILL);
                }
            }
        }

        // ── ⑤ 右下角「打开」按钮（跳微信读书）──
        drawOpenBox(c, left, right, h, OPEN_LABEL);

        // ── ⑥ 🆕 TASK-071：左下角「选书」按钮 —— 与「打开」**同一水平带、左右对称** ──
        //
        // 用户 2026-10-07 拍板：「本书的选书放在卡片的左下角，和「打开」同一行」。
        // 桌面卡（fullscreen=false）与 App 全屏档**都画**（两端入口保持一致，App 侧原来
        // 底部按钮行里的 `btn_book_pick` 已随之删除）。
        //
        // 🔴 矩形只在这里算一次：桌面档的命中走 OverlayController 那个透明小窗
        // （坐标来自 `CardSpec.prevBoxLeft/Top`），App 档走 CardInteraction 直接判
        // `host.pickBox` —— 两条路共用**同一个权威定义**（左下按钮位）⇒ 严格重合，
        // 不会"点框没反应"。横坐标与「打开」镜像：`left + PREV_BOX_MARGIN_L` ⇄ `right - boxW - MARGIN_R`。
        drawBoxAt(c, left + CardSpec.PREV_BOX_MARGIN_L, h, PICK_LABEL, host.pickBox);
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
     * 字数溢出三步走：① 按可用高度决定最多几行 → ② 逐字折行（{@link CardLayout#wrapLines}）
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
     * ── 字号（v0.9，TASK-016）：**固定值，不再按字数分档** ──
     * 以前卡片档 ≤70 字用 18 号、否则 14 号，App 档 19/17/15/14 按 188/250/304 字分界 ——
     * 字数一多字就变小，观感跳。现在两档各取一个注入进来的固定号值：
     *   · 卡片档 = {@code host.noteCardSize}（默认 17 号 = 桌面应用名字号，可调 12–24）；
     *   · App 档 = {@code host.noteFullSize}（由 sizeTier 决定，15/17/19 号）。
     * ⚠️ 长文因此**不再靠缩字号消化** —— 那正是要去掉的机制。缺口由下面两处分担：
     *   卡片档限行 + 省略号（TASK-017 起末尾还有「展开▽」）、App 档靠滚动。
     *
     * ── 滚动（v0.4.4）──
     * 卡片档（桌面）：两段各自限行（原文 2 行 / 想法 4 行），**装不下就逐级往下收紧**，
     * 保证永远不压到署名。桌面卡片是"瞥一眼"，不给它滚动这种需要手指的动作。
     * App 全屏档：不限行数，正文段**可纵向滚动**（自绘，触摸在 CardInteraction#noteTouch）；
     * 抬头、署名、末行、进度、按钮全部**固定**，滑到哪儿都点得到「换一条」。
     */
    // ══════════════════════ 待办：未完成清单（V1.0.3-beta，TASK-024） ══════════════════════

    /**
     * 画待办清单：**只画未完成项**（列表已由 controller 过滤），每行三段 ——
     * 勾选框（最左）｜内容（超出省略号）｜日期时间（最右，**无则不画该列**）。
     *
     * 展开/收起复用本记机制（TASK-017）：装不下时在末行下方画「展开▽」/「收起△」（右对齐），
     * 桌面那个透明小窗由 {@link WeekCardView#expandBox} 的回写驱动 —— **不新增窗口**。
     * 逐条勾选框矩形写进 {@link WeekCardView#todoBoxes}（桌面"直接勾选"用，见 OverlayController）。
     */
    private void drawTodoBody(Canvas c, float w, float h, float left, float right,
                              float padY, float ruleY) {
        float tSize = SZ_TODO * host.unit;
        float whenSize = SZ_TODO_WHEN * host.unit;
        float lineH = tSize * 1.95f;

        host.p.setTypeface(android.graphics.Typeface.DEFAULT);
        java.util.List<com.inkread.weekread.core.TodoItem> list = host.todo;
        host.todoBoxes.clear();                       // 每帧重建（勾选框位置随条目增减而变）

        if (list == null || list.isEmpty()) {
            drawCentered(c, (left + right) / 2f, h * 0.44f, TODO_EMPTY, SZ_EMPTY * host.unit, INK);
            drawCentered(c, (left + right) / 2f, h * 0.44f + SZ_EMPTY * host.unit * 1.8f,
                    "在 APP「待办」页添加", SZ_SUB * host.unit, GRAY);
            return;
        }

        // 上沿与 drawHeader 同源：展开态锚定收起态基准（上半部分不随高度下移，与本记一致）
        float padTop = (host.isNoteExpanded() ? CardSpec.cardHeight() : h) * 0.055f;
        float top = ruleY + padTop * 0.75f;
        float bottom = h - padY * 0.9f;

        // 底部留一行给「展开/收起」按钮
        float btnH = tSize * 1.7f;
        int canRows = (int) Math.floor((bottom - btnH - top) / lineH + 1e-3);
        if (canRows < 1) canRows = 1;

        int total = list.size();
        int showRows = Math.min(total, canRows);

        // 日期时间列宽（所有条目取最大，保证右对齐整齐；全空则整列为 0 ⇒ 不占位）
        host.p.setTextSize(whenSize);
        float whenW = 0f;
        for (com.inkread.weekread.core.TodoItem it : list) {
            String s = it.whenLabel();
            if (s.length() > 0) whenW = Math.max(whenW, host.p.measureText(s));
        }
        float whenColW = (whenW > 0f) ? (whenW + tSize * 0.6f) : 0f;

        float boxS = tSize * 1.0f;
        float boxColW = boxS + tSize * 0.8f;
        float textX = left + boxColW;
        float textMaxW = right - whenColW - textX;
        if (textMaxW < tSize * 2f) textMaxW = tSize * 2f;

        for (int i = 0; i < showRows; i++) {
            com.inkread.weekread.core.TodoItem it = list.get(i);
            float rowCy = top + i * lineH + lineH / 2f;

            // ① 勾选框（描边方块）
            float bx = left, by = rowCy - boxS / 2f;
            host.p.setStyle(Paint.Style.STROKE);
            host.p.setStrokeWidth(Math.max(1.5f, tSize * 0.10f));
            host.p.setColor(INK);
            c.drawRect(bx, by, bx + boxS, by + boxS, host.p);
            // 记下矩形（View 内坐标）—— 桌面"点它勾选"的透明小窗按它摆
            host.todoBoxes.add(new RectF(bx, by, bx + boxS, by + boxS));

            // ② 内容（单行，超出省略号）
            host.p.setStyle(Paint.Style.FILL);
            host.p.setColor(INK);
            host.p.setTextAlign(Paint.Align.LEFT);
            host.p.setTextSize(tSize);
            host.p.setFakeBoldText(false);
            c.drawText(host.layout.ellipsize(it.content, textMaxW), textX, rowCy + tSize * 0.35f, host.p);

            // ③ 日期时间（右对齐；为空则整列不画 —— 上面 whenColW 已归零）
            String when = it.whenLabel();
            if (when.length() > 0) {
                host.p.setColor(GRAY);
                host.p.setTextSize(whenSize);
                host.p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(when, right, rowCy + whenSize * 0.35f, host.p);
                host.p.setTextAlign(Paint.Align.LEFT);
                host.p.setColor(INK);
            }
        }

        // 「展开▽ / 收起△」—— 收起态只在装不下时画；展开态总要画（否则收不回去）。
        // 🔴 复用 drawExpandToggle ⇒ expandBox 回写 + OverlayController 那个透明小窗全部现成。
        boolean drawBtn = host.isNoteExpanded() || (total > showRows);
        if (drawBtn) {
            float sz = SZ_EXPAND * host.unit;
            host.p.setTextSize(sz);
            String label = host.isNoteExpanded() ? EXPAND_LABEL_UP : EXPAND_LABEL_DOWN;
            float btnW = host.p.measureText(label) + (EXPAND_TRI_W + EXPAND_TRI_GAP) * host.unit;
            drawExpandToggle(c, label, right - btnW, top + showRows * lineH + btnH * 0.62f, btnW);
        }
    }

    private void drawNoteBody(Canvas c, float w, float h, float left, float right,
                              float padY, float ruleY) {
        float availW = right - left;

        if (host.note == null) {
            // 空态分三种，必须让用户分得清"在忙 / 出错了 / 真的没有"（v0.5.3，R02/R08）——
            // 上一版只有"正在同步"与"本记还没有准备好"两种，导致"Key 失效"与"新账号"
            // 都只能得到同一句含糊的话，用户唯一的动作是反复点刷新（而旧逻辑会把它打成循环）。
            String what = host.noteIdeasSlot ? "想法" : "划线笔记";
            String main, sub;
            if (host.refreshing) {
                main = "正在同步" + what + "…";
                sub = host.noteIdeasSlot
                        ? "第一次要拉 55 本书的想法，几秒就好"
                        : "第一次要同步 228 本书的笔记，稍等一会儿";
            } else if (host.noteHint != null) {
                main = host.noteHint;
                sub = "点右上角 ⟳ 或下方「刷新」再试一次";
            } else {
                main = "本记还没有准备好";
                sub = "点右上角 ⟳ 开始同步";
            }
            drawCentered(c, (left + right) / 2f, h * 0.42f, main, SZ_EMPTY * host.unit, INK);
            wrapCentered(c, (left + right) / 2f, h * 0.42f + SZ_EMPTY * host.unit * 1.9f,
                    availW * 0.9f, sub, SZ_SUB * host.unit, GRAY);
            host.noteContentH = 0f;
            host.noteViewH = 0f;
            host.noteScrollMax = 0f;
            // 空态也要画按钮行 —— 「只看想法」没内容时，用户唯一的出路是点「筛选」切回「全部」
            drawNoteButtons(c, h, left, right);
            return;
        }

        float qSize = (host.fullscreen ? SZ_NOTE_QUOTE_FULL : SZ_NOTE_QUOTE) * host.unit;
        // ── 字号（v0.9，TASK-016）：**固定值**，由两条注入路径各塞一个号进来 ──
        // 卡片档 ← CardPrefs.note_card_size（默认 17 = 桌面应用名字号）；
        // App 档  ← NoteExport.sizeTier 三档（15/17/19）。都**不看字数**。
        float tNum = host.fullscreen ? host.noteFullSize : host.noteCardSize;
        float tSize = tNum * host.unit;
        float tagSize = (host.fullscreen ? SZ_NOTE_IDEA_TAG_FULL : SZ_NOTE_IDEA_TAG) * host.unit;
        float tiSize = (host.fullscreen ? SZ_NOTE_TITLE_FULL : SZ_NOTE_TITLE) * host.unit;
        float auSize = (host.fullscreen ? SZ_NOTE_AUTHOR_FULL : SZ_NOTE_AUTHOR) * host.unit;
        float mSize = (host.fullscreen ? SZ_NOTE_META_FULL : SZ_NOTE_META) * host.unit;

        // 底部信息行的位置：贴着底部按钮行往上排，短句也不会飘。
        // v0.4.2：App 全屏档多一行进度（第 N / 共 M 条），所以 meta/sign 再往上让一行；
        // 卡片档把进度塞进左右两个按钮之间的空隙，不额外占行。
        float boxTop = h - host.layout.boxH() - host.layout.boxMarginB();
        float progY = 0f;
        float metaY;
        if (host.fullscreen) {
            progY = boxTop - padY * 0.35f;
            metaY = progY - mSize * 1.9f;
        } else {
            metaY = boxTop - padY * 0.35f;
        }
        float signY = metaY - mSize * 1.9f;

        // 正文区：抬头分隔线以下 ~ 署名以上
        // 正文上沿与 drawHeader 同源：展开态锚定收起态基准，上半部分不随卡片高度下移；
        // 底部署名/章节日期/按钮仍用传入的 padY（随展开下移），这里只固定上沿这一处。
        float padTop = (host.isNoteExpanded() ? CardSpec.cardHeight() : h) * 0.055f;
        float textTop = ruleY + padTop * 0.75f;
        float bodyBottom = signY - tSize * 1.4f;
        float bodyH = bodyBottom - textTop;
        if (bodyH < tSize * 1.6f) bodyH = tSize * 1.6f;

        boolean hasQuote = host.note.markText != null && host.note.markText.trim().length() > 0;

        // ── ① 大引号（只在有原文时画 —— 独立想法没有"引文"可引）──
        host.p.setStyle(Paint.Style.FILL);
        host.p.setColor(LIGHT);
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setTypeface(android.graphics.Typeface.SERIF);
        host.p.setTextSize(qSize);
        float quoteW = host.p.measureText("\u201c");
        if (hasQuote) {
            c.drawText("\u201c", left, textTop + qSize * 0.78f, host.p);
        }

        // ── ② 排版（原文段 + 想法段），两段共用同一套折行 ──
        float tx = hasQuote ? left + quoteW * 0.85f : left;
        float textW = right - tx;
        int maxQ, maxI;
        /** 不限行数的"自然"排版结果 —— 卡片档用它判溢出（TASK-017），App 档为 null */
        CardLayout.NoteBody nat = null;
        if (host.fullscreen) {
            maxQ = 0;
            maxI = 0;                       // 不限行数：超出屏幕的部分靠滚动看
        } else {
            // 卡片档：**先算这块地方能放几行，再按两段各自的需要量分配**（v0.4.5）。
            //
            // ⚠️ 不能再用固定的「原文 2 行 / 想法 4 行」：全库 5162 条划线里绝大多数
            // 根本没有想法，固定 2 行会让正文只占 2×33 ≈ 67px，而正文区可用高 177px
            // —— **110px（卡片高度的三分之一）白空着**。真机截图逐行量过：
            // 正文两行落在卡片内 y 64…121，下一处墨迹要等到 y 254 的署名行。
            nat = CardLayout.layoutNote(host.note.markText, host.note.ideaText, textW, tSize, tagSize, 0, 0);
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
        CardLayout.NoteBody body = CardLayout.layoutNote(host.note.markText, host.note.ideaText, textW, tSize, tagSize, maxQ, maxI);

        // 卡片档：限行后仍可能超高（长句配大字号），逐级收紧到装得下为止
        if (!host.fullscreen) {
            int guard = 0;
            while (body.height > bodyH && guard++ < 8 && (maxQ > 1 || maxI > 1)) {
                if (maxI > 1) maxI--;
                else maxQ--;
                body = CardLayout.layoutNote(host.note.markText, host.note.ideaText, textW, tSize, tagSize, maxQ, maxI);
            }
        }

        // ── ②b 行末「展开▽ / 收起△」的判定 + 末行预留（v0.9，TASK-017）──
        //
        // 只在**卡片档**出现：App 全屏档正文可滚动，不需要也不该有这个按钮
        // （`MainActivity` 全局 setFullscreen(true)，桌面卡片恒 false ⇒ 一条判据就够，不用额外开关）。
        //
        // 🔴 **溢出判据 = "限行后行数 < 自然行数"**，不是 `maxScroll > 0`：
        // 卡片档限行之后 `noteContentH ≤ noteViewH` ⇒ maxScroll 恒为 0，
        // 用它判会永远判成"没溢出"，按钮一次都不出现（开工前点名的坑）。
        String expandLabel = null;
        if (!host.fullscreen && nat != null) {
            boolean overflow = (nat.quote.length > body.quote.length)
                    || (nat.idea.length > body.idea.length);
            if (host.noteExpanded) expandLabel = EXPAND_LABEL_UP;      // 展开态：恒给「收起△」
            else if (overflow) expandLabel = EXPAND_LABEL_DOWN;        // 收起态：只在溢出时给「展开▽」
        }
        float expandW = 0f;
        float expandAnchorX = 0f;
        boolean expandNextLine = false;
        if (expandLabel != null) {
            host.p.setTypeface(android.graphics.Typeface.DEFAULT);
            host.p.setTextSize(SZ_EXPAND * host.unit);
            expandW = host.p.measureText(expandLabel)
                    + (EXPAND_TRI_GAP + EXPAND_TRI_W) * host.unit;
            // 末行要先把按钮这一截让出来，否则「…」顶到行尾、按钮被挤出卡片（或被裁剪框切掉）
            float reserve = expandW + EXPAND_GAP * host.unit;
            // 按钮挂在哪一段的末行：**想法段优先**（有想法时它排在最后）
            boolean onIdea = body.idea.length > 0;
            // 🔴 把 maxLines 收紧到**实际行数**再预留 —— 预留必须落在"真正有字的最后一行"上。
            // 内容没被截断时两者本应相等；但 `NOTE_CARD_IDEA_MIN` 会把 maxI 顶到 2 而实际只有 1 行，
            // 这种时候不收紧就会把宽度预留在一个根本不存在的行上，末行照旧顶满 ⇒ 按钮压字。
            int rq = body.quote.length > 0 ? body.quote.length : maxQ;
            int ri = body.idea.length > 0 ? body.idea.length : maxI;

            // 🆕 方案 A（2026-09-28 用户拍板）：展开态内容**已全显**（自然行数 ≤ fit）时，
            // 不做末行预留——「收起△」要么挂末行文字末尾，要么（末行恰好满行）挂下一行，
            // 绝不把末行挤短截字（否则"空间够 12 行、内容只有 5 行，末行却因按钮预留
            // 被截成假省略号"）。收起态 / 展开态内容仍截断（超长笔记）时保持原"预留+贴右"。
            boolean fullShown = host.noteExpanded
                    && (nat.quote.length <= body.quote.length)
                    && (nat.idea.length <= body.idea.length);
            if (fullShown) {
                String[] seg = onIdea ? body.idea : body.quote;
                float lastW = 0f;
                if (seg.length > 0) {
                    host.p.setTypeface(android.graphics.Typeface.SERIF);
                    host.p.setTextSize(tSize);
                    lastW = host.p.measureText(seg[seg.length - 1]);
                    host.p.setTypeface(android.graphics.Typeface.DEFAULT);
                }
                if (tx + lastW + reserve <= right) {
                    expandAnchorX = tx + lastW + EXPAND_GAP * host.unit;   // 挂末行文字末尾
                } else {
                    expandAnchorX = right - expandW;                        // 末行满 → 挂下一行
                    expandNextLine = true;
                }
            } else {
                body = CardLayout.layoutNote(host.note.markText, host.note.ideaText, textW, tSize,
                        tagSize, rq, ri, onIdea ? 0f : reserve, onIdea ? reserve : 0f);
                expandAnchorX = right - expandW;                        // 预留 + 贴右（原行为）
            }
        }

        // ── ③ 画正文：App 全屏档超出一屏时可滚动（clip + 位移）──
        // 可视高与滚动量都**取整到整行**：裁剪边界因此永远落在行边界上，两端都不会切字。
        host.noteContentH = body.height;
        host.noteLineH = body.lineH;
        // 可视高向下取整（宁可少显示一整行，也不能把一行切一半）。
        // +1e-3 是防浮点：恰好整除时 floor(7.999999) 会少算一整行。
        host.noteViewH = (float) (Math.floor(bodyH / body.lineH + 1e-3) * body.lineH);
        if (host.noteViewH < body.lineH) host.noteViewH = body.lineH;
        float maxScroll = host.noteContentH - host.noteViewH;
        if (maxScroll < 0f) maxScroll = 0f;
        // 滚动量则必须**四舍五入**到整行，绝不能向下取整：
        // contentH 与 viewH 都是 lineH 的整数倍，差天然是整数倍，但浮点除法会算出
        // 2.9999999，floor 掉就变成 2 行 —— 窗口底边停在内容最后一行的上方，
        // 那一行**永远滚不出来**（v0.4.4 首包实测：「》十三章」看不到）。
        int scrollLines = Math.round(maxScroll / body.lineH);
        if (scrollLines < 0) scrollLines = 0;
        maxScroll = scrollLines * body.lineH;
        host.noteScrollMax = maxScroll;
        if (host.noteScrollY > maxScroll) host.noteScrollY = maxScroll;
        if (host.noteScrollY < 0f) host.noteScrollY = 0f;
        float sy = host.noteScrollY;

        c.save();
        c.clipRect(left - 2f, textTop - 2f, right + 2f, textTop + host.noteViewH + 2f);
        host.p.setStyle(Paint.Style.FILL);
        host.p.setTypeface(android.graphics.Typeface.SERIF);
        host.p.setColor(INK);
        host.p.setTextSize(tSize);
        float ly = textTop + tSize - sy;
        for (int i = 0; i < body.quote.length; i++) {
            c.drawText(body.quote[i], tx, ly, host.p);
            ly += body.lineH;
        }
        if (body.idea.length > 0) {
            float segEnd = textTop + body.quote.length * body.lineH - sy;
            // 「想法」小标：小字灰色，坐在两段之间的间隔里
            host.p.setTypeface(android.graphics.Typeface.DEFAULT);
            host.p.setColor(GRAY);
            host.p.setTextSize(tagSize);
            c.drawText(IDEA_TAG, tx, segEnd + body.lineH * 0.75f, host.p);
            // 想法正文
            host.p.setTypeface(android.graphics.Typeface.SERIF);
            host.p.setColor(INK);
            host.p.setTextSize(tSize);
            float iy = segEnd + body.tagBlock + tSize;
            for (int i = 0; i < body.idea.length; i++) {
                c.drawText(body.idea[i], tx, iy, host.p);
                iy += body.lineH;
            }
        }
        host.p.setTypeface(android.graphics.Typeface.DEFAULT);
        c.restore();

        // ── ③b 画行末「展开▽ / 收起△」+ 回写触摸矩形（v0.9，TASK-017）──
        if (expandLabel != null) {
            float lastBaseY;
            if (body.idea.length > 0) {
                lastBaseY = textTop + body.quote.length * body.lineH + body.tagBlock
                        + (body.idea.length - 1) * body.lineH + tSize - sy;
            } else {
                lastBaseY = textTop + (body.quote.length - 1) * body.lineH + tSize - sy;
            }
            if (expandNextLine) lastBaseY += body.lineH;   // 末行满 → 按钮挂下一行
            drawExpandToggle(c, expandLabel, expandAnchorX, lastBaseY, expandW);
        }

        // ── ④ 滚动条：只在真的超出一屏时出现，贴最右侧，不抢文字 ──
        if (maxScroll > 0f) {
            float barH = Math.max(24f, host.noteViewH * host.noteViewH / host.noteContentH);
            float barY = textTop + (host.noteViewH - barH) * (host.noteScrollY / maxScroll);
            host.p.setColor(0xFFB8B8B8);
            c.drawRect(right - 3f, barY, right, barY + barH, host.p);
        }

        // ── ⑤ 署名：书名(粗) + 作者(灰)。书名放不下作者时（v0.4.1 拍板④）改两行：
        //     书名独占署名行，作者下移与章节·日期同排 ──
        String title = (host.note.title == null || host.note.title.length() == 0) ? "(未命名)" : host.note.title;
        String author = (host.note.author == null) ? "" : host.note.author.trim();
        boolean dropAuthor = false;                      // 作者是否下移到末行
        if (author.length() > 0) {
            host.p.setFakeBoldText(true);
            host.p.setTextSize(tiSize);
            float titleW = host.p.measureText(title);
            host.p.setFakeBoldText(false);
            host.p.setTextSize(auSize);
            float authorW = host.p.measureText(author);
            if (titleW + auSize * 0.8f + authorW > availW) {
                dropAuthor = true;
                if (titleW > availW) {
                    // 🔴 量书名必须切回**书名的最终绘制态**（粗体 + tiSize）：`ellipsize` 的契约是
                    //    「调用前把 p 的字号/字重设成最终值」，此前停在 `auSize` 非粗体上量 ⇒ 偏窄，
                    //    长书名（如"卡拉马佐夫兄弟（套装上下册）（陀思妥耶夫斯基文集2015）"）虽然
                    //    被截过、画出来仍会**冲出卡片右边界**（TASK-057 选书后长书名成常态，真机复现）。
                    host.p.setFakeBoldText(true);
                    host.p.setTextSize(tiSize);
                    title = host.layout.ellipsize(title, availW);
                }
            }
        } else {
            // 无作者时原来整段被跳过 ⇒ 超宽书名**完全不截断**。补上（同样按粗体 + tiSize 量）。
            host.p.setFakeBoldText(true);
            host.p.setTextSize(tiSize);
            if (host.p.measureText(title) > availW) title = host.layout.ellipsize(title, availW);
        }
        host.p.setColor(INK);
        host.p.setFakeBoldText(true);
        host.p.setTextSize(tiSize);
        c.drawText(title, left, signY, host.p);
        host.p.setFakeBoldText(false);
        if (author.length() > 0 && !dropAuthor) {
            float keep = host.p.measureText(title);           // 书名当前实际宽度
            host.p.setTextSize(auSize);
            host.p.setColor(GRAY);
            c.drawText(author, left + keep + auSize * 0.7f, signY, host.p);
            host.p.setColor(INK);
        }

        // ── ⑥ 末行：作者(下移时) · 章节 · 日期 ──
        String ch = (host.note.chapterTitle == null || host.note.chapterTitle.length() == 0)
                ? "第 " + (host.note.chapterIdx + 1) + " 章" : host.note.chapterTitle;
        String meta = (dropAuthor ? author + " · " : "")
                + ch + (host.note.dateText().length() > 0 ? " · " + host.note.dateText() : "");
        // 想法的来源标记：让人一眼看出这条是"我说的话"而不是"我划的线"
        if (host.note.hasIdea()) meta = meta + " · 想法";
        host.p.setColor(GRAY);
        host.p.setTextSize(mSize);
        if (host.p.measureText(meta) > availW) meta = host.layout.ellipsize(meta, availW);
        c.drawText(meta, left, metaY, host.p);
        host.p.setColor(INK);

        // ── ⑦ 底部按钮行 ──
        // 左下「上一条」↔ 右下「换一条」，左右对称。
        // App 全屏档空间宽裕 → 三个按钮等分排开（上一条 / 换一条 / 导出）；
        // 卡片档按钮区只有 368 宽 → 保持左右各一个（导出只在 App 里出现）
        drawNoteButtons(c, h, left, right);

        // ── ⑧ 进度：第 N / 共 M 条（v0.4.2，池内序号 —— 与"换一条/上一条"同步增减）──
        // 🆕 TASK-057：按**槽位**取数（App 由 MainActivity 注入 1 想法 / 2 选书）
        // 🆕 TASK-057 R1（2026-10-06）：**桌面也走注入** —— CardContentController 传
        //   NoteStore.desktopSlot()，只可能是 0 / 2（桌面**不**跟随"只看想法"维度）。
        //   ⚠️ 原注释"桌面恒 0 = 全库"已被本修订作废。
        int[] pr = NoteStore.progress(host.getContext(), host.noteSlot);
        if (pr != null) {
            String ps = "第 " + pr[0] + " / 共 " + pr[1] + " 条";
            host.p.setStyle(Paint.Style.FILL);
            host.p.setColor(GRAY);
            host.p.setTextSize(mSize);
            host.p.setTextAlign(Paint.Align.CENTER);
            if (host.fullscreen) {
                c.drawText(ps, (left + right) / 2f, progY, host.p);
            } else {
                // 卡片档：塞进左右两个按钮之间的空隙，与按钮垂直同轴
                Paint.FontMetrics fmP = host.p.getFontMetrics();
                c.drawText(ps, (left + right) / 2f,
                        boxTop + host.layout.boxH() / 2f - (fmP.descent + fmP.ascent) / 2f, host.p);
            }
            host.p.setTextAlign(Paint.Align.LEFT);
            host.p.setColor(INK);
        }
    }

    /**
     * 行末的「展开▽ / 收起△」（v0.9，TASK-017）—— **无边框**（用户拍板 E3）。
     *
     * 位置：贴正文末行**行尾右侧**，与末行同一条基线。按钮左端由调用方传 {@code anchorX}：
     * · 收起态 / 展开态末行顶满时 → `anchorX = right − btnW`（贴右），排版阶段已用
     *   {@code CardLayout#wrapMax} 的 `reserveLast` 扣掉 `btnW + EXPAND_GAP`，正文不压字；
     * · 展开态内容全显时（方案 A）→ `anchorX = 末行文字末尾 + EXPAND_GAP`（挂行尾，不截字）。
     *
     * @param anchorX 按钮**左端** x（文字起点）
     * @param baseY   末行的**基线** y
     * @param btnW    按钮总宽（文字 + 间距 + 三角），由调用方量好传进来（与本方法用同一支画笔，不会算歪）
     */
    private void drawExpandToggle(Canvas c, String label, float anchorX, float baseY, float btnW) {
        float sz = SZ_EXPAND * host.unit;
        float x = anchorX;

        host.p.setStyle(Paint.Style.FILL);
        host.p.setTypeface(android.graphics.Typeface.DEFAULT);
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setColor(INK);
        host.p.setTextSize(sz);
        c.drawText(label, x, baseY, host.p);

        // 三角：**收起态画 ▽（点它展开）/ 展开态画 △（点它收起）**
        float tw = EXPAND_TRI_W * host.unit;
        float th = EXPAND_TRI_H * host.unit;
        float tx0 = x + host.p.measureText(label) + EXPAND_TRI_GAP * host.unit;
        float cy = baseY - sz * 0.30f;                 // 视觉中线（基线往上约小三成字高）
        host.path.reset();
        if (!host.noteExpanded) {
            host.path.moveTo(tx0, cy - th / 2f);
            host.path.lineTo(tx0 + tw, cy - th / 2f);
            host.path.lineTo(tx0 + tw / 2f, cy + th / 2f);
        } else {
            host.path.moveTo(tx0 + tw / 2f, cy - th / 2f);
            host.path.lineTo(tx0, cy + th / 2f);
            host.path.lineTo(tx0 + tw, cy + th / 2f);
        }
        host.path.close();
        c.drawPath(host.path, host.p);

        // 回写触摸矩形（**View 内坐标**）—— 桌面那个透明小窗按它摆（OverlayController）。
        // 纵向比字大一圈：墨水屏上手指按不准，字高只有 13.8px，框给到 ≈33px。
        // 右端 = 按钮左端 + 总宽（贴右时 `x + btnW = right`，与旧行为逐位一致）。
        float pad = EXPAND_BOX_PAD * host.unit;
        host.expandBox.set(x - pad, baseY - sz * 1.5f, x + btnW + pad, baseY + sz * 0.9f);
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
        boolean has = host.coverBmp != null && host.book != null
                && host.book.bookId != null && host.book.bookId.equals(host.coverBmpId);
        if (has) {
            host.bmpDst.set(x, y, x + cw, y + ch);
            c.drawBitmap(host.coverBmp, null, host.bmpDst, host.bmpPaint);
        } else {
            host.p.setStyle(Paint.Style.FILL);
            host.p.setColor(LIGHT);
            host.p.setTextAlign(Paint.Align.CENTER);
            host.p.setTextSize(13f * host.unit);
            c.drawText("封面", x + cw / 2f, y + ch / 2f, host.p);
            host.p.setTextAlign(Paint.Align.LEFT);
        }
        host.p.setStyle(Paint.Style.STROKE);
        host.p.setStrokeWidth(1.6f);
        host.p.setColor(INK);
        c.drawRect(x + 0.8f, y + 0.8f, x + cw - 0.8f, y + ch - 0.8f, host.p);
        host.p.setStyle(Paint.Style.FILL);
    }

    /**
     * 右下角的描边「打开」按钮。
     *
     * 位置与 {@link CardSpec#openBoxLeft()} / {@link CardSpec#openBoxTop()} **共用同一组常量** ——
     * 桌面那个透明触摸窗就是按它们摆的，两边必须严格重合，否则用户会点在框上却没反应。
     * 矩形存进 {@link WeekCardView#openBox}，App 内的命中测试也用它。
     */
    private void drawOpenBox(Canvas c, float left, float right, float h, String label) {
        drawBoxAt(c, right - host.layout.boxW() - CardSpec.OPEN_BOX_MARGIN_R, h, label, host.openBox);
    }

    /**
     * 画一个描边按钮，矩形回写进 {@code box} —— 命中测试与桌面那个透明触摸窗共用同一组常量，
     * 两边必须严格重合，否则用户点在框上却没反应。
     */
    private void drawBoxAt(Canvas c, float bx, float h, String label, RectF box) {
        drawBoxAt(c, bx, h, label, box, host.layout.boxW());
    }

    /** 同上，但格宽由调用方给（v0.4.5 的 App 本记按钮行是四格等宽，与其它页不同） */
    private void drawBoxAt(Canvas c, float bx, float h, String label, RectF box, float bw) {
        float bh = host.layout.boxH();
        float by = h - bh - host.layout.boxMarginB();
        box.set(bx, by, bx + bw, by + bh);

        host.p.setStyle(Paint.Style.STROKE);
        host.p.setStrokeWidth(2f);
        host.p.setColor(INK);
        c.drawRect(bx + 1f, by + 1f, bx + bw - 1f, by + bh - 1f, host.p);
        host.p.setStyle(Paint.Style.FILL);

        host.p.setColor(INK);
        host.p.setTextAlign(Paint.Align.CENTER);
        host.p.setTextSize((host.fullscreen ? 17f : SZ_OPEN) * host.unit);
        Paint.FontMetrics fm = host.p.getFontMetrics();
        c.drawText(label, bx + bw / 2f, by + bh / 2f - (fm.descent + fm.ascent) / 2f, host.p);
        host.p.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * 本记页底部按钮行（v0.4.5 抽成一个方法）。
     *
     * **空态也要画**：`只看想法` 池子没内容时，用户唯一的出路就是点「筛选」切回「全部」——
     * 如果空态不画按钮行，就会卡在一个什么都没有、也点不动的页面上（触摸矩形是画的时候回写的）。
     */
    private void drawNoteButtons(Canvas c, float h, float left, float right) {
        if (host.fullscreen) {
            // 🆕 TASK-057：五格等宽居中 —— 「筛选」/ 上一条 / 换一条 / 选书 / 导出
            float step = NOTE_BOX_W + NOTE_BOX_GAP;
            float total = NOTE_BOX_W * 5f + NOTE_BOX_GAP * 4f;
            float x0 = left + ((right - left) - total) / 2f;
            if (x0 < left) x0 = left;
            drawFilterBox(c, x0, h);
            drawBoxAt(c, x0 + step, h, PREV_LABEL, host.prevBox, NOTE_BOX_W);
            drawBoxAt(c, x0 + step * 2f, h, NOTE_LABEL, host.openBox, NOTE_BOX_W);
            drawBoxAt(c, x0 + step * 3f, h, PICK_LABEL, host.pickBox, NOTE_BOX_W);
            drawBoxAt(c, x0 + step * 4f, h, EXPORT_LABEL, host.exportBox, NOTE_BOX_W);
        } else {
            // 桌面卡片：按钮区只有 368 宽，放不下五格 —— 保持左右各一个
            drawBoxAt(c, left + CardSpec.PREV_BOX_MARGIN_L, h, PREV_LABEL, host.prevBox);
            drawBoxAt(c, right - host.layout.boxW() - CardSpec.OPEN_BOX_MARGIN_R, h, NOTE_LABEL, host.openBox);
            host.exportBox.setEmpty();
            host.filterBox.setEmpty();          // 卡片档没有筛选格（也不开触摸窗）
            host.pickBox.setEmpty();            // 本记卡片档没有「选书」格（TASK-057）；
                                                // 🆕 TASK-071：桌面「选书」格只在**本书**形态，见 drawBookBody ⑥
        }
    }

    /**
     * 「筛选」格（v0.4.5，App 本记页专属）：**一格两态**，点一下在「全部 / 只看想法」之间切换。
     *
     * 视觉上要一眼看出当前在哪一档，所以「只看想法」用**反白填充**（黑底白字）、
     * 「全部」（默认档）用描边 —— 墨水屏没有高亮色，反白是唯一足够醒目的状态差。
     * 右侧那个小三角（表示"可切换"）用 Path 画，不写字形：
     * 本机字体不保证带 ▾ 这类码位（这条教训见 {@link #drawSwitchIcon} 的注释）。
     */
    private void drawFilterBox(Canvas c, float bx, float h) {
        float bw = NOTE_BOX_W, bh = host.layout.boxH();
        float by = h - bh - host.layout.boxMarginB();
        host.filterBox.set(bx, by, bx + bw, by + bh);

        boolean on = host.noteIdeasSlot;
        host.p.setStyle(Paint.Style.FILL);
        host.p.setColor(on ? INK : 0xFFFFFFFF);
        c.drawRect(bx, by, bx + bw, by + bh, host.p);
        host.p.setStyle(Paint.Style.STROKE);
        host.p.setStrokeWidth(2f);
        host.p.setColor(INK);
        c.drawRect(bx + 1f, by + 1f, bx + bw - 1f, by + bh - 1f, host.p);

        String label = on ? FILTER_IDEA : FILTER_ALL;
        float size = SZ_NOTE_FILTER * host.unit;
        float tri = size * 0.42f;
        float gap = size * 0.42f;
        host.p.setStyle(Paint.Style.FILL);
        host.p.setTextSize(size);
        host.p.setTextAlign(Paint.Align.LEFT);
        float tw = host.p.measureText(label);
        float tx = bx + (bw - (tw + gap + tri)) / 2f;
        Paint.FontMetrics fm = host.p.getFontMetrics();
        float baseline = by + bh / 2f - (fm.descent + fm.ascent) / 2f;
        host.p.setColor(on ? 0xFFFFFFFF : INK);
        c.drawText(label, tx, baseline, host.p);

        float cx = tx + tw + gap + tri / 2f;
        float cy = by + bh / 2f;
        host.path.reset();
        host.path.moveTo(cx - tri / 2f, cy - tri * 0.30f);
        host.path.lineTo(cx + tri / 2f, cy - tri * 0.30f);
        host.path.lineTo(cx, cy + tri * 0.34f);
        host.path.close();
        c.drawPath(host.path, host.p);

        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setColor(INK);
        host.p.setStyle(Paint.Style.FILL);
    }

    // ── 公共小件 ──

    private void drawCentered(Canvas c, float cx, float baselineY, String text, float size, int color) {
        host.p.setStyle(Paint.Style.FILL);
        host.p.setTextSize(size);
        host.p.setColor(color);
        host.p.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, cx, baselineY, host.p);
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setColor(INK);
    }

    private void wrapCentered(Canvas c, float cx, float startY, float maxW, String text, float size, int color) {
        host.p.setStyle(Paint.Style.FILL);
        host.p.setTextSize(size);
        host.p.setColor(color);
        host.p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = host.p.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float y = startY;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            line.append(text.charAt(i));
            if (host.p.measureText(line.toString()) > maxW || i == text.length() - 1) {
                c.drawText(line.toString(), cx, y, host.p);
                y += lineH * 1.2f;
                line.setLength(0);
            }
        }
        host.p.setTextAlign(Paint.Align.LEFT);
        host.p.setColor(INK);
    }
}
