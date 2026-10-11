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
     * 🔴 算法 A 的算式与判据**一字未动**（task-15 才诊断它）—— 🆕 **task-41（2026-10-11）打破了这一条**：
     * 算法 A 的**期末取值与判据改为与算法 B 同源**（都取 {@code progressEndPct}、都只要求"这个值存在"），
     * 详见下方 `else` 分支内的说明；task-15~task-40 期间"一字未动"是那时的实况。
     *
     * @return {@code >= 0} 实付（分）；{@link #UNKNOWN} ⇒ 该行显示 `—` 且**不计入合计**
     */
    public static int paidFen(Context c, Bill.Item it, String algo) {
        if (it == null || algo == null) return UNKNOWN;
        if (MenuPrefs.PAID_OFF.equals(algo)) return UNKNOWN;

        final int price = effFen(c, it);
        // 🆕 TASK-086 第三批 8-b（2026-10-11 用户拍板原文「改 >= 0，免费实付应为 0」）：
        //   `UNKNOWN`（未知价 ⇒ 显示 `—`）与 `FREE`（免费 ⇒ 显示 `¥0`）必须**分开**处理 ——
        //   免费书的实付是**确定的 ¥0**，不需要进度也成立，故在下面的进度判据**之前**短路返回。
        if (price == UNKNOWN) return UNKNOWN;               // 未知价 ⇒ 实付仍 `—`（不计合计）
        if (price == FREE) return FREE;                     // 免费 ⇒ 实付 `¥0`（先于进度判据短路）

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
            // 🆕 TASK-086 第三批（`§20.10`）：**估算期**（该期没有真快照）改用"该期自己的累计线"两端 ——
            //   期末 = `progressEndPct`（估算值）、期初 = `progressStartPct`（上一期的估算值），
            //   两者都由 `BillEstimator` 在**生成期**按锚点法落盘（渲染仍然零请求、纯算术）。
            //   🔴 为什么不能走下面的旧判据：估算期 `progressEndKnown` 恒 false（它描述"有没有真快照"），
            //      旧判据会直接 `—` ⇒ 历史期增额永远算不出来（正是 `验证记录/208` 的症状根因）。
            if (it.progressEndEst) {
                if (it.progressEndPct < 0 || it.progressStartPct < 0) return UNKNOWN;
                delta = it.progressEndPct - it.progressStartPct;
                if (delta < 0) delta = 0;                   // 估算线单调不减；兜底 clamp 不倒扣
            } else {
                // 🆕 task-41（2026-10-11）：**与累计档同源** —— 期末一律取"该期自己的期末累计"
                //   {@link Bill.Item#progressEndPct}（task-13 起它才是该期的期末值），只在它缺失时
                //   才回落到 {@link Bill.Item#progressPct}（老账兜底）。
                //   🔴 为什么去掉旧的 `!progressEndKnown` 硬门：`progressEndKnown` 的语义是
                //      "**本次生成**抓到的进度是不是该期期末"（生成时机的 provenance），而这里要判的是
                //      "**落盘的 progressEndPct** 这个值本身可不可信" —— task-13 引入 `progressEndPct`
                //      （该期自己的期末累计）之后，旧判据已经陈旧：历史期（如 2026-06/07/08）的
                //      `progressEndPct` 是 `ProgressLog` 里的**真期界快照**，却因 `progressEndKnown=false`
                //      被旧判据打成 `—`；而**同一行的累计档**（只要求 `progressEndPct >= 0`）却能算出值
                //      ⇒ 两个算法判据不一致（用户报的"月维度没修好"的判据层根因）。
                //   "值本身可不可信"改由 {@link BillEstimator#rebuild} 的**期末值可信度 pass** 负责标注：
                //      非真期末（如"进行中"的当期月）⇒ 标 `progressEndEst = true` ⇒ 走上面的估算支并带 `≈`。
                int ep = it.progressEndPct;
                if (ep < 0 && it.progressEndKnown) ep = end;   // 老账回落（与累计档同一条线）
                if (ep < 0) return UNKNOWN;                    // 该期期末取不到 ⇒ 如实留白，不编数
                if (it.progressStartPct < 0) return UNKNOWN;   // 首期无基线 ⇒ 留白（不编数）
                delta = ep - it.progressStartPct;
                if (delta < 0) delta = 0;                      // 重读致进度回落 ⇒ clamp(≥0)，不倒扣
            }
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
            if (v >= 0) sum += v;   // 🆕 8-b：免费行现在**算得出** `¥0`，按用户原话「改 >= 0」累加（加 0 不改变合计）
        }
        return sum;
    }

    /**
     * 合计里有几行算出**非零**实付（{@link #paidFen} &gt; 0）。
     *
     * <p>🆕 TASK-086 上机实测补：合计行的「读回 / 折合」两段**只在 n &gt; 0 时**才写。
     * 一行都算不出来（老账无 `marketPriceFen` / 算法 A 首期无基线 / 全是免费书）时，
     * 打印「读回 ¥0，折合 ¥0/小时」是**噪声且会误导**（读上去像"一分没读回来"），
     * 此时合计如实退化为**只说时长**（与 `tasks/TASK-086` §老账兼容的括注一致）。
     *
     * <p>🆕 TASK-086 第三批 8-b（2026-10-11）：免费行改判 `¥0`（见 {@link #paidFen}）后，
     * 「全免费书」会重新变成 `n &gt; 0` 且 `sum == 0` ⇒ 又打印「读回 ¥0」噪声。故本计数
     * **只数 `&gt; 0` 的行**（免费行与 `delta == 0` 的行都不算「读回来了钱」）。
     * <p>🔴 只改这一处的理由：`paidCount` 全仓仅两个调用点 —— `feature/BillSection.java`（墨单合计行）
     * 与 `feature/BillWallpaper.java`（海报合计行），二者共用本方法当「读回/折合」显示门 ⇒ 改这里
     * 两个表面**自动同口径**（A11），无需动那两处；改后不变式 **{@code n > 0 ⇒ sum > 0}** 重新成立。
     * 对应的合计累加口径见 {@link #totalPaidFen}（`&gt;= 0`，用户拍板原话）。
     */
    public static int paidCount(Context c, List<Bill.Item> items, String algo) {
        if (items == null) return 0;
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            if (paidFen(c, items.get(i), algo) > 0) n++;    // 🆕 8-b：免费 `¥0` 不计入（避免「读回 ¥0」噪声）
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

    // ══════════════════════ ③-b 估算标记（第三批 · A18） ══════════════════════

    /**
     * 这一行的实付金额是不是**估算值**（⇒ 金额前加 `≈`）。
     *
     * <p>🆕 TASK-086 第三批（2026-10-11 · `§20.10` 定稿 Q-D）：形制**只保留"估算金额前缀 `≈`"一项**，
     * 精确金额**无前缀**。本方法是**唯一**判据 —— 两个表面（`feature/BillSection` 墨屏 /
     * `feature/BillWallpaper` 海报）都经 {@link #paidText} 走它 ⇒ 自动同口径（A11）。
     *
     * <p>口径：
     * <ul>
     *   <li>累计档（算法 B）：金额只取决于**期末**那一个点 ⇒ 期末是估算即估算；</li>
     *   <li>增额档（算法 A）：金额 = 两端之差 ⇒ **任一端**是估算即估算
     *       （含"启用当期"= 期末精确、期初估算 ⇒ 如实标 `≈`，见 `§20.5`）；</li>
     *   <li>免费行（`¥0`）/ 未知行（`—`）：不进本判据（免费是确定值，未知不画金额）。</li>
     * </ul>
     */
    public static boolean paidEstimated(Context c, Bill.Item it, String algo) {
        if (it == null || algo == null) return false;
        if (MenuPrefs.PAID_OFF.equals(algo)) return false;
        final int fen = paidFen(c, it, algo);
        if (fen == UNKNOWN) return false;   // 未知 ⇒ 根本没有金额，谈不上估算
        // 🔴 免费行（`¥0`）**不加 `≈`**：0 元是**确定值**（`paidFen` 在进度判据之前就短路成 FREE），
        //    与进度估不估算无关；标成"约 ¥0"只会让人以为金额不确定。
        //    ⚠️ 实现上必须**显式判 FREE**（FREE == 0，不是 `<= 0` 的同义词）—— 否则估算期里
        //    "进度字段被填过"会让免费行也带 `≈`（本批自查发现并修掉的一处）。
        if (fen == FREE) return false;
        if (MenuPrefs.PAID_CUMULATIVE.equals(algo)) return it.progressEndEst;
        return it.progressEndEst || it.progressStartEst;
    }

    /**
     * 合计里**是否含估算行** —— 合计（与「折合 ¥Y/小时」）据此加 `≈`。
     *
     * <p>🔴 这是本批**唯一**一处"卡面没写死、由实现定的口径"：卡面只规定"估算**金额**前缀 `≈`"，
     * 而合计是**多行金额之和**，其中混有估算行 ⇒ 它自己也是估算值。若只在行上加 `≈`、
     * 合计不加，会出现"5 行全 `≈`、合计却像精确值"的**形制自相矛盾**。故本实现让合计与折合
     * **同规则**标 `≈`（已回报 Lead 备案；若拍板要"合计不标"，删本方法的两处调用即可）。
     */
    public static boolean totalEstimated(Context c, List<Bill.Item> items, String algo) {
        if (items == null) return false;
        for (int i = 0; i < items.size(); i++) {
            if (paidEstimated(c, items.get(i), algo)) return true;
        }
        return false;
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

    /**
     * 「实付」列的文案：与 {@link #paidFen} 同源；**估算值加 `≈` 前缀**（A18 形制的唯一一项）。
     *
     * <p>🔴 前缀拼在**本方法**里（而不是两个渲染侧各拼一次）：`feature/BillSection.java`（墨屏）
     * 与 `feature/BillWallpaper.java`（海报）都调它 ⇒ 屏幕与海报逐字一致（A11）。
     */
    public static String paidText(Context c, Bill.Item it, String algo) {
        int fen = paidFen(c, it, algo);
        if (fen == UNKNOWN) return "—";
        return (paidEstimated(c, it, algo) ? "≈" : "") + yuan(fen);
    }

    /** 秒 → `16.9`（合计行的「H 小时」，保留 1 位小数；0 / 负 ⇒ `0`）。 */
    public static String hoursText(int totalSec) {
        if (totalSec <= 0) return "0";
        return String.format(Locale.US, "%.1f", totalSec / 3600.0);
    }
}
