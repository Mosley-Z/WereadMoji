package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 🆕 TASK-077：**阅读账单的配置**（prefs 文件 `cfg`，与 `CardPrefs` / `PagePrefs` / `StatsStore` 同上）。
 *
 * <p>共 **13 组**（定稿设计 §6.5 / `tasks/TASK-077` §配置）：
 * <ol>
 *   <li>{@code menu_title} 账单标题（可空；空 ⇒ 沿用默认「墨单」）</li>
 *   <li>{@code menu_unit} 时长单位 {@link #UNIT_H}/{@link #UNIT_M}</li>
 *   <li>{@code menu_top_n} 书目 Top N（3/5/8）</li>
 *   <li>{@code menu_min_sec} 最小时长阈值（秒；配置按分钟输入 ⇒ 见 {@link #setMinMinutes}）</li>
 *   <li>{@code menu_drop_off_shelf} 去除不在书架的书</li>
 *   <li>{@code menu_note_src} 备注来源 {@link #SRC_AUTO}/{@link #SRC_MANUAL}/{@link #SRC_AUTO_FIRST}</li>
 *   <li>{@code menu_excerpt_mode} 摘录策略 {@link #EX_MINE}/{@link #EX_HOT_FILL}/{@link #EX_HOT_ONLY}/{@link #EX_ANY}</li>
 *   <li>{@code menu_show_author} / {@code menu_show_duration} / {@code menu_show_progress} 每本书显示</li>
 *   <li>{@code menu_footer} 底部 {@link #FOOT_NONE}/{@link #FOOT_NOTE}/{@link #FOOT_BARCODE}/{@link #FOOT_BOTH}</li>
 *   <li>{@code menu_blocks_mask} 子块开关（票据头 / 合计 / 备注）</li>
 *   <li>{@code menu_font_title_serif} · {@code menu_font_body_mono} 字体族（标题衬线 / 正文等宽）</li>
 *   <li>{@code menu_fs_title} · {@code menu_fs_body} · {@code menu_fs_serial} 字号三档（0=小 / 1=中 / 2=大）</li>
 *   <li>{@code menu_empty_action} 空数据处理 {@link #EMPTY_PLACEHOLDER}/{@link #EMPTY_SKIP}</li>
 * </ol>
 *
 * <p>🔴 **每本书的长期备注**单独一段（`menu_notes`，键 = `bookId`）—— 照 ReadTrace 自认的反面教材：
 * 它"按 NO.01~NO.05 **位置**存会错位"，我们**绝不**按 Top-N 序号存（`tasks/TASK-077` §配置 #9 红线）。
 *
 * <p>🔴 本类**只读配置、不发请求、不碰渲染**（账单渲染永不发请求是项目铁律）。
 */
public final class MenuPrefs {

    private static final String PREFS = "cfg";

    // ── ① 标题 ──
    private static final String K_TITLE = "menu_title";
    public static final String DEFAULT_TITLE = "墨单";

    // ── ② 时长单位 ──
    private static final String K_UNIT = "menu_unit";
    public static final String UNIT_H = "h";
    public static final String UNIT_M = "m";

    // ── ③ Top N ──
    private static final String K_TOP_N = "menu_top_n";
    /** 允许的 Top N 档位（🔴 只认这三档，越界钳到最近档）。 */
    public static final int[] TOP_N_CHOICES = { 3, 5, 8 };
    public static final int DEFAULT_TOP_N = 5;

    // ── ④ 最小时长阈值（存**秒**，配置面按分钟） ──
    private static final String K_MIN_SEC = "menu_min_sec";

    // ── ⑤ 去除不在书架的书 ──
    private static final String K_DROP_OFF_SHELF = "menu_drop_off_shelf";

    // ── ⑥ 备注来源 ──
    private static final String K_NOTE_SRC = "menu_note_src";
    /** 自动摘录（账单里预热的摘录原文）。 */
    public static final String SRC_AUTO = "auto";
    /** 手动备注（每本书长期备注）。 */
    public static final String SRC_MANUAL = "manual";
    /** 自动优先：没有摘录时用手动备注。 */
    public static final String SRC_AUTO_FIRST = "auto_first";

    // ── ⑦ 摘录策略（4 档） ──
    private static final String K_EXCERPT = "menu_excerpt_mode";
    /** 仅我的内容：个人批注 + 个人划线（无 ⇒ 留白）。 */
    public static final String EX_MINE = "mine";
    /** 热门补充：我的内容为空时用热门划线。 */
    public static final String EX_HOT_FILL = "hot_fill";
    /** 只看热门：只显示微信热门划线。 */
    public static final String EX_HOT_ONLY = "hot_only";
    /** 有啥显示啥：个人优先，其次热门 —— 两者都有时**合并显示**（见 `BillSection`）。 */
    public static final String EX_ANY = "any";

    // ── ⑧ 每本书显示 ──
    private static final String K_SHOW_AUTHOR = "menu_show_author";
    private static final String K_SHOW_DURATION = "menu_show_duration";
    private static final String K_SHOW_PROGRESS = "menu_show_progress";

    // ── ⑨ 底部 ──
    private static final String K_FOOTER = "menu_footer";
    public static final String FOOT_NONE = "none";
    public static final String FOOT_NOTE = "note";
    public static final String FOOT_BARCODE = "barcode";
    public static final String FOOT_BOTH = "barcode_note";

    // ── ⑩ 子块开关（位掩码） ──
    private static final String K_BLOCKS = "menu_blocks_mask";
    public static final int BLOCK_HEAD = 1;      // 票据头（标题 + 副行 + 表头）
    public static final int BLOCK_TOTAL = 2;     // 合计行
    public static final int BLOCK_NOTE = 4;      // 整单备注
    public static final int BLOCK_ALL = BLOCK_HEAD | BLOCK_TOTAL | BLOCK_NOTE;

    // ── ⑪ 字体族 ──
    private static final String K_FONT_TITLE_SERIF = "menu_font_title_serif";
    private static final String K_FONT_BODY_MONO = "menu_font_body_mono";

    // ── ⑫ 字号三档（0=小 1=中 2=大；实际倍率见 {@link #scaleOf}） ──
    private static final String K_FS_TITLE = "menu_fs_title";
    private static final String K_FS_BODY = "menu_fs_body";
    private static final String K_FS_SERIAL = "menu_fs_serial";
    public static final int FS_SMALL = 0;
    public static final int FS_MID = 1;
    public static final int FS_LARGE = 2;
    private static final float[] FS_RATIO = { 0.8f, 1.0f, 1.2f };

    // ── ⑬ 空数据处理 ──
    private static final String K_EMPTY = "menu_empty_action";
    /** 生成占位（写"本期无阅读记录"）—— 🔴 默认，绝不产 0 值账单。 */
    public static final String EMPTY_PLACEHOLDER = "placeholder";
    /** 不生成。 */
    public static final String EMPTY_SKIP = "skip";

    // ── 每本书长期备注（另段：段名 `menu_notes`，键 = bookId） ──
    private static final String NOTES_PREFS = "menu_notes";

    // ── 整单备注（🔴 **内部状态**，非 13 项配置）──
    //    票据尾那行「整单备注：…」的正文；口径照 ReadTrace 的 `noteText`（**全局一句**，可空）。
    //    默认空 ⇒ 该行不画（卡面标注"可空"）。§13 项配置里只有"底部/子块"两个**显示开关**，
    //    并不含这行正文本身，所以它落在配置之外。
    private static final String K_FOOTER_NOTE = "menu_footer_note";

    // ── 当前视图（🔴 **内部状态**，不属于那 13 项配置）──
    //    两菜单共用一个渲染容器，用户切到哪一档要跨"呼出/关闭"记住；
    //    默认 = 摘录菜单（Q1 ␁a）。
    private static final String K_VIEW = "menu_view";
    public static final int VIEW_EXCERPT = 0;   // 摘录菜单（默认）
    public static final int VIEW_READING = 1;   // 读书菜单
    public static final int VIEW_CALENDAR = 2;  // 🆕 TASK-077b 月历（**仅月账单可用**）

    private MenuPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences np(Context c) {
        return c.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE);
    }

    // ══════════════════════ ① 标题 ══════════════════════

    /** 账单标题（空 ⇒ 用 {@link #DEFAULT_TITLE}）。 */
    public static String title(Context c) {
        String v = sp(c).getString(K_TITLE, DEFAULT_TITLE);
        if (v == null) return DEFAULT_TITLE;
        v = v.trim();
        return v.length() == 0 ? DEFAULT_TITLE : v;
    }

    /** 原样取（含空串）—— 设置页回填用。 */
    public static String rawTitle(Context c) {
        String v = sp(c).getString(K_TITLE, DEFAULT_TITLE);
        return v == null ? "" : v;
    }

    public static void setTitle(Context c, String v) {
        sp(c).edit().putString(K_TITLE, v == null ? "" : v.trim()).commit();
    }

    // ══════════════════════ ② 时长单位 ══════════════════════

    public static String unit(Context c) {
        String v = sp(c).getString(K_UNIT, UNIT_H);
        return UNIT_M.equals(v) ? UNIT_M : UNIT_H;
    }

    public static void setUnit(Context c, String v) {
        sp(c).edit().putString(K_UNIT, UNIT_M.equals(v) ? UNIT_M : UNIT_H).commit();
    }

    // ══════════════════════ ③ Top N ══════════════════════

    /** Top N（只认 {@link #TOP_N_CHOICES} 三档；越界 ⇒ 钳到最近档）。 */
    public static int topN(Context c) {
        int v = sp(c).getInt(K_TOP_N, DEFAULT_TOP_N);
        int best = TOP_N_CHOICES[0], bestD = Math.abs(v - best);
        for (int i = 1; i < TOP_N_CHOICES.length; i++) {
            int d = Math.abs(v - TOP_N_CHOICES[i]);
            if (d < bestD) { bestD = d; best = TOP_N_CHOICES[i]; }
        }
        return best;
    }

    public static void setTopN(Context c, int v) {
        sp(c).edit().putInt(K_TOP_N, v).commit();
    }

    // ══════════════════════ ④ 最小时长阈值 ══════════════════════

    /** 最小时长阈值（**秒**；≤0 = 不过滤）。 */
    public static int minSec(Context c) {
        int v = sp(c).getInt(K_MIN_SEC, 0);
        return v < 0 ? 0 : v;
    }

    /** 配置面按**分钟**读写（内部存秒）。 */
    public static int minMinutes(Context c) {
        return minSec(c) / 60;
    }

    public static void setMinMinutes(Context c, int minutes) {
        int m = minutes < 0 ? 0 : minutes;
        sp(c).edit().putInt(K_MIN_SEC, m * 60).commit();
    }

    // ══════════════════════ ⑤ 去除不在书架的书 ══════════════════════

    public static boolean dropOffShelf(Context c) {
        return sp(c).getBoolean(K_DROP_OFF_SHELF, true);
    }

    public static void setDropOffShelf(Context c, boolean v) {
        sp(c).edit().putBoolean(K_DROP_OFF_SHELF, v).commit();
    }

    // ══════════════════════ ⑥ 备注来源 ══════════════════════

    public static String noteSrc(Context c) {
        String v = sp(c).getString(K_NOTE_SRC, SRC_AUTO_FIRST);
        if (SRC_MANUAL.equals(v) || SRC_AUTO.equals(v)) return v;
        return SRC_AUTO_FIRST;
    }

    public static void setNoteSrc(Context c, String v) {
        if (!SRC_MANUAL.equals(v) && !SRC_AUTO.equals(v)) v = SRC_AUTO_FIRST;
        sp(c).edit().putString(K_NOTE_SRC, v).commit();
    }

    // ══════════════════════ ⑦ 摘录策略 ══════════════════════

    public static String excerptMode(Context c) {
        String v = sp(c).getString(K_EXCERPT, EX_MINE);
        if (EX_HOT_FILL.equals(v) || EX_HOT_ONLY.equals(v) || EX_ANY.equals(v)) return v;
        return EX_MINE;
    }

    public static void setExcerptMode(Context c, String v) {
        if (!EX_HOT_FILL.equals(v) && !EX_HOT_ONLY.equals(v) && !EX_ANY.equals(v)) v = EX_MINE;
        sp(c).edit().putString(K_EXCERPT, v).commit();
    }

    // ══════════════════════ ⑧ 每本书显示 ══════════════════════

    public static boolean showAuthor(Context c) { return sp(c).getBoolean(K_SHOW_AUTHOR, true); }
    public static boolean showDuration(Context c) { return sp(c).getBoolean(K_SHOW_DURATION, true); }
    public static boolean showProgress(Context c) { return sp(c).getBoolean(K_SHOW_PROGRESS, false); }

    public static void setShowAuthor(Context c, boolean v) { sp(c).edit().putBoolean(K_SHOW_AUTHOR, v).commit(); }
    public static void setShowDuration(Context c, boolean v) { sp(c).edit().putBoolean(K_SHOW_DURATION, v).commit(); }
    public static void setShowProgress(Context c, boolean v) { sp(c).edit().putBoolean(K_SHOW_PROGRESS, v).commit(); }

    // ══════════════════════ ⑨ 底部 ══════════════════════

    public static String footer(Context c) {
        String v = sp(c).getString(K_FOOTER, FOOT_BOTH);
        if (FOOT_NONE.equals(v) || FOOT_NOTE.equals(v) || FOOT_BARCODE.equals(v)) return v;
        return FOOT_BOTH;
    }

    public static void setFooter(Context c, String v) {
        if (!FOOT_NONE.equals(v) && !FOOT_NOTE.equals(v) && !FOOT_BARCODE.equals(v)) v = FOOT_BOTH;
        sp(c).edit().putString(K_FOOTER, v).commit();
    }

    // ══════════════════════ ⑩ 子块开关 ══════════════════════

    /** 子块开关掩码（🔴 `& BLOCK_ALL`；**0 合法** —— 用户可以把三个子块全关）。 */
    public static int blocks(Context c) {
        return sp(c).getInt(K_BLOCKS, BLOCK_ALL) & BLOCK_ALL;
    }

    public static void setBlocks(Context c, int mask) {
        sp(c).edit().putInt(K_BLOCKS, mask & BLOCK_ALL).commit();
    }

    public static boolean blockOn(Context c, int bit) {
        return (blocks(c) & bit) != 0;
    }

    public static void setBlockOn(Context c, int bit, boolean on) {
        int m = blocks(c);
        setBlocks(c, on ? (m | bit) : (m & ~bit));
    }

    // ══════════════════════ ⑪ 字体族 ══════════════════════

    public static boolean titleSerif(Context c) { return sp(c).getBoolean(K_FONT_TITLE_SERIF, true); }
    public static boolean bodyMono(Context c) { return sp(c).getBoolean(K_FONT_BODY_MONO, true); }

    public static void setTitleSerif(Context c, boolean v) { sp(c).edit().putBoolean(K_FONT_TITLE_SERIF, v).commit(); }
    public static void setBodyMono(Context c, boolean v) { sp(c).edit().putBoolean(K_FONT_BODY_MONO, v).commit(); }

    // ══════════════════════ ⑫ 字号三档 ══════════════════════

    private static int fs(Context c, String k) {
        int v = sp(c).getInt(k, FS_MID);
        if (v < FS_SMALL) v = FS_SMALL;
        if (v > FS_LARGE) v = FS_LARGE;
        return v;
    }

    /** 标题档位（0/1/2）。 */
    public static int fsTitle(Context c) { return fs(c, K_FS_TITLE); }
    /** 正文档位（0/1/2）。 */
    public static int fsBody(Context c) { return fs(c, K_FS_BODY); }
    /** 单号档位（0/1/2）。 */
    public static int fsSerial(Context c) { return fs(c, K_FS_SERIAL); }

    public static void setFsTitle(Context c, int v) { sp(c).edit().putInt(K_FS_TITLE, clampFs(v)).commit(); }
    public static void setFsBody(Context c, int v) { sp(c).edit().putInt(K_FS_BODY, clampFs(v)).commit(); }
    public static void setFsSerial(Context c, int v) { sp(c).edit().putInt(K_FS_SERIAL, clampFs(v)).commit(); }

    /** 档位 → 倍率（0.8 / 1.0 / 1.2）。 */
    public static float scaleOf(int level) {
        int i = clampFs(level);
        return FS_RATIO[i];
    }

    private static int clampFs(int v) {
        if (v < FS_SMALL) return FS_SMALL;
        if (v > FS_LARGE) return FS_LARGE;
        return v;
    }

    // ══════════════════════ ⑬ 空数据处理 ══════════════════════

    public static String emptyAction(Context c) {
        return EMPTY_SKIP.equals(sp(c).getString(K_EMPTY, EMPTY_PLACEHOLDER))
                ? EMPTY_SKIP : EMPTY_PLACEHOLDER;
    }

    public static void setEmptyAction(Context c, String v) {
        sp(c).edit().putString(K_EMPTY,
                EMPTY_SKIP.equals(v) ? EMPTY_SKIP : EMPTY_PLACEHOLDER).commit();
    }

    // ══════════════════════ 每本书长期备注（按 bookId） ══════════════════════

    /** 取某本书的长期备注（无 ⇒ ""）。 */
    public static String noteOf(Context c, String bookId) {
        if (bookId == null || bookId.length() == 0) return "";
        String v = np(c).getString(bookId, "");
        return v == null ? "" : v;
    }

    public static void setNoteOf(Context c, String bookId, String note) {
        if (bookId == null || bookId.length() == 0) return;
        String v = note == null ? "" : note.trim();
        if (v.length() == 0) np(c).edit().remove(bookId).commit();
        else np(c).edit().putString(bookId, v).commit();
    }

    /** 写过备注的书本数（设置页提示用）。 */
    public static int noteCount(Context c) {
        try {
            return np(c).getAll().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ══════════════════════ 整单备注（内部状态 · 非 13 项配置） ══════════════════════

    /** 票据尾「整单备注：…」的正文（**全局一句**，默认空 ⇒ 该行不画）。 */
    public static String footerNote(Context c) {
        String v = sp(c).getString(K_FOOTER_NOTE, "");
        return v == null ? "" : v;
    }

    public static void setFooterNote(Context c, String v) {
        sp(c).edit().putString(K_FOOTER_NOTE, v == null ? "" : v.trim()).commit();
    }

    // ══════════════════════ 当前视图（内部状态 · 非 13 项配置） ══════════════════════
    /** 当前菜单视图（{@link #VIEW_EXCERPT} / {@link #VIEW_READING} / {@link #VIEW_CALENDAR}）。 */
    public static int view(Context c) {
        int v = sp(c).getInt(K_VIEW, VIEW_EXCERPT);
        return (v == VIEW_READING || v == VIEW_CALENDAR) ? v : VIEW_EXCERPT;
    }

    public static void setView(Context c, int v) {
        int nv = (v == VIEW_READING || v == VIEW_CALENDAR) ? v : VIEW_EXCERPT;
        sp(c).edit().putInt(K_VIEW, nv).commit();
    }

    /**
     * 三视图的显示名（顺序 = `view` 的取值 0/1/2；墨台切换条与设置页共用同一份文案）。
     * 🔴 第 3 项「月历」**只在月账单**出现（周账单下不显示，见 `TASK-077b` B1）。
     */
    public static final String[] VIEW_LABELS = { "摘录菜单", "读书菜单", "月历" };

    // ══════════════════════ 🆕 周期选择（内部状态 · 非 13 项配置） ══════════════════════
    //
    // 2026-10-10 用户诉求：「墨单可以在墨台界面便捷地切换周/月并选择周期」。
    // 墨单头部那条 `◀ 2026-W40 ▶` 选的是**看哪一期**；`[周|月]` 选的是**粒度**。
    //
    // 🔴 **缺省 = 未选**（键不存在）⇒ 渲染回落 `BillStore.latest(c)` —— 与改动前**逐字节一致**
    //    （老用户零差异）。用户一旦点过就记住 (mode, start) 这一对，跨"呼出/关闭"生效。
    // 🔴 **只记"看哪一期"、不触发生成**：不在库里的期（`startsOf` 之外）根本选不到
    //    （导航箭头到边界即灰），所以永远不会出现"选了一期没有的账单"。

    private static final String K_P_MODE  = "menu_period_mode";
    private static final String K_P_START = "menu_period_start";

    /** 已选周期类型；`""` = 未选（跟随最新一期）。 */
    public static String periodMode(Context c) {
        String v = sp(c).getString(K_P_MODE, "");
        if (v == null) return "";
        return (PeriodRange.WEEKLY.equals(v) || PeriodRange.MONTHLY.equals(v)) ? v : "";
    }

    /** 已选周期起点（秒）；0 = 未选（跟随最新一期）。 */
    public static long periodStart(Context c) {
        long v = sp(c).getLong(K_P_START, 0L);
        return v > 0L ? v : 0L;
    }

    /** 记住用户选的这一期（mode 只认周/月，别的值钳成周）。 */
    public static void setPeriodSel(Context c, String mode, long start) {
        if (start <= 0L) { clearPeriodSel(c); return; }
        String m = PeriodRange.MONTHLY.equals(mode) ? PeriodRange.MONTHLY : PeriodRange.WEEKLY;
        sp(c).edit().putString(K_P_MODE, m).putLong(K_P_START, start).commit();
    }

    /** 回到「跟随最新一期」（两条键一起删 —— 只删一条会留下"半选"脏态）。 */
    public static void clearPeriodSel(Context c) {
        sp(c).edit().remove(K_P_MODE).remove(K_P_START).commit();
    }
}
