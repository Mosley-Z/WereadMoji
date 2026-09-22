package com.inkread.weekread;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 「本记」的本地仓库（v0.4.0，M1；v0.4.4 起纳入想法/点评；v0.4.5 起改为**按需抽样**）。
 *
 * 四样东西，全部走文件（理由见 {@link BookStore} 的类注释：SP 扛不住大文件）：
 *   ① `nt_index.json` —— 笔记书索引（精简版）：bookId / title / author / cover /
 *      noteCount / reviewCount / sort。一页就能拿全（实测 `/user/notebooks?count=300`
 *      → 228 本 / 283KB / hasMore=0）。**它的顺序就是后台预热的顺序**，见 {@link #compactIndex}。
 *   ② `nt_<bookId>.json` —— 这本书的划线原文，按书永久缓存，带 synckey 增量。
 *   ③ `ni_<bookId>.json` —— 这本书的**想法/点评**（v0.4.4），全量替换 + 6 小时 TTL。
 *   ④ `nt_batch*.json` + SP `notes` —— 抽取状态：这一小把抽了哪 40 条、抽到第几条、
 *      当前展示的那条、只看看法的开关。
 *
 * ── 抽取策略（v0.4.5 重做）──
 * · **每次只抽一小把**（{@link #BATCH} = 40 条）：从索引里**按条加权**随机挑书、
 *   只读那一两本的文件。单次取用 3–10ms，内存里最多 40 条 + 8 本书的缓存。
 * · **每日一签**：同一天内不主动换（{@link #pick} 非手动时返回当天那条），
 *   跨天自动前进一条；手动「换一条」随时插队。
 * · **「共 M 条」= 全库总数**，直接从索引求和，**零文件读**（见 {@link #total}）。
 *
 * ── 为什么不再"把全库展开成一个池子" ──
 * v0.4.4 的做法是把 228 本书的 5162 条划线全部读进内存、再打乱成一个洗牌袋。两个问题：
 *   ① **缓存从未命中**（2026-09-22 23:57 探针实测）：缓存键用了 `idx.hashCode()`，
 *      而 Android 的 `org.json.JSONArray` **没有覆写 hashCode()**（用的是对象身份哈希），
 *      `index()` 每次又都返回新解析出来的对象 —— 键永远不相等。结果是**每画一帧就重读
 *      456 个文件 ≈ 700ms**，本记页一拖动就卡成幻灯片（每个 MOVE 都 invalidate）。
 *   ② 没人会把几千条一次点完 —— 全量展开本来就是白花的成本，还顺带把"随机性"做成了
 *      "一条固定的洗牌顺序"（抽到第几条是可预测的）。
 * 现在两个问题一起解决：索引在内存里按**文件指纹**缓存（改了才重读），
 * 内容改成一按需读书抽样。
 */
public final class NoteStore {

    private NoteStore() {
    }

    private static final String F_INDEX = "nt_index.json";
    private static final String P_MARK = "nt_";
    /** 想法文件前缀（v0.4.4）—— 注意与 {@link #P_MARK} 的前缀 `nt_` 不冲突 */
    private static final String P_IDEA = "ni_";
    /** 这一小把的内容（v0.4.5）；`_i` 后缀那份属于「只看想法」档 */
    private static final String F_BATCH = "nt_batch.json";
    private static final String F_BATCH_I = "nt_batch_i.json";
    private static final String PREFS = "notes";

    /**
     * 混投比例（v0.4.4 用户拍板）：一把 40 条里**平均每 IDEA_EVERY 条放 1 条想法**。
     *
     * 为什么不是天然比例：全库 5162 条划线 vs 189 条想法 ≈ 3.5%，纯按数量混投要
     * 抽 30 条才碰上 1 条想法 —— 而想法是用户**亲手写的字**，是这个池子里最该被看见的部分。
     */
    public static final int IDEA_EVERY = 8;

    /**
     * 每一小把抽多少条（v0.4.5）。
     *
     * 40 ≈ 用户"随手翻一会儿"的量：一轮内不重复，翻完了自然换新的一把。
     * 取值的权衡：太小（如 12）会导致"翻两下就换一批"，随机感变差（同一把里是固定内容）；
     * 太大（如 200）则每次建批要读更多书、首屏变慢，也失去"每次只抽少量"的意义。
     */
    public static final int BATCH = 40;

    /** 书名/想法元信息的内存缓存：最多几本书（一把 40 条通常只落在十来本书上） */
    private static final int BOOK_CACHE = 8;

    /**
     * 索引排序里"最近有笔记的前 N 本"（v0.4.4，E-2）。
     * 见 {@link #compactIndex} —— 预热从这些书开始，今天刚划的线才可能当天就被抽到。
     */
    private static final int RECENT_HEAD = 6;

    /** 「只看想法」开关（App 本记页的切换；桌面卡片不受影响） */
    private static final String K_IDEAS_ONLY = "ideas_only";
    private static final String K_DAY = "day";            // 每日一签的日期键 yyyy-MM-dd
    /** 已抽到第几条（进度显示；「换一条」+1，「上一条」-1） */
    private static final String K_TAKEN = "taken";
    /** 当前展示的那条（bookId + 它在本记里的唯一 id）—— 与"这一把抽到第几条"是两套坐标 */
    private static final String K_CUR_B = "cur_book";
    private static final String K_CUR_K = "cur_key";
    /** 抽到这一把的第几条 */
    private static final String K_BATCH_POS = "batch_pos";
    /** 已展示过的条目历史（JSON 数组，栈顶在前、v0.4.5 起存 bookId+key）——「上一条」靠它回看 */
    private static final String K_HIST = "hist";
    /** 历史栈上限 —— 够回看即可，不必无限涨 */
    private static final int HIST_MAX = 64;

    /** 索引自动有效期 —— 笔记书列表变动慢，6 小时一次足够 */
    public static final long INDEX_TTL_MS = 6L * 3600L * 1000L;
    /** 手动刷新索引的最小间隔 */
    public static final long INDEX_MIN_MS = 10L * 60L * 1000L;

    // ══════════════════════ ① 索引（内存缓存 + 文件指纹）══════════════════════

    private static JSONArray sIdx;
    private static String sIdxStamp = "";
    /** 全库总数（{@link #total}）的内存缓存，跟着索引走 */
    private static JSONArray sTotKey;
    private static int sTotMarks = -1, sTotIdeas = -1;

    /**
     * 文件指纹（长度:修改时间）—— 判断"这份文件换了没有"的最便宜办法。
     *
     * ⚠️ 不要用 `JSONArray.hashCode()` 当缓存键：Android 的 `org.json.JSONArray`
     * 没有覆写 hashCode()，用的是**对象身份哈希**。index() 每次解析出的都是新对象，
     * 身份哈希每次不同 → 缓存键永远不相等 → 缓存形同虚设（v0.4.4 实测：每帧重读
     * 456 个文件 ≈ 700ms）。文件指纹是真实的"内容换没换"信号，而且只要两次 stat。
     */
    private static String stamp(Context c, String name) {
        File f = f(c, name);
        if (!f.exists()) return null;
        return f.length() + ":" + f.lastModified();
    }

    /** 索引数组；没有返回 null。按文件指纹缓存，同一份索引只解析一次 */
    public static JSONArray index(Context c) {
        String st = stamp(c, F_INDEX);
        if (st == null) {
            sIdx = null;
            sIdxStamp = "";
            return null;
        }
        if (sIdx != null && st.equals(sIdxStamp)) return sIdx;
        String s = read(c, F_INDEX);
        if (s == null) return null;
        try {
            JSONArray a = new JSONObject(s).optJSONArray("books");
            if (a == null || a.length() == 0) {
                sIdx = null;
                sIdxStamp = "";
                return null;
            }
            sIdx = a;
            sIdxStamp = st;
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    public static long indexAge(Context c) {
        String s = read(c, F_INDEX);
        if (s == null) return Long.MAX_VALUE;
        try {
            long t = new JSONObject(s).optLong("savedAt", 0L);
            if (t <= 0) return Long.MAX_VALUE;
            long d = System.currentTimeMillis() - t;
            return d > 0 ? d : 0L;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    public static boolean indexFresh(Context c) {
        return index(c) != null && indexAge(c) <= INDEX_TTL_MS;
    }

    /** 存索引（传进来的已是精简数组） */
    public static void saveIndex(Context c, JSONArray books) {
        JSONObject o = new JSONObject();
        try {
            o.put("savedAt", System.currentTimeMillis());
            o.put("books", books == null ? new JSONArray() : books);
        } catch (Exception ignored) {
        }
        write(c, F_INDEX, o.toString());
        dropIndexCache();
    }

    /** 索引换了：索引缓存、总数缓存、按书缓存一起作废（**不动当前这一把**） */
    private static synchronized void dropIndexCache() {
        sIdx = null;
        sIdxStamp = "";
        sTotKey = null;
        sTotMarks = -1;
        sTotIdeas = -1;
        sBooks.clear();
    }

    /** 某本书的文件换了：只作废那一本的缓存（当前这一把照旧，条目按 key 重新解析） */
    private static synchronized void dropBookCache(String bookId) {
        if (bookId != null && bookId.length() > 0) sBooks.remove(bookId);
    }

    /** 清掉全部内存缓存（设置页「清空缓存」用） */
    private static synchronized void dropAllCache() {
        dropIndexCache();
        sBatch = null;
        sBatchLoaded = false;
    }

    /**
     * 把 `/user/notebooks` 的回包压成精简索引。
     *
     * 只留 7 个字段 —— 228 本压完 ~45KB（原包 283KB）。
     *
     * ── 顺序 = 后台预热的顺序（v0.4.4，方案 E-2）──
     *   ① **最近有笔记的前 {@link #RECENT_HEAD} 本**（按 `sort` 降序）——
     *      今天刚划的线当天就能被抽到，这是"每日一签"最该有的新鲜度；
     *   ② 其余按**划线数降序**（同数再看最近），把池子的量快速堆起来。
     *
     * ⚠️ 曾经的写法是 `noteCount * 1000000 + sort` 一把梭 —— 而 `sort` 是**秒级时间戳**
     * （≈1.8e9），量级直接压过 `noteCount * 1e6`（≤2.2e8），于是"按划线数降序"实际上
     * 变成了"按最近笔记时间降序"，注释与行为不符（实测：今天在记的《卡拉马佐夫兄弟》
     * 排在第 17 位，第 2 轮才进池）。现在把两段拆开，各自用各自的口径。
     */
    public static JSONArray compactIndex(JSONArray src) {
        JSONArray out = new JSONArray();
        if (src == null) return out;
        JSONArray cand = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject b = src.optJSONObject(i);
            if (b == null) continue;
            String id = b.optString("bookId", "");
            int n = b.optInt("noteCount", 0);
            int rv = b.optInt("reviewCount", 0);
            // 既没划线也没想法的书不进索引；只写想法（整本书评）却零划线的书**要留**，
            // 否则它的想法永远进不了池子
            if (id.length() == 0 || (n <= 0 && rv <= 0)) continue;
            JSONObject book = b.optJSONObject("book");
            JSONObject o = new JSONObject();
            try {
                o.put("bookId", id);
                o.put("noteCount", n);
                o.put("reviewCount", rv);
                o.put("title", book == null ? "" : book.optString("title", ""));
                o.put("author", book == null ? "" : book.optString("author", ""));
                o.put("cover", book == null ? "" : book.optString("cover", ""));
                o.put("sort", b.optLong("sort", 0L));
            } catch (Exception e) {
                continue;
            }
            cand.put(o);
        }

        boolean[] used = new boolean[cand.length()];
        // ① 最近有笔记的前 RECENT_HEAD 本
        for (int k = 0; k < RECENT_HEAD && k < cand.length(); k++) {
            int best = -1;
            long bt = -1L;
            for (int j = 0; j < cand.length(); j++) {
                if (used[j]) continue;
                long s = cand.optJSONObject(j).optLong("sort", 0L);
                if (s > bt) {
                    bt = s;
                    best = j;
                }
            }
            if (best < 0) break;
            used[best] = true;
            out.put(cand.optJSONObject(best));
        }
        // ② 其余按划线数降序（乘数取 1e10，保证 noteCount 主导、sort 只做同数时的次级键）
        for (int k = out.length(); k < cand.length(); k++) {
            int best = -1;
            long bt = -1L;
            for (int j = 0; j < cand.length(); j++) {
                if (used[j]) continue;
                JSONObject o = cand.optJSONObject(j);
                long v = o.optLong("noteCount", 0L) * 10000000000L + o.optLong("sort", 0L);
                if (v > bt) {
                    bt = v;
                    best = j;
                }
            }
            if (best < 0) break;
            used[best] = true;
            out.put(cand.optJSONObject(best));
        }
        return out;
    }

    // ══════════════════════ ② 划线原文 ══════════════════════

    /** 这本书的划线数组（没同步过返回 null） */
    public static JSONArray marks(Context c, String bookId) {
        if (bookId == null || bookId.length() == 0) return null;
        String s = read(c, P_MARK + bookId + ".json");
        if (s == null) return null;
        try {
            JSONArray a = new JSONObject(s).optJSONArray("marks");
            return (a == null || a.length() == 0) ? null : a;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean synced(Context c, String bookId) {
        return marks(c, bookId) != null;
    }

    /**
     * 合并写入一本书的划线（按 bookmarkId 去重，已有的不动）。
     * synckey 增量回包只带新增的那几条，所以是**合并**而不是覆盖。
     */
    public static void mergeMarks(Context c, String bookId, JSONArray updated, long synckey) {
        if (bookId == null || bookId.length() == 0 || updated == null) return;
        JSONObject o = new JSONObject();
        JSONArray keep = new JSONArray();
        try {
            String old = read(c, P_MARK + bookId + ".json");
            if (old != null) {
                JSONObject oo = new JSONObject(old);
                JSONArray oa = oo.optJSONArray("marks");
                if (oa != null) {
                    for (int i = 0; i < oa.length(); i++) keep.put(oa.opt(i));
                }
            }
            HashSet<String> seen = new HashSet<String>();
            for (int i = 0; i < keep.length(); i++) {
                JSONObject m = keep.optJSONObject(i);
                if (m != null) seen.add(m.optString("bookmarkId", ""));
            }
            for (int i = 0; i < updated.length(); i++) {
                JSONObject m = updated.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("bookmarkId", "");
                if (id.length() == 0) {
                    // 老数据没有 bookmarkId → 用 bookId+range 造一个稳定的
                    id = bookId + "_" + m.optString("range", "");
                    m.put("bookmarkId", id);
                }
                if (seen.contains(id)) continue;
                seen.add(id);
                keep.put(m);
            }
            o.put("synckey", synckey);
            o.put("marks", keep);
        } catch (Exception ignored) {
            return;
        }
        write(c, P_MARK + bookId + ".json", o.toString());
        dropBookCache(bookId);         // 只有这本书的内容变了
    }

    /** 更新某条划线的章节名（异步反查到之后回填，避免下次再查） */
    public static void setChapterTitle(Context c, String bookId, String bookmarkId, String title) {
        if (bookId == null || bookmarkId == null || title == null) return;
        String s = read(c, P_MARK + bookId + ".json");
        if (s == null) return;
        try {
            JSONObject o = new JSONObject(s);
            JSONArray a = o.optJSONArray("marks");
            if (a == null) return;
            boolean hit = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if (m != null && bookmarkId.equals(m.optString("bookmarkId", ""))) {
                    m.put("chapterTitle", title);
                    hit = true;
                }
            }
            if (!hit) return;
            write(c, P_MARK + bookId + ".json", o.toString());
            dropBookCache(bookId);
        } catch (Exception ignored) {
        }
    }

    public static long markSynckey(Context c, String bookId) {
        String s = read(c, P_MARK + bookId + ".json");
        if (s == null) return 0L;
        try {
            return new JSONObject(s).optLong("synckey", 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 还没同步过划线的书 id，最多 limit 本（按索引顺序 = 最近 6 本 + 划线数降序） */
    public static List<String> unsynced(Context c, int limit) {
        List<String> out = new ArrayList<String>();
        JSONArray idx = index(c);
        if (idx == null) return out;
        for (int i = 0; i < idx.length() && out.size() < limit; i++) {
            JSONObject b = idx.optJSONObject(i);
            if (b == null) continue;
            String id = b.optString("bookId", "");
            if (id.length() == 0) continue;
            if (b.optInt("noteCount", 0) <= 0) continue;    // 零划线的书不必拉（防每轮重试）
            if (!synced(c, id)) out.add(id);
        }
        return out;
    }

    // ══════════════════════ ②b 想法 / 点评（v0.4.4）══════════════════════
    //
    // 数据源是 `/review/list/mine`（按书查），一次回包给出该书的全部个人内容：
    // 划线想法、章节点评、整本书评。**189 条实测里 91% 带原文**（`review.abstract`），
    // 剩下 8% 是整本书评/章节点评，没有可定位的原文（只有 `content`）。
    //
    // 与划线的两个不同，决定了这里不做增量：
    //   ① 接口的 `synckey` 是**翻页游标**，不是"数据版本号"（`/book/bookmarklist` 的才是），
    //      拿它当增量键会漏数据；
    //   ② 单本想法通常 1-3 条，全量重拉成本极低（55 本实测 1.3 秒拉完）。
    // 所以：**落盘即全量替换**，靠 `fetchedAt` 做 6 小时 TTL 挡掉重复请求。

    /** 想法缓存有效期 —— 想法变动比划线更慢，和索引同档 */
    public static final long IDEA_TTL_MS = 6L * 3600L * 1000L;

    /** 这本书的想法数组（没同步过返回 null） */
    public static JSONArray ideas(Context c, String bookId) {
        if (bookId == null || bookId.length() == 0) return null;
        String s = read(c, P_IDEA + bookId + ".json");
        if (s == null) return null;
        try {
            JSONArray a = new JSONObject(s).optJSONArray("ideas");
            return (a == null || a.length() == 0) ? null : a;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean ideaSynced(Context c, String bookId) {
        return ideas(c, bookId) != null;
    }

    /** 想法缓存是否还在有效期内（不需要重拉） */
    public static boolean ideaFresh(Context c, String bookId) {
        String s = read(c, P_IDEA + bookId + ".json");
        if (s == null) return false;
        try {
            long t = new JSONObject(s).optLong("fetchedAt", 0L);
            if (t <= 0) return false;
            long d = System.currentTimeMillis() - t;
            return d >= 0 && d <= IDEA_TTL_MS;
        } catch (Exception e) {
            return false;
        }
    }

    /** 全量写入一本书的想法（v0.4.4：接口给的就是全量，按 reviewId 去重后替换） */
    public static void mergeIdeas(Context c, String bookId, JSONArray items) {
        if (bookId == null || bookId.length() == 0) return;
        JSONArray keep = new JSONArray();
        HashSet<String> seen = new HashSet<String>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                JSONObject r = it.optJSONObject("review");
                if (r == null) r = it;
                String rid = r.optString("reviewId", "");
                if (rid.length() == 0) continue;
                if (seen.contains(rid)) continue;
                seen.add(rid);
                // 存精简版：只留本记要用的字段，228 本全拉也不过几十 KB
                JSONObject o = new JSONObject();
                try {
                    o.put("reviewId", rid);
                    o.put("content", r.optString("content", ""));
                    o.put("abstract", r.optString("abstract", ""));
                    o.put("range", r.optString("range", ""));
                    o.put("chapterUid", r.optInt("chapterUid", 0));
                    o.put("chapterIdx", r.optInt("chapterIdx", 0));
                    o.put("chapterName", r.optString("chapterName", ""));
                    o.put("createTime", r.optLong("createTime", 0L));
                    o.put("star", r.optInt("star", -1));
                } catch (Exception e) {
                    continue;
                }
                keep.put(o);
            }
        }
        JSONObject root = new JSONObject();
        try {
            root.put("fetchedAt", System.currentTimeMillis());
            root.put("ideas", keep);
        } catch (Exception ignored) {
        }
        write(c, P_IDEA + bookId + ".json", root.toString());
        dropBookCache(bookId);         // 只有这本书的内容变了
    }

    /**
     * 还没同步过想法的书 id，最多 limit 本。
     *
     * 按**索引顺序**取 —— 索引前 {@link #RECENT_HEAD} 本正是"最近在记的书"，
     * 所以想法预热天然是"最近写的先入池"，与 {@link #unsynced} 同一套优先级。
     * 只看 `reviewCount > 0` 的书（索引里没这个字段的老数据按"可能有"处理，多拉一次无害）。
     *
     * ── 为什么要分两趟（v0.4.4）──
     * 想法缓存的 TTL 是 6 小时，但**一轮只补 12 本**。如果直接按"TTL 过期"筛，
     * 一天只打开一次 App 的用户会永远重复拉前 12 本、后面 43 本一次都进不来。
     * 所以先补**从未拉过**的，全都拉完了才轮到**过期重拉** —— 保证覆盖，再谈新鲜。
     */
    public static List<String> unsyncedIdeas(Context c, int limit) {
        List<String> out = new ArrayList<String>();
        JSONArray idx = index(c);
        if (idx == null) return out;
        for (int i = 0; i < idx.length() && out.size() < limit; i++) {
            String id = ideaCandidate(idx.optJSONObject(i));
            if (id != null && !ideaSynced(c, id)) out.add(id);
        }
        for (int i = 0; i < idx.length() && out.size() < limit; i++) {
            String id = ideaCandidate(idx.optJSONObject(i));
            if (id != null && !out.contains(id) && !ideaFresh(c, id)) out.add(id);
        }
        return out;
    }

    /** 这本书有没有可能带想法？返回 bookId，否则 null */
    private static String ideaCandidate(JSONObject b) {
        if (b == null) return null;
        String id = b.optString("bookId", "");
        if (id.length() == 0) return null;
        if (b.optInt("reviewCount", 0) <= 0) return null;
        return id;
    }

    // ══════════════════════ ③ 按需抽样（v0.4.5 取代"全量池 + 洗牌袋"）══════════════════════

    /**
     * 全库总数（**只读索引，不碰任何笔记文件**）—— 进度行的「共 M 条」用它。
     *
     * 索引里每本书都记着 noteCount（划线数）与 reviewCount（想法数），求和即得全库规模。
     * 这正是"全库都在本地、前端只抽一小把"能成立的前提：报总数不需要把 5162 条读进内存。
     *
     * @param ideasSlot true = 只看想法（口径是想法数，全库 189）
     */
    public static int total(Context c, boolean ideasSlot) {
        JSONArray idx = index(c);
        if (idx == null) return 0;
        if (sTotKey == idx) return ideasSlot ? sTotIdeas : sTotMarks;
        int m = 0, iv = 0;
        for (int i = 0; i < idx.length(); i++) {
            JSONObject b = idx.optJSONObject(i);
            if (b == null) continue;
            m += b.optInt("noteCount", 0);
            iv += b.optInt("reviewCount", 0);
        }
        sTotKey = idx;
        sTotMarks = m;
        sTotIdeas = iv;
        return ideasSlot ? iv : m;
    }

    /** 池子里有多少条（= 全库划线数，供 UI 显示"已就绪 N 条"） */
    public static int poolSize(Context c) {
        return total(c, false);
    }

    /** 全库想法条数 */
    public static int ideaCount(Context c) {
        return total(c, true);
    }

    /** 索引里记录的全库内容总数（划线 + 想法，含未同步的） */
    public static int indexTotal(Context c) {
        return total(c, false) + total(c, true);
    }

    // ── 按书解析：一本书的内容（划线 + 想法，划线做「想法配对」去重）──

    /** 书名 → 已解析条目，小 LRU（一把 40 条通常只落在十来本书上，命中率很高） */
    private static final LinkedHashMap<String, List<NoteStats>> sBooks =
            new LinkedHashMap<String, List<NoteStats>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<NoteStats>> e) {
                    return size() > BOOK_CACHE;
                }
            };

    /**
     * 一本书的池化条目（**同步** —— 会被主线程的抽取与工作线程的落盘同时碰到）。
     *
     * 配对口径与 v0.4.4 全量池完全一致（见 {@link #poolOfBook} 里的注释）。
     */
    static synchronized List<NoteStats> itemsOfBook(Context c, String bookId) {
        List<NoteStats> out = new ArrayList<NoteStats>();
        if (bookId == null || bookId.length() == 0) return out;
        List<NoteStats> hit = sBooks.get(bookId);
        if (hit != null) return hit;
        JSONObject entry = indexEntry(c, bookId);
        if (entry != null) out = poolOfBook(c, entry);
        sBooks.put(bookId, out);
        return out;
    }

    private static JSONObject indexEntry(Context c, String bookId) {
        JSONArray idx = index(c);
        if (idx == null) return null;
        for (int i = 0; i < idx.length(); i++) {
            JSONObject b = idx.optJSONObject(i);
            if (b != null && bookId.equals(b.optString("bookId", ""))) return b;
        }
        return null;
    }

    /**
     * 把一本书的划线 + 想法合成一份条目列表（v0.4.5：从"全库池"降级为"按书"）。
     *
     * ── 配对 ──
     * 用户对某段划线写了想法时，接口会同时给出"划线"和"想法"两条记录。
     * 按 **(bookId, range)** 把想法挂到对应的划线上，同一条内容就只进池一次。
     *
     * ⚠️ 实测只有约 41%（78/189）的想法 range 能对上同书的划线 ——
     * `review.range` 与 `bookmark.range` 并不总是同一套偏移（换了版本、
     * 或想法写在已取消的划线上）。所以配对只是**去重优化**：
     * 配不上的想法独立成条，**一条都不丢**。
     * range 不中时再按"原文精确相等"兜一次底（实测多配上 5 条）。
     */
    private static List<NoteStats> poolOfBook(Context c, JSONObject b) {
        List<NoteStats> marks = new ArrayList<NoteStats>();
        List<NoteStats> ideas = new ArrayList<NoteStats>();
        String id = b.optString("bookId", "");
        String title = b.optString("title", "");
        String author = b.optString("author", "");
        String cover = b.optString("cover", "");

        JSONArray ms = marks(c, id);
        if (ms != null) {
            for (int j = 0; j < ms.length(); j++) {
                JSONObject m = ms.optJSONObject(j);
                if (m == null) continue;
                String text = m.optString("markText", "").trim();
                if (text.length() == 0) continue;
                NoteStats n = new NoteStats();
                n.kind = NoteStats.KIND_MARK;
                n.bookId = id;
                n.bookmarkId = m.optString("bookmarkId", id + "_" + m.optString("range", ""));
                n.title = title;
                n.author = author;
                n.coverUrl = cover;
                n.markText = text;
                n.range = m.optString("range", "");
                n.chapterUid = m.optInt("chapterUid", 0);
                n.chapterIdx = m.optInt("chapterIdx", 0);
                n.chapterTitle = m.optString("chapterTitle", "");
                n.createTime = m.optLong("createTime", 0L);
                marks.add(n);
            }
        }

        JSONArray is = ideas(c, id);
        if (is != null) {
            for (int j = 0; j < is.length(); j++) {
                JSONObject it = is.optJSONObject(j);
                if (it == null) continue;
                String content = it.optString("content", "").trim();
                String abs = it.optString("abstract", "").trim();
                if (content.length() == 0 && abs.length() == 0) continue;
                NoteStats n = new NoteStats();
                n.kind = NoteStats.KIND_IDEA;
                n.bookId = id;
                n.bookmarkId = it.optString("reviewId", "");
                n.title = title;
                n.author = author;
                n.coverUrl = cover;
                n.markText = abs;                 // 原文（可能为空：整本书评/章节点评）
                n.ideaText = content;             // 用户自己写的那段
                n.range = it.optString("range", "");
                n.star = it.optInt("star", -1);
                n.chapterUid = it.optInt("chapterUid", 0);
                n.chapterIdx = it.optInt("chapterIdx", 0);
                n.chapterTitle = it.optString("chapterName", "");
                n.createTime = it.optLong("createTime", 0L);
                ideas.add(n);
            }
        }

        java.util.HashMap<String, NoteStats> byRange = new java.util.HashMap<String, NoteStats>();
        java.util.HashMap<String, ArrayList<NoteStats>> byText =
                new java.util.HashMap<String, ArrayList<NoteStats>>();
        for (int i = 0; i < marks.size(); i++) {
            NoteStats m = marks.get(i);
            if (m.range.length() > 0) byRange.put(m.range, m);
            String tk = normText(m.markText);
            ArrayList<NoteStats> l = byText.get(tk);
            if (l == null) {
                l = new ArrayList<NoteStats>();
                byText.put(tk, l);
            }
            l.add(m);
        }
        List<NoteStats> lone = new ArrayList<NoteStats>();
        for (int i = 0; i < ideas.size(); i++) {
            NoteStats it = ideas.get(i);
            NoteStats host = (it.range.length() > 0) ? byRange.get(it.range) : null;
            if (host == null && it.markText.length() > 0) {
                ArrayList<NoteStats> l = byText.get(normText(it.markText));
                if (l != null) {                       // 同文可能有多个划线段，取还没带想法的那个
                    for (int k = 0; k < l.size(); k++) {
                        if (!l.get(k).hasIdea() && l.get(k).kind.equals(NoteStats.KIND_MARK)) {
                            host = l.get(k);
                            break;
                        }
                    }
                }
            }
            if (host != null && !host.hasIdea()) {
                host.ideaText = it.ideaText;
                host.kind = NoteStats.KIND_IDEA;
                host.star = it.star;
                if (host.chapterTitle.length() == 0) host.chapterTitle = it.chapterTitle;
            } else {
                lone.add(it);
            }
        }

        List<NoteStats> out = marks;
        out.addAll(lone);
        return out;
    }

    /**
     * 归一化正文用于"想法↔划线"的兜底配对：去首尾空白、去省略号、压掉所有空白字符。
     *
     * 只用于**完全相等**的比较（不做前缀匹配）—— 前缀匹配在两个人名/同一句话重复出现的
     * 书里会错配，而错配的代价是把想法挂到不相干的划线上，比不配还糟。
     */
    private static String normText(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            if (ch == '\u2026' || ch == '.' || ch == '\u3002') continue;   // … . 。
            sb.append(ch);
        }
        return sb.toString();
    }

    // ── 抽样：抽书 → 抽条 ──

    /** 抽样候选：一个层（划线层 / 想法层）里的书 + 权重（= 索引里的条数，这就是"按条均匀"） */
    private static final class Cand {
        String id;
        int weight;
    }

    /**
     * 某个层的候选书。
     *
     * ⚠️ 只把**本地已经有文件**的书列为候选 —— 没预热到的书抽到了也读不出内容。
     * 权重仍取索引里的总条数，所以本地内容越多，这一层的分布就越接近全库真实分布。
     */
    private static List<Cand> candidates(Context c, JSONArray idx, boolean ideaLayer) {
        List<Cand> out = new ArrayList<Cand>();
        if (idx == null) return out;
        for (int i = 0; i < idx.length(); i++) {
            JSONObject b = idx.optJSONObject(i);
            if (b == null) continue;
            String id = b.optString("bookId", "");
            if (id.length() == 0) continue;
            int w = ideaLayer ? b.optInt("reviewCount", 0) : b.optInt("noteCount", 0);
            if (w <= 0) continue;
            File f = f(c, (ideaLayer ? P_IDEA : P_MARK) + id + ".json");
            if (!f.exists() || f.length() == 0) continue;
            Cand cd = new Cand();
            cd.id = id;
            cd.weight = w;
            out.add(cd);
        }
        return out;
    }

    /** 按权重随机挑一本书（权重 = 条数 → **按条均匀**，大部头出现得更频繁，符合直觉） */
    private static String pickBook(List<Cand> cs, Random r) {
        int sum = 0;
        for (int i = 0; i < cs.size(); i++) sum += cs.get(i).weight;
        if (sum <= 0) return null;
        int t = r.nextInt(sum);
        for (int i = 0; i < cs.size(); i++) {
            t -= cs.get(i).weight;
            if (t < 0) return cs.get(i).id;
        }
        return cs.get(cs.size() - 1).id;
    }

    /**
     * 抽新的一小把（{@link #BATCH} 条，一轮内不重复）。
     *
     * 想法层按 {@link #IDEA_EVERY} 交错进来（每 7 条划线插 1 条想法 ≈ 12.5%）：
     * 位置**随机偏移**而不是固定在第 8、16、24 格，免得"每次翻到第 8 格就是想法"。
     * 「只看想法」档整把都从想法层取。
     */
    private static List<String[]> newBatch(Context c, boolean ideasSlot) {
        long t0 = android.os.SystemClock.uptimeMillis();
        List<String[]> out = new ArrayList<String[]>(BATCH);
        JSONArray idx = index(c);
        if (idx == null) return out;
        Random r = new Random(System.nanoTime());
        List<Cand> ideaC = candidates(c, idx, true);
        List<Cand> markC = candidates(c, idx, false);

        boolean[] wantIdea = new boolean[BATCH];
        if (ideasSlot) {
            for (int i = 0; i < BATCH; i++) wantIdea[i] = true;
        } else if (!ideaC.isEmpty()) {
            int off = r.nextInt(IDEA_EVERY);
            for (int i = off; i < BATCH; i += IDEA_EVERY) wantIdea[i] = true;
        }

        HashSet<String> seen = new HashSet<String>();
        HashSet<String> books = new HashSet<String>();
        int ideaN = 0;
        for (int i = 0; i < BATCH; i++) {
            boolean idea = wantIdea[i] && !ideaC.isEmpty();
            List<Cand> cs = idea ? ideaC : markC;
            if (cs.isEmpty()) cs = idea ? markC : ideaC;      // 这一层还没料 → 退到另一层
            String[] s = drawOne(c, r, cs, idea, seen);
            if (s == null) continue;
            out.add(s);
            books.add(s[0]);
            if (idea) ideaN++;                               // 层是按 hasIdea() 分的，直接数槽位就准
        }
        // 诊断：这一把抽了多少条、落在几本书上、其中几条是想法（≈1/8）
        CardDebug.note(c, "newBatch slot=" + ideasSlot + " items=" + out.size()
                + " books=" + books.size() + " ideas=" + ideaN
                + " candM=" + markC.size() + " candI=" + ideaC.size()
                + " ms=" + (android.os.SystemClock.uptimeMillis() - t0));
        return out;
    }

    /** 抽一条：随机挑书 → 在"该层的条目"里均匀取一条；重复了就换一本书再试 */
    private static String[] drawOne(Context c, Random r, List<Cand> cs, boolean ideaLayer,
                                   HashSet<String> seen) {
        for (int attempt = 0; attempt < 10 && !cs.isEmpty(); attempt++) {
            String bookId = pickBook(cs, r);
            if (bookId == null) return null;
            List<NoteStats> items = itemsOfBook(c, bookId);
            int n = 0;
            for (int k = 0; k < items.size(); k++) if (items.get(k).hasIdea() == ideaLayer) n++;
            if (n == 0) continue;
            int at = r.nextInt(n);
            for (int k = 0; k < items.size(); k++) {
                NoteStats it = items.get(k);
                if (it.hasIdea() != ideaLayer) continue;
                if (at-- > 0) continue;
                if (it.bookmarkId.length() == 0) break;
                if (!seen.add(bookId + "|" + it.bookmarkId)) break;   // 这一把里重了 → 换本书重抽
                return new String[]{bookId, it.bookmarkId};
            }
        }
        return null;
    }

    // ── 这一把的落盘与解析 ──

    private static List<String[]> sBatch;
    private static boolean sBatchSlot;
    private static boolean sBatchLoaded;

    /** 当前这一把（内存优先，落盘只是为了"进程重启后还是同一条"） */
    private static synchronized List<String[]> batch(Context c, boolean ideasSlot) {
        if (sBatchLoaded && sBatchSlot == ideasSlot && sBatch != null) return sBatch;
        List<String[]> out = new ArrayList<String[]>();
        String s = read(c, ideasSlot ? F_BATCH_I : F_BATCH);
        if (s != null) {
            try {
                JSONArray a = new JSONObject(s).optJSONArray("items");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String b = o.optString("b", "");
                    String k = o.optString("k", "");
                    if (b.length() == 0 || k.length() == 0) continue;
                    out.add(new String[]{b, k});
                }
            } catch (Exception ignored) {
            }
        }
        sBatch = out;
        sBatchSlot = ideasSlot;
        sBatchLoaded = true;
        return out;
    }

    private static synchronized void saveBatch(Context c, boolean ideasSlot, List<String[]> b) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < b.size(); i++) {
            JSONObject o = new JSONObject();
            try {
                o.put("b", b.get(i)[0]);
                o.put("k", b.get(i)[1]);
            } catch (Exception ignored) {
            }
            a.put(o);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("savedAt", System.currentTimeMillis());
            root.put("items", a);
        } catch (Exception ignored) {
        }
        write(c, ideasSlot ? F_BATCH_I : F_BATCH, root.toString());
        sBatch = b;
        sBatchSlot = ideasSlot;
        sBatchLoaded = true;
    }

    private static NoteStats resolve(Context c, List<String[]> b, int pos) {
        if (b == null || pos < 0 || pos >= b.size()) return null;
        return resolveSlot(c, b.get(pos)[0], b.get(pos)[1]);
    }

    /** 把 (bookId, 唯一id) 还原成条目 —— 只读那一本书的文件（LRU 内多半已有） */
    private static NoteStats resolveSlot(Context c, String bookId, String key) {
        if (bookId == null || bookId.length() == 0 || key == null || key.length() == 0) return null;
        List<NoteStats> items = itemsOfBook(c, bookId);
        for (int i = 0; i < items.size(); i++) {
            NoteStats n = items.get(i);
            if (key.equals(n.bookmarkId)) return n;
        }
        return null;
    }

    /**
     * 取一条内容。
     *
     * @param manual    true = 用户点了「换一条」（立刻前进一条）；
     *                  false = 常规展示（同一天内返回当天那条，跨天才自动前进）
     * @param ideasSlot true = 走「只看想法」那套**独立**的抽取状态（App 本记页的筛选）。
     *
     * 为什么要分两套状态：桌面卡片始终用全量档，App 里切成「只看想法」不能把卡片也改掉。
     * 两套状态各有自己的一把、自己的序号，互不干扰（见 {@link #k}）。
     */
    public static synchronized NoteStats pick(Context c, boolean manual) {
        return pick(c, manual, false);
    }

    /** 同 {@link #pick(Context, boolean)} */
    public static synchronized NoteStats pick(Context c, boolean manual, boolean ideasSlot) {
        long t0 = android.os.SystemClock.uptimeMillis();     // 取一帧内容的耗时（写入诊断日志）
        SharedPreferences p = sp(c);
        // 每日一签：同一天不主动换
        if (!manual && dayKey().equals(p.getString(k(K_DAY, ideasSlot), ""))) {
            NoteStats cur = currentIn(c, ideasSlot);
            if (cur != null) return cur;
        }

        List<String[]> b = batch(c, ideasSlot);
        int pos = p.getInt(k(K_BATCH_POS, ideasSlot), -1) + 1;
        NoteStats n = resolve(c, b, pos);
        if (pos < 0 || pos >= b.size() || n == null) {
            // 这一把翻完了（或里面的书被清了）→ 抽新的一把
            b = newBatch(c, ideasSlot);
            saveBatch(c, ideasSlot, b);
            pos = 0;
            n = resolve(c, b, pos);
        }
        if (n == null) return null;

        int m = total(c, ideasSlot);
        int taken = p.getInt(k(K_TAKEN, ideasSlot), 0) + 1;
        if (m > 0 && taken > m) taken = 1;         // 全库看完一轮 → 序号回到 1
        p.edit()
                .putInt(k(K_BATCH_POS, ideasSlot), pos)
                .putString(k(K_DAY, ideasSlot), dayKey())
                .putInt(k(K_TAKEN, ideasSlot), taken)
                .putString(k(K_CUR_B, ideasSlot), n.bookId)
                .putString(k(K_CUR_K, ideasSlot), n.bookmarkId)
                .putString(k(K_HIST, ideasSlot),
                        pushHist(p.getString(k(K_HIST, ideasSlot), ""), n.bookId, n.bookmarkId))
                .apply();                          // apply：主线程不落 fsync（commit 会卡一帧）
        // 诊断：一次取用的耗时与坐标。v0.4.4 曾经是 700ms/次（全量建池 + 缓存从未命中），
        // 这条日志让"又卡起来了"能一眼看出来（本机 logcat 吞第三方日志，只走文件通道）
        CardDebug.note(c, "pick manual=" + manual + " slot=" + ideasSlot
                + " ms=" + (android.os.SystemClock.uptimeMillis() - t0)
                + " pos=" + pos + "/" + b.size() + " M=" + m
                + " cur=" + n.bookId + "|" + n.bookmarkId);
        return n;
    }

    /**
     * 取**上一条**（v0.4.2，用户拍板"卡片左下角一个对称的独立按钮"）。
     *
     * 为什么需要历史栈：抽取是**随机抽样**的，靠"上一条 = 这一把的前一格"回退只会退到
     * 另一条随机内容上去。所以单独记一条访问历史（{@link #K_HIST}，**栈顶在前**，
     * 栈顶恒等于当前展示的那条）。
     *
     * 回看 = 丢弃栈顶、把新的栈顶当当前条（旧历史原样保留在后面），
     * 所以连点多次会沿着"来时的路"一条条退回去，而不会变成前进。
     *
     * 历史空时（第一次打开就点上一条）退到这一把里的前一条，环形 —— 用户不会撞墙。
     */
    public static synchronized NoteStats pickPrev(Context c) {
        return pickPrev(c, false);
    }

    /** 同 {@link #pickPrev(Context)}，{@code ideasSlot} 见 {@link #pick} */
    public static synchronized NoteStats pickPrev(Context c, boolean ideasSlot) {
        SharedPreferences p = sp(c);
        JSONArray hist = histOf(p.getString(k(K_HIST, ideasSlot), ""));
        String curB = p.getString(k(K_CUR_B, ideasSlot), "");
        String curK = p.getString(k(K_CUR_K, ideasSlot), "");

        // 栈顶若不是当前条（跨天自动前进后没来得及对齐等），就从栈顶本身找起
        int start = 0;
        if (hist.length() > 0) {
            JSONObject o0 = hist.optJSONObject(0);
            if (o0 != null && curB.equals(o0.optString("b", ""))
                    && curK.equals(o0.optString("k", ""))) {
                start = 1;
            }
        }

        NoteStats n = null;
        JSONArray rest = new JSONArray();
        for (int i = start; i < hist.length(); i++) {
            JSONObject o = hist.optJSONObject(i);
            if (o == null) continue;
            if (n == null) {
                n = resolveSlot(c, o.optString("b", ""), o.optString("k", ""));
                if (n == null) continue;           // 这条读不出来了 → 再往前找
                continue;
            }
            rest.put(o);
        }

        SharedPreferences.Editor ed = p.edit();
        if (n == null) {
            List<String[]> b = batch(c, ideasSlot);
            if (b.isEmpty()) return null;
            int pos = p.getInt(k(K_BATCH_POS, ideasSlot), 0) - 1;
            if (pos < 0) pos = b.size() - 1;
            n = resolve(c, b, pos);
            if (n == null) return null;
            ed.putInt(k(K_BATCH_POS, ideasSlot), pos);
        }

        int taken = p.getInt(k(K_TAKEN, ideasSlot), 1) - 1;
        if (taken < 1) taken = 1;
        ed.putInt(k(K_TAKEN, ideasSlot), taken)
                .putString(k(K_CUR_B, ideasSlot), n.bookId)
                .putString(k(K_CUR_K, ideasSlot), n.bookmarkId)
                .putString(k(K_HIST, ideasSlot), rest.toString())
                .putString(k(K_DAY, ideasSlot), dayKey())
                .apply();
        return n;
    }

    /**
     * 本记的进度 {现在第几条(1-based), 共几条}；两条都没有返回 null。
     *
     * 「共 M 条」= **全库总数**（索引求和，见 {@link #total}）—— 不是"当前这一把 40 条"。
     * 序号是"累计已看条数"（「换一条」+1、「上一条」-1），跨把累加，看完全库一圈回到 1。
     */
    public static int[] progress(Context c) {
        return progress(c, false);
    }

    /** 同 {@link #progress(Context)}，{@code ideasSlot} 见 {@link #pick} */
    public static int[] progress(Context c, boolean ideasSlot) {
        int m = total(c, ideasSlot);
        if (m <= 0) {
            // 索引还没建好（首次装好还没同步）→ 退到"这一把的长度"，至少不是空白
            m = batch(c, ideasSlot).size();
            if (m <= 0) return null;
        }
        int n = sp(c).getInt(k(K_TAKEN, ideasSlot), 0);
        if (n < 1) n = 1;
        if (n > m) n = m;
        return new int[]{n, m};
    }

    /** 当前展示的那条（不做任何前进），用于刷新后重绘 */
    public static synchronized NoteStats current(Context c) {
        return current(c, false);
    }

    /** 同 {@link #current(Context)}，{@code ideasSlot} 见 {@link #pick} */
    public static synchronized NoteStats current(Context c, boolean ideasSlot) {
        NoteStats n = currentIn(c, ideasSlot);
        return n != null ? n : pick(c, false, ideasSlot);
    }

    /** 从 (K_CUR_B, K_CUR_K) 还原当前条，不做前进 */
    private static NoteStats currentIn(Context c, boolean ideasSlot) {
        SharedPreferences p = sp(c);
        return resolveSlot(c, p.getString(k(K_CUR_B, ideasSlot), ""),
                p.getString(k(K_CUR_K, ideasSlot), ""));
    }

    // ── 历史栈（JSON 数组，栈顶在前）──

    private static JSONArray histOf(String s) {
        if (s == null || s.length() == 0) return new JSONArray();
        try {
            JSONArray a = new JSONArray(s);
            return a == null ? new JSONArray() : a;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 把刚展示的那条压进历史串（栈顶在前），超上限截尾 */
    private static String pushHist(String old, String bookId, String key) {
        JSONArray a = histOf(old);
        JSONArray out = new JSONArray();
        JSONObject o = new JSONObject();
        try {
            o.put("b", bookId);
            o.put("k", key);
        } catch (Exception ignored) {
        }
        out.put(o);
        for (int i = 0; i < a.length() && out.length() < HIST_MAX; i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            if (bookId.equals(e.optString("b", "")) && key.equals(e.optString("k", ""))) continue;
            out.put(e);
        }
        return out.toString();
    }

    /** 是否「只看想法」（App 本记页的切换，v0.4.4）。桌面卡片不受影响 */
    public static boolean ideasOnly(Context c) {
        return sp(c).getBoolean(K_IDEAS_ONLY, false);
    }

    /** 切「只看想法」—— 内容都在本地，切一下只是换一套抽取状态，不用重读任何文件 */
    public static void setIdeasOnly(Context c, boolean v) {
        sp(c).edit().putBoolean(K_IDEAS_ONLY, v).apply();
    }

    /** 两套抽取状态（默认档 / 只看想法）的键名与批次文件 —— 后缀 `_i` 区分 */
    private static String k(String base, boolean ideasSlot) {
        return ideasSlot ? base + "_i" : base;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String dayKey() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(new java.util.Date());
    }

    /** 清掉本记的全部本地数据（设置页「清空缓存」用） */
    public static void clear(Context c) {
        File dir = c.getFilesDir();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName();
                if (n.startsWith("nt_") || n.startsWith("ni_")) f.delete();
            }
        }
        sp(c).edit().clear().apply();
        dropAllCache();
    }

    // ── 文件读写 ──

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

    private static void write(Context c, String name, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f(c, name), false);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
