package com.inkread.weekread;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;

/**
 * 「本记」的同步编排（v0.4.0，M1）。
 *
 * 三步走，**都能被打断、都能断点续传**：
 *   ① 索引：`/user/notebooks` **按 `hasMore` + `lastSort` 翻完所有页**（v0.5.3，R07 ——
 *      旧版只取第一页，>300 本有笔记的书会永久缺席），压成精简索引落盘
 *      （{@link NoteStore#compactIndex}）。6 小时内只拉一次；中途失败保留旧索引。
 *   ② 预热想法（v0.4.4）：按索引顺序取还没拉过想法的书，每次最多
 *      {@link #IDEA_PREFETCH} 本，逐本 `/review/list/mine` → 整体替换落盘。
 *   ③ 预热 / 刷新划线（v0.5.3，R03）：按索引顺序取「从没同步过」+「超过
 *      {@link NoteStore#MARK_TTL_MS} 没更新」的书，每次最多 {@code prefetch} 本，
 *      逐本 `/book/bookmarklist` → **全量替换**落盘（旧版只挑"从没同步过"的，
 *      于是有过缓存的书永远不更新）。
 *
 * 为什么"每次最多 N 本"而不是一次全拉：全库 5500+ 条 ≈ 2MB、200+ 次请求，
 * 一口气跑完要几分钟，而卡片/页面此刻正等着出内容。
 * 渐进式预热让**第一次打开就能抽**（池子可能只有几百条，但足够用），
 * 之后每次打开再补一批，几轮下来自动满库。
 *
 * 索引顺序（= 预热顺序）由 {@link NoteStore#compactIndex} 定：**最近 6 本金优先**，
 * 其余按划线数降序 —— 今天刚划的线当天就能抽到，这是 v0.4.4 修的（方案 E-2）。
 */
public final class NoteSync {

    private NoteSync() {
    }

    /**
     * 一轮同步的结束回调（v0.5.3 起带**状态**，见 {@link #STATE_OK} 等）。
     *
     * ⚠️ 回调实现里**不要再无条件发起同步** —— 那正是 R02 那个无界环的成因。
     * 现在的约定是：拿到 {@link #STATE_OK} 才展示内容；空 / 失败**停在空态**，
     * 由 {@link #BACKOFF_MS} 挡掉自动重试，等用户手动刷新（{@code force=true}）或退避过期。
     */
    public interface Listener {
        /**
         * @param pool  本地已就绪的**划线**条数
         * @param ideas 本地已就绪的**想法**条数（v0.5.3 新加：只看想法档下划线数可能为 0，
         *              只报划线条数会把"划线空、想法有货"误判成空态）
         * @param total 全库总数（索引统计）
         * @param state 见 {@link #STATE_OK} / {@link #STATE_EMPTY} / {@link #STATE_ERROR} / {@link #STATE_BUSY}
         */
        void onDone(int pool, int ideas, int total, String error, int state);
    }

    /** 同步拿到了内容（两个档位至少一个有货）→ 可以放心展示 */
    public static final int STATE_OK = 0;
    /** 一轮跑完、没报错，但两个档位都还是空的（新账号 / 确实没有笔记） */
    public static final int STATE_EMPTY = 1;
    /** 这一轮出了错（离线 / 401 / 网关异常） */
    public static final int STATE_ERROR = 2;
    /** 已经有一轮在跑，这次请求被 in-flight 去重挡掉了（**没有**新起线程、**没有**发请求） */
    public static final int STATE_BUSY = 3;

    /** 一轮最多预热多少本书的**划线**（v0.4.4：12 → 18，见方案 E-2） */
    public static final int DEFAULT_PREFETCH = 18;

    /**
     * 一轮最多预热多少本书的**想法**（v0.4.4）。
     *
     * 全库只有 55 本有想法（189 条），单本 1-3 条 / 通常 1 页 —— 一次请求就够，
     * 成本远低于划线预热（划线一本几十条、还有 synckey 增量）。
     * 取 24：**两轮**（手动刷新一轮 48 本）就能把「只看想法」这个池子填满，
     * 否则按 12/轮 要 5 轮 —— 一天开一次 App 的话要 5 天才见效，旗舰功能不该这么慢。
     */
    public static final int IDEA_PREFETCH = 24;

