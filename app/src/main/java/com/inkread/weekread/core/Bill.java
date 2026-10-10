package com.inkread.weekread.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 🆕 TASK-077：**一期阅读账单**（已完结周期的快照）。
 *
 * <p>口径（`tasks/TASK-077` §关键规则）：
 * <ul>
 *   <li>🔴 **价** = {@code clamp(⌈阅读分钟 ÷ 10⌉, 1, 999)} ⇒ {@link #priceOf(int)}（传**秒**）；</li>
 *   <li>🔴 **账单合计** = **各书价之和**（<b>不是</b>总时长折算）⇒ {@link #totalPrice()}；</li>
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
        /**
         * 价（¥）—— **生成期**按 {@link #priceOf(int)} 算好落盘。
         * 🔴 是**造出来的**（分钟÷10）⇒ 文案须像"菜单标价"，不得暗示真收费（风险 R5）。
         */
        public int price;
        /** 个人摘录（该书本期一条划线/想法原文；无 ⇒ null）。 */
        public String mineText;
        /**
         * 该书**本地已同步**的摘录条数（划线 + 想法）—— 渲染侧据它标「基于本地已同步 N 条」
         * （`tasks/TASK-077` §关键约束 6 / Q2）。0 = 本地没有任何该书内容。
         */
        public int mineCount;
        /** 微信公开热门划线（后台预热；无 / 未取 ⇒ null）。🔴 不等同本人划线 ⇒ 渲染须标来源。 */
        public String hotText;
        /** 阅读进度百分比（0~100；-1 = 未知）。 */
        public int progressPct = -1;
    }

    // ══════════════════════ 口径 ══════════════════════

    /**
     * 价 = {@code clamp(⌈阅读分钟 ÷ 10⌉, 1, 999)}。
     *
     * @param readTimeSec 该书本期阅读时长（秒）
     */
    public static int priceOf(int readTimeSec) {
        int min = (readTimeSec + 59) / 60;          // 向上取整到分钟
        int p = (min + 9) / 10;                     // ⌈分钟 / 10⌉
        if (p < 1) p = 1;
        if (p > 999) p = 999;
        return p;
    }

    /** 账单合计 = **各书价之和**（🔴 不是总时长折算）。 */
    public int totalPrice() {
        int sum = 0;
        for (int i = 0; i < items.size(); i++) sum += items.get(i).price;
        return sum;
    }

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
                j.put("price", it.price);
                if (it.mineText != null) j.put("mineText", it.mineText);
                if (it.mineCount > 0) j.put("mineCount", it.mineCount);
                if (it.hotText != null) j.put("hotText", it.hotText);
                if (it.progressPct >= 0) j.put("progressPct", it.progressPct);
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
                    it.price = j.optInt("price", 0);
                    it.mineText = j.has("mineText") ? j.optString("mineText", "") : null;
                    it.mineCount = j.optInt("mineCount", 0);
                    it.hotText = j.has("hotText") ? j.optString("hotText", "") : null;
                    it.progressPct = j.has("progressPct") ? j.optInt("progressPct", -1) : -1;
                    b.items.add(it);
                }
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }
}
