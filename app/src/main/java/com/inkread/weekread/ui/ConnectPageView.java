package com.inkread.weekread.ui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.inkread.weekread.R;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.remote.HidKeepAliveService;
import com.inkread.weekread.remote.HidLink;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * V1.2.0-beta · 遥控台「连接」页（TASK-040，自绘）。
 *
 * <p>把"连不上怎么办"从**玄学**变成**可自助排障**（竞品洞察）：一页给出
 * <b>系统层自检 + 本机蓝牙信息 + 已配对设备 + 实时连接日志 + 可被发现 / 电池白名单入口 + 断链恢复引导</b>。
 *
 * <p>🔴 **零新增权限**：已配对设备读 {@code getBondedDevices()}（复用已授的 {@code BLUETOOTH_CONNECT}）；
 * 可被发现走 {@code ACTION_REQUEST_DISCOVERABLE}（TASK-035 既有路径）；电池白名单走
 * {@code ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS}（**跳系统列表页**，**不需要** {@code REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}）。
 * <p>🔴 **隐私口径**：本机蓝牙名 / 地址**只在本机显示、绝不外发**（无任何网络请求）。
 * <p>🔴 **重连只能由墨水屏发起**（{@code TASK-036}/`验证记录/131` 已定论）⇒ 设备行不给"手机主动连"，
 * 只**如实提示**去墨水屏点连接。
 * <p>🔴 尽量**纯 framework 组合**（无 AndroidX / 无第三方），仅用 {@link InkTheme} 着色 —— 符合 docs/09 "渐进式自绘"。
 */
public class ConnectPageView extends LinearLayout {

    /** 交互回调：请求「让本机可被发现」（由宿主 Activity 发 startActivityForResult）。 */
    public interface Listener {
        void onRequestDiscoverable();
    }

    /** 内存环形缓冲（进程内共享，跨页面重建保留）——**不外发**。 */
    private static final int LOG_MAX = 40;
    private static final ArrayDeque<String> sLog = new ArrayDeque<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Listener listener;
    private boolean attached = false;

    private TextView tvSelfCheck;
    private TextView tvLocalBt;
    private LinearLayout llBonded;
    private TextView tvLog;
    private TextView tvGuide;

    /**
     * HID 状态订阅（🆕 TASK-040：用 `HidKeepAliveService` 的**多监听器**机制——与设置页/遥控台并存不互顶）。
     * 回调可能在 binder 线程 ⇒ 记日志后 post 到主线程刷 UI。
     */
    private final HidKeepAliveService.StateListener mHidListener = new HidKeepAliveService.StateListener() {
        @Override
        public void onHidStateChanged() {
            log(stateSummary());
            post(new Runnable() {
                @Override
                public void run() {
                    refreshLog();
                    refresh();
                }
            });
        }
    };

    public ConnectPageView(Context c) {
        this(c, null);
    }

    public ConnectPageView(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(VERTICAL);
        int pad = (int) InkTheme.dp(c, 16f);
        setPadding(pad, pad, pad, pad);
        buildUi();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ── 构建（一次性）──

    private void buildUi() {
        // ① 系统层自检
        LinearLayout c1 = card(R.string.console_conn_selfcheck);
        tvSelfCheck = body();
        c1.addView(tvSelfCheck);

        // ② 本机蓝牙
        LinearLayout c2 = card(R.string.console_conn_local);
        tvLocalBt = body();
        c2.addView(tvLocalBt);

        // ③ 已配对设备
        LinearLayout c3 = card(R.string.console_conn_bonded);
        llBonded = new LinearLayout(getContext());
        llBonded.setOrientation(VERTICAL);
        c3.addView(llBonded);
        c3.addView(button(R.string.console_conn_refresh, new OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshBonded();
            }
        }));

        // ④ 实时连接日志
        LinearLayout c4 = card(R.string.console_conn_log);
        tvLog = body();
        tvLog.setTypeface(InkTheme.mono());
        tvLog.setTextSize(12f);
        c4.addView(tvLog);

