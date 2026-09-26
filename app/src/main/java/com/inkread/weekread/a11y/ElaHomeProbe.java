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
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
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
            List<AccessibilityNodeInfo> hits =
                    root.findAccessibilityNodeInfosByViewId(HOME_CLOCK_ID);
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
                CardDebug.note(ctx, "home probe 时钟节点 visible=" + vis
                        + " bounds=" + rb + " onScreen=" + onScreen);
                if (vis && onScreen) return true;
            }
            return false;
        } catch (Throwable t) {
            CardDebug.note(ctx, "home probe 异常 → 不动作");
            return false;
        }
    }
}
