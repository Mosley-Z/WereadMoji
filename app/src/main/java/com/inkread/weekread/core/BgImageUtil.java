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
