package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.inkread.weekread.R;
import com.inkread.weekread.core.Bill;
import com.inkread.weekread.core.BillMoney;
import com.inkread.weekread.core.BillStore;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.MenuPrefs;
import com.inkread.weekread.core.MenuPrice;
import com.inkread.weekread.core.PagePrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.ui.InkTheme;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 🆕 TASK-077：墨台「阅读账单」分区 —— **两菜单**（摘录菜单〔默认〕/ 读书菜单）。
 *
 * <h3>一份数据、两个视图</h3>
 * 两个菜单**共用同一份 {@link Bill}**（{@link BillStore#latest}）与同一套尺（{@code unit}），
 * 顶部一枚**自绘页签条**切换视图 —— 切视图**零额外请求**（定稿设计 §6.2）。
 *
 * <h3>🔴 渲染永不发请求（项目铁律）</h3>
 * 本类只读 {@link BillStore}（账单）与 {@code MenuPrefs}（配置）/ {@code NoteStore}（不读，条数已随账单落盘）。
 * **热门划线 / 统计 / 进度全部在 {@link com.inkread.weekread.core.BillScheduler} 的生成期预热**，
 * 渲染只消费已落盘的快照。
 *
 * <h3>为什么不用 {@code ui/SegTabView}</h3>
 * 墨台容器（{@link DeskPageView}）是**纯 Canvas 自绘**，塞不进一个真的 {@code View}
 * ⇒ 这里按 {@code SegTabView} 的视觉语言（居中双段 / 选中加粗 + 底部黑条 / 段间竖分隔线 /
 * 整条下边框）**用 Canvas 重画一份**，字号与字族仍走 {@code InkTheme}（墨水屏端衬线）。
 *
 * <h3>排版（定稿设计 §6.3 / §6.4；🆕 2026-10-10 三改头部 · 诉求 ①）</h3>
 * <pre>
 * 墨单                        [摘录菜单 | 读书菜单]  生成壁纸    ← 分区抬头（⑤；① 页签上提、去框）
 * [ 周 | 月 ]                        ‹  2026-W40  ›            ← 周期条（⑥；① 降到第二行）
 * 2026-W40                                    墨单            ← 票据头（⑤ 对调：左=单号、右=墨单）
 * 周结 · 09.28–10.04 · 5 本 · 16h53m
 * ────────────────────────────
 *  品类      主厨      价格
 *   ────────────────────────────              ────────────────────────────
 *    品类      主厨      价格                   NO.01  《惊悚乐园》
 *   ────────────────────────────                三天两觉 · 7h24m · 15%
 *    NO.01  夜里老鼠们要睡觉                     备注：半夜看会做噩梦 →
 *           沃尔夫冈·博尔…  ¥34
 *           咖啡的味道说不清
 *   ────────────────────────────              ────────────────────────────
 *   整单备注：…                                 整单备注：…
 *   账单合计：¥79
 *   ▮▮▎▮▎▮▮▎▮  墨单 · 2026-W40
 * </pre>
 * 两菜单差异：**摘录菜单显示价（⌈分钟/10⌉）与合计、不出进度行；读书菜单不显示价格**、
 * 以书名/作者/备注为主体（§6.3 / §6.4）。
 */
final class BillSection implements InsightRenderer.Section {

    // ── 尺（与洞察页正文同档，保证"同一 App 同一观感"）──
    //    🔴 2026-10-12（第七批 W2 / task-66 ⑤④ 方案 B）：`SZ_BODY` / `SZ_SMALL` / `LH` 由 `private`
    //    **放宽为包内可见** —— 同包的海报 `feature/BillWallpaper.java` 按用户拍板「与墨台同尺」
    //    直接复用这三条（**值一字未改**），两个表面从此共用同一份尺、不可能各自漂移。
    //    🔴 同日追加（Lead 裁定：抬头族**按「与墨台同名常量逐一对齐」**，不用 ×0.682 的整页比例）：
    //    `SZ_TITLE` / `SZ_SERIAL` 一并放宽为包内可见（**值一字未改**：18f / 12f），供海报抬头族
    //    与落款复用 ⇒ 海报侧不再留任何"自带数字"的字号。
    static final float SZ_TITLE  = 18f;   // 账单标题（同包 `BillWallpaper` 复用：抬头标题）
    static final float SZ_SERIAL = 12f;   // 单号（同包 `BillWallpaper` 复用：抬头单号）
    static final float SZ_BODY   = 15f;   // 正文（同包 `BillWallpaper` 复用）
    static final float SZ_SMALL  = 12f;   // 作者 / meta / 提示（同包 `BillWallpaper` 复用）
    /** 行距倍率（×字号）。 */
    static final float LH = 1.7f;         // 同包 `BillWallpaper` 复用

    private static final int INK   = 0xFF000000;
    private static final int GRAY  = 0xFF3C3C3C;
    /**
     * 🆕 task-39（用户第四批 #1「提示类浅灰提亮」）：**提示小字 / 禁用态**色。
     *
     * <p>旧值 `#A8A8A8`（168）白底只有 **2.38:1** —— task-29 已把**页签未选中项**换成
     * {@link InkTheme#INK2}（见 {@link #TAB_OFF}），但**提示小字与禁用态当时未动**
     * （用户 #9「小字不得模糊」只落了一半）。本轮把本常量整体换成**同一个既有 token**
     * `InkTheme.INK2`（`#6B6459`）：白底 **5.85:1**、纸白 `#F7F3EA` 5.28:1、纸 2
     * `#EFE9DC` 4.83:1 ⇒ 三种真实底色全部 ≥ AA 4.5:1；禁用态也比底线 `#767676`
     * （4.54:1）更深 ⇒ **两种角色同时达标**，且**不新造灰、不引第二套灰阶**。
     *
     * <p>覆盖处（全部经本常量）：`:869` 周期 chip 的 `avail=false`、`:966` 左右周期箭头禁用态、
     * `:1112` 手动录价提示、`:1229` 本地条数提示。
     * （🆕 task-40 起：原「每日条归一提示」与「月历侧」两处随月历一并摘除。）
     *
     * <p>🔴 只换颜色：字号 / 字族 / 字重 / 位置 / 行高一律不动；
     * `feature/BillWallpaper.java` 的同名常量**必须同值同步**（A11：海报与墨单同形制）。
     */
    private static final int LIGHT = InkTheme.INK2;
    private static final int LINE  = 0xFFD8D8D8;

    /**
     * 🆕 task-29：页签**未选中**色。
     *
     * <p>旧值用的就是上面的 {@link #LIGHT}（`#A8A8A8`，168）—— 用户 #9「小字不得浅灰」点名的
     * 「摘录菜单 / 读书菜单」未选中项。2026-10-11 真机取证（`T27a`，`x289-348` = 「读书菜单」）：
     * 墨迹最暗 = **168**，与源码常量**精确吻合**；页签行**底色实测 = `#FFFFFF` 纯白（100% 采样点）**
     * ⇒ 对比度 **2.38:1**，远低于 WCAG AA 正文 4.5:1。
     *
     * <p>改为复用**既有 token** `InkTheme.INK2`（`#6B6459`，不是自造新灰）：
     * 白底 **5.85:1**、纸白底 `#F7F3EA` 5.28:1、纸 2 底 `#EFE9DC` 4.83:1 ⇒ **三种真实底色全部达标**。
     * <p>🔴 只换颜色：字族（`InkTheme.serif()`）/ 字号（`12.5f × unit = 15.0px`）/ 字重（未选中仍常规、
     * 选中才 `setFakeBoldText(true)`）/ 位置 / 下划线**一律不动**；选中态仍是纯黑 + 下划线 ⇒ 状态可辨
     * （`INK` 0 vs `INK2` 约 107 灰阶，约 3.9:1）。
     */
    private static final int TAB_OFF = InkTheme.INK2;

    /** 空态文案（无任何账单）。 */
    private static final String EMPTY = "本期暂无账单";
    /** 占位文案（Q10：本期无阅读记录 —— 🔴 绝不产 0 值空白账单）。 */
    private static final String PLACEHOLDER = "本期无阅读记录";

    /** 顶部页签条占分区宽的比例（2 项）。🔴 原「3 项档 0.56」随月历摘除（task-40）。 */
    private static final float TAB_W_RATIO   = 0.44f;
    /** 页签条与右端「生成壁纸」之间的间隔（×unit）。 */
    private static final float HEAD_TAB_GAP_UNITS = 8f;

    // ── 🆕 2026-10-10：头部第二行「周期条」的尺（画与命中共用同一批常量）──
    /** 周期条占高（×unit）。 */
    private static final float PERIOD_BAR_UNITS = 2.95f;
    /** 周期条内小控件高（×unit）。 */
    private static final float CHIP_H_UNITS     = 1.95f;
    /** `[周|月]` 双段总宽（×unit）。 */
    private static final float MODE_W_UNITS     = 78f;
    /** `◀ … ▶` 导航区总宽（×unit）。 */
    private static final float NAV_W_UNITS      = 112f;
    /** `◀` / `▶` 各自的可点宽（×unit）。 */
    private static final float ARROW_W_UNITS    = 26f;
    /** 「生成壁纸」标签宽 / 高（×unit）—— 🆕 诉求 ① 起**无按键框**，与页签同行、同字族同字号。
     *  宽度从 80 收窄到 62：字被「去掉内衬」后不需要原来的框内留白（4 字 × 12.5unit ≈ 74px @S4）。 */
    private static final float WP_W_UNITS       = 62f;
    private static final float WP_H_UNITS       = 1.85f;

    // ── 🆕 TASK-086：价格两列 / 收入算法控件（数字键盘的尺已随 task-66 抽到 `PricePad`） ──
    /**
     * 「收入」列宽（×unit）—— 右对齐在分区右内边距；「原价」列落在它**左侧**同等宽度处。
     *
     * <p>🆕 task-66：改包内可见 —— {@code AssetSection} 的「长按价格区」用它算**书名区/价格区的
     * 分界**（`right − 2·PRICE_COL_W·unit`）。两处必须是**同一条列宽**，故只留这一份定义。
     */
    static final float PRICE_COL_W      = 62f;
    /** 「收入算法：增额 ▾」整块宽（×unit）。 */
    private static final float ALGO_W_UNITS     = 118f;
    /** 周期条里 `‹ 单号 ›` 与 `[周|月]` 之间的间隔（×unit）。 */
    private static final float NAV_GAP_UNITS    = 10f;

    // ── 行的种类 ──
    private static final int K_SEP  = 0;   // 1px 分隔线
    private static final int K_LINE = 1;   // 单行文字（可两端对齐 / 居中）
    private static final int K_WRAP = 2;   // 折行文字（左对齐，可封顶 + 省略号）
    private static final int K_BARS = 3;   // 装饰条码行
    /** 🆕 task-39（#6c）：**双线行**（合计行上方，`docs/13:238`）。行高 = 两条 1px + 本间隔。 */
    private static final int K_DSEP = 5;
    /** 双线之间的间隔（×unit）—— `2.5×unit = 3.0px` @480×800（unit=1.2），照 `docs/13:238` 的 3px。 */
    private static final float DSEP_GAP_UNITS = 2.5f;

    private final Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Context ctx;
    private Bill bill;                       // null = 库里没有任何账单
    private int view = MenuPrefs.VIEW_EXCERPT;

    // ── 🆕 TASK-086：两个**瞬时态**（都不落盘 —— 弹层不是配置） ──
    /** 收入算法下拉框是否展开。 */
    private boolean algoOpen;
    /**
     * 🆕 手动录价数字键盘。task-66 起它是**共享实现**（{@link PricePad}，墨单与「阅读资产」共用一份，
     * 起因：用户第七批要求资产表也能「长按价格区 ⇒ 手填原价」，自绘键盘只能有一份）。
     * 打开状态/输入串都在它内部；本类只负责「命中墨单的哪一行」与「落盘后重建行」。
     */
    private final PricePad pad = new PricePad();

    private List<Row> rows;                  // 逻辑行（refresh 时失效重建）
    private List<Row> laidRows;              // 折行后的最终行
    private float cw = -1f, cu = -1f;        // 折行缓存键

    // ══════════════════════ 对外 ══════════════════════

    public String title() { return PagePrefs.moduleName(PagePrefs.MOD_BILL); }

    /**
     * 重装料 —— **每次墨台呼出时调一次**（`DeskPageView.buildSections`）。
     * 🔴 只读本地：{@link BillStore#latest} + {@link MenuPrefs}，**零网络**。
     */
    void refresh(Context c) {
        this.ctx = c.getApplicationContext();
        this.view = MenuPrefs.view(c);
        this.bill = pickBill();
        this.algoOpen = false;          // 🆕 TASK-086：弹层是瞬时态，每次呼出都归零
        this.pad.close();
        invalidateRows();
    }

    /**
     * 决定这次显示哪一期（🆕 2026-10-10 起带"用户选过的周期"）：
     * <pre>
     *   用户选过 (mode, start) 且那一期还在库 ⇒ 用它
     *   否则                                   ⇒ 回落 `BillStore.latest`（= 改动前的老行为）
     * </pre>
     * 🔴 **缺省零差异**：没点过周期导航的老用户，走的就是第二条分支 ⇒ 与改造前逐字节一致。
     */
    private Bill pickBill() {
        String m = MenuPrefs.periodMode(ctx);
        long s = MenuPrefs.periodStart(ctx);
        if (m.length() > 0 && s > 0L) {
            Bill b = BillStore.load(ctx, m, s);
            if (b != null) return b;
        }
        return BillStore.latest(ctx);
    }

    // ══════════════════════ 🆕 2026-10-10：周期切换 / 导航（用户诉求 ⑥） ══════════════════════

    /** 现在显示的是哪一种粒度（无账 ⇒ 按周算，画面与"空态"一致）。 */
    String shownMode() { return (bill == null) ? null : bill.mode; }

    /** 现在显示的是哪一期起点（秒）；无账 ⇒ 0。 */
    long shownStart() { return (bill == null) ? 0L : bill.periodStart; }

    /** 该粒度**有没有已生成的期**（决定 `[周|月]` 那一段可不可点）。 */
    boolean hasMode(String mode) {
        return ctx != null && !BillStore.startsOf(ctx, mode).isEmpty();
    }

    /** 当前周期在 `startsOf` 里的序号（0 = 最新）；-1 = 不在列表里（老账被轮转淘汰）。 */
    private int periodIndex() {
        if (ctx == null || bill == null) return -1;
        List<Long> starts = BillStore.startsOf(ctx, bill.mode);
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i) == bill.periodStart) return i;
        }
        return -1;
    }

    /**
     * 翻到上一期 / 下一期。
     *
     * @param dir {@code -1} = 更老的一期、{@code +1} = 更新的一期
     * @return 是否真的换了（到边界 / 列表里找不到 ⇒ false，调用方不重画）
     */
    boolean shiftPeriod(int dir) {
        if (ctx == null || bill == null || dir == 0) return false;
        // 🔴 `starts` 是**降序**（`BillStore.startsOf` 用 `Collections.reverseOrder()`）⇒ 索引 0 = 最新、
        //    索引越大越老 ⇒「更老的一期」= 索引 **+1**，与 `dir` 的符号**相反**。
        //    ⚠️ 2026-10-10 上机实测踩到：原写法 `idx + dir` 会让 `‹`（dir=-1）在最前一期上算出
        //    `to = -1` ⇒ 直接 return false（连点 4 次**毫无反应**），而 `›`（dir=+1）反而翻到**更老**
        //    的一期（2026-09 → 2026-08）—— 两个箭头方向整体反了。
        //    端点置灰逻辑（`drawArrow`：左 `idx < n-1`、右 `idx > 0`）本来就按"左=更老 / 右=更新"
        //    写的 ⇒ 把这里的索引方向翻译对，画与命中语义即一致。
        List<Long> starts = BillStore.startsOf(ctx, bill.mode);
        int idx = periodIndex();
        if (idx < 0) return false;
        int to = (dir < 0) ? idx + 1 : idx - 1;
        if (to < 0 || to >= starts.size()) return false;
        return applySelection(bill.mode, starts.get(to));
    }

    /**
     * 切粒度（周 ⇄ 月）。
     *
     * <p>🔴 切过去落在**该粒度最新一期**（不是"把当前期起点换算成另一种粒度"）——
     * 周与月的起点根本不是同一套刻度，硬换会落到一个不存在的期。
     *
     * @return 是否真的换了（本来就是该粒度 / 该粒度一期都没有 ⇒ false）
     */
    boolean switchMode(String mode) {
        if (ctx == null || bill == null) return false;
        final String m = PeriodRange.MONTHLY.equals(mode) ? PeriodRange.MONTHLY : PeriodRange.WEEKLY;
        if (m.equals(bill.mode)) return false;
        List<Long> starts = BillStore.startsOf(ctx, m);
        if (starts.isEmpty()) return false;
        return applySelection(m, starts.get(0));
    }

    /** 落盘选择 + 换料 + 归一视图 + 失效折行缓存。 */
    private boolean applySelection(String mode, long start) {
        Bill nb = BillStore.load(ctx, mode, start);
        if (nb == null) return false;                  // 键在但读坏了 ⇒ 当没换（绝不显示半成品）
        MenuPrefs.setPeriodSel(ctx, mode, start);
        bill = nb;
        invalidateRows();
        return true;
    }

    // ── 命中（画与命中同一份几何）──

    /** 触点是否落在「生成壁纸」上（无账 ⇒ 永远 false —— 那时按钮根本没画）。 */
    boolean hitWallpaper(float x, float y, float w, float unit, float top) {
        if (ctx == null || bill == null) return false;
        RectF r = wallpaperBtn(w, unit, top);
        float m = unit * 6f;
        return x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m;
    }

    /** 触点落在 `[周|月]` 的哪一段；返回 {@link PeriodRange#WEEKLY}/{@link PeriodRange#MONTHLY}，未命中 ⇒ null。 */
    String hitMode(float x, float y, float w, float unit, float top) {
        if (ctx == null) return null;
        RectF r = modeStrip(w, unit, top);
        float m = unit * 6f;
        if (x < r.left - m || x > r.right + m || y < r.top - m || y > r.bottom + m) return null;
        return (x < r.centerX()) ? PeriodRange.WEEKLY : PeriodRange.MONTHLY;
    }

    /** 触点落在 `‹` / `›` 上；返回 -1 / +1，未命中 ⇒ 0。 */
    int hitArrow(float x, float y, float w, float unit, float top) {
        if (ctx == null || bill == null) return 0;
        RectF r = navStrip(w, unit, top);
        float m = unit * 8f;
        if (y < r.top - m || y > r.bottom + m) return 0;
        float aw = ARROW_W_UNITS * unit;
        if (x >= r.left - m && x <= r.left + aw) return -1;
        if (x >= r.right - aw && x <= r.right + m) return +1;
        return 0;
    }

    /** 当前视图序号（0 = 摘录菜单 / 1 = 读书菜单）。 */
    int view() { return view; }

    /**
     * 当前账单**可显示**的视图集合：摘录菜单 / 读书菜单（🆕 task-40：月历已下架）。
     * 页签绘制、命中、循环切换都以它为准。
     */
    int[] visibleViews() {
        return new int[] { MenuPrefs.VIEW_EXCERPT, MenuPrefs.VIEW_READING };
    }

    /** 切到指定视图（写回 {@link MenuPrefs}，跨"呼出/关闭"记住）。 */
    void setView(Context c, int v) {
        if (v == view) return;
        view = v;
        MenuPrefs.setView(c, v);
        invalidateRows();
    }

    private void invalidateRows() {
        rows = null;
        laidRows = null;
        cw = -1f;
        cu = -1f;
    }

    /**
     * 顶部页签条的几何（画与命中共用同一份 ⇒ 不会"看起来能点、其实点不中"）。
     *
     * <p>🆕 <b>诉求 ①（2026-10-10 第三轮）</b>：页签条上提到**分区标题那一行**，并整体左移，
     * 给右端**无边框**的「生成壁纸」让出位置（两者同一行、同一垂直中心）。
     * 层级上「看哪个菜单」高于「看哪一期」⇒ 页签在周期条**之上**。
     * <p>🔴 历史：旧版页签独占第三行，是因为"周月 + 页签 + 单号导航"三簇在 480px 上塞不下；
     * 现在页签与「生成壁纸」都成了纯文字（去掉了 1px 框与框内留白）⇒ 一行放得下。
     */
    RectF tabStrip(float w, float unit, float top) {
        float right = w - w * InsightRenderer.PAD_X_RATIO
                - WP_W_UNITS * unit - HEAD_TAB_GAP_UNITS * unit;
        float ratio = TAB_W_RATIO;
        float sw = w * ratio;
        float sh = headRowH(unit);
        float cy = headRowCy(unit, top);
        return new RectF(right - sw, cy - sh / 2f, right, cy + sh / 2f);
    }

    // ══════════════════════ 🆕 2026-10-10：头部几何（第 1 行 + 周期条） ══════════════════════

    /** 头部第一行（标题行）的**垂直中心** —— 标题 / 页签 / 「生成壁纸」三者共用它 ⇒ 严格同一行。 */
    private static float headRowCy(float unit, float top) {
        return top + InsightRenderer.secHeadH(unit) * 0.52f;
    }

    /** 页签条高（= 标题行高 × 0.80，留在标题行内，不会蹭到第二行的周期条）。 */
    private static float headRowH(float unit) {
        return InsightRenderer.secHeadH(unit) * 0.80f;
    }

    /** 周期条占高（px）。 */
    private float periodBarH(float unit) { return SZ_SMALL * unit * PERIOD_BAR_UNITS; }

    /** 头部总高（px）= 分区标题行 + 周期条 —— 正文从它之下起画。
     *  🔴 页签条**不再独占一行**（🆕 诉求 ① 已并入标题行）⇒ 比旧版少 ≈45px（@S4，unit=1.2）。 */
    private float headArea(float unit) {
        return InsightRenderer.secHeadH(unit) + periodBarH(unit);
    }

    /** 周期条内小控件的顶边 y。 */
    private float chipTop(float unit, float top) {
        float barTop = top + InsightRenderer.secHeadH(unit);
        return barTop + (periodBarH(unit) - SZ_SMALL * unit * CHIP_H_UNITS) / 2f;
    }

    /** `[周|月]` 双段的外框。 */
    private RectF modeStrip(float w, float unit, float top) {
        float left = w * InsightRenderer.PAD_X_RATIO;
        float h = SZ_SMALL * unit * CHIP_H_UNITS;
        float y0 = chipTop(unit, top);
        return new RectF(left, y0, left + MODE_W_UNITS * unit, y0 + h);
    }

    /**
     * `‹ 单号 ›` 的外框。
     *
     * <p>🆕 <b>TASK-086（用户 Q4）</b>：**周期选择向左靠齐** —— 紧邻 `[周|月]` 右缘，
     * 把右端整块让给「收入算法：增额 ▾」（那一簇才是"向右靠齐"的）。
     * 改前它右端对齐分区右内边距，两簇会在 480px 上撞在一起。
     */
    private RectF navStrip(float w, float unit, float top) {
        float left = modeStrip(w, unit, top).right + NAV_GAP_UNITS * unit;
        float h = SZ_SMALL * unit * CHIP_H_UNITS;
        float y0 = chipTop(unit, top);
        return new RectF(left, y0, left + NAV_W_UNITS * unit, y0 + h);
    }

    /** 🆕 TASK-086：`收入算法：增额 ▾` 的外框（**右端对齐**分区右内边距，与周期选择分列两端）。 */
    private RectF algoStrip(float w, float unit, float top) {
        float right = w - w * InsightRenderer.PAD_X_RATIO;
        float h = SZ_SMALL * unit * CHIP_H_UNITS;
        float y0 = chipTop(unit, top);
        return new RectF(right - ALGO_W_UNITS * unit, y0, right, y0 + h);
    }

    /** 下拉框第 {@code idx} 项的矩形（紧贴控件下沿；**画与命中共用**）。 */
    private RectF algoDropItem(float w, float unit, float top, int idx) {
        RectF a = algoStrip(w, unit, top);
        float h = SZ_SMALL * unit * CHIP_H_UNITS;
        float y0 = a.bottom + unit * 1.5f + idx * h;
        return new RectF(a.left, y0, a.right, y0 + h);
    }

    /**
     * 「生成壁纸」标签（在**分区标题那一行的最右端**）。
     *
     * <p>🆕 <b>诉求 ①</b>：删掉 1px 按键框、与页签同字族同字号 ⇒ 它读起来就是与
     * 「摘录菜单 / 读书菜单」同层级的**第四个文字项**（而不是一个"按钮"）。
     * 名字保留 `wallpaperBtn`：命中的仍是同一块区域（{@link #hitWallpaper} 吃它）。
     */
    private RectF wallpaperBtn(float w, float unit, float top) {
        float right = w - w * InsightRenderer.PAD_X_RATIO;
        float h = SZ_SMALL * unit * WP_H_UNITS;
        float cy = headRowCy(unit, top);
        return new RectF(right - WP_W_UNITS * unit, cy - h / 2f, right, cy + h / 2f);
    }

    /** 触点是否落在页签条上（命中区上下各放宽一点 —— 字小，手指点不准）。 */
    boolean hitTab(float x, float y, float w, float unit, float top) {
        RectF r = tabStrip(w, unit, top);
        float pad = unit * 8f;
        return x >= r.left - unit * 4f && x <= r.right && y >= r.top - pad && y <= r.bottom + pad;
    }

    // ══════════════════════ 🆕 TASK-086：收入算法下拉（自绘） ══════════════════════

    /** 命中码：没中（不消费）。 */
    static final int ALGO_MISS = -1;
    /** 命中码：点在控件本身 ⇒ 开合切换。 */
    static final int ALGO_TOGGLE = 3;
    /** 命中码：弹层开着、点在外面 ⇒ 收起（消费掉这次点击）。 */
    static final int ALGO_DISMISS = 4;

    /**
     * 收入算法控件 / 下拉框的命中。
     *
     * <p>🔴 **必须自绘**：墨台是单 Canvas 逐帧自绘的覆盖层（宿主是 Service 的
     * `TYPE_ACCESSIBILITY_OVERLAY` 窗），塞 `Spinner` 之类会破坏"整页一次重绘、无动画"的前提
     * —— 先例 = `ui/KeyPadView`（软锁 PIN 键盘就是这么做的）。
     *
     * @return {@code 0..2} = 选中第 i 个算法；{@link #ALGO_TOGGLE} / {@link #ALGO_DISMISS}；
     *         {@link #ALGO_MISS} = 没命中（调用方继续判别的命中区）
     */
    int hitAlgo(float x, float y, float w, float unit, float top) {
        if (ctx == null || bill == null) return ALGO_MISS;
        if (algoOpen) {
            for (int i = 0; i < MenuPrefs.PAID_CHOICES.length; i++) {
                if (algoDropItem(w, unit, top, i).contains(x, y)) return i;
            }
            // 弹层开着点时：面板以外一律"收起并吃掉"（防穿透到下面的条目 / 返回手势）
            return ALGO_DISMISS;
        }
        RectF r = algoStrip(w, unit, top);
        float pad = unit * 6f;
        if (x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad) {
            return ALGO_TOGGLE;
        }
        return ALGO_MISS;
    }

    boolean algoOpen() { return algoOpen; }

    void setAlgoOpen(boolean open) { this.algoOpen = open; }

    /** 选第 {@code idx} 项（落盘 + 收起）。@return 是否真的变了（决定要不要 remeasure）。 */
    boolean pickAlgo(int idx) {
        if (ctx == null) return false;
        String cur = MenuPrefs.paidAlgo(ctx);
        String next = MenuPrefs.paidAlgoAt(idx);
        algoOpen = false;
        if (next.equals(cur)) return false;
        MenuPrefs.setPaidAlgo(ctx, next);
        invalidateRows();                     // 表头列数 / 合计行文案都变 ⇒ 必须重建逻辑行
        return true;
    }

    /** 画下拉框（**最后画**，压在内容之上）。 */
    private void drawAlgoDrop(Canvas c, float w, float unit, float top, Paint p) {
        if (!algoOpen || ctx == null) return;
        final String cur = MenuPrefs.paidAlgo(ctx);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        RectF first = algoDropItem(w, unit, top, 0);
        RectF last = algoDropItem(w, unit, top, MenuPrefs.PAID_CHOICES.length - 1);
        c.drawRect(first.left, first.top, last.right, last.bottom, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0xFF000000);
        c.drawRect(first.left + 0.5f, first.top + 0.5f, last.right - 0.5f, last.bottom - 0.5f, p);

        p.setStyle(Paint.Style.FILL);
        p.setTypeface(InkTheme.serif());
        p.setTextSize(SZ_SMALL * unit * 1.02f);
        Paint.FontMetrics fm = p.getFontMetrics();
        for (int i = 0; i < MenuPrefs.PAID_CHOICES.length; i++) {
            RectF r = algoDropItem(w, unit, top, i);
            boolean on = MenuPrefs.PAID_CHOICES[i].equals(cur);
            p.setFakeBoldText(on);
            p.setColor(on ? 0xFF000000 : GRAY);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(MenuPrefs.PAID_LABELS[i], r.left + unit * 6f,
                    r.centerY() - (fm.ascent + fm.descent) / 2f, p);
            if (on) {
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText("·", r.right - unit * 6f,
                        r.centerY() - (fm.ascent + fm.descent) / 2f, p);
            }
            if (i < MenuPrefs.PAID_CHOICES.length - 1) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(LINE);
                c.drawRect(r.left, r.bottom - 1f, r.right, r.bottom, p);
            }
        }
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(false);
        p.setTypeface(null);
        p.setStyle(Paint.Style.FILL);
    }

    // ══════════════════════ 🆕 TASK-086：手动录价（长按无价行 ⇒ 自绘数字键盘） ══════════════════════

    boolean padOpen() { return pad.isOpen(); }

    /**
     * 打开键盘编辑第 {@code idx} 行。
     *
     * <p>🆕 **TASK-086 拍板①（2026-10-10）**：门从「只有 {@link BillMoney#UNKNOWN}」放宽到
     * 「{@code UNKNOWN} **或** {@code FREE}」。
     * <p>🔴 起因：真机上 `/readdata/detail` 对**自导入 / 网文**（pixiv 合集、36氪、极限返航…）
     * 也回 {@code free = 1} ⇒ 全部落 {@code FREE}，`UNKNOWN` 一条都没有 ⇒
     * Q7 的录价入口在真实数据下**没有可命中对象**，A9/A10 不可达（`验证记录/200` §3.3）。
     * <p>🆕 **收口 F2/G2（2026-10-10）**：门再改为判**有无真实市场价**（`marketPriceFen > 0`）——
     * 于是**已手填价的行也能再次长按改价**（body 里会回填现值）；详见方法内注释与
     * {@code 验证记录/203}。
     * <p>🔴 **原设计意图保留**：有**真实市场价**（{@code marketPriceFen > 0}）的行仍然不给键盘 ——
     * 免得用户改了价却看不出"改了哪一列"。
     */
    void openPad(int idx) {
        if (ctx == null || bill == null) return;
        if (idx < 0 || idx >= bill.items.size()) return;
        Bill.Item it = bill.items.get(idx);
        // 🆕 TASK-086 收口（F2/G2，2026-10-10）：门判**有无真实市场价**，不再判生效价。
        //   🔴 为什么必须改：`effFen()` 是**手填价优先**的合成值 ⇒ 一旦某行录过价（如 332），
        //      旧门 `effFen > 0` 就把这一行**永久锁死**，用户填错价之后无路可改
        //      （审查 `验证记录/202` G2：「已录价行不可再编辑」）。
        //   ⇒ 改用生成期从接口解析出来的真价 `marketPriceFen`（手填价**不影响**它）：
        //      免费行（0）/ 未知行（-1）/ **已手填价的免费或未知行**都开放；
        //      有真实市场价（> 0）的行照旧不给键盘 —— 原设计意图与拍板①都保住。
        if (it.marketPriceFen > 0) return;      // 🔴 有真实市场价 ⇒ 不开放（原设计意图保留）
        // 已录过就回填（便于改价），否则从空开始 —— 回填与"元"文本格式化都在 `PricePad` 内
        pad.open(it.bookId, it.title, MenuPrice.fen(ctx, it.bookId));
        algoOpen = false;
    }

    void closePad() {
        pad.close();
    }

    /**
     * 该点是不是落在「**可录价**书目行」上（长按录价的入口）。
     *
     * <p>🔴 只对**摘录菜单**且该行**没有真实市场价**（`marketPriceFen <= 0` ⇒ 未知 `—` **或**免费，
     * 含**已手填价**的那些行）开放 ——
     * 有真实市场价（`> 0`）的行长按不给键盘（避免"改了也算不出"的困惑）。
     * 🆕 拍板①：免费行同样开放（旧代码只认 `UNKNOWN` ⇒ 入口不可达）。
     * 🆕 收口 F2/G2：判据与 {@link #openPad} **同源**（`marketPriceFen > 0` 才锁）⇒
     * **已手填价的行仍可长按改价**；🔴 与 {@link #hasManualEntryRow()}（提示小字）
     * **有意不对称**：提示只表示"还有价没定"，录满即消失，但长按入口仍在（详见 203）。
     *
     * @return items 下标；{@code -1} = 不是
     */
    int hitPriceRow(float x, float y, float w, float unit, float top) {
        if (ctx == null || bill == null || bill.placeholder) return -1;
        if (view != MenuPrefs.VIEW_EXCERPT) return -1;
        float left = w * InsightRenderer.PAD_X_RATIO;
        float right = w - w * InsightRenderer.PAD_X_RATIO;
        if (x < left || x > right) return -1;
        float yy = top + headArea(unit);
        List<Row> ls = laid(w, unit);
        for (int k = 0; k < ls.size(); k++) {
            Row r = ls.get(k);
            float rh = r.h(unit);
            if (r.itemIdx >= 0 && y >= yy - unit * 3f && y <= yy + rh + unit * 3f
                    && r.itemIdx < bill.items.size()) {
                Bill.Item it = bill.items.get(r.itemIdx);
                // 🆕 F2/G2：与 `openPad` **同源**判据 —— 只有**真实市场价**才锁死。
                //   （旧代码判 `effFen <= 0` ⇒ 已手填价的行长按连候选都不给 ⇒ 无法改价）
                return (it.marketPriceFen > 0) ? -1 : r.itemIdx;
            }
            yy += rh;
        }
        return -1;
    }

    /**
     * 键盘命中：返回命中的键码（0–9 / {@link PricePad#PK_DOT} / {@link PricePad#PK_DEL} /
     * {@link PricePad#PK_OK} / {@link PricePad#PK_CANCEL}）；{@code -1} = 没中。
     *
     * <p>🆕 task-66：面板矩形与 12 键排布已抽到 {@link PricePad}（**画与命中共用同一份** ⇒
     * 不存在两套尺漂移的可能）。
     */
    int hitPad(float x, float y, float w, float vh, float unit) {
        return pad.hit(x, y, w, vh, unit);
    }

    /**
     * 按一个键。
     *
     * <p>🔴 **只有「确定」落盘**（{@link MenuPrice#set}，键 = `bookId`）；其余都是瞬时编辑。
     * <p>🆕 **收口 F3（2026-10-10）**：删空 + 点「确定」= **清除**该行手填价
     * （`MenuPrice.set(..., -1)` ⇒ `ed.remove(bookId)`），该行自然回落到「免费 / `—`」。
     * <p>🆕 **收口 F1（2026-10-10）**：落盘/清除后必须 {@link #invalidateRows()} ——
     * 行文本（`Row.c3` 原价、收入列、合计、以及表头下的提示小字）都是 `laid()` 期
     * **算好并缓存**的（缓存键 `cw/cu`），只 `invalidate()` 重画的是**旧**行数据。
     * <p>🆕 task-66：输入串编辑与「元 ⇒ 分」的解析都搬进 {@link PricePad}（**共享实现**），
     * 本方法只剩宿主的两个动作 —— 落盘 / 清除 + 重建行。
     */
    void padPress(int key) {
        if (ctx == null || bill == null) return;
        final int r = pad.press(key);
        if (r == PricePad.R_EDIT) return;
        if (r == PricePad.R_CANCEL) { pad.close(); return; }
        final String bid = pad.bookId();
        if (r == PricePad.R_CLEAR) {
            // 🆕 F3：空输入 + 「确定」= 清除该行手填价（回落到市场价口径：免费 / —）
            MenuPrice.set(ctx, bid, -1);
            CardDebug.noteV(ctx, "墨单清除手动价：bookId=" + bid);
        } else {
            final int fen = pad.resultFen();
            MenuPrice.set(ctx, bid, fen);
            CardDebug.noteV(ctx, "墨单手动价：bookId=" + bid + " ⇒ " + BillMoney.yuan(fen));
        }
        invalidateRows();            // 🆕 F1：同上（录价/改价/清除后当场重算）
        pad.close();
    }

    /**
     * 画数字键盘（**最后画**，压在内容之上）。
     *
     * <p>🆕 task-66：面板、12 键、标题行的绘制整体搬进 {@link PricePad}（**共享实现**，
     * 墨单与「阅读资产」同一份像素级几何）。这里只补两件宿主信息：标题文案（读资源
     * `R.string.menu_price_manual_title`）与书名。
     */
    private void drawPad(Canvas c, float w, float vh, float unit, Paint p) {
        if (!pad.isOpen() || ctx == null) return;
        pad.draw(c, w, vh, unit, p, ctx.getString(R.string.menu_price_manual_title), pad.title());
    }

    // ══════════════════════ 量高 / 画 ══════════════════════

    public float height(float w, float vh, float unit) {
        float head = headArea(unit);
        if (bill == null) return head + SZ_BODY * unit * 3.8f;
        List<Row> ls = laid(w, unit);
        float h = head;
        for (int i = 0; i < ls.size(); i++) h += ls.get(i).h(unit);
        return h + unit * 6f;
    }

    public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
        // ── 头部第 1 行：分区标题（「墨单」）+ 右端「页签 + 生成壁纸」；第 2 行：周期条 ──
        drawHeadArea(c, w, unit, top, p);

        float y = top + headArea(unit);
        float left = w * InsightRenderer.PAD_X_RATIO;
        float right = w - w * InsightRenderer.PAD_X_RATIO;

        if (bill == null) {
            InsightRenderer.drawCenteredIn(c, w, y, SZ_BODY * unit * 3.8f, unit, p, EMPTY);
            return;
        }
        List<Row> ls = laid(w, unit);
        for (int i = 0; i < ls.size(); i++) {
            Row r = ls.get(i);
            y += r.draw(c, left, right, y, unit, p);
        }

        // 🆕 TASK-086：两个**瞬时弹层**最后画（压在内容之上；都是自绘，不引任何 View）
        drawAlgoDrop(c, w, unit, top, p);
        drawPad(c, w, vh, unit, p);
    }

    // ══════════════════════ 🆕 2026-10-10：头部绘制 ══════════════════════

    /**
     * 头部**两行**的绘制（🆕 诉求 ① 重新分层：菜单页签高于周/月）：
     * <pre>
     *   墨单                            [摘录菜单 | 读书菜单]  生成壁纸   ← ① 标题行（三者同行）
     *   [ 周 | 月 ]                            ‹  2026-W40  ›             ← ② 周期条
     * </pre>
     * 三者在 480px 屏上的横向占位（unit = 1.2）：
     * <pre>
     *   标题 [24, 65]   页签 [103, 372]（3 项）/ [161, 372]（2 项）   生成壁纸 [382, 456]
     * </pre>
     * 🔴 旧版页签独占第三行（"周月 + 页签 + 单号导航"三簇塞不下）；诉求 ① 把页签提到标题行、
     * 与去框后的「生成壁纸」并排 ⇒ 头部由三行降为两行，且**消除了"菜单在周月之下"的层级错位**。
     */
    private void drawHeadArea(Canvas c, float w, float unit, float top, Paint p) {
        InsightRenderer.drawSectionHead(c, w, top, unit, p, title());
        drawTabs(c, w, unit, top, p);        // 页签条 + 右端「生成壁纸」（同一行）
        drawPeriodBar(c, w, unit, top, p);
    }

    /** 周期条：左 `[周|月]`、右 `‹ 单号 ›`（🔴 与 {@link #hitMode}/{@link #hitArrow} 共用同一份几何）。 */
    private void drawPeriodBar(Canvas c, float w, float unit, float top, Paint p) {
        if (ctx == null) return;                          // 防御：refresh 之前绝不画
        final String curMode = (bill == null) ? PeriodRange.WEEKLY : bill.mode;
        final String[] modes = { PeriodRange.WEEKLY, PeriodRange.MONTHLY };
        final String[] labs = { ctx.getString(R.string.menu_mode_week), ctx.getString(R.string.menu_mode_month) };

        // ① 左：[周|月] 双段（照 SegTabView 的语言：选中加粗 + 底部黑条；无饼圆角）
        RectF ms = modeStrip(w, unit, top);
        float slot = ms.width() / 2f;
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(InkTheme.serif());
        p.setFakeBoldText(false);
        p.setTextSize(SZ_SMALL * unit * 1.02f);
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = ms.centerY() - (fm.ascent + fm.descent) / 2f;
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 2; i++) {
            boolean on = modes[i].equals(curMode);
            boolean avail = hasMode(modes[i]);
            float cx = ms.left + slot * i + slot / 2f;
            p.setFakeBoldText(on);
            p.setColor(on ? INK : (avail ? GRAY : LIGHT));
            c.drawText(labs[i], cx, base, p);
            if (on) {
                p.setColor(INK);
                float bw = slot * 0.48f;
                c.drawRect(cx - bw / 2f, ms.bottom - Math.max(1.5f, unit * 1.7f),
                        cx + bw / 2f, ms.bottom, p);
            }
        }
        p.setFakeBoldText(false);
        p.setTypeface(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(LINE);
        c.drawRect(ms.left + 0.5f, ms.top + 0.5f, ms.right - 0.5f, ms.bottom - 0.5f, p);
        c.drawLine(ms.left + slot, ms.top + ms.height() * 0.24f,
                ms.left + slot, ms.bottom - ms.height() * 0.24f, p);
        p.setStyle(Paint.Style.FILL);

        // ② 右：‹ 单号 ›（没有账单 ⇒ 整条不画）
        if (bill == null) {
            p.setTextAlign(Paint.Align.LEFT);
            return;
        }
        RectF ns = navStrip(w, unit, top);
        final float aw = ARROW_W_UNITS * unit;
        int idx = periodIndex();
        int n = BillStore.startsOf(ctx, bill.mode).size();
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(SZ_SMALL * unit * 1.04f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(INK);
        Paint.FontMetrics nf = p.getFontMetrics();
        c.drawText(Bill.serialOf(bill.mode, bill.periodStart), ns.centerX(),
                ns.centerY() - (nf.ascent + nf.descent) / 2f, p);
        p.setTypeface(null);
        drawArrow(c, ns.left + aw / 2f, ns.centerY(), unit, true, idx >= 0 && idx < n - 1, p);
        drawArrow(c, ns.right - aw / 2f, ns.centerY(), unit, false, idx > 0, p);
        p.setTextAlign(Paint.Align.LEFT);

        // ③ 🆕 TASK-086（用户 Q4）：右端「收入算法：增额 ▾」——**右靠**，与左簇（周月 + 周期导航）分列两端
        drawAlgoChip(c, w, unit, top, p);
    }

    /**
     * 🆕 TASK-086：`收入算法：增额 ▾` 控件本体（下拉框由 {@link #drawAlgoDrop} 另画）。
     *
     * <p>🔴 只在**摘录菜单**出现 —— 读书菜单根本没有价格列（`TASK-077` §非目标），
     * 在那儿摆一个"收入算法"是无意义的控件。
     */
    private void drawAlgoChip(Canvas c, float w, float unit, float top, Paint p) {
        if (ctx == null || bill == null) return;
        if (view != MenuPrefs.VIEW_EXCERPT) return;
        RectF r = algoStrip(w, unit, top);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(InkTheme.serif());
        p.setFakeBoldText(false);
        p.setTextSize(SZ_SMALL * unit * 1.02f);
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = r.centerY() - (fm.ascent + fm.descent) / 2f;
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(GRAY);
        String pre = ctx.getString(R.string.menu_paid_prefix);
        c.drawText(pre, r.left, base, p);
        p.setColor(INK);
        p.setFakeBoldText(true);
        String lab = MenuPrefs.paidLabel(MenuPrefs.paidAlgo(ctx));
        c.drawText(lab, r.left + p.measureText(pre), base, p);
        p.setFakeBoldText(false);
        // 下拉箭头
        p.setTextSize(SZ_SMALL * unit * 0.9f);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("▾", r.right, base, p);
        // 下划线（与周月/页签同族的"可点"暗示；无按键框）
        p.setStyle(Paint.Style.FILL);
        p.setColor(LINE);
        c.drawRect(r.left, r.bottom - 1f, r.right, r.bottom, p);

        // 展开时控件高亮（黑底白字），与弹层形成"选中"闭环
        if (algoOpen) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(INK);
            c.drawRect(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, p);
            p.setStyle(Paint.Style.FILL);
        }
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(null);
    }

    /** 周期条两端的小箭头（`‹` / `›`）—— 到边界置灰（浅灰、明显不可点）。 */
    private void drawArrow(Canvas c, float cx, float cy, float unit, boolean left, boolean on, Paint p) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(null);
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(SZ_SMALL * unit * 1.5f);
        p.setColor(on ? INK : LIGHT);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(left ? "‹" : "›", cx, cy - (fm.ascent + fm.descent) / 2f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ══════════════════════ 页签条 ══════════════════════

    /**
     * 页签条（`摘录菜单 / 读书菜单`）+ 右端**无边框**的「生成壁纸」。
     *
     * <p>🆕 诉求 ①：① 页签上提到标题行（`tabStrip` 已把中心对齐到 {@code headRowCy}）；
     * ② 「生成壁纸」删掉按键框，改用**页签同一套**字族 / 字号 / 颜色（非选中色 = INK，
     * 因为它是可点动作、不是"未选中的状态"），紧贴页签条右侧
     * （间隔 {@link #HEAD_TAB_GAP_UNITS}）⇒ 三/四个文字项读起来同层级。
     */
    private void drawTabs(Canvas c, float w, float unit, float top, Paint p) {
        if (ctx == null) return;                       // 防御：refresh 之前绝不画
        RectF r = tabStrip(w, unit, top);
        final int[] vis = visibleViews();
        final String[] labels = MenuPrefs.VIEW_LABELS;
        int n = vis.length;
        float slot = r.width() / n;
        float sz = 12.5f * unit;

        p.setStyle(Paint.Style.FILL);
        p.setTextSize(sz);
        p.setTypeface(InkTheme.serif());          // 与墨水屏端 SegTabView 同族
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = r.centerY() - (fm.ascent + fm.descent) / 2f;
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            float cx = r.left + slot * i + slot / 2f;
            boolean on = (vis[i] == view);
            p.setFakeBoldText(on);
            p.setColor(on ? INK : TAB_OFF);          // 🆕 task-29：未选中 #A8A8A8 ⇒ INK2（2.38:1 ⇒ 5.85:1）
            c.drawText(labels[vis[i]], cx, base, p);
            if (on) {
                p.setColor(INK);
                float bw = slot * 0.42f;
                c.drawRect(cx - bw / 2f, r.bottom - Math.max(2f, unit * 2.4f), cx + bw / 2f, r.bottom, p);
            }
        }
        // 段间竖线 + 整条下边框（照 SegTabView 的视觉语言）
        p.setStyle(Paint.Style.STROKE);
        p.setColor(LINE);
        for (int i = 1; i < n; i++) {
            float x = r.left + slot * i;
            c.drawLine(x, r.top + r.height() * 0.24f, x, r.bottom - r.height() * 0.18f, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(LINE);
        c.drawRect(r.left, r.bottom - 1f, r.right, r.bottom, p);
        p.setTextAlign(Paint.Align.LEFT);

        // ── 右端「生成壁纸」：🆕 诉求 ① **无边框** + 页签同字族同字号（无账 ⇒ 不画、也不命中）──
        if (bill != null) {
            RectF wb = wallpaperBtn(w, unit, top);
            p.setStyle(Paint.Style.FILL);
            p.setFakeBoldText(false);
            p.setColor(INK);
            p.setTextSize(sz);
            // 字装不下就缩（下限 0.7×）—— 与卡片「打开墨台」同一个兜底
            String lab = ctx.getString(R.string.menu_gen_wallpaper);
            while (p.measureText(lab) > wb.width() && sz > 12.5f * unit * 0.7f) {
                sz -= 0.4f;
                p.setTextSize(sz);
            }
            Paint.FontMetrics wf = p.getFontMetrics();
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(lab, wb.centerX(), wb.centerY() - (wf.ascent + wf.descent) / 2f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        p.setFakeBoldText(false);
        p.setTypeface(null);
    }

    // ══════════════════════ 组行（逻辑行） ══════════════════════

    private List<Row> rows() {
        if (rows != null) return rows;
        List<Row> out = new ArrayList<Row>();
        rows = out;
        if (ctx == null || bill == null) return out;

        final boolean excerptMenu = (view == MenuPrefs.VIEW_EXCERPT);
        final int blocks = MenuPrefs.blocks(ctx);
        final boolean headOn  = (blocks & MenuPrefs.BLOCK_HEAD) != 0;
        final boolean totalOn = (blocks & MenuPrefs.BLOCK_TOTAL) != 0;
        final boolean noteOn  = (blocks & MenuPrefs.BLOCK_NOTE) != 0;
        final String footer = MenuPrefs.footer(ctx);
        final String unitPref = MenuPrefs.unit(ctx);
        final float scT = MenuPrefs.scaleOf(MenuPrefs.fsTitle(ctx));
        final float scB = MenuPrefs.scaleOf(MenuPrefs.fsBody(ctx));
        final float scS = MenuPrefs.scaleOf(MenuPrefs.fsSerial(ctx));
        final boolean tSerif = MenuPrefs.titleSerif(ctx);
        final boolean bMono  = MenuPrefs.bodyMono(ctx);

        final String billTitle = MenuPrefs.title(ctx);
        final String serial = Bill.serialOf(bill.mode, bill.periodStart);
        // 🆕 TASK-086：收入算法（渲染期只读一条 prefs）——`off` ⇒ 收入列整列不出、表头回落 3 列
        final String algo = MenuPrefs.paidAlgo(ctx);
        final boolean paidCol = !MenuPrefs.PAID_OFF.equals(algo);

        // ── ① 票据头：**单号（左） + 标题（右）** / 副行 / 分隔线 / 表头 / 分隔线 ──
        //    🆕 2026-10-10 用户拍板：与改造前**对调**（原为「墨单 … 2026-W40」）。
        if (headOn) {
            Row t = Row.line(serial, billTitle, SZ_TITLE * scT);
            t.bold = true;
            t.serif = tSerif;
            out.add(t);

            String sub = Bill.kindLabel(bill.mode) + " · " + Bill.rangeLabel(bill.mode, bill.periodStart)
                    + " · " + bill.bookCount() + " 本"
                    + (bill.placeholder ? "" : (" · " + dur(bill.totalSec, unitPref)));
            Row s2 = Row.line(sub, null, SZ_BODY * scB);
            s2.color = GRAY;
            s2.mono = bMono;
            out.add(s2);

            out.add(Row.sep());
            if (excerptMenu && !bill.placeholder) {
                // 表头沿用菜单隐喻（Q11）：🆕 TASK-086 ⇒ **4 列** `序号 │ 品类 │ 原价 │ 收入`
                //（收入列关着 ⇒ 回落 3 列 `序号 │ 品类 │ 原价`，列位与条目行严格对齐）
                // 🔴 2026-10-11（TASK-086 第六批B ⑥）：用户可见「收入」统一改叫**收入**（Java 标识符不动）。
                // 🔴 2026-10-12（第七批 W2 / B④）：用户只改字 ——「品类」→「序号」、「主厨」→「品类」。
                //     列位/**列宽**/右对齐锚点一字未动（`Row` 几何与 `PRICE_COL_W` 都没碰）。
                Row h = Row.line("序号", paidCol ? "收入" : null, SZ_BODY * scB);
                h.color = GRAY;
                h.mono = bMono;
                h.c2 = "品类";
                h.c3 = "原价";
                out.add(h);
                // 🔴 第二条分隔线**只在表头真的画了**时才加 —— 否则占位账会出现
                //    两条相隔 12px 的平行线（上机实测过，观感像画错）。
                out.add(Row.sep());
                // 🆕 TASK-086 拍板①（Lead 定向干预 2026-10-10）：长按录价的**入口提示 = 整张墨单一条**。
                //   🔴 为什么不是"每行挂一句"：`验证记录/200` 已查明真机 8 期账 20+ 书目行里
                //     `UNKNOWN` 一条都没有（自导入/网文全落 `FREE`）⇒ per-row 会在**几乎每一行**重复，
                //     且它是追加在单行绘制的「作者 · 时长」尾巴上 ⇒ 有把「时长」挤掉的实测风险，
                //     而那一行版面是上一轮已验收的（A5/A6 类）。故改为独立小字，**不碰条目行版面**。
                //   🔴 位置 = 表头正下方（条目区之前）：属"读前说明"，与 `hitPriceRow` 的行扫描不冲突
                //     （它只认 `itemIdx >= 0` 的行），也不会把合计行往下挤。
                //   🔴 只在**还有"价没定"的行**（`effFen <= 0`）时出现 —— 全都有价的账单不挂噪声。
                //   ⚠️ 收口 F2/G2 后这里**有意**与长按入口不同源（入口判 `marketPriceFen`）：
                //      "提示消失但长按仍可改价"是设计，详见 `hasManualEntryRow()` 的 javadoc。
                if (hasManualEntryRow()) {
                    Row mh = Row.line(ctx.getString(R.string.menu_price_manual_hint), null, SZ_SMALL * scB);
                    mh.color = LIGHT;
                    mh.mono = bMono;
                    out.add(mh);
                }
            }
        }

        // ── ② 占位账：只写一句"本期无阅读记录"，绝不产 0 值账单（Q10） ──
        if (bill.placeholder) {
            Row ph = Row.line(PLACEHOLDER, null, SZ_BODY * scB);
            ph.color = GRAY;
            ph.align = 2;
            out.add(ph);
            return out;
        }

        // ── ③ 逐本 ──
        for (int i = 0; i < bill.items.size(); i++) {
            Bill.Item it = bill.items.get(i);
            if (i > 0) out.add(Row.gap());                  // 条目之间留一口气

            String no = String.format(java.util.Locale.US, "NO.%02d", i + 1);
            if (excerptMenu) {
                // 🔴 价**总是跟着条目走**（菜单的"价格"列）；配置 ⑩ 的「合计」子块只管**底部那行合计**。
                //    🆕 TASK-086：右端拆成「原价 │ 收入」两列（收入关着 ⇒ 只剩原价，列位不变）。
                Row l1 = Row.line(no + "  " + nz(it.title),
                        paidCol ? BillMoney.paidText(ctx, it, algo) : null, SZ_BODY * scB);
                l1.bold = true;
                l1.mono = bMono;
                l1.c3 = BillMoney.priceText(ctx, it);
                l1.itemIdx = i;                       // 🆕 长按录价的命中锚点（`hitPriceRow`）
                out.add(l1);

                // 第二行：主厨（作者）[· 时长] —— 🆕 拍板①后**恢复上一轮形状**，不挂任何尾巴
                //    （Lead 定向干预 2026-10-10：per-row 挂提示会在真实数据下近乎每行重复，
                //     且有把「时长」挤出单行绘制的实测风险 ⇒ 入口提示改为**整张墨单一条**独立小字）
                StringBuilder meta = new StringBuilder();
                if (MenuPrefs.showAuthor(ctx) && nz(it.author).length() > 0) meta.append(it.author);
                if (MenuPrefs.showDuration(ctx)) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append(dur(it.readTimeSec, unitPref));
                }
                if (meta.length() > 0) {
                    Row l2 = Row.line(meta.toString(), null, SZ_SMALL * scB);
                    l2.color = GRAY;
                    l2.mono = bMono;
                    out.add(l2);
                }
                addNotes(out, it, bMono, scB);
            } else {
                Row l1 = Row.line(no + "  《" + nz(it.title) + "》", null, SZ_BODY * scB);
                l1.bold = true;
                l1.serif = tSerif;
                out.add(l1);

                StringBuilder meta = new StringBuilder();
                if (MenuPrefs.showAuthor(ctx) && nz(it.author).length() > 0) meta.append(it.author);
                if (MenuPrefs.showDuration(ctx)) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append(dur(it.readTimeSec, unitPref));
                }
                if (MenuPrefs.showProgress(ctx) && it.progressPct >= 0) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append(it.progressPct).append("%");
                }
                if (meta.length() > 0) {
                    Row l2 = Row.line(meta.toString(), null, SZ_SMALL * scB);
                    l2.color = GRAY;
                    l2.mono = bMono;
                    out.add(l2);
                }
                addNotes(out, it, bMono, scB);
            }
        }

        // ── ④ 票据尾：备注 / 合计 / 条码 / 本地条数提示 ──
        boolean hasTail = noteOn || (excerptMenu && totalOn)
                || MenuPrefs.FOOT_BARCODE.equals(footer) || MenuPrefs.FOOT_BOTH.equals(footer)
                || excerptMenu;

        String wholeNote = nz(MenuPrefs.footerNote(ctx));
        boolean showWholeNote = noteOn && wholeNote.length() > 0
                && !MenuPrefs.FOOT_BARCODE.equals(footer);      // 底部只勾条码 ⇒ 不画备注
        // 🆕 task-39（#6c · docs/13:238）：合计行正上方改画**双线**（1px + 3px + 1px）⇒ 这条双线
        //   自己就是「票据尾」的分隔线；若尾的第一行正是合计（前面没有「整单备注」），就不再叠
        //   一条单线 —— 否则双线之上还压着一条 1px，读起来是"三线"。
        boolean totalFirst = (excerptMenu && totalOn) && !showWholeNote;
        if (hasTail && !totalFirst) out.add(Row.sep());

        if (showWholeNote) {
            Row n = Row.wrap("整单备注：" + wholeNote, SZ_SMALL * scB, 2);
            n.color = GRAY;
            n.mono = bMono;
            out.add(n);
        }
        if (excerptMenu && totalOn) {
            // 🆕 TASK-086（Q5）→ 🆕 task-39（用户第四批 #5）：合计行**压回一行**。
            //   旧版（Q5）拆两行是上机倒逼的 —— 单行约 532px > 内容宽 432px，`Row.fit` 会把
            //   最有价值的「折合 ¥Y/小时」截掉（见 `billTotalNote` 注释）。
            //   本轮把解法换成「**按需轻微横向压缩**」（与金额列 task-35 同族手段、同一个
            //   `InkTheme.moneyScaleX`，必要时用 `InkTheme.TOTAL_MIN_SCALE_X` 兜底）⇒
            //   单行**完整可读**、**不动列宽**（`PRICE_COL_W` 一字未动，也不新增第二套尺）。
            //   文案 = 用户原话口径 `合计  阅读 x 小时，读回 ¥xx，折合 ¥xx/小时`
            //   （「本期阅读」按用户原话简化为「阅读」）；`≈` 前缀 / H=0 不写「折合」/
            //   只累加算得出的行 —— 三条红线全在 `billTotalNote` 内，未改写。
            out.add(Row.dsep());                       // 合计正上方双线（docs/13:238）
            Row tf = Row.line(billTotalLine(algo), null, SZ_BODY * scB);
            tf.bold = true;
            tf.mono = bMono;
            tf.compress = true;                        // 🔴 放不下 ⇒ 压字距画**完整**串，绝不截断
            out.add(tf);
        }
        if (MenuPrefs.FOOT_BARCODE.equals(footer) || MenuPrefs.FOOT_BOTH.equals(footer)) {
            out.add(Row.bars(billTitle + " · " + serial));
        }
        if (excerptMenu) {
            Row hint = Row.line("摘录基于本地已同步 " + localCount() + " 条 · 可先手动刷新", null, SZ_SMALL * scB);
            hint.color = LIGHT;
            hint.mono = bMono;
            out.add(hint);
        }
        return out;
    }

    /**
     * 按「备注来源」（配置 ⑥）× 「摘录策略」（配置 ⑦）决定要画几行、每行什么标签。
     *
     * <p>🔴 **热门划线的来源必须显式标注**（风险 R4）：本人划线的行**不加前缀**（直接给原文），
     * 公开热门的行用 `热门：` —— 绝不能让用户误以为那是自己划的。
     */
    private void addNotes(List<Row> out, Bill.Item it, boolean mono, float sc) {
        String src = MenuPrefs.noteSrc(ctx);
        List<String[]> auto = autoParts(it);

        if (MenuPrefs.SRC_MANUAL.equals(src)) {
            String manual = nz(MenuPrefs.noteOf(ctx, it.bookId));
            if (manual.length() > 0) out.add(noteRow("备注：" + manual, mono, sc));
            return;
        }
        if (MenuPrefs.SRC_AUTO.equals(src)) {
            for (int i = 0; i < auto.size(); i++) {
                String[] p = auto.get(i);
                out.add(noteRow(p[0] + p[1], mono, sc));
            }
            return;
        }
        // SRC_AUTO_FIRST（默认）：有自动内容就用，没有才回落到手动备注
        if (!auto.isEmpty()) {
            for (int i = 0; i < auto.size(); i++) {
                String[] p = auto.get(i);
                out.add(noteRow(p[0] + p[1], mono, sc));
            }
            return;
        }
        String manual = nz(MenuPrefs.noteOf(ctx, it.bookId));
        if (manual.length() > 0) out.add(noteRow("备注：" + manual, mono, sc));
    }

    private Row noteRow(String text, boolean mono, float sc) {
        Row r = Row.wrap(text, SZ_SMALL * sc, 2);        // 🔴 「过长截断」：最多两行
        r.color = GRAY;
        r.mono = mono;
        r.indent = 3f;
        return r;
    }

    /**
     * 「摘录策略」4 档 → 要画的 (标签, 正文) 列表。
     *
     * <p>⚠️ **【解读】**卡面的档位表里，「热门补充」（个人为空时用热门）与「有啥显示啥」
     * （个人优先、其次热门）字面几乎重合 ⇒ 按表里「数据源」一列落定：<br>
     * ③ 热门补充 = **二选一**（我的为空才用热门）；④ 有啥显示啥 = **两者合并显示**。
     * 本条已登记进验证记录。
     */
    private List<String[]> autoParts(Bill.Item it) {
        List<String[]> out = new ArrayList<String[]>();
        String mode = MenuPrefs.excerptMode(ctx);
        String mine = nz(it.mineText);
        String hot = nz(it.hotText);
        if (MenuPrefs.EX_MINE.equals(mode)) {
            if (mine.length() > 0) out.add(new String[] { "", mine });
        } else if (MenuPrefs.EX_HOT_ONLY.equals(mode)) {
            if (hot.length() > 0) out.add(new String[] { "热门：", hot });
        } else if (MenuPrefs.EX_HOT_FILL.equals(mode)) {
            if (mine.length() > 0) out.add(new String[] { "", mine });
            else if (hot.length() > 0) out.add(new String[] { "热门：", hot });
        } else {   // EX_ANY：两者合并
            if (mine.length() > 0) out.add(new String[] { "", mine });
            if (hot.length() > 0) out.add(new String[] { "热门：", hot });
        }
        return out;
    }

    /**
     * 🆕 TASK-086 拍板①：这张墨单里**还有没有"价没定"的行**（`effFen <= 0` ⇒ 免费 或 未定价）。
     *
     * <p>只有存在时才挂那一条「长按书目行可录入实际书价」的独立小字 ——
     * 全都有价的账单**不挂任何噪声**。
     * <p>🔴 **有意的不对称（收口 F2/G2 之后，2026-10-10）**：这里的判据仍是**生效价**
     * `effFen <= 0`（语义 = "还有价没定 ⇒ 邀请你来录"），而长按入口 {@link #hitPriceRow} /
     * {@link #openPad} 已改为判**真实市场价**（`marketPriceFen > 0`）。
     * ⇒ 会出现「提示小字消失了，但长按仍可改价」的组合 —— **这是有意的**，不是漏改：
     * 提示是"邀请"，录满即功成身退；而入口要一直留着，否则用户填错价就再也改不回来。
     * ⚠️ 由此本方法**不再**与入口门同源（旧注释曾写"完全同源"）。
     */
    private boolean hasManualEntryRow() {
        if (bill == null || bill.items == null) return false;
        for (int i = 0; i < bill.items.size(); i++) {
            if (BillMoney.effFen(ctx, bill.items.get(i)) <= 0) return true;
        }
        return false;
    }

    /** 本地已同步的摘录总条数（划线 + 想法）—— 供提示行 `基于本地已同步 N 条`（Q2）。 */
    private int localCount() {
        if (bill == null) return 0;
        int n = 0;
        for (int i = 0; i < bill.items.size(); i++) n += Math.max(0, bill.items.get(i).mineCount);
        return n;
    }

    /**
     * 🆕 TASK-086（Q5）→ 🆕 task-39（用户第四批 #5）：**合计行**（**单行**）——
     * `合计   阅读 16.9 小时，≈读回 ¥2130.81，折合 ¥126.10/小时`。
     *
     * <p>🔴 H = `totalSec ÷ 3600`（{@link BillMoney#hoursText} = 1 位小数）。
     * 文案 = **用户原话口径**「合计  阅读 x 小时，读回 ¥xx，折合 ¥xx/小时」——
     * 「本期阅读」按用户原话简化为「阅读」（task-39 记录 §口径 已写明）。
     *
     * <p>🔴 第二段的三种"不写"情形、`≈` 前缀规则、只累加算得出的行 —— **全部复用
     * {@link #billTotalNote(String)}**（单一出口，本方法不重写任何金额口径）。
     * 单行放不下时由调用方那一行（`Row.compress`）压字距画完整串，不截断。
     *
     * <p>🆕 task-48（用户第五批 #3）：第一段**按收入算法分档**——
     * <ul>
     *   <li>**增额档**（`PAID_PERIOD`）：`合计   阅读 16.9 小时` —— 时段阅读时长，逐字不动；</li>
     *   <li>**累计档**（`PAID_CUMULATIVE`）：`合计   本期阅读 16.9 小时` —— 加「本期」二字，
     *       因为下一段的金额是**开读至今累计**，不加会与"本期读回"混读
     *       （用户第五批原话：累计档要写明是哪一段的时长）。</li>
     * </ul>
     * 两种档的 H 同源 = `bill.totalSec ÷ 3600`（不变）。`BillWallpaper` 同口径（A11）。
     */
    private String billTotalLine(String algo) {
        String note = billTotalNote(algo);
        // 🆕 task-48（#3）：累计档写「本期阅读」（下一段是"开读至今"的金额，须标清时长归属）
        String lead = MenuPrefs.PAID_CUMULATIVE.equals(algo) ? "合计   本期阅读 " : "合计   阅读 ";
        return lead + BillMoney.hoursText(bill.totalSec) + " 小时"
                + (note != null ? "，" + note : "");
    }

    /**
     * 🆕 TASK-086（Q5）：**合计行**第二段 —— `读回 ¥X，折合 ¥Y/小时`。
     *
     * @return 该段的文案；**返回 null = 这一段不画**（三种情况见下）
     *
     * <p>🔴 上机实测（480×800）倒逼的两条口径：
     * <ol>
     *   <li>**一行画不下** —— `合计   阅读 16.9 小时，读回 ¥55.50，折合 ¥3.28/小时`
     *       在 `SZ_BODY` 下约 532px &gt; 内容宽 432px（`PAD_X_RATIO`=0.05 ⇒ 480×0.9）⇒
     *       `Row.fit` 会把**最有价值的那一段（折合 ¥Y/小时）**截掉。
     *       历史解法（Q5）= 拆**两行**；🆕 **task-39 起改为单行 + 按需轻微横向压缩**
     *       （`Row.compress` → `InkTheme.moneyScaleX`，必要时 `InkTheme.TOTAL_MIN_SCALE_X` 兜底）
     *       ⇒ 单行完整可读且不动列宽。`BillWallpaper` 同口径同步（A11）。</li>
     *   <li>**一行都算不出来时不写** —— 老账无价 / 算法 A 首期无基线 / 全是免费书时，
     *       `¥0 / ¥0 每小时`是噪声，会读成"一分钱没读回来"。此时如实只留第一段
     *       （与 `tasks/TASK-086` §老账兼容「合计按新格式（仅时长）」一致）。</li>
     * </ol>
     * 🔴 H = 0 时**不写「折合」**（除零保护，且 0 小时读回任何钱都是噪声）；
     * 🔴 X 只累加**算得出来**的行（无价 / 无进度 / 首期无基线的不进合计 —— A7）。
     * <p>🆕 TASK-086 第三批（A18）：合计里**只要有一行是估算值**（历史期锚点法），整段前缀 `≈`
     * —— 行上标 `≈` 而合计不标会读成"合计是精确值"，自相矛盾；`BillWallpaper` 同规则同步（A11）。
     *
     * <p>🆕 task-48（用户第五批 #3）：**累计档**（{@code PAID_CUMULATIVE}）第二段改为
     * 「{@code (≈)累计读回 ¥X}」并**去掉「折合 ¥Y/小时」**——
     * <ol>
     *   <li>累计档的 X = {@link BillMoney#totalPaidFen} = 各书**开读至今**的合计（不是本期增量），
     *       所以口径名要带「累计」二字；</li>
     *   <li>「折合 ¥Y/小时」= X ÷ **本期**时长 ⇒ 分子累计、分母本期，是**跨口径混算**
     *       （用户第五批 #3 原话：累计档不要折合）⇒ 累计档整段不写折合。</li>
     * </ol>
     * 🔴 `≈` 位置：沿用本方法"整段前缀"的既有先例 ⇒ `≈累计读回 ¥X`（备选写 `累计读回 ≈¥X`
     * 与行级 `≈¥X` 一致；本轮取前者，已登记 `验证记录/224`）。
     * 增额档（`PAID_PERIOD`）逐字不动。
     */
    private String billTotalNote(String algo) {
        if (MenuPrefs.PAID_OFF.equals(algo)) return null;
        if (BillMoney.paidCount(ctx, bill.items, algo) <= 0) return null;   // ② 全算不出 ⇒ 不写
        long pay = BillMoney.totalPaidFen(ctx, bill.items, algo);
        final boolean cum = MenuPrefs.PAID_CUMULATIVE.equals(algo);
        // 🆕 task-48（#3）：累计档 ⇒ 「累计读回」（口径名标清）；增额档 ⇒ 「读回」（逐字不动）
        String s = (BillMoney.totalEstimated(ctx, bill.items, algo) ? "≈" : "")
                + (cum ? "累计读回 " : "读回 ") + BillMoney.yuan(pay);
        float h = bill.totalSec / 3600f;
        // 🔴 累计档**不写折合**（分子"开读至今"÷ 分母"本期时长" = 跨口径混算，见方法 javadoc）
        if (!cum && h > 0f) s += "，折合 " + BillMoney.yuan((int) Math.round(pay / h)) + "/小时";
        return s;
    }

    // ══════════════════════ 折行 ══════════════════════

    private List<Row> laid(float w, float unit) {
        if (laidRows != null && cw == w && cu == unit) return laidRows;
        cw = w;
        cu = unit;
        float uw = w * (1f - 2f * InsightRenderer.PAD_X_RATIO);
        List<Row> src = rows();
        List<Row> out = new ArrayList<Row>();
        for (int i = 0; i < src.size(); i++) {
            Row r = src.get(i);
            if (r.kind != K_WRAP) { out.add(r); continue; }
            mp.setTextSize(r.size * unit);
            mp.setTypeface(r.mono ? Typeface.MONOSPACE : null);
            float maxW = uw - r.indent * unit - unit * 4f;
            List<String> frags = wrap(mp, r.a, maxW, (int) r.maxLines);
            for (int k = 0; k < frags.size(); k++) {
                Row n = Row.line(frags.get(k), null, r.size);
                n.color = r.color;
                n.mono = r.mono;
                n.bold = r.bold;
                n.indent = r.indent;
                out.add(n);
            }
        }
        mp.setTypeface(null);
        laidRows = out;
        return out;
    }

    private static List<String> wrap(Paint p, String s, float maxW, int maxLines) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() == 0) return out;
        float[] mw = new float[1];
        int guard = 0;
        while (s.length() > 0 && guard++ < 16) {
            if (maxLines > 0 && out.size() >= maxLines - 1) { out.add(fit(p, s, maxW)); return out; }
            int n = p.breakText(s, true, maxW, mw);
            if (n <= 0) n = 1;
            if (n >= s.length()) { out.add(s); return out; }
            out.add(s.substring(0, n).trim());
            s = s.substring(n).trim();
        }
        if (s.length() > 0) out.add(fit(p, s, maxW));
        return out;
    }

    /** 按**实测宽度**截断 + 省略号（中文与数字宽度差很大 ⇒ 不能按字数估）。 */
    // 🆕 task-66：改包内可见 —— `PricePad` 的书名提示行需要同一套截断（只留这一份实现）。
    static String fit(Paint p, String s, float maxW) {
        if (s == null) return "";
        if (maxW <= 0f) return "";
        if (p.measureText(s) <= maxW) return s;
        for (int i = s.length() - 1; i > 0; i--) {
            String t = s.substring(0, i) + "…";
            if (p.measureText(t) <= maxW) return t;
        }
        return "…";
    }

    // ══════════════════════ 小工具 ══════════════════════

    /** 时长文案：小时档 `3h39m` / 分钟档 `219m`（配置 ②）。 */
    private static String dur(int sec, String unit) {
        if (sec <= 0) return MenuPrefs.UNIT_M.equals(unit) ? "0m" : "0h00m";
        if (MenuPrefs.UNIT_M.equals(unit)) return (sec / 60) + "m";
        int h = sec / 3600, m = (sec % 3600) / 60;
        return h + "h" + (m < 10 ? "0" + m : String.valueOf(m)) + "m";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ══════════════════════ 行 ══════════════════════

    /**
     * 🆕 TASK-086 第六批B：本类由 {@code private} **放宽为包内可见**（Lead 批准，见 `tasks/TASK-086`）。
     * 🔴 **零行为、零版式变化** —— 只去掉了类上那一个 `private`，字段 / 工厂 / {@code h()} / {@code draw()}
     * 本来就是包内可见；放宽的唯一目的是让同包的 {@code AssetSection}（阅读资产）**复用同一套行渲染**，
     * 不再另写第二套（用户 ③ 的「版式统一」要求）。
     */
    static final class Row {
        int kind;
        String a, b;            // a = 主；b = 右端（两端对齐时用）
        String c2;              // 中间列（表头三段用）
        /** 🆕 TASK-086：右起第二列（`原价`）—— 与 `b`（`收入`）组成价格两列，严格右对齐。 */
        String c3;
        /** 🆕 TASK-086：这一行属于哪个书目（`items` 下标；-1 = 不是书目行）—— 长按录价的命中锚点。 */
        int itemIdx = -1;
        /** 🆕 第七批 W2（task-66）：这一行属于哪本书（`AssetStore.Book.id`；`null` = 非书目行）——
         *  阅读资产「长按书名移出统计 / 长按『免费』录价」的命中锚点（按行取 id，不再排平行表）。 */
        String bookId;
        float size;             // 基础字号（×unit）
        int color = INK;
        boolean bold, serif, mono;
        float indent;           // ×unit
        int align;              // 0 左 / 1 右 / 2 中
        float maxLines;         // K_WRAP 的行数上限（0 = 不限）
        float gap;              // K_GAP 的额外留白（×unit）
        /** 🆕 task-39（#5）：左对齐单行**放不下时按需压字距**画完整串（合计行用），绝不截断。 */
        boolean compress;

        static Row line(String a, String b, float size) {
            Row r = new Row();
            r.kind = K_LINE;
            r.a = a;
            r.b = b;
            r.size = size;
            return r;
        }

        static Row wrap(String a, float size, int maxLines) {
            Row r = new Row();
            r.kind = K_WRAP;
            r.a = a;
            r.size = size;
            r.maxLines = maxLines;
            return r;
        }

        static Row sep() {
            Row r = new Row();
            r.kind = K_SEP;
            return r;
        }

        /** 🆕 task-39（#6c · docs/13:238）：**双线**分隔行 —— 合计行正上方（1px + 3px + 1px）。 */
        static Row dsep() {
            Row r = new Row();
            r.kind = K_DSEP;
            return r;
        }

        static Row bars(String caption) {
            Row r = new Row();
            r.kind = K_BARS;
            r.a = caption;
            return r;
        }

        /** 条目之间的一口气（借 K_SEP 的空白位，但不画线）。 */
        static Row gap() {
            Row r = new Row();
            r.kind = K_SEP;
            r.gap = 1f;         // 标记：不画线，只留白
            return r;
        }

        float h(float unit) {
            if (kind == K_BARS) return unit * 20f;
            if (kind == K_DSEP) return unit * DSEP_GAP_UNITS + 2f;   // 两条 1px + 间隔
            if (kind == K_SEP) return unit * (gap > 0f ? 12f : 10f);
            return size * LH * unit;
        }

        float draw(Canvas c, float left, float right, float y, float unit, Paint p) {
            float h = h(unit);
            if (kind == K_BARS) {
                drawBars(c, left, right, y, unit, p, a);
                return h;
            }
            if (kind == K_DSEP) {
                // 🆕 task-39（#6c · docs/13:238）：**合计行上方双线** = 1px 线 + 3px 间隔 + 1px 线。
                //    只改画：不引新字体、不新增第二套尺（间隔仍以 unit 为单位）、不动任何命中区。
                // 🆕 task-48（用户第五批 #5）：**可见性修复** —— ① 颜色由 `LINE 0xFFD8D8D8`
                //    （白底 1.43:1，实测渲成 235/236 ≈ 看不见）换成票据横线色 `InkTheme.RULE`；
                //    ② 两条线**对齐到整数物理行**（`Math.round`）⇒ 1px 落在整行上、不再被抗锯齿
                //    摊成两行各 50%（实测证据见 `验证记录/224`）。间隔仍是 3px。
                p.setStyle(Paint.Style.FILL);
                p.setColor(InkTheme.RULE);
                final float dy0 = Math.round(y);
                float dy1 = Math.round(y + h) - 1f;
                if (dy1 < dy0) dy1 = dy0;
                c.drawRect(left, dy0, right, dy0 + 1f, p);
                c.drawRect(left, dy1, right, dy1 + 1f, p);
                return h;
            }
            if (kind == K_SEP) {
                if (gap > 0f) return h;                  // 只留白，不画线
                // 🆕 task-48（用户第五批 #5）：同 K_DSEP —— 票据横线换 `InkTheme.RULE` + 整数行对齐
                //    （原来 `LINE` 1px 画在半像素上 ⇒ 实测色 219，墨屏读不出）。
                p.setStyle(Paint.Style.FILL);
                p.setColor(InkTheme.RULE);
                final float sy = Math.round(y + h * 0.5f);
                c.drawRect(left, sy, right, sy + 1f, p);
                return h;
            }
            float sz = size * unit;
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTextSize(sz);
            p.setTypeface(serif ? InkTheme.serif() : (mono ? Typeface.MONOSPACE : null));
            p.setFakeBoldText(bold);
            Paint.FontMetrics fm = p.getFontMetrics();
            float base = y + h * 0.5f - (fm.ascent + fm.descent) / 2f;
            float x = left + indent * unit;

            if (c3 != null) {
                // 🆕 TASK-086：右端**两列** —— 「收入」贴分区右边距、「原价」落在它左侧同等宽度处。
                //    🔴 两列都右对齐，表头与每一条书目共用同一份列宽 ⇒ 数字严格成列（不会参差）。
                // 🆕 TASK-12（用户 2026-10-10 指令① 第 3 条）：**收入关**
                //    （`MenuPrefs.PAID_OFF` ⇒ 调用方把 `b` 传 null、只给 `c3`）时，
                //    原价**占用最右列**（锚点 = `right`，与收入开着时**收入所在的同一 x**），
                //    而不是仍停在 `right − pcw` 把右端空出一格。表头 / 条目行共用本分支 ⇒ 两处一致。
                //    ⚠️ 只用"锚点"这一处变量搬家，**不动列宽、不动列序、不动行高** ⇒ 收入开着时
                //    版面逐像素不变（同一份 `pcw`、同一份 `lim` 公式）。
                final float pcw = PRICE_COL_W * unit;
                p.setTextAlign(Paint.Align.RIGHT);
                // 🆕 TASK-086 第三批 · task-35：金额列改为「按需轻微横向压缩」，不再直接截断。
                //    根因（2026-10-11 真机量化）：预算 `pcw - 2u = 72.0px` ÷ 10.75px/字形 = 6.7 字形
                //    ⇒ 7 字形必被 `fit()` 截成 `≈¥12.…`（`T27h` 实测，A18 形制破形）。
                //    `sc == 1f` ⇒ 走原 `fit()`（放得下 ⇒ 原样返回；压到下限仍放不下 ⇒ 截断，与改造前一致）；
                //    `sc < 1f` ⇒ 压缩绘制**完整**串。列宽 / 锚点 / 标题上限口径**全部不变**。
                final float moneyMax = pcw - unit * 2f;
                final float scPay = (b != null) ? InkTheme.moneyScaleX(p, b, moneyMax) : 1f;
                final String bs = (b != null) ? (scPay < 1f ? b : fit(p, b, moneyMax)) : null;
                if (bs != null) {
                    p.setTextScaleX(scPay);
                    c.drawText(bs, right, base, p);
                    p.setTextScaleX(1f);
                }
                final float scPrice = InkTheme.moneyScaleX(p, c3, moneyMax);
                final String cs = (scPrice < 1f) ? c3 : fit(p, c3, moneyMax);
                final float priceX = (bs != null) ? (right - pcw) : right;   // 🔴 TASK-12：收入关 ⇒ 原价贴右
                p.setTextScaleX(scPrice);
                c.drawText(cs, priceX, base, p);
                p.setTextScaleX(1f);
                p.setTextAlign(Paint.Align.LEFT);
                // 🔴 上机实测（2026-10-10，480×800）：`lim` **不能只按列宽推**。
                //    收入**关**时 `lim = right − pcw − 8u ≈ 372`，而「原价」右对齐在 `right − pcw`、
                //    其墨迹左沿可能落在 ~326 ⇒ 标题被放进价格列里，**画出来是糊在一起的乱码**
                //    （实测：「卡拉马佐夫兄弟（套装上下册）」压住「¥97.99」）。
                //    故再与「原价**实际墨迹**左沿 − 间隔」取小；顺带让窄价（如 ¥15）多让出空间。
                //    ✅ TASK-12 后这条教训继续成立（收入关时原价移到 `right`，墨迹左沿随之内缩）。
                float lim = right - pcw * ((bs != null) ? 2f : 1f) - unit * 8f;
                final float inkLim = priceX - p.measureText(cs) - unit * 6f;
                if (inkLim < lim) lim = inkLim;
                if (c2 != null) {
                    // 表头：中列「品类」（左侧给「序号」，右侧让给价格两列）
                    float mid = left + (right - left) * 0.34f;
                    c.drawText(fit(p, c2, lim - mid), mid, base, p);
                    c.drawText(fit(p, a, mid - left - unit * 4f), x, base, p);
                } else {
                    c.drawText(fit(p, a, lim - x), x, base, p);
                }
            } else if (b != null && c2 != null) {
                // 表头三段：序号（左）/ 品类（中）/ 价格（右）
                p.setTextAlign(Paint.Align.RIGHT);
                String bs = fit(p, b, (right - left) * 0.3f);
                c.drawText(bs, right, base, p);
                p.setTextAlign(Paint.Align.LEFT);
                float mid = left + (right - left) * 0.46f;
                c.drawText(fit(p, c2, (right - left) * 0.3f), mid, base, p);
                c.drawText(fit(p, a, mid - left - unit * 4f), x, base, p);
            } else if (b != null) {
                p.setTextAlign(Paint.Align.RIGHT);
                String bs = fit(p, b, (right - x) * 0.42f);
                c.drawText(bs, right, base, p);
                float bw = p.measureText(bs);
                p.setTextAlign(Paint.Align.LEFT);
                // 🔴 左侧主串与右侧数值之间留一段最小间隔（unit×10 ≈ 12px）——
                //    否则长书名截断后的省略号会**紧贴**价格（上机实测："…（陀思…¥39"）。
                c.drawText(fit(p, a, right - x - bw - unit * 10f), x, base, p);
            } else if (align == 2) {
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(fit(p, a, right - left), (left + right) / 2f, base, p);
            } else if (align == 1) {
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(fit(p, a, right - x), right, base, p);
            } else {
                p.setTextAlign(Paint.Align.LEFT);
                if (compress) {
                    // 🆕 task-39（#5）：合计**单行** —— 放不下就「按需轻微横向压缩」
                    //    （与金额列 task-35 同族手段、同一个 `InkTheme.moneyScaleX`），
                    //    🔴 绝不把「折合 ¥Y/小时」截成 `…`。
                    //    `moneyScaleX` 自带 0.68 下限（语义：压不动就交回截断）；合计行还允许用
                    //    更松的 `InkTheme.TOTAL_MIN_SCALE_X`（0.60）再兜一次 —— 那一段是结论句，
                    //    "字略瘦"远好过"结论被截掉"。真到 0.60 还压不下（病态超长）才回到老 `fit()`。
                    final float maxW = right - x;
                    float sc = InkTheme.moneyScaleX(p, a, maxW);
                    if (sc >= 1f) {
                        float need = p.measureText(a);
                        if (need > maxW) {
                            float raw = maxW / need;
                            if (raw >= InkTheme.TOTAL_MIN_SCALE_X) sc = raw;
                        }
                    }
                    if (sc < 1f) {
                        p.setTextScaleX(sc);
                        c.drawText(a, x, base, p);
                        p.setTextScaleX(1f);
                    } else {
                        c.drawText(fit(p, a, maxW), x, base, p);
                    }
                } else {
                    c.drawText(fit(p, a, right - x), x, base, p);
                }
            }
            p.setTextAlign(Paint.Align.LEFT);
            p.setFakeBoldText(false);
            p.setTypeface(null);
            return h;
        }

        /**
         * 🔴 装饰条码 —— **纯自绘、不编码任何真实数据**（`tasks/TASK-077` §非目标）。
         * 条宽由一个固定图样决定（不含用户内容），右边跟一句 `墨单 · 2026-W40`。
         */
        private static void drawBars(Canvas c, float left, float right, float y, float unit,
                                     Paint p, String caption) {
            float cy = y + unit * 10f;
            float top = cy - unit * 5.5f, bot = cy + unit * 5.5f;
            int[] pat = { 3, 1, 1, 2, 1, 3, 1, 2, 2, 1, 3, 1, 1, 2, 3, 1, 2, 1, 1, 3, 2, 1, 1, 2, 1 };
            p.setStyle(Paint.Style.FILL);
            p.setColor(INK);
            float x = left;
            for (int i = 0; i < pat.length && x < left + unit * 92f; i++) {
                float bw = unit * (pat[i] * 0.9f + 0.4f);
                c.drawRect(x, top, x + bw, bot, p);
                x += bw + unit * (1.1f + (pat[i] % 2));
            }
            if (caption != null && caption.length() > 0) {
                p.setTextSize(SZ_SMALL * unit);
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextAlign(Paint.Align.LEFT);
                p.setColor(GRAY);
                Paint.FontMetrics fm = p.getFontMetrics();
                float base = cy - (fm.ascent + fm.descent) / 2f;
                c.drawText(fit(p, caption, right - x - unit * 8f), x + unit * 8f, base, p);
                p.setTextAlign(Paint.Align.LEFT);
                p.setTypeface(null);
            }
        }
    }
}
