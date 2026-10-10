package com.inkread.weekread.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 🆕 TASK-075：背景图的**读取 / 解析 / 解码 / 铺满**共享工具。
 *
 * <p><b>来源 = 纯搬移</b>：本类把原先散在 {@link LockPrefs#resolveBgFile} / {@link LockPrefs#toAbsolute}
 * 与 {@code a11y/LockOverlay.LockView} 的 {@code loadBg / decodeFile / decodeUri / sampleFor}
 * 以及 {@code drawBackground} 里的 {@code centerCrop} 段**原样搬过来**（逻辑一行不改），
 * 让「软锁」与「墨台」（第三覆盖窗）**共用同一套背景能力**，而不是各写一份。
 *
 * <p>🔴 <b>逐像素不变</b>：搬移后 {@code LockOverlay} 只把"怎么拿到 bitmap / 怎么铺满"改成
 * 调本类，绘制顺序、坐标算式、默认网点半径全部保持原值 ⇒ 锁屏同图 diff 必须 == 0（TASK-075 A3）。
 *
 * <p>⚠️ 无 {@code READ_EXTERNAL_STORAGE} 时 ①/② 会静默判"不存在"（分区存储行为）——
 * 这正是我们要的：拿不到就老老实实回退默认底，绝不因此弹不出界面。
 */
public final class BgImageUtil {

    /** 兜底候选目录（按序找同名文件）—— 图放哪儿都尽量能认出来。 */
    private static final String[] FALLBACK_DIRS = {
            "Pictures", "Picture", "DCIM", "Download", "Documents" };

    private BgImageUtil() {
    }

    // ── 解析：路径 → 实际可读文件 ──

    /**
     * 解析出**实际可读**的背景图文件；没有则返回 {@code null}（调用方回退浅色网点）。
     *
     * <p>顺序：① 用户设置的原路径（绝对路径直接用；相对路径拼共享存储根）
     * → ② 同名文件在常见图片目录（Pictures / Picture / DCIM / Download / Documents）
     * → ③ App 私有外部目录（<b>零权限</b>，可用 {@code adb push} 放图 ⇒ 作为"不给权限"时的兜底）。
     *
     * <p>🔴 本方法是 {@code LockPrefs.resolveBgFile} 的**参数化搬移**（原为"读 LockPrefs 里的路径"）。
     */
    public static File resolveBgFile(Context c, String path) {
        if (path == null) return null;
        if (path.length() == 0) return null;              // 未设置 ⇒ 浅色网点
        if (path.startsWith("content://")) return null;   // 历史 SAF URI：交给 ContentResolver

        File f = toAbsolute(path);
        if (isReadableFile(f)) return f;

        String name = new File(path).getName();
        if (name.length() > 0) {
            for (String dir : FALLBACK_DIRS) {
                File g = new File(new File(Environment.getExternalStorageDirectory(), dir), name);
                if (isReadableFile(g)) return g;
            }
            if (c != null) {
                File priv = c.getExternalFilesDir(null);   // 私有目录兜底（零权限）
                if (priv != null && isReadableFile(new File(priv, name))) {
                    return new File(priv, name);
                }
            }
        }
        return null;
    }

    /** 相对路径 → 拼共享存储根；绝对路径原样返回（也供"触发媒体扫描"用）。 */
    public static File toAbsolute(String path) {
        File f = new File(path);
        return f.isAbsolute() ? f : new File(Environment.getExternalStorageDirectory(), path);
    }

    private static boolean isReadableFile(File f) {
        try {
            return f != null && f.isFile() && f.canRead();
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 解码：总是回退 null（调用方绝因此崩）──

    /**
     * 总入口：按 {@code path} 拿到"按目标尺寸采样后的背景位图"；任何失败一律 {@code null}。
     *
     * <p>先走文件路径（含多目录兜底）；历史 {@code content://} URI 仍兼容。
     * 这是 {@code LockOverlay.loadBg} 的**参数化搬移**。
     */
    public static Bitmap load(Context c, String path, int w, int h) {
        try {
            File f = resolveBgFile(c, path);
            if (f != null) {
                Bitmap bm = decodeFile(f, w, h);
                if (bm != null) return bm;
            }
            if (path != null && path.startsWith("content://")) {
                return decodeUri(c, Uri.parse(path), w, h);
            }
        } catch (Throwable t) {
            // 读不出来就退回默认底，绝不因此弹不出界面
        }
        return null;
    }

    public static Bitmap decodeFile(File f, int w, int h) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = new FileInputStream(f);
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, w, h);
            InputStream in2 = new FileInputStream(f);
            Bitmap bm = BitmapFactory.decodeStream(in2, null, opt);
            in2.close();
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Bitmap decodeUri(Context c, Uri u, int w, int h) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(u);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, w, h);
            InputStream in2 = c.getContentResolver().openInputStream(u);
            if (in2 == null) return null;
            Bitmap bm = BitmapFactory.decodeStream(in2, null, opt);
            in2.close();
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 两遍解码的公共一步：按目标尺寸算 2 的幂采样率，避免整张大图进内存。 */
    private static int sampleFor(int ow, int oh, int w, int h) {
        int sample = 1;
        while (ow / (sample * 2) >= w && oh / (sample * 2) >= h) {
            sample *= 2;
        }
        return sample;
    }

    // ── 候选：给"自绘选图页"用的图片清单 ──

    /** 认可的图片后缀（小写比较）。 */
    private static final String[] IMG_EXT = { ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif" };

    /** 扫描的子目录（相对共享存储根）—— **非递归**，够覆盖绝大多数放图习惯。 */
    private static final String[] SCAN_SUBS = {
            "Pictures", "Picture", "DCIM", "DCIM/Camera", "Download", "Documents", "Pictures/微读墨记" };

    /**
     * 🆕 2026-10-10：为**墨台的自绘「选择背景图」**扫描候选图片。
     *
     * <p>🔴 <b>为什么必须自绘扫描、不能用系统选择器</b>：S4 ROM 缺
     * {@code com.android.documentsui} ⇒ {@code ACTION_OPEN_DOCUMENT / GET_CONTENT / PICK}
     * 在真机上全部 {@code No activities found}（本项目 {@code LockPrefs} §背景图 已实测登记）；
     * 且墨台是 {@code FLAG_NOT_FOCUSABLE} 的**覆盖窗**，塞不进 EditText（拿不到输入法）
     * ⇒ "填路径"这条老路在墨台内也走不通。剩下唯一可行且零新增权限的路 = 自己扫目录列出来让用户点。
     *
     * <p>扫描范围：{@link #SCAN_SUBS} 各子目录（**非递归**）+ App 私有外部目录（零权限兜底）。
     * 顺序 = 最后修改时间**倒序**（刚存的图排前面）；总数封顶 {@code max}，避免大目录卡顿。
     *
     * <p>⚠️ 无 {@code READ_EXTERNAL_STORAGE} 时公共目录会读到空表（分区存储行为）——
     * 这正是"如实反映能力"：调用方据此显示权限提示，而不是假装有一堆图。
     */
    public static List<File> candidates(Context c, int max) {
        List<File> out = new ArrayList<File>();
        if (max <= 0) return out;
        File root = null;
        try {
            root = Environment.getExternalStorageDirectory();
        } catch (Throwable ignored) {
        }
        for (String sub : SCAN_SUBS) {
            if (root == null) break;
            collect(new File(root, sub), out, max);
        }
        if (c != null) {
            try {
                collect(c.getExternalFilesDir(null), out, max);
            } catch (Throwable ignored) {
            }
        }
        // 按最后修改时间倒序（新的在前）—— 稳定排序，同刻的按路径
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long d = b.lastModified() - a.lastModified();
                if (d != 0L) return d > 0L ? 1 : -1;
                return a.getAbsolutePath().compareTo(b.getAbsolutePath());
            }
        });
        if (out.size() > max) return new ArrayList<File>(out.subList(0, max));
        return out;
    }

    private static void collect(File dir, List<File> out, int max) {
        if (dir == null || out.size() >= max * 3) return;      // 多收一点给排序留余量，但有硬上限
        try {
            if (!dir.isDirectory()) return;
            File[] fs = dir.listFiles();
            if (fs == null) return;
            for (File f : fs) {
                if (f == null || !f.isFile() || !f.canRead()) continue;
                if (!isImage(f.getName())) continue;
                out.add(f);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isImage(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        for (String e : IMG_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    // ── 铺满：centerCrop（原 LockOverlay.drawBackground 里的那段，算式一字不改）──

    /**
     * 把 {@code bg} 以 {@code centerCrop} 铺满 {@code w×h}（短边填满、长边居中裁切）。
     *
     * <p>🔴 逐像素搬移：{@code sc = max(w/bw, h/bh)}、居中偏移、{@code drawBitmap(src=null, dst)} 全同原式。
     */
    public static void drawCenterCrop(Canvas c, Bitmap bg, float w, float h, Paint p) {
        if (bg == null || bg.getWidth() <= 0 || bg.getHeight() <= 0) return;
        float sc = Math.max(w / bg.getWidth(), h / bg.getHeight());
        float dw = bg.getWidth() * sc, dh = bg.getHeight() * sc;
        float left = (w - dw) / 2f, top = (h - dh) / 2f;
        c.drawBitmap(bg, null, new RectF(left, top, left + dw, top + dh), p);
    }
}
