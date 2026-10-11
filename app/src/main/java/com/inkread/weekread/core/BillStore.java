package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 🆕 TASK-077：**账单仓库**（prefs 文件 `bills`）。
 *
 * <p>键 = {@code bill_<mode>_<periodStart>}（如 `bill_weekly_1759632000`），值 = {@link Bill#toJson()}。
 * 🔴 **各 mode 各留最近 12 期**（`tasks/TASK-077` §关键约束 5，照 `StatsStore.prune` 的轮转口径）。
 *
 * <p>🔴 **只读写、不发请求、不做业务判断**：生成逻辑全在 {@link BillScheduler}；
 * 本类与 {@link Bill} 一起构成"渲染侧只读"的落点（账单一渲染就只碰这里 ⇒ 天然零请求）。
 */
public final class BillStore {

    private static final String PREFS = "bills";
    private static final String PREFIX = "bill_";
    /** 每个 mode 各留几期（weekly 档；monthly 档见 {@link #KEEP_MONTHLY}）。 */
    public static final int KEEP = 12;

    /**
     * 🆕 `TASK-086` 第三批（A19）：**monthly 档**单独留 13 期 = 当月 + 前 12 个月。
     *
     * <p>🔴 为什么必须比 {@link #KEEP} 多 1：年回填的目标是"近 12 个月"
     * （`BillScheduler.YEAR_BACKFILL_MONTHS = 12`），而它逐期填的是 `curStart` 之前第 1..12 个月
     * ⇒ 当月那一期 + 12 个回填期 = **13 个键**。若仍按 12 留，写第 12 个月时会恰好触发
     * {@link #prune}，把**刚写好的最老一期当场淘汰** ⇒ 净窗口只有"当月 + 前 11 个月"，
     * 比承诺少一个月（Lead 2026-10-11 裁决：不能只登记，须最小修复）。
     *
     * <p>weekly 档维持 {@link #KEEP}：回填只按月做，weekly 没有"当月 + 前 12 周"的需求。
     */
    public static final int KEEP_MONTHLY = 13;

    /** 该 mode 的保留期数：monthly ⇒ {@link #KEEP_MONTHLY}（13），其余 ⇒ {@link #KEEP}（12）。 */
    private static int keepOf(String mode) {
        return PeriodRange.MONTHLY.equals(mode) ? KEEP_MONTHLY : KEEP;
    }

    /**
     * 🆕 `TASK-086` 第三批（A19）：**年回填水位线**的键。
     *
     * <p>🔴 键名**故意**不以 `bill_weekly_` / `bill_monthly_` 开头 ⇒ {@link #startsOf} 的
     * `startsWith(prefix)` 扫描不会把它误当成一期账单。
     */
    private static final String K_YEAR_BACKFILLED = "bill_year_backfilled";

    private BillStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 键：`bill_weekly_<start>` / `bill_monthly_<start>`。 */
    static String keyOf(String mode, long periodStart) {
        return PREFIX + (PeriodRange.MONTHLY.equals(mode) ? PeriodRange.MONTHLY : PeriodRange.WEEKLY)
                + "_" + periodStart;
    }

    private static String prefixOf(String mode) {
        return PREFIX + (PeriodRange.MONTHLY.equals(mode) ? PeriodRange.MONTHLY : PeriodRange.WEEKLY) + "_";
    }

    // ══════════════════════ 读 ══════════════════════

    /** 取某一期账单；无 / 坏数据 ⇒ null。 */
    public static Bill load(Context c, String mode, long periodStart) {
        if (periodStart <= 0) return null;
        String raw = sp(c).getString(keyOf(mode, periodStart), null);
        return Bill.fromJson(raw, mode, periodStart);
    }

    /** 该期**是否已有**（含占位账）—— 幂等判据。 */
    public static boolean has(Context c, String mode, long periodStart) {
        return periodStart > 0 && sp(c).contains(keyOf(mode, periodStart));
    }

    /**
     * 该 mode 下**已生成**的全部期起点（**降序** = 最近在前）。
     * 供补缺扫描 / 调试断言用。
     */
    public static List<Long> startsOf(Context c, String mode) {
        String pre = prefixOf(mode);
        List<Long> out = new ArrayList<Long>();
        try {
            Map<String, ?> all = sp(c).getAll();
            for (String k : all.keySet()) {
                if (k == null || !k.startsWith(pre)) continue;
                try {
                    out.add(Long.parseLong(k.substring(pre.length())));
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }

    /**
     * 墨台「阅读账单」模块要显示的那一期 = **已生成账单里最新的一期**。
     *
     * <p>周与月各取最新一期，谁更近用谁；**期起点相同 ⇒ 周优先**（周是更"像小票"的粒度）。
     * 两者都没有 / 都为占位 ⇒ 返回 null（渲染侧画空态）。
     */
    public static Bill latest(Context c) {
        List<Long> w = startsOf(c, PeriodRange.WEEKLY);
        List<Long> m = startsOf(c, PeriodRange.MONTHLY);
        long ws = w.isEmpty() ? -1L : w.get(0);
        long ms = m.isEmpty() ? -1L : m.get(0);
        if (ws < 0 && ms < 0) return null;
        if (ws >= ms) return load(c, PeriodRange.WEEKLY, ws);
        return load(c, PeriodRange.MONTHLY, ms);
    }

    // ══════════════════════ 写 ══════════════════════

    /**
     * 落一期账单 + 轮转（各 mode 留 {@link #keepOf} 期：monthly 13 / weekly 12）。
     *
     * <p>🔴 <b>整体覆盖，不做任何合并</b>：同一 `(mode, periodStart)` 的旧记录会被
     * {@link Bill#toJson()} 的整串结果直接顶掉。而 `toJson()` 是**条件写**（没值的字段不写）
     * ⇒ <b>调用方必须先把"本次没取到"的字段从旧账继承回来再调本方法</b>，
     * 否则一次"数据源不可用"的重算就能把旧账的好数据洗成缺省。本方法**故意**不做这个合并：
     * 类注释承诺"只读写、不做业务判断"，而"哪些字段算取到了"是业务语义（`-1`/`UNKNOWN` 的约定
     * 定义在 {@link Bill.Item} 里）。实现见 `BillScheduler.generateOne(...)` 尾部的"旧账字段继承"块。
     *
     * <p>全仓唯一调用点 = `BillScheduler.generateOne`（task-11(a) 核实）。
     */
    public static void save(Context c, Bill b) {
        if (b == null || b.periodStart <= 0) return;
        sp(c).edit().putString(keyOf(b.mode, b.periodStart), b.toJson()).commit();
        prune(c, b.mode);
    }

    /** 该 mode 只留最近 {@link #keepOf} 期（按**期起点**降序保留；更老的连键删掉）。 */
    static void prune(Context c, String mode) {
        final int keep = keepOf(mode);
        List<Long> starts = startsOf(c, mode);
        if (starts.size() <= keep) return;
        SharedPreferences.Editor ed = sp(c).edit();
        for (int i = keep; i < starts.size(); i++) ed.remove(keyOf(mode, starts.get(i)));
        ed.commit();
        CardDebug.noteV(c, "bill prune: " + mode + " 保留 " + keep + " 期，删 " + (starts.size() - keep) + " 期");
    }

    /** 清空全部账单（设置页 / 调试用）。 */
    public static void clear(Context c) {
        sp(c).edit().clear().commit();
    }

    /** 某 mode 现有几期（断言用）。 */
    public static int count(Context c, String mode) {
        return startsOf(c, mode).size();
    }

    // ══════════════════ 年回填水位线（TASK-086 第三批 · A19） ══════════════════

    /**
     * 🆕 `TASK-086` 第三批（A19）：**当月 + 前 12 个月**（共 13 期）的**逐期回填**（`BillScheduler.maybeBackfillYear`）
     * 是否已经**完整**跑过一次。
     *
     * <p>为什么需要水位线：回填是"一次性后台补历史"（用户拍板：首开无提示、后台自动回填、不阻塞渲染）
     * ⇒ 跑完就该停，不能每次开 App 都重拉 13 次请求。**只在 13 期全部走完时**才置位
     * （中途失败 ⇒ 下次继续补，见 `BillScheduler.maybeBackfillYear`）。
     */
    public static boolean yearBackfilled(Context c) {
        return sp(c).getBoolean(K_YEAR_BACKFILLED, false);
    }

    /** 置年回填水位线。调用点唯一 = `BillScheduler.maybeBackfillYear`（13 期全走完那一刻）。 */
    public static void markYearBackfilled(Context c) {
        sp(c).edit().putBoolean(K_YEAR_BACKFILLED, true).commit();
    }
}
