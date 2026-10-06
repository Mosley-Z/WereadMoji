# ui · 通用 UI 控件（MODULE.md）

**职责**：可复用的自绘控件。**只画自己、不知道业务**（不认识"周报""笔记"这些概念）。

## 包含的类

| 类 | 一句话 |
|---|---|
| `SegTabView` | 通用分段页签（N 段，标签代码可改）。设置页切「初始化/自定义/实验室」；**v1.2 起兼作主页大标签栏**（阅读/设置/实验室/待办，4 等分） |
| `NavDropView` | 🆕 v1.2（TASK-044）**阅读页顶部下拉**：收起一行（当前界面名 + ▽），点击原地展开 5 项浮层（本周/本月/本书/本记/洞察）；展开时自身高度改 MATCH_PARENT 覆盖下方、不推挤 |
| `FoldHintView` | 🆕 v1.2.1（TASK-065）**可折叠引导卡标题行**：自绘（三角 + 衬线标题 + 下框线），点一下展开 / 收起。**只画标题行、不认识内容** —— 内容容器由调用方切换可见性（`Listener.onToggle`）。🔴 两端各一套 token：手机端走 `InkTheme` 亮 / 深双色板，墨水屏端纯黑白 |
| `PeriodPickerView` | 周期步进选择器（上下周/月） |
| `CardMenuView` | 卡片上的弹出菜单 |
| `KeyPadView` | 🆕 v1.2（TASK-060）遥控台「**按键**」页自绘键盘；🆕 **TASK-062 形态重做** —— **环形轮盘**（外圈 4 扇区 = 上/下/左/右 + 圆心圆盘 = 确认「OK」）+ 实测可达系统键（退格/空格/删除/跳格/行首/行尾，**2 列 × 3 行圆角矩形**）。未连接 ⇒ 整体 40% 置灰。🔴 取色统一走 `setColorAlpha()`（**固有 α × 连接态 α**）—— 深色档多色**自带透明度**，若写成 `setColor + setAlpha(255)` 会把固有 α 覆盖成不透明（曾致确认键底/字同色 ⇒「OK」隐身）。🔴 只往上抛**本类键 id**（`K_UP`…`K_END`），**不认识 HID usage**（映射在 `shell/ConsoleActivity`） |
| `KeyboardPageView` | 🆕 v1.2（TASK-061）遥控台「**键盘**」页 —— 多行 `EditText` + 状态行 + 「实时同步输入」`CheckBox` + 「发送 / 清空」+ 五个常用键（回车/退格/空格/跳格/删除）。🔴 只抛**语义事件**（`onSendAll(String)` / `onCommonKey(int keyId)` / `onSyncDelta(删尾, 插段)` / `onRealtimeChanged(boolean)`），**不认识 HID usage / 不做 ASCII 过滤**（`HidKeymap` 在 `remote`、映射在 `shell`）。发送期间按钮由外部 `setSending(boolean)` 置灰；`rebuild()` 供切页重挂 IME |

## 对外接口（关键 public API）

- `SegTabView.setLabels(String[])` / `.setSelected(int)` / 回调 `onSegSelected(int)`
- `NavDropView.setLabels(String[])` / `.setSelected(int)` / `.collapse()` / 回调 `onPicked(int)`
- `PeriodPickerView.setPeriod(String mode, long anchorStart)`
- `CardMenuView.setItems(String[])`
- `KeyPadView.setListener(Listener)` / `.setConnected(boolean)` / 回调 `onKey(int keyId)`
- `KeyboardPageView.setListener(Listener)` / `.setConnected(boolean)` / `.getText()` / `.isRealtime()` / `.setRealtime(boolean)` / `.setStatus(String)` / `.setSending(boolean)` / `.rebuild()`；回调 `Listener{ onSendAll(String), onCommonKey(int), onSyncDelta(String,String), onRealtimeChanged(boolean) }`

## 依赖规则

- ✅ **允许**：`core`。
- ❌ **禁止**：`net` / `feature` / `shell` / `a11y` / `update`。
- ✅ **实测（2026-09-25 · TASK-005）**：唯一出边 = `PeriodPickerView → core.PeriodRange`。
- ✅ **实测（TASK-060）**：`KeyPadView` **无出边**（仅用同包 `InkTheme`）⇒ 依赖方向**不变**。
- ✅ **实测（TASK-061）**：`KeyboardPageView` **零项目出边**（`import` 只有 `android.*` / `java.*`，
  连 `core` 都不引）⇒ 依赖方向**不变**；`ui → remote` 亦**未发生**（清单过滤在 `remote.HidKeymap`，由 `shell` 调用）。
- 被谁依赖：`feature` / `shell` / `a11y`（a11y 用 `CardMenuView` 弹菜单）。

**相关**：`docs/02_架构.md` §1
