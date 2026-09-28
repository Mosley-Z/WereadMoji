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

    // ── TASK-016：本记正文字号（**仅桌面卡片**）──
    //
    // 单位统一为「号」（1 号 = 屏高 × 0.0015 = 本设备 1.2px，口径唯一来源
    // {@code WeekCardView#UNIT_RATIO}），与 App 内本记页 / 导出图的 `sizeTier` 三档
    // **分开存** —— 用户 2026-09-26 拍板：卡片字号与导出字号是两个东西，不许混。

    /**
     * 默认字号（号）= 桌面应用名称的字号 —— `验证记录/59` 项① 上机实测
     * **字形高 20px ≈ 16.7 号** ⇒ 取 17（= 20.4px）。
     */
    public static final int NOTE_CARD_SIZE_DEFAULT = 17;

    /** 下限（号）。再小在 219dpi 的墨水屏上就糊了 */
    public static final int NOTE_CARD_SIZE_MIN = 12;

    /**
     * 上限（号）。🔴 **24 是硬边界，不是拍脑袋**：卡片档正文区
     * `bodyH = 207.31 − tSize×1.4`、`lineH = tSize×1.55`（推导见
     * `.workbuddy/artifacts/2026-09-27_TASK016-017_实现方案与待拍板.md` §1.1），
     * 24 号（28.8px）算出来**正好 3 行** = `NOTE_LINES_MIN` 的下限；
     * 再往上就会开始吃行，有压到署名行的风险。
     */
    public static final int NOTE_CARD_SIZE_MAX = 24;

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

    /**
     * 桌面卡片本记正文的字号（号）。**越界值一律钳回** `[MIN, MAX]` ——
     * 手改的 prefs / 老版本残留的值也能安全读出，不让渲染端拿到离谱的字号。
     */
    public static int getNoteCardSize(Context c) {
        int v = sp(c).getInt("note_card_size", NOTE_CARD_SIZE_DEFAULT);
        if (v < NOTE_CARD_SIZE_MIN) return NOTE_CARD_SIZE_MIN;
        if (v > NOTE_CARD_SIZE_MAX) return NOTE_CARD_SIZE_MAX;
        return v;
    }

    public static void setNoteCardSize(Context c, int v) {
        if (v < NOTE_CARD_SIZE_MIN) v = NOTE_CARD_SIZE_MIN;
        if (v > NOTE_CARD_SIZE_MAX) v = NOTE_CARD_SIZE_MAX;
        sp(c).edit().putInt("note_card_size", v).commit();
    }

    // ── TASK-018：遥控翻页（V1.0 Beta）──
    //
    // 同一 APK 装两端，remote_role 决定启用哪几个环节（docs/FEATURES/remote.md）：
    //   off（默认）= 与现状零差异；eink = 收指令注入翻页手势；phone = 捕获音量键发指令。

    /** 遥控角色：关闭（默认 —— 不开 socket、不建连，设置页只多一个角色开关） */
    public static final int REMOTE_ROLE_OFF = 0;
    /** 遥控角色：墨水屏端（Client：连手机热点网关，收指令 → 注入左右滑） */
    public static final int REMOTE_ROLE_EINK = 1;
    /** 遥控角色：手机端（Server：listen 等墨水屏连入，捕获音量键 → 发指令） */
    public static final int REMOTE_ROLE_PHONE = 2;

    public static final int DEFAULT_REMOTE_ROLE = REMOTE_ROLE_OFF;

    /** 空闲自动断开的默认秒数（显式会话 + 用完即断，按需连接策略） */
    public static final int REMOTE_IDLE_DEFAULT = 300;
    public static final int REMOTE_IDLE_MIN = 30;
    public static final int REMOTE_IDLE_MAX = 3600;

    public static int getRemoteRole(Context c) {
        int v = sp(c).getInt("remote_role", DEFAULT_REMOTE_ROLE);
        if (v < REMOTE_ROLE_OFF || v > REMOTE_ROLE_PHONE) return DEFAULT_REMOTE_ROLE;
        return v;
    }

    public static void setRemoteRole(Context c, int v) {
        if (v < REMOTE_ROLE_OFF || v > REMOTE_ROLE_PHONE) v = DEFAULT_REMOTE_ROLE;
        sp(c).edit().putInt("remote_role", v).commit();
    }

    /** 空闲超时（秒）。越界值钳回 [MIN, MAX]，与 note_card_size 同一套安全读出纪律。 */
    public static int getRemoteIdleTimeout(Context c) {
        int v = sp(c).getInt("remote_idle_timeout", REMOTE_IDLE_DEFAULT);
        if (v < REMOTE_IDLE_MIN) return REMOTE_IDLE_MIN;
        if (v > REMOTE_IDLE_MAX) return REMOTE_IDLE_MAX;
        return v;
    }

    public static void setRemoteIdleTimeout(Context c, int v) {
        if (v < REMOTE_IDLE_MIN) v = REMOTE_IDLE_MIN;
        if (v > REMOTE_IDLE_MAX) v = REMOTE_IDLE_MAX;
        sp(c).edit().putInt("remote_idle_timeout", v).commit();
    }
}
