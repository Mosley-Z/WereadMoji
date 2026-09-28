# net · 网络（MODULE.md）

**职责**：所有"出网"与由网络驱动的调度。**只有这里能发 HTTP 请求。**
（铁律：`WeekCardView` 等渲染路径**永不发请求**，否则空池 + 非空 Key 会进无界同步循环。）

## 包含的类

| 类 | 一句话 |
|---|---|
| `WereadApi` | 微信读书 Agent 网关客户端（纯 `HttpURLConnection`，零依赖） |
| `NoteSync` | 笔记同步调度（后台线程 + 退避；`isRunning` / `canAutoStart`） |
| `UpdateChecker` | 检查新版本（读 `update.json`，比对 versionCode）；**TASK-020 双通道**：按 `CardPrefs.update_channel` 选 `main` / `beta` 清单 |

## 对外接口（关键 public API）

- `WereadApi`：网关请求（书架 / 统计 / 笔记 / 想法）
- `NoteSync.isRunning()` / `.canAutoStart()` / `.clearBackoff()`
- `UpdateChecker.currentVersionCode(Context)` / `.isNewer(Context, Info)` / `.remoteVersionName(Context)` / `.hasKnownUpdate(Context)`

## 关键约束（TASK-020 双通道）

- 清单 URL 按 **`CardPrefs.getUpdateChannel()`** 选分支段：`stable`→`main`、`beta`→`beta`。
  🔴 **正式版分支段是字面量 `main`，与改造前逐字相同**（老用户升级零差异）。
- 🔴 **缓存按通道隔离**：`upd_remote_*` 附 `upd_remote_channel` 标记；换通道后旧缓存不再显示
  （`remoteVersionCode/Name/Notes` 会先校验通道）。老数据无该字段 ⇒ 按 `stable` 论（向后兼容）。
- ✅ **依赖仍合法**：新增 `UpdateChecker → core.CardPrefs`（`net → core` 允许，见下）。

## 依赖规则

- ✅ **允许**：`core`。
- ❌ **禁止**：`ui` / `feature` / `shell` / `a11y` / `update`。
- ✅ **实测（2026-09-25 · TASK-005）**：`WereadApi → core.*`；`NoteSync → core.*`；
  `UpdateChecker → core.CardDebug`。**无出边越界**。
- 被谁依赖：`feature` / `shell` / `a11y` / `update`（`update.ApkInstaller → net.UpdateChecker`）。

**相关**：`docs/02_架构.md` §4 数据流 ｜ 网关约定见 `MEMORY.md`
