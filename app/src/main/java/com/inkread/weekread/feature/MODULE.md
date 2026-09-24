# feature · 业务形态（MODULE.md）

**职责**：把数据**呈现成具体的业务形态**。这里是"业务语义"开始出现的地方。

## 包含的类

| 类 | 一句话 |
|---|---|
| `WeekCardView` | 🔴 卡片自绘（四态布局、按钮、筛选格）—— **1,996 行，拆分见 TASK-007** |
| `NoteExport` | 笔记导出（纸张/字号/署名等偏好 + 绘制长图并分享） |
| `OverlayWindow` | 无障碍悬浮窗的窗口参数（`TYPE_ACCESSIBILITY_OVERLAY`） |
| `lab/`（子包） | `ProbeResult` `DefaultHomeProbe` `CapabilityProbes` `LabRunner` —— 实验室页的**设备能力自检**（只读探针），见 `docs/FEATURES/lab.md` |

> 📌 **为什么 `feature/` 是扁平包、不是 `feature/week` + `feature/note` + `feature/overlay`**：
> `NoteExport` 调用 `WeekCardView.wrapAll(...)`，而它是**包级私有**的 ⇒ 两者**必须在同一个包里**。
> 三条出路中选最不动逻辑的一条：
> ①（弃）给 `wrapAll` 加 `public` —— TASK-005 卡**明令禁止**「顺手加 public」，且会破坏"逻辑零改动"判据；
> ②（弃）把 `NoteExport` 塞进 `feature/week/` —— 语义失真；
> ③（**选中**）`feature/` 扁平 —— 零可见性改动、零逻辑改动；且卡里只要求**一份 `feature/MODULE.md`**
> （没有 `feature/week/MODULE.md`），本就支持这个读法。
> 🔎 **这是一处真实的隐式耦合**（跨文件使用包级私有成员），已登记，供 TASK-007 拆 `WeekCardView` 时一并处理。
> ✅ **2026-09-25 用户已拍板接受此取舍**（`feature/` 保持扁平），本项**不再是待定议题**。

## 对外接口（关键 public API）

- `WeekCardView.setStats(PeriodStats, String note)` / `.setBook(BookStats)` / `.setNote(NoteStats)` / `.setMode(String)` / `.setFullscreen(boolean)` / `.setPadXRatio(float)`
- `NoteExport.paper(Context)` / `.sizeTier(Context)` / `.showSign(Context)` / `.showDate(Context)`（及对应 setter）/ `.exportAndShare(...)`
- `OverlayWindow.params(int type)` / `.paramsTouch(...)` / `.paramsOpenTouch(...)` / `.paramsPrevTouch(...)` / `.paramsTitleTouch(...)` / `.paramsMenu(...)` / `.typeAccessibility()`
- `LabRunner.run(Context, Callback)` / `.compose(...)`；`DefaultHomeProbe.probe(Context)` / `.toDisplay(...)`；`CapabilityProbes.volumeKeys(Context)` 等 5 项

## 依赖规则

- ✅ **允许**：`core`、`ui`、`net`；feature 内部（含 `lab/` 子包）。
- ❌ **禁止**：`shell`、`a11y`、`update`。
- ✅ **实测（2026-09-25 · TASK-005）**：`WeekCardView → core.*`；`NoteExport → core.NoteStats`；
  `OverlayWindow → core.CardSpec`；`lab/* → lab/*`。**无越界**。
- 被谁依赖：`shell`、`a11y`。

**相关**：`docs/FEATURES/lab.md` ｜ `docs/02_架构.md` §3（巨类拆分）｜ TASK-007
