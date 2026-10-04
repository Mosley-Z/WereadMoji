package com.inkread.weekread.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 设备侧诊断日志：同时写 logcat 和应用私有外部目录下的 card_debug.log。
 *
 * 为什么不能只靠 logcat：本机 ROM 会把第三方应用 logcat 里的 I/D 级日志吞掉
 * （实测 adb logcat 完全看不到，但桌面行为又确实不对），
 * 所以留一个文件通道，用 `adb shell cat .../files/card_debug.log` 直接看，
 * 排查"卡片该显示却没显示"这类问题非常快。
 *
 * ── 两级日志（V1.1.0 前置降噪）──
 * · {@link #note}  —— **必留痕**：回归脚本靠 `tail -1` 读的关键取证行
 *   （`visibility=` / `elaSwipe` / `swipe sub=` / `SCREEN_ON 收到` 等）与低频状态变更，
 *   **任何构建都写**。
 * · {@link #noteV} —— **verbose**：逐事件 / 逐帧刷屏的噪声行，只在 **dev 构建**
 *   （`android:debuggable="true"`，见 `tools/build.sh --dev`）写；release 构建直接丢弃。
 *   目的：避免长期运行把 card_debug.log 刷到 {@link #MAX_BYTES} 被整文件删除，
 *   反而**吃掉**真正要看的证据。
 */
public final class CardDebug {

    public static final String FILE_NAME = "card_debug.log";

    /** 单文件上限：超过就整文件重开（旧的先删），避免长期运行无限增长。 */
    private static final long MAX_BYTES = 64 * 1024;

    /** 缓存"本构建是否可调试"。noteV 高频调用，避免每次都查 ApplicationInfo。 -1=未测定。 */
    private static volatile int sVerbose = -1;

    private CardDebug() {
    }

    /** 必留痕日志：任何构建都写。 */
    public static void note(Context c, String msg) {
        write(c, msg);
    }

    /**
     * verbose 日志：**只在 dev（debuggable）构建**写，release 构建丢弃。
     * 用于逐事件 / 逐帧的高频噪声行，避免 release 用户设备上把 card_debug.log 刷爆。见类注释。
     */
    public static void noteV(Context c, String msg) {
        if (!isVerbose(c)) return;
        write(c, msg);
    }

    /** 本构建是否可调试（只测一次并缓存）。取不到时按"不可调试"处理（宁少写不多写）。 */
    private static boolean isVerbose(Context c) {
        int v = sVerbose;
        if (v >= 0) return v == 1;
        boolean flag = false;
        try {
            ApplicationInfo ai = c.getApplicationInfo();
            flag = ai != null && (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable ignored) {
        }
        sVerbose = flag ? 1 : 0;
        return flag;
    }

    private static void write(Context c, String msg) {
        Log.i("WeekReadCard", msg);
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        FileWriter w = null;
        try {
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, FILE_NAME);
            // 简单截断，避免长期运行无限增长
            if (f.length() > MAX_BYTES) f.delete();
            w = new FileWriter(f, true);
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
            w.write(ts + "  " + msg + "\n");
        } catch (Throwable ignored) {
        } finally {
            // 🔴 关流放 finally：写成败都不会漏关 fd（原先正常路径 w.close() 之外，
            //    new FileWriter 成功但 w.write 抛异常时 fd 会泄漏）。
            if (w != null) {
                try { w.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
