package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * **墨台**（桌面呼出页）的偏好落盘（prefs 文件 `cfg`，与 {@link CardPrefs} / {@link LockPrefs} 同上）。
 *
 * <p>**TASK-075** 落**背景**两项（定稿设计 §6.1 / §10）：
 * <ul>
 *   <li>{@code desk_bg_path} —— 背景图路径（空 = 浅色底 + 细网点）</li>
 *   <li>{@code desk_bg_veil} —— 全屏白纱不透明度（%，默认 90）</li>
 * </ul>
 *
 * <p>**TASK-076** 追加**模块系统**三项（定稿设计 §3.2 / §10）：
 * <ul>
 *   <li>{@code desk_enabled} —— 墨台总开关（默认 true）</li>
 *   <li>{@code desk_order} —— 6 个模块的**有序全表**（CSV；🔴 顺序即渲染顺序）</li>
 *   <li>{@code desk_on_mask} —— 开启模块的**位掩码**（默认 = 账单 | 排行 | 待办）</li>
 * </ul>
 * 口径照抄现有习惯（{@code StatsStore.CARD_ORDER} + {@code CardPrefs.getCardPoolMask} /
 * {@code CARD._ORDER}）：**键值是 CSV / 掩码、读出来一律做一遍"认不认识 / 越界"的净化**，
 * 免得旧版脏值或未来版本的模块 id 把渲染搞崩。
 *
 * <p>背景路径语义与锁屏**完全一致**（同一套"固定路径 + 多目录兜底"解析，见 {@link BgImageUtil}）：
 * 只是换了一个键，从而让"软锁背景"与"墨台背景"各自独立可配。
 */
public final class PagePrefs {

    /** 背景白纱默认不透明度（% 白）—— 定稿设计 §0 Q4 拍板「默认 90% 白合适」。 */
    public static final int DEFAULT_BG_VEIL = 90;

    private static final String K_BG_PATH = "desk_bg_path";
    private static final String K_BG_VEIL = "desk_bg_veil";

    // ══════════════════════ 🆕 TASK-076：模块系统 ══════════════════════
    //
    // 模块目录（定稿设计 §3.1 · 全集 = 6 个）：
    //   bill    阅读账单   ✅ 默认开   （内容卡：TASK-077）
    //   rank    读书排行   ✅ 默认开   （复 InsightRenderer.RankSection）
    //   todo    待办摘要   ✅ 默认开   （读 TodoStore）
    //   profile 阅读画像   ⬜ 默认关   （复 InsightRenderer.profileSection）
    //   note    今日一签   ⬜ 默认关   （读 NoteStore）
    //   annual  年度视图   ⬜ 默认关   （复 InsightRenderer.annualSection）
    //
    // 🔴 **顺序即渲染顺序**；关掉的模块**不渲染、不占高**（见 DeskPageView/DeskRenderer）。

    public static final String MOD_BILL    = "bill";
    public static final String MOD_RANK    = "rank";
    public static final String MOD_TODO    = "todo";
    public static final String MOD_PROFILE = "profile";
    public static final String MOD_NOTE    = "note";
    public static final String MOD_ANNUAL  = "annual";

    /** 模块全集（**新增模块只管往这里加** —— 顺序 = 出厂默认顺序 = 设置页行序的兜底）。 */
    public static final String[] MODULE_ORDER = {
            MOD_BILL, MOD_RANK, MOD_TODO, MOD_PROFILE, MOD_NOTE, MOD_ANNUAL };

    public static final int BIT_BILL    = 1;
    public static final int BIT_RANK    = 2;
    public static final int BIT_TODO    = 4;
    public static final int BIT_PROFILE = 16;
    public static final int BIT_NOTE    = 32;
    public static final int BIT_ANNUAL  = 64;

    /** 本版认识的位全集（未知位一律丢弃）。 */
    public static final int MODULE_ALL = BIT_BILL | BIT_RANK | BIT_TODO
            | BIT_PROFILE | BIT_NOTE | BIT_ANNUAL;

    /** 出厂默认开启集 = 「账单 | 排行 | 待办」（定稿设计 §3.1）。 */
    public static final int DEFAULT_ON_MASK = BIT_BILL | BIT_RANK | BIT_TODO;

    private static final String K_ENABLED = "desk_enabled";
    private static final String K_ORDER   = "desk_order";
    private static final String K_ON_MASK = "desk_on_mask";

    private PagePrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    // ── 模块 id ↔ 位 / 名称 ──

    /** 模块 id → 位；不认识 ⇒ 0。 */
    public static int moduleBit(String id) {
        if (MOD_BILL.equals(id))    return BIT_BILL;
        if (MOD_RANK.equals(id))    return BIT_RANK;
        if (MOD_TODO.equals(id))    return BIT_TODO;
        if (MOD_PROFILE.equals(id)) return BIT_PROFILE;
        if (MOD_NOTE.equals(id))    return BIT_NOTE;
        if (MOD_ANNUAL.equals(id))  return BIT_ANNUAL;
        return 0;
    }

