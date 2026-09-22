package com.inkread.weekread;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 「本记」的同步编排（v0.4.0，M1）。
 *
 * 三步走，**都能被打断、都能断点续传**：
 *   ① 索引：`/user/notebooks?count=300` 一页拿全（实测 228 本 / 283KB），
 *      压成精简索引落盘（{@link NoteStore#compactIndex}）。6 小时内只拉一次。
 *   ② 预热想法（v0.4.4）：按索引顺序取还没拉过想法的书，每次最多
 *      {@link #IDEA_PREFETCH} 本，逐本 `/review/list/mine` → 整体替换落盘。
 *   ③ 预热划线：同样按索引顺序取还没同步的书，每次最多 {@code prefetch} 本，
 *      逐本 `/book/bookmarklist` → 合并落盘。
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

    public interface Listener {
        /** @param pool 本地已就绪的划线条数；@param total 全库总数（索引统计） */
        void onDone(int pool, int total, String error);
    }

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

    /**
     * @param force    true = 用户手动刷新（索引也要重拉，最小间隔 {@link NoteStore#INDEX_MIN_MS}）
     * @param prefetch 本轮最多同步几本书的划线；0 表示只建索引、不预热
     */
    public static void sync(final Context c, final String apiKey, final boolean force,
                            final int prefetch, final Listener l) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;

                // ① 索引
                boolean needIndex = !NoteStore.indexFresh(c)
                        || (force && NoteStore.indexAge(c) > NoteStore.INDEX_MIN_MS);
                if (needIndex) {
                    JSONObject arg = new JSONObject();
                    try {
                        arg.put("count", 300);
                    } catch (Exception ignored) {
                    }
                    WereadApi.Resp r = WereadApi.raw(apiKey,
                            WereadApi.buildBody("/user/notebooks", arg));
                    if (r.error == null && r.json != null) {
                        JSONArray src = r.json.optJSONArray("books");
                        if (src != null && src.length() > 0) {
                            NoteStore.saveIndex(c, NoteStore.compactIndex(src));
                        }
                    } else if (NoteStore.index(c) == null) {
                        err = (r.error == null) ? "笔记本列表为空" : r.error;
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

                // ③ 预热划线
                if (err == null && prefetch > 0) {
                    List<String> ids = NoteStore.unsynced(c, prefetch);
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
                        if (up == null || up.length() == 0) {
                            NoteStore.mergeMarks(c, id, new JSONArray(), r.json.optLong("synckey", 0L));
                            continue;
                        }
                        NoteStore.mergeMarks(c, id, up, r.json.optLong("synckey", 0L));
                    }
                }

                final int pool = NoteStore.poolSize(c);
                final int ideas = NoteStore.ideaCount(c);
                final int total = NoteStore.indexTotal(c);
                final String fErr = err;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (l != null) l.onDone(pool, total, fErr);
                    }
                });
            }
        }).start();
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
