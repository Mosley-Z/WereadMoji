package com.inkread.weekread.feature;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.inkread.weekread.core.PowerSettingsManager;

/**
 * 🆕 TASK-081：**Daydream 屏保**的开关 + **原值备份 / 一键还原**。
 *
 * <p>落在 {@code feature} 包（而非 {@code core}）：它需要引用本包的
 * {@link DreamWallpaperService} 来拼组件名，而 🔴 <b>{@code core} 不得反向依赖 {@code feature}</b>。
 *
 * <h3>受控的三个 {@link Settings.Secure} 键</h3>
 * <table border="1">
 *   <tr><th>键</th><th>本机出厂值</th><th>开启时写入</th></tr>
 *   <tr><td>{@code screensaver_components}</td><td>DeskClock 的 Screensaver</td><td>本 App 的 DreamService</td></tr>
 *   <tr><td>{@code screensaver_activate_on_sleep}</td><td>0</td><td><b>1</b></td></tr>
 *   <tr><td>{@code screensaver_enabled}</td><td>1</td><td>1</td></tr>
 * </table>
 *
 * <h3>🔴 三条硬约束（见 `TASK-081`）</h3>
 * <ol>
 *   <li><b>默认关</b>：不调用 {@link #enable} 时，三个键一个不碰（{@link #isOn} = false）。</li>
 *   <li><b>先备份再写</b>：首次开启时把三个键的**当前值逐键留档**（{@code cap_*}），
 *       {@link #restore} 逐键写回 ⇒ A3「逐键比对一致」。</li>
 *   <li><b>幂等</b>：重复 {@link #enable} **不覆盖**已留的备份（否则会把"我们自己"当成原值）。</li>
 * </ol>
 *
 * <p>🔴 写入需 {@code WRITE_SECURE_SETTINGS}（已在清单里、TASK-023 通道）。无权限时
 * 全部降级为 {@code false}，由 UI 提示去 grant（⛔ 不崩溃、不改任何键）。
 */
public final class DreamPrefs {

    private static final String TAG = "DreamWall081";

    private static final String SP = "dream_cfg";
    private static final String K_CAP_DONE  = "cap_done";
    private static final String K_CAP_COMP  = "cap_components";
    private static final String K_CAP_SLEEP = "cap_on_sleep";
    private static final String K_CAP_ENABLED = "cap_enabled";

    /** 三个受控的 Secure 键 */
    private static final String S_COMPONENTS = "screensaver_components";
    private static final String S_ON_SLEEP   = "screensaver_activate_on_sleep";
    private static final String S_ENABLED    = "screensaver_enabled";

    /** 无备份时的兜底（本机实测基线；只在"备份丢了"这种异常路径上用到） */
    private static final int FB_ON_SLEEP = 0;
    private static final int FB_ENABLED  = 1;

    private DreamPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 本 App 屏保组件的扁平名（{@code 包名/类全名}）—— 写进 {@code screensaver_components} 的值。 */
    public static String component(Context c) {
        return new ComponentName(c.getApplicationContext(), DreamWallpaperService.class)
                .flattenToString();
    }

    // ══════════════════════ 状态 ══════════════════════

    /** 能否写 Secure（={@code WRITE_SECURE_SETTINGS} 是否已授权）。 */
    public static boolean canWrite(Context c) {
        return PowerSettingsManager.hasPermission(c);
    }

    /** 🔴 开关态一律以**设备真值**为准（不读偏好记录）—— 与 {@code PowerSettingsManager} 同一纪律。 */
    public static boolean isOn(Context c) {
        String cur = Settings.Secure.getString(c.getContentResolver(), S_COMPONENTS);
        return component(c).equals(cur)
                && readInt(c, S_ON_SLEEP, 0) == 1
                && readInt(c, S_ENABLED, 1) == 1;
    }

    /** 「组件已是我们、但激活条件没配全」——用于如实提示（用户可能只改了一半）。 */
    public static boolean isPartial(Context c) {
        String cur = Settings.Secure.getString(c.getContentResolver(), S_COMPONENTS);
        return component(c).equals(cur) && !isOn(c);
    }

