package com.inkread.weekread.core;

import android.content.Context;
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
 */
public final class CardDebug {

    public static final String FILE_NAME = "card_debug.log";

    private CardDebug() {
    }

    public static void note(Context c, String msg) {
        Log.i("WeekReadCard", msg);
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        try {
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, FILE_NAME);
            // 简单截断，避免长期运行无限增长
            if (f.length() > 64 * 1024) f.delete();
            FileWriter w = new FileWriter(f, true);
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
            w.write(ts + "  " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
