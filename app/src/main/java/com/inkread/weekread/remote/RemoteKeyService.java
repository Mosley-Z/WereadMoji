package com.inkread.weekread.remote;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.inkread.weekread.core.CardPrefs;

/**
 * 遥控无障碍服务（**独立于卡片服务** —— res/xml/a11y_card_service.xml 一字不改，硬约束 A6；
 * 配置见 res/xml/a11y_remote_service.xml：canRequestFilterKeyEvents + canPerformGestures）。
 *
 * 双角色行为（remote_role，docs/FEATURES/remote.md「双角色」）：
 *  - phone：**两条并列的捕获路径**
 *           ① onKeyEvent 捕获音量键（本类）；
 *              🆕 TASK-039：按**通道分流** —— 蓝牙控制开 ⇒ 音量键发 **HID 键**（`RemoteLinkManager`
 *              内部 HID 优先分派）；热点通道 ⇒ 发 **TCP 指令**（原行为）。蓝牙通道下另受
 *              `CardPrefs.bt_volkey_enabled`（默认开）门控：关掉 ⇒ 音量键交还系统调音量。
 *           ② {@link ShakeDetector} 捕获晃动（TASK-029，仅 role=phone + 总开关 on + 会话已连接时注册）
 *           → 都经 RemoteLinkManager 发指令（音量键那条返回 true 消费，实测观感：音量条不出现）；
 *  - eink ：RemoteLinkManager 收指令 → RemoteInjector 注入点击边界热区（方案 D）。
 *
 * 🔴 三条实测硬约束（`_probe/a11y_keyprobe/EVIDENCE.md`，全部内建）：
 *  ① 按键过滤全局互斥：与「开关控制」等同机共存时**一条都收不到** —— 实验室页必须给可操作指引；
 *  ② 只处理 ACTION_DOWN 且 repeatCount==0 才产生翻页（P8=A：长按不连翻，验收 A12）；
 *     repeat>0 的重复 DOWN 也 return true 消费 —— 翻一页后长按不会变成连续调音量；
 *  ③ 禁止按 deviceId 过滤（音量上/下来自两个不同 input 设备：deviceId=2 / 5），只认 keyCode。
 */
public class RemoteKeyService extends AccessibilityService {

    private static final String TAG = "RemoteKeyService";
    private static volatile RemoteKeyService sInstance;

    /**
     * 🆕 TASK-029：会话状态监听 —— 只在「已连接」期间让晃动捕获层采样（门控 G3）。
     *
     * <p>🔴 这里能安全注册，靠的是 TASK-029 顺带修的 §2.2（`RemoteLinkManager` 由单槽
     * 监听改为 `addStateListener`）—— 否则本监听会和设置页的状态行监听**互相顶掉**。
     */
    private final RemoteLinkManager.StateListener mSessionStateListener =
            new RemoteLinkManager.StateListener() {
                @Override
                public void onStateChanged(int state, String peerIp, String detail) {
                    ShakeDetector.sync(RemoteKeyService.this);
                }
            };

    /** 探针/调试用：确认服务活着。 */
    public static boolean isAlive() {
        return sInstance != null;
    }

    /**
     * 🆕 TASK-073：运行中实例（未运行 = null）。
     *
     * <p>「本机晃动翻页」需要 {@code canPerformGestures} 的服务句柄来做**本机**手势注入
     * （{@link RemoteInjector#injectLocal}）—— 本 App 只有本服务声明了该能力。
     */
    public static RemoteKeyService instance() {
        return sInstance;
    }

