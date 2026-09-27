package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

/** 桌面卡片的用户偏好：卡片开不开 + 仅一页模式（TASK-011）+ 刷新绑定（TASK-012）。 */
public final class CardPrefs {

    /** 默认开：装好、配好 Key 就该看见卡片，别让用户再去别处找开关 */
    public static final boolean DEFAULT_ENABLED = true;

    /** 桌面只有一页时打开：让位链里的"翻页让位"整路不参与（TASK-011，v0.8.0） */
    public static final boolean DEFAULT_SINGLE_PAGE_MODE = false;

    // ── TASK-012：刷新绑定（手动点「更新于…」时，按勾选一并补发其它形态）──

    /** 位掩码：补发「本周」 */
    public static final int BIND_WEEK = 1;
    /** 位掩码：补发「本月」 */
    public static final int BIND_MONTH = 2;
    /** 位掩码：补发「本书」 */
    public static final int BIND_BOOK = 4;
    /** 默认绑定 = 本周 + 本书（进度/封面数据常看，值得每次顺手带上） */
    public static final int DEFAULT_BIND_TARGETS = BIND_WEEK | BIND_BOOK;

    // ── TASK-014：本月呈现方式（打卡网格 ↔ 阅读热力图）──

    /** 本月呈现 = 打卡网格（v0.8.0 及以前的形态：实心黑块 + 白对勾） */
    public static final int MONTH_STYLE_CHECKIN = 0;
    /** 本月呈现 = 阅读热力图（5 档黑度网点，TASK-014） */
    public static final int MONTH_STYLE_HEATMAP = 1;

    /**
     * 默认呈现 = **热力图**（用户 2026-09-27 拍板：「热力图开发完成后，默认值改为热力图」）。
     *
     * ⚠️ 这**改变首装/未设置用户的默认观感** —— 默认不再是 v0.8.0 的打卡网格。
     * 「打卡模式」作为可选项保留（设置页单选），切过去与 v0.8.0 **逐像素一致**。
     */
    public static final int DEFAULT_MONTH_STYLE = MONTH_STYLE_HEATMAP;

    private CardPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean("card_enabled", DEFAULT_ENABLED);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("card_enabled", v).commit();
    }

    /** 仅一页模式（opt-in，默认关）：开着时翻页不再触发让位，见 OverlayController.applyVisibility */
    public static boolean isSinglePageMode(Context c) {
        return sp(c).getBoolean("single_page_mode", DEFAULT_SINGLE_PAGE_MODE);
    }

    public static void setSinglePageMode(Context c, boolean v) {
        sp(c).edit().putBoolean("single_page_mode", v).commit();
    }

    /**
     * 刷新绑定（TASK-012）：手动刷新时一并补发哪些形态，{@link #BIND_WEEK} 等的位或。
     * 0 = 全不勾 → 刷新退回"只拉当前形态"的旧行为。
     */
    public static int getBindTargets(Context c) {
        return sp(c).getInt("bind_targets", DEFAULT_BIND_TARGETS);
    }

    public static void setBindTargets(Context c, int v) {
        sp(c).edit().putInt("bind_targets", v).commit();
    }

    /**
     * 本月呈现方式（TASK-014）：{@link #MONTH_STYLE_CHECKIN} / {@link #MONTH_STYLE_HEATMAP}。
     * 默认 {@link #DEFAULT_MONTH_STYLE}（= 热力图）。只影响本月形态的格子画法。
     */
    public static int getMonthStyle(Context c) {
        return sp(c).getInt("month_style", DEFAULT_MONTH_STYLE);
    }

    public static void setMonthStyle(Context c, int v) {
        sp(c).edit().putInt("month_style", v).commit();
    }
}
