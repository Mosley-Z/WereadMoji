package com.inkread.weekread;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
 * ── 另外五类"让位" ──
 * ① 进了 ELauncher 的隐藏页（「设置」，scrollX≥960）→ 让位（§22）；
 * ② 通知栏（状态栏）已下拉 → 让位，收起后自动恢复（§24，见 {@link #shadeOpen}）；
 * ③ 进了我们自己的界面（主页 / 设置页）→ 让位。
 * ④ 用户点按了桌面上的图标 → 让位（§25，见 {@link #iconGate}）。
 * ⑤ **翻离桌面第 1 页** → 让位，回到第 1 页再显示（v0.6.0，见 {@link #pageGate}）。
 * 五者都只影响"是否显示"，**不翻转** onDesktop，避免出现回不来的状态。
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
    private boolean pageGate = false;

    /** 窗口内 `cct=1`（**恰好** SUBTREE，不含 TEXT 位）的条数 */
    private int swipeSub = 0;
    /**
     * 窗口内 FrameLayout 事件的**总条数**（cct 取值 0/1/2/3 都算一条）。
     *
     * 它把两种"只有 TEXT 没有纯 SUBTREE"的形态分开：
     *   · 时钟整分跳字 = **1 条**（只有 cct=3，由 TextView 的文本变化冒泡上来）
     *   · 亮屏后的第一次翻页（离开第 1 页）= **2 条**（cct=3 + cct=2，时钟块消失）
     * 不数这个的话，整分会被当成"翻页离开"，卡片每分钟消失一次。
     */
    private int swipeTotal = 0;
    /**
     * 窗口内是否出现过 TEXT 位（`cct & 2`）。
     *
     * 这是"第 1 页的时钟 / 日期块被重建或消失"的指纹，见 {@link #pageGate} 结论 ②。
     */
    private boolean swipeText = false;
    /**
     * 窗口内是否出现过 `cct=0`（`CONTENT_CHANGE_TYPE_UNDEFINED`）。
     *
     * ★ 它是"桌面 Activity 刚 resume、整窗重绘"的标记（亮屏 / 从应用返回），
     * 见 {@link #pageGate} 结论 ①。它不是"整窗作废"，而是"照这个数量**扣掉**
     * resume 的足迹"，这样"亮屏后马上滑动并进同一窗口"也能正确判向（见 {@link #settleSwipe}）。
     */
    private int swipeZero = 0;
    /** 窗口内是否出现过 `WINDOW_STATE_CHANGED LauncherActivity` —— **仅供日志**，不参与判定 */
    private boolean swipeWinState = false;
    /** 窗口是否开着（用来区分"第一条"和"后续"） */
    private boolean swipeOpen = false;
    /** 最近一条翻页指纹的到达时刻（`SystemClock.uptimeMillis()`），只用来在日志里报"窗口跨度" */
    private long swipeLastAt = 0L;
    /** 本窗口第一条翻页指纹的到达时刻 —— 与 {@link #swipeLastAt} 一起报出窗口跨度 */
    private long swipeFirstAt = 0L;
    /** 本窗口是否已经"延迟"过一次（见 {@link #SWIPE_LINGER_MS}）—— 只允许延一次 */
    private boolean swipeLingered = false;

    // ────────────────────────────────────────────────────────────────────────
    // ELauncher 桌面翻页 / resume 判据（v0.6.0）
    //
    // 与 Tomo 那套（swipeSub / swipeText …）**完全独立**：那个桌面的翻页容器是
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
    //   ⑥ 进「设置」隐藏页              `ViewPager cct=1 sx=480` → `ViewPager cct=3 sx=960`
    //
    // ★ 为什么必须"等 ViewPager 来了才算"（而不是见到 TextView 就判）：
    //   · ④ 整分那条 `TextView` 用 sx=0 就能挡掉，但 ② 与 ④ 的**类名与 cct 完全一样**，
    //     只有 sx 不同 —— 多一道"有没有 ViewPager 伴随"的确认，等于把整分、
    //     以及别的桌面（Tomo 的时钟块）都挡在门外。
    //   · `sawViewPager` 是在收到 ViewPager 事件时才置位的，而 ViewPager 又是
    //     **后**到的那一条 —— 所以结算必须推迟到窗口到期，不能在 TextView 上就地判。
    //
    // ★ ③ 为什么可以判成"回到第 1 页"：ELauncher 的 HOME / 亮屏 / 从应用返回
    //   **一律回到第 1 页**（三轮实测 5/5：从第 2 页按 HOME、亮屏、进应用再返回，
    //   截图像素真值全部落在第 1 页），与 Tomo"回到原来那一页"的行为**相反**。
    //   所以这条 resume 指纹就是"应该显示卡片"的直接理由，见 {@link #settleElauncherWindow}。
    // ────────────────────────────────────────────────────────────────────────

    /** ELauncher 判据的窗口是否开着 */
    private boolean elaOpen = false;
    /** 窗口里见过的"翻页伴随"桌面 `TextView` 的 sx；0 = 没见过 */
    private int elaTextSx = 0;
    /** 窗口里见过桌面 `FrameLayout`（sx=0，= 桌面 resume）吗 */
    private boolean elaFrame = false;
    /** 窗口里见过桌面 `ViewPager` 吗（翻页与 resume 都会发，用来确认"这是本判据认的桌面"） */
    private boolean elaVp = false;
    /** 本窗口是哪个桌面包发起的 —— 结算时用来核对它是"发 ViewPager 的那类桌面" */
    private String elaPkg = null;

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
     * 亮屏时刻（`SystemClock.uptimeMillis()`），0 = 本次服务生命周期内还没见过亮屏。
     * 见 {@link #SCREEN_ON_IGNORE_MS}。
     */
    private long screenOnAt = 0L;

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
    private long lastHomeTextNodeAt = 0L;

    /** 桌面 TextView 事件与翻页指纹算作"同一次"的最大间隔（毫秒），见 {@link #lastHomeTextNodeAt} */
    private static final long HOME_TEXT_NODE_MS = 400L;

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

    /** 只认"屏幕亮了"这一个动作 —— 见 {@link #SCREEN_ON_IGNORE_MS} */
    private BroadcastReceiver screenOnRx;

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
    private String defaultHomePkg = null;
    /**
     * 上一次重查默认桌面的时刻（`SystemClock.uptimeMillis()`），0 = 还没查过。
     * 见 {@link #maybeRefreshDefaultHome} 的冷却说明。
     */
    private long lastHomeResolveAt = 0L;
    /** 两次重查默认桌面之间的最小间隔 —— 解析要走一次 PackageManager IPC，不能每条事件都查 */
    private static final long HOME_RESOLVE_MIN_MS = 10_000L;
    /**
     * 默认桌面刚被换掉的时刻（`SystemClock.uptimeMillis()`），0 = 没过。
     * 见 {@link #HOME_CHANGE_IGNORE_MS}。
     */
    private long homeChangedAt = 0L;
    /**
     * 换桌面后的**抑制窗**：期内桌面事件一律不听。
     *
     * 理由（真机踩到）：换桌面那一刻，新桌面正在做**冷启动整窗重绘**，而它的指纹
     * 与"真的翻了一页"**完全同形** —— 实测 Tomo 接手默认桌面时，那条孤立的
     * `FrameLayout` TEXT 事件被判成"离开第 1 页"，卡片就此藏死（按 HOME 也回不来，
     * 因为 `desk == onDesktop` 不会触发重算）。等它几百毫秒稳定下来，判据才可靠。
     *
     * 与 {@link #SCREEN_ON_IGNORE_MS} 同一个思路：**宁可少让位一次，也不能把卡片藏死**
     * —— 少让位一次用户看得出来（第 2 页多显示一会儿），藏死则像是应用坏了。
     */
    private static final long HOME_CHANGE_IGNORE_MS = 2_000L;
    /** 见 {@link #homeChangedAt}：一个抑制窗里最多留几条日志，免得刷屏 */
    private int homeSettleLogged = 0;
    /**
     * 发过 ViewPager 事件的桌面包 —— 这类桌面走 {@link #handleDesktopPage} 的
     * `scrollX` 判据，**不再**启用本帧指纹，免得两套判据在同一台上互相打架。
     */
    private final Set<String> sawViewPager = new HashSet<>();

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
    private void settleSwipe() {
        if (!swipeOpen) return;
        swipeOpen = false;
        if (ui != null) ui.removeCallbacks(applySwipeWindow);
        int sub = swipeSub;
        int total = swipeTotal;
        boolean text = swipeText;
        int zero = swipeZero;
        boolean ws = swipeWinState;
        boolean lingered = swipeLingered;
        swipeSub = 0;
        swipeTotal = 0;
        swipeText = false;
        swipeZero = 0;
        swipeWinState = false;
        swipeLingered = false;
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
            CardDebug.note(this, "swipe drop (resume 重绘 sub=" + sub + " total=" + total
                    + " zero=" + zero + " text=" + text + " ws=" + ws + ")");
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
            CardDebug.note(this, "swipe linger (等伙伴 sub=" + sub + " total=" + total
                    + " zero=" + zero + " ws=" + ws + ")");
            swipeLingered = true;
            swipeOpen = true;
            swipeSub = sub;
            swipeTotal = total;
            swipeText = text;
            swipeZero = zero;
            swipeWinState = ws;
            if (ui != null) ui.postDelayed(applySwipeWindow, SWIPE_LINGER_MS);
            return;
        } else {
            // 等过了、伙伴还是没来。这条孤立 TEXT 指纹有两种可能，靠"窗口附近有没有
            // 桌面 TextView 事件"分开（见 lastHomeTextNodeAt 的实测对照）：
            boolean nearbyTextNode = lastHomeTextNodeAt != 0
                    && lastHomeTextNodeAt >= swipeFirstAt - HOME_TEXT_NODE_MS
                    && lastHomeTextNodeAt <= swipeLastAt + HOME_TEXT_NODE_MS;
            if (nearbyTextNode) {
                // 时钟整分跳字（时钟自己的 TextView 变了，子树跟着重绘）→ 什么都不做
                CardDebug.note(this, "swipe ignore (整分, 有 TextView 伴随 sub=" + sub
                        + " total=" + total + " span=" + (swipeLastAt - swipeFirstAt) + "ms)");
                return;
            }
            // 没有 TextView 伴随 ⇒ 是"亮屏后翻页离开第 1 页"那种只发一条 cct=3 的形态
            leave = true;
        }
        CardDebug.note(this, "swipe sub=" + sub + "→" + subEff + " total=" + total + "→" + totalEff
                + " text=" + text + " zero=" + zero + " ws=" + ws
                + " span=" + (swipeLastAt - swipeFirstAt) + "ms"
                + " → " + (leave ? "离开第1页" : "落到第1页") + " (cur=" + pageGate + ")");
        if (leave != pageGate) {
            pageGate = leave;
            applyVisibility();
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
    private void cancelSwipeWindow() {
        if (!swipeOpen) return;
        swipeOpen = false;
        swipeSub = 0;
        swipeTotal = 0;
        swipeText = false;
        swipeZero = 0;
        swipeWinState = false;
        swipeLingered = false;
        if (ui != null) ui.removeCallbacks(applySwipeWindow);
    }

    /**
     * 作废 ELauncher 判据的未结算窗口（不清 {@code pageGate} 本身）。
     *
     * 用途与 {@link #cancelSwipeWindow} 对称：亮屏、服务重连、手动重置时，
     * 把"半截窗口"扔掉，免得它带着过期的证据在 400ms 后改状态。
     */
    private void cancelElaWindow() {
        if (!elaOpen) return;
        elaOpen = false;
        elaTextSx = 0;
        elaFrame = false;
        elaVp = false;
        elaPkg = null;
        if (ui != null) ui.removeCallbacks(applyElaWindow);
    }

    /** 把 ELauncher 判据的字段全部归零（服务重连 / 手动重置用，无回调可摘） */
    private void resetElaFields() {
        elaOpen = false;
        elaTextSx = 0;
        elaFrame = false;
        elaVp = false;
        elaPkg = null;
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
    private void noteLauncherWindowState() {
        if (!swipeOpen) return;
        swipeWinState = true;
    }

    /**
     * 记下"桌面自己的 TextView 内容变化"（时钟/日期块）—— 见 {@link #lastHomeTextNodeAt}。
     *
     * ⚠️ 必须在 {@link #handleLauncherSwipe} **之前**调用：实测时钟整分时
     * TextView 那条**先到**、`FrameLayout cct=3` 后到，等翻页指纹开窗时
     * TextView 早过去了，反过来就抓不到。
     */
    private void noteHomeTextNode(String pkg, String cls) {
        if (defaultHomePkg == null || !pkg.equals(defaultHomePkg)) return;
        if (!cls.endsWith("TextView")) return;
        lastHomeTextNodeAt = android.os.SystemClock.uptimeMillis();
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
    private void noteElauncherWindow(String pkg, String cls, AccessibilityEvent event) {
        if (!isLauncher(pkg)) return;
        if (sOwnUiForeground) return;              // 在自家界面里一切两清
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
        elaOpen = true;
        elaPkg = pkg;
        if (isVp) elaVp = true;
        if (isFrame) elaFrame = true;
        if (isText) elaTextSx = sx;
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
     *     （> 分界 = 离开第 1 页；≤ 分界 = 落到第 1 页）
     *   · 其余 sx 认不出来                      → 不动（宁可不改，也不改错）
     */
    private void settleElauncherWindow() {
        if (!elaOpen) return;
        elaOpen = false;
        if (ui != null) ui.removeCallbacks(applyElaWindow);
        boolean frame = elaFrame, vp = elaVp;
        int sx = elaTextSx;
        String pkg = elaPkg;
        elaFrame = false;
        elaVp = false;
        elaTextSx = 0;
        elaPkg = null;
        // ① 没有 ViewPager ⇒ 不是"发 ViewPager 的那类桌面"的翻页/resume
        if (!vp || pkg == null || !sawViewPager.contains(pkg)) return;
        boolean leave;
        if (frame) {
            leave = false;                          // ② 桌面 resume ⇒ 回到第 1 页
        } else if (sx >= ELA_SX_LO && sx <= ELA_SX_HI) {
            leave = sx > ELA_SX_SPLIT;              // ③/④ 真翻页，按 sx 落在分界的哪一侧定向
        } else {
            // ⑤ 认不出的 sx（比如 ROM 换了残值）⇒ 不动作，只留痕，便于日后诊断
            CardDebug.note(this, "elaSwipe 不动 (frame=" + frame + " vp=" + vp
                    + " sx=" + sx + " cur=" + pageGate + ")");
            return;
        }
        CardDebug.note(this, "elaSwipe frame=" + frame + " sx=" + sx
                + " → " + (leave ? "离开第1页" : "落到第1页") + " (cur=" + pageGate + ")");
        if (leave != pageGate) {
            pageGate = leave;
            applyVisibility();
        }
    }

    /** {@link #ELA_WINDOW_MS} 到期回调 */
    private final Runnable applyElaWindow = new Runnable() {
        @Override
        public void run() {
            settleElauncherWindow();
        }
    };

    /**
     * 订阅系统亮屏广播 —— 见 {@link #SCREEN_ON_IGNORE_MS} 说明为什么要它。
     *
     * 运行时注册即可，不需要权限、也不用写进清单（`ACTION_SCREEN_ON` 是系统广播，
     * 只允许系统发，但任何应用都能注册接收）。注册失败不影响主流程 ——
     * 拿不到就退回"靠 `cct=0` 认亮屏"那套（见 {@link #swipeZero}），
     * 所以这里吞掉异常、只记一条日志。
     */
    private void registerScreenOn() {
        if (screenOnRx != null) return;
        screenOnRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                screenOnAt = android.os.SystemClock.uptimeMillis();
                cancelSwipeWindow();               // 亮屏附带的那半截窗口直接作废
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
        // 服务刚连上时桌面停在哪一页无从得知 ⇒ 按"第 1 页"乐观处理（宁可先显示出来，
        // 用户翻一下页就会纠正）。这同时是"万一闸门卡住"的兜底恢复口之一。
        pageGate = false;
        swipeOpen = false;
        swipeSub = 0;
        swipeTotal = 0;
        swipeText = false;
        swipeZero = 0;
        swipeWinState = false;
        swipeLingered = false;
        swipeLastAt = 0L;
        swipeFirstAt = 0L;
        shadeOpen = false;                          // 服务刚连上时通知栏一定没下拉
        iconGate = false;                           // 同理，刚连上时不存在"刚点过图标"
        iconGateAt = 0L;
        hideGate = false;                           // 也没人长按过（进程刚起来）
        hideUntil = 0L;
        menuOpen = false;
        screenOnAt = 0L;
        lastHomeTextNodeAt = 0L;
        resetElaFields();
        registerScreenOn();
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
            noteHomeTextNode(pkg, clsForLog);       // 时钟块自己的文本变化（区分整分用）
            noteElauncherWindow(pkg, clsForLog, event);   // ELauncher 的翻页 / resume 判据
            handleLauncherSwipe(pkg, clsForLog, event);
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
        if (desk) {
            // 进桌面（含亮屏、从应用按 HOME 回来）Tomo 会额外补一条 cct=1，它不是翻页。
            //    但**不能整窗丢弃**：形态 ③（回到桌面后又真的翻回第 1 页）里真正起区分
            //    作用的 TEXT 事件，是紧跟在这条窗口态之后才到的，丢了就再也看不到了。
            //    所以只打标记 + 延长窗口，由 settleSwipe 按"有无 TEXT"分流。
            noteLauncherWindowState();
        }
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
        // 换桌面的"官宣"就在这条窗口事件上 —— 新桌面接手时会**先发它、再发冷启动重绘**
        //（真机实测：Tomo 接手时 `WINDOW_STATE_CHANGED` 在前、那条惹祸的 `FrameLayout` 在后）。
        // 在这里补一次重查 ⇒ 换桌面**立刻**生效；否则只能等新桌面下一次整分重绘，
        // 实测差了近 60s，而这段时间里新桌面自己的翻页判据还处于"没认出来"的停摆状态。
        // 必须排在 decide 之后：下面要按刚更新过的 onDesktop 重算显隐。
        maybeRefreshDefaultHome(pkg);
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
    private void handleLauncherSwipe(String pkg, String cls, AccessibilityEvent event) {
        if (!isLauncher(pkg)) return;
        if (!SWIPE_NODE_CLS.equals(cls)) return;
        // 只认**系统默认桌面**。ELauncher 同样是 CATEGORY_HOME（也可能成为前台），
        // 它那份 FrameLayout 事件的语义和 Tomo 的 swipe_page 完全不同 ——
        // 真机上抓到过：点图标进了一次 ELauncher，回来时卡片就被它的事件改成了"显示"。
        if (defaultHomePkg == null || !pkg.equals(defaultHomePkg)) return;
        // 有 ViewPager 判据的桌面走 handleDesktopPage，两套判据不叠加
        if (sawViewPager.contains(pkg)) return;
        if (sOwnUiForeground) return;               // 自家界面上不该有桌面翻页
        int cct = 0;
        try {
            cct = event.getContentChangeTypes();
        } catch (Throwable ignored) {
        }
        long now = android.os.SystemClock.uptimeMillis();
        // 亮屏后的一小段时间内一律不听：那一段是桌面 resume 的整窗重绘，
        // 形态不稳定、且与"真的翻回第 1 页"高度同形（见 SCREEN_ON_IGNORE_MS）。
        if (screenOnAt != 0 && now - screenOnAt < SCREEN_ON_IGNORE_MS) {
            CardDebug.note(this, "swipe ignore (亮屏后 " + (now - screenOnAt) + "ms < "
                    + SCREEN_ON_IGNORE_MS + "ms, cct=" + cct + ")");
            return;
        }
        // ⚠️ 这里**刻意不做"间隔过大就早结算"**：一次翻页的两条事件间隔不稳定
        // （实测 7ms 到 264ms 都有），早结算会把它俩拆成两个窗口、判据只看到
        // 两条"时钟整分"，方向就丢了。窗口的唯一出口是截止时间（见 SWIPE_LINGER_MS）。
        if (!swipeOpen) swipeFirstAt = now;
        swipeOpen = true;
        swipeTotal++;
        // ⚠️ 必须是 `cct == 1`，不能写成 `(cct & 1) != 0` —— cct=3 也含 SUBTREE 位，
        // 那样会把"整分跳字"和"亮屏后翻页"都算进来（踩过）。
        if (cct == CCT_SUBTREE) swipeSub++;
        if ((cct & CCT_TEXT) != 0) swipeText = true;
        if (cct == CCT_UNDEFINED) swipeZero++;         // resume 整窗重绘的标记（要计数）
        swipeLastAt = now;
        if (ui != null) {
            ui.removeCallbacks(applySwipeWindow);
            ui.postDelayed(applySwipeWindow, SWIPE_WINDOW_MS);
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
        // 这台桌面有 ViewPager 判据 ⇒ 关掉那条"FrameLayout 翻页指纹"（v0.6.0）。
        // 两套判据同时生效会在同一台上互相打架；实测 Tomo 不发 ViewPager 事件、
        // ELauncher 会发，所以这个运行期标记正好把两者自动分开，不用写死包名。
        sawViewPager.add(pkg);
        // 🔴 顺手把「Tomo 那条 FrameLayout 判据」的窗口掐掉。原因：服务刚重连时
        // {@link #sawViewPager} 还是空的，而同一次翻页里 **FrameLayout 事件先到**，
        // 于是 Tomo 判据漏过分流、误触发 —— 真机复现（2026-09-24）：覆盖安装后按 HOME
        // 回桌面，卡片被判成"离开第 1 页"而隐藏（日志 `→ 离开第1页 … visibility=GONE`）。
        // ViewPager 事件一到就作废那个窗口 ⇒ 两套判据立刻互斥，不依赖"谁先建立记忆"。
        cancelSwipeWindow();
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
        unregisterScreenOn();
        cancelPending();
        removeWindow();
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        unregisterScreenOn();
        cancelPending();
        removeWindow();
        sInstance = null;
        super.onDestroy();
    }

    /** 服务要走了，把还没生效的页码回调与闸门超时都撤掉，别让它们去碰已经销毁的窗口 */
    private void cancelPending() {
        pendingPage = -1;
        swipeOpen = false;
        swipeSub = 0;
        swipeTotal = 0;
        swipeText = false;
        swipeZero = 0;
        swipeWinState = false;
        swipeLingered = false;
        resetElaFields();
        if (ui != null) {
            ui.removeCallbacks(applyPendingPage);
            ui.removeCallbacks(applySwipeWindow);
            ui.removeCallbacks(applyElaWindow);
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
                && !pageGate                         // 翻离了桌面第 1 页 → 让位（v0.6.0）
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
                + ", swipePage=" + pageGate
                + ", shade=" + shadeOpen
                + ", icon=" + iconGate
                + ", hide=" + hideGate
                + ", ownUi=" + sOwnUiForeground + ")");
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
        sInstance.pageGate = false;
        sInstance.lastHomeResolveAt = 0L;            // 顺手让下次桌面事件重查默认桌面
        sInstance.cancelSwipeWindow();
        sInstance.cancelElaWindow();
        sInstance.applyVisibility();
        CardDebug.note(sInstance, "resetPageGate (手动)");
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
     * 默认桌面的**运行期自适应**（v0.6.0）—— 用户换默认桌面后不必重启无障碍服务。
     *
     * 原先 `defaultHomePkg` 只在服务连接时解析一次；用户换桌面后那份缓存就过期了，
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
    private void maybeRefreshDefaultHome(String pkg) {
        if (!isLauncher(pkg)) return;
        if (pkg.equals(defaultHomePkg)) return;      // 就是当前那个，没必要查
        long now = android.os.SystemClock.uptimeMillis();
        if (lastHomeResolveAt != 0 && now - lastHomeResolveAt < HOME_RESOLVE_MIN_MS) return;
        lastHomeResolveAt = now;
        String before = defaultHomePkg;
        resolveDefaultHome();
        if (defaultHomePkg == null || defaultHomePkg.equals(before)) {
            // 没换（只是别的桌面包在活动，比如点图标进了备用的那一个）⇒ 什么都不做
            CardDebug.note(this, "defaultHome 未变 " + defaultHomePkg + "（事件来自 " + pkg + "）");
            return;
        }
        CardDebug.note(this, "★ defaultHome 随用户切换更新：" + before + " → " + defaultHomePkg);
        // ① 开抑制窗：紧接着的几百毫秒是新桌面的冷启动整窗重绘，它的指纹与
        //    "真的翻了一页"同形，不挡掉就会把卡片误判成"离开第 1 页"（见 homeChangedAt）。
        homeChangedAt = now;
        homeSettleLogged = 0;
        // ② 把上一个桌面残留的翻页状态全部清掉 —— 新桌面的第 1 页与旧桌面的页码无关
        pageGate = false;
        desktopPage = 0;
        pendingPage = -1;
        cancelSwipeWindow();
        cancelElaWindow();
        // ③ 此刻人还在桌面上，按"第 1 页"立刻重算一次。
        //    必须显式算：随后那条"新桌面 LauncherActivity"的窗口事件走的是
        //    `desk == onDesktop` 那条不重算的分支，靠它卡片回不来。
        applyVisibility();
    }

    /**
     * 默认桌面刚换过、新桌面还在冷启动 —— 这期间的桌面事件**整段不听**，见 {@link #homeChangedAt}。
     *
     * 放在 {@link #maybeRefreshDefaultHome} 之后、各条翻页判据之前，一处挡住两套判据
     *（Tomo 的帧指纹与 ELauncher 的 scrollX），免得各写一遍。
     *
     * 只留头几条日志：抑制期通常就三五条事件，全记会刷屏。
     */
    private boolean inHomeChangeSettle(String pkg, String cls) {
        if (homeChangedAt == 0) return false;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - homeChangedAt >= HOME_CHANGE_IGNORE_MS) return false;
        if (homeSettleLogged < 3) {
            homeSettleLogged++;
            CardDebug.note(this, "home settle ignore (" + pkg + " " + cls
                    + ", 换桌面后 " + (now - homeChangedAt) + "ms)");
        }
        return true;
    }

    /** 解析系统默认桌面（`MATCH_DEFAULT_ONLY`）写入 {@link #defaultHomePkg}；失败保持原值 */
    private void resolveDefaultHome() {
        try {
            Intent def = new Intent(Intent.ACTION_MAIN);
            def.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager()
                    .resolveActivity(def, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                defaultHomePkg = ri.activityInfo.packageName;
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
        // 系统**默认**桌面（v0.6.0）：翻页指纹只在这一个包上启用 —— ELauncher 也是
        // CATEGORY_HOME，但它的 FrameLayout 事件语义与 Tomo 的 swipe_page 不同，
        // 真机上抓到过它把卡片状态改错。这里先解析一次；**运行期换桌面**由
        // {@link #maybeRefreshDefaultHome} 兜住，用户不必重启无障碍服务。
        resolveDefaultHome();
        CardDebug.note(this, "launchers = " + launchers + "  homeComponents = " + homeComponents
                + "  defaultHome = " + defaultHomePkg);
    }
}
