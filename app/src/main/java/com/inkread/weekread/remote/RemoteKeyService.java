package com.inkread.weekread.remote;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 遥控无障碍服务（**独立于卡片服务** —— res/xml/a11y_card_service.xml 一字不改，硬约束 A6；
 * 配置见 res/xml/a11y_remote_service.xml：canRequestFilterKeyEvents + canPerformGestures）。
 *
 * 双角色行为（remote_role，docs/FEATURES/remote.md「双角色」）：
 *  - phone：onKeyEvent 捕获音量键 → 经 RemoteLinkManager 发指令 → 返回 true 消费
 *           （实测观感：音量条不出现；eink 端音量键不受影响，正常调音量）；
 *  - eink ：RemoteLinkManager 收指令 → RemoteInjector 注入左右滑。
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

    /** 探针/调试用：确认服务活着。 */
    public static boolean isAlive() {
        return sInstance != null;
    }

    @Override
    public void onServiceConnected() {
        sInstance = this;
        RemoteRole role = RemoteRole.from(this);
        Log.i(TAG, "SERVICE_CONNECTED role=" + role
                + " caps=0x" + Integer.toHexString(
                        getServiceInfo() == null ? 0 : getServiceInfo().getCapabilities()));
        if (role != RemoteRole.OFF) {
            RemoteLinkManager.get().setCommandSink(new RemoteLinkManager.CommandSink() {
                @Override
                public boolean inject(int cmd) {
                    return RemoteInjector.inject(RemoteKeyService.this, cmd);
                }
            });
        }
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
        // ②③ 只认 keyCode；仅 repeat==0 产生翻页（A12 长按不连翻），重复 DOWN 也消费掉
        if (event.getRepeatCount() == 0) {
            boolean sent = RemoteLinkManager.get().sendCommand(cmd);
            Log.i(TAG, "onKeyEvent keyCode=" + event.getKeyCode()
                    + " repeat=0 → " + RemoteProtocol.name(cmd) + " sent=" + sent);
        } else {
            Log.i(TAG, "onKeyEvent repeat=" + event.getRepeatCount() + " ignored（P8=A 长按不连翻）");
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
        sInstance = null;
        RemoteLinkManager.get().onServiceDestroyed();
        super.onDestroy();
    }
}
