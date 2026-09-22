package com.inkread.weekread;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 下载更新包、校验、拉起系统安装器。
 *
 * 几个刻意的选择：
 *  · 下载到 getExternalFilesDir()/apk —— App 私有目录，**不需要任何存储权限**。
 *  · sha256 校验通过才改名成正式文件，避免半个包被安装。
 *  · 进度**按 10% 步进回调**：墨水屏刷新一次要几百毫秒，百分比逐格刷会糊屏。
 *  · 拉起安装器走 ApkProvider（不能用 file://，见该类注释）。
 */
public final class ApkInstaller {

    /** 固定文件名 —— ApkProvider 的白名单就是它 */
    public static final String APK_FILE_NAME = "update.apk";

    private static final String PART_SUFFIX = ".part";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;

    private ApkInstaller() {
    }

    // ────────────────────────── 回调 ──────────────────────────

    public interface Progress {
        /** percent 为 10 / 20 … / 100，或 -1（服务器没给长度） */
        void onProgress(int percent);

        /** 成功时 apk 非空；失败时 error 非空 */
        void onDone(File apk, String error);
    }

    // ────────────────────────── 路径与权限 ──────────────────────────

    public static File apkDir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "apk");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** Android 8.0 起安装未知来源应用是「按应用授权」的 */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return c.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /** 引导用户去开「允许安装未知应用」；低版本返回 null 表示无需引导 */
    public static Intent unknownSourcesIntent(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            it.setData(Uri.parse("package:" + c.getPackageName()));
            return it;
        }
        return null;
    }

    /** 清掉上次没下完的残包（App 启动时调用） */
    public static void cleanupPartial(Context c) {
        File[] fs = apkDir(c).listFiles();
        if (fs == null) {
            return;
        }
        for (File f : fs) {
            if (f.getName().endsWith(PART_SUFFIX)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    // ────────────────────────── 下载 ──────────────────────────

    public static void download(final Context context, final UpdateChecker.Info info,
                                final Progress p) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String lastErr = null;

                String[] urls = (info == null) ? new String[0] : info.urls;
                for (String url : urls) {
                    try {
                        File apk = downloadOne(app, url, info, p);
                        CardDebug.note(app, "[upd] 下载完成 " + apk.length() + "B via " + url);
                        post(p, apk, null);
                        return;
                    } catch (Exception e) {
                        lastErr = (e.getMessage() == null) ? e.getClass().getSimpleName()
                                : e.getMessage();
                        CardDebug.note(app, "[upd] 下载源失败 " + url + " : " + lastErr);
                    }
                }

                post(p, null, (lastErr == null) ? "下载地址为空" : lastErr);
            }
        }).start();
    }

    private static File downloadOne(Context app, String urlStr, UpdateChecker.Info info,
                                    Progress p) throws Exception {
        File dir = apkDir(app);
        File tmp = new File(dir, APK_FILE_NAME + PART_SUFFIX);
        File dst = new File(dir, APK_FILE_NAME);

        HttpURLConnection conn = null;
        FileOutputStream fos = null;
        InputStream is = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WereadMoji");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }

            long total = conn.getContentLength();   // 可能为 -1
            is = conn.getInputStream();
            fos = new FileOutputStream(tmp);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            long got = 0L;
            int lastStep = -1;
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                md.update(buf, 0, n);
                got += n;
                if (total > 0) {
                    int step = (int) (got * 100 / total) / 10;    // 10% 一档
                    if (step != lastStep) {
                        lastStep = step;
                        postProgress(p, step * 10);
                    }
                }
            }

            String sha = hex(md.digest());
            if (info != null && info.sha256 != null && info.sha256.length() > 0
                    && !info.sha256.equalsIgnoreCase(sha)) {
                throw new Exception("校验失败（包可能被篡改或下载不完整）");
            }

            if (dst.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
            }
            if (!tmp.renameTo(dst)) {
                throw new Exception("无法写入目标文件");
            }
            return dst;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ────────────────────────── 安装 ──────────────────────────

    /** 拉起系统安装器。调用前请先确认 canInstall()，并收起桌面卡片避免遮挡安装界面。 */
    public static void install(Context c, File apk) {
        Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + apk.getName());
        Intent it = new Intent(Intent.ACTION_VIEW);
        it.setDataAndType(uri, "application/vnd.android.package-archive");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(it);
    }

    // ────────────────────────── 小工具 ──────────────────────────

    private static void postProgress(final Progress p, final int pct) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                p.onProgress(pct);
            }
        });
    }

    private static void post(final Progress p, final File apk, final String err) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                p.onDone(apk, err);
            }
        });
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            int v = x & 0xFF;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
