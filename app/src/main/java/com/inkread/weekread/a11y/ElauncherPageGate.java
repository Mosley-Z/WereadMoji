package com.inkread.weekread.a11y;

import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.CardSpec;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;

/**
 * ELauncher 桌面的**翻页 / resume 判据**（TASK-006 从 `CardA11yService` 整段平移而来，含全部注释）。
 *
 * 🔴 **TASK-010（2026-09-26）改动一处**：结算的「swipe 落到第 1 页」不再直接显示卡片，
 * 改为 {@link ElaHomeProbe} 内容探测双命中后才显示 —— ELauncher 桌面升级 3 页后残值判据
 * 被打穿（P3→P2 = 524254 与 P2→P1 = 524255 只差 1，P2→P3 还会误报 524255），事件层无解。
 * 「离开」与「resume」两条路径判据**一行未改**；权威规格仍在 `docs/04_桌面让位判据.md` §2。
 *
 * ⚠️ 它与 {@link TomoPageGate} **语义相反**（本桌面的 HOME / 亮屏一律回第 1 页，
 * Tomo 是回上次那一页）⇒ **不要合并成一个 Gate**。
 */
final class ElauncherPageGate {

    private final Context ctx;
    private final android.os.Handler ui;
    private final CardVisibilityState st;
    private OverlayController ov;
    private A11yEventRouter rt;
    private TomoPageGate tomo;
    private ElaHomeProbe homeProbe;

    /** 翻页容器的类名（与 {@link TomoPageGate} 同义，两个 Gate 各判各的、常量各持一份） */
    private static final String SWIPE_NODE_CLS = "android.widget.FrameLayout";
    /** `CONTENT_CHANGE_TYPE_TEXT` —— 翻页伴随那条 TextView 实测是 cct=2 */
    private static final int CCT_TEXT = 2;

    ElauncherPageGate(Context ctx, android.os.Handler ui, CardVisibilityState st) {
        this.ctx = ctx;
        this.ui = ui;
        this.st = st;
    }

    void attach(OverlayController ov, A11yEventRouter rt, TomoPageGate tomo,
                ElaHomeProbe homeProbe) {
        this.ov = ov;
        this.rt = rt;
        this.tomo = tomo;
        this.homeProbe = homeProbe;
    }

    /** 作废在途的「落到第 1 页」内容探测（服务断开 / 手动重置用） */
    void cancelHomeProbe() {
        if (homeProbe != null) homeProbe.cancel();
    }

