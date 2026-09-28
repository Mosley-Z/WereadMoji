# remote · 遥控翻页（MODULE.md）

**职责**：手机当遥控器，给墨水屏上的微信读书翻页（V1.0 Beta / TASK-018）。
**三层解耦**：捕获层（无障碍按键）→ 传输层（`RemoteLink` = `WifiTcpLink`，TCP）→ 注入层（`dispatchGesture` 左右滑）。

> 规格 `docs/FEATURES/remote.md`｜决策 `docs/DECISIONS/ADR-010_V1.0Beta_热点TCP遥控翻页.md`｜
> 入口 UI =「设置 · 实验室」`docs/FEATURES/lab.md`

## 包含的类

| 类 | 一句话 |
|---|---|
| `RemoteRole` | 角色枚举（`OFF`/`EINK`/`PHONE`）+ 从 `CardPrefs.remote_role` 解析 |
| `RemoteProtocol` | 文本行协议（`PAGE_NEXT`/`PAGE_PREV`/`HELLO`/`BYE`）+ 端口常量 + 解析/命名 |
| `RemoteLink` | 传输**接口**（`start`/`stop`/`send`/`Listener`）—— 换传输层只加实现类 |
| `WifiTcpLink` | TCP 实现（手机 = Server listen 45678；墨水屏 = Client 连**默认网关**，含重试） |
| `RemoteLinkManager` | 会话生命周期 + 空闲超时 + 状态回调（**按需连接策略唯一出口**） |
| `RemoteInjector` | 注入层：`dispatchGesture` 左右滑（S0-a 上机实测参数 420↔60 / y=400 / 200ms） |
| `RemoteKeyService` | 独立无障碍服务（配置 `res/xml/a11y_remote_service.xml`）：phone 捕获音量键 / eink 收到指令注入 |

## 关键约束（🔴 硬约束）

- `res/xml/a11y_card_service.xml` **一字不改**（卡内验收 A6）⇒ 现有纯墨水屏用户的无障碍声明零污染；
  遥控能力全落在**独立**的 `RemoteKeyService` 上（为什么必须分离见 `ADR-010` 决定 5）。
- 🔴 **按键过滤全局互斥**（同机同类服务同时只有一个能生效）⇒ 设置页「实验室」必须给可操作指引。
- 🔴 **`onKeyEvent` 只处理 `ACTION_DOWN` 且 `repeatCount == 0`**（长按不连翻，A12）；
  **禁止按 `deviceId` 过滤**（音量上与下来自不同 input 设备）。
- 🔴 **默认 `remote_role=off`** ⇒ 不建 socket、与现状零差异；只有显式「开始遥控」才建 TCP。
- 🔴 **断开语义**：单条连接断开 ⇒ `RemoteLink.Listener.onConnectionLost`（会话回「等待重连」，**不**结束）；
  收 `BYE` 或链路终结 ⇒ 结束会话。详见 `docs/FEATURES/remote.md`「断开语义」（O1/O3，2026-09-28）。
- 🔴 **屏幕熄灭 / 锁屏后系统不再向本 App 派发音量键 ⇒ 本功能失效**（2026-09-28 定论）；
  三条通路（无障碍过滤 / 唤醒锁保活 / 媒体会话 / 系统音量）**全部实测堵死**，见
  `docs/FEATURES/remote.md`「锁屏 / 息屏边界」⇒ 文案必须写"保持屏幕点亮"，⛔ 严禁"锁屏息屏可用"。
- 文案三条硬约束（只写已验证事实 / 写明互斥 / 角色切换需重开无障碍）见 `docs/FEATURES/lab.md`。

## 依赖规则

- ✅ **允许**：`core`（只读 `CardPrefs` 偏好）。
- ❌ **禁止**：`a11y`、`feature`（不反向依赖）；`feature`/`a11y` 也不依赖 `remote`。
- 被谁依赖：`shell`（`SettingsActivity` 装配 UI、`MainActivity` phone 分流）、`AndroidManifest.xml`（服务注册）。

**相关**：`docs/FEATURES/remote.md` ｜ `docs/FEATURES/lab.md` ｜ `ADR-010` ｜ TASK-018
