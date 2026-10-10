package com.inkread.weekread.feature;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.inkread.weekread.R;
import com.inkread.weekread.core.BgImageUtil;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.PagePrefs;

import java.text.SimpleDateFormat;
import java.util.Date;
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
    }

    private final DeskRenderer renderer = new DeskRenderer();

    /**
     * 「读书排行」分区常驻实例（TASK-076）—— 内含内滚位移，装配时复用同一份。
     * 🔴 与 {@link InsightPageView} 同做法：换料只调 `setItems`，不 new（new 会把内滚位移丢掉）。
     */
    private final InsightRenderer.RankSection rankSection = DeskModules.rankSection();

    /**
     * 🆕 TASK-077：「墨单」分区常驻实例 —— 头部条（页签 / 周期 / 生成壁纸）的命中判定要用它。
     * 与 {@link #rankSection} 同理：装配时复用同一份，不 new。
     */
    private final BillSection billSection = DeskModules.billSection();

    /** 🆕 2026-10-10：墨台设置态的自绘面板（用户诉求 ①）。 */
    private final DeskSettings settings = new DeskSettings(getContext());

    private Listener listener;

    private float unit;

    private int mode = MODE_LIST;

    private float scrollY = 0f;
    private float maxScroll = 0f;

    private Bitmap bg;
    private String bgKey = null;

    private String updatedLabel = "";

    // ── 墨单壁纸预览（MODE_WALLPAPER）──
    private final Paint wp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap preview;
    private String previewErr = "";
    private boolean previewBusy = false;
    private String previewMode = null;
    private long previewStart = 0L;
    private final RectF btnSave = new RectF();
    private final RectF btnShare = new RectF();   // 🆕 2026-10-10 用户 ⑤：分享（发到手机）
    private final RectF btnClose = new RectF();
    /** 🆕 2026-10-10 用户 ⑤：本次预览**已落盘**得到的相册 uri —— 分享复用它，避免重复落盘。 */
    private Uri savedUri;

    // ── 手势 ──
    private float downX, downY, lastY;
    private float totDx, totDy;
    private int axis = AXIS_NONE;
    /** 🆕 TASK-076：本次手势是否**落在排行区视口内**（手势分区 —— 决定这次滑动谁吃）。 */
    private boolean dragRank = false;
    /** 🆕 设置态：本次手势是否在拖白纱滑条（按住即拖，不走滚动）。 */
    private boolean dragVeil = false;

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

    /** 每次呼出都从干净状态开始：回列表态 + 滚动归顶 + 背景重读 + 模块重装 + 「更新于」= 现在。 */
    public void reset() {
        mode = MODE_LIST;
        scrollY = 0f;
        axis = AXIS_NONE;
        dragRank = false;
        dragVeil = false;
        releasePreview();
        bg = null;               // 每次呼出重新读背景图（用户可能换了同名文件，缓存不能赖着）
        bgKey = null;
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
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        String key = PagePrefs.getDeskBgPath(getContext());
        if (bgKey == null || !bgKey.equals(key)) {
            bgKey = key;
            bg = BgImageUtil.load(getContext(), key, w, h);
        }
        int veil = PagePrefs.getDeskBgVeil(getContext());
        applyChrome();

        if (mode == MODE_WALLPAPER) {
            drawWallpaper(c, w, h, veil);
            return;
        }

        remeasure(w, h);     // 每帧自纠：分区高 / 设置行可能随字号、文案、偏好变化
        if (mode == MODE_SETTINGS) {
            renderer.drawChrome(c, w, h, unit, bg, veil, updatedLabel);
            float barH = renderer.headerHeight(unit);
            c.save();
            c.clipRect(0f, barH, w, h);                  // 与列表态同一套"吸顶"裁剪纪律
            settings.draw(c, w, unit, barH - scrollY);
            c.restore();
            return;
        }
        renderer.draw(c, w, h, unit, scrollY, bg, veil, updatedLabel);
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
        dragVeil = false;
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
        if (m == null || s <= 0L) {
            toast(getContext().getString(R.string.desk_wallpaper_none));
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
                final BillWallpaper.Poster r = BillWallpaper.render(app, m, s);
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
                        CardDebug.note(getContext(), "墨单壁纸渲染：" + (preview == null ? previewErr
                                : (r.width + "×" + r.height + " · " + r.itemDrawn + "/" + r.itemTotal + " 本")));
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
        btnSave.setEmpty();
        btnShare.setEmpty();
        btnClose.setEmpty();
        savedUri = null;
    }

    /** 保存当前预览（后台压缩落盘 → 回主线程报结果）。 */
    private void saveWallpaper() {
        if (preview == null || previewBusy) return;
        final Bitmap bmp = preview;
        final String m = previewMode;
        final long s = previewStart;
        final Context app = getContext().getApplicationContext();
        toast(getContext().getString(R.string.desk_wallpaper_saving));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BillWallpaper.Saved r = BillWallpaper.save(app, bmp, m, s);
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (r.err != null) {
                            toast(r.err);
                        } else if (r.uri != null) {
                            toast(getContext().getString(R.string.desk_wallpaper_saved));
                        } else {
                            toast(getContext().getString(R.string.desk_wallpaper_saved_priv,
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
            return;
        }
        if (savedUri != null) {                 // 本页「保存到相册」已点过 ⇒ 直接分享
            startShare(savedUri);
            return;
        }
        final Bitmap bmp = preview;
        final String m = previewMode;
        final long s = previewStart;
        final Context app = getContext().getApplicationContext();
        toast(getContext().getString(R.string.desk_wallpaper_sharing));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BillWallpaper.Saved r = BillWallpaper.save(app, bmp, m, s);
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
                            toast(r.err != null ? r.err
                                    : getContext().getString(R.string.desk_wallpaper_share_none));
                        }
                    }
                });
            }
        }).start();
    }

    /** 起系统分享面板；本机没有能收图的应用（或起不来）⇒ 如实提示。 */
    private void startShare(Uri uri) {
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
            toast(getContext().getString(R.string.desk_wallpaper_share_none));
        }
    }

    private void toast(String s) {
        try {
            Toast.makeText(getContext(), s, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    // ══════════════════════ 壁纸预览态：画 ══════════════════════

    private void drawWallpaper(Canvas c, int w, int h, int veil) {
        renderer.drawChrome(c, w, h, unit, bg, veil, updatedLabel);

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
            float availBot = btnY - unit * 10f;
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

    // ══════════════════════ 🆕 TASK-076：排行区手势分区 ══════════════════════

    /** 排行区**内容视口**在屏幕坐标里的顶边 y；-1 = 排行区不在分区表里（模块关了 / 没料）。 */
    private float rankBodyTop() {
        int idx = renderer.indexOf(rankSection);
        if (idx < 0) return -1f;
        float top = renderer.sectionTop(idx, getWidth(), getHeight(), unit) - scrollY;
        return top + InsightRenderer.secHeadH(unit);
    }

    /** 触点是否落在排行区的**视口矩形**内（🔴 手势分区的唯一判据）。 */
    private boolean hitRank(float y) {
        float bt = rankBodyTop();
        if (bt < 0f) return false;
        float vp = getHeight() * InsightRenderer.RANK_VIEWPORT_RATIO;
        return y >= bt && y <= bt + vp;
    }

    /** 排行区当前是否需要内滚（装得下 ⇒ 不吃手势，让整页滚）。 */
    private boolean rankScrollable() { return rankSection.canScroll(getHeight(), unit); }

    // ══════════════════════ 🆕 TASK-077：账单分区头部命中 ══════════════════════

    /** 账单分区**顶边**的屏幕坐标 y；-1 = 账单分区不在分区表里（模块关了）。 */
    private float billTop() {
        int idx = renderer.indexOf(billSection);
        if (idx < 0) return -1f;
        return renderer.sectionTop(idx, getWidth(), getHeight(), unit) - scrollY;
    }

    /**
     * 点一下账单页签条 ⇒ 在「摘录菜单 / 读书菜单 / 月历」之间**循环**切换
     * （🆕 TASK-077b：月账单 3 档、周账单 2 档 —— 由 `visibleViews()` 决定）。
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

    /** 🆕 TASK-077b：点月历格子 ⇒ 选中该日（只影响绘制 ⇒ 只需重画，不必 remeasure）。 */
    private boolean hitCalCell(float x, float y) {
        float top = billTop();
        if (top < 0f) return false;
        if (!billSection.hitCalCell(x, y, getWidth(), unit, top)) return false;
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
                dragRank = (mode == MODE_LIST) && rankScrollable() && hitRank(downY);
                // 🆕 设置态：按住白纱滑条 ⇒ 本次手势只拖动滑条（不走滚动）
                if (mode == MODE_SETTINGS) {
                    DeskSettings.Item it = settings.hitItem(downX,
                            downY - renderer.headerHeight(unit) + scrollY, getWidth(), unit);
                    dragVeil = settings.isVeilBar(it);
                    if (dragVeil) setVeilFromX(downX);
                } else {
                    dragVeil = false;
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (dragVeil) {
                    setVeilFromX(e.getX());
                    lastY = e.getY();
                    return true;
                }
                totDx = e.getX() - downX;
                totDy = e.getY() - downY;
                if (mode == MODE_WALLPAPER) return true;      // 预览态不滚
                if (axis == AXIS_NONE) {
                    if (Math.max(Math.abs(totDx), Math.abs(totDy)) > SLOP_PX) {
                        axis = (Math.abs(totDx) > Math.abs(totDy)) ? AXIS_HORIZ : AXIS_VERT;
                    }
                }
                if (axis == AXIS_VERT) {
                    float mv = lastY - e.getY();     // 手指上滑 ⇒ mv > 0 ⇒ 内容上移
                    if (dragRank) {
                        // 🆕 TASK-076：落在排行区 ⇒ 本次滑动只驱动**区内**滚动，不带整页
                        if (rankSection.scrollBy(mv, getHeight(), unit)) invalidate();
                    } else {
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
                if (dragVeil) {
                    dragVeil = false;
                    return true;
                }

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
                            if (tip != null) toast(tip);
                            if (changed) {
                                remeasure(getWidth(), getHeight());
                                invalidate();
                            }
                        }
                    }
                    axis = AXIS_NONE;
                    dragRank = false;
                    return true;
                }

                // ── 列表态 ──
                if (axis == AXIS_HORIZ
                        && totDx <= -Math.max(SLOP_PX, getWidth() * BACK_RATIO)) {
                    if (listener != null) listener.onDeskClose();       // 左滑返回
                } else if (up && axis == AXIS_NONE) {
                    // 顶栏右端「设置」最优先（它最靠边，不与其它命中区重叠）
                    if (renderer.hitAction(e.getX(), e.getY(), getWidth(), unit)) {
                        enterSettings();
                    } else if (hitBillHead(e.getX(), e.getY())) {
                        // 🆕 2026-10-10：墨单头部（生成壁纸 / 周月 / 周期箭头）
                    } else if (hitBillTab(e.getX(), e.getY())) {
                        // 🆕 TASK-077：点账单页签条 ⇒ 切菜单（优先于"返回"判定，两者区域不重叠）
                    } else if (hitCalCell(e.getX(), e.getY())) {
                        // 🆕 TASK-077b：点月历格子 ⇒ 选中该日（同上，优先于"返回"）
                    } else if (renderer.hitBack(e.getX(), e.getY(), getWidth(), unit)) {
                        if (listener != null) listener.onDeskClose();   // 点 `‹ 返回`
                    }
                }
                axis = AXIS_NONE;
                dragRank = false;
                return true;
            }

            default:
                return true;     // 模态：其余动作也吃掉，绝不穿透到桌面
        }
    }

    /** 设置态：按触点 x 反算白纱百分比并落盘（变了才重画）。 */
    private void setVeilFromX(float x) {
        int pct = settings.veilPctAt(x, getWidth(), unit);
        if (pct != PagePrefs.getDeskBgVeil(getContext())) {
            PagePrefs.setDeskBgVeil(getContext(), pct);
            settings.invalidateCache();
            remeasure(getWidth(), getHeight());
            invalidate();
        }
    }
}