    // ────────────────────────────────────────────────────────────────────────
    // ELauncher 桌面翻页 / resume 判据（v0.6.0）
    //
    // 与 Tomo 那套（st.swipeSub / st.swipeText …）**完全独立**：那个桌面的翻页容器是
    // 一个 `FrameLayout`，而 ELauncher 用 `androidx.viewpager.widget.ViewPager`，
    // 事件语义不同。两套判据用"这个包发过 ViewPager 事件吗"（{@link #sawViewPager}）
    // 自动分流，各自只在自己那一类桌面上生效。
    //
    // ELauncher 的事件面（2026-09-24 三轮真机探针，见 验证记录/37）：
    //
    //   ① 翻页 · 离开第 1 页（P1→P2）  `TextView cct=2 sx=524266` → `ViewPager cct=3 sx=480`
    //   ② 翻页 · 落到第 1 页（P2→P1）  `TextView cct=2 sx=524255` → `ViewPager cct=3 sx=480`
    //   ③ 桌面 resume（亮屏 / 按 HOME / 从应用返回）  `FrameLayout cct=1/3 sx=0` → `ViewPager cct=3 sx=480`
    //   ④ 时钟整分跳字                 `TextView cct=2 sx=0`（×2，**没有** ViewPager）
    //   ⑤ 边界回弹（在第 1 页右滑、在第 2 页左滑）  `ViewPager cct=1 sx=480`（×2，**没有** TextView）
    //   ⑥ 进「设置」隐藏页（2026-09-25 漂移）  现只投 `ViewPager cct=3 sx=480`（与①②③收尾同形），
    //     sx=960 不再投递 ⇒ 事件层无解，改内容探测（TASK-009 / SettingsPageProbe）
    //   ⑦ 3 页桌面的翻页残值（2026-09-26 实测，验证记录/55）：
    //     P3→P2 = 524254（比「落到」的 524255 还小 1 ⇒ 被误判成落到）；P2→P3 两种形态
    //     （无残值 / 也误报 524255）；ViewPager 收尾事件 sx 恒 0（不再投 480）⇒
    //     「落到」方向事件层无解，改内容探测（TASK-010 / ElaHomeProbe）
    //
    // ★ 为什么必须"等 ViewPager 来了才算"（而不是见到 TextView 就判）：
    //   · ④ 整分那条 `TextView` 用 sx=0 就能挡掉，但 ② 与 ④ 的**类名与 cct 完全一样**，
    //     只有 sx 不同 —— 多一道"有没有 ViewPager 伴随"的确认，等于把整分、
    //     以及别的桌面（Tomo 的时钟块）都挡在门外。
    //   · `st.sawViewPager` 是在收到 ViewPager 事件时才置位的，而 ViewPager 又是
    //     **后**到的那一条 —— 所以结算必须推迟到窗口到期，不能在 TextView 上就地判。
    //
    // ★ ③ 为什么可以判成"回到第 1 页"：ELauncher 的 HOME / 亮屏 / 从应用返回
    //   **一律回到第 1 页**（三轮实测 5/5：从第 2 页按 HOME、亮屏、进应用再返回，
    //   截图像素真值全部落在第 1 页），与 Tomo"回到原来那一页"的行为**相反**。
    //   所以这条 resume 指纹就是"应该显示卡片"的直接理由，见 {@link #settleElauncherWindow}。
    // ────────────────────────────────────────────────────────────────────────

    /** ELauncher 判据的窗口是否开着 */
    /** 窗口里见过的"翻页伴随"桌面 `TextView` 的 sx；0 = 没见过 */
    /** 窗口里见过桌面 `FrameLayout`（sx=0，= 桌面 resume）吗 */
    /** 窗口里见过桌面 `ViewPager` 吗（翻页与 resume 都会发，用来确认"这是本判据认的桌面"） */
    /** 本窗口是哪个桌面包发起的 —— 结算时用来核对它是"发 ViewPager 的那类桌面" */

    /** ELauncher 判据的窗口长度（毫秒）。实测一次翻页/resume 的几条事件在 250ms 内到齐。 */
    private static final long ELA_WINDOW_MS = 400L;
    /**
     * 翻页伴随的那条桌面 `TextView` 事件的 `scrollX` 下限。
     *
     * 实测值是 524255 / 524266（≈2^19，是那个时钟块 View 在翻页动画里的残值），
     * 而**时钟整分那条是 0**。取 500000 作分界，把整分干净挡掉。
     */
    private static final int ELA_SX_MIN = 500000;
    /** 实测：**落到第 1 页**时那条 `TextView` 的 sx（250ms / 600ms 两种手势各 2 次，全一致） */
    private static final int ELA_SX_TO_P1 = 524255;
    /** 实测：**离开第 1 页（去第 2 页）**时那条 `TextView` 的 sx（同上，全一致） */
    private static final int ELA_SX_TO_P2 = 524266;
    /**
     * 方向分界：取上面两个实测值的**中点**。
     *
     * 🔴 这里必须用"中点分界"而不是"各自带容差比对" —— 两个值只差 11，
     * 第一版给每个值配了 ±16 的容差，结果 `524255` 先被 `524266` 那个分支
     * 吞掉（`|524255-524266| = 11 ≤ 16`），"回到第 1 页"全被判成"离开第 1 页"
     * （真机 5 处复现）。**容差一定要小于两个实测值间距的一半。**
     */
    private static final int ELA_SX_SPLIT = (ELA_SX_TO_P1 + ELA_SX_TO_P2) / 2;
    /** 认得出这两个实测值的范围；超出即"认不出"⇒ 不动作（宁可不改也不改错） */
    private static final int ELA_SX_LO = ELA_SX_TO_P1 - 10;
    private static final int ELA_SX_HI = ELA_SX_TO_P2 + 10;

