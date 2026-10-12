package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * **墨台**（桌面呼出页）的偏好落盘（prefs 文件 `cfg`，与 {@link CardPrefs} / {@link LockPrefs} 同上）。
 *
 * <p>🔴 **2026-10-11（TASK-18）**：TASK-075 落下的**背景两项**
 * （{@code desk_bg_path} 背景图路径、{@code desk_bg_veil} 白纱不透明度）**已整体删除** ——
 * 用户 2026-10-10 指令①第 6 条「放弃墨台的壁纸，改为纯白色，删除相关设置」。
 * 墨台背景自本卡起**恒为纯白**（{@code DeskRenderer.drawBackground} 只画 `0xFFFFFFFF`）。
 * <ul>
 *   <li>旧值**保留不读**：两个键若还躺在 `cfg` 里，本类已无任何读取路径 ⇒
 *       <b>不崩、不读图、不申请存储权限</b>（也不主动清理，避免为一次性的收尾写路径）。</li>
 *   <li>🔴 与「生成壁纸」无关：墨单海报的预览 / 保存 / 分享（`BillWallpaper` +
 *       `DeskPageView.MODE_WALLPAPER`）全部保留未动。</li>
 * </ul>
 *
 * <p>**TASK-076** 追加**模块系统**三项（定稿设计 §3.2 / §10）：
 * <ul>
 *   <li>{@code desk_enabled} —— 墨台总开关（默认 true）</li>
 *   <li>{@code desk_order} —— **2 个**模块（墨单 / 阅读资产）的**有序全表**（CSV；🔴 顺序即渲染顺序）</li>
 *   <li>{@code desk_on_mask} —— 开启模块的**位掩码**（默认 = 墨单 | 阅读资产）</li>
 * </ul>
 * 口径照抄现有习惯（{@code StatsStore.CARD_ORDER} + {@code CardPrefs.getCardPoolMask} /
 * {@code CARD._ORDER}）：**键值是 CSV / 掩码、读出来一律做一遍"认不认识 / 越界"的净化**，
 * 免得旧版脏值或未来版本的模块 id 把渲染搞崩。
 */
public final class PagePrefs {

    // ══════════════════════ 🆕 TASK-076：模块系统 ══════════════════════
    //
    // 模块目录（🔴 第七批 W1 删除「阅读画像 / 今日一签 / 年度视图」后 · 全集 = 2 个）：
    //   bill    墨单       ✅ 默认开   （内容卡：TASK-077）
    //   rank    阅读资产   ✅ 默认开   （内容卡：TASK-086 第六批B —— 就地取代原「读书排行」）
    //
    // 🆕 第七批 W1（2026-10-12 用户拍板）：墨台「内容模块」备选里的
    //   `profile`（阅读画像）/ `note`（今日一签）/ `annual`（年度视图，用户笔误「年度识图」）
    //   **三项整体删除** —— 三个模块内部类随之下线（`feature/DeskModules.java`）。
    //   🔴 **位 16 / 32 / 64 从此不复用**（同第六批 D24 对位 4 的口径）：旧 `desk_on_mask`
    //   里的这三个位由 {@link #MODULE_ALL} 过滤掉，旧 `desk_order` 里的三个 id 由
    //   {@link #isKnownModule} 过滤掉 ⇒ **老用户零迁移**。
    //   ⚠️ 洞察页**不受影响**：`feature/InsightRenderer` 的 `profileSection` / `annualSection` /
    //   `aggregateCategories` / `aggregateAuthors` / `judgePortrait` 仍由
    //   `feature/InsightPageView`（`:124/:135/:136/:184/:195-199`）在用，**不得删**。
    //
    // 🆕 TASK-086 第六批B（D23/D24/D25，2026-10-11）：**「待办摘要」模块整块删除**
    //   （用户第六批 #5）—— 手机端的待办页与卡片池里的「待办」**都保留**，只删墨台这一块。
    //   ⇒ `todo` id、`BIT_TODO = 4`、`MODULE_ORDER` 里的位置、`moduleName` 的分支全部下线；
    //   🔴 **位 4 从此不复用**（旧 `desk_on_mask` 里的位 4 由 {@link #MODULE_ALL} 过滤掉，
    //   旧 `desk_order` 里的 `todo` 由 {@link #isKnownModule} 过滤掉 ⇒ 老用户零迁移）。
    //
    // 🔴 **顺序即渲染顺序**；关掉的模块**不渲染、不占高**（见 DeskPageView/DeskRenderer）。

    public static final String MOD_BILL    = "bill";
    public static final String MOD_RANK    = "rank";

