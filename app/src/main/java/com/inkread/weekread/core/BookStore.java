package com.inkread.weekread.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 「本书」数据的本地缓存 —— 走**文件**，不走 SharedPreferences。
 *
 * ⚠️ 为什么不能用 SharedPreferences：`/shelf/sync` 全量回包 **462KB**（1175 本）。
 * SP 是把整个 XML 一次性读进内存、且 `commit/apply` 会重写整个文件，
 * 把几百 KB 塞进去既慢又占内存。所以这里用 filesDir 下的普通文件。
 *
 * 缓存三样东西：
 *   ① `shelf.json` —— 书架**精简快照**：只留 bookId / title / author / readUpdateTime /
 *      deepLink 四个字段，按 readUpdateTime 降序，最多 200 本。
 *      462KB 的原包压缩到 ~30KB，磁盘上也只是个小文件。
 *   ② `ch_<bookId>.json` —— 章节目录原文。章节几乎不变，**一本书只拉一次**。
 *   ③ `book.json` —— 上一次成功拿到的 {@link BookStats}，供离线/启动瞬间直接显示。
 */
public final class BookStore {

    private BookStore() {
    }

    private static final String F_SHELF = "shelf.json";
    private static final String F_BOOK = "book.json";
    private static final String P_CHAPTER = "ch_";

    /**
     * 书架快照的自动有效期（毫秒）。
     *
     * `shelf/sync` 462KB 太重，不能每次刷新都拉；但缓存太久会"换了一本书、
     * 卡片还显示旧的"。6 小时是折中：一天最多 4 次 462KB（≈1.8MB/天）。
     */
    public static final long SHELF_TTL_MS = 6L * 3600L * 1000L;

    /**
     * 手动刷新时的最小间隔（毫秒）。
     *
     * 用户主动点刷新，语义上是"我现在就要最新的" —— 但如果他连点 5 下，
     * 那就是 2.3MB。10 分钟内只认第一次，其余走缓存（进度本身仍会实时拉）。
     */
    public static final long SHELF_MIN_MS = 10L * 60L * 1000L;

    /** 精简快照最多保留多少本（按最近阅读排序，够用了） */
    private static final int SHELF_KEEP = 200;

    // ── 本书进度 ──

