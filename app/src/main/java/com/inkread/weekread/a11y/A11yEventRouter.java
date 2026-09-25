package com.inkread.weekread.a11y;

import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.CardSpec;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无障碍事件的**订阅与分发**（TASK-006 从 `CardA11yService` 整段平移而来，含全部注释）。
 *
 * 职责三块：① 桌面识别（`isHomeScreen` / 默认桌面自适应）；② 通知栏与图标点按闸门；
 * ③ 把事件按类型转给两个 Gate，或落到自己的闸门上。
 *
 * 🔴 **判据一行未改**；它只写 {@link CardVisibilityState}，显隐由
 * {@code overlay.applyVisibility()} 统一出口处理。
 */
final class A11yEventRouter {

    private final Context ctx;
    private final android.os.Handler ui;
    private final CardVisibilityState st;
    private OverlayController ov;
    private TomoPageGate tomo;
    private ElauncherPageGate ela;

    /** 事件去重用（避免日志被高频事件刷爆）—— 纯日志用途，不算让位状态 */
    private String lastPkg = null;
    private int lastType = -1;
    private String lastCls = null;

    A11yEventRouter(Context ctx, android.os.Handler ui, CardVisibilityState st) {
        this.ctx = ctx;
        this.ui = ui;
        this.st = st;
    }

    void attach(OverlayController ov, TomoPageGate tomo, ElauncherPageGate ela) {
        this.ov = ov;
        this.tomo = tomo;
        this.ela = ela;
    }


    /**
     * 桌面当前在第几页（0 = 第 1 页）。
     *
     * ⚠️ 只能当"不小于真实页码"的粗略值用 —— 见 {@link #PAGE_HIDE_FROM} 里的事件投递实测。
     * 唯一可靠的用途是判断"是不是进了 ELauncher 的隐藏页（设置页）"。
     *
     * Tomo 桌面没有这种 ViewPager（不发这类事件），此值恒为 0，完全不受影响。
     */
    /** 待生效的页码，见 {@link #PAGE_DEBOUNCE_MS} */

    /**
     * 页码去抖窗口（毫秒）。
     *
     * 为什么要去抖：`scrollX` 在**页面切换动画途中**会被投递一个中间值。
     * 实测按 HOME 从「设置」页回桌面时，先来一条 `scrollX=480`、220ms 后才是 `scrollX=0`；
     * 不去抖的话卡片会"先藏一下、再出现"，墨水屏上就是两次全屏刷新，非常显眼。
     * 所以收到新页码先记下来，静置这么久没有更新的值才真正生效。
     */
    private static final long PAGE_DEBOUNCE_MS = 220;
    private final Runnable applyPendingPage = new Runnable() {
        @Override
        public void run() {
            if (st.pendingPage < 0) return;
            int p = st.pendingPage;
            st.pendingPage = -1;
            if (p != st.desktopPage) {
                st.desktopPage = p;
                CardDebug.note(ctx, "st.desktopPage=" + p + " (debounced)");
                ov.applyVisibility();
            }
        }
    };

