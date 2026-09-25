package com.inkread.weekread.a11y;

import com.inkread.weekread.core.BookStats;
import com.inkread.weekread.core.BookStore;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.feature.OverlayWindow;
import com.inkread.weekread.feature.WeekCardView;
import com.inkread.weekread.ui.CardMenuView;

import android.content.Context;
import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮窗的生命周期与**显隐单一出口**（TASK-006 从 `CardA11yService` 整段平移而来）。
 *
 * 🔴 **只有这个类写 `setVisibility`** —— 卡片明令：Gate 不许直接 setVisibility，
 * 否则三条"防藏死"重算路径会散掉。两个 Gate 与 Router 只改 {@link CardVisibilityState}，
 * 再调 {@link #applyVisibility()} 让它统一落盘。
 *
 * ⚠️ 本类**不含任何判据**，判据全在 {@link TomoPageGate} / {@link ElauncherPageGate}。
 */
final class OverlayController {

    private final Context ctx;
    private final android.os.Handler ui;
    private final CardVisibilityState st;
    private CardContentController content;   // 构造后注入（窗口里的点击要回调它）

    private WindowManager wm;
    private WeekCardView view;
    /** 右上角「更新于…」的透明触摸区（手动刷新） */
    private View hitView;
    /** 左上角「抬头」的透明触摸区（短按切形态、长按弹菜单） */
    private View titleView;
    /** 右下角「打开」按钮的透明触摸区（只在本书形态出现） */
    private View openView;
    /** 左下角「上一条」按钮的透明触摸区（只在本记形态出现） */
    private View prevView;
    /** 长按抬头弹出的菜单（铺满卡片的透明窗口，平时 GONE） */
    private CardMenuView menuView;
    private boolean windowAdded = false;

    OverlayController(Context ctx, android.os.Handler ui, CardVisibilityState st) {
        this.ctx = ctx;
        this.ui = ui;
        this.st = st;
    }

    void attachContent(CardContentController c) {
        this.content = c;
    }

    /** 给内容层用：卡片视图本体（可能为 null —— 窗口还没建 / 已摘） */
    WeekCardView cardView() {
        return view;
    }

    /**
     * 从第几页开始让位（0 基）。
     *
     * ⚠️ **这个值为什么是 2 而不是 1，是实测出来的，不要"优化"成 1。**
     *
     * ELauncher 的 scroller 是 `com.wetao.elauncher:id/viewPager`（宽 = 屏宽 480），
     * 页码 = `scrollX / 480`。但**事件投递是不可靠的**：
     *
     *   进入「设置」页：收到 480(cct=1) → 960(cct=3)   ← 最终值 960 有投递 ✅
     *   按 HOME 回桌面：收到 960(cct=1) → 480(cct=3)   ← **停在 0 的最终值从来没投递过** ❌
     *   静置在第 1 页时：完全没有 ViewPager 事件（只有时钟 TextView 每分钟跳一次）
     *
     * 也就是说：
     *   · 第 1 页（真值 scrollX=0）在事件里**最高只会表现为 480**；
     *   · 第 2 页（真值 scrollX=480）同样表现为 480。
     * 两者在事件层面**无法区分** —— 所以绝不能写成"page != 0 就藏"，
     * 那样会把卡片在第 1 页也藏掉，而且因为静置无事件、它永远不会自己恢复。
     *
     * 能可靠区分的只有 `scrollX ≥ 960` 这一档（ELauncher 那些**不在指示点里的隐藏页**，
     * 目前已知「设置」是其中之一）。所以阈值取 2：**只在 ≥ 第 3 页时让位**。
     * 代价是"桌面第 2 页仍会被卡片挡住"，这一点已如实写进交付说明，等用户决定是否
     * 用 `canRetrieveWindowContent` 换取精确判定。
     */
    private static final int PAGE_HIDE_FROM = 2;

    /** 长按菜单的四项，与 {@link #HIDE_MS} 一一对应 */
    private static final String[] MENU_ITEMS = {
            "隐藏 15 秒（测试）",
            "隐藏 1 分钟",
            "隐藏 5 分钟",
            "取消"
    };
    /**
     * 每个菜单项对应的隐藏时长（毫秒）。
     *
     * 15 秒那档是**测试期**用的（用户拍板：先用短时长提高测试效率），
     * 正式版会把它去掉或挪到最后 —— 排序上把它放第一项，测试时点起来最快。
     */
    private static final long[] HIDE_MS = {15_000L, 60_000L, 300_000L, 0L};

    /** 菜单多久自动消失（毫秒）。没人操作时别一直占着卡片区域的触摸 */
    private static final long MENU_AUTO_MS = 8_000L;

    private final Runnable hideExpire = new Runnable() {
        @Override
        public void run() {
            if (!st.hideGate) return;
            st.hideGate = false;
            st.hideUntil = 0L;
            CardDebug.note(ctx, "st.hideGate=false (timer) → 按当前前台重算");
            applyVisibility();
        }
    };

    private final Runnable menuTimeout = new Runnable() {
        @Override
        public void run() {
            dismissMenu();
        }
    };

    void ensureWindow() {
        if (windowAdded && view != null) return;
        try {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            view = new WeekCardView(ctx);
            // 卡片窗口就是卡片的左右边界，所以内容不再留横向内边距，
            // 分隔线/柱状图两端才正好落在 56 / 423 这两个图标描边上
            view.setPadXRatio(0f);
            String m0 = StatsStore.getCardPeriod(ctx);
            view.setMode(m0);
            if (PeriodRange.BOOK.equals(m0)) {
                BookStats b0 = BookStore.load(ctx);
                view.setBook(b0);
                content.loadCover(b0);
            } else if (PeriodRange.NOTE.equals(m0)) {
                if (!content.showNote(false)) content.noteSync(false);
            } else view.setStats(StatsStore.loadCard(ctx));
            view.setOpenListener(new WeekCardView.OpenListener() {
                @Override
                public void onOpen() {
                    // 右下角那个框：本书=打开，本记=换一条
                    if (PeriodRange.NOTE.equals(StatsStore.getCardPeriod(ctx))) {
                        content.nextNote();
                    } else {
                        content.openBook();
                    }
                }
            });
            wm.addView(view, OverlayWindow.params(OverlayWindow.typeAccessibility()));

            // 右上角「更新于…」那一小块：点它手动刷新。
            // 单独开一个透明小窗，而不是给整张卡片去掉 NOT_TOUCHABLE ——
            // 卡片有 368×346，去掉的话桌面在这一大片里的手势就全废了。
            hitView = new View(ctx);
            hitView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    content.manualRefresh();
                }
            });
            wm.addView(hitView, OverlayWindow.paramsTouch(OverlayWindow.typeAccessibility()));

            // 左上角「抬头」那一小块：**短按**切形态（本周→本月→本书→本记，见 StatsStore.toggleCardPeriod）、**长按**弹隐藏菜单。
            // 同样是独立小窗 —— 切换只是"换一帧内容"，不碰任何显隐状态。
            titleView = new View(ctx);
            titleView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    content.togglePeriod();
                }
            });
            titleView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showMenu();
                    return true;
                }
            });
            wm.addView(titleView, OverlayWindow.paramsTitleTouch(OverlayWindow.typeAccessibility()));

            // 右下角「打开」：只在本书形态可见（applyVisibility 里按形态开关），
            // 点了跳微信读书当前进度页。
            openView = new View(ctx);
            openView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (PeriodRange.NOTE.equals(StatsStore.getCardPeriod(ctx))) {
                        content.nextNote();
                    } else {
                        content.openBook();
                    }
                }
            });
            wm.addView(openView, OverlayWindow.paramsOpenTouch(OverlayWindow.typeAccessibility()));

            // 左下角「上一条」（v0.4.2）：与右下角对称，只在本记形态出现。
            // 单独开窗而不是把整张卡变成可触摸 —— 理由同「打开」按钮。
            prevView = new View(ctx);
            prevView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    content.prevNote();
                }
            });
            wm.addView(prevView, OverlayWindow.paramsPrevTouch(OverlayWindow.typeAccessibility()));

            // 长按菜单：铺满卡片的透明窗口，平时 GONE —— 见 CardMenuView 的说明
            menuView = new CardMenuView(ctx);
            menuView.setItems(MENU_ITEMS);
            menuView.setListener(new CardMenuView.Listener() {
                @Override
                public void onSelect(int index) {
                    long ms = (index >= 0 && index < HIDE_MS.length) ? HIDE_MS[index] : 0L;
                    dismissMenu();
                    if (ms > 0L) startHide(ms);
                }

                @Override
                public void onDismiss() {
                    dismissMenu();
                }
            });
            wm.addView(menuView, OverlayWindow.paramsMenu(OverlayWindow.typeAccessibility()));

            windowAdded = true;
        } catch (Throwable t) {
            view = null;
            hitView = null;
            titleView = null;
            openView = null;
            menuView = null;
            windowAdded = false;
        }
    }

    // ══════════════════════ 长按菜单 / 隐藏闸门 ══════════════════════

    void showMenu() {
        if (menuView == null) return;
        st.menuOpen = true;
        if (ui != null) {
            ui.removeCallbacks(menuTimeout);
            ui.postDelayed(menuTimeout, MENU_AUTO_MS);
        }
        CardDebug.note(ctx, "longPress → menu");
        applyVisibility();
    }

    void dismissMenu() {
        if (!st.menuOpen) return;
        st.menuOpen = false;
        if (ui != null) ui.removeCallbacks(menuTimeout);
        applyVisibility();
    }

    /** 隐藏 ms 毫秒后自动恢复（恢复那一瞬间若不在桌面，就等回到桌面再显示） */
    void startHide(long ms) {
        st.hideGate = true;
        st.hideUntil = System.currentTimeMillis() + ms;
        if (ui != null) {
            ui.removeCallbacks(hideExpire);
            ui.postDelayed(hideExpire, ms);
        }
        CardDebug.note(ctx, "st.hideGate=true " + (ms / 1000L) + "s");
        applyVisibility();
    }

    void applyVisibility() {
        if (view == null) return;
        boolean show = CardPrefs.isEnabled(ctx) && st.onDesktop && !CardA11yService.sOwnUiForeground
                && !st.shadeOpen                        // 通知栏已下拉 → 让位，别压住通知
                && !st.iconGate                         // 刚点过桌面图标（书架等）→ 让位（§25）
                && !st.hideGate                         // 用户长按选择"隐藏 N 分钟" → 让位（v0.3.4）
                && !st.pageGate                         // 翻离了桌面第 1 页 → 让位（v0.6.0）
                && st.desktopPage < PAGE_HIDE_FROM;     // 进了桌面的隐藏页（如 ELauncher「设置」）→ 让位
        view.setVisibility(show ? View.VISIBLE : View.GONE);
        // 触摸区跟着一起显隐 —— 卡片藏起来时它们必须也走开，
        // 否则会在看不见的地方继续吃掉桌面的点击
        if (hitView != null) hitView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (titleView != null) titleView.setVisibility(show ? View.VISIBLE : View.GONE);
        // 「打开」只在本书形态存在：周/月形态下它要完全让开，不然会吃掉右下角的桌面手势
        String cardMode = StatsStore.getCardPeriod(ctx);
        boolean bookMode = PeriodRange.BOOK.equals(cardMode);
        boolean noteMode = PeriodRange.NOTE.equals(cardMode);
        if (openView != null) {
            openView.setVisibility((show && (bookMode || noteMode)) ? View.VISIBLE : View.GONE);
        }
        // 「上一条」只在本记形态存在 —— 其它形态下它必须完全让开，否则会吃掉左下角的桌面手势（v0.4.2）
        if (prevView != null) {
            prevView.setVisibility((show && noteMode) ? View.VISIBLE : View.GONE);
        }
        if (menuView != null) {
            menuView.setVisibility((show && st.menuOpen) ? View.VISIBLE : View.GONE);
        }
        CardDebug.note(ctx, "visibility=" + (show ? "VISIBLE" : "GONE")
                + " (enabled=" + CardPrefs.isEnabled(ctx)
                + ", st.onDesktop=" + st.onDesktop
                + ", page=" + st.desktopPage
                + ", swipePage=" + st.pageGate
                + ", shade=" + st.shadeOpen
                + ", icon=" + st.iconGate
                + ", hide=" + st.hideGate
                + ", ownUi=" + CardA11yService.sOwnUiForeground + ")");
    }

    void removeWindow() {
        // 六个窗口视图全部要摘干净 —— 漏掉任何一个，下次 ensureWindow 会再 addView 一个，
        // 旧的那个就永久留在 WindowManager 里（看不见但一直吃掉那片区域的触摸）。
        // v0.4.2 补上：原来只摘了 view / hitView / titleView，openView / menuView 一直没摘。
        removeSafely(view);
        removeSafely(hitView);
        removeSafely(titleView);
        removeSafely(openView);
        removeSafely(prevView);
        removeSafely(menuView);

        view = null;
        hitView = null;
        titleView = null;
        openView = null;
        prevView = null;
        menuView = null;
        windowAdded = false;
    }

    void removeSafely(View v) {
        if (wm == null || v == null) return;
        try {
            wm.removeView(v);
        } catch (Throwable ignored) {
        }
    }
}