package com.inkread.weekread.remote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;

import com.inkread.weekread.core.CardPrefs;

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
 * 🆕 TASK-073（**本机模式**）沿用**方案 D 的思路（点边界窄条）**，只是**落点要贴边**：
 * 手机端微信读书**普通版**的翻页热区同样是**贴着左右边界的窄条** —— 真机标定（`_shots/T073`）：
 * `x ≤ 100px`（1200px 屏）可翻页；`x = 120 / 160px` 落进**正文**（点中文字 ⇒ 弹「划线/想法」）；
 * `x = 200px` 落进**中央菜单区**。初版用「屏宽 1/6」换算成 200px ⇒ **必然踩正文**（真机复现）。
 * 墨水屏版热区 ≈ 55px / 480px，两版**都不是屏宽比例** ⇒ **禁用比例换算**。
 * ⇒ 本机的翻页手势**提供两种，由用户单选**（{@code CardPrefs.shake_local_mode}）：
 * <ul>
 *   <li>{@link CardPrefs#SHAKE_LOCAL_MODE_TAP}（**默认**）= 点**边界窄条**（{@code LOCAL_EDGE_*}），
 *       与墨水屏端**同语义**（左边缘=上一页 / 右边缘=下一页）、手势最短；</li>
 *   <li>{@link CardPrefs#SHAKE_LOCAL_MODE_SWIPE} = **水平横扫**（{@code LOCAL_SWIPE_*}），
 *       与分辨率无关、不吃热区宽度。</li>
 * </ul>
 * 两者都经真机验证可翻页（点击 `_shots/T073/70~72`；滑动 `sw_0..4` + 晃动 16→18）。
 * 🔴 两者**都只对竖屏、左右横向翻页有效**（横屏不在支持范围，UI 已注明）。
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

    /**
     * 🆕 TASK-073（2026-10-09 **修正**）· **本机注入** = 点**贴边窄条**（与墨水屏端**同语义**）。
     *
     * <p>🔴 根因（手机真机标定，`_shots/T073` 扫描 + 你的复测反馈）：**微信读书普通版**的翻页热区
     * 是**贴着左右边界的窄条**（**固定物理尺寸**，非屏宽比例）：在 1200px 宽的手机上
     * `x ≤ 100px` 才翻页，`x = 120 / 160px` 落进**正文**（点中文字 ⇒ 弹「划线/想法」工具条），
     * `x = 200px` 落进**中央菜单区**。初版用「屏宽 1/6 = 200px」⇒ **必然踩正文**。
     * 墨水屏版热区 ≈ 55px / 480px（`X_HOT_*`）—— 两版**都不是屏宽比例** ⇒ 比例换算整体作废。
     *
     * <p>✅ 落点：**距左右边缘固定 {@link #LOCAL_EDGE_PX}**（左 = 上一页 / 右 = 下一页），
     * 与墨水屏端**同一套语义**，只是把 55px 放宽到通用值。真机实测（`_shots/T073/70~72`）：
     * `tap(50,·)` → 上一页（18→17）、`tap(1150,·)` = 宽−50 → 下一页（17→18），**双向都翻页**。
     *
     * <p>⚠️ 用**固定 px**（而非 dp / 比例）：两版热区实测都是「固定物理尺寸窄条」，
     * 且 `LOCAL_*` 只在**手机本机**这条路径生效（不用在墨水屏上）⇒ 固定 px 最直观可复现。
     * <p>⚠️ 墨水屏端**仍用** {@code X_HOT_* = 55/425 @400}（480 屏专用），**不要跟着改**。
     */
    private static final float LOCAL_EDGE_PX = 50f;
    private static final float LOCAL_Y_RATIO = 0.5f;

    /**
     * 🆕 TASK-073（二改）· 本机注入的**第二种方式：横向横扫**（由 {@code CardPrefs.shake_local_mode} 单选）。
     *
     * <p>命中不了的场景（未来某机型热区更窄 / 点击被拒）可用横扫兜底：与分辨率无关、不必命中窄热区，
     * 也不吃正文。**下一页 = 右→左**（{@link #LOCAL_SWIPE_FROM} → {@link #LOCAL_SWIPE_TO}），
     * 上一页 = 反向；横向按屏宽比例取（换机型自动适配），纵向取屏高 1/2。
     * <p>✅ 真机实测 6/6（`_shots/T073/sw_0..4`）+ 真机晃动实测 16→18（`_shots/T073/63~65`）。
     * <p>⚠️ 墨水屏端**不使用**本组：滑动在 S4 上会被微信读书判成 tap（见类注释 · 方案 D），
     * 对端**恒为点边窄条** {@code X_HOT_*}。
     */
    private static final float LOCAL_SWIPE_FROM = 0.80f;
    private static final float LOCAL_SWIPE_TO = 0.20f;
    /** 本机滑动时长（ms）：明显长于点击、又远小于长按阈值（≈500ms）⇒ 判为一次横扫。 */
    private static final long LOCAL_SWIPE_MS = 200L;

    /**
     * 待派发的手势（FIFO，保序不丢）。队列元素 = **已解析的落点 + 时长**
     * —— 对端注入（墨水屏 55/425/400）与本机注入（贴边 50px）共用同一条串行队列，
     * 因为系统同一时刻只允许一条 {@code dispatchGesture}。
     *
     * <p>🆕 TASK-073：两侧**都是单点点击**（{@code x1==x2 && y1==y2}），差别只在落点与 {@code mode}；
     * 保留起止两点字段是为了将来若某端要回到滑动手势时不必再改队列结构。
     */
    private static final class Gesture {
        final float x1, y1, x2, y2;
        final long ms;
        final boolean isNext;
        /** 仅日志用：{@code peer} / {@code local}。 */
        final String mode;

        Gesture(float x1, float y1, float x2, float y2, long ms, boolean isNext, String mode) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.ms = ms;
            this.isNext = isNext;
            this.mode = mode;
        }
    }

    private static final ArrayDeque<Gesture> sQueue = new ArrayDeque<Gesture>();
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
            final float tx = isNext ? X_HOT_RIGHT : X_HOT_LEFT;
            // 对端（墨水屏）：单点点击（起止同一点 ⇒ 路径只挂 moveTo）
            sQueue.addLast(new Gesture(tx, TAP_Y, tx, TAP_Y, TAP_MS, isNext, "peer"));
            if (sInFlight) {
                Log.i(TAG, "inject QUEUED next=" + isNext + " depth=" + sQueue.size());
                return true;    // 已受理（排队），等前一条走完自动补发
            }
            sInFlight = true;
            dispatchNextLocked();
        }
        return true;
    }

    /**
     * 🆕 TASK-073 · **本机注入**：把翻页指令打进**本机屏幕**（晃本机 ⇒ 翻本机上的微信读书）。
     *
     * <p>与 {@link #inject} 的差别只有两点：
     * <ol>
     *   <li><b>门控</b>：不看 {@code remote_role}（本机注入与"谁是墨水屏端"无关），
     *       只认「本机晃动开关已开」({@code CardPrefs.shake_local_enabled}) + 服务句柄非空；</li>
     *   <li><b>手势</b>：由 {@code CardPrefs.shake_local_mode} 单选 ——
     *       {@code TAP}（默认）= 点**贴边窄条** {@link #LOCAL_EDGE_PX}（左 = 上一页 / 右 = 下一页）；
     *       {@code SWIPE} = **水平横扫**（{@code LOCAL_SWIPE_*}）。两者都**只对竖屏横向翻页**有效。</li>
     * </ol>
     * 两者**共用同一条 FIFO 串行队列**（系统同一时刻只允许一条 {@code dispatchGesture}）。
     *
     * @return 指令被受理返回 true
     */
    public static boolean injectLocal(AccessibilityService svc, int cmd) {
        // 🔴 本方法只做「本机注入」，与 inject() 的 EINK 门控**互不影响**：
        //    本机模式下 remote_role 多半是 OFF（不依赖它），所以这里绝不能再要求 role==EINK。
        if (svc == null) {
            Log.i(TAG, "injectLocal 拒绝：无障碍服务未就绪（需在系统里打开「微读墨记 · 遥控」）");
            return false;
        }
        if (!CardPrefs.isShakeLocalEnabled(svc)) {
            Log.i(TAG, "injectLocal 拒绝：本机晃动开关未开");
            return false;
        }
        final boolean isNext;
        if (cmd == RemoteProtocol.CMD_PAGE_NEXT) {
            isNext = true;
        } else if (cmd == RemoteProtocol.CMD_PAGE_PREV) {
            isNext = false;
        } else {
            return false;
        }
        final int mode = CardPrefs.getShakeLocalMode(svc);
        float x1 = 0f, y1 = 0f, x2 = 0f, y2 = 0f;
        long ms;
        final String tag;   // 日志用：local-tap / local-swipe
        try {
            android.util.DisplayMetrics dm = svc.getResources().getDisplayMetrics();
            final float w = dm.widthPixels, h = dm.heightPixels;
            final float y = h * LOCAL_Y_RATIO;
            if (mode == CardPrefs.SHAKE_LOCAL_MODE_SWIPE) {
                // 横扫：下一页 = 手指**右→左**（0.80w→0.20w）；上一页 = 反向
                final float from = w * LOCAL_SWIPE_FROM;
                final float to = w * LOCAL_SWIPE_TO;
                x1 = isNext ? from : to;
                x2 = isNext ? to : from;
                y1 = y;
                y2 = y;
                ms = LOCAL_SWIPE_MS;
                tag = "local-swipe";
            } else {
                // 点边窄条：下一页 = 点**右**边缘；上一页 = 点**左**边缘（与墨水屏端同语义；单点 = 起止同点）
                final float xt = isNext ? (w - LOCAL_EDGE_PX) : LOCAL_EDGE_PX;
                x1 = xt;
                x2 = xt;
                y1 = y;
                y2 = y;
                ms = TAP_MS;
                tag = "local-tap";
            }
        } catch (Throwable t) {
            Log.w(TAG, "injectLocal: 取屏幕尺寸失败(swallowed): " + t);
            return false;
        }
        synchronized (sQueue) {
            if (svc != sSvc) {
                sQueue.clear();
                sInFlight = false;
                sSvc = svc;
            }
            sQueue.addLast(new Gesture(x1, y1, x2, y2, ms, isNext, tag));
            if (sInFlight) {
                Log.i(TAG, "injectLocal QUEUED mode=" + tag + " next=" + isNext + " depth=" + sQueue.size());
                return true;
            }
            sInFlight = true;
            dispatchNextLocked();
        }
        return true;
    }

    /** 派发队首手势（须持有 sQueue 锁）。队空则复位 sInFlight，表示本轮结束。 */
    private static void dispatchNextLocked() {
        final Gesture head = sQueue.pollFirst();
        if (head == null) {
            sInFlight = false;
            return;
        }
        final boolean isNext = head.isNext;
        final String mode = head.mode;
        // 🆕 TASK-073：手势与落点一律在**入队时**解析（对端 = 墨水屏 55/425/400 单点；
        //   本机 = 贴边 50px 单点 或 屏宽 0.8↔0.2 横扫，按 shake_local_mode 单选）。
        Path path = new Path();
        path.moveTo(head.x1, head.y1);
        if (head.x2 != head.x1 || head.y2 != head.y1) {
            path.lineTo(head.x2, head.y2);   // 起止不同 ⇒ 横扫；相同 ⇒ 单点（≡ adb `input tap`）
        }
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, head.ms))
                .build();
        boolean accepted = sSvc.dispatchGesture(gd, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.i(TAG, "inject COMPLETED mode=" + mode + " next=" + isNext);
                synchronized (sQueue) {
                    dispatchNextLocked();   // 上一条走完 ⇒ 派发下一条
                }
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "inject CANCELLED mode=" + mode + " next=" + isNext);
                synchronized (sQueue) {
                    dispatchNextLocked();   // 被取消也视为「本次结束」，继续派发
                }
            }
        }, null);
        Log.i(TAG, "inject mode=" + mode + " next=" + isNext
                + " (" + head.x1 + "," + head.y1 + ")->(" + head.x2 + "," + head.y2 + ") @" + head.ms
                + "ms accepted=" + accepted + " queued=" + sQueue.size());
        if (!accepted) {
            // 未被系统受理 ⇒ **不会回调** ⇒ 立即派发下一条，否则队列永久卡死
            Log.w(TAG, "inject REJECTED mode=" + mode + " next=" + isNext);
            dispatchNextLocked();
        }
    }
}
