package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 🆕 TASK-060 · 遥控台「按键」页（自绘）—— **环形轮盘（四向 + 确认）** + 下方系统键网格。
 * 🆕 TASK-062 · 形态重做：十字键 → **空调遥控器式环形轮盘**；系统键 → **圆角矩形、两列**。
 *
 * <p>用途：把 HID 键盘的**方向键 / 确认键 / 可达的系统键**用起来，让用户在墨水屏上做
 * **方向导航 / 翻菜单 / 确认**（如微信读书的界面导航）。竞品有独立「按键」页，本卡补齐。
 *
 * <h3>几何（TASK-062 起）</h3>
 * 竖排两段：顶部一行说明 → **环形轮盘**（外圈 4 扇区 = 上/下/左/右，圆心圆盘 = 确认「OK」）
 * → **系统键网格**（**2 列 × 3 行**，圆角矩形）。
 * 触控目标一律 ≥ 48dp（`docs/09` §七）。未连接 ⇒ 整体 40% 透明度（与 {@link FlipKeyView} 同款语义）。
 *
 * <h3>🔴 模块边界（为什么不直接用 `HidConst`）</h3>
 * `docs/02_架构.md` §1 的依赖方向是 `shell → feature → ui → net → core`，
 * **`ui` 只出边到 `core`**（见 `ui/MODULE.md`）⇒ 本类**不认识 HID usage**，
 * 只往上抛**本类自己的键 id**（{@link #K_UP} … {@link #K_END}），由 `shell/ConsoleActivity`
 * 映射到 {@code remote.HidConst.KEY_*} 再发送。
 *
 * <h3>🔴 系统键的可达性（卡面要求如实落地）</h3>
 * HID 键盘 usage 表**不覆盖全部 Android 系统键** ——「返回 / 最近」**无直接对应**，
 * `KEY_HOME` 落到的是**行首**（`MOVE_HOME`）而非系统 Home。卡面要求「**只列实测可达者**」，
 * 故 {@link #sysKeys} 的最终成员以**真机逐键定案**为准（见 {@code 验证记录/171}）。
 * ⛔ 不留"看着能用其实没用"的键。
 *
 * <h3>🔴 深色模式取色陷阱（TASK-062 修掉的坑）</h3>
 * 深色档里 {@link InkTheme#bambooWeak} / {@link InkTheme#line} 等是**带固有透明度**的
 * （如 `0x1A4ECDC4`）。若先 {@code setColor()} 再 {@code setAlpha(255)}，会把颜色自带的 α
 * **覆盖成不透明** —— 旧版确认键因此底色被强制成不透明青玉、与青玉字**同色**，「OK」直接隐身。
 * 本类统一走 {@link #setColorAlpha}（**把固有 α 与连接态 α 相乘**），从根上杜绝此类事故。
 */
public final class KeyPadView extends View {

    // ── 本类自己的键 id（**不是** HID usage；映射在 ConsoleActivity）──

    /** 十字键：上。 */
    public static final int K_UP = 1;
    /** 十字键：下。 */
    public static final int K_DOWN = 2;
    /** 十字键：左。 */
    public static final int K_LEFT = 3;
    /** 十字键：右。 */
    public static final int K_RIGHT = 4;
    /** 轮盘中心：确认（回车）。 */
    public static final int K_OK = 5;

    /** 系统键：跳格（Tab）。 */
    public static final int K_TAB = 102;
    /** 系统键：空格。 */
    public static final int K_SPACE = 103;
    /** 系统键：退格。 */
    public static final int K_BACKSPACE = 104;
    /** 系统键：向前删除。 */
    public static final int K_DELETE = 105;
    /** 系统键：行首。 */
    public static final int K_HOME = 106;
    /** 系统键：行尾。 */
    public static final int K_END = 107;

    /** 按键回调（键 id 见 {@link #K_UP} 等）。 */
    public interface Listener {
        void onKey(int keyId);
    }

    /** 未连接时的整体透明度（"按了没用"一眼可见，与 {@link FlipKeyView} 同值）。 */
    private static final int ALPHA_DISCONNECTED = 102;   // 255 * 0.4

    /**
     * 系统键（**只列真机实测可达者**；顺序即 UI 顺序，{@link #SYS_COLS} 个一行）。
     *
     * 🔴 真机逐键定案（S4，`验证记录/171`）：`Esc` 与「应用 / 菜单键」在该 ROM 上**无任何可见行为**
     * （连通知栏都不关、也不出选项菜单）⇒ 按卡面「只列实测可达者」**不上 UI**。
     * 对应 usage 仍留在 `HidConst`（注明不可行），将来换 ROM 可再验。
     * <p>🔴 TASK-062：用户拍板**暂不引入系统 HOME / 返回**（当前 HID 为纯键盘描述符，发不出）。
     */
    private static final int[] sysKeys = new int[] {
            K_BACKSPACE, K_SPACE,
            K_DELETE, K_TAB,
            K_HOME, K_END,
    };

    /** 系统键标签（与 {@link #sysKeys} **逐项对应**，改一处必须改另一处）。 */
    private static final String[] sysLabels = new String[] {
            "退格", "空格",
            "删除", "跳格",
            "行首", "行尾",
    };

    /** 系统键列数（6 键 ⇒ **2 列 × 3 行**）。 */
    private static final int SYS_COLS = 2;

    /** 外圈扇区之间的角度留白（度）—— 让四个方向"看得见是四块"。 */
    private static final float SECTOR_GAP_DEG = 12f;

    /** 轮盘整体相对可用空间的缩放（🆕 TASK-062 反馈：缩到 90%）。 */
    private static final float RING_SCALE = 0.90f;

    /** 圆心确认盘半径 / 外半径（🆕 TASK-062 反馈：内圈占比再小一档，0.46 → 0.40）。 */
    private static final float INNER_RATIO = 0.40f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path sectorPath = new Path();
    private final RectF rectOuter = new RectF();
    private final RectF rectInner = new RectF();

    /** 命中表（**仅下方系统键网格**）：矩形 + 键 id（{@link #layout} 填，{@link #hitTest} 用）。 */
    private final RectF[] hits = new RectF[16];
    private final int[] hitIds = new int[16];
    private int hitCount = 0;

    // ── 环形轮盘几何（layout() 算，onDraw / hitTest 共用同一份 ⇒ 杜绝"画这、点那"）──
    private boolean ringReady = false;
    private float ringCx, ringCy;
    private float ringR;        // 外半径
    private float ringInner;    // 圆心圆盘半径（确认键）

    private boolean connected = true;
    /** 当前按下的键 id（-1 = 无）。用**键 id** 而非下标，便于把自绘轮盘一起纳入。 */
    private int pressedId = -1;
    private Listener listener;

    public KeyPadView(Context c) {
        this(c, null);
    }

    public KeyPadView(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        for (int i = 0; i < hits.length; i++) hits[i] = new RectF();
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

    // ── 几何 ──

    private float dp(float v) {
        return InkTheme.dp(getContext(), v);
    }

    /**
     * 重算轮盘与系统键网格（幂等；{@link #onSizeChanged} 与 {@link #onDraw} 都会调）。
     *
     * <p>把"算几何"与"画"分开：命中测试与绘制**共用同一份**几何，
     * 从根上杜绝"画在这、点在别处"（本项目历史上栽过：`NotePickView` 的假按钮）。
     */
    private void layout() {
        float w = getWidth();
        float h = getHeight();
        hitCount = 0;
        ringReady = false;
        if (w <= 0f || h <= 0f) return;

        final float padX = dp(16f);
        final float captionH = dp(26f);
        final float gapV = dp(10f);

        // ① 系统键网格（先占底）：2 列 × 3 行，圆角矩形
        final float rowH = dp(54f);
        final float colGap = dp(12f);
        final float rowGap = dp(10f);
        final int rows = (sysKeys.length + SYS_COLS - 1) / SYS_COLS;
        final float gridH = rows * rowH;
        final float gridTop = h - gridH;
        final float keyW = (w - padX * 2f - colGap * (SYS_COLS - 1)) / SYS_COLS;
        for (int i = 0; i < sysKeys.length; i++) {
            int r = i / SYS_COLS;
            int c = i % SYS_COLS;
            float l = padX + c * (keyW + colGap);
            float t = gridTop + r * rowH + rowGap * 0.5f;
            float rr = l + keyW;
            float b = gridTop + (r + 1) * rowH - rowGap * 0.5f;
            addHit(l, t, rr, b, sysKeys[i]);
        }

        // ② 环形轮盘：占满「说明行」与「系统键网格」之间的空间
        float areaTop = captionH + gapV;
        float areaBottom = gridTop - gapV;
        float areaH = Math.max(0f, areaBottom - areaTop);
        float maxRbyW = (w - padX * 2f) * 0.5f;
        float maxRbyH = areaH * 0.5f;
        float r = Math.min(maxRbyW, maxRbyH);
        // 触控目标下限（docs/09 §七）：半径不足时尽量顶到下限，但仍受可用空间约束
        float minR = dp(76f);
        if (r < minR) r = Math.min(minR, Math.min(maxRbyW, maxRbyH));
        // 🆕 TASK-062（用户反馈）：轮盘整体缩到可用空间的 90%
        r *= RING_SCALE;
        if (r <= dp(24f)) return;                 // 空间太小 ⇒ 不画轮盘（防御）

        ringCx = w * 0.5f;
        ringCy = areaTop + areaH * 0.5f;
        ringR = r;
        ringInner = r * INNER_RATIO;
        ringReady = true;
    }

    private void addHit(float l, float t, float rr, float b, int id) {
        if (hitCount >= hits.length) return;
        hits[hitCount].set(l, t, rr, b);
        hitIds[hitCount] = id;
        hitCount++;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layout();
    }

    // ── 绘制 ──

    @Override
    protected void onDraw(Canvas c) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (hitCount == 0 || !ringReady) layout();
        int a = connected ? 255 : ALPHA_DISCONNECTED;

        drawCaption(c, a);

        if (ringReady) {
            // 上方 -90°（up），顺时针：上 → 右 → 下 → 左
            drawSector(c, -90f, K_UP, "\u2191", a);
            drawSector(c, 0f, K_RIGHT, "\u2192", a);
            drawSector(c, 90f, K_DOWN, "\u2193", a);
            drawSector(c, 180f, K_LEFT, "\u2190", a);
            drawOk(c, a);
        }

        for (int i = 0; i < hitCount; i++) {
            drawSystemKey(c, hits[i], hitIds[i], hitIds[i] == pressedId, a);
        }
    }

    private void drawCaption(Canvas c, int a) {
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(InkTheme.serif());
        p.setTextSize(InkTheme.sp(getContext(), 12f));
        setColorAlpha(p, InkTheme.ink3(getContext()), a);
        c.drawText("方向键 / 确认键：先让墨水屏停在可操作的界面再按。", dp(16f), dp(18f), p);
        p.setTypeface(null);
    }

    /** 画一个外圈扇区（环形带 + 边线 + 方向箭头）。 */
    private void drawSector(Canvas c, float centerDeg, int id, String glyph, int a) {
        float start = centerDeg - 45f + SECTOR_GAP_DEG * 0.5f;
        float sweep = 90f - SECTOR_GAP_DEG;
        rectOuter.set(ringCx - ringR, ringCy - ringR, ringCx + ringR, ringCy + ringR);
        rectInner.set(ringCx - ringInner, ringCy - ringInner, ringCx + ringInner, ringCy + ringInner);
        sectorPath.reset();
        sectorPath.arcTo(rectOuter, start, sweep, true);
        sectorPath.arcTo(rectInner, start + sweep, -sweep, false);
        sectorPath.close();

        boolean pressed = (id == pressedId);

        p.setStyle(Paint.Style.FILL);
        setColorAlpha(p, pressed ? InkTheme.keyPressed(getContext()) : InkTheme.paper2(getContext()), a);
        c.drawPath(sectorPath, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, dp(1f)));
        setColorAlpha(p, InkTheme.line(getContext()), a);
        c.drawPath(sectorPath, p);

        // 方向箭头（落在扇区弧中线上）
        float midR = (ringInner + ringR) * 0.5f;
        float gx = ringCx + midR * (float) Math.cos(Math.toRadians(centerDeg));
        float gy = ringCy + midR * (float) Math.sin(Math.toRadians(centerDeg));
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(InkTheme.serif());
        p.setTextSize((ringR - ringInner) * 0.70f);
        setColorAlpha(p, InkTheme.ink(getContext()), a);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(glyph, gx, gy - (fm.ascent + fm.descent) * 0.5f, p);
        p.setTypeface(null);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * 圆心确认盘。
     *
     * 🔴 取色原则（TASK-062 修「OK 隐身」的最终解）：**高对比在所有状态都成立**。
     * 本页未连接时会整体降到 40% α（"按了没用"），此时"深底 + 深字/亮底 + 亮字"这类
     * 中调搭配会一起塌掉 ⇒ 改用 **浅底（竹青浅底，保留其固有 10% α）+ 墨色字 + 竹青 2dp 描边**：
     * 亮/深 × 连接/未连接 **四种组合**下字与底都够分离，且描边把"中心主操作"立住。
     * 按下 ⇒ 反白（竹青实底 + 纸白字），给出明确的按下反馈。
     */
    private void drawOk(Canvas c, int a) {
        boolean pressed = (pressedId == K_OK);
        float r = ringInner * 0.94f;

        // 底
        p.setStyle(Paint.Style.FILL);
        setColorAlpha(p, pressed ? InkTheme.bamboo(getContext()) : InkTheme.bambooWeak(getContext()), a);
        c.drawCircle(ringCx, ringCy, r, p);

        // 圈（2dp 竹青，让中心键立得住）
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, dp(2f)));
        setColorAlpha(p, InkTheme.bamboo(getContext()), a);
        c.drawCircle(ringCx, ringCy, r, p);

        // 字：常态墨色（浅底上高对比）/ 按下纸白（实底上高对比）
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(InkTheme.serif());
        p.setTextSize(ringInner * 0.78f);
        setColorAlpha(p, pressed ? InkTheme.paper(getContext()) : InkTheme.ink(getContext()), a);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText("OK", ringCx, ringCy - (fm.ascent + fm.descent) * 0.5f, p);
        p.setTypeface(null);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 下方系统键：**圆角矩形** + 居中文字。 */
    private void drawSystemKey(Canvas c, RectF box, int id, boolean pressed, int a) {
        float cr = dp(10f);
        p.setStyle(Paint.Style.FILL);
        setColorAlpha(p, pressed ? InkTheme.keyPressed(getContext()) : InkTheme.paper2(getContext()), a);
        c.drawRoundRect(box, cr, cr, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, dp(1f)));
        setColorAlpha(p, InkTheme.line(getContext()), a);
        c.drawRoundRect(box, cr, cr, p);

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(InkTheme.serif());
        p.setTextSize(InkTheme.sp(getContext(), 16f));
        setColorAlpha(p, InkTheme.ink(getContext()), a);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(labelOf(id), box.centerX(), box.centerY() - (fm.ascent + fm.descent) * 0.5f, p);
        p.setTypeface(null);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private String labelOf(int id) {
        for (int i = 0; i < sysKeys.length; i++) {
            if (sysKeys[i] == id) return sysLabels[i];
        }
        return "";
    }

    /**
     * 🔴 统一的取色-设置：**把颜色的固有 α 与"连接态 α"相乘**后再落到 Paint。
     *
     * <p>深色档有不少颜色**自带透明度**（如 `DARK_BAMBOO_WEAK=0x1A4ECDC4`、`DARK_LINE=0x1CE9E4D8`）。
     * 若图省事写成 {@code setColor(c); setAlpha(a);}，{@code setAlpha} 会把固有 α **覆盖**掉 ⇒
     * 半透明色被强制成不透明（本项目 TASK-062 前确认键「OK」隐身即因此）。故统一走本方法。
     */
    private void setColorAlpha(Paint paint, int color, int a) {
        paint.setColor(color);
        int intrinsic = (color >>> 24) & 0xFF;
        paint.setAlpha(intrinsic * a / 255);
    }

    // ── 触摸 ──

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pressedId = hitTest(e.getX(), e.getY());
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int id = hitTest(e.getX(), e.getY());
                // 只有"按下与抬起落在同一区域"才算一次点击（防止误触）
                if (id != -1 && id == pressedId) {
                    fire(id);
                }
                pressedId = -1;
                invalidate();
                performClick();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                pressedId = -1;
                invalidate();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** 命中哪个键（-1 = 没命中）。先查下方网格矩形，再按**极坐标**判定轮盘扇区/圆心。 */
    private int hitTest(float x, float y) {
        if (hitCount == 0 || !ringReady) layout();
        for (int i = 0; i < hitCount; i++) {
            if (hits[i].contains(x, y)) return hitIds[i];
        }
        if (ringReady) {
            float dx = x - ringCx;
            float dy = y - ringCy;
            float d = (float) Math.hypot(dx, dy);
            if (d <= ringInner * 0.94f) return K_OK;              // 圆心 = 确认
            if (d <= ringR) {                                     // 外圈 = 四向
                double ang = Math.toDegrees(Math.atan2(dy, dx));  // -180..180，0=正右、90=正下
                if (ang >= -45 && ang < 45) return K_RIGHT;
                if (ang >= 45 && ang < 135) return K_DOWN;
                if (ang >= 135 || ang < -135) return K_LEFT;
                return K_UP;
            }
        }
        return -1;
    }

    private void fire(int keyId) {
        if (listener != null) listener.onKey(keyId);
    }
}
