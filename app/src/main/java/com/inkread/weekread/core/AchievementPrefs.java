package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * TASK-013 阅读成就提示的偏好（独立 prefs 文件 {@code achv}，不与 {@code cfg} 混键）。
 *
 * <p>语义（v0.9，方案见 {@code .workbuddy/artifacts/TASK-013_实现方案_待拍板.md}，已拍板 2026-09-27）：
 * <ul>
 *   <li>{@code enabled} 总开关，默认<b>关</b> —— 关闭时周/月卡片版面与 v0.8.0 逐像素一致；</li>
 *   <li>{@code week_kind} / {@code month_kind} ∈ {@link #KIND_OFF}–{@link #KIND_CUSTOM}，
 *       决定目标档位与文案口径（预设走官方口径文案，自定义走「您的目标」）；</li>
 *   <li>{@code week_min} / {@code month_min} 为自定义目标的<b>整数分钟</b>
 *       （round(小时×60)，设置页输入小时、≤2 位小数）。仅 kind=CUSTOM 时使用；
 *       ≤0 视为「未设置」→ 成就行不画（避免 0 目标恒触发「达成」）。</li>
 * </ul>
 *
 * <p>🔴 目标秒数换算集中在 {@link #targetSec}（预设四档 = 卡面定死：完美周 10h / 狂暴周 25h /
 * 完美月 40h / 狂暴月 100h）。数据来源 = 微信读书 API 累计时长（{@code totalReadTime}），
 * 与官方挑战口径可能不同 ⇒ 文案不出现「官方/挑战」字样。
 */
public final class AchievementPrefs {
    private static final String FILE = "achv";

    public static final int KIND_OFF = 0;
    public static final int KIND_PERFECT = 1;
    public static final int KIND_BERSERK = 2;
    public static final int KIND_CUSTOM = 3;

    private AchievementPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) { return sp(c).getBoolean("enabled", false); }
    public static void setEnabled(Context c, boolean v) { sp(c).edit().putBoolean("enabled", v).commit(); }

    public static int getWeekKind(Context c) { return sp(c).getInt("week_kind", KIND_OFF); }
    public static void setWeekKind(Context c, int v) { sp(c).edit().putInt("week_kind", v).commit(); }
    public static int getWeekMin(Context c) { return sp(c).getInt("week_min", 0); }
    public static void setWeekMin(Context c, int v) { sp(c).edit().putInt("week_min", v).commit(); }

    public static int getMonthKind(Context c) { return sp(c).getInt("month_kind", KIND_OFF); }
    public static void setMonthKind(Context c, int v) { sp(c).edit().putInt("month_kind", v).commit(); }
    public static int getMonthMin(Context c) { return sp(c).getInt("month_min", 0); }
    public static void setMonthMin(Context c, int v) { sp(c).edit().putInt("month_min", v).commit(); }

    /**
     * 该形态的目标秒数；返回 {@code <=0} = 没有有效目标（成就行不画）。kind=OFF 一律返回 0。
     * @param week true = 周（完美 10h / 狂暴 25h / 自定义 {@code week_min}），false = 月（40h / 100h / {@code month_min}）
     */
    public static long targetSec(Context c, boolean week) {
        int kind = week ? getWeekKind(c) : getMonthKind(c);
        if (kind == KIND_PERFECT) return week ? 10L * 3600L : 40L * 3600L;
        if (kind == KIND_BERSERK) return week ? 25L * 3600L : 100L * 3600L;
        if (kind == KIND_CUSTOM) {
            int min = week ? getWeekMin(c) : getMonthMin(c);
            return min > 0 ? min * 60L : 0L;
        }
        return 0L;
    }
}
