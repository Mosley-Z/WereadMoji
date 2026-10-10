package com.inkread.weekread.a11y;

import com.inkread.weekread.core.BookStats;
import com.inkread.weekread.core.BookStore;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.core.TodoItem;
import com.inkread.weekread.core.TodoStore;
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
    /**
     * 🆕 TASK-080：左上角「打开墨台」小按钮的透明触摸区 —— **四种形态都可见**
     * （它是墨台的唯一常驻入口，见 `tasks/TASK-080` §关键约束 1）。
     *
     * <p>位置是**固定值**（抬头行永远在卡片上沿，不随展开态下移）⇒ 与
     * {@link #titleView} / {@link #hitView} 同款"开一次窗、定死坐标"；
     * 🔴 **不承载长按**（长按 = 隐藏时长菜单仍只归 {@code titleView}）。
     */
    private View deskView;
    /** 左上角「抬头」的透明触摸区（短按切形态、长按弹菜单） */
    private View titleView;
    /** 右下角「打开」按钮的透明触摸区（只在本书形态出现） */
    private View openView;
    /**
     * 左下角按钮位的透明触摸区 —— **两个形态复用同一个窗**：
     * · 「本记」形态 = 「上一条」（v0.4.2）；
     * · 🆕「本书」形态 = 「选书」（TASK-071，入口唯一化到左下角）。
     *
     * 两形态互斥 ⇒ 物理上是同一个按钮位（坐标取 {@code CardSpec} 左下按钮位的唯一权威定义
     * `prevBoxLeft/prevBoxTop`，与画出来的框共用一组常量）。
     * 复用而非再开一窗，与右下角 {@link #openView} 的做法完全对称（那边也是本书=打开 / 本记=换一条）。
     */
    private View prevView;
    /** 本记**行末**「展开▽ / 收起△」的透明触摸区（TASK-017，位置随绘制结果走） */
    private View expandView;
    /**
     * 待办**逐条勾选框**的透明触摸区（V1.0.3-beta，TASK-024）。
     *
     * 🔴 与 {@link #expandView} 同款但**是"一组"**：待办形态每一条左侧的 `☐` 各自一个窗，
     * 一屏最多五六条。条目增减 / 展开收起会让整列位移 ⇒ 位置全部由绘制回写驱动
     * （{@link #updateTodoTouches}），本类只负责把它们落到 WindowManager。
     * 列表为空时整组让开（否则会在看不见的地方吃掉桌面点击）。
     */
    private final java.util.List<View> todoViews = new java.util.ArrayList<View>();
    /** 长按抬头弹出的菜单（铺满卡片的透明窗口，平时 GONE） */
    private CardMenuView menuView;
    /**
     * 「列表模式」的**选卡下拉框**（V1.0.3-beta，TASK-025）：铺满卡片、只画中间那个框，平时 GONE。
     *
     * 与 {@link #menuView}（长按隐藏菜单）同款不同用：那个选隐藏时长，这个选下一张卡。
     * 两者互斥显示 —— 开一个先关另一个（否则两张菜单叠在同一个位置）。
     */
    private CardMenuView periodMenuView;
    /**
     * 下拉框当前这一屏的选项**对应的形态**（与 {@link CardMenuView} 的行一一对应）。
     *
     * ⚠️ 只在 {@link #showPeriodMenu} 里重建 —— 菜单开着时清单不会变（用户此刻看不到设置页），
     * 所以点第 index 行就取这里第 index 个形态，安全。
     */
    private final java.util.List<String> periodMenuModes = new java.util.ArrayList<String>();

    /**
     * 🆕 TASK-069：「本书」候选菜单（点卡片左下角「选书」按钮后弹出）。
     *
     * 🔴 TASK-071：入口从「长按菜单第 0 行」改到**卡片左下角那个按钮**（用户拍板入口唯一化）；
     * 菜单本身的构建与点选逻辑一字未动。
     *
     * 与 {@link #menuView} / {@link #periodMenuView} 同款的一层透明窗（同一位置），
     * 三者**互斥显示** —— 开一个先关另外两个。
     */
    private CardMenuView bookPickMenuView;
    /** 候选菜单当前这一屏每行对应的 bookId（索引 0 = 自动档 `""`），与行一一对应。 */
    private final java.util.List<String> bookPickIds = new java.util.ArrayList<String>();
    /** 「本书」候选菜单是否开着。 */
    private boolean bookPickOpen = false;

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
    private static final String[] HIDE_LABELS = {
            "隐藏 15 秒（测试）",
            "隐藏 1 分钟",
            "隐藏 5 分钟",
            "取消"
    };
    /**
     * 每个隐藏菜单项对应的隐藏时长（毫秒）。
     *
     * 15 秒那档是**测试期**用的（用户拍板：先用短时长提高测试效率），
     * 正式版会把它去掉或挪到最后 —— 排序上把它放第一项，测试时点起来最快。
     */
    private static final long[] HIDE_MS = {15_000L, 60_000L, 300_000L, 0L};
    /** 🆕 TASK-069：候选列表第一行（= 恢复"最近在读"自动档）。 */
    private static final String BOOK_PICK_AUTO_LABEL = "自动（最近在读）";

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

    /** 下拉框的超时收摊（与长按菜单同一条纪律：没人操作就别一直占着卡片区域的触摸） */
    private final Runnable periodMenuTimeout = new Runnable() {
        @Override
        public void run() {
            dismissPeriodMenu();
        }
    };

    /** 🆕 TASK-069：「本书」候选菜单的超时收摊（同一条纪律） */
    private final Runnable bookPickTimeout = new Runnable() {
        @Override
        public void run() {
            dismissBookPickMenu();
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
            // 待办逐条勾选框的宿主回调（TASK-024）：位置同样"只有绘制时才定得下来"，
            // 由卡片回写到本类再落到 WindowManager —— 与「展开▽」完全同款。
            view.setTodoBoxListener(new WeekCardView.TodoBoxListener() {
                @Override
                public void onTodoBoxes(java.util.List<RectF> boxes) {
                    updateTodoTouches(boxes);
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
            } else if (PeriodRange.TODO.equals(m0)) {
                view.setTodo(TodoStore.pending(ctx));      // TASK-024：待办形态（只显未完成）
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

                @Override
                public void onPickBook() {
                    // 🆕 TASK-071：左下角「选书」框（画在卡上）。
                    // 🔴 桌面档**走不到这里** —— 卡片主体带 FLAG_NOT_TOUCHABLE（手势要穿透给桌面），
                    //   卡上按钮的点击一律由透明小窗承接（本形态 = prevView ⇒ showBookPickMenu）。
                    //   这一支是给"卡片本体可触摸"的宿主（App / 预览）兜底的，与那个小窗同归。
                    showBookPickMenu();
                }

                @Override
                public void onOpenDesk() {
                    // 🆕 TASK-080：左上角「打开墨台」。同上——桌面档由 deskView 那个小窗承接，
                    //   这一支给"卡片本体可触摸"的宿主兜底。
                    CardA11yService.showDesk();
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

            // 🆕 TASK-080：左上角最左「打开墨台」—— 四种形态都可见（墨台的唯一常驻入口）。
            // 与其余小窗同款：只圈住那个小按钮，卡片其它区域照旧穿透给桌面。
            // 坐标固定（抬头行不随展开态位移）⇒ 开一次窗即可，不必回写。
            deskView = new View(ctx);
            deskView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CardA11yService.showDesk();
                }
            });
            wm.addView(deskView, OverlayWindow.paramsDeskTouch(OverlayWindow.typeAccessibility()));

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

            // 左下角按钮位 —— **一个窗，两个形态复用**（与右下角「打开/换一条」同款做法）：
            //   · 「本书」形态 = 「选书」（🆕 TASK-071，用户拍板入口唯一化到左下角）；
            //   · 「本记」形态 = 「上一条」（v0.4.2）。
            // 两形态互斥 ⇒ 物理上是同一个按钮位；单独开窗而不是把整张卡变成可触摸
            // —— 理由同「打开」按钮。
            prevView = new View(ctx);
            prevView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (PeriodRange.BOOK.equals(StatsStore.getCardPeriod(ctx))) {
                        showBookPickMenu();
                    } else {
                        content.prevNote();
                    }
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
            menuView.setItems(HIDE_LABELS);        // 隐藏时长，全形态一致的 4 项（TASK-071 起不再按形态插项）
            menuView.setListener(new CardMenuView.Listener() {
                @Override
                public void onSelect(int index) {
                    // 🔴 TASK-071：本菜单**只**管隐藏时长。原先「本书」形态在第 0 行插的
                    //   「选书…」已删除 —— 选书入口唯一化到卡片左下角那个按钮
                    //   （桌面 = 左下透明小窗 / App = 画布命中），见 showBookPickMenu 的注释。
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

            // 「列表模式」选卡下拉框（TASK-025）：与长按菜单同款的一层透明窗，
            // 平时 GONE；点抬头时按"已勾选的卡片池"动态填项（见 showPeriodMenu）。
            periodMenuView = new CardMenuView(ctx);
            periodMenuView.setListener(new CardMenuView.Listener() {
                @Override
                public void onSelect(int index) {
                    String m = (index >= 0 && index < periodMenuModes.size())
                            ? periodMenuModes.get(index) : null;
                    dismissPeriodMenu();
                    if (m != null && content != null) content.selectPeriod(m);
                }

                @Override
                public void onDismiss() {
                    dismissPeriodMenu();
                }
            });
            wm.addView(periodMenuView, OverlayWindow.paramsMenu(OverlayWindow.typeAccessibility()));

            // 🆕 TASK-069：「本书」候选菜单（长按菜单 → 「选书…」）—— 与上面两张菜单同款的一层透明窗
            bookPickMenuView = new CardMenuView(ctx);
            bookPickMenuView.setListener(new CardMenuView.Listener() {
                @Override
                public void onSelect(int index) {
                    String id = (index >= 0 && index < bookPickIds.size()) ? bookPickIds.get(index) : null;
                    dismissBookPickMenu();
                    if (id == null || content == null) return;
                    CardPrefs.setBookPick(ctx, id);          // 空串 = 恢复自动档
                    CardDebug.note(ctx, "book pick=" + (id.length() == 0 ? "auto" : id));
                    content.refreshBookAfterPick();          // 立刻按新偏好重取一次
                }

                @Override
                public void onDismiss() {
                    dismissBookPickMenu();
                }
            });
            wm.addView(bookPickMenuView, OverlayWindow.paramsMenu(OverlayWindow.typeAccessibility()));

            windowAdded = true;
        } catch (Throwable t) {
            // 🔴 A1：半装配失败时，**已 addView 的窗必须先逐个摘干净，再清引用**。
            //    原先只 removeSafely 了 todoViews，其余成员直接置 null ⇒ 已经加进
            //    WindowManager 的窗留在那里（用户表现为"桌面某几处点不动、关掉卡片也不恢复"）。
            //    与本类 removeWindow() 同一纪律：漏摘一个，下次 ensureWindow 会再 addView 一个。
            removeSafely(view);
            removeSafely(hitView);
            removeSafely(titleView);
            removeSafely(deskView);
            removeSafely(openView);
            removeSafely(prevView);
            removeSafely(expandView);
            for (int i = 0; i < todoViews.size(); i++) removeSafely(todoViews.get(i));
            todoViews.clear();
            removeSafely(menuView);
            removeSafely(periodMenuView);
            removeSafely(bookPickMenuView);

            view = null;
            hitView = null;
            titleView = null;
            deskView = null;
            openView = null;
            prevView = null;
            expandView = null;
            menuView = null;
            periodMenuView = null;
            bookPickMenuView = null;
            bookPickOpen = false;
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
            if (periodMenuView != null) {
                wm.updateViewLayout(periodMenuView,
                        OverlayWindow.paramsMenu(OverlayWindow.typeAccessibility(), h));
            }
            if (bookPickMenuView != null) {          // 🆕 TASK-069
                wm.updateViewLayout(bookPickMenuView,
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

    /**
     * 按绘制回写出来的矩形摆**待办逐条勾选框**的触摸窗（TASK-024）。
     *
     * @param boxes **View 内坐标**的勾选框矩形列表，顺序 = 卡片上从上到下 = {@link TodoStore#pending}；
     *              null / 空 = 非待办形态或没有条目 ⇒ 整组让开
     */
    private void updateTodoTouches(java.util.List<RectF> boxes) {
        if (wm == null) return;
        int n = (boxes == null) ? 0 : boxes.size();
        // 点击要定位到"第几条" ⇒ 与当前未完成清单按顺序对齐（与绘制用的是同一份数据）
        java.util.List<TodoItem> items = (n > 0) ? TodoStore.pending(ctx) : null;

        // ① 窗口数量先跟条目数对齐（多退少补）——
        //    勾掉一条后卡片重绘只剩 n-1 个框，这里必须把末尾那个窗摘掉，
        //    否则它会留在原地继续吃掉一次点击。
        while (todoViews.size() < n) {
            View v = new View(ctx);
            v.setVisibility(View.GONE);
            try {
                wm.addView(v, OverlayWindow.paramsTodoTouch(OverlayWindow.typeAccessibility(),
                        CardSpec.CARD_LEFT, CardSpec.CARD_TOP, 1, 1));
                todoViews.add(v);
            } catch (Throwable t) {
                CardDebug.note(ctx, "addView todo touch failed");
                break;
            }
        }
        while (todoViews.size() > n) {
            removeSafely(todoViews.remove(todoViews.size() - 1));
        }

        // ② 逐条摆位 + 绑点击（只有"卡片此刻确实可见 且 是待办形态"才接管触摸 ——
        //    与 openView/prevView 同一条纪律：藏起来就必须让开）
        boolean showTodo = cardVisible && PeriodRange.TODO.equals(StatsStore.getCardPeriod(ctx));
        for (int i = 0; i < todoViews.size(); i++) {
            final long id = (items != null && i < items.size()) ? items.get(i).id : -1L;
            View v = todoViews.get(i);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    if (id < 0L) return;
                    // 勾选 = 标记完成（卡片只显未完成 ⇒ 这一条随即从卡片上消失）
                    TodoStore.setDone(ctx, id, true);
                    if (content != null) content.refresh();
                }
            });
            RectF b = boxes.get(i);
            // View 内坐标 → 屏幕坐标（窗口左上角就在 CARD_LEFT/CARD_TOP，与 expandBox 同款换算）
            int x = Math.round(CardSpec.CARD_LEFT + b.left);
            int y = Math.round(CardSpec.CARD_TOP + b.top);
            int w = Math.max(1, Math.round(b.width()));
            int h = Math.max(1, Math.round(b.height()));
            try {
                wm.updateViewLayout(v, OverlayWindow.paramsTodoTouch(
                        OverlayWindow.typeAccessibility(), x, y, w, h));
                v.setVisibility(showTodo ? View.VISIBLE : View.GONE);
            } catch (Throwable t) {
                v.setVisibility(View.GONE);
                CardDebug.note(ctx, "updateTodoTouches failed");
            }
        }
    }

    // ══════════════════════ 长按菜单 / 隐藏闸门 ══════════════════════

    void showMenu() {
        if (menuView == null) return;
        dismissPeriodMenu();                 // 三张菜单互斥：开这张前先把另两张收掉
        dismissBookPickMenu();
        // 🔴 TASK-071：长按菜单恢复为**全形态一致的 4 项**（隐藏时长）。
        //   原先 TASK-069 在「本书」形态第 0 行插的「选书…」已删除 —— 选书入口唯一化到
        //   卡片左下角那个按钮。连带撤销：`menuHasBookPick` 字段、`MENU_LABEL_PICK_BOOK` 常量、
        //   以及 onSelect 里"索引减一"的那段偏移逻辑。
        menuView.setItems(HIDE_LABELS);
        menuView.setBoxWidth(0f);            // 复位默认框宽（候选菜单可能把它改宽了）
        menuView.setMarkedIndex(-1);         // 长按菜单没有"当前档"标记
        menuView.setLastIsCancel(true);      // 末行「取消」照旧画灰
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

    /**
     * 打开「列表模式」的选卡下拉框（TASK-025）。
     *
     * 选项 = **已勾选的卡片池**（按固有顺序，见 {@link StatsStore#poolModes}）。
     * 池里只有一张时也照常弹（用户能看到"只有这一张"这个事实），点了原地不动。
     */
    void showPeriodMenu() {
        if (periodMenuView == null) return;
        dismissMenu();                       // 两张菜单互斥
        java.util.List<String> modes = StatsStore.poolModes(ctx);
        periodMenuModes.clear();
        periodMenuModes.addAll(modes);
        String[] items = new String[modes.size()];
        for (int i = 0; i < modes.size(); i++) items[i] = StatsStore.modeShortLabel(modes.get(i));
        periodMenuView.setItems(items);
        st.periodMenuOpen = true;
        if (ui != null) {
            ui.removeCallbacks(periodMenuTimeout);
            ui.postDelayed(periodMenuTimeout, MENU_AUTO_MS);
        }
        CardDebug.note(ctx, "tap title → periodMenu (" + modes.size() + ")");
        applyVisibility();
    }

    void dismissPeriodMenu() {
        if (!st.periodMenuOpen) return;
        st.periodMenuOpen = false;
        if (ui != null) ui.removeCallbacks(periodMenuTimeout);
        applyVisibility();
    }

    /**
     * 🆕 TASK-069：打开「本书」候选菜单。
     *
     * 🔴 TASK-071：入口 = **卡片左下角那个「选书」按钮**（桌面档由 {@code prevView} 透明小窗接住；
     * App 档走画布命中 → {@code OpenListener#onPickBook()}）。原先的"长按菜单 → 「选书…」"
     * 已按用户拍板删除 —— 入口唯一化。**本方法的构建与点选逻辑一字未动。**
     *
     * <p>行 = **第 0 行恒为「自动（最近在读）」**（{@code bookId=""}）+ 候选书（{@link BookStore#bookCandidates}）。
     * 料全部来自本地书架快照 ⇒ 这一屏**零网络请求**。
     *
     * <p>「当前档」用左黑竖条标出（墨屏没有高亮色）：手动选中的那本；没手动选就标在自动档上。
     * 框宽放到 300px（书名比「隐藏 5 分钟」长），末行**不**画成灰「取消」（那是真书）。
     */
    void showBookPickMenu() {
        if (bookPickMenuView == null) return;
        dismissMenu();                       // 三张菜单互斥
        dismissPeriodMenu();

        org.json.JSONArray cands = BookStore.bookCandidates(ctx, BookStore.BOOK_CANDIDATES);
        String pick = CardPrefs.getBookPick(ctx);
        bookPickIds.clear();
        java.util.List<String> rows = new java.util.ArrayList<String>();
        bookPickIds.add("");                 // 索引 0 = 自动档
        rows.add(BOOK_PICK_AUTO_LABEL);
        int marked = 0;                      // 默认标在自动档
        if (cands != null) {
            for (int i = 0; i < cands.length(); i++) {
                org.json.JSONObject b = cands.optJSONObject(i);
                if (b == null) continue;
                String id = b.optString("bookId", "");
                if (id.length() == 0) continue;
                String title = b.optString("title", "").trim();
                if (title.length() == 0) title = "（未命名）";
                bookPickIds.add(id);
                rows.add(fitPickTitle(title));
                if (pick.length() > 0 && pick.equals(id)) marked = rows.size() - 1;
            }
        }
        bookPickMenuView.setBoxWidth(300f);  // 书名长，加宽
        bookPickMenuView.setItems(rows.toArray(new String[rows.size()]));
        bookPickMenuView.setMarkedIndex(marked);
        bookPickMenuView.setLastIsCancel(false);   // 末行是真书，别画灰
        bookPickOpen = true;
        if (ui != null) {
            ui.removeCallbacks(bookPickTimeout);
            ui.postDelayed(bookPickTimeout, MENU_AUTO_MS);
        }
        CardDebug.note(ctx, "bookPick menu (" + (rows.size() - 1) + " cands, marked=" + marked + ")");
        applyVisibility();
    }

    void dismissBookPickMenu() {
        if (!bookPickOpen) return;
        bookPickOpen = false;
        if (ui != null) ui.removeCallbacks(bookPickTimeout);
        applyVisibility();
    }

    /** 候选行书名最多留几个字（见 {@link #fitPickTitle}）。 */
    private static final int PICK_TITLE_MAX = 12;

    /**
     * 候选行书名截断：**宁可截字，也不要缩字号**。
     *
     * <p>卡片菜单框宽 300px、正文 19.2px（`16 × 屏高 × 0.0015`）⇒ 一行约放 13 个汉字。
     * 微信读书的书名很长（实测「卡拉马佐夫兄弟（套装上下册）（陀思妥耶夫斯基文集2015）」有 30 字），
     * 若交给 {@link CardMenuView} 自带的"缩到放得下"，会被压到下限 12px ——
     * **同一张菜单里字号参差**，正是 TASK-070① 用户点名的那类毛病。
     * ⇒ 这里先截到 12 字，整张菜单**字号统一**。
     */
    private static String fitPickTitle(String t) {
        if (t == null) return "";
        return (t.length() > PICK_TITLE_MAX) ? (t.substring(0, PICK_TITLE_MAX) + "…") : t;
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
        // 🆕 TASK-080：「打开墨台」四种形态都可见（它是墨台的常驻入口）⇒ 只跟卡片显隐走。
        if (deskView != null) deskView.setVisibility(show ? View.VISIBLE : View.GONE);
        // 「打开」只在本书形态存在：周/月形态下它要完全让开，不然会吃掉右下角的桌面手势
        String cardMode = StatsStore.getCardPeriod(ctx);
        boolean bookMode = PeriodRange.BOOK.equals(cardMode);
        boolean noteMode = PeriodRange.NOTE.equals(cardMode);
        boolean todoMode = PeriodRange.TODO.equals(cardMode);
        if (openView != null) {
            openView.setVisibility((show && (bookMode || noteMode)) ? View.VISIBLE : View.GONE);
        }
        // 左下角按钮位：本书=「选书」/ 本记=「上一条」⇒ 这两种形态可见。
        // 其它形态（周/月/待办）必须完全让开，否则会吃掉左下角的桌面手势（v0.4.2 的纪律不变）。
        if (prevView != null) {
            prevView.setVisibility((show && (bookMode || noteMode)) ? View.VISIBLE : View.GONE);
        }
        // 「展开▽ / 收起△」在本记 + 待办两种形态出现、卡片可见、且这一帧真画出了按钮时才接管触摸。
        // 其它形态 / 卡片藏起来时一律 GONE —— 它就在正文区里，藏不掉就会吃掉桌面的长按与滑动。
        if (expandView != null) {
            expandView.setVisibility(
                    (show && (noteMode || todoMode) && !expandBoxScreen.isEmpty())
                            ? View.VISIBLE : View.GONE);
        }
        // 待办逐条勾选框：只在本待办形态、卡片可见时接管触摸；其它形态 / 卡片藏起来时整组让开。
        for (int i = 0; i < todoViews.size(); i++) {
            View v = todoViews.get(i);
            if (v != null) v.setVisibility((show && todoMode) ? View.VISIBLE : View.GONE);
        }
        if (menuView != null) {
            menuView.setVisibility((show && st.menuOpen) ? View.VISIBLE : View.GONE);
        }
        // 选卡下拉框：与长按菜单同款 —— 卡片可见且下拉框开着才接管触摸
        if (periodMenuView != null) {
            periodMenuView.setVisibility((show && st.periodMenuOpen) ? View.VISIBLE : View.GONE);
        }
        // 🆕 TASK-069：「本书」候选菜单 —— 同一条纪律（缺了这一句，藏起来的卡片上会留下吃点击的窗）
        if (bookPickMenuView != null) {
            bookPickMenuView.setVisibility((show && bookPickOpen) ? View.VISIBLE : View.GONE);
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
        // 所有窗口视图都要摘干净 —— 漏掉任何一个，下次 ensureWindow 会再 addView 一个，
        // 旧的那个就永久留在 WindowManager 里（看不见但一直吃掉那片区域的触摸）。
        // v0.4.2 补上：原来只摘了 view / hitView / titleView，openView / menuView 一直没摘。
        // V1.0.3-beta 再补：待办勾选窗是一组（数量随条目变），必须逐个摘（见下方 for）。
        removeSafely(view);
        removeSafely(hitView);
        removeSafely(titleView);
        removeSafely(deskView);
        removeSafely(openView);
        removeSafely(prevView);
        removeSafely(expandView);
        // 待办勾选窗是"一组"，数量随条目变 ⇒ 必须逐个摘干净（同 expandView 一条纪律）
        for (int i = 0; i < todoViews.size(); i++) removeSafely(todoViews.get(i));
        todoViews.clear();
        removeSafely(menuView);
        removeSafely(periodMenuView);
        removeSafely(bookPickMenuView);

        view = null;
        hitView = null;
        titleView = null;
        deskView = null;
        openView = null;
        prevView = null;
        expandView = null;
        menuView = null;
        periodMenuView = null;
        bookPickMenuView = null;
        st.periodMenuOpen = false;
        bookPickOpen = false;
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