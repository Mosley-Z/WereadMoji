package com.inkread.weekread.a11y;

import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.CardPrefs;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * 桌面悬浮卡片的宿主 —— TASK-006 之后退化为**薄壳**：
 * 只留 `AccessibilityService` 的生命周期、静态入口，以及把事件转给 {@link A11yEventRouter}。
 *
 * 走 TYPE_ACCESSIBILITY_OVERLAY：**不需要 SYSTEM_ALERT_WINDOW 权限**，
 * 用户到「系统设置 → 无障碍 → 微读墨记」开一次开关即可，重启后由系统自动拉起。
 *
 * ── 拆到哪儿去了（TASK-006，2,228 行 → 5 个类）──
 * · {@link A11yEventRouter}        事件订阅与分发、桌面识别、通知栏 / 图标点按闸门
 * · {@link TomoPageGate}           Tomo 桌面的翻页判据（FrameLayout 条数结算）
 * · {@link ElauncherPageGate}      ELauncher 桌面的判据（ViewPager + TextView 的 sx）
 * · {@link OverlayController}      悬浮窗生命周期 + **显隐单一出口** `applyVisibility()`
 * · {@link CardContentController}  卡片显示什么内容（拉数据 / 切周期 / 本记 / 跳转）
 * · {@link CardVisibilityState}    上面几处共享的运行期状态（纯数据，无逻辑）
 *
 * 分工原则：**Gate 与 Router 只改状态，只有 OverlayController 写 `setVisibility`** ——
 * 这样"三条防藏死重算路径"始终收敛在一个出口上。
 *
 * ── 五类"让位"（判据全在两个 Gate 与 Router 里，本壳不参与）──
 * ① 进了 ELauncher 的隐藏页（scrollX≥960）；② 通知栏已下拉；③ 进了我们自己的界面；
 * ④ 用户点按了桌面上的图标；⑤ 翻离桌面第 1 页。
 * 五者都只影响"是否显示"，**不翻转** onDesktop，避免出现回不来的状态。
 *
 * 注意：a11y 配置原为 canRetrieveWindowContent="false"（只读事件自带包名，不抓取内容）。
 * TASK-009（2026-09-26 用户拍板）翻成 true：ELauncher「设置」页的事件指纹漂移后
 * 事件层无解，只能查节点树。全仓库**唯二**使用窗口内容处 = {@link SettingsPageProbe} 与
 * {@link ElaHomeProbe}
 * （自限：仅歧义页码事件触发、各只查一个控件 id、命中才让位、不存储不上传）。
 */
public class CardA11yService extends AccessibilityService {

    private static CardA11yService sInstance;

    /**
     * 自家界面（主页 / 设置页）是否在前台。
     *
     * ⚠️ 这个标志存在的理由 —— 见 §「服务重连时卡片会压在自己界面上」：
     * 无障碍事件只能告诉我们"窗口变了"，而 TYPE_WINDOW_STATE_CHANGED **不会**在
     * 服务刚连上时补发一次当前 Activity 的事件。于是服务重连的那一刻，
     * 我们会以为自己还在桌面（onDesktop 的默认假设），把卡片显示出来 ——
     * 恰好盖在用户正在看的主页/设置页上。
     * 冷启动 App（桌面点图标 → 进程被杀过 → 服务跟着重连）必现。
     *
     * 服务和 Activity 同进程，所以让 Activity 自己在 onResume/onPause 里直接告诉服务，
     * 比任何"事后猜"都可靠。
     */
    /**
     * ⚠️ 包级可见（不是 private）：TASK-006 之后两个 Gate 与 Router 都要读它，
     * 而它**必须保持 static** —— 服务还没连上时 Activity 也会调
     * {@link #noteOwnUiForeground}，那时还没有 `CardVisibilityState` 可写。
     */
    static boolean sOwnUiForeground = false;