    /**
     * 系统**默认**桌面包（`MATCH_DEFAULT_ONLY`）—— 翻页指纹只在这一个包上生效。
     *
     * 为什么不是"当前前台桌面"：ELauncher 也是 CATEGORY_HOME，点图标进它一次，
     * "前台桌面"就变成它了，而它那份 `FrameLayout` 事件的语义和 Tomo 的
     * `swipe_page` 完全不同（真机上抓到这个污染：切进 ELauncher 再回来，卡片被
     * 它的事件改成了"显示"）。锁在"系统默认桌面"上就没这个问题。
     *
     * ★ **运行期自动适配**（v0.6.0）：用户换默认桌面后**不必重启无障碍服务** ——
     * 见到"别的桌面包在活动"就地重查一次，见 {@link #maybeRefreshDefaultHome}。
     */
    /**
     * 上一次重查默认桌面的时刻（`SystemClock.uptimeMillis()`），0 = 还没查过。
     * 见 {@link #maybeRefreshDefaultHome} 的冷却说明。
     */
    /** 两次重查默认桌面之间的最小间隔 —— 解析要走一次 PackageManager IPC，不能每条事件都查 */
    private static final long HOME_RESOLVE_MIN_MS = 10_000L;
    /**
     * 默认桌面刚被换掉的时刻（`SystemClock.uptimeMillis()`），0 = 没过。
     * 见 {@link #HOME_CHANGE_IGNORE_MS}。
     */
    /**
     * 换桌面后的**抑制窗**：期内桌面事件一律不听。
     *
     * 理由（真机踩到）：换桌面那一刻，新桌面正在做**冷启动整窗重绘**，而它的指纹
     * 与"真的翻了一页"**完全同形** —— 实测 Tomo 接手默认桌面时，那条孤立的
     * `FrameLayout` TEXT 事件被判成"离开第 1 页"，卡片就此藏死（按 HOME 也回不来，
     * 因为 `desk == st.onDesktop` 不会触发重算）。等它几百毫秒稳定下来，判据才可靠。
     *
     * 与 {@link #SCREEN_ON_IGNORE_MS} 同一个思路：**宁可少让位一次，也不能把卡片藏死**
     * —— 少让位一次用户看得出来（第 2 页多显示一会儿），藏死则像是应用坏了。
     */
    private static final long HOME_CHANGE_IGNORE_MS = 2_000L;
    /** 见 {@link #homeChangedAt}：一个抑制窗里最多留几条日志，免得刷屏 */
    /**
     * 发过 ViewPager 事件的桌面包 —— 这类桌面走 {@link #handleDesktopPage} 的
     * `scrollX` 判据，**不再**启用本帧指纹，免得两套判据在同一台上互相打架。
     */

    // ── 通知栏（状态栏）让位闸门 ──

