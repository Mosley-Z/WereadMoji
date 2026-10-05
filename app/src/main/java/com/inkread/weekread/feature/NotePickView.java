package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.inkread.weekread.core.NoteStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 🆕 TASK-057（K11）本记页「选书」弹层 —— **半屏（下沿贴底的 bottom sheet）**：标题行 + 搜索框 + 书名列表。
 *
 * <h3>为什么单独一个 View，而不是画进 {@link WeekCardView}</h3>
 * · 🔴 <b>桌面隔离（卡面 A6）</b>：本 View 只出现在 `activity_main.xml` 的 `mp_reader` 里，
 *   桌面悬浮卡那套（{@code CardA11yService} / `OverlayController`）**整条链路都碰不到它**
 *   ⇒ 桌面本记形态逐像素不变的结论是**结构性成立**的，不靠"记得别改"来维持。
 * · 卡片本体已经有"自绘 + 自处理触摸 + 滚动吸附"三套耦合逻辑，再往里塞一个带
 *   `EditText` 的模态层，回归面太大。
 *
 * <h3>为什么用真 `EditText`</h3>
 * 卡面 A8 要验"真机输入搜索词可用"。自绘键盘在本机上等于重新发明轮子，直接放一个
 * 原生 `EditText`（设备默认输入法 `com.iflytek.inputmethod` 已启用，见验证记录）。
 * 它是本 View 的**唯一子 View**，位置在 {@link #onLayout} 里按 {@link #searchRect} 摆。
 *
 * <h3>坐标与尺寸</h3>
 * 全部用 px，但由 dp/sp 换算（{@link #dp}/{@link #sp}）——与 `WeekCardView` 的 `unit`
 * 体系不同：那套是"随屏高缩放"，这里只需要在 S4 上不出错，dp/sp 更直白。
 *
 * <h3>触摸</h3>
 * 本 View 是模态：`onTouchEvent` 恒返回 `true`（吃下全部落在"空白处"的手势）。
 * · 列表区 → 拖动滚列表 / 松手判行（点中 ⇒ {@link Listener#onPick}）；
 * · 上半屏遮罩 → 松手即 {@link Listener#onDismiss}（点空白关闭）；
 * · 搜索框 → 事件被子 `EditText` 先吃掉（Android 正常派发），本类不管。
 */
public final class NotePickView extends FrameLayout {

    // ── 墨屏三档灰（与 CardRenderer 同色；本类不出现在桌面上，硬编码即可）──
    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF3C3C3C;
    private static final int LIGHT = 0xFFA8A8A8;
    private static final int WHITE = 0xFFFFFFFF;
    /** 上半屏遮罩（浅灰）—— 与 `sep_mid` 同色，读作"被压暗的背景" */
    private static final int SCRIM = 0xFFD8D8D8;
    /** 行分隔线 */
    private static final int HAIR = 0xFFD8D8D8;

    /** 弹层占视口高的比例（0.70 ≈ 七成屏，题目说的"半屏列表"取靠上的口径） */
    private static final float SHEET_RATIO = 0.70f;
    private static final float TITLE_H_DP = 44f;
    private static final float SEARCH_H_DP = 54f;
    private static final float ROW_H_DP = 44f;
    private static final float PAD_X_DP = 16f;
    /** 触摸框 / 手势判定阈值（px） */
    private static final float SLOP = 12f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF searchRect = new RectF();
    /**
     * 「关闭」的触摸矩形。
     *
     * 🔴 第一版只把「关闭」**画**出来、没接点击 —— 真机实测点它毫无反应，
     * 正是本工程历史上栽过的「画上去的假按钮」（见 {@code CardInteraction} 类注释里 v0.4.1 那段）。
     * 现在它与「点空白处关闭」共用 {@link Listener#onDismiss}。
     */
    private final RectF closeRect = new RectF();

    /** 搜索框（唯一子 View） */
    private EditText search;
    /** 全量书目（{@code NoteStore.bookList}，索引原序） */
    private final List<NoteStore.BookRef> all = new ArrayList<NoteStore.BookRef>();
    /** 按当前搜索词过滤后的可见书目 */
    private final List<NoteStore.BookRef> shown = new ArrayList<NoteStore.BookRef>();
    /** 当前已选中的书（`""` = 全部书籍 ⇒ 随机池） */
    private String selectedId = "";

    // ── 绘制/触摸用的几何（{@link #computeGeo} 里算）──
    private float sheetTop = 0f;
    private float listTop = 0f;
    private float listH = 0f;
    private float scrollY = 0f;
    private float maxScroll = 0f;
    private float downY = 0f;
    private float downScroll = 0f;
    private boolean dragging = false;

    private Listener listener;

    /** 宿主回调（`MainActivity` 实现） */
    public interface Listener {
        /** 选中某本书；`bookId` 为空串 = 点的是「全部书籍」（恢复随机池） */
        void onPick(String bookId);

        /** 点空白 / 关闭 ⇒ 收起弹层 */
        void onDismiss();
    }

    public NotePickView(Context c) {
        this(c, null);
    }

    public NotePickView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(WHITE);
        setClickable(true);
        setFocusable(true);
        buildSearch(c);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ══════════════════ 对外 API ══════════════════

    /**
     * 打开弹层：喂入书目 + 当前选中项。
     *
     * 🔴 每次打开都**清掉上次的搜索词并滚回顶部** —— 弹层是"临时模态"，
     * 残留的过滤词会让下次进来看到一份不完整的列表，用户会以为书少了。
     */
    public void open(List<NoteStore.BookRef> books, String selected) {
        all.clear();
        if (books != null) all.addAll(books);
        selectedId = (selected == null) ? "" : selected;
        scrollY = 0f;
        if (isLaidOut()) computeGeo(getWidth(), getHeight());
        if (search != null) search.setText("");   // 触发 TextWatcher ⇒ applyFilter()
        applyFilter();                            // 显式再调一次：setText 同值可能不触发 watcher
    }

    /** 收起时清焦点（顺手收掉软键盘），下次进来不会自己弹键盘 */
    public void onHidden() {
        if (search != null) {
            search.clearFocus();
        }
        dragging = false;
    }

    // ══════════════════ 子 View：搜索框 ══════════════════

    private void buildSearch(Context c) {
        search = new EditText(c);
        search.setSingleLine(true);
        search.setHint("搜索书名");
        search.setHintTextColor(LIGHT);
        search.setTextColor(INK);
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp(16f));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        // 自带下划线/背景一律去掉 —— 描边由本类自己画（墨屏统一风貌）
        search.setBackground(null);
        search.setPadding((int) dp(10f), 0, (int) dp(10f), 0);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        addView(search, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /**
     * 过滤：本地 `contains`（忽略大小写）—— 书名或 bookId 命中都算。
     *
     * 与 `_probe/t057/search_probe.py` 的离线断言**逐条同构**（卡面 A2 的"离线断言 contains 结果集"）。
     */
    private void applyFilter() {
        String q = (search == null) ? "" : search.getText().toString().trim().toLowerCase();
        shown.clear();
        for (int i = 0; i < all.size(); i++) {
            NoteStore.BookRef b = all.get(i);
            if (q.length() == 0
                    || (b.title != null && b.title.toLowerCase().contains(q))
                    || (b.id != null && b.id.toLowerCase().contains(q))) {
                shown.add(b);
            }
        }
        scrollY = 0f;
        recomputeMaxScroll();
        invalidate();
    }

    // ══════════════════ 几何 ══════════════════

    /**
     * 由视口尺寸算出弹层几何（**onMeasure 调用**，故 w/h 恒为最终尺寸）。
     */
    private void computeGeo(int w, int h) {
        float titleH = dp(TITLE_H_DP);
        float searchH = dp(SEARCH_H_DP);
        float sheetH = h * SHEET_RATIO;
        sheetTop = h - sheetH;
        float padX = dp(PAD_X_DP);
        searchRect.set(padX, sheetTop + titleH + dp(6f), w - padX, sheetTop + titleH + searchH - dp(6f));
        // 「关闭」触摸区（比字号大一圈，墨屏上手指按不准 —— 与 expandBox 同款取舍）
        closeRect.set(w - padX - dp(56f), sheetTop + dp(2f), w - padX + dp(6f), sheetTop + titleH - dp(2f));
        listTop = sheetTop + titleH + searchH;
        listH = Math.max(0f, h - listTop);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        computeGeo(w, h);
        recomputeMaxScroll();
        // 🔴 搜索框必须在这里**按目标尺寸量一次**（与 onLayout 的摆位配对）。
        //
        // 踩过两次的坑（真机 A8/A2 复现）：
        //   ① 直接在 onLayout 里 `search.layout(...)` ⇒ 只改 frame、不重跑 measure，
        //      `TextView` 内部那份 `Layout` 还按 MATCH_PARENT 算，把它按"垂直居中"摆进
        //      高 58px 的框里 ⇒ 文字被算到框外 ~136px、再被自己裁掉 ⇒ **框里一个字都看不见**
        //      （`uiautomator dump` 里文本却是对的）。
        //   ② 改用 `setLayoutParams(带 margins)` 后，**首次**摆放仍会晚一拍：
        //      真机表现为"点列表某一行却落到搜索框上、还把键盘顶出来"。
        // 正解 = 教科书式：**onMeasure 量、onLayout 摆**。两者用同一份 {@link #searchRect}，
        // 尺寸与位置天然一致，TextView 的 Layout 也随 measure 重建。
        if (search != null && searchRect.width() > 0f && searchRect.height() > 0f) {
            search.measure(
                    MeasureSpec.makeMeasureSpec((int) searchRect.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((int) searchRect.height(), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (search != null && searchRect.width() > 0f) {
            search.layout((int) searchRect.left, (int) searchRect.top,
                    (int) searchRect.right, (int) searchRect.bottom);
        }
    }

    private void recomputeMaxScroll() {
        float content = (shown.size() + 1) * dp(ROW_H_DP);      // +1 = 顶部「全部书籍」行
        maxScroll = Math.max(0f, content - listH);
        if (scrollY > maxScroll) scrollY = maxScroll;
    }

    private float rowH() {
        return dp(ROW_H_DP);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    // ══════════════════ 绘制 ══════════════════

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // ① 上半屏遮罩（浅灰）—— 点它关闭；行内给一句提示，省得用户不知道能点
        p.setStyle(Paint.Style.FILL);
        p.setColor(SCRIM);
        c.drawRect(0, 0, w, sheetTop, p);
        p.setColor(GRAY);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(sp(14f));
        c.drawText("点空白处关闭", w / 2f, sheetTop * 0.5f, p);

        // ② 弹层底板（白）+ 上沿黑线
        p.setStyle(Paint.Style.FILL);
        p.setColor(WHITE);
        c.drawRect(0, sheetTop, w, h, p);
        p.setColor(INK);
        c.drawRect(0, sheetTop, w, sheetTop + dp(1.5f), p);

        // ③ 标题行：「选择书籍」 + 「关闭」
        float titleBase = sheetTop + dp(TITLE_H_DP) * 0.68f;
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(true);
        p.setTextSize(sp(17f));
        p.setColor(INK);
        c.drawText("选择书籍", dp(PAD_X_DP), titleBase, p);
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(sp(15f));
        p.setColor(GRAY);
        c.drawText("关闭", w - dp(PAD_X_DP), titleBase, p);
        // 标题行下分隔线
        float divY = sheetTop + dp(TITLE_H_DP);
        p.setColor(HAIR);
        c.drawRect(0, divY, w, divY + 1f, p);

        // ④ 搜索框描边（EditText 本体画不了框，外面补一圈）
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(GRAY);
        c.drawRect(searchRect.left, searchRect.top, searchRect.right, searchRect.bottom, p);
        p.setStyle(Paint.Style.FILL);

        // ⑤ 列表
        drawList(c, w);

        // 收尾：把共享画笔复位（其它绘制方法可能复用同一支的状态）
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(INK);
    }

    private void drawList(Canvas c, int w) {
        if (listH <= 0f) return;

        float rh = rowH();
        float padX = dp(PAD_X_DP);

        // 列表区裁剪（滚出视野的行不画；`clipRect` 只影响本段，之后自动复位）
        c.save();
        c.clipRect(0f, listTop, (float) w, listTop + listH);

        int rows = shown.size() + 1;
        for (int i = 0; i < rows; i++) {
            float rt = listTop + i * rh - scrollY;
            if (rt + rh < listTop || rt > listTop + listH) continue;   // 完全在视野外

            boolean isAll = (i == 0);
            NoteStore.BookRef b = isAll ? null : shown.get(i - 1);
            String title = isAll ? "全部书籍" : (b.title == null ? "" : b.title);
            String meta = isAll ? "恢复随机" : (b.count + " 条" + (b.ready ? "" : " · 未就绪"));
            boolean picked = isAll ? (selectedId.length() == 0) : selectedId.equals(b.id);

            float base = rt + rh / 2f;
            Paint.FontMetrics fm = p.getFontMetrics();

            // 选中行：左侧黑竖条（墨屏没有高亮色，这是唯一够醒目的"当前"标记）
            if (picked) {
                p.setColor(INK);
                c.drawRect(0f, rt + dp(6f), dp(4f), rt + rh - dp(6f), p);
            }

            // 右侧 meta 先量（给标题留出宽度）
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(sp(12.5f));
            p.setColor(picked ? GRAY : LIGHT);
            float metaW = p.measureText(meta);
            c.drawText(meta, w - padX, base - (fm.descent + fm.ascent) / 2f, p);

            // 标题（左，超出则省略号）
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(sp(16f));
            p.setFakeBoldText(picked || isAll);
            p.setColor(isAll ? GRAY : INK);
            float titleMax = (w - padX * 2f) - metaW - dp(12f);
            String t = ellipsize(title, titleMax);
            c.drawText(t, padX + (picked ? dp(8f) : 0f), base - (fm.descent + fm.ascent) / 2f, p);
            p.setFakeBoldText(false);

            // 行分隔线
            p.setColor(HAIR);
            c.drawRect(padX, rt + rh - 1f, w - padX, rt + rh, p);
        }

        c.restore();

        // 空态：过滤没命中
        if (shown.isEmpty()) {
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(sp(14f));
            p.setColor(LIGHT);
            c.drawText("没有匹配的书", w / 2f, listTop + dp(40f), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** 简易省略号截断（与 `CardLayout#ellipsize` 同思路：逐字量到装不下为止） */
    private String ellipsize(String s, float maxW) {
        if (s == null || s.length() == 0 || maxW <= 0f) return s == null ? "" : s;
        if (p.measureText(s) <= maxW) return s;
        String ell = "…";
        float ellW = p.measureText(ell);
        StringBuilder sb = new StringBuilder();
        float acc = 0f;
        for (int i = 0; i < s.length(); i++) {
            float cw = p.measureText(s, i, i + 1);
            if (acc + cw + ellW > maxW) break;
            sb.append(s.charAt(i));
            acc += cw;
        }
        return sb.toString() + ell;
    }

    // ══════════════════ 触摸 ══════════════════

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                downScroll = scrollY;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (maxScroll <= 0f) return true;
                float dy = downY - e.getY();
                if (!dragging && Math.abs(dy) > SLOP) dragging = true;
                if (dragging) setScroll(downScroll + dy);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (dragging) {
                    dragging = false;
                    return true;                 // 这一下是滚动，不算点击
                }
                float y = e.getY();
                float x = e.getX();
                if (y < sheetTop) {              // 上半屏遮罩 → 关闭
                    if (listener != null) listener.onDismiss();
                    return true;
                }
                if (closeRect.contains(x, y)) {  // 「关闭」二字 → 关闭（第一版漏了这个，实测点不动）
                    if (listener != null) listener.onDismiss();
                    return true;
                }
                if (y >= listTop && y <= listTop + listH) {
                    int idx = (int) ((y - listTop + scrollY) / rowH());
                    if (idx < 0) idx = 0;
                    if (idx == 0) {
                        if (listener != null) listener.onPick("");      // 全部书籍 ⇒ 恢复随机池
                    } else {
                        int k = idx - 1;
                        if (k >= 0 && k < shown.size() && listener != null) {
                            listener.onPick(shown.get(k).id);
                        }
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    /** 滚列表（钳制到 [0, maxScroll]；**不吸附整行、不做惯性** —— 弹层不需要那么讲究） */
    private void setScroll(float v) {
        if (v < 0f) v = 0f;
        if (v > maxScroll) v = maxScroll;
        if (Math.abs(v - scrollY) < 1f) return;
        scrollY = v;
        invalidate();
    }
}
