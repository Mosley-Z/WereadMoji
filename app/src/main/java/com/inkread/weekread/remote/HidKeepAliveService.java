package com.inkread.weekread.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * TASK-032 · T1 蓝牙 HID 注册保活前台服务。
 *
 * <p>为什么必须存在（官方明文 + 真机铁证）：{@code BluetoothHidDevice.registerApp} 的注册
 * **在 App 退到后台 / 息屏时会被系统自动注销** ⇒ 墨水屏就收不到翻页键。用前台服务
 * （{@code foregroundServiceType=connectedDevice}，声明于 manifest）+ {@code PARTIAL_WAKE_LOCK}
 * 让进程/注册在后台与息屏时存活。
 *
 * <p>🔴 **只做"注册存活"**（{@code ADR-012} 约束② / 本卡 Step 0）。指令映射归 {@code TASK-033}。
 * 🔴 **默认零差异**：本服务**只在**「蓝牙控制」角色激活时启动（由 UI 触发，见 {@code TASK-033}）；
 * 默认未启用 ⇒ 不常驻、无通知（本卡验收 A6）。
 * 🔴 **只在手机端运行**：墨水屏端不启用「蓝牙控制」⇒ 不启动本服务、无通知（本卡验收 A4/A10）。
 */
public class HidKeepAliveService extends Service {

    private static final String TAG = "HidKeepAlive";
    private static final String CHANNEL_ID = "hid_keepalive";
    private static final int NOTI_ID = 0x4849;

    /** 🔴 TASK-033：运行中实例（`onCreate` 置、`onDestroy` 清）—— 供发指令方取 HID 链路。 */
    private static volatile HidKeepAliveService sInstance;

    /** 🆕 TASK-033：状态变化监听（设置页「蓝牙控制」页订阅，驱动状态行实时刷新）。 */
    public interface StateListener {
        void onHidStateChanged();
    }

    private static final java.util.concurrent.CopyOnWriteArrayList<StateListener> sListeners =
            new java.util.concurrent.CopyOnWriteArrayList<StateListener>();

    /** 订阅 HID 状态变化（注册 / 连接态变化）。 */
    public static void addListener(StateListener l) {
        if (l != null) sListeners.addIfAbsent(l);
    }

    /** 注销订阅。 */
    public static void removeListener(StateListener l) {
        if (l != null) sListeners.remove(l);
    }

    private static void notifyListeners() {
        for (StateListener l : sListeners) {
            try {
                l.onHidStateChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    private HidLink mLink;
    private PowerManager.WakeLock mWake;

    /** 运行中实例（未运行 = null）。 */
    public static HidKeepAliveService instance() {
        return sInstance;
    }

    /** 当前 HID 链路（未运行 = null）。发指令方（RemoteLinkManager）用。 */
    public static HidLink link() {
        HidKeepAliveService s = sInstance;
        return s == null ? null : s.mLink;
    }

    /** 保活是否在运行（= HID 已注册或正在注册）。 */
    public static boolean isRunning() {
        return sInstance != null;
    }

    /**
     * 启动保活（幂等）。UI「蓝牙控制」开关打开时调。
     *
     * <p>🔴 前台服务启动时机很关键：**必须在 App 处于前台时发起**，否则部分 ROM（MIUI）
     * 会把 FGS 降级、通知不投递（实测 `numPostedByApp=0`）。本方法由设置页点击事件调用 ⇒ 天然满足。
     */
    public static void start(Context c) {
        try {
            Intent i = new Intent(c, HidKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "start 异常 " + t);
        }
    }

    /** 停止保活（幂等）。UI「蓝牙控制」开关关闭时调（注销注册 + 释放锁 + 去通知）。 */
    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, HidKeepAliveService.class));
        } catch (Throwable t) {
            Log.w(TAG, "stop 异常 " + t);
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;   // 非绑定服务
    }

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        startForegroundCompat();
        acquireWakeLock();

        if (!HidLink.supported()) {
            Log.w(TAG, "本机不支持 HID（SDK_INT=" + Build.VERSION.SDK_INT + "）⇒ 服务自停");
            stopSelf();
            return;
        }

        mLink = new HidLink(this);
        mLink.setListener(new HidLink.Listener() {
            @Override public void onRegistered(boolean registered, BluetoothDevice device) {
                Log.i(TAG, "registered=" + registered);
                notifyListeners();   // 🆕 TASK-033：驱动设置页状态行
            }
            @Override public void onConnectionState(BluetoothDevice device, int state) {
                Log.i(TAG, "conn state=" + state);
                notifyListeners();   // 🆕 TASK-033
            }
        });
        mLink.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 系统回收后尽量重启（保活是本服务的全部目的）。
        return START_STICKY;
    }

    @Override public void onDestroy() {
        Log.i(TAG, "onDestroy");
        sInstance = null;
        if (mLink != null) {
            mLink.stop();
            mLink = null;
        }
        if (mWake != null && mWake.isHeld()) {
            try { mWake.release(); } catch (Throwable ignored) {}
        }
        mWake = null;
        notifyListeners();   // 🆕 TASK-033：服务已停 ⇒ 设置页状态行回「未启用」
        super.onDestroy();
    }

    /** 起前台 + 通知（API 26+ 需 channel）。targetSdk=30 ⇒ FGS 通知走 legacy 行为。 */
    private void startForegroundCompat() {
        try {
            Notification n;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(new NotificationChannel(
                            CHANNEL_ID, "蓝牙遥控（保活）", NotificationManager.IMPORTANCE_LOW));
                }
                n = new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("蓝牙遥控运行中")
                        .setContentText("保持手机蓝牙键盘注册（息屏可用）")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setOngoing(true)
                        .build();
            } else {
                n = new Notification.Builder(this)
                        .setContentTitle("蓝牙遥控运行中")
                        .setContentText("保持手机蓝牙键盘注册（息屏可用）")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setOngoing(true)
                        .build();
            }
            startForeground(NOTI_ID, n);
        } catch (Throwable t) {
            Log.w(TAG, "startForeground 异常 " + t);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            mWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wereadmoji:hid_keepalive");
            mWake.setReferenceCounted(false);
            mWake.acquire();
            Log.i(TAG, "PARTIAL_WAKE_LOCK acquired");
        } catch (Throwable t) {
            Log.w(TAG, "wakelock 异常 " + t);
        }
    }
}
