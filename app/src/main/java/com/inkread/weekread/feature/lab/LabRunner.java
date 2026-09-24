package com.inkread.weekread.feature.lab;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 设备能力自检的**编排**（TASK-000）。
 *
 * 职责只有三件：串行跑「1 项默认桌面 + 5 项能力」→ 汇总成一段**纯文本** → 回到主线程交付。
 *
 * 为什么要下后台线程：④ 蓝牙项要等一个最多 400ms 的回调，
 * 放在主线程会在墨水屏上直接卡顿（卡的验收要求「无感知闪烁」）。
 *
 * 🔴 只读性质由各探针自己保证（见 {@link CapabilityProbes} 的类注释）；
 *   本类额外保证：**不写文件、不改设置、不轮询** —— 跑完就完，连接口都不留。
 */
public final class LabRunner {

    /** 一轮自检的结果 */
    public interface Callback {
        /**
         * @param text    汇总后的纯文本（直接可复制）
         * @param results 5 项能力结论（顺序 = 卡的编号 1..5）
         * @param home    默认桌面探测结果（常显行）
         */
        void onDone(String text, List<ProbeResult> results, DefaultHomeProbe.Result home);
    }

    private LabRunner() { }

    /** 跑一轮。回调一定发生在**主线程**。 */
    public static void run(Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ProbeResult> rs = new ArrayList<ProbeResult>();
                // 逐项独立：某一项内部崩了也只影响它自己（各探针已自带 try/catch，
                // 这里再加一层兜底，保证「5 项一定齐」——页面不该因缺项而错位）
                safely(rs, new Call() {
                    @Override public ProbeResult go() { return CapabilityProbes.volumeKeys(app); }
                }, 1, "物理音量键");
                safely(rs, new Call() {
                    @Override public ProbeResult go() { return CapabilityProbes.keyFilter(app); }
                }, 2, "ROM 是否放行按键过滤");
                safely(rs, new Call() {
                    @Override public ProbeResult go() { return CapabilityProbes.wirelessAdb(app); }
                }, 3, "无线调试");
                safely(rs, new Call() {
                    @Override public ProbeResult go() { return CapabilityProbes.bluetoothHid(app); }
                }, 4, "蓝牙 HID Device");
                safely(rs, new Call() {
                    @Override public ProbeResult go() { return CapabilityProbes.epd(app); }
                }, 5, "EPD（墨水屏）接口");

                final DefaultHomeProbe.Result home = DefaultHomeProbe.probe(app);
                final String text = compose(app, rs, home);

                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(text, rs, home);
                    }
                });
            }
        }, "lab-probe").start();
    }

    /** 探针调用的小接口（避免用 lambda —— 本工程走 Java 8 + 匿名内部类的老实风格） */
    private interface Call {
        ProbeResult go();
    }

    /** 跑一项；抛出来也补一条「不确定」，保证结果列表始终 5 项 */
    private static void safely(List<ProbeResult> out, Call c, int no, String title) {
        try {
            ProbeResult r = c.go();
            if (r != null) { out.add(r); return; }
        } catch (Throwable t) {
            // 落到这里说明探针自身的 try/catch 都没兜住（极不可能）
        }
        ProbeResult r = new ProbeResult(no, title, ProbeResult.UNKNOWN,
                "探针未能执行 —— 无法判断（不是「不可用」）。");
        out.add(r);
    }

    /** 汇总成可复制的纯文本 */
    public static String compose(Context c, List<ProbeResult> rs, DefaultHomeProbe.Result home) {
        StringBuilder sb = new StringBuilder();
        sb.append("微读墨记 · 设备能力自检（只读）\n");
        sb.append("生成时间：").append(now()).append('\n');
        sb.append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" / Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("ABI：").append(abis()).append('\n');
        sb.append("App 版本：").append(appVer(c)).append('\n');
        sb.append("\n─── 当前系统默认桌面 ───\n");
        sb.append(DefaultHomeProbe.toText(home));
        sb.append("\n─── 五项能力 ───\n");
        for (int i = 0; i < rs.size(); i++) {
            sb.append(rs.get(i).toText()).append('\n');
        }
        sb.append("三档结论 = 可用 / 不可用 / 本次探测不确定；");
        sb.append("「探测失败」一律归入「不确定」，不冒充「不可用」。\n");
        return sb.toString();
    }

    private static String now() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        } catch (Throwable t) {
            return "(unknown)";
        }
    }

    private static String abis() {
        try {
            String[] a = Build.SUPPORTED_ABIS;
            if (a == null || a.length == 0) return "(unknown)";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(unknown)";
        }
    }

    private static String appVer(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "(unknown)";
        }
    }
}
