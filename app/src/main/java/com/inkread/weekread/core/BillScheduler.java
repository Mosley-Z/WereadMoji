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
            if (BillStore.has(c, mode, ps)) break;                 // 已有 ⇒ 更早的期也已处理（连续性）
            long end = periodEnd(mode, ps);
            if (nowSec < end) continue;                            // 该期尚未结束（正常到不了这里）
            if (!generateOne(c, apiKey, mode, ps)) break;          // 未能决定 ⇒ 本轮停止，下次重试
        }
    }

    /** 周期结束时刻（秒，不含）—— 起点 + 周期天数 × 86400。 */
    private static long periodEnd(String mode, long periodStart) {
        return periodStart + (long) PeriodRange.daysInPeriod(mode, periodStart) * 86400L;
    }

    /**
     * 生成**一期**账单。
     *
     * @return true = **本期已决定**（落盘了真实账 / 占位账，或按用户配置"不生成"）；
     *         false = **未能决定**（数据拿不到）⇒ 调用方应停止本轮并等下次重试
     */
    private static boolean generateOne(Context c, String apiKey, String mode, long periodStart) {
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
        boolean wantHot = !MenuPrefs.EX_MINE.equals(exMode);
        boolean wantProgress = MenuPrefs.showProgress(c);
        long endSec = periodEnd(mode, periodStart) - 1L;

        // 🆕 TASK-086：**实付**要用进度（算法 A 要期初+期末、算法 B 要期末）⇒
        //    实付列没关就必须逐本抓进度。接口仍是既有的 `/book/getprogress`（**零新增接口**），
        //    代价是"补齐一期"多 Top-N 次请求（N ≤ 8）—— 一期只发生一次，可接受。
        final boolean needProgress = wantProgress
                || !MenuPrefs.PAID_OFF.equals(MenuPrefs.paidAlgo(c));

        // 🆕 TASK-086：本期是不是"刚刚结束的那一期" —— 只有它抓到的进度才等于**本期期末进度**。
        //    补更早的期时（三周没开 App）抓到的其实是"现在"，不能冒充期末 ⇒ progressEndKnown=false。
        final long curStart = PeriodRange.startOf(mode, PeriodRange.nowSec());
        final boolean endKnown = (periodStart == PeriodRange.shift(mode, curStart, -1));
        final long prevStart = PeriodRange.shift(mode, periodStart, -1);

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
        BillStore.save(c, b);
        CardDebug.noteV(c, "bill: 生成 " + mode + " " + periodStart + " 本数=" + b.items.size()
                + " 有价=" + BillMoney.pricedCount(c, b.items) + "/" + b.items.size()
                + " 实付合计=" + BillMoney.yuan(BillMoney.totalPaidFen(c, b.items, MenuPrefs.paidAlgo(c)))
                + (endKnown ? "" : " (补旧期·期末进度不可信)")
                + (b.placeholder ? " (占位)" : ""));
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
