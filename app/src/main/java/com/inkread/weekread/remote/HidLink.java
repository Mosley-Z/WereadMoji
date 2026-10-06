package com.inkread.weekread.remote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.Executor;

/**
 * TASK-032 · T1 蓝牙 HID 外设链路（经典优先）。
 *
 * <p>手机注册成「蓝牙 HID 键盘」，从而给墨水屏（HID 主机）直接发按键
 * （走 **OS 输入源层**，不经墨水屏 App、不经 TCP、无落点 ⇒ 见 {@code ADR-012} 第 3 种模型）。
 *
 * <p>🔴 **本卡范围**：只做到「**能注册、能连接**」。指令映射（PAGE_UP/DOWN ↔ 键值）归
 * {@code TASK-033}；本类只保留最小 {@link #sendKey(int)} 供冒烟。
 *
 * <p>🔴 **落码基线**：照抄 {@code _probe/bt_hidprobe}（唯一被真机端到端验证过的组合）——
 * 47 字节标准启动键盘 Report Descriptor（Report ID=1）+ {@code sendReport(dev, 1, 8字节)}。
 * ⚠️ 真实 API **无** {@code android.bluetooth.HidReportMap}（本工程 {@code javap} 实证）⇒ 描述符必须自带 byte[]。
 *
 * <p>🔴 {@code BluetoothHidDevice} 需 **API 28**（本工程 minSdk=23）⇒ 全部入口先过
 * {@link #supported()}；不满足一律 **不加载本类**（避免 API&lt;28 类校验失败）。
 */
public class HidLink {

    private static final String TAG = "HidLink";

    /** {@code BluetoothProfile.HID_DEVICE} = 19（0x13）（TASK-033：常量统一收口到 {@link HidConst}）。 */
    public static final int PROFILE_HID_DEVICE = HidConst.PROFILE_HID_DEVICE;

    /** 标准「启动键盘」Report Descriptor（Report ID = 1），47 字节。HID 规范标准描述符，非第三方代码。 */
    private static final byte[] REPORT_MAP = new byte[] {
            (byte) 0x05, (byte) 0x01, (byte) 0x09, (byte) 0x06,
            (byte) 0xA1, (byte) 0x01, (byte) 0x85, (byte) 0x01,
            (byte) 0x05, (byte) 0x07, (byte) 0x19, (byte) 0xE0,
            (byte) 0x29, (byte) 0xE7, (byte) 0x15, (byte) 0x00,
            (byte) 0x25, (byte) 0x01, (byte) 0x75, (byte) 0x01,
            (byte) 0x95, (byte) 0x08, (byte) 0x81, (byte) 0x02,
            (byte) 0x95, (byte) 0x01, (byte) 0x75, (byte) 0x08,
            (byte) 0x81, (byte) 0x01, (byte) 0x95, (byte) 0x06,
            (byte) 0x75, (byte) 0x08, (byte) 0x15, (byte) 0x00,
            (byte) 0x25, (byte) 0x65, (byte) 0x05, (byte) 0x07,
            (byte) 0x19, (byte) 0x00, (byte) 0x29, (byte) 0x65,
            (byte) 0x81, (byte) 0x00, (byte) 0xC0
    };

    /** 状态回调（注册态 / 连接态变化）。 */
    public interface Listener {
        void onRegistered(boolean registered, BluetoothDevice device);
        void onConnectionState(BluetoothDevice device, int state);
    }

    private final Context mCtx;
    private BluetoothAdapter mAdapter;
    // 🔴 审查修复（NG-3）：mHid/mHost 由 binder 线程写、主线程读（isHostConnected/sendKey/host()）
    //   ⇒ 必须 volatile 建立 happens-before。同族的 mRegistered/mRunning/mProfileUnavailable/mEverConnected
    //   本就是 volatile —— 此前的遗漏会造成可见性竞态（可能读到过期引用）。
    private volatile BluetoothHidDevice mHid;
    private volatile BluetoothDevice mHost;   // 当前 HID 主机（= 墨水屏）
    private volatile boolean mRegistered;
    private volatile boolean mRunning;
    private volatile Listener mListener;      // 同族：主线程 setListener 写、binder 回调读

