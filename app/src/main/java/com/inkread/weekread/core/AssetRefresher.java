package com.inkread.weekread.core;

import android.content.Context;

import com.inkread.weekread.net.WereadApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🆕 TASK-086 第六批B（D9 / D10 / D19，2026-10-11）：**阅读资产的拉取器**。
 *
 * <p>职责：把「全量书架 → 逐本累计进度」这条链跑在**后台线程**里，结果落 {@link AssetStore}。
 * 渲染侧（{@code feature.AssetSection}）只读 {@link AssetStore}，**永不阻塞、永不出网**。
 *
 * <h3>链路（与卡面 D9 逐条对应）</h3>
 * <ol>
 *   <li><b>全量书架</b>：`/shelf/sync`（约 462KB，6h 缓存 —— 走 {@link BookStore} 已有的书架缓存，
 *       缓存过期 / 首次才真拉）。</li>
 *   <li><b>预筛</b>：书架里 `readUpdateTime &lt;= 0` 的书 = **从没打开过** ⇒ 连 `/book/getprogress`
 *       都不发（省掉绝大多数请求）。预筛数写进 {@link Result#prefiltered}，报告里要明写。</li>
 *   <li><b>逐本</b>：`/book/getprogress`（约 0.4KB）取 `progress` / `readingTime`（**累计秒，主口径**；
 *       缺失或为 0 时才回落 `recordReadingTime` —— 实测 `recordReadingTime` 恒 0，见 S4 真实回包
 *       `_archive/_probe2/api_progress.json`）/ `finishTime`。</li>
 *   <li><b>增量</b>：已有记录且不超过 {@link #BOOK_TTL_MS} 的书**不发请求**；没记录 /
 *       老过 TTL 的才补。首轮 = 全量，之后 = 日常增量。</li>
 *   <li><b>降级</b>（D10）：单轮最多 {@link #MAX_REQ} 个请求，且**任何一次传输失败就停下**
 *       —— 已取到的先落盘、页面先渲染缓存，下一轮（≥{@link #MIN_GAP_MS} 之后）接着补。
 *       全量跑完才写 {@link AssetStore#setFullAt}（没跑完 ⇒ 下次继续，不会假装"全量完成"）。</li>
 * </ol>
 *
 * <h3>调用契约（D19）</h3>
 * <p>{@link #ensure(Context)} 是**幂等**的、**零 UI**、**可在主线程调用**（内部只做两次
 * 轻量读 + 起一个后台线程）。它自带两层闸门：
 * <ul>
 *   <li><b>TTL</b>：全部记录都新鲜（且全量轮也在 {@link #FULL_TTL_MS} 内）⇒ 直接返回，零请求；</li>
 *   <li><b>最短间隔</b>：上一轮尝试在 {@link #MIN_GAP_MS} 内 ⇒ 不重复起线程（失败也不会打转）。</li>
 * </ul>
 * <p>🔴 与 task-52 / 墨单侧的交叉点：**A 侧在本月卡片刷新路径里调一次本方法**即可实现
 * D19「随本月卡片刷新时机刷新」；本类不引用 `BillScheduler` / `Bill*` 任何东西（避免反向依赖）。
 * 渲染侧另在 {@code AssetSection.refresh} 里调一次（进墨台/退设置态）作为兜底。
 */
public final class AssetRefresher {

    /** 单本记录的有效期 —— 老过它就重拉（D9「日常增量」的粒度）。 */
    public static final long BOOK_TTL_MS = 7L * 24L * 3600L * 1000L;

    /** 全量轮的周期 —— 全量跑完后至少要隔这么久才会再全量（避免每次进页都扫书架）。 */
    public static final long FULL_TTL_MS = 24L * 3600L * 1000L;

    /** 相邻两轮之间的最短间隔 —— 失败也记时间，防止"失败 ⇒ 每次进页都重试"。 */
    public static final long MIN_GAP_MS = 60L * 1000L;

    /** 单轮请求上限（超时保护：宁可分几轮补完，也不让后台线程跑成无底洞）。 */
    public static final int MAX_REQ = 400;

    /** 每攒够这么多条就落一次盘（进程被杀 / 请求中途失败时少丢一点）。 */
    private static final int FLUSH_EVERY = 25;

    private static final Object LOCK = new Object();
    private static volatile boolean sRunning;
    private static volatile Result sLast;

    private AssetRefresher() {
    }

    /** 后台是否正在拉（渲染侧可据它决定要不要显示"同步中"）。 */
    public static boolean isRunning() {
        return sRunning;
    }

    /** 上一轮的结果（进程内最近一次；没跑过 ⇒ null）。 */
    public static Result last() {
        return sLast;
    }

    /**
     * 按需触发一次后台刷新（**幂等**）。这是 D19 的唯一入口，主线程可调。
     *
     * <p>满足任一条件才真的起线程：① 一条记录都没有；② 全量轮超过 {@link #FULL_TTL_MS}；
     * ③ 存在老过 {@link #BOOK_TTL_MS} 的记录。否则**零请求**返回。
     */
    public static void ensure(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        if (app == null) return;

        synchronized (LOCK) {
            if (sRunning) return;
            long now = System.currentTimeMillis();
            if (now - AssetStore.runAt(app) < MIN_GAP_MS) return;   // 刚跑过（含刚失败过）

            boolean fullStale = now - AssetStore.fullAt(app) > FULL_TTL_MS;
            boolean anyStale = false;
            List<AssetStore.Book> books = AssetStore.all(app);
            if (books.isEmpty()) {
                fullStale = true;
            } else {
                for (int i = 0; i < books.size(); i++) {
                    AssetStore.Book b = books.get(i);
                    if (b.at <= 0L || now - b.at > BOOK_TTL_MS) {
                        anyStale = true;
                        break;
                    }
                }
            }
            if (!fullStale && !anyStale) return;                    // 🔴 都新鲜 ⇒ 零请求
            sRunning = true;
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                // 🔴 不能写成 `run(app, null)`：匿名 `Runnable` 里 `run` 这个名字被继承来的
                //    无参 `Runnable.run()` 占住，配套方法名改用 `pull`（javac 实证：
                //    "run(java.lang.Object) … 需要: 无参数"）。
                pull(app, null);
            }
        }, "asset-refresh");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** 强制拉一轮（忽略 TTL；仍受 {@link #MAX_REQ} 与失败即停约束）。回调在**后台线程**。 */
    public static void refreshNow(Context c, Listener l) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        if (app == null) return;
        synchronized (LOCK) {
            if (sRunning) return;
            sRunning = true;
        }
        final Listener cb = l;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                pull(app, cb);      // 同上：不能叫 `run`（会被 `Runnable.run()` 遮蔽）
            }
        }, "asset-refresh-now");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ══════════════════════ 后台实现 ══════════════════════

    private static void pull(Context c, Listener l) {
        Result r = new Result();
        try {
            AssetStore.setRunAt(c, System.currentTimeMillis());

            // ⓪ 前置条件自检（零请求）：没配 apiKey 就什么都不做
            String key = StatsStore.getKey(c);
            if (key == null || key.length() == 0) {
                r.error = "no_api_key";
                return;
            }

            long now0 = System.currentTimeMillis();
            boolean full = AssetStore.isEmpty(c) || (now0 - AssetStore.fullAt(c) > FULL_TTL_MS);
            r.full = full;

            // ① 书架（能走缓存就走缓存）
            JSONArray books = BookStore.shelf(c);
            boolean needShelf = full
                    || books == null || books.length() == 0
                    || BookStore.shelfAge(c) > BookStore.SHELF_TTL_MS;
            if (needShelf) {
                WereadApi.Resp resp = WereadApi.raw(key, WereadApi.buildBody("/shelf/sync", null));
                if (resp != null && resp.error == null && resp.json != null) {
                    JSONArray src = resp.json.optJSONArray("books");
                    if (src != null && src.length() > 0) {
                        books = src;                                // 🔴 用**全量**回包做预筛（缓存只留 200 本）
                        BookStore.saveShelf(c, BookStore.compactShelf(src));
                        BookStore.saveShelfIds(c, src);
                    }
                } else if (books == null || books.length() == 0) {
                    r.error = "shelf:" + (resp == null ? "null" : resp.error);
                    return;                                         // 没缓存又拉不到 ⇒ 保留现状
                }
            }
            if (books == null) books = new JSONArray();
            r.shelf = books.length();

            // ② 已有记录的"新鲜度"一次性读进内存（别在循环里反复解析 JSON）
            Map<String, Long> atById = new HashMap<String, Long>();
            List<AssetStore.Book> known = AssetStore.all(c);
            for (int i = 0; i < known.size(); i++) {
                AssetStore.Book kb = known.get(i);
                atById.put(kb.id, Long.valueOf(kb.at));
            }

            // ③ 预筛 + 逐本补
            List<AssetStore.Book> batch = new ArrayList<AssetStore.Book>();
            int sinceFlush = 0;
            for (int i = 0; i < books.length(); i++) {
                JSONObject o = books.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("bookId", "");
                if (id.length() == 0) continue;

                // D9 预筛：从没打开过的书（readUpdateTime <= 0）不发请求
                long t = o.optLong("readUpdateTime", 0L);
                if (t <= 0L) {
                    r.prefiltered++;
                    continue;
                }

                // D9 增量：已有且新鲜的记录不重拉
                Long at = atById.get(id);
                if (at != null && at.longValue() > 0L
                        && System.currentTimeMillis() - at.longValue() <= BOOK_TTL_MS) {
                    continue;
                }

                if (r.requested >= MAX_REQ) {                       // 单轮上限 ⇒ 下一轮接着补
                    r.capped = true;
                    break;
                }
                r.requested++;

                JSONObject arg = new JSONObject();
                try {
                    arg.put("bookId", id);
                } catch (Exception ignored) {
                }
                WereadApi.Resp pr = WereadApi.raw(key, WereadApi.buildBody("/book/getprogress", arg));
                if (pr == null || pr.error != null || pr.json == null) {
                    // D10：失败即停 —— 已取到的先落盘，页面渲染缓存，下一轮续补
                    r.error = "getprogress:" + (pr == null ? "null" : pr.error);
                    break;
                }
                JSONObject b = pr.json.optJSONObject("book");
                if (b == null) continue;                            // 公众号 / 听书这类没进度的 ⇒ 跳过

                AssetStore.Book rec = new AssetStore.Book();
                rec.id = id;
                rec.title = o.optString("title", "");
                rec.author = o.optString("author", "");
                // 累计秒：真实回包同时含两字段且 `recordReadingTime` 可能为 0（S4 实测 =0），
                // 以既有通路（WereadApi.java:400 / PeriodStats.java:251）同口径的 `readingTime` 为准，
                // 仅当它缺失或为 0 时才回落 `recordReadingTime`（确定性优先级，不用 Math.max 猜语义）。
                // 🔴 2026-10-11 修：旧写法 `b.has("recordReadingTime") ? … : readingTime` 在
                //   `recordReadingTime=0` 时把 33808 秒丢掉 ⇒ sec 恒 0 ⇒ 阅读资产「暂无数据」（227 §6.6）。
                rec.sec = b.optInt("readingTime", 0);
                if (rec.sec <= 0) rec.sec = b.optInt("recordReadingTime", 0);
                rec.pct = b.optInt("progress", -1);
                rec.finishTime = b.optLong("finishTime", 0L);
                rec.at = System.currentTimeMillis();
                if (rec.sec > 0) r.kept++;
                r.fetched++;
                batch.add(rec);

                if (++sinceFlush >= FLUSH_EVERY) {
                    AssetStore.putAll(c, batch);
                    batch.clear();
                    sinceFlush = 0;
                }
            }
            if (!batch.isEmpty()) AssetStore.putAll(c, batch);

            // ④ 全量轮**真的跑完了**才记时间；被 cap / 出错 ⇒ 下次继续
            if (r.error == null && !r.capped) {
                AssetStore.setFullAt(c, System.currentTimeMillis());
                r.complete = true;
            }
        } catch (Throwable e) {
            r.error = (r.error == null) ? ("exception:" + e.getClass().getSimpleName()) : r.error;
        } finally {
            sLast = r;
            sRunning = false;
            if (l != null) {
                try {
                    l.onDone(r);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ══════════════════════ 回调 / 结果 ══════════════════════

    /** 完成回调（**后台线程**调用；只用来记日志 / 触发一次重绘，别做重活）。 */
    public interface Listener {
        void onDone(Result r);
    }

    /** 一轮的结果 —— 报告里的"预筛数 / 实际请求数"就从这里取。 */
    public static final class Result {
        /** 书架条目数（全量回包时 = 全量书架）。 */
        public int shelf;
        /** 预筛掉的书（`readUpdateTime <= 0`，从没打开过）。 */
        public int prefiltered;
        /** 本**轮实际发出**的请求数（不累计历史）。 */
        public int requested;
        /** 成功解析到进度的本数。 */
        public int fetched;
        /** 其中累计时长 &gt; 0 的本数。 */
        public int kept;
        /** 本轮是不是"全量轮"（首次 / 全量轮过期）。 */
        public boolean full;
        /** 是否触到 {@link #MAX_REQ} 上限（⇒ 还有书没补，下一轮继续）。 */
        public boolean capped;
        /** 全量轮是否跑完（写入了 `full_at`）。 */
        public boolean complete;
        /** 失败原因（null = 没失败）。 */
        public String error;

        /** 一行摘要（日志 / 上机取证用）。 */
        public String text() {
            return "shelf=" + shelf + " prefiltered=" + prefiltered
                    + " requested=" + requested + " fetched=" + fetched + " kept=" + kept
                    + " full=" + full + " capped=" + capped + " complete=" + complete
                    + (error == null ? "" : (" error=" + error));
        }

        @Override
        public String toString() {
            return text();
        }
    }
}
