package com.inkread.weekread.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 🆕 TASK-077：**一期阅读账单**（已完结周期的快照）。
 *
 * <p>口径（`tasks/TASK-077` §关键规则；🔴 **价格 / 合计口径已于 `TASK-086` 全面改写**）：
 * <ul>
 *   <li>🔴 <b>价</b> = **书籍市场价**（{@link Item#marketPriceFen}，单位**分**；生成期解析、渲染只读
 *       ⇒ {@link BillMoney}）。<b>旧口径</b>「`clamp(⌈分钟÷10⌉)` 虚构价」**已作废**（`priceOf` 已删）；</li>
 *   <li>🔴 <b>实付</b> = 书价 × 进度（两算法，见 {@link BillMoney#paidFen}）；</li>
 *   <li>🔴 <b>合计行</b> = `合计   本期阅读 H 小时` + 次行 `读回 ¥X，折合 ¥Y/小时`
 *       （{@code BillSection#billTotalMain} / {@code #billTotalNote}；两行是上机倒逼，见该方法注释）；</li>
 *   <li>周期起止**必须**由 {@link PeriodRange} 算（周 = 周一 00:00、月 = 1 日 00:00）⇒ {@link #serialOf}/{@link #rangeLabel} 同源。</li>
 * </ul>
 *
 * <p>🔴 **摘录在生成期预热、渲染期只读**：{@link Item#mineText}（个人划线/想法）与
 * {@link Item#hotText}（微信公开热门划线）都在 {@link BillScheduler} 里抓好落盘，
 * 渲染函数据 {@code MenuPrefs.excerptMode} 二者择一/合并 —— **渲染永不发请求**。
 *
 * <p>🔴 **手动备注不在这里**：按 `bookId` 长期存在 `MenuPrefs`（`menu_notes` 段）⇒ 改 Top-N 顺序不会错位。
 */
public final class Bill {

    /** 周期类型：{@link PeriodRange#WEEKLY} / {@link PeriodRange#MONTHLY}。 */
    public String mode = PeriodRange.WEEKLY;
    /** 周期起点（秒）—— 与既有周卡/月卡**同源**。 */
    public long periodStart;
    /** 生成时刻（毫秒）。 */
    public long generatedAt;

    /**
     * 是否**占位账**（Q10）：本期确无书目 ⇒ 写"本期无阅读记录"，🔴 **绝不产 0 值空白账单**。
     * 占位账**只有** {@code mode/periodStart/generatedAt/placeholder=true}，{@link #items} 为空。
     */
    public boolean placeholder;

    /** 本期时长合计（秒）—— 来自 {@code PeriodStats.totalSec}。 */
    public int totalSec;
    /**
     * 逐日秒数（**0 基**：月 = 1 日 ⇒ index 0；周 = 周一 ⇒ index 0）。固定 31 格，多余恒 0。
     *
     * <p>🆕 `TASK-077b` 月历视图用（数据来自 {@code PeriodStats.daySec}，**零额外请求**）。
     * 🔴 **老账无此字段 ⇒ 全 0** ⇒ 月历画空网格，不崩。
     */
    public int[] daySec = new int[31];
    /** 本周期天数（月 28–31 / 周 7）—— 月历网格据此收边。 */
    public int dayCount = 7;
    /** 书目（已按 Top N + 最小时长阈值裁剪，按时长降序）。 */
    public List<Item> items = new ArrayList<Item>();

    /** 书单里的一行（= 菜单的一个「菜」）。 */
    public static final class Item {
        public String bookId = "";
        public String title = "";
        public String author = "";
        /** 本期该书阅读时长（秒）。 */
        public int readTimeSec;
        /** 个人摘录（该书本期一条划线/想法原文；无 ⇒ null）。 */
        public String mineText;
        /**
         * 该书**本地已同步**的摘录条数（划线 + 想法）—— 渲染侧据它标「基于本地已同步 N 条」
         * （`tasks/TASK-077` §关键约束 6 / Q2）。0 = 本地没有任何该书内容。
         */
        public int mineCount;
        /** 微信公开热门划线（后台预热；无 / 未取 ⇒ null）。🔴 不等同本人划线 ⇒ 渲染须标来源。 */
        public String hotText;
        /** 阅读进度百分比（0~100；-1 = 未知）。 = **期末/当前**进度（实付的两算法都用它）。 */
        public int progressPct = -1;

        // ── 🆕 TASK-086：市场价 + 期初进度（实付两算法的数据基础） ──

        /**
         * **市场价**（分）——生成期由 {@link BillScheduler} 解析好落盘，渲染期只读。
         *
         * <p>三态（🔴 绝不用 0 冒充"未知"，否则合计被 0 拉低 —— huibenlema 的 `resetWereadZeroPrice` 教训）：
         * <ul>
         *   <li>{@code > 0} 有价；</li>
         *   <li>{@link BillMoney#FREE}（0）明确免费；</li>
         *   <li>{@link BillMoney#UNKNOWN}（-1）未定价（自导入 / 网文 / 接口没给）⇒ 显示 `—`。</li>
         * </ul>
         * 🔴 **用户手填价优先级更高**（{@link MenuPrice}）—— 由 {@link BillMoney#effFen} 合成，
         * 所以本字段只存"接口给的价"。
         */
        public int marketPriceFen = BillMoney.UNKNOWN;

        /**
         * **期初进度**（= 上一期结束时的进度，0~100；-1 = 无基线）。
         * 🆕 由 {@link ProgressLog} 的期界快照提供；首期 / 补旧期 ⇒ -1 ⇒ 算法 A 该行显示 `—`。
         */
        public int progressStartPct = -1;

        /**
         * 「期末进度」是否**可信**（= 生成时这一期才刚结束）。
         * 🔴 三周不开 App 后一次补 4 期时，更早那几期抓到的其实是"现在"的进度
         * ⇒ 置 false ⇒ 算法 A 对这些期显示 `—`（宁可留白，不编假数）。
         */
        public boolean progressEndKnown = false;

        /**
         * 🆕 **该期期末的累计进度**（0~100；{@code -1} = 取不到）。
         *
         * <p>由 {@link BillScheduler} 在**生成期**按"真实性优先"的取值链解析好落盘，渲染期只读
         * （渲染零请求）。**累计档（算法 B）用它**，于是每个月显示的是**所选周期末**的累计，
         * 而不是"无论看哪一期都显示当前累计"（用户 2026-10-11 报的问题，见 `验证记录/207`）。
         *
         * <p>取值链（宁可留白，不编数）：
         * <ol>
         *   <li>{@link ProgressLog} 里**本期**的期界快照（该期期末的真实记录）—— 最可信；</li>
         *   <li>本期就是"刚结束的那一期"（{@link #progressEndKnown}）⇒ {@link #progressPct} 即期末值；</li>
         *   <li>本期是**当前进行中**的期 ⇒ {@link #progressPct}（语义是"至今"，非严格期末）；</li>
         *   <li>都取不到 ⇒ {@code -1} ⇒ 累计档显示 `—`。</li>
         * </ol>
         *
         * <p>🔴 与 {@link #progressPct} 的分工：后者语义是"期末/**当前**"，补旧期时会写进"现在"的值
         * （各期显示同一个数）；本字段只存**该期自己的**值。增额档（算法 A）不用本字段。
         *
         * <p>序列化照 {@link #progressStartPct}：{@code >= 0} 才写、缺失读回 {@code -1}
         * ⇒ 旧账（无此键）读出来是 {@code -1}，由 {@link BillMoney#paidFen} 的回落分支接管。
         */
        public int progressEndPct = -1;
    }

    // ══════════════════════ 口径 ══════════════════════

    public int bookCount() {
        return items.size();
    }

    // ══════════════════════ 单号 / 周期文案 ══════════════════════

    /**
     * 单号：周 = `2026-W40`（**ISO 周**：周一起、首周含 ≥4 天）；月 = `2026-09`。
     * 与 {@link PeriodRange#startOf} 同源 ⇒ 不会出现"单号与本周期不符"。
     */
    public static String serialOf(String mode, long periodStart) {
        Calendar c = Calendar.getInstance();
        if (PeriodRange.MONTHLY.equals(mode)) {
            c.setTimeInMillis(periodStart * 1000L);
            return String.format(java.util.Locale.US, "%04d-%02d",
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
        }
        // ISO 周：取该周的**周四**定周号与 ISO 年（周一 + 3 天）
        c.setTimeInMillis(periodStart * 1000L + 3L * 86400000L);
        c.setFirstDayOfWeek(Calendar.MONDAY);
        c.setMinimalDaysInFirstWeek(4);
        int week = c.get(Calendar.WEEK_OF_YEAR);
        return String.format(java.util.Locale.US, "%04d-W%02d", c.get(Calendar.YEAR), week);
    }

    /** 副行里的紧凑周期：周 = `10.05–10.11`（跨月/跨年补月份）；月 = `09.01–09.30`。 */
    public static String rangeLabel(String mode, long periodStart) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        a.setTimeInMillis(periodStart * 1000L);
        int days = PeriodRange.daysInPeriod(mode, periodStart);
        b.setTimeInMillis(a.getTimeInMillis() + (long) (days - 1) * 86400000L);
        boolean sameMonth = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.MONTH) == b.get(Calendar.MONTH);
        if (PeriodRange.MONTHLY.equals(mode)) {
            return String.format(java.util.Locale.US, "%02d.01–%02d.%02d",
                    a.get(Calendar.MONTH) + 1, b.get(Calendar.MONTH) + 1, b.get(Calendar.DAY_OF_MONTH));
        }
        if (sameMonth) {
            return String.format(java.util.Locale.US, "%02d.%02d–%02d.%02d",
                    a.get(Calendar.MONTH) + 1, a.get(Calendar.DAY_OF_MONTH),
                    b.get(Calendar.MONTH) + 1, b.get(Calendar.DAY_OF_MONTH));
        }
        return String.format(java.util.Locale.US, "%02d.%02d–%02d.%02d",
                a.get(Calendar.MONTH) + 1, a.get(Calendar.DAY_OF_MONTH),
                b.get(Calendar.MONTH) + 1, b.get(Calendar.DAY_OF_MONTH));
    }

    /** 副行前缀：周 = `周结`、月 = `月结`。 */
    public static String kindLabel(String mode) {
        return PeriodRange.MONTHLY.equals(mode) ? "月结" : "周结";
    }

    // ══════════════════════ 序列化（落 prefs 的就是它） ══════════════════════

    /**
     * 序列化为 JSON（落 `SharedPreferences` 的就是它）。
     *
     * <p>🔴 **条件写**：`progressPct` / `marketPriceFen` / `progressStartPct` / `progressEndPct`
     * / `progressEndKnown` 只有"本对象里真的有值"时才写进 JSON（缺省不写 ⇒ 老账/无值行回落"未知"，
     * 体积极小）。
     * 配合 {@link BillStore#save} 的**整体覆盖**语义，这条约定意味着：
     * <b>调用方必须在落盘前把"本次没取到"的字段从旧账继承回来</b>（`-1` 在 `fromJson` 里
     * 与"没这个字段"等价，缺字段就会被读成缺省）—— 实现在
     * `BillScheduler.generateOne(...)` 尾部"旧账字段继承"块（task-11(a)）。</p>
     */
    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("mode", mode);
            o.put("periodStart", periodStart);
            o.put("generatedAt", generatedAt);
            o.put("placeholder", placeholder);
            o.put("totalSec", totalSec);
            o.put("dayCount", dayCount);
            JSONArray ds = new JSONArray();
            for (int i = 0; i < dayCount && i < daySec.length; i++) ds.put(daySec[i]);
            o.put("daySec", ds);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                JSONObject j = new JSONObject();
                j.put("bookId", it.bookId == null ? "" : it.bookId);
                j.put("title", it.title == null ? "" : it.title);
                j.put("author", it.author == null ? "" : it.author);
                j.put("readTimeSec", it.readTimeSec);
                if (it.mineText != null) j.put("mineText", it.mineText);
                if (it.mineCount > 0) j.put("mineCount", it.mineCount);
                if (it.hotText != null) j.put("hotText", it.hotText);
                if (it.progressPct >= 0) j.put("progressPct", it.progressPct);
                // 🆕 TASK-086（缺省不写 ⇒ 老账/本例外的行自然回落"未知"，体积极小）
                if (it.marketPriceFen != BillMoney.UNKNOWN) j.put("marketPriceFen", it.marketPriceFen);
                if (it.progressStartPct >= 0) j.put("progressStartPct", it.progressStartPct);
                // 🆕 task-13：**该期期末累计**（累计档用它；缺省不写 ⇒ 老账读回 -1，由 paidFen 回落）
                if (it.progressEndPct >= 0) j.put("progressEndPct", it.progressEndPct);
                if (it.progressEndKnown) j.put("progressEndKnown", true);
                arr.put(j);
            }
            o.put("items", arr);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /**
     * 反序列化。🔴 **任何异常都返回 null**（不返回半成品）—— 坏数据一律当"没有这一期"，
     * 由 `BillScheduler` 下次重新生成，绝不让坏账渲染出去。
     */
    public static Bill fromJson(String raw, String mode, long periodStart) {
        if (raw == null || raw.length() == 0) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Bill b = new Bill();
            b.mode = PeriodRange.MONTHLY.equals(mode) ? PeriodRange.MONTHLY : PeriodRange.WEEKLY;
            b.periodStart = periodStart;
            b.generatedAt = o.optLong("generatedAt", 0L);
            b.placeholder = o.optBoolean("placeholder", false);
            b.totalSec = o.optInt("totalSec", 0);
            b.dayCount = o.optInt("dayCount", PeriodRange.daysInPeriod(b.mode, periodStart));
            if (b.dayCount <= 0) b.dayCount = 7;
            JSONArray ds = o.optJSONArray("daySec");
            if (ds != null) {
                for (int i = 0; i < ds.length() && i < b.daySec.length; i++) {
                    b.daySec[i] = ds.optInt(i, 0);
                }
            }
            JSONArray arr = o.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.optJSONObject(i);
                    if (j == null) continue;
                    Item it = new Item();
                    it.bookId = j.optString("bookId", "");
                    it.title = j.optString("title", "");
                    it.author = j.optString("author", "");
                    it.readTimeSec = j.optInt("readTimeSec", 0);
                    it.mineText = j.has("mineText") ? j.optString("mineText", "") : null;
                    it.mineCount = j.optInt("mineCount", 0);
                    it.hotText = j.has("hotText") ? j.optString("hotText", "") : null;
                    it.progressPct = j.has("progressPct") ? j.optInt("progressPct", -1) : -1;
                    // 🆕 TASK-086：缺字段 ⇒ 未知/无基线（**老账一律这样**，A13 老账兼容）
                    it.marketPriceFen = j.has("marketPriceFen")
                            ? j.optInt("marketPriceFen", BillMoney.UNKNOWN) : BillMoney.UNKNOWN;
                    it.progressStartPct = j.has("progressStartPct")
                            ? j.optInt("progressStartPct", -1) : -1;
                    // 🆕 task-13：无此键（老账）⇒ -1 ⇒ `BillMoney.paidFen` 的累计档走回落分支
                    it.progressEndPct = j.has("progressEndPct")
                            ? j.optInt("progressEndPct", -1) : -1;
                    it.progressEndKnown = j.optBoolean("progressEndKnown", false);
                    b.items.add(it);
                }
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }
}