    // ══════════════════════ 同步的三道闸门（v0.5.3，R02）══════════════════════
    //
    // 背景（`验证记录/34` §3.1）：v0.5.2 及以前，本记页在"笔记为空"时会自己把同步
    // 打成无界循环 —— `showNoteItem(null)` → 起同步 → `onDone` 里 `showNote(false)`
    // → 池子还是空 → 又起同步 …… 一轮最多 43 次请求（索引 1 + 想法预热 24 + 划线预热 18），
    // 每轮还在主线程读文件、新起线程，用的是**用户自己的 API Key**（有被网关限流的风控风险）。
    //
    // 现在三道闸门叠起来，缺一不可：
    //   ① **渲染函数不再发请求**（`MainActivity.showNoteItem` / `CardA11yService.showNoteItem`）——
    //      同步只由明确的入口发起：进本记页 / 切档 / 手动刷新 / 渲染落空时的那一次补齐；
    //   ② **in-flight 去重**（{@link #isRunning}）：同一时刻只有一轮，重复请求回 {@link #STATE_BUSY}；
    //   ③ **空 / 失败退避**（{@link #canAutoStart}）：{@link #BACKOFF_MS} 内不再自动发起，
    //      手动刷新（{@code force=true}）调 {@link #clearBackoff} 立即放行。

    private static final Object LOCK = new Object();
    /** 是否有一轮同步正在跑（**静态**：App 与本进程内的无障碍服务共用同一把闸门） */
    private static boolean sRunning;
    /** 空 / 失败后的退避截止（elapsedRealtime；0 = 可以自动发起） */
    private static long sBackoffUntil;

    /** 空 / 失败后**自动**重试的冷却时间；手动刷新不受它限制 */
    public static final long BACKOFF_MS = 5L * 60L * 1000L;

    /** 现在有没有一轮在跑（渲染函数靠它决定"要不要等"而不是"再发一次"） */
    public static boolean isRunning() {
        synchronized (LOCK) {
            return sRunning;
        }
    }

    /** 现在允许**自动**发起同步吗（退避到期 / 没失败过） */
    public static boolean canAutoStart() {
        return android.os.SystemClock.elapsedRealtime() >= sBackoffUntil;
    }

    /** 用户手动刷新：清掉退避，立即放行（见 {@link #BACKOFF_MS}） */
    public static void clearBackoff() {
        sBackoffUntil = 0L;
    }

