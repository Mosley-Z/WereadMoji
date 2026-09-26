package com.inkread.weekread.a11y;

import java.util.HashSet;
import java.util.Set;

/**
 * 桌面卡片「让位」相关的**全部运行期状态** —— TASK-006 从 `CardA11yService` 里抽出来的纯数据持有者。
 *
 * <p>🔴 **这个类刻意没有逻辑**（只有字段与归零方法）。它存在的唯一理由是：
 * `CardA11yService` 拆成多个类之后，`applyVisibility()` 这个**单一出口**
 * 要读的状态分散在各处（闸门在两个 Gate 里、桌面识别在 Router 里、菜单在 Overlay 里）。
 * 与其让它们互相持有引用（循环依赖），不如把状态集中到一个谁都能读写的对象上。
 *
 * <p>**它保住了两条铁律**：
 * <ul>
 *   <li>**单一出口**：只有 `OverlayController.applyVisibility()` 一处写 `setVisibility`，
 *       Gate 只改状态、不碰视图 ⇒ 卡片明令警告的"三条防藏死重算路径"不会散掉。</li>
 *   <li>**行为不变**：字段就是原来那些字段，布尔表达式原样照搬，只是多了 `st.` 前缀。</li>
 * </ul>
 *
 * <p>⚠️ 字段**刻意用包级可见**（不是 private）：这些状态本来就是同一个类里的字段，
 * 拆开后仍属同一个内聚体；加 getter/setter 只会让 diff 变大、让"逻辑零改动"更难证明。
 */
final class CardVisibilityState {

    // ── 桌面识别 ──

    /** 所有 CATEGORY_HOME 的桌面包名。见 `CardA11yService` 原 `launchers`。 */
    final Set<String> launchers = new HashSet<>();
    /**
     * 真正的「桌面 Activity」全名集合（`包名/类名`）。
     *
     * ⚠️ 光有包名不够 —— 桌面包里往往还挂着别的界面。实测 `com.astraabove.tomo`
     * 一个包里就有 11 个 Activity（含书架 / 文件 / 设置），按包名判断会把它们误判成"还在桌面"。
     */
    final Set<String> homeComponents = new HashSet<>();
    /** 系统**默认**桌面包（`MATCH_DEFAULT_ONLY`）—— 翻页指纹只在这一个包上生效 */
    String defaultHomePkg = null;
    /** 上一次重查默认桌面的时刻（`uptimeMillis()`），0 = 还没查过 */
    long lastHomeResolveAt = 0L;
    /** 默认桌面刚被换掉的时刻（`uptimeMillis()`），0 = 没换过。见 `HOME_CHANGE_IGNORE_MS` */
    long homeChangedAt = 0L;
    /** 一个抑制窗里最多留几条日志，免得刷屏 */
    int homeSettleLogged = 0;
    /** 发过 ViewPager 事件的桌面包 —— 这类桌面走 ELauncher 的 `scrollX` 判据 */
    final Set<String> sawViewPager = new HashSet<>();

    // ── 让位闸门（五道，只影响"这一刻显不显示"，不翻转 onDesktop）──

    /** 前台是否在桌面。连接时先按"是"，随后由第一个窗口事件纠正 */
    boolean onDesktop = true;
    /** 桌面当前在第几页（0 = 第 1 页）。只能当"不小于真实页码"的粗略值用 */
    int desktopPage = 0;
    /** 待生效的页码，见 `PAGE_DEBOUNCE_MS` */
    int pendingPage = -1;
    /** 「用户已经翻离桌面第 1 页」闸门（v0.6.0） */
    boolean pageGate = false;
    /** ELauncher「设置」隐藏页闸门（TASK-009：内容探测命中置位；resume/落回 P1 实锤清除） */
    boolean settingsGate = false;
    /** 最近一次「resume / 落到第 1 页」实锤时刻（uptimeMillis），探测置位的抑制窗锚点，见 SettingsPageProbe */
    long settingsSettledAt = 0L;
    /** 通知栏（状态栏）是否已下拉 */
    boolean shadeOpen = false;
    /** 用户刚点过桌面上的图标（§25） */
    boolean iconGate = false;
    /** `iconGate` 置位时刻（`uptimeMillis()`），供最小保持时间与兜底超时用 */
    long iconGateAt = 0L;
    /** 用户长按选择了「隐藏 N 分钟」 */
    boolean hideGate = false;
    /** 隐藏到什么时候（`currentTimeMillis()`），只用于日志 */
    long hideUntil = 0L;
    /** 长按菜单是否展开 */
    boolean menuOpen = false;

    // ── Tomo 翻页判据的窗口状态 ──

    boolean swipeOpen = false;
    boolean swipeText = false;
    boolean swipeWinState = false;
    boolean swipeLingered = false;
    int swipeSub = 0;
    int swipeTotal = 0;
    int swipeZero = 0;
    long swipeLastAt = 0L;
    long swipeFirstAt = 0L;

    // ── ELauncher 翻页判据的窗口状态 ──

    boolean elaOpen = false;
    boolean elaFrame = false;
    boolean elaVp = false;
    int elaTextSx = 0;
    String elaPkg = null;

    // ── 亮屏 / 桌面 TextView 的时间戳 ──

    /** 亮屏时刻（`uptimeMillis()`），0 = 本次服务生命周期内还没见过亮屏 */
    long screenOnAt = 0L;
    /** 最近一次"桌面自己的 TextView 内容变化"的时刻 —— 用它把时钟整分与翻页分开 */
    long lastHomeTextNodeAt = 0L;

    /**
     * 把**窗口类**状态全部归零（服务重连 / 手动重置用）。
     *
     * ⚠️ 只清"半截窗口"，**不动** `onDesktop` / `desktopPage` / 各道闸门 ——
     * 原 `resetElaFields()` 与 `cancelPending()` 的语义就是这个，别扩大范围。
     */
    void resetWindows() {
        swipeOpen = false;
        swipeText = false;
        swipeWinState = false;
        swipeLingered = false;
        swipeSub = 0;
        swipeTotal = 0;
        swipeZero = 0;
        swipeLastAt = 0L;
        swipeFirstAt = 0L;
        elaOpen = false;
        elaFrame = false;
        elaVp = false;
        elaTextSx = 0;
        elaPkg = null;
    }

    /**
     * 服务重连时的全量归零 —— 对应原 `onServiceConnected()` 里那一整段。
     *
     * ⚠️ `launchers` / `homeComponents` / `sawViewPager` **不清**：它们是"认得哪些桌面"
     * 的缓存，重连后仍有效（原代码也不清，`loadLaunchers()` 靠 `isEmpty()` 自己判断）。
     */
    void resetAll() {
        onDesktop = true;
        desktopPage = 0;
        pendingPage = -1;
        pageGate = false;
        settingsGate = false;
        settingsSettledAt = 0L;
        shadeOpen = false;
        iconGate = false;
        iconGateAt = 0L;
        hideGate = false;
        hideUntil = 0L;
        menuOpen = false;
        screenOnAt = 0L;
        lastHomeTextNodeAt = 0L;
        resetWindows();
    }
}
