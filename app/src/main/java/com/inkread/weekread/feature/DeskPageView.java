package com.inkread.weekread.feature;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
// 🆕 第七批 W2（task-72）：`import android.widget.Toast;` 已删 —— 反馈改屏上自绘（见 `showTip`）。
// 🆕 TASK-089b（(b′) 裁定）：选书用**标准 Dialog**（Activity 形态下 IME 可用），UI 全代码构建。
import android.app.Dialog;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.inkread.weekread.R;
// 🔴 2026-10-11（TASK-18）：`import com.inkread.weekread.core.BgImageUtil;` 已随墨台背景图功能删除
//    （本类不再加载任何背景位图；背景由 `DeskRenderer` 直接画纯白）。
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.MenuPrefs;
import com.inkread.weekread.core.NoteStore;
import com.inkread.weekread.core.PagePrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.net.NoteSync;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 🆕 TASK-075：**墨台**的内容容器 —— 纯 `Canvas` 自绘 + 单指纵向拖动滚动（照 {@link InsightPageView}
 * 的"量高 → 钳位 → 单指纵向拖动"语义），外加**左滑返回**。
 *
 * <p>为什么不复用 {@code InsightPageView}：那个类是本页**并列**的、且其滚动语义耦合了"排行区内滚"的
 * 手势分区；墨台是**第三窗**（模态覆盖层），语义不同（横滑 = 返回）。两者共享的是
 * {@link DeskRenderer} 对 {@code InsightRenderer.Section} 契约与静态工具的**复用**。
 *
 * <p>🔴 <b>模态语义</b>：墨台是"被呼出的整页"，铺满全屏 ⇒ 本视图**恒吃掉触摸**（与 {@code LockOverlay}
 * 一致），否则横滑会穿透到桌面。纵向拖动仅在内容溢出时真正位移（装得下 ⇒ `maxScroll == 0` ⇒
 * 钳住在 0，**不出现"空滚"**）—— 这是对 TASK-075 卡面「装得下不吞手势」在**模态层**下的正确落法。
 *
 * <p>🆕 **TASK-076**：分区**由模块注册表装配**（{@link PagePrefs} 的顺序/开关 + {@link DeskModules}）
 * ——「顺序即渲染顺序、关掉的模块不渲染不占高、总开关关掉 ⇒ 只剩空态」。
 *
 * <p>🆕 **2026-10-10（用户诉求 ①⑤）**：本视图升级为**三态**容器 ——
 * <pre>
 *   MODE_LIST      墨台（分区列表；顶栏右端「设置」）
 *   MODE_SETTINGS  墨台设置（{@link DeskSettings} 自绘面板；`‹ 返回` 回列表）
 *   MODE_WALLPAPER 墨单壁纸预览（{@link BillWallpaper} 出的位图 + 保存/关闭）
 * </pre>
 * 三态共用同一套"背景 + 顶栏"chrome（{@link DeskRenderer#drawChrome}）⇒ 进出不会跳一下。
 *
 * <p>🔴 **无惯性 / 无回弹 / 无动画**（墨水屏铁律）；一帧一次重绘。
 */
public final class DeskPageView extends View {

    /** 与周卡 / 洞察页同尺 —— 同一 App 同一观感。 */
    private static final float UNIT_RATIO = 0.0015f;

    /** 手势轴判定阈值（px）：超过它才锁定"横"或"纵"。 */
    private static final float SLOP_PX = 14f;
    /** 左滑返回：横向位移须 ≥ 视宽 × 本值 才判定为"返回"。 */
    private static final float BACK_RATIO = 0.12f;

    // ── 三态 ──
    private static final int MODE_LIST = 0;
    private static final int MODE_SETTINGS = 1;
    private static final int MODE_WALLPAPER = 2;

    /** 关闭回调（左滑 / 点 `‹ 返回` 都走它）。 */
    public interface Listener {
        void onDeskClose();

        /**
         * 🆕 2026-10-10 用户 ⑤：本页即将去起**外部界面**（系统分享面板）⇒ 通知宿主一声。
         *
         * <p>🔴 TASK-088 Phase 1（task-94，2026-10-13）：宿主已从「{@code TYPE_ACCESSIBILITY_OVERLAY}
         * 全屏覆盖窗」迁成自家 Activity（{@code shell/DeskActivity}）⇒ 本页与分享面板是**普通
         * Activity 叠放**，不再存在"覆盖窗把面板盖住"的问题；让位由宿主 Activity 的
         * {@code onPause} 交给卡片显隐链。本回调因此**可以留空**，保留只是为了不改调用点
         * （见 {@link #startShare(android.net.Uri)}）。
         *
         * <p>与 {@link #onDeskClose()} 的区别：分享面板接管前台后**墨台不再自动回来**（用户在面板里），
         * 之后回墨台还是回桌面都交给系统任务栈。
         */
        void onShareRequested();
    }

    private final DeskRenderer renderer = new DeskRenderer();

    /**
     * 「阅读资产」分区常驻实例（TASK-086 第六批B / D16：就地替换原「读书排行」）——
     * 头部条（排序三键 / 展开·收起）的命中判定要用它。
     * 🔴 与 {@link InsightPageView} 同做法：装配时复用同一份，不 new。
     * 不再有内滚位移（D25：分区随整页滚动），所以这里也不需要 getter 式换料。
     */
    private final AssetSection assetSection = DeskModules.assetSection();

    /**
     * 🆕 TASK-077：「墨单」分区常驻实例 —— 头部条（页签 / 周期 / 生成壁纸）的命中判定要用它。
     * 与 {@link #assetSection} 同理：装配时复用同一份，不 new。
     */
    private final BillSection billSection = DeskModules.billSection();

    /** 🆕 2026-10-10：墨台设置态的自绘面板（用户诉求 ①）。 */
    private final DeskSettings settings = new DeskSettings(getContext());

    private Listener listener;

    private float unit;

    private int mode = MODE_LIST;

    private float scrollY = 0f;
    private float maxScroll = 0f;

    private String updatedLabel = "";

    // ── 墨单壁纸预览（MODE_WALLPAPER）──
    private final Paint wp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap preview;
    private String previewErr = "";
    private boolean previewBusy = false;

    // 🆕 第七批 W2（task-72）：屏上自绘提示（替代被系统吞掉的 Toast，详见 `showTip`）。
    /** 当前提示文本（null = 不显示）。 */
    private String tipText = null;
    /** 提示自动消失的时长（ms）。 */
    private static final long TIP_MS = 4000L;
    /** 提示条专用画笔（不借用共享的 `wp`，避免污染同帧其它绘制）。 */
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 到期抹掉提示（只改提示状态，不动任何业务数据）。 */
    private final Runnable tipHide = new Runnable() {
        @Override
        public void run() {
            tipText = null;
            invalidate();
        }
    };
    private String previewMode = null;
    private long previewStart = 0L;
    /** 🆕 TASK-089b：落盘名基三态 —— 本张预览的**来源**（bill / asset / fp）。 */
    private static final int WP_SRC_BILL = 0;
    private static final int WP_SRC_ASSET = 1;
    private static final int WP_SRC_FP = 2;

    /**
     * 本张预览的**来源**（同时是落盘名基选择器）：
     * {@link #WP_SRC_BILL} = 墨单海报（{@link BillWallpaper#PREFIX_BILL} `modan_*`）·
     * {@link #WP_SRC_ASSET} = 阅读资产海报（{@link BillWallpaper#PREFIX_ASSET} `yuezi_*`）·
     * {@link #WP_SRC_FP} = 阅读指纹海报（{@link BillWallpaper#PREFIX_FP} `fp_*`）。
     *
     * <p>🆕 TASK-089b（Lead 批准的三态命名基）：本字段由 boolean 改为三态；默认
     * {@link #WP_SRC_BILL} ⇒ **旧行为一字不变**。保存 / 分享在进后台线程**之前**把它抓成
     * `final` 局部量（后台线程里读实例字段会与 `releasePreview()` 抢时序）。
     *
     * <p>🔴 `card_debug` 诊断**不**认它：`saveWallpaper()` 不写任何诊断；
     * `shareWallpaper()` 里那条 `CardDebug.note`（改后 :659-661）文案固定为「墨单壁纸：…」，与本字段无关
     * （F2，`task-93` A 半复核 `243`）。
     */
    private int wpFrom = WP_SRC_BILL;

    /** 当前来源对应的落盘名基（**必须在进后台线程之前**调用取值）。 */
    private String wpPrefix() {
        if (wpFrom == WP_SRC_ASSET) return BillWallpaper.PREFIX_ASSET;
        if (wpFrom == WP_SRC_FP) return BillWallpaper.PREFIX_FP;
        return BillWallpaper.PREFIX_BILL;
    }
    private final RectF btnSave = new RectF();
    private final RectF btnShare = new RectF();   // 🆕 2026-10-10 用户 ⑤：分享（发到手机）
    private final RectF btnClose = new RectF();
    /** 🆕 2026-10-10 用户 ⑤：本次预览**已落盘**得到的相册 uri —— 分享复用它，避免重复落盘。 */
    private Uri savedUri;

    // ── 🆕 TASK-086：长按录价 / 模态弹层（都是**瞬时态**，不落任何盘）──
    /** 长按录价 / 移出的候选书目行下标（-1 = 当前这次按下不在可长按的行上）。 */
    private int pendingPriceIdx = -1;
    /** 🆕 第七批 W2（task-66）：长按目标种类（0 = 无；见 {@link #LP_*}）。 */
    private int longKind;
    /**
     * 🆕 W2：按下瞬间**记下的书 id**（阅读资产那一侧的动作锚点）。
     *
     * <p>🔴 为什么不直接用行下标：阅读资产的行缓存会随「数据刷新 / `reload`」整体重建
     * （`AssetSection.stamp` 变 ⇒ `laid()` 重算，排序可能变），而长按要等 550ms —— 中间真发生过
     * 一次刷新的话，`bookIdAt(idx)` 会指到**另一本书**（把别人的书移出统计 = 用户可见的错）。
     * 按下时立刻把 id 定住，长按到点只认这个 id；`null` 时才回退用行下标。
     */
    private String longBookId;
    private static final int LP_NONE = 0;
    /** 墨单：长按无价行 ⇒ 录价键盘。 */
    private static final int LP_BILL_PRICE = 1;
    /** 阅读资产：长按**书名区** ⇒ 从统计中移出（可在墨台设置里逐个恢复）。 */
    private static final int LP_ASSET_TITLE = 2;
    /** 阅读资产：长按**原价/「免费」区** ⇒ 录价键盘（两区按 x 分列，互不重叠）。 */
    private static final int LP_ASSET_PRICE = 3;
    /** 长按是否**已经**触发（触发后这一次抬手不再当"点击"处理）。 */
    private boolean longFired;
    /** 数字键盘模态：按下时键盘已开 ⇒ 整段手势只归键盘。 */
    private boolean padModal;
    /**
     * 🆕 TASK-087-A1：本次手势是否驱动「阅读资产」的**区内滚**。
     *
     * <p>按下那一刻**只判定一次**（照 `InsightPageView` 的 `dragRank` 范式）：落在资产内滚视口里
     * 且该分区当前可滚（`maxInner > 0`）⇒ true。拖动时先喂区内滚，**到边界**（`scrollBy` 返回 false）
     * 就把这一段剩余量回落给**整页滚动**；反向拖回时区内滚可继续接住。
     */
    private boolean dragAsset;
    /** 长按判定时长 —— 墨屏上手指停留普遍比手机久，取 550ms 比 400ms 稳。 */
    private static final int LONG_PRESS_MS = 550;

    /** 长按到点 ⇒ 开数字键盘 / 移出统计（🔴 键盘是**自绘**的：覆盖窗里塞不进 `View`/`EditText`）。 */
    private final Runnable longPressRun = new Runnable() {
        @Override
        public void run() {
            if (longKind == LP_NONE) return;
            final int kind = longKind;
            final int idx = pendingPriceIdx;
            final String bid0 = longBookId;
            longFired = true;
            longKind = LP_NONE;
            pendingPriceIdx = -1;
            longBookId = null;
            if (kind == LP_BILL_PRICE) {
                billSection.openPad(idx);
                CardDebug.note(getContext(), "墨单：长按无价行 ⇒ 打开录价键盘 idx=" + idx);
            } else if (kind == LP_ASSET_PRICE) {
                if (bid0 != null) assetSection.openPad(bid0);
                else assetSection.openPad(idx);
                CardDebug.note(getContext(), "阅读资产：长按价格区 ⇒ 打开录价键盘 row=" + idx + " bookId=" + bid0);
            } else if (kind == LP_ASSET_TITLE) {
                // 🆕 W2 A2：长按书名 ⇒ 移出阅读资产统计 —— **必须有可见反馈**（禁静默）：
                //    提示条 + 该行立刻从列表与合计里消失（`hideBook` 内部 reload）。
                final String bookId = (bid0 != null) ? bid0 : assetSection.bookIdAt(idx);
                final String title = assetSection.titleOf(bookId);
                if (assetSection.hideBook(getContext(), bookId)) {
                    showTip(getContext().getString(R.string.toast_asset_hidden, title));
                    CardDebug.note(getContext(), "阅读资产：长按书名移出 bookId=" + bookId);
                    remeasure(getWidth(), getHeight());
                }
            }
            invalidate();
        }
    };

    // ── 手势 ──
    private float downX, downY, lastY;
    private float totDx, totDy;
    private int axis = AXIS_NONE;
    // 🔴 2026-10-11（TASK-18）：`dragVeil`（设置态拖白纱滑条）已随「背景」组删除。
    // 🔴 2026-10-11（TASK-086 第六批B / D25）：`dragRank`（排行区内滚）已随「阅读资产」去掉内滚而删除。

    private static final int AXIS_NONE = 0;
    private static final int AXIS_VERT = 1;
    private static final int AXIS_HORIZ = 2;

    public DeskPageView(Context c) { this(c, null); }

    public DeskPageView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFFFFFFFF);                 // 墨屏：白底，不做任何背景装饰
        unit = c.getResources().getDisplayMetrics().heightPixels * UNIT_RATIO;
        renderer.setEmptyText(c.getString(R.string.desk_empty));
        renderer.setLabels(c.getString(R.string.desk_title), c.getString(R.string.desk_back));
        renderer.setActionText(c.getString(R.string.desk_settings));
    }

    public void setListener(Listener l) { listener = l; }

    /** 每次呼出都从干净状态开始：回列表态 + 滚动归顶 + 模块重装 + 「更新于」= 现在。 */
    public void reset() {
        mode = MODE_LIST;
        scrollY = 0f;
        axis = AXIS_NONE;
        releasePreview();
        updatedLabel = "更新于 " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        buildSections();
        remeasure(getWidth(), getHeight());
        invalidate();
    }

    /**
     * 🆕 TASK-076：按 {@code PagePrefs} 的**顺序 + 开关**装配分区。
     *
     * <pre>
     *   总开关关 ⇒ 不装任何分区（只剩空态）
     *   否则     ⇒ 遍历「已开启模块（按顺序）」，逐个 build 一个 Section 装进来
     * </pre>
     *
     * 🔴 关掉的模块**根本不进分区表** ⇒ 既不渲染也不占高（这是卡面「关掉不占高」的落法）。
     */
    private void buildSections() {
        renderer.clear();
        Context c = getContext();
        if (!PagePrefs.isDeskEnabled(c)) return;         // 总开关关 ⇒ 空态
        List<String> ids = PagePrefs.enabledInOrder(c);
        for (int i = 0; i < ids.size(); i++) {
            DeskModule m = DeskModules.byId(ids.get(i));
            if (m == null) {
                // 只可能是"注册表漏登记"（`PagePrefs.MODULE_*` 加了、`DeskModules.ALL` 没加）；
                // 正常路径永不触发 ⇒ 静默跳过 + dev 留痕，绝不因此崩。
                CardDebug.noteV(c, "desk: 模块无实现，已跳过 id=" + ids.get(i));
                continue;
            }
            InsightRenderer.Section s = m.build(c);
            if (s != null) renderer.add(s);
        }
    }

    /** 内容总高（像素）—— 给自动化断言 / 调试用。 */
    public float contentHeightPx() {
        return renderer.contentHeight(getWidth(), getHeight(), unit);
    }

    /** 当前滚动位移（给断言用）。 */
    public float scrollYPx() { return scrollY; }

    /** 🆕 TASK-076：当前装配进来的分区数（给自动化断言用）。 */
    public int sectionCount() { return renderer.count(); }

    /** 🆕 TASK-076：第 i 个分区的标题（给自动化断言用；越界 ⇒ 空串）。 */
    public String sectionTitle(int i) { return renderer.titleOf(i); }

    /** 🆕 2026-10-10：当前处于哪一态（0 列表 / 1 设置 / 2 壁纸预览）—— 给自动化断言用。 */
    public int uiMode() { return mode; }

    private void remeasure(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float ch;
        if (mode == MODE_SETTINGS) {
            ch = renderer.headerHeight(unit) + settings.height(w, unit);
        } else {
            // 🆕 TASK-087-A1：阅读资产分区要按「抬头（固定）+ 视口（内滚）+ 尾部（固定）」算高，
            //    视口高 = 视图高 − 顶栏高 − 抬头 − 尾部 ⇒ 必须把顶栏高喂给分区（同 D25 之前的 rankH 注入）。
            assetSection.setDeskHeadH(renderer.headerHeight(unit));
            ch = renderer.contentHeight(w, h, unit);
        }
        maxScroll = ch - h;
        if (maxScroll < 0f) maxScroll = 0f;
        if (scrollY > maxScroll) scrollY = maxScroll;
        if (scrollY < 0f) scrollY = 0f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        remeasure(w, h);
    }

    /** 按当前态刷顶栏文案（每帧一次，纯字段赋值，零开销）。 */
    private void applyChrome() {
        Context c = getContext();
        if (mode == MODE_SETTINGS) {
            renderer.setLabels(c.getString(R.string.desk_settings_title), c.getString(R.string.desk_back));
            renderer.setActionText("");
        } else if (mode == MODE_WALLPAPER) {
            renderer.setLabels(c.getString(R.string.menu_gen_wallpaper), c.getString(R.string.desk_back));
            renderer.setActionText("");
        } else {
            // 🔴 列表态**恒**挂「设置」—— 即使墨台总开关被关（只剩空态），也必须能进去再打开它。
            renderer.setLabels(c.getString(R.string.desk_title), c.getString(R.string.desk_back));
            renderer.setActionText(c.getString(R.string.desk_settings));
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        drawPage(c);
        // 🆕 第七批 W2（task-72）：提示条**永远压在最上层** —— 页面本体有三个 return
        //    （壁纸态 / 设置态 / 列表态），所以提示画在这里而不是各个 return 之前。
        drawTip(c, getWidth(), getHeight());
    }

    /** 页面本体（原 `onDraw` 内容，一字未改，只被 {@link #onDraw} 包了一层以便收尾画提示）。 */
    private void drawPage(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 🔴 2026-10-11（TASK-18）：墨台背景**恒纯白** —— 这里原先要读 `PagePrefs` 的背景图路径
        //    并 `BgImageUtil.load(...)`（会碰共享存储）+ 读白纱不透明度，现已整体删除；
        //    背景由 `DeskRenderer.drawBackground` 直接画 `0xFFFFFFFF`，本类不再持有 `bg`/`bgKey`。
        applyChrome();

        if (mode == MODE_WALLPAPER) {
            drawWallpaper(c, w, h);
            return;
        }

        remeasure(w, h);     // 每帧自纠：分区高 / 设置行可能随字号、文案、偏好变化
        if (mode == MODE_SETTINGS) {
            renderer.drawChrome(c, w, h, unit, updatedLabel);
            float barH = renderer.headerHeight(unit);
            c.save();
            c.clipRect(0f, barH, w, h);                  // 与列表态同一套"吸顶"裁剪纪律
            settings.draw(c, w, unit, barH - scrollY);
            c.restore();
            return;
        }
        renderer.draw(c, w, h, unit, scrollY, updatedLabel);
        // 🆕 第七批 W2（task-66）：阅读资产的录价键盘画在**所有分区之上** —— 分区按 `desk_order`
        //    顺序绘制，后面的分区会盖住前面的（墨单的键盘画在自身分区内、靠"它在最前面"侥幸不被盖）。
        assetSection.drawPad(c, w, h, unit, wp);
    }

    // ══════════════════════ 🆕 2026-10-10：态切换 ══════════════════════

    /** 进设置态（顶栏「设置」）。 */
    private void enterSettings() {
        mode = MODE_SETTINGS;
        scrollY = 0f;
        axis = AXIS_NONE;
        settings.reload(getContext());
        remeasure(getWidth(), getHeight());
        invalidate();
        CardDebug.note(getContext(), "墨台：进入设置态");
    }

    /** 出设置态（`‹ 返回` / 左滑）—— 回来必须**重建分区**（模块开关 / 顺序可能刚被改过）。 */
    private void exitSettings() {
        mode = MODE_LIST;
        scrollY = 0f;
        axis = AXIS_NONE;
        buildSections();
        remeasure(getWidth(), getHeight());
        invalidate();
        CardDebug.note(getContext(), "墨台：退出设置态");
    }

    /**
     * 进壁纸预览态：把当前那一期墨单渲染成**本机屏幕尺寸**的海报（{@link BillWallpaper}）。
     *
     * <p>🔴 **纯后台渲染**：`480×800 RGB_565` 位图 + 全篇排版都在工作线程上，完成后回主线程换帧
     * （照 {@code NoteExport} 的 C2 拆法 —— 这种活留在主线程就是 ANR 风险）。
     */
    private void enterWallpaper() {
        final String m = billSection.shownMode();
        final long s = billSection.shownStart();
        // 🆕 2026-10-10 第四轮 ①：**在哪个菜单类别点生成，就出哪个类别的海报**
        final int v = billSection.view();
        if (m == null || s <= 0L) {
            showTip(getContext().getString(R.string.desk_wallpaper_none));
            return;
        }
        mode = MODE_WALLPAPER;
        scrollY = 0f;
        axis = AXIS_NONE;
        releasePreview();
        previewBusy = true;
        previewMode = m;
        previewStart = s;
        invalidate();

        final Context app = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BillWallpaper.Poster r = BillWallpaper.render(app, m, s, v);
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (mode != MODE_WALLPAPER) {          // 期间用户已退出 ⇒ 直接丢弃
                            if (r.bmp != null) r.bmp.recycle();
                            return;
                        }
                        previewBusy = false;
                        preview = r.bmp;
                        previewErr = (r.err == null) ? "" : r.err;
                        CardDebug.note(getContext(), "墨单壁纸渲染(" + MenuPrefs.VIEW_LABELS[
                                (v >= 0 && v < MenuPrefs.VIEW_LABELS.length) ? v : 0] + ")："
                                + (preview == null ? previewErr
                                : (r.width + "×" + r.height + " · " + r.itemDrawn + "/" + r.itemTotal + " 本")));
                        invalidate();
                    }
                });
            }
        }).start();
    }

    /**
     * 🆕 TASK-087-A2（用户诉求 ①）：进壁纸预览态 —— 这次渲染的是**阅读资产**整表
     * （{@link AssetPoster}），而不是墨单。
     *
     * <p>🔴 与 {@link #enterWallpaper()} **同一套态与同一条后台范式**：纯后台渲染 → 回主线程换帧；
     * 保存 / 分享 / 关闭三个按钮、落盘目录、分享面板全部复用（只有位图来源不同）。
     *
     * <p>🔴 落盘名基由 {@link #wpFrom} 决定（本路径必为 {@link #WP_SRC_ASSET}）⇒
     * {@link BillWallpaper#PREFIX_ASSET}（`yuezi_&lt;mode&gt;_&lt;start&gt;_&lt;ts&gt;.png`）；它在进后台线程
     * **之前**算好（`saveWallpaper()` :568-569 / `shareWallpaper()` :617-618）。{@code previewMode} /
     * {@code previewStart} 只提供 `&lt;mode&gt;` / `&lt;start&gt;` 两段，与画面内容无关；墨单分区没有
     * 当期口径时（本该不发生）退到**本周**，保证文件名始终合法。
     *
     * <p>旧 4 参 {@code BillWallpaper.save} 仍固定 `modan_*`，只由墨单路径使用。
     */
    private void enterAssetWallpaper() {
        String m = billSection.shownMode();
        long s = billSection.shownStart();
        if (m == null || s <= 0L) {
            m = PeriodRange.WEEKLY;
            s = PeriodRange.startOf(PeriodRange.WEEKLY, PeriodRange.nowSec());
        }
        final String fm = m;
        final long fs = s;
        // 🔴 壁纸跟随**当前菜单类别**（摘录 / 读书），与屏上同一档
        final int v = MenuPrefs.view(getContext());
        final int sortKey = assetSection.sortKey();

        mode = MODE_WALLPAPER;
        scrollY = 0f;
        axis = AXIS_NONE;
        releasePreview();
        previewBusy = true;
        previewMode = fm;
        previewStart = fs;
        wpFrom = WP_SRC_ASSET;
        invalidate();

        final Context app = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AssetPoster.Poster r = AssetPoster.render(app, sortKey, v);
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (mode != MODE_WALLPAPER) {          // 期间用户已退出 ⇒ 直接丢弃
                            if (r.bmp != null) r.bmp.recycle();
                            return;
                        }
                        previewBusy = false;
                        preview = r.bmp;
                        previewErr = (r.err == null) ? "" : r.err;
                        CardDebug.note(getContext(), "资产壁纸渲染(" + MenuPrefs.VIEW_LABELS[
                                (v >= 0 && v < MenuPrefs.VIEW_LABELS.length) ? v : 0] + ")："
                                + (preview == null ? previewErr
                                : (r.width + "×" + r.height + " · " + r.itemDrawn + "/" + r.itemTotal + " 本")));
                        if (preview == null && previewErr.length() > 0) showTip(previewErr);
                        invalidate();
                    }
                });
            }
        }).start();
    }

    /**
     * 🆕 TASK-089b：「阅读指纹」分区的「生成壁纸」——渲染**阅读指纹**海报（480×800）。
     *
     * <p>🔴 与 {@link #enterAssetWallpaper()} **同一套态、同一条后台范式**（纯后台渲染 → 回主线程
     * 换帧，期间用户退出则丢弃位图）；保存 / 分享 / 关闭三键、落盘目录、分享面板全部复用，
     * 只有两处不同：位图来源 = {@link FingerprintPoster#renderToBitmap}，落盘名基 =
     * {@link #WP_SRC_FP} ⇒ {@link BillWallpaper#PREFIX_FP}（`fp_&lt;mode&gt;_&lt;start&gt;_&lt;ts&gt;.png`）。
     *
     * <p>🔴 **渲染期零网络、零权限**：{@code renderToBitmap} 只读本地（缺字的书留白，不拉取）。
     */
    private void enterFpWallpaper() {
        final int w = FingerprintPoster.CANVAS_W;
        final int h = FingerprintPoster.CANVAS_H;
        mode = MODE_WALLPAPER;
        scrollY = 0f;
        axis = AXIS_NONE;
        releasePreview();
        previewBusy = true;
        previewMode = PeriodRange.WEEKLY;
        previewStart = PeriodRange.startOf(PeriodRange.WEEKLY, PeriodRange.nowSec());
        wpFrom = WP_SRC_FP;
        invalidate();

        final Context app = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = FingerprintPoster.SECTION.renderToBitmap(w, h, null);
                final String diag = FingerprintPoster.SECTION.statsLine();
                final String miss = FingerprintPoster.SECTION.hintText();
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (mode != MODE_WALLPAPER) {          // 期间用户已退出 ⇒ 直接丢弃
                            if (bmp != null) bmp.recycle();
                            return;
                        }
                        previewBusy = false;
                        preview = bmp;
                        previewErr = (bmp == null)
                                ? (miss == null ? "阅读指纹生成失败" : miss) : "";
                        CardDebug.note(getContext(), "指纹壁纸渲染：" + diag);
                        if (preview == null && previewErr.length() > 0) showTip(previewErr);
                        invalidate();
                    }
                });
            }
        }).start();
    }

    /** 出预览态（`‹ 返回` / 「关闭」）。 */
    private void exitWallpaper() {
        mode = MODE_LIST;
        scrollY = 0f;
        axis = AXIS_NONE;
        releasePreview();
        remeasure(getWidth(), getHeight());
        invalidate();
    }

    private void releasePreview() {
        if (preview != null) {
            try { preview.recycle(); } catch (Throwable ignored) { }
            preview = null;
        }
        previewBusy = false;
        previewErr = "";
        previewMode = null;
        previewStart = 0L;
        wpFrom = WP_SRC_BILL;
        btnSave.setEmpty();
        btnShare.setEmpty();
        btnClose.setEmpty();
        savedUri = null;
    }

    /** 保存当前预览（后台压缩落盘 → 回主线程报结果）。 */
    private void saveWallpaper() {
        if (preview == null || previewBusy) {
            // 🆕 第七批 W2（task-72）：这条分支原本**静默 return** —— 卡片诊断 (A) 指出它是
            //    唯一"连提示都没有"的路径（保存按钮已按下，却什么都不发生）。
            //    现在一律给屏上可见提示：生成中（或还没有错误文案，`previewErr` 缺省是空串）
            //    ⇒ 固定文案；已失败 ⇒ 逐字回显 `previewErr`。
            showTip(previewBusy || previewErr == null || previewErr.length() == 0
                    ? getContext().getString(R.string.desk_wallpaper_save_wait) : previewErr);
            return;
        }
        final Bitmap bmp = preview;
        final String m = previewMode;
        final long s = previewStart;
        // 🆕 TASK-087-W0：名基在**进线程之前**定死（后台线程里读 wpFrom 会与 releasePreview() 抢时序）。
        final String prefix = wpPrefix();
        final Context app = getContext().getApplicationContext();
        showTip(getContext().getString(R.string.desk_wallpaper_saving));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BillWallpaper.Saved r = BillWallpaper.save(app, bmp, m, s, prefix);
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (r.err != null) {
                            showTip(r.err);
                        } else if (r.uri != null) {
                            showTip(getContext().getString(R.string.desk_wallpaper_saved));
                        } else {
                            showTip(getContext().getString(R.string.desk_wallpaper_saved_priv,
                                    r.fallbackPath == null ? "?" : r.fallbackPath));
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 🆕 2026-10-10 用户 ⑤：分享当前墨单壁纸（照 {@code NoteExport.presentResult} 的范式）。
     *
     * <p>流程：预览已在手 ⇒ 先**确保落盘到相册**（复用 {@link BillWallpaper#save}，零新权限，
     * 只多一次 MediaStore 插入），拿到 uri 后回主线程 `ACTION_SEND` + `createChooser`
     * 交给别的应用（S4 上实测可收图的：蓝牙 OPP / 打印 / ntfy … ⇒ 蓝牙能直发手机）。
     *
     * <p>🔴 墨台是**无障碍覆盖窗**（宿主是 Service 不是 Activity）⇒ 起 Activity 必须带
     * {@code FLAG_ACTIVITY_NEW_TASK}（同 {@code CardContentController.tryStart} 先例）。
     * 🔴 本次预览已存过（{@link #savedUri} 非空）⇒ 直接分享，**不重复落盘**。
     */
    private void shareWallpaper() {
        if (preview == null || previewBusy) {
            // 🆕 第七批 W2（task-72）：与「保存到相册」同一纪律 —— 静默 return 一律改成可见提示。
            showTip(getContext().getString(R.string.desk_wallpaper_save_wait));
            return;
        }
        if (savedUri != null) {                 // 本页「保存到相册」已点过 ⇒ 直接分享
            startShare(savedUri);
            return;
        }
        final Bitmap bmp = preview;
        final String m = previewMode;
        final long s = previewStart;
        // 🆕 TASK-087-W0：与 saveWallpaper() 同口径 —— 名基进线程前定死（资产海报 ⇒ yuezi_*）。
        final String prefix = wpPrefix();
        final Context app = getContext().getApplicationContext();
        showTip(getContext().getString(R.string.desk_wallpaper_sharing));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BillWallpaper.Saved r = BillWallpaper.save(app, bmp, m, s, prefix);
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (mode != MODE_WALLPAPER) {        // 期间用户已退出 ⇒ 丢弃
                            return;
                        }
                        if (r.uri != null) {
                            savedUri = r.uri;
                            startShare(r.uri);
                        } else {
                            // 落盘失败（只剩私有目录）⇒ 如实说，绝不假装分享出去了
                            showTip(r.err != null ? r.err
                                    : getContext().getString(R.string.desk_wallpaper_share_none));
                        }
                    }
                });
            }
        }).start();
    }

    /** 起系统分享面板；本机没有能收图的应用（或起不来）⇒ 如实提示。 */
    private void startShare(Uri uri) {
        // 🆕 用户 ⑤：通知宿主一声（TASK-088 Phase 1 起宿主是 Activity，已无覆盖窗需要摘；
        //    见 Listener#onShareRequested 的说明）。保留调用点，宿主实现可留空。
        if (listener != null) listener.onShareRequested();
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send,
                    getContext().getString(R.string.desk_wallpaper_share_title));
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooser);
            CardDebug.note(getContext(), "墨单壁纸：起分享面板 uri=" + uri);
        } catch (Throwable t) {
            CardDebug.note(getContext(), "墨单壁纸：分享失败 " + t.getClass().getSimpleName());
            showTip(getContext().getString(R.string.desk_wallpaper_share_none));
        }
    }

    /**
     * 屏上自绘提示（🆕 第七批 W2 · task-72）。**不再用系统 `Toast`**。
     *
     * <p>🔴 实测（task-64 ⑤ 诊断）：墨台的宿主是**无障碍 Service**（不是 Activity），
     * 系统 `Toast` 被立刻回收 —— logcat 逐字
     * `W NotificationService: Toast already killed. pkg=com.inkread.weekread`；
     * 保存后 4 张截图与预览帧**同为 140,765 B（零 toast 像素）** ⇒ 用户判成"点了没反应"。
     * 改成画布自绘：文本交给 {@link #drawTip}，{@link #TIP_MS} 后自动消失。
     *
     * <p>顺带把 task-66 A2（长按书名移出统计）的反馈也接到这条路上 —— 它原先走 `Toast`，
     * 真机上同样不可见 ⇒「禁静默」在实测层面并未达成。
     */
    private void showTip(String s) {
        if (s == null || s.length() == 0) return;
        tipText = s;
        removeCallbacks(tipHide);
        postDelayed(tipHide, TIP_MS);
        postInvalidate();
    }

    /**
     * 🆕 task-72：提示条画在**最上层**（由 {@link #onDraw} 收尾调用，三个 return 都经过）。
     *
     * <p>几何（实测基准 480×800、`unit = 1.2`；`btnH = unit*34f`、`btnY = h − unit*14f − btnH`）：
     * 蓝牙分享提示墨迹占 `[btnY − 19u, btnY − 6u]`，预览框下沿 `availBot = btnY − 24u`。
     * 提示条**底边 = `h − unit*74f` ＝ `btnY − 26u`**、高 `unit*22f`、字号 `unit*13f`
     * ⇒ 与蓝牙提示顶留 `unit*7f`（≈8px）、与预览下沿留 `unit*2f`，与底部三个按钮
     * （`[btnY, btnY + unit*34f]`）**零重叠**；反白（黑底白字）在墨水屏上最醒目。
     */
    private void drawTip(Canvas c, int w, int h) {
        if (tipText == null || w <= 0 || h <= 0) return;
        final float pad = w * InsightRenderer.PAD_X_RATIO;
        final float boxH = unit * 22f;
        final float bot = h - unit * 74f;
        final float inner = w - pad * 2f - unit * 8f;
        tipPaint.setStyle(Paint.Style.FILL);
        tipPaint.setTypeface(null);
        tipPaint.setFakeBoldText(false);
        tipPaint.setTextAlign(Paint.Align.LEFT);
        tipPaint.setColor(0xFF000000);
        c.drawRect(pad, bot - boxH, w - pad, bot, tipPaint);
        float sz = 13f * unit;
        tipPaint.setColor(0xFFFFFFFF);
        tipPaint.setTextSize(sz);
        while (tipPaint.measureText(tipText) > inner && sz > 9f * unit) {
            sz -= 0.3f;
            tipPaint.setTextSize(sz);
        }
        String show = tipText;
        if (tipPaint.measureText(show) > inner) {                 // 极小屏兜底：截断加省略号
            int n = show.length();
            while (n > 4 && tipPaint.measureText(show.substring(0, n) + "…") > inner) n--;
            show = show.substring(0, n) + "…";
        }
        Paint.FontMetrics fm = tipPaint.getFontMetrics();
        c.drawText(show, pad + unit * 4f, bot - boxH / 2f - (fm.ascent + fm.descent) / 2f, tipPaint);
    }

    // ══════════════════════ 壁纸预览态：画 ══════════════════════

    private void drawWallpaper(Canvas c, int w, int h) {
        renderer.drawChrome(c, w, h, unit, updatedLabel);

        final float barH = renderer.headerHeight(unit);
        final float pad = w * InsightRenderer.PAD_X_RATIO;
        final float btnH = unit * 34f;
        final float btnY = h - unit * 14f - btnH;
        final Context ctx = getContext();

        // ① 图像（或"生成中 / 失败"文案）
        if (previewBusy || preview == null) {
            String msg = previewBusy ? ctx.getString(R.string.desk_wallpaper_rendering) : previewErr;
            InsightRenderer.drawCenteredIn(c, w, barH, btnY - barH, unit, wp, msg);
        } else {
            float availTop = barH + unit * 10f;
            // 🆕 第七批 W2（task-66）B①：预览框下沿**必须让开**蓝牙分享提示那一行。
            //    实测（480×800，unit=1.2）：提示基线在 `btnY − 6u − descent` ⇒ 提示墨迹占
            //    `[btnY − 19u, btnY − 6u]`，旧的 `btnY − 10u` 会让预览白底压住提示约 9u。
            //    改 `− 24u` ⇒ 与提示顶留 5u（=6px）净空；预览按 `sc` 自适应缩放，不截断内容。
            float availBot = btnY - unit * 24f;
            float availW = w - pad * 2f;
            float availH = availBot - availTop;
            if (availH < unit * 20f) availH = unit * 20f;
            float sc = Math.min(availW / preview.getWidth(), availH / preview.getHeight());
            float dw = preview.getWidth() * sc, dh = preview.getHeight() * sc;
            float l = (w - dw) / 2f;
            float t = availTop + (availH - dh) / 2f;
            wp.setStyle(Paint.Style.FILL);
            wp.setColor(0xFFFFFFFF);
            c.drawRect(l, t, l + dw, t + dh, wp);              // 白底（海报本身也是白底）
            c.drawBitmap(preview, null, new RectF(l, t, l + dw, t + dh), wp);
            wp.setStyle(Paint.Style.STROKE);
            wp.setStrokeWidth(1f);
            wp.setColor(0xFFD8D8D8);
            c.drawRect(l + 0.5f, t + 0.5f, l + dw - 0.5f, t + dh - 0.5f, wp);
            wp.setStyle(Paint.Style.FILL);
        }

        // ①′ 🆕 2026-10-10 第五轮（诉求 1 · 用户真机实测反馈）：**分享小字提示**。
        //    为什么必须提前摆：蓝牙接收方的提示**只躺在它自己的通知栏里**，不点就"看着像失败"
        //    —— 用户上一次就是这么判成"分享失败"的（实测：连着蓝牙 + 去通知栏点接收 = 成功）。
        final String btTip = ctx.getString(R.string.desk_wallpaper_bt_tip);
        wp.setStyle(Paint.Style.FILL);
        wp.setTypeface(null);
        wp.setFakeBoldText(false);
        wp.setColor(0xFF3C3C3C);
        wp.setTextAlign(Paint.Align.LEFT);
        float tipSz = 11.5f * unit;
        wp.setTextSize(tipSz);
        while (wp.measureText(btTip) > w - pad * 2f && tipSz > 9f * unit) {
            tipSz -= 0.3f;
            wp.setTextSize(tipSz);
        }
        String tipShow = btTip;
        if (wp.measureText(tipShow) > w - pad * 2f) {                 // 极小屏兜底：截断加省略号
            int n = tipShow.length();
            while (n > 4 && wp.measureText(tipShow.substring(0, n) + "…") > w - pad * 2f) n--;
            tipShow = tipShow.substring(0, n) + "…";
        }
        Paint.FontMetrics tfm = wp.getFontMetrics();
        c.drawText(tipShow, pad, btnY - unit * 6f - tfm.descent, wp);
        wp.setTextAlign(Paint.Align.LEFT);

        // ② 底部三个按钮（矩形每帧重算；触摸判定就吃这一份 ⇒ 画与命中一致）
        //    🆕 2026-10-10 用户 ⑤：「保存到相册 / 分享 / 关闭」—— 分享 = 先落盘再 ACTION_SEND
        float gap = unit * 10f;
        float bw = (w - pad * 2f - gap * 2f) / 3f;
        btnSave.set(pad, btnY, pad + bw, btnY + btnH);
        btnShare.set(pad + bw + gap, btnY, pad + bw + gap + bw, btnY + btnH);
        btnClose.set(pad + (bw + gap) * 2f, btnY, w - pad, btnY + btnH);
        drawBtn(c, btnSave, ctx.getString(R.string.desk_wallpaper_save), unit, preview != null);
        drawBtn(c, btnShare, ctx.getString(R.string.desk_wallpaper_share), unit, preview != null);
        drawBtn(c, btnClose, ctx.getString(R.string.desk_wallpaper_close), unit, true);
        wp.setTextAlign(Paint.Align.LEFT);
        wp.setFakeBoldText(false);
        wp.setTypeface(null);
    }

    /** 墨屏大按钮：1px 直角描边 + 居中文字（禁用 ⇒ 浅灰）。 */
    private void drawBtn(Canvas c, RectF r, String label, float unit, boolean enabled) {
        wp.setStyle(Paint.Style.STROKE);
        wp.setStrokeWidth(1f);
        wp.setColor(enabled ? 0xFF000000 : 0xFFA8A8A8);
        c.drawRect(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, wp);
        wp.setStyle(Paint.Style.FILL);
        wp.setTypeface(null);
        wp.setFakeBoldText(false);
        wp.setTextAlign(Paint.Align.CENTER);
        wp.setColor(enabled ? 0xFF000000 : 0xFFA8A8A8);
        float sz = 14f * unit;
        wp.setTextSize(sz);
        while (wp.measureText(label) > r.width() - unit * 10f && sz > 11f * unit) {
            sz -= 0.4f;
            wp.setTextSize(sz);
        }
        Paint.FontMetrics fm = wp.getFontMetrics();
        c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, wp);
        wp.setTextAlign(Paint.Align.LEFT);
    }

    // ══════════════════════ 🆕 TASK-086 第六批B：阅读资产头部命中 ══════════════════════
    // 🔴 D25 取舍：原「读书排行」有内滚（`dragRank` + `RANK_VIEWPORT_RATIO` 视口），
    //    换成「阅读资产」时先去掉了内滚（内容随整页滚）。
    // 🆕 TASK-087-A1（2026-10-12，用户 ③ 拍板）：**重新加回内滚**，但形态按用户要求 =
    //    「抬头（标题 + 三键 + 生成壁纸）固定 + 中间条目内滚 + 尾部（还有 N 本 / 展开收起 / 合计）固定」；
    //    手指拖动跟手、松手停在原地（无惯性）；**内滚到边界必须回落给整页滚动**。
    //    参见 `dragAsset` 与 `AssetSection.scrollBy/maxInner/canScroll`。

    /** 阅读资产分区**顶边**的屏幕坐标 y；-1 = 分区不在分区表里（模块关了 / 没料）。 */
    private float assetTop() {
        int idx = renderer.indexOf(assetSection);
        if (idx < 0) return -1f;
        return renderer.sectionTop(idx, getWidth(), getHeight(), unit) - scrollY;
    }

    /**
     * 🆕 TASK-087-A1：阅读资产**内滚视口**顶边的屏幕坐标 y（= 分区顶边 + 抬头高）；
     * `-1f` = 分区不在分区表里。给"拖动是否落在内容区"的判定用（尾部/抬头不在其中）。
     */
    private float assetBodyTop() {
        float top = assetTop();
        if (top == -1f) return -1f;
        return top + InsightRenderer.secHeadH(unit);
    }

    /**
     * 阅读资产**头部条**命中：排序三键 → 展开/收起。
     *
     * <p>顺序即优先级：三键矩形与展开行不重叠，但先判键位更稳（键位是 3 个小矩形，
     * 展开行是整行宽）。命中后立刻 `remeasure` + `invalidate`（排序/展开都改变行数 ⇒ 高度变）。
     *
     * <p>🔴 守卫**只拦哨兵 `-1f`**（分区不在分区表里）：阅读资产折叠态高度 ≈805 px > 屏高 800 px，
     * 「展开 ▽」可见时头部必然已滚到视口上方（`top` 为负）——若把负数也当哨兵，展开/收起控件
     * 在它唯一可见的状态下永远不可达（用户症状：「展开 ▽」点了没反应）。
     * 头部条以外的越界由 `hitSort`/`hitToggle` 的矩形判据自行处理（矩形落在视口外自然不命中）。
     * 注意：账单侧 `hitBillTab`/`hitBillHead` 的守卫**保持原样**（它们位于分区顶部，
     * 顶边为负时控件确实已出屏）。
     *
     * @return true = 已消费本次点击（调用方不要再往下走别的命中链）
     */
    private boolean hitAssetHead(float x, float y) {
        float top = assetTop();
        if (top == -1f) return false;
        int key = assetSection.hitSort(x, y, getWidth(), unit, top);
        if (key >= 0) {
            if (assetSection.setSort(key)) {           // 同键点击 ⇒ false ⇒ 不白刷一次
                remeasure(getWidth(), getHeight());
                invalidate();
            }
            return true;
        }
        // 🆕 TASK-087-A1：抬头最右端「生成壁纸」——A1 只登记「存在 + 可命中」，
        //    🆕 TASK-087-A2 起动作落地：进壁纸预览态、渲染**阅读资产**海报（不再是空动作）。
        //    放在三键之后、展开行之前：两者与它都不重叠（`sortRects` 里已留 4×unit 间距）。
        if (assetSection.hitWall(x, y, getWidth(), unit, top)) {
            enterAssetWallpaper();
            return true;
        }
        if (assetSection.hitToggle(x, y, getWidth(), getHeight(), unit, top)) {
            assetSection.toggleOpen();
            remeasure(getWidth(), getHeight());
            invalidate();
            return true;
        }
        return false;
    }

    // ══════════════════════ 🆕 TASK-089b：阅读指纹分区头部命中 ══════════════════════

    /** 阅读指纹分区**顶边**的屏幕坐标 y；-1 = 分区不在分区表里（模块关了）。 */
    private float fpTop() {
        int idx = renderer.indexOf(FingerprintPoster.SECTION);
        if (idx < 0) return -1f;
        return renderer.sectionTop(idx, getWidth(), getHeight(), unit) - scrollY;
    }

    /**
     * 🆕 TASK-089b：阅读指纹**控制行**命中 —— 字号 4 档 → 选书 → 生成壁纸。
     *
     * <p>顺序即优先级：三个矩形互不相交（{@link FingerprintPoster} 里 4 个字号码左对齐、
     * 「选书」右对齐到「生成壁纸」左侧，间距 8×unit）⇒ 点字号绝不会误触「生成壁纸」。
     *
     * <p>🔴 字号切换**零请求**：只写 prefs + 让分区按新字号重排（{@code refresh} 的 stateKey
     * 含字号 ⇒ 才会真重算）。
     */
    private boolean hitFpHead(float x, float y) {
        float top = fpTop();
        if (top == -1f) return false;
        final float w = getWidth();
        int f = FingerprintPoster.SECTION.hitFont(x, y, w, unit, top);
        if (f >= 0) {
            final int[] ch = PagePrefs.FP_FONT_CHOICES;
            if (f < ch.length) {
                PagePrefs.setFpFontPx(getContext(), ch[f]);
                FingerprintPoster.SECTION.refresh(getContext());
                remeasure(getWidth(), getHeight());
                invalidate();
            }
            return true;
        }
        if (FingerprintPoster.SECTION.hitPick(x, y, w, unit, top)) {
            pickFpBooks();
            return true;
        }
        if (FingerprintPoster.SECTION.hitWall(x, y, w, unit, top)) {
            enterFpWallpaper();
            return true;
        }
        return false;
    }

    /**
     * 🆕 TASK-089b（Lead (b′) 裁定）：**本记选书** —— 标准 {@link Dialog}。
     *
     * <p>为什么是 {@code Dialog} 而不是覆盖层菜单：墨台自 TASK-088 Phase 1 起跑在
     * {@code shell.DeskActivity}（Activity 形态）里，{@code Dialog} 窗默认可获焦 ⇒
     * {@code EditText} + 系统输入法直接可用；且 {@code Dialog} **不触发 Activity.onPause**，
     * 不会误走「让位链」。（旧的覆盖窗菜单 `OverlayController.paramsMenu` 是
     * {@code FLAG_NOT_FOCUSABLE}，收不了键入 ⇒ 不能复用。）
     *
     * <p>UI 全部**代码构建**（本卡写域不含 {@code res/layout}）：搜索框 + 滚动多选 + 清空/取消/确定。
     * 数据源 {@link NoteStore#bookList} = **本地索引**（零新请求）；写回 {@link PagePrefs#setFpBooks}
     * （上限 50 本，由 prefs 侧截断）。
     */
    private void pickFpBooks() {
        final Context ctx = getContext();
        if (!(ctx instanceof android.app.Activity)) {   // 旧覆盖窗形态：不打对话框，如实说
            showTip("选书需在墨台页内使用（Activity 形态）");
            return;
        }
        final List<NoteStore.BookRef> all = NoteStore.bookList(ctx);
        if (all.isEmpty()) {
            showTip("还没有可选的本地书");
            return;
        }
        final LinkedHashSet<String> sel = new LinkedHashSet<String>(PagePrefs.fpBooks(ctx));

        final Dialog dlg = new Dialog(ctx);
        final LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        final EditText search = new EditText(ctx);
        search.setHint("搜索书名");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        // 🆕 TASK-089b（Lead m04008 第 5 条）：显示总数与已展示条数 —— 屏上有 300 条上限闸，
        //    但不许让用户以为"只有 300 本"。
        final TextView info = new TextView(ctx);
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ScrollView sv = new ScrollView(ctx);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final LinearLayout bar = new LinearLayout(ctx);
        final Button clear = new Button(ctx);
        clear.setText("清空");
        final Button cancel = new Button(ctx);
        cancel.setText("取消");
        final Button ok = new Button(ctx);
        ok.setText("确定");
        bar.addView(clear);
        bar.addView(cancel);
        bar.addView(ok);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg.setContentView(root);

        final Runnable refill = new Runnable() {
            @Override
            public void run() {
                final String q = search.getText().toString().trim();
                box.removeAllViews();
                int shown = 0;
                int match = 0;
                for (int i = 0; i < all.size(); i++) {
                    final NoteStore.BookRef b = all.get(i);
                    final String t = (b.title == null) ? b.id : b.title;
                    if (q.length() > 0 && t.indexOf(q) < 0) continue;
                    match++;
                    if (shown >= 300) continue;                 // 屏上条目上限（防超大书库卡顿）
                    final CheckBox cb = new CheckBox(ctx);
                    cb.setText(t + (b.ready ? "" : "（未同步）"));
                    cb.setChecked(sel.contains(b.id));
                    cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                            if (on) sel.add(b.id); else sel.remove(b.id);
                            // 勾选即时反映到「确定」按钮（不重建列表 ⇒ 不跳滚动位）
                            ok.setText("确定（已选 " + sel.size() + "）");
                        }
                    });
                    box.addView(cb);
                    shown++;
                }
                // 🔴 如实报数：总数 / 匹配数 / 已展示数三档都要能看见（上限 300 不许"藏书"）。
                info.setText("共 " + all.size() + " 本" + (q.length() > 0 ? "，匹配 " + match + " 本" : "")
                        + "，显示 " + shown + " 本"
                        + (match > shown ? "（上限 300，用搜索缩小范围）" : "")
                        + "；已选 " + sel.size() + " 本");
                ok.setText("确定（已选 " + sel.size() + "）");
                if (shown == 0) {
                    final TextView tv = new TextView(ctx);
                    tv.setText("没有匹配的书");
                    box.addView(tv);
                }
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refill.run();
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sel.clear();
                refill.run();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PagePrefs.setFpBooks(getContext(), sel);
                FingerprintPoster.SECTION.refresh(getContext());
                remeasure(getWidth(), getHeight());
                invalidate();
                dlg.dismiss();
                kickFpSync();                                   // 未就绪的书才起同步（零网络 ⇒ 有书缺料才拉）
            }
        });
        refill.run();
        dlg.show();
        Window win = dlg.getWindow();
        if (win != null) {   // 🔴 只设窗级软键盘模式，**不动** AndroidManifest（写域外）
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        if (sel.isEmpty()) search.requestFocus();
    }

    /**
     * 🆕 TASK-089b：选中书里有「本地无划线」的 ⇒ 起一轮 {@link NoteSync}（范式照
     * {@code a11y/CardContentController#noteSync}：无 Key 如实提示、in-flight 去重、不加退避强拉）。
     *
     * <p>🔴 全类**唯一**起网络的地方，且只由用户点「确定」触发；渲染期一律零网络。
     */
    private void kickFpSync() {
        final List<String> pend = FingerprintPoster.SECTION.pendingBooks();
        if (pend.isEmpty()) return;
        final Context ctx = getContext();
        final String key = StatsStore.getKey(ctx);
        if (key == null || key.trim().length() == 0) {
            showTip("选中的书有 " + pend.size() + " 本还没同步；先填 API Key 才能拉取");
            return;
        }
        if (NoteSync.isRunning()) return;
        showTip("正在拉取选中书的划线…");
        NoteSync.sync(ctx, key, false, NoteSync.DEFAULT_PREFETCH, new NoteSync.Listener() {
            @Override
            public void onDone(int pool, int ideas, int total, String error, int state) {
                FingerprintPoster.SECTION.refresh(getContext());
                remeasure(getWidth(), getHeight());
                invalidate();
                if (state != NoteSync.STATE_OK && error != null && error.length() > 0) showTip(error);
            }
        });
    }

    // ══════════════════════ 🆕 TASK-077：账单分区头部命中 ══════════════════════

    /** 账单分区**顶边**的屏幕坐标 y；-1 = 账单分区不在分区表里（模块关了）。 */
    private float billTop() {
        int idx = renderer.indexOf(billSection);
        if (idx < 0) return -1f;
        return renderer.sectionTop(idx, getWidth(), getHeight(), unit) - scrollY;
    }

    /**
     * 点一下账单页签条 ⇒ 在「摘录菜单 / 读书菜单」之间**循环**切换
     * （🆕 task-40：月历已下架 ⇒ 恒 2 档，由 `visibleViews()` 决定）。
     *
     *
     * <p>🔴 切换**零额外请求**：各视图共用同一份 {@code Bill}（`BillStore`），只是画法不同；
     * 分会变高变矮 ⇒ 切完要 remeasure + 重绘。
     *
     * @return true = 这次点击被页签条吃掉了（调用方不要再当"返回"处理）
     */
    private boolean hitBillTab(float x, float y) {
        float top = billTop();
        if (top < 0f) return false;
        if (!billSection.hitTab(x, y, getWidth(), unit, top)) return false;
        int[] vis = billSection.visibleViews();
        int cur = billSection.view();
        int pos = 0;
        for (int i = 0; i < vis.length; i++) {
            if (vis[i] == cur) pos = i;
        }
        int next = vis[(pos + 1) % vis.length];
        billSection.setView(getContext(), next);
        remeasure(getWidth(), getHeight());
        invalidate();
        return true;
    }

    /**
     * 🆕 2026-10-10：点墨单头部的「生成壁纸」/ `[周|月]` / `◀ ▶`。
     *
     * @return true = 这次点击被墨单头部吃掉了
     */
    private boolean hitBillHead(float x, float y) {
        float top = billTop();
        if (top < 0f) return false;

        if (billSection.hitWallpaper(x, y, getWidth(), unit, top)) {
            enterWallpaper();
            return true;
        }
        int dir = billSection.hitArrow(x, y, getWidth(), unit, top);
        if (dir != 0) {
            if (billSection.shiftPeriod(dir)) {
                remeasure(getWidth(), getHeight());
                invalidate();
            }
            return true;
        }
        String m = billSection.hitMode(x, y, getWidth(), unit, top);
        if (m != null) {
            if (billSection.switchMode(m)) {
                remeasure(getWidth(), getHeight());
                invalidate();
            }
            return true;
        }
        return false;
    }

    // ══════════════════════ 触控：单指纵滚 + 左滑返回（模态，恒吃手势）══════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastY = downY;
                totDx = 0f;
                totDy = 0f;
                axis = AXIS_NONE;
                // 🔴 命中判定**只在按下那一刻做一次** —— 之后整段滑动都归它，避免半途改判导致跳变。
                // 🔴 2026-10-11（TASK-086 第六批B / D25）：原「落在排行区 ⇒ 手势只驱动区内滚」的
                //    `dragRank` 判定已删除 ——「阅读资产」没有内滚，列表态滑动恒为整页滚动。
                // 🆕 TASK-086：数字键盘是**模态** —— 开着时整段按下只归键盘（不滚、不点、不返回）
                padModal = (mode == MODE_LIST) && (billSection.padOpen() || assetSection.padOpen());
                if (padModal) return true;
                // 🆕 TASK-086：长按无价书目行 ⇒ 自绘数字键盘（从按下这一刻起算）
                // 🆕 第七批 W2（task-66）：阅读资产同样两处可长按，**按 x 严格分列**：
                //    书名区（左）= 移出统计；原价/「免费」区（右）= 录价键盘。
                pendingPriceIdx = -1;
                longKind = LP_NONE;
                longBookId = null;
                longFired = false;
                if (mode == MODE_LIST) {
                    final float bt = billTop();
                    if (bt >= 0f) {
                        pendingPriceIdx = billSection.hitPriceRow(downX, downY, getWidth(), unit, bt);
                        if (pendingPriceIdx >= 0) longKind = LP_BILL_PRICE;
                    }
                    if (longKind == LP_NONE) {
                        final float at = assetTop();      // -1f = 分区不在分区表；滚出屏上方时为负值，仍需可命中
                        if (at != -1f) {
                            int row = assetSection.hitRow(downX, downY, getWidth(), unit, at, true);
                            if (row >= 0) {
                                longKind = LP_ASSET_PRICE;
                            } else {
                                row = assetSection.hitRow(downX, downY, getWidth(), unit, at, false);
                                if (row >= 0) longKind = LP_ASSET_TITLE;
                            }
                            if (longKind != LP_NONE) {
                                pendingPriceIdx = row;
                                // 🔴 立刻把书 id 定住（见 `longBookId` 注释）：550ms 里若发生一次刷新，
                                //    行下标会指到别的书 —— 那是「把别人的书移出统计」的可见错误。
                                longBookId = assetSection.bookIdAt(row);
                            }
                        }
                    }
                    if (longKind != LP_NONE) postDelayed(longPressRun, LONG_PRESS_MS);
                }
                // 🆕 TASK-087-A1：区内滚判定**只在按下这一刻做一次**（照 `InsightPageView.dragRank` 范式）。
                //    条件 = 列表态 + 阅读资产当前可滚（`maxInner > 0`） + 按点落在**内滚视口**里
                //    （抬头/尾部不算 —— 那两块是固定的，按在上面应当照旧走整页滚动 / 点击）。
                dragAsset = false;
                if (mode == MODE_LIST && assetSection.canScroll(getHeight(), unit)) {
                    final float at = assetTop();
                    if (at != -1f && assetSection.inViewport(downY, at, getHeight(), unit)) dragAsset = true;
                }
                // 🔴 2026-10-11（TASK-18）：设置态的「按住白纱滑条 ⇒ 只拖滑条」分支已随白纱删除
                //     ⇒ 设置态与列表态共用同一套滚动手势（不再有"按住即拖"的特例）。
                return true;

            case MotionEvent.ACTION_MOVE: {
                totDx = e.getX() - downX;
                totDy = e.getY() - downY;
                // 🆕 TASK-086：手指一动就不算长按；长按已开键盘 ⇒ 移动也归键盘（吃掉）
                if (longKind != LP_NONE) {
                    removeCallbacks(longPressRun);
                    pendingPriceIdx = -1;
                    longKind = LP_NONE;
                    longBookId = null;
                }
                if (longFired) return true;
                if (mode == MODE_WALLPAPER) return true;      // 预览态不滚
                if (axis == AXIS_NONE) {
                    if (Math.max(Math.abs(totDx), Math.abs(totDy)) > SLOP_PX) {
                        axis = (Math.abs(totDx) > Math.abs(totDy)) ? AXIS_HORIZ : AXIS_VERT;
                    }
                }
                if (axis == AXIS_VERT) {
                    float mv = lastY - e.getY();     // 手指上滑 ⇒ mv > 0 ⇒ 内容上移
                    // 🆕 TASK-087-A1：先喂「阅读资产」区内滚；**到边界**（`scrollBy` 返回 false =
                    //    本次量一点没吃下）就把这一段剩余量**回落给整页滚动**（用户 ③ 拍板：
                    //    "内滚到边界后回落整页滚动"）。区内滚只要真动了就不动整页（防双滚）。
                    boolean handled = false;
                    if (dragAsset) {
                        handled = assetSection.scrollBy(mv, getHeight(), unit);
                        if (handled) invalidate();
                    }
                    if (!handled) {
                        float next = scrollY + mv;
                        if (next < 0f) next = 0f;
                        if (next > maxScroll) next = maxScroll;     // 到顶/到底夹住，不穿透、不回弹
                        if (next != scrollY) {
                            scrollY = next;
                            invalidate();
                        }
                    }
                }
                lastY = e.getY();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                final boolean up = (e.getActionMasked() == MotionEvent.ACTION_UP);
                // 🆕 TASK-086：长按收尾 —— 已开键盘 ⇒ 这一次抬手不再当"点击"
                removeCallbacks(longPressRun);
                // 🆕 TASK-087-A1：手势结束 ⇒ 清区内滚锁（下次按下重新判定）
                dragAsset = false;
                if (longFired) {
                    longFired = false;
                    pendingPriceIdx = -1;
                    longKind = LP_NONE;
                    longBookId = null;
                    axis = AXIS_NONE;
                    return true;
                }
                pendingPriceIdx = -1;
                longKind = LP_NONE;
                longBookId = null;

                // ── 壁纸预览态：只有「保存 / 分享 / 关闭 / 返回 ›」四个动作 ──
                if (mode == MODE_WALLPAPER) {
                    if (up && axis == AXIS_NONE) {
                        if (renderer.hitBack(e.getX(), e.getY(), getWidth(), unit)
                                || btnClose.contains(e.getX(), e.getY())) {
                            exitWallpaper();
                        } else if (btnSave.contains(e.getX(), e.getY())) {
                            saveWallpaper();
                        } else if (btnShare.contains(e.getX(), e.getY())) {
                            shareWallpaper();          // 🆕 2026-10-10 用户 ⑤
                        }
                    }
                    axis = AXIS_NONE;
                    return true;
                }

                // ── 设置态：横滑 = 回列表（不是关墨台，避免误退）──
                if (mode == MODE_SETTINGS) {
                    if (axis == AXIS_HORIZ && totDx <= -Math.max(SLOP_PX, getWidth() * BACK_RATIO)) {
                        exitSettings();
                    } else if (up && axis == AXIS_NONE) {
                        if (renderer.hitBack(e.getX(), e.getY(), getWidth(), unit)) {
                            exitSettings();
                        } else {
                            DeskSettings.Item it = settings.hitItem(e.getX(),
                                    e.getY() - renderer.headerHeight(unit) + scrollY, getWidth(), unit);
                            final boolean changed = settings.activate(it);
                            // 🆕 诉求 ②：「应用」的结果立刻弹一条（墨屏重绘慢，光靠面板里的状态行太迟）
                            final String tip = settings.consumeToast();
                            if (tip != null) showTip(tip);
                            if (changed) {
                                // 🆕 第七批 W2（task-66）：设置里改了「只在架」或恢复了隐藏书目 ⇒
                                //    阅读资产必须**本地重算**（`reload` 零网络；不含 `AssetRefresher`）。
                                assetSection.reload(getContext());
                                remeasure(getWidth(), getHeight());
                                invalidate();
                            }
                        }
                    }
                    axis = AXIS_NONE;
                    return true;
                }

                // ── 列表态 ──
                // 🆕 TASK-086：数字键盘**优先级最高**（模态）—— 开着时只有键位/关闭两种结局
                if (up && (billSection.padOpen() || assetSection.padOpen())) {
                    // 🆕 第七批 W2（task-66）：阅读资产的录价键盘与墨单**共用** `PricePad` 实现，
                    //    但各自持有实例 ⇒ 这里按"谁开着"路由（同一时刻只可能一个开着）。
                    final boolean assetPad = assetSection.padOpen();
                    int key = assetPad
                            ? assetSection.hitPad(e.getX(), e.getY(), getWidth(), getHeight(), unit)
                            : billSection.hitPad(e.getX(), e.getY(), getWidth(), getHeight(), unit);
                    if (key >= 0) {
                        if (assetPad) assetSection.padPress(key);
                        else billSection.padPress(key);
                        // 🆕 收口 F1（2026-10-10）：键盘**关闭**这一下会真正改到行数据
                        //   （确定 = 录价/改价/清除；取消 = 无改动）⇒ 必须重排版。
                        //   🔴 为什么只在这一下做：数字键只改 `padText`，与行文本无关，
                        //      每键都 remeasure 是白花墨屏重绘（`remeasure` 要重算整篇行高）。
                        //   🔴 为什么必须调：`BillSection` 的行文本是 `laid()` 期算好并**按 `cw/cu`
                        //      缓存**的 ⇒ 只 `invalidate()` 会把**旧**行数据重画一遍
                        //      （审查 `验证记录/202` G1 实测「点确定后当前帧逐字节不变」）。
                        //      数据侧的 `invalidateRows()` 在 `padPress` 里已调，这里补"高度/可滚"。
                        //   🆕 W2：阅读资产侧的手填价还会改变**排序（原价/收入降序）**与合计
                        //      ⇒ `AssetSection.padPress` 内部已 `reload`（重算价格链），这里同样补高度。
                        if (!billSection.padOpen() && !assetSection.padOpen()) remeasure(getWidth(), getHeight());
                        invalidate();
                    }
                    axis = AXIS_NONE;
                    return true;
                }
                if (axis == AXIS_HORIZ
                        && totDx <= -Math.max(SLOP_PX, getWidth() * BACK_RATIO)) {
                    if (listener != null) listener.onDeskClose();       // 左滑返回
                } else if (up && axis == AXIS_NONE) {
                    // 🆕 TASK-086：收入算法下拉（**最优先**：弹层开着时它还负责"点外面收起"）
                    final float bTop = billTop();
                    int algoHit = (bTop >= 0f)
                            ? billSection.hitAlgo(e.getX(), e.getY(), getWidth(), unit, bTop)
                            : BillSection.ALGO_MISS;
                    if (algoHit >= 0 && algoHit <= 2) {
                        if (billSection.pickAlgo(algoHit)) {
                            remeasure(getWidth(), getHeight());     // 列数 / 合计行变了 ⇒ 分会变
                        }
                        invalidate();
                    } else if (algoHit == BillSection.ALGO_TOGGLE) {
                        billSection.setAlgoOpen(true);
                        invalidate();
                    } else if (algoHit == BillSection.ALGO_DISMISS) {
                        billSection.setAlgoOpen(false);
                        invalidate();
                    } else
                    // 顶栏右端「设置」最优先（它最靠边，不与其它命中区重叠）
                    if (renderer.hitAction(e.getX(), e.getY(), getWidth(), unit)) {
                        enterSettings();
                    } else if (hitBillHead(e.getX(), e.getY())) {
                        // 🆕 2026-10-10：墨单头部（生成壁纸 / 周月 / 周期箭头）
                    } else if (hitBillTab(e.getX(), e.getY())) {
                        // 🆕 TASK-077：点账单页签条 ⇒ 切菜单（优先于"返回"判定，两者区域不重叠）
                    } else if (hitAssetHead(e.getX(), e.getY())) {
                        // 🆕 TASK-086 第六批B：阅读资产头部（排序三键 / 展开·收起）
                        //    🔴 与账单头、页签条互不重叠（分属不同分区），先后无碍；
                        //       「‹ 返回」必须留在最后（它最靠边、面积最小）。
                    } else if (hitFpHead(e.getX(), e.getY())) {
                        // 🆕 TASK-089b：阅读指纹控制行（字号 4 档 / 选书 / 生成壁纸）
                        //    🔴 同理：本行矩形与其它分区不重叠，守在「‹ 返回」之前。
                    } else if (renderer.hitBack(e.getX(), e.getY(), getWidth(), unit)) {
                        if (listener != null) listener.onDeskClose();   // 点 `‹ 返回`
                    }
                }
                axis = AXIS_NONE;
                return true;
            }

            default:
                return true;     // 模态：其余动作也吃掉，绝不穿透到桌面
        }
    }
    // 🔴 2026-10-11（TASK-18）：原先这里还有 `setVeilFromX(float)`（白纱滑条落盘）——
    //    已随「背景」组与白纱滑条整体删除。
}