    public static BookStats load(Context c) {
        String s = read(c, F_BOOK);
        if (s == null) return null;
        try {
            return BookStats.fromJson(new JSONObject(s));
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(Context c, BookStats b) {
        if (b == null) return;
        write(c, F_BOOK, b.toJson().toString());
    }

    // ── 书架快照 ──

    /** 精简后的书架数组（已按 readUpdateTime 降序）；没有缓存返回 null */
    public static JSONArray shelf(Context c) {
        String s = read(c, F_SHELF);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            JSONArray a = o.optJSONArray("books");
            return (a == null || a.length() == 0) ? null : a;
        } catch (Exception e) {
            return null;
        }
    }

    /** 快照保存时刻（毫秒）。没缓存返回 0 —— 0 会被判定成"必须拉" */
    public static long shelfSavedAt(Context c) {
        String s = read(c, F_SHELF);
        if (s == null) return 0L;
        try {
            return new JSONObject(s).optLong("savedAt", 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 快照已经存了多久（毫秒） */
    public static long shelfAge(Context c) {
        long t = shelfSavedAt(c);
        if (t <= 0) return Long.MAX_VALUE;
        long d = System.currentTimeMillis() - t;
        return d > 0 ? d : 0L;
    }

    /** 快照是否还在有效期内（自动刷新用 {@link #SHELF_TTL_MS}） */
    public static boolean shelfFresh(Context c) {
        return shelf(c) != null && shelfAge(c) <= SHELF_TTL_MS;
    }

    public static void saveShelf(Context c, JSONArray books) {
        JSONObject o = new JSONObject();
        try {
            o.put("savedAt", System.currentTimeMillis());
            o.put("books", books == null ? new JSONArray() : books);
        } catch (Exception ignored) {
        }
        write(c, F_SHELF, o.toString());
    }

    /**
     * 把 `/shelf/sync` 的全量回包压成精简快照。
     *
     * 只挑有 `readUpdateTime`（真的读过）的书，按它降序，砍到 {@link #SHELF_KEEP} 本。
     * 为什么必须砍：1175 本的精简数组也接近 100KB，而"最近在读"只可能是前几本。
     */
    public static JSONArray compactShelf(JSONArray src) {
        JSONArray out = new JSONArray();
        if (src == null) return out;

        // ① 先挑出"真的读过"的书（有 readUpdateTime），只留 6 个字段
        //    （v0.3.5.1 起多留 cover —— 封面 URL，CDN 直连无需鉴权，~80KB/本）
        JSONArray cand = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject b = src.optJSONObject(i);
            if (b == null) continue;
            String id = b.optString("bookId", "");
            long t = b.optLong("readUpdateTime", 0L);
            if (id.length() == 0 || t <= 0) continue;      // 没读过的书不可能是"最近在读"
            JSONObject o = new JSONObject();
            try {
                o.put("bookId", id);
                o.put("title", b.optString("title", ""));
                o.put("author", b.optString("author", ""));
                o.put("deepLink", b.optString("deepLink", ""));
                o.put("cover", b.optString("cover", ""));
                o.put("readUpdateTime", t);
            } catch (Exception e) {
                continue;
            }
            cand.put(o);
        }

        // ② 选择排序取前 SHELF_KEEP 本（200 本以内 O(n²) 也就几万次比较，且在后台线程跑）
        boolean[] used = new boolean[cand.length()];
        int limit = Math.min(SHELF_KEEP, cand.length());
        for (int k = 0; k < limit; k++) {
            int best = -1;
            long bt = -1L;
            for (int j = 0; j < cand.length(); j++) {
                if (used[j]) continue;
                long t = cand.optJSONObject(j).optLong("readUpdateTime", 0L);
                if (t > bt) {
                    bt = t;
                    best = j;
                }
            }
            if (best < 0) break;
            used[best] = true;
            out.put(cand.optJSONObject(best));
        }
        return out;
    }

    // ── 章节目录 ──

    public static String chapters(Context c, String bookId) {
        if (bookId == null || bookId.length() == 0) return null;
        return read(c, P_CHAPTER + bookId + ".json");
    }

    public static void saveChapters(Context c, String bookId, String raw) {
        if (bookId == null || bookId.length() == 0 || raw == null) return;
        write(c, P_CHAPTER + bookId + ".json", raw);
    }

    // ── 清缓存（v0.5.3，R05）──

    /**
     * 清掉「本书」的全部本地缓存：书架快照 + 书籍进度 + 章节目录。
     *
     * 这三样**全是账号数据**（别人的书架、别人的进度），换 API Key 时必须一起失效 ——
     * v0.5.2 之前本类**连 clear 方法都没有**（REVIEW 的 R05 缺口之一）。
     * 调用方：{@link StatsStore#setKey}。
     */
    public static void clear(Context c) {
        File dir = c.getFilesDir();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName();
                // 连 `*.tmp`（原子写的半成品）一起清掉
                if (n.equals(F_BOOK) || n.startsWith(F_BOOK + ".")
                        || n.equals(F_SHELF) || n.startsWith(F_SHELF + ".")
                        || n.startsWith(P_CHAPTER)) f.delete();
            }
        }
    }

    // ── 文件读写（UTF-8）──

    private static File f(Context c, String name) {
        return new File(c.getFilesDir(), name);
    }

    private static String read(Context c, String name) {
        FileInputStream in = null;
        try {
            File file = f(c, name);
            if (!file.exists()) return null;
            in = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 写文件 —— **原子替换**（v0.5.3）：先写 `*.tmp` 再 rename。
     * 上一版直接截断写，中断会留下半截 JSON（书架 30KB / 章节目录更大），
     * 下次读解析失败就被当成"没有缓存"。
     */
    private static void write(Context c, String name, String text) {
        File dst = f(c, name);
        File tmp = new File(dst.getParentFile(), name + ".tmp");
        boolean written = false;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp, false);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            written = true;
        } catch (Exception ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (written && tmp.renameTo(dst)) return;
        tmp.delete();
        FileOutputStream o2 = null;
        try {
            o2 = new FileOutputStream(dst, false);
            o2.write(text.getBytes("UTF-8"));
            o2.flush();
        } catch (Exception ignored) {
        } finally {
            if (o2 != null) {
                try {
                    o2.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
