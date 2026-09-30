package com.inkread.weekread.core;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * 一条待办（TASK-024 / V1.0.3-beta）。
 *
 * 字段极简，**零依赖**（`org.json` 是 Android 内置）：
 * · 内容必填；日期 / 时间**均可空**（用户拍板：只输内容也能存）；
 * · 日期粒度到「日」（{@link #dateSec} = 当天 00:00 的秒）；时间粒度到「分钟」（{@link #minuteOfDay}）；
 * · {@link #order} 越小越靠前（排序态的 ↑↓ 就是改它）。
 *
 * 🔴 与 `PeriodStats` / `BookStats` 同款：**纯数据 + JSON 存取**，读写落在 {@link TodoStore}。
 */
public final class TodoItem {

    /** 未设时间的哨兵值（0..1439 是合法分钟数） */
    public static final int NO_TIME = -1;

    /** 唯一 id（{@link TodoStore} 自增分配） */
    public long id;
    /** 内容（必填；空白视为非法，由 UI 拦截） */
    public String content = "";
    /** 日期（当天 00:00 的秒）；0 = 未设 */
    public long dateSec = 0L;
    /** 时间：当天第几分钟（0..1439）；{@link #NO_TIME} = 未设 */
    public int minuteOfDay = NO_TIME;
    /** 完成态：true = 已勾选（移入已完成列表） */
    public boolean done = false;
    /** 排序序号（升序；越小越靠前） */
    public long order = 0L;

    public TodoItem() {
    }

    public TodoItem(long id, String content) {
        this.id = id;
        this.content = content;
    }

    public boolean hasDate() {
        return dateSec > 0L;
    }

    public boolean hasTime() {
        return minuteOfDay >= 0 && minuteOfDay < 1440;
    }

    /**
     * 「日期时间」列的文字（卡片与该列与 APP 列表共用同一口径）。
     * 三种组合各一种写法，都空则返回空串（调用方据此**不画这一列**，验收 A5/A7）：
     * · 只有日期 → `9月30日`
     * · 只有时间 → `14:30`
     * · 都有     → `9月30日 14:30`
     */
    public String whenLabel() {
        String d = hasDate() ? dateLabel() : null;
        String t = hasTime() ? timeLabel() : null;
        if (d == null && t == null) return "";
        if (d == null) return t;
        if (t == null) return d;
        return d + " " + t;
    }

    /** `9月30日`（跨年补年份，与本项目 `PeriodRange.weekLabel` 同口径） */
    public String dateLabel() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dateSec * 1000L);
        int y = c.get(Calendar.YEAR);
        int m = c.get(Calendar.MONTH) + 1;
        int d = c.get(Calendar.DAY_OF_MONTH);
        int nowYear = Calendar.getInstance().get(Calendar.YEAR);
        if (y != nowYear) return y + "年" + m + "月" + d + "日";
        return m + "月" + d + "日";
    }

    /** `14:30`（两位补零） */
    public String timeLabel() {
        int h = minuteOfDay / 60, mi = minuteOfDay % 60;
        return (h < 10 ? "0" : "") + h + ":" + (mi < 10 ? "0" : "") + mi;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("content", content == null ? "" : content);
            o.put("date", dateSec);
            o.put("min", minuteOfDay);
            o.put("done", done);
            o.put("order", order);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 解析；字段缺失/类型错一律走默认值（**老数据 / 手改脏值不炸**，与项目其它 parse 同纪律） */
    public static TodoItem fromJson(JSONObject o) {
        TodoItem it = new TodoItem();
        it.id = o.optLong("id", 0L);
        it.content = o.optString("content", "");
        it.dateSec = o.optLong("date", 0L);
        it.minuteOfDay = o.optInt("min", NO_TIME);
        it.done = o.optBoolean("done", false);
        it.order = o.optLong("order", 0L);
        return it;
    }
}
