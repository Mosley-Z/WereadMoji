package com.inkread.weekread.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 一个统计周期（**周或月**）的阅读数据 —— `/readdata/detail` 的解析结果。
 *
 * v0.3.2 之前这个类叫 `WeekStats`，只认 7 天。本月功能要复用同一套渲染，
 * 所以泛化成"周期"：多出 {@link #mode}、{@link #dayCount}，
 * {@link #daySec} 固定 31 格（用不到的格子恒为 0）。
 *
 * 接口只改了两个入参（`mode` / `baseTime`），**回包结构完全一样**：
 * · `weekly`  按天分桶 7 个 key；`monthly` 按天分桶 28–31 个 key；
 * · key 是该天 00:00 的时间戳（秒），**没有阅读记录的那天服务端不返回** → 必须补 0。
 */
public class PeriodStats {

    /** weekly / monthly */
    public String mode = PeriodRange.WEEKLY;
    /** 周期起点（周=周一 00:00；月=月初 00:00），秒 */
    public long baseTime;
    /** 拉取时间（毫秒），用于「更新于 HH:MM」 */
    public long fetchedAt;
    public int totalSec;
    public int readDays;
    /** 自然日均秒数（`dayAverageReadTime`） */
    public int avgSec;
    /** 与上一周期日均比，null = 无数据 */
    public Double compare;
    /** 本周期天数：周 7，月 28–31 */
    public int dayCount = 7;
    /** 逐日秒数，0 基（周：0=周一；月：0=1 日）。固定 31 格，多余位置恒 0 */
    public int[] daySec = new int[31];
    public String rawJson;
    public String topBook;
    public int topBookSec;

    // ═════════════════ TASK-046 统计解析扩容：吐「全量原始料」 ═════════════════
    // 本块字段只做「原样吐」，**不替消费方做任何过滤/解读**（过滤与口径留在各界面卡里）。
    // 老缓存（无这些字段）⇒ 一律空集合/全 0，不崩。

    /** 全量 readLongest（**不再只取 [0]**）。{@link #topBook}/{@link #topBookSec} 仍由 [0] 派生，二者并存。 */
    public List<Longest> longest = new ArrayList<Longest>();
    /** 分类偏好（preferCategory）。🔴 **原样吐、不过滤** —— `readingTime<=0` 的项也在内，过滤交给消费方（K4）。 */
    public List<PreferCat> preferCategory = new ArrayList<PreferCat>();
    /** 作者偏好（preferAuthor）—— ⚠️ **仅 annually/overall 回包有**，周/月为空集合。 */
    public List<PreferCat> preferAuthor = new ArrayList<PreferCat>();
    /** 四段摘要（readStat：读过 / 读完 / 阅读 / 笔记）。⚠️ `counts` 是**带单位的字符串**（如 "8本" / "21天"）。 */
    public List<StatItem> readStat = new ArrayList<StatItem>();
    /** 24 时段偏好（preferTime）。🔴 **回包从 6 点起排序**（6→次日 5），语义解读留给消费方（K5）。 */
    public int[] preferTime = new int[24];
    /** 勋章名（medals[].name）。用户未勾 ⑪ ⇒ 只用于**计数**（K7 不做勋章墙）。 */
    public List<String> medals = new ArrayList<String>();
    /** 注册时间（**毫秒**）。回包是秒 ⇒ 解析时已 ×1000；0 = 回包未带。 */
    public long registTimeMs;
    /** 12 桶按月（**仅 annually 有意义**：该年各月秒数）。其它 mode 恒全 0。 */
    public int[] monthSec = new int[12];

    /**
     * 🔴 B6：回包结构上**不像统计数据**（四个统计字段 `totalReadTime` / `readDays` /
     * `readTimes` / `dayAverageReadTime` **全缺**）。用于把"2xx 但内容不对"的异常响应用
     * 失败处理 —— **不写回缓存、不渲染成 0 分钟**。
     * 注意：合法的"这周没读书"会带 `totalReadTime: 0`，字段是**在**的 ⇒ 不会被误判。
     */
    public boolean incomplete;

    public boolean isMonthly() {
        return PeriodRange.MONTHLY.equals(mode);
    }

    /**
     * @param mode    请求用的周期
     * @param reqBase 请求用的 baseTime（0=当前周期）—— 回包里若没带 baseTime，用它兜底
     */
    public static PeriodStats parse(JSONObject j, long fetchedAt, String mode, long reqBase) {
        PeriodStats s = new PeriodStats();
        // 🔴 TASK-046：weekly/monthly 归一化不变；annually/overall **原样保留**（缓存键要按它分段），未知一律回 weekly。
        s.mode = normalizeMode(mode);
        s.fetchedAt = fetchedAt;
        if (j == null) {
            s.baseTime = PeriodRange.startOf(s.mode, reqBase);
            s.dayCount = PeriodRange.daysInPeriod(s.mode, s.baseTime);
            return s;
        }
        s.baseTime = j.optLong("baseTime", 0);
        if (s.baseTime <= 0) s.baseTime = PeriodRange.startOf(s.mode, reqBase);
        s.dayCount = PeriodRange.daysInPeriod(s.mode, s.baseTime);

        // 🔴 B6：结构校验 —— 四个统计字段**全缺** ⇒ 这不是一份统计回包（疑似中间页/异常响应）
        s.incomplete = !(j.has("totalReadTime") || j.has("readDays")
                || j.has("readTimes") || j.has("dayAverageReadTime"));

        s.totalSec = j.optInt("totalReadTime", 0);
        s.readDays = j.optInt("readDays", 0);
        s.avgSec = j.optInt("dayAverageReadTime", 0);
        if (j.has("compare")) {
            try {
                s.compare = j.getDouble("compare");
            } catch (Exception ignored) {
            }
        }

        JSONObject rt = j.optJSONObject("readTimes");
        if (rt != null) {
            List<Long> keys = new ArrayList<Long>();
            Iterator<String> it = rt.keys();
            while (it.hasNext()) {
                try {
                    keys.add(Long.parseLong(it.next()));
                } catch (Exception ignored) {
                }
            }
            Collections.sort(keys);
            for (Long k : keys) {
                int idx = (int) ((k - s.baseTime) / 86400L);
                if (idx >= 0 && idx < s.dayCount) {
                    s.daySec[idx] = rt.optInt(String.valueOf(k), 0);
                }
            }
        }

        // ── 原有派生逻辑（[0] 一本书）—— 🔴 一行未改，保证 topBook/topBookSec 逐字节兼容 ──
        JSONArray longest = j.optJSONArray("readLongest");
        if (longest != null && longest.length() > 0) {
            JSONObject first = longest.optJSONObject(0);
            if (first != null) {
                JSONObject book = first.optJSONObject("book");
                if (book != null) s.topBook = book.optString("title", null);
                s.topBookSec = first.optInt("readTime", 0);
            }
        }

        // ── TASK-046：全量 readLongest（与上面的 [0] 并存）──
        if (longest != null) {
            for (int i = 0; i < longest.length(); i++) {
                JSONObject o = longest.optJSONObject(i);
                if (o == null) continue;
                Longest l = new Longest();
                JSONObject book = o.optJSONObject("book");
                if (book != null) {
                    l.bookId = book.optString("bookId", null);
                    l.title = book.optString("title", null);
                    l.author = book.optString("author", null);
                    l.cover = book.optString("cover", null);
                    l.deepLink = book.optString("deepLink", null);
                    // 🆕 TASK-086：书价就在**已经在拿**的回包里（`book.centPrice`，单位**分**）——
                    // 此前只取 bookId/title/readTime 就把它丢了。🔴 不新增接口、不新增解析成本。
                    l.centPrice = book.optInt("centPrice", 0);
                    l.priceYuan = book.optDouble("price", 0.0);
                    l.origYuan = book.optInt("originalPrice", 0);
                    l.free = book.optInt("free", 0) == 1;
                }
                l.readTime = o.optInt("readTime", 0);
                JSONArray tags = o.optJSONArray("tags");
                if (tags != null) {
                    for (int t = 0; t < tags.length(); t++) {
                        String tg = tags.optString(t, null);
                        if (tg != null) l.tags.add(tg);
                    }
                }
                s.longest.add(l);
            }
        }

        // ── TASK-046：偏好分类 / 作者（**原样吐，不过滤**）──
        s.preferCategory = readPreferList(j.optJSONArray("preferCategory"), true);
        s.preferAuthor = readPreferList(j.optJSONArray("preferAuthor"), false);

        // ── TASK-046：四段摘要 readStat（counts 带单位，原样存字符串）──
        JSONArray stat = j.optJSONArray("readStat");
        if (stat != null) {
            for (int i = 0; i < stat.length(); i++) {
                JSONObject o = stat.optJSONObject(i);
                if (o == null) continue;
                StatItem it = new StatItem();
                it.stat = o.optString("stat", null);
                it.counts = o.optString("counts", null);
                s.readStat.add(it);
            }
        }

        // ── TASK-046：24 时段 preferTime（🔴 原序：从 6 点起）──
        JSONArray pt = j.optJSONArray("preferTime");
        if (pt != null) {
            for (int i = 0; i < pt.length() && i < s.preferTime.length; i++) {
                s.preferTime[i] = pt.optInt(i, 0);
            }
        }

        // ── TASK-046：勋章（只收名字，供计数）──
        JSONArray med = j.optJSONArray("medals");
        if (med != null) {
            for (int i = 0; i < med.length(); i++) {
                JSONObject o = med.optJSONObject(i);
                if (o == null) continue;
                String nm = o.optString("name", null);
                if (nm != null) s.medals.add(nm);
            }
        }

        // ── TASK-046：注册时间（秒 → 毫秒，单位统一）──
        long reg = j.optLong("registTime", 0);
        if (reg > 0) s.registTimeMs = reg < 100000000000L ? reg * 1000L : reg;

        // ── TASK-046：12 桶按月（仅 annually；键 = 各月起点秒）──
        if (PeriodRange.ANNUALLY.equals(s.mode) && rt != null) {
            Iterator<String> mk = rt.keys();
            while (mk.hasNext()) {
                String k = mk.next();
                long ks;
                try {
                    ks = Long.parseLong(k);
                } catch (Exception ignored) {
                    continue;
                }
                int mi = monthIndex(s.baseTime, ks);
                if (mi >= 0 && mi < s.monthSec.length) s.monthSec[mi] = rt.optInt(k, 0);
            }
        }
        return s;
    }

    /** weekly/monthly 归一化；annually/overall 原样；其它一律 weekly（老行为不变）。 */
    private static String normalizeMode(String m) {
        if (PeriodRange.MONTHLY.equals(m)) return PeriodRange.MONTHLY;
        if (PeriodRange.ANNUALLY.equals(m)) return PeriodRange.ANNUALLY;
        if (PeriodRange.OVERALL.equals(m)) return PeriodRange.OVERALL;
        return PeriodRange.WEEKLY;
    }

    /** 解析偏好数组；{@code category=true} 走分类字段、否则走作者字段。 */
    private static List<PreferCat> readPreferList(JSONArray arr, boolean category) {
        List<PreferCat> out = new ArrayList<PreferCat>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            PreferCat p = new PreferCat();
            if (category) {
                p.id = o.optString("categoryId", null);
                p.name = o.optString("categoryTitle", null);
                p.parent = o.optString("parentCategoryTitle", null);
                p.count = o.optInt("readingCount", 0);
                p.readTimeSec = o.optInt("readingTime", 0);
            } else {
                p.id = o.optString("authorId", null);
                p.name = o.optString("name", null);
                p.count = o.optInt("count", 0);
                p.readTimeText = o.optString("readTime", null);   // 作者回包是 "48小时20分钟"
            }
            out.add(p);
        }
        return out;
    }

    /** 从 {@code baseSec} 到 {@code kSec} 相差几个自然月（annually 的 12 桶定位用）。 */
    private static int monthIndex(long baseSec, long kSec) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(baseSec * 1000L);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(kSec * 1000L);
        return (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12
                + (b.get(Calendar.MONTH) - a.get(Calendar.MONTH));
    }

    // ═════════════════ TASK-046：三个静态内类 ═════════════════

    /** readLongest[] 的一项：一本书 + 本周期在它上面的时长。 */
    public static class Longest {
        public String bookId;
        public String title;
        public String author;
        public String cover;
        public String deepLink;
        /** 秒 */
        public int readTime;
        /** 徽章（如「笔记最多」「单日阅读最久」；可能为空） */
        public List<String> tags = new ArrayList<String>();

        // ── 🆕 TASK-086：回包 `readLongest[].book` 里的定价字段（此前未解析、白丢） ──
        /** 定价（**分**）；0 = 未给 ⇒ 交由 `BillMoney.fenOf` 与其它两项一起判。 */
        public int centPrice;
        /** 售价（**元**，float）。 */
        public double priceYuan;
        /** 原价（**元**；实测常为 0 ⇒ 取 max 正是为了兼容）。 */
        public int origYuan;
        /** 免费标记（`free == 1`）。 */
        public boolean free;
    }

    /**
     * 偏好项 —— **分类与作者共用**。
     * · 分类：填 {@link #parent}（parentCategoryTitle）与 {@link #readTimeSec}（readingTime，秒）；
     * · 作者：填 {@link #readTimeText}（回包是 "48小时20分钟" 这种**文本**，无秒值）。
     */
    public static class PreferCat {
        public String id;
        public String name;
        /** parentCategoryTitle（仅分类用；作者为 null） */
        public String parent;
        /** readingCount / count */
        public int count;
        /** readingTime（仅分类用，秒） */
        public int readTimeSec;
        /** 作者的时长文本（仅作者用，如 "48小时20分钟"） */
        public String readTimeText;
    }

    /** readStat[] 的一项：`{"stat":"读过","counts":"8本"}`（counts 是**带单位的字符串**）。 */
    public static class StatItem {
        public String stat;
        public String counts;
    }

    /**
     * 这个周期是不是"当前周期"（本周 / 本月）。
     *
     * ⚠️ 为什么必须单独有这个判据，而不是拿 {@link #todayIndex()} 凑：
     * `todayIndex()` 为了"把整月画满"会把**已经整个过去的周期**钳到 `dayCount-1`，
     * 于是它和"今天正好是周期最后一天"**完全无法区分**。
     * 看历史周时如果不问这一句，周日就会被当成"今天"画成实心黑柱（第 8 轮实测踩到：
     * 9/14–9/20 那一周的周日被标黑了）。凡是"历史周期里不存在今天"的绘制，都先问这里。
     */
    public boolean isCurrentPeriod() {
        return baseTime > 0 && baseTime == PeriodRange.startOf(mode, 0);
    }

    /**
     * 今天在本周期内的第几天（0 基）。
     *
     * 三种情况都要能表达，日历网格全靠它区分"过去 / 今天 / 未来"：
     * · 周期正在进行 → 正常返回 0…dayCount-1；
     * · 周期**已经整个过去**（看历史月）→ 返回 dayCount-1（整月都要画出来）；
     * · 周期**完全在未来** → 返回 **-1**（整格留白，不要画出"已过去"的空框）。
     */
    public int todayIndex() {
        if (baseTime <= 0) return dayCount - 1;
        long diff = (PeriodRange.todayStartSec() - baseTime) / 86400L;
        if (diff < 0) return -1;
        if (diff >= dayCount) return dayCount - 1;
        return (int) diff;
    }

    /** 该日是否已过去（含今天） */
    public boolean isPast(int dayIndex) {
        int t = todayIndex();
        return t >= 0 && dayIndex <= t;
    }

    /**
     * 一行摘要，写进 card_debug.log。
     *
     * 为什么值得常驻：日历网格"画到第几天"完全由 `baseTime / dayCount / todayIdx` 决定，
     * 而这三个值都来自服务端回包 —— 一旦时区或归一化口径有变，**光看截图是看不出来的**
     * （会误以为是自己画错了）。出问题时把这行贴出来就能定位。
     */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("base=").append(PeriodRange.MONTHLY.equals(mode)
                ? PeriodRange.monthLabel(baseTime) : PeriodRange.weekLabel(baseTime))
          .append(" dayCount=").append(dayCount)
          .append(" todayIdx=").append(todayIndex())
          .append(" readDays=").append(readDays)
          .append(" avg=").append(avgSec)
          .append(" filled=[");
        for (int i = 0; i < dayCount && i < daySec.length; i++) {
            if (daySec[i] > 0) sb.append(i).append(',');
        }
        sb.append("]");
        if (compare != null) sb.append(" compare=").append(compare);
        return sb.toString();
    }
}
