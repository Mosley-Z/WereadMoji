package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 🆕 TASK-069（V1.2.1-beta）「本书」手动选书弹层 —— **半屏（下沿贴底的 bottom sheet）**：
 * 标题行 + 候选列表，第一行恒为「自动（最近在读）」，其后是最近读过的若干本。
 *
 * <h3>与 {@link NotePickView} 的关系</h3>
 * 同一款交互范式（模态半屏 + 点空白关闭 + 选中行左侧黑竖条），但**刻意不复用**：
 * · 本记选书的料是 {@code NoteStore.BookRef}（划线条数 / 是否就绪），本页的料是**书架快照**
 *   （bookId / 书名 / 最近阅读时间）—— 两者字段不同，硬塞进一个 View 会变成"两个 if"；
 * · 候选只有 3~4 条 ⇒ **不要搜索框**（卡面拍板：3 本用不上搜索）。
 *
 * <h3>为什么单独一个 View，而不是画进 {@link WeekCardView}</h3>
 * 🔴 **桌面隔离**：本 View 只出现在 {@code activity_main.xml} 的 {@code mp_reader} 里，
 * 桌面悬浮卡那套（{@code CardA11yService} / {@code OverlayController}）**整条链路都碰不到它**
 * ⇒ 桌面「本书」形态的回归面是**结构性为零**的，不靠"记得别改"来维持。
 *
 * <h3>触摸</h3>
 * 本 View 是模态：{@code onTouchEvent} 恒返回 {@code true}。
 * · 列表区 → 拖动滚列表 / 松手判行（点中 ⇒ {@link Listener#onPick}）；
 * · 上半屏遮罩 / 「关闭」→ {@link Listener#onDismiss}。
 *
 * <p>🔴 墨屏三档灰与 {@link NotePickView} 同色（本类不出现在桌面上，硬编码即可）。
 */
public final class BookPickView extends FrameLayout {

    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF3C3C3C;
    private static final int LIGHT = 0xFFA8A8A8;
    private static final int WHITE = 0xFFFFFFFF;
    /** 上半屏遮罩（浅灰）—— 读作"被压暗的背景" */
    private static final int SCRIM = 0xFFD8D8D8;
    /** 行分隔线 / 下框线 */
    private static final int HAIR = 0xFFD8D8D8;

    /** 弹层占视口高的比例（0.62 ≈ 六成屏；候选最多 4 行，够用且不压住阅读区） */
    private static final float SHEET_RATIO = 0.62f;
    private static final float TITLE_H_DP = 44f;
    private static final float ROW_H_DP = 46f;
    private static final float PAD_X_DP = 16f;
    /** 触摸 / 手势判定阈值（px） */
    private static final float SLOP = 12f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF closeRect = new RectF();

    /** 候选（`bookId` / `title` / `readUpdateTime`）—— 索引 0 = 「自动（最近在读）」的占位 */
    private final List<Row> rows = new ArrayList<Row>();

    /** 当前档：`""` = 自动 */
    private String selectedId = "";

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
        /** 选中某本书；`bookId` 为空串 = 点的是「自动（最近在读）」 */
        void onPick(String bookId);

        /** 点空白 / 关闭 ⇒ 收起弹层 */
        void onDismiss();
    }

    /** 一行 = bookId + 主标题 + 右侧 meta（最近阅读时间）。 */
    private static final class Row {
        String bookId;
        String title;
        String meta;
    }

    public BookPickView(Context c) {
        this(c, null);
    }

    public BookPickView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(WHITE);
        setClickable(true);
        setFocusable(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ══════════════════ 对外 API ══════════════════

    /**
     * 打开弹层：喂入候选（{@link com.inkread.weekread.core.BookStore#bookCandidates} 的产物）
     * + 当前选中项（`""` = 自动）。
     *
     * <p>🔴 每次打开都**滚回顶部** —— 弹层是"临时模态"，残留的滚动位置会让人以为少了书。
     */
    public void open(JSONArray cands, String selected) {
        rows.clear();
        Row auto = new Row();
        auto.bookId = "";
        auto.title = "自动（最近在读）";
        auto.meta = "微信读书最近在读";
        rows.add(auto);

        if (cands != null) {
            for (int i = 0; i < cands.length(); i++) {
                JSONObject o = cands.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("bookId", "");
                if (id.length() == 0) continue;
                Row r = new Row();
                r.bookId = id;
                String t = o.optString("title", "");
                r.title = (t.length() == 0) ? "（未命名）" : t;
                r.meta = fmtTime(o.optLong("readUpdateTime", 0L));
                rows.add(r);
            }
        }
        selectedId = (selected == null) ? "" : selected;
        scrollY = 0f;
        if (isLaidOut()) computeGeo(getWidth(), getHeight());
        recomputeMaxScroll();
        invalidate();
    }

    /** 收起时清焦点（下次进来不会有残留状态） */
    public void onHidden() {
        dragging = false;
    }

    // ══════════════════ 几何 ══════════════════

    private void computeGeo(int w, int h) {
        float titleH = dp(TITLE_H_DP);
        float sheetH = h * SHEET_RATIO;
        sheetTop = h - sheetH;
        float padX = dp(PAD_X_DP);
        // 「关闭」触摸区（比字号大一圈 —— 与 NotePickView / expandBox 同款取舍）
        closeRect.set(w - padX - dp(56f), sheetTop + dp(2f), w - padX + dp(6f), sheetTop + titleH - dp(2f));
        listTop = sheetTop + titleH;
        listH = Math.max(0f, h - listTop);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        computeGeo(w, h);
        recomputeMaxScroll();
        setMeasuredDimension(w, h);
    }

    private void recomputeMaxScroll() {
        float content = rows.size() * dp(ROW_H_DP);
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

    /** 秒级时间戳 → 「MM-DD HH:mm」；<=0 返回空串（不画假的"最近阅读"）。 */
    private static String fmtTime(long sec) {
        if (sec <= 0L) return "";
        try {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
            return f.format(new Date(sec * 1000L));
        } catch (Throwable t) {
            return "";
        }
    }

    // ══════════════════ 绘制 ══════════════════

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // ① 上半屏遮罩（浅灰）—— 点它关闭
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

        // ③ 标题行：「选择「本书」」 + 「关闭」
        float titleBase = sheetTop + dp(TITLE_H_DP) * 0.68f;
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(true);
        p.setTextSize(sp(17f));
        p.setColor(INK);
        c.drawText("选择「本书」", dp(PAD_X_DP), titleBase, p);
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(sp(15f));
        p.setColor(GRAY);
        c.drawText("关闭", w - dp(PAD_X_DP), titleBase, p);
        float divY = sheetTop + dp(TITLE_H_DP);
        p.setColor(HAIR);
        c.drawRect(0, divY, w, divY + 1f, p);

        // ④ 列表
        drawList(c, w);

        // 收尾：共享画笔复位
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(INK);
    }

    private void drawList(Canvas c, int w) {
        if (listH <= 0f || rows.isEmpty()) return;

        float rh = rowH();
        float padX = dp(PAD_X_DP);

        c.save();
        c.clipRect(0f, listTop, (float) w, listTop + listH);

        for (int i = 0; i < rows.size(); i++) {
            float rt = listTop + i * rh - scrollY;
            if (rt + rh < listTop || rt > listTop + listH) continue;   // 完全在视野外

            Row r = rows.get(i);
            boolean isAuto = (r.bookId.length() == 0);
            boolean picked = isAuto ? (selectedId.length() == 0) : selectedId.equals(r.bookId);

            float base = rt + rh / 2f;
            Paint.FontMetrics fm = p.getFontMetrics();

            // 选中行：左侧黑竖条（墨屏没有高亮色，这是唯一够醒目的"当前"标记）
            if (picked) {
                p.setColor(INK);
                c.drawRect(0f, rt + dp(6f), dp(4f), rt + rh - dp(6f), p);
            }

            // 右侧 meta 先量（给标题留宽度）
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(sp(12.5f));
            p.setColor(picked ? GRAY : LIGHT);
            float metaW = (r.meta == null) ? 0f : p.measureText(r.meta);
            if (metaW > 0f) {
                c.drawText(r.meta, w - padX, base - (fm.descent + fm.ascent) / 2f, p);
            }

            // 标题（左，超出则省略号）
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(sp(16f));
            p.setFakeBoldText(picked);
            p.setColor(isAuto ? GRAY : INK);
            float titleMax = (w - padX * 2f) - metaW - dp(12f);
            String t = ellipsize(r.title, titleMax);
            c.drawText(t, padX + (picked ? dp(8f) : 0f), base - (fm.descent + fm.ascent) / 2f, p);
            p.setFakeBoldText(false);

            // 行分隔线
            p.setColor(HAIR);
            c.drawRect(padX, rt + rh - 1f, w - padX, rt + rh, p);
        }

        c.restore();
    }

    /** 简易省略号截断（与 {@link NotePickView} 同思路：逐字量到装不下为止） */
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
                if (closeRect.contains(x, y)) {  // 「关闭」二字
                    if (listener != null) listener.onDismiss();
                    return true;
                }
                if (y >= listTop && y <= listTop + listH) {
                    int idx = (int) ((y - listTop + scrollY) / rowH());
                    if (idx < 0) idx = 0;
                    if (idx < rows.size() && listener != null) {
                        listener.onPick(rows.get(idx).bookId);
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

    /** 滚列表（钳制到 [0, maxScroll]；**不吸附整行、不做惯性**） */
    private void setScroll(float v) {
        if (v < 0f) v = 0f;
        if (v > maxScroll) v = maxScroll;
        if (Math.abs(v - scrollY) < 1f) return;
        scrollY = v;
        invalidate();
    }
}
