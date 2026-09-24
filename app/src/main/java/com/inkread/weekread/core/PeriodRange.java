package com.inkread.weekread.core;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 统计周期的"日历算术" —— 全部集中在这里，别在别处手写月份天数表。
 *
 * 本项目的周期口径与微信读书 `/readdata/detail` **完全一致**：
 * · `weekly`  —— 自然周，**周一 00:00** 为起点（和服务端归一化口径一致）；
 * · `monthly` —— 自然月，**当月 1 日 00:00** 为起点。
 *
 * 一律用 {@link java.util.Calendar} 做加减（闰年 2 月、大小月都交给它），
 * 时间戳统一用**秒**（与接口 `baseTime` 同口径）。
 */
public final class PeriodRange {

    public static final String WEEKLY = "weekly";
    public static final String MONTHLY = "monthly";
    /**
     * 「本书」—— v0.3.4 起的第三个形态。
     *
     * ⚠️ 它不是"周期"：没有起止、不能步进，数据也不落在 {@link StatsStore} 的
     * 周期缓存里（走 {@link BookStore}）。放进来纯粹是为了让"形态"这个维度
     * 只有一处定义 —— 选项卡、卡片抬头、设置页的三选共用同一组常量。
     * 凡是"按周期算日历"的地方（{@link #startOf} / {@link #shift} 等）**不要**传它。
     */
    public static final String BOOK = "book";

    /**
     * 「本记」—— v0.4.0 起的第四个形态：随机回顾一条划线笔记。
     *
     * 和「本书」一样不是周期：没有起止、不能步进，数据走 {@link NoteStore}。
     * 唯一区别是它连"一本书"都不绑定 —— 每条内容可能来自 228 本笔记书里的任意一本。
     */
    public static final String NOTE = "note";

    private PeriodRange() {
    }

    /** 是不是「本书」形态 */
    public static boolean isBook(String m) {
        return BOOK.equals(m);
    }

    /** 是不是「本记」形态 */
    public static boolean isNote(String m) {
        return NOTE.equals(m);
    }

    private static Calendar newCal() {
        Calendar c = Calendar.getInstance();
        c.setFirstDayOfWeek(Calendar.MONDAY);     // 周一起算，别用 Locale 默认（可能是周日）
        return c;
    }

    private static Calendar cal(long sec) {
        Calendar c = newCal();
        if (sec > 0) c.setTimeInMillis(sec * 1000L);
        return c;
    }

    private static void zero(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static long sec(Calendar c) {
        return c.getTimeInMillis() / 1000L;
    }

    public static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    /** 今天 00:00 的时间戳（秒） */
    public static long todayStartSec() {
        Calendar c = newCal();
        zero(c);
        return sec(c);
    }

    /**
     * 某时刻所属周期的起点。
     *
     * @param mode weekly / monthly
     * @param s    任意时刻（秒）；≤0 表示"现在"
     */
    public static long startOf(String mode, long s) {
        Calendar c = cal(s > 0 ? s : nowSec());
        zero(c);
        if (MONTHLY.equals(mode)) {
            c.set(Calendar.DAY_OF_MONTH, 1);
        } else {
            // Calendar.DAY_OF_WEEK: 周日=1 … 周六=7；要往前退几天才到周一
            int dow = c.get(Calendar.DAY_OF_WEEK);
            int back = (dow == Calendar.SUNDAY) ? 6 : (dow - Calendar.MONDAY);
            c.add(Calendar.DAY_OF_MONTH, -back);
        }
        return sec(c);
    }

    /** 从某周期起点往前后挪 n 个周期（n 可为负） */
    public static long shift(String mode, long periodStart, int n) {
        if (periodStart <= 0) periodStart = startOf(mode, 0);
        Calendar c = cal(periodStart);
        if (MONTHLY.equals(mode)) {
            c.add(Calendar.MONTH, n);                 // 从 1 号加减，不会出现"31 号跳月"的坑
        } else {
            c.add(Calendar.DAY_OF_MONTH, 7 * n);
        }
        zero(c);
        return sec(c);
    }

    /** 周期天数：周恒为 7，月按实际（28/29/30/31） */
    public static int daysInPeriod(String mode, long periodStart) {
        if (!MONTHLY.equals(mode)) return 7;
        Calendar c = cal(periodStart > 0 ? periodStart : nowSec());
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    /** 周期内第 i 天（0 基）的 00:00 时间戳 */
    public static long dayStart(long periodStart, int i) {
        return periodStart + (long) i * 86400L;
    }

    public static boolean isCurrent(String mode, long periodStart) {
        return periodStart > 0 && periodStart == startOf(mode, 0);
    }

    /** 该日是不是"今天" */
    public static boolean isToday(long dayStartSec) {
        return dayStartSec == todayStartSec();
    }

    /** 当月 1 日是周几（周一=0 … 周日=6）—— 日历网格首行要空几格 */
    public static int firstWeekdayIndex(long periodStart) {
        Calendar c = cal(periodStart);
        c.set(Calendar.DAY_OF_MONTH, 1);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        return (dow == Calendar.SUNDAY) ? 6 : (dow - Calendar.MONDAY);
    }

    private static String fmt(String pattern, long sec) {
        return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(sec * 1000L));
    }

    /**
     * 卡片抬头（按用户拍板 A）：
     * · 本周     → `本周阅读时长`
     * · 本月     → `9月阅读`（**当月不加年份**，与参考图一致）
     * · 历史月   → `2026年3月阅读`（**带年份**，否则跨年会歧义）
     */
    public static String title(String mode, long periodStart, boolean current) {
        if (BOOK.equals(mode)) return "本书阅读进度";
        if (NOTE.equals(mode)) return "本记 · 今日一签";
        if (!MONTHLY.equals(mode)) {
            if (current) return "本周阅读时长";
            return "周 " + weekLabel(periodStart);
        }
        Calendar c = cal(periodStart);
        int m = c.get(Calendar.MONTH) + 1;
        if (current) return m + "月阅读";
        return c.get(Calendar.YEAR) + "年" + m + "月阅读";
    }

    /** `2026年9月15日 – 9月21日`（跨年补上第二段的年份） */
    public static String weekLabel(long weekStart) {
        long end = weekStart + 6L * 86400L;
        Calendar a = cal(weekStart);
        Calendar b = cal(end);
        StringBuilder sb = new StringBuilder();
        sb.append(a.get(Calendar.YEAR)).append("年")
          .append(a.get(Calendar.MONTH) + 1).append("月")
          .append(a.get(Calendar.DAY_OF_MONTH)).append("日 – ");
        if (a.get(Calendar.YEAR) != b.get(Calendar.YEAR)) {
            sb.append(b.get(Calendar.YEAR)).append("年");
        }
        sb.append(b.get(Calendar.MONTH) + 1).append("月")
          .append(b.get(Calendar.DAY_OF_MONTH)).append("日");
        return sb.toString();
    }

    /** `2026年9月` */
    public static String monthLabel(long monthStart) {
        return fmt("yyyy年M月", monthStart);
    }
}
