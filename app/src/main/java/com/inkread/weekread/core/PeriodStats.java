package com.inkread.weekread.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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

    public boolean isMonthly() {
        return PeriodRange.MONTHLY.equals(mode);
    }

    /**
     * @param mode    请求用的周期
     * @param reqBase 请求用的 baseTime（0=当前周期）—— 回包里若没带 baseTime，用它兜底
     */
    public static PeriodStats parse(JSONObject j, long fetchedAt, String mode, long reqBase) {
        PeriodStats s = new PeriodStats();
        s.mode = PeriodRange.MONTHLY.equals(mode) ? PeriodRange.MONTHLY : PeriodRange.WEEKLY;
        s.fetchedAt = fetchedAt;
        if (j == null) {
            s.baseTime = PeriodRange.startOf(s.mode, reqBase);
            s.dayCount = PeriodRange.daysInPeriod(s.mode, s.baseTime);
            return s;
        }
        s.baseTime = j.optLong("baseTime", 0);
        if (s.baseTime <= 0) s.baseTime = PeriodRange.startOf(s.mode, reqBase);
        s.dayCount = PeriodRange.daysInPeriod(s.mode, s.baseTime);

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

        JSONArray longest = j.optJSONArray("readLongest");
        if (longest != null && longest.length() > 0) {
            JSONObject first = longest.optJSONObject(0);
            if (first != null) {
                JSONObject book = first.optJSONObject("book");
                if (book != null) s.topBook = book.optString("title", null);
                s.topBookSec = first.optInt("readTime", 0);
            }
        }
        return s;
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