    /**
     * 作废 ELauncher 判据的未结算窗口（不清 {@code st.pageGate} 本身）。
     *
     * 用途与 {@link #cancelSwipeWindow} 对称：亮屏、服务重连、手动重置时，
     * 把"半截窗口"扔掉，免得它带着过期的证据在 400ms 后改状态。
     */
    void cancelElaWindow() {
        if (!st.elaOpen) return;
        st.elaOpen = false;
        st.elaTextSx = 0;
        st.elaFrame = false;
        st.elaVp = false;
        st.elaPkg = null;
        if (ui != null) ui.removeCallbacks(applyElaWindow);
    }

    /** 把 ELauncher 判据的字段全部归零（服务重连 / 手动重置用，无回调可摘） */
    void resetElaFields() {
        st.elaOpen = false;
        st.elaTextSx = 0;
        st.elaFrame = false;
        st.elaVp = false;
        st.elaPkg = null;
    }

    /**
     * ELauncher 判据的事件入口：把窗口内三类事件记下来，到期交给
     * {@link #settleElauncherWindow} 结算。形态表见 {@link #elaOpen} 上面那段说明。
     *
     * 只收三种（其余一律不参与）：
     *   · `ViewPager`                    —— 确认"这是本判据认的桌面"（Tomo 从不发这条）
     *   · `FrameLayout` 且 `sx==0`        —— 桌面 resume（亮屏 / 按 HOME / 从应用返回）
     *   · `TextView` 且 `cct=2` 且 `sx≥ELA_SX_MIN` —— 翻页伴随（整分那条 sx=0，被挡掉）
     */
    void noteElauncherWindow(String pkg, String cls, AccessibilityEvent event) {
        if (!rt.isLauncher(pkg)) return;
        if (CardA11yService.sOwnUiForeground) return;              // 在自家界面里一切两清
        boolean isVp = cls.endsWith("ViewPager");
        boolean isFrame = SWIPE_NODE_CLS.equals(cls);
        boolean isText = cls.endsWith("TextView");
        if (!isVp && !isFrame && !isText) return;
        int sx = 0;
        try {
            sx = event.getScrollX();
        } catch (Throwable ignored) {
        }
        if (isFrame && sx != 0) return;            // resume 指纹必是 sx=0
        if (isText) {
            if (sx < ELA_SX_MIN) return;           // 时钟整分跳字（sx=0）在这里被挡掉
            int cct = 0;
            try {
                cct = event.getContentChangeTypes();
            } catch (Throwable ignored) {
            }
            if (cct != CCT_TEXT) return;           // 翻页伴随那条实测是 cct=2
        }
        st.elaOpen = true;
        st.elaPkg = pkg;
        if (isVp) st.elaVp = true;
        if (isFrame) st.elaFrame = true;
        if (isText) st.elaTextSx = sx;
        if (ui != null) {
            ui.removeCallbacks(applyElaWindow);
            ui.postDelayed(applyElaWindow, ELA_WINDOW_MS);
        }
    }

