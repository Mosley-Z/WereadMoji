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

    // ── 🆕 补丁（m01985）：本地价格链索引（每次换料建一次，**零新增请求**）──
    /** `bookId → 市场价(分)`：`bills.xml` 落盘的 `Bill.Item.marketPriceFen > 0`。 */
    private final Map<String, Integer> billFen = new HashMap<String, Integer>();
    /** 账单里**明确免费**的 `bookId`（`marketPriceFen == 0`）。 */
    private final Set<String> billFree = new HashSet<String>();
    /** `bookId → 原价(分)`：`cfg.xml` 落盘的 `PeriodStats.Longest` 经 {@link BillMoney#fenOf} 判为**有价**者。 */
    private final Map<String, Integer> statFen = new HashMap<String, Integer>();
    /** 统计里**明确免费**的 `bookId`（`fenOf` 判为 `FREE`）。 */
    private final Set<String> statFree = new HashSet<String>();

    // ── 行缓存（w / unit / sort / open / **view** / 数据版本 六元组）──
    private List<BillSection.Row> rows;
    private int toggleIdx = -1;
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

    /** 🆕 W2：手填原价键盘（与墨单**共用同一实现**；本类只做宿主侧的开/关、落盘与重算）。 */
    private final PricePad pad = new PricePad();

    // ══════════════════════ 数据 ══════════════════════

    /** 一行资产（`AssetStore` 缓存 + 本地价格的合成视图；**不落任何新盘**）。 */
    private static final class Book {
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
        float h = InsightRenderer.secHeadH(unit);
        if (rows == null || rows.isEmpty()) return h + SZ_BODY * unit * 3.8f;   // 空态（居中一句话）
        for (int i = 0; i < rows.size(); i++) h += rows.get(i).h(unit);
        return h + unit * 6f;
    }

    @Override
    public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
        float y = InsightRenderer.drawSectionHead(c, w, top, unit, p, title());
        laid(w, unit);
        if (rows == null || rows.isEmpty()) {
            InsightRenderer.drawCenteredIn(c, w, y, SZ_BODY * unit * 3.8f, unit, p, EMPTY);
            return;
        }
        drawSortChips(c, w, unit, top);            // D12：标题行右端三键
        final float left = w * InsightRenderer.PAD_X_RATIO;
        final float right = w - left;
        for (int i = 0; i < rows.size(); i++) {
            y += rows.get(i).draw(c, left, right, y, unit, p);
        }
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
        float yy = top + InsightRenderer.secHeadH(unit);
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

    /** D13：点「展开 ▽ / 收起 △」（命中区 = 整行，方便点）。 */
    boolean hitToggle(float x, float y, float w, float unit, float top) {
        if (items.isEmpty()) return false;
        laid(w, unit);
        if (toggleIdx < 0 || rows == null || toggleIdx >= rows.size()) return false;
        float yTop = top + InsightRenderer.secHeadH(unit);
        for (int i = 0; i < toggleIdx; i++) yTop += rows.get(i).h(unit);
        final float left = w * InsightRenderer.PAD_X_RATIO;
        toggleRect.set(left, yTop, w - left, yTop + rows.get(toggleIdx).h(unit));
        return toggleRect.contains(x, y);
    }

    // ══════════════════════ 换料实现 ══════════════════════

    private void load(Context c) {
        List<AssetStore.Book> all = AssetStore.all(c);
        buildLedgerIndex(c);                                    // 🆕 价格链索引（零网络，换料时建一次）
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
            if (fen < 0 && billFen.containsKey(s.id)) fen = billFen.get(s.id).intValue();
            if (fen < 0 && billFree.contains(s.id)) {
                fen = 0;
                free = true;
            }
            if (fen < 0 && statFen.containsKey(s.id)) fen = statFen.get(s.id).intValue();
            if (fen < 0 && statFree.contains(s.id)) {
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
            items.add(b);
        }
    }

    /**
     * 摘录（D14：摘录皮肤第三行）—— 手填摘录优先，其次**本地已同步**的最新原文划线 / 想法。
     * 🔴 零请求、无摘录返回 null（⇒ 不占行）。
     */
    private String excerpt(Context c, String bookId) {
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
        toggleIdx = -1;
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
                if (excerpt) b.note = excerpt(ctx, b.id);
                addItem(rows, b, i + 1, excerpt);
            }
            if (collapsible) {
                if (!open) {
                    // D13：省略号行（「… 还有 N 本」）
                    BillSection.Row e = BillSection.Row.line("… 还有 " + (n - shown) + " 本", null, SZ_SMALL);
                    e.color = GRAY;
                    rows.add(e);
                }
                BillSection.Row t = BillSection.Row.line(open ? COLLAPSE : EXPAND, null, SZ_SMALL);
                t.color = GRAY;
                toggleIdx = rows.size();
                rows.add(t);
            }
            addTotal(rows);
        }
        laidW = w;
        laidUnit = unit;
        laidSort = sort;
        laidOpen = open;
        laidView = view;
        laidStamp = stamp;
    }

    /** 一条书目（摘录皮肤 = 主串 + meta + 摘录；读书皮肤 = 折行书名 + meta，精简与周/月一致）。 */
    private void addItem(List<BillSection.Row> out, Book b, int no, boolean excerpt) {
        // 三态（🆕 m01985）：有价 ⇒ 金额；**明确免费** ⇒ 「免费」；**未知** ⇒ `—`
        final String income = b.incomeKnown ? money(b.income) : "—";
        final String price = b.priced ? money(b.fen) : (b.free ? "免费" : "—");
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
        int priceSum = 0, incomeSum = 0, secSum = 0, knownN = 0, unkPriceN = 0, unkProgN = 0;
        for (int i = 0; i < items.size(); i++) {
            Book b = items.get(i);
            secSum += b.sec;
            if (b.priced) {
                priceSum += b.fen;
                knownN++;                                   // 有价 ⇒ 计入原价合计
                if (!b.incomeKnown) unkProgN++;              // 有价但进度未取到 ⇒ 收入 `—`（🆕 m01985）
            } else if (b.free) {
                knownN++;                                   // 明确免费 ⇒ 价格已定（0），不影响金额
            } else {
                unkPriceN++;                                // 原价未知 ⇒ 原价与收入都 `—`
            }
            if (b.incomeKnown) incomeSum += b.income;
        }
        out.add(BillSection.Row.dsep());
        BillSection.Row r = BillSection.Row.line("合计 " + items.size() + " 本 · " + dur(secSum),
                money(incomeSum), SZ_BODY);
        r.c3 = money(priceSum);
        out.add(r);
        // 🆕 三计数（m01985）：`已计价` = **价格已定**（有价 + 明确免费）本数 ⇒ 与 `原价未知` 之和 = 全部本数
        String note = "已计价 " + knownN + " 本 · " + unkPriceN + " 本原价未知 · " + unkProgN + " 本进度未取到";
        BillSection.Row n = BillSection.Row.line(note, null, SZ_SMALL);
        n.color = GRAY;
        out.add(n);
    }

    private Comparator<Book> bySort() {
        return new Comparator<Book>() {
            @Override
            public int compare(Book x, Book y) {
                int c;
                if (sort == SORT_PRICE) {
                    // 原价未知（`—`）永远排在最后（🆕 m01985）
                    if (x.priceKnown != y.priceKnown) return x.priceKnown ? -1 : 1;
                    c = y.fen - x.fen;
                } else if (sort == SORT_INCOME) {
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
        hp.setFakeBoldText(false);
        hp.setTextAlign(Paint.Align.LEFT);
    }

    /** 三键矩形（右对齐排在标题行右端）—— 画与命中同一份几何。 */
    private void sortRects(float w, float unit, float top) {
        if (items.isEmpty()) {
            for (int i = 0; i < chipRects.length; i++) chipRects[i].setEmpty();
            return;
        }
        final float right = w - w * InsightRenderer.PAD_X_RATIO;
        final float cy = top + InsightRenderer.secHeadH(unit) * 0.5f;
        final float pad = unit * 6f;
        final float gap = unit * 8f;
        final float half = unit * SZ_SMALL * 0.95f;
        hp.setTextSize(SZ_SMALL * unit);
        hp.setTypeface(null);
        final float[] ws = new float[SORT_LABELS.length];
        float total = gap * (SORT_LABELS.length - 1);
        for (int i = 0; i < SORT_LABELS.length; i++) {
            ws[i] = hp.measureText(SORT_LABELS[i]) + pad * 2f;
            total += ws[i];
        }
        float x = right - total;
        for (int i = 0; i < SORT_LABELS.length; i++) {
            chipRects[i].set(x, cy - half, x + ws[i], cy + half);
            x += ws[i] + gap;
        }
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
    private void buildLedgerIndex(Context c) {
        billFen.clear();
        billFree.clear();
        statFen.clear();
        statFree.clear();
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
                            if (!billFen.containsKey(it.bookId)) {
                                billFen.put(it.bookId, Integer.valueOf(it.marketPriceFen));
                            }
                        } else if (it.marketPriceFen == 0) {
                            billFree.add(it.bookId);
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
                        if (!statFen.containsKey(lg.bookId)) statFen.put(lg.bookId, Integer.valueOf(f));
                    } else if (f == 0) {
                        statFree.add(lg.bookId);
                    }
                }
            }
        }
    }

    // ══════════════════════ 小工具 ══════════════════════

    /** 时长文案（照墨单口径：`3h39m` / `219m`）。 */
    private static String dur(int sec) {
        if (sec < 3600) return Math.max(1, sec / 60) + "m";
        final int h = sec / 3600;
        final int m = (sec % 3600) / 60;
        return (m > 0) ? (h + "h" + m + "m") : (h + "h");
    }

    /** 金额文案（走墨单同一口径：`¥39` / `¥12.50`；`¥0` = 免费收入）。 */
    private static String money(int fen) { return BillMoney.yuan(fen); }

    private static String nz(String s) { return (s == null) ? "" : s; }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return (s.length() == 0) ? null : s;
    }
}
