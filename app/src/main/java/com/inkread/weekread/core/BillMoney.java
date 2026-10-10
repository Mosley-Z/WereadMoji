package com.inkread.weekread.core;

import android.content.Context;

import java.util.List;
import java.util.Locale;

/**
 * 🆕 `TASK-086`：**墨单的钱**（原价解析 / 实付两算法 / 合计 / 金额格式化）—— 全部是**渲染期算术**。
 *
 * <h3>三条红线</h3>
 * <ol>
 *   <li>🔴 <b>零请求</b>：本类只读「生成期已落盘的 {@link Bill.Item#marketPriceFen}」+
 *       「用户手填的 {@link MenuPrice}」+「已落盘的 {@link Bill.Item#progressPct}」，
 *       一个网络调用都没有 ⇒ 切算法**不重生成账单**（A8）。</li>
 *   <li>🔴 <b>「0 元 ≠ 有价」</b>（借 huibenlema `resetWereadZeroPrice` 的教训）：
 *       三态值 {@code -1 未知 / 0 免费 / >0 有价} 全程用 {@code int} 承载，
 *       绝不把"未知"写成 0 —— 否则合计会被 0 一路拉低。</li>
 *   <li>🔴 <b>单位只有一种：分</b>。元只在最后格式化的那一刻出现（{@link #yuan(int)}）。</li>
 * </ol>
 *
 * <p>🔴 一处**对卡面的解读**（已在 `验证记录/200` 登记）：免费判据取 <b>{@code free==1}</b>，
 * 而**不采**「{@code centPrice==0} 即免费」—— 因为自导入书 / 网文的字段同样是全 0，
 * 按后者会把它们误标成「免费」（而卡面 §4 要求它们显示 `—`）。
 */
public final class BillMoney {

    /** 未知（显示 `—`、不计入合计）。🔴 与「免费 = 0」严格区分。 */
    public static final int UNKNOWN = -1;

    /** 免费。 */
    public static final int FREE = 0;

    private BillMoney() {
    }

    // ══════════════════════ ① 原价解析（生成期 / 索引用） ══════════════════════

    /**
     * 价格解析口径（照 huibenlema `OfficialGatewayClient.parsePriceFen`）：
     * <pre>
     *   fen = max( centPrice(分), originalPrice(元)×100, price(元)×100 )
     *   fen &gt; 0        ⇒ 有价
     *   fen == 0 &amp;&amp; free ⇒ 免费（0）
     *   否则             ⇒ 未知（-1）
     * </pre>
     * 🔴 `originalPrice` 实测常为 0（取 max 正是为了兼容它偶尔带值的情形）。
     */
    public static int fenOf(int centPrice, double priceYuan, int origYuan, boolean free) {
        long a = centPrice;
        long b = Math.round(priceYuan * 100.0);
        long d = (long) origYuan * 100L;
        long best = Math.max(a, Math.max(b, d));
        if (best > 0L) return (int) Math.min(best, Integer.MAX_VALUE);
        return free ? FREE : UNKNOWN;
    }

    // ══════════════════════ ② 生效价（手动价优先） ══════════════════════

    /**
     * 这一行**最终生效**的原价（分）。
     * <pre>
     *   ① 用户手填（{@link MenuPrice}）—— 🔴 **优先级最高，重同步不覆盖**
     *   ② 生成期落盘的市场价（{@link Bill.Item#marketPriceFen}）
     * </pre>
     */
    public static int effFen(Context c, Bill.Item it) {
        if (it == null) return UNKNOWN;
        int manual = MenuPrice.fen(c, it.bookId);
        if (manual >= 0) return manual;
        return it.marketPriceFen;
    }

    // ══════════════════════ ③ 实付（两算法） ══════════════════════