    /**
     * 「离开自家界面时强制重算一次显隐」的**一次性**意图标记（v0.8.1）。
     *
     * ★ 为什么需要它（真机复现，2026-09-27）──
     * 设置页的「重新同步卡片显示」按钮调 {@link #resetPageGate()}，其中
     * `ov.applyVisibility()` 在**设置页里**执行时，`sOwnUiForeground=true`
     * ⇒ 恒定算出 GONE（这是对的：此刻人确实在设置页）。
     * 但用户**离开设置页回桌面**时，{@link #noteOwnUiForeground(boolean)} 那一路
     * 刻意什么都不做（防墨水屏闪屏），指望"紧随其后的窗口事件"接手重算 ——
     * 而实测那条桌面窗口事件经常会因为 `wasOwnUi` 已被别的事件清成 false 而
     * **不触发重算** ⇒ 卡片不会立即出现，按钮等于没生效。
     *
     * 用户主动点了这个按钮 = 明确要求"把卡片弄回来" ⇒ 破例重算一次是符合意图的，
     * 而且只走这一次（用完即清），不会破坏"常规离开不重算"的闪屏防护。
     */
    private static boolean sForceRecomputeOnLeave = false;

    /** 亮屏后多久之内的翻页指纹一律忽略（毫秒）—— 见 TomoPageGate 里的说明 */
    private static final long SCREEN_ON_IGNORE_MS = 700L;
    /** 只认"屏幕亮了"这一个动作 */
    private BroadcastReceiver screenOnRx;

    private android.os.Handler ui;
    private CardVisibilityState st;
    private OverlayController ov;
    private CardContentController content;
    private A11yEventRouter router;
    private TomoPageGate tomo;
    private ElauncherPageGate ela;
    private SettingsPageProbe probe;
    private ElaHomeProbe homeProbe;

    // ── 供同进程其它组件调用 ──

    /** 服务是否已经连上（系统把开关打开了并且服务活着） */
    public static boolean isConnected() {
        return sInstance != null;
    }

