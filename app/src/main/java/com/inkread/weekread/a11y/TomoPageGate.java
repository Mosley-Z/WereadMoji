package com.inkread.weekread.a11y;

import com.inkread.weekread.core.CardDebug;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;

/**
 * Tomo 桌面的**翻页判据**（TASK-006 从 `CardA11yService` 整段平移而来，含全部注释）。
 *
 * 🔴 **判据一行未改** —— 权威规格仍在 `docs/04_桌面让位判据.md` §1，本类只是换了位置。
 *
 * 它只写 {@link CardVisibilityState#pageGate}，再由 {@code overlay.applyVisibility()} 落盘；
 * **自己绝不碰 `setVisibility`**。
 */
final class TomoPageGate {

    private final Context ctx;
    private final android.os.Handler ui;
    private final CardVisibilityState st;
    private OverlayController ov;
    private A11yEventRouter rt;

    TomoPageGate(Context ctx, android.os.Handler ui, CardVisibilityState st) {
        this.ctx = ctx;
        this.ui = ui;
        this.st = st;
    }

    void attach(OverlayController ov, A11yEventRouter rt) {
        this.ov = ov;
        this.rt = rt;
    }

    // ══════════════════════ 桌面翻页闸门（v0.6.0） ══════════════════════

    /**
     * 「用户已经翻离桌面第 1 页」闸门。true 时卡片让位。
     *
     * ── 为什么需要它（用户诉求）──
     * 卡片窗口 `[56,70][424,416]` 正好压在**两行图标**的位置上（桌面 4 行图标的
     * 第 1、2 行是 y 82…176 / 260…354），而各页图标的 y 位置完全一样 ⇒
     * 翻到第 2 页时卡片照旧挡着前两行。用户不要"其他页也显示"，要的是
     * **离开第 1 页就收起来、回到第 1 页再显示**。
     *
     * ── 判据（2026-09-24 真机探针 + 像素真值）──
     * Tomo 的翻页容器是 `com.astraabove.tomo:id/swipe_page`（`android.widget.FrameLayout`）。
     * 翻页动画结束时它发 `TYPE_WINDOW_CONTENT_CHANGED`，**一个窗口里的证据组合**
     * 决定"落在哪一页"。实测到的**全部**形态（每条都配了截图像素真值核对，
     * 原始序列留档在 `_verify060/samples/`）：
     *
     *   ├ 静置（常规翻页）· 离开第 1 页  ：`cct=1` ×1
     *   ├ 静置（常规翻页）· 落到第 1 页  ：`cct=1` ×2（间隔 98–135ms）
     *   ├ 亮屏（**第 1 页与第 2 页完全同形**）：`cct=1` + `cct=0`（+ 窗口态事件）
     *   ├ 亮屏后翻页 · 离开第 1 页       ：`cct=3` + `cct=2`，或**只有一条 `cct=3`**
     *   ├ 亮屏后翻页 · 落到第 1 页       ：`cct=1` + `cct=3` + `cct=2`
     *   └ 时钟整分跳字                  ：`TextView cct=2` + `FrameLayout cct=3` ← 见下
     *
     * ★ 三条关键结论 ★
     *
     * ① **`cct=0`（UNDEFINED）是"桌面 Activity 刚 resume"的标记** —— 亮屏与
     *    从应用返回时，Tomo 会连发一条 `cct=1` + 一条 `cct=0` 的整窗重绘。
     *    那条 `cct=1` **不是翻页** ⇒ 扣掉这份足迹再判（见 {@link #settleSwipe}）。
     *    不过亮屏这件事本身另有更硬的信号：直接订阅 `ACTION_SCREEN_ON`
     *（{@link #SCREEN_ON_IGNORE_MS}），亮屏后 700ms 内的指纹一律不听 ——
     *    因为亮屏那簇的形态**不稳定**（见过 `cct=1`+`cct=0`，也见过 `cct=1`+`cct=3`），
     *    靠内容去认它等于每次都在赌。
     *
     * ② **TEXT 位（`cct & 2`）= "第 1 页的时钟块被重建 / 消失"** —— 第 1 页有
     *    时钟 / 日期块，它出现或消失都会让 TextView 的文本变化向上冒泡成
     *    `cct=3 / cct=2`。于是"没有 `cct=0` 但有 TEXT 位"的窗口里：
     *    带 `cct=1`（子树重绘）⇒ 落到第 1 页；≥2 条且无 `cct=1` ⇒ 亮屏后
     *    那第一次翻页（时钟块消失）⇒ 离开第 1 页。
     *
     * ③ **孤立的 `cct=3`（只一条）要与"时钟整分跳字"区分** —— 这两者形态完全
     *    一样，但**时钟整分那条前面必有一条 `TextView cct=2`**（时钟自己的文本
     *    变化），而翻页没有（实测对照 4:1，见 {@link #lastHomeTextNodeAt}）。
     *    判别不了时按"宁可多显示"处理：整分误判成翻页只会让卡片多藏一会儿，
     *    翻页误判成整分才是用户真正不满意的方向。
     *
     * ⚠️ 这个判据**与桌面页数无关**：P2 ↔ P3 之间翻页同样是 1 条且无 TEXT，
     * 所以桌面多于两页时也不会误判 —— 早期设想的"奇偶翻转"方案才会在那里失效。
     *
     * ── 为什么必须限定"当前前台桌面包"（踩过的坑）──
     * 实测 `com.wetao.elauncher`（备用桌面）**也发** `FrameLayout cct=1`，
     * 而它同样在 CATEGORY_HOME 名单里 ⇒ 它的后台事件会污染 Tomo 上的页面判定
     * （真机日志里抓到过：人在 Tomo 桌面，ELauncher 的一条事件把状态改了）。
     * 两道过滤：
     *   ① 只认**系统默认桌面**那一个包（{@link #defaultHomePkg}）；
     *   ② 会发 ViewPager 事件的桌面（ELauncher）一律不启用本判据 —— 它有自己那套
     *      `scrollX` 判据（{@link #handleDesktopPage}），两套不要互相干扰。
     *
     * ── 与"进桌面"的关系（关键，别改回去）──
     * "从第 2 页点开应用 → 按 HOME"实测回到的是**原来的第 2 页**，而这条路径
     * 只会补一次 resume 重绘（`cct=1` + `cct=0`）**不是翻页** ⇒ 按上面结论 ①
     * 整窗丢弃、闸门值**保留**。否则卡片会立刻显示在 P2 上、继续遮挡。
     *
     * ── 窗口态事件（`WINDOW_STATE_CHANGED LauncherActivity`）不再参与判定 ──
     * 留它只为日志。⚠️ **不要**再拿它当"亮屏 / 回桌面"的指纹：实测 Tomo
     * **每次翻页也会发**这条（2026-09-24 抓到：亮屏后左滑的序列是
     * `cct=3` → 窗口态 → `cct=2`），早先一句"真正的翻页不会"是错的。
     *
     * ── 万一漏投怎么办 ──
     * 失败方向按"宁可多显示"处理：判不准时一律当"在第 1 页"。另外留了两个恢复口：
     * ① 服务重连（开关无障碍 / 重启）时闸门归零；② 设置页的「重新同步卡片显示」按钮
     * （{@link #resetPageGate}）。
     */

