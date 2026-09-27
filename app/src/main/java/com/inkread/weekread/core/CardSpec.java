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

    // ══════════════════════ 本记「展开▽」态（v0.9，TASK-017）══════════════════════
    //
    // 🔴 **CARD_BOTTOM = 416 一个字没动** —— 那个值是"不与桌面图标重叠"逐像素量出来的，
    // 收起态必须维持原样（用户 2026-09-26 拍板）。
    // 这里只是**新增**一个"展开态底边"，只有本记形态、且用户点了「展开▽」之后才用。
    //
    // 值 = 730 的由来（`验证记录/59` 项②，TASK-015 实测）：
    //   末排应用名 TextView 的 `bounds.bottom` = 737（墨迹底 ≈733）⇒ 取 730 留 7px 视觉余量。
    // ⚠️ 该值"仅测一次、固化成常量"（用户要求），不要再动态算 —— a11y 侧不读节点几何。
    //
    // ⚠️ 展开态**会盖住下方一排应用图标及名称**，这是**受控的有意行为**（用户已确认）：
    // 卡片整幅白底（CardRenderer#draw 的 `c.drawColor(0xFFFFFFFF)`）把新暴露区域涂白，
    // 观感就是"卡片向下展开、把下面那排盖住"。收起即恢复。

    /** 展开态底边（屏幕坐标）。见上面的量测说明 */
    public static final int CARD_BOTTOM_EXPANDED = 730;

    /** 展开态卡片高度 = 730 − 70 = 660 */
    public static int cardHeightExpanded() {
        return CARD_BOTTOM_EXPANDED - CARD_TOP;
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

    // ── TASK-014 本月热力图（5 档黑度网点）──
    //
    // 与"打卡网格"共用同一套几何（MONTH_* 全部复用，不改），只换"格子怎么画"。
    // 黑度靠**纯黑像素密度（有序抖动 Bayer 网点）**表达，**不是**灰阶
    //（docs/03 §1.3 硬规则：只用 #000000/#FFFFFF，禁灰阶）。

    /**
     * 档位下界（秒），长度 = 档数 − 1；配合 {@link #HEAT_LEVELS} 使用。
     *
     * 语义：`sec ≤ HEAT_TIERS_SEC[0]`（=0）落第 0 档；否则取满足 `sec > HEAT_TIERS_SEC[k]`
     * 的最大 k + 1 档。即：
     * | 档 | 当日阅读 | 图案区黑度 | 整体观感 |
     * |---|---|---|---|
     * | 0 | 0 分钟 | 0% | **空心黑框**（框内纯白） |
     * | 1 | (0, 30 分钟] | 25% | 疏网 |
     * | 2 | (30 分钟, 1 小时] | 50% | 中网 |
     * | 3 | (1 小时, 3 小时] | 75% | 密网 |
     * | 4 | > 3 小时 | 100% | 实心黑 |
     *
     * 🔴 **黑度口径 = "图案区"（扣掉 1px 黑框后的内区）**，不是整格。
     * 因为 1px 黑框本身占面积（20px 格占 19%、52px 格占 7.5%），若按整格量，
     * 0 档就不是 0% 而是 19% —— 验收口径已相应调整（见 `验证记录/69`）。
     *
     * 🔴 末档阈值**由 2 小时上调至 3 小时**（用户 2026-09-26 拍板）——
     * 本机用户日均约 1.1h，2 小时阈值会让"满月一片全黑"、失去区分度。
     */
    public static final int[] HEAT_TIERS_SEC = { 0, 1800, 3600, 10800 };

    /**
     * 各档**图案区**目标黑度（%），与 {@link #HEAT_TIERS_SEC} 的档位一一对应。
     * 由 rank-order 构造（方案 D 正交点阵）**精确保证**（黑点数 = `round(pct × inner² / 100)`）。
     */
    public static final int[] HEAT_LEVELS = { 0, 25, 50, 75, 100 };

    // ── 观感方案「D · 正交 2px 点阵」（2026-09-27 用户拍板，替代 C 的 45° 斜纹）──
    //
    // 演进：4×4 Bayer（太粗，能数格子）→ 16×16 Bayer（20px 格非整除 ⇒ 横条纹）
    // → C · 45° 斜向网屏（细，但**斜纹仍可数**：点距 d = p·√2 ≥ 2.83px，
    //    且晶格轴周期 T = 2p，卡片内区 18px 内仅 ~4–6 个周期 ⇒ 近距离仍看得出"花纹"）
    // → **D · 正交 2px 点阵**（点距 = 2px，18px 内 9 个周期，远超肉眼分辨极限 ⇒ 均匀灰）。
    //
    // 做法与 C 同源：**按目标像素直接生成图案**（不经"小位图拉伸"），
    // 像素按 rank 升序排列、取前 k 个涂黑（rank-order dithering）⇒ 黑度由构造**精确**保证。
    // 唯一区别是 rank 的判据：C 用"离 45° 晶格点的距离"，D 用 **2×2 Bayer 序**
    //（等价于正交 2px 点阵：25% = 2px 单点阵；50% = 2px 对角棋盘；75% = 3/4 覆盖）。

    /**
     * 正交点阵周期（px）。**全档统一取 2**（2026-09-27 用户拍板）——
     * 判据是**点距**：2px 已到"人眼不可分辨"的下限，大格与小格同用此值即可：
     * · 卡片内区 18px → `18 ÷ 2 = 9` 个周期；
     * · 全屏内区 50px → `50 ÷ 2 = 25` 个周期。
     * ⚠️ 取 1px 无意义（已是单像素，无法再"抖动"）；取 ≥3 会重新出现可数纹理。
     * 🔴 本方案**只有一种周期**（不再按小/大格分档）—— C 方案的小/大格两个周期常量已随之删除。
     */
    public static final int HEAT_HTM_PITCH = 2;

    /**
     * 每格的描边宽度（px）。**黑色**（见 {@link #HEAT_CELL_BORDER_INK}）。
     *
     * 🔴 2026-09-27 由「1px 白色内描边」改为「1px **黑色**描边」（用户拍板）——
     * 参考同类墨水屏热力图：格子都带清晰黑框，形成"方块阵列"的秩序感；
     * 且**全白格（0 档）也有框** ⇒ 能看出"这天存在但没读"。
     *
     * ⚠️ 副作用（用户 2026-09-27 已知并接受）：框占面积，0 档不再是纯白，
     * 而是"**空心框**"—— 20px 格整体黑度 19%（`400−18²=76px`）、52px 格 7.5%。
     * 好处：5 档整体黑度变成 19 / 39 / 60 / 79 / 100，**步长均匀 20 个点**。
     * 因此验收口径改为**按图案区（扣框）核定**，见 {@link #HEAT_TIERS_SEC} 的说明。
     */
    public static final int HEAT_CELL_BORDER_PX = 1;

    /** 格框颜色：true = 纯黑框（#000000）；false = 纯白框。2026-09-27 改黑框。 */
    public static final boolean HEAT_CELL_BORDER_INK = true;

    /** 4 档（100% 黑度）是否画实心（不画网点）。true = 实心，省事且最黑。 */
    public static final boolean HEAT_TOP_TIER_SOLID = true;

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

    /** 框上边界（**屏幕**坐标）—— 收起态（h = {@link #cardHeight()}） */
    public static int openBoxTop() {
        return openBoxTop(cardHeight());
    }

    /**
     * 框上边界（**屏幕**坐标）—— 高度由调用方给（TASK-017）。
     *
     * 本记「展开▽」后卡片变高，页内按钮按 `h` 定位**天然随下沿下移**（用户拍板②）⇒
     * 桌面那两个透明触摸窗也得跟着下移，否则会停在老位置、与画出来的框错开。
     * 收起态传 {@link #cardHeight()} 时与旧的无参版**逐位相同**（不破坏既有对齐）。
     */
    public static int openBoxTop(int h) {
        return CARD_TOP + h - OPEN_BOX_H - OPEN_BOX_MARGIN_B;
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

    /** 框上边界（**屏幕**坐标）—— 与「换一条」同一条水平线（收起态） */
    public static int prevBoxTop() {
        return openBoxTop(cardHeight());
    }

    /** 同上，高度由调用方给（TASK-017 展开态）—— 与 {@link #openBoxTop(int)} 同源 */
    public static int prevBoxTop(int h) {
        return openBoxTop(h);
    }
}
