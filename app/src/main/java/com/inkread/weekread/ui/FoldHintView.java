package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

import com.inkread.weekread.core.CardPrefs;

/**
 * V1.2.1-beta · TASK-065：可折叠「引导卡」的标题行（自绘控件）。
 *
 * <p>体检结论（{@code 2026-10-07_产品体检…} §4.2）：设置 / 实验室页「一屏堆 200+ 字」。
 * 解法 = **一屏三行内 + 折叠卡** —— 该说的主操作留在屏上，长段说明收进折叠卡，
 * 点标题行展开 / 收起。
 *
 * <p>本类**只画标题行**（三角 + 文字），**不认识内容**；展开态由调用方通过
 * {@link Listener#onToggle(boolean)} 切换内容容器的可见性。这样内容排版仍是普通
 * TextView / LinearLayout，**不改任何功能行为**（验收 A6）。
 *
 * <p>🔴 配色两端各一套 token、语义一致：
 * <ul>
 *   <li><b>手机端</b>（{@code install_role=phone}）⇒ 走 {@link InkTheme} 亮 / 深双色板；</li>
 *   <li><b>墨水屏端</b> ⇒ 纯黑白（禁彩色，{@code docs/03} 硬规则）。</li>
 * </ul>
 *
 * <p>🔴 符合 {@code docs/09}：纯自绘、无圆角、无阴影、**无动效**（直接 {@code invalidate}，
 * 不跑动画帧 —— 墨水屏闪屏红线）。
 *
 * <p>🆕 <b>TASK-070（V1.2.1-beta · 手机端版面精修）</b>：修正两处「上折叠卡后版面变乱」——
 * <ol>
 *   <li><b>字号层级</b>：原实现只有一套「屏高 × 0.0015」标度（墨水屏专用，S4 上 19.2px ≈ 14sp）。
 *       同一个值在手机上（2400px 高）会算出 57.6px ≈ **21sp**，比设置页正文（13–15sp）大出一大截
 *       ⇒ 层级塌陷。现在手机端改走 {@code sp}（15sp，与设置页正文同一把尺），墨水屏端**沿用原标度**
 *       （原值不变）。</li>
 *   <li><b>风格统一</b>：手机端原来给标题行铺了淡青底（{@code BAMBOO_WEAK}）+ 青色三角，
 *       而设置 / 实验室页其余部分一律黑白灰 ⇒ 折叠卡像「另一个 App 的控件」。现在手机端
 *       **去掉彩色底**、三角与文字同色（{@link InkTheme#ink}），只留标题 + 下框线，
 *       与同页其它条目风格一致。</li>
 * </ol>
 */
public class FoldHintView extends View {

    public interface Listener {
        /** @param expanded 切换后的展开态（true = 已展开）。 */
        void onToggle(boolean expanded);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();

    private String title = "说明";
    private boolean expanded = false;
    private Listener listener;

    // ── 尺寸（🆕 TASK-070：按角色取标度，见类注释 §🆕）──
    private float mTextSize = 0f;   // 标题字号（px）
    private float mPadH = 0f;       // 左右内边距（px）
    private float mPadV = 0f;       // 上下内边距（px）

    public FoldHintView(Context c) { this(c, null); }

    public FoldHintView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0x00000000);
        recompute();
    }

    /**
     * 按当前角色重算字号 / 内边距。
     *
     * <p>手机端 = sp / dp 标度；墨水屏端 = 屏高比例（与 TASK-065 原始实现**逐值一致**）。
     * 角色只在 Activity 重建时才变，所以构造里算一次足够；{@link #onMeasure} 再算一次是兜底
     * （极少数分支会在 attach 后才读到角色偏好）。
     */
    private void recompute() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (isEink()) {
            float unit = dm.heightPixels * 0.0015f;
            mTextSize = 16f * unit;
            mPadH = 10f * unit;
            mPadV = 12f * unit;
        } else {
            mTextSize = 15f * dm.scaledDensity;   // 15sp：与设置页正文同一层级
            mPadH = 0f;                           // 无底无框 ⇒ 与页面正文左沿对齐
            mPadV = 9f * dm.density;
        }
    }

    // ── API ──

    public void setTitle(String t) {
        if (t != null) { title = t; requestLayout(); invalidate(); }
    }

    public boolean isExpanded() { return expanded; }

    public void setExpanded(boolean e) {
        if (this.expanded == e) return;
        this.expanded = e;
        invalidate();
    }

    public void setListener(Listener l) { listener = l; }

    // ── 量算 ──

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        recompute();
        int w = MeasureSpec.getSize(wSpec);
        p.setTextSize(mTextSize);
        Paint.FontMetrics fm = p.getFontMetrics();
        int h = (int) Math.ceil(mPadV * 2 + (fm.descent - fm.ascent));
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    // ── 绘制 ──

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        final boolean eink = isEink();
        // 手机端：亮 / 深由 InkTheme 语义自动判定（reader 端恒 dark=false，走下面的黑白分支）
        final boolean dark = !eink && InkTheme.isDark(getContext());
        final int colText, colLine, colTri, colBg;
        if (eink) {
            colText = 0xFF000000; colLine = 0xFFD8D8D8; colTri = 0xFF000000; colBg = 0xFFFFFFFF;
        } else {
            colText = dark ? InkTheme.DARK_INK : InkTheme.INK;
            colLine = dark ? InkTheme.DARK_LINE : InkTheme.LINE;
            colTri  = colText;              // 🆕 TASK-070：取消彩色强调，与页面同色
            colBg   = 0x00000000;           // 🆕 TASK-070：不铺底，融进设置页的留白
        }

        // 底（墨水屏端仍是白底 = 与页面同色；手机端不铺）
        if ((colBg >>> 24) != 0) c.drawColor(colBg);

        // 左三角（收起 = 右指；展开 = 下指）
        float cx = mPadH + mTextSize * 0.42f;
        float cy = h / 2f;
        float r = mTextSize * 0.34f;
        tri.reset();
        if (expanded) {
            tri.moveTo(cx - r, cy - r * 0.6f);
            tri.lineTo(cx + r, cy - r * 0.6f);
            tri.lineTo(cx, cy + r * 0.8f);
        } else {
            tri.moveTo(cx - r * 0.6f, cy - r);
            tri.lineTo(cx - r * 0.6f, cy + r);
            tri.lineTo(cx + r * 0.8f, cy);
        }
        tri.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(colTri);
        c.drawPath(tri, p);

        // 标题（衬线 = 书香气来源；两端一致）；手机端加粗以承担「可点条目」的层级
        p.setColor(colText);
        p.setTextSize(mTextSize);
        p.setTypeface(InkTheme.serif());
        p.setFakeBoldText(!eink);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = (h - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        c.drawText(title, mPadH + mTextSize * 1.05f, baseline, p);
        p.setFakeBoldText(false);
        p.setTypeface(null);

        // 下框线
        p.setStyle(Paint.Style.FILL);
        p.setColor(colLine);
        c.drawRect(0f, h - 1f, w, h, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        expanded = !expanded;
        invalidate();
        if (listener != null) listener.onToggle(expanded);
        return true;
    }

    /** 墨水屏端（非 phone）⇒ 纯黑白。异常兜底为墨水屏语义（黑白最安全）。 */
    private boolean isEink() {
        try {
            return CardPrefs.getInstallRole(getContext()) != CardPrefs.INSTALL_ROLE_PHONE;
        } catch (Throwable t) {
            return true;
        }
    }
}
