# ui · 通用 UI 控件（MODULE.md）

**职责**：可复用的自绘控件。**只画自己、不知道业务**（不认识"周报""笔记"这些概念）。

## 包含的类

| 类 | 一句话 |
|---|---|
| `SegTabView` | 顶部分段页签（v0.7 起支持 3 段；设置页用它切「初始化/自定义/实验室」） |
| `TabBarView` | 主页底部/顶部页面选项卡 |
| `PeriodPickerView` | 周期步进选择器（上下周/月） |
| `CardMenuView` | 卡片上的弹出菜单 |

## 对外接口（关键 public API）

- `SegTabView.setLabels(String[])` / `.setSelected(int)` / 回调 `onSegSelected(int)`
- `TabBarView.setSelected(int)`
- `PeriodPickerView.setPeriod(String mode, long anchorStart)`
- `CardMenuView.setItems(String[])`

## 依赖规则

- ✅ **允许**：`core`。
- ❌ **禁止**：`net` / `feature` / `shell` / `a11y` / `update`。
- ✅ **实测（2026-09-25 · TASK-005）**：唯一出边 = `PeriodPickerView → core.PeriodRange`。
- 被谁依赖：`feature` / `shell` / `a11y`（a11y 用 `CardMenuView` 弹菜单）。

**相关**：`docs/02_架构.md` §1
