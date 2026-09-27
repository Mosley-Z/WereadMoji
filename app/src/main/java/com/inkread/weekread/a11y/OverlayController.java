package com.inkread.weekread.a11y;

import com.inkread.weekread.core.BookStats;
import com.inkread.weekread.core.BookStore;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.core.CardSpec;
import com.inkread.weekread.feature.OverlayWindow;
import com.inkread.weekread.feature.WeekCardView;
import com.inkread.weekread.ui.CardMenuView;

import android.content.Context;
import android.graphics.RectF;
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
    /** 本记**行末**「展开▽ / 收起△」的透明触摸区（TASK-017，位置随绘制结果走） */
    private View expandView;
    /** 长按抬头弹出的菜单（铺满卡片的透明窗口，平时 GONE） */
    private CardMenuView menuView;
    private boolean windowAdded = false;

    // ══════════════════════ 本记「展开▽」（v0.9，TASK-017）══════════════════════

    /**
     * 卡片主体窗口的**当前高度**（收起 = {@code CardSpec.cardHeight()}）。
     *
     * 展开态只是把这个值换成 {@code CardSpec.cardHeightExpanded()}，
     * 底部那两个按钮的触摸窗与菜单窗都按它重算 ⇒ 三处永远与画出来的框重合。
     */
    private int cardH = CardSpec.cardHeight();

    /** 最近一次回写出来的行末按钮矩形（**屏幕**坐标）；空 = 这次没有按钮 */
    private final RectF expandBoxScreen = new RectF();
    /**
     * 卡片主体**此刻是否可见**（{@link #applyVisibility} 算出来的那个 `show`）。
     *
     * 「展开/收起」窗的可见性要跟着它走 —— 卡片藏起来时这个窗必须也走开，
     * 否则会在看不见的地方继续吃掉桌面的点击（与 openView/prevView 同一条纪律）。
     */
    private boolean cardVisible = false;

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
     * 代价是"桌面第 2 页仍会被卡片挡住"，这一点已如实写进交付说明。
     *
     * ⚠️ **2026-09-25 漂移（t11）**：进「设置」页的那条 `sx=960` 不再投递（只投 480，
     * 与第 1/2 页同形）⇒ 本闸门在设置页上失效。修复 = TASK-009 内容探测
     * （{@link SettingsPageProbe}）：歧义页码事件后查一次节点树找 `settings_top`，
     * 命中 ⇒ `st.settingsGate` 让位（见 applyVisibility 的新条件）。
     * 本闸门保留，作 sx≥960 场景的兜底；`PAGE_HIDE_FROM=1` 仍然不可行（理由不变）。
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
            cardH = CardSpec.cardHeight();
            // 「展开▽ / 收起△」的宿主回调（TASK-017）：窗口高度与行末按钮的位置都由卡片回写驱动，
            // 本类只负责把它们落到 WindowManager —— **不碰任何让位判据**。
            view.setExpandListener(new WeekCardView.ExpandListener() {
                @Override
                public void onExpanded(boolean expanded) {
                    setCardHeight(expanded ? CardSpec.cardHeightExpanded() : CardSpec.cardHeight());
                }

                @Override
                public void onExpandBox(RectF box) {
                    updateExpandTouch(box);
                }
            });
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

            // 行末「展开▽ / 收起△」（TASK-017）：初始尺寸给个 1×1 并先 GONE ——
            // 真正的位置要等第一帧画完才知道（按钮挂在第几行取决于这条划线有多长）。
            expandView = new View(ctx);
            expandView.setVisibility(View.GONE);
            expandView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (view != null) view.toggleNoteExpanded();
                }
            });
            wm.addView(expandView, OverlayWindow.paramsExpandTouch(
                    OverlayWindow.typeAccessibility(),
                    CardSpec.CARD_LEFT, CardSpec.CARD_TOP, 1, 1));

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
            prevView = null;
            expandView = null;
            menuView = null;
            cardH = CardSpec.cardHeight();
            windowAdded = false;
        }
    }

    // ══════════════════════ 卡片高度 / 行末按钮窗（TASK-017）══════════════════════

    /**
     * 改卡片主体窗口的高度，并让**所有随高度走**的窗口跟着重摆。
     *
     * @param h 目标高度（{@code CardSpec.cardHeight()} 或 {@code cardHeightExpanded()}）
     */
    private void setCardHeight(int h) {
        if (wm == null || view == null) return;
        if (cardH == h) return;              // 没变就别动 —— 每次 updateViewLayout 都是一次合成
        cardH = h;
        try {
            wm.updateViewLayout(view, OverlayWindow.params(OverlayWindow.typeAccessibility(), h));
            // 页内两个按钮按 h 定位，天然随下沿下移 ⇒ 触摸窗必须同步，否则框与窗错位
            if (openView != null) {
                wm.updateViewLayout(openView,
                        OverlayWindow.paramsOpenTouch(OverlayWindow.typeAccessibility(), h));
            }
            if (prevView != null) {
                wm.updateViewLayout(prevView,
                        OverlayWindow.paramsPrevTouch(OverlayWindow.typeAccessibility(), h));
            }
            if (menuView != null) {
                wm.updateViewLayout(menuView,
                        OverlayWindow.paramsMenu(OverlayWindow.typeAccessibility(), h));
            }
            CardDebug.note(ctx, "card height=" + h);
        } catch (Throwable t) {
            // 窗口已经被摘（服务重建的竞态）⇒ 记一笔就好，别把异常抛进绘制回调
            CardDebug.note(ctx, "setCardHeight failed h=" + h);
        }
    }

    /**
     * 按绘制回写出来的矩形摆「展开/收起」触摸窗。
     *
     * @param box **View 内坐标**的按钮矩形；null 或空 = 这次没有按钮 ⇒ 把窗藏起来
     */
    private void updateExpandTouch(RectF box) {
        if (wm == null || expandView == null) return;
        if (box == null || box.isEmpty()) {
            expandBoxScreen.setEmpty();
            expandView.setVisibility(View.GONE);
            return;
        }
        // View 内坐标 → 屏幕坐标（与 openBox/prevBox 同款换算：窗口左上角就在 CARD_LEFT/CARD_TOP）
        expandBoxScreen.set(CardSpec.CARD_LEFT + box.left, CardSpec.CARD_TOP + box.top,
                CardSpec.CARD_LEFT + box.right, CardSpec.CARD_TOP + box.bottom);
        int x = Math.round(expandBoxScreen.left);
        int y = Math.round(expandBoxScreen.top);
        int w = Math.max(1, Math.round(expandBoxScreen.width()));
        int h = Math.max(1, Math.round(expandBoxScreen.height()));
        try {
            wm.updateViewLayout(expandView,
                    OverlayWindow.paramsExpandTouch(OverlayWindow.typeAccessibility(), x, y, w, h));
            // 只有"卡片此刻确实可见 且 是本记形态"才接管触摸 —— 其它形态下必须让开，
            // 否则会在看不见的地方吃掉桌面的点击（与 openView/prevView 同一条纪律）
            expandView.setVisibility(cardVisible ? View.VISIBLE : View.GONE);
        } catch (Throwable t) {
            expandView.setVisibility(View.GONE);
            CardDebug.note(ctx, "updateExpandTouch failed");
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
        // 仅一页模式（TASK-011，opt-in 默认关）：桌面只有一页时"翻页让位"整路不参与 ——
        // pageGate 与 desktopPage 兜底两句一起短路；开关关时本式与原式逐位等价。
        // 其余让位路（点图标/长按隐藏/通知栏/设置页/自家界面）不受影响。
        boolean pageAllow = CardPrefs.isSinglePageMode(ctx)
                || (!st.pageGate && st.desktopPage < PAGE_HIDE_FROM);
        boolean show = CardPrefs.isEnabled(ctx) && st.onDesktop && !CardA11yService.sOwnUiForeground
                && !st.shadeOpen                        // 通知栏已下拉 → 让位，别压住通知
                && !st.iconGate                         // 刚点过桌面图标（书架等）→ 让位（§25）
                && !st.hideGate                         // 用户长按选择"隐藏 N 分钟" → 让位（v0.3.4）
                && !st.settingsGate                     // ELauncher「设置」页（内容探测命中）→ 让位（TASK-009）
                && pageAllow;                           // 翻页让位（v0.6.0 + PAGE_HIDE_FROM 兜底）—— 仅一页模式可短路（TASK-011）
        cardVisible = show;
        // ── TASK-017：让位 / 隐藏 ⇒ **强制收起**本记展开态 ──
        // 理由（用户拍板⑥）：卡片被藏起来时如果还留着 660px 的展开态，恢复显示时会"露出一大块"，
        // 或只露出一半 —— 收起态才是"随时可以显示"的安全形态。
        // 🔴 只在这一行加钩子，**不动上面任何一个判据**（让位逻辑已由 TASK-010 收口）。
        if (!show) view.collapseNote();
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
        // 「展开▽ / 收起△」只在本记形态、卡片可见、且这一帧真画出了按钮时才接管触摸。
        // 其它形态 / 卡片藏起来时一律 GONE —— 它就在正文区里，藏不掉就会吃掉桌面的长按与滑动。
        if (expandView != null) {
            expandView.setVisibility(
                    (show && noteMode && !expandBoxScreen.isEmpty()) ? View.VISIBLE : View.GONE);
        }
        if (menuView != null) {
            menuView.setVisibility((show && st.menuOpen) ? View.VISIBLE : View.GONE);
        }
        CardDebug.note(ctx, "visibility=" + (show ? "VISIBLE" : "GONE")
                + " (enabled=" + CardPrefs.isEnabled(ctx)
                + ", st.onDesktop=" + st.onDesktop
                + ", page=" + st.desktopPage
                + ", swipePage=" + st.pageGate
                + ", set=" + st.settingsGate
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
        removeSafely(expandView);
        removeSafely(menuView);

        view = null;
        hitView = null;
        titleView = null;
        openView = null;
        prevView = null;
        expandView = null;
        menuView = null;
        cardH = CardSpec.cardHeight();
        cardVisible = false;
        expandBoxScreen.setEmpty();
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