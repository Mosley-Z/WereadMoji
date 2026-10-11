package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 🆕 `TASK-086` §2：**期界阅读进度快照**（独立 prefs 文件 `menu_progress`）。
 *
 * <h3>它解决什么</h3>
 * 「收入」的**算法 A（本期增额）**要的是
 * {@code 书价 × clamp(期末进度 − 期初进度, 0, 100) ÷ 100} ——
 * 期末进度 = 生成这一期账单时抓到的那次；**期初进度 = 上一期结束时的进度**，
 * 而"上一期结束时"的进度**没有任何接口能事后补查**（`/book/getprogress` 只给"现在"）。
 * 所以只能在每期生成时**顺手把当期的进度记下来**，供下一期当期初基线用 —— 这就是本类。
 *
 * <h3>键值口径</h3>
 * <pre>
 *   键 = bookId
 *   值 = "&lt;periodStart&gt;:&lt;pct&gt;:g1"     例："1759622400:34:g1"
 * </pre>
 * 🔴 一本书只留**最近一次**快照（不累积历史）：算法 A 只问"上一期结束时的进度"，
 * 更早的快照用不上；留一份能把 prefs 体积压到常量级（228 本 ≈ 几 KB）。
 *
 * <h3>🔴 代际戳 `g1`（第七批 W1 · task-71，2026-10-12）</h3>
 * 本类在 2026-10-10 首次落盘时**没有** `endKnown` 门（`BillScheduler` 当时无条件 `put`），
 * 于是"补更早的期"时把**当时的"现在"**当期末写了进去 —— 存量 24 条里混着这种**合成值**，
 * 而 `BillEstimator.isTrueEnd` / `markEndTrust` 把"有条目"当成"有期界快照"，
 * 从而把合成值当真期末用（《演员自我修养》2026-06 期末 96 ⇒ 2026-07 增额 `¥0` 的根因）。
 * <p>修法 = 给**本代码代**写入的值打上 `:g1` 戳，{@link #at} **只认带戳的条目**：
 * 存量旧格式条目**自然降级**为"没有快照"（返回 {@code -1}），无需删除文件、无需数据迁移；
 * 旧的合成条目就此不再冒充真期末，历史期显示 `—` / `≈`（宁可留白也不编数）。
 *
 * <h3>🔴 如实登记的精度边界（不随 `g1` 消失）</h3>
 * 只有在**该期刚结束时**（= 生成时机落在这个期的下一个周期内）抓到的进度才是真正的"期末进度"；
 * `BillScheduler` 的 `endKnown` 门保证只在这种情况下 `put`。若用户三周没开 App、一次补齐 4 期，
 * 更早那几期**不会被写入**（本类里根本没有那些期的条目），算法 A 对这些期显示 `—`。
 * 见 `验证记录/200` §如实登记。
 */
public final class ProgressLog {

    private static final String PREFS = "menu_progress";

    /**
     * 代际戳（写在值末尾的第三段）。🔴 语义 = "这条快照是**加了 {@code endKnown} 门之后**的代码写的"，
     * 只有这种条目才可能是真的期界快照。**不要**在没想清楚的情况下改这个字面量：
     * 改它等于把当前全部存量条目再降级一次。
     */
    private static final String GEN = "g1";

    private ProgressLog() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 记下「{@code periodStart} 这一期结束时，这本书的进度是 {@code pct}%」。 */
    public static void put(Context c, String bookId, long periodStart, int pct) {
        if (c == null || bookId == null || bookId.length() == 0) return;
        if (periodStart <= 0L || pct < 0 || pct > 100) return;
        try {
            sp(c).edit().putString(bookId, periodStart + ":" + pct + ":" + GEN).commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 取「{@code periodStart} 这一期结束时该书进度」。
     *
     * <p>🔴 **只认带代际戳的条目**（`"&lt;periodStart&gt;:&lt;pct&gt;:g1"`）：
     * 旧两段格式（2026-10-10 那版无门代码写的合成值）一律 ⇒ {@code -1}，等价于"这一期没有快照"。
     *
     * @return {@code 0..100}；该期没有（可信的）快照 / 存的不是这一期 ⇒ {@code -1}
     */
    public static int at(Context c, String bookId, long periodStart) {
        if (c == null || bookId == null || bookId.length() == 0 || periodStart <= 0L) return -1;
        try {
            String v = sp(c).getString(bookId, null);
            if (v == null) return -1;
            int p1 = v.indexOf(':');
            if (p1 <= 0) return -1;
            int p2 = v.indexOf(':', p1 + 1);
            if (p2 <= p1 + 1) return -1;
            if (!GEN.equals(v.substring(p2 + 1))) return -1;    // 🔴 旧格式 / 别代写法 ⇒ 没有快照
            long ps = Long.parseLong(v.substring(0, p1));
            if (ps != periodStart) return -1;                   // 🔴 期不匹配 ⇒ 当作没有基线
            int pct = Integer.parseInt(v.substring(p1 + 1, p2));
            return (pct >= 0 && pct <= 100) ? pct : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 记了几本（验证留痕用）。 */
    public static int count(Context c) {
        if (c == null) return 0;
        try {
            return sp(c).getAll().size();
        } catch (Throwable t) {
            return 0;
        }
    }
}
