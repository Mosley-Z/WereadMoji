package com.inkread.weekread.remote;

import android.content.Context;

import com.inkread.weekread.core.CardPrefs;

/**
 * 遥控角色（TASK-018，V1.0 Beta）：同一 APK 装两端，角色决定启用哪几个环节
 * （docs/FEATURES/remote.md「双角色」）。
 *
 * off（默认）= 与现状零差异；
 * eink = 墨水屏端，收指令 → 注入翻页手势；
 * phone = 手机端，捕获音量键 → 发指令（隐藏卡片相关 UI，见 MainActivity 分流）。
 */
public enum RemoteRole {
    OFF, EINK, PHONE;

    /** 从偏好解析角色（越界/缺省一律回落 OFF —— CardPrefs 已钳，此处双保险）。 */
    public static RemoteRole from(Context c) {
        switch (CardPrefs.getRemoteRole(c)) {
            case CardPrefs.REMOTE_ROLE_EINK:
                return EINK;
            case CardPrefs.REMOTE_ROLE_PHONE:
                return PHONE;
            default:
                return OFF;
        }
    }
}
