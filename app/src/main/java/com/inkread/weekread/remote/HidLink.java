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
    private BluetoothHidDevice mHid;
    private BluetoothDevice mHost;         // 当前 HID 主机（= 墨水屏）
    private volatile boolean mRegistered;
    private volatile boolean mRunning;
    private Listener mListener;

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
    }

    private final BluetoothProfile.ServiceListener mProxy = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            Log.i(TAG, "proxyConnected profile=" + profile);
            if (profile == PROFILE_HID_DEVICE && proxy instanceof BluetoothHidDevice) {
                mHid = (BluetoothHidDevice) proxy;
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
                Log.i(TAG, "onAppStatusChanged registered=" + reg
                        + " dev=" + (d == null ? "null" : d.getAddress()));
                Listener l = mListener;
                if (l != null) l.onRegistered(reg, d);
            }
            @Override public void onConnectionStateChanged(BluetoothDevice d, int state) {
                Log.i(TAG, "onConnectionStateChanged "
                        + (d == null ? "null" : d.getAddress()) + " state=" + state);
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    mHost = d;
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
        final BluetoothHidDevice hid = mHid;
        final BluetoothDevice host = mHost;
        if (hid == null || host == null) {
            Log.w(TAG, "sendKey 跳过：未连接（hid=" + (hid != null) + " host=" + (host != null) + "）");
            return false;
        }
        byte[] down = new byte[] { 0, 0, (byte) usage, 0, 0, 0, 0, 0 };
        try {
            boolean ok = hid.sendReport(host, 1, down);
            Log.i(TAG, "sendReport key=0x" + Integer.toHexString(usage) + " down=" + ok);
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
}
