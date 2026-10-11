package com.inkread.weekread.core;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🆕 `TASK-086` 第三批（2026-10-11 · 方案文档 `§20.4` + `§20.10` 定稿）：**估算点回填器**
 * —— 把"没有真快照的历史期"补成**估算值**（锚点法），让实付的两种算法在历史期也**算得出来**。
 *
 * <h3>它解决什么</h3>
 * 实付依赖"每本书**每期期末**的累计进度"这条线 `Cum(book, period)`（`§20.1` 统一模型）：
 * <pre>
 *   算法 A · 增额(k) = 价 × clamp(Cum(k) − Cum(k−1), 0, 100) ÷ 100
 *   算法 B · 累计(k) = 价 × Cum(k) ÷ 100
 * </pre>
 * 而"历史期的期末进度"**事后无法补查**（`/book/getprogress` 只给"现在"）⇒ 历史期 A/B 全是 `—`
 * （正是 `验证记录/208` 诊断出的症状根因）。本类把缺的那几期**估算**出来。
 *
 * <h3>锚点法（`§20.4`，方案文档用真机数据验证过）</h3>
 * <pre>
 *   CumEst(b, k) = Cum(b, m) × Σ_{j≤k} R_j(b) ÷ Σ_{j≤m} R_j(b)
 * </pre>
 * <ul>
 *   <li>`m` = **离 k 最近的有真快照的期**（本实现取 k 之后**最早**的精确期，通常 = 启用期）；
 *       `Cum(b, m)` 就是那一期账单里已落盘的 `progressEndPct`。</li>
 *   <li>`R_j(b)` = 第 j 期这本书的阅读时长（秒）—— 🔴 **就是历史账单里的
 *       {@link Bill.Item#readTimeSec}** ⇒ **零新存储、零新增请求**（`§20.8` 落法：
 *       优先复用 {@link BillStore} 的历史账单，每个 mode 留 12 期）。</li>
 *   <li>某期这本书**没进榜** ⇒ 该期 `R_j = 0` ⇒ 曲线**平台**（**不编数**，如实登记为边界）。</li>
 *   <li>归一化 ⇒ `k = m` 时 `CumEst = Cum(m)` 严丝合缝；**各期增额之和 = 末期累计（A15 自洽）由
 *       {@link #rebuild} 保证**：`fill` 是"一期一期就地估算"（当期先用旧窗口估、后期用新窗口估 ⇒
 *       数列不 telescope），`rebuild` 在同一趟里用同一锚点把全窗口一次写定、并把**期初链到上一期期末**
 *       ⇒ 逐期增额必然首尾相消。</li>
 * </ul>
 *
 * <h3>三条红线</h3>
 * <ol>
 *   <li>🔴 <b>渲染零请求</b>：本类只在 {@link BillScheduler} 的**生成期后台线程**里跑，结果
 *       **落进账单**（`progressEndPct` / `progressStartPct` + 两个 `*Est` 标记）⇒ 渲染侧仍是纯算术。</li>
 *   <li>🔴 <b>不编数</b>：一个锚点都找不到 / 分母为 0 / 本期在锚点之后（= 外推）⇒ **不动**该行
 *       （保持 `-1` ⇒ 该期如实显示 `—`）。</li>
 *   <li>🔴 <b>精确值永不漂移</b>（`§20.5` / A16）：`fill` 只在字段**为空**时填，已精确的值绝不覆盖；
 *       **但估算值会被 {@link #rebuild} 按同一锚点基准刷新**（2026-10-11 S4 实测：一期期独立估算
 *       出来的数列不 telescope ⇒ `Σ增额 ≠ 末期累计`，A15 不成立）⇒ "同点不漂移"**收窄为
 *       "精确点不漂移"**，估算点的含义 = "以当前锚点为基准的近似值"，锚点变好就该变准。</li>
 * </ol>
 */
public final class BillEstimator {

    private BillEstimator() {
    }

    /**
     * 就地回填一期账单里**缺进度**的行（写估算值 + 估算标记）。
     *
     * @return 本次改动的行数（留痕用；{@code 0} = 无需估算 / 无从估算）
     */
    public static int fill(Context c, Bill b) {
        if (c == null || b == null || b.items == null || b.items.isEmpty()) return 0;

        // ① 窗口（升序：早 → 晚）= 该 mode **已落盘**的全部期 + 本期。
        //    🔴 为什么用"已落盘的期"而不是写死 12 期：估算只依赖**手头真有的** `R_j`
        //       （历史账单里的 `readTimeSec`），缺的期按 0 计 ⇒ 少几期只是精度粗一点，不会编数。
        List<Long> win = new ArrayList<Long>(BillStore.startsOf(c, b.mode));
        Long self = Long.valueOf(b.periodStart);
        if (!win.contains(self)) win.add(self);
        Collections.sort(win);

        // ② 窗口内每一期的账单（本期用调用方传进来的 b —— 它**还没落盘**）
        Map<Long, Bill> byPeriod = new HashMap<Long, Bill>();
        for (int i = 0; i < win.size(); i++) {
            long p = win.get(i).longValue();
            Bill x = (p == b.periodStart) ? b : BillStore.load(c, b.mode, p);
            if (x != null && x.items != null && !x.items.isEmpty()) byPeriod.put(Long.valueOf(p), x);
        }

        int rows = 0;
        for (int i = 0; i < b.items.size(); i++) {
            Bill.Item it = b.items.get(i);
            if (it == null || it.bookId == null || it.bookId.length() == 0) continue;

            // ③ 锚点 m：k 之后**最早**的精确期（"精确" = 有值 **且** 不是估算）
            long anchorP = -1L;
            int anchorPct = -1;
            for (int w = 0; w < win.size(); w++) {
                long p = win.get(w).longValue();
                if (p < b.periodStart) continue;
                Bill.Item x = itemOf(byPeriod, p, it.bookId);
                if (x != null && x.progressEndPct >= 0 && !x.progressEndEst) {
                    anchorP = p;
                    anchorPct = x.progressEndPct;
                    break;
                }
            }
            // 兜底（`§20.4` 末段）：一帧真快照都没有 ⇒ 锚定"**现在**的累计" —— 取窗口里**最新**一个
            //   带 `progressPct`（= 生成那期时抓到的"现在"）的同期行。仍取不到 ⇒ 不编数。
            if (anchorP < 0) {
                for (int w = win.size() - 1; w >= 0; w--) {
                    long p = win.get(w).longValue();
                    Bill.Item x = itemOf(byPeriod, p, it.bookId);
                    if (x != null && x.progressPct >= 0) {
                        anchorP = p;
                        anchorPct = x.progressPct;
                        break;
                    }
                }
            }
            if (anchorP < 0 || anchorPct < 0) continue;     // 没有锚点 ⇒ 不编数
            if (b.periodStart > anchorP) continue;         // 本期在锚点**之后** ⇒ 那是外推，不做

            // ④ 三个和：分母 Σ_{j≤m}、分子 Σ_{j≤k}、期初 Σ_{j<k}
            long den = 0L, num = 0L, numPrev = 0L;
            long prevP = -1L;
            for (int w = 0; w < win.size(); w++) {
                long p = win.get(w).longValue();
                if (p > anchorP) break;
                den += rOf(byPeriod, p, it.bookId);
            }
            if (den <= 0L) continue;                       // 分母 0 ⇒ 无从分摊（不编数）
            for (int w = 0; w < win.size(); w++) {
                long p = win.get(w).longValue();
                if (p > b.periodStart) break;
                num += rOf(byPeriod, p, it.bookId);
            }
            for (int w = 0; w < win.size(); w++) {
                long p = win.get(w).longValue();
                if (p >= b.periodStart) break;
                numPrev += rOf(byPeriod, p, it.bookId);
                prevP = p;
            }

            int changed = 0;
            // ⑤ 期末：**只在没有值**时填
            //    （已精确 ⇒ 不覆盖 = A16「升级后不回退」；已估算 ⇒ 不重算 = 同点不漂移）
            if (it.progressEndPct < 0) {
                it.progressEndPct = share(anchorPct, num, den);
                it.progressEndEst = true;
                changed++;
            }
            // ⑥ 期初：同理（算法 A 要的"上一期期末"）
            if (it.progressStartPct < 0) {
                int st = (prevP > 0L) ? share(anchorPct, numPrev, den) : 0;
                if (it.progressEndPct >= 0 && st > it.progressEndPct) st = it.progressEndPct;  // 不倒扣
                it.progressStartPct = st;
                it.progressStartEst = true;
                changed++;
            }
            if (changed > 0) rows++;
        }
        if (rows > 0) {
            CardDebug.noteV(c, "bill estimate: " + b.mode + " " + b.periodStart
                    + " 估算行=" + rows + "/" + b.items.size());
        }
        return rows;
    }

    /**
     * 把**整个 mode** 的估算点重算成一套**自洽**的曲线（A15：同一本书各期增额之和 = 末期累计）。
     *
     * <p>🔴 为什么 `fill` 不够（2026-10-11 S4 实测发现的真缺陷）：`fill` 只在"生成某一期"时就地估算
     * 那一期，而各期的**锚点与分母**会随数据增长而变（先进来的期用旧窗口估、后进来的期用新窗口估）
     * ⇒ 一期期独立写定的数列**不 telescope**：实测《卡拉马佐夫兄弟》Σ各期增额 = ¥98 ≠ 末期累计 ¥33.32。
     * 本方法在**同一趟**里用**同一个锚点基准**把全窗口的估算值一次写定，于是
     * `Σ_k (Cum(k) − Cum(k−1)) = Cum(末) − Cum(窗首之前)` 恒成立（窗首期初按 0 处理）⇒ A15 成立。
     *
     * <p>🔴 只改**估算值**：`progressEndPct >= 0 && !progressEndEst` 的行是**真快照** ⇒ 一律不动（A16
     * 升级不回退）；它同时参与"锚点"与"下一期的链式期初"（真值当然可以当起点）。
     * 反过来，**已估算值会被刷新**（锚点变好了就该变准）—— 这是本批对"同点不漂移"的**收窄**：
     * 精确值永不改写，估算值可随锚点刷新（否则 A15 无法成立）。每次改动都留
     * {@code bill estimate rebuild:} 日志。
     *
     * <p>成本：纯本地（窗口 ≤ 13 期 × 每期 ≤ 7 行）、**零请求**、每 mode 一次
     * {@link BillStore#save} 级别的写入；由 {@code BillScheduler.runEnsure} 在每轮后台刷新末尾调用。
     *
     * @return 被改写的**期数**（留痕用）
     */
    public static int rebuild(Context c, String mode) {
        if (c == null || mode == null) return 0;
        List<Long> win = new ArrayList<Long>(BillStore.startsOf(c, mode));
        if (win.isEmpty()) return 0;
        Collections.sort(win);

        Map<Long, Bill> byPeriod = new HashMap<Long, Bill>();
        for (int w = 0; w < win.size(); w++) {
            long p = win.get(w).longValue();
            Bill x = BillStore.load(c, mode, p);
            if (x != null && x.items != null && !x.items.isEmpty()) byPeriod.put(Long.valueOf(p), x);
        }
        if (byPeriod.isEmpty()) return 0;

        List<Long> dirty = new ArrayList<Long>();
        Map<String, Boolean> seen = new HashMap<String, Boolean>();
        for (int w = 0; w < win.size(); w++) {
            long pk = win.get(w).longValue();
            Bill pb = byPeriod.get(Long.valueOf(pk));
            if (pb == null) continue;
            for (int j = 0; j < pb.items.size(); j++) {
                Bill.Item seed = pb.items.get(j);
                if (seed == null || seed.bookId == null || seed.bookId.length() == 0) continue;
                final String bid = seed.bookId;
                if (seen.containsKey(bid)) continue;         // 每本书只算一次
                seen.put(bid, Boolean.TRUE);

                // ① 锚点 m = 这本书**最新的真快照**（精确期末）；一个都没有 ⇒ 退到"最新带 progressPct 的行"
                long anchorP = -1L;
                int anchorPct = -1;
                for (int v = win.size() - 1; v >= 0; v--) {
                    long p = win.get(v).longValue();
                    Bill.Item x = itemOf(byPeriod, p, bid);
                    if (x != null && x.progressEndPct >= 0 && !x.progressEndEst) {
                        anchorP = p;
                        anchorPct = x.progressEndPct;
                        break;
                    }
                }
                if (anchorP < 0) {
                    for (int v = win.size() - 1; v >= 0; v--) {
                        long p = win.get(v).longValue();
                        Bill.Item x = itemOf(byPeriod, p, bid);
                        if (x != null && x.progressPct >= 0) {
                            anchorP = p;
                            anchorPct = x.progressPct;
                            break;
                        }
                    }
                }
                if (anchorP < 0 || anchorPct < 0) continue;    // 没锚点 ⇒ 不编数（该期如实留 `—`）

                // ② 分母 Σ_{j≤m}（这本书的时长；没进榜的期记 0）
                long den = 0L;
                for (int v = 0; v < win.size(); v++) {
                    long p = win.get(v).longValue();
                    if (p > anchorP) break;
                    den += rOf(byPeriod, p, bid);
                }
                if (den <= 0L) continue;                       // 分母 0 ⇒ 无从分摊（不编数）

                // ③ 从最早往最晚走：期末 = 同一锚点基准下的 `CumEst(k)`；期初 = **上一期的期末**（链式）
                long num = 0L;
                int prevEnd = -1;
                boolean prevEndEst = false;
                for (int v = 0; v < win.size(); v++) {
                    long p = win.get(v).longValue();
                    if (p > anchorP) break;                    // 锚点之后 = 外推，不做
                    num += rOf(byPeriod, p, bid);
                    Bill.Item x = itemOf(byPeriod, p, bid);
                    if (x == null) continue;                   // 这本书这期没进榜 ⇒ 曲线平台（期初沿用 prevEnd）
                    boolean rowDirty = false;
                    if (x.progressEndPct < 0 || x.progressEndEst) {              // 精确值不动（A16）
                        int ev = share(anchorPct, num, den);
                        if (ev >= 0 && (ev != x.progressEndPct || !x.progressEndEst)) {
                            x.progressEndPct = ev;
                            x.progressEndEst = true;
                            rowDirty = true;
                        }
                    }
                    if (x.progressStartPct < 0 || x.progressStartEst) {
                        int sv = (prevEnd >= 0) ? prevEnd : 0;
                        if (x.progressEndPct >= 0 && sv > x.progressEndPct) sv = x.progressEndPct;   // 不倒扣
                        boolean est = (prevEnd >= 0) && prevEndEst;
                        if (sv != x.progressStartPct || est != x.progressStartEst) {
                            x.progressStartPct = sv;
                            x.progressStartEst = est;
                            rowDirty = true;
                        }
                    }
                    if (rowDirty && !dirty.contains(Long.valueOf(p))) dirty.add(Long.valueOf(p));
                    prevEnd = x.progressEndPct;
                    prevEndEst = x.progressEndEst;
                }
            }
        }

        for (int i = 0; i < dirty.size(); i++) {
            Bill x = byPeriod.get(dirty.get(i));
            if (x != null) BillStore.save(c, x);
        }
        if (!dirty.isEmpty()) {
            CardDebug.noteV(c, "bill estimate rebuild: " + mode + " 改写期数=" + dirty.size() + "/" + win.size());
        }
        return dirty.size();
    }

    /**
     * `锚点累计 × num ÷ den` —— **整数半进位**（与 {@link BillMoney#paidFen} 的取整口径一致），
     * 并 clamp 到 `0..100`。分母为 0 ⇒ {@code -1}（防御；调用点已先判）。
     */
    private static int share(int anchorPct, long num, long den) {
        if (den <= 0L) return -1;
        long v = ((long) anchorPct * num + den / 2L) / den;
        if (v < 0L) v = 0L;
        if (v > 100L) v = 100L;
        return (int) v;
    }

    /** 窗口里某一期这本书的那一行；没有（没进榜 / 该期账单缺失）⇒ null。 */
    private static Bill.Item itemOf(Map<Long, Bill> byPeriod, long periodStart, String bookId) {
        Bill x = byPeriod.get(Long.valueOf(periodStart));
        if (x == null || x.items == null) return null;
        for (int i = 0; i < x.items.size(); i++) {
            Bill.Item it = x.items.get(i);
            if (it == null) continue;
            String bid = it.bookId == null ? "" : it.bookId;
            if (bookId.equals(bid)) return it;
        }
        return null;
    }

    /** 某一期这本书的阅读时长（秒）；没进榜 / 缺期 ⇒ {@code 0}（= 那一期没读，如实按 0 分摊）。 */
    private static int rOf(Map<Long, Bill> byPeriod, long periodStart, String bookId) {
        Bill.Item x = itemOf(byPeriod, periodStart, bookId);
        if (x == null) return 0;
        return x.readTimeSec > 0 ? x.readTimeSec : 0;
    }
}