    /** 窗口内 `cct=1`（**恰好** SUBTREE，不含 TEXT 位）的条数 */
    /**
     * 窗口内 FrameLayout 事件的**总条数**（cct 取值 0/1/2/3 都算一条）。
     *
     * 它把两种"只有 TEXT 没有纯 SUBTREE"的形态分开：
     *   · 时钟整分跳字 = **1 条**（只有 cct=3，由 TextView 的文本变化冒泡上来）
     *   · 亮屏后的第一次翻页（离开第 1 页）= **2 条**（cct=3 + cct=2，时钟块消失）
     * 不数这个的话，整分会被当成"翻页离开"，卡片每分钟消失一次。
     */
    /**
     * 窗口内是否出现过 TEXT 位（`cct & 2`）。
     *
     * 这是"第 1 页的时钟 / 日期块被重建或消失"的指纹，见 {@link #pageGate} 结论 ②。
     */
    /**
     * 窗口内是否出现过 `cct=0`（`CONTENT_CHANGE_TYPE_UNDEFINED`）。
     *
     * ★ 它是"桌面 Activity 刚 resume、整窗重绘"的标记（亮屏 / 从应用返回），
     * 见 {@link #pageGate} 结论 ①。它不是"整窗作废"，而是"照这个数量**扣掉**
     * resume 的足迹"，这样"亮屏后马上滑动并进同一窗口"也能正确判向（见 {@link #settleSwipe}）。
     */
    /** 窗口内是否出现过 `WINDOW_STATE_CHANGED LauncherActivity` —— **仅供日志**，不参与判定 */
    /** 窗口是否开着（用来区分"第一条"和"后续"） */
    /** 最近一条翻页指纹的到达时刻（`SystemClock.uptimeMillis()`），只用来在日志里报"窗口跨度" */
    /** 本窗口第一条翻页指纹的到达时刻 —— 与 {@link #swipeLastAt} 一起报出窗口跨度 */
    /** 本窗口是否已经"延迟"过一次（见 {@link #SWIPE_LINGER_MS}）—— 只允许延一次 */