    /** 模块 id 是不是本版认识的。 */
    public static boolean isKnownModule(String id) {
        return moduleBit(id) != 0;
    }

    /**
     * 模块显示名（设置页行标签 + 分区标题的**唯一来源**）。
     *
     * <p>为什么不放 `strings.xml`：设置页的 6 行是**按注册表动态装配**的，名字必须与
     * {@link #MODULE_ORDER} 同源；放资源里就得再维护一张 id→资源 的映射表（多一处会漂的地方）。
     * 本项目 `StatsStore.modeShortLabel` / `CardLayout` 等已有"渲染/标签文案硬编码"的先例。
     */
    public static String moduleName(String id) {
        // 🔴 2026-10-10 改名（用户拍板）：`bill` 的对外名 = **「墨单」**（原「阅读账单」）。
        //    这是"分区标题 + 设置页行标签"的**唯一来源** ⇒ 改这一处两端同步。
        //    ⚠️ 模块 id 恒为 `bill`（prefs 键 `desk_order` / `desk_on_mask` 不受影响）。
        if (MOD_BILL.equals(id))    return "墨单";
        if (MOD_RANK.equals(id))    return "读书排行";
        if (MOD_TODO.equals(id))    return "待办摘要";
        if (MOD_PROFILE.equals(id)) return "阅读画像";
        if (MOD_NOTE.equals(id))    return "今日一签";
        if (MOD_ANNUAL.equals(id))  return "年度视图";
        return id == null ? "" : id;
    }

    /** 出厂默认是否开启（文档 / 「恢复默认」用；实际开关值读 {@link #getDeskOnMask}）。 */
    public static boolean moduleDefaultOn(String id) {
        int bit = moduleBit(id);
        return bit != 0 && (DEFAULT_ON_MASK & bit) != 0;
    }

    // ── 总开关 ──

