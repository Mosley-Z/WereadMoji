package com.inkread.weekread;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 桌面悬浮卡片的宿主。
 *
 * 走 TYPE_ACCESSIBILITY_OVERLAY：**不需要 SYSTEM_ALERT_WINDOW 权限**，
 * 用户到「系统设置 → 无障碍 → 微读墨记」开一次开关即可，重启后由系统自动拉起。
 *
 * ── 只在桌面显示 ──
 * 监听 TYPE_WINDOW_STATE_CHANGED，用事件里的包名判断前台是不是「桌面」。
 * 是桌面 → 卡片可见；切到任何别的应用（含我们自己的界面）→ 卡片立刻隐藏。
 *
 * ── 另外四类"让位" ──
 * ① 进了 ELauncher 的隐藏页（「设置」，scrollX≥960）→ 让位（§22）；
 * ② 通知栏（状态栏）已下拉 → 让位，收起后自动恢复（§24，见 {@link #shadeOpen}）；
 * ③ 进了我们自己的界面（主页 / 设置页）→ 让位。
 * ④ 用户点按了桌面上的图标 → 让位（§25，见 {@link #iconGate}）。
 * 四者都只影响"是否显示"，**不翻转** onDesktop，避免出现回不来的状态。
 * 桌面包名不是写死的，启动时用 PackageManager 查 CATEGORY_HOME 得到，
 * 这样 Tomo / OEM 桌面 / 用户装的第三方桌面都能认。
 *
 * 注意：本服务刻意保持 android:canRetrieveWindowContent="false" ——
 * 只读事件自带的包名，**不抓取任何窗口内容**，权限说明里也不出现"读取屏幕"字样。
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
    private static boolean sOwnUiForeground = false;

    private WindowManager wm;
    private WeekCardView view;
    /** 右上角「更新于…」的透明触摸区（手动刷新） */
    private View hitView;
    /** 左上角「抬头」的透明触摸区（**短按**切形态、**长按**弹菜单，§26 / v0.3.4） */
    private View titleView;
    /** 右下角「打开」按钮的透明触摸区（只在本书形态出现） */
    private View openView;
    /** 左下角「上一条」按钮的透明触摸区（只在本记形态出现，v0.4.2） */
    private View prevView;
    /** 长按抬头弹出的菜单（铺满卡片的透明窗口，平时 GONE） */
    private CardMenuView menuView;
    private boolean windowAdded = false;

    private final Set<String> launchers = new HashSet<>();
    /**
     * 真正的「桌面 Activity」全名集合（`包名/类名`）。
     *
     * ⚠️ 光有包名不够 —— 桌面包里往往还挂着别的界面。实测 `com.astraabove.tomo`
     * （图墨桌面）这一个包里就有 LauncherActivity、**BookshelfActivity（书架）**、
     * **FileManagerActivity（文件）**、**SettingsActivity（设置）** 等 11 个 Activity。
     * 用户点开「书架」，前台包名依然是 com.astraabove.tomo，
     * 按包名判断就会认为"还在桌面"→ 卡片继续悬浮在书架上面。
     * 所以必须精确到"这个包里的哪一个 Activity 才是桌面"。
     */
    private final Set<String> homeComponents = new HashSet<>();
    /** 前台是否在桌面。连接时先按"是"，随后由第一个窗口事件纠正 */
    private boolean onDesktop = true;
    /**
     * 从第几页开始让位（0 基）。
     *
     * ⚠️ **这个值为什么是 2 而不是 1，是实测出来的，不要"优化"成 1。**
     *
     * ELauncher 的 scroller 是 `com.wetao.elauncher:id/viewPager`（宽 = 屏宽 480），
     * 页码 = `scrollX / 480`。但**事件投递是不可靠的**：
     *
     *   进入「设置」页：收到 480(cct=1) → 960(cct=3)   ← 最终值 960 有投递 ✅
     *   按 HOME 回桌面：收到 960(cct=1) → 480(cct=3)   ← **停在 0 的最终值从来没投递过** ❌
     *   静置在第 1 页时：完全没有 ViewPager 事件（只有时钟 TextView 每分钟跳一次）
     *
     * 也就是说：
     *   · 第 1 页（真值 scrollX=0）在事件里**最高只会表现为 480**；
     *   · 第 2 页（真值 scrollX=480）同样表现为 480。
     * 两者在事件层面**无法区分** —— 所以绝不能写成"page != 0 就藏"，
     * 那样会把卡片在第 1 页也藏掉，而且因为静置无事件、它永远不会自己恢复。
     *
     * 能可靠区分的只有 `scrollX ≥ 960` 这一档（ELauncher 那些**不在指示点里的隐藏页**，
     * 目前已知「设置」是其中之一）。所以阈值取 2：**只在 ≥ 第 3 页时让位**。
     * 代价是"桌面第 2 页仍会被卡片挡住"，这一点已如实写进交付说明，等用户决定是否
     * 用 `canRetrieveWindowContent` 换取精确判定。
     */
    private static final int PAGE_HIDE_FROM = 2;

    /**
     * 桌面当前在第几页（0 = 第 1 页）。
     *
     * ⚠️ 只能当"不小于真实页码"的粗略值用 —— 见 {@link #PAGE_HIDE_FROM} 里的事件投递实测。
     * 唯一可靠的用途是判断"是不是进了 ELauncher 的隐藏页（设置页）"。
     *
     * Tomo 桌面没有这种 ViewPager（不发这类事件），此值恒为 0，完全不受影响。
     */
    private int desktopPage = 0;
    /** 待生效的页码，见 {@link #PAGE_DEBOUNCE_MS} */
    private int pendingPage = -1;

    /**
     * 页码去抖窗口（毫秒）。
     *
     * 为什么要去抖：`scrollX` 在**页面切换动画途中**会被投递一个中间值。
     * 实测按 HOME 从「设置」页回桌面时，先来一条 `scrollX=480`、220ms 后才是 `scrollX=0`；
     * 不去抖的话卡片会"先藏一下、再出现"，墨水屏上就是两次全屏刷新，非常显眼。
     * 所以收到新页码先记下来，静置这么久没有更新的值才真正生效。
     */
    private static final long PAGE_DEBOUNCE_MS = 220;
    private android.os.Handler ui;
    private final Runnable applyPendingPage = new Runnable() {
        @Override
        public void run() {
            if (pendingPage < 0) return;
            int p = pendingPage;
            pendingPage = -1;
            if (p != desktopPage) {
                desktopPage = p;
                CardDebug.note(CardA11yService.this, "desktopPage=" + p + " (debounced)");
                applyVisibility();
            }
        }
    };

    // ── 通知栏（状态栏）让位闸门 ──

    /**
     * 通知栏是否处于「已下拉」状态。true 时卡片让位。
     *
     * ⚠️ 为什么单开一个闸门、而不是顺手把 onDesktop 翻成 false（第 4 轮实测结论）：
     * 卡片是 `TYPE_ACCESSIBILITY_OVERLAY`，**z 序高于 systemui 的通知栏面板**，
     * 所以下拉状态栏时不是"卡片被盖住"，而是"卡片盖住了通知"（有截图实证：
     * 通知文字被卡片压住）。下拉时桌面的窗口**根本没被销毁**，收起时也不会补发
     * 任何 launcher 事件 —— 一旦在这里翻掉 onDesktop，就永远回不来了。
     *
     * 而"只挂一个独立闸门"让"收起后恢复"变成**天然**结果：下拉前的
     * onDesktop / desktopPage 完全没被动过，收起时重算一遍，原来该显示的还显示、
     * 原来该藏的还藏。顺带绕开了"ELauncher 页码测不准"这个死结 ——
     * 压根不需要知道用户当时在第几页。
     */
    private boolean shadeOpen = false;

    /**
     * 「面板消失」标记。即 `AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED`（API 28）。
     * 这里写字面量而不是引用常量，是为了不把编译门槛抬到 API 28。
     */
    private static final int CCT_PANE_DISAPPEARED = 0x20;

    /** 通知栏面板的宿主包名。它是"瞬时浮层"，但**只有通知栏面板**需要我们让位。 */
    private static final String SYSTEMUI_PKG = "com.android.systemui";

    /**
     * 通知栏面板标题在事件文本里的特征串。
     *
     * 本机（ELauncher 的墨水屏 ROM）通知栏面板的事件文本长这样：
     *   展开：`[通知栏。][通知][静音][Android 系统][•][已开启 USB 文件传输模式][展开][2026-9-22 星期二][01:21]`
     *   收起：`[通知栏。]`
     * 也就是**任何一条**通知栏事件都以面板标题开头。用它做"这条事件到底是不是通知栏"的判据，
     * 以免把音量条 / 电源菜单这类同样来自 systemui 的浮层误当成通知栏（那会让卡片白闪一次）。
     * ROM 若改了标题，最坏结果只是"卡片不再为通知栏让位"（退回旧行为），不会误藏。
     */
    private static final String SHADE_TITLE = "通知栏";

    // ══════════════════════ 长按隐藏闸门（v0.3.4，问题 3） ══════════════════════

    /**
     * 用户**长按**卡片抬头 → 选一个时长 → 卡片让位，到点自动恢复。
     *
     * ── 为什么是"第五道闸门"而不是直接关掉卡片 ──
     * 现有的四道（页码 / 通知栏 / 自家界面 / 图标点按）都只影响"这一刻显不显示"，
     * 不翻转 onDesktop —— 这样"让位的原因消失了就自然恢复"。
     * 隐藏也必须走同一套：闸门一清，{@link #applyVisibility} 照常把该显示的显示出来，
     * **不需要任何"记住之前是什么状态"的逻辑**。
     *
     * ── "到点时不在桌面怎么办"（用户要的行为）──
     * 不用管：到期只是把闸门清掉、重算一次可见性。此刻若在其他应用里，
     * `onDesktop == false` → 仍然隐藏；等用户回到桌面，那个窗口事件会再算一次，
     * 卡片自己就出来了。这正是通知栏让位已验证过的路径。
     */
    private boolean hideGate = false;
    /** 隐藏到什么时候（`System.currentTimeMillis()`），只用于日志 */
    private long hideUntil = 0L;

    /** 长按菜单的四项，与 {@link #HIDE_MS} 一一对应 */
    private static final String[] MENU_ITEMS = {
            "隐藏 15 秒（测试）",
            "隐藏 1 分钟",
            "隐藏 5 分钟",
            "取消"
    };
    /**
     * 每个菜单项对应的隐藏时长（毫秒）。
     *
     * 15 秒那档是**测试期**用的（用户拍板：先用短时长提高测试效率），
     * 正式版会把它去掉或挪到最后 —— 排序上把它放第一项，测试时点起来最快。
     */
    private static final long[] HIDE_MS = {15_000L, 60_000L, 300_000L, 0L};

    /** 菜单多久自动消失（毫秒）。没人操作时别一直占着卡片区域的触摸 */
    private static final long MENU_AUTO_MS = 8_000L;
    private boolean menuOpen = false;

    private final Runnable hideExpire = new Runnable() {
        @Override
        public void run() {
            if (!hideGate) return;
            hideGate = false;
            hideUntil = 0L;
            CardDebug.note(CardA11yService.this, "hideGate=false (timer) → 按当前前台重算");
            applyVisibility();
        }
    };

    private final Runnable menuTimeout = new Runnable() {
        @Override
        public void run() {
            dismissMenu();
        }
    };

    /** 微信读书墨水屏版包名（兜底显式跳转用） */
    private static final String WEREAD_EINK = "com.tencent.weread.eink";

    // ── 桌面图标点按让位闸门（第 6 轮，用户拍板"只要方案 A"） ──

    /**
     * 用户刚点过桌面上的图标 → 卡片让位。true 时卡片隐藏。
     *
     * ── 为什么需要它（实测根因，§25）──
     * ELauncher 把「书架」做成了**桌面 ViewPager 的一页之外的东西**：点「书架」图标时
     * 桌面 ViewPager 会先回到 `scrollX=0`，然后书架面板才盖上来 —— 而 `scrollX=0`
     * 在现有页码逻辑里恰好等于"第 1 页"，于是卡片被判定为"该显示"，正好压住书架。
     * 「设置」页（scrollX=960）不在此列，它已被 {@link #PAGE_HIDE_FROM} 修好。
     *
     * 光靠页码永远分不开"真的在第 1 页"和"在书架上"（两者都报 0），
     * 但**点按图标这个动作本身有独立事件**：实测 3/3 命中
     * `TYPE_VIEW_CLICKED pkg=<桌面> cls=android.widget.ImageView`。
     * 所以新增这道闸门：**只由"点了桌面图标"置位，只影响显隐，不碰页码与 onDesktop**。
     *
     * ── 为什么不会把自己藏死（三道恢复）──
     * ① 任何**真实应用**的窗口事件（含我们自己的界面）→ 立刻清闸门（点封面进阅读器就是这条）；
     * ② 桌面 ViewPager 再有任何**翻页事件** → 清闸门（按 HOME 回桌面实测会先报 `scrollX=0`）；
     * ③ 兜底超时 {@link #GATE_MAX_MS}（3 分钟）→ 无条件清闸门，**宁可多显示、不可永久消失**。
     */
    private boolean iconGate = false;
    /** 闸门置位时刻（`SystemClock.uptimeMillis()`），供最小保持时间与兜底超时用 */
    private long iconGateAt = 0L;

    /**
     * 闸门最小保持时间（毫秒）。
     *
     * 为什么需要：点「书架」时 `scrollX=0` 的翻页事件**紧贴着**点击事件到达
     * （实测同一秒内，甚至更早）。没有这个"最小保持时间"，恢复条件 ② 会在
     * 闸门刚置位的瞬间就把它清掉，闸门等于失效。1.5 秒足以盖住这次同帧投递，
     * 又不影响用户真正"切走再回来"（那是秒级以上）。
     */
    private static final long GATE_MIN_HOLD_MS = 1500L;

    /**
     * 闸门兜底超时（毫秒）。超过这个时间一律恢复显示 —— 失败方向只允许是"多显示一会儿"，
     * 绝不能是"卡片再也不出现"（用户看不出是闸门卡住，只会以为功能坏了）。
     */
    private static final long GATE_MAX_MS = 180_000L;

    /** 兜底超时任务：置位时挂上，清闸门时撤掉 */
    private final Runnable gateTimeout = new Runnable() {
        @Override
        public void run() {
            if (!iconGate) return;
            iconGate = false;
            CardDebug.note(CardA11yService.this, "iconGate=false (timeout " + (GATE_MAX_MS / 1000) + "s)");
            applyVisibility();
        }
    };

    /**
     * 临时探针开关：把收到的所有无障碍事件原样写进 card_debug.log。
     *
     * ⚠️ 常规构建必须为 false。它的用途只有一个：下次再遇到"某个界面明明换了、
     * 却收不到任何能区分的事件"时，把它打开重新出包，就能看到 ROM 到底投递了什么。
     */
    private static final boolean PROBE_ALL = false;
    /** 事件去重用（避免日志被高频事件刷爆） */
    private String lastPkg = null;
    private int lastType = -1;
    private String lastCls = null;

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
            sInstance.applyVisibility();
        }
        // 离开自家界面时刻意什么都不做：此刻 onDesktop 可能还是进入前那个旧值，
        // 立刻重算会让卡片闪一下（墨水屏上一次无谓刷新很显眼）。
        // 交给紧随其后的那个窗口事件去定：回桌面 → 桌面事件把它显示出来。
    }

    // ── 生命周期 ──

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        if (ui == null) ui = new android.os.Handler(android.os.Looper.getMainLooper());
        // 先按"在桌面第 1 页"假设。这个假设只在服务重连的那一刻用一次，
        // 且被 sOwnUiForeground 否决（用户正开着自己的界面时不该显示卡片）。
        // 真正的"当前是不是桌面 / 在第几页"由随后的窗口与翻页事件纠正。
        onDesktop = true;
        desktopPage = 0;
        pendingPage = -1;
        shadeOpen = false;                          // 服务刚连上时通知栏一定没下拉
        iconGate = false;                           // 同理，刚连上时不存在"刚点过图标"
        iconGateAt = 0L;
        hideGate = false;                           // 也没人长按过（进程刚起来）
        hideUntil = 0L;
        menuOpen = false;
        refresh();
        CardDebug.note(this, "service connected, enabled=" + CardPrefs.isEnabled(this)
                + ", ownUi=" + sOwnUiForeground);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        CharSequence cs = event.getPackageName();
        String pkg = cs == null ? "(null)" : cs.toString();
        String clsForLog = event.getClassName() == null ? "" : event.getClassName().toString();

        // 探针：原样记录所有事件（PROBE_ALL 打开时），用完要关
        if (PROBE_ALL) probeAll(event, type, pkg, clsForLog);

        // 先无条件留痕（去重后写入），用于确认本机 ROM 到底投递了哪些事件。
        // 去重键必须带上 className —— 只按 (包名, 类型) 去重会把"自己界面"的
        // 那条事件误判成重复而吃掉，排查时会看到一堆 android.view.View 却找不到
        // MainActivity 的痕迹（§19.6 的坑）。
        if (!pkg.equals(lastPkg) || type != lastType || !clsForLog.equals(lastCls)) {
            lastPkg = pkg;
            lastType = type;
            lastCls = clsForLog;
            CardDebug.note(this, "event " + AccessibilityEvent.eventTypeToString(type)
                    + " pkg=" + pkg + " cls=" + clsForLog
                    + " ownUi=" + sOwnUiForeground);
        }

        // ── 桌面翻页：非第 1 页时卡片让位（§21.5） ──
        // 翻页不换窗口，所以不会有 WINDOW_STATE_CHANGED，只能靠这一条。
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handleDesktopPage(pkg, clsForLog, event);
            return;
        }
        // ── 点了桌面图标：卡片让位（§25 方案 A） ──
        // 只有"点按桌面上的图标"这一个动作能置位。必须排在下面所有判定之前，
        // 因为点「书架」时页码会被报成 0（= 第 1 页），再往下走就会被误判成"该显示"。
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleDesktopClick(pkg, clsForLog);
            return;
        }
        // 窗口列表变化：目前只用于诊断，不参与状态判定
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (cs == null) return;                    // 拿不到包名就别乱动，维持现状

        // ── 通知栏（状态栏）开合：只动 shadeOpen 闸门，绝不翻转桌面状态 ──
        // 必须排在 isTransientOverlay 之前：systemui 的窗口全是瞬时浮层，
        // 但其中**只有通知栏面板**需要卡片让位（音量条 / 电源菜单不需要，
        // 由 handleShadeState 里的标题判据把它们筛掉）。
        if (SYSTEMUI_PKG.equals(pkg)) {
            handleShadeState(event);
            return;
        }

        // ⚠️ 关键：我们自己持的悬浮窗也会触发 WINDOW_STATE_CHANGED（cls=android.view.View）。
        // 如果不把它排除，卡片一显示就会收到这条事件、被误判成"用户切到了别的应用"，
        // 于是立刻把自己藏起来 —— 表现为"卡片永远不出现"（实测踩到过这个坑）。
        if (getPackageName().equals(pkg)) {
            boolean ownScreen = clsForLog.endsWith(".MainActivity") || clsForLog.endsWith(".SettingsActivity");
            if (!ownScreen) return;                // 自己的悬浮窗事件：忽略，维持现状
            // 用户在我们的界面里：桌面上那张卡片要让位（主页已经整屏显示同一张卡片了）
            sOwnUiForeground = true;
            if (onDesktop) {
                onDesktop = false;
                applyVisibility();
            }
            return;
        }

        // ── 瞬时系统浮层：一律"维持现状"，不翻转桌面状态（§21.4 方案 A） ──
        // 为什么必须这样：下拉状态栏时桌面的窗口**根本没被销毁**，只是被浮层盖住，
        // 所以收起状态栏**不会**再发一条 launcher 的 WINDOW_STATE_CHANGED。
        // 一旦在这里把 onDesktop 置成 false，就再也没有事件能把它改回 true
        // → 卡片永久消失（用户报的 3(b) 就是这个，已实测复现）。
        if (isTransientOverlay(pkg, clsForLog)) {
            CardDebug.note(this, "ignore transient overlay pkg=" + pkg + " cls=" + clsForLog);
            return;
        }

        // ── 漏投兜底闸门 ──
        // 能走到这里 = 这是一条来自"别的真实应用 / 桌面"的窗口事件。
        // 打开任何界面都会先收起通知栏，所以此刻通知栏必然已经收起。
        // 万一"收起"那条系统事件被漏投，卡片也不会被永久藏死。
        if (shadeOpen) {
            shadeOpen = false;
            CardDebug.note(this, "shadeOpen=false (real window changed: " + pkg + ")");
        }

        // ── 图标点按闸门的恢复（§25 恢复条件 ①） ──
        // 能走到这里 = 前台换成了一个真实界面。无论它是别的应用（点封面进阅读器）、
        // 还是桌面本身（按 HOME 回来），都说明"刚点的那一下"已经结束了 → 清闸门。
        // 注意：清完必须**强制重算一次显隐**，因为下面那段只在 desk!=onDesktop 时才重算，
        // 而"从书架按 HOME 回桌面"时 onDesktop 本来就是 true → 不强制的话卡片不会回来。
        boolean gateCleared = false;
        if (iconGate) {
            iconGate = false;
            gateCleared = true;
            if (ui != null) ui.removeCallbacks(gateTimeout);
            CardDebug.note(this, "iconGate=false (real window changed: " + pkg + ")");
        }

        // 桌面判定必须精确到 Activity：桌面包里还挂着书架 / 文件管理 / 设置等普通界面，
        // 只看包名会把它们误判成"还在桌面"（§21.3）。
        boolean desk = isHomeScreen(pkg, clsForLog);
        boolean wasOwnUi = sOwnUiForeground;
        if (desk) {
            // 已经在（桌面）的界面上，那肯定不在我们自己的界面里。
            // 这同时是 noteOwnUiForeground(false) 万一没被调到的兜底。
            sOwnUiForeground = false;
        }
        CardDebug.note(this, "decide pkg=" + pkg + " cls=" + clsForLog
                + " desk=" + desk + " was=" + onDesktop + " page=" + desktopPage);
        // 注意第二个条件：从自家界面回到桌面时 onDesktop 可能本来就是 true
        // （进自家界面只改了标志、没改 onDesktop），这时也必须重算一次，
        // 否则卡片会一直藏着不出来。
        if (desk != onDesktop || (desk && wasOwnUi) || gateCleared) {
            onDesktop = desk;
            if (desk) {
                // 进入桌面时**乐观**地按"第 1 页"处理：HOME 键、从应用返回，
                // 落点几乎都是第 1 页。若实际停在第 2 页 / 设置页，随后那个
                // ViewPager 事件会在 220ms 内把页码纠正过来、再把卡片藏回去。
                // 反过来（先藏后显）的代价更大 —— 卡片"该出现时半天不出现"，
                // 用户会以为它坏了。
                desktopPage = 0;
                pendingPage = -1;
                if (ui != null) ui.removeCallbacks(applyPendingPage);
            }
            applyVisibility();
        }
    }

    /**
     * 桌面翻页判定。
     *
     * ELauncher 的桌面是 `com.wetao.elauncher:id/viewPager`（宽 = 屏宽 480），
     * `页码 = scrollX / 480`。但**事件投递不可靠**，详见 {@link #PAGE_HIDE_FROM}：
     * 第 1 页与第 2 页在事件里都只表现为 480，无法区分；只有 ≥960 那一档（隐藏页）可靠。
     *
     * 必须按 className 过滤：`android.widget.TextView` 之类的节点也会发 content-changed，
     * 而它们报出来的 scrollX 是**垃圾值**（实测见过 524266），不过滤会误判页码。
     */
    private void handleDesktopPage(String pkg, String cls, AccessibilityEvent event) {
        if (!isLauncher(pkg)) return;
        if (!cls.endsWith("ViewPager")) return;
        // 桌面能吃翻页手势 ⇒ 屏幕没有被通知栏盖住（盖着时桌面收不到触摸）→ 兜底清闸门
        if (shadeOpen) {
            shadeOpen = false;
            CardDebug.note(this, "shadeOpen=false (desktop page event)");
        }
        // ── 图标点按闸门的恢复（§25 恢复条件 ②） ──
        // 桌面 ViewPager 又发事件 ⇒ 用户确实在对桌面做翻页动作（按 HOME 从书架回桌面时
        // 实测会先报 scrollX=0）。但点「书架」时那条 scrollX=0 **紧贴着点击事件**到达，
        // 必须用最小保持时间把它隔开，否则闸门刚置位就被清掉、等于没做。
        boolean gateCleared = false;
        if (iconGate
                && android.os.SystemClock.uptimeMillis() - iconGateAt >= GATE_MIN_HOLD_MS) {
            iconGate = false;
            gateCleared = true;
            if (ui != null) ui.removeCallbacks(gateTimeout);
            CardDebug.note(this, "iconGate=false (desktop page event)");
        }
        int sx = 0;
        try {
            sx = event.getScrollX();
        } catch (Throwable ignored) {
        }
        if (sx < 0) return;                        // 负数＝拿不到，维持现状
        int page = sx / CardSpec.SCREEN_W;         // 整数除法：0…479→0，480…959→1，依此类推
        int cct = 0;
        try {
            cct = event.getContentChangeTypes();
        } catch (Throwable ignored) {
        }
        CardDebug.note(this, "pageRaw sx=" + sx + " cct=" + cct + " → page=" + page
                + " (cur=" + desktopPage + ")");
        // 拖动过程中会经过中间值，交给去抖窗口决定最终停在哪一页
        if (page != pendingPage) {
            pendingPage = page;
            if (ui != null) {
                ui.removeCallbacks(applyPendingPage);
                ui.postDelayed(applyPendingPage, PAGE_DEBOUNCE_MS);
            }
        }
        // 闸门被清掉时立即重算一次：去抖回调只在"页码真的变了"时才重算，
        // 而这次翻页完全可能把页码停在原值（比如书架回来仍是"第 1 页"），
        // 不强制重算的话卡片就回不来了。
        if (gateCleared) applyVisibility();
    }

    /**
     * 用户点了桌面上的图标 → 置位图标闸门，卡片让位（§25 方案 A）。
     *
     * 这是"书架 / 第 2 页被卡片挡住"的唯一可用解 —— 它们的页码信号
     * （书架报 `scrollX=0`、第 2 页报 `scrollX=480`）分别与"第 1 页"和
     * "第 1 页动画途中"**完全同形**，靠页码永远分不开（详见 {@link #PAGE_HIDE_FROM}）。
     *
     * 判据 = 「是不是桌面发出的点按」**且**「被点的是图标类控件」（见方法内白名单注释）：
     * · 点我们自己的卡片（左上角切换 / 右上角刷新）不会被误判 —— 事件包名是我们的包名；
     * · 点歪穿透到桌面小组件（FrameLayout 等容器）也不再误触发 —— 2026-09-22 修复；
     * · 点空白处不会触发 —— 桌面没有可点节点，实测只有图标与小组件。
     */
    private void handleDesktopClick(String pkg, String cls) {
        if (!isLauncher(pkg)) return;
        if (sOwnUiForeground) return;              // 在自家界面里一切两清

        // ── 图标白名单（2026-09-22 实测教训，问题 2 的第二层修复）──
        // 用户点卡片抬头没命中我们触摸窗的部分会**穿透**给桌面；在图墨桌面上，
        // 抬头下方正好是它的小组件容器，点击报
        //   TYPE_VIEW_CLICKED pkg=com.astraabove.tomo cls=android.widget.FrameLayout
        // 旧判据只看包名 → 误触发 iconGate 让位；而图墨桌面既没有 ViewPager 翻页事件、
        // 点击后也不保证补发窗口事件（实测第三次点击后 40 秒无恢复）→ 卡片"消失"。
        // 只有**图标类**控件（点应用图标进应用）才需要这道闸门 —— 那类场景下
        // 一定会有后续窗口事件恢复。容器/文本类（小组件、时钟、空白容器）一律忽略。
        // 已知事件形态（均实测）：
        //   点应用图标（ELauncher 书架）  cls=android.widget.ImageView   → 置位（保留）
        //   图墨小组件容器被穿透点击      cls=android.widget.FrameLayout → 忽略（本修复）
        boolean iconLike = cls.endsWith("ImageView") || cls.endsWith("ImageButton");
        if (!iconLike) {
            CardDebug.note(this, "click cls=" + cls + " → 忽略（非图标，不置闸门）");
            return;
        }
        if (iconGate
                && android.os.SystemClock.uptimeMillis() - iconGateAt < GATE_MIN_HOLD_MS) {
            return;                                // 刚置位又点了一下：保持原计时，别把超时无限续期
        }
        iconGate = true;
        iconGateAt = android.os.SystemClock.uptimeMillis();
        if (ui != null) {
            ui.removeCallbacks(gateTimeout);
            ui.postDelayed(gateTimeout, GATE_MAX_MS);
        }
        CardDebug.note(this, "iconTap → 让位 (pkg=" + pkg + ")");
        applyVisibility();
    }

    /**
     * 这条 WINDOW_STATE_CHANGED 是不是"瞬时系统浮层"——即不该改变"当前在哪个应用"的窗口。
     *
     * 两类：
     * ① `com.android.systemui` 自己的窗口：下拉状态栏、音量条、电源菜单、通知横幅。
     *    它们浮在当前应用**上方**，收起后当前应用压根没变过（连窗口都没重建），
     *    所以既不该藏卡片、更不该把状态位翻转过去回不来。
     * ② 类名不是任何 Activity、而是普通 View 类的窗口（`android.widget.*` / `android.view.*` /
     *    `android.app.Dialog*`）：这类是 Dialog / Toast / PopupWindow 的装饰窗。
     *    真实的应用切换一律报 Activity 类名（如 `com.simple.appstore.MainActivity`），
     *    所以这条规则不会漏掉真正的应用切换。
     */
    private boolean isTransientOverlay(String pkg, String cls) {
        if (SYSTEMUI_PKG.equals(pkg)) return true;
        if (cls.startsWith("android.widget.") || cls.startsWith("android.view.")
                || cls.startsWith("android.app.Dialog")) return true;
        return false;
    }

    /**
     * 处理通知栏（状态栏）面板的开 / 合。
     *
     * 判据来自 2026-09-22 凌晨的只读探针（4 轮开合，结果 100% 一致）：
     *
     *   展开：`WINDOW_STATE_CHANGED pkg=com.android.systemui cct=0  text=[通知栏。][通知][静音][…][时间]`
     *   收起：`WINDOW_STATE_CHANGED pkg=com.android.systemui cct=0x20  text=[通知栏。]`
     *
     * 收起那条的 `cct=0x20` 正是 `CONTENT_CHANGE_TYPE_PANE_DISAPPEARED`（"面板消失了"），
     * 语义上就是通知栏收起 —— 这是**唯一**能区分"收起之后还留在桌面"的信号，
     * 因为下拉状态栏不会销毁桌面窗口、也不会补发 launcher 事件。
     *
     * 两条安全设计：
     * · **展开判据从严**：必须真的带面板标题（{@link #SHADE_TITLE}）且不止一条文本，
     *   否则音量条 / 电源菜单这类同样来自 systemui 的浮层会让卡片白闪一次。
     * · **收起判据从宽且无条件重算**：只要看到"面板消失"或"只剩标题"就重算一次可见性，
     *   即使 shadeOpen 早就是 false 也照算 —— 这样上面那条兜底闸门万一误清，
     *   真正的收起事件也能把状态拉回来（{@code View.setVisibility} 传入相同值不会重绘，
     *   所以无条件重算没有代价）。
     */
    private void handleShadeState(AccessibilityEvent event) {
        int cct = 0;
        try {
            cct = event.getContentChangeTypes();
        } catch (Throwable ignored) {
        }
        int n = 0;
        try {
            List<CharSequence> t = event.getText();
            n = (t == null) ? 0 : t.size();
        } catch (Throwable ignored) {
        }
        boolean title = hasShadeTitle(event);

        boolean closeSig = (cct & CCT_PANE_DISAPPEARED) != 0 || (title && n <= 1);
        boolean openSig = title && n >= 2;
        CardDebug.note(this, "shadeEvt cct=" + cct + " n=" + n + " title=" + title
                + " → close=" + closeSig + " open=" + openSig + " (cur=" + shadeOpen + ")");

        if (closeSig) {
            shadeOpen = false;
            applyVisibility();                     // 无条件重算：这就是"收起后恢复"
            return;
        }
        if (openSig && !shadeOpen) {
            shadeOpen = true;
            applyVisibility();
        }
    }

    /** 事件文本里有没有通知栏面板的标题（见 {@link #SHADE_TITLE}） */
    private boolean hasShadeTitle(AccessibilityEvent event) {
        try {
            List<CharSequence> t = event.getText();
            if (t == null) return false;
            for (CharSequence c : t) {
                if (c != null && c.toString().contains(SHADE_TITLE)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 探针：把事件的关键字段原样写进日志。PROBE_ALL 关掉后就是空操作。 */
    private void probeAll(AccessibilityEvent e, int type, String pkg, String cls) {
        StringBuilder sb = new StringBuilder("PROBEALL ");
        sb.append(AccessibilityEvent.eventTypeToString(type));
        sb.append(" pkg=").append(pkg).append(" cls=").append(cls);
        try {
            sb.append(" cct=").append(e.getContentChangeTypes());
        } catch (Throwable ignored) {
        }
        try {
            sb.append(" win=").append(e.getWindowId());
        } catch (Throwable ignored) {
        }
        try {
            sb.append(" dx=").append(e.getScrollDeltaX()).append(" sx=").append(e.getScrollX());
        } catch (Throwable ignored) {
        }
        // 2026-09-22 第5轮新增：验证 ViewPager 的"真实页码"线索是否存在
        try {
            sb.append(" from=").append(e.getFromIndex()).append(" to=").append(e.getToIndex())
              .append(" cnt=").append(e.getItemCount()).append(" maxSx=").append(e.getMaxScrollX());
        } catch (Throwable ignored) {
        }
        try {
            List<CharSequence> tx = e.getText();
            if (tx != null && !tx.isEmpty()) {
                StringBuilder t = new StringBuilder();
                for (CharSequence c : tx) t.append('[').append(c).append(']');
                String s = t.toString();
                if (s.length() > 140) s = s.substring(0, 140);
                sb.append(" text=").append(s);
            }
        } catch (Throwable ignored) {
        }
        try {
            CharSequence cd = e.getContentDescription();
            if (cd != null && cd.length() > 0) sb.append(" cd=").append(cd);
        } catch (Throwable ignored) {
        }
        CardDebug.note(this, sb.toString());
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cancelPending();
        removeWindow();
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cancelPending();
        removeWindow();
        sInstance = null;
        super.onDestroy();
    }

    /** 服务要走了，把还没生效的页码回调与闸门超时都撤掉，别让它们去碰已经销毁的窗口 */
    private void cancelPending() {
        pendingPage = -1;
        if (ui != null) {
            ui.removeCallbacks(applyPendingPage);
            ui.removeCallbacks(gateTimeout);
        }
    }

    // ── 内部 ──

    /** 把「偏好 + 数据 + 可见性」一次性对齐 */
    private void refresh() {
        if (!CardPrefs.isEnabled(this)) {
            removeWindow();
            return;
        }
        ensureWindow();
        if (view != null) {
            // 卡片显示哪个形态由偏好决定（设置页定默认，左上角短按可临时切）
            String mode = StatsStore.getCardPeriod(this);
            view.setMode(mode);
            // 「本书」的数据在 BookStore、「本记」在 NoteStore（都是文件缓存）—— 分开取
            if (PeriodRange.BOOK.equals(mode)) {
                BookStats b = BookStore.load(this);
                view.setBook(b);
                loadCover(b);
            } else if (PeriodRange.NOTE.equals(mode)) {
                if (!showNote(false)) noteSync(false);
            } else view.setStats(StatsStore.loadCard(this));
        }
        applyVisibility();
    }

    private void ensureWindow() {
        if (windowAdded && view != null) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            view = new WeekCardView(this);
            // 卡片窗口就是卡片的左右边界，所以内容不再留横向内边距，
            // 分隔线/柱状图两端才正好落在 56 / 423 这两个图标描边上
            view.setPadXRatio(0f);
            String m0 = StatsStore.getCardPeriod(this);
            view.setMode(m0);
            if (PeriodRange.BOOK.equals(m0)) {
                BookStats b0 = BookStore.load(this);
                view.setBook(b0);
                loadCover(b0);
            } else if (PeriodRange.NOTE.equals(m0)) {
                if (!showNote(false)) noteSync(false);
            } else view.setStats(StatsStore.loadCard(this));
            view.setOpenListener(new WeekCardView.OpenListener() {
                @Override
                public void onOpen() {
                    // 右下角那个框：本书=打开，本记=换一条
                    if (PeriodRange.NOTE.equals(StatsStore.getCardPeriod(CardA11yService.this))) {
                        nextNote();
                    } else {
                        openBook();
                    }
                }
            });
            wm.addView(view, OverlayWindow.params(OverlayWindow.typeAccessibility()));

            // 右上角「更新于…」那一小块：点它手动刷新。
            // 单独开一个透明小窗，而不是给整张卡片去掉 NOT_TOUCHABLE ——
            // 卡片有 368×346，去掉的话桌面在这一大片里的手势就全废了。
            hitView = new View(this);
            hitView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manualRefresh();
                }
            });
            wm.addView(hitView, OverlayWindow.paramsTouch(OverlayWindow.typeAccessibility()));

            // 左上角「抬头」那一小块：**短按**切形态（本周→本月→本书）、**长按**弹隐藏菜单。
            // 同样是独立小窗 —— 切换只是"换一帧内容"，不碰任何显隐状态。
            titleView = new View(this);
            titleView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePeriod();
                }
            });
            titleView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showMenu();
                    return true;
                }
            });
            wm.addView(titleView, OverlayWindow.paramsTitleTouch(OverlayWindow.typeAccessibility()));

            // 右下角「打开」：只在本书形态可见（applyVisibility 里按形态开关），
            // 点了跳微信读书当前进度页。
            openView = new View(this);
            openView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (PeriodRange.NOTE.equals(StatsStore.getCardPeriod(CardA11yService.this))) {
                        nextNote();
                    } else {
                        openBook();
                    }
                }
            });
            wm.addView(openView, OverlayWindow.paramsOpenTouch(OverlayWindow.typeAccessibility()));

            // 左下角「上一条」（v0.4.2）：与右下角对称，只在本记形态出现。
            // 单独开窗而不是把整张卡变成可触摸 —— 理由同「打开」按钮。
            prevView = new View(this);
            prevView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prevNote();
                }
            });
            wm.addView(prevView, OverlayWindow.paramsPrevTouch(OverlayWindow.typeAccessibility()));

            // 长按菜单：铺满卡片的透明窗口，平时 GONE —— 见 CardMenuView 的说明
            menuView = new CardMenuView(this);
            menuView.setItems(MENU_ITEMS);
            menuView.setListener(new CardMenuView.Listener() {
                @Override
                public void onSelect(int index) {
                    long ms = (index >= 0 && index < HIDE_MS.length) ? HIDE_MS[index] : 0L;
                    dismissMenu();
                    if (ms > 0L) startHide(ms);
                }

                @Override
                public void onDismiss() {
                    dismissMenu();
                }
            });
            wm.addView(menuView, OverlayWindow.paramsMenu(OverlayWindow.typeAccessibility()));

            windowAdded = true;
        } catch (Throwable t) {
            view = null;
            hitView = null;
            titleView = null;
            openView = null;
            menuView = null;
            windowAdded = false;
        }
    }

    // ══════════════════════ 长按菜单 / 隐藏闸门 ══════════════════════

    private void showMenu() {
        if (menuView == null) return;
        menuOpen = true;
        if (ui != null) {
            ui.removeCallbacks(menuTimeout);
            ui.postDelayed(menuTimeout, MENU_AUTO_MS);
        }
        CardDebug.note(this, "longPress → menu");
        applyVisibility();
    }

    private void dismissMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        if (ui != null) ui.removeCallbacks(menuTimeout);
        applyVisibility();
    }

    /** 隐藏 ms 毫秒后自动恢复（恢复那一瞬间若不在桌面，就等回到桌面再显示） */
    private void startHide(long ms) {
        hideGate = true;
        hideUntil = System.currentTimeMillis() + ms;
        if (ui != null) {
            ui.removeCallbacks(hideExpire);
            ui.postDelayed(hideExpire, ms);
        }
        CardDebug.note(this, "hideGate=true " + (ms / 1000L) + "s");
        applyVisibility();
    }

    /**
     * 跳微信读书 —— 落点就是当前读到的那一页。
     *
     * `weread://reading?bId={bookId}` 是从微信读书墨水屏版 1.9.9 的 dex 里翻出来的
     * 官方深链（2026-09-22 实测：**冷启动 / 热进程都直达阅读页正文**）；
     * 早期试的 `weread://book/{id}` 只在个别状态下生效（热进程时会被主界面吞掉），
     * 只留作第二级兜底。最后再兜书架给的 https 深链。零新权限。
     */
    private void openBook() {
        BookStats b = BookStore.load(this);
        if (b == null || b.bookId == null || b.bookId.length() == 0) {
            CardDebug.note(this, "openBook: 本地还没缓存书籍，先刷新一次");
            return;
        }
        String uri = "weread://reading?bId=" + b.bookId;
        CardDebug.note(this, "openBook " + uri);
        if (tryStart(schemeIntent(uri, null))) return;
        if (tryStart(schemeIntent(uri, WEREAD_EINK))) return;
        if (tryStart(schemeIntent("weread://book/" + b.bookId, null))) return;
        if (b.deepLink != null && b.deepLink.length() > 0) {
            if (tryStart(schemeIntent(b.deepLink, null))) return;
        }
        CardDebug.note(this, "openBook 失败：没找到能处理的应用");
    }

    private Intent schemeIntent(String uri, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) i.setPackage(pkg);
        return i;
    }

    /** 从 Service 起 Activity 必须带 NEW_TASK；失败返回 false 让调用方继续兜底 */
    private boolean tryStart(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用户点了卡片右上角的「更新于…」→ 手动拉一次 */
    private void manualRefresh() {
        CardDebug.note(this, "tap refresh, mode=" + StatsStore.getCardPeriod(this)
                + ", keyLen=" + StatsStore.getKey(this).length());
        fetchCardData();
    }

    /**
     * 用户**短按**了卡片左上角的抬头 → 三态循环（本周 → 本月 → 本书）。
     *
     * 墨水屏没有涟漪动画，**换帧本身就是反馈**（抬头从「本周阅读时长」变成「9月阅读」、
     * 再变成「本书阅读进度」，图形也从柱状图变成日历、再变成进度条）——
     * 所以这里立刻 refresh() 一帧，不等网络。
     * 若目标形态本地没有缓存，再顺手拉一次；有缓存就先显示缓存（离线也能用）。
     */
    private void togglePeriod() {
        String mode = StatsStore.toggleCardPeriod(this);
        CardDebug.note(this, "tap title → mode=" + mode);
        refresh();                                  // 立刻换成另一形态的那一帧
        if (PeriodRange.BOOK.equals(mode)) {
            if (BookStore.load(this) == null) fetchBookData(StatsStore.getKey(this), false);
        } else if (PeriodRange.NOTE.equals(mode)) {
            if (!showNote(false)) noteSync(false);      // 池子空才起同步（去重 + 退避，见 noteSync）
        } else if (StatsStore.loadCard(this) == null) {
            fetchCardData();
        }
        applyVisibility();
    }

    /**
     * 拉卡片当前周期的数据（卡片**只看当前周期**，不提供历史周期 —— ④ + 拍板 F）。
     *
     * 失败就留着旧数据，别弹窗打扰（用户可能只是路过点了一下）。
     */
    private void fetchCardData() {
        final String key = StatsStore.getKey(this);
        if (key.length() == 0) return;
        final String mode = StatsStore.getCardPeriod(this);
        CardDebug.note(this, "fetch start mode=" + mode);
        if (PeriodRange.NOTE.equals(mode)) {
            // 本记的"刷新中"由 noteSync 自己管（v0.5.3）：如果此刻已经有另一轮同步在跑
            // （比如 App 那边刚发起），noteSync 会直接返回 —— 那么这里**不能**先把
            // refreshing 打开，否则没有任何回调来关它，卡片会永久停在"正在同步…"。
            noteSync(true);                         // 手动刷新 → 重拉索引 + 多补一批书（清退避）
            return;
        }
        // 立刻给文字反馈：墨水屏没有涟漪动画，不写"刷新中…"用户会以为没点到
        if (view != null) view.setRefreshing(true);
        if (PeriodRange.BOOK.equals(mode)) {
            fetchBookData(key, true);               // 手动刷新 → 允许重拉书架（有 10 分钟最小间隔）
            return;
        }
        final long gen = StatsStore.keyGen();          // R05
        WereadApi.fetchDetail(key, mode, 0, new WereadApi.Callback() {
            @Override
            public void onResult(PeriodStats stats, String rawJson, String error) {
                if (gen != StatsStore.keyGen()) return;         // 换过 Key → 丢弃旧会话结果
                if (stats != null) {
                    StatsStore.save(CardA11yService.this, stats);
                    // 拉取期间用户可能又切了周期 —— 只认"和当前偏好一致"的那份
                    String now = StatsStore.getCardPeriod(CardA11yService.this);
                    if (view != null && stats.mode.equals(now)) {
                        view.setMode(stats.mode);
                        view.setStats(stats);
                    }
                    CardDebug.note(CardA11yService.this, "fetch ok mode=" + stats.mode
                            + ", total=" + stats.totalSec + ", " + stats.dump());
                } else {
                    if (view != null) view.setRefreshing(false);
                    CardDebug.note(CardA11yService.this, "fetch failed: " + error);
                }
                applyVisibility();
            }
        });
    }

    /**
     * 拉「本书」的数据（书架 → 进度 → 章节目录，见 {@link WereadApi#fetchBook}）。
     *
     * 失败时**留着旧数据**（BookStore 里那份），别把已经显示出来的书名抹掉 ——
     * 用户可能只是路过点了一下，网络抖一下就要他重看一遍空卡片太亏。
     */
    private void fetchBookData(final String key, boolean forceShelf) {
        if (key.length() == 0) return;
        final long gen = StatsStore.keyGen();          // R05
        CardDebug.note(this, "fetch book start force=" + forceShelf);
        WereadApi.fetchBook(this, key, forceShelf, new WereadApi.BookCallback() {
            @Override
            public void onResult(BookStats b, String error) {
                if (gen != StatsStore.keyGen()) return;         // 换过 Key → 丢弃旧会话结果
                if (b != null) {
                    BookStore.save(CardA11yService.this, b);
                    CardDebug.note(CardA11yService.this, "fetch book ok " + b.dump());
                    // 期间用户可能又切了形态 —— 只认"现在还是本书"的情况
                    if (view != null
                            && PeriodRange.BOOK.equals(StatsStore.getCardPeriod(CardA11yService.this))) {
                        view.setBook(b);
                        loadCover(b);
                    }
                } else {
                    if (view != null) {
                        view.setRefreshing(false);
                        if (BookStore.load(CardA11yService.this) == null) {
                            view.setError(error == null ? "获取失败" : error);
                        }
                    }
                    CardDebug.note(CardA11yService.this, "fetch book failed: " + error);
                }
                applyVisibility();
            }
        });
    }

    // ══════════════════════ 本记（v0.4.0）══════════════════════

    /**
     * 把当前该显示的那条划线放到卡片上（与 App 内 {@code MainActivity.showNote} 同源）。
     *
     * @return true = 抽到了一条内容（调用方据此决定"要不要发起同步"）
     */
    private boolean showNote(boolean manual) {
        if (view == null) return true;              // 没有窗口就什么都不用管
        NoteStats n = NoteStore.pick(this, manual);
        showNoteItem(n);
        return n != null;
    }

    /**
     * 把某条划线放上卡片（「换一条」/「上一条」/ 常规展示共用，v0.4.2 抽出来）。
     */
    private void showNoteItem(NoteStats n) {
        if (view == null) return;
        if (n == null) {
            // 🔴 渲染函数**绝不发起同步**（v0.5.3，R02）。上一版这里 `syncNoteData(false)`
            // 而同步回调又会回到本函数 —— 空池时形成无界环（桌面侧与 App 侧同构，
            // 一轮最多 43 次请求）。现在同步统一走 {@link #noteSync}。
            if (view.getNote() != null) view.setNote(null);
            return;
        }
        view.setNoteHint(null);
        view.setNote(n);
        CardDebug.note(this, "note pick " + n.dump());
        // 章节名要联网反查（章节目录按书永久缓存），查到后回填并重绘 —— 不阻塞出内容
        final NoteStats fn = n;
        NoteSync.resolveChapter(this, StatsStore.getKey(this), n, new Runnable() {
            @Override
            public void run() {
                if (view != null && view.getNote() == fn) view.setNote(fn);
            }
        });
    }

    /** 用户点了「换一条」 */
    private void nextNote() {
        CardDebug.note(this, "tap 换一条");
        if (view == null) return;
        if (!showNote(true)) noteSync(true);        // 手动 → 清退避立即放行
        applyVisibility();
    }

    /** 用户点了「上一条」（v0.4.2）—— 沿来时的路退回去 */
    private void prevNote() {
        CardDebug.note(this, "tap 上一条");
        if (view == null) return;
        NoteStats n = NoteStore.pickPrev(this);
        showNoteItem(n);
        if (n == null) noteSync(true);
        applyVisibility();
    }

    /**
     * 本记同步的**唯一入口**（v0.5.3，R02）—— 与 {@code MainActivity.noteSync} 同一套闸门：
     * in-flight 去重（{@link NoteSync#isRunning}）+ 空/失败退避（{@link NoteSync#canAutoStart}），
     * 手动刷新（force）清退避立即放行。
     */
    private void noteSync(boolean force) {
        final String key = StatsStore.getKey(this);
        if (key == null || key.length() == 0) {
            if (view != null) {
                view.setRefreshing(false);
                view.setNoteHint("还没填 API Key");
            }
            return;
        }
        if (NoteSync.isRunning()) return;                      // 已经在同步 → 等它回调
        if (!force && !NoteSync.canAutoStart()) {              // 退避期内 → 停在空态，别空转
            if (view != null) {
                view.setRefreshing(false);
                view.setNoteHint(lastNoteState == NoteSync.STATE_ERROR
                        ? "同步失败，请检查网络" : "还没有同步到笔记");
            }
            return;
        }
        if (force) NoteSync.clearBackoff();
        syncNoteData(force);
    }

    /** 最近一轮本记同步的结果状态（空态文案要据此区分"失败"与"真的没有"，v0.5.3） */
    private int lastNoteState = NoteSync.STATE_OK;

    /**
     * 同步「本记」的数据：索引 → 逐本预热划线。
     *
     * 渐进式：一次最多补 {@link NoteSync#DEFAULT_PREFETCH} 本，所以每次回到本记
     * 都会悄悄多一批书进池子，几轮下来自然满库（全库 5572 条 ≈ 2MB）。
     */
    private void syncNoteData(boolean force) {
        final String key = StatsStore.getKey(this);
        if (key.length() == 0) return;
        final long gen = StatsStore.keyGen();          // R05：换 Key 后旧会话的迟到结果不许写回
        if (view != null) view.setRefreshing(true);
        NoteSync.sync(this, key, force, NoteSync.DEFAULT_PREFETCH, new NoteSync.Listener() {
            @Override
            public void onDone(int pool, int ideas, int total, String error, int state) {
                CardDebug.note(CardA11yService.this, "note sync pool=" + pool + " ideas=" + ideas
                        + "/" + total + " state=" + state
                        + (error == null ? "" : (" err=" + error)));
                if (gen != StatsStore.keyGen()) return;         // 换过 Key → 丢弃
                if (view == null) return;                       // 窗口已经摘了 → 丢弃过期 UI 回调
                view.setRefreshing(false);
                if (state == NoteSync.STATE_BUSY) return;       // 别的入口在跑，这轮不算数
                lastNoteState = state;
                if (!PeriodRange.NOTE.equals(StatsStore.getCardPeriod(CardA11yService.this))) {
                    applyVisibility();
                    return;                                     // 用户已切走形态 → 只记状态
                }
                // 拿到内容就换上；仍为空则**停在空态**（这里不会再起同步 —— 环已断）
                boolean got = showNote(false);
                view.setNoteHint(got ? null
                        : (state == NoteSync.STATE_ERROR ? "同步失败，请检查网络" : "还没有同步到笔记"));
                applyVisibility();
            }
        });
    }

    /** 上次"缓存缺封面 URL → 补拉书架"的时刻（防抖：5 分钟内最多一次） */
    private static long sCoverNudgeAt = 0L;

    /**
     * 把当前书的封面接到卡片上（v0.3.5.1）。
     * 缓存命中时同步出图；没缓存走异步下载，完成后回调重绘（回调已在主线程）。
     * 数据是升级前的旧缓存（没有 cover URL）→ 触发一次书架补拉，拿到 URL 后自会回来。
     */
    private void loadCover(BookStats b) {
        if (view == null || b == null || b.bookId == null || b.bookId.length() == 0) return;
        if (b.coverUrl == null || b.coverUrl.length() == 0) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - sCoverNudgeAt > 300_000L) {
                sCoverNudgeAt = now;
                CardDebug.note(this, "cover: 旧缓存缺 cover URL → 补拉书架");
                fetchBookData(StatsStore.getKey(this), false);
            }
            return;
        }
        CoverStore.loadAsync(this, b.bookId, b.coverUrl, 256, new CoverStore.Callback() {
            @Override
            public void onCover(android.graphics.Bitmap bmp) {
                if (view != null) view.setCoverBitmap(bmp, b.bookId);
            }
        });
    }

    private void applyVisibility() {
        if (view == null) return;
        boolean show = CardPrefs.isEnabled(this) && onDesktop && !sOwnUiForeground
                && !shadeOpen                        // 通知栏已下拉 → 让位，别压住通知
                && !iconGate                         // 刚点过桌面图标（书架等）→ 让位（§25）
                && !hideGate                         // 用户长按选择"隐藏 N 分钟" → 让位（v0.3.4）
                && desktopPage < PAGE_HIDE_FROM;     // 进了桌面的隐藏页（如 ELauncher「设置」）→ 让位
        view.setVisibility(show ? View.VISIBLE : View.GONE);
        // 触摸区跟着一起显隐 —— 卡片藏起来时它们必须也走开，
        // 否则会在看不见的地方继续吃掉桌面的点击
        if (hitView != null) hitView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (titleView != null) titleView.setVisibility(show ? View.VISIBLE : View.GONE);
        // 「打开」只在本书形态存在：周/月形态下它要完全让开，不然会吃掉右下角的桌面手势
        String cardMode = StatsStore.getCardPeriod(this);
        boolean bookMode = PeriodRange.BOOK.equals(cardMode);
        boolean noteMode = PeriodRange.NOTE.equals(cardMode);
        if (openView != null) {
            openView.setVisibility((show && (bookMode || noteMode)) ? View.VISIBLE : View.GONE);
        }
        // 「上一条」只在本记形态存在 —— 其它形态下它必须完全让开，否则会吃掉左下角的桌面手势（v0.4.2）
        if (prevView != null) {
            prevView.setVisibility((show && noteMode) ? View.VISIBLE : View.GONE);
        }
        if (menuView != null) {
            menuView.setVisibility((show && menuOpen) ? View.VISIBLE : View.GONE);
        }
        CardDebug.note(this, "visibility=" + (show ? "VISIBLE" : "GONE")
                + " (enabled=" + CardPrefs.isEnabled(this)
                + ", onDesktop=" + onDesktop
                + ", page=" + desktopPage
                + ", shade=" + shadeOpen
                + ", icon=" + iconGate
                + ", hide=" + hideGate
                + ", ownUi=" + sOwnUiForeground + ")");
    }

    private void removeWindow() {
        // 六个窗口视图全部要摘干净 —— 漏掉任何一个，下次 ensureWindow 会再 addView 一个，
        // 旧的那个就永久留在 WindowManager 里（看不见但一直吃掉那片区域的触摸）。
        // v0.4.2 补上：原来只摘了 view / hitView / titleView，openView / menuView 一直没摘。
        removeSafely(view);
        removeSafely(hitView);
        removeSafely(titleView);
        removeSafely(openView);
        removeSafely(prevView);
        removeSafely(menuView);

        view = null;
        hitView = null;
        titleView = null;
        openView = null;
        prevView = null;
        menuView = null;
        windowAdded = false;
    }

    private void removeSafely(View v) {
        if (wm == null || v == null) return;
        try {
            wm.removeView(v);
        } catch (Throwable ignored) {
        }
    }

    private boolean isLauncher(String pkg) {
        if (launchers.isEmpty()) loadLaunchers();
        return launchers.contains(pkg);
    }

    /**
     * 前台是不是"桌面本身"。
     *
     * 两级判断：① 包名必须是某个桌面；② 更关键的一步 —— Activity 必须是那个桌面
     * **声明为 CATEGORY_HOME 的那一个**。见 {@link #homeComponents} 的说明。
     */
    private boolean isHomeScreen(String pkg, String cls) {
        if (!isLauncher(pkg)) return false;
        // 组件名单没查到（查询被系统过滤等）→ 退回包名判断，宁可在桌面上显示
        if (homeComponents.isEmpty()) return true;
        return homeComponents.contains(pkg + "/" + cls);
    }

    private void loadLaunchers() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo == null) continue;
                    String p = ri.activityInfo.packageName;
                    // FallbackHome 是开机时系统还没拉起桌面之前的过渡黑屏，别在它上面显示
                    if ("com.android.settings".equals(p)) continue;
                    launchers.add(p);
                    if (ri.activityInfo.name != null) {
                        homeComponents.add(p + "/" + ri.activityInfo.name);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 兜底：万一查询被系统过滤成空表（Android 11 包可见性，已在 manifest 用 <queries> 解决），
        // 至少认下本机已知的桌面，别让卡片因为"一个桌面包都没认出来"而永远不显示。
        if (launchers.isEmpty()) {
            launchers.add("com.astraabove.tomo");
            launchers.add("com.wetao.elauncher");
        }
        if (homeComponents.isEmpty()) {
            homeComponents.add("com.astraabove.tomo/com.astraabove.tomo.LauncherActivity");
            homeComponents.add("com.wetao.elauncher/com.wetao.elauncher.ui.main.MainActivity");
        }
        CardDebug.note(this, "launchers = " + launchers + "  homeComponents = " + homeComponents);
    }
}
