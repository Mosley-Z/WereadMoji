# a11y · 无障碍桌面卡片（MODULE.md）

**职责**：桌面悬浮卡片的**宿主**。订阅无障碍事件、判断"在不在桌面 / 在哪个桌面 / 第几页"、
管理 `TYPE_ACCESSIBILITY_OVERLAY` 窗口、渲染卡片内容。

🔴 **这是本工程唯一的"呈现方式"** —— 标准 AppWidget / 壁纸模式 / ADB 悬浮窗都**已定论不可行**（见 `MEMORY.md`）。

> ✅ **2026-09-25 · TASK-006 已拆**：原 `CardA11yService` **2,228 行**巨类 → 7 个类，
> 薄壳降到 **259 行**。**判据一行未改**（双向归一化逐行比对，差异 0 条）。
> ✅ **2026-09-26 · TASK-009 增第 8 类**：`SettingsPageProbe`（ELauncher 设置页内容探测，
> 修 t11；`canRetrieveWindowContent` 翻 true，权限承诺见 `help.txt`）。
> ✅ **2026-09-26 · TASK-010 增第 9 类**：`ElaHomeProbe`（ELauncher 3 页桌面「落到 P1」
> 内容探测复核；桌面升 3 页后残值判据被 524254/524255 打穿，卡片会误显示在 P2/P3）。
> 🔴 **窗口内容使用点 = 仅这两个 probe 类**（审查口径：`grep -rn getRootInActiveWindow`）。

## 包含的类

| 类 | 行数 | 职责 |
|---|---:|---|
| `CardA11yService` | 259 | 🔴 **薄壳**：`AccessibilityService` 生命周期 + 静态入口 + 把事件转给 Router |
| `CardVisibilityState` | 142 | 让位相关的**全部运行期状态**（纯数据，无逻辑）—— 见下方"为什么要有它" |
| `A11yEventRouter` | 759 | 事件订阅与分发、桌面识别、通知栏 / 图标点按闸门、桌面页码通道 |
| `TomoPageGate` | 427 | Tomo 桌面翻页判据（`FrameLayout` 条数结算，见 `docs/04` §1） |
| `ElauncherPageGate` | 268 | ELauncher 判据（`ViewPager` + 伴随 `TextView` 的 `sx` 中点分界，见 `docs/04` §2）；TASK-010 起「swipe 落到」改走内容探测 |
| `OverlayController` | 334 | 悬浮窗生命周期 + 🔴 **显隐单一出口** `applyVisibility()` |
| `CardContentController` | 373 | 卡片**显示什么内容**（拉数据 / 切周期 / 本记 / 跳微信读书） |
| `SettingsPageProbe` | 238 | ELauncher **设置页内容探测**（TASK-009）：650ms 延迟 + 600ms 复核双命中 `settings_top` 才让位；窗口内容使用点之一 |
| `ElaHomeProbe` | 207 | ELauncher **「落到 P1」内容探测**（TASK-010）：`ElauncherPageGate` 判「swipe 落到」后不再直接显示，双命中**可见的** `txt_clock` 才显示（3 页桌面残值 524254/524255 打穿事件层判据；可见性过滤挡邻页保留残树）；窗口内容使用点之一 |

### 🔴 为什么多出一个 `CardVisibilityState`（卡的规划里没有）

`applyVisibility()` 要读 8+ 个状态，而这些状态拆开后分散在各处（闸门在两个 Gate、
桌面识别在 Router、菜单在 Overlay）。若让它们互相持有引用 ⇒ **循环依赖**；
若让 Gate 直接 `setVisibility` ⇒ **散掉三条"防藏死"重算路径**（卡片明令禁止）。

⇒ 解法：**状态集中到一个纯数据对象**，谁都能读写。

- ✅ **单一出口保住**：只有 `OverlayController.applyVisibility()` 一处写 `setVisibility`
- ✅ **无循环依赖**：Gate 只写状态对象，不认识 Overlay
- ✅ **行为不变**：布尔表达式原样照搬，只是多了 `st.` 前缀

分工原则：**Gate 与 Router 只改状态，只有 OverlayController 碰视图。**

## 对外接口（关键 API）

- `CardA11yService.isConnected()` —— 服务是否存活（外部唯一需要的静态查询）
- `CardA11yService.isEnabledInSystem(Context)` —— 系统设置里是否已启用
- `CardA11yService.sync()` —— 偏好/数据变化后让卡片同步
- `CardA11yService.noteOwnUiForeground(boolean)` —— ⚠️ **新增自家 Activity 必须调**（否则卡片会压在自己的页面上）
- `CardA11yService.resetPageGate()` —— 用户手动出口（设置页「重新同步卡片显示」）

## 依赖规则

- ✅ **允许**：`core`、`net`、`ui`、`feature` —— **它是顶层宿主**（已拆出的 `CardContentController`
  要驱动网络与卡片视图，`OverlayController` 要用 `feature.OverlayWindow` / `WeekCardView`）。
- ❌ **禁止被依赖**：`core` / `net` / `ui` / `feature` / `update` 不得依赖 `a11y`（✅ 实测：只有 `shell` 依赖它）。
- 📌 依赖方向理想值 `shell → feature → ui → net → core` 在本包**不成立**（入口层天然横跨），
  属既有架构现状，收敛留给后续卡。

## 动它之前

🔴 **必读** `docs/04_桌面让位判据.md` —— 判"在不在"必须**像素真值**（`cct==1` 严格等值）、
换桌面/冷启动重绘与真翻页**同形**（`HOME_CHANGE_IGNORE_MS=2000`）。
安全网 = `bash regress/run_all.sh <tag>`（= `ela4.sh` 20 项 + `tomo_regress.sh`），**不许靠肉眼**。

⚠️ **两个 Gate 语义相反**（Tomo 回上次那页 / ELauncher 一律回第 1 页）⇒ **不要合并**。
⚠️ `handleDesktopPage` 写在 Router 里（它改的是路由自己的 `desktopPage` / `pendingPage` 与图标闸门）。

**相关**：`docs/02_架构.md` §3 ｜ `docs/04` ｜ TASK-006（已拆）· TASK-007（拆 `WeekCardView`）