    /** 模块全集（**新增模块只管往这里加** —— 顺序 = 出厂默认顺序 = 设置页行序的兜底）。 */
    public static final String[] MODULE_ORDER = {
            MOD_BILL, MOD_RANK };

    public static final int BIT_BILL    = 1;
    public static final int BIT_RANK    = 2;
    // 🔴 TASK-086 第六批B：`BIT_TODO = 4` 已随「待办摘要」模块删除 —— **位 4 不复用**。
    // 🔴 第七批 W1：`BIT_PROFILE = 16` / `BIT_NOTE = 32` / `BIT_ANNUAL = 64` 已随
    //    「阅读画像 / 今日一签 / 年度视图」三项删除 —— **位 16/32/64 不复用**（同 D24 口径）。

    /** 本版认识的位全集（未知位一律丢弃；位 4/16/32/64 已废弃 ⇒ 老值里的这些位在这里被丢掉）。 */
    public static final int MODULE_ALL = BIT_BILL | BIT_RANK;

    /** 出厂默认开启集 = 「墨单 | 阅读资产」（原含待办，TASK-086 第六批B 随模块删除）。 */
    public static final int DEFAULT_ON_MASK = BIT_BILL | BIT_RANK;


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
        return 0;                                   // 含已废弃的 `todo`（位 4）与 `profile`/`note`/`annual`（位 16/32/64）—— 均不复用
    }

    /** 模块 id 是不是本版认识的。 */
    public static boolean isKnownModule(String id) {
        return moduleBit(id) != 0;
    }

    /**
     * 模块显示名（设置页行标签 + 分区标题的**唯一来源**）。
     *
     * <p>为什么不放 `strings.xml`：设置页的行是**按注册表动态装配**的，名字必须与
     * {@link #MODULE_ORDER} 同源；放资源里就得再维护一张 id→资源 的映射表（多一处会漂的地方）。
     * 本项目 `StatsStore.modeShortLabel` / `CardLayout` 等已有"渲染/标签文案硬编码"的先例。
     */
    public static String moduleName(String id) {
        // 🔴 2026-10-10 改名（用户拍板）：`bill` 的对外名 = **「墨单」**（原「阅读账单」）。
        //    这是"分区标题 + 设置页行标签"的**唯一来源** ⇒ 改这一处两端同步。
        //    ⚠️ 模块 id 恒为 `bill`（prefs 键 `desk_order` / `desk_on_mask` 不受影响）。
        if (MOD_BILL.equals(id))    return "墨单";
        // 🆕 TASK-086 第六批B（D18，2026-10-11 用户拍板）：`rank` 的对外名 = **「阅读资产」**
        //    （原「读书排行」）—— 内容卡换成 AssetSection，但 **id 恒为 `rank`**、
        //    位/顺序/开关**零迁移**（`desk_order` / `desk_on_mask` 里的 `rank`/位 2 原样有效）。
        if (MOD_RANK.equals(id))    return "阅读资产";
        // 🔴 第七批 W1：`profile`/`note`/`annual`（阅读画像/今日一签/年度视图）已删除 ⇒ 无分支。
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
     * ⇒ 返回值永远是"本版全集（**2 个：墨单 / 阅读资产**）的一个排列"，调用方无需再做防御。
     * （第七批 W1 删掉三项后，旧 `desk_order` 里的 `profile`/`note`/`annual` 在此按"不认识"丢掉。）
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

    // ══════════════════════ 🆕 第七批 W1：阅读资产偏好（🔴 跨卡冻结接口） ══════════════════════
    //
    // 消费方 = W2（task-66：资产页「只看书架」+ 长按隐藏）。**签名与键名一律不许改**（W1 冻结 / W2 只调用）。
    // 落盘沿用本文件既有 prefs 文件 `cfg`（与 `desk_*` 同源）。

    private static final String K_ASSET_SHELF_ONLY = "asset_shelf_only";
    private static final String K_ASSET_HIDDEN_IDS = "asset_hidden_ids";

    /** 阅读资产是否「只看书架」（只列已入库书籍；关 = 列全部）。缺省 **false**。 */
    public static boolean isAssetShelfOnly(Context c) {
        return sp(c).getBoolean(K_ASSET_SHELF_ONLY, false);
    }

    public static void setAssetShelfOnly(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ASSET_SHELF_ONLY, on).commit();
    }

    // 🆕 TASK-087-A1（2026-10-12，用户 ② 拍板 4/5）：阅读资产「只统计读完的书」。
    //    🔴 键名与签名**冻结**：A2（资产壁纸）与墨台设置页只调用，不许另建键、不许改默认值。

    private static final String K_ASSET_FINISHED_ONLY = "asset_finished_only";

    /**
     * 阅读资产是否「只统计**读完**的书」。缺省 **true**（用户拍板 4：默认打开）。
     *
     * <p>🔴 判据唯一入口 = {@link com.inkread.weekread.core.BillMoney#progressForBilling(int, boolean)}
     * （`finished ⇒ 100` / `pct ≥ 99 ⇒ 100` / `pct < 0 且未读完 ⇒ -1`）。因此
     * 「**进度未知（-1）且未读完**」也 &lt; 100 ⇒ 与用户拍板 5 一致地**被隐藏**；
     * 这些书要靠关掉本开关（或长按录价前先关）才看得见 —— 后果已登记在 `验证记录/236`。
     */
    public static boolean isAssetFinishedOnly(Context c) {
        return sp(c).getBoolean(K_ASSET_FINISHED_ONLY, true);
    }

    public static void setAssetFinishedOnly(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ASSET_FINISHED_ONLY, on).commit();
    }

    /**
     * 被**隐藏**的资产 `bookId` 集合。
     *
     * <p>🔴 契约：**永不 null**、**返回副本**（调用方增删它不会影响落盘 / 不会污染 prefs 缓存）；
     * 空白 id 一律不返回。缺省 = 空集。
     */
    public static Set<String> assetHiddenIds(Context c) {
        // `getStringSet` 返回的是 prefs 内部实例（文档禁止直接改）⇒ 必须拷一份。
        Set<String> raw = sp(c).getStringSet(K_ASSET_HIDDEN_IDS, null);
        Set<String> out = new HashSet<String>();
        if (raw != null) {
            for (String id : raw) {
                if (id != null && id.trim().length() > 0) out.add(id.trim());
            }
        }
        return out;
    }

    /**
     * 隐藏一本书。**幂等**：已经隐藏过 ⇒ 返回 false（不写盘）。
     *
     * @return 本次是否真的新增了一条（false 也含 `bookId` 为 null / 空白 —— 那种情况**不写盘**）
     */
    public static boolean hideAssetBook(Context c, String bookId) {
        if (bookId == null || bookId.trim().length() == 0) return false;
        Set<String> cur = assetHiddenIds(c);
        if (!cur.add(bookId.trim())) return false;
        sp(c).edit().putStringSet(K_ASSET_HIDDEN_IDS, cur).commit();
        return true;
    }

    /** 取消隐藏一本书。**幂等**：本来就没隐藏 ⇒ 返回 false（不写盘）。 */
    public static boolean unhideAssetBook(Context c, String bookId) {
        if (bookId == null || bookId.trim().length() == 0) return false;
        Set<String> cur = assetHiddenIds(c);
        if (!cur.remove(bookId.trim())) return false;
        sp(c).edit().putStringSet(K_ASSET_HIDDEN_IDS, cur).commit();
        return true;
    }

    /** 清空隐藏集（幂等；直接删键 ⇒ 与"空集"同义，不留残值）。 */
    public static void clearAssetHidden(Context c) {
        sp(c).edit().remove(K_ASSET_HIDDEN_IDS).commit();
    }

    // ══════════════════════ 🔴 已删除：墨台背景图 + 白纱 ══════════════════════
    //
    // 2026-10-11（TASK-18 · 用户指令①第 6 条「放弃墨台的壁纸，改为纯白色，删除相关设置」）：
    //   原有的 `desk_bg_path`（背景图路径）/ `desk_bg_veil`（白纱不透明度）两项偏好、
    //   连同 `DESK_BG_REL = "Pictures/墨台背景.jpg"`、`getDeskBgPath/setDeskBgPath/clearDeskBg`、
    //   `resolveCustomBg/isCustomBgAvailable`（会读共享存储的图片）、`getDeskBgVeil/setDeskBgVeil`
    //   **整体删除**。墨台背景恒为纯白（`feature/DeskRenderer.drawBackground` 只画 `0xFFFFFFFF`）。
    //
    // 旧键处理 = **保留不读**：`cfg` 里若残留 `desk_bg_path` / `desk_bg_veil`，本类已无任何读取
    //   路径 ⇒ 不崩、不读图、不申请存储权限；也不写一次性清理代码（少一条收尾路径就少一个坑）。
    //
    // 🔴 与「生成壁纸」的界线（防误删）：墨单海报的 `feature/BillWallpaper.java` 与
    //   `DeskPageView` 的 `MODE_WALLPAPER`/`enterWallpaper`/`saveWallpaper`/`shareWallpaper`
    //   全部保留未动 —— 本次删的是"墨台背景图"，不是"生成壁纸"。
}
