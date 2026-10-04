# remote · 遥控翻页（MODULE.md）

**职责**：手机当遥控器，给墨水屏上的微信读书翻页（V1.0 Beta / TASK-018）。
**三层解耦**：捕获层（无障碍按键）→ 传输层（`RemoteLink` = `WifiTcpLink`，TCP）→ 注入层（`dispatchGesture` 点击边界热区）。

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
| `RemoteInjector` | 注入层：`dispatchGesture` **点击边界热区**（下一页=点右 `x=425`、上一页=点左 `x=55`，y=400；左右各 55px 边界**不受正文划线影响**，方案 D 真机标定 `验证记录/118`）；派发走**方案 S = 串行队列**（有手势在飞则入队，等 `onCompleted` 再派发下一条，不丢指令，见 `验证记录/110` §10、`验证记录/111`）。⚠️ 旧「滑动」几何 `380↔100` 因墨水屏把 `dispatchGesture` 判成 tap ⇒ 弹划线面板，已弃用（`验证记录/117`） |
| `RemoteKeyService` | 独立无障碍服务（配置 `res/xml/a11y_remote_service.xml`）：phone 捕获音量键 / eink 收到指令注入 |
| `ShakeDetector` | 🆕 TASK-029：phone 的**第二条捕获路径** —— 50Hz 加速度计 → 去重力 → 双峰反转 → 发翻页指令（**零新增权限**） |
| `HidLink` | 🆕 TASK-032：**T1 蓝牙 HID 外设链路**（第 3 种模型，见 `ADR-012`）—— 手机注册 `BluetoothHidDevice` 直接给墨水屏 OS 发按键。🆕 TASK-033 起对外暴露 `isHostConnected()` / `pagePrev()` / `pageNext()`（供 `RemoteLinkManager` 通道分派）。⚠️ `SDK_INT<28` 或不支持 ⇒ `supported()=false`，fail-closed |
| `HidConst` | 🆕 TASK-033：**HID 常量唯一来源**（`PROFILE_HID_DEVICE=19` / `SUBCLASS1_COMBO=0xC0` / `REPORT_ID_KEYBOARD=1` / `KEY_PAGE_UP=0x4B` / `KEY_PAGE_DOWN=0x4E` / 消费者音量键 `0xE9`/`0xEA`）—— 避免 `HidLink` 与调用方各写一份魔数 |
| `HidKeepAliveService` | 🆕 TASK-032：**HID 注册保活前台服务**（`foregroundServiceType=connectedDevice` + `PARTIAL_WAKE_LOCK`）—— 官方明文 + 真机铁证：注册在退后台/息屏时被自动注销 ⇒ 必须前台服务保活。只在手机端启用「蓝牙控制」时启动（默认零差异）。🆕 TASK-033 加静态入口 `instance()`/`link()`/`isRunning()`/`start()`/`stop()` + `StateListener`（设置页订阅刷状态行） |

## 🆕 TASK-029：手机端晃动翻页（V1.0.4-beta）

**一句话**：摇一摇手机 → 墨水屏翻页。与音量键**并列**的两条捕获路径，都汇到
`RemoteLinkManager.sendCommand`。<b>S4 端无需为晃动新增代码、零新增权限</b>（加速度计免权限）——
复用既有 `RemoteInjector` 注入路径；同卡另顺带修其 R10 几何 / R11 串行化 2 处既有缺陷（见上表）。

- **三道门控**（集中在 `ShakeDetector.sync(Context)`）：`role==PHONE` + `remote_shake_enabled` +
  `STATE_CONNECTED`（随会话 CONNECTED 注册 / 断开注销，不常驻采样）。
- **新增 4 个偏好键**（`core/CardPrefs`）：`remote_shake_enabled`（总开关，默认 `false`）/
  `remote_shake_lr_rev`（左右反转，默认 `false`）/ `remote_shake_ud_rev`（上下反转，默认 `false`）/
  `remote_shake_sens`（🆕 灵敏度档位 `0/1/2`，**默认 `1` = 中**）。
  🔴 两个反转开关**互相独立**；`ShakeDetector` 每次命中**复读偏好**，改完立即生效。
