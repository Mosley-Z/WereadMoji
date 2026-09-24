package com.inkread.weekread.net;

import com.inkread.weekread.core.CardDebug;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * 检查更新：读取仓库里的版本清单 dist/update.json，与自身 versionCode 比较（整数比较）。
 *
 * 「有没有新版本」只认 versionCode，不比较 versionName 字符串。
 *
 * 清单有两个源，按顺序尝试：
 *   ① raw.githubusercontent.com —— 缓存只有几分钟，能立刻反映新版本，作主源
 *   ② cdn.jsdelivr.net          —— CDN 更快，但分支引用(@main)有数小时缓存，作回落
 * 两个都不可达才算失败。
 *
 * ⚠ 实测过：部分网络下 github.com 不可达，所以清单地址**不能**用 github.com 域名。
 *   APK 的下载地址由清单里的 urls 字段给出（jsDelivr@tag 优先），不在本类里拼。
 */
public final class UpdateChecker {

    /** GitHub 仓库（与 README、jsDelivr 路径、tools/build.sh 保持一致） */
    public static final String REPO = "Mosley-Z/WereadMoji";

    private static final String[] MANIFEST_URLS = {
            "https://raw.githubusercontent.com/" + REPO + "/main/dist/update.json",
            "https://cdn.jsdelivr.net/gh/" + REPO + "@main/dist/update.json",
    };

    private static final int TIMEOUT_MS = 8000;

    /** 静默检查的节流间隔：一天至多一次 */
    private static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private static final String SP_NAME = "cfg";
    private static final String K_LAST_CHECK = "upd_last_check";
    private static final String K_REMOTE_CODE = "upd_remote_code";
    private static final String K_REMOTE_NAME = "upd_remote_name";
    private static final String K_REMOTE_NOTES = "upd_remote_notes";

    private UpdateChecker() {
    }

    // ────────────────────────── 数据与回调 ──────────────────────────

    /** 清单内容。urls 是给下载器按顺序尝试的地址。 */
    public static final class Info {
        public int versionCode;
        public String versionName = "";
        public String notes = "";
        public long size;
        public String sha256 = "";
        public String apk = "";
        public String[] urls = new String[0];

        public String primaryUrl() {
            return (urls.length > 0) ? urls[0] : "";
        }
    }

    public interface Callback {
        /** info 非空 = 查到清单；error 非空 = 失败（此时 info 为 null） */
        void onResult(Info info, String error);
    }

    // ────────────────────────── 自身版本 ──────────────────────────

    public static int currentVersionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return (v == null) ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** 清单版本是否比本机新 */
    public static boolean isNewer(Context c, Info info) {
        return info != null && info.versionCode > currentVersionCode(c);
    }

    // ────────────────────────── 节流 ──────────────────────────

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    private static boolean shouldAutoCheck(Context c) {
        long last = sp(c).getLong(K_LAST_CHECK, 0L);
        return System.currentTimeMillis() - last >= AUTO_INTERVAL_MS;
    }

