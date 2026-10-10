package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 🆕 `TASK-086` §2：**期界阅读进度快照**（独立 prefs 文件 `menu_progress`）。
 *
 * <h3>它解决什么</h3>
 * 「实付」的**算法 A（本期增额）**要的是
 * {@code 书价 × clamp(期末进度 − 期初进度, 0, 100) ÷ 100} ——
 * 期末进度 = 生成这一期账单时抓到的那次；**期初进度 = 上一期结束时的进度**，
 * 而"上一期结束时"的进度**没有任何接口能事后补查**（`/book/getprogress` 只给"现在"）。
 * 所以只能在每期生成时**顺手把当期的进度记下来**，供下一期当期初基线用 —— 这就是本类。
 *
 * <h3>键值口径</h3>
 * <pre>
 *   键 = bookId
 *   值 = "&lt;periodStart&gt;:&lt;pct&gt;"     例："1759622400:34"
 * </pre>
 * 🔴 一本书只留**最近一次**快照（不累积历史）：算法 A 只问"上一期结束时的进度"，
 * 更早的快照用不上；留一份能把 prefs 体积压到常量级（228 本 ≈ 几 KB）。
 *
 * <h3>🔴 如实登记的精度边界</h3>
 * 只有在**该期刚结束时**（= 生成时机落在这个期的下一个周期内）抓到的进度才是真正的"期末进度"。
 * 若用户三周没开 App、一次补齐 4 期，则更早那几期填进来的其实是"现在"的进度
 * ⇒ {@link Bill.Item#progressEndKnown} 会被判 {@code false}，算法 A 对这些期**显示 `—`**
 * （宁可留白也不编一个假数）。见 `验证记录/200` §如实登记。
 */
public final class ProgressLog {

    private static final String PREFS = "menu_progress";

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
            sp(c).edit().putString(bookId, periodStart + ":" + pct).commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 取「{@code periodStart} 这一期结束时该书进度」。
     *
     * @return {@code 0..100}；该期没有快照 / 存的不是这一期 ⇒ {@code -1}
     */
    public static int at(Context c, String bookId, long periodStart) {
        if (c == null || bookId == null || bookId.length() == 0 || periodStart <= 0L) return -1;
        try {
            String v = sp(c).getString(bookId, null);
            if (v == null) return -1;
            int p = v.indexOf(':');
            if (p <= 0) return -1;
            long ps = Long.parseLong(v.substring(0, p));
            if (ps != periodStart) return -1;               // 🔴 期不匹配 ⇒ 当作没有基线
            int pct = Integer.parseInt(v.substring(p + 1));
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