    /**
     * ELauncher 判据的窗口结算。分流规则（每条都在三轮真机上验过，见 {@link #elaOpen}）：
     *
     *   · 窗口里**没有** `ViewPager`            → 不动
     *     （Tomo 的 FrameLayout 翻页事件、ELauncher 的时钟整分都从这里被丢掉）
     *   · 有 `FrameLayout(sx=0)`               → **落到第 1 页**（桌面 resume，实测 5/5）
     *   · 有 `TextView` 且 sx ∈ [524245, 524276] → 真翻页，按 {@link #ELA_SX_SPLIT} 定方向
     *     （> 分界 = 离开第 1 页；≤ 分界 = 落到第 1 页 —— ⚠️ TASK-010 起落到侧须经
     *     {@link ElaHomeProbe} 内容探测双命中才真正显示）
     *   · 其余 sx 认不出来                      → 不动（宁可不改，也不改错）
     */
    void settleElauncherWindow() {
        if (!st.elaOpen) return;
        st.elaOpen = false;
        if (ui != null) ui.removeCallbacks(applyElaWindow);
        boolean frame = st.elaFrame, vp = st.elaVp;
        int sx = st.elaTextSx;
        String pkg = st.elaPkg;
        st.elaFrame = false;
        st.elaVp = false;
        st.elaTextSx = 0;
        st.elaPkg = null;
        // ① 没有 ViewPager ⇒ 不是"发 ViewPager 的那类桌面"的翻页/resume
        if (!vp || pkg == null || !st.sawViewPager.contains(pkg)) return;
        boolean leave;
        if (frame) {
            leave = false;                          // ② 桌面 resume ⇒ 回到第 1 页
        } else if (sx >= ELA_SX_LO && sx <= ELA_SX_HI) {
            leave = sx > ELA_SX_SPLIT;              // ③/④ 真翻页，按 sx 落在分界的哪一侧定向
        } else {
            // ⑤ 认不出的 sx（比如 ROM 换了残值）⇒ 不动作，只留痕，便于日后诊断
            CardDebug.note(ctx, "elaSwipe 不动 (frame=" + frame + " vp=" + vp
                    + " sx=" + sx + " cur=" + st.pageGate + ")");
            return;
        }
        CardDebug.note(ctx, "elaSwipe frame=" + frame + " sx=" + sx
                + " → " + (leave ? "离开第1页" : "落到第1页") + " (cur=" + st.pageGate + ")");
        // TASK-009：resume / 落到第 1 页都是「人确实在 P1」的实锤 ⇒ 清设置页闸门让卡片
        // 立即回来，并记抑制窗锚点（过渡残树会让紧跟着的内容探测误报，见 SettingsPageProbe）。
        // ⚠️ 3 页桌面后「swipe 落到」也可能是误判（P3→P2 = 524254、P2→P3 形态二 = 524255，
        // 见 ElaHomeProbe 头注），但这里照旧清 settingsGate / 刷 settledAt 是安全的：
        // 两者只影响「设置页让位」的判定，而人在 P2/P3 时 settingsGate 本就该是 false，
        // settledAt 的抑制窗反而正好盖住该场景下 tap 进设置的过渡期。
        boolean dirty = false;
        if (!leave && st.settingsGate) {
            st.settingsGate = false;
            dirty = true;
            CardDebug.note(ctx, "settingsGate=false (ela settle → 落到第1页)");
        }
        if (!leave) st.settingsSettledAt = android.os.SystemClock.uptimeMillis();
        // ── TASK-010：显示方向按证据强度分流 ──
        // · 离开 ⇒ 事件层 524266 可靠，直接隐藏，并作废在途的"回 P1"探测；
        // · resume（frame 指纹）⇒ 必落第 1 页（5/5 实测）⇒ 直接显示（这同时是
        //   探测漏报时的天然恢复路径：任何 HOME / 进出应用都会走到这里）；
        // · swipe 落到 ⇒ 3 页桌面残值判据已不可信（524254/524255 差 1、P2→P3 两种形态），
        //   改由内容探测双命中 txt_clock 后才显示（ElaHomeProbe）。
        if (leave) {
            cancelHomeProbe();
            if (!st.pageGate) {
                st.pageGate = true;
                dirty = true;
            }
        } else if (frame) {
            cancelHomeProbe();
            if (st.pageGate) {
                st.pageGate = false;
                dirty = true;
            }
        } else {
            if (homeProbe != null) homeProbe.schedule();
        }
        if (dirty) ov.applyVisibility();
    }

    /** {@link #ELA_WINDOW_MS} 到期回调 */
    private final Runnable applyElaWindow = new Runnable() {
        @Override
        public void run() {
            settleElauncherWindow();
        }
    };
}