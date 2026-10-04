package com.inkread.weekread.remote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * 注入层：{@code dispatchGesture} **点击边界热区**让墨水屏微信读书翻页（方案 D，2026-10-04）。
 *
 * 🔴🔴 2026-10-04 形态由「滑动」改为「**点击**」（方案 D，真机标定 `验证记录/118`）：
 * 滑动（无论两点直线，还是 24 段折线）在 S4 墨水屏上会被微信读书**判成 tap**，落点由正文
 * 内容决定 —— `PAGE_NEXT` 的滑动起点 `x=380` 恰是**已知划线命中坐标**（`验证记录/110 §5`）
 * ⇒ 右晃**稳定弹出「划线/想法」面板**。S4 系统日志铁证：`inject COMPLETED next=true` 之后
 * ≈0.1s 出现 `PopupWindow` + `RecyclerView`（`artifacts/2026-10-04_右晃弹划线_排查与方案D.md`）。
 * 滑动这颗雷**参数消不掉** ⇒ 弃滑动，改注入**单点点击**到**边界热区**：
 * 左右各 **55px** 内点击**不受正文划线影响**（真机标定 `验证记录/118`：整行划线页上
 * `tap(55,400)` ✅上一页、`tap(425,400)` ✅下一页，且不弹面板）。
 * ⇒ 几何：**下一页=点右热区 `x=425`；上一页=点左热区 `x=55`**，`y=400`。
 *
 * 沿革（滑动时代，已弃用，留作参数来源）：
 * 🔴 2026-10-02（TASK-029-R10）几何由 `420↔60` 内缩为 `380↔100`：
 * 原起点 `x=420` 距右屏缘仅 60px，被系统判定为**边缘手势** ⇒ 滑动后顶部会弹
 * **系统状态栏**（实测持续 ≈2.3s）遮挡正文。上机对照（`验证记录/106`，3 次/组）：
 * `420→100` **3/3 跳栏**；`380→100` 与 `100→380` 均 **0/3 跳栏 + 3/3 翻页**；
 * `340→140` 虽 0/3 跳栏但**翻页仅 2/3**（位移过短，伤可靠性）。
 * ⇒ 取 `380↔100`，即两端各距屏缘 **100px**（屏宽 480）。改后复验 `验证记录/108`
 * （真实链路 15 次动作 0/15 跳栏 + 15/15 翻页），换设备需重新标定。
 *
 * 🔴 2026-10-02（TASK-029-R11，`验证记录/110`）新增**串行化**（方案 S）：
 * 系统同一时刻**只允许一条** {@code dispatchGesture}；上一条没走完就派发下一条 ⇒
 * 旧手势立即 `CANCELLED`（**只投了 DOWN、没走完 UP**）⇒ 被微信读书判成 **tap** ⇒
 * 落点若在划线上就弹「划线/想法」页且**不翻页**。
 * 实测判据：**退化 ⟺ 下发间隔 `gap` < 手势时长 `ms`**（固定 gap 扫 ms：50/100→6/6 退化、
 * 200/400/600→1/6；串行且 gap=0 → 6/6 通过、0 退化）。
 * ⇒ 有手势在飞时**入队**，等 `onCompleted/onCancelled` 回调再派发队首：天然不重叠、**不丢动作**
 * （相对「≥300ms 节流」方案不丢失指令，严格对应手指动作次数）。
 *
 * 线程模型：{@link #inject} **只在主线程**被调用（RemoteLinkManager 收指令后先 post 回主线程），
 * 回调也在主线程 ⇒ 队列单线程访问；仍加 {@code synchronized} 防御。
 *
 * 失败兜底（规格备选）：performAction(ACTION_SCROLL_FORWARD/BACKWARD) 打到
 * 微信读书的 RecyclerView —— ⛔ **已定论不可行**（阅读页无 `isScrollable` 节点、
 * 正文容器 `ViewPager` 报 `scroll=false`、`performAction` 恒 `ok=false`），不再启用。
 */
public final class RemoteInjector {

    private static final String TAG = "RemoteInjector";
    /**
     * 方案 D 几何（真机标定 `验证记录/118`）：屏幕 480×800，左右各 **55px** 边界热区
     * **不受正文划线影响**。点击点落在**页边距**（无文字 ⇒ 不可能压到划线），
     * `y=400` 为正文行中部（已验命中点，翻页方向正确且不弹面板）。
     */
    private static final float TAP_Y = 400f;
    private static final float X_HOT_LEFT = 55f;
    private static final float X_HOT_RIGHT = 425f;
    /**
     * 点击手势时长（ms）。须 {@code >0} 且远小于系统长按阈值（≈500ms）⇒ 判为轻点。
     * 单点路径（仅 {@code moveTo}、无 {@code lineTo}）依据 `验证记录/110 §5` 已证
     * **≡ adb `input tap`**（通道无风险）。
     */
    private static final long TAP_MS = 50;

    /** 待派发的方向队列（FIFO，保序不丢）：true=下一页(点右热区)，false=上一页(点左热区)。 */
    private static final ArrayDeque<Boolean> sQueue = new ArrayDeque<Boolean>();
    /** 当前是否已有手势在飞（在飞则新指令只入队，不派发）。 */
    private static boolean sInFlight = false;
    /** 派发用的服务实例（回调里派发下一条时复用）。 */
    private static AccessibilityService sSvc;

    private RemoteInjector() {
    }

    /**
     * 把指令翻译成翻页手势并派发（串行化：有手势在飞则排队，见类注释）。
     *
     * @return 指令被接受返回 true；非翻页指令（BYE 等）返回 false。
     */
    public static boolean inject(AccessibilityService svc, int cmd) {
        // 🔴 C1（CODE_REVIEW 加固）：只允许「阅读器端（EINK）」执行注入 —— 手机端只发不收。
        //    本方法是注入层**唯一入口**，而 sink 在 RemoteKeyService 里是**无条件挂载**的
        //    （保留 TASK-029 §2.1 修复本意：先开无障碍、后切角色时也能收到指令），
        //    所以真正的角色门控放在这里**惰性判**：非 EINK 一律拒绝（不注入、不弹面板、
        //    不改任何状态）。此前 phone 端若也收到指令会照样注入 ⇒ 手机自己乱翻页。
        //    ⚠️ BYE **不经过**本方法（RemoteLinkManager 收到 BYE 时已先行结束会话）
        //       ⇒ 会话结束语义**不变**。
        RemoteRole role = RemoteRole.from(svc);
        if (role != RemoteRole.EINK) {
            Log.i(TAG, "inject 拒绝：role=" + role + "（仅阅读器端注入）");
            return false;
        }
        final boolean isNext;
        if (cmd == RemoteProtocol.CMD_PAGE_NEXT) {
            isNext = true;    // 点击右热区 = 下一页
        } else if (cmd == RemoteProtocol.CMD_PAGE_PREV) {
            isNext = false;   // 点击左热区 = 上一页
        } else {
            return false;
        }
        synchronized (sQueue) {
            // 服务实例变更（无障碍被关→开、服务被回收重建）⇒ 丢弃上一世状态：
            // 否则残留的 sInFlight=true 会让新会话的指令**永久排队**、永不派发。
            if (svc != sSvc) {
                sQueue.clear();
                sInFlight = false;
                sSvc = svc;
            }
            sQueue.addLast(Boolean.valueOf(isNext));
            if (sInFlight) {
                Log.i(TAG, "inject QUEUED next=" + isNext + " depth=" + sQueue.size());
                return true;    // 已受理（排队），等前一条走完自动补发
            }
            sInFlight = true;
            dispatchNextLocked();
        }
        return true;
    }

    /** 派发队首手势（须持有 sQueue 锁）。队空则复位 sInFlight，表示本轮结束。 */
    private static void dispatchNextLocked() {
        Boolean head = sQueue.pollFirst();
        if (head == null) {
            sInFlight = false;
            return;
        }
        final boolean isNext = head.booleanValue();
        // 方案 D：注入**单点点击**到边界热区（滑动在墨水屏上会退化成 tap、落点踩划线 ⇒ 弃用）。
        final float x = isNext ? X_HOT_RIGHT : X_HOT_LEFT;
        Path path = new Path();
        path.moveTo(x, TAP_Y);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .build();
        boolean accepted = sSvc.dispatchGesture(gd, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.i(TAG, "inject COMPLETED next=" + isNext);
                synchronized (sQueue) {
                    dispatchNextLocked();   // 上一条走完 ⇒ 派发下一条
                }
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "inject CANCELLED next=" + isNext);
                synchronized (sQueue) {
                    dispatchNextLocked();   // 被取消也视为「本次结束」，继续派发
                }
            }
        }, null);
        Log.i(TAG, "inject next=" + isNext + " accepted=" + accepted + " queued=" + sQueue.size());
        if (!accepted) {
            // 未被系统受理 ⇒ **不会回调** ⇒ 立即派发下一条，否则队列永久卡死
            Log.w(TAG, "inject REJECTED next=" + isNext);
            dispatchNextLocked();
        }
    }
}