    /**
     * 最近一次"桌面自己的 TextView 内容变化"的时刻。
     *
     * ★ 它的唯一用途：把**时钟整分跳字**与**翻页**分开。两者都会让翻页容器发一条
     * 孤立的 `FrameLayout cct=3`（TEXT 位），形态完全一样 ——
     * 实测对照（2026-09-24，各 4 次）：
     *
     *   时钟整分（01:43:00 / 01:44:00 / 01:45:00 / 01:46:00，4/4 一致）
     *       `TextView cct=2`   ← 时钟自己的文本变化，先到
     *       `FrameLayout cct=3` ← 子树重绘向上冒泡
     *   亮屏后翻页离开第 1 页（01:41:05）
     *       `FrameLayout cct=3` ← **只有这一条，没有 TextView 事件**
     *
     * 所以"窗口附近有没有桌面 TextView 事件"就是判别点，而且**只看类名、
     * 不读内容**，与 canReceiveWindowContent=false 不冲突。
     */

    /** 桌面 TextView 事件与翻页指纹算作"同一次"的最大间隔（毫秒），见 {@link #lastHomeTextNodeAt} */
    private static final long HOME_TEXT_NODE_MS = 400L;

    /**
     * 「一条窗口的跨度超过这个值 ⇒ 认定它把不相关的事件误并了」的门槛（毫秒）。
     *
     * 🔴 v0.8.1 新增 —— 修「人在第 1 页、pageGate 却被误置位、卡片永久消失」的**根因**。
     *
     * ★ 真机抓到的两条对照（2026-09-27）──
     *   真翻页（用户左滑离开第 1 页）：
     *     `swipe sub=1→1 total=1→1 text=false zero=0 ws=false span=0ms   → 离开第1页` ✔
     *   误判（覆盖安装后服务重连，桌面补发 resume 簇）：
     *     `swipe sub=0→0 total=2→2 text=true  zero=0 ws=false span=968ms → 离开第1页` ✘
     *
     * 两条的 `span`（窗口内首末事件的间隔）差了三个数量级：**真翻页的两条事件
     * 实测间隔 7–264ms**，而误判那条 **968ms** —— 说明窗口把"服务重连时桌面补发的
     * 一次整窗重绘"和"Windows 状态/文本变化"这些**彼此无关**的事件并进了一起，
     * 从而凑出了"`total≥2` 且 `text` 但 `sub==0`"这个本该代表"离开第 1 页"的组合。
     *
     * ★ 为什么选 500ms ──
     * 实测真翻页最大 264ms，留了近一倍余量；而误判样本 968ms 远在其上。
     * 一次翻页的两条事件哪怕慢一些也远不会到 500ms（真到了就说明这不是一次翻页）。
     *
     * ★ 失败方向 ──
     * 命中守卫时**不判向**（保持闸门现状），符合本项目一贯的"宁可多显示"：
     * 万一真的漏判了一次翻页，卡片多显示一会儿，下一次翻页就会纠正。
     *
     * ⚠️ **必须与 `zero == 0` 联用**（见 {@link #settleSwipe} 里的三条合取）：
     * 单看 span 会误伤已实测的合法形态「亮屏+左滑并窗」（慢滑时 span 也可能偏大）。
     */
    private static final long SWIPE_MAX_SPAN_MS = 500L;

