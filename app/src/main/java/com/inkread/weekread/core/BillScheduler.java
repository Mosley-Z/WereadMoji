package com.inkread.weekread.core;

import android.content.Context;

import com.inkread.weekread.net.WereadApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🆕 TASK-077：**阅读账单的补缺器** —— 把「已完结的周 / 月」固化成 {@link Bill} 落进 {@link BillStore}。
 *
 * <h3>为什么是"补缺"而不是"定时"</h3>
 * 项目铁律：**不做定时闹钟**（{@link android.app.AlarmManager} / 前台服务一律不引 —— 墨水屏设备上
 * 那是纯负担，还多一份"被系统杀掉"的不确定性）。所以口径改成**幂等补齐**：
 * 每次 App 起来（服务创建 + {@code MainActivity.onStart}）扫一遍"最近几个已完结周期"，
 * **缺哪个补哪个**。整周 / 整月没开 App ⇒ 下次打开一次补上（{@code tasks/TASK-077} §目标 2）。
 *
 * <h3>三条红线（{@code tasks/TASK-077} §关键约束）</h3>
 * <ol>
 *   <li><b>幂等</b>：可重复调用；{@link BillStore#has} 已存在的期**不重算**（{@link #fill} 一碰到
 *       已存在的期就停 —— "更早的期早已处理过"是连续性假设，见 {@link #fill} 注释）；</li>
 *   <li><b>不写假账</b>：缓存与接口**都拿不到** ⇒ 本期**跳过**（不落空 JSON）；拿到了但本期无书目
 *       ⇒ 按 {@link MenuPrefs#emptyAction} 写**占位账**（Q10）或按用户选择不生成；</li>
 *   <li><b>渲染永不发请求</b>：生成 / 热门预热只在本类的后台线程里做；
 *       渲染侧（{@code feature/BillSection}）只读 {@link BillStore}。</li>
 * </ol>
 *
 * <h3>请求预算</h3>
 * 稳态下每次调用只做 `BillStore.has()` 检查（**零请求**）。只有真的缺期时才发请求：
 * 每期 1 次 `/readdata/detail`（若本地缓存已有则跳过）+ 每本 Top N 书 1 次
 * `/book/bestbookmarks`（仅当摘录策略含"热门"）+ 每本 1 次 `/book/getprogress`（仅当开了"进度"）。
 * ⇒ 默认配置（仅我的内容 + 不显示进度）下，**每补齐一期只需 1 次请求**。
 */
public final class BillScheduler {

    /**
     * 补缺**回溯上限**（周 / 月各自最多往回扫几期）。
     *
     * <p>为什么要有上限：{@link #fill} 是"从最近的已完结期往回扫"，若用户半年没开 App，
     * 无上限就会一次冒出二十几期账单 + 几十次请求。取 4 ⇒ 覆盖"一个月没开"的常见情形，
     * 更老的期**不补**（宁可留白，也不要在启动时打一串请求）。
     */
    private static final int BACKFILL_MAX = 4;

    /**
     * 🆕 `TASK-086` 第三批（A19）：**年回填**往回补几个月（= 用户拍板的"近 12 个月"，`§20.10`）。
     *
     * <p>🔴 实际回填 **13 期 = 当月 + 前 12 个月**（循环 `i = 0 .. 该值`）—— 与
     * {@link BillStore#KEEP_MONTHLY}（13）成对：承诺的窗口是"当月 + 前 12 个月"，少一个月会被
     * `prune` 当场吃掉（见 `验证记录/212` 的"修复前/修复后"对照）。
     *
     * <p>为什么用"月"而不是"周"：月粒度正好覆盖"回查一年"，且一周一次的回填在**估算**上更细但
     * 请求量 ×4（§20.8 落法写的是"月 ×12 ≈ 12 次"）⇒ 按定稿口径只回填月账。
     */
    private static final int YEAR_BACKFILL_MONTHS = 12;

    /** 年回填每期之间的**限流**间隔（毫秒）—— 一次性补 13 期，别把接口打成连发（`§20.10` 分批限流）。 */
    private static final long YEAR_BACKFILL_GAP_MS = 400L;

    /**
     * 🆕 task-48（用户第五批 #2④）：**当期刷新**的最小间隔（毫秒）—— 见 {@link #refreshCurrent}。
     *
     * <p>取 60 s：`ensureBills` 的调用点不止一个（服务创建 / `MainActivity.onStart` / a11y 回调），
     * 短时间连开会被连点；60 s 既能保证"每次真正打开 App 拿到的是当期最新进度"，又把
     * 稳态请求量压到"每分钟至多一趟 `getprogress`"。
     */
    private static final long CUR_REFRESH_GAP_MS = 60_000L;

    /**
     * 🔴 并发闸：服务创建与 {@code MainActivity.onStart} 是两个独立的调用点，
     * 可能在极短时间内各调一次。不闸的话两遍扫描会并发跑同一期（重复请求；落盘虽然幂等，
     * 但白烧流量）。用 CAS 保证**同一时刻只有一趟**在跑。
     */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private BillScheduler() {
    }

    /**
     * **幂等补齐**账单 —— 唯一的对外入口。
     *
     * <p>🔴 内部起独立后台线程（可能发网络请求），**立即返回** —— 调用点（服务创建 / onStart）
     * 都是主线程，绝不能在这里同步等 IO。
     */
    public static void ensureBills(Context ctx) {
        if (ctx == null) return;
        final Context c = ctx.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return;      // 已在跑 ⇒ 幂等早退
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runEnsure(c);
                } catch (Throwable ignored) {
                } finally {
                    RUNNING.set(false);
                }
            }
        }, "bill-ensure");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 清单全在**后台线程**跑。
     *
     * <p>先把没算完的"活跃记忆"（{@code stats_*} 系列缓存）读一遍：{@link #fill} 对每一期
     * 优先走这份本地缓存（**零请求**），缓存缺了才发 `/readdata/detail`。
     */
    private static void runEnsure(Context c) {
        String apiKey = StatsStore.getKey(c);
        long now = PeriodRange.nowSec();
        // 时间线：**月** 通常"下个月 1 号"就成型（粒度粗、变化快），**周** 则每周一成型。
        // 两个 mode 各自独立补，互不影响。
        fill(c, apiKey, PeriodRange.WEEKLY, now);
        fill(c, apiKey, PeriodRange.MONTHLY, now);
        // 🆕 TASK-086 第三批（A19）：一次性把最近 12 个月补齐（**自动、无提示、不阻塞渲染**）
        maybeBackfillYear(c, apiKey, now);
        // 🆕 task-48（用户第五批 #2④）：**当期有界刷新** —— `fill` 只补已完结期、年回填有一次性水位线
        //    ⇒ 当期账一旦落盘就再也不更新（本机 2026-10 月账停在装包时刻）。这里对两个 mode 的当期
        //    各做一次**节流常规趟**（只刷已存在的当期；60 s 内不重复）。放在 `rebuild` **之前** ⇒
        //    估算曲线拿到的当期进度是刚刷过的。
        refreshCurrent(c, apiKey, PeriodRange.WEEKLY, now);
        refreshCurrent(c, apiKey, PeriodRange.MONTHLY, now);
        // 🆕 TASK-086 第三批（A15 自洽）：把两个 mode 的估算点**整条曲线**重算一遍 ——
        //    `fill` 是"一期一期就地估算"，各期锚点/分母随数据增长而变 ⇒ 数列不 telescope
        //    （2026-10-11 S4 实测 Σ增额 ¥98 ≠ 末期累计 ¥33.32）。这里用同一锚点基准写定全窗口，
        //    并把**期初链到上一期期末** ⇒ 逐期增额首尾相消。纯本地、零请求。
        BillEstimator.rebuild(c, PeriodRange.WEEKLY);
        BillEstimator.rebuild(c, PeriodRange.MONTHLY);
    }

    /**
     * 🆕 `TASK-086` 第三批（A19，`§20.8` / `§20.10` 定稿）：**一次性**年回填 —— 把**当月 + 前
     * {@link #YEAR_BACKFILL_MONTHS} 个月**（共 13 期，循环 `i = 0 .. 12`）的**月账**补齐，好让估算点（`§20.4` 锚点法）拿得到
     * 逐期的 `R_j`（= 各期账单里每本书的 `readTimeSec`）。
     *
     * <h3>为什么不复用 {@link #fill}</h3>
     * `fill` 一碰到"已有"就 `break`（期与期连续 ⇒ 更早的期也都处理过），而年回填恰恰要**跳过已有的、
     * 继续往更早补**（用户可能已有 3 期账）⇒ 必须 `continue`，且要跨过 {@link #BACKFILL_MAX} 的 4 期上限。
     *
     * <h3>一次性水位线</h3>
     * 跑完 13 期才 {@link BillStore#markYearBackfilled}（中途某期数据拿不到 ⇒ **不置位**，
     * 下次启动继续补）⇒ 稳态下每次开 App 只做一次 `getBoolean`（**零请求**）。
     *
     * <h3>请求预算与红线</h3>
     * 每期 1 次 `/readdata/detail`（本地缓存已有则跳过）＋ 每期之间限流 {@link #YEAR_BACKFILL_GAP_MS}；
     * 估算趟**不抓进度、不抓热门**（见 {@link #generateOne(Context, String, String, long, boolean)}）⇒
     * 一次性成本 ≈ 13 次请求。🔴 全部发生在 `bill-ensure` 后台线程，**渲染侧依旧只读 `BillStore`**。
     */
    private static void maybeBackfillYear(Context c, String apiKey, long nowSec) {
        // 实付关了 ⇒ 估算没人看，别占带宽（用户随时可以重新打开，那时再补）
        if (MenuPrefs.PAID_OFF.equals(MenuPrefs.paidAlgo(c))) return;
        if (BillStore.yearBackfilled(c)) return;                       // 已完整回填过 ⇒ 一次性
        if (apiKey == null || apiKey.length() == 0) return;            // 没登录 ⇒ 拉不到，等下次

        final long curStart = PeriodRange.startOf(PeriodRange.MONTHLY, nowSec);
        int done = 0;
        // 🔴 i 从 **0** 起（2026-10-11 修正）：口径是「**当月 + 前 12 个月**」⇒ 共
        //    {@link #YEAR_BACKFILL_MONTHS} + 1 = 13 期；且只有把**当月**也落盘，store 里才会
        //    真正出现 13 个 monthly 键，`KEEP_MONTHLY = 13` 的留档修复才**可被观测/可被演练**
        //    （此前 i 从 1 起 ⇒ 只有 12 键，`prune` 恰好不触发，修复等于无法验证）。
        for (int i = 0; i <= YEAR_BACKFILL_MONTHS; i++) {
            long ps = PeriodRange.shift(PeriodRange.MONTHLY, curStart, -i);
            if (ps <= 0) break;
            if (BillStore.has(c, PeriodRange.MONTHLY, ps)) {           // 🔴 continue，**不是** break
                done++;
                continue;
            }
            // 🔴 估算趟只在**历史期**用（i > 0）：估算趟不抓进度 ⇒ 若把**当月**也按估算趟生成，
            //    当月的行会全是 `pStart/pEnd = 空` ⇒ 月档当月退化成整片 `—`（2026-10-11 实测踩到）。
            //    当月走**常规趟**（抓进度、拿真实"现在"的累计），历史期走估算趟。
            if (!generateOne(c, apiKey, PeriodRange.MONTHLY, ps, i > 0)) break;   // 拿不到 ⇒ 本轮停，下次重试
            done++;
            try {
                Thread.sleep(YEAR_BACKFILL_GAP_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (done >= YEAR_BACKFILL_MONTHS + 1) {
            BillStore.markYearBackfilled(c);
            CardDebug.noteV(c, "bill: 年回填完成（当月 + 前 " + YEAR_BACKFILL_MONTHS + " 个月，估算点已就位）");
        } else {
            CardDebug.noteV(c, "bill: 年回填未完成 done=" + done + "/" + (YEAR_BACKFILL_MONTHS + 1) + "（下次启动续补）");
        }
    }

    /**
     * 补某一个 mode 的缺期：从"上一个已完结期"往回扫，最多 {@link #BACKFILL_MAX} 期。
     *
     * <p>🔴 碰到 `BillStore.has(...) == true` 就**停** —— 期与期之间是连续的，
     * 一旦某一期已在库里，就说明它（以及更早的期）上一轮已经处理过了 ⇒ 不必再往回扫。
     * 这既保住了幂等（不重算），也把稳态成本压到"1 次 has 检查"。
     *
     * <p>🔴 本期"**没能决定**"（拿不到数据）时也停 —— 否则一次失败会连带把更老的期全拉一遍；
     * 停在这里，下一次启动会从本期重试（{@code tasks/TASK-077} §关键约束 2）。
     */
    private static void fill(Context c, String apiKey, String mode, long nowSec) {
        long curStart = PeriodRange.startOf(mode, nowSec);
        for (int i = 1; i <= BACKFILL_MAX; i++) {
            long ps = PeriodRange.shift(mode, curStart, -i);
            if (ps <= 0) break;
            if (BillStore.has(c, mode, ps)) {
                if (i == 1) healJustFinished(c, apiKey, mode, ps);     // 🆕 task-41（#7 有界自愈）
                break;                                                 // 已有 ⇒ 更早的期也已处理（连续性）
            }
            long end = periodEnd(mode, ps);
            if (nowSec < end) continue;                            // 该期尚未结束（正常到不了这里）
            if (!generateOne(c, apiKey, mode, ps)) break;          // 未能决定 ⇒ 本轮停止，下次重试
        }
    }

    /**
     * 🆕 task-41（2026-10-11 · 用户 #7「老账不自愈复评」）—— **有界自愈：只补"刚结束的那一期"**。
     *
     * <p>问题（复评结论）：{@link #fill} 一碰到"库里已有"就 `break`、{@link #maybeBackfillYear} 一碰 `has`
     * 就 `continue` 且水位线一次性 ⇒ **已落盘的期永远不会再生成**。若某一期的账是**它还在进行中**时生成的
     * （典型 = 年回填 `i == 0` 的当期月账，见 {@code 验证记录/208}），那期的进度就永久停在"生成那一刻"，
     * 不会自愈。
     *
     * <p><b>可修面（只此一面）</b>：**刚结束的那一期**（{@code i == 1}）—— 它现在是过去时，用**常规趟**
     * 重生成一次，即可由 `/book/getprogress` 拿到"接近期末"的真值（{@code endKnown = true} ⇒ 顺带把该期的
     * 真期界快照写进 {@link ProgressLog}）。<b>幂等 / 自限流</b>：重生成后该期行里就有
     * {@code progressEndKnown = true}，下次不再触发 ⇒ 正常情况下**一期只多一次常规趟**；只有在生成失败
     * （数据拿不到）时才会在下次启动重试。
     *
     * <p>🔴 <b>不可修面（技术理由，不是"延后"）</b>：更早的历史期 —— 它们各自的期末早已过去，而
     * `/book/getprogress` 只返回"现在"的进度 ⇒ **历史期末进度事后不可查**；任何"用现在的值回填历史期"
     * 都是编数（{@code 验证记录/208} 的坑）。因此本方法**只**碰 {@code i == 1}，且只在"该期账里一行真期末
     * 都没有"时动手；不动原 `break` 语义、不动更早的期。
     */
    private static void healJustFinished(Context c, String apiKey, String mode, long ps) {
        try {
            Bill b = BillStore.load(c, mode, ps);
            if (b == null || b.items == null || b.items.isEmpty()) return;
            for (int i = 0; i < b.items.size(); i++) {
                Bill.Item it = b.items.get(i);
                // 只要有一行是"真期末"（本期就是刚结束期时抓到的），就认为这期的进度可信 ⇒ 不自愈
                if (it != null && it.progressEndKnown && it.progressEndPct >= 0) return;
            }
            CardDebug.noteV(c, "bill: 期末自愈（刚结束期缺真期末）⇒ 常规趟重生成 " + mode + " " + ps);
            generateOne(c, apiKey, mode, ps);                          // 常规趟：endKnown = true
        } catch (Throwable t) {
            // 自愈是"锦上添花"：异常绝不能影响主流程
            CardDebug.noteV(c, "bill: 期末自愈异常 " + mode + " " + ps + " " + t);
        }
    }

    /** 周期结束时刻（秒，不含）—— 起点 + 周期天数 × 86400。 */
    private static long periodEnd(String mode, long periodStart) {
        return periodStart + (long) PeriodRange.daysInPeriod(mode, periodStart) * 86400L;
    }

    /**
     * 🆕 task-48（用户第五批 #2④）：**当期（未完期）有界刷新** —— 让"当期"不再冻结在生成那一刻。
     *
     * <p><b>问题</b>：{@link #fill} 只补**已完结**的过去期（`i >= 1`），{@link #maybeBackfillYear} 有
     * 一次性水位线（{@link BillStore#yearBackfilled}）⇒ **当期账单一旦落盘就永不更新**。
     * 本机实测（2026-10-11）：2026-10 月账的 `generatedAt` 仍停在装包时刻，当期进度/金额与实际阅读脱节
     * （用户第五批 #2：当期虽有行但金额不对、且全部带 `≈`）。
     *
     * <p><b>做法</b>：对某个 mode 的**当期**，若落盘账龄 ≥ {@link #CUR_REFRESH_GAP_MS} 就用**常规趟**
     * 重生成（{@link #generateOne}：抓进度、不抓热门）⇒ 当期 `progressEndPct` 变"**至今**累计"真值、
     * 金额随之更新；`progressEndEst` 的处置由 {@link BillEstimator} 的**当期豁免**（task-48 #2③）决定。
     *
     * <p><b>有界性（红线，逐条都不许破）</b>
     * <ol>
     *   <li>**只刷"已存在的"当期** —— 当期账不存在时**不生成**（不越权改"什么时候该有账"的口径；
     *       本机当期**周**账从来不生成这一既有事实见 `验证记录/223` 的登记项，不在本轮改）；</li>
     *   <li>**节流**：账龄 &lt; {@link #CUR_REFRESH_GAP_MS} ⇒ 早退（`generatedAt` 是账单自带、已持久化的
     *       时间戳 ⇒ 冷启动也认，`BillStore` 不必新增 pref）；</li>
     *   <li>**实付关不刷**、**没登录不刷**（与 {@link #maybeBackfillYear} 同一条红线）；</li>
     *   <li>成本 = 一次常规趟：本地 `stats_*` 缓存命中 ⇒ **0 次 `/readdata/detail`**，
     *       外加 Top-N 次 `/book/getprogress`（N ≤ 榜单上限；实付关时连它也跳过）；</li>
     *   <li>全程在 `bill-ensure` 后台线程内 ⇒ **渲染侧依旧零请求**。</li>
     * </ol>
     */
    private static void refreshCurrent(Context c, String apiKey, String mode, long nowSec) {
        try {
            if (apiKey == null || apiKey.length() == 0) return;
            if (MenuPrefs.PAID_OFF.equals(MenuPrefs.paidAlgo(c))) return;
            final long curStart = PeriodRange.startOf(mode, nowSec);
            if (curStart <= 0) return;
            Bill old = BillStore.load(c, mode, curStart);
            if (old == null) return;                                   // ① 只刷已存在的当期
            long age = System.currentTimeMillis() - old.generatedAt;
            if (old.generatedAt > 0 && age >= 0 && age < CUR_REFRESH_GAP_MS) return;   // ② 节流
            CardDebug.noteV(c, "bill: 当期刷新 " + mode + " " + curStart
                    + "（上次生成 " + (old.generatedAt > 0 ? (age / 1000) + "s 前" : "时刻未知") + "）");
            generateOne(c, apiKey, mode, curStart);                    // 常规趟 ⇒ 当期进度变"至今"真值
        } catch (Throwable t) {
            // 刷新是"锦上添花"：异常绝不能影响主流程（与 healJustFinished 同一条红线）
            CardDebug.noteV(c, "bill: 当期刷新异常 " + mode + " " + t);
        }
    }

    /**
     * 生成**一期**账单。
     *
     * @return true = **本期已决定**（落盘了真实账 / 占位账，或按用户配置"不生成"）；
     *         false = **未能决定**（数据拿不到）⇒ 调用方应停止本轮并等下次重试
     */
    private static boolean generateOne(Context c, String apiKey, String mode, long periodStart) {
        return generateOne(c, apiKey, mode, periodStart, false);
    }

    /**
     * 同上；{@code estimatePass = true} 表示这是 **年回填的估算趟**（{@link #maybeBackfillYear}）。
     *
     * <p>🔴 估算趟与常规趟的**唯一**差别：历史期（`endKnown == false`）不抓进度、不抓热门 ——
     * 那两样对"估算"无意义却要花掉 Top-N × 12 次请求（见本方法内 `needProgress` / `wantHot`）。
     * 估算本身由 {@link BillEstimator} 用**已落盘账单里的阅读时长**算（零请求）。
     */
    private static boolean generateOne(Context c, String apiKey, String mode, long periodStart,
                                       boolean estimatePass) {
        // ① 数据源：**本地缓存优先**（零请求）⇒ 缺了才拉接口
        PeriodStats st = StatsStore.load(c, mode, periodStart);
        if (st == null) {
            st = WereadApi.fetchDetailBlocking(apiKey, mode, periodStart);
            if (st == null) {                                      // 🔴 都拿不到 ⇒ 跳过本期（不写假账）
                // 留痕：这条路径**不落任何文件**，没有日志的话"没生成"既可能是"跳过"也可能是"没跑"
                CardDebug.noteV(c, "bill: 本期无数据（缓存无 + 接口不可用）⇒ 跳过 "
                        + mode + " " + periodStart);
                return false;
            }
            StatsStore.save(c, st);                                // 顺手入缓存（后续再进 App 零请求）
        }
        if (st.baseTime <= 0) st.baseTime = periodStart;

        // ② 组书单：书架过滤（用户配置）⇒ 最小阈值 ⇒ 按时长降序 ⇒ Top N
        List<PeriodStats.Longest> src = RankFilter.apply(c, st.longest, MenuPrefs.dropOffShelf(c));
        int minSec = MenuPrefs.minSec(c);
        List<PeriodStats.Longest> keep = new ArrayList<PeriodStats.Longest>();
        if (src != null) {
            for (int i = 0; i < src.size(); i++) {
                PeriodStats.Longest l = src.get(i);
                if (l == null) continue;
                if (l.readTime <= 0 || l.readTime < minSec) continue;   // 只列"本期真的读过"的书
                keep.add(l);
            }
        }
        Collections.sort(keep, BY_TIME_DESC);
        int topN = MenuPrefs.topN(c);
        if (keep.size() > topN) keep = keep.subList(0, topN);

        // ③ 逐本抓摘录 / 热门 / 进度，装进账单
        Bill b = new Bill();
        b.mode = mode;
        b.periodStart = periodStart;
        b.generatedAt = System.currentTimeMillis();
        b.totalSec = st.totalSec;
        // 🆕 TASK-077b：逐日快照一并落盘（月历视图用；**零额外请求** —— daySec 就在这次 st 里）
        if (st.dayCount > 0) b.dayCount = st.dayCount;
        if (st.daySec != null) {
            for (int i = 0; i < st.daySec.length && i < b.daySec.length; i++) b.daySec[i] = st.daySec[i];
        }

        String exMode = MenuPrefs.excerptMode(c);
        // 🔴 「仅我的内容」= 纯本地 ⇒ **一次热门请求都不发**（v5 拍板：默认档只读本地）
        // 🆕 TASK-086 第三批：**年回填的估算趟**同样不抓热门 —— 回填只为"逐期 R_j（阅读时长，
        //    来自 `/readdata/detail` 的 `readLongest`）+ 估算"（§20.8 请求预算：月 ×12 ≈ 12 次）；
        //    每期再打 Top-N 次 `/book/bestbookmarks` 会把一次性成本抬到上百次（不值）。
        boolean wantHot = !MenuPrefs.EX_MINE.equals(exMode) && !estimatePass;
        boolean wantProgress = MenuPrefs.showProgress(c);
        long endSec = periodEnd(mode, periodStart) - 1L;

        // 🆕 TASK-086：**实付**要用进度（算法 A 要期初+期末、算法 B 要期末）⇒
        //    实付列没关就必须逐本抓进度。接口仍是既有的 `/book/getprogress`（**零新增接口**），
        //    代价是"补齐一期"多 Top-N 次请求（N ≤ 8）—— 一期只发生一次，可接受。
        final boolean baseNeedProgress = wantProgress
                || !MenuPrefs.PAID_OFF.equals(MenuPrefs.paidAlgo(c));

        // 🆕 TASK-086：本期是不是"刚刚结束的那一期" —— 只有它抓到的进度才等于**本期期末进度**。
        //    补更早的期时（三周没开 App）抓到的其实是"现在"，不能冒充期末 ⇒ progressEndKnown=false。
        final long curStart = PeriodRange.startOf(mode, PeriodRange.nowSec());
        final boolean endKnown = (periodStart == PeriodRange.shift(mode, curStart, -1));
        final long prevStart = PeriodRange.shift(mode, periodStart, -1);

        // 🆕 TASK-086 第三批（A19 年回填）：**估算趟**里的**历史期**一律不抓进度 ——
        //    抓到的"现在"不是该期期末（`endKnown == false`），估算走锚点法、根本不会采信它，
        //    却要为此每本打一次 `/book/getprogress`（Top-N 次）⇒ 白烧带宽。
        final boolean needProgress = baseNeedProgress && !(estimatePass && !endKnown);

        for (int i = 0; i < keep.size(); i++) {
            PeriodStats.Longest l = keep.get(i);
            Bill.Item it = new Bill.Item();
            it.bookId = nz(l.bookId);
            it.title = nz(l.title);
            it.author = nz(l.author);
            it.readTimeSec = l.readTime;

            // 🆕 TASK-086：**市场价** —— ① 本期直接读过的书：`/readdata/detail` 自带 `centPrice`（最直接）
            //    ② 否则查本地价格索引（来自 `/user/notebooks`，零请求）③ 都没有 ⇒ UNKNOWN（显示 `—`）
            int fen = (l.centPrice > 0)
                    ? l.centPrice
                    : (it.bookId.length() > 0
                        ? NoteStore.priceFenOf(c, it.bookId) : BillMoney.UNKNOWN);
            if (l.centPrice <= 0 && l.free) fen = BillMoney.FREE;
            it.marketPriceFen = fen;

            if (it.bookId.length() > 0) {
                // 个人摘录（**本地只读**，不发请求）
                JSONArray marks = NoteStore.marks(c, it.bookId);
                JSONArray ideas = NoteStore.ideas(c, it.bookId);
                it.mineCount = (marks == null ? 0 : marks.length())
                        + (ideas == null ? 0 : ideas.length());
                it.mineText = pickExcerpt(marks, ideas, periodStart, endSec);

                // 热门（**仅当策略需要**；后台预热后缓存，渲染只读缓存）
                if (wantHot) {
                    List<String> hot = WereadApi.fetchBestBookmarks(apiKey, it.bookId);
                    if (!hot.isEmpty()) it.hotText = hot.get(0);
                }
                // 进度（默认关；🆕 实付列开着时强制抓 —— 见 needProgress）
                if (needProgress) {
                    Integer pg = fetchProgressPct(apiKey, it.bookId);
                    if (pg == null) {
                        // 兜底：索引里顺带带回的 `readingProgress`（略旧但真实，且零请求）
                        int idxPg = NoteStore.progressOf(c, it.bookId);
                        if (idxPg >= 0) pg = Integer.valueOf(idxPg);
                    }
                    if (pg != null) {
                        it.progressPct = pg.intValue();
                        // 🆕 TASK-086：**期初进度** = 上一期结束时的快照（`ProgressLog`）
                        // 🆕 TASK-086 拍板②（2026-10-10）：`ProgressLog` 没有该期快照时（首期 / 升版前
                        //    生成的账没有快照 / 换机），回落到**上一期账单里同书的 `progressPct`** ——
                        //    `BillStore` 留 12 期，其 `progressPct` 是**当期真实的期末值**（拍板文档 §3-2）。
                        // 🔴 判据（照 Lead 核实的理由，必须留档）：`Bill.Item.progressEndKnown` 标记
                        //    "这个进度是不是真的该期期末值"。补旧期时抓到的其实是**"现在"**，不能冒充期末
                        //    ⇒ **只有 `endKnown == true` 且 `progressPct >= 0` 的上一期才可当基线**。
                        // 🔴 `it.progressEndKnown = endKnown` 的语义**不改** —— `BillMoney.paidFen` 依赖它。
                        it.progressStartPct = ProgressLog.at(c, it.bookId, prevStart);
                        if (it.progressStartPct < 0) {
                            Bill prev = BillStore.load(c, mode, prevStart);
                            // 拍板② 兜底的前提：这**真的是**上一期的账单（期号自证，别信 store 的返回值）
                            if (prev != null && prev.periodStart == prevStart && prev.items != null) {
                                for (int j = 0; j < prev.items.size(); j++) {
                                    Bill.Item pit = prev.items.get(j);
                                    if (pit != null && it.bookId.equals(nz(pit.bookId))
                                            && pit.progressEndKnown && pit.progressPct >= 0) {
                                        it.progressStartPct = pit.progressPct;
                                        break;
                                    }
                                }
                            }
                        }
                        it.progressEndKnown = endKnown;
                        // 🆕 task-13（2026-10-11）：**该期期末的累计进度** —— 累计档据此显示"所选周期末"
                        //   的累计，而不是"现在"（用户报的问题：无论看哪一期都显示同一个 34%）。
                        // 🔴 必须在下面的 `ProgressLog.put` **之前**读，否则本期快照会被本次的值顶掉、
                        //   链① 永远取不到"本期原有的历史快照"。
                        // 🔴 取值链（真实性优先，宁可留白不编数）：
                        //   ① `ProgressLog` 里**本期**的期界快照（键语义 = "该期结束时"，见 `ProgressLog.put`
                        //      的 javadoc）—— 该期期末的真实记录，最可信。
                        //      ⚠️ 卡面写的 `at(nextStart)` **不采用**：`nextStart` 那期的期末值是**未来**的值，
                        //         拿来当"本期期末"会张冠李戴（已在 `验证记录/207` 登记）。
                        //   ② 本期就是"刚结束的那一期"（`endKnown`）⇒ 本次抓到的 `pg` 即期末值。
                        //   ③ 本期是**当前进行中**的期（`periodStart == curStart`）⇒ `pg`，语义是"至今"。
                        //      （`fill()` 目前不为当期生成账单 ⇒ 本条不可达，留作兜底。）
                        //   ④ 都取不到 ⇒ -1 ⇒ 累计档该行 `—`（不编数）。
                        int endPct = ProgressLog.at(c, it.bookId, periodStart);
                        if (endPct < 0 && endKnown) endPct = pg.intValue();
                        if (endPct < 0 && periodStart == curStart) endPct = pg.intValue();
                        it.progressEndPct = endPct;
                        // 记下"本期结束时"的进度，供**下一期**当期初基线（一期一次，常量级增长）
                        // 🔴 拍板② 配套修复（上机倒逼，2026-10-10）：**只在本期真的是"刚结束的那一期"时**才记。
                        //    `endKnown == false` 表示这次抓到的是**"现在"**（补更早的期），
                        //    它**不是**那一期的期末值；若照样 `put`，会用"现在"顶掉那一期的真实快照，
                        //    并让 `ProgressLog.at(下一期)` 永久失配 ⇒ 下一期实付整列 `—`。
                        //    上机实测（S4）：`31808219` 的快照被补旧期覆盖成 `1788192000:34`，
                        //    于是当期 `1790524800` 取不到基线 ⇒ 整列 `—`，正是拍板② 要治的病根之一。
                        if (endKnown) {
                            ProgressLog.put(c, it.bookId, periodStart, pg.intValue());
                        }
                    }
                }
            }
            b.items.add(it);
        }

        // ④ 空数据处理（Q10 / 配置 ⑬）
        if (b.items.isEmpty()) {
            if (MenuPrefs.EMPTY_SKIP.equals(MenuPrefs.emptyAction(c))) {
                CardDebug.noteV(c, "bill: 本期无书目，按配置不生成 " + mode + " " + periodStart);
                return true;                                       // "已决定" ⇒ 继续扫更早的期
            }
            b.placeholder = true;                                  // 写占位账（"本期无阅读记录"）
        }
        // ══════════ 🆕 task-11(a) ② 健壮性：旧账字段继承（2026-10-10）══════════
        // 🔴 症状（上轮 §8 已知缺陷）：`Bill.toJson()` 是**条件写**（`Bill.java:187-191`：`progressPct >= 0` /
        //    `marketPriceFen != UNKNOWN` / `progressStartPct >= 0` / `progressEndKnown == true` 才写该字段），
        //    而 `BillStore.save()` 是**整体覆盖**（`putString(key, b.toJson())`，无合并）⇒ 只要有一次 regen
        //    赶在"数据源不可用"时（`/book/getprogress` 取不到 **且** 索引里的 `readingProgress` 也没有
        //    ⇒ `pg == null`；价格同理），新记录就**缺** progressPct / progressStartPct / progressEndKnown /
        //    marketPriceFen 字段，读回时落成缺省（-1 / -1 / false / UNKNOWN）⇒ **旧账里已有的好数据被洗掉**。
        //    （`BillStore.save` 全仓唯一调用点就是下面那一行 ⇒ 在这里合并 = 覆盖所有落盘路径。）
        // ✅ 修法：落盘前，与**同一 mode + 同一 periodStart 的旧账**逐本（按 `bookId`）比对，**只补本次没取到的**字段。
        // 🔴 继承判据 / 清单（**不得把继承值伪装成本期期末可信**）：
        //    · `progressPct`     ：仅当本次 `< 0`（= 本次没取到）且旧值 `>= 0` 时继承，并**原样沿用**旧账的
        //                          `progressEndKnown`。该标记的语义是"**这个存下来的值**是不是真的该期期末值"，
        //                          属于值本身（不是"这一次跑的结论"）⇒ 继承时**绝不把它抬成 true**；
        //                          旧值是 false 就保持 false，从源头杜绝"继承来的值冒充可信期末值"。
        //    · `progressStartPct`：仅当本次 `< 0` 且旧值 `>= 0` 时继承（它是**上一期**的期末快照，与本次成败无关）。
        //    · `progressEndPct`  ：🆕 task-13 同款判据（"该期**自己的**期末累计"，属值本身的语义；不继承会让累计档无故 `—`）。
        //    · `marketPriceFen`  ：仅当本次为 `UNKNOWN` 且旧值 `!= UNKNOWN` 时继承（**含 FREE=0**，0 也是有效真值，
        //                          所以比较必须用 `== UNKNOWN` 而不是 `<= 0`）。
        //    · 其余字段（`title` / `author` / `readTimeSec` / `mineCount` / `mineText` / `hotText`）**一律不继承**：
        //      它们本次是**真实重算**的（阅读时长/摘录来自本地统计与本地索引，不存在"数据源不可用"的灰区），
        //      继承只会把旧值当新值。**不在本期 `keep` 里的书也不回填**——那属于"这次统计里真没有"，不是缺字段。
        // 🔴 为什么放在调度层而不是 `BillStore.save`：`BillStore` 的类注释承诺"只读写、不做业务判断"，而
        //    "哪些字段算取到了"是业务语义（`-1`/`UNKNOWN` 的约定定义在 `Bill.Item` 里）。
        // ⚠️ 已知的**同域但不在本卡范围**的另一条洗数路径（仅登记、未修，见 `验证记录/205` §遗留）：
        //    `MenuPrefs.EMPTY_SKIP` 之外的"本次 keep 为空 ⇒ placeholder 占位账"会把旧账的 items 整体换成空表；
        //    以及调试钩子 `--ez wb_bill_regen true` 会先 `BillStore.clear()` 再重建（那时旧账已不存在，无可继承）。
        int inheritedFields = 0;
        Bill oldSame = BillStore.load(c, mode, periodStart);
        if (oldSame != null && oldSame.periodStart == periodStart && oldSame.items != null) {
            for (int i = 0; i < b.items.size(); i++) {
                Bill.Item it = b.items.get(i);
                if (it.bookId == null || it.bookId.length() == 0) continue;   // 无 bookId 无从对齐，宁可不动
                for (int j = 0; j < oldSame.items.size(); j++) {
                    Bill.Item oit = oldSame.items.get(j);
                    if (oit == null || !it.bookId.equals(nz(oit.bookId))) continue;
                    if (it.progressPct < 0 && oit.progressPct >= 0) {
                        it.progressPct = oit.progressPct;
                        it.progressEndKnown = oit.progressEndKnown;   // 原样沿用；**不**抬成 true
                        inheritedFields++;
                    }
                    if (it.progressStartPct < 0 && oit.progressStartPct >= 0) {
                        it.progressStartPct = oit.progressStartPct;
                        // 🆕 TASK-086 第三批：**估算标记随值一起继承** —— 标记描述的是"这个值本身
                        //    是算出来的还是快照"，属于值的一部分；丢标记会把 `≈` 弄丢（或反过来把
                        //    精确值标成估算）。同 `progressEndKnown` 的口径：原样沿用，不臆造。
                        it.progressStartEst = oit.progressStartEst;
                        inheritedFields++;
                    }
                    // 🆕 task-13：`progressEndPct` 是"该期**自己的**期末累计"，属**值本身**的语义
                    //    （不像 `progressEndKnown` 那样描述"这次生成时机是否可信"）⇒ 本次取不到时
                    //    从旧账同书继承是安全的；**不**继承会让累计档在老账上无故变 `—`。
                    if (it.progressEndPct < 0 && oit.progressEndPct >= 0) {
                        it.progressEndPct = oit.progressEndPct;
                        it.progressEndEst = oit.progressEndEst;      // 🆕 第三批：同上（标记随值继承）
                        inheritedFields++;
                    }
                    if (it.marketPriceFen == BillMoney.UNKNOWN && oit.marketPriceFen != BillMoney.UNKNOWN) {
                        it.marketPriceFen = oit.marketPriceFen;
                        inheritedFields++;
                    }
                    break;   // bookId 在一期内唯一，命中即可停
                }
            }
        }
        if (inheritedFields > 0) {
            CardDebug.noteV(c, "bill: 旧账字段继承 " + mode + " " + periodStart
                    + " 生效条目=" + b.items.size() + " 继承字段数=" + inheritedFields);
        }

        // ══════════ 🆕 TASK-086 第三批：估算点回填（A15 / §20.4 锚点法）══════════
        // 🔴 放在**继承之后、落盘之前**：
        //    · 继承之后 —— 旧账里已有的真值先拿回来（估算只在**字段为空**时填，绝不覆盖真值 ⇒ A16 不回退）；
        //    · 落盘之前 —— 估算结果随本期账单一起写进 `BillStore`，**渲染侧因此仍是纯算术、零请求**。
        // 🔴 估算只补"缺期末（或期初）快照的历史期"，数据源 = **已落盘账单里的 `readTimeSec`**
        //    （每 mode 留 12 期）⇒ 零新表、零新增接口。
        int estRows = BillEstimator.fill(c, b);

        BillStore.save(c, b);
        CardDebug.noteV(c, "bill: 生成 " + mode + " " + periodStart + " 本数=" + b.items.size()
                + " 有价=" + BillMoney.pricedCount(c, b.items) + "/" + b.items.size()
                + " 实付合计=" + BillMoney.yuan(BillMoney.totalPaidFen(c, b.items, MenuPrefs.paidAlgo(c)))
                + (endKnown ? "" : " (补旧期·期末进度不可信)")
                + (b.placeholder ? " (占位)" : "")
                + (estRows > 0 ? " 估算行=" + estRows + "/" + b.items.size() : ""));
        return true;
    }

    // ══════════════════════ 摘录挑选 ══════════════════════

    /**
     * 从本地缓存里挑**一条**摘录：**周期内优先**（划线原文 > 想法原文 > 想法正文），
     * 周期内一条都没有 ⇒ 退回"该书最新的那条"。
     *
     * <p>⚠️ **【解读】卡面口径写的是"该书周期内一条划线/想法"**。但本地缓存是**增量同步**的
     * （{@code NoteStore} 没有"按周期取全量"的方法 —— 本卡已把这条登记为风险 R1），
     * 若严格要求"周期内"、否则留白，绝大多数书（本周读过 ≠ 本周划过）会整月没有"摘"行，
     * 反而让整个功能像坏掉。所以取"**周期内优先、否则退最新**"：退回来的那条**仍是本人
     * 在这本书上的真实内容**（不会误导成别人的），只是时点可能早于本期。
     * 本条为本卡唯一一处对卡面口径的**放宽解读**，已在验证记录里显式登记。
     */
    private static String pickExcerpt(JSONArray marks, JSONArray ideas, long startSec, long endSec) {
        String s = newestMark(marks, startSec, endSec);
        if (s != null) return s;
        s = newestIdea(ideas, startSec, endSec);
        if (s != null) return s;
        s = newestMark(marks, 0L, Long.MAX_VALUE);
        if (s != null) return s;
        return newestIdea(ideas, 0L, Long.MAX_VALUE);
    }

    /** 区间 [{@code lo}, {@code hi}] 内 createTime 最新的一条划线原文；无 ⇒ null。 */
    private static String newestMark(JSONArray marks, long lo, long hi) {
        if (marks == null) return null;
        String best = null;
        long bestT = Long.MIN_VALUE;
        for (int i = 0; i < marks.length(); i++) {
            JSONObject o = marks.optJSONObject(i);
            if (o == null) continue;
            long t = o.optLong("createTime", 0L);
            if (t < lo || t > hi) continue;
            String v = clean(o.optString("markText", ""));
            if (v != null && t >= bestT) { bestT = t; best = v; }
        }
        return best;
    }

    /**
     * 区间内 createTime 最新的一条想法；**原文（{@code abstract}）优先**于想法正文
     * （§摘录策略：摘的是"原文"，想法正文只是原文缺位时的兜底）。
     */
    private static String newestIdea(JSONArray ideas, long lo, long hi) {
        if (ideas == null) return null;
        String bestAbs = null, bestBody = null;
        long tA = Long.MIN_VALUE, tB = Long.MIN_VALUE;
        for (int i = 0; i < ideas.length(); i++) {
            JSONObject o = ideas.optJSONObject(i);
            if (o == null) continue;
            long t = o.optLong("createTime", 0L);
            if (t < lo || t > hi) continue;
            String abs = clean(o.optString("abstract", ""));
            if (abs != null && t >= tA) { tA = t; bestAbs = abs; }
            String body = clean(o.optString("content", ""));
            if (body != null && t >= tB) { tB = t; bestBody = body; }
        }
        return bestAbs != null ? bestAbs : bestBody;
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() == 0 ? null : s;
    }

    // ══════════════════════ 进度（可选 · 默认关） ══════════════════════

    /** 拉某本书的阅读进度百分比；失败 / 无 ⇒ null。**阻塞式**（仅后台线程）。 */
    private static Integer fetchProgressPct(String apiKey, String bookId) {
        if (apiKey == null || apiKey.trim().length() == 0) return null;
        if (bookId == null || bookId.length() == 0) return null;
        try {
            JSONObject args = new JSONObject();
            args.put("bookId", bookId);
            WereadApi.Resp r = WereadApi.raw(apiKey.trim(),
                    WereadApi.buildBody("/book/getprogress", args));
            if (r.error != null || r.json == null) return null;
            JSONObject book = r.json.optJSONObject("book");
            if (book == null) return null;
            int p = book.optInt("progress", -1);
            return (p >= 0 && p <= 100) ? Integer.valueOf(p) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ══════════════════════ 小工具 ══════════════════════

    /** 时长降序（价 = 时长派生 ⇒ 同时就是"价"降序，与菜单"主厨在前"的观感一致）。 */
    private static final Comparator<PeriodStats.Longest> BY_TIME_DESC =
            new Comparator<PeriodStats.Longest>() {
                @Override
                public int compare(PeriodStats.Longest a, PeriodStats.Longest b) {
                    return b.readTime - a.readTime;
                }
            };

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