    private static void markChecked(Context c) {
        sp(c).edit().putLong(K_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    /**
     * 把查到的远端版本记下来。
     *
     * 静默检查的意义就在这：启动时顺手查一次、结果落盘，用户之后打开设置页
     * 立刻就能看到「有新版本」，**不必弹窗打断**（墨水屏上弹窗尤其讨厌）。
     */
    private static void saveRemote(Context c, Info info) {
        sp(c).edit()
                .putInt(K_REMOTE_CODE, info.versionCode)
                .putString(K_REMOTE_NAME, info.versionName)
                .putString(K_REMOTE_NOTES, info.notes)
                .apply();
    }

    /** 上次查到的远端 versionCode（0 = 还没查到过） */
    public static int remoteVersionCode(Context c) {
        return sp(c).getInt(K_REMOTE_CODE, 0);
    }

    public static String remoteVersionName(Context c) {
        String s = sp(c).getString(K_REMOTE_NAME, "");
        return (s == null) ? "" : s;
    }

    public static String remoteNotes(Context c) {
        String s = sp(c).getString(K_REMOTE_NOTES, "");
        return (s == null) ? "" : s;
    }

    /** 已知有新版（缓存里查到的远端版本比本机新） */
    public static boolean hasKnownUpdate(Context c) {
        return remoteVersionCode(c) > currentVersionCode(c);
    }

    // ────────────────────────── 检查 ──────────────────────────

    /**
     * @param manual true = 用户点的（忽略节流，成功失败都要回调）；
     *               false = 启动时的静默检查（受每天一次节流限制，且只在新版本时才回调）
     */
    public static void check(final Context context, final boolean manual, final Callback cb) {
        final Context app = context.getApplicationContext();

        if (!manual && !shouldAutoCheck(app)) {
            CardDebug.note(app, "[upd] 静默检查被节流（距上次不足 24h）");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Info info = null;
                String err = null;
                String usedUrl = null;

                for (String url : MANIFEST_URLS) {
                    try {
                        String body = fetch(url);
                        Info parsed = parse(body);
                        if (parsed.versionCode <= 0) {
                            throw new Exception("清单缺少有效 versionCode");
                        }
                        info = parsed;
                        usedUrl = url;
                        break;
                    } catch (Exception e) {
                        err = shortReason(e);
                        CardDebug.note(app, "[upd] 源失败 " + host(url) + " : " + err);
                    }
                }

                if (info != null) {
                    markChecked(app);
                    saveRemote(app, info);
                    final Info fi = info;
                    CardDebug.note(app, "[upd] 清单 OK via " + host(usedUrl)
                            + " 远端 vc=" + fi.versionCode + " 本机 vc=" + currentVersionCode(app)
                            + " 有新版=" + isNewer(app, fi));
                    if (manual || isNewer(app, fi)) {
                        post(cb, fi, null);
                    }
                } else {
                    CardDebug.note(app, "[upd] 全部源失败，最后错误: " + err);
                    if (manual) {
                        post(cb, null, err == null ? "无法连接更新服务器" : err);
                    }
                }
            }
        }).start();
    }

    /** 启动时的静默检查：不关心结果，查到就落盘，用户进设置页自然看到 */
    public static void autoCheck(Context c) {
        check(c, false, new Callback() {
            @Override
            public void onResult(Info info, String error) {
            }
        });
    }

    private static void post(final Callback cb, final Info info, final String err) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(info, err);
            }
        });
    }

    // ────────────────────────── 网络与解析 ──────────────────────────

    private static String fetch(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            // GitHub raw 会拒绝没有 UA 的请求
            conn.setRequestProperty("User-Agent", "WereadMoji");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            InputStream is = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return sb.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Info parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        Info in = new Info();
        in.versionCode = o.optInt("versionCode", 0);
        in.versionName = o.optString("versionName", "");
        in.notes = o.optString("notes", "");
        in.size = o.optLong("size", 0L);
        in.sha256 = o.optString("sha256", "");
        in.apk = o.optString("apk", "");

        JSONArray arr = o.optJSONArray("urls");
        if (arr != null) {
            ArrayList<String> list = new ArrayList<String>();
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (s != null && s.length() > 0) {
                    list.add(s);
                }
            }
            in.urls = list.toArray(new String[list.size()]);
        }
        return in;
    }

    // ────────────────────────── 小工具 ──────────────────────────

    private static String host(String url) {
        if (url == null) {
            return "?";
        }
        int a = url.indexOf("://");
        int b = url.indexOf('/', a + 3);
        return (b > 0) ? url.substring(a + 3, b) : url;
    }

    /** 把异常压成一句人话（给设置页显示，也给日志） */
    private static String shortReason(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) {
            return e.getClass().getSimpleName();
        }
        return m;
    }
}