    /** 墨台总开关（缺省 = 开）。关掉 ⇒ 即使被呼出也画空态。 */
    public static boolean isDeskEnabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, true);
    }

    public static void setDeskEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ENABLED, on).commit();
    }

    // ── 顺序表（CSV）──

    /**
     * 模块顺序（**有序全表**，长度恒 = {@link #MODULE_ORDER}.length）。
     *
     * <p>净化规则（照 `StatsStore.readOrder` 的习惯）：
     * ① 丢弃不认识的 id 与重复项；② 把漏掉的模块**按出厂顺序补到末尾**。
     * ⇒ 返回值永远是"6 个模块的一个排列"，调用方无需再做防御。
     */
    public static String[] getDeskOrder(Context c) {
        String csv = sp(c).getString(K_ORDER, null);
        List<String> out = new ArrayList<String>();
        if (csv != null && csv.length() > 0) {
            for (String raw : csv.split(",")) {
                String id = raw == null ? "" : raw.trim();
                if (!isKnownModule(id)) continue;      // 不认识 ⇒ 丢
                if (out.contains(id)) continue;        // 重复 ⇒ 丢
                out.add(id);
            }
        }
        for (String id : MODULE_ORDER) {               // 漏掉的按出厂顺序补尾
            if (!out.contains(id)) out.add(id);
        }
        return out.toArray(new String[0]);
    }

    public static void setDeskOrder(Context c, String[] order) {
        // 🔴 去重按**整 id 全等**判（`List.contains`），**不用** `StringBuilder.indexOf` ——
        //    后者是**子串**匹配：将来若出现"新 id 是旧 id 子串"（如 `note` / `note_short`），
        //    会把合法的那个误判成"重复"而丢掉，写出的表就不再是用户要的顺序。
        List<String> out = new ArrayList<String>();
        if (order != null) {
            for (String id : order) {
                if (!isKnownModule(id)) continue;      // 不认识 ⇒ 丢
                if (out.contains(id)) continue;        // 重复 ⇒ 丢
                out.add(id);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(out.get(i));
        }
        sp(c).edit().putString(K_ORDER, sb.toString()).commit();
    }

    /**
     * 把某模块在顺序表里**上移 / 下移一格**（{@code dir < 0} 上移，{@code dir > 0} 下移）。
     *
     * @return 是否真的动了（已在顶 / 底 ⇒ false，调用方无需刷新 UI）
     */
    public static boolean moveModule(Context c, String id, int dir) {
        if (dir == 0 || !isKnownModule(id)) return false;
        String[] cur = getDeskOrder(c);
        int idx = -1;
        for (int i = 0; i < cur.length; i++) if (cur[i].equals(id)) { idx = i; break; }
        if (idx < 0) return false;
        int to = idx + (dir < 0 ? -1 : 1);
        if (to < 0 || to >= cur.length) return false;
        String tmp = cur[idx];
        cur[idx] = cur[to];
        cur[to] = tmp;
        setDeskOrder(c, cur);
        return true;
    }

    // ── 开启掩码 ──

    /**
     * 已开启模块的位掩码。
     *
     * <p>只保留本版认识的位（未知位丢弃）。🔴 与「卡片池」不同，**0 是合法值**（用户可以把
     * 模块全关掉 ⇒ 墨台显示空态「暂无内容」），这里**不做「0 回落全集」**的兜底 ——
     * 否则用户永远关不空，反而更迷惑。
     */
    public static int getDeskOnMask(Context c) {
        return sp(c).getInt(K_ON_MASK, DEFAULT_ON_MASK) & MODULE_ALL;
    }

    public static void setDeskOnMask(Context c, int mask) {
        sp(c).edit().putInt(K_ON_MASK, mask & MODULE_ALL).commit();
    }

    public static boolean isModuleOn(Context c, String id) {
        int bit = moduleBit(id);
        return bit != 0 && (getDeskOnMask(c) & bit) != 0;
    }

    /** 开 / 关某模块（其它位不动）。 */
    public static void setModuleOn(Context c, String id, boolean on) {
        int bit = moduleBit(id);
        if (bit == 0) return;
        int mask = getDeskOnMask(c);
        setDeskOnMask(c, on ? (mask | bit) : (mask & ~bit));
    }

    /**
     * **按渲染顺序**列出"已开启"的模块 id —— 墨台渲染直接吃这个列表
     * （🔴 顺序即渲染顺序、关掉的不出现 ⇒ 天然"不渲染、不占高"）。
     */
    public static List<String> enabledInOrder(Context c) {
        List<String> out = new ArrayList<String>();
        int mask = getDeskOnMask(c);
        for (String id : getDeskOrder(c)) {
            int bit = moduleBit(id);
            if (bit != 0 && (mask & bit) != 0) out.add(id);
        }
        return out;
    }

    // ── 背景图（语义同 LockPrefs 的 lock_bg_path）──
    //
    // 🆕 2026-10-10 第二轮（用户诉求 ⑥）：「墨台背景图片除了纯白之外可选择自定义，和锁屏壁纸一样
    //   **通过指定路径实现**」⇒ 墨台的背景不再是"扫目录列候选让用户点"，而是**固定一条路径**：
    //     · 纯白（不使用图片）= `desk_bg_path` 为空；
    //     · 自定义            = `desk_bg_path` = {@link #DESK_BG_REL}。
    //   🔴 理由：墨台是 `FLAG_NOT_FOCUSABLE` 的覆盖窗，塞不进 EditText（拿不到输入法），
    //     而 S4 ROM 又没有系统文件选择器 ⇒ "自己扫目录列出来"是上一版唯一能走的路，
    //     但它要求存储读权限、还会把**墨单生成的壁纸**（也落在 `Pictures/` 下）混进候选里。
    //     改成固定路径后这两件事一起消失，用户的心智模型与锁屏完全一致：
    //     **把想要的图改名放到那个位置，再在墨台里选「自定义」**。

    /** 自定义背景的**固定路径**（相对共享存储根）—— 与锁屏 {@code LockPrefs.DEFAULT_BG_REL} 同款口径。 */
    public static final String DESK_BG_REL = "Pictures/墨台背景.jpg";

    /** 已设置的墨台背景图路径（空 = 未设置 ⇒ 纯白 + 细网点）。 */
    public static String getDeskBgPath(Context c) {
        String v = sp(c).getString(K_BG_PATH, "");
        return v == null ? "" : v;
    }

    /** 自定义背景图**实际可读**的文件；没有 ⇒ {@code null}（调用方据此把「自定义」置灰并如实提示）。 */
    public static File resolveCustomBg(Context c) {
        return BgImageUtil.resolveBgFile(c, DESK_BG_REL);
    }

    /** 「自定义」当前可选吗（= 固定路径上真有一张能读的图）。 */
    public static boolean isCustomBgAvailable(Context c) {
        return resolveCustomBg(c) != null;
    }

    public static void setDeskBgPath(Context c, String path) {
        sp(c).edit().putString(K_BG_PATH, path == null ? "" : path).commit();
    }

    public static void clearDeskBg(Context c) {
        sp(c).edit().remove(K_BG_PATH).commit();
    }

    // ── 白纱（0~100 的 %）──

    /** 全屏白纱不透明度（%）；越界钳进 [0,100]，缺省 = {@link #DEFAULT_BG_VEIL}。 */
    public static int getDeskBgVeil(Context c) {
        int v = sp(c).getInt(K_BG_VEIL, DEFAULT_BG_VEIL);
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public static void setDeskBgVeil(Context c, int pct) {
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        sp(c).edit().putInt(K_BG_VEIL, pct).commit();
    }
}
