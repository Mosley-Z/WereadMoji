# shell · 壳与设置（MODULE.md）

**职责**：应用外壳 —— 用户能直接点开的 Activity，以及系统入口组件。**最顶层，可以依赖所有下层。**

## 包含的类

| 类 | 一句话 |
|---|---|
| `MainActivity` | 主页（全屏四态：本周 / 本月 / 本书 / 本记）+ dev 包的 `--es api_key` 调试入口 |
| `SettingsActivity` | 设置页（三段页签：初始化 / 自定义 / 实验室） |
| `HelpActivity` | 「怎么用」（正文在 `res/raw/help.txt`） |

> 🧹 `StatsWidgetProvider` 已删（2026-09-25 · TASK-008）：标准 AppWidget 死代码，本机两个桌面
> 都没有 appwidget 宿主、manifest 也无声明；连带删 `res/xml/widget_info.xml`、
> `res/layout/widget_stats.xml`、strings 2 条。见 `验证记录/50`。

## 对外接口（关键 public API）

- `MainActivity`：无对外静态 API；回调 `onOpen()` / `onDone(pool, ideas, total, error, state)` / `onCover(Bitmap)`
- `SettingsActivity`：无对外静态 API；回调 `onSegSelected(int)`
- 组件名（**移动过包，清单已同步**）：
  - `com.inkread.weekread.shell.MainActivity`（launcher）
  - `com.inkread.weekread.shell.SettingsActivity`（无障碍服务的 `settingsActivity` 指向它）
  - `com.inkread.weekread.shell.HelpActivity`

## 依赖规则

- ✅ **允许**：**全部**下层（`core` `net` `ui` `feature` `update` `a11y`）。
- ❌ **禁止被依赖**：`core` / `net` / `ui` / `feature` / `a11y` / `update` **都不得依赖 shell**
  （✅ 实测成立：唯一从外部引用 shell 的是 `res/xml/a11y_card_service.xml` 的 `settingsActivity` 属性，属配置而非代码依赖）。
- ✅ **实测（2026-09-25 · TASK-005）**：`MainActivity → core, net, ui, feature, update, a11y`；
  `SettingsActivity → core, net, ui, feature, update, a11y`；`HelpActivity → a11y`。
  （`StatsWidgetProvider → core` 随类删除，TASK-008。）

**相关**：`docs/02_架构.md` ｜ TASK-008（删死代码）