    @Override
    public void onServiceConnected() {
        sInstance = this;
        RemoteRole role = RemoteRole.from(this);
        Log.i(TAG, "SERVICE_CONNECTED role=" + role
                + " caps=0x" + Integer.toHexString(
                        getServiceInfo() == null ? 0 : getServiceInfo().getCapabilities()));
        // 🔴 TASK-029 §2.1 修复：**无条件**挂 CommandSink。
        //    此前是 `if (role != OFF)` —— 用户「先开无障碍、后切角色」时，onServiceConnected
        //    那一刻 role 还是默认 OFF ⇒ sink 从未挂上 ⇒ 链路正常建起、指令也正常收到
        //    （`recv PAGE_NEXT`），但注入静默不发生（一条日志都没有），观感 =「显示已连接却按了没反应」。
        //    sink 只在**收到对端指令**时被调用（RemoteLinkManager.onCommand），OFF 角色既不
        //    startSession 也不会收到指令 ⇒ 无条件挂载**零副作用**。
        //    🔴 C1（CODE_REVIEW 加固）：角色的**执行门控**已在 `RemoteInjector.inject()` 内
        //    惰性判（仅 EINK 端真正注入，phone 端只发不收）⇒ 这里**依旧无条件挂载**，
        //    与上面的 §2.1 修复本意一致，两者不冲突。
        RemoteLinkManager.get().setCommandSink(new RemoteLinkManager.CommandSink() {
            @Override
            public boolean inject(int cmd) {
                return RemoteInjector.inject(RemoteKeyService.this, cmd);
            }
        });
        // 🆕 TASK-029：订阅会话状态，驱动晃动捕获层按 G3 注册/注销
        RemoteLinkManager.get().addStateListener(mSessionStateListener);
        ShakeDetector.sync(this);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        // 非 phone 角色不碰按键（eink 端音量键归系统；off 不会被启用）
        if (RemoteRole.from(this) != RemoteRole.PHONE) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;   // UP / 其它动作不消费
        }
        final int cmd;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                cmd = RemoteProtocol.CMD_PAGE_NEXT;   // 音量下 = 下一页（实测键值映射）
                break;
            case KeyEvent.KEYCODE_VOLUME_UP:
                cmd = RemoteProtocol.CMD_PAGE_PREV;   // 音量上 = 上一页
                break;
            default:
                return false;
        }
        // 🆕 TASK-039：蓝牙通道下的音量键翻页开关（默认开）。
        //    🔴 **只作用于蓝牙通道**（`bt_control_enabled=true`）：关掉 ⇒ 音量键交还系统调音量。
        //    热点通道**不受本开关影响** —— 只在蓝牙通道生效，避免动到已验证的热点路径（验收 A4）。
        if (CardPrefs.isBtControlEnabled(this) && !CardPrefs.isBtVolkeyEnabled(this)) {
            Log.i(TAG, "onKeyEvent keyCode=" + event.getKeyCode()
                    + " → 蓝牙通道且「音量键翻页」已关，交还系统（不消费）");
            return false;
        }
        // 🔴 B1（CODE_REVIEW 加固）：**只有"正在遥控（任一通道已就绪）"时才吞音量键**。
        //    此前只要 role=phone 就无条件 `return true` —— 用户没点「开始遥控」、
        //    或会话已断 / 空闲超时后，音量键仍被吃掉 ⇒ 音量调不动（用户侧："音量键失灵"）。
        //    改为：**无可发通道** ⇒ 不消费（return false），音量键**交还系统**。
        //    🆕 TASK-033：判据由「TCP 会话 CONNECTED」升级为 `canSend()`（= HID 已连接 **或** TCP 已连接），
        //    否则「蓝牙控制」通道下音量键会被误判为"未连接"而不生效。
        if (!RemoteLinkManager.get().canSend()) {
            Log.i(TAG, "onKeyEvent " + RemoteProtocol.name(cmd)
                    + " → 无可发通道（HID/TCP 均未就绪），音量键交还系统（不消费）");
            return false;
        }
        // ②③ 只认 keyCode；仅 repeat==0 产生翻页（A12 长按不连翻），重复 DOWN 也消费掉
        //
        // 🔴 try/catch 兜底：本方法是**系统在主线程的回调**，任何未捕获异常都会**杀掉整个进程**，
        //    进而把本 App 的两个无障碍服务一起停用（上机实测：曾因 sendCommand 在主线程
        //    write socket 抛 NetworkOnMainThreadException 而整进程崩溃）。异常只落到日志，
        //    绝不逃逸出本方法。
        try {
            if (event.getRepeatCount() == 0) {
                boolean sent = RemoteLinkManager.get().sendCommand(cmd);
                Log.i(TAG, "onKeyEvent keyCode=" + event.getKeyCode()
                        + " repeat=0 → " + RemoteProtocol.name(cmd) + " sent=" + sent);
            } else {
                Log.i(TAG, "onKeyEvent repeat=" + event.getRepeatCount() + " ignored（P8=A 长按不连翻）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "onKeyEvent failed(swallowed): " + t);
        }
        return true;   // 消费：音量条不出现
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不读窗口内容（canRetrieveWindowContent=false）
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        // 必须留痕：服务被系统回收会连带结束会话（RemoteLinkManager.onServiceDestroyed），
        // 是「点开始遥控后立刻变成『无障碍服务已回收』」这类现象的唯一线索
        // （TASK-018 上机排查：曾因本方法零日志而只能靠推断）
        Log.i(TAG, "SERVICE_DESTROYED isSelf=" + (sInstance == this));
        sInstance = null;
        RemoteLinkManager.get().removeStateListener(mSessionStateListener);   // 🆕 TASK-029
        ShakeDetector.shutdown();     // 🆕 TASK-029：服务没了 ⇒ 采样必须停，不留残采
        RemoteLinkManager.get().onServiceDestroyed();
        super.onDestroy();
    }
}