    /**
     * 亮屏后多久之内的翻页指纹一律忽略（毫秒）。
     *
     * ★ 为什么直接订阅系统亮屏广播，而不是从事件里"猜"亮屏 ——
     * 亮屏时桌面 Activity 会 resume 并整窗重绘，Tomo 补发的那几条事件**形态不稳定**：
     * 实测到过 `cct=1 + cct=0`（三次）、也见过 `cct=1 + cct=3`。后者与"真的翻回
     * 第 1 页"（`cct=1 + cct=3 + cct=2`）几乎同形，只差一条 —— 靠事件内容去
     * 分辨亮屏，等于每次都在赌。而 `ACTION_SCREEN_ON` 是**原因本身**，订阅它
     * 就能把整段亮屏噪声一次性排除，不用再枚举变体。
     *
     * 700ms 的取值：实测亮屏附带的那簇在亮屏后 0–300ms 内到齐；而人手从
     * 按电源键到把滑动做完至少 1 秒以上，所以 700ms 能干净地切开两者。
     * 万一用户真的在 700ms 内滑完，代价只是"这一次没识别"，卡片停在上次状态
     *（按"宁可多显示"的失败方向处理），下一次滑动就会纠正。
     */
    private static final long SCREEN_ON_IGNORE_MS = 700L;

    /**
     * 翻页窗口长度（毫秒）—— 每收到一条指纹就**从它重新计时**。
     *
     * 为什么要开窗口而不是"收到一条就判"：一次翻页会发两条（间隔实测 103ms 到
     * 数百 ms 不等），必须等到窗口结束才知道这次一共几条、有没有 TEXT 位。
     * 400ms 是常见间隔的数倍余量；用户连续两次翻页（P1→P2→P3）通常隔 1 秒以上，
     * 所以 400ms 既能收齐一次翻页、又不至于把两次翻页并成一簇。
     * 万一证据模棱两可（只有一条 TEXT 事件），会再延 {@link #SWIPE_LINGER_MS} 等伙伴。
     */
    private static final long SWIPE_WINDOW_MS = 400L;

    /**
     * "拿不准就再等一会"的延时（毫秒）。
     *
     * 为什么需要它 —— 2026-09-24 真机踩到的坑：一次翻页的两条事件
     *（`cct=3` 与 `cct=2`）**间隔并不稳定**，实测出现过 >220ms 的情况；
     * 早先按"间隔 > 220ms 就是新一簇"立刻结算，于是这一对被打散成两个窗口
     * （各 1 条），判据读到的是两条"时钟整分"⇒ 左滑完全没被识别，
     * 卡片留在第 2 页上继续遮挡（并在随后"从应用返回"时把错误延续下去）。
     *
     * 现在不吃间隔这个赌注：窗口只有**一个**截止时间，
     * 若到期时证据是"模棱两可"的（只有一条 TEXT 事件 —— 既可能是时钟整分，
     * 也可能是半次翻页），就**再加 400ms** 等它的伙伴；伙伴来了就合并成一次翻页。
     * 时钟整分没有伙伴，等完仍判"不动"，代价只是一次多余的 400ms。
     */
    private static final long SWIPE_LINGER_MS = 400L;

    /** 翻页容器的类名 —— swipe_page 是 FrameLayout */
    private static final String SWIPE_NODE_CLS = "android.widget.FrameLayout";
    /** `CONTENT_CHANGE_TYPE_SUBTREE` */
    private static final int CCT_SUBTREE = 1;
    /** `CONTENT_CHANGE_TYPE_TEXT` —— 见 {@link #swipeText} */
    private static final int CCT_TEXT = 2;
    /** `CONTENT_CHANGE_TYPE_UNDEFINED` —— 见 {@link #swipeZero} */
    private static final int CCT_UNDEFINED = 0;