        // ⑤ 快捷入口（可被发现 / 电池白名单）
        LinearLayout c5 = card(R.string.console_conn_entries);
        c5.addView(button(R.string.console_conn_discoverable, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onRequestDiscoverable();
            }
        }));
        c5.addView(note(R.string.lab_bt_discoverable_tip));
        c5.addView(button(R.string.console_conn_battery, new OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatteryWhitelist();
            }
        }));
        c5.addView(note(R.string.console_conn_battery_tip));

        // ⑥ 引导（配对 / 断链恢复，按状态择一显示）
        LinearLayout c6 = card(R.string.console_conn_guide);
        tvGuide = body();
        c6.addView(tvGuide);

        refresh();
    }

    // ── 生命周期：只在挂载期订阅 ──

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!attached) {
            attached = true;
            HidKeepAliveService.addListener(mHidListener);
            log(getContext().getString(R.string.console_conn_log_open));
        }
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (attached) {
            attached = false;
            HidKeepAliveService.removeListener(mHidListener);
        }
    }

    // ── 刷新 ──

    /** 按**服务真值**刷新整页（不读偏好）。 */
    public void refresh() {
        Context c = getContext();
        HidLink hid = HidKeepAliveService.link();
        boolean running = HidKeepAliveService.isRunning() && hid != null;
        boolean connected = running && hid.isHostConnected();
        boolean noProfile = running && hid != null && hid.profileUnavailable();

        // ① 自检
        StringBuilder sb = new StringBuilder();
        sb.append(c.getString(R.string.console_conn_hid_cap))
                .append("：")
                .append(HidLink.supported()
                        ? (noProfile ? c.getString(R.string.console_conn_cap_rom_no) : c.getString(R.string.console_conn_cap_ok))
                        : c.getString(R.string.console_conn_cap_sdk_no))
                .append('\n');
        sb.append(c.getString(R.string.console_conn_reg)).append("：")
                .append(running ? c.getString(R.string.console_conn_reg_yes) : c.getString(R.string.console_conn_reg_no))
                .append('\n');
        sb.append(c.getString(R.string.console_conn_link)).append("：").append(stateText(c, running, connected, hid, noProfile));
        tvSelfCheck.setText(sb.toString());

        // ② 本机蓝牙（仅本机显示）
        tvLocalBt.setText(localBtText(c));

        // ③ 设备列表
        refreshBonded();

        // ④ 日志
        refreshLog();

        // ⑤ 引导：按**真实态**给"下一步该做什么"（🔴 未启用时绝不谎报"已连上"）
        Context c2 = getContext();
        String guide;
        if (!HidLink.supported() || noProfile) {
            guide = c2.getString(R.string.console_conn_guide_no_profile);
        } else if (!running) {
            guide = c2.getString(CardPrefs.isBtControlEnabled(c2)
                    ? R.string.console_conn_guide_starting : R.string.console_conn_guide_off);
        } else if (connected) {
            guide = c2.getString(R.string.console_conn_guide_none);
        } else {
            guide = c2.getString(hid != null && hid.everConnected()
                    ? R.string.lab_bt_reconnect_guide : R.string.lab_bt_pair_guide);
        }
        tvGuide.setText(guide);
    }

    private String stateText(Context c, boolean running, boolean connected, HidLink hid, boolean noProfile) {
        if (!running || noProfile) return c.getString(R.string.lab_bt_status_off);
        if (!connected) {
            return c.getString(hid != null && hid.everConnected()
                    ? R.string.lab_bt_status_lost : R.string.lab_bt_status_registered);
        }
        return c.getString(R.string.lab_bt_status_connected, HidLink.safeName(hid.host()));
    }

    /** 概览一行（用于日志）。 */
    private String stateSummary() {
        Context c = getContext();
        HidLink hid = HidKeepAliveService.link();
        boolean running = HidKeepAliveService.isRunning() && hid != null;
        boolean connected = running && hid.isHostConnected();
        return stateText(c, running, connected, hid, running && hid.profileUnavailable());
    }

    /** 本机蓝牙名 / 地址（🔴 仅本机显示，不外发）。 */
    private String localBtText(Context c) {
        BluetoothAdapter a = null;
        try {
            a = BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable ignored) {
        }
        if (a == null) return c.getString(R.string.console_conn_no_adapter);

        String name = null, addr = null;
        boolean on = false;
        try {
            on = a.isEnabled();
            if (on) {
                name = a.getName();
                addr = a.getAddress();
            }
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append(c.getString(R.string.console_conn_bt_name)).append("：")
                .append(name == null || name.length() == 0 ? "—" : name).append('\n');
        sb.append(c.getString(R.string.console_conn_bt_addr)).append("：")
                .append(addr == null || addr.length() == 0 ? "—" : addr).append('\n');
        sb.append(c.getString(R.string.console_conn_bt_state)).append("：")
                .append(on ? c.getString(R.string.console_conn_bt_on)
                        : c.getString(R.string.console_conn_bt_off));
        sb.append('\n').append(c.getString(R.string.console_conn_bt_privacy));
        return sb.toString();
    }

    /** 已配对设备列表（含每行"去墨水屏连接"提示）。 */
    private void refreshBonded() {
        Context c = getContext();
        if (llBonded == null) return;
        llBonded.removeAllViews();

        BluetoothAdapter a = null;
        Set<BluetoothDevice> bonded = null;
        try {
            a = BluetoothAdapter.getDefaultAdapter();
            if (a != null && a.isEnabled()) bonded = a.getBondedDevices();
        } catch (Throwable ignored) {
        }

        if (bonded == null || bonded.isEmpty()) {
            llBonded.addView(readonly(a == null
                    ? c.getString(R.string.console_conn_no_adapter)
                    : c.getString(R.string.console_conn_bonded_empty)));
            return;
        }
        for (BluetoothDevice d : bonded) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setPadding(0, (int) InkTheme.dp(c, 6f), 0, (int) InkTheme.dp(c, 6f));

            TextView t = new TextView(c);
            t.setText(HidLink.safeName(d));
            t.setTextColor(InkTheme.INK);
            t.setTextSize(14f);
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            row.addView(t, lp);

            final String name = HidLink.safeName(d);
            TextView btn = new TextView(c);
            btn.setText(R.string.console_conn_how_connect);
            btn.setTextColor(InkTheme.BAMBOO);
            btn.setTextSize(13f);
            btn.setPadding((int) InkTheme.dp(c, 10f), (int) InkTheme.dp(c, 6f),
                    (int) InkTheme.dp(c, 10f), (int) InkTheme.dp(c, 6f));
            btn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 🔴 手机不能主动连（TASK-036 已定论）⇒ 如实引导去墨水屏侧
                    Toast.makeText(getContext(),
                            getContext().getString(R.string.console_conn_reconnect_hint, name),
                            Toast.LENGTH_LONG).show();
                }
            });
            row.addView(btn);
            llBonded.addView(row);
        }
    }

    private void refreshLog() {
        if (tvLog == null) return;
        StringBuilder sb = new StringBuilder();
        synchronized (sLog) {
            if (sLog.isEmpty()) {
                sb.append(getContext().getString(R.string.console_conn_log_empty));
            } else {
                for (String line : sLog) sb.append(line).append('\n');
            }
        }
        tvLog.setText(sb.toString());
    }

    /** 电池优化白名单入口：**跳系统列表页**（零权限，不申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。 */
    private void openBatteryWhitelist() {
        Context c = getContext();
        try {
            Intent it = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
        } catch (Throwable t1) {
            try {
                Intent it2 = new Intent(Settings.ACTION_SETTINGS);
                it2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(it2);
                Toast.makeText(c, R.string.console_conn_battery_fallback, Toast.LENGTH_LONG).show();
            } catch (Throwable t2) {
                Toast.makeText(c, R.string.console_conn_battery_fail, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ── 日志缓冲 ──

    private static void log(String text) {
        if (text == null) return;
        String line;
        synchronized (TS) {
            line = TS.format(new Date()) + "  " + text;
        }
        synchronized (sLog) {
            sLog.addFirst(line);
            while (sLog.size() > LOG_MAX) sLog.removeLast();
        }
    }

    // ── 小部件工厂 ──

    /** 一张分组卡（paper2 底 + line 描边 + 14dp 圆角 + 16dp 内边距），带衬线标题。 */
    private LinearLayout card(int titleRes) {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(InkTheme.PAPER2);
        bg.setCornerRadius(InkTheme.dp(c, 14f));
        bg.setStroke(Math.max(1, (int) InkTheme.dp(c, 0.5f)), InkTheme.LINE);
        box.setBackground(bg);
        int p = (int) InkTheme.dp(c, 16f);
        box.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) InkTheme.dp(c, 12f);
        box.setLayoutParams(lp);

        TextView title = new TextView(c);
        title.setText(titleRes);
        title.setTextColor(InkTheme.INK);
        title.setTextSize(16f);
        title.setTypeface(InkTheme.serif());
        title.setPadding(0, 0, 0, (int) InkTheme.dp(c, 8f));
        box.addView(title);
        addView(box);
        return box;
    }

    private TextView body() {
        TextView tv = new TextView(getContext());
        tv.setTextColor(InkTheme.INK2);
        tv.setTextSize(14f);
        tv.setLineSpacing(0f, 1.5f);
        return tv;
    }

    private TextView readonly(String s) {
        TextView tv = new TextView(getContext());
        tv.setText(s);
        tv.setTextColor(InkTheme.INK3);
        tv.setTextSize(13f);
        tv.setLineSpacing(0f, 1.4f);
        return tv;
    }

    private TextView note(int res) {
        TextView tv = new TextView(getContext());
        tv.setText(res);
        tv.setTextColor(InkTheme.INK3);
        tv.setTextSize(12f);
        tv.setLineSpacing(0f, 1.3f);
        tv.setPadding(0, (int) InkTheme.dp(getContext(), 2f), 0, (int) InkTheme.dp(getContext(), 6f));
        return tv;
    }

    /** 描边按钮（docs/09 §5.6）：line 描边 + paper 底 + ink 字。 */
    private TextView button(int res, OnClickListener l) {
        Context c = getContext();
        TextView tv = new TextView(c);
        tv.setText(res);
        tv.setTextColor(InkTheme.INK);
        tv.setTextSize(14f);
        tv.setGravity(android.view.Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(InkTheme.PAPER);
        bg.setCornerRadius(InkTheme.dp(c, 14f));
        bg.setStroke(Math.max(1, (int) InkTheme.dp(c, 1f)), InkTheme.LINE);
        tv.setBackground(bg);
        int vp = (int) InkTheme.dp(c, 12f);
        tv.setPadding(vp, vp, vp, vp);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) InkTheme.dp(c, 6f);
        tv.setLayoutParams(lp);
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }
}
