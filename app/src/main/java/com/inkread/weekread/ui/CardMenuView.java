package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 长按卡片抬头弹出的**纯文字菜单**（墨水屏友好：白底黑框黑字、无动画、无阴影）。
 *
 * 本 View 的窗口铺满整张卡片（见 {@link OverlayWindow#paramsMenu}），
 * 但**只画中间那个框**，其余区域保持透明 —— 这样"点框外 = 取消"是天然行为。
 *
 * 画什么由调用方通过 {@link #setItems} 给；选中第几项由 {@link Listener} 回调。
 */
public class CardMenuView extends View {

    public interface Listener {
        /** 选中了第 index 项 */
        void onSelect(int index);

        /** 点了框外（= 取消） */
        void onDismiss();
    }

    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF5A5A5A;
    private static final int LINE = 0xFFD0D0D0;

    private static final float UNIT_RATIO = 0.0015f;

    /** 框宽（px）。368 宽的卡片上占一半多一点，四个字 + 两个字的选项都放得下 */
    private static final float BOX_W = 196f;
    /** 每行高（px）。30px 上下是墨水屏上手指能按准的下限 */
    private static final float ROW_H = 36f;
    /** 框顶相对卡片顶边的偏移。放在抬头下面一点，不压住抬头本身 */
    private static final float BOX_TOP = 58f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float unit;
    private String[] items = new String[0];
    private Listener listener;
    /** 最后一项（取消）画成灰色，视觉上和"真的要隐藏"区分开 */
    private boolean lastIsCancel = true;

    public CardMenuView(Context c) { this(c, null); }

    public CardMenuView(Context c, AttributeSet a) {
        super(c, a);
        unit = c.getResources().getDisplayMetrics().heightPixels * UNIT_RATIO;
    }

    public void setListener(Listener l) { listener = l; }

    public void setItems(String[] arr) {
        items = (arr == null) ? new String[0] : arr;
        invalidate();
    }

    // ── 绘制 ──

    @Override
    protected void onDraw(Canvas c) {
        if (items.length == 0) return;
        float w = getWidth();
        float x = (w - BOX_W) / 2f;
        float y = BOX_TOP;
        float h = ROW_H * items.length;
        float r = 2f;                          // 方角：墨水屏上圆角没有意义，还多一次抗锯齿

        // 框体：先实心白（盖住底下的卡片），再黑描边
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawRect(x, y, x + BOX_W, y + h, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(INK);
        c.drawRect(x + r, y + r, x + BOX_W - r, y + h - r, p);
        p.setStyle(Paint.Style.FILL);

        float size = 16f * unit;
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(true);

        float padX = 14f;
        Paint.FontMetrics fm = p.getFontMetrics();
        for (int i = 0; i < items.length; i++) {
            float rowTop = y + ROW_H * i;
            float base = rowTop + ROW_H / 2f - (fm.descent + fm.ascent) / 2f;

            // 行分隔线（最后一行不画）
            if (i < items.length - 1) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1f);
                p.setColor(LINE);
                c.drawLine(x + 2f, rowTop + ROW_H, x + BOX_W - 2f, rowTop + ROW_H, p);
                p.setStyle(Paint.Style.FILL);
            }

            boolean cancel = lastIsCancel && i == items.length - 1;
            p.setColor(cancel ? GRAY : INK);
            p.setFakeBoldText(!cancel);
            // 选项文字也要防溢出：框内可用宽 = BOX_W - 2*padX
            String t = items[i];
            float s = size;
            p.setTextSize(s);
            while (p.measureText(t) > BOX_W - padX * 2f && s > 10f * unit) {
                s -= 0.5f;
                p.setTextSize(s);
            }
            c.drawText(t, x + padX, base, p);
            p.setTextSize(size);
        }
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(INK);
    }

    // ── 触摸 ──

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        if (items.length == 0) return true;

        float w = getWidth();
        float x = (w - BOX_W) / 2f;
        float y = BOX_TOP;
        float h = ROW_H * items.length;

        float px = e.getX(), py = e.getY();
        if (px < x || px > x + BOX_W || py < y || py > y + h) {
            if (listener != null) listener.onDismiss();
            return true;
        }
        int idx = (int) ((py - y) / ROW_H);
        if (idx < 0) idx = 0;
        if (idx > items.length - 1) idx = items.length - 1;
        if (listener != null) listener.onSelect(idx);
        return true;
    }
}
