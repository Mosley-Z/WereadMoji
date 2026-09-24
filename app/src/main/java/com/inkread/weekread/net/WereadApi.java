package com.inkread.weekread.net;

import com.inkread.weekread.core.BookStats;
import com.inkread.weekread.core.BookStore;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PeriodStats;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 微信读书官方 Skill 网关客户端（纯 HttpURLConnection，无第三方依赖）。
 *
 * v0.3.3 起从"只会拉本周"泛化成 {@link #fetchDetail}：
 * 周期（`mode`）与锚点（`baseTime`）都由调用方给。
 * · `baseTime = 0` → 当前周期（服务端归一化到本周一 / 本月 1 日）；
 * · `baseTime = 某历史时刻` → **该时刻所在的周期**（这就是"选某一周 / 某年某月"的实现，
 *   不需要任何新接口，见设计方案 §4.1）。
 *
 * v0.3.4 新增「本书」三接口（{@link #fetchBook}）：
 * `/shelf/sync` → `/book/getprogress` → `/book/chapterinfo`。
 * 它们是一个**有依赖的串行链**，所以另开了 {@link #raw}（阻塞式，只能在后台线程调），
 * 由 {@link #fetchBook} 在单独一个线程里顺序跑完，而不是套三层回调。
 */
public class WereadApi {

    public static final String GATEWAY = "https://i.weread.qq.com/api/agent/gateway";
    public static final String SKILL_VERSION = "1.0.4";

    public interface Callback {
        void onResult(PeriodStats stats, String rawJson, String error);
    }

    /** 通用 JSON 回调（书架 / 进度 / 章节目录用） */
    public interface JsonCallback {
        void onResult(JSONObject json, String error);
    }

    /** 「本书」链路的回调 */
    public interface BookCallback {
        void onResult(BookStats stats, String error);
    }

    /** 一次网关调用的原始结果 */
    public static final class Resp {
        public JSONObject json;
        public String error;
        /** 网关业务码（HTTP 200 也会带）；0 = 正常，v0.4.2 起「测试连接」按它区分失败原因 */
        public int errcode;
        /** 网关给的 HTTP 状态码（0 = 没走到响应） */
        public int httpCode;
    }

    // ── 周 / 月统计 ──

    /** 拉当前周期的周数据（最常用，包一层省事） */
    public static void fetchWeekly(final String apiKey, final Callback cb) {
        fetchDetail(apiKey, PeriodRange.WEEKLY, 0, cb);
    }

    /**
     * @param mode     {@link PeriodRange#WEEKLY} / {@link PeriodRange#MONTHLY}
     * @param baseTime 0 = 当前周期；否则传该周期内任意时间戳（秒）
     */
    public static void fetchDetail(final String apiKey, final String mode,
                                   final long baseTime, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject body = new JSONObject();
                try {
                    body.put("api_name", "/readdata/detail");
                    body.put("mode", mode);
                    body.put("baseTime", baseTime);
                    body.put("skill_version", SKILL_VERSION);
                } catch (Exception ignored) {
                }
                final Resp r = raw(apiKey, body);
                final String fRaw = (r.json == null) ? null : r.json.toString();
                final String fErr = r.error;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        PeriodStats stats = null;
                        if (fRaw != null) {
                            try {
                                stats = PeriodStats.parse(new JSONObject(fRaw),
                                        System.currentTimeMillis(), mode, baseTime);
                                stats.rawJson = fRaw;
                            } catch (Exception ignored) {
                            }
                        }
                        cb.onResult(stats, fRaw, fErr);
                    }
                });
            }
        }).start();
    }

    // ── 单接口异步封装（调试/备用） ──

    public static void fetchShelf(final String apiKey, final JsonCallback cb) {
        post(apiKey, "/shelf/sync", null, cb);
    }

    public static void fetchProgress(final String apiKey, final String bookId, final JsonCallback cb) {
        JSONObject a = new JSONObject();
        try {
            a.put("bookId", bookId);
        } catch (Exception ignored) {
        }
        post(apiKey, "/book/getprogress", a, cb);
    }

    public static void fetchChapters(final String apiKey, final String bookId, final JsonCallback cb) {
        JSONObject a = new JSONObject();
        try {
            a.put("bookId", bookId);
        } catch (Exception ignored) {
        }
        post(apiKey, "/book/chapterinfo", a, cb);
    }

    /** 起一个线程调网关，结果回主线程 */
    public static void post(final String apiKey, final String apiName,
                            final JSONObject args, final JsonCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Resp r = raw(apiKey, buildBody(apiName, args));
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(r.json, r.error);
                    }
                });
            }
        }).start();
    }

    // ══════════════════════ 「本书」串行链 ══════════════════════

    /**
     * 拉「最近在读的书 + 它的进度」。
     *
     * 三个接口有依赖（要先有书架才知道 bookId，要先有 progress 才知道 chapterUid），
     * 所以在**一个后台线程里顺序跑完**，最后把结果回主线程 —— 比三层回调好读得多。
     *
     * ── 流量策略（② 拍板：按 462KB 书架的包袱来设计）──
     * · `/shelf/sync` **462KB** —— 只在"没缓存 / 缓存过期 / 用户手动刷新且距上次 >10 分钟"时拉；
     * · `/book/getprogress` **0.4KB** —— 每次都拉，进度必须实时；
     * · `/book/chapterinfo` —— **按 bookId 落盘缓存**，同一本书只拉一次（章节几乎不变）。
     *
     * ── 容错 ──
     * 书架里混着公众号（bookId 形如 `MP_WXS_xxx`），它们不一定有阅读进度。
     * 所以前 {@link #TRY_BOOKS} 本逐个试，第一本能拿到 progress 的就用它。
     *
     * @param force true = 用户手动刷新（书架缓存最短间隔 {@link BookStore#SHELF_MIN_MS}）
     */
    public static void fetchBook(final Context c, final String apiKey,
                                 final boolean force, final BookCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                BookStats stats = null;
                String error = null;
                try {
                    stats = bookChain(c, apiKey, force);
                    if (stats == null) error = "没读到最近在读的书";
                } catch (Exception e) {
                    error = e.getClass().getSimpleName();
                }
                final BookStats fS = stats;
                final String fE = error;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(fS, fE);
                    }
                });
            }
        }).start();
    }

    /** 拿到进度之前最多试几本书（书架前几本里可能有公众号/听书这类没有进度的） */
    private static final int TRY_BOOKS = 3;

    private static BookStats bookChain(Context c, String apiKey, boolean force) {
        // ① 书架快照：能不拉就不拉
        JSONArray books = BookStore.shelf(c);
        long age = BookStore.shelfAge(c);
        boolean needShelf = (books == null)
                || (force ? age > BookStore.SHELF_MIN_MS : age > BookStore.SHELF_TTL_MS);
        // v0.3.5.1：升级前存的旧缓存没有 cover 字段（封面功能需要）→ 视为过期，
        // 重拉一次书架即可补上；受 SHELF_MIN_MS 保护，不会连着刷。
        if (!needShelf && books != null && books.length() > 0) {
            JSONObject first = books.optJSONObject(0);
            if (first != null && !first.has("cover")) needShelf = true;
        }
        if (needShelf) {
            Resp r = raw(apiKey, buildBody("/shelf/sync", null));
            if (r.error == null && r.json != null) {
                JSONArray src = r.json.optJSONArray("books");
                if (src != null && src.length() > 0) {
                    books = BookStore.compactShelf(src);
                    BookStore.saveShelf(c, books);
                }
            } else if (books == null) {
                return null;                       // 没缓存又拉不到 → 直接失败
            }
            // 拉失败但有旧缓存 → 继续用旧的（离线可用，比"一片空白"强）
        }
        if (books == null || books.length() == 0) return null;

        // ② 逐个试前几本，直到某本能拿到进度
        int n = Math.min(TRY_BOOKS, books.length());
        for (int i = 0; i < n; i++) {
            JSONObject b = books.optJSONObject(i);
            if (b == null) continue;
            String bookId = b.optString("bookId", "");
            if (bookId.length() == 0) continue;

            JSONObject arg = new JSONObject();
            try {
                arg.put("bookId", bookId);
            } catch (Exception ignored) {
            }
            Resp pr = raw(apiKey, buildBody("/book/getprogress", arg));
            if (pr.error != null || pr.json == null) continue;
            JSONObject book = pr.json.optJSONObject("book");
            if (book == null) continue;

            BookStats s = new BookStats();
            s.bookId = bookId;
            s.title = b.optString("title", "");
            s.author = b.optString("author", "");
            s.deepLink = b.optString("deepLink", "");
            s.coverUrl = b.optString("cover", "");
            s.readUpdateTime = b.optLong("readUpdateTime", 0L);
            s.progress = book.optInt("progress", 0);
            s.readingSec = book.optInt("readingTime", 0);
            s.chapterUid = book.optInt("chapterUid", 0);
            s.chapterIdx = book.optInt("chapterIdx", 0);
            s.chapterTitle = resolveChapter(c, apiKey, bookId,
                    s.chapterUid, s.chapterIdx);
            s.fetchedAt = System.currentTimeMillis();
            return s;
        }
        return null;
    }

    /**
     * 用 `chapterUid` 在章节目录里反查章节标题。
     *
     * 目录按 bookId 落盘（{@link BookStore#saveChapters}），**一本书只拉一次**；
     * 查不到就退回「第 N 章」—— 宁可朴素，也不要空着。
     */
    public static String resolveChapter(Context c, String apiKey, String bookId,
                                         int uid, int idx) {
        String raw = BookStore.chapters(c, bookId);
        if (raw == null) {
            JSONObject arg = new JSONObject();
            try {
                arg.put("bookId", bookId);
            } catch (Exception ignored) {
            }
            Resp cr = raw(apiKey, buildBody("/book/chapterinfo", arg));
            if (cr.error == null && cr.json != null && cr.json.optJSONArray("chapters") != null) {
                raw = cr.json.toString();
                BookStore.saveChapters(c, bookId, raw);
            }
        }
        if (raw != null) {
            try {
                JSONArray chs = new JSONObject(raw).optJSONArray("chapters");
                if (chs != null) {
                    for (int i = 0; i < chs.length(); i++) {
                        JSONObject ch = chs.optJSONObject(i);
                        if (ch != null && ch.optInt("chapterUid", -1) == uid) {
                            String t = ch.optString("title", "");
                            if (t.length() > 0) return t;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "第 " + (idx + 1) + " 章";
    }

    // ── HTTP ──

    public static JSONObject buildBody(String apiName, JSONObject args) {
        JSONObject body = new JSONObject();
        try {
            body.put("api_name", apiName);
            body.put("skill_version", SKILL_VERSION);
            if (args != null) {
                JSONArray names = args.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String k = names.optString(i, null);
                        if (k != null) body.put(k, args.opt(k));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return body;
    }

    /**
     * **阻塞式**调一次网关。只能从后台线程调（否则 NetworkOnMainThreadException）。
     *
     * `errcode != 0` 也算失败 —— 网关的 HTTP 码总是 200，业务错误全在 `errcode` 里。
     */
    public static Resp raw(String apiKey, JSONObject body) {
        Resp r = new Resp();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(GATEWAY).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            r.httpCode = code;
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(is);
            // 先把能解析的解析出来 —— 非 2xx 时 errcode 仍然有用（「测试连接」的人话文案
            // 就是按 -2013 / -2010 分档的，见 {@link #testKey}）
            JSONObject j = null;
            try {
                if (text != null && text.length() > 0) j = new JSONObject(text);
            } catch (Exception ignored) {
            }
            if (j != null) {
                r.errcode = j.optInt("errcode", 0);
            }
            // 🔴 **非 2xx 一律判失败**（v0.5.3，R11）。
            // 上一版只把这段改成"读 errorStream"，之后却仅看 JSON 的 errcode —— 而
            // `optInt("errcode", 0)` 在字段缺失时默认 0，于是「HTTP 500 + {message:…}（无 errcode）」
            // 会被判成**成功**：r.error=null、r.json 非空 → fetchDetail 把缺统计字段的对象
            // 解析成全零数据 → 而 PeriodStats.parse 在缺 baseTime 时会兜底成本地算出的周期起点，
            // StatsStore.save 的 `baseTime<=0` 守卫拦不住 → **有效的旧缓存被全零数据覆盖**。
            // 注意：**合法的空结果走 2xx**（某周确实没读书、totalReadTime=0），不受这条影响。
            if (code < 200 || code >= 300) {
                r.json = null;                       // 关键：错误响应绝不留给调用方当数据用
                r.error = "HTTP " + code
                        + (r.errcode != 0 ? (" errcode=" + r.errcode + " " + j.optString("errmsg")) : "")
                        + (text == null || text.length() == 0 ? "" : (" " + brief(text)));
                return r;
            }
            if (text == null || text.length() == 0) {
                r.error = "HTTP " + code + " 空响应";
                return r;
            }
            if (j == null) {
                r.error = "HTTP " + code + " 响应不是 JSON：" + brief(text);
                return r;
            }
            if (r.errcode != 0) {
                r.error = "errcode=" + r.errcode + " " + j.optString("errmsg");
                return r;
            }
            r.json = j;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    /** 错误正文的短摘要（日志/界面文案里用；压掉换行、最多 80 字） */
    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    /**
     * 探测一个 API Key 能不能用（v0.4.2「测试连接」）。
     *
     * **阻塞式 —— 必须从后台线程调**，返回值就是给用户看的那一整句话。
     *
     * 文案按**实测**的真实错误码分档（2026-09-22 用四种 Key 打过网关）：
     * <pre>
     *   真 key         → HTTP 200，带 totalBookCount / totalNoteCount
     *   错 key / 截断  → HTTP 401 + errcode -2013「鉴权失败」
     *   空 key         → HTTP 401 + errcode -2010「用户不存在」
     *   断网           → httpCode 0（没走到响应）
     * </pre>
     * 所以成功时顺手把书架本数与划线总数报出来 —— 用户能看到"确实读到了我自己的数据"，
     * 而不是只有一句干巴巴的"成功"。
     */
    public static String testKey(String apiKey) {
        if (apiKey == null || apiKey.trim().length() == 0) return "请先填入 API Key";
        JSONObject args = new JSONObject();
        try {
            args.put("count", 1);                       // 只取 1 条，测试要的是"通不通"不是数据
        } catch (Exception ignored) {
        }
        Resp r = raw(apiKey.trim(), buildBody("/user/notebooks", args));
        if (r.json != null) {
            return "✓ 连接正常：书架 " + r.json.optInt("totalBookCount", 0)
                    + " 本，共 " + r.json.optInt("totalNoteCount", 0) + " 条划线";
        }
        if (r.errcode == -2013) return "✗ Key 无效（鉴权失败），请检查是否复制完整";
        if (r.errcode == -2010) return "✗ 查不到用户，这个 Key 可能已失效，请重新获取";
        if (r.httpCode == 0) return "✗ 网络不通，检查 WiFi 后重试";
        return "✗ 连接失败：" + (r.error == null ? "未知错误" : r.error);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return null;
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
