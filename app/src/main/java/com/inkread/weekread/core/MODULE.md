# core · 数据与通用（MODULE.md）

**职责**：本地数据存取、周期计算、卡片几何、用户偏好、设备侧诊断日志。
**不含**任何 UI 绘制、网络请求、无障碍代码 —— 它是全工程的**最底层**。

## 包含的类

| 类 | 一句话 |
|---|---|
| `BookStore` | 书架缓存（落盘 + 新鲜度） |
| `NoteStore` | 划线/想法数据：索引、按需取单本、抽样 |
| `StatsStore` | 阅读统计缓存 + ApiKey + 卡片周期偏好 |
| `CoverStore` | 封面位图的内存/磁盘缓存 |
| `PeriodStats` | 一个周期的统计模型（解析/落盘 JSON） |
| `PeriodRange` | 周期与日期的换算（周/月/书/记 四种 mode） |
| `NoteStats` | 一条笔记的模型 |
| `BookStats` | 一本书的统计模型 |
| `CardSpec` | 🔴 **卡片几何的唯一来源**（位置、字号、命中区） |
| `CardPrefs` | 卡片开关等用户偏好 |
| `CardDebug` | 设备侧诊断日志（logcat 被 ROM 吞掉时的备用通道） |

## 对外接口（关键 public API）

- `BookStore.load(Context)` / `.save(Context, BookStats)` / `.shelf(Context)` / `.shelfFresh(Context)`
- `NoteStore.index(Context)` / `.marks(Context, bookId)` / `.mergeMarks(...)` / `.ideasOnly(Context)`
- `StatsStore.getKey(Context)` / `.setKey(...)` / `.load(Context, mode, periodStart)` / `.getCardPeriod(Context)`
- `CoverStore.peek(Context, bookId, maxW)`
- `PeriodRange.nowSec()` / `.startOf(mode, s)` / `.shift(mode, start, n)` / `.dayStart(start, i)` / `.isBook(m)` / `.isNote(m)`
- `PeriodStats.parse(JSONObject, fetchedAt, mode, reqBase)` / `.isCurrentPeriod()` / `.todayIndex()`
- `CardSpec.cardWidth()` / `.cardHeight()` / `.tapLeft()` / `.tapTop()` …
- `CardPrefs.isEnabled(Context)` / `.setEnabled(Context, boolean)`
- `CardDebug.note(Context, String)`

## 依赖规则

- ✅ **允许**：只依赖本包内其他类。
- ❌ **禁止**：依赖 `net` / `ui` / `feature` / `shell` / `a11y` / `update`。
- ✅ **实测（2026-09-25 · TASK-005）**：**core 出边 = 0**，硬要求成立。
- 被谁依赖：`net` `ui` `feature` `shell` `a11y` `update` **全都可以**（它们都向下依赖 core）。

**相关**：`docs/02_架构.md` §1 ｜ 几何铁律见该文件「技术笔记」