    /** 🆕 TASK-034：ROM 无 HID Device profile（{@code getProfileProxy} 返回 false）⇒ 显式上抛「本机不支持」。 */
    private volatile boolean mProfileUnavailable;
    /** 🆕 TASK-034：本次运行周期内是否成功连接过主机（区分「首次待连」与「连过又断」）。 */
    private volatile boolean mEverConnected;

    /** registerApp 需要一个 Executor：直接同步执行即可（回调本身在 binder 线程）。 */
    private final Executor mExec = new Executor() {
        @Override public void execute(Runnable r) { r.run(); }
    };

    public HidLink(Context context) {
        mCtx = context.getApplicationContext();
    }

    /** 本机是否支持 HID 外设（API 28+）。false ⇒ 调用方 fail-closed（报"本机不支持"、退回 TCP）。 */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 28;
    }

    /**
     * 🆕 审查修复（NG-4）：安全取蓝牙**地址**（仅供日志；null 设备 ⇒ {@code "null"}、异常/空 ⇒ {@code "?"}）。
     *
     * <p>{@code BluetoothDevice.getAddress()} 在无 {@code BLUETOOTH_CONNECT}（API 31+）或权限被撤时
     * 会抛 {@link SecurityException}；本方法跑在 HID 的 binder 回调链里，异常逃逸会带走整个进程 ⇒ 就地兜底。
     */
    private static String safeAddr(BluetoothDevice d) {
        if (d == null) return "null";
        try {
            String a = d.getAddress();
            return (a != null && a.length() > 0) ? a : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 🆕 审查修复（NG-4）：安全取设备**显示名**（异常 / 为空则退地址，再失败则空串）。
     *
     * <p>供 UI 状态行与常驻通知共用 —— 二者都在可能抛 {@link SecurityException} 的调用链上
     * （{@code getName()}/{@code getAddress()} 需 {@code BLUETOOTH_CONNECT}），一律就地兜底、绝不逃逸。
     */
    public static String safeName(BluetoothDevice d) {
        if (d == null) return "";
        try {
            String n = d.getName();
            if (n != null && n.length() > 0) return n;
        } catch (Throwable ignored) { }
        try {
            String a = d.getAddress();
            if (a != null && a.length() > 0) return a;
        } catch (Throwable ignored) { }
        return "";
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public boolean isRegistered() {
        return mRegistered;
    }

    public BluetoothDevice host() {
        return mHost;
    }

    /**
     * HID 主机（墨水屏）是否已连接。
     *
     * <p>🔴 TASK-033：这是 HID 通道「能否发指令」的唯一判据（对应 TCP 的 {@code STATE_CONNECTED}）——
     * 供 {@code RemoteLinkManager.sendCommand()} 分派与 {@code RemoteKeyService} 的按键消费门控用。
     */
    public boolean isHostConnected() {
        return mHid != null && mHost != null;
    }

    /**
     * 🆕 TASK-034：本机 ROM **无 HID Device profile**（{@code getProfileProxy} 返回 false）。
     *
     * <p>与 {@link #supported()}（只查 SDK 版本）互补：个别 ROM 虽为 API 28+，却裁掉了 HID profile，
     * 此时 {@code start()} 会返回 false。UI 据此显式提示「本机不支持」，避免开关打开却永远停在
     * 「等待连接」的假象（本卡 A5 缺口修复）。
     */
    public boolean profileUnavailable() {
        return mProfileUnavailable;
    }

    /**
     * 🆕 TASK-034：本次运行周期内是否**曾连上**主机。
     *
     * <p>用于断链提示：区分「还没连过（首次待连）」与「连过又断了（需恢复）」——两者给用户的
     * 引导语不同（本卡 A1/A2）。
     */
    public boolean everConnected() {
        return mEverConnected;
    }

    /** 发送一次「上一页」（键盘 PageUp）。@return true = down 已受理。 */
    public boolean pagePrev() {
        return sendKey(HidConst.KEY_PAGE_UP);
    }

    /** 发送一次「下一页」（键盘 PageDown）。@return true = down 已受理。 */
    public boolean pageNext() {
        return sendKey(HidConst.KEY_PAGE_DOWN);
    }

    /** 取代理并注册。返回 false = 本机不支持 / 无适配器 / ROM 无 HID profile（{@code getProfileProxy} 返回 false）。 */
    public boolean start() {
        if (!supported()) {
            Log.w(TAG, "HID 不支持：SDK_INT=" + Build.VERSION.SDK_INT + "（需 ≥28）");
            return false;
        }
        mRunning = true;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter == null) {
            Log.w(TAG, "无蓝牙适配器");
            return false;
        }
        boolean ok = false;
        try {
            ok = mAdapter.getProfileProxy(mCtx, mProxy, PROFILE_HID_DEVICE);
        } catch (Throwable t) {
            Log.w(TAG, "getProfileProxy 异常 " + t);
        }
        Log.i(TAG, "getProfileProxy(HID_DEVICE) returned " + ok);
        if (!ok) {
            Log.w(TAG, "ROM 无 HID Device profile（fail-closed）");
            mProfileUnavailable = true;   // 🆕 TASK-034：显式上抛（A5 缺口修复）
        } else {
            mProfileUnavailable = false;
        }
        return ok;
    }

    /** 注销 + 关代理。幂等。 */
    public void stop() {
        mRunning = false;
        unregister();
        BluetoothHidDevice hid = mHid;
        BluetoothAdapter ad = mAdapter;
        if (hid != null && ad != null) {
            try { ad.closeProfileProxy(PROFILE_HID_DEVICE, hid); } catch (Throwable ignored) {}
        }
        mHid = null;
        mHost = null;
        mProfileUnavailable = false;   // 🆕 TASK-034：停用后复位，下次启动重新探测
        mEverConnected = false;        // 🆕 TASK-034
    }

    private final BluetoothProfile.ServiceListener mProxy = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            Log.i(TAG, "proxyConnected profile=" + profile);
            if (profile == PROFILE_HID_DEVICE && proxy instanceof BluetoothHidDevice) {
                mHid = (BluetoothHidDevice) proxy;
                mProfileUnavailable = false;   // 🆕 TASK-034：proxy 到了 ⇒ profile 确实可用
                register();
            }
        }
        @Override public void onServiceDisconnected(int profile) {
            Log.i(TAG, "proxyDisconnected profile=" + profile);
            mHid = null;
            mRegistered = false;
            mHost = null;
        }
    };

    private void register() {
        BluetoothHidDevice hid = mHid;
        if (hid == null || mRegistered || !mRunning) return;

        BluetoothHidDevice.Callback cb = new BluetoothHidDevice.Callback() {
            @Override public void onAppStatusChanged(BluetoothDevice d, boolean reg) {
                mRegistered = reg;
                Log.i(TAG, "onAppStatusChanged registered=" + reg + " dev=" + safeAddr(d));
                Listener l = mListener;
                if (l != null) l.onRegistered(reg, d);
            }
            @Override public void onConnectionStateChanged(BluetoothDevice d, int state) {
                Log.i(TAG, "onConnectionStateChanged " + safeAddr(d) + " state=" + state);
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    mHost = d;
                    mEverConnected = true;   // 🆕 TASK-034：断链提示据此区分「首次待连」与「连过又断」
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    mHost = null;
                }
                Listener l = mListener;
                if (l != null) l.onConnectionState(d, state);
            }
        };

        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "微读墨记", "WereadMoji HID Remote", "WereadMoji", HidConst.SUBCLASS1_COMBO, REPORT_MAP);

        boolean ok = false;
        try {
            ok = hid.registerApp(sdp, null, null, mExec, cb);
        } catch (Throwable t) {
            Log.w(TAG, "registerApp 异常 " + t);
        }
        Log.i(TAG, "registerApp returned " + ok);
    }

    private void unregister() {
        BluetoothHidDevice hid = mHid;
        if (hid == null || !mRegistered) { mRegistered = false; return; }
        try { hid.unregisterApp(); } catch (Throwable t) { Log.w(TAG, "unregisterApp 异常 " + t); }
        mRegistered = false;
        mHost = null;
        Log.i(TAG, "unregisterApp called");
    }

    /**
     * 发一次按键（down → 30ms → up）。8 字节 payload、reportId=1。
     *
     * <p>⚠️ 本卡只作冒烟；正式映射（PAGE_UP=0x4B / PAGE_DOWN=0x4E 等）归 {@code TASK-033}。
     *
     * @return true = down 已受理。
     */
    public boolean sendKey(int usage) {
        return sendKey(usage, HidConst.MOD_NONE);
    }

    /**
     * 🆕 TASK-061：**带修饰键**的按键（down → 30ms → up，异步抬手）。
     *
     * <p>修饰键落在 8 字节 payload 的**第 0 字节**（bit 位图，见 {@code HidConst.MOD_*}）；
     * 抬手时整包清零 —— 与既有 {@link #sendKey(int)} 同一套报文结构，**零协议改动**。
     *
     * @param usage    HID 键盘 usage（{@code HidConst.KEY_*}）
     * @param modifier HID 修饰位图（{@code HidConst.MOD_*}）；0 = 无
     * @return true = down 已受理
     */
    public boolean sendKey(int usage, int modifier) {
        final BluetoothHidDevice hid = mHid;
        final BluetoothDevice host = mHost;
        if (hid == null || host == null) {
            Log.w(TAG, "sendKey 跳过：未连接（hid=" + (hid != null) + " host=" + (host != null) + "）");
            return false;
        }
        byte[] down = new byte[] { (byte) modifier, 0, (byte) usage, 0, 0, 0, 0, 0 };
        try {
            boolean ok = hid.sendReport(host, 1, down);
            Log.i(TAG, "sendReport key=0x" + Integer.toHexString(usage)
                    + " mod=0x" + Integer.toHexString(modifier) + " down=" + ok);
            // 200ms 后 up（避免过长占用；调用方通常是设置页/服务线程，用 Handler 由调用方处更好，
            // 此处为最小实现用后台线程）
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Thread.sleep(30);
                        hid.sendReport(host, 1, new byte[] { 0, 0, 0, 0, 0, 0, 0, 0 });
                    } catch (Throwable ignored) { }
                }
            }, "HidLink-up").start();
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "sendReport 异常 " + t);
            return false;
        }
    }

    /**
     * 🆕 TASK-061：**同步**发一次按键（down → holdMs → up），**阻塞调用线程**。
     *
     * <p>为什么需要它：{@link #sendKey(int, int)} 每键都另起一条 `HidLink-up` 线程且**不等它结束**，
     * 批量打字（整段发送）会 (a) 并发起几十条线程、(b) down/up 顺序不可控 ⇒ 丢键。
     * 本方法把 down+up 收进**调用线程**内完成，调用方按可调间隔串行调用即可。
     *
     * <p>🔴 必须在**非主线程**调用（会 sleep）。
     *
     * @param usage    HID 键盘 usage
     * @param modifier HID 修饰位图（{@code HidConst.MOD_*}）
     * @param holdMs   down 与 up 之间的保持时长（钳到 ≥4ms；太小对端可能来不及识别）
     * @return true = down 已受理
     */
    public boolean sendKeyBlocking(int usage, int modifier, int holdMs) {
        final BluetoothHidDevice hid = mHid;
        final BluetoothDevice host = mHost;
        if (hid == null || host == null) {
            return false;
        }
        try {
            boolean ok = hid.sendReport(host, 1,
                    new byte[] { (byte) modifier, 0, (byte) usage, 0, 0, 0, 0, 0 });
            int hold = holdMs < 4 ? 4 : holdMs;
            try {
                Thread.sleep(hold);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            hid.sendReport(host, 1, new byte[] { 0, 0, 0, 0, 0, 0, 0, 0 });
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "sendKeyBlocking 异常 " + t);
            return false;
        }
    }
}