    /**
     * 结算当前窗口（窗口到期、或被新一簇打断时调用）。
     *
     * 分流规则（每一条都在真机 + 截图像素真值上验过，见 {@link #pageGate} 的形态表）：
     *
     * 第一步：**扣掉 resume 重绘的足迹**。
     * resume 重绘 = 一条 `cct=1` + 一条 `cct=0`（见 {@link #swipeZero}），
     * 它那条 `cct=1` 不是翻页，但它可能跟紧随其后的真翻页并进同一个窗口
     * （亮屏后马上滑动，间隔 < 220ms）。所以不是"整窗丢弃"，而是**扣掉一份**：
     *
     *   sub' = max(0, sub - 1)          （窗口里有 cct=0 时才扣）
     *   total' = total - zeroCount - (sub≥1 ? 1 : 0)
     *
     * 扣完再看剩下的（`sub'` / `total'`）：
     *
     *   · total'==0                       → 不动（就是一次纯亮屏 / 纯返回桌面）
     *   · !text                           → 纯翻页：leave = (sub' < 2)
     *   · text && sub'≥1                   → **落到第 1 页**（第 1 页的时钟块被重建）
     *   · text && sub'==0 && total'≥2      → **离开第 1 页**（亮屏后首次翻页：时钟块消失）
     *   · text && sub'==0 && total'==1     → 先延一次等伙伴；还是没有就按
     *     **有没有桌面 TextView 伴随**分流：有 ⇒ 时钟整分（不动）；
     *     没有 ⇒ **离开第 1 页**（只发一条 `cct=3` 的翻页形态）。
     *
     * 举四个实测过的例子（都验过）：
     *   纯亮屏              `cct=1, cct=0`                       → total'=0            → 不动 ✓
     *   亮屏+左滑并窗        `cct=1, cct=0, cct=3, cct=2`          → text, sub'=0, tot'=2 → 离开 ✓
     *   亮屏+右滑并窗        `cct=1, cct=0, cct=1, cct=3, cct=2`   → text, sub'=1         → 落到 ✓
     *   亮屏后右滑（分两窗） `cct=1, cct=3, cct=2`                 → text, sub'=1         → 落到 ✓
     */
    void settleSwipe() {
        if (!st.swipeOpen) return;
        st.swipeOpen = false;
        if (ui != null) ui.removeCallbacks(applySwipeWindow);
        int sub = st.swipeSub;
        int total = st.swipeTotal;
        boolean text = st.swipeText;
        int zero = st.swipeZero;
        boolean ws = st.swipeWinState;
        boolean lingered = st.swipeLingered;
        st.swipeSub = 0;
        st.swipeTotal = 0;
        st.swipeText = false;
        st.swipeZero = 0;
        st.swipeWinState = false;
        st.swipeLingered = false;
        if (total == 0) return;

        // 扣掉 resume 重绘的足迹（一条 cct=1 + zero 条 cct=0）
        int subEff = sub;
        int totalEff = total;
        if (zero > 0) {
            if (sub >= 1) {
                subEff = sub - 1;
                totalEff = total - zero - 1;
            } else {
                totalEff = total - zero;
            }
        }
        if (totalEff <= 0) {
            // 纯亮屏 / 纯返回桌面：没有任何翻页证据，维持现状
            CardDebug.note(ctx, "swipe drop (resume 重绘 sub=" + sub + " total=" + total
                    + " zero=" + zero + " text=" + text + " ws=" + ws + ")");
            return;
        }

        // 🔴 v0.8.1 根因修复：**只针对"明确的误并形态"**不判向（见 SWIPE_MAX_SPAN_MS）。
        // 判定必须同时满足三条，缺一不可 —— 因为已实测存在的合法形态
        //「亮屏+左滑并窗」（`cct=1,cct=0,cct=3,cct=2`，含 `zero≥1`）在慢滑时
        // span 也可能偏大，单看 span 会误伤它：
        //   ① `zero == 0`：误判样本**没有** `cct=0`（桌面 resume 标记）；
        //      而所有实测过的合法并窗形态都至少带一条 `cct=0`。
        //   ② `text == true`：误判样本靠 TEXT 位凑出方向。
        //   ③ `span > SWIPE_MAX_SPAN_MS`：真翻页两条事件实测 7–264ms，样本 968ms。
        // 三条同时成立时，这个窗口不可能是"一次翻页"，只能是彼此无关的事件被并了进来
        // ⇒ 不判向（保持闸门现状，符合"宁可多显示"）。
        long span = st.swipeLastAt - st.swipeFirstAt;
        if (zero == 0 && text && span > SWIPE_MAX_SPAN_MS) {
            CardDebug.note(ctx, "swipe drop (误并形态: zero=0 text=true span=" + span + "ms > "
                    + SWIPE_MAX_SPAN_MS + "ms ⇒ 不判向; sub=" + sub + " total=" + total + ")");
            return;
        }

        boolean leave;
        if (!text) {
            leave = subEff < 2;                      // 1 条 = 离开第 1 页，≥2 条 = 落到第 1 页
        } else if (subEff >= 1) {
            leave = false;
        } else if (totalEff >= 2) {
            leave = true;
        } else if (!lingered) {
            // 只有一条 TEXT 事件 —— 既可能是**时钟整分跳字**，也可能是**半次翻页**
            //（伙伴那条还没到，实测两条间隔能超过 220ms）。不下结论：把窗口
            // 原样存回去、再等 SWIPE_LINGER_MS，看伙伴来不来。只允许延一次。
            CardDebug.note(ctx, "swipe linger (等伙伴 sub=" + sub + " total=" + total
                    + " zero=" + zero + " ws=" + ws + ")");
            st.swipeLingered = true;
            st.swipeOpen = true;
            st.swipeSub = sub;
            st.swipeTotal = total;
            st.swipeText = text;
            st.swipeZero = zero;
            st.swipeWinState = ws;
            if (ui != null) ui.postDelayed(applySwipeWindow, SWIPE_LINGER_MS);
            return;
        } else {
            // 等过了、伙伴还是没来。这条孤立 TEXT 指纹有两种可能，靠"窗口附近有没有
            // 桌面 TextView 事件"分开（见 st.lastHomeTextNodeAt 的实测对照）：
            boolean nearbyTextNode = st.lastHomeTextNodeAt != 0
                    && st.lastHomeTextNodeAt >= st.swipeFirstAt - HOME_TEXT_NODE_MS
                    && st.lastHomeTextNodeAt <= st.swipeLastAt + HOME_TEXT_NODE_MS;
            if (nearbyTextNode) {
                // 时钟整分跳字（时钟自己的 TextView 变了，子树跟着重绘）→ 什么都不做
                CardDebug.note(ctx, "swipe ignore (整分, 有 TextView 伴随 sub=" + sub
                        + " total=" + total + " span=" + (st.swipeLastAt - st.swipeFirstAt) + "ms)");
                return;
            }
            // 没有 TextView 伴随 ⇒ 是"亮屏后翻页离开第 1 页"那种只发一条 cct=3 的形态
            leave = true;
        }
        CardDebug.note(ctx, "swipe sub=" + sub + "→" + subEff + " total=" + total + "→" + totalEff
                + " text=" + text + " zero=" + zero + " ws=" + ws
                + " span=" + (st.swipeLastAt - st.swipeFirstAt) + "ms"
                + " → " + (leave ? "离开第1页" : "落到第1页") + " (cur=" + st.pageGate + ")");
        if (leave != st.pageGate) {
            st.pageGate = leave;
            ov.applyVisibility();
        }
    }