    /**
     * 通知栏是否处于「已下拉」状态。true 时卡片让位。
     *
     * ⚠️ 为什么单开一个闸门、而不是顺手把 st.onDesktop 翻成 false（第 4 轮实测结论）：
     * 卡片是 `TYPE_ACCESSIBILITY_OVERLAY`，**z 序高于 systemui 的通知栏面板**，
     * 所以下拉状态栏时不是"卡片被盖住"，而是"卡片盖住了通知"（有截图实证：
     * 通知文字被卡片压住）。下拉时桌面的窗口**根本没被销毁**，收起时也不会补发
     * 任何 launcher 事件 —— 一旦在这里翻掉 st.onDesktop，就永远回不来了。
     *
     * 而"只挂一个独立闸门"让"收起后恢复"变成**天然**结果：下拉前的
     * st.onDesktop / st.desktopPage 完全没被动过，收起时重算一遍，原来该显示的还显示、
     * 原来该藏的还藏。顺带绕开了"ELauncher 页码测不准"这个死结 ——
     * 压根不需要知道用户当时在第几页。
     */

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
     * 所以新增这道闸门：**只由"点了桌面图标"置位，只影响显隐，不碰页码与 st.onDesktop**。
     *
     * ── 为什么不会把自己藏死（三道恢复）──
     * ① 任何**真实应用**的窗口事件（含我们自己的界面）→ 立刻清闸门（点封面进阅读器就是这条）；
     * ② 桌面 ViewPager 再有任何**翻页事件** → 清闸门（按 HOME 回桌面实测会先报 `scrollX=0`）；
     * ③ 兜底超时 {@link #GATE_MAX_MS}（3 分钟）→ 无条件清闸门，**宁可多显示、不可永久消失**。
     */
    /** 闸门置位时刻（`SystemClock.uptimeMillis()`），供最小保持时间与兜底超时用 */

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
            if (!st.iconGate) return;
            st.iconGate = false;
            CardDebug.note(ctx, "st.iconGate=false (timeout " + (GATE_MAX_MS / 1000) + "s)");
            ov.applyVisibility();
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

    public void onEvent(AccessibilityEvent event) {
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
            CardDebug.note(ctx, "event " + AccessibilityEvent.eventTypeToString(type)
                    + " pkg=" + pkg + " cls=" + clsForLog
                    + " ownUi=" + CardA11yService.sOwnUiForeground);
        }

        // ── 桌面翻页：非第 1 页时卡片让位（§21.5 / §v0.6.0） ──
        // 翻页不换窗口，所以不会有 WINDOW_STATE_CHANGED，只能靠这一条。
        // 两个桌面的翻页事件形态不同，各判各的、互不影响：
        //   · Tomo      → swipe_page 的 FrameLayout content-changed（见 handleLauncherSwipe）
        //   · ELauncher → viewPager 的 scrollX（见 handleDesktopPage）
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            maybeRefreshDefaultHome(pkg);           // 用户可能刚换过默认桌面（带冷却）
            // 刚换过默认桌面：新桌面这几百毫秒在做冷启动整窗重绘，指纹与"真翻页"同形
            //（实测踩到：Tomo 接手时被判成"离开第 1 页"，卡片藏死）。这段一律不听。
            if (inHomeChangeSettle(pkg, clsForLog)) return;
            tomo.noteHomeTextNode(pkg, clsForLog);       // 时钟块自己的文本变化（区分整分用）
            ela.noteElauncherWindow(pkg, clsForLog, event);   // ELauncher 的翻页 / resume 判据
            tomo.handleLauncherSwipe(pkg, clsForLog, event);
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

        // ── 通知栏（状态栏）开合：只动 st.shadeOpen 闸门，绝不翻转桌面状态 ──
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
        if (ctx.getPackageName().equals(pkg)) {
            boolean ownScreen = clsForLog.endsWith(".MainActivity") || clsForLog.endsWith(".SettingsActivity");
            if (!ownScreen) return;                // 自己的悬浮窗事件：忽略，维持现状
            // 用户在我们的界面里：桌面上那张卡片要让位（主页已经整屏显示同一张卡片了）
            CardA11yService.sOwnUiForeground = true;
            if (st.onDesktop) {
                st.onDesktop = false;
                ov.applyVisibility();
            }
            return;
        }

        // ── 瞬时系统浮层：一律"维持现状"，不翻转桌面状态（§21.4 方案 A） ──
        // 为什么必须这样：下拉状态栏时桌面的窗口**根本没被销毁**，只是被浮层盖住，
        // 所以收起状态栏**不会**再发一条 launcher 的 WINDOW_STATE_CHANGED。
        // 一旦在这里把 st.onDesktop 置成 false，就再也没有事件能把它改回 true
        // → 卡片永久消失（用户报的 3(b) 就是这个，已实测复现）。
        if (isTransientOverlay(pkg, clsForLog)) {
            CardDebug.note(ctx, "ignore transient overlay pkg=" + pkg + " cls=" + clsForLog);
            return;
        }

        // ── 漏投兜底闸门 ──
        // 能走到这里 = 这是一条来自"别的真实应用 / 桌面"的窗口事件。
        // 打开任何界面都会先收起通知栏，所以此刻通知栏必然已经收起。
        // 万一"收起"那条系统事件被漏投，卡片也不会被永久藏死。
        if (st.shadeOpen) {
            st.shadeOpen = false;
            CardDebug.note(ctx, "st.shadeOpen=false (real window changed: " + pkg + ")");
        }

        // ── 图标点按闸门的恢复（§25 恢复条件 ①） ──
        // 能走到这里 = 前台换成了一个真实界面。无论它是别的应用（点封面进阅读器）、
        // 还是桌面本身（按 HOME 回来），都说明"刚点的那一下"已经结束了 → 清闸门。
        // 注意：清完必须**强制重算一次显隐**，因为下面那段只在 desk!=st.onDesktop 时才重算，
        // 而"从书架按 HOME 回桌面"时 st.onDesktop 本来就是 true → 不强制的话卡片不会回来。
        boolean gateCleared = false;
        if (st.iconGate) {
            st.iconGate = false;
            gateCleared = true;
            if (ui != null) ui.removeCallbacks(gateTimeout);
            CardDebug.note(ctx, "st.iconGate=false (real window changed: " + pkg + ")");
        }

        // 桌面判定必须精确到 Activity：桌面包里还挂着书架 / 文件管理 / 设置等普通界面，
        // 只看包名会把它们误判成"还在桌面"（§21.3）。
        boolean desk = isHomeScreen(pkg, clsForLog);
        if (desk) {
            // 进桌面（含亮屏、从应用按 HOME 回来）Tomo 会额外补一条 cct=1，它不是翻页。
            //    但**不能整窗丢弃**：形态 ③（回到桌面后又真的翻回第 1 页）里真正起区分
            //    作用的 TEXT 事件，是紧跟在这条窗口态之后才到的，丢了就再也看不到了。
            //    所以只打标记 + 延长窗口，由 settleSwipe 按"有无 TEXT"分流。
            tomo.noteLauncherWindowState();
        }
        boolean wasOwnUi = CardA11yService.sOwnUiForeground;
        if (desk) {
            // 已经在（桌面）的界面上，那肯定不在我们自己的界面里。
            // 这同时是 noteOwnUiForeground(false) 万一没被调到的兜底。
            CardA11yService.sOwnUiForeground = false;
        }
        CardDebug.note(ctx, "decide pkg=" + pkg + " cls=" + clsForLog
                + " desk=" + desk + " was=" + st.onDesktop + " page=" + st.desktopPage);
        // 注意第二个条件：从自家界面回到桌面时 st.onDesktop 可能本来就是 true
        // （进自家界面只改了标志、没改 st.onDesktop），这时也必须重算一次，
        // 否则卡片会一直藏着不出来。
        if (desk != st.onDesktop || (desk && wasOwnUi) || gateCleared) {
            st.onDesktop = desk;
            if (desk) {
                // 进入桌面时**乐观**地按"第 1 页"处理：HOME 键、从应用返回，
                // 落点几乎都是第 1 页。若实际停在第 2 页 / 设置页，随后那个
                // ViewPager 事件会在 220ms 内把页码纠正过来、再把卡片藏回去。
                // 反过来（先藏后显）的代价更大 —— 卡片"该出现时半天不出现"，
                // 用户会以为它坏了。
                st.desktopPage = 0;
                st.pendingPage = -1;
                if (ui != null) ui.removeCallbacks(applyPendingPage);
            }
            ov.applyVisibility();
        }
        // 换桌面的"官宣"就在这条窗口事件上 —— 新桌面接手时会**先发它、再发冷启动重绘**
        //（真机实测：Tomo 接手时 `WINDOW_STATE_CHANGED` 在前、那条惹祸的 `FrameLayout` 在后）。
        // 在这里补一次重查 ⇒ 换桌面**立刻**生效；否则只能等新桌面下一次整分重绘，
        // 实测差了近 60s，而这段时间里新桌面自己的翻页判据还处于"没认出来"的停摆状态。
        // 必须排在 decide 之后：下面要按刚更新过的 st.onDesktop 重算显隐。
        maybeRefreshDefaultHome(pkg);
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
    void handleDesktopClick(String pkg, String cls) {
        if (!isLauncher(pkg)) return;
        if (CardA11yService.sOwnUiForeground) return;              // 在自家界面里一切两清

        // ── 图标白名单（2026-09-22 实测教训，问题 2 的第二层修复）──
        // 用户点卡片抬头没命中我们触摸窗的部分会**穿透**给桌面；在图墨桌面上，
        // 抬头下方正好是它的小组件容器，点击报
        //   TYPE_VIEW_CLICKED pkg=com.astraabove.tomo cls=android.widget.FrameLayout
        // 旧判据只看包名 → 误触发 st.iconGate 让位；而图墨桌面既没有 ViewPager 翻页事件、
        // 点击后也不保证补发窗口事件（实测第三次点击后 40 秒无恢复）→ 卡片"消失"。
        // 只有**图标类**控件（点应用图标进应用）才需要这道闸门 —— 那类场景下
        // 一定会有后续窗口事件恢复。容器/文本类（小组件、时钟、空白容器）一律忽略。
        // 已知事件形态（均实测）：
        //   点应用图标（ELauncher 书架）  cls=android.widget.ImageView   → 置位（保留）
        //   图墨小组件容器被穿透点击      cls=android.widget.FrameLayout → 忽略（本修复）
        boolean iconLike = cls.endsWith("ImageView") || cls.endsWith("ImageButton");
        if (!iconLike) {
            CardDebug.note(ctx, "click cls=" + cls + " → 忽略（非图标，不置闸门）");
            return;
        }
        if (st.iconGate
                && android.os.SystemClock.uptimeMillis() - st.iconGateAt < GATE_MIN_HOLD_MS) {
            return;                                // 刚置位又点了一下：保持原计时，别把超时无限续期
        }
        st.iconGate = true;
        st.iconGateAt = android.os.SystemClock.uptimeMillis();
        if (ui != null) {
            ui.removeCallbacks(gateTimeout);
            ui.postDelayed(gateTimeout, GATE_MAX_MS);
        }
        CardDebug.note(ctx, "iconTap → 让位 (pkg=" + pkg + ")");
        ov.applyVisibility();
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
    boolean isTransientOverlay(String pkg, String cls) {
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
     *   即使 st.shadeOpen 早就是 false 也照算 —— 这样上面那条兜底闸门万一误清，
     *   真正的收起事件也能把状态拉回来（{@code View.setVisibility} 传入相同值不会重绘，
     *   所以无条件重算没有代价）。
     */
    void handleShadeState(AccessibilityEvent event) {
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
        CardDebug.note(ctx, "shadeEvt cct=" + cct + " n=" + n + " title=" + title
                + " → close=" + closeSig + " open=" + openSig + " (cur=" + st.shadeOpen + ")");

        if (closeSig) {
            st.shadeOpen = false;
            ov.applyVisibility();                     // 无条件重算：这就是"收起后恢复"
            return;
        }
        if (openSig && !st.shadeOpen) {
            st.shadeOpen = true;
            ov.applyVisibility();
        }
    }

    /** 事件文本里有没有通知栏面板的标题（见 {@link #SHADE_TITLE}） */
    boolean hasShadeTitle(AccessibilityEvent event) {
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
    void probeAll(AccessibilityEvent e, int type, String pkg, String cls) {
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
        CardDebug.note(ctx, sb.toString());
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
    void handleDesktopPage(String pkg, String cls, AccessibilityEvent event) {
        if (!isLauncher(pkg)) return;
        if (!cls.endsWith("ViewPager")) return;
        // 这台桌面有 ViewPager 判据 ⇒ 关掉那条"FrameLayout 翻页指纹"（v0.6.0）。
        // 两套判据同时生效会在同一台上互相打架；实测 Tomo 不发 ViewPager 事件、
        // ELauncher 会发，所以这个运行期标记正好把两者自动分开，不用写死包名。
        st.sawViewPager.add(pkg);
        // 🔴 顺手把「Tomo 那条 FrameLayout 判据」的窗口掐掉。原因：服务刚重连时
        // {@link #sawViewPager} 还是空的，而同一次翻页里 **FrameLayout 事件先到**，
        // 于是 Tomo 判据漏过分流、误触发 —— 真机复现（2026-09-24）：覆盖安装后按 HOME
        // 回桌面，卡片被判成"离开第 1 页"而隐藏（日志 `→ 离开第1页 … visibility=GONE`）。
        // ViewPager 事件一到就作废那个窗口 ⇒ 两套判据立刻互斥，不依赖"谁先建立记忆"。
        tomo.cancelSwipeWindow();
        // 桌面能吃翻页手势 ⇒ 屏幕没有被通知栏盖住（盖着时桌面收不到触摸）→ 兜底清闸门
        if (st.shadeOpen) {
            st.shadeOpen = false;
            CardDebug.note(ctx, "st.shadeOpen=false (desktop page event)");
        }
        // ── 图标点按闸门的恢复（§25 恢复条件 ②） ──
        // 桌面 ViewPager 又发事件 ⇒ 用户确实在对桌面做翻页动作（按 HOME 从书架回桌面时
        // 实测会先报 scrollX=0）。但点「书架」时那条 scrollX=0 **紧贴着点击事件**到达，
        // 必须用最小保持时间把它隔开，否则闸门刚置位就被清掉、等于没做。
        boolean gateCleared = false;
        if (st.iconGate
                && android.os.SystemClock.uptimeMillis() - st.iconGateAt >= GATE_MIN_HOLD_MS) {
            st.iconGate = false;
            gateCleared = true;
            if (ui != null) ui.removeCallbacks(gateTimeout);
            CardDebug.note(ctx, "st.iconGate=false (desktop page event)");
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
        CardDebug.note(ctx, "pageRaw sx=" + sx + " cct=" + cct + " → page=" + page
                + " (cur=" + st.desktopPage + ")");
        // 拖动过程中会经过中间值，交给去抖窗口决定最终停在哪一页
        if (page != st.pendingPage) {
            st.pendingPage = page;
            if (ui != null) {
                ui.removeCallbacks(applyPendingPage);
                ui.postDelayed(applyPendingPage, PAGE_DEBOUNCE_MS);
            }
        }
        // 闸门被清掉时立即重算一次：去抖回调只在"页码真的变了"时才重算，
        // 而这次翻页完全可能把页码停在原值（比如书架回来仍是"第 1 页"），
        // 不强制重算的话卡片就回不来了。
        if (gateCleared) ov.applyVisibility();
    }

    boolean isLauncher(String pkg) {
        if (st.launchers.isEmpty()) loadLaunchers();
        return st.launchers.contains(pkg);
    }

    /**
     * 默认桌面的**运行期自适应**（v0.6.0）—— 用户换默认桌面后不必重启无障碍服务。
     *
     * 原先 `st.defaultHomePkg` 只在服务连接时解析一次；用户换桌面后那份缓存就过期了，
     * 得关掉再打开无障碍开关才生效（真机上踩过）。这里换个思路：**见到"某个桌面包在
     * 活动、但它不是当前记住的那个默认桌面"就地重查一次** —— 换桌面必然伴随新桌面的
     * 事件（回桌面 / 翻页），所以纯事件驱动就够，不用轮询。
     *
     * 🔴 两条约束：
     *   · **必须带冷却**（{@link #HOME_RESOLVE_MIN_MS}）：解析要走一次 PackageManager
     *     IPC（毫秒级、在主线程），不能每条事件都查。
     *   · 重查判据始终是**系统默认桌面**（`MATCH_DEFAULT_ONLY`），**不是"当前前台"**
     *     —— 后者会把"点图标进了另一个桌面"误当成换桌面（见 {@link #defaultHomePkg}）。
     *
     * 真换桌面时顺手把翻页状态归零：新桌面的页码与旧桌面无关，卡片应回到"显示"。
     */
    void maybeRefreshDefaultHome(String pkg) {
        if (!isLauncher(pkg)) return;
        if (pkg.equals(st.defaultHomePkg)) return;      // 就是当前那个，没必要查
        long now = android.os.SystemClock.uptimeMillis();
        if (st.lastHomeResolveAt != 0 && now - st.lastHomeResolveAt < HOME_RESOLVE_MIN_MS) return;
        st.lastHomeResolveAt = now;
        String before = st.defaultHomePkg;
        resolveDefaultHome();
        if (st.defaultHomePkg == null || st.defaultHomePkg.equals(before)) {
            // 没换（只是别的桌面包在活动，比如点图标进了备用的那一个）⇒ 什么都不做
            CardDebug.note(ctx, "defaultHome 未变 " + st.defaultHomePkg + "（事件来自 " + pkg + "）");
            return;
        }
        CardDebug.note(ctx, "★ defaultHome 随用户切换更新：" + before + " → " + st.defaultHomePkg);
        // ① 开抑制窗：紧接着的几百毫秒是新桌面的冷启动整窗重绘，它的指纹与
        //    "真的翻了一页"同形，不挡掉就会把卡片误判成"离开第 1 页"（见 st.homeChangedAt）。
        st.homeChangedAt = now;
        st.homeSettleLogged = 0;
        // ② 把上一个桌面残留的翻页状态全部清掉 —— 新桌面的第 1 页与旧桌面的页码无关
        st.pageGate = false;
        st.desktopPage = 0;
        st.pendingPage = -1;
        tomo.cancelSwipeWindow();
        ela.cancelElaWindow();
        // ③ 此刻人还在桌面上，按"第 1 页"立刻重算一次。
        //    必须显式算：随后那条"新桌面 LauncherActivity"的窗口事件走的是
        //    `desk == st.onDesktop` 那条不重算的分支，靠它卡片回不来。
        ov.applyVisibility();
    }

    /**
     * 默认桌面刚换过、新桌面还在冷启动 —— 这期间的桌面事件**整段不听**，见 {@link #homeChangedAt}。
     *
     * 放在 {@link #maybeRefreshDefaultHome} 之后、各条翻页判据之前，一处挡住两套判据
     *（Tomo 的帧指纹与 ELauncher 的 scrollX），免得各写一遍。
     *
     * 只留头几条日志：抑制期通常就三五条事件，全记会刷屏。
     */
    boolean inHomeChangeSettle(String pkg, String cls) {
        if (st.homeChangedAt == 0) return false;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - st.homeChangedAt >= HOME_CHANGE_IGNORE_MS) return false;
        if (st.homeSettleLogged < 3) {
            st.homeSettleLogged++;
            CardDebug.note(ctx, "home settle ignore (" + pkg + " " + cls
                    + ", 换桌面后 " + (now - st.homeChangedAt) + "ms)");
        }
        return true;
    }

    /** 解析系统默认桌面（`MATCH_DEFAULT_ONLY`）写入 {@link #defaultHomePkg}；失败保持原值 */
    void resolveDefaultHome() {
        try {
            Intent def = new Intent(Intent.ACTION_MAIN);
            def.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager()
                    .resolveActivity(def, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                st.defaultHomePkg = ri.activityInfo.packageName;
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 前台是不是"桌面本身"。
     *
     * 两级判断：① 包名必须是某个桌面；② 更关键的一步 —— Activity 必须是那个桌面
     * **声明为 CATEGORY_HOME 的那一个**。见 {@link #homeComponents} 的说明。
     */
    boolean isHomeScreen(String pkg, String cls) {
        if (!isLauncher(pkg)) return false;
        // 组件名单没查到（查询被系统过滤等）→ 退回包名判断，宁可在桌面上显示
        if (st.homeComponents.isEmpty()) return true;
        return st.homeComponents.contains(pkg + "/" + cls);
    }

    void loadLaunchers() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = ctx.getPackageManager().queryIntentActivities(home, 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo == null) continue;
                    String p = ri.activityInfo.packageName;
                    // FallbackHome 是开机时系统还没拉起桌面之前的过渡黑屏，别在它上面显示
                    if ("com.android.settings".equals(p)) continue;
                    st.launchers.add(p);
                    if (ri.activityInfo.name != null) {
                        st.homeComponents.add(p + "/" + ri.activityInfo.name);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 兜底：万一查询被系统过滤成空表（Android 11 包可见性，已在 manifest 用 <queries> 解决），
        // 至少认下本机已知的桌面，别让卡片因为"一个桌面包都没认出来"而永远不显示。
        if (st.launchers.isEmpty()) {
            st.launchers.add("com.astraabove.tomo");
            st.launchers.add("com.wetao.elauncher");
        }
        if (st.homeComponents.isEmpty()) {
            st.homeComponents.add("com.astraabove.tomo/com.astraabove.tomo.LauncherActivity");
            st.homeComponents.add("com.wetao.elauncher/com.wetao.elauncher.ui.main.MainActivity");
        }
        // 系统**默认**桌面（v0.6.0）：翻页指纹只在这一个包上启用 —— ELauncher 也是
        // CATEGORY_HOME，但它的 FrameLayout 事件语义与 Tomo 的 swipe_page 不同，
        // 真机上抓到过它把卡片状态改错。这里先解析一次；**运行期换桌面**由
        // {@link #maybeRefreshDefaultHome} 兜住，用户不必重启无障碍服务。
        resolveDefaultHome();
        CardDebug.note(ctx, "st.launchers = " + st.launchers + "  st.homeComponents = " + st.homeComponents
                + "  defaultHome = " + st.defaultHomePkg);
    }

    /**
     * 服务要走了：撤掉本类挂在 Handler 上的回调（去抖页码 / 图标闸门超时）。
     *
     * 对应原 `CardA11yService.cancelPending()` 里属于路由的那一半；
     * 两个 Gate 那两半由它们自己的 `cancelSwipeWindow()` / `cancelElaWindow()` 负责。
     */
    void cancelPendingCallbacks() {
        if (ui != null) {
            ui.removeCallbacks(applyPendingPage);
            ui.removeCallbacks(gateTimeout);
        }
    }
}
