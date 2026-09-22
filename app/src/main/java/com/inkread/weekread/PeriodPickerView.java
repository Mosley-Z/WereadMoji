package com.inkread.weekread;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * App 内的**周期步进选择器**（设计方案 §6）——「本周 / 本月」选项卡下面那一条。
 *
 * 为什么只在 App 里放（④ 已拍板）：桌面卡片只有 368×346，塞不下选择器，
 * 硬塞会把主数字挤掉；App 内是 480×800 的整屏，有的是地方。
 *
 * 两种形态（由 {@link #setPeriod} 的 mode 决定）：
 * <pre>
 *   weekly   ◀        2026年9月15日 – 9月21日        ▶
 *   monthly  ◀◀   ◀      2026年9月      ▶   ▶▶
 *                        （本月）
 * </pre>
 * · `◀ / ▶` —— 逐周期步进（周 ±1 周；月 ±1 月）；
 * · `◀◀ / ▶▶` —— **跨 12 个周期（年快跳）**，按 ⑤ 新增；周模式下不出现。
 *
 * ⚠️ 三角一律用 {@link Path} **画出来，不用 ◀ ▶ 这些字符**：
 * 本机字体不一定带这些码位，万一缺字会渲染成豆腐块（墨水屏上还没法忽略）。
 *
 * ⚠️ 墨水屏没有涟漪动画 → 点完必须**立刻改变标签文字**（这本身就是唯一的视觉反馈）；
 * 已经在"当前周期"上时，右侧箭头画成浅灰（表示不能再往后看），点了也不响应。
 */
public class PeriodPickerView extends View {

    public interface Listener {
        /**
         * @param delta 周期偏移量：周只有 ±1；月有 ±1（逐月）与 ±12（年快跳）
         */
        void onShift(int delta);
    }

    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF3C3C3C;
    /** 不可用状态（已经在当前周期，不能再往后）*/
    private static final int LIGHT = 0xFFA8A8A8;

    /** 1 号 = 屏高 × 0.0015（480×800 屏上 = 1.2px），与 WeekCardView 同一套 */
    private static final float UNIT_RATIO = 0.0015f;

    /** 单个箭头按钮占屏宽的比例（480 屏上 ≈ 55px，手指按得准） */
    private static final float BTN_W_RATIO = 0.115f;

    private float unit;
    private String mode = PeriodRange.WEEKLY;
    private long anchorStart;
    /** 已经在"当前周期"上 → ▶ / ▶▶ 浅灰且不响应 */
    private boolean boundary = true;
    private Listener listener;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public PeriodPickerView(Context c) { this(c, null); }

    public PeriodPickerView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFFFFFFFF);
        unit = c.getResources().getDisplayMetrics().heightPixels * UNIT_RATIO;
    }

    public void setListener(Listener l) { listener = l; }

    /** 当前显示哪个周期。boundary 由 anchorStart 自己推出来，不用调用方操心 */
    public void setPeriod(String mode, long anchorStart) {
        this.mode = PeriodRange.MONTHLY.equals(mode) ? PeriodRange.MONTHLY : PeriodRange.WEEKLY;
        this.anchorStart = anchorStart > 0 ? anchorStart : PeriodRange.startOf(this.mode, 0);
        long cur = PeriodRange.startOf(this.mode, 0);
        this.boundary = this.anchorStart >= cur;
        invalidate();
    }

    private boolean isMonthly() {
        return PeriodRange.MONTHLY.equals(mode);
    }

    private float btnW() {
        return getWidth() * BTN_W_RATIO;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFFFFFFFF);
        float w = getWidth(), h = getHeight();

        // ── 上下各一条浅细线，把选择器和上面的选项卡、下面的卡片分开 ──
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0xFFD8D8D8);
        c.drawLine(0, 0.5f, w, 0.5f, p);
        c.drawLine(0, h - 0.5f, w, h - 0.5f, p);
        p.setStyle(Paint.Style.FILL);

        // ── 主标签：周 → 「2026年9月15日 – 9月21日」；月 → 「2026年9月」 ──
        String label = isMonthly() ? PeriodRange.monthLabel(anchorStart)
                                   : PeriodRange.weekLabel(anchorStart);
        float size = 17f * unit;
        float labelBase = h * 0.52f;
        float b = btnW();

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(INK);
        p.setFakeBoldText(true);
        p.setTextSize(size);
        float avail = w - b * 4f - unit * 8f;      // 月模式最多 4 个按钮位
        while (p.measureText(label) > avail && size > 10f * unit) {
            size -= 0.5f;
            p.setTextSize(size);
        }
        c.drawText(label, w / 2f, labelBase, p);
        p.setFakeBoldText(false);

        // ── 副标签「（本周）/（本月）」：只在正好停在当前周期时出现 ──
        if (boundary) {
            p.setTextSize(13f * unit);
            p.setColor(GRAY);
            c.drawText(isMonthly() ? "（本月）" : "（本周）", w / 2f, h * 0.87f, p);
        }

        // ── 箭头 ──
        float cy = labelBase - size * 0.36f;       // 与标签的视觉中线对齐
        float hw = unit * 4.6f;                    // 三角半宽
        float hh = unit * 7.0f;                    // 三角半高
        int fwdColor = boundary ? LIGHT : INK;     // 不能往后看 → 浅灰

        if (isMonthly()) {
            doubleArrow(c, b * 0.5f, cy, hw, hh, true, INK);
            arrow(c, b * 1.5f, cy, hw, hh, true, INK);
            arrow(c, w - b * 1.5f, cy, hw, hh, false, fwdColor);
            doubleArrow(c, w - b * 0.5f, cy, hw, hh, false, fwdColor);
        } else {
            arrow(c, b * 0.5f, cy, hw, hh, true, INK);
            arrow(c, w - b * 0.5f, cy, hw, hh, false, fwdColor);
        }

        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(INK);
    }

    /** 单个三角：left=true 指左（◀），false 指右（▶） */
    private void arrow(Canvas c, float cx, float cy, float hw, float hh, boolean left, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        path.reset();
        if (left) {
            path.moveTo(cx - hw, cy);
            path.lineTo(cx + hw, cy - hh);
            path.lineTo(cx + hw, cy + hh);
        } else {
            path.moveTo(cx + hw, cy);
            path.lineTo(cx - hw, cy - hh);
            path.lineTo(cx - hw, cy + hh);
        }
        path.close();
        c.drawPath(path, p);
    }

    /** 双三角（年快跳 ◀◀ / ▶▶）*/
    private void doubleArrow(Canvas c, float cx, float cy, float hw, float hh,
                             boolean left, int color) {
        float step = hw * 2.05f;
        arrow(c, cx - step / 2f, cy, hw, hh, left, color);
        arrow(c, cx + step / 2f, cy, hw, hh, left, color);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        float w = getWidth();
        float b = btnW();
        float x = e.getX();

        int delta = 0;
        if (x < b) {
            delta = isMonthly() ? -12 : -1;            // ◀◀ 年快跳（周模式就是普通上一周）
        } else if (isMonthly() && x < b * 2f) {
            delta = -1;                                // ◀ 上一月
        } else if (x > w - b) {
            delta = isMonthly() ? 12 : 1;              // ▶▶
        } else if (isMonthly() && x > w - b * 2f) {
            delta = 1;                                 // ▶
        }
        if (delta == 0) return true;
        if (delta > 0 && boundary) return true;         // 已在当前周期，不许看未来（箭头也是浅灰）
        if (listener != null) listener.onShift(delta);
        return true;
    }
}
