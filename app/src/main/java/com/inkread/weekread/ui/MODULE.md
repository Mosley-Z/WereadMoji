# ui · 通用 UI 控件（MODULE.md）

**职责**：可复用的自绘控件。**只画自己、不知道业务**（不认识"周报""笔记"这些概念）。

## 包含的类

| 类 | 一句话 |
|---|---|
| `SegTabView` | 通用分段页签（N 段，标签代码可改）。设置页切「初始化/自定义/实验室」；**v1.2 起兼作主页大标签栏**（阅读/设置/实验室/待办，4 等分） |
| `NavDropView` | 🆕 v1.2（TASK-044）**阅读页顶部下拉**：收起一行（当前界面名 + ▽），点击原地展开 5 项浮层（本周/本月/本书/本记/洞察）；展开时自身高度改 MATCH_PARENT 覆盖下方、不推挤 |
| `TabBarView` | 主页页面选项卡（5 段）。⚠️ v1.2 起 `MainActivity` **不再使用**（改用 `SegTabView` + `NavDropView`），类保留待其它引用 |
| `PeriodPickerView` | 周期步进选择器（上下周/月） |
| `CardMenuView` | 卡片上的弹出菜单 |
| `KeyPadView` | 🆕 v1.2（TASK-060）遥控台「**按键**」页自绘键盘 —— 十字方向键（↑↓←→ + 居中「确认」）+ 实测可达系统键（退格/空格/删除/跳格/行首/行尾，3×2 网格）。未连接 ⇒ 整体 40% 置灰。🔴 只往上抛**本类键 id**（`K_UP`…`K_END`），**不认识 HID usage**（映射在 `shell/ConsoleActivity`） |

## 对外接口（关键 public API）

- `SegTabView.setLabels(String[])` / `.setSelected(int)` / 回调 `onSegSelected(int)`
- `NavDropView.setLabels(String[])` / `.setSelected(int)` / `.collapse()` / 回调 `onPicked(int)`
- `TabBarView.setSelected(int)`
- `PeriodPickerView.setPeriod(String mode, long anchorStart)`
- `CardMenuView.setItems(String[])`
- `KeyPadView.setListener(Listener)` / `.setConnected(boolean)` / 回调 `onKey(int keyId)`

## 依赖规则

- ✅ **允许**：`core`。
- ❌ **禁止**：`net` / `feature` / `shell` / `a11y` / `update`。
- ✅ **实测（2026-09-25 · TASK-005）**：唯一出边 = `PeriodPickerView → core.PeriodRange`。
- ✅ **实测（TASK-060）**：`KeyPadView` **无出边**（仅用同包 `InkTheme`）⇒ 依赖方向**不变**。
- 被谁依赖：`feature` / `shell` / `a11y`（a11y 用 `CardMenuView` 弹菜单）。

**相关**：`docs/02_架构.md` §1
