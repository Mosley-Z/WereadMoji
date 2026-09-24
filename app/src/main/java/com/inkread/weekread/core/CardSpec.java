package com.inkread.weekread.core;

/**
 * 桌面悬浮卡片的几何规格 —— 所有位置/尺寸集中在这里，上机复核只改这一个文件。
 *
 * 设备：阅星曈 S4，480×800 px @219dpi（density ≈ 1.37）
 *
 * ✅ 2026-09-21 上机实测（Tomo 桌面 uiautomator 节点树 + 截图逐像素量取）：
 *
 *    状态栏 status_bar        [  0,  0][480, 54]
 *    Tomo 卡片槽 desk_host    [ 30, 54][450,410]   ← 桌面自带的卡片预留区
 *    应用网格 app_grid        [ 30,410][450,766]   ← 3 列 × 2 行，每格 140×178
 *    图标 ImageView 外框       x = 53–147 / 193–287 / 333–427
 *    **图标可见描边**（截图量取，Tomo 与 OEM 桌面完全一致）
 *                             x = 56 … 423，宽 368，中心 239.5
 *
 * 卡片左右两端取**图标可见描边** 56 / 423，而不是 ImageView 外框 53 / 427：
 * 图标是带圆角的描边方块，ImageView 比实际画出来的图形每边多 3px，
 * 按 view 外框对齐全让卡片每边宽出 3px（第一版就是这么写的，被像素量测抓出来了）。
 *
 * ── 2026-09-21 22:xx 第二轮：卡片高度从 440 收到 416 ──
 * 用户反馈「在原生桌面 Elauncher 上卡片对图标有轻微遮挡」。逐像素量测（截图 200_elauncher.png）：
 *
 *    Elauncher 图标 ImageView 外框  y = 436 起（Tomo 是 440）
 *    图标实际可见描边              y = 440 起
 *    卡片内容（底部统计行）实际落到 y = 405…424
 *
 * 也就是说**卡片窗口 70…440 的底边压过了图标 view 的 436**（虽然窗口本身透明，
 * 描边像素没被盖住，但 4px 的重叠在 hit-test 上是实打实的，视觉上也贴得过近）。
 * 收到 416 之后：内容落到 ~404，窗口底边也低于 436 → 两个桌面都不再重叠。
 * 顺带把「翻到第 2 页」那种场景也一起避开了（各页图标 y 位置相同）。
 */
public final class CardSpec {

    private CardSpec() {
    }

    // ── 屏幕 ──
    public static final int SCREEN_W = 480;
    public static final int SCREEN_H = 800;

    // ── 卡片窗口（屏幕坐标）──
    /** 左边界 = 首列图标可见描边（含） */
    public static final int CARD_LEFT = 56;
    /** 右边界 = 末列图标可见描边（不含，取 424 使有效像素恰好落在 56…423） */
    public static final int CARD_RIGHT = 424;
    public static final int CARD_TOP = 70;
    /** 底边。2026-09-21 由 440 收到 416 —— 见类注释里的量测 */
    public static final int CARD_BOTTOM = 416;

    // ── 卡片右上角「更新于…」小块：整张卡片唯一可触摸的区域 ──
    /** 触摸块宽度（右对齐） */
    public static final int TAP_W = 160;
    /** 触摸块高度。比文字本身高一些，墨水屏上手指按得准 */
    public static final int TAP_H = 34;
    /** 触摸块顶边相对卡片顶边的偏移 */
    public static final int TAP_TOP_OFFSET = 18;

    public static int cardWidth() {
        return CARD_RIGHT - CARD_LEFT;
    }

    public static int cardHeight() {
        return CARD_BOTTOM - CARD_TOP;
    }

    public static int tapLeft() {
        return CARD_RIGHT - TAP_W;
    }

    public static int tapTop() {
        return CARD_TOP + TAP_TOP_OFFSET;
    }

    // ─────────────────────────────────────────────────────────────
    // 以下为 v0.3.3「本月」新增。**上面的常量一个都没动**（它们的值是与桌面图标
    // 逐像素对齐过的，改一个就会破坏对齐）。
    // 这里的坐标分两类：
    //   · 带 window 语义的（TITLE_TAP_*）——屏幕绝对坐标，给 WindowManager 用；
    //   · 其余 —— 卡片 View **内部**坐标（左上角为 0,0，尺寸 368×346）。
    // ─────────────────────────────────────────────────────────────