    // ══════════════════════ 开 / 关 ══════════════════════

    /**
     * **开启**：备份原值（仅首次）→ 写三个键。
     *
     * @return true = 三键都写入成功（无权限 / 被系统拒绝时为 false）
     */
    public static boolean enable(Context c) {
        if (!canWrite(c)) {
            Log.i(TAG, "enable: no WRITE_SECURE_SETTINGS");
            return false;
        }
        SharedPreferences p = sp(c);
        if (!p.getBoolean(K_CAP_DONE, false)) {
            String curComp = Settings.Secure.getString(c.getContentResolver(), S_COMPONENTS);
            // 🔴 当前值**已是我们自己** ⇒ 不备份（否则还原会把"我们"当原值，永远退不回去）
            if (!component(c).equals(curComp)) {
                p.edit()
                        .putString(K_CAP_COMP, curComp == null ? "" : curComp)
                        .putInt(K_CAP_SLEEP, readInt(c, S_ON_SLEEP, FB_ON_SLEEP))
                        .putInt(K_CAP_ENABLED, readInt(c, S_ENABLED, FB_ENABLED))
                        .putBoolean(K_CAP_DONE, true)
                        .commit();
                Log.i(TAG, "enable: 备份原值 components=" + curComp
                        + " on_sleep=" + p.getInt(K_CAP_SLEEP, -1)
                        + " enabled=" + p.getInt(K_CAP_ENABLED, -1));
            }
        }
        boolean ok = true;
        ok = writeString(c, S_COMPONENTS, component(c)) && ok;
        ok = writeInt(c, S_ON_SLEEP, 1) && ok;
        ok = writeInt(c, S_ENABLED, 1) && ok;
        Log.i(TAG, "enable: ok=" + ok + " isOn=" + isOn(c));
        return ok;
    }

    /**
     * **一键还原**：把三个键**逐键写回备份原值**，并清掉备份标记（下次开启重新捕获）。
     *
     * @return true = 全部写回成功；false = 无备份（从未开启过）或无权限
     */
    public static boolean restore(Context c) {
        if (!canWrite(c)) {
            Log.i(TAG, "restore: no WRITE_SECURE_SETTINGS");
            return false;
        }
        SharedPreferences p = sp(c);
        if (!p.getBoolean(K_CAP_DONE, false)) {
            Log.i(TAG, "restore: 无备份 ⇒ 不动任何键");
            return false;
        }
        String comp = p.getString(K_CAP_COMP, "");
        int sleep = p.getInt(K_CAP_SLEEP, FB_ON_SLEEP);
        int enabled = p.getInt(K_CAP_ENABLED, FB_ENABLED);
        boolean ok = true;
        ok = writeString(c, S_COMPONENTS, comp) && ok;
        ok = writeInt(c, S_ON_SLEEP, sleep) && ok;
        ok = writeInt(c, S_ENABLED, enabled) && ok;
        p.edit().remove(K_CAP_DONE).remove(K_CAP_COMP)
                .remove(K_CAP_SLEEP).remove(K_CAP_ENABLED).commit();
        Log.i(TAG, "restore: ok=" + ok + " components=" + comp
                + " on_sleep=" + sleep + " enabled=" + enabled);
        return ok;
    }

    /** 备份原值（供验证时逐键比对；未备份过 ⇒ null）。 */
    public static String backupLine(Context c) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean(K_CAP_DONE, false)) return null;
        return "components=" + p.getString(K_CAP_COMP, "")
                + " on_sleep=" + p.getInt(K_CAP_SLEEP, -1)
                + " enabled=" + p.getInt(K_CAP_ENABLED, -1);
    }

    // ══════════════════════ 读写（读写都不崩、失败即 false） ══════════════════════

    private static int readInt(Context c, String key, int def) {
        try {
            return Settings.Secure.getInt(c.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean writeInt(Context c, String key, int v) {
        try {
            return Settings.Secure.putInt(c.getContentResolver(), key, v);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean writeString(Context c, String key, String v) {
        try {
            return Settings.Secure.putString(c.getContentResolver(), key, v == null ? "" : v);
        } catch (Throwable t) {
            return false;
        }
    }
}
