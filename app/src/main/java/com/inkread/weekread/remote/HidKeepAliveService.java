package com.inkread.weekread.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.inkread.weekread.R;
import com.inkread.weekread.core.CardPrefs;

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
    /** 🆕 TASK-034：当前通知正文（连态变化时更新，A1「在通知给出提示」）。 */
    private String mNotiText;
    /** 🆕 TASK-034：服务已销毁标志 —— 阻断 `stop()` 触发的**迟到 HID 回调**再重投通知。 */
    private volatile boolean mDestroyed;

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
                // 🆕 TASK-034：注册态 → 通知正文（A1）
                updateNotification(getString(registered
                        ? R.string.lab_bt_noti_registered : R.string.lab_bt_noti_init));
                notifyListeners();   // 🆕 TASK-033：驱动设置页状态行
                // 🆕 审查修复（NG-2）：注册态变化会改变 canSend() 判据 ⇒ 重算晃动门控。
                ShakeDetector.sync(HidKeepAliveService.this);
            }
            @Override public void onConnectionState(BluetoothDevice device, int state) {
                Log.i(TAG, "conn state=" + state);
                // 🆕 TASK-034：连态变化 → 通知正文（断开时给「去墨水屏点连接」引导，A1/A2）
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    String name = HidLink.safeName(device);   // 🆕 审查修复（NG-4）：防 SecurityException 逃逸
                    updateNotification(getString(R.string.lab_bt_noti_connected, name));
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    updateNotification(getString(R.string.lab_bt_noti_disconnected));
                }
                notifyListeners();   // 🆕 TASK-033
                // 🆕 审查修复（NG-2）：HID 连/断态直接决定 canSend() ⇒ 重算晃动门控。
                //   放**服务层**（而非仅设置页）：设置页不在前台、墨水屏稍后自动连上时同样即时生效/停采。
                ShakeDetector.sync(HidKeepAliveService.this);
            }
        });
        boolean started = mLink.start();
        if (!started) {
            // 🆕 TASK-034：ROM 无 HID profile / 无蓝牙适配器 ⇒ start() 返回 false 且不会有任何后续回调
            //   （onServiceConnected 不会来）⇒ 主动通知 UI 显式提示「本机不支持」，别停在假「等待连接」。
            // 🆕 审查修复（NG-1）：`startForegroundCompat()` + `acquireWakeLock()` 已在 L122-123 执行 ⇒
            //   若不在此收尾，会留下**用户划不掉的「蓝牙遥控运行中」假通知 + WakeLock 白占**（撞文案红线）。
            //   故：复位「蓝牙控制」开关（否则开关亮着却状态=未启用，自相矛盾）+ 通知 UI + **服务自停**
            //   （`stopSelf()` ⇒ `onDestroy` 清通知 / 释放锁）。
            Log.w(TAG, "HID start 失败（本机不支持 / 无蓝牙适配器）⇒ 复位开关 + 服务自停");
            try { CardPrefs.setBtControlEnabled(this, false); } catch (Throwable ignored) {}
            notifyListeners();
            stopSelf();
            return;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 系统回收后尽量重启（保活是本服务的全部目的）。
        return START_STICKY;
    }

    @Override public void onDestroy() {
        Log.i(TAG, "onDestroy");
        // 🆕 TASK-034（关键）：**先**置销毁位 + 摘监听，再 stop()。
        //   🔴 真机铁证：`unregisterApp()` 触发的 `onAppStatusChanged(registered=false)` 会**晚于 onDestroy**
        //   到达（实测 +16ms）⇒ 若清完通知才收到该回调，会被 `updateNotification()` **重新投递**，
        //   导致停用后通知栏仍常驻、且用户划不掉（ongoing）。
        mDestroyed = true;
        sInstance = null;
        if (mLink != null) {
            mLink.setListener(null);
            mLink.stop();
            mLink = null;
        }
        if (mWake != null && mWake.isHeld()) {
            try { mWake.release(); } catch (Throwable ignored) {}
        }
        mWake = null;
        // 🆕 TASK-034：停用即清除常驻通知（`stopForeground(remove)` + 显式 cancel）。
        try { stopForeground(true); } catch (Throwable ignored) {}
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
        } catch (Throwable ignored) {}
        notifyListeners();   // 🆕 TASK-033：服务已停 ⇒ 设置页状态行回「未启用」
        // 🆕 审查修复（NG-2）：服务停 ⇒ `link()` 归 null ⇒ `canSend()` 转 false ⇒ 确保晃动采样停。
        ShakeDetector.sync(this);
        super.onDestroy();
    }

    /** 起前台 + 通知（API 26+ 需 channel）。targetSdk=30 ⇒ FGS 通知走 legacy 行为。 */
    private void startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(new NotificationChannel(
                            CHANNEL_ID, "蓝牙遥控（保活）", NotificationManager.IMPORTANCE_LOW));
                }
            }
            mNotiText = getString(R.string.lab_bt_noti_init);
            startForeground(NOTI_ID, buildNotification(mNotiText));
        } catch (Throwable t) {
            Log.w(TAG, "startForeground 异常 " + t);
        }
    }

    /**
     * 🆕 TASK-034：更新前台通知正文（连态变化时调用）。
     *
     * <p>NotificationManager 线程安全 ⇒ 可直接在 HID 的 binder 回调线程调用，无需切主线程。
     */
    private void updateNotification(String text) {
        if (text == null || mDestroyed) return;   // 🆕 TASK-034：已销毁 ⇒ 不再投递（防迟到回调重投）
        mNotiText = text;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNotification(text));
        } catch (Throwable t) {
            Log.w(TAG, "updateNotification 异常 " + t);
        }
    }

    /** 构造常驻通知（标题固定，正文按连态变）。 */
    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("蓝牙遥控运行中")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("蓝牙遥控运行中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
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