    /**
     * 「实付」列（分）。
     *
     * <pre>
     *   算法 A · 本期增额（默认）  = 价 × clamp(期末 − 期初, 0, 100) ÷ 100
     *   算法 B · 累计进度          = 价 × **该期期末累计** ÷ 100
     *   关闭                       = 不出这一列（本方法返回 UNKNOWN）
     * </pre>
     *
     * <p>🆕 task-13（2026-10-11）：算法 B 的进度**不再取 {@link Bill.Item#progressPct}**（那是"期末/
     * <b>当前</b>"语义，补旧期时写进的是"现在"，于是每期都显示同一个数 —— 用户报的问题），
     * 改用生成期解析好的 {@link Bill.Item#progressEndPct}（**该期自己的**期末累计）。
     * 🔴 算法 A 的算式与判据**一字未动**（task-15 才诊断它）。
     *
     * @return {@code >= 0} 实付（分）；{@link #UNKNOWN} ⇒ 该行显示 `—` 且**不计入合计**
     */
    public static int paidFen(Context c, Bill.Item it, String algo) {
        if (it == null || algo == null) return UNKNOWN;
        if (MenuPrefs.PAID_OFF.equals(algo)) return UNKNOWN;

        final int price = effFen(c, it);
        if (price <= 0) return UNKNOWN;                     // 免费 / 未知 ⇒ 实付一律 —

        final int end = it.progressPct;
        final boolean cumulative = MenuPrefs.PAID_CUMULATIVE.equals(algo);

        int delta;
        if (cumulative) {
            // 🆕 task-13：累计 = **所选周期末**的累计
            int ep = it.progressEndPct;
            if (ep < 0 && it.progressEndKnown) ep = end;     // 老账回落（见下方注释与 `验证记录/207`）
            if (ep < 0) return UNKNOWN;                     // 该期期末取不到 ⇒ 如实留白，不编数
            delta = ep;
        } else {
            if (end < 0) return UNKNOWN;                    // 进度未知 ⇒ 算不出来
            // 算法 A：首期无基线（progressStartPct < 0）或期末不可信 ⇒ 如实留白
            if (!it.progressEndKnown || it.progressStartPct < 0) return UNKNOWN;
            delta = end - it.progressStartPct;
            if (delta < 0) delta = 0;                       // 重读致进度回落 ⇒ clamp(≥0)，不倒扣
        }
        if (delta > 100) delta = 100;
        // 🆕 TASK-086 拍板③（2026-10-10）：**整数半进位（四舍五入）**，替代原来的整数截断。
        //   🔴 用整数运算 + 50 而不是 Math.round(double)：double 走一遍二进制浮点会引入
        //      边界误差（如 0.5 恰好落在浮点不可表示处），整数式 `(a + 50) / 100` 是精确的。
        //   自证：9799 × 34% = 3331.66 分 ⇒ 截断 3331（¥33.31）→ 半进位 **3332（¥33.32）**，
        //   与 `tasks/TASK-086` §表头布局的举例值 `¥33.32` 对齐（拍板文档 §3-3）。
        //   ⚠️ 只改取整规则；`delta == 0` ⇒ 0（免费行不显示）；`delta > 0` 且价 `> 0` ⇒ 结果 ≥ 1。
        //   🔴 `totalPaidFen` 逐行累加本方法结果 ⇒ 合计自动同步，无需另改。
        return (int) (((long) price * (long) delta + 50L) / 100L);
    }

    /** 合计实付（分）—— 只累加**算得出来**的行（无价 / 无进度 / 首期无基线的行不进合计）。 */
    public static long totalPaidFen(Context c, List<Bill.Item> items, String algo) {
        if (items == null) return 0L;
        long sum = 0L;
        for (int i = 0; i < items.size(); i++) {
            int v = paidFen(c, items.get(i), algo);
            if (v > 0) sum += v;
        }
        return sum;
    }

    /**
     * 合计里有几行**真的算得出来**（{@link #paidFen} ≠ {@link #UNKNOWN}）。
     *
     * <p>🆕 TASK-086 上机实测补：合计行的「读回 / 折合」两段**只在 n &gt; 0 时**才写。
     * 一行都算不出来（老账无 `marketPriceFen` / 算法 A 首期无基线 / 全是免费书）时，
     * 打印「读回 ¥0，折合 ¥0/小时」是**噪声且会误导**（读上去像"一分没读回来"），
     * 此时合计如实退化为**只说时长**（与 `tasks/TASK-086` §老账兼容的括注一致）。
     */
    public static int paidCount(Context c, List<Bill.Item> items, String algo) {
        if (items == null) return 0;
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            if (paidFen(c, items.get(i), algo) != UNKNOWN) n++;
        }
        return n;
    }

    /** 有价（含免费）行数 / 全部行数 —— 表尾如实说明覆盖率用。 */
    public static int pricedCount(Context c, List<Bill.Item> items) {
        if (items == null) return 0;
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            if (effFen(c, items.get(i)) >= 0) n++;
        }
        return n;
    }

    // ══════════════════════ ④ 格式化 ══════════════════════

    /** 分 → `¥97.99` / 整元 → `¥34`（借 huibenlema A3：整元省略小数）。 */
    public static String yuan(int fen) {
        if (fen == UNKNOWN) return "—";
        if (fen < 0) fen = 0;
        if (fen % 100 == 0) return "¥" + (fen / 100);
        return String.format(Locale.US, "¥%.2f", fen / 100.0);
    }

    /** 分 → `¥1,234.5`? —— 本项目不做千分位（墨屏等宽字体下省略号更值钱），保留 {@link #yuan(int)} 口径。 */
    public static String yuan(long fen) {
        return yuan((int) Math.min(fen, Integer.MAX_VALUE));
    }

    /**
     * 「原价」列的文案：有价 ⇒ `¥97.99`；免费 ⇒ `免费`；未知 ⇒ `—`。
     * 🔴 免费与未知**必须显示成不同的东西**（A7）。
     */
    public static String priceText(Context c, Bill.Item it) {
        int fen = effFen(c, it);
        if (fen == UNKNOWN) return "—";
        if (fen == FREE) return "免费";
        return yuan(fen);
    }

    /** 「实付」列的文案：与 {@link #paidFen} 同源。 */
    public static String paidText(Context c, Bill.Item it, String algo) {
        int fen = paidFen(c, it, algo);
        return (fen == UNKNOWN) ? "—" : yuan(fen);
    }

    /** 秒 → `16.9`（合计行的「H 小时」，保留 1 位小数；0 / 负 ⇒ `0`）。 */
    public static String hoursText(int totalSec) {
        if (totalSec <= 0) return "0";
        return String.format(Locale.US, "%.1f", totalSec / 3600.0);
    }
}
