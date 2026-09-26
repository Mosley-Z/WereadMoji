package com.inkread.weekread.a11y;

import com.inkread.weekread.core.CardDebug;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * ELauncher「设置」隐藏页的**内容探测**（TASK-009）。
 *
 * ── 为什么需要它 ──
 * 「设置」是 ViewPager 的一个隐藏页，tap 进去**不换窗口、不换 Activity**，事件层只投一条
 * `ViewPager cct=3 sx=480` —— 与 resume / 翻页的收尾事件**完全同形**（`docs/04` §2.2）。
 * 曾经可靠的 `sx=960` 已漂移不再投递 ⇒ 事件层原理性无解，只能换内容信号。
 *
 * ── 判据（v4，2026-09-26 真机四轮迭代）──
 * 设置页节点树里有特征 id `com.wetao.elauncher:id/settings_top`
 * （真机实证：设置页恰 1 处；P1/P2 **静置**树中均为 0 —— uiautomator dump 实测）。
 * ⚠️ 但 **HOME / P2→P1 滚动的过渡窗内（约 1–2s）**，ViewPager 邻页保留策略会让
 * settings 子树短暂残留 ⇒ 单次命中不可信。所以三层防误报（失败方向都朝"少让位"）：
 *
 * · **resume 序列免疫**：调度时若该 ViewPager 事件属于 resume 序列（窗口里有
 *   FrameLayout(sx=0) 指纹）⇒ 命中一律判残树、只许清不许置 —— resume 必落第 1 页
 *   （5/5 实测），此刻不可能真的在设置页。tap 跳转没有 FrameLayout 伴随 ⇒ 不受限。
 * · **settle 抑制窗（兜底）**：Ela 判据结算出「resume / 落到第 1 页」后
 *   {@link #SUPPRESS_MS} 内禁止置位。
 * · **置位二次复核**：首查命中后再等 {@link #RECHECK_DELAY_MS} 复查一次，
 *   两次都命中才置位（覆盖 tap 跳转路径上的残树）。
 * 另有事件层硬实锤先行：`sx=0` 事件只可能来自第 1 页 ⇒ Router 里 page==0 直接清闸
 * （见 handleDesktopPage），以及 {@link #GATE_MAX_MS} 兜底超时。
 *
 * 清闸方向**不受任何抑制**：探测返回 false 且窗口确是 ELauncher ⇒ 立即清（恢复要快）。
 *
 * ── 安全设计（失败方向只允许"少让位"，绝不允许"藏死"）──
 * · **命中才藏**：只有根窗口确为 ELauncher 且（复核）查到 settings_top 才置位；
 *   拿不到根窗口 / 异常 / 窗口已切走 ⇒ 一律**维持现状**（静默降级）。
 * · **id 有包名命名空间**：Tomo 的窗口永远查不中 `com.wetao.elauncher:id/*` ⇒
 *   两桌面自动适配，Tomo 零改动（它也不发 ViewPager 事件，本类根本不会被调度）。
 * · **seq 防过期**：探测在途又来了新事件 ⇒ 旧结果作废，由新事件重新调度。
 * · **成本**：每次探测 = 1 次 getRootInActiveWindow IPC（几 ms）+ 1 次按 id 查找；
 *   仅用户翻页 / 回桌面时发生，空闲零开销。置位路径最多两轮探测（≈1.25s）。
 *
 * 🔴 **窗口内容使用点之一**（TASK-010 起与 {@link ElaHomeProbe} 并列，全仓库仅这两个）——
 * 它存在的代价是 a11y 配置从 canRetrieveWindowContent=false 翻成 true（2026-09-26 用户拍板，
 * help.txt 权限段已同步改为如实描述）。任何别的需求都**不许**在这两个类之外再碰窗口内容；
 * 动本类之前必读 `docs/04` §2.2 与 `tasks/TASK-009_设置页内容探测.md`。
 */
final class SettingsPageProbe {

    private final android.content.Context ctx;
    private final CardA11yService svc;
    private final android.os.Handler ui;
    private final CardVisibilityState st;
    private OverlayController ov;

    /** ELauncher 桌面包 —— 探测只认它的窗口，别的包（含 Tomo）一律"维持现状" */
    private static final String ELA_PKG = "com.wetao.elauncher";
    /** 设置页特征 id：设置页恰 1 处；P1/P2 静置树中 0 处（验证记录/53） */
    private static final String SETTINGS_ID = "com.wetao.elauncher:id/settings_top";
    /** 事件后等多久首查（毫秒）。盖过翻页动画（实测 ~300ms） */
    private static final long PROBE_DELAY_MS = 650L;
    /** 首查命中后，等多久复核第二次（毫秒）。两次之间过渡残树会被回收 */
    private static final long RECHECK_DELAY_MS = 600L;
    /**
     * settle 抑制窗（毫秒，纯兜底）：主防线上 resume 序列免疫（suspectResume）与
     * page==0 取消探测已覆盖全部实测残树场景，本窗只挡"settle 刚实锤落 P1、
     * 紧跟着的非 resume 探测还撞上未回收残树"的缝隙。1500ms 的依据：
     * 残树实测存活 ≤1.3s（复核对 +1.25s，被本窗挡住）；HOME→tap 进设置 1.7s 放行
     * （真机 13:34 实测 2.5s 会误伤快速进入，故从 2500 压到 1500）。
     */
    private static final long SUPPRESS_MS = 1500L;
    /**
     * 闸门兜底超时（毫秒）—— 与 Router 的 iconGate {@code GATE_MAX_MS} 同一个先例：
     * 超时无条件清闸，失败方向只允许"多显示一会儿"，绝不允许"卡片再也不出现"。
     * 覆盖一切未想到的"人已回 P1 但三条恢复路径都没赶上"的角落。
     */
    private static final long GATE_MAX_MS = 180_000L;

    /** 探测序号：每调度一次 +1；回调时对不上号 = 期间有新事件 ⇒ 结果过期，丢弃 */
    private int seq = 0;
    /** 是否有探测在途（同一时刻只挂一个，后来的事件把它顶掉并顺延） */
    private boolean pending = false;
    /** 复核在途标记（复核不做二次在途管理 —— seq 已足够防过期） */
    private boolean recheckPending = false;
    /**
     * 本次探测是否属于 resume 序列（调度时由 Router 传入 = 窗口里有 FrameLayout(sx=0) 指纹）。
     * resume 必落第 1 页（5/5 实测）⇒ 这类探测**命中只能是过渡残树** ⇒ 只许清、不许置。
     * tap 跳转进设置页没有 FrameLayout 伴随 ⇒ 不受此限，也不受抑制窗拖累。
     */
    private boolean suspectResume = false;

    SettingsPageProbe(CardA11yService svc, android.os.Handler ui, CardVisibilityState st) {
        this.ctx = svc;
        this.svc = svc;
        this.ui = ui;
        this.st = st;
    }

    void attach(OverlayController ov) {
        this.ov = ov;
    }

    /** 作废在途探测与复核（服务断开 / 页码已由 sx≥960 定死时调，别让过期结果改状态） */
    void cancel() {
        pending = false;
        recheckPending = false;
        if (ui != null) {
            ui.removeCallbacks(run);
            ui.removeCallbacks(recheck);
        }
    }

    private void armGateTimeout() {
        if (ui == null) return;
        ui.removeCallbacks(gateTimeout);
        ui.postDelayed(gateTimeout, GATE_MAX_MS);
    }

    private void disarmGateTimeout() {
        if (ui != null) ui.removeCallbacks(gateTimeout);
    }

    /** 兜底超时：闸门置位后 3 分钟内没有任何恢复路径赶上 → 无条件清（宁可多显示） */
    private final Runnable gateTimeout = new Runnable() {
        @Override
        public void run() {
            if (!st.settingsGate) return;
            st.settingsGate = false;
            CardDebug.note(ctx, "settingsGate=false (timeout " + (GATE_MAX_MS / 1000) + "s)");
            ov.applyVisibility();
        }
    };

    /**
     * 歧义页码事件（page≤1）后调用：挂 / 顺延一次探测。
     *
     * @param inResumeSequence 这条 ViewPager 事件是否属于 resume 序列
     *        （窗口里见过 FrameLayout(sx=0)）—— 见 {@link #suspectResume}。
     *
     * 只会由 {@code A11yEventRouter.handleDesktopPage} 调 —— 而 Tomo 不发 ViewPager 事件，
     * 那条路在 Tomo 上根本走不到 ⇒ 本类在 Tomo 上零调用、零影响。
     */
    void schedule(boolean inResumeSequence) {
        seq++;
        pending = true;
        recheckPending = false;
        suspectResume = inResumeSequence;
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
            if (!hit) {
                applyGate(false);                  // 清闸不受抑制 —— 恢复要快
                return;
            }
            if (st.settingsGate) return;           // 已经让位中，无需复核
            if (suspectResume) {
                // resume 必落第 1 页 ⇒ 此刻的命中只能是过渡残树，绝不置位
                CardDebug.note(ctx, "probe hit 但属 resume 序列 → 判残树，不置位");
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (st.settingsSettledAt != 0
                    && now - st.settingsSettledAt < SUPPRESS_MS) {
                CardDebug.note(ctx, "probe hit 但在 settle 抑制窗内 → 不置位");
                return;
            }
            // 首查命中：挂复核，两次都命中才置位（防过渡残树）
            recheckPending = true;
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
                CardDebug.note(ctx, "recheck 未命中 → 放弃置位（过渡残树）");
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (st.settingsSettledAt != 0
                    && now - st.settingsSettledAt < SUPPRESS_MS) {
                CardDebug.note(ctx, "recheck hit 但在 settle 抑制窗内 → 不置位");
                return;
            }
            applyGate(true);
        }
    };

    /** 探测结论落状态（只写 settingsGate，显隐仍走单一出口） */
    private void applyGate(boolean gate) {
        if (gate == st.settingsGate) return;
        st.settingsGate = gate;
        CardDebug.note(ctx, "settingsGate=" + gate + " (content probe)");
        if (gate) armGateTimeout();
        else disarmGateTimeout();
        ov.applyVisibility();
    }

    /**
     * 🔴 窗口内容使用点之一（另一个在 {@link ElaHomeProbe#probeOnce}）。
     *
     * 返回值三态：true = 查到设置页特征（首查 / 复核共用）；
     * false = 确认**不在**设置页（仅当根窗口确是 ELauncher 才会给出）；
     * 其余一切拿不准的情形（根窗口为 null / 异常 / 窗口已切走）返回
     * {@code st.settingsGate} 原值 = **维持现状**，绝不在拿不准时改状态。
     */
    private boolean probeOnce() {
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return st.settingsGate;
            CharSequence pkg = root.getPackageName();
            if (pkg == null || !ELA_PKG.equals(pkg.toString())) return st.settingsGate;
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(SETTINGS_ID);
            return hits != null && !hits.isEmpty();
        } catch (Throwable t) {
            return st.settingsGate;
        }
    }
}
