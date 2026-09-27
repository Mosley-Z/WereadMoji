package com.inkread.weekread.a11y;

import com.inkread.weekread.core.CardDebug;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * ELauncher「翻页落到第 1 页」的**内容探测复核**（TASK-010）。
 *
 * ── 为什么需要它 ──
 * ELauncher 桌面 2026-09 升级为 **3 页** 后，翻页伴随那条 TextView 的残值全表实测
 * （验证记录/55 探测）：P1→P2 = 524266、P2→P1 = 524255、**P3→P2 = 524254**、
 * **P2→P3 两种形态**（无残值 / 也报 524255）。其中 524254 与 524255 **只差 1**，
 * 落进 {@link ElauncherPageGate} 的「落到」侧 ⇒ 卡片被错误显示在 P2/P3 上（遮挡图标）。
 * 任何线性分界都同时满足不了 524254（离开侧语义）与 524255（落到侧语义）；
 * 3 页版翻页的 ViewPager 事件 `scrollX` 又恒为 0 ⇒ `desktopPage` 通道失效。
 * 事件层原理性无解，只能比照 {@link SettingsPageProbe}（TASK-009）换内容信号。
 *
 * ── 判据 ──
 * P1 桌面时钟块有特征 id {@code com.wetao.elauncher:id/txt_clock}
 * （真机实证：P1 静置树恰 1 处；P2/P3 树 **0 命中** —— uiautomator dump 实测，
 * 验证记录/55）。{@link ElauncherPageGate} 结算出「swipe 落到第 1 页」后不再直接显示，
 * 改为调度本探测：延迟 {@link #PROBE_DELAY_MS} 首查 + {@link #RECHECK_DELAY_MS} 复核，
 * **两次都命中"可见的"时钟块才把卡片显示出来**（pageGate=false）。
 * 🔴 命中必须带可见性过滤（`isVisibleToUser` + 屏内 bounds）：真机 G 场景实锤
 * P2/P3 树会短暂**残留** P1 时钟节点（ViewPager 保留邻页；dump 测不到 0 命中是因为
 * uiautomator 跳过不可见节点，`getRootInActiveWindow` 看得到）—— 仅按 id 命中会在
 * P2 上误显示（2026-09-26 v1 构建实测踩到，16:04:17 G 场景）。
 *
 * 与 {@link SettingsPageProbe} 方向相反、失败方向也相反：
 * · 那边「命中才藏」，失败方向只许少让位；这边「命中才显」，失败方向只许**维持隐藏** ——
 *   探测拿不到根窗口 / 窗口已切走 / 没查到时钟块 ⇒ 一律不动作（不显示、也**绝不藏**，
 *   「离开」方向仍由事件层 524266 那条可靠路径负责，本类永不写 pageGate=true）。
 *
 * ── 防误报 / 恢复设计 ──
 * · **双命中**：首查 + 复核之间隔 600ms，覆盖翻页动画与邻页保留的过渡残树；
 *   复核未命中 ⇒ 放弃显示。
 * · **resume 不走本类**：桌面 resume（HOME / 亮屏 / 从应用返回）必落第 1 页
 *   （5/5 实测 + 2026-09-26 复测）⇒ ElaGate 的 frame 路径仍**直接显示**，不经探测 ——
 *   这同时是本探测漏报时的**天然恢复路径**（任何 HOME / 进出应用都会把卡片带回来）。
 * · **离开即取消**：结算判「离开」⇒ 在途探测立即作废（人都不在 P1 了）。
 * · **seq 防过期**：探测在途又来新事件 ⇒ 旧结果作废，由新事件重新调度。
 * · **id 有包名命名空间**：Tomo 的窗口永远查不中 {@code com.wetao.elauncher:id/*}；
 *   且本类只被 ElaGate 结算调度，Tomo 不发 ViewPager 事件 ⇒ 那条路根本走不到
 *   ⇒ 两桌面自动适配，Tomo 零影响。
 * · **成本**：每次探测 = 1 次 getRootInActiveWindow IPC + 1 次按 id 查找，
 *   仅「swipe 落到」结算时发生；显示路径最多两轮（≈1.25s 延迟，墨水屏可接受）。
 *
 * 🔴 **窗口内容使用点之二**（与 {@link SettingsPageProbe} 并列，全仓库仅这两个）——
 * 本类的存在不新增任何权限（canRetrieveWindowContent 已于 TASK-009 翻 true）。
 * 动本类之前必读 `docs/04` §2 与 `tasks/TASK-010_Ela三页内容探测.md`。
 *
 * <p>**v0.8.2（方案 A）新增第三个用途**：{@link #checkCurrentScreen()} —— 服务重连时
 * 一次性判断"当前在桌面第 1 页 / 桌面其他页 / 不是桌面"，供 `CardA11yService`
 * 修正"重连后无条件按第 1 页处理"的假设。它与上面那条**共用同一个
 * `getRootInActiveWindow()` 调用点**（{@link #activeRoot()}）⇒ 窗口内容使用点
 * 仍是"两处两个类"，能力面声明不变。
 */
final class ElaHomeProbe {

    private final android.content.Context ctx;
    private final CardA11yService svc;
    private final android.os.Handler ui;
    private final CardVisibilityState st;
    private OverlayController ov;

    /** ELauncher 桌面包 —— 探测只认它的窗口，别的包一律「不动作」 */
    private static final String ELA_PKG = "com.wetao.elauncher";
    /**
     * P1 特征 id：桌面时钟块。真机实证（2026-09-26，验证记录/55）：
     * P1 静置树恰 1 处；P2 / P3 树 0 命中。取时钟块而非日期块：时钟每分钟必在、值稳定。
     */
    private static final String HOME_CLOCK_ID = "com.wetao.elauncher:id/txt_clock";
    /** 事件后等多久首查（毫秒）。盖过翻页动画（实测 ~300ms）—— 与 SettingsPageProbe 同值 */
    private static final long PROBE_DELAY_MS = 650L;
    /** 首查命中后，等多久复核第二次（毫秒）。两次之间过渡残树会被回收 */
    private static final long RECHECK_DELAY_MS = 600L;

    // ══════════════════ v0.8.2（方案 A）：服务重连时的「当前在前台哪一屏」一次性自检 ══════════════════
    //
    // ── 为什么需要它（真机实锤，2026-09-27）──
    // 服务重连（覆盖安装后系统重启服务 / 关开无障碍开关 / 设备重启）时，原实现
    // `CardVisibilityState.resetAll()` 会**无条件**把 `onDesktop=true` + `pageGate=false`，
    // 即"假定人在桌面第 1 页"。可人完全可能在**第 2 页**（Tomo 尤为明显：Tomo 判向靠
    // swipe 事件，重连后没有事件可依）⇒ 卡片错误显示、压住第 2 页图标，且**不会自愈**
    //（实测 30s 后仍 VISIBLE，只有用户主动翻页才恢复）。
    //
    // ── 判据（与"落到 P1"那条同源：都是"可见的 P1 特征节点"）──
    // 拿一次根窗口，按包名分三类，再对**已知桌面**查它的 P1 指纹：
    //   · 包名不是桌面（别的应用 / 我们自己的界面）⇒ 当前就不该显示卡片
    //   · 是桌面 且 可见的 P1 特征节点命中 ⇒ 第 1 页
    //   · 是桌面 但没命中           ⇒ **不在第 1 页**（这就是要修的情形）
    //   · 拿不到根窗口 / systemui 浮层 / 不认识的桌面 ⇒ **拿不准，维持原行为**
    //
    // ── 失败方向（符合本项目一贯的"宁可少让位、不可藏死"）──
    // 只有**拿到正面证据**才改状态：命中 ⇒ 显示（与原行为一致）；桌面但未命中 ⇒ 让位
    //（失败方向 = 多藏一次，下一次翻页/回桌面就会纠正）；前台非桌面 ⇒ onDesktop=false
    //（这条同时让"从应用按 HOME 回来"重新满足 `desk != st.onDesktop` ⇒ 必然重算显示，
    // 不会出现"藏死后回不来"）。三类都拿不准 ⇒ 一个字段都不动。
    //
    // ── 为什么放在本类、而不是新开一个 probe 类 ──
    // 本类本就承担「桌面第 1 页特征节点的窗口内容探测」；重连自检用的是**同一件事**，
    // 只是触发时机不同（一次同步查询 vs 延迟双命中）。放进本类可复用同一个
    // `getRootInActiveWindow()` 调用点 ⇒ **不新增窗口内容使用点**（全仓库仍为
    // 本类 + {@link SettingsPageProbe} 两处两个类），也免去扩能力面所需的单独拍板。
    //
    // ── 已知取舍（如实登记）──
    // 类名带 `Ela` 前缀，而自检对 Tomo 也生效 ⇒ 命名比职责窄。不为此改名：改名要动
    // 调用方与多处能力面文案，收益不抵风险；列为遗留。
    // ══════════════════════════════════════════════════════════════════════════════

    /** 自检结论：确认在默认桌面**第 1 页** */
    static final int CUR_HOME_P1 = 0;
    /** 自检结论：确认在桌面，但**不在第 1 页**（要修的那一类） */
    static final int CUR_HOME_NOT_P1 = 1;
    /** 自检结论：前台**不是桌面**（别的应用 / 我们自己的界面） */
    static final int CUR_NOT_HOME = 2;
    /** 自检结论：**拿不准**（拿不到根窗口 / systemui 浮层 / 不认识的桌面）⇒ 不动作 */
    static final int CUR_UNKNOWN = 3;

    /** Tomo 桌面包 —— 自检时用来查它的 P1 时钟指纹 */
    private static final String TOMO_PKG = "com.astraabove.tomo";
    /**
     * Tomo 的 P1 特征 id：桌面时钟容器。
     *
     * 2026-09-27 只读探针实测（`_shots/PROBE_tomo_p1_ui.xml` vs `PROBE_tomo_p2_ui.xml`）：
     * `desk_clock` 系列（`desk_clock` / `desk_clock_only` / `desk_clock_only_date` /
     * `desk_clock_row`）**只在 P1 存在**，P2 独有 `app_grid_more`。
     *
     * ⚠️ 与 ELauncher 不同，Tomo **不做邻页保留**（其翻页容器 `swipe_page` 的直接子节点
     * 恒为 1 个 `home_stack`，内容整体替换）⇒ 没有"邻页残树"干扰；但仍照
     * {@link #HOME_CLOCK_ID} 的规矩加上可见性过滤（多一道不会有坏处）。
     */
    private static final String TOMO_CLOCK_ID = "com.astraabove.tomo:id/desk_clock";

    /** systemui 的窗口是浮层（下拉通知栏 / 音量条），此刻"当前应用"没变 ⇒ 一律拿不准，不动作 */
    private static final String SYSTEMUI_PKG = "com.android.systemui";

    /** 探测序号：每调度一次 +1；回调时对不上号 = 期间有新事件 ⇒ 结果过期，丢弃 */
    private int seq = 0;
    /** 是否有首查在途（同一时刻只挂一个，后来的事件把它顶掉并顺延） */
    private boolean pending = false;
    /** 复核在途标记 */
    private boolean recheckPending = false;

    ElaHomeProbe(CardA11yService svc, android.os.Handler ui, CardVisibilityState st) {
        this.ctx = svc;
        this.svc = svc;
        this.ui = ui;
        this.st = st;
    }

    void attach(OverlayController ov) {
        this.ov = ov;
    }

    /** 作废在途探测与复核（判「离开」/ resume / 服务断开 / 手动重置时调） */
    void cancel() {
        pending = false;
        recheckPending = false;
        if (ui != null) {
            ui.removeCallbacks(run);
            ui.removeCallbacks(recheck);
        }
    }

    /**
     * 「swipe 落到第 1 页」结算后调度一次探测。
     *
     * 只会由 {@link ElauncherPageGate#settleElauncherWindow} 调 —— resume（frame 指纹）
     * 路径**不经过这里**（直接显示）；Tomo 无 ViewPager 事件 ⇒ 本类在 Tomo 上零调用。
     */
    void schedule() {
        seq++;
        pending = true;
        recheckPending = false;
        if (ui != null) {
            ui.removeCallbacks(run);
            ui.removeCallbacks(recheck);
            ui.postDelayed(run, PROBE_DELAY_MS);
        }
    }

    private final Runnable run = new Runnable() {
        @Override
        public void run() {
            if (!pending) return;
            pending = false;
            int my = seq;
            boolean hit = probeOnce();
            if (my != seq) return;                 // 在途又有新事件 ⇒ 结果过期，新事件会重新调度
            if (!hit) return;                      // 未命中 / 拿不准 ⇒ 维持隐藏，绝不动作
            if (!st.pageGate) return;              // 卡片已显示，无需复核
            recheckPending = true;                 // 首查命中：挂复核，两次都命中才显示
            if (ui != null) ui.postDelayed(recheck, RECHECK_DELAY_MS);
        }
    };

    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            if (!recheckPending) return;
            recheckPending = false;
            int my = seq;
            boolean hit = probeOnce();
            if (my != seq) return;
            if (!hit) {
                CardDebug.note(ctx, "home probe recheck 未命中 → 放弃显示（过渡残树）");
                return;
            }
            applyShow();
        }
    };

    /** 探测结论落状态（只把 pageGate 翻成 false = 显示；本类**永不**写 true） */
    private void applyShow() {
        if (!st.pageGate) return;
        st.pageGate = false;
        CardDebug.note(ctx, "pageGate=false (home probe 双命中 txt_clock)");
        ov.applyVisibility();
    }

    /**
     * 🔴 窗口内容使用点之二（全仓库仅此与 {@link SettingsPageProbe#probeOnce}）。
     *
     * 返回 true = 查到**可见的** P1 时钟块。返回 false = 「不该显示」——
     * 包含三种情形：① 根窗口确为 ELauncher 但没有时钟块（确认不在 P1）；
     * ② 有时钟块但**不可见 / 跑到屏外**（ViewPager 保留邻页的残树 —— 2026-09-26 真机 G 场景
     * 实锤：P3→P2 的「形态二」里 P2 树会短暂残留 P1 时钟节点且存活 >1.25s，仅按 id 命中
     * 会在 P2 上误显示，必须加可见性过滤）；③ 根窗口拿不到 / 已切走 / 异常。
     * 任何 false 都只导致「维持隐藏」。
     */
    private boolean probeOnce() {
        AccessibilityNodeInfo root = activeRoot();
        if (root == null) {
            CardDebug.note(ctx, "home probe 拿不到根窗口 → 不动作");
            return false;
        }
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !ELA_PKG.equals(pkg.toString())) {
            CardDebug.note(ctx, "home probe 窗口非 ELauncher("
                    + (pkg == null ? "null" : pkg) + ") → 不动作");
            return false;
        }
        return hasVisibleNode(root, HOME_CLOCK_ID);
    }

    /**
     * 读当前根窗口 —— **本类唯一的 `getRootInActiveWindow()` 调用点**。
     *
     * 抽出来是为了让「翻页落到 P1 的双命中探测」与「服务重连时的一次性自检」
     *（v0.8.2 方案 A）共用同一个窗口内容入口 ⇒ 全仓库的窗口内容使用点仍为
     * **两处两个类**（本类 + {@link SettingsPageProbe}），审查口径
     * `grep -rn getRootInActiveWindow` 的**行数不变**。
     * 拿不到（服务刚连上 / 异常）返回 null，调用方一律按"不动作"处理。
     */
    private AccessibilityNodeInfo activeRoot() {
        try {
            return svc.getRootInActiveWindow();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在 root 子树里查 viewId，判断有没有**可见且在屏内**的命中节点。
     *
     * 🔴 可见性过滤不可省：ELauncher 的 ViewPager 会保留邻页 ⇒ P2/P3 树会短暂残留 P1
     * 时钟节点（2026-09-26 真机 G 场景实锤），仅按 id 命中会在 P2 上误判"在 P1"。
     * Tomo 无邻页保留，但同一道过滤对它也无害。
     *
     * ⚠️ 日志字样由"home probe 时钟节点"改为中性的"home probe 节点"（两条调用路径共用）。
     * 该行只进 `card_debug.log`、不被任何回归脚本断言，故不影响判据。
     */
    private boolean hasVisibleNode(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(viewId);
        if (hits == null || hits.isEmpty()) return false;   // 确认不在 P1（静默，高频）
        // 可见性过滤：保留邻页里的时钟节点 id 相同但不可见/在屏外，不能当"P1 实锤"
        for (AccessibilityNodeInfo n : hits) {
            boolean vis = false;
            try {
                vis = n.isVisibleToUser();
            } catch (Throwable ignored) {
            }
            android.graphics.Rect r = new android.graphics.Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Throwable ignored) {
            }
            String rb = r.toShortString();     // intersect 会改写 r，日志先留原值
            boolean onScreen = r.intersect(0, 0,
                    com.inkread.weekread.core.CardSpec.SCREEN_W,
                    com.inkread.weekread.core.CardSpec.SCREEN_H);
            CardDebug.note(ctx, "home probe 节点 visible=" + vis
                    + " bounds=" + rb + " onScreen=" + onScreen);
            if (vis && onScreen) return true;
        }
        return false;
    }

    /**
     * 服务重连时的一次性自检 —— 返回 {@link #CUR_HOME_P1} / {@link #CUR_HOME_NOT_P1} /
     * {@link #CUR_NOT_HOME} / {@link #CUR_UNKNOWN}。调用点只有一个：
     * `CardA11yService.onServiceConnected()`（排在任何 `applyVisibility()` 之前）。
     *
     * 🔴 **调用前必须保证 `st.launchers` 已填好**（`A11yRouter.loadLaunchers()` 已在
     * 本类之前调用），否则会把桌面误判成"别的应用"。
     */
    int checkCurrentScreen() {
        AccessibilityNodeInfo root = activeRoot();
        if (root == null) {
            CardDebug.note(ctx, "reconnect probe 拿不到根窗口 → 维持原行为（按第 1 页）");
            return CUR_UNKNOWN;
        }
        CharSequence cs = root.getPackageName();
        if (cs == null) return CUR_UNKNOWN;
        String pkg = cs.toString();
        // systemui 是**浮层**：此刻"当前应用"并没变，但它不是桌面 ⇒ 不能据此判"不在桌面"
        //（否则下拉通知栏时重连会把 onDesktop 置成 false，而"收起通知栏"不会再发桌面事件
        //  ⇒ 卡片可能一直回不来）。一律拿不准。
        if (SYSTEMUI_PKG.equals(pkg)) {
            CardDebug.note(ctx, "reconnect probe 前台是 systemui 浮层 → 维持原行为");
            return CUR_UNKNOWN;
        }
        if (st.launchers == null || !st.launchers.contains(pkg)) {
            CardDebug.note(ctx, "reconnect probe 前台非桌面 pkg=" + pkg + " ⇒ 不该显示卡片");
            return CUR_NOT_HOME;
        }
        String id = firstPageIdOf(pkg);
        if (id == null) {
            // 本机只认 Tomo / ELauncher 两套指纹。别家桌面没有 P1 判据 ⇒ 拿不准，
            // 维持原行为（显示）——对未知桌面"藏"会让卡片再也回不来，方向反了。
            CardDebug.note(ctx, "reconnect probe 桌面 " + pkg + " 无已知 P1 指纹 → 维持原行为");
            return CUR_UNKNOWN;
        }
        boolean hit = hasVisibleNode(root, id);
        CardDebug.note(ctx, "reconnect probe pkg=" + pkg + " id=" + id + " hit=" + hit
                + " → " + (hit ? "第1页" : "非第1页"));
        return hit ? CUR_HOME_P1 : CUR_HOME_NOT_P1;
    }

    /** 已知桌面 → 它的 P1 特征 id；不认识的桌面返回 null（= 拿不准） */
    private static String firstPageIdOf(String pkg) {
        if (TOMO_PKG.equals(pkg)) return TOMO_CLOCK_ID;
        if (ELA_PKG.equals(pkg)) return HOME_CLOCK_ID;
        return null;
    }
}