    /** 系统设置里这个无障碍服务是否处于已启用状态（可能还没连上） */
    public static boolean isEnabledInSystem(Context c) {
        try {
            AccessibilityManager am =
                    (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) return false;
            String prefix = c.getPackageName() + "/";
            for (AccessibilityServiceInfo info : list) {
                String id = info.getId();
                if (id != null && id.startsWith(prefix)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 偏好或数据变化后叫它一声，让卡片与当前状态同步（同进程静态调用） */
    public static void sync() {
        if (sInstance != null) sInstance.refresh();
    }

    /**
     * 由自家**每一个** Activity（MainActivity / SettingsActivity / HelpActivity）
     * 在 onResume / onPause 里调用 —— 新增 Activity 时务必补上，否则卡片会压在那个页面上。
     *
     * @param fg true = 用户进了我们自己的界面（卡片必须让位）
     *           false = 离开了（**不立刻重算可见性**，见下）
     */
    public static void noteOwnUiForeground(boolean fg) {
        if (sOwnUiForeground == fg) return;
        sOwnUiForeground = fg;
        if (sInstance == null) return;
        if (fg) {
            // 进自家界面：立刻隐藏，别让卡片压住主页/设置页
            sInstance.ov.applyVisibility();
            return;
        }
        // 离开自家界面时刻意什么都不做：此刻 onDesktop 可能还是进入前那个旧值，
        // 立刻重算会让卡片闪一下（墨水屏上一次无谓刷新很显眼）。
        // 交给紧随其后的那个窗口事件去定：回桌面 → 桌面事件把它显示出来。
        //
        // 🔴 v0.8.1 例外：用户刚点过「重新同步卡片显示」⇒ 这是他**明确要求**把卡片
        // 弄回来，而那个按钮在设置页里调用时必然被 sOwnUiForeground=true 压成 GONE。
        // 若不在这里补一次重算，按钮就形同虚设（真机实测：清闸门后卡片仍不出现）。
        // 只走一次，用完即清 —— 不破坏上面那条"常规离开不重算"的闪屏防护。
        if (sForceRecomputeOnLeave) {
            sForceRecomputeOnLeave = false;
            CardDebug.note(sInstance, "ownUi=false → 强制重算（手动同步按钮意图）");
            sInstance.ov.applyVisibility();
        }
    }

    /**
     * 清掉「翻离了第 1 页」闸门，让卡片回到"按当前前台重新算一遍"的状态。
     *
     * 这是**给用户留的出口**：万一某次翻页事件被系统漏投、卡片停在隐藏状态，
     * 设置页的「重新同步卡片显示」按钮就调它。同类的出口还有：服务重连
     * （关掉再打开无障碍开关 / 重启设备）时 {@link #onServiceConnected} 里归零。
     */
    public static void resetPageGate() {
        if (sInstance == null) return;
        sInstance.st.pageGate = false;
        sInstance.st.settingsGate = false;          // TASK-009：手动出口覆盖所有瞬态闸
        sInstance.st.lastHomeResolveAt = 0L;        // 顺手让下次桌面事件重查默认桌面
        sInstance.tomo.cancelSwipeWindow();
        sInstance.ela.cancelElaWindow();
        sInstance.ela.cancelHomeProbe();            // TASK-010：在途的"回 P1"探测一并作废
        // 🔴 v0.8.1：此刻人在设置页（sOwnUiForeground=true），下面这次 applyVisibility()
        // 必然算出 GONE；真正让它生效的是**离开设置页那一刻**的强制重算，所以这里
        // 先把这个一次性意图挂上，见 sForceRecomputeOnLeave 的注释。
        sForceRecomputeOnLeave = true;
        sInstance.ov.applyVisibility();
        CardDebug.note(sInstance, "resetPageGate (手动)");
    }

    // ── 生命周期 ──

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        if (ui == null) ui = new android.os.Handler(android.os.Looper.getMainLooper());
        if (st == null) {
            // 状态与四个协作对象只建一次；重连只做归零，不重建（否则会丢掉
            // launchers / homeComponents / sawViewPager 这些"认得哪些桌面"的缓存）。
            st = new CardVisibilityState();
            ov = new OverlayController(this, ui, st);
            content = new CardContentController(this, ov, st);
            tomo = new TomoPageGate(this, ui, st);
            ela = new ElauncherPageGate(this, ui, st);
            probe = new SettingsPageProbe(this, ui, st);
            homeProbe = new ElaHomeProbe(this, ui, st);
            router = new A11yEventRouter(this, ui, st);
            ov.attachContent(content);
            tomo.attach(ov, router);
            ela.attach(ov, router, tomo, homeProbe);
            probe.attach(ov);
            homeProbe.attach(ov);
            router.attach(ov, tomo, ela, probe);
        }
        st.resetAll();
        sForceRecomputeOnLeave = false;             // v0.8.1：重连时旧的一次性意图作废
        registerScreenOn();
        refresh();
        CardDebug.note(this, "service connected, enabled=" + CardPrefs.isEnabled(this)
                + ", ownUi=" + sOwnUiForeground);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        router.onEvent(event);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        unregisterScreenOn();
        cancelPending();
        ov.removeWindow();
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        unregisterScreenOn();
        cancelPending();
        ov.removeWindow();
        sInstance = null;
        super.onDestroy();
    }

    // ── 内部 ──

    /** 把「偏好 + 数据 + 可见性」一次性对齐 */
    private void refresh() {
        if (!CardPrefs.isEnabled(this)) {
            ov.removeWindow();
            return;
        }
        ov.ensureWindow();
        content.refresh();
    }

    /** 服务要走了，把还没生效的页码回调与闸门超时都撤掉，别让它们去碰已经销毁的窗口 */
    private void cancelPending() {
        st.pendingPage = -1;
        st.resetWindows();
        tomo.cancelSwipeWindow();
        ela.cancelElaWindow();
        router.cancelPendingCallbacks();
    }

    /**
     * 订阅系统亮屏广播 —— 见 TomoPageGate 里 `SCREEN_ON_IGNORE_MS` 说明为什么要它。
     *
     * 运行时注册即可，不需要权限、也不用写进清单（`ACTION_SCREEN_ON` 是系统广播，
     * 只允许系统发，但任何应用都能注册接收）。注册失败不影响主流程 ——
     * 拿不到就退回"靠 `cct=0` 认亮屏"那套，所以这里吞掉异常、只记一条日志。
     */
    private void registerScreenOn() {
        if (screenOnRx != null) return;
        screenOnRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                st.screenOnAt = android.os.SystemClock.uptimeMillis();
                tomo.cancelSwipeWindow();          // 亮屏附带的那半截窗口直接作废
                CardDebug.note(CardA11yService.this, "SCREEN_ON 收到 → 忽略其后 "
                        + SCREEN_ON_IGNORE_MS + "ms 内的翻页指纹");
            }
        };
        try {
            registerReceiver(screenOnRx, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (Throwable t) {
            screenOnRx = null;
            CardDebug.note(this, "SCREEN_ON 注册失败：" + t);
        }
    }

    private void unregisterScreenOn() {
        if (screenOnRx == null) return;
        try {
            unregisterReceiver(screenOnRx);
        } catch (Throwable ignored) {
        }
        screenOnRx = null;
    }
}
