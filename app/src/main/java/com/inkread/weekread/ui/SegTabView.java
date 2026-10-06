package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 设置页顶部的分段页签（v0.4.3）：**初始化 / 自定义**。
 * （v0.7 起调用方可用 {@link #setLabels} 扩到三段，加「实验室」—— 默认标签仍是两段。）
 *
 * 为什么不用两个 Activity：设置项的读写本来就是即时生效的（勾了就存），
 * 拆成两个 Activity 只会多一次整屏刷新、还得在两页之间同步已填的 Key。
 * 用同一页内的两个容器 + 顶部页签切换，state 天然共享，也与主页大标签栏
 * （同为本类实例）是同一套视觉语言（纯黑白、选中加粗 + 底部黑条）。
 *
 * 标签由调用方经 {@link #setLabels} 给定：设置页 2~3 段，主页大标签 3~4 段。
 *
 * <p>🆕 <b>TASK-066 · 按角色取色取字</b>：本组件**两端都在用**（手机端设置页 / 墨水屏端主页大标签），
 * 所以色与字都走 {@link InkTheme#isPhone(Context)} 二选一 ——
 * <ul>
 *   <li><b>phone</b> ⇒ 原字面量一字不改（逐像素零差异，验收 A5）；</li>
 *   <li><b>墨水屏</b> ⇒ 取 {@link InkTheme} 单色 token（墨 / 提示 / 界线，**值与原本相同**），
 *       且标题改**衬线** ⇒ 与手机端「书香气」共用同一套设计语言（验收 A2）。</li>
 * </ul>
 */
public class SegTabView extends View {

    public interface Listener {
        /** index 从 0 开始，与 {@link #setLabels} 的顺序一致 */
        void onSegSelected(int index);
    }

    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF9A9A9A;

    // 🆕 TASK-041：深色模式色（仅手机端 setDark(true) 时启用；默认 false ⇒ 与现状逐像素一致）
    private static final int D_BG = 0xFF070A0F;        // 墨底（= InkTheme.DARK_PAPER）
    private static final int D_INK = 0xFFE9E4D8;       // 纸白（= InkTheme.DARK_INK）
    private static final int D_GRAY = 0xFF7E8894;      // 淡（= InkTheme.DARK_INK3）
    private static final int D_SEP = 0x1CE9E4D8;       // 界线（rgba .11）
    private static final int D_BOTTOM = 0x1CE9E4D8;    // 下边框

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float unit;               // 1 号 = 屏幕高度 * 0.0015（同全局字号口径）
    private String[] labels = {"初始化", "自定义"};
    private int selected = 0;
    private Listener listener;
    /** 🆕 TASK-041：深色模式（默认 false = 亮色，行为与改造前完全一致）。 */
    private boolean dark = false;

    public SegTabView(Context c) { this(c, null); }

    public SegTabView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0xFFFFFFFF);
        unit = c.getResources().getDisplayMetrics().heightPixels * 0.0015f;
    }

    /** 🆕 TASK-041：切深色（设置页在 phone + dark 时调用；默认不调 ⇒ 零差异）。 */
    public void setDark(boolean on) {
        if (this.dark == on) return;
        this.dark = on;
        setBackgroundColor(on ? D_BG : 0xFFFFFFFF);
        invalidate();
    }

    public boolean isDark() { return dark; }

    public void setLabels(String[] l) {
        if (l != null && l.length > 0) {
            labels = l;
            if (selected > labels.length - 1) selected = labels.length - 1;
            invalidate();
        }
    }

    public void setListener(Listener l) { listener = l; }

    public int getSelected() { return selected; }

    public void setSelected(int i) {
        if (i < 0) i = 0;
        if (i > labels.length - 1) i = labels.length - 1;
        if (i == selected) return;
        selected = i;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(dark ? D_BG : 0xFFFFFFFF);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float slot = w / labels.length;

        // 🆕 TASK-066：按角色取色取字。🔴 在 onDraw 里判（不在构造里）——
        //    首次启动时 role 还没选完，构造期判会把手机端错判成墨水屏。
        final boolean mono = !InkTheme.isPhone(getContext());
        final int colInk;
        final int colGray;
        final int colSep;
        final int colBottom;
        if (dark) {
            colInk = D_INK; colGray = D_GRAY; colSep = D_SEP; colBottom = D_BOTTOM;
        } else if (mono) {
            colInk = InkTheme.MONO_INK;  colGray = InkTheme.MONO_INK3;
            colSep = InkTheme.MONO_LINE; colBottom = InkTheme.MONO_LINE;
        } else {
            colInk = INK; colGray = GRAY; colSep = 0xFFE0E0E0; colBottom = 0xFFD8D8D8;
        }

        // 衬线（书香气来源）：仅墨水屏端；手机端保持原样（default 字体）。
        p.setTypeface(mono ? InkTheme.serif() : null);
        float size = 18f * unit;
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = (h - (fm.descent - fm.ascent)) / 2f - fm.ascent;

        for (int i = 0; i < labels.length; i++) {
            float cx = slot * i + slot / 2f;
            boolean on = (i == selected);
            p.setFakeBoldText(on);
            p.setColor(on ? colInk : colGray);
            c.drawText(labels[i], cx, baseline, p);

            if (on) {
                // 当前页：底部指示条（墨水屏上比"加粗"更醒目）
                p.setStyle(Paint.Style.FILL);
                p.setColor(colInk);
                float bw = slot * 0.34f;
                c.drawRect(cx - bw / 2f, h - Math.max(3f, h * 0.075f), cx + bw / 2f, h, p);
            }
        }
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(null);

        // 页签之间的分隔竖线（弱化，暗示并列关系）
        p.setColor(colSep);
        p.setStyle(Paint.Style.STROKE);
        for (int i = 1; i < labels.length; i++) {
            c.drawLine(slot * i, h * 0.28f, slot * i, h * 0.72f, p);
        }

        // 整条下边框
        p.setColor(colBottom);
        p.setStyle(Paint.Style.FILL);
        c.drawRect(0f, h - 1f, w, h, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        int idx = (int) (e.getX() / (getWidth() / (float) labels.length));
        if (idx < 0) idx = 0;
        if (idx > labels.length - 1) idx = labels.length - 1;
        setSelected(idx);
        if (listener != null) listener.onSegSelected(idx);
        return true;
    }
}
