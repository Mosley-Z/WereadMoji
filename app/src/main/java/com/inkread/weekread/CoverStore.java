package com.inkread.weekread;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 封面图的下载与缓存（v0.3.5.1，本书形态加封面）。
 *
 * ── 数据从哪来 ──
 * 书架接口 `/shelf/sync` 的每本书自带 `cover` 字段（CDN 直连、无需鉴权，
 * 2026-09-22 实测 t6 规格 250×360 ≈ 81KB）。{@link BookStore#compactShelf}
 * 已把它留在压缩后的书架缓存里，{@link WereadApi} 塞进 {@link BookStats#coverUrl}。
 *
 * ── 缓存策略 ──
 * 按 bookId 落盘 `files/covers/<bookId>.jpg`：封面几乎永不变，**只下一次**，
 * 之后全走本地。解码时按显示宽度做 `inSampleSize` 下采样（显示只要 ~110px 宽，
 * 源图 250px → 采样 2 即可），内存里只留小图。
 *
 * ── 失败语义 ──
 * 没缓存又下载失败（离线 / 无 cover 字段）→ **不回调**，视图画描边占位框。
 * 坏文件（解码失败）→ 删掉重试一次网络。
 *
 * 回调一律切主线程（墨水屏重绘必须串行在 UI 线程）。
 */
public final class CoverStore {

    private CoverStore() {
    }

    /** 下载/解码完成后的回调（**主线程**）。bmp 为 null 表示拿不到（视图画占位框） */
    public interface Callback {
        void onCover(Bitmap bmp);
    }

    /** 缓存目录名（在 getExternalFilesDir 下，与 card_debug.log 同级，卸载即清） */
    private static final String DIR = "covers";
    /** 下载超时（毫秒）。封面不急，放宽一点减少失败率 */
    private static final int TIMEOUT_MS = 15_000;
    /** 正在下载的 bookId（防重复触发；同进程两个界面共用本类） */
    private static String inFlight = null;

    private static File fileFor(Context c, String bookId) {
        File dir = new File(c.getExternalFilesDir(null), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, bookId + ".jpg");
    }

    /**
     * 同步读缓存（**只读盘、不发网络**）。视图刷新时可先调它立刻出图，
     * 没有就先画占位框，再交给 {@link #loadAsync} 异步补。
     */
    public static Bitmap peek(Context c, String bookId, int maxW) {
        if (bookId == null || bookId.length() == 0) return null;
        try {
            return decode(fileFor(c, bookId), maxW);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 异步取封面：有缓存读缓存，没缓存按 url 下载后落盘。
     * 结果（含失败）在主线程回调；url 为空且无缓存时回调 null。
     */
    public static void loadAsync(final Context c, final String bookId,
                                 final String url, final int maxW, final Callback cb) {
        if (bookId == null || bookId.length() == 0 || cb == null) return;
        final Bitmap cached = peek(c, bookId, maxW);
        if (cached != null) {
            cb.onCover(cached);
            return;
        }
        if (url == null || url.length() == 0) {
            cb.onCover(null);
            return;
        }
        if (bookId.equals(inFlight)) return;           // 已经在下了，别重复
        inFlight = bookId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = download(c, bookId, url);
                } catch (Throwable ignored) {
                }
                final Bitmap bmp = ok ? peek(c, bookId, maxW) : null;
                inFlight = null;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onCover(bmp);
                    }
                });
            }
        }).start();
    }

    /** 下载到缓存文件。成功返回 true */
    private static boolean download(Context c, String bookId, String url) {
        FileOutputStream out = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11)");
            int code = conn.getResponseCode();
            if (code != 200) return false;
            InputStream in = conn.getInputStream();
            File f = fileFor(c, bookId);
            out = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            in.close();
            // 解码验一遍：坏的（CDN 偶发返回错误页）当场删掉，别留垃圾
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                f.delete();
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 按目标宽度下采样解码（省内存：显示 ~110px 宽时内存只要几十 KB） */
    private static Bitmap decode(File f, int maxW) {
        if (f == null || !f.exists() || !f.isFile() || f.length() == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            f.delete();                                // 缓存坏了 → 删，下次重新下
            return null;
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxW) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        if (b != null && b.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap cp = b.copy(Bitmap.Config.ARGB_8888, false);
                b.recycle();
            b = cp;
        }
        return b;
    }
}
