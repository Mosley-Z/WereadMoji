# a11y · 无障碍卡片（MODULE.md）

**职责**：桌面悬浮卡片的**宿主**。订阅无障碍事件、判断"在不在桌面 / 在哪个桌面 / 第几页"、
管理 `TYPE_ACCESSIBILITY_OVERLAY` 窗口、引导开权限。

🔴 **这是本工程唯一的"呈现方式"** —— 标准 AppWidget / 壁纸模式 / ADB 悬浮窗都**已定论不可行**（见 `MEMORY.md`）。

## 包含的类

| 类 | 说明 |
|---|---|
| `CardA11yService` | 🔴 **2,211 行巨类**（占全项目约 40%）：事件路由 + 两套桌面翻页判据 + 悬浮窗管理 + 权限。**拆分见 TASK-006** |

## 对外接口（关键 public API）

- `CardA11yService.isConnected()` —— 服务是否存活（外部唯一需要的静态查询）
- 服务生命周期：`onServiceConnected` / `onAccessibilityEvent` / `onInterrupt`
- ⚠️ 新增**自家** Activity 时必须调 `noteOwnUiForeground(boolean)`（否则会把自己判成"不在桌面"）

## 依赖规则

- ✅ **允许**：`core`、`net`、`ui`、`feature` —— **它是顶层宿主，天然要驱动窗口、卡片、菜单、网络**。
  （卡与 `docs/02` 原先写的"a11y 只能依赖 core"**与现状不符**，2026-09-25 实测后如实订正。）
- ❌ **禁止被依赖**：`core` / `net` / `ui` / `feature` / `update` 不得依赖 `a11y`
  （✅ 实测成立：只有 `shell` 依赖它）。
- ✅ **实测（2026-09-25 · TASK-005）**：`CardA11yService → core.*, net.*, ui.CardMenuView, feature.overlay.OverlayWindow, feature.week.WeekCardView`。

## 动它之前

🔴 **必读** `docs/04_桌面让位判据.md` —— 判"在不在"必须**像素真值**（`cct==1` 严格等值）、
换桌面/冷启动重绘与真翻页**同形**（`HOME_CHANGE_IGNORE_MS=2000`）。
安全网 = `_verify060/ela4.sh`（20 项）+ `_verify060/tomo_regress.sh`，**不许靠肉眼**。

**相关**：`docs/02_架构.md` §3 ｜ `docs/04` ｜ TASK-006
