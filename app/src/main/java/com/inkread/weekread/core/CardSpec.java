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

    // ── 🆕 2026-10-10（第二轮）：抬头行左侧簇 = 「⇄ 二字标题」+「⟳ 更新于 HH:MM」──
    //
    // 用户 2026-10-10 第二轮拍板（原话：「换一下切换形态和刷新的位置」）⇒ 抬头行最终形态：
    //     ⇄ 本周 ⟳更新于 14:32 ······················ [打开墨台]
    // 与第一版（最左是刷新、其右是形态）相比，这两个块只是**互换位次** ——
    // 宽度、纵向位置、与右端按钮的空档全部沿用第一版的值（那里有真机复核记录）。
    //
    // ⚠️ 常量声明顺序：`UPD_TAP_LEFT_OFFSET` 已删除 ⇒ 左沿改由 {@link #updTapLeft()} **算**出来
    //    （方法体里引用后文声明的 {@link #TITLE_TAP_W} 合法；写成常量表达式会撞 Java 的
    //     "illegal forward reference"）。这样"两窗不重叠"仍由**一处**定义（`TITLE_TAP_W + 间隙`）。

    /** 抬头两个触摸窗之间的横向间隙（px）—— 🔴 两窗**不许重叠**（重叠会让后 add 的窗吃掉对方） */
    static final int HEAD_TAP_GAP = 2;

    /**
     * 「⟳ 刷新 + 更新于 HH:MM」触摸块宽度。
     *
     * <p>🔴 2026-10-10 第二轮由 112 加到 128：换位次后它落在**标题右侧**，而实测「⟳ + 更新于 14:32」
     * 画出来 ≈104px、起点距卡片左沿 ≈92px（标题宽 + 间隙）⇒ 右端要到 ≈196px；
     * 旧的 112（右端 168px）罩不住最后两位数字 ⇒ 点"分钟"那一小截点不动。
     */
    public static final int UPD_TAP_W = 128;
    /** 触摸块高度。比文字本身高一些，墨水屏上手指按得准 */
    public static final int UPD_TAP_H = 34;
    /** 触摸块顶边相对卡片顶边的偏移 */
    public static final int UPD_TAP_TOP_OFFSET = 18;

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
    // 值 = 750 的由来：730 是按 ELauncher 量到末排应用名 `bounds.bottom`=737 留 7px 余量，
    //   但上机实测（Tomo）展开后点「换一张」按钮时容易误触到下方末排应用 ⇒ 2026-09-28
    //   用户拍板**增大到 750**，把末排应用图标及名称一并盖住（受控的有意遮挡，防误触）。
    // ⚠️ 该值"仅测一次、固化成常量"（用户要求），不要再动态算 —— a11y 侧不读节点几何。
    //
    // ⚠️ 展开态**会盖住下方一排应用图标及名称**，这是**受控的有意行为**（用户已确认）：
    // 卡片整幅白底（CardRenderer#draw 的 `c.drawColor(0xFFFFFFFF)`）把新暴露区域涂白，
    // 观感就是"卡片向下展开、把下面那排盖住"。收起即恢复。

    /** 展开态底边（屏幕坐标）。见上面的量测说明 */
    public static final int CARD_BOTTOM_EXPANDED = 750;

    /** 展开态卡片高度 = 750 − 70 = 680 */
    public static int cardHeightExpanded() {
        return CARD_BOTTOM_EXPANDED - CARD_TOP;
    }

    /**
     * 「⟳ 刷新 + 更新于 HH:MM」触摸块左沿（**屏幕**坐标）—— 点它手动刷新。
     *
     * <p>🔴 2026-10-10 第二轮：它由"卡片最左"**右移**到「⇄ + 二字标题」窗的右侧
     *（用户拍板的目标排布 `⇄ 本周 ⟳更新于 14:32 ········ [打开墨台]`）⇒
     * 左沿 = 卡片左沿 + {@link #TITLE_TAP_W}（标题窗宽）+ {@link #HEAD_TAP_GAP}。
     */
    public static int updTapLeft() {
        return CARD_LEFT + TITLE_TAP_W + HEAD_TAP_GAP;
    }

    public static int updTapTop() {
        return CARD_TOP + UPD_TAP_TOP_OFFSET;
    }

    // ─────────────────────────────────────────────────────────────
    // 以下为 v0.3.3「本月」新增。**上面的常量一个都没动**（它们的值是与桌面图标
    // 逐像素对齐过的，改一个就会破坏对齐）。
    // 这里的坐标分两类：
    //   · 带 window 语义的（TITLE_TAP_*）——屏幕绝对坐标，给 WindowManager 用；
    //   · 其余 —— 卡片 View **内部**坐标（左上角为 0,0，尺寸 368×346）。
    // ─────────────────────────────────────────────────────────────

    // ── 「打开墨台」按钮（🆕 2026-10-10 起：**卡片右上角**，抬头行最右）──
    //
    // 演进：TASK-080（2026-10-09）放在**左上角**；用户 2026-10-10 拍板**搬到右上角**，
    // 腾出来的左上角改放「⇄ 形态 + ⟳ 刷新 + 更新于」。新抬头行 =
    //     ⇄ 本周 ⟳更新于 14:32 ······················ [打开墨台]
    //（🔴 2026-10-10 第二轮：左上簇里「⇄ 形态」与「⟳ 刷新」也互换过一次位次 —— 见上面那组常量）
    //
    // 与前两个按钮位（右下 openBox / 左下 prevBox）的关键区别：**它不随卡片高度变化**
    // —— 抬头行永远在卡片上沿，所以位置是**固定值**，不像 openBoxTop(h) 那样要跟展开态下移。
    //
    // 尺寸：字号 = 抬头字号 × 0.66（19 × 1.2 × 0.66 ≈ 15px），四字「打开墨台」≈ 60px
    // + 左右各 ≈8px 内衬 ⇒ **76**。高度 24，与图标行（icon ≈ 14px）**垂直居中**
    //（见 CardRenderer#drawHeader：`iconTop + (icon − DESK_BOX_H)/2`，两边同源）。
    //
    // 🔴 触摸窗比画出来的框**大一圈**（见下面的 DESK_TOUCH_*）—— 与「更新于」那一块
    //    （文字 ≈20px 却开 34px 高的窗）同一条理由：墨水屏上手指按得准。

    /** 「打开墨台」按钮宽度（绘制与触摸窗共用） */
    public static final int DESK_BOX_W = 76;
    /** 「打开墨台」按钮高度 */
    public static final int DESK_BOX_H = 24;
    /** 按钮右沿距卡片右沿的内缩（与抬头右端同一条竖线 ⇒ 0） */
    public static final int DESK_BOX_MARGIN_R = 0;

    /** 触摸窗宽度（比画出来的框 +4，手指按得准） */
    public static final int DESK_TOUCH_W = DESK_BOX_W + 4;
    /** 触摸窗高度（同上，比 24 高的画框大一圈） */
    public static final int DESK_TOUCH_H = 30;
    /**
     * 触摸窗顶边相对卡片顶边的偏移 —— 罩住画出来的框（view 坐标 ≈[21.6, 45.6] ⇒ 窗 [18, 48]）。
     * 与 {@link #TITLE_TAP_TOP_OFFSET}（14）同量级；两窗**只要求横向不重叠**，纵向可以嵌套。
     */
    public static final int DESK_TOUCH_TOP_OFFSET = 18;

    /** 按钮左边界（**屏幕**坐标，给 WindowManager 用）—— **右对齐**（用户 2026-10-10 拍板搬右上） */
    public static int deskBoxLeft() {
        return CARD_RIGHT - DESK_BOX_W - DESK_BOX_MARGIN_R;
    }

    /** 按钮触摸窗左边界（**屏幕**坐标）—— 比画框宽 4px */
    public static int deskTouchLeft() {
        return CARD_RIGHT - DESK_TOUCH_W;
    }

    /** 按钮触摸窗上边界（**屏幕**坐标，给 WindowManager 用） */
    public static int deskTouchTop() {
        return CARD_TOP + DESK_TOUCH_TOP_OFFSET;
    }

    /**
     * 卡片左上角「⇄ 切形态 + 二字标题」触摸窗：**短按** = 切换形态（本周 → 本月 → 本书 → 本记），
     * **长按** = 弹隐藏时长菜单（用户 2026-10-10 明确：长按行为**不做修改**）。
     *
     * <p>🔴 2026-10-10 **第二轮**（用户拍板「换一下切换形态和刷新的位置」）：本窗由"刷新窗右侧"
     * **左移回卡片最左** ⇒ 左沿 = 卡片左沿（{@link #TITLE_TAP_LEFT_OFFSET} = 0）；
     *   · 覆盖范围（实测口径）：⇄ 图标 [74,89] + 二字标题 [92,138] ⇒ 88 宽足够罩住；
     *   · 右沿 = 56 + 88 = 144 <「⟳ + 更新于」窗左沿 146（{@link #updTapLeft()}）
     *     ⇒ **两窗不重叠**（重叠会让后 add 的窗盖住彼此、点击落到错误动作上）；
     *   · 与右上「打开墨台」窗 [344,424] 之间留 200px 空档（就是那一串点）。
     *
     * <p>⚠️ 历史（值语义的来源，改宽度前务必读）：旧宽 170 是**实测教训**值（2026-09-22 13:11，
     * 图墨桌面"反复点切换卡片消失"的根因）—— 窗没罩住全部标题文字时，点击会**穿透**给桌面
     * 小组件容器（FrameLayout），触发 iconGate 让位且在图墨上没有可靠恢复。
     * 🆕 TASK-080 起标题**二字化**（`nav_*`）⇒ 需要罩住的横向范围大幅缩短，故可安全收窄
     * （第一版 80 因"窗起点在卡片左沿、而文字带 padX 内衬"不够宽 ⇒ 本轮放宽到 88）。
     */
    public static final int TITLE_TAP_W = 88;
    public static final int TITLE_TAP_H = 34;
    /** 触摸窗顶边相对卡片顶边的偏移（窗口内坐标） */
    public static final int TITLE_TAP_TOP_OFFSET = 14;
    /** 触摸窗左沿相对卡片左沿的偏移（**贴卡片左沿** ⇒ 0；其右才是刷新窗） */
    public static final int TITLE_TAP_LEFT_OFFSET = 0;

    public static int titleTapTop() {
        return CARD_TOP + TITLE_TAP_TOP_OFFSET;
    }

    /** 触摸窗左边界（**屏幕**坐标，给 WindowManager 用）—— 卡片最左 */
    public static int titleTapLeft() {
        return CARD_LEFT + TITLE_TAP_LEFT_OFFSET;
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

    // ── 左下角按钮位（v0.4.2「上一条」 / 🆕 TASK-071「选书」）：与右下角**左右对称** ──
    //
    // 用户拍板：卡片左下角一个独立按钮，和右下角那个对称。
    // 🔴 这一格**两个形态共用**（互斥，永不同时出现）：
    //    · 本记态 = 「上一条」（v0.4.2）；
    //    · 本书态 = 「选书」（🆕 TASK-071，用户拍板"入口唯一化到左下角"）。
    //    周/月/待办三态不画按钮、也不开触摸窗（左下角让给桌面手势）。
    // 尺寸复用 OPEN_BOX_W/H；只有横坐标不同，所以对齐逻辑仍是"改几何只改本文件"。
    // 🔴 命名保留 v0.4.2 的 `prev*` —— 与右下角 `openView`（本书=打开 / 本记=换一条）同款：
    //    按**主要角色**命名，不为"一格两角色"再改一轮名。但它是这一格**唯一的权威定义**：
    //    绘制侧（卡内矩形）与桌面那个透明小窗（屏幕坐标）都取它，**不许另起一份**。

    /** 左下按钮位距卡片左沿的内缩（与 {@link #OPEN_BOX_MARGIN_R} 对称） */
    public static final int PREV_BOX_MARGIN_L = 2;

    /** 框左边界（**屏幕**坐标，给 WindowManager 用） */
    public static int prevBoxLeft() {
        return CARD_LEFT + PREV_BOX_MARGIN_L;
    }

    /** 框上边界（**屏幕**坐标）—— 与「换一条 / 打开」同一条水平线（收起态） */
    public static int prevBoxTop() {
        return openBoxTop(cardHeight());
    }

    /** 同上，高度由调用方给（TASK-017 展开态）—— 与 {@link #openBoxTop(int)} 同源 */
    public static int prevBoxTop(int h) {
        return openBoxTop(h);
    }

    // ── 🆕 TASK-071「选书」框（只在**本书**形态出现）：卡片的**左下角** ──
    //
    // 用户 2026-10-07 拍板：「本书的选书放在卡片的左下角，和「打开」同一行」。
    // 于是它与右下角的「打开」构成**左右对称**的一对 —— 尺寸、水平线全部复用
    // OPEN_BOX_* / openBoxTop()，只有横坐标不同（贴左沿）。
    //
    // 🔴 **不在这里另起一组常量** —— 它是**同一个按钮位**：
    //    本记态左边是「上一条」、本书态左边是「选书」，两形态互斥、永不同时出现。
    //    ⇒ 位置一律取上面那组 `PREV_BOX_MARGIN_L` / `prevBoxLeft()` / `prevBoxTop()`
    //      （绘制侧算卡内矩形、桌面透明小窗算屏幕坐标，**共用一个定义** ⇒ 天然重合，
    //       不会出现"点在框上没反应"）。另起一组只会多出一份会分叉的坐标。
}
