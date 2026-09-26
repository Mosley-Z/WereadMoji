package com.inkread.weekread.a11y;

import com.inkread.weekread.core.BookStats;
import com.inkread.weekread.core.BookStore;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.CoverStore;
import com.inkread.weekread.core.NoteStats;
import com.inkread.weekread.core.NoteStore;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PeriodStats;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.feature.WeekCardView;
import com.inkread.weekread.net.NoteSync;
import com.inkread.weekread.net.WereadApi;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * 卡片**显示什么内容 + 用户怎么交互**（TASK-006 从 `CardA11yService` 整段平移而来）。
 *
 * 这一整块原本 337 行、却与「桌面让位」判据**毫无关系**：它只管拉数据、切周期、
 * 换一条划线、跳微信读书。TASK-006 把它单拎出来，薄壳才真的薄得下来
 * （留在壳里的话壳 ≈ 586 行，远超卡片验收项「≤ 400 行」）。
 *
 * 🔴 它**不碰任何让位状态**，只通过 {@link OverlayController#cardView()} 改卡片内容，
 * 改完调 {@code overlay.applyVisibility()} 让显隐由单一出口重算。
 */
final class CardContentController {

    private final Context ctx;
    private final OverlayController ov;
    private final CardVisibilityState st;

    /** 上次"缓存缺封面 URL → 补拉书架"的时刻（防抖：5 分钟内最多一次） */
    private static long sCoverNudgeAt = 0L;

    // ── TASK-012：手动刷新一轮的在途闸门 + 非手动触发节流 ──

    /** 手动刷新一轮还没回来的请求数（>0 = 闸门关，连点直接挡掉） */
    private static int sRoundPending = 0;
    /** 本轮闸门关掉的起点（配 30s 兜底超时用） */
    private static long sRoundGateAt = 0L;
    /** 在途闸门兜底：正常一轮秒级回来；回调万一丢了，30s 后强制放行，闸门不焊死 */
    private static final long ROUND_GATE_MAX_MS = 30_000L;
    /** 非手动触发的拉取最小间隔（5s）：短时间重复触发只放行第一次 */
    private static final long PERIODIC_MIN_GAP_MS = 5_000L;
    /** 上一次拉取实际发出的时刻（手动也算 —— 刚拉过就别让周期路再重拉一遍） */
    private static long sLastFetchAt = 0L;
    /** 最近一轮本记同步的结果状态（空态文案据此区分"失败"与"真的没有"） */
    private int lastNoteState = NoteSync.STATE_OK;

    CardContentController(Context ctx, OverlayController ov, CardVisibilityState st) {
        this.ctx = ctx;
        this.ov = ov;
        this.st = st;
    }

    /** 卡片视图（可能是 null —— 窗口还没建 / 已摘）。原代码里所有 `view != null` 判空照旧 */
    private WeekCardView cardView() {
        return ov.cardView();
    }

    /** 微信读书墨水屏版包名（兜底显式跳转用） */
    private static final String WEREAD_EINK = "com.tencent.weread.eink";

    /** 把「偏好 + 数据 + 可见性」一次性对齐 */
    void refresh() {
        if (!CardPrefs.isEnabled(ctx)) {
            ov.removeWindow();
            return;
        }
        ov.ensureWindow();
        if (cardView() != null) {
            // 卡片显示哪个形态由偏好决定（设置页定默认，左上角短按可临时切）
            String mode = StatsStore.getCardPeriod(ctx);
            cardView().setMode(mode);
            // 「本书」的数据在 BookStore、「本记」在 NoteStore（都是文件缓存）—— 分开取
            if (PeriodRange.BOOK.equals(mode)) {
                BookStats b = BookStore.load(ctx);
                cardView().setBook(b);
                loadCover(b);
            } else if (PeriodRange.NOTE.equals(mode)) {
                if (!showNote(false)) noteSync(false);
            } else cardView().setStats(StatsStore.loadCard(ctx));
        }
        ov.applyVisibility();
    }

    /**
     * 跳微信读书 —— 落点就是当前读到的那一页。
     *
     * `weread://reading?bId={bookId}` 是从微信读书墨水屏版 1.9.9 的 dex 里翻出来的
     * 官方深链（2026-09-22 实测：**冷启动 / 热进程都直达阅读页正文**）；
     * 早期试的 `weread://book/{id}` 只在个别状态下生效（热进程时会被主界面吞掉），
     * 只留作第二级兜底。最后再兜书架给的 https 深链。零新权限。
     */
    void openBook() {
        BookStats b = BookStore.load(ctx);
        if (b == null || b.bookId == null || b.bookId.length() == 0) {
            CardDebug.note(ctx, "openBook: 本地还没缓存书籍，先刷新一次");
            return;
        }
        String uri = "weread://reading?bId=" + b.bookId;
        CardDebug.note(ctx, "openBook " + uri);
        if (tryStart(schemeIntent(uri, null))) return;
        if (tryStart(schemeIntent(uri, WEREAD_EINK))) return;
        if (tryStart(schemeIntent("weread://book/" + b.bookId, null))) return;
        if (b.deepLink != null && b.deepLink.length() > 0) {
            if (tryStart(schemeIntent(b.deepLink, null))) return;
        }
        CardDebug.note(ctx, "openBook 失败：没找到能处理的应用");
    }

    Intent schemeIntent(String uri, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) i.setPackage(pkg);
        return i;
    }

    /** 从 Service 起 Activity 必须带 NEW_TASK；失败返回 false 让调用方继续兜底 */
    boolean tryStart(Intent i) {
        try {
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用户点了卡片右上角的「更新于…」→ 手动拉一轮（TASK-012：按「刷新绑定」补发其它形态） */
    void manualRefresh() {
        CardDebug.note(ctx, "tap refresh, mode=" + StatsStore.getCardPeriod(ctx)
                + ", keyLen=" + StatsStore.getKey(ctx).length());
        refreshBoth();
    }

    /**
     * 手动刷新的总入口（TASK-012）：拉当前形态 + 按「刷新绑定」补发勾选的其它形态。
     *
     * 扁平补发：当前形态路走完整现有路径（置"刷新中"、唯一一次重绘、回调顺带清 refreshing）；
     * 补发路 {@link #fireDetailSilent} / {@link #fireBookSilent} **只写缓存、绝不碰卡片 UI** ——
     * 连 setRefreshing(false) 都不调，避免后台补发失败把当前形态的"刷新中"提前熄掉、
     * 或把错误文案画到别的形态上（CardRenderer 在 stats==null 时会画 errorText）。
     *
     * 在途闸门：一轮发出的所有请求都计数，全部回来才重开；30s 兜底防回调丢失把闸门焊死
     * （照 SettingsPageProbe.GATE_MAX_MS 先例）。
     * 「本记」例外：NoteSync 自带 in-flight 去重 + 退避，且本记没有"其它形态"可补
     * （拍板：本记形态只走 noteSync(true)，不补发）。
     */
    void refreshBoth() {
        final String key = StatsStore.getKey(ctx);
        if (key.length() == 0) return;
        final String mode = StatsStore.getCardPeriod(ctx);
        if (PeriodRange.NOTE.equals(mode)) {
            CardDebug.note(ctx, "refresh: note → noteSync(true) only");
            noteSync(true);
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (sRoundPending > 0 && now - sRoundGateAt < ROUND_GATE_MAX_MS) {
            CardDebug.note(ctx, "refresh: gate closed (" + sRoundPending + " in flight), skip");
            return;
        }
        sRoundGateAt = now;
        final int targets = CardPrefs.getBindTargets(ctx);
        int pending = 1;                        // 当前形态路必发一个请求
        fetchCardData(true, true);              // ① 当前形态：完整现有路径 + 计入在途
        // ② 补发路：只写缓存。当前形态自己那一份不重复发。
        if ((targets & CardPrefs.BIND_WEEK) != 0 && !PeriodRange.WEEKLY.equals(mode)) {
            fireDetailSilent(key, PeriodRange.WEEKLY);
            pending++;
        }
        if ((targets & CardPrefs.BIND_MONTH) != 0 && !PeriodRange.MONTHLY.equals(mode)) {
            fireDetailSilent(key, PeriodRange.MONTHLY);
            pending++;
        }
        if ((targets & CardPrefs.BIND_BOOK) != 0 && !PeriodRange.BOOK.equals(mode)) {
            fireBookSilent(key);
            pending++;
        }
        // 回调都 post 到主线程，本方法在主线程一口气跑完 → 先落定计数，再等回调逐个回收
        sRoundPending = pending;
        CardDebug.note(ctx, "refresh: fired " + pending + " req(s), mode=" + mode
                + ", targets=" + targets);
    }

    /** 一轮里的一个请求回来了 → 在途计数减一；减到 0 闸门重开 */
    private static void roundDone() {
        if (sRoundPending > 0) sRoundPending--;
    }

    /**
     * 用户**短按**了卡片左上角的抬头 → 三态循环（本周 → 本月 → 本书）。
     *
     * 墨水屏没有涟漪动画，**换帧本身就是反馈**（抬头从「本周阅读时长」变成「9月阅读」、
     * 再变成「本书阅读进度」，图形也从柱状图变成日历、再变成进度条）——
     * 所以这里立刻 refresh() 一帧，不等网络。
     * 若目标形态本地没有缓存，再顺手拉一次；有缓存就先显示缓存（离线也能用）。
     */
    void togglePeriod() {
        String mode = StatsStore.toggleCardPeriod(ctx);
        CardDebug.note(ctx, "tap title → mode=" + mode);
        refresh();                                  // 立刻换成另一形态的那一帧
        if (PeriodRange.BOOK.equals(mode)) {
            if (BookStore.load(ctx) == null) fetchBookData(StatsStore.getKey(ctx), false);
        } else if (PeriodRange.NOTE.equals(mode)) {
            if (!showNote(false)) noteSync(false);      // 池子空才起同步（去重 + 退避，见 noteSync）
        } else if (StatsStore.loadCard(ctx) == null) {
            fetchCardData(false);               // 非手动触发：吃 5s 节流（TASK-012）
        }
        ov.applyVisibility();
    }

    /**
     * 拉卡片当前周期的数据（卡片**只看当前周期**，不提供历史周期 —— ④ + 拍板 F）。
     *
     * 失败就留着旧数据，别弹窗打扰（用户可能只是路过点了一下）。
     *
     * TASK-012 拆成两参：{@code manual} = 用户手动触发（恒放行，不走 5s 节流）；
     * {@code countRound} = 作为手动刷新一轮的一部分发出（回调里回收在途计数）。
     * 「只拉当前形态、不在轮上」的内部触发（如换周期后缓存缺补拉）→ 用单参重载。
     */
    void fetchCardData(boolean manual) {
        fetchCardData(manual, false);
    }

    private void fetchCardData(boolean manual, boolean countRound) {
        final String key = StatsStore.getKey(ctx);
        if (key.length() == 0) return;
        final String mode = StatsStore.getCardPeriod(ctx);
        long now = android.os.SystemClock.elapsedRealtime();
        if (!manual && now - sLastFetchAt < PERIODIC_MIN_GAP_MS) {
            CardDebug.note(ctx, "fetch throttled (gap<5s), mode=" + mode);
            return;
        }
        sLastFetchAt = now;                     // 手动也记时刻：刚拉过的数据没必要让周期路再重拉
        CardDebug.note(ctx, "fetch start manual=" + manual + " mode=" + mode);
        if (PeriodRange.NOTE.equals(mode)) {
            // 本记的"刷新中"由 noteSync 自己管（v0.5.3）：如果此刻已经有另一轮同步在跑
            // （比如 App 那边刚发起），noteSync 会直接返回 —— 那么这里**不能**先把
            // refreshing 打开，否则没有任何回调来关它，卡片会永久停在"正在同步…"。
            noteSync(true);                     // 重拉索引 + 多补一批书（清退避，语义不变）
            return;
        }
        // 立刻给文字反馈：墨水屏没有涟漪动画，不写"刷新中…"用户会以为没点到
        if (cardView() != null) cardView().setRefreshing(true);
        if (PeriodRange.BOOK.equals(mode)) {
            fetchBookData(key, true, countRound);   // 允许重拉书架（有 10 分钟最小间隔）
            return;
        }
        final long gen = StatsStore.keyGen();          // R05
        WereadApi.fetchDetail(key, mode, 0, detailCb(gen, countRound));
    }

    /**
     * 周/月详情回调（原 fetchCardData 内联逻辑抽出，TASK-012）：
     * 写缓存 + 「与当前偏好一致」才重绘 + 收尾。
     *
     * @param countRound 作为手动刷新一轮的一部分发出时，回调先回收在途计数
     */
    private WereadApi.Callback detailCb(final long gen, final boolean countRound) {
        return new WereadApi.Callback() {
            @Override
            public void onResult(PeriodStats stats, String rawJson, String error) {
                if (countRound) roundDone();                    // 先回收：被丢弃的结果也说明"请求回来了"
                if (gen != StatsStore.keyGen()) return;         // 换过 Key → 丢弃旧会话结果
                if (stats != null) {
                    StatsStore.save(ctx, stats);
                    // 拉取期间用户可能又切了周期 —— 只认"和当前偏好一致"的那份
                    String now = StatsStore.getCardPeriod(ctx);
                    if (cardView() != null && stats.mode.equals(now)) {
                        cardView().setMode(stats.mode);
                        cardView().setStats(stats);
                    }
                    CardDebug.note(ctx, "fetch ok mode=" + stats.mode
                            + ", total=" + stats.totalSec + ", " + stats.dump());
                } else {
                    if (cardView() != null) cardView().setRefreshing(false);
                    CardDebug.note(ctx, "fetch failed: " + error);
                }
                ov.applyVisibility();
            }
        };
    }

    /**
     * 拉「本书」的数据（书架 → 进度 → 章节目录，见 {@link WereadApi#fetchBook}）。
     *
     * 失败时**留着旧数据**（BookStore 里那份），别把已经显示出来的书名抹掉 ——
     * 用户可能只是路过点了一下，网络抖一下就要他重看一遍空卡片太亏。
     */
    void fetchBookData(final String key, boolean forceShelf) {
        fetchBookData(key, forceShelf, false);
    }

    /**
     * 拉「本书」的数据（书架 → 进度 → 章节目录，见 {@link WereadApi#fetchBook}）。
     *
     * 失败时**留着旧数据**（BookStore 里那份），别把已经显示出来的书名抹掉 ——
     * 用户可能只是路过点了一下，网络抖一下就要他重看一遍空卡片太亏。
     *
     * @param countRound TASK-012：作为手动刷新一轮的一部分发出时，回调回收在途计数
     */
    private void fetchBookData(final String key, boolean forceShelf, final boolean countRound) {
        if (key.length() == 0) return;
        final long gen = StatsStore.keyGen();          // R05
        CardDebug.note(ctx, "fetch book start force=" + forceShelf);
        WereadApi.fetchBook(ctx, key, forceShelf, new WereadApi.BookCallback() {
            @Override
            public void onResult(BookStats b, String error) {
                if (countRound) roundDone();                    // 先回收：被丢弃的结果也说明"请求回来了"
                if (gen != StatsStore.keyGen()) return;         // 换过 Key → 丢弃旧会话结果
                if (b != null) {
                    BookStore.save(ctx, b);
                    CardDebug.note(ctx, "fetch book ok " + b.dump());
                    // 期间用户可能又切了形态 —— 只认"现在还是本书"的情况
                    if (cardView() != null
                            && PeriodRange.BOOK.equals(StatsStore.getCardPeriod(ctx))) {
                        cardView().setBook(b);
                        loadCover(b);
                    }
                } else {
                    if (cardView() != null) {
                        cardView().setRefreshing(false);
                        if (BookStore.load(ctx) == null) {
                            cardView().setError(error == null ? "获取失败" : error);
                        }
                    }
                    CardDebug.note(ctx, "fetch book failed: " + error);
                }
                ov.applyVisibility();
            }
        });
    }

    /**
     * 补发路的静默周/月请求（TASK-012）：只写缓存，不碰卡片 UI —— 连"刷新中"都不碰。
     * keyGen 丢弃与正式路同规；回调先回收在途计数。
     */
    private void fireDetailSilent(String key, String mode) {
        sLastFetchAt = android.os.SystemClock.elapsedRealtime();
        final long gen = StatsStore.keyGen();
        WereadApi.fetchDetail(key, mode, 0, new WereadApi.Callback() {
            @Override
            public void onResult(PeriodStats stats, String rawJson, String error) {
                roundDone();
                if (gen != StatsStore.keyGen()) return;         // 换过 Key → 丢弃
                if (stats != null) {
                    StatsStore.save(ctx, stats);
                    CardDebug.note(ctx, "bind ok mode=" + stats.mode + ", total=" + stats.totalSec);
                } else {
                    CardDebug.note(ctx, "bind failed: " + error);
                }
            }
        });
    }

    /**
     * 补发路的静默「本书」拉取（TASK-012）：只写 BookStore，不碰卡片 UI。
     *
     * 🔴 刻意不走 {@link #fetchBookData}：那条路的失败回调会 setRefreshing(false)/setError ——
     * 后台补发失败会把当前形态的"刷新中"提前熄掉，甚至把错误文案画到周/月卡上
     * （CardRenderer 在 stats==null 时画 errorText，跨形态污染）。
     */
    private void fireBookSilent(String key) {
        final long gen = StatsStore.keyGen();
        WereadApi.fetchBook(ctx, key, true, new WereadApi.BookCallback() {
            @Override
            public void onResult(BookStats b, String error) {
                roundDone();
                if (gen != StatsStore.keyGen()) return;         // 换过 Key → 丢弃
                if (b != null) {
                    BookStore.save(ctx, b);
                    CardDebug.note(ctx, "bind book ok");
                } else {
                    CardDebug.note(ctx, "bind book failed: " + error);
                }
            }
        });
    }

    /**
     * 把当前该显示的那条划线放到卡片上（与 App 内 {@code MainActivity.showNote} 同源）。
     *
     * @return true = 抽到了一条内容（调用方据此决定"要不要发起同步"）
     */
    boolean showNote(boolean manual) {
        if (cardView() == null) return true;              // 没有窗口就什么都不用管
        NoteStats n = NoteStore.pick(ctx, manual);
        showNoteItem(n);
        return n != null;
    }

    /**
     * 把某条划线放上卡片（「换一条」/「上一条」/ 常规展示共用，v0.4.2 抽出来）。
     */
    void showNoteItem(NoteStats n) {
        if (cardView() == null) return;
        if (n == null) {
            // 🔴 渲染函数**绝不发起同步**（v0.5.3，R02）。上一版这里 `syncNoteData(false)`
            // 而同步回调又会回到本函数 —— 空池时形成无界环（桌面侧与 App 侧同构，
            // 一轮最多 43 次请求）。现在同步统一走 {@link #noteSync}。
            if (cardView().getNote() != null) cardView().setNote(null);
            return;
        }
        cardView().setNoteHint(null);
        cardView().setNote(n);
        CardDebug.note(ctx, "note pick " + n.dump());
        // 章节名要联网反查（章节目录按书永久缓存），查到后回填并重绘 —— 不阻塞出内容
        final NoteStats fn = n;
        NoteSync.resolveChapter(ctx, StatsStore.getKey(ctx), n, new Runnable() {
            @Override
            public void run() {
                if (cardView() != null && cardView().getNote() == fn) cardView().setNote(fn);
            }
        });
    }

    /** 用户点了「换一条」 */
    void nextNote() {
        CardDebug.note(ctx, "tap 换一条");
        if (cardView() == null) return;
        if (!showNote(true)) noteSync(true);        // 手动 → 清退避立即放行
        ov.applyVisibility();
    }

    /** 用户点了「上一条」（v0.4.2）—— 沿来时的路退回去 */
    void prevNote() {
        CardDebug.note(ctx, "tap 上一条");
        if (cardView() == null) return;
        NoteStats n = NoteStore.pickPrev(ctx);
        showNoteItem(n);
        if (n == null) noteSync(true);
        ov.applyVisibility();
    }

    /**
     * 本记同步的**唯一入口**（v0.5.3，R02）—— 与 {@code MainActivity.noteSync} 同一套闸门：
     * in-flight 去重（{@link NoteSync#isRunning}）+ 空/失败退避（{@link NoteSync#canAutoStart}），
     * 手动刷新（force）清退避立即放行。
     */
    void noteSync(boolean force) {
        final String key = StatsStore.getKey(ctx);
        if (key == null || key.length() == 0) {
            if (cardView() != null) {
                cardView().setRefreshing(false);
                cardView().setNoteHint("还没填 API Key");
            }
            return;
        }
        if (NoteSync.isRunning()) return;                      // 已经在同步 → 等它回调
        if (!force && !NoteSync.canAutoStart()) {              // 退避期内 → 停在空态，别空转
            if (cardView() != null) {
                cardView().setRefreshing(false);
                cardView().setNoteHint(lastNoteState == NoteSync.STATE_ERROR
                        ? "同步失败，请检查网络" : "还没有同步到笔记");
            }
            return;
        }
        if (force) NoteSync.clearBackoff();
        syncNoteData(force);
    }

    /**
     * 同步「本记」的数据：索引 → 逐本预热划线。
     *
     * 渐进式：一次最多补 {@link NoteSync#DEFAULT_PREFETCH} 本，所以每次回到本记
     * 都会悄悄多一批书进池子，几轮下来自然满库（全库 5572 条 ≈ 2MB）。
     */
    void syncNoteData(boolean force) {
        final String key = StatsStore.getKey(ctx);
        if (key.length() == 0) return;
        final long gen = StatsStore.keyGen();          // R05：换 Key 后旧会话的迟到结果不许写回
        if (cardView() != null) cardView().setRefreshing(true);
        NoteSync.sync(ctx, key, force, NoteSync.DEFAULT_PREFETCH, new NoteSync.Listener() {
            @Override
            public void onDone(int pool, int ideas, int total, String error, int state) {
                CardDebug.note(ctx, "note sync pool=" + pool + " ideas=" + ideas
                        + "/" + total + " state=" + state
                        + (error == null ? "" : (" err=" + error)));
                if (gen != StatsStore.keyGen()) return;         // 换过 Key → 丢弃
                if (cardView() == null) return;                       // 窗口已经摘了 → 丢弃过期 UI 回调
                cardView().setRefreshing(false);
                if (state == NoteSync.STATE_BUSY) return;       // 别的入口在跑，这轮不算数
                lastNoteState = state;
                if (!PeriodRange.NOTE.equals(StatsStore.getCardPeriod(ctx))) {
                    ov.applyVisibility();
                    return;                                     // 用户已切走形态 → 只记状态
                }
                // 拿到内容就换上；仍为空则**停在空态**（这里不会再起同步 —— 环已断）
                boolean got = showNote(false);
                cardView().setNoteHint(got ? null
                        : (state == NoteSync.STATE_ERROR ? "同步失败，请检查网络" : "还没有同步到笔记"));
                ov.applyVisibility();
            }
        });
    }

    /**
     * 把当前书的封面接到卡片上（v0.3.5.1）。
     * 缓存命中时同步出图；没缓存走异步下载，完成后回调重绘（回调已在主线程）。
     * 数据是升级前的旧缓存（没有 cover URL）→ 触发一次书架补拉，拿到 URL 后自会回来。
     */
    void loadCover(BookStats b) {
        if (cardView() == null || b == null || b.bookId == null || b.bookId.length() == 0) return;
        if (b.coverUrl == null || b.coverUrl.length() == 0) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - sCoverNudgeAt > 300_000L) {
                sCoverNudgeAt = now;
                CardDebug.note(ctx, "cover: 旧缓存缺 cover URL → 补拉书架");
                fetchBookData(StatsStore.getKey(ctx), false);
            }
            return;
        }
        CoverStore.loadAsync(ctx, b.bookId, b.coverUrl, 256, new CoverStore.Callback() {
            @Override
            public void onCover(android.graphics.Bitmap bmp) {
                if (cardView() != null) cardView().setCoverBitmap(bmp, b.bookId);
            }
        });
    }
}