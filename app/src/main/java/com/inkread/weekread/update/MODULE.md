# update · 自更新（MODULE.md）

**职责**：应用内"检查更新 → 下载 → 校验 → 拉起系统安装器"。
自己用 `ContentProvider` 把 APK 暴露给系统安装器（**手写，因为本工程无 AndroidX、不能用 FileProvider**）。

## 包含的类

| 类 | 一句话 |
|---|---|
| `ApkInstaller` | 下载、sha256 校验、清理半成品、拉起安装器；缺「允许安装未知应用」时引导过去 |
| `ApkProvider` | 自己的 `ContentProvider`：`authorities="com.inkread.weekread.apk"`，`exported=false` + 临时授权 |

## 对外接口（关键 public API）

- `ApkInstaller.apkDir(Context)` / `.canInstall(Context)` / `.unknownSourcesIntent(Context)` / `.cleanupPartial(Context)` / `.install(Context, File)`
- `ApkProvider.openFile(Uri, String)` / `.getType(Uri)` / `onCreate()`

## 依赖规则

- ✅ **允许**：`core`、`net`。
- ❌ **禁止**：`ui` / `feature` / `shell` / `a11y`。
- ✅ **实测（2026-09-25 · TASK-005）**：`ApkInstaller → core.CardDebug, net.UpdateChecker, update.ApkProvider`。
  ⚠️ 卡与 `docs/02` 原写"update 只能依赖 core"**与现状不符** —— `ApkInstaller` 需要 `net.UpdateChecker`
  拿远端版本信息；2026-09-25 实测后**如实订正**。
- 被谁依赖：`shell`（`MainActivity` / `SettingsActivity`）。

**相关**：技能 `android-inapp-update-github` §5.4 ｜ 签名铁律见 `MEMORY.md`
