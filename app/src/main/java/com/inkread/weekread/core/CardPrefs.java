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

    // ── TASK-020：更新通道（正式版 stable / Beta）──
    //
    // 两条通道共用同一包名/签名、同一个全局 vc 池，**唯一差别 = 读哪一份清单**
    // （`main/dist/update.json` vs `beta/dist/update.json`）。详见 `ADR-011`。

    /** 更新通道：正式版（稳定，默认）—— 读 `main` 分支的清单 */
    public static final String CHANNEL_STABLE = "stable";
    /** 更新通道：Beta（尝鲜）—— 读 `beta` 分支的清单 */
    public static final String CHANNEL_BETA = "beta";
    /** 默认通道 = 正式版（老用户升级上来行为零差异） */
    public static final String DEFAULT_UPDATE_CHANNEL = CHANNEL_STABLE;

    /**
     * 更新通道（TASK-020 验收 A6 · 向后兼容读取）。
     *
     * 🔴 **容错铁律**：未设 / 非法值 / 来自未来版本的未知值 ⇒ **一律按 stable 处理，绝不崩**。
     * 只认字面量 {@code "beta"} 走 Beta，其余全部归 stable —— 这样正式版读 Beta 留下的脏偏好也安全。
     */
    public static String getUpdateChannel(Context c) {
        String v = sp(c).getString("update_channel", DEFAULT_UPDATE_CHANNEL);
        return CHANNEL_BETA.equals(v) ? CHANNEL_BETA : CHANNEL_STABLE;
    }

    public static void setUpdateChannel(Context c, String v) {
        sp(c).edit()
                .putString("update_channel", CHANNEL_BETA.equals(v) ? CHANNEL_BETA : CHANNEL_STABLE)
                .commit();
    }

    // ── TASK-030：云端备用更新源 ──
    //
    // GitHub 两源（raw → jsDelivr）同属 GitHub 生态，GitHub 整体不可达时两者会同时失效；
    // 打开本项后，两个源都失败时再回落自托管的云端清单（见 UpdateChecker 的 CLOUD_* 常量）。

    /**
     * 云端备用更新源：**默认开**。
     *
     * <p>🔴 备用源的价值恰在用户没能力自己排查的时候，默认关等于默认放弃这层保障。
     * 关闭后 {@code UpdateChecker.manifestUrls()} 返回的数组与改造前**逐字相同**（默认零差异）。
     */
    public static boolean isCloudFallbackEnabled(Context c) {
        return sp(c).getBoolean("upd_cloud_fallback", true);
    }

    public static void setCloudFallbackEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("upd_cloud_fallback", on).commit();
    }

    // ── TASK-018：遥控翻页（V1.0 Beta）──
    //
    // 同一 APK 装两端，remote_role 决定启用哪几个环节（docs/FEATURES/remote.md）：
    //   off（默认）= 与现状零差异；eink = 收指令注入翻页手势；phone = 捕获音量键发指令。

    /** 遥控角色：关闭（默认 —— 不开 socket、不建连，设置页只多一个角色开关） */
    public static final int REMOTE_ROLE_OFF = 0;
    /** 遥控角色：墨水屏端（Client：连手机热点网关，收指令 → 注入点击边界热区） */
    public static final int REMOTE_ROLE_EINK = 1;
    /** 遥控角色：手机端（Server：listen 等墨水屏连入，捕获音量键 → 发指令） */
    public static final int REMOTE_ROLE_PHONE = 2;

    public static final int DEFAULT_REMOTE_ROLE = REMOTE_ROLE_OFF;

    /** 空闲自动断开的默认秒数（显式会话 + 用完即断，按需连接策略） */
    public static final int REMOTE_IDLE_DEFAULT = 300;
    public static final int REMOTE_IDLE_MIN = 30;
    public static final int REMOTE_IDLE_MAX = 3600;

    public static int getRemoteRole(Context c) {
        // 🆕 TASK-064：正式版（能力门关）恒返回 OFF —— 「遥控」整条链路（热点通道 + HID 蓝牙通道 +
        //   音量键捕获 + 手机端遥控台）在正式版不可用。**这一处是遥控链路的唯一总闸**：
        //   RemoteRole.from / 遥控服务启动 / 实验室角色开关 全部经此读取 ⇒ 已隐藏入口之外，
        //   还要挡住「升级用户存量 remote_role=phone/eink」把正式版拖进遥控链路。
        if (!FeatureGate.remoteVisible(c)) return REMOTE_ROLE_OFF;
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

    // ── TASK-029：手机端晃动翻页（V1.0.4-beta）──
    //
    // 与「音量键捕获」并列的第二条捕获路径（实现在 remote/ShakeDetector）：
    //   总开关 on  + role=手机 + 会话已连接 ⇒ 注册 50Hz 加速度计；晃动 → 发翻页指令。
    // 🔴 三个开关**默认全 false** ⇒ 升级后与现状**零差异**（验收 A1）；
    //    「左右」「上下」两个反转开关**互相独立**（验收 A6/A7 的独立性）。
    // 🔴 越界/缺失一律回落 false（与 note_card_size 同一套安全读出纪律）。

    /** 晃动翻页总开关（默认关）。 */
    public static final boolean DEFAULT_SHAKE_ENABLED = false;
    /**
     * 左右晃方向反转（**旧键 · 只读**，默认关 = 左晃上一页 / 右晃下一页）。
     *
     * <p>🔴 自「三值化」（2026-10-10 用户 ②）起，方向不再用两个 rev 勾选表达，改由
     * {@link #getShakePeerActDir} 的 4 动作 × 三值直存。本键**只为老数据迁移而读**，
     * 不再有写入方（{@code setShakeLrRev} 已删）。
     */
    public static final boolean DEFAULT_SHAKE_LR_REV = false;
    /** 上下晃方向反转（**旧键 · 只读**，默认关 = 上晃上一页 / 下晃下一页）。迁移口径同 {@link #DEFAULT_SHAKE_LR_REV}。 */
    public static final boolean DEFAULT_SHAKE_UD_REV = false;

    public static boolean isShakeEnabled(Context c) {
        // 🆕 TASK-064：正式版（能力门关）恒 false —— 「晃动翻页」不可见（不注册加速度计）。
        if (!FeatureGate.remoteVisible(c)) return false;
        return sp(c).getBoolean("remote_shake_enabled", DEFAULT_SHAKE_ENABLED);
    }

    public static void setShakeEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("remote_shake_enabled", v).commit();
    }

    /** 左右晃方向反转 —— 🔴 **旧键 · 只读**（仅 {@link #getShakePeerActDir} 迁移时调用）。 */
    public static boolean isShakeLrRev(Context c) {
        return sp(c).getBoolean("remote_shake_lr_rev", DEFAULT_SHAKE_LR_REV);
    }

    /** 上下晃方向反转 —— 🔴 **旧键 · 只读**（仅 {@link #getShakePeerActDir} 迁移时调用）。 */
    public static boolean isShakeUdRev(Context c) {
        return sp(c).getBoolean("remote_shake_ud_rev", DEFAULT_SHAKE_UD_REV);
    }

    // ── 🆕 TASK-042 的「通道使能」已删除（🆕 2026-10-10 第三轮 · 用户诉求 ③）──
    //
    // 原 API：`isShakeAxisLrEnabled` / `isShakeAxisUdEnabled` / `setShakeAxisLrEnabled` /
    //        `setShakeAxisUdEnabled`（键 `shake_axis_lr_enabled` / `shake_axis_ud_enabled`）。
    // 🔴 删除理由：四动作方向已升级为**三值**（上一页 / 下一页 / **关**，见
    //    {@link #SHAKE_DIR_NONE} 与 {@link #getShakeLocalActDir}）⇒ 「让某个方向不响应」这件事
    //    已经由**逐动作的「关」**直接表达，轴使能是重复的一层，且两层叠加时用户说不清
    //    （"我明明把左晃设成上一页，为什么没反应？"—— 其实是轴被关了）。
    // 🔴 读取层不再做门控 ⇒ ShakeDetector 的三轴一律参与判轴（即两轮之前的默认行为）。
    // ⚠️ 旧键仍可能留在 prefs 里，但已无任何读取方 ⇒ 天然失效，无需迁移。

    // ── 🆕 TASK-073：本机晃动翻页（V1.2.2-beta）──
    //
    // 🔴 与上面「晃动翻页（控制对端）」**解耦**：本项管的是"晃本机 ⇒ 翻本机上的微信读书"
    //    （屏幕正对使用者握持），**不依赖** remote_role / install_role / 任一通道是否连通
    //    （用户 2026-10-09 Q2 拍板）—— 它靠无障碍手势注入打到**本机**屏幕，与对端无关。
    //    true  ⇒ ShakeDetector 增开一条「本机」采样实例；翻页走 RemoteInjector.injectLocal。
    //    false（默认）⇒ **与改造前零差异**（不注册加速度计、不注入）。
    // 🔴 方向与「背面朝自己」模式**相反** ⇒ 由 ShakeDetector 的本机分支对 sign 取反（A5）。
    // 🔴 无加速度计的设备（S4）⇒ ShakeDetector 采样注册失败、静默不启用（UI 侧另行置灰提示）。
    // 🔴 仍受「正式版能力门」约束（与 remote_shake_enabled / bt_control_enabled 同一口径，
    //    正式版恒 false ⇒ 不注册加速度计、不注入）。
    public static final boolean DEFAULT_SHAKE_LOCAL_ENABLED = false;

    public static boolean isShakeLocalEnabled(Context c) {
        if (!FeatureGate.remoteVisible(c)) return false;
        return sp(c).getBoolean("shake_local_enabled", DEFAULT_SHAKE_LOCAL_ENABLED);
    }

    public static void setShakeLocalEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("shake_local_enabled", v).commit();
    }

    // ── 🆕 TASK-073（二改）· 本机晃动的**翻页方式**（用户单项选择）────────────────────
    //   两个都经真机验证可用，让用户按手感自选：
    //     TAP（默认）  = 点**贴边窄条**（左/右边缘 50px）—— 与墨水屏端**同语义**、手势最短。
    //     SWIPE        = 水平**横扫**（右→左=下一页 / 左→右=上一页）—— 与分辨率无关、不吃热区宽度。
    //   🔴 两者**都只对竖屏、左右横向翻页有效**（UI 说明行已注明）；横屏不在支持范围。
    //   🔴 纯参数项：**不做** FeatureGate 门控（主开关 isShakeLocalEnabled 已门控，本项读了也无效）。
    public static final int SHAKE_LOCAL_MODE_TAP = 0;
    public static final int SHAKE_LOCAL_MODE_SWIPE = 1;
    public static final int DEFAULT_SHAKE_LOCAL_MODE = SHAKE_LOCAL_MODE_TAP;

    public static int getShakeLocalMode(Context c) {
        int v = sp(c).getInt("shake_local_mode", DEFAULT_SHAKE_LOCAL_MODE);
        // 防御：只认 0/1，脏值一律回落默认（避免 UI 单选无选中态）
        return (v == SHAKE_LOCAL_MODE_SWIPE) ? SHAKE_LOCAL_MODE_SWIPE : SHAKE_LOCAL_MODE_TAP;
    }

    public static void setShakeLocalMode(Context c, int v) {
        sp(c).edit().putInt("shake_local_mode", v).commit();
    }

    // ── 🆕 TASK-074 · 晃动「四动作方向」自选（用户 2026-10-09）────────────────────
    //   用户需求（原文）：「我希望本机晃动翻页的四个动作都能自选方向，每个动作一个单选：
    //   左晃：上一页/下一页，右晃：上一页/下一页，上晃：上一页/下一页，下晃：上一页/下一页，
    //   每个动作之间不冲突，默认选项是当前的对应关系」。
    //   🔴 默认值 = **当时的实际对应关系**（TASK-073 口径，逐位复现旧行为）：
    //       左晃→上一页 / 右晃→下一页 / 上晃→上一页 / 下晃→下一页。
    //   🔴 「不冲突」= 四个动作**各存各的**、互不牵连（不同于旧的"左右/上下各一个反转"）。
    //   🔴 纯参数项：**不做** FeatureGate 门控（主开关 isShakeLocalEnabled 已门控，本项读了也无效）。
    public static final int SHAKE_ACT_LEFT = 0;
    public static final int SHAKE_ACT_RIGHT = 1;
    public static final int SHAKE_ACT_UP = 2;
    public static final int SHAKE_ACT_DOWN = 3;

    // ── 🆕 2026-10-10 用户 ② · 方向三值化（每动作多一个「关」）────────────────────
    //
    //   用户需求（原文）：「在本机翻页选择四个动作的翻页方向中，增加一个选项"关"，选择后，
    //   触发动作不发送翻页指令，而非从直接不判断某个方向的动作，这个逻辑同步到遥控晃动翻页中」。
    //   ⇒ 方向由「上一页/下一页」二值扩为**三值**；「关」= 该动作命中后**仍走完整判决**，
    //     只是到投递环节**直接丢弃**（不注入、不发指令、不算失败）——
    //     与「响应方向（轴使能）」正交：轴使能是在**检测层**弃权，本三值是在**投递层**丢弃。
    //   🔴 两份独立表：本机（手机正对自己）与遥控（背面朝自己）**各存各的**；
    //     两边的 UI 语义都按**用户物理动作**（左晃/右晃/上晃/下晃）命名，raw sign 差异由
    //     {@code ShakeDetector.fire} 内部用 flip(action) 折掉（详见该类 Javadoc）。
    //   🔴 默认三值 = 旧行为（左/上→上一页，右/下→下一页），故**升级后逐位零差异**。
    /** 该动作命中 ⇒ 发「上一页」。 */
    public static final int SHAKE_DIR_PREV = 0;
    /** 该动作命中 ⇒ 发「下一页」。 */
    public static final int SHAKE_DIR_NEXT = 1;
    /** 该动作命中 ⇒ **不发任何翻页指令**（"关"）。 */
    public static final int SHAKE_DIR_NONE = 2;

    /** 本机四动作方向键（下标 = SHAKE_ACT_*）。🔴 新键名（旧键是 boolean，不能复用 —— getInt 会 CCE）。 */
    private static final String[] SHAKE_LOCAL_DIR_KEY = {
            "shake_ldir_left", "shake_ldir_right", "shake_ldir_up", "shake_ldir_down"};
    /** 遥控（对端）四动作方向键（下标 = SHAKE_ACT_*）。 */
    private static final String[] SHAKE_PEER_DIR_KEY = {
            "shake_pdir_left", "shake_pdir_right", "shake_pdir_up", "shake_pdir_down"};
    /** 三值的默认（下标 = SHAKE_ACT_*）：左/上 = 上一页，右/下 = 下一页 —— 与 TASK-073/074 现状一致。 */
    private static final int[] SHAKE_DIR_DEFAULT = {
            SHAKE_DIR_PREV, SHAKE_DIR_NEXT, SHAKE_DIR_PREV, SHAKE_DIR_NEXT};

    /** 动作下标归一（越界一律按「左晃」，绝不抛）。 */
    private static int normAct(int act) {
        return (act < 0 || act >= SHAKE_DIR_DEFAULT.length) ? SHAKE_ACT_LEFT : act;
    }

    /** 三值归一（脏值/越界一律回落该动作的默认方向）。 */
    private static int normDir(int dir, int act) {
        return (dir == SHAKE_DIR_PREV || dir == SHAKE_DIR_NEXT || dir == SHAKE_DIR_NONE)
                ? dir : SHAKE_DIR_DEFAULT[normAct(act)];
    }

    /**
     * 🆕 2026-10-10：本机某动作的方向（{@link #SHAKE_DIR_PREV}/{@link #SHAKE_DIR_NEXT}/{@link #SHAKE_DIR_NONE}）。
     *
     * <p>🔴 迁移：旧键 {@code shake_local_dir_*} 是 boolean（"是不是下一页"）——
     * 新键**存在**时读新键；不存在时读旧 boolean 并按 {@code next ? NEXT : PREV} 折算，
     * 保证老用户升级后**逐位零差异**（且不写脏：一旦用户在 UI 里改过就落新键，此后不再看旧键）。
     */
    public static int getShakeLocalActDir(Context c, int act) {
        final int a = normAct(act);
        if (sp(c).contains(SHAKE_LOCAL_DIR_KEY[a])) {
            return normDir(sp(c).getInt(SHAKE_LOCAL_DIR_KEY[a], SHAKE_DIR_DEFAULT[a]), a);
        }
        final boolean next = sp(c).getBoolean(SHAKE_ACT_KEY_LEGACY[a], SHAKE_DIR_DEFAULT[a] == SHAKE_DIR_NEXT);
        return next ? SHAKE_DIR_NEXT : SHAKE_DIR_PREV;
    }

    public static void setShakeLocalActDir(Context c, int act, int dir) {
        if (act < 0 || act >= SHAKE_LOCAL_DIR_KEY.length) {
            return;
        }
        sp(c).edit().putInt(SHAKE_LOCAL_DIR_KEY[act], dir).commit();
    }

    /**
     * 🆕 2026-10-10：遥控（对端）某动作的方向 —— 「同步到遥控晃动翻页」。
     *
     * <p>🔴 迁移：旧口径是「左右/上下各一个反转」({@code remote_shake_lr_rev}/{@code remote_shake_ud_rev})，
     * 等价于：默认 {左/上→上一页，右/下→下一页}，勾了反转则该组**两个动作一起翻**。
     * ⇒ 新键不存在时按此式折算，保证老用户升级后**逐位零差异**。
     *
     * <p>⚠ 这里的 {@code act} 必须已经是**用户物理动作**（左/右/上/下晃），
     * 由 {@code ShakeDetector.fire} 用 {@code flip(actionOf(...))} 折算后传入
     * （遥控时手机背面朝自己 ⇒ raw sign 与"屏幕正对自己"相反）。
     */
    public static int getShakePeerActDir(Context c, int act) {
        final int a = normAct(act);
        if (sp(c).contains(SHAKE_PEER_DIR_KEY[a])) {
            return normDir(sp(c).getInt(SHAKE_PEER_DIR_KEY[a], SHAKE_DIR_DEFAULT[a]), a);
        }
        // 迁移：本组的"反转"勾选把所有动作方向整体翻一次
        final boolean lrGroup = (a == SHAKE_ACT_LEFT || a == SHAKE_ACT_RIGHT);
        final boolean rev = lrGroup ? isShakeLrRev(c) : isShakeUdRev(c);
        final boolean basePrev = (SHAKE_DIR_DEFAULT[a] == SHAKE_DIR_PREV);
        final boolean isPrev = rev ? !basePrev : basePrev;
        return isPrev ? SHAKE_DIR_PREV : SHAKE_DIR_NEXT;
    }

    public static void setShakePeerActDir(Context c, int act, int dir) {
        if (act < 0 || act >= SHAKE_PEER_DIR_KEY.length) {
            return;
        }
        sp(c).edit().putInt(SHAKE_PEER_DIR_KEY[act], dir).commit();
    }

    /** 🔴 旧 boolean 键（仅 {@link #getShakeLocalActDir} 迁移时读）。 */
    private static final String[] SHAKE_ACT_KEY_LEGACY = {
            "shake_local_dir_left", "shake_local_dir_right",
            "shake_local_dir_up", "shake_local_dir_down"};

    // ── 🆕 TASK-041：手机端深色模式（V1.1.1-beta）──
    //
    // 🔴 **仅手机端**（install_role=phone）生效；阅读器端据此键也为 false 默认 ⇒ 零差异。
    //    true  ⇒ 遥控台 + 设置页按 docs/09 §2.1 深色 token 渲染；
    //    false（默认）⇒ 与现状逐像素一致（验收 A6）。
    public static final boolean DEFAULT_PHONE_DARK_MODE = false;

    public static boolean isPhoneDarkMode(Context c) {
        return sp(c).getBoolean("phone_dark_mode", DEFAULT_PHONE_DARK_MODE);
    }

    public static void setPhoneDarkMode(Context c, boolean v) {
        sp(c).edit().putBoolean("phone_dark_mode", v).commit();
    }

    // 🆕 TASK-029 手感优化（2026-10-03）：晃动灵敏度三档（低/中/高），**默认中**。
    //   0=低（更难触发）/ 1=中（默认）/ 2=高（更易触发）；每档四个参数以中档为圆心同向偏移 ≈15%。
    //   🔴 越界/缺失一律回落中档（与 note_card_size 同一套安全读出纪律）。
    public static final int SHAKE_SENS_LOW = 0;
    public static final int SHAKE_SENS_MID = 1;
    public static final int SHAKE_SENS_HIGH = 2;
    public static final int DEFAULT_SHAKE_SENS = SHAKE_SENS_MID;

    public static int getShakeSens(Context c) {
        int v = sp(c).getInt("remote_shake_sens", DEFAULT_SHAKE_SENS);
        return (v < SHAKE_SENS_LOW || v > SHAKE_SENS_HIGH) ? DEFAULT_SHAKE_SENS : v;
    }

    public static void setShakeSens(Context c, int v) {
        if (v < SHAKE_SENS_LOW) v = SHAKE_SENS_LOW;
        if (v > SHAKE_SENS_HIGH) v = SHAKE_SENS_HIGH;
        sp(c).edit().putInt("remote_shake_sens", v).commit();
    }

    // ── 🆕 TASK-033：「蓝牙控制」（HID 外设通道，V1.1.0-beta）──
    //
    // 🔴 独立于 TCP「热点翻页」的**另一条链路**（HID 外设模型，见 ADR-012）：手机当蓝牙键盘，
    //    翻页键走**操作系统**层直接送到墨水屏，不经墨水屏 App、不经 TCP。
    //    true  ⇒ 手机端注册 HID + 前台服务保活；翻页优先走 HID。
    //    false（默认）⇒ **与现状零差异**：不注册 HID、不启前台服务、无通知（验收 A6/A10）。
    public static final boolean DEFAULT_BT_CONTROL = false;

    public static boolean isBtControlEnabled(Context c) {
        // 🆕 TASK-064：正式版（能力门关）恒 false —— 「HID 蓝牙遥控」不可见
        //   （不注册 HID、不启前台服务、无通知）。
        if (!FeatureGate.remoteVisible(c)) return false;
        return sp(c).getBoolean("bt_control_enabled", DEFAULT_BT_CONTROL);
    }

    public static void setBtControlEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("bt_control_enabled", v).commit();
    }

    // ── 🆕 TASK-039：音量键翻页（蓝牙通道 · V1.1.1-beta）──
    //
    // 🔴 **只作用于蓝牙通道**（即 `bt_control_enabled=true` 时）：让手机侧面**实体音量键**
    //    也能翻页（复用 RemoteKeyService 捕获层，按通道分流 —— 蓝牙走 HID、热点走 TCP）。
    //    · true（默认）= 蓝牙通道下占用音量键发 HID 翻页键；
    //    · false       = 交还系统（音量键正常调音量）。
    // 🔴 **热点通道行为不受本开关影响**（既有音量键翻页照旧）—— 避免动到已验证路径。
    public static final boolean DEFAULT_BT_VOLKEY = true;

    public static boolean isBtVolkeyEnabled(Context c) {
        // 🆕 TASK-064：正式版（能力门关）恒 false —— 「音量键翻页」不可见（音量键交还系统）。
        if (!FeatureGate.remoteVisible(c)) return false;
        return sp(c).getBoolean("bt_volkey_enabled", DEFAULT_BT_VOLKEY);
    }

    public static void setBtVolkeyEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("bt_volkey_enabled", v).commit();
    }

    // ── 🆕 TASK-038：遥控台翻页交互偏好（V1.1.1-beta）──
    //
    // 🔴 与热点通道的方向设置（`remote_shake_lr_rev` 等）**彼此独立** —— 蓝牙翻页自有一套
    //    `bt_flip_*`，命名隔离、不复用，避免两条通道"串味"（验收 R4）。
    // 🔴 三项**默认值**（不换向 / 左右排布 / 220ms）⇒ 与 TASK-037 基座行为零差异。

    /** 方向交换（默认关）：false = 左/上「上一页」、右/下「下一页」。 */
    public static final boolean DEFAULT_BT_FLIP_SWAP = false;
    /** 上下排布（默认关）—— 关 = 左右各半。 */
    public static final boolean DEFAULT_BT_FLIP_VERTICAL = false;
    /** 长按连翻默认间隔（ms）。 */
    public static final int BT_FLIP_REPEAT_DEFAULT = 220;
    /** 间隔下限（ms）—— 过快会灌爆对端按键队列。 */
    public static final int BT_FLIP_REPEAT_MIN = 120;
    /** 间隔上限（ms）。 */
    public static final int BT_FLIP_REPEAT_MAX = 400;

    public static boolean isBtFlipSwap(Context c) {
        return sp(c).getBoolean("bt_flip_swap", DEFAULT_BT_FLIP_SWAP);
    }

    public static void setBtFlipSwap(Context c, boolean v) {
        sp(c).edit().putBoolean("bt_flip_swap", v).commit();
    }

    public static boolean isBtFlipVertical(Context c) {
        return sp(c).getBoolean("bt_flip_vertical", DEFAULT_BT_FLIP_VERTICAL);
    }

    public static void setBtFlipVertical(Context c, boolean v) {
        sp(c).edit().putBoolean("bt_flip_vertical", v).commit();
    }

    /** 长按连翻间隔（ms）。越界/缺失一律钳回 `[MIN, MAX]`（同一套安全读出纪律）。 */
    public static int getBtFlipRepeatMs(Context c) {
        int v = sp(c).getInt("bt_flip_repeat_ms", BT_FLIP_REPEAT_DEFAULT);
        if (v < BT_FLIP_REPEAT_MIN) return BT_FLIP_REPEAT_MIN;
        if (v > BT_FLIP_REPEAT_MAX) return BT_FLIP_REPEAT_MAX;
        return v;
    }

    public static void setBtFlipRepeatMs(Context c, int v) {
        if (v < BT_FLIP_REPEAT_MIN) v = BT_FLIP_REPEAT_MIN;
        if (v > BT_FLIP_REPEAT_MAX) v = BT_FLIP_REPEAT_MAX;
        sp(c).edit().putInt("bt_flip_repeat_ms", v).commit();
    }

    // ── 🆕 TASK-061：键盘页「打字间隔」（蓝牙 HID 逐字符发送时的字符间隔，ms）──
    //
    //  · 短 = 打得快、但可能丢键（对端处理不过来）；长 = 稳但慢。
    //  · 默认取**保守档**（本机实测不丢键）；越界 / 缺失一律钳回 `[MIN, MAX]`（同一套安全读出纪律）。
    //  · 只作用于「键盘」页的逐字符发送；翻页长按连翻用另一键（{@link #getBtFlipRepeatMs}）。

    /** 打字间隔上限（ms）—— 再慢就像卡住。 */
    public static final int BT_TYPE_INTERVAL_MAX = 80;
    /** 打字间隔下限（ms）—— 过快会灌爆对端按键队列（丢键）。 */
    public static final int BT_TYPE_INTERVAL_MIN = 10;
    /** 打字间隔默认（ms）—— 保守值。 */
    public static final int BT_TYPE_INTERVAL_DEFAULT = 30;

    public static int getBtTypeIntervalMs(Context c) {
        int v = sp(c).getInt("bt_type_interval_ms", BT_TYPE_INTERVAL_DEFAULT);
        if (v < BT_TYPE_INTERVAL_MIN) return BT_TYPE_INTERVAL_MIN;
        if (v > BT_TYPE_INTERVAL_MAX) return BT_TYPE_INTERVAL_MAX;
        return v;
    }

    public static void setBtTypeIntervalMs(Context c, int v) {
        if (v < BT_TYPE_INTERVAL_MIN) v = BT_TYPE_INTERVAL_MIN;
        if (v > BT_TYPE_INTERVAL_MAX) v = BT_TYPE_INTERVAL_MAX;
        sp(c).edit().putInt("bt_type_interval_ms", v).commit();
    }

    // ── TASK-025：卡片池（哪几张卡进桌面循环/列表）+ 切换模式（V1.0.3-beta）──
    //
    // 🔴 作用范围**只有桌面卡片** —— 未勾选的卡不参与桌面循环/列表，
    //    但 **APP 内主页面（周/月/书/记/待办）的查看完全不受影响**（验收 A4）。
    // 🔴 默认**全勾**（K7 拍板）：升级后行为与现状零差异（所有卡都在池里）。

    public static final int POOL_WEEK = 1;
    public static final int POOL_MONTH = 2;
    public static final int POOL_BOOK = 4;
    public static final int POOL_NOTE = 8;
    public static final int POOL_TODO = 16;
    /** 全部卡都在池里（5 张：周/月/书/记/待办） */
    public static final int POOL_ALL = POOL_WEEK | POOL_MONTH | POOL_BOOK | POOL_NOTE | POOL_TODO;
    public static final int DEFAULT_POOL_MASK = POOL_ALL;

    /** 切换模式：循环（现状）/ 列表（点左上角弹列表选卡） */
    public static final int SWITCH_LOOP = 0;
    public static final int SWITCH_LIST = 1;
    public static final int DEFAULT_SWITCH_MODE = SWITCH_LOOP;

    /**
     * 卡片池掩码（{@link #POOL_WEEK} 等的位或）。
     *
     * 🔴 **0 视为非法 ⇒ 回落全勾**（UI 侧也拦截"一张都不勾"，这里是防脏值的第二道保险）——
     * 否则桌面会一张卡都不显示，用户会以为 App 坏了。
     * 未知位（来自未来版本的脏值）一律丢弃，只保留本版认识的 5 位。
     */
    public static int getCardPoolMask(Context c) {
        int v = sp(c).getInt("card_pool_mask", DEFAULT_POOL_MASK) & POOL_ALL;
        return v == 0 ? DEFAULT_POOL_MASK : v;
    }

    public static void setCardPoolMask(Context c, int v) {
        v &= POOL_ALL;
        if (v == 0) v = DEFAULT_POOL_MASK;
        sp(c).edit().putInt("card_pool_mask", v).commit();
    }

    /** 切换模式：{@link #SWITCH_LOOP} / {@link #SWITCH_LIST}。非法值一律按循环处理（不炸） */
    public static int getSwitchMode(Context c) {
        int v = sp(c).getInt("switch_mode", DEFAULT_SWITCH_MODE);
        return (v == SWITCH_LIST) ? SWITCH_LIST : SWITCH_LOOP;
    }

    public static void setSwitchMode(Context c, int v) {
        sp(c).edit().putInt("switch_mode", (v == SWITCH_LIST) ? SWITCH_LIST : SWITCH_LOOP).commit();
    }

    // ── 🆕 TASK-059：书籍排名「只统计书架上的书」（V1.2.0-beta）──
    //
    // 🔴 只影响 **App 内两处排名**（本月页 K9 + 洞察页 K8），**不碰桌面卡片**：
    //    · true  ⇒ 排名只列 `bookId ∈ 书架` 的书（书架来自 `/shelf/sync` 全量 id 快照，见 BookStore）；
    //    · false（默认）⇒ **与改造前逐像素零差异**（RankFilter 直接原样返回原列表）。
    // 🔴 越界/缺失一律回落 false（与 note_card_size 同一套安全读出纪律）。

    /** 书籍排名是否只统计书架上的书（默认关）。 */
    public static final boolean DEFAULT_RANK_SHELF_ONLY = false;

    public static boolean isRankShelfOnly(Context c) {
        return sp(c).getBoolean("rank_shelf_only", DEFAULT_RANK_SHELF_ONLY);
    }

    public static void setRankShelfOnly(Context c, boolean v) {
        sp(c).edit().putBoolean("rank_shelf_only", v).commit();
    }

    // ── 🆕 TASK-069：「本书」手动选书（V1.2.1-beta）──
    //
    // 背景：「本书」默认取书架里 `readUpdateTime` 最大的那本（**微信读书说了算**）。
    // 而这个字段的语义是"这本书最后一次被打开/同步"，跟用户心里的"我在读哪本"并不总是一回事
    // ⇒ 用户会看到「卡片显示的不是我在读的那本」。
    //
    // 本项给用户一个**手动的否决权**：
    //   · ""（默认）⇒ 自动：取最近在读 —— 与改造前**逐像素零差异**（验收口径）；
    //   · 非空     ⇒ 优先取这本书的进度；**取不到进度时静默回落自动，且不改写本偏好**
    //                （书暂时没有进度不代表用户选错了 —— 下次有进度就自动生效）。
    // 🔴 只存 bookId（不存书名 / 封面）—— 书名封面始终从书架快照现取，避免文案过期。
    // 🔴 越界/缺失一律回落 ""（与 note_card_size 同一套安全读出纪律）。

    /** 「本书」自动档（= 最近在读）。 */
    public static final String DEFAULT_BOOK_PICK = "";

    private static final String K_BOOK_PICK = "book_pick";

    /** 手动选中的 bookId；空串 = 自动（最近在读）。null 兜底为自动档。 */
    public static String getBookPick(Context c) {
        String v = sp(c).getString(K_BOOK_PICK, DEFAULT_BOOK_PICK);
        return (v == null) ? DEFAULT_BOOK_PICK : v.trim();
    }

    public static void setBookPick(Context c, String bookId) {
        sp(c).edit().putString(K_BOOK_PICK, bookId == null ? "" : bookId.trim()).commit();
    }

    // ── TASK-031：本机角色（安装形态）—— 「这台设备用来读 / 用来当遥控面板」──
    //
    // 🔴 这是 **App 形态**（V1.1.0-beta，ADR-012），与遥控线的 `remote_role`（TCP 传输角色）**正交**、**不得混用**：
    //   · install_role=reader（默认）= 现状：阅读统计主页 + 桌面卡片 + 全部既有子标签；
    //   · install_role=phone        = 控制面板：主入口直进「设置-实验室」，只装配手机端关联子标签。
    // 🔴 越界/缺失一律回落 reader（与 note_card_size 同一套安全读出纪律）；缺省即 reader ⇒ 老用户升级零感知。

    /** 本机角色：阅读器端（默认）—— 正常当阅读器用 */
    public static final int INSTALL_ROLE_READER = 0;
    /** 本机角色：手机端 —— 退化为遥控控制面板 */
    public static final int INSTALL_ROLE_PHONE = 1;

    public static final int DEFAULT_INSTALL_ROLE = INSTALL_ROLE_READER;

    private static final String K_INSTALL_ROLE = "install_role";

    public static int getInstallRole(Context c) {
        // 🆕 TASK-064：正式版（能力门关）恒返回 READER —— 「安装角色选择」不可见；
        //   即便升级用户存量 install_role=phone，正式版也不会进手机端形态（遥控台不可达）。
        //   🔴 这也是「手机端深色模式」的间接总闸：InkTheme.isDark = install_role==phone && phone_dark_mode
        //   ⇒ 正式版恒为阅读器端 ⇒ 深色分支永不启用（零差异）。
        if (!FeatureGate.rolePickVisible(c)) return INSTALL_ROLE_READER;
        int v = sp(c).getInt(K_INSTALL_ROLE, DEFAULT_INSTALL_ROLE);
        if (v < INSTALL_ROLE_READER || v > INSTALL_ROLE_PHONE) return DEFAULT_INSTALL_ROLE;
        return v;
    }

    public static void setInstallRole(Context c, int v) {
        if (v < INSTALL_ROLE_READER || v > INSTALL_ROLE_PHONE) v = DEFAULT_INSTALL_ROLE;
        sp(c).edit().putInt(K_INSTALL_ROLE, v).commit();
    }

    /**
     * 本机角色是否"已经选过"（键是否被写过）。
     *
     * <p>用于首装引导判据：**键从未写入** 且 {@link #isFreshInstall} ⇒ 才弹一次二选一。
     * 与 `getInstallRole()`（缺省返回 reader）不同 —— 后者是"读出来用"，这个是"是否写过"。
     */
    public static boolean isInstallRoleChosen(Context c) {
        return sp(c).contains(K_INSTALL_ROLE);
    }

    /**
     * 是否**全新安装**（首启）：`cfg` 一份偏好都没有。
     *
     * <p>🔴 调用时机有硬要求：必须在**任何可能写 `cfg` 的动作之前**读（见 `MainActivity.onCreate`）。
     * 本工程启动早期唯一可能写 `cfg` 的是 `UpdateChecker.autoCheck`（与 CardPrefs 共用 `cfg`，
     * 且写盘在后台线程 + 联网成功后）—— 若先跑它再读本项，存在竞态误判。
     */
    public static boolean isFreshInstall(Context c) {
        return sp(c).getAll().isEmpty();
    }
}
