package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * 续航（省电指令）管理器 —— TASK-023 / V1.0.2-beta；本轮（V1.0.4 迭代）修订。
 *
 * <p>把「省电开关」抽象成可勾选的<b>条目</b>，每个条目控制 1..n 个系统设置键
 * （{@link Settings.Global} 或 {@link Settings.Secure}，见 {@link Item#ns}）：
 * <ul>
 *   <li><b>勾选</b> = 写入「开启值」；<b>取消</b> = <b>显式写回捕获值</b>
 *       （⛔ 绝不用 {@code settings delete} —— delete 后值为 {@code null}，<b>不是</b>还原，
 *       见 `2026-09-29_Beta功能与省电指令_通道对照表.md` §2.3）。</li>
 *   <li>写入需要 {@code WRITE_SECURE_SETTINGS}（保护级 = {@code signature|privileged|development}，
 *       <b>含 development ⇒ 可一次性 {@code pm grant}</b>，免 root）。</li>
 * </ul>
 *
 * <p>🔴 <b>本轮修订（依据真机探针，`验证记录/121`）</b>：
 * <ul>
 *   <li><b>移除 B1「息屏断网」</b>（原写 {@code wifi_sleep_policy}）—— 探针实测：该键在 S4 上对
 *       熄屏行为<b>零影响</b>（档 0/2 无差异），是<b>伪开关</b>。</li>
 *   <li><b>移除 B2「省电模式」</b>（原写 {@code low_power}）—— 该 ROM 的 Battery Saver
 *       <b>恒为 ON</b>（`Last ON 10-01`、`Times enabled=2`），写键是<b>空操作</b>。</li>
 *   <li><b>新增 Q3「关时钟屏保」</b>（写 {@code screensaver_enabled}，<b>Secure</b> 命名空间）。</li>
 * </ul>
 * 注：本机（S4 ROM）无法程序关/休眠 WiFi（系统 `BLOCKED: Automatic WiFi disable`），
 * 故「息屏断网」的唯一真实通道是 **Shizuku 飞行模式**（`cmd connectivity airplane-mode`），
 * 不属本模块，留待后续「增强版」。
 *
 * <p>🔴 <b>关键设计</b>：UI 勾选态一律以「<b>设备实际值</b>」为准（{@link #isOn}），
 * 不以偏好记录为准 —— 系统状态机可能改回某项，只有读真值才不会出现「界面显示已开、实际已关」。
 *
 * <p>捕获值（取消勾选时要写回的原值）落在本类自管的 SharedPreferences（{@code power_cfg}），
 * 键名 = {@code cap_<键名>}；无捕获值时用 {@code fallback}（本机实测基线）。
 * ⚠️ 本类<b>不碰</b> {@link CardPrefs}，以缩小改动面。
 */
public final class PowerSettingsManager {

    private PowerSettingsManager() {
    }

    // ──────────────────────────── 权限 ────────────────────────────

    /** 需要的权限（保护级 = signature|privileged|<b>development</b> ⇒ 可 {@code pm grant}） */
    public static final String PERM_WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS";

    /**
     * 一次性授权命令（App 内「复制命令」按钮直接用这条）。
     * ⚠️ 用真实包名拼接，别硬编码。
     */
    public static String grantCommand(Context c) {
        return "adb shell pm grant " + c.getPackageName() + " " + PERM_WRITE_SECURE;
    }

    /** 是否已拿到 {@code WRITE_SECURE_SETTINGS}（用老 API {@code checkPermission}，全版本可用）。 */
    public static boolean hasPermission(Context c) {
        return c.getPackageManager()
                .checkPermission(PERM_WRITE_SECURE, c.getPackageName())
                == PackageManager.PERMISSION_GRANTED;
    }

    // ──────────────────────────── 命名空间 ────────────────────────────

    /** 写 {@link Settings.Global}（多数省电键在此）。 */
    public static final int NS_GLOBAL = 0;
    /** 写 {@link Settings.Secure}（Q3 屏保键在此）。 */
    public static final int NS_SECURE = 1;

    // ──────────────────────────── 受控的键 ────────────────────────────

    /** A3 · 关「开放网络通知」（Global，1→0） */
    private static final String K_WIFI_NET_NOTIF = "wifi_networks_available_notification_on";
    /** A5 · 关三档动画（Global，1→0，一键控 3 键） */
    private static final String K_ANIM_WINDOW = "window_animation_scale";
    private static final String K_ANIM_TRANSITION = "transition_animation_scale";
    private static final String K_ANIM_ANIMATOR = "animator_duration_scale";
    /** Q3 · 关时钟屏保（Secure，1→0） */
    private static final String K_SCREENSAVER = "screensaver_enabled";

    // ──────────────────────────── 条目定义 ────────────────────────────

    /** 一条省电条目：控 1..n 个系统设置键，统一落在同一命名空间 {@link #ns}。 */
    public static final class Item {
        /** 稳定标识（仅用于调试/日志，不落盘） */
        public final String id;
        /** 命名空间：{@link #NS_GLOBAL} / {@link #NS_SECURE} */
        public final int ns;
        /** 受控的键 */
        public final String[] keys;
        /** 勾选时写入的值（与 {@link #keys} 一一对应） */
        public final int[] onValues;
        /** 取消勾选时的兜底还原值（无捕获值时用；本机实测基线） */
        public final int[] fallback;

        Item(String id, int ns, String[] keys, int[] onValues, int[] fallback) {
            this.id = id;
            this.ns = ns;
            this.keys = keys;
            this.onValues = onValues;
            this.fallback = fallback;
        }
    }

    /** A3 · 关「开放网络通知」（基线 = 1） */
    public static final Item ITEM_WIFI_NET_NOTIF = new Item("wifi_net_notif", NS_GLOBAL,
            new String[]{ K_WIFI_NET_NOTIF }, new int[]{ 0 }, new int[]{ 1 });

    /** A5 · 关三档动画（基线 = 1/1/1） */
    public static final Item ITEM_ANIM = new Item("anim", NS_GLOBAL,
            new String[]{ K_ANIM_WINDOW, K_ANIM_TRANSITION, K_ANIM_ANIMATOR },
            new int[]{ 0, 0, 0 }, new int[]{ 1, 1, 1 });

    /** Q3 · 关时钟屏保（Secure，基线 = 1） */
    public static final Item ITEM_SCREENSAVER = new Item("screensaver", NS_SECURE,
            new String[]{ K_SCREENSAVER }, new int[]{ 0 }, new int[]{ 1 });

    /** UI 顺序：A3 → A5 → Q3 */
    public static final Item[] ALL = {
            ITEM_WIFI_NET_NOTIF, ITEM_ANIM, ITEM_SCREENSAVER };

    // ──────────────────────────── 读 / 写 ────────────────────────────

    /** 读一个键（读不需要权限）；异常时返回 {@code def}。 */
    public static int get(Context c, int ns, String key, int def) {
        try {
            switch (ns) {
                case NS_SECURE:
                    return Settings.Secure.getInt(c.getContentResolver(), key, def);
                default:
                    return Settings.Global.getInt(c.getContentResolver(), key, def);
            }
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 写一个键（<b>需 {@code WRITE_SECURE_SETTINGS}</b>）。
     * ⚠️ 无权限时可能抛 SecurityException ⇒ 捕获后返回 false，由调用方降级提示。
     */
    public static boolean put(Context c, int ns, String key, int value) {
        try {
            switch (ns) {
                case NS_SECURE:
                    return Settings.Secure.putInt(c.getContentResolver(), key, value);
                default:
                    return Settings.Global.putInt(c.getContentResolver(), key, value);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 该条目当前是否「已生效」= 其全部键的值都等于 {@code onValues}。
     * 🔴 UI 勾选态以此为准（不读偏好记录），保证易失项被系统改回后界面能跟上。
     */
    public static boolean isOn(Context c, Item it) {
        for (int i = 0; i < it.keys.length; i++) {
            int cur = get(c, it.ns, it.keys[i], Integer.MIN_VALUE);
            if (cur != it.onValues[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 勾选 / 取消一个条目。
     *
     * <p>勾选：逐键「先捕获当前值落盘（仅当当前值 ≠ 开启值）→ 再写开启值」。
     * <br>取消：逐键「读捕获值（无则用兜底基线）→ 显式写回」。
     *
     * @return 是否全部写入成功（无权限时为 false）
     */
    public static boolean setOn(Context c, Item it, boolean on) {
        SharedPreferences sp = sp(c);
        boolean ok = true;
        for (int i = 0; i < it.keys.length; i++) {
            if (on) {
                int cur = get(c, it.ns, it.keys[i], it.fallback[i]);
                if (cur != it.onValues[i]) {
                    // 只在"当前值不是开启值"时记捕获值，免得把开启值本身当成原值
                    sp.edit().putInt(CAP_PREFIX + it.keys[i], cur).apply();
                }
                ok = put(c, it.ns, it.keys[i], it.onValues[i]) && ok;
            } else {
                int back = sp.getInt(CAP_PREFIX + it.keys[i], it.fallback[i]);
                ok = put(c, it.ns, it.keys[i], back) && ok;
            }
        }
        return ok;
    }

    // ──────────────────────────── 内部 ────────────────────────────

    private static final String SP = "power_cfg";
    private static final String CAP_PREFIX = "cap_";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