    /**
     * @param force    true = 用户手动刷新（索引也要重拉，最小间隔 {@link NoteStore#INDEX_MIN_MS}）
     * @param prefetch 本轮最多同步几本书的划线；0 表示只建索引、不预热
     */
    public static void sync(final Context c, final String apiKey, final boolean force,
                            final int prefetch, final Listener l) {
        // 闸门②：in-flight 去重 —— 已经有一轮在跑就**不排队、不重叠**，
        // 直接回一个 STATE_BUSY（调用方原地保持"同步中"，别叠第二次）
        synchronized (LOCK) {
            if (sRunning) {
                post(l, 0, 0, 0, null, STATE_BUSY);
                return;
            }
            sRunning = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;

                // ① 索引（v0.5.3：含翻页，见 fetchAllNotebooks）
                boolean needIndex = !NoteStore.indexFresh(c)
                        || (force && NoteStore.indexAge(c) > NoteStore.INDEX_MIN_MS);
                if (needIndex) {
                    JSONArray all = fetchAllNotebooks(apiKey);
                    if (all != null && all.length() > 0) {
                        NoteStore.saveIndex(c, NoteStore.compactIndex(all));
                    } else if (NoteStore.index(c) == null) {
                        err = (all == null) ? "笔记本列表拉取失败" : "笔记本列表为空";
                    }
                    // 有旧索引就用旧的，离线不至于一片空白
                }

                // ② 预热想法（v0.4.4）—— 排在划线前面：只有 55 本，一轮就能把
                //    "最近在记的书"的想法全部拿到，比划线更快见效
                if (err == null && prefetch > 0) {
                    int want = force ? IDEA_PREFETCH * 2 : IDEA_PREFETCH;
                    List<String> iids = NoteStore.unsyncedIdeas(c, want);
                    for (String id : iids) {
                        syncIdeas(c, apiKey, id);
                    }
                }

                // ③ 预热 / 刷新划线（v0.5.3：不再只挑"从没同步过"的书，见 NoteStore.refreshQueue）
                if (err == null && prefetch > 0) {
                    List<String> ids = NoteStore.refreshQueue(c, prefetch);
                    for (String id : ids) {
                        JSONObject arg = new JSONObject();
                        try {
                            arg.put("bookId", id);
                        } catch (Exception ignored) {
                        }
                        WereadApi.Resp r = WereadApi.raw(apiKey,
                                WereadApi.buildBody("/book/bookmarklist", arg));
                        if (r.error != null || r.json == null) continue;   // 单本失败不影响别的
                        JSONArray up = r.json.optJSONArray("updated");
                        // **全量替换**（R03）：官方文档里本接口只有 bookId 一个参数，
                        // 回包 updated[] 就是这本书的全部划线 —— 所以新增能进来、删掉的会消失。
                        NoteStore.replaceMarks(c, id, up == null ? new JSONArray() : up,
                                r.json.optLong("synckey", 0L));
                    }
                }

                final int pool = NoteStore.poolSize(c);
                final int ideas = NoteStore.ideaCount(c);
                final int total = NoteStore.indexTotal(c);
                final String fErr = err;
                // 状态判定：出错 = 失败；没错但两档都空 = 空态；否则成功。
                // 空 / 失败都打退避 —— 这是"回调里再渲染又起同步"那个环的第二道闸门（闸门③）。
                final int state = (err != null) ? STATE_ERROR
                        : ((pool + ideas) > 0 ? STATE_OK : STATE_EMPTY);
                if (state != STATE_OK) {
                    sBackoffUntil = android.os.SystemClock.elapsedRealtime() + BACKOFF_MS;
                }
                synchronized (LOCK) {
                    sRunning = false;          // 必须先放开闸门，再回调（回调里可能合法地再发起）
                }
                post(l, pool, ideas, total, fErr, state);
            }
        }).start();
    }

    /** 把结束回调送回主线程（同步本身必须在后台线程跑） */
    private static void post(final Listener l, final int pool, final int ideas,
                             final int total, final String error, final int state) {
        if (l == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                l.onDone(pool, ideas, total, error, state);
            }
        });
    }

    /** 索引分页：每页多少本（实测首页 228 本 / 283KB；300 一页够用，但必须按 hasMore 续拉） */
    private static final int INDEX_PAGE_SIZE = 300;

    /** 索引最多翻几页（每页 300 本 → 上限 3000 本）。防异常回包把一轮同步拖死 */
    private static final int MAX_INDEX_PAGES = 10;

    /**
     * 拉全书笔记本索引（**含翻页**，v0.5.3，R07）。
     *
     * 官方语义（本地文档副本 `references/notes.md` 与技能包一致）：`/user/notebooks` 是
     * **游标分页** —— 只支持 `count` + `lastSort`，**不支持 offset/limit**；`hasMore==1` 时
     * 用本页最后一本书的 `sort` 续拉下一页。
     * 旧版只发一次 `count=300` 就当完整索引保存，于是**超过 300 本有笔记的书会永久缺席**
     * （本机现在 228 本，暂时没撞上 —— 但那是运气，不是设计）。
     *
     * 三重防护：① 按 bookId 去重；② 游标不前进 / 空页就停（防死循环）；③ 页数上限。
     * **中途任何一页失败 → 整体返回 null**：宁可保留旧索引，也不让半截索引覆盖完整的。
     */
    private static JSONArray fetchAllNotebooks(String apiKey) {
        JSONArray out = new JSONArray();
        HashSet<String> seen = new HashSet<String>();
        long lastSort = 0L;
        for (int page = 0; page < MAX_INDEX_PAGES; page++) {
            JSONObject arg = new JSONObject();
            try {
                arg.put("count", INDEX_PAGE_SIZE);
                if (lastSort > 0) arg.put("lastSort", lastSort);   // 游标：上一页最后一本的 sort
            } catch (Exception ignored) {
            }
            WereadApi.Resp r = WereadApi.raw(apiKey, WereadApi.buildBody("/user/notebooks", arg));
            if (r.error != null || r.json == null) return null;
            JSONArray books = r.json.optJSONArray("books");
            int added = 0;
            long nextSort = 0L;
            for (int i = 0; books != null && i < books.length(); i++) {
                JSONObject b = books.optJSONObject(i);
                if (b == null) continue;
                long s = b.optLong("sort", 0L);
                if (s != 0) nextSort = s;
                String id = b.optString("bookId", "");
                if (id.length() == 0 || !seen.add(id)) continue;    // ① 去重
                out.put(b);
                added++;
            }
            if (r.json.optInt("hasMore", 0) != 1) break;            // 没有下一页了
            if (nextSort == 0 || nextSort == lastSort || added == 0) {
                break;                                              // ② 游标不前进 / 空页 → 停
            }
            lastSort = nextSort;
        }
        return out;
    }

    /**
     * 拉一本书的**全部想法 / 点评**（`/review/list/mine`），整体替换落盘。
     *
     * ⚠️ `synckey` 在这个接口里是**翻页游标**，不是 `/book/bookmarklist` 那种"数据版本号"——
     * 拿它当增量键会漏数据。所以做法是：一页页翻到 `hasMore != 1`，然后全量替换
     * （{@link NoteStore#mergeIdeas} 内部按 reviewId 去重）。
     * 单本实测 1-3 条、通常 1 页，这点成本换来的是一份永远自洽的本地副本。
     */
    private static void syncIdeas(Context c, String apiKey, String bookId) {
        JSONArray all = new JSONArray();
        int synckey = 0;
        for (int page = 0; page < 8; page++) {
            JSONObject arg = new JSONObject();
            try {
                arg.put("bookid", bookId);        // ⚠️ 接口参数名是小写 bookid
                arg.put("count", 50);
                arg.put("synckey", synckey);
            } catch (Exception ignored) {
            }
            WereadApi.Resp r = WereadApi.raw(apiKey,
                    WereadApi.buildBody("/review/list/mine", arg));
            if (r.error != null || r.json == null) {
                return;      // 单本失败就放弃这本（不写盘）—— 下轮还会再试，不会污染已有数据
            }
            JSONArray revs = r.json.optJSONArray("reviews");
            if (revs != null) {
                for (int i = 0; i < revs.length(); i++) all.put(revs.opt(i));
            }
            int ns = r.json.optInt("synckey", 0);
            if (r.json.optInt("hasMore", 0) != 1 || ns == 0 || ns == synckey) break;
            synckey = ns;
        }
        NoteStore.mergeIdeas(c, bookId, all);
    }

    /**
     * 章节名异步反查（画到屏幕上之后才补，不阻塞出内容）。
     *
     * 章节目录按书永久缓存（{@link BookStore#saveChapters}），**一本书只拉一次**；
     * 查到之后回写进划线文件，下次直接从池子里带出来。
     */
    public static void resolveChapter(final Context c, final String apiKey,
                                      final NoteStats n, final Runnable onDone) {
        if (n == null || n.chapterTitle.length() > 0) {
            if (onDone != null) onDone.run();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String t = WereadApi.resolveChapter(c, apiKey, n.bookId,
                        n.chapterUid, n.chapterIdx);
                NoteStore.setChapterTitle(c, n.bookId, n.bookmarkId, t);
                n.chapterTitle = t;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (onDone != null) onDone.run();
                    }
                });
            }
        }).start();
    }
}
