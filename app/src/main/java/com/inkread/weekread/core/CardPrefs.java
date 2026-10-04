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
    /** 左右晃方向反转（默认关 = 左晃上一页 / 右晃下一页）。 */
    public static final boolean DEFAULT_SHAKE_LR_REV = false;
    /** 上下晃方向反转（默认关 = 上晃上一页 / 下晃下一页）。 */
    public static final boolean DEFAULT_SHAKE_UD_REV = false;

    public static boolean isShakeEnabled(Context c) {
        return sp(c).getBoolean("remote_shake_enabled", DEFAULT_SHAKE_ENABLED);
    }

    public static void setShakeEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("remote_shake_enabled", v).commit();
    }

    public static boolean isShakeLrRev(Context c) {
        return sp(c).getBoolean("remote_shake_lr_rev", DEFAULT_SHAKE_LR_REV);
    }

    public static void setShakeLrRev(Context c, boolean v) {
        sp(c).edit().putBoolean("remote_shake_lr_rev", v).commit();
    }

    public static boolean isShakeUdRev(Context c) {
        return sp(c).getBoolean("remote_shake_ud_rev", DEFAULT_SHAKE_UD_REV);
    }

    public static void setShakeUdRev(Context c, boolean v) {
        sp(c).edit().putBoolean("remote_shake_ud_rev", v).commit();
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
        return sp(c).getBoolean("bt_control_enabled", DEFAULT_BT_CONTROL);
    }

    public static void setBtControlEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("bt_control_enabled", v).commit();
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
