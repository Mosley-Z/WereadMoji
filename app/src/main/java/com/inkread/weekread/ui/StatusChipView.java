package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * V1.2.0-beta · 遥控台「状态胶囊」（docs/09 §5.1）。
 *
 * <p>常驻顶部，一眼看出「连没连上」：圆形状态点（8dp）+ 一行文字；右侧挂一个**换向**图标按钮
 * （TASK-038 的方向交换）。整体底色 {@code paper2}、圆角 999dp。
 *
 * <p>状态色语义（三档统一）：
 * <ul>
 *   <li>未启用 → 灰点 + 灰字（{@link #ST_OFF}）</li>
 *   <li>已启用未连接 → 赭石点 +「等待连接」（{@link #ST_WAITING}）</li>
 *   <li>已断开 → 赭石点 +「已断开」（{@link #ST_LOST}）</li>
 *   <li>已连接 → 竹青点 +「已连接 S4」（{@link #ST_CONNECTED}）</li>
 * </ul>
 */
public class StatusChipView extends View {

    public static final int ST_OFF = 0;
    public static final int ST_WAITING = 1;
    public static final int ST_CONNECTED = 2;
    public static final int ST_LOST = 3;

    /** 交互回调：点胶囊主体 / 点右侧换向图标。 */
    public interface Listener {
        void onChipTap();

        void onSwapTap();
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private int state = ST_OFF;
    private String text = "";
    private boolean swapChecked = false;
    private Listener listener;

    public StatusChipView(Context c) {
        this(c, null);
    }

    public StatusChipView(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        p.setTypeface(InkTheme.serif());
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** @param state {@link #ST_OFF}/{@link #ST_WAITING}/{@link #ST_CONNECTED}/{@link #ST_LOST} */
    public void setStatus(int state, String text) {
        this.state = state;
        this.text = text == null ? "" : text;
        setContentDescription(this.text);
        invalidate();
    }

    /** 方向交换是否已生效（换向图标高亮）。 */
    public void setSwapChecked(boolean on) {
        if (this.swapChecked == on) return;
        this.swapChecked = on;
        invalidate();
    }

    private int dotColor() {
        switch (state) {
            case ST_CONNECTED:
                return InkTheme.BAMBOO;
            case ST_WAITING:
            case ST_LOST:
                return InkTheme.CLAY;
            default:
                return InkTheme.INK3;
        }
    }

    private int textColor() {
        return state == ST_OFF ? InkTheme.INK3 : InkTheme.INK;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 胶囊底：paper2 + 0.5dp 界线 + 999dp 圆角
        r.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
        p.setStyle(Paint.Style.FILL);
        p.setColor(InkTheme.PAPER2);
        float rad = h / 2f;
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(InkTheme.dp(getContext(), 0.5f));
        p.setColor(InkTheme.LINE);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);

        // 状态点
        float pad = InkTheme.dp(getContext(), 12f);
        float dotR = Math.min(InkTheme.dp(getContext(), 4f), h * 0.14f);
        float cx = pad + dotR;
        float cy = h / 2f;
        p.setColor(dotColor());
        c.drawCircle(cx, cy, dotR, p);

        // 文字
        float tx = cx + dotR + InkTheme.dp(getContext(), 8f);
        float swapW = InkTheme.dp(getContext(), 34f);   // 右侧换向热区宽
        p.setTypeface(InkTheme.serif());
        p.setTextSize(InkTheme.sp(getContext(), 13f));
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(textColor());
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        // 超出可用宽度就省略（状态文案都短，正常不会触发）
        float avail = w - tx - swapW - pad;
        String show = text;
        if (p.measureText(show) > avail && show.length() > 1) {
            while (show.length() > 1 && p.measureText(show + "…") > avail) {
                show = show.substring(0, show.length() - 1);
            }
            show = show + "…";
        }
        c.drawText(show, tx, baseline, p);

        // 右侧换向图标「⇄」（TASK-038：一键交换左右方向）
        float swCx = w - pad - swapW / 2f + InkTheme.dp(getContext(), 4f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(InkTheme.sp(getContext(), 15f));
        p.setColor(swapChecked ? InkTheme.BAMBOO : InkTheme.INK2);
        c.drawText("⇄", swCx, baseline, p);

        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                float swapW = InkTheme.dp(getContext(), 34f);
                if (e.getX() >= getWidth() - swapW) {
                    if (listener != null) listener.onSwapTap();
                } else {
                    if (listener != null) listener.onChipTap();
                }
                performClick();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
