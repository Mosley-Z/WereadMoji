package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 🆕 TASK-086 第六批B（D9，2026-10-11）：**阅读资产**的本地缓存（每本书的累计阅读时长 / 进度 / 完读时间）。
 *
 * <p>为什么单独一份，而不是复用书架快照（{@link BookStore#shelf}）或墨单（{@link Bill}）：
 * <ul>
 *   <li>书架快照只有 `bookId/title/author/deepLink/cover/readUpdateTime` —— **没有累计时长**，
 *       而「阅读资产」整页都要按**累计时长**排序、按 10 分钟门槛过滤（D9）；</li>
 *   <li>墨单是**周期账**（本周/本月读了多少），落盘的是周期量 + 市场价，跟"这本书一共读了多久"不是一回事；</li>
 *   <li>接口口径：`/book/getprogress` 回包 `book.readingTime` = **累计秒**（本类用它），
 *       `book.progress` = 进度（0–100），`book.finishTime` = 完读时间戳（0 = 未完读）。
 *       ⚠️ 同回包里还有 `book.recordReadingTime`，**实测恒为 0**（S4 真实回包
 *       `_archive/_probe2/api_progress.json`：`readingTime=33808` / `recordReadingTime=0`）
 *       ⇒ 只在 `readingTime` 缺失或为 0 时回落它，别反过来（`AssetRefresher` 的旧写法就是反着取，
 *       导致 sec 恒 0、整页「暂无数据」，见 `验证记录/227` §6.6）。</li>
 * </ul>
 *
 * <p>🔴 **零请求**：本类只读 / 写本地 JSON，不出网（拉数据的活全在 {@link AssetRefresher}）。
 *
 * <p>存的是**分记录**而不是一个整页快照：增量刷新（D9「日常增量」）只碰需要更新的那几条，
 * 未刷到的新书也不会把已有记录冲掉。
 *
 * <p>键（prefs 文件 {@code asset_books}）：
 * <ul>
 *   <li>{@code books} —— JSON 数组，元素 {@code {id,title,author,sec,pct,fin,at}}</li>
 *   <li>{@code full_at} —— **上一轮全量拉取完成**的时间戳（0 = 从没跑过）</li>
 *   <li>{@code run_at} —— 上一次**尝试**拉取的时间戳（含失败；用于最短重试间隔）</li>
 * </ul>
 */
public final class AssetStore {

    // ══════════════════════ 记录 ══════════════════════

    /** 一本书的累计资产。字段语义见类注释；{@code sec} 单位 = 秒。 */
    public static final class Book {
        public String id = "";
        public String title = "";
        public String author = "";
        /** 累计阅读时长（秒）—— `/book/getprogress` 的 `readingTime`（缺失/为 0 才回落 `recordReadingTime`）。 */
        public int sec;
        /** 进度（0–100；{@code -1} = 接口没给）。 */
        public int pct = -1;
        /** 完读时间戳（毫秒；0 = 未完读）。 */
        public long finishTime;
        /** 本条记录的拉取时间（毫秒）。 */
        public long at;

        /** 完读判据（与 `BillMoney.progressForBilling(...)` 的 `finished` 入参同源：完读时间戳 > 0）。 */
        public boolean finished() {
            return finishTime > 0L;
        }
    }

    private static final String P = "asset_books";
    private static final String K_BOOKS = "books";
    private static final String K_FULL_AT = "full_at";
    private static final String K_RUN_AT = "run_at";

    private AssetStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    // ══════════════════════ 读 ══════════════════════

    /** 全部记录（**不排序、不过滤**；排序 / 10 分钟门槛都在渲染侧做）。 */
    public static List<Book> all(Context c) {
        List<Book> out = new ArrayList<Book>();
        JSONArray arr = raw(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Book b = fromJson(o);
            if (b.id.length() == 0) continue;
            out.add(b);
        }
        return out;
    }

    /** 按 bookId 取；没有 ⇒ null。 */
    public static Book get(Context c, String bookId) {
        if (bookId == null || bookId.length() == 0) return null;
        JSONArray arr = raw(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (bookId.equals(o.optString("id", ""))) return fromJson(o);
        }
        return null;
    }

    public static int count(Context c) {
        return raw(c).length();
    }

    public static boolean isEmpty(Context c) {
        return raw(c).length() == 0;
    }

    /** 本轮全量拉取是否完成过（{@code false} = 从没跑完过一轮，首屏只有缓存）。 */
    public static boolean hasFull(Context c) {
        return fullAt(c) > 0L;
    }

    /** 上一轮全量拉取**完成**时间戳。 */
    public static long fullAt(Context c) {
        return sp(c).getLong(K_FULL_AT, 0L);
    }

    public static void setFullAt(Context c, long t) {
        sp(c).edit().putLong(K_FULL_AT, t).commit();
    }

    /** 上一次**尝试**拉取的时间戳（失败也记 —— 免得失败时每次进页都重试）。 */
    public static long runAt(Context c) {
        return sp(c).getLong(K_RUN_AT, 0L);
    }

    public static void setRunAt(Context c, long t) {
        sp(c).edit().putLong(K_RUN_AT, t).commit();
    }

    /** 这本书需不需要拉 / 重拉：没记录、或记录老过 {@code ttlMs}。 */
    public static boolean needsFetch(Context c, String bookId, long ttlMs) {
        Book b = get(c, bookId);
        if (b == null || b.at <= 0L) return true;
        return System.currentTimeMillis() - b.at > ttlMs;
    }

    // ══════════════════════ 写 ══════════════════════

    /** 落 / 更新一条（同 id 覆盖）。 */
    public static void put(Context c, Book b) {
        if (b == null || b.id == null || b.id.length() == 0) return;
        List<Book> one = new ArrayList<Book>();
        one.add(b);
        putAll(c, one);
    }

    /**
     * 批量落盘（**一次 commit**）。同 id 覆盖、不删除别的记录。
     *
     * <p>🔴 为什么批量：全量一轮可能有几百本书 ⇒ 逐条 read-modify-write 是 O(n²) 的
     * JSON 解析（每本都要把整份数组读出来再写回去）。{@link AssetRefresher} 因此
     * 攒一批调一次（每 {@code FLUSH_EVERY} 本 + 收尾各一次）。
     */
    public static void putAll(Context c, List<Book> books) {
        if (books == null || books.isEmpty()) return;
        JSONArray arr = raw(c);
        // ① 先把已有记录按 id 索引（保持原顺序）
        List<String> ids = new ArrayList<String>();
        List<JSONObject> objs = new ArrayList<JSONObject>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (id.length() == 0) continue;
            ids.add(id);
            objs.add(o);
        }
        // ② 覆盖 / 追加
        for (int i = 0; i < books.size(); i++) {
            Book b = books.get(i);
            if (b == null || b.id == null || b.id.length() == 0) continue;
            int hit = ids.indexOf(b.id);
            JSONObject o = toJson(b);
            if (hit >= 0) {
                objs.set(hit, o);
            } else {
                ids.add(b.id);
                objs.add(o);
            }
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < objs.size(); i++) out.put(objs.get(i));
        sp(c).edit().putString(K_BOOKS, out.toString()).commit();
    }

    public static void clear(Context c) {
        sp(c).edit().remove(K_BOOKS).remove(K_FULL_AT).remove(K_RUN_AT).commit();
    }

    // ══════════════════════ JSON ══════════════════════

    private static JSONArray raw(Context c) {
        String s = sp(c).getString(K_BOOKS, null);
        if (s == null || s.length() == 0) return new JSONArray();
        try {
            JSONArray a = new JSONArray(s);
            return a == null ? new JSONArray() : a;
        } catch (Exception e) {
            return new JSONArray();                 // 脏值 ⇒ 当空（不崩渲染）
        }
    }

    private static Book fromJson(JSONObject o) {
        Book b = new Book();
        b.id = o.optString("id", "");
        b.title = o.optString("title", "");
        b.author = o.optString("author", "");
        b.sec = o.optInt("sec", 0);
        b.pct = o.optInt("pct", -1);
        b.finishTime = o.optLong("fin", 0L);
        b.at = o.optLong("at", 0L);
        return b;
    }

    private static JSONObject toJson(Book b) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", b.id);
            o.put("title", b.title);
            o.put("author", b.author);
            o.put("sec", b.sec);
            o.put("pct", b.pct);
            o.put("fin", b.finishTime);
            o.put("at", b.at);
        } catch (Exception ignored) {
        }
        return o;
    }
}
