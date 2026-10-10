package com.inkread.weekread.core;

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
    /**
     * 🆕 TASK-057：**选书档**的批次文件 —— 独立第三槽，与默认档/只看想法档互不干扰。
     *
     * 🔴 为什么独立（真机实测踩到）：App 本记页「全部（未选书）」与「选了某本书」是两套
     * **浏览空间**（批次 / 序号 / 当前条 / 历史各不相同）—— 若选书档复用默认档，选书会把
     * 「全部」档的进度与历史连人带号一起换掉，切回「全部」时"看到哪儿"就丢了。
     * <p>⚠️ **TASK-057 R1（2026-10-06）起，桌面卡片会跟随选书档**（{@link #desktopSlot}）——
     * 桌面与 App 在「选书」维度上**共用**本档；旧 A6「桌面逐像素不变」的隔离判据已作废。
     */
    private static final String F_BATCH_P = "nt_batch_p.json";
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

    /** 「只看想法」开关（App 本记页的切换；🔴 桌面卡片**不跟随**，见 {@link #desktopSlot}） */
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

    /**
     * 🆕 TASK-057（K11）**选书档**：用户在 App 本记页选中的书（`""` = 未选 ⇒ 走原随机池）。
     *
     * 🔴 **TASK-057 R1（2026-10-06）：桌面卡片也读本键**（经 {@link #desktopSlot}）——
     * 用户明确要求「App 选书后桌面同步换池子」；⚠️ 旧 A6「桌面不读该键」已作废。
     * · 与「只看想法」**互斥**（卡面 Q6 = 乙）⇒ 见 {@link #setPickedBook} / {@link #setIdeasOnly}。
     */
    private static final String K_PICK_BOOK = "pick_book";

    /**
     * 🆕 TASK-057：**抽取槽位**（0/1/2）—— 取代原先的布尔 `ideasSlot`。
     *
     * · {@link #SLOT_DEFAULT}（0）= 默认档：**未选书时的桌面卡片** + App 本记页「全部」且没选书时；
     * · {@link #SLOT_IDEA}（1）= 只看想法档：App 本记页专属（键后缀 `_i`，同旧 `ideasSlot=true`）；
     * · {@link #SLOT_PICK}（2）= **选书档**：App 本记页 + **R1 起的桌面卡片**（键后缀 `_p` + `nt_batch_p.json`）。
     *
     * 🔴 为什么要第三槽：App 的「全部」与「选了某本书」是两套浏览空间，混用会互相覆盖进度/历史。
     * <p>⚠️ TASK-057 R1（2026-10-06）：桌面卡片**跟随选书**（选了书就走本槽）——旧 A6 隔离判据作废。
     */
    private static final int SLOT_DEFAULT = 0;
    private static final int SLOT_IDEA = 1;
    private static final int SLOT_PICK = 2;

    /** 索引自动有效期 —— 笔记书列表变动慢，6 小时一次足够 */
    public static final long INDEX_TTL_MS = 6L * 3600L * 1000L;
    /** 手动刷新索引的最小间隔 */
    public static final long INDEX_MIN_MS = 10L * 60L * 1000L;

    /**
     * 索引格式版本（TASK-056 起记录）。
     *
     * <pre>
     * v2 = 每本书多两个字段 `charCount` / `ideaChars`（字数）。
     * v3 = 🆕 `TASK-086` 拍板④（2026-10-10）：`compactIndex()` 每本书**多留 5 个价格/进度字段**
     *      （`centPrice` / `priceY` / `origY` / `free` / `progress`）⇒ 索引的**结构语义已变**。
     * </pre>
     *
     * ⚠️ **读到老版本（无 `v` / `v<3`）不强制重建** —— 缺字段按各自的 `optInt(…, 默认值)` 读，
     * 功能不受影响（缺价 ⇒ 该行落 {@link BillMoney#UNKNOWN} ⇒ 显示 `—`，正是卡面 §老账兼容要的形态）；
     * 下次正常的索引刷新（{@link #saveIndex}）会把新字段给每本书补齐，
     * 已算出的字数由 {@link #carryCharCounts} 搬过来，不会清零。
     * 这样避免"升级即多发一次 `/user/notebooks`"（卡面「零新请求」的红利）。
     *
     * <p>🔴 **本常量目前只被写、从不被读**（写入点：{@link #saveIndex} 与
     * {@link #writeIndexKeepCache} 的 {@code o.put("v", INDEX_VERSION)}；全仓**没有** {@code optInt("v")}
     * 之类的版本闸）⇒ 升到 3 **不触发任何回退 / 重建分支**，老索引（v=2）靠字段级默认值 + 字数搬运容忍。
     * 复核命令与输出见 `验证记录/201`（拍板④）。
     */
    public static final int INDEX_VERSION = 3;

    // ══════════════════════ ① 索引（内存缓存 + 文件指纹）══════════════════════

    private static JSONArray sIdx;
    private static String sIdxStamp = "";
    /**
     * 已解析索引里的 `savedAt`（**索引从服务端刷新的时刻**）。
     *
     * ⚠️ TASK-056：`writeIndexKeepCache` 只回填字数，**不是**一次索引刷新，
     * 所以写回时必须**沿用**这个值 —— 否则 `savedAt` 被顶成"现在"，
     * {@link #indexAge}/{@link #indexFresh} 会误判"索引刚刷过"，导致长期不再去拉笔记本列表。
     */
    private static long sIdxSavedAt;
    /** 全库总数（{@link #total}）的内存缓存，跟着索引走 */
    private static JSONArray sTotKey;
    private static int sTotMarks = -1, sTotIdeas = -1;
    /**
     * 全库**字数**缓存（TASK-056，K10b）—— 与 {@link #sTotKey} 同一份索引快照，
     * 一次遍历同时算出四个量。`-1` = 未算。
     *
     * · {@link #sTotChars} = 全库**笔记字数**（划线原文 + 想法正文）
     * · {@link #sTotIdeaChars} = 全库**想法正文字数**（用户自己写的那部分，`sTotChars` 的子集）
     */
    private static int sTotChars = -1, sTotIdeaChars = -1;

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
            JSONObject o = new JSONObject(s);          // TASK-056：顺手把 savedAt 记下来
            JSONArray a = o.optJSONArray("books");
            if (a == null || a.length() == 0) {
                sIdx = null;
                sIdxStamp = "";
                return null;
            }
            sIdx = a;
            sIdxStamp = st;
            sIdxSavedAt = o.optLong("savedAt", 0L);
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

    // ══════════════════════ 🆕 TASK-086：索引里的书价 / 进度 ══════════════════════

    /**
     * 从**本地索引**取某本书的价（**分**）。
     *
     * <p>🔴 索引读的是"压缩时多留的那几个字段"（见 {@link #compactIndex}）——
     * 数据来自 `/user/notebooks`，**不发任何请求**。
     *
     * @return {@code > 0} 有价（分）；{@code 0} 明确免费（`free==1`）；
     *         {@link BillMoney#UNKNOWN} = 查不到 / 老索引无字段（**未知**，不是 0 元）
     */
    public static int priceFenOf(Context c, String bookId) {
        if (c == null || bookId == null || bookId.length() == 0) return BillMoney.UNKNOWN;
        JSONArray a = index(c);
        if (a == null) return BillMoney.UNKNOWN;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (!bookId.equals(o.optString("bookId", ""))) continue;
            return BillMoney.fenOf(o.optInt("centPrice", 0), o.optDouble("priceY", 0.0),
                    o.optInt("origY", 0), o.optInt("free", 0) == 1);
        }
        return BillMoney.UNKNOWN;
    }

    /**
     * 从本地索引取某本书的**阅读进度**（`readingProgress`，0–100）。
     *
     * <p>用途：`/book/getprogress` 拉失败时的**兜底**（索引是上一次同步的快照，略旧但真实）。
     *
     * @return {@code 0..100}；查不到 / 老索引无字段 ⇒ {@code -1}
     */
    public static int progressOf(Context c, String bookId) {
        if (c == null || bookId == null || bookId.length() == 0) return -1;
        JSONArray a = index(c);
        if (a == null) return -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (!bookId.equals(o.optString("bookId", ""))) continue;
            int p = o.optInt("progress", -1);
            return (p >= 0 && p <= 100) ? p : -1;
        }
        return -1;
    }

    /**
     * 存索引（传进来的已是精简数组）。
     *
     * ⚠️ TASK-056：`compactIndex` 出来的数组字数是**占位 0**（接口没给正文），
     * 真正算出来的字数记在**旧索引**里。若直接覆盖，每次索引刷新都会把已算的字数清零。
     * 所以这里先把**旧索引里已算出的字数搬过来**（{@link #carryCharCounts}），再落盘。
     */
    public static void saveIndex(Context c, JSONArray books) {
        // 读旧索引必须是**落盘前**：index() 拿的是当前文件（尚未被本次覆盖）
        JSONArray prev = index(c);
        carryCharCounts(prev, books);
        JSONObject o = new JSONObject();
        try {
            o.put("savedAt", System.currentTimeMillis());
            o.put("v", INDEX_VERSION);
            o.put("books", books == null ? new JSONArray() : books);
        } catch (Exception ignored) {
        }
        write(c, F_INDEX, o.toString());
        dropIndexCache();
    }

    /**
     * 把旧索引里已算出的字数搬到新索引（TASK-056）。
     *
     * 按 bookId 对齐：新索引里出现的、旧索引里算过的书 ⇒ 沿用旧值；
     * 新出现的书（旧索引没有 / 旧索引是没字数字段的**老格式**）⇒ 保持占位 0，
     * 等它被 {@link #poolOfBook} 读到正文时再回填。**老索引不报错、不崩**（A3）。
     */
    private static void carryCharCounts(JSONArray prev, JSONArray next) {
        if (prev == null || next == null) return;
        java.util.HashMap<String, int[]> m = new java.util.HashMap<String, int[]>();
        for (int i = 0; i < prev.length(); i++) {
            JSONObject b = prev.optJSONObject(i);
            if (b == null) continue;
            String id = b.optString("bookId", "");
            if (id.length() == 0) continue;
            int cc = b.optInt("charCount", 0);
            int ic = b.optInt("ideaChars", 0);
            if (cc > 0 || ic > 0) m.put(id, new int[]{cc, ic});
        }
        if (m.isEmpty()) return;
        for (int i = 0; i < next.length(); i++) {
            JSONObject b = next.optJSONObject(i);
            if (b == null) continue;
            int[] v = m.get(b.optString("bookId", ""));
            if (v == null) continue;
            try {
                b.put("charCount", v[0]);
                b.put("ideaChars", v[1]);
            } catch (Exception ignored) {
            }
        }
    }

    /** 索引换了：索引缓存、总数缓存、按书缓存一起作废（**不动当前这一把**） */
    private static synchronized void dropIndexCache() {
        sIdx = null;
        sIdxStamp = "";
        sIdxSavedAt = 0L;
        sTotKey = null;
        sTotMarks = -1;
        sTotIdeas = -1;
        sTotChars = -1;
        sTotIdeaChars = -1;
        sBooks.clear();
        sNoteAt.clear();
    }

    /** 某本书的文件换了：只作废那一本的缓存（当前这一把照旧，条目按 key 重新解析） */
    private static synchronized void dropBookCache(String bookId) {
        if (bookId != null && bookId.length() > 0) {
            sBooks.remove(bookId);
            sNoteAt.remove(bookId);      // v0.8.1：「更新于」时间戳也随书失效，下次读新落盘值
        }
    }

    /** 清掉全部内存缓存（设置页「清空缓存」用） */
    private static synchronized void dropAllCache() {
        dropIndexCache();
        sBatch = null;
        sBatchLoaded = false;
        sBatchSlot = -1;          // 🆕 TASK-057：槽位缓存一并失效（免与下一槽误命中）
        sNoteAt.clear();
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
                // ── 字数（TASK-056，K10b）──
                // `/user/notebooks` 只给条数、不给正文，所以这里**只能先占 0**：
                // 真实字数在 {@link #poolOfBook} 读到这本书的正文时**顺手累加**后回填
                // （{@link #setCharCount}）。索引刷新时由 {@link #saveIndex} 把旧值搬过来，
                // 保证"重建索引"不会把已算出的字数清零。
                o.put("charCount", 0);      // 笔记字数 = 划线原文 + 想法正文
                o.put("ideaChars", 0);      // 其中的想法正文（用户自撰）
                // ── 🆕 TASK-086：**书价与进度**（`/user/notebooks` 回包里本来就有，压缩时被丢掉了）──
                //    🔴 加价**零新增请求** —— 数据在 `book` 子对象里，同一次回包一路带回来的。
                //    老索引没有这些字段 ⇒ `optXxx` 取默认值 ⇒ **未知**（不是 0 元），不崩、不误判。
                o.put("centPrice", book == null ? 0 : book.optInt("centPrice", 0));
                o.put("priceY", book == null ? 0.0 : book.optDouble("price", 0.0));
                o.put("origY", book == null ? 0 : book.optInt("originalPrice", 0));
                o.put("free", book == null ? 0 : book.optInt("free", 0));
                // 进度在**条目顶层**（不在 `book` 里）—— 与价格同一次回包（实付的兜底数据源）
                o.put("progress", b.optInt("readingProgress", -1));
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
            o.put("fetchedAt", System.currentTimeMillis());   // v0.5.3：R03 的 TTL 判定要用
            o.put("marks", keep);
        } catch (Exception ignored) {
            return;
        }
        write(c, P_MARK + bookId + ".json", o.toString());
        dropBookCache(bookId);         // 只有这本书的内容变了
    }

    // ══════════════════════ ②a-2 划线的"重拉"与全量替换（v0.5.3，R03）══════════════════════
    //
    // 上一版只有"从未同步过的书才拉"这一条路（`unsynced()` 用 `!synced()` 过滤），
    // 于是**一本书只要有过划线缓存，就永远不会再更新**：新划的线看不到，
    // 删掉的线还留在本记里。手动刷新也只影响索引与数量，不改这个筛选条件。

    /**
     * 划线缓存的有效期（v0.5.3）。比想法/索引的 6 小时短 —— 用户是**边读边划**的，
     * "刚划的线今天就能在卡片上看到"才是这个功能的意义（索引顺序也是"最近在记的在前"，
     * 两者配合起来，最近读的几本书总是最先被刷新）。
     */
    public static final long MARK_TTL_MS = 2L * 3600L * 1000L;

    /** 这本书的划线是什么时候拉回来的（毫秒）；没拉过 / 老数据没时间戳 → 0 */
    public static long markFetchedAt(Context c, String bookId) {
        if (bookId == null || bookId.length() == 0) return 0L;
        String s = read(c, P_MARK + bookId + ".json");
        if (s == null) return 0L;
        try {
            return new JSONObject(s).optLong("fetchedAt", 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 这本书的划线缓存还在有效期内（不需要重拉） */
    public static boolean markFresh(Context c, String bookId) {
        long t = markFetchedAt(c, bookId);
        if (t <= 0) return false;                        // 老数据没时间戳 → 当作过期，重拉一次补上
        long d = System.currentTimeMillis() - t;
        return d >= 0 && d <= MARK_TTL_MS;
    }

    /**
     * **全量替换**一本书的划线（v0.5.3，R03）。
     *
     * 为什么可以当作全量：官方接口文档里 `/book/bookmarklist` **只有 `bookId` 一个请求参数**，
     * 回包 `updated[]` 就是这本书的划线列表（`synckey` 只是回包里的数据版本号，
     * 我们从来没有把它当请求参数传过 —— 它是"数据版本号"还是"翻页游标"是另一回事）。
     * 所以每次调用拿到的都是全量，直接替换即可：
     *   · 新增的划线会进来（R03 的主要缺口：旧版对已有缓存的书**根本不重拉**）；
     *   · 用户删掉的划线也会随之消失（旧版是 merge，只增不减，删了还在）。
     *
     * ⚠️ 将来若确认了增量语义，再改回"合并"；在此之前不猜。
     */
    public static void replaceMarks(Context c, String bookId, JSONArray marks, long synckey) {
        if (bookId == null || bookId.length() == 0) return;
        JSONArray keep = new JSONArray();
        HashSet<String> seen = new HashSet<String>();
        for (int i = 0; marks != null && i < marks.length(); i++) {
            JSONObject m = marks.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("bookmarkId", "");
            if (id.length() == 0) {
                id = bookId + "_" + m.optString("range", "");
                try {
                    m.put("bookmarkId", id);
                } catch (Exception ignored) {
                }
            }
            if (!seen.add(id)) continue;                 // 同一本书里 bookmarkId 去重
            keep.put(m);
        }
        JSONObject o = new JSONObject();
        try {
            o.put("synckey", synckey);
            o.put("fetchedAt", System.currentTimeMillis());
            o.put("marks", keep);
        } catch (Exception ignored) {
        }
        write(c, P_MARK + bookId + ".json", o.toString());
        dropBookCache(bookId);
    }

    /**
     * 这一轮该刷新哪几本书的划线（v0.5.3，R03）—— 最多 limit 本，**按索引顺序**
     * （= 最近 6 本在记的书 + 其余按划线数降序，见 {@link #compactIndex}）。
     *
     * 两趟，与 {@link #unsyncedIdeas} 同一套结构：
     *   ① **从未同步过**的 —— 先把池子的量堆起来；
     *   ② **已同步但超过 {@link #MARK_TTL_MS} 没更新**的 —— 让新增/删除的划线反映出来。
     * 旧版只有第一趟，所以"有过缓存的书就再也不刷新"。
     */
    public static List<String> refreshQueue(Context c, int limit) {
        List<String> out = new ArrayList<String>();
        JSONArray idx = index(c);
        if (idx == null) return out;
        for (int i = 0; i < idx.length() && out.size() < limit; i++) {
            String id = markCandidate(idx.optJSONObject(i));
            if (id != null && !synced(c, id)) out.add(id);
        }
        for (int i = 0; i < idx.length() && out.size() < limit; i++) {
            String id = markCandidate(idx.optJSONObject(i));
            if (id != null && !out.contains(id) && !markFresh(c, id)) out.add(id);
        }
        return out;
    }

    /** 这本书有没有可能带划线？返回 bookId，否则 null */
    private static String markCandidate(JSONObject b) {
        if (b == null) return null;
        String id = b.optString("bookId", "");
        if (id.length() == 0) return null;
        if (b.optInt("noteCount", 0) <= 0) return null;   // 零划线的书不必拉（防每轮重试）
        return id;
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

    /**
     * ⚠️ v0.5.2 的 `unsynced()` 已被 {@link #refreshQueue} 取代（v0.5.3，R03）——
     * 旧版只挑"从没同步过"的书，导致有过缓存的书永远不更新。
     */

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

    /** 这本书的想法是什么时候拉回来的（毫秒）；没拉过 / 老数据没时间戳 → 0（与 {@link #markFetchedAt} 同款） */
    public static long ideaFetchedAt(Context c, String bookId) {
        if (bookId == null || bookId.length() == 0) return 0L;
        String s = read(c, P_IDEA + bookId + ".json");
        if (s == null) return 0L;
        try {
            return new JSONObject(s).optLong("fetchedAt", 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 这本书的「本记」内容最近一次被同步回来的时刻（毫秒）—— 供卡片「更新于 HH:MM」用。
     *
     * 为什么要单独一个方法：本记一条内容是**划线 + 想法**两路缓存合成出来的
     * （见 {@link #poolOfBook}），两个文件各存各的 `fetchedAt`。用户在卡片上看到的那条
     * 到底来自哪一路、两路谁更新，调用方并不关心 —— 这里取**两者中较新者**
     * （= 这份内容最近一次被更新到本地的时刻），语义直观。
     *
     * 🔴 v0.8.1：在此之前，「更新于」用的是 `WeekCardView.setNote()` 里的
     * `System.currentTimeMillis()` —— 那是**渲染当下**，每次重绘都变（表现为"时钟"，
     * 且离线也一直在变）。改为读这个**真实落盘时刻**后，只有真正同步回新数据才会前进。
     *
     * ⚠️ 本方法会读盘（`markFetchedAt` / `ideaFetchedAt` 各一次）。调用方
     * （`WeekCardView.setNote`）在**主线程**，若不缓存则每次换一条 / 章节回填都会多读两次文件。
     * 故这里加一层按书 LRU 记忆，写入侧（{@link #dropBookCache} / {@link #dropAllCache}）失效 ——
     * 与 `sBooks` 同款口径，**不改变"文件是唯一真相"的前提**。
     *
     * @return 毫秒时间戳；两路都没有时间戳（老数据 / 没同步过）→ 0（调用方据此不画「更新于」）
     */
    // 🔴 A2：`sNoteAt` 是 accessOrder 的 LinkedHashMap（LRU）。它的 get/put 原先**没有加锁**，
    //    而 dropBookCache / dropAllCache / dropIndexCache（均为 `static synchronized`，类锁）
    //    会 remove/clear 同一个表 ⇒ 并发下可能结构损坏（遍历死循环 / 丢条目）。
    //    本方法改为 `static synchronized`，与那几个方法**同一把锁**（对齐同类 itemsOfBook）。
    public static synchronized long noteFetchedAt(Context c, String bookId) {
        if (bookId == null || bookId.length() == 0) return 0L;
        Long hit = sNoteAt.get(bookId);
        if (hit != null) return hit;
        long v = Math.max(markFetchedAt(c, bookId), ideaFetchedAt(c, bookId));
        sNoteAt.put(bookId, v);
        return v;
    }

    /** 「更新于」时间戳的按书记忆（24 本 LRU，够覆盖"最近在记的几本"）；见 {@link #noteFetchedAt} */
    private static final LinkedHashMap<String, Long> sNoteAt =
            new LinkedHashMap<String, Long>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> e) {
                    return size() > 24;
                }
            };

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
        ensureTotals(idx);
        return ideasSlot ? sTotIdeas : sTotMarks;
    }

    /**
     * 一次遍历把四个全库汇总算齐（条数两档 + 字数两档，TASK-056）。
     * 同一份索引数组只算一次（`sTotKey` 身份判等，索引换了/字数回填后会被置空重算）。
     */
    private static void ensureTotals(JSONArray idx) {
        if (sTotKey == idx) return;
        int m = 0, iv = 0, cc = 0, ic = 0;
        for (int i = 0; i < idx.length(); i++) {
            JSONObject b = idx.optJSONObject(i);
            if (b == null) continue;
            m += b.optInt("noteCount", 0);
            iv += b.optInt("reviewCount", 0);
            cc += b.optInt("charCount", 0);
            ic += b.optInt("ideaChars", 0);
        }
        sTotKey = idx;
        sTotMarks = m;
        sTotIdeas = iv;
        sTotChars = cc;
        sTotIdeaChars = ic;
    }

    /**
     * 全库**笔记字数**（TASK-056，K10b）：把索引里每本书的 `charCount` 求和。
     *
     * 口径 = **划线原文 + 想法正文**（见 {@link #poolOfBook} 的累加点）。
     * 单位是 Java `String.length()`（UTF-16 code unit）—— 🔴 与「汉字个数」的直觉口径
     * 在**非 BMP 字符**（emoji、部分生僻字）上会有差异，留档见验证记录 T056 的 A5。
     *
     * **零文件读**：只读索引里的整数，跟 {@link #total} 一样便宜。
     * ⚠️ 只有**被读过正文**的书才有字数（老索引/没预热到的书是 0）—— 随预热逐步补齐。
     */
    public static int totalCharCount(Context c) {
        JSONArray idx = index(c);
        if (idx == null) return 0;
        ensureTotals(idx);
        return sTotChars;
    }

    /**
     * 全库**想法正文**字数（TASK-056）：`charCount` 的子集 —— 只算**用户自己写的那段**。
     *
     * 供 K10（`TASK-055`）「思考沉淀型（≥8000 字）」判定。之所以单列一档：划线原文是
     * **作者的话**，而「思考沉淀」指的是**用户自己的思考**；8000 字若按"原文+想法"算，
     * 多数人几本书就过线、失去区分度。K10 落码时二选一，本卡把两档都备好 ⇒ 免二次改本文件。
     */
    public static int totalIdeaChars(Context c) {
        JSONArray idx = index(c);
        if (idx == null) return 0;
        ensureTotals(idx);
        return sTotIdeaChars;
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

        // ── 字数累加器（TASK-056，K10b）── 正文接下来本来就要逐条读过，顺手 length() 不花额外成本
        int markChars = 0;   // 划线原文
        int ideaChars = 0;   // 想法正文（用户自撰）

        JSONArray ms = marks(c, id);
        if (ms != null) {
            for (int j = 0; j < ms.length(); j++) {
                JSONObject m = ms.optJSONObject(j);
                if (m == null) continue;
                String text = m.optString("markText", "").trim();
                if (text.length() == 0) continue;
                markChars += text.length();                 // TASK-056：划线原文字数
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
                ideaChars += content.length();              // TASK-056：想法正文字数（原文不重复计）
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

        // ── 回填字数到索引（TASK-056，K10b）──
        // 只有**真的读到了文件**才回填（否则 0 会被误当"这本书没字"而覆盖掉旧值）。
        // setCharCount 内部"值没变就不写"，所以同一本书重复读取不会反复写索引。
        if (ms != null || is != null) {
            setCharCount(c, id, markChars + ideaChars, ideaChars);
        }
        return out;
    }

    /**
     * 把一本书的字数写回索引并落盘（TASK-056，K10b）。
     *
     * ⚠️ **不用 {@link #saveIndex}**：那个会把索引缓存、总数缓存、按书缓存全清掉
     * （{@link #dropIndexCache}）—— 而我们此刻正在 {@link #poolOfBook} 里，
     * 清掉缓存等于把刚解析出来的这本书又扔了。这里用 {@link #writeIndexKeepCache}：
     * 落盘后把**文件指纹对齐**，索引缓存原地保持有效。
     *
     * @param chars     笔记字数 = 划线原文 + 想法正文
     * @param ideaChars 其中的想法正文字数
     */
    private static synchronized void setCharCount(Context c, String bookId, int chars, int ideaChars) {
        if (bookId == null || bookId.length() == 0) return;
        JSONArray idx = index(c);
        if (idx == null) return;
        for (int i = 0; i < idx.length(); i++) {
            JSONObject b = idx.optJSONObject(i);
            if (b == null || !bookId.equals(b.optString("bookId", ""))) continue;
            if (b.optInt("charCount", -1) == chars && b.optInt("ideaChars", -1) == ideaChars) return;
            try {
                b.put("charCount", chars);
                b.put("ideaChars", ideaChars);
            } catch (Exception e) {
                return;
            }
            writeIndexKeepCache(c, idx);
            sTotKey = null;          // 汇总变了，下次 total/字数重算（一笔遍历，很便宜）
            return;
        }
    }

    /**
     * 覆盖写索引但**保住内存缓存**（TASK-056）：落盘后把指纹对齐到新文件，避免下次误判"换了"而重读重解析。
     *
     * 🔴 `savedAt` **沿用原值**（{@link #sIdxSavedAt}）—— 这里只是回填字数，不是索引刷新；
     * 若改成 `now`，{@link #indexFresh} 会一直为真、索引再也不刷新。
     */
    private static void writeIndexKeepCache(Context c, JSONArray idx) {
        JSONObject o = new JSONObject();
        try {
            o.put("savedAt", sIdxSavedAt > 0L ? sIdxSavedAt : System.currentTimeMillis());
            o.put("v", INDEX_VERSION);
            o.put("books", idx);
        } catch (Exception ignored) {
            return;
        }
        write(c, F_INDEX, o.toString());
        String st = stamp(c, F_INDEX);
        sIdxStamp = (st == null) ? "" : st;
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
    private static List<String[]> newBatch(Context c, int slot) {
        long t0 = android.os.SystemClock.uptimeMillis();
        List<String[]> out = new ArrayList<String[]>(BATCH);
        JSONArray idx = index(c);
        if (idx == null) return out;

        // 🆕 TASK-057（K11，Q5 = 乙）：**选书档（slot 2）** —— 直接顺序全量入队，
        // 绕开 candidates()/pickBook() 的加权随机。放在随机路径之前
        // （书本身就是从索引里选的 ⇒ 索引没建好时无书可选，早退无害）。
        if (slot == SLOT_PICK) {
            String pick = pickedBook(c);
            if (pick.length() > 0) return orderedBatch(c, pick);
        }
        boolean ideasSlot = (slot == SLOT_IDEA);

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
        // 🔴 「只看想法」档**绝不跨层回落**（v0.5.3，R08）。
        // v0.5.2 的写法是 `if (cs.isEmpty()) cs = idea ? markC : ideaC;` ——
        // 于是"没有写过想法"或"想法还没预热到本地"的用户切到「想法」后，
        // 抽出来的仍是**纯划线**（hasIdea=false），而"筛选已生效"就成了一个谎话；
        // 更糟的是它还会被写进 K_CUR_K_i，之后 currentIn 一路沿用这条错的。
        // 现在想法层没候选 → 整把为空 → pick 返回 null → 卡片画**明确空态**。
        if (ideasSlot && ideaC.isEmpty()) {
            CardDebug.note(c, "newBatch slot=true items=0 (想法层无候选，严格档不回落)");
            return out;
        }
        for (int i = 0; i < BATCH; i++) {
            boolean idea = wantIdea[i];
            List<Cand> cs = idea ? ideaC : markC;
            // 只有全量档会走到这里：某一层还没预热到 → 退到另一层（混投档的容错，不是筛选）
            if (cs.isEmpty()) cs = idea ? markC : ideaC;
            String[] s = drawOne(c, r, cs, idea, seen);
            if (s == null) continue;
            out.add(s);
            books.add(s[0]);
            if (idea) ideaN++;                               // 层是按 hasIdea() 分的，直接数槽位就准
        }
        // 诊断：这一把抽了多少条、落在几本书上、其中几条是想法（≈1/8）
        CardDebug.note(c, "newBatch slot=" + slot + " items=" + out.size()
                + " books=" + books.size() + " ideas=" + ideaN
                + " candM=" + markC.size() + " candI=" + ideaC.size()
                + " ms=" + (android.os.SystemClock.uptimeMillis() - t0));
        return out;
    }

    /**
     * 🆕 TASK-057（K11，Q5 = 乙）：**选书档**的一把 —— 该书**全部**条目按本地文件**原序**入队。
     *
     * 与随机路径的两个关键差别：
     * · **不设 {@link #BATCH} 上限** —— 卡面 A4 要求"该书划线**全部**可达"，截成 40 条就到不了；
     * · **不走 {@link #candidates} 的"本地没文件就跳过"** —— 选的书若还没预热到本地，
     *   这里返回空表 ⇒ {@link #pick} 返回 null ⇒ 卡片画空态（不崩、不瞎抽别的书）。
     *
     * 空 `bookmarkId` 的条目直接丢弃（{@link #resolveSlot} 按它回查，空 id 永远查不到）。
     */
    private static List<String[]> orderedBatch(Context c, String bookId) {
        List<NoteStats> items = itemsOfBook(c, bookId);
        List<String[]> out = new ArrayList<String[]>(items.size());
        for (int i = 0; i < items.size(); i++) {
            NoteStats it = items.get(i);
            if (it.bookmarkId.length() == 0) continue;
            out.add(new String[]{bookId, it.bookmarkId});
        }
        CardDebug.note(c, "newBatch(pick) book=" + bookId + " pool=" + items.size()
                + " queued=" + out.size());
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
    /** 内存里这一把属于哪个槽位（🆕 TASK-057：由 boolean 改 int，见 SLOT_* 常量） */
    private static int sBatchSlot;
    private static boolean sBatchLoaded;

    /** 当前这一把（内存优先，落盘只是为了"进程重启后还是同一条"） */
    private static synchronized List<String[]> batch(Context c, int slot) {
        if (sBatchLoaded && sBatchSlot == slot && sBatch != null) return sBatch;
        List<String[]> out = new ArrayList<String[]>();
        String s = read(c, batchFile(slot));
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
        sBatchSlot = slot;
        sBatchLoaded = true;
        return out;
    }

    private static synchronized void saveBatch(Context c, int slot, List<String[]> b) {
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
        write(c, batchFile(slot), root.toString());
        sBatch = b;
        sBatchSlot = slot;
        sBatchLoaded = true;
    }

    /** 槽位 → 批次文件名（0 默认 / 1 只看想法 / 2 选书） */
    private static String batchFile(int slot) {
        if (slot == SLOT_IDEA) return F_BATCH_I;
        if (slot == SLOT_PICK) return F_BATCH_P;
        return F_BATCH;
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
     * <p>**桌面卡片专用入口** —— 槽位由 {@link #desktopSlot(Context)} 决定：
     * 选了书 ⇒ 与 App 共用 {@link #SLOT_PICK}（桌面显示这本书）；未选 ⇒ {@link #SLOT_DEFAULT}。
     *
     * <p>🔴 **2026-10-06 修订（TASK-057 R1）**：TASK-057 原设计是「桌面恒 {@code SLOT_DEFAULT}、
     * 与 App 彻底解耦」（旧卡面 A6「桌面逐像素不变」）；用户明确要求「App 选书后桌面也同步换池子」
     * ⇒ 改为**跟随选书档**。桌面与 App 共用同一套 {@code SLOT_PICK} 状态（真同步：翻一条两边一起变）。
     *
     * <p>App 本记页请用 {@link #pick(Context, boolean, boolean)}（会按「只看想法 / 选书」落槽）。
     *
     * @param manual true = 用户点了「换一条」（立刻前进一条）；
     *               false = 常规展示（同一天内返回当天那条，跨天才自动前进）
     */
    public static synchronized NoteStats pick(Context c, boolean manual) {
        // 🔴 桌面卡片入口（{@code CardContentController} 唯一调用点）⇒ 跟随选书（TASK-057 R1）。
        return pick(c, manual, desktopSlot(c));
    }

    /** 同 {@link #pick(Context, boolean)}；{@code ideasSlot} 见 {@link #k} */
    public static synchronized NoteStats pick(Context c, boolean manual, boolean ideasSlot) {
        // App 本记页入口：按「只看想法 / 是否有选书」落到具体槽位
        return pick(c, manual, slotOf(c, ideasSlot));
    }

    /**
     * 按槽位抽一条（0 默认 / 1 只看想法 / 2 选书）。
     *
     * @param manual true = 用户点了「换一条」（立刻前进一条）；
     *               false = 常规展示（同一天内返回当天那条，跨天才自动前进）
     */
    private static synchronized NoteStats pick(Context c, boolean manual, int slot) {
        boolean ideasSlot = (slot == SLOT_IDEA);
        long t0 = android.os.SystemClock.uptimeMillis();     // 取一帧内容的耗时（写入诊断日志）
        SharedPreferences p = sp(c);
        // 每日一签：同一天不主动换
        if (!manual && dayKey().equals(p.getString(k(K_DAY, slot), ""))) {
            NoteStats cur = currentIn(c, slot);
            // 严格档要**复核今天这条是否真的带想法**（v0.5.3，R08）：
            // v0.5.2 的跨层回落可能把一条纯划线写成"今天这条"，此后每天都会沿用下去
            if (cur != null && (!ideasSlot || cur.hasIdea())) return cur;
        }

        List<String[]> b = batch(c, slot);
        int pos = p.getInt(k(K_BATCH_POS, slot), -1) + 1;
        NoteStats n = resolve(c, b, pos);
        // 档位不符（升级前落下的混合批次）也当成"没抽到" → 重建这一把
        if (pos < 0 || pos >= b.size() || n == null || (ideasSlot && !n.hasIdea())) {
            // 这一把翻完了（或里面的书被清了）→ 抽新的一把
            b = newBatch(c, slot);
            saveBatch(c, slot, b);
            pos = 0;
            n = resolve(c, b, pos);
        }
        if (n == null) return null;

        int m = poolSizeFor(c, slot);            // 🆕 TASK-057：选书档 ⇒ 这本书的条目数
        int taken = p.getInt(k(K_TAKEN, slot), 0) + 1;
        if (m > 0 && taken > m) taken = 1;         // 全库看完一轮 → 序号回到 1
        p.edit()
                .putInt(k(K_BATCH_POS, slot), pos)
                .putString(k(K_DAY, slot), dayKey())
                .putInt(k(K_TAKEN, slot), taken)
                .putString(k(K_CUR_B, slot), n.bookId)
                .putString(k(K_CUR_K, slot), n.bookmarkId)
                .putString(k(K_HIST, slot),
                        pushHist(p.getString(k(K_HIST, slot), ""), n.bookId, n.bookmarkId))
                .apply();                          // apply：主线程不落 fsync（commit 会卡一帧）
        // 诊断：一次取用的耗时与坐标。v0.4.4 曾经是 700ms/次（全量建池 + 缓存从未命中），
        // 这条日志让"又卡起来了"能一眼看出来（本机 logcat 吞第三方日志，只走文件通道）
        CardDebug.note(c, "pick manual=" + manual + " slot=" + slot
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
        // 🔴 桌面卡片入口 ⇒ 跟随选书（TASK-057 R1）；与 {@link #pick(Context, boolean)} 同一口径
        return pickPrev(c, desktopSlot(c));
    }

    /** 同 {@link #pickPrev(Context)}；App 本记页入口（按「只看想法 / 选书」落槽） */
    public static synchronized NoteStats pickPrev(Context c, boolean ideasSlot) {
        return pickPrev(c, slotOf(c, ideasSlot));
    }

    /** 按槽位取上一条（0 默认 / 1 只看想法 / 2 选书） */
    private static synchronized NoteStats pickPrev(Context c, int slot) {
        boolean ideasSlot = (slot == SLOT_IDEA);        // 🆕 TASK-057：槽位→严格档（历史里跳过纯划线）
        SharedPreferences p = sp(c);
        JSONArray hist = histOf(p.getString(k(K_HIST, slot), ""));
        String curB = p.getString(k(K_CUR_B, slot), "");
        String curK = p.getString(k(K_CUR_K, slot), "");

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
                NoteStats cand = resolveSlot(c, o.optString("b", ""), o.optString("k", ""));
                if (cand == null) continue;                        // 这条读不出来了 → 再往前找
                if (ideasSlot && !cand.hasIdea()) continue;        // 严格档：跳过历史里的纯划线（R08）
                n = cand;
                continue;
            }
            rest.put(o);
        }

        SharedPreferences.Editor ed = p.edit();
        if (n == null) {
            List<String[]> b = batch(c, slot);
            if (b.isEmpty()) return null;
            int pos = p.getInt(k(K_BATCH_POS, slot), 0) - 1;
            if (pos < 0) pos = b.size() - 1;
            n = resolve(c, b, pos);
            if (n == null || (ideasSlot && !n.hasIdea())) return null;
            ed.putInt(k(K_BATCH_POS, slot), pos);
        }

        int taken = p.getInt(k(K_TAKEN, slot), 1) - 1;
        if (taken < 1) taken = 1;
        ed.putInt(k(K_TAKEN, slot), taken)
                .putString(k(K_CUR_B, slot), n.bookId)
                .putString(k(K_CUR_K, slot), n.bookmarkId)
                .putString(k(K_HIST, slot), rest.toString())
                .putString(k(K_DAY, slot), dayKey())
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
        // 🔴 桌面卡片入口 ⇒ 跟随选书（TASK-057 R1）：选了书则进度行显示「这本书」的条数
        return progress(c, desktopSlot(c));
    }

    /**
     * 🆕 TASK-057：**按槽位**取进度（0 默认 / 1 只看想法 / 2 选书）。
     *
     * 🔴 渲染层（{@code CardRenderer}）必须走这个 int 版：桌面卡片与 App 都会调它，
     * 传 {@code host.noteSlot}（桌面 = {@code NoteStore.desktopSlot(c)}、App 由 {@code MainActivity} 注入 {@code slotFor}）。
     */
    public static int[] progress(Context c, int slot) {
        int m = poolSizeFor(c, slot);            // 🆕 TASK-057：选书档 ⇒ 这本书的条目数
        if (m <= 0) {
            // 索引还没建好（首次装好还没同步）→ 退到"这一把的长度"，至少不是空白
            m = batch(c, slot).size();
            if (m <= 0) return null;
        }
        int n = sp(c).getInt(k(K_TAKEN, slot), 0);
        if (n < 1) n = 1;
        if (n > m) n = m;
        return new int[]{n, m};
    }

    /** 当前展示的那条（不做任何前进），用于刷新后重绘。桌面卡片入口 ⇒ 跟随选书（TASK-057 R1） */
    public static synchronized NoteStats current(Context c) {
        return current(c, desktopSlot(c));
    }

    /** 按槽位取当前条（0 默认 / 1 只看想法 / 2 选书）；App 侧请传 {@code NoteStore.slotFor(c)} */
    public static synchronized NoteStats current(Context c, int slot) {
        NoteStats n = currentIn(c, slot);
        return n != null ? n : pick(c, false, slot);
    }

    /** 从 (K_CUR_B, K_CUR_K) 还原当前条，不做前进 */
    private static NoteStats currentIn(Context c, int slot) {
        SharedPreferences p = sp(c);
        return resolveSlot(c, p.getString(k(K_CUR_B, slot), ""),
                p.getString(k(K_CUR_K, slot), ""));
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

    /** 是否「只看想法」（App 本记页的切换，v0.4.4）。🔴 桌面卡片**不跟随**（见 {@link #desktopSlot}） */
    public static boolean ideasOnly(Context c) {
        return sp(c).getBoolean(K_IDEAS_ONLY, false);
    }

    /** 切「只看想法」—— 内容都在本地，切一下只是换一套抽取状态，不用重读任何文件 */
    public static void setIdeasOnly(Context c, boolean v) {
        // 🆕 TASK-057（K11，Q6 = 乙）：与「选书」互斥 —— 切到「想法」档即退出选书。
        // 反向由 setPickedBook 负责（选书 ⇒ 强制回「全部」档）。语义 = **后动者胜**，
        // 两边都不留"点了没反应"的死键。
        if (v) sp(c).edit().putString(K_PICK_BOOK, "").apply();
        sp(c).edit().putBoolean(K_IDEAS_ONLY, v).apply();
    }

    // ══════════════════ 🆕 TASK-057（K11）：选书档 ══════════════════

    /** 当前选中的书（`""` = 未选 ⇒ 走原随机池）。🔴 桌面卡片 R1 起**也读**该键（经 {@link #desktopSlot}）。 */
    public static String pickedBook(Context c) {
        String v = sp(c).getString(K_PICK_BOOK, "");
        return v == null ? "" : v;
    }

    /**
     * 选中一本书（`bookId` 空串 = 恢复「全部书籍」）。
     *
     * 🔴 四件事一次做掉（缺一条就会"选了没用"）：
     *   ① 写 `K_PICK_BOOK`，并把「只看想法」强制关掉（Q6 互斥 ⇒ 选书只作用于「全部」档）；
     *   ② **作废选书档这一把**（内存 + `nt_batch_p.json`）—— 否则沿用旧批次，选书不生效；
     *   ③ **选书档的抽签状态归零**（`day` / `batch_pos` / `taken` / 当前条 / 历史）——
     *      `day` 不清的话 {@link #pick} 会走"每日一签"短路，把旧的条原样返回；
     *   ④ 🔴 **一个字节都不碰默认档**（`SLOT_DEFAULT`）—— 那是"未选书"时的池子。
     *      但 **TASK-057 R1 起桌面会跟随选书档**（{@link #desktopSlot}）⇒ 选书会**同时**
     *      改变桌面「今日一签」（这是用户 2026-10-06 明确要求的"同步换池子"，非回归）。
     */
    public static synchronized void setPickedBook(Context c, String bookId) {
        String v = (bookId == null) ? "" : bookId;
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit().putString(K_PICK_BOOK, v);
        if (v.length() > 0) e.putBoolean(K_IDEAS_ONLY, false);
        e.apply();

        // ② 作废选书档这一把（走 saveBatch：同时换掉内存缓存 + 覆写批次文件，重启也不会读到旧的）
        saveBatch(c, SLOT_PICK, new ArrayList<String[]>());

        // ③ 选书档抽签状态归零 —— 顺序遍历从这本书的第 1 条开始
        p.edit()
                .putInt(k(K_BATCH_POS, SLOT_PICK), -1)
                .putInt(k(K_TAKEN, SLOT_PICK), 0)
                .remove(k(K_DAY, SLOT_PICK))
                .putString(k(K_CUR_B, SLOT_PICK), "")
                .putString(k(K_CUR_K, SLOT_PICK), "")
                .putString(k(K_HIST, SLOT_PICK), "")
                .apply();
        CardDebug.note(c, "pickedBook = " + (v.length() == 0 ? "(全部书籍)" : v)
                + " （只动 slot=2；R1 起桌面跟随选书档）");
    }

    /** 可选书籍条目（书名 / 条数 / 本地是否已就绪）。 */
    public static final class BookRef {
        public String id;
        public String title;
        /** 条数 = 划线 + 想法（取索引里的 `noteCount + reviewCount`） */
        public int count;
        /** 本地是否已有内容文件（**决定这本书选了能不能真读出东西**） */
        public boolean ready;
    }

    /**
     * 🆕 TASK-057：选书列表的料 —— **索引原序**（= 预热序，最近有笔记的在前），零新请求。
     *
     * `ready` = 本地已有 `nt_<id>.json` / `ni_<id>.json`（判据与 {@link #candidates} 一致）。
     * 🔴 之所以把 `ready` 一并带出而不在列表里过滤掉：索引 200+ 本、本地预热到的只是子集，
     * 若只列已就绪的，用户会以为"我的书少了"；标出来让他自己选更诚实。
     */
    public static List<BookRef> bookList(Context c) {
        List<BookRef> out = new ArrayList<BookRef>();
        JSONArray idx = index(c);
        if (idx == null) return out;
        for (int i = 0; i < idx.length(); i++) {
            JSONObject b = idx.optJSONObject(i);
            if (b == null) continue;
            String id = b.optString("bookId", "");
            if (id.length() == 0) continue;
            BookRef r = new BookRef();
            r.id = id;
            String t = b.optString("title", "");
            r.title = (t.length() == 0) ? "（未知书名）" : t;
            r.count = b.optInt("noteCount", 0) + b.optInt("reviewCount", 0);
            r.ready = hasLocal(c, id);
            out.add(r);
        }
        return out;
    }

    /** 本地是否已有这本书的内容文件（两个层任一有非空文件即可） */
    private static boolean hasLocal(Context c, String id) {
        File a = f(c, P_MARK + id + ".json");
        if (a.exists() && a.length() > 0) return true;
        File b = f(c, P_IDEA + id + ".json");
        return b.exists() && b.length() > 0;
    }

    /**
     * 当前「池子」规模 —— 供进度行「第 N / 共 M 条」。
     *
     * 常规 = 全库总数（{@link #total}）；**选书档（slot 2）= 这本书的条目数**，
     * 否则进度会显示成"第 1 / 共 5483 条"（全库），与眼前这本书对不上。
     */
    private static int poolSizeFor(Context c, int slot) {
        if (slot == SLOT_PICK) {
            String pick = pickedBook(c);
            if (pick.length() > 0) {
                int sz = itemsOfBook(c, pick).size();
                if (sz > 0) return sz;
            }
        }
        return total(c, slot == SLOT_IDEA);
    }

    /**
     * 抽取状态的键名 —— 🆕 TASK-057 由布尔改**三槽位**（0 默认 / 1 只看想法 / 2 选书）。
     *
     * 与旧 `k(base, boolean)` 逐位兼容：旧 `true`（只看想法）= 新 {@link #SLOT_IDEA}（后缀 `_i`），
     * 旧 `false` = 新 {@link #SLOT_DEFAULT}（无后缀）⇒ **老用户的历史状态不会失配**。
     */
    private static String k(String base, int slot) {
        if (slot == SLOT_IDEA) return base + "_i";
        if (slot == SLOT_PICK) return base + "_p";
        return base;
    }

    /**
     * 当前该用哪个槽位（**App 本记页专用**）：
     * 只看想法 ⇒ {@link #SLOT_IDEA}；否则选了书 ⇒ {@link #SLOT_PICK}；否则 {@link #SLOT_DEFAULT}。
     *
     * 🔴 桌面卡片走 {@link #desktopSlot(Context)}（只跟随「选书」、**不看**「只看想法」）——
     * TASK-057 R1 起桌面与 App 在「选书」维度上**共用** {@code SLOT_PICK}。
     */
    private static int slotOf(Context c, boolean ideasSlot) {
        if (ideasSlot) return SLOT_IDEA;
        return pickedBook(c).length() > 0 ? SLOT_PICK : SLOT_DEFAULT;
    }

    /**
     * 🆕 TASK-057：**App 本记页**该用的槽位（公开给渲染层）。
     *
     * `MainActivity` 在设内容前把它喂给 `WeekCardView.setNoteSlot(int)`，
     * 渲染层的进度行（{@code NoteStore.progress(ctx, host.noteSlot)}）与卡片行为都读它。
     */
    public static int slotFor(Context c) {
        return slotOf(c, ideasOnly(c));
    }

    /**
     * 🆕 TASK-057 R1（2026-10-06）：**桌面卡片**该用的槽位 = 跟随「选书」。
     *
     * 选了书 ⇒ {@link #SLOT_PICK}（桌面与 App **共用同一套抽取状态** ⇒ 桌面显示这本书的划线，
     * 且 App 翻一条两边一起变）；未选 ⇒ {@link #SLOT_DEFAULT}（原随机池）。
     *
     * 🔴 **不含**「只看想法」维度 —— 那是 App 本记页的浏览开关，桌面不跟随。
     * 桌面四个入口（{@code pick / pickPrev / progress / current} 的单参重载）都走这里；
     * ⚠️ 旧 TASK-057 A6「桌面与 App 解耦、桌面逐像素不变」已被本修订**推翻**。
     */
    public static int desktopSlot(Context c) {
        return pickedBook(c).length() > 0 ? SLOT_PICK : SLOT_DEFAULT;
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

    /**
     * 写文件 —— **原子替换**（v0.5.3）。
     *
     * 上一版是 `new FileOutputStream(f, false)` 直接截断写：写到一半被打断（进程被回收、
     * 电量骤降）就会留下半截 JSON，下一次读解析失败 → 被当成"没有缓存" → 重新拉；
     * 如果此刻网络还不通，本记页就会掉进"空态"（而空态又可能触发同步）。
     * 改成"先写同目录临时文件 → rename"：同一分区上的 rename 是原子的，
     * 读者要么看到旧文件、要么看到完整的新文件，绝不会看到半截。
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
            out.getFD().sync();        // 先落盘，再改名
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
        tmp.delete();                  // 写坏 / 改名失败的临时文件绝不能顶上去
        // 兜底：临时文件方案不可用（极少数文件系统）→ 直接写，至少别丢这次更新
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