    private final Runnable applySwipeWindow = new Runnable() {
        @Override
        public void run() {
            settleSwipe();
        }
    };

    /**
     * 丢弃还没结算的翻页窗口 —— 整窗作废，不写任何状态。
     *
     * 调用点：服务销毁、设置页手动重置。**注意"进桌面"不再走这里**：
     * 进桌面只把窗口标记成"见过窗口态"（因为亮屏 / 回桌面附带的那条 cct=1
     * 与"回桌面后真的翻了一次页"要靠 TEXT 位区分，直接丢会误伤后者）。
     */
    void cancelSwipeWindow() {
        if (!st.swipeOpen) return;
        st.swipeOpen = false;
        st.swipeSub = 0;
        st.swipeTotal = 0;
        st.swipeText = false;
        st.swipeZero = 0;
        st.swipeWinState = false;
        st.swipeLingered = false;
        if (ui != null) ui.removeCallbacks(applySwipeWindow);
    }

    /**
     * 桌面内又收到一条 `WINDOW_STATE_CHANGED LauncherActivity`（亮屏、从应用回到桌面、翻页）。
     *
     * ⚠️ **它不再影响判定，只是打个日志标记**。曾经拿它当"亮屏 / 回桌面"的指纹，
     * 后来实测发现 Tomo **每次翻页也发**这条（亮屏后左滑的序列是
     * `cct=3` → 窗口态 → `cct=2`），所以那个假设是错的、已废弃。
     * 现在"亮屏 / 回桌面"由 {@link #swipeZero}（`cct=0`）来识别 —— 那是原因本身。
     * 窗口也**不再延长**：延长只会把后面真正翻页的事件并进来。
     */
    void noteLauncherWindowState() {
        if (!st.swipeOpen) return;
        st.swipeWinState = true;
    }

