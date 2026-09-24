# net · 网络（MODULE.md）

**职责**：所有"出网"与由网络驱动的调度。**只有这里能发 HTTP 请求。**
（铁律：`WeekCardView` 等渲染路径**永不发请求**，否则空池 + 非空 Key 会进无界同步循环。）

## 包含的类

| 类 | 一句话 |
|---|---|
| `WereadApi` | 微信读书 Agent 网关客户端（纯 `HttpURLConnection`，零依赖） |
| `NoteSync` | 笔记同步调度（后台线程 + 退避；`isRunning` / `canAutoStart`） |
| `UpdateChecker` | 检查新版本（读 `update.json`，比对 versionCode） |

## 对外接口（关键 public API）

- `WereadApi`：网关请求（书架 / 统计 / 笔记 / 想法）
- `NoteSync.isRunning()` / `.canAutoStart()` / `.clearBackoff()`
- `UpdateChecker.currentVersionCode(Context)` / `.isNewer(Context, Info)` / `.remoteVersionName(Context)` / `.hasKnownUpdate(Context)`

## 依赖规则

- ✅ **允许**：`core`。
- ❌ **禁止**：`ui` / `feature` / `shell` / `a11y` / `update`。
- ✅ **实测（2026-09-25 · TASK-005）**：`WereadApi → core.*`；`NoteSync → core.*`；
  `UpdateChecker → core.CardDebug`。**无出边越界**。
- 被谁依赖：`feature` / `shell` / `a11y` / `update`（`update.ApkInstaller → net.UpdateChecker`）。

**相关**：`docs/02_架构.md` §4 数据流 ｜ 网关约定见 `MEMORY.md`
