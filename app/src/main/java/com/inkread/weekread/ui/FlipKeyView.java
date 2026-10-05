package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * V1.2.0-beta · 遥控台「巨型翻页键」（docs/09 §5.2 + TASK-038 交互增强）。
 *
 * <p>整屏铺满：左右各半（默认）或上下各半（{@link #setVertical}）；键面 {@code paper2} +
 * **超大方向符号** + 主字「上一页 / 下一页」。未连接时整体降透明度至 40%（一眼看出"按了没用"）。
 *
 * <p><b>三条交互</b>（TASK-038）：
 * <ol>
 *   <li>轻点 → 发一页；</li>
 *   <li>长按不放 → 按 {@link #setRepeatMs} 间隔**连续**发页；</li>
 *   <li>整块滑动 → 位移过阈值即发一页，可连着滑连续翻（滑动方向：左/上 = 下一页，右/下 = 上一页）。</li>
 * </ol>
 *
 * <p>所有发送都经 {@link Listener#onFlip(boolean)} 交给 Activity → {@code RemoteLinkManager.sendCommand}
 * （本类**不碰协议**）。🔴 无震动反馈（用户 2026-10-05 拍板取消）。
 */
public class FlipKeyView extends View {

    /** 翻页回调：next=true 下一页 / false 上一页。 */
    public interface Listener {
        void onFlip(boolean next);
    }

    /** 长按起判延时（ms）：≥ 此值算长按连翻，否则是一次轻点。 */
    private static final long LONG_PRESS_MS = 400L;
    /** 默认长按连翻间隔（ms）—— 可调范围见 {@link #setRepeatMs}。 */
    public static final int REPEAT_MS_DEFAULT = 220;
    public static final int REPEAT_MS_MIN = 120;
    public static final int REPEAT_MS_MAX = 400;

    /** 未连接时的整体透明度（"按了没用"一眼可见）。 */
    private static final int ALPHA_DISCONNECTED = 102;   // 255 * 0.4

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final int slop;

    private boolean connected = true;
    private boolean swapped = false;
    private boolean vertical = false;
    private int repeatMs = REPEAT_MS_DEFAULT;
    private Listener listener;

    // 触摸状态
    private boolean down = false;
    private boolean longFired = false;
    private boolean slid = false;
    private float downX, downY;
    private float accum;                 // 滑动累计（判方向）
    private int pressedKey = -1;         // 0 = 第一半（左/上），1 = 第二半（右/下）

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!down) return;
            longFired = true;
            flipByKey(pressedKey);
            h.postDelayed(this, repeatMs);   // 连续：按住不放按间隔连翻
        }
    };

    public FlipKeyView(Context c) {
        this(c, null);
    }

    public FlipKeyView(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 连接态（false ⇒ 整体 40% 透明度）。 */
    public void setConnected(boolean on) {
        if (connected == on) return;
        connected = on;
        invalidate();
    }

    /** 方向交换：true ⇒ 左/上 = 下一页，右/下 = 上一页。 */
    public void setSwapped(boolean on) {
        if (swapped == on) return;
        swapped = on;
        invalidate();
    }

    /** 上下排布（默认左右）。 */
    public void setVertical(boolean on) {
        if (vertical == on) return;
        vertical = on;
        invalidate();
    }

    /** 长按连翻间隔（ms），越界钳回 [{@link #REPEAT_MS_MIN}, {@link #REPEAT_MS_MAX}]。 */
    public void setRepeatMs(int ms) {
        if (ms < REPEAT_MS_MIN) ms = REPEAT_MS_MIN;
        if (ms > REPEAT_MS_MAX) ms = REPEAT_MS_MAX;
        repeatMs = ms;
    }

    // ── 绘制 ──

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int a = connected ? 255 : ALPHA_DISCONNECTED;

        if (!vertical) {
            drawKey(c, 0f, 0f, w / 2f, h, false, pressedKey == 0, a);
            drawKey(c, w / 2f, 0f, w, h, true, pressedKey == 1, a);
        } else {
            drawKey(c, 0f, 0f, w, h / 2f, false, pressedKey == 0, a);
            drawKey(c, 0f, h / 2f, w, h, true, pressedKey == 1, a);
        }
    }

    /**
     * @param which 几何上的哪半（false = 左/上，true = 右/下）—— 决定方向符号的朝向
     * @param halfNext 该半对应的语义（考虑 swap 后）是不是"下一页"
     */
    private void drawKey(Canvas c, float l, float t, float rr, float b, boolean which, boolean pressed, int a) {
        r.set(l, t, rr, b);
        p.setStyle(Paint.Style.FILL);
        p.setColor(pressed ? InkTheme.keyPressed(getContext()) : InkTheme.paper2(getContext()));
        p.setAlpha(a);
        c.drawRect(r, p);

        // 两半之间的细界线
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(InkTheme.dp(getContext(), 0.5f));
        p.setColor(InkTheme.line(getContext()));
        p.setAlpha(a);
        c.drawRect(r, p);
        p.setStyle(Paint.Style.FILL);

        // 该半的语义：先按几何定默认，再套 swap
        boolean halfNext = which;
        if (swapped) halfNext = !halfNext;

        boolean hz = !vertical;
        float cx = (l + rr) / 2f;
        float cy = (t + b) / 2f;

        // 超大方向符号
        String sym = hz ? (which ? "›" : "‹") : (which ? "⌄" : "⌃");
        p.setTypeface(InkTheme.serif());
        p.setColor(InkTheme.bamboo(getContext()));
        p.setAlpha(a);                       // 🔴 setColor 会重置 alpha ⇒ 必须 setColor 后再 setAlpha
        p.setTextAlign(Paint.Align.CENTER);
        float symSize = Math.min(hz ? (b - t) * 0.42f : (rr - l) * 0.30f,
                hz ? (rr - l) * 0.5f : (b - t) * 0.42f);
        if (symSize < InkTheme.sp(getContext(), 40f)) symSize = InkTheme.sp(getContext(), 40f);
        p.setTextSize(symSize);
        c.drawText(sym, cx, cy + symSize * 0.24f, p);

        // 主字
        p.setTypeface(InkTheme.serif());
        p.setTextSize(InkTheme.sp(getContext(), 20f));
        p.setColor(InkTheme.ink(getContext()));
        p.setAlpha(a);                       // 🔴 同上：setColor 后再 setAlpha
        c.drawText(halfNext ? "下一页" : "上一页", cx,
                cy + symSize * 0.24f + InkTheme.sp(getContext(), 34f), p);

        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(null);
        p.setAlpha(255);
    }

    // ── 触摸（轻点 / 长按连翻 / 滑动）──

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                longFired = false;
                slid = false;
                accum = 0f;
                downX = e.getX();
                downY = e.getY();
                pressedKey = keyOf(e.getX(), e.getY());
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                h.postDelayed(longPress, LONG_PRESS_MS);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!down) return true;
                float d = vertical ? (e.getY() - downY) : (e.getX() - downX);
                if (Math.abs(d) > slop) {
                    // 一旦开始滑动，取消长按连翻
                    if (!longFired) h.removeCallbacks(longPress);
                }
                if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) {
                    accum = d;
                }
                if (Math.abs(accum) >= slideThreshold()) {
                    // 滑动方向：左/上 = 下一页，右/下 = 上一页（swap 后反转）
                    boolean next = accum < 0;
                    if (swapped) next = !next;
                    slid = true;
                    accum = 0f;
                    downX = e.getX();
                    downY = e.getY();
                    fire(next);
                }
                return true;

            case MotionEvent.ACTION_UP:
                h.removeCallbacks(longPress);
                if (down && !longFired && !slid) {
                    // 轻点：按落在哪半决定方向
                    pressedKey = keyOf(e.getX(), e.getY());
                    flipByKey(pressedKey);
                }
                down = false;
                pressedKey = -1;
                invalidate();
                performClick();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;

            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPress);
                down = false;
                pressedKey = -1;
                invalidate();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
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

    /** 滑动触发阈值（屏高/宽的一小段）。 */
    private float slideThreshold() {
        float base = vertical ? getHeight() : getWidth();
        return Math.max(InkTheme.dp(getContext(), 48f), base * 0.12f);
    }

    /** 落点属于哪半：0 = 左/上，1 = 右/下。 */
    private int keyOf(float x, float y) {
        if (!vertical) return x >= getWidth() / 2f ? 1 : 0;
        return y >= getHeight() / 2f ? 1 : 0;
    }

    /** 按落点那半发页（几何半 0/1 → swap → next/prev）。 */
    private void flipByKey(int key) {
        boolean next = (key == 1);
        if (swapped) next = !next;
        fire(next);
    }

    private void fire(boolean next) {
        if (listener != null) listener.onFlip(next);
    }
}
