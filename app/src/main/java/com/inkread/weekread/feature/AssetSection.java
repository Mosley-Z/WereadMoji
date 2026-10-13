package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.inkread.weekread.R;
import com.inkread.weekread.core.AssetRefresher;
import com.inkread.weekread.core.AssetStore;
import com.inkread.weekread.core.Bill;
import com.inkread.weekread.core.BillMoney;
import com.inkread.weekread.core.BillStore;
import com.inkread.weekread.core.BookStore;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.MenuPrefs;
import com.inkread.weekread.core.MenuPrice;
import com.inkread.weekread.core.NoteStore;
import com.inkread.weekread.core.PagePrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PeriodStats;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.ui.InkTheme;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 🆕 TASK-086 第六批B（用户 ③ · D9–D19）：「阅读资产」分区 —— **就地取代**墨台原「读书排行」。
 *
 * <p>🔴 **id / 位 / 顺序 / 开关全部不动**（D16）：模块 id 仍是 {@link PagePrefs#MOD_RANK}（`rank`），
 * 只把它的**料**从「年度最常读排行」换成「逐本阅读资产」；洞察页 K8 的
 * {@link InsightRenderer.RankSection} 继续保留、不受影响（D17）。
 *
 * <p>数据面（D9）：{@link AssetStore} 是本地缓存（`/shelf/sync` 全量书架 + 逐本 `/book/getprogress`
 * 的 `progress` / `readingTime`（优先；≤0 时回退 `recordReadingTime`，见 {@link AssetRefresher} 第六批B 修复）
 * / `finishTime`），由 {@link AssetRefresher#ensure} 在
 * **本月卡片刷新的同一时机**增量续补（D19）。本类**只读缓存 + 本地价格索引**，自身**零网络**。
 *
 * <p>版式：完全复用 {@link BillSection.Row}（同包、同一套行渲染 —— D14「与周/月菜单一致」）。
 * 每一条书目 = 主串（`No. 书名`）+ 右端两列（**收入**贴右、**原价**在其左，与墨单同列宽），
 * 第二行小字 meta（作者 · 时长 · 进度），摘录皮肤再加第三行摘录（无摘录**不占行**）。
 *
 * <p>口径（Lead 冻结，见 `tasks/TASK-086` 第六批B ①）：
 * <ul>
 *   <li>收入(分) = 原价(分) × {@link BillMoney#progressForBilling}(pct, finished) ÷ 100，半进位
 *       `(price*delta + 50) / 100`；</li>
 *   <li>**进度未取到**（`progressForBilling < 0`）⇒ 该条收入显示 `—` 且**不计入合计**；</li>
 *   <li>🆕 补丁（Lead m01985 授权 —— 用户 m00885：「许多应当有原价的书籍都显示免费」）价格**三态**：
 *       **有价** ⇒ 金额（收入 = 原价 × delta ÷ 100）；**明确免费**（价格源 `0` + free 标记）⇒
 *       「免费」+ 收入 `¥0`；**未知**（各价格源都查不到）⇒ 原价 `—` + 收入 `—`，**两者都不计入合计**。
 *       价格链（**零新增请求**）= 手填价 → {@link NoteStore#priceFenOf} → {@link BillStore} 落盘市场价 →
 *       {@link StatsStore} 落盘 {@link PeriodStats.Longest#centPrice} → 未知。</li>
 * </ul>
 */
final class AssetSection implements InsightRenderer.Section {

    // ══════════════════════ D12：三键（排序）══════════════════════

    /** 时长降序（**默认**，D12）。 */
    static final int SORT_SEC = 0;
    /** 原价降序。 */
    static final int SORT_PRICE = 1;
    /** 收入降序。 */
    static final int SORT_INCOME = 2;
    private static final String[] SORT_LABELS = { "时长", "原价", "收入" };

    // ── 尺 / 色（与墨单同族，不引第二套视觉）──
    private static final float SZ_BODY = 15f;
    private static final float SZ_SMALL = 12f;
    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF3C3C3C;
    private static final int LIGHT = InkTheme.INK2;

    /** D13：折叠时先显示的本数。 */
    private static final int COLLAPSED_N = 10;
    /** D9：累计阅读时长 **≤ 10 分钟（600s）** 的书不进资产表。 */
    private static final int MIN_SEC = 600;

    /**
     * 墨台顶栏高**兜底**（×unit）= `DeskRenderer.SZ_TITLE(18) × BAR_H_UNITS(3.0)`。
     * 🔴 正常路径由 `DeskPageView` 每帧喂入真值（见 {@link #setDeskHeadH}）；改 `DeskRenderer` 的那两个
     * 私有常量时必须同步本值（否则只有"未喂入"的极端帧会少算一屏高度）。
     */
    private static final float BLANK_HEAD_UNITS = 54f;

    /** 🆕 TASK-087-A1：抬头右端「生成壁纸」按钮尺寸（×unit）—— 与 `BillSection` 的同名按钮同款。 */
    private static final float WALL_W_UNITS = 62f;
    private static final float WALL_H_UNITS = 1.85f;
    /** 三键与「生成壁纸」的间距（×unit；🔴 Lead 冻结下限 = 4）。 */
    private static final float WALL_GAP_UNITS = 4f;

    /**
     * 🆕 TASK-087-A1：抬头右端「生成壁纸」按钮**字号的最小缩放比** —— 标签放不下时，
     * 字号按 0.4f 步长从 `SZ_SMALL * unit` 往下降，**降到 `SZ_SMALL * unit * 本值` 为止**；
     * 若到此仍超宽，则让文字自然溢出（不再继续缩），保证小字不糊。
     * （用途见 {@link #drawSortChips} 的「生成壁纸」分支。）与 {@code BillSection.WP_H_UNITS}
     * **无关**（后者是海报条目高，不是缩放比）。
     */
    private static final float WALL_MAX_SHRINK = 0.7f;

    private static final String EMPTY = "暂无数据";
    private static final String EXPAND = "展开 ▽";
    private static final String COLLAPSE = "收起 △";

    /** 自绘用（三键 / 命中区测量都走它 ⇒ 画与点**同一份几何**）。 */
    private final Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Context ctx;

    /** D12：当前排序键（默认时长降序）。 */
    private int sort = SORT_SEC;
    /** D13：是否展开了全量（**瞬时态**，不落盘）。 */
    private boolean open = false;

    /** 已合成的资产行（本地缓存 + 本地价格 ⇒ 零网络）。 */
    private final List<Book> items = new ArrayList<Book>();

    /**
     * 🆕 TASK-087-A2：最近一次换料的**共享快照**（条目 + 合计口径）。
     * 🔴 资产页与**资产壁纸**（{@link AssetPoster}）用的是**同一份** {@link #snapshot} 结果 ——
     * 价格链 / 收入算法 / 排序 / 合计只有一份实现，杜绝双源分叉。
     */
    private Snapshot snap;

    // ── 行缓存（w / unit / sort / open / **view** / 数据版本 六元组）──
    //    🔴 TASK-087-A1：缓存键**不含** `inner`（内滚只改"可见窗口"，不改"内容行"）。
    private List<BillSection.Row> rows;
    private float laidW = -1f;
    private float laidUnit = -1f;
    private int laidSort = -1;
    private boolean laidOpen = false;
    /**
     * 🆕 D14 换皮：缓存的**皮肤**维度（`MenuPrefs.view`；`ctx == null` 时 `-1`）。
     * 摘录皮肤每本书多画一行摘录、读书皮肤画折行书名 ⇒ 两者**行数不同**，漏键会让切皮后
     * 资产表保持旧皮肤版面。`AssetSection` 里其余影响绘制的读数只有 `noteOf`（绘制期现算）
     * 与 `PagePrefs.moduleName`（标题，与版面无关）——**`paidAlgo`/`showProgress` 本类一次都没读**
     * ⇒ 不进键（已查，见 227）。
     */
    private int laidView = -1;
    private int stamp = 0;
    private int laidStamp = -1;

    private final RectF[] chipRects = { new RectF(), new RectF(), new RectF() };
    private final RectF toggleRect = new RectF();

    // ── 🆕 TASK-087-A1（2026-10-12，用户 ③）：固定抬头 + 固定尾部 + **中间条目内滚** ──

    /** 抬头右端「生成壁纸」文字按钮矩形（内容坐标；画与命中共用，见 {@link #sortRects}）。 */
    private final RectF wallRect = new RectF();
    /**
     * 尾部**固定行**（「… 还有 N 本」→「展开 ▽ / 收起 △」→ 分隔线 → 合计行 → 三计数提示）。
     * 🔴 与 {@link #rows} 分两层：尾部**不随** {@link #inner} 移动；合计口径仍是**全部 items**（D15）。
     */
    private List<BillSection.Row> footRows;
    /** 尾部里「展开 / 收起」那行的下标（`-1` = 本页不可折叠 ⇒ 无该行）。 */
    private int toggleFootIdx = -1;
    /** 内滚位移（像素，0 = 内容顶部）。🔴 只有**条目视口**随它动，抬头/尾部不动。 */
    private float inner = 0f;
    /**
     * 墨台顶栏高（px）—— 由 `DeskPageView` 每帧喂入（= `DeskRenderer.headerHeight(unit)`）。
     *
     * <p>🔴 为什么需要：视口高 = `vh − 顶栏 − 抬头 − 尾部`（Lead 冻结公式），而本类拿不到
     * `DeskRenderer` 的**私有**常量 `SZ_TITLE(18) × BAR_H_UNITS(3.0)`；`≤ 0` 时按 `54 × unit` 兜底。
     */
    private float deskHeadH = 0f;

    /** 🆕 W2：手填原价键盘（与墨单**共用同一实现**；本类只做宿主侧的开/关、落盘与重算）。 */
    private final PricePad pad = new PricePad();

    // ══════════════════════ 数据 ══════════════════════

    /**
     * 一行资产（`AssetStore` 缓存 + 本地价格的合成视图；**不落任何新盘**）。
     *
     * <p>🆕 TASK-087-A2：改为**包内可见**（连同 {@link Snapshot}）—— 资产壁纸 {@link AssetPoster}
     * 读同一份条目对象，不再另建一套数据模型。
     */
    static final class Book {
        String id, title, author;
        /** 摘录皮肤第三行（无摘录 ⇒ null ⇒ **不占行**，D14）。 */
        String note;
        /** 累计阅读时长（秒）。 */
        int sec;
        /** 阅读进度百分比（`<0` = 未取到）。 */
        int pct;
        /** 原价（分）；仅当 {@link #priceKnown} 时有意义。 */
        int fen;
        /** true = **有价**（`fen > 0`，能量收入）。 */
        boolean priced;
        /** 🆕 补丁（m01985）：true = **价格已定**（有价 **或** 明确免费）—— 与「未知」严格区分。 */
        boolean priceKnown;
        /** 🆕 补丁（m01985）：true = **明确免费**（价格源给出 `0` 且带 free 标记）⇒ 显示「免费」+ 收入 `¥0`。 */
        boolean free;
        /** 收入（分）。 */
        int income;
        /** false = 收入不可知（**原价未知** 或 **进度未取到**）⇒ 显示 `—` 且**不计入**收入合计。 */
        boolean incomeKnown;
    }

    // ══════════════════════ InsightRenderer.Section ══════════════════════

    @Override
    public String title() { return PagePrefs.moduleName(PagePrefs.MOD_RANK); }

    @Override
    public float height(float w, float vh, float unit) {
        laid(w, unit);
        final float head = InsightRenderer.secHeadH(unit);
        if (items.isEmpty() || rows == null || rows.isEmpty()) {
            return head + SZ_BODY * unit * 3.8f;      // 空态（居中一句话；无尾部）
        }
        // 🔴 Lead 冻结公式：一屏 = 抬头 + 视口 + 尾部；`vpH = (vh − 墨台顶栏) − 抬头 − 尾部`。
        //    内容装得下 ⇒ 分区**自高收缩**为「抬头 + 内容 + 尾部」（不留大片空白，canScroll=false）。
        final float vp = vpOf(vh, unit);
        final float ch = contentH(unit);
        return head + ((ch < vp) ? ch : vp) + footH(unit);
    }

    @Override
    public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
        float y = InsightRenderer.drawSectionHead(c, w, top, unit, p, title());
        laid(w, unit);
        if (items.isEmpty() || rows == null || rows.isEmpty()) {
            InsightRenderer.drawCenteredIn(c, w, y, SZ_BODY * unit * 3.8f, unit, p, EMPTY);
            return;
        }
        drawSortChips(c, w, unit, top);            // D12：标题行右端三键 + 🆕「生成壁纸」（**固定**）
        clampInner(vh, unit);
        final float left = w * InsightRenderer.PAD_X_RATIO;
        final float right = w - left;
        final float vp = vpOf(vh, unit);
        // ── 中间条目视口：裁剪 + 逐行 `− inner` + 视口剔除（照 `InsightRenderer.drawRankSection` 纪律）──
        c.save();
        c.clipRect(left, y, right, y + vp);
        float yy = y - inner;
        for (int i = 0; i < rows.size(); i++) {
            BillSection.Row r = rows.get(i);
            final float rh = r.h(unit);
            if (yy + rh > y && yy < y + vp) r.draw(c, left, right, yy, unit, p);
            yy += rh;
        }
        c.restore();
        // ── 尾部：**固定**（不随 inner 动），自视口下沿起画 ──
        float fy = y + vp;
        for (int i = 0; i < footRows.size(); i++) {
            fy += footRows.get(i).draw(c, left, right, fy, unit, p);
        }
    }

    // ══════════════════════ 🆕 TASK-087-A1：内滚 API（宿主 `DeskPageView` 用）══════════════════════

    /** 墨台顶栏高喂入（`≤ 0` 忽略 —— 保持上一次有效值 / 兜底 `54 × unit`）。 */
    void setDeskHeadH(float h) { if (h > 0f) deskHeadH = h; }

    /** 顶栏高（`DeskRenderer.headerHeight` 的本地镜像；未喂入时按 `18 × 3.0 × unit` 兜底）。 */
    private float headH(float unit) { return (deskHeadH > 0f) ? deskHeadH : BLANK_HEAD_UNITS * unit; }

    /** 条目**内容**总高（全部内容行，与 {@link #inner} 无关）。 */
    private float contentH(float unit) {
        if (rows == null) return 0f;
        float h = 0f;
        for (int i = 0; i < rows.size(); i++) h += rows.get(i).h(unit);
        return h;
    }

    /** 尾部固定块总高（与 {@link #inner} 无关）。 */
    private float footH(float unit) {
        if (footRows == null) return 0f;
        float h = 0f;
        for (int i = 0; i < footRows.size(); i++) h += footRows.get(i).h(unit);
        return h;
    }

    /** 条目视口高（像素）——一屏减去顶栏/抬头/尾部。可为负（极小屏）⇒ 调用方按 `≥0` 用。 */
    float vpOf(float vh, float unit) {
        final float v = vh - headH(unit) - InsightRenderer.secHeadH(unit) - footH(unit);
        return (v > 0f) ? v : 0f;
    }

    /** 视口里能内滚的最大位移（`0` ⇒ 装得下、不需要内滚）。 */
    float maxInner(float vh, float unit) {
        final float m = contentH(unit) - vpOf(vh, unit);
        return (m > 0f) ? m : 0f;
    }

    /** 是否需要内滚（用户 ③：内容多于一屏时中间可滑）。 */
    boolean canScroll(float vh, float unit) { return maxInner(vh, unit) > 0.5f; }

    /** 当前内滚位移（自动化断言用）。 */
    float inner() { return inner; }

    /** 内滚复位（换料/换皮/换排序/展开收起后由 {@link #laid} 调用）。 */
    void resetInner() { inner = 0f; }

    /**
     * 内滚一步。{@code delta > 0} = 内容上移（看更靠后的行）。
     *
     * @return 是否**真的**移动了；`false` = 本方向已到边界（`inner == 0` 或 `== maxInner`）
     *         ⇒ 宿主必须把这次 MOVE **回落给整页滚动**（否则用户滚到页面底部后翻不回去）。
     */
    boolean scrollBy(float delta, float vh, float unit) {
        final float m = maxInner(vh, unit);
        float n = inner + delta;
        if (n < 0f) n = 0f;
        if (n > m) n = m;
        if (n == inner) return false;
        inner = n;
        return true;
    }

    /** 把 {@link #inner} 钳回 `[0, maxInner]`（视口高随屏/字号变化时用）。 */
    private void clampInner(float vh, float unit) {
        final float m = maxInner(vh, unit);
        if (inner > m) inner = m;
        if (inner < 0f) inner = 0f;
    }

    /**
     * 触点 y 是否落在**条目视口**矩形内（屏幕坐标）。
     *
     * <p>🔴 宿主在 ACTION_DOWN 只用它判定一次：视口内 ⇒ 锁定 `dragAsset`（内滚），
     * 抬头/尾部 ⇒ 不锁（那些是固定控件或整页滚动的落点）。
     */
    boolean inViewport(float y, float top, float vh, float unit) {
        if (top == -1f) return false;
        final float t = top + InsightRenderer.secHeadH(unit);
        return y >= t && y <= t + vpOf(vh, unit);
    }

    // ══════════════════════ 装配 / 换料 ══════════════════════

    /**
     * 换料（由 {@link DeskModules.RankModule#build} 调用，每次装配分区时调一次）。
     *
     * <p>🔴 **零阻塞**：只读本地缓存 + 本地价格索引；随后 {@link AssetRefresher#ensure} 是
     * **幂等 + TTL 闸门 + 后台线程**的，本调用不会产生任何网络等待（D19）。
     */
    void refresh(Context c) {
        ctx = (c == null) ? null : c.getApplicationContext();
        items.clear();
        if (ctx != null) load(ctx);
        stamp++;
        if (ctx != null) AssetRefresher.ensure(ctx);
    }

    /** D12：当前排序键。 */
    int sort() { return sort; }

    /** D12：切排序键。@return true = 真的变了（调用方要 remeasure + 重绘） */
    boolean setSort(int key) {
        if (key < SORT_SEC || key > SORT_INCOME || key == sort) return false;
        sort = key;
        return true;
    }

    /** D13：是否展开全量。 */
    boolean isOpen() { return open; }

    /** D13：展开 / 收起。@return true = 状态变了 */
    boolean toggleOpen() {
        open = !open;
        return true;
    }

    // ══════════════════════ 🆕 第七批 W2（task-66）：长按移出 / 长按录价 ══════════════════════

    /** 🆕 W2：本地重算（**零网络**、不触发 {@link AssetRefresher}）—— 设置项或手填价改完后调用。 */
    void reload(Context c) {
        ctx = (c == null) ? null : c.getApplicationContext();
        items.clear();
        if (ctx != null) load(ctx);
        stamp++;
    }

    /**
     * 🆕 W2：长按命中书目**主行**（返回行下标；`-1` = 未命中）。两个区域**按 x 分列、互不重叠**：
     * <ul>
     *   <li><b>摘录皮肤</b>（画价格两列）：书名区 `x ∈ [left, split)`、价格区 `x ∈ [split, right]`，
     *       其中 `split = right − 2·{@link BillSection#PRICE_COL_W}·unit`（与行渲染用**同一常量**）；</li>
     *   <li><b>读书皮肤</b>（不画价格列）：整行 `[left, right]` 都是书名区；`priceZone = true` 恒 `-1`。</li>
     * </ul>
     * y 判据 = 该行矩形上下各外扩 `3·unit`（与墨单 `hitPriceRow` 同一宽容度）。
     */
    int hitRow(float x, float y, float w, float unit, float top, boolean priceZone) {
        if (items.isEmpty() || ctx == null) return -1;
        laid(w, unit);
        if (rows == null || rows.isEmpty()) return -1;
        final boolean excerpt = (MenuPrefs.view(ctx) == MenuPrefs.VIEW_EXCERPT);
        if (priceZone && !excerpt) return -1;                 // 读书皮肤没有价格列 ⇒ 无价格区
        final float left = w * InsightRenderer.PAD_X_RATIO;
        final float right = w - left;
        final float split = right - 2f * BillSection.PRICE_COL_W * unit;
        // 🔴 TASK-087-A1：条目是**内滚**的 ⇒ 命中起点必须减 `inner`，否则会出现「点 A 行改 B 书价」。
        float yy = top + InsightRenderer.secHeadH(unit) - inner;
        for (int i = 0; i < rows.size(); i++) {
            BillSection.Row r = rows.get(i);
            final float rh = r.h(unit);
            if (r.bookId != null) {                            // 只认书目**主行**（meta / 摘录行 bookId 为 null）
                final boolean inX = priceZone ? (x >= split && x <= right)
                        : (excerpt ? (x >= left && x < split) : (x >= left && x <= right));
                if (inX && y >= yy - unit * 3f && y <= yy + rh + unit * 3f) return i;
            }
            yy += rh;
        }
        return -1;
    }

    /** 🆕 W2：行下标 ⇒ 书 id（`null` = 该行不是书目主行）。 */
    String bookIdAt(int rowIdx) {
        if (rows == null || rowIdx < 0 || rowIdx >= rows.size()) return null;
        return rows.get(rowIdx).bookId;
    }

    /** 🆕 W2：书 id ⇒ 书名（本地缓存优先；缺则回 id 本身 —— 提示与设置页共用）。 */
    String titleOf(String id) {
        if (id == null || id.length() == 0) return "";
        for (int i = 0; i < items.size(); i++) {
            Book b = items.get(i);
            if (id.equals(b.id) && b.title != null && b.title.length() > 0) return b.title;
        }
        return id;
    }

    /** 🆕 W2：把一本书移出阅读资产统计（幂等；落盘在 `PagePrefs`，见 W1 冻结 API）。 */
    boolean hideBook(Context c, String id) {
        if (c == null || id == null || id.length() == 0) return false;
        final boolean ok = PagePrefs.hideAssetBook(c, id);
        if (ok) reload(c);
        return ok;
    }

    // ── 🆕 W2：手填原价（键盘与墨单**共用** {@link PricePad}；落盘仍走 `core/MenuPrice`）──

    boolean padOpen() { return pad.isOpen(); }

    void closePad() { pad.close(); }

    /** 打开录价键盘（`rowIdx` = 长按命中的书目主行）。 */
    void openPad(int rowIdx) {
        if (rows == null || rowIdx < 0 || rowIdx >= rows.size()) return;
        openPad(rows.get(rowIdx).bookId);
    }

    /**
     * 🆕 W2：按**书 id** 打开录价键盘 —— 宿主在「按下」那一刻就把 id 定住（行下标会随数据刷新漂移，
     * 见 `DeskPageView.longBookId`）。
     */
    void openPad(String bookId) {
        if (ctx == null || bookId == null || bookId.length() == 0) return;
        pad.open(bookId, titleOf(bookId), MenuPrice.fen(ctx, bookId));
    }

    int hitPad(float x, float y, float w, float vh, float unit) { return pad.hit(x, y, w, vh, unit); }

    /** 键盘按键（与墨单同口径：`R_CLEAR` ⇒ 清除手填价；`R_COMMIT` ⇒ 写入 `MenuPrice`）。 */
    void padPress(int key) {
        final int r = pad.press(key);
        if (r == PricePad.R_EDIT) return;
        if (r == PricePad.R_CANCEL) { pad.close(); return; }
        final String bid = pad.bookId();
        if (r == PricePad.R_CLEAR) {
            MenuPrice.set(ctx, bid, -1);
            CardDebug.noteV(ctx, "阅读资产清除手动价：bookId=" + bid);
        } else if (r == PricePad.R_COMMIT) {
            MenuPrice.set(ctx, bid, pad.resultFen());
            CardDebug.noteV(ctx, "阅读资产手动价：bookId=" + bid + " ⇒ " + BillMoney.yuan(pad.resultFen()));
        }
        pad.close();
        reload(ctx);              // 价格链重算（可能改变排序 / 合计）
    }

    /** 🆕 W2：把打开的录价键盘画在**所有分区之上**（由 `DeskPageView` 在 `renderer.draw` 之后调用）。 */
    void drawPad(Canvas c, float w, float vh, float unit, Paint p) {
        if (!pad.isOpen() || ctx == null) return;
        pad.draw(c, w, vh, unit, p, ctx.getString(R.string.menu_price_manual_title), pad.title());
    }

    // ══════════════════════ 命中（三键 / 展开）══════════════════════

    /**
     * 点三键。@return 命中的排序键（{@link #SORT_SEC}/{@link #SORT_PRICE}/{@link #SORT_INCOME}）；
     * `-1` = 没点到。
     */
    int hitSort(float x, float y, float w, float unit, float top) {
        if (items.isEmpty()) return -1;
        sortRects(w, unit, top);
        for (int i = 0; i < chipRects.length; i++) {
            if (chipRects[i].contains(x, y)) return i;
        }
        return -1;
    }

    /**
     * D13：点「展开 ▽ / 收起 △」。
     *
     * <p>🔴 TASK-087-A1：该行属于**固定尾部**（用户拍板 3）⇒ **不减 `inner`**（减了反而与画的位置错位）；
     * 位置 = 抬头下沿 + 视口高 + 尾部内前几行高。命中区 = 整行宽（方便点）。
     */
    boolean hitToggle(float x, float y, float w, float vh, float unit, float top) {
        if (items.isEmpty()) return false;
        laid(w, unit);
        if (toggleFootIdx < 0 || footRows == null || toggleFootIdx >= footRows.size()) return false;
        float yTop = top + InsightRenderer.secHeadH(unit) + vpOf(vh, unit);
        for (int i = 0; i < toggleFootIdx; i++) yTop += footRows.get(i).h(unit);
        final float left = w * InsightRenderer.PAD_X_RATIO;
        toggleRect.set(left, yTop, w - left, yTop + footRows.get(toggleFootIdx).h(unit));
        return toggleRect.contains(x, y);
    }

    /**
     * 🆕 TASK-087-A1：点抬头右端「生成壁纸」文字按钮。
     *
     * <p>🔴 归属：**布局与命中 = A1**；按钮按下后「生成资产壁纸」的**动作**由 A2 在 `DeskPageView.enterAssetWallpaper()`
     * （那里消费这次点击并进入壁纸预览态）实现 —— 本方法只判命中、不消费点击。抬头**固定** ⇒ 同样不减 `inner`。
     */
    boolean hitWall(float x, float y, float w, float unit, float top) {
        if (items.isEmpty() || ctx == null) return false;
        sortRects(w, unit, top);
        if (wallRect.isEmpty()) return false;
        // 与 `BillSection.hitWallpaper` 同一宽容度（字小、手指点不准）：四周各外扩 `6 × unit`
        final float m = unit * 6f;
        return x >= wallRect.left - m && x <= wallRect.right + m
                && y >= wallRect.top - m && y <= wallRect.bottom + m;
    }

    // ══════════════════════ 换料实现 ══════════════════════

    private void load(Context c) {
        // 🔴 TASK-087-A2：条目构建 / 过滤 / 价格链 / 收入 / 合计**全部**在共享构建器
        //    {@link #snapshot} 里 —— 资产页与**资产壁纸**（{@link AssetPoster}）调同一份，
        //    禁止在壁纸侧重写第二套算法（双源必然分叉）。
        snap = snapshot(c);
        items.addAll(snap.items);
    }

    // ══════════════════════ 🆕 TASK-087-A2：共享构建器（资产页 + 资产壁纸同源） ══════════════════════

    /** 本地价格链索引（每本书一条；建一次，**零新增请求**）。 */
    private static final class Ledger {
        /** `bookId → 市场价(分)`：`bills.xml` 落盘的 `Bill.Item.marketPriceFen > 0`。 */
        final Map<String, Integer> billFen = new HashMap<String, Integer>();
        /** 账单里**明确免费**的 `bookId`（`marketPriceFen == 0`）。 */
        final Set<String> billFree = new HashSet<String>();
        /** `bookId → 原价(分)`：`cfg.xml` 落盘的 `PeriodStats.Longest` 经 {@link BillMoney#fenOf} 判为**有价**者。 */
        final Map<String, Integer> statFen = new HashMap<String, Integer>();
        /** 统计里**明确免费**的 `bookId`（`fenOf` 判为 `FREE`）。 */
        final Set<String> statFree = new HashSet<String>();
    }

    /**
     * 🆕 TASK-087-A2：**资产快照** = 过滤后的全部条目 + 合计口径。
     *
     * <p>🔴 这是资产相关数字的**唯一**来源：墨台屏上「阅读资产」列表 / 合计与资产壁纸
     * （{@link AssetPoster}）都读它 ⇒ 同一筛选 / 同一价格链 / 同一收入算法 / 同一合计。
     * {@code items} 里的 {@code note}（摘录文本）**不在此处烘焙**（与皮肤无关，见 D14），
     * 由调用方按当前皮肤用 {@link #excerptOf} 现算。
     */
    static final class Snapshot {
        final List<Book> items = new ArrayList<Book>();
        int secSum;         // 时长合计（秒，**全部**条目）
        int priceSum;       // 原价合计（分，只累加「有价」条目）
        int incomeSum;      // 收入合计（分，只累加 `incomeKnown`）
        int knownN;         // 已计价 = 有价 + 明确免费
        int unkPriceN;      // 原价未知
        int unkProgN;       // 有价但进度未取到 ⇒ 收入 `—`
    }

    /** 建本地价格链索引（零网络）—— 资产页与资产壁纸**共用**。 */
    private static Ledger ledgerIndex(Context c) {
        Ledger led = new Ledger();
        final String[] modes = { PeriodRange.WEEKLY, PeriodRange.MONTHLY };
        for (int m = 0; m < modes.length; m++) {
            List<Long> starts = BillStore.startsOf(c, modes[m]);
            if (starts == null) continue;
            for (int i = 0; i < starts.size(); i++) {
                final long t = starts.get(i).longValue();
                Bill bill = BillStore.load(c, modes[m], t);
                if (bill != null && bill.items != null) {
                    for (int k = 0; k < bill.items.size(); k++) {
                        Bill.Item it = bill.items.get(k);
                        if (it == null || it.bookId == null || it.bookId.length() == 0) continue;
                        if (it.marketPriceFen > 0) {
                            if (!led.billFen.containsKey(it.bookId)) {
                                led.billFen.put(it.bookId, Integer.valueOf(it.marketPriceFen));
                            }
                        } else if (it.marketPriceFen == 0) {
                            led.billFree.add(it.bookId);
                        }
                    }
                }
                PeriodStats ps = StatsStore.load(c, modes[m], t);
                if (ps == null || ps.longest == null) continue;
                for (int k = 0; k < ps.longest.size(); k++) {
                    PeriodStats.Longest lg = ps.longest.get(k);
                    if (lg == null || lg.bookId == null || lg.bookId.length() == 0) continue;
                    // 三态照 `BillMoney.fenOf`（`centPrice` / `priceYuan` / `origYuan` 取 max，free 才认免费）
                    final int f = BillMoney.fenOf(lg.centPrice, lg.priceYuan, lg.origYuan, lg.free);
                    if (f > 0) {
                        if (!led.statFen.containsKey(lg.bookId)) led.statFen.put(lg.bookId, Integer.valueOf(f));
                    } else if (f == 0) {
                        led.statFree.add(lg.bookId);
                    }
                }
            }
        }
        return led;
    }

    /**
     * 🆕 TASK-087-A2：**共享构建器** —— 过滤 → 条目（价格链 + 收入）→ 合计。**零网络、零 I/O 之外**。
     *
     * <p>🔴 摘录文本**不在这里烘焙**（`load` 与皮肤无关，见 D14）—— 调用方按当前皮肤用
     * {@link #excerptOf} 现算；排序也不在这里（调用方用 {@link #sorter}）。
     */
    static Snapshot snapshot(Context c) {
        Snapshot o = new Snapshot();
        if (c == null) return o;
        final Ledger led = ledgerIndex(c);
        final List<AssetStore.Book> all = AssetStore.all(c);
        // 🆕 第七批 W2（task-66）A1：**独立**的「只统计书架上的书」（只作用于阅读资产，不共用
        //    `rank_shelf_only`）—— 口径**同 `core/RankFilter#apply`**：开关关 ⇒ 不过滤；
        //    `BookStore.shelfIds` 返回 `null`（文件缺失/读失败 ⇒ 「还不知道」）⇒ 不过滤（等后台补拉）；
        //    空集 ≠ null（`BookStore` 文档：空集 = 真的没有书）⇒ 照常过滤；**顺序不变**。
        final Set<String> shelf = PagePrefs.isAssetShelfOnly(c) ? BookStore.shelfIds(c) : null;
        // 🆕 A2：长按书名移出统计的书 ⇒ **不进列表、不进合计**（合计按本表逐本累加，见 addTotal）。
        final Set<String> hidden = PagePrefs.assetHiddenIds(c);
        // 🔴 D14 换皮修复：摘录文本**不在换料期烘焙**（`load` 与皮肤无关），改由 `laid()` 按
        //    **当前皮肤**现算 —— 否则切到读书皮肤后第三行仍画着摘录期的旧文本（227 §10 缺陷②）。
        for (int i = 0; i < all.size(); i++) {
            AssetStore.Book s = all.get(i);
            if (s == null || s.id == null) continue;
            if (s.sec <= MIN_SEC) continue;                     // D9：≤10 分钟不进资产表
            if (hidden != null && hidden.contains(s.id)) continue;   // A2：已长按移出统计
            if (shelf != null && !shelf.contains(s.id)) continue;    // A1：只在架（清单未知时 shelf==null）
            // 🆕 TASK-087-A1（用户 ②，拍板 4/5）：**只统计读完的书**（缺省打开）。
            //    🔴 判据唯一入口 = `BillMoney.progressForBilling`（**不许另写一套**）：
            //       `finished ⇒ 100` / `pct ≥ READ_DONE_PCT(99) ⇒ 100` / `pct < 0 且未读完 ⇒ -1`
            //       ⇒ 「进度未知且未读完」自然落进 `< 100`，按用户拍板 5 **隐藏**（关掉本开关即可找回）。
            //    🔴 三条过滤（读完 / 只在架 / 已移出）**AND 叠加**，且都作用于**同一份 `load` 结果**
            //       ⇒ 墨台「阅读资产」列表、合计与**资产壁纸**（A2 复用同一份）一起生效。
            if (PagePrefs.isAssetFinishedOnly(c)
                    && BillMoney.progressForBilling(s.pct, s.finished()) < 100) continue;
            Book b = new Book();
            b.id = s.id;
            b.title = s.title;
            b.author = s.author;
            b.sec = s.sec;
            b.pct = s.pct;
            // 价格链（🆕 m01985 授权，**三态**）：手填价 → 笔记索引 → 账单落盘市场价 →
            // 统计落盘原价 → 仍无 ⇒ **未知**。`>0` 有价 / `0`（且明确 free）免费 / `-1` 未知。
            // 🔴 旧实现把「未知」当「免费」，用户 m00885 反馈「许多应当有原价的书籍都显示免费」即此。
            int fen = -1;
            boolean free = false;
            int manual = MenuPrice.fen(c, s.id);
            if (manual >= 0) {
                fen = manual;
                free = (manual == 0);
            }
            if (fen < 0) {
                int nf = NoteStore.priceFenOf(c, s.id);
                if (nf >= 0) {
                    fen = nf;
                    free = (nf == 0);
                }
            }
            if (fen < 0 && led.billFen.containsKey(s.id)) fen = led.billFen.get(s.id).intValue();
            if (fen < 0 && led.billFree.contains(s.id)) {
                fen = 0;
                free = true;
            }
            if (fen < 0 && led.statFen.containsKey(s.id)) fen = led.statFen.get(s.id).intValue();
            if (fen < 0 && led.statFree.contains(s.id)) {
                fen = 0;
                free = true;
            }
            b.priceKnown = fen >= 0;
            b.free = free;
            b.priced = fen > 0;
            b.fen = b.priced ? fen : 0;
            int delta = BillMoney.progressForBilling(s.pct, s.finished());
            if (b.priced && delta >= 0) {
                b.income = (int) (((long) b.fen * (long) delta + 50L) / 100L);
                b.incomeKnown = true;
            } else {
                // 明确免费 ⇒ 「免费」+ 收入 ¥0（**计入**，不影响合计）；
                // 原价未知 ⇒ 原价 `—` + 收入 `—`；有价但进度未取到 ⇒ 收入 `—`（后两者都不计入收入合计）
                b.income = 0;
                b.incomeKnown = free && !b.priced;
            }
            o.items.add(b);
        }
        // ── 合计口径（D15 / m01985，读数 scope = **全部**条目，与展开态无关）──
        for (int i = 0; i < o.items.size(); i++) {
            Book b = o.items.get(i);
            o.secSum += b.sec;
            if (b.priced) {
                o.priceSum += b.fen;
                o.knownN++;                                     // 有价 ⇒ 计入原价合计
                if (!b.incomeKnown) o.unkProgN++;                // 有价但进度未取到 ⇒ 收入 `—`
            } else if (b.free) {
                o.knownN++;                                     // 明确免费 ⇒ 价格已定（0）
            } else {
                o.unkPriceN++;                                  // 原价未知 ⇒ 原价与收入都 `—`
            }
            if (b.incomeKnown) o.incomeSum += b.income;
        }
        return o;
    }

    /**
     * 摘录（D14：摘录皮肤第三行）—— 手填摘录优先，其次**本地已同步**的最新原文划线 / 想法。
     * 🔴 零请求、无摘录返回 null（⇒ 不占行）。
     */
    static String excerptOf(Context c, String bookId) {
        String s = clean(MenuPrefs.noteOf(c, bookId));
        if (s != null) return s;
        JSONArray marks = NoteStore.marks(c, bookId);
        if (marks != null) {
            String best = null;
            long bt = Long.MIN_VALUE;
            for (int i = 0; i < marks.length(); i++) {
                JSONObject o = marks.optJSONObject(i);
                if (o == null) continue;
                long t = o.optLong("createTime", 0L);
                String v = clean(o.optString("markText", ""));
                if (v != null && t >= bt) { bt = t; best = v; }
            }
            if (best != null) return best;
        }
        JSONArray ideas = NoteStore.ideas(c, bookId);
        if (ideas == null) return null;
        String bestAbs = null, bestBody = null;
        long ta = Long.MIN_VALUE, tb = Long.MIN_VALUE;
        for (int i = 0; i < ideas.length(); i++) {
            JSONObject o = ideas.optJSONObject(i);
            if (o == null) continue;
            long t = o.optLong("createTime", 0L);
            String abs = clean(o.optString("abstract", ""));
            if (abs != null && t >= ta) { ta = t; bestAbs = abs; }
            String body = clean(o.optString("content", ""));
            if (body != null && t >= tb) { tb = t; bestBody = body; }
        }
        return (bestAbs != null) ? bestAbs : bestBody;
    }

    // ══════════════════════ 版面（缓存 + 几何）══════════════════════

    private void laid(float w, float unit) {
        // 🔴 D14 换皮修复：`view`（皮肤）**必须进缓存键** —— 摘录皮肤每本书多一行摘录、
        //    读书皮肤画折行书名，两者行数不同；漏此维度会让切皮后资产表保持旧皮肤版面。
        final int view = (ctx != null) ? MenuPrefs.view(ctx) : -1;
        if (rows != null && w == laidW && unit == laidUnit && sort == laidSort
                && open == laidOpen && stamp == laidStamp && view == laidView) {
            return;
        }
        rows = new ArrayList<BillSection.Row>();
        footRows = new ArrayList<BillSection.Row>();
        toggleFootIdx = -1;
        // 🔴 TASK-087-A1：内容/皮肤/排序/展开态真的变了 ⇒ 内滚复位到顶（避免停在越界位置画成空白）。
        //    缓存键**不含** `inner`（"内容行"与"可见窗口"分两层，否则滚动不会重画）。
        inner = 0f;
        if (!items.isEmpty()) {
            List<Book> order = new ArrayList<Book>(items);
            Collections.sort(order, bySort());
            final int n = order.size();
            final int shown = open ? n : Math.min(COLLAPSED_N, n);
            final boolean collapsible = n > COLLAPSED_N;
            final boolean excerpt = (view == MenuPrefs.VIEW_EXCERPT);
            for (int i = 0; i < shown; i++) {
                Book b = order.get(i);
                // 摘录文本按**当前皮肤**现算（只算真正会被画出来的那几本；读书皮肤不算 ⇒ 省读数）
                if (excerpt) b.note = excerptOf(ctx, b.id);
                addItem(rows, b, i + 1, excerpt);
            }
            // ── 🆕 TASK-087-A1：尾部**固定块**（用户拍板 3：「展开/收起」随尾部固定）──
            if (collapsible) {
                if (!open) {
                    // D13：省略号行（「… 还有 N 本」）—— 归尾部（固定），不进内滚内容
                    BillSection.Row e = BillSection.Row.line("… 还有 " + (n - shown) + " 本", null, SZ_SMALL);
                    e.color = GRAY;
                    footRows.add(e);
                }
                BillSection.Row t = BillSection.Row.line(open ? COLLAPSE : EXPAND, null, SZ_SMALL);
                t.color = GRAY;
                toggleFootIdx = footRows.size();
                footRows.add(t);
            }
            // 合计口径仍是**全部 items**（不是 shown）—— `addTotal` 只读 `items`，与展开态无关。
            addTotal(footRows);
        }
        laidW = w;
        laidUnit = unit;
        laidSort = sort;
        laidOpen = open;
        laidView = view;
        laidStamp = stamp;
    }

    /** 🆕 TASK-087-A2：**只读**访问器 —— 资产壁纸要与屏上用**同一个排序键**（不加写路径）。 */
    int sortKey() { return sort; }

    /** 一条书目（摘录皮肤 = 主串 + meta + 摘录；读书皮肤 = 折行书名 + meta，精简与周/月一致）。 */
    private void addItem(List<BillSection.Row> out, Book b, int no, boolean excerpt) {
        // 三态（🆕 m01985）：有价 ⇒ 金额；**明确免费** ⇒ 「免费」；**未知** ⇒ `—`
        // 🔴 TASK-087-A2：文本口径走**共享** {@link #incomeText}/{@link #priceText}（资产壁纸同源）
        final String income = incomeText(b);
        final String price = priceText(b);
        if (excerpt) {
            BillSection.Row r = BillSection.Row.line(no + ". " + nz(b.title), income, SZ_BODY);
            r.c3 = price;                        // 右起第二列（原价），与墨单同列宽
            r.bookId = b.id;                     // 🆕 W2：长按命中锚点（x 左半 = 书名区，右半 = 价格区）
            out.add(r);
            out.add(meta(b));
            if (b.note != null) {
                BillSection.Row t = BillSection.Row.wrap(b.note, SZ_SMALL, 1);
                t.color = GRAY;
                t.indent = 1f;
                out.add(t);
            }
        } else {
            BillSection.Row r = BillSection.Row.wrap(no + ". 「" + nz(b.title) + "」", SZ_BODY, 2);
            r.bookId = b.id;                     // 🆕 W2：读书皮肤**无价格列** ⇒ 整行都是书名区
            out.add(r);
            out.add(meta(b));
        }
    }

    /** 第二行小字：作者 · 时长 · 进度。 */
    private BillSection.Row meta(Book b) {
        StringBuilder sb = new StringBuilder();
        if (b.author != null && b.author.length() > 0) sb.append(b.author).append(" · ");
        sb.append(dur(b.sec));
        if (b.pct >= 0) sb.append(" · ").append(b.pct).append("%");
        BillSection.Row m = BillSection.Row.line(sb.toString(), null, SZ_SMALL);
        m.color = LIGHT;
        m.indent = 1f;
        return m;
    }

    /** D15：合计 = **全部**（含未展开）三列 —— 原价（右起第二列）/ 收入（贴右）/ 时长（主串）。 */
    private void addTotal(List<BillSection.Row> out) {
        final Snapshot s = (snap != null) ? snap : new Snapshot();
        out.add(BillSection.Row.dsep());
        out.add(totalRow(s));
        // 🆕 三计数（m01985）：`已计价` = **价格已定**（有价 + 明确免费）本数 ⇒ 与 `原价未知` 之和 = 全部本数
        BillSection.Row n = BillSection.Row.line(totalNote(s), null, SZ_SMALL);
        n.color = GRAY;
        out.add(n);
    }

    /** 🆕 TASK-087-A2 共享：合计行 —— 左「合计 N 本 · 时长」/ 右起第二「原价合计」/ 贴右「收入合计」。 */
    static BillSection.Row totalRow(Snapshot s) {
        BillSection.Row r = BillSection.Row.line(totalMain(s.items.size(), s.secSum), money(s.incomeSum), SZ_BODY);
        r.c3 = money(s.priceSum);
        return r;
    }

    /** 🆕 TASK-087-A2 共享：合计行主串（**全部**条目，与展开态无关）。 */
    static String totalMain(int n, int secSum) { return "合计 " + n + " 本 · " + dur(secSum); }

    /** 🆕 TASK-087-A2 共享：合计下方三计数小字。 */
    static String totalNote(Snapshot s) {
        return "已计价 " + s.knownN + " 本 · " + s.unkPriceN + " 本原价未知 · " + s.unkProgN + " 本进度未取到";
    }

    private Comparator<Book> bySort() { return sorter(sort); }

    /**
     * 🆕 TASK-087-A2 共享：排序键 → 比较器（资产页与资产壁纸**同一份**）。
     * 口径：`SORT_PRICE` 原价未知排最后、再按 `fen` 降序；`SORT_INCOME` 收入未知排最后、
     * 再按 `income` 降序；默认按时长降序；并列再按时长、再按书名字典序。
     */
    static Comparator<Book> sorter(final int sortKey) {
        return new Comparator<Book>() {
            @Override
            public int compare(Book x, Book y) {
                int c;
                if (sortKey == SORT_PRICE) {
                    // 原价未知（`—`）永远排在最后（🆕 m01985）
                    if (x.priceKnown != y.priceKnown) return x.priceKnown ? -1 : 1;
                    c = y.fen - x.fen;
                } else if (sortKey == SORT_INCOME) {
                    // 进度未取到（`—`）永远排在最后
                    int xi = x.incomeKnown ? x.income : Integer.MIN_VALUE;
                    int yi = y.incomeKnown ? y.income : Integer.MIN_VALUE;
                    c = (xi == yi) ? 0 : ((xi > yi) ? -1 : 1);
                } else {
                    c = y.sec - x.sec;
                }
                if (c != 0) return c;
                if (x.sec != y.sec) return y.sec - x.sec;
                return nz(x.title).compareTo(nz(y.title));
            }
        };
    }

    // ══════════════════════ 三键绘制 / 几何 ══════════════════════

    /** 画三键（D12）—— 命中区与绘制**共用** {@link #sortRects}。 */
    private void drawSortChips(Canvas c, float w, float unit, float top) {
        sortRects(w, unit, top);
        hp.setTextSize(SZ_SMALL * unit);
        hp.setTypeface(null);
        hp.setTextScaleX(1f);
        hp.setTextAlign(Paint.Align.CENTER);
        hp.setStyle(Paint.Style.FILL);
        for (int i = 0; i < SORT_LABELS.length; i++) {
            RectF r = chipRects[i];
            final boolean on = (i == sort);
            hp.setColor(on ? INK : LIGHT);
            hp.setFakeBoldText(on);
            Paint.FontMetrics fm = hp.getFontMetrics();
            float base = r.centerY() - (fm.ascent + fm.descent) / 2f;
            c.drawText(SORT_LABELS[i], r.centerX(), base, hp);
            if (on) {
                hp.setStyle(Paint.Style.FILL);
                hp.setColor(InkTheme.RULE);
                float ly = Math.round(r.bottom);
                c.drawRect(r.left, ly, r.right, ly + 1f, hp);
            }
        }
        // 🆕 TASK-087-A1：抬头最右端「生成壁纸」文字按钮（与 `BillSection` 的同名按钮同款：
        //    无边框、INK、与三键同字号；装不下就逐次 −0.4f 缩到 WALL_MAX_SHRINK）。
        if (!wallRect.isEmpty()) {
            final String label = wallLabel();
            float sz = SZ_SMALL * unit;
            final float limit = sz * WALL_MAX_SHRINK;
            hp.setTypeface(null);
            hp.setFakeBoldText(false);
            hp.setTextScaleX(1f);
            hp.setTextAlign(Paint.Align.CENTER);
            hp.setStyle(Paint.Style.FILL);
            hp.setColor(INK);
            hp.setTextSize(sz);
            final float avail = wallRect.width();
            while (sz > limit && hp.measureText(label) > avail) {
                sz -= 0.4f;
                hp.setTextSize(sz);
            }
            Paint.FontMetrics fm = hp.getFontMetrics();
            float base = wallRect.centerY() - (fm.ascent + fm.descent) / 2f;
            c.drawText(label, wallRect.centerX(), base, hp);
        }
        hp.setFakeBoldText(false);
        hp.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * 三键矩形（右对齐排在标题行右端）—— 画与命中同一份几何。
     *
     * <p>🆕 TASK-087-A1：抬头**最右端**先钉住「生成壁纸」文字按钮，三键再整体右对齐到它左侧
     * （间距 `≥ 4 × unit`；两者矩形**不相交** ⇒ 不会"点键变壁纸"）。显式 4 个矩形全为内容坐标
     * （抬头**固定**，全不减 `inner`）。
     */
    private void sortRects(float w, float unit, float top) {
        if (items.isEmpty()) {
            for (int i = 0; i < chipRects.length; i++) chipRects[i].setEmpty();
            wallRect.setEmpty();
            return;
        }
        final float right = w - w * InsightRenderer.PAD_X_RATIO;
        final float cy = top + InsightRenderer.secHeadH(unit) * 0.5f;
        final float pad = unit * 6f;
        final float gap = unit * 8f;
        final float half = unit * SZ_SMALL * 0.95f;
        hp.setTextSize(SZ_SMALL * unit);
        hp.setTypeface(null);
        // ── 最右端：「生成壁纸」文字按钮（与墨单头部同一块尺寸：62 × 1.85 × SZ_SMALL）──
        final float wallHalf = SZ_SMALL * unit * WALL_H_UNITS * 0.5f;
        wallRect.set(right - WALL_W_UNITS * unit, cy - wallHalf, right, cy + wallHalf);
        final float chipsRight = wallRect.left - WALL_GAP_UNITS * unit;
        final float[] ws = new float[SORT_LABELS.length];
        float total = gap * (SORT_LABELS.length - 1);
        for (int i = 0; i < SORT_LABELS.length; i++) {
            ws[i] = hp.measureText(SORT_LABELS[i]) + pad * 2f;
            total += ws[i];
        }
        float x = chipsRight - total;
        for (int i = 0; i < SORT_LABELS.length; i++) {
            chipRects[i].set(x, cy - half, x + ws[i], cy + half);
            x += ws[i] + gap;
        }
    }

    /** 「生成壁纸」按钮文案（复用墨单同一字符串键 ⇒ 两处入口视觉/文案一致）。 */
    private String wallLabel() {
        return (ctx != null) ? ctx.getString(R.string.menu_gen_wallpaper) : "生成壁纸";
    }

    // ══════════════════════ 价格链索引（🆕 补丁 m01985）══════════════════════

    /**
     * 建 `bookId → 价格` 的**本地**索引（🔴 零网络）：扫**已落盘**的账单（{@link BillStore}）与
     * 周期统计（{@link StatsStore}），取 `Bill.Item#marketPriceFen` 与
     * {@link PeriodStats.Longest#centPrice}。
     *
     * <p>链序（Lead m01985 冻结，**先命中先定**）：手填价 → {@link NoteStore#priceFenOf} →
     * 账单落盘市场价 → 统计落盘原价 → 仍无 ⇒ **未知**。本方法只备后两级，前两级在 {@link #load} 里逐本问。
     *
     * <p>🔴 语义照 {@link BillMoney#fenOf}：`> 0` 有价 / `0`（带 free 标记）明确免费 / `-1` 未知 ——
     * **不许用 max 之类的猜测**（两字段语义未经证实相同）。
     */
    // ══════════════════════ 小工具 ══════════════════════

    /** 时长文案（照墨单口径：`3h39m` / `219m`）。 */
    static String dur(int sec) {
        if (sec < 3600) return Math.max(1, sec / 60) + "m";
        final int h = sec / 3600;
        final int m = (sec % 3600) / 60;
        return (m > 0) ? (h + "h" + m + "m") : (h + "h");
    }

    /** 金额文案（走墨单同一口径：`¥39` / `¥12.50`；`¥0` = 免费收入）。 */
    static String money(int fen) { return BillMoney.yuan(fen); }

    /**
     * 🆕 TASK-087-A2 共享：**原价**文案（三态）—— 有价 ⇒ 金额；明确免费 ⇒ 「免费」；未知 ⇒ `—`。
     */
    static String priceText(Book b) {
        return b.priced ? money(b.fen) : (b.free ? "免费" : "—");
    }

    /**
     * 🆕 TASK-087-A2 共享：**收入**文案（三态）—— 可算 ⇒ 金额；否则 `—`（免费 ⇒ `¥0`，计入合计）。
     */
    static String incomeText(Book b) { return b.incomeKnown ? money(b.income) : "—"; }

    static String nz(String s) { return (s == null) ? "" : s; }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return (s.length() == 0) ? null : s;
    }
}
