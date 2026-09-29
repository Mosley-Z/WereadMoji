package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 锁屏密码偏好（TASK-022，V1.0.1-beta）—— 应用级「软锁」的落盘。
 *
 * <p>与 {@link CardPrefs} 共用同一个 {@code cfg} 偏好文件（同一个 App、同一个进程，
 * 没必要为几个字段另开一份）。
 *
 * <p>🔴 <b>安全边界（如实登记，别在文案里吹）</b>：这是<b>防误触 / 防好奇</b>的软锁，
 * <b>不是</b>设备安全保护 —— 锁屏层可被 Home / 返回键绕过、重启后失效、清 App 数据即抹掉。
 * 这里做哈希<b>只为了"不像明文那样直接摆在偏好里"</b>，并不宣称抗暴力破解
 * （4 位数字只有 1 万种组合，「能绕过」才是真正的现实防线）。
 *
 * <p>落盘字段：
 * <ul>
 *   <li>{@code lock_enabled}  —— 是否启用锁屏（默认 {@code false}，验收 A6：老用户升级后零差异）</li>
 *   <li>{@code lock_pin_salt} —— 随机盐（8 字节，hex）</li>
 *   <li>{@code lock_pin_hash} —— {@code SHA-256(salt ‖ pin)}（hex），<b>不存明文</b>（验收 A3）</li>
 *   <li>{@code lock_bg_uri}   —— 自选背景图 URI（SAF 授权；空 = 用默认浅色网点，验收 A2）</li>
 * </ul>
 */
public final class LockPrefs {

    /** 默认关：不设密码、不打开 ⇒ 与现状（V1.0.0-beta.1）完全一致（验收 A6）。 */
    public static final boolean DEFAULT_ENABLED = false;

    /** 固定 4 位数字（P4 拍板：不做图案 / 生物识别 / 多密码）。 */
    public static final int PIN_LENGTH = 4;

    private static final String K_ENABLED = "lock_enabled";
    private static final String K_SALT = "lock_pin_salt";
    private static final String K_HASH = "lock_pin_hash";
    private static final String K_BG = "lock_bg_uri";

    private LockPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    // ── 开关 ──

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, DEFAULT_ENABLED);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_ENABLED, v).commit();
    }

    // ── 密码 ──

    /** 是否已经设过密码（盐 + 哈希都在，才算"设过"）。 */
    public static boolean hasPin(Context c) {
        String h = sp(c).getString(K_HASH, null);
        String s = sp(c).getString(K_SALT, null);
        return h != null && h.length() > 0 && s != null && s.length() > 0;
    }

    /**
     * 锁屏<b>此刻是否应生效</b> = 已启用 <b>且</b> 已设密码。
     *
     * <p>两者缺一都不锁 —— 宁可"该锁没锁"，也**绝不能**在没设密码的情况下把用户关在外面。
     */
    public static boolean isActive(Context c) {
        return isEnabled(c) && hasPin(c);
    }

    /** 校验是不是「4 位纯数字」。非法一律 false，由调用方给提示。 */
    public static boolean isValidPin(String pin) {
        if (pin == null || pin.length() != PIN_LENGTH) return false;
        for (int i = 0; i < pin.length(); i++) {
            char ch = pin.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /**
     * 设置 / 修改密码：生成随机盐 → 存 {@code salt} 与 {@code SHA-256(salt ‖ pin)}。
     * 非法密码直接忽略（调用方先用 {@link #isValidPin} 校验并提示）。
     *
     * @return true = 已写入
     */
    public static boolean setPin(Context c, String pin) {
        if (!isValidPin(pin)) return false;
        byte[] salt = new byte[8];
        new SecureRandom().nextBytes(salt);
        sp(c).edit()
                .putString(K_SALT, toHex(salt))
                .putString(K_HASH, toHex(sha256(salt, pin)))
                .commit();
        return true;
    }

    /** 清掉密码（同时把开关关掉 —— 留着"已启用但无密码"是没意义的中间态）。 */
    public static void clearPin(Context c) {
        sp(c).edit()
                .remove(K_SALT)
                .remove(K_HASH)
                .putBoolean(K_ENABLED, false)
                .commit();
    }

    /**
     * 比对明文密码。
     *
     * <p>明文只在这里出现一次、用完即弃；比对用 {@link MessageDigest#isEqual}
     * （长度相同则常数时间比较，不因"第几位不同"而提前返回）。
     */
    public static boolean verifyPin(Context c, String pin) {
        if (!isValidPin(pin)) return false;
        String saltHex = sp(c).getString(K_SALT, null);
        String hashHex = sp(c).getString(K_HASH, null);
        if (saltHex == null || hashHex == null) return false;
        byte[] salt = fromHex(saltHex);
        if (salt == null) return false;
        byte[] got = sha256(salt, pin);
        if (got == null) return false;
        return MessageDigest.isEqual(got, fromHexOrEmpty(hashHex));
    }

    // ── 背景图（SAF，零权限）──

    /** 自选背景图 URI（可能为空串 = 用默认浅色网点）。 */
    public static String getBgUri(Context c) {
        String v = sp(c).getString(K_BG, "");
        return v == null ? "" : v;
    }

    public static void setBgUri(Context c, String uri) {
        sp(c).edit().putString(K_BG, uri == null ? "" : uri).commit();
    }

    public static void clearBg(Context c) {
        sp(c).edit().remove(K_BG).commit();
    }

    // ── 内部：哈希 / hex ──

    /** 拼装 {@code SHA-256(salt ‖ pin)}：盐在前、明文在后，UTF-8 编码。失败返回 null。 */
    private static byte[] sha256(byte[] salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(pin.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String toHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /** hex → 字节；非法（含奇数长度 / 非 hex 字符）返回 null。 */
    private static byte[] fromHex(String s) {
        if (s == null || s.length() == 0 || (s.length() & 1) != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 给比对用：读不出（脏偏好）时给空数组，让 isEqual 直接判不等，别抛异常。 */
    private static byte[] fromHexOrEmpty(String s) {
        byte[] b = fromHex(s);
        return b == null ? new byte[0] : b;
    }
}
