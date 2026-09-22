package com.inkread.weekread;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 页面选项卡：本周 / 本月 / 本书。
 *
 * v0.3.3 起两页都是真页面（本月是"全屏大版"，见 {@link WeekCardView#setFullscreen}）；
 * v0.3.4 再加第三个「本书」（最近在读的书的进度，见 {@link BookStats}）。
 *
 * 「本书」页没有周期概念，App 里的 {@link PeriodPickerView} 会整条隐藏。
 */
public class TabBarView extends View {

    public interface Listener {
        /** index: 0=本周 1=本月 2=本书 */
        void onTabSelected(int index);
    }

    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF9A9A9A;

    private static final String[] TABS = {"本周", "本月", "本书", "本记"};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float unit;      // 1 号 = 屏幕高度 * 0.0015
    private int selected = 0;
    private Listener listener;

    public TabBarView(Context c) { this(c, null); }

    public TabBarView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFFFFFFFF);
        unit = c.getResources().getDisplayMetrics().heightPixels * 0.0015f;
    }

    public void setListener(Listener l) { listener = l; }

    public void setSelected(int i) {
        if (i == selected) return;
        selected = i;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFFFFFFFF);
        float w = getWidth(), h = getHeight();
        float slot = w / TABS.length;

        float size = 19f * unit;          // 原 16 号 → +2，标题类文字再略大一点
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        float baseline = (h - (p.getFontMetrics().descent - p.getFontMetrics().ascent)) / 2f
                - p.getFontMetrics().ascent;

        for (int i = 0; i < TABS.length; i++) {
            float cx = slot * i + slot / 2f;
            boolean on = (i == selected);
            p.setFakeBoldText(on);
            p.setColor(on ? INK : GRAY);
            c.drawText(TABS[i], cx, baseline, p);

            if (on) {
                // 当前页：底部黑色指示条
                p.setStyle(Paint.Style.FILL);
                p.setColor(INK);
                float bw = slot * 0.34f;
                c.drawRect(cx - bw / 2f, h - Math.max(3f, h * 0.075f), cx + bw / 2f, h, p);
            }
        }
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.LEFT);

        // 页签之间的分隔竖线（弱化，暗示这是几个并列的页签）
        p.setColor(0xFFE0E0E0);
        p.setStyle(Paint.Style.STROKE);
        for (int i = 1; i < TABS.length; i++) {
            c.drawLine(slot * i, h * 0.25f, slot * i, h * 0.75f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        int idx = (int) (e.getX() / (getWidth() / (float) TABS.length));
        if (idx < 0) idx = 0;
        if (idx > TABS.length - 1) idx = TABS.length - 1;
        if (listener != null) listener.onTabSelected(idx);
        return true;
    }
}