- **档位变更路径**：设置页改档 → `ShakeDetector.reload(Context)`（**正在采样才重建**，几毫秒空窗；
  重建会清冷却态 ⇒ 改档后紧接着甩一下会立即触发，可接受）。
- **标定常量唯一来源** = `验证记录/103_TASK028_手机晃动定标.md` + `_probe/shake_phone/EVIDENCE.md` §5
  + `验证记录/112`（🆕 手感三档 = 2026-10-03 拍板）：50Hz / α=0.8 / `MIN_REVERSALS=1`（三档共用）。
  灵敏度三档，每档四个参数以中档为圆心**同向**偏移 ≈15%（低 = 更难触发，高 = 更易触发）——
  🆕 2026-10-03 第二轮重定：**中档 = 原高档水平**（默认更灵敏），低/高再以新中档为圆心推：

  | 档位 | 阈值 m/s² | 冷却 ms | 双峰窗口 ms | 最小峰间隔 ms |
  |---|---|---|---|---|
  | 低 | 1.56 | 980 | 340 | 70 |
  | **中（默认）** | **1.35** | **850** | **400** | **60** |
  | 高 | 1.14 | 720 | 460 | 50 |

  🔴 **判轴按实测改为 x 与 z**（方案初值的 `|lin_x| vs |lin_y|` 实测不成立），
  实现为 x/y/z 三通道并行、谁先凑齐谁生效。
- 🔴 **既有缺陷一并修复**：§2.1 `onServiceConnected` 无条件挂 `CommandSink`（此前
  `role != OFF` 条件导致「先开无障碍后切角色 ⇒ 链路通但注入静默不执行」）；
  §2.2 `setStateListener`（单槽）→ `addStateListener`/`removeStateListener`（`CopyOnWriteArrayList`）。
- 🔴 **边界如实**：**非前台不生效**（HyperOS 冻结后台进程，实测采样降至 6.7%）；
  **息屏 = 未定论**（A11 实测 21s 锁屏窗口内 5 次全部实时送达 —— 无障碍服务让进程免冻结
  `PolicyMaker reason=accessibility` —— **推翻旧"息屏不可用"结论**，但**长时间息屏未测**
  ⇒ 文案里不提息屏）；误触发**无法靠幅度区分**（走路/拿起放下峰值 p90 4.23 ≥ 有意甩动 3.89）
  ⇒ **三档都压不住走路误触**，"低档"只是要甩更用力，**不是**走路不翻；横持方向语义未定义。

## 🆕 TASK-033：蓝牙控制通道（HID 落码）

**一句话**：设置页「实验室 · 蓝牙控制」开关 → 启 `HidKeepAliveService`（注册保活）→ 音量键 / 晃动
不再（只）走 TCP，改由 **HID 直发按键**给墨水屏（`sendCommand` 通道分派）。

- **通道分派**（`RemoteLinkManager.sendCommand`）：**HID 优先**（`HidKeepAliveService.link()` 非空且
  `isHostConnected()` ⇒ `pageNext()`/`pagePrev()`），否则退回 TCP（**TCP 行为逐字不变**）。
- **统一门控**（`RemoteLinkManager.canSend()`）：HID 已连 **或** TCP `STATE_CONNECTED` ⇒ 允许发；
  音量键（`RemoteKeyService.onKeyEvent`）与晃动（`ShakeDetector.sync` G3）都改读这个统一判据。
- **互斥**：开「蓝牙控制」时先 `stopSession()` 结束 TCP 会话（两条通道不并存）。
- **默认零差异**：`bt_control_enabled` 默认 `false` ⇒ 不启服务、无通知、行为与旧版一致。
- **子标签**：`lab_tab_remote` 改名「热点翻页」+ 新增 `lab_tab_bt`「蓝牙控制」（手机端与阅读器端都装配）。

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
