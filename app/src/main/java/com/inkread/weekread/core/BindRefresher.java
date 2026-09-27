package com.inkread.weekread.core;

import android.content.Context;

import com.inkread.weekread.net.WereadApi;

/**
 * 「刷新绑定」的**静默补发**工具（v0.8.1）。
 *
 * ── 为什么要有这个类 ──
 * v0.8.0（TASK-012）只把「刷新绑定」接进了**桌面卡片**那条链路
 * （卡片右上角「更新于…」→ {@code CardContentController.refreshBoth()}）：
 * 点一下，除了当前形态，还会按勾选把其它形态的缓存也一并拉新。
 * 但 **App 内的刷新按钮走的是另一条独立链路**（{@code MainActivity.refresh()}），
 * 它从来只拉当前页签 —— 用户观察到「App 内刷新没跟着绑定走、只有桌面卡片绑定了」。
 *
 * 本类把补发逻辑抽到 core 层，**App 侧与卡片侧共用同一份实现**，
 * 避免两条链路各写一份、日后漂移（呼应 TASK-012 拍板语义：**补发只写缓存、绝不碰 UI**）。
 *
 * ── 铁律（与 TASK-012 完全一致，勿改）──
 * · 补发路**只写缓存**（{@link StatsStore#save}/{@link BookStore#save}），
 *   **绝不碰任何 UI**：不置"刷新中"、不画错误文案 —— 否则后台补发失败会把
 *   当前形态的"刷新中"提前熄掉，甚至跨形态污染卡片（见 CardRenderer 的 errorText 分支）。
 * · keyGen 丢弃与正式路同规：换过 Key 的迟到结果一律丢弃。
 * · 当前形态自己那一份**不重复发**（由调用方照旧走完整路径）。
 *
 * 使用者：{@code MainActivity}（App 内刷新按钮 / 各页签刷新）。
 * 桌面卡片侧仍由 {@code CardContentController.refreshBoth()} 负责（它另有"在途闸门"与
 * 5s 节流等卡片专属机制），本类不接管那一路。
 */
public final class BindRefresher {

    /**
     * 在途闸门：上一轮补发还没全回来时，短时间内的连点直接跳过（防请求堆积）。
     * 口径对齐桌面卡片侧 `CardContentController.refreshBoth()` 的 `sRoundPending` +
     * `ROUND_GATE_MAX_MS`（30s 兜底防回调丢失把闸门焊死），只是这里不细数"几个在途"，
     * 只用"是否有在途 + 时间窗"判断 —— App 侧是用户手动触发，粒度够用。
     */
    private static int sPending = 0;
    private static long sGateAt = 0L;
    private static final long GATE_MAX_MS = 30_000L;

    private BindRefresher() {
    }

    /**
     * 按「刷新绑定」把**除当前形态外**、被勾选的其它形态静默补发一轮。
     *
     * @param c        上下文
     * @param apiKey   微信读书 Key（空则什么都不发）
     * @param curMode  当前正在显示的形态（{@link PeriodRange#WEEKLY}/{@link PeriodRange#MONTHLY}/
     *                 {@link PeriodRange#BOOK}/{@link PeriodRange#NOTE}）—— 这一份由调用方自己拉，
     *                 本方法会跳过它，避免重复请求。
     * @return 实际发起的补发请求数（0 = 无需补发 / 没勾选 / 上一轮还在途）
     */
    public static int fireBound(Context c, String apiKey, String curMode) {
        if (c == null || apiKey == null || apiKey.length() == 0) return 0;
        long now = android.os.SystemClock.elapsedRealtime();
        if (sPending > 0 && now - sGateAt < GATE_MAX_MS) {
            CardDebug.note(c, "bind(app): gate closed (" + sPending + " in flight), skip");
            return 0;
        }
        sGateAt = now;
        final int targets = CardPrefs.getBindTargets(c);
        int fired = 0;
        if ((targets & CardPrefs.BIND_WEEK) != 0 && !PeriodRange.WEEKLY.equals(curMode)) {
            fireDetail(c, apiKey, PeriodRange.WEEKLY);
            fired++;
        }
        if ((targets & CardPrefs.BIND_MONTH) != 0 && !PeriodRange.MONTHLY.equals(curMode)) {
            fireDetail(c, apiKey, PeriodRange.MONTHLY);
            fired++;
        }
        if ((targets & CardPrefs.BIND_BOOK) != 0 && !PeriodRange.BOOK.equals(curMode)) {
            fireBook(c, apiKey);
            fired++;
        }
        sPending = fired;
        return fired;
    }

    /** 一个补发请求回来了 → 在途计数减一 */
    private static void done() {
        if (sPending > 0) sPending--;
    }

    /** 静默拉一个周期（周/月）的 detail，只写 StatsStore 缓存 */
    private static void fireDetail(final Context c, String apiKey, final String mode) {
        final long gen = StatsStore.keyGen();
        WereadApi.fetchDetail(apiKey, mode, 0L, new WereadApi.Callback() {
            @Override
            public void onResult(PeriodStats stats, String rawJson, String error) {
                done();                                      // 在途回收（先于 gen 判定，与卡片侧同规）
                if (gen != StatsStore.keyGen()) return;      // 换过 Key → 丢弃
                if (stats != null) {
                    StatsStore.save(c, stats);
                    CardDebug.note(c, "bind(app) ok mode=" + stats.mode + ", total=" + stats.totalSec);
                } else {
                    CardDebug.note(c, "bind(app) failed mode=" + mode + ": " + error);
                }
            }
        });
    }

    /**
     * 静默拉「本书」，只写 BookStore 缓存。
     *
     * 🔴 刻意**不**走 App / 卡片那条带 UI 的 book 路径：那条路的失败回调会
     * setRefreshing(false) / setError，后台补发会把当前页签的"刷新中"提前熄掉。
     * 这里拿到的结果只落盘（force=true：手动刷新场景允许更频繁地重拉书架缓存）。
     */
    private static void fireBook(final Context c, String apiKey) {
        final long gen = StatsStore.keyGen();
        WereadApi.fetchBook(c, apiKey, true, new WereadApi.BookCallback() {
            @Override
            public void onResult(BookStats b, String error) {
                done();                                      // 在途回收
                if (gen != StatsStore.keyGen()) return;      // 换过 Key → 丢弃
                if (b != null) {
                    BookStore.save(c, b);
                    CardDebug.note(c, "bind(app) book ok");
                } else {
                    CardDebug.note(c, "bind(app) book failed: " + error);
                }
            }
        });
    }
}