    /**
     * 记下"桌面自己的 TextView 内容变化"（时钟/日期块）—— 见 {@link #lastHomeTextNodeAt}。
     *
     * ⚠️ 必须在 {@link #handleLauncherSwipe} **之前**调用：实测时钟整分时
     * TextView 那条**先到**、`FrameLayout cct=3` 后到，等翻页指纹开窗时
     * TextView 早过去了，反过来就抓不到。
     */
    void noteHomeTextNode(String pkg, String cls) {
        if (st.defaultHomePkg == null || !pkg.equals(st.defaultHomePkg)) return;
        if (!cls.endsWith("TextView")) return;
        st.lastHomeTextNodeAt = android.os.SystemClock.uptimeMillis();
    }

    /**
     * Tomo 桌面翻页判定（v0.6.0）—— "离开第 1 页就收起来"。
     *
     * 判据与实测依据全写在 {@link #pageGate} 的注释里，这里只讲实现：
     * ① 包名必须是**系统默认桌面**（挡掉 ELauncher 的后台事件）、类名必须是
     *    `android.widget.FrameLayout`；
     * ② 把窗口内每一条都记下来：`cct=1` 计数、TEXT 位打标、`cct=0` 打标；
     * ③ 窗口**只有一个出口：截止时间**（不做"间隔过大就早结算"，理由见方法内注释），
     *    到期交给 {@link #settleSwipe} 分流；证据模棱两可时会再延一次
     *（{@link #SWIPE_LINGER_MS}）。
     *
     * 为什么"每条都收"而不是"只看 cct=1"：有的形态里真正区分方向的不是 cct=1，
     * 而是它旁边那些 `cct=3 / cct=2 / cct=0`（详见 {@link #pageGate} 的形态表）。
     */
    void handleLauncherSwipe(String pkg, String cls, AccessibilityEvent event) {
        if (!rt.isLauncher(pkg)) return;
        if (!SWIPE_NODE_CLS.equals(cls)) return;
        // 只认**系统默认桌面**。ELauncher 同样是 CATEGORY_HOME（也可能成为前台），
        // 它那份 FrameLayout 事件的语义和 Tomo 的 swipe_page 完全不同 ——
        // 真机上抓到过：点图标进了一次 ELauncher，回来时卡片就被它的事件改成了"显示"。
        if (st.defaultHomePkg == null || !pkg.equals(st.defaultHomePkg)) return;
        // 有 ViewPager 判据的桌面走 handleDesktopPage，两套判据不叠加
        if (st.sawViewPager.contains(pkg)) return;
        if (CardA11yService.sOwnUiForeground) return;               // 自家界面上不该有桌面翻页
        int cct = 0;
        try {
            cct = event.getContentChangeTypes();
        } catch (Throwable ignored) {
        }
        long now = android.os.SystemClock.uptimeMillis();
        // 亮屏后的一小段时间内一律不听：那一段是桌面 resume 的整窗重绘，
        // 形态不稳定、且与"真的翻回第 1 页"高度同形（见 SCREEN_ON_IGNORE_MS）。
        if (st.screenOnAt != 0 && now - st.screenOnAt < SCREEN_ON_IGNORE_MS) {
            CardDebug.note(ctx, "swipe ignore (亮屏后 " + (now - st.screenOnAt) + "ms < "
                    + SCREEN_ON_IGNORE_MS + "ms, cct=" + cct + ")");
            return;
        }
        // ⚠️ 这里**刻意不做"间隔过大就早结算"**：一次翻页的两条事件间隔不稳定
        // （实测 7ms 到 264ms 都有），早结算会把它俩拆成两个窗口、判据只看到
        // 两条"时钟整分"，方向就丢了。窗口的唯一出口是截止时间（见 SWIPE_LINGER_MS）。
        if (!st.swipeOpen) st.swipeFirstAt = now;
        st.swipeOpen = true;
        st.swipeTotal++;
        // ⚠️ 必须是 `cct == 1`，不能写成 `(cct & 1) != 0` —— cct=3 也含 SUBTREE 位，
        // 那样会把"整分跳字"和"亮屏后翻页"都算进来（踩过）。
        if (cct == CCT_SUBTREE) st.swipeSub++;
        if ((cct & CCT_TEXT) != 0) st.swipeText = true;
        if (cct == CCT_UNDEFINED) st.swipeZero++;         // resume 整窗重绘的标记（要计数）
        st.swipeLastAt = now;
        if (ui != null) {
            ui.removeCallbacks(applySwipeWindow);
            ui.postDelayed(applySwipeWindow, SWIPE_WINDOW_MS);
        }
    }
}