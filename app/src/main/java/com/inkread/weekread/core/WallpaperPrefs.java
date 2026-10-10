package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 🆕 TASK-078：**壁纸管家**的落盘（prefs 文件 `walls`）。
 *
 * <p>五键（`tasks/TASK-078` §落盘）：
 * <ul>
 *   <li>{@link #K_POOL} 图片池（CSV，**App 私有目录副本的绝对路径**）；</li>
 *   <li>{@link #K_INTERVAL} 轮换间隔（天，**&le;0 = 轮换关** ⇒ 不新增"开关"键）；</li>
 *   <li>{@link #K_SCOPE} 应用范围（{@code soft-lock} / {@code dream} / {@code both}）；</li>
 *   <li>{@link #K_INDEX} 当前序号；{@link #K_LAST} 上次轮换日期（`yyyy-MM-dd`）。</li>
 * </ul>
 *
 * <h3>🔴 三条硬约束</h3>
 * <ol>
 *   <li><b>图片池 = 私有目录副本</b>：{@link #add} 把源图 copy 进 {@code files/walls/}，
 *       文件名 = <b>sha1(源绝对路径)</b> ⇒ <b>同一源不重复收</b>（去重）；源图被删**不影响**已收图。</li>
 *   <li><b>不引入后台定时/闹钟</b>：轮换判据只在 {@link #effectiveSoftLockPath} 里**顺带判一次**
 *       （调用点 = 软锁绘背景），**幂等** —— 同一天多次打开**不重复推进**。</li>
 *   <li><b>默认行为完全不变</b>：池 &lt; 2 张 / 间隔关 / 范围不含软锁 ⇒
 *       {@link #effectiveSoftLockPath} 原样返回 {@link LockPrefs#getBgPath} ⇒ 软锁**逐像素不变**（A4）。</li>
 * </ol>
 */
public final class WallpaperPrefs {

    private static final String PREFS = "walls";

    private static final String K_POOL     = "wall_pool";
    private static final String K_INTERVAL = "wall_interval_days";
    private static final String K_SCOPE    = "wall_scope";
    private static final String K_INDEX    = "wall_index";
    private static final String K_LAST     = "wall_last_date";

    public static final String SCOPE_SOFT  = "soft-lock";
    public static final String SCOPE_DREAM = "dream";
    public static final String SCOPE_BOTH  = "both";
    public static final String[] SCOPE_LABELS = { "仅软锁", "仅屏保", "两者" };

    /** 默认间隔（天）—— 池为空时天然不轮换，故默认给 1 无副作用。 */
    public static final int DEFAULT_INTERVAL = 1;

    private WallpaperPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ══════════════════════ 轮换参数 ══════════════════════

    /** 轮换间隔（天）；**&le;0 = 关**。 */
    public static int intervalDays(Context c) {
        return sp(c).getInt(K_INTERVAL, DEFAULT_INTERVAL);
    }

    public static void setIntervalDays(Context c, int v) {
        sp(c).edit().putInt(K_INTERVAL, v).commit();
    }

    /** 轮换开关（**派生** = 间隔 &gt; 0；关 ⇒ 写 0，开 ⇒ 0 则回默认间隔）。 */
    public static boolean isRotationOn(Context c) {
        return intervalDays(c) > 0;
    }

    public static void setRotationOn(Context c, boolean on) {
        int cur = intervalDays(c);
        if (on) {
            if (cur <= 0) setIntervalDays(c, DEFAULT_INTERVAL);
        } else if (cur > 0) {
            setIntervalDays(c, 0);
        }
    }

    public static String scope(Context c) {
        String s = sp(c).getString(K_SCOPE, SCOPE_SOFT);
        if (SCOPE_DREAM.equals(s) || SCOPE_BOTH.equals(s)) return s;
        return SCOPE_SOFT;
    }

    public static void setScope(Context c, String v) {
        String s = (SCOPE_DREAM.equals(v) || SCOPE_BOTH.equals(v)) ? v : SCOPE_SOFT;
        sp(c).edit().putString(K_SCOPE, s).commit();
    }

    /** 范围的中文标签（如实；`dream` 未验证前 UI 须置灰并提示 —— 见 `TASK-079`/`081`）。 */
    public static String scopeLabel(Context c) {
        String s = scope(c);
        if (SCOPE_DREAM.equals(s)) return SCOPE_LABELS[1];
        if (SCOPE_BOTH.equals(s))  return SCOPE_LABELS[2];
        return SCOPE_LABELS[0];
    }

    /**
     * 轮换**是否真的作用于软锁**（= 开关开了 **且** 范围包含软锁）。
     *
     * <p>🔴 供 UI **如实提示**用（R4）：`scope=dream` 时轮换对软锁**无效果**，
     * 因为 Dream 屏保侧要到 {@code TASK-081} 才接——UI **不得**写成"已支持 Daydream"。
     */
    public static boolean rotatesSoftLock(Context c) {
        return intervalDays(c) > 0 && !SCOPE_DREAM.equals(scope(c));
    }

    public static int index(Context c) {
        return sp(c).getInt(K_INDEX, 0);
    }

    public static void setIndex(Context c, int v) {
        sp(c).edit().putInt(K_INDEX, v < 0 ? 0 : v).commit();
    }

    public static String lastDate(Context c) {
        return sp(c).getString(K_LAST, "");
    }

    public static void setLastDate(Context c, String v) {
        sp(c).edit().putString(K_LAST, v == null ? "" : v).commit();
    }

    // ══════════════════════ 图片池 ══════════════════════

    /** 池目录：`files/walls/`（App 私有 ⇒ 删源图不影响、无需存储权限）。 */
    public static File poolDir(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "walls");
    }

    /** 池内**仍存在**的副本路径（文件被误删 ⇒ 自动过滤；**本方法不写盘**）。 */
    public static List<String> pool(Context c) {
        List<String> out = new ArrayList<String>();
        String csv = sp(c).getString(K_POOL, "");
        if (csv == null || csv.length() == 0) return out;
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() == 0) continue;
            if (new File(p).isFile()) out.add(p);
        }
        return out;
    }

    private static void writePool(Context c, List<String> p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.size(); i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.get(i));
        }
        sp(c).edit().putString(K_POOL, sb.toString()).commit();
    }

    /**
     * **收图**：把源图 copy 进私有目录并加入池。
     *
     * @return true = 已加入（或该源**已在池中**）；false = 源不存在 / 拷贝失败
     */
    public static boolean add(Context c, String srcPath) {
        if (srcPath == null) return false;
        String p = srcPath.trim();
        if (p.length() == 0) return false;
        File src = BgImageUtil.toAbsolute(p);
        if (src == null || !src.isFile()) return false;

        File dir = poolDir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;

        // 🔴 同一源 ⇒ 同一文件名 ⇒ 天然去重（"同一源图不重复收"）
        File dst = new File(dir, poolFileName(src));
        if (!dst.isFile() && !copy(src, dst)) return false;

        List<String> list = new ArrayList<String>(pool(c));
        String dstPath = dst.getAbsolutePath();
        if (!list.contains(dstPath)) list.add(dstPath);
        writePool(c, list);
        return true;
    }

    /**
     * 🆕 TASK-079：**重收**同一源 —— 先删掉池里那份旧副本，再把当前文件收进来。
     *
     * <p>为什么需要单开一个方法：{@link #add} 对同一源路径是**去重**的（"同一源不重复收"），
     * 而「生成账单壁纸」恰恰是**同一路径、内容会变**（重新生成同一期 ⇒ 覆盖
     * {@code files/wallbill/bill_<mode>_<start>.png}）⇒ 只走 {@code add} 会留下池里那张
     * **旧画面**（路径去重命中、不重新拷贝）。本方法把"重收"这条语义补齐。
     *
     * @return true = 已（重新）加入
     */
    public static boolean replace(Context c, String srcPath) {
        if (srcPath == null) return false;
        String p = srcPath.trim();
        if (p.length() == 0) return false;
        File src = BgImageUtil.toAbsolute(p);
        if (src == null || !src.isFile()) return false;

        File dst = new File(poolDir(c), poolFileName(src));
        if (dst.isFile()) dst.delete();
        if (dst.isFile()) dst.delete();
        List<String> list = new ArrayList<String>(pool(c));
        if (list.remove(dst.getAbsolutePath())) writePool(c, list);
        return add(c, srcPath);
    }

    /** 从池里移除一项（并删副本文件）；**索引随之收敛**。 */
    public static boolean remove(Context c, String path) {
        if (path == null) return false;
        List<String> list = new ArrayList<String>(pool(c));
        if (!list.remove(path)) return false;
        File f = new File(path);
        if (f.isFile() && !f.delete()) {
            CardDebug.noteV(c, "wall: 副本删除失败 " + path);      // 不阻断（下次 prune 再试）
        }
        writePool(c, list);
        int idx = index(c);
        if (list.isEmpty()) setIndex(c, 0);
        else if (idx >= list.size()) setIndex(c, list.size() - 1);
        return true;
    }

    /**
     * **清空图片池**：删掉池内全部副本 + 复位序号。
     *
     * <p>🔴 <b>不动</b>「间隔 / 范围」—— 那两个是用户的偏好设置，清池只清内容。
     * 池空 ⇒ {@link #effectiveSoftLockPath} 自然短路回 {@link LockPrefs#getBgPath}
     * （软锁回到改动前行为，A4 由此自动成立）。
     *
     * @return 实际删掉的副本数
     */
    public static int clear(Context c) {
        List<String> list = pool(c);
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            File f = new File(list.get(i));
            if (f.isFile() && f.delete()) n++;
        }
        writePool(c, new ArrayList<String>());
        setIndex(c, 0);
        setLastDate(c, "");
        File dir = poolDir(c);
        if (dir.isDirectory()) {
            String[] rest = dir.list();
            if (rest != null) {
                for (int i = 0; i < rest.length; i++) new File(dir, rest[i]).delete();
            }
        }
        return n;
    }

    // ══════════════════════ 轮换（唯一入口） ══════════════════════
    /**
     * 🔴 **轮换与软锁的唯一接缝** —— 软锁绘背景时问它"这一帧该显示哪张"。
     *
     * <p>四条短路（任一成立 ⇒ **完全等价于改动前**）：间隔关 / 池 &lt; 2 张 / 范围不含软锁 / 池为空。
     * 否则：**顺带幂等推进**（到期才换、同日不换）后返回池中当前那张的绝对路径。
     */
    public static String effectiveSoftLockPath(Context c) {
        String base = LockPrefs.getBgPath(c);
        if (intervalDays(c) <= 0) return base;                       // 轮换关
        if (SCOPE_DREAM.equals(scope(c))) return base;               // 只屏保 ⇒ 软锁不换
        List<String> p = pool(c);
        if (p.size() < 2) return base;                               // 池不足 ⇒ 不轮换
        rotateIfDue(c, p.size());
        int i = index(c);
        if (i < 0 || i >= p.size()) {
            i = 0;
            setIndex(c, 0);
        }
        return p.get(i);
    }

    /**
     * **到期才推进**（幂等）：首见只记日期；同日不换；跨 ≥N 天则按 `days / N` 一次补多期。
     */
    private static void rotateIfDue(Context c, int size) {
        String today = todayStr();
        String last = lastDate(c);
        if (last == null || last.length() == 0) {
            setLastDate(c, today);
            return;
        }
        if (today.equals(last)) return;
        int iv = Math.max(1, intervalDays(c));
        long days = daysBetween(last, today);
        if (days < iv) return;
        int steps = (int) (days / iv);
        int idx = index(c) + steps;
        if (idx < 0) idx = 0;
        setIndex(c, idx % size);
        setLastDate(c, today);
    }

    /** 「换一张」：**立即**推进一张（并记今天 ⇒ 当日不再被自动推进）。 */
    public static void rotateNow(Context c) {
        List<String> p = pool(c);
        if (p.size() < 2) return;
        int idx = index(c);
        if (idx < 0 || idx >= p.size()) idx = 0;
        setIndex(c, (idx + 1) % p.size());
        setLastDate(c, todayStr());
    }

    /** 当前应显示的那张（**给墨台用**，不推进；无池 ⇒ null）。 */
    public static String currentPath(Context c) {
        List<String> p = pool(c);
        if (p.isEmpty()) return null;
        int i = index(c);
        if (i < 0 || i >= p.size()) i = 0;
        return p.get(i);
    }

    // ══════════════════════ 工具 ══════════════════════

    public static String todayStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static long daysBetween(String a, String b) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            long ta = f.parse(a).getTime();
            long tb = f.parse(b).getTime();
            return (tb - ta) / 86400000L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 🆕 TASK-079：池内副本的**文件名** = {@code sha1(源绝对路径) + "_" + 源文件名(净化)}。
     *
     * <p>为什么不是纯 sha1：实验室「已收」列表直接把文件名念给用户听 —— 一串 sha1 对用户
     * 毫无意义（TASK-079 上机可见性实测后改）。前缀仍是 sha1 ⇒ **同一源依然天然去重**。
     *
     * <p>净化规则（只为"放进 CSV 偏好 + 文件名"安全）：去掉半角逗号（池是 CSV）、
     * 路径分隔符、控制字符；名字截到 24 个字符。
     */
    private static String poolFileName(File src) {
        String n = src.getName();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length() && sb.length() < 32; i++) {
            char ch = n.charAt(i);
            if (ch == ',' || ch == '/' || ch == '\\' || ch < 32) continue;
            sb.append(ch);
        }
        if (sb.length() == 0) sb.append("img");
        return sha1(src.getAbsolutePath()) + "_" + sb;
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                sb.append(String.format(Locale.US, "%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static boolean copy(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
        }
    }
}