    /**
     * 卡片左上角「抬头」触摸窗：点它 = 切换形态（本周 → 本月 → 本书）。
     *
     * 位置取卡片上沿左侧，screen y ∈ [84, 118]，**离桌面图标（自 y=436 起）远得很**，
     * 不会和任何图标抢触摸。抬头本身在窗口内 y≈20…44，触摸窗覆盖它并留了点余量。
     *
     * ⚠️ 宽度 170 是**实测教训**（2026-09-22 13:11，图墨桌面"反复点切换卡片消失"的根因）：
     * 抬头 = 切换图标(icon≈14px) + 间距(≈4px) + 6 个汉字 × 22.8px ≈ 155px，
     * 文字一直画到屏幕 x≈214 —— 旧值 120 只盖到 x=176，**"时长"两字的点击会穿透**
     * 给图墨桌面的小组件容器（FrameLayout），触发 iconGate 让位且在图墨上没有可靠恢复，
     * 表现为"点几下卡片就消失"。170 盖到 x=226，把三个形态的标题全罩住并留余量。
     */
    public static final int TITLE_TAP_W = 170;
    public static final int TITLE_TAP_H = 34;
    /** 触摸窗顶边相对卡片顶边的偏移（窗口内坐标） */
    public static final int TITLE_TAP_TOP_OFFSET = 14;

    public static int titleTapTop() {
        return CARD_TOP + TITLE_TAP_TOP_OFFSET;
    }

    // ── 本月版式：右侧日历打卡网格 ──

    /** 固定 7 列，表头恒为「一…日」（周一起算，与接口 weekly 口径一致） */
    public static final int MONTH_COLS = 7;
    /** 卡片版格子边长。20px 已是能画细对勾的下限（对勾笔画 2px） */
    public static final int MONTH_CELL = 20;
    /** 列间距 */
    public static final int MONTH_GAP = 4;
    /** 行间距 */
    public static final int MONTH_ROW_GAP = 4;
    /** 网格总宽 = 7×20 + 6×4 = 164 */
    public static final int MONTH_GRID_W = MONTH_COLS * MONTH_CELL + (MONTH_COLS - 1) * MONTH_GAP;

    /** 网格与屏幕右沿的间距（卡片右侧留一点呼吸位，不贴边） */
    public static final int MONTH_GRID_RIGHT_PAD = 2;

    /** 左侧文字列可用宽度（主数字要按这个宽度自动缩字号） */
    public static final int MONTH_LEFT_W = 176;

    // ─────────────────────────────────────────────────────────────
    // 以下为 v0.3.4「本书」与「长按隐藏」新增。同样**没有动上面的常量**。
    // ─────────────────────────────────────────────────────────────

    // ── 「打开」按钮（只在本书形态出现）：卡片右下角的一个描边框 ──
    //
    // 为什么放右下角：左上被"抬头（切形态）"占了，右上被"更新于（刷新）"占了，
    // 左下是正文。右下角是唯一不与现有触摸窗打架的位置，而且离桌面图标最远
    // （图标自 y=436 起，这个框落在 y 372…402）。

    public static final int OPEN_BOX_W = 68;
    public static final int OPEN_BOX_H = 30;
    /** 框右边 / 下边 距卡片右、下边沿的内缩（px） */
    public static final int OPEN_BOX_MARGIN_R = 2;
    public static final int OPEN_BOX_MARGIN_B = 14;

    /** 框左边界（**屏幕**坐标，给 WindowManager 用） */
    public static int openBoxLeft() {
        return CARD_LEFT + cardWidth() - OPEN_BOX_W - OPEN_BOX_MARGIN_R;
    }

    /** 框上边界（**屏幕**坐标） */
    public static int openBoxTop() {
        return CARD_TOP + cardHeight() - OPEN_BOX_H - OPEN_BOX_MARGIN_B;
    }

    // ── 「上一条」按钮（v0.4.2 本记态）：与右下角「换一条」**左右对称** ──
    //
    // 用户拍板：卡片左下角一个独立按钮，和右下角那个对称。
    // 左下角在本记态本来就是空的 —— 正文浮在署名上方、底部两行信息贴着按钮排，
    // 周/月/书本三态根本不画这个按钮（也不开触摸窗）。
    // 尺寸复用 OPEN_BOX_W/H；只有横坐标不同，所以对齐逻辑仍是"改几何只改本文件"。

    /** 「上一条」框距卡片左沿的内缩（与 {@link #OPEN_BOX_MARGIN_R} 对称） */
    public static final int PREV_BOX_MARGIN_L = 2;

    /** 框左边界（**屏幕**坐标，给 WindowManager 用） */
    public static int prevBoxLeft() {
        return CARD_LEFT + PREV_BOX_MARGIN_L;
    }

    /** 框上边界（**屏幕**坐标）—— 与「换一条」同一条水平线 */
    public static int prevBoxTop() {
        return openBoxTop();
    }
}
