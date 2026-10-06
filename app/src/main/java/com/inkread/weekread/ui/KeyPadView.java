package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 🆕 TASK-060 · 遥控台「按键」页（自绘）—— 十字方向键 + 确认键 + 系统键一排。
 *
 * <p>用途：把 HID 键盘的**方向键 / 确认键 / 可达的系统键**用起来，让用户在墨水屏上做
 * **方向导航 / 翻菜单 / 确认**（如微信读书的界面导航）。竞品有独立「按键」页，本卡补齐。
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
 * <h3>几何</h3>
 * 竖排三段：顶部一行说明 → **十字键（3×3 网格，中心 OK）** → **系统键网格（每行 4 个）**。
 * 触控目标一律 ≥ 48dp（`docs/09` §七）。未连接 ⇒ 整体 40% 透明度（与 {@link FlipKeyView} 同款语义）。
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
    /** 十字键中心：确认（回车）。 */
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
     * 系统键（**只列真机实测可达者**；顺序即 UI 顺序，每行 {@link #SYS_PER_ROW} 个）。
     *
     * 🔴 真机逐键定案（S4，`验证记录/171`）：`Esc` 与「应用 / 菜单键」在该 ROM 上**无任何可见行为**
     * （连通知栏都不关、也不出选项菜单）⇒ 按卡面「只列实测可达者」**不上 UI**。
     * 对应 usage 仍留在 `HidConst`（注明不可行），将来换 ROM 可再验。
     */
    private static final int[] sysKeys = new int[] {
            K_BACKSPACE, K_SPACE, K_DELETE,
            K_TAB, K_HOME, K_END,
    };

    /** 系统键标签（与 {@link #sysKeys} **逐项对应**，改一处必须改另一处）。 */
    private static final String[] sysLabels = new String[] {
            "退格", "空格", "删除",
            "跳格", "行首", "行尾",
    };

    /** 系统键每行个数（6 键 ⇒ 3×2 网格，与十字键的 3 列对齐）。 */
    private static final int SYS_PER_ROW = 3;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    /** 命中表：每个可点区域的矩形 + 其键 id（{@link #layout} 填，{@link #hitTest} 用）。 */
    private final RectF[] hits = new RectF[16];
    private final int[] hitIds = new int[16];
    private int hitCount = 0;

    private boolean connected = true;
    private int pressedIdx = -1;
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

    private final float dp(float v) {
        return InkTheme.dp(getContext(), v);
    }

    /**
     * 重算全部可点矩形（幂等；{@link #onSizeChanged} 与 {@link #onDraw} 都会调）。
     *
     * <p>把"算几何"与"画"分开：命中测试与绘制**共用同一份** {@link #hits}，
     * 从根上杜绝"画在这、点在别处"（本项目历史上栽过：`NotePickView` 的假按钮）。
     */
    private void layout() {
        float w = getWidth();
        float h = getHeight();
        hitCount = 0;
        if (w <= 0f || h <= 0f) return;

        float padX = dp(16f);
        float captionH = dp(28f);
        float gapV = dp(8f);

        int perRow = SYS_PER_ROW;
        int rows = (sysKeys.length + perRow - 1) / perRow;
        float sysRowH = dp(52f);
        float sysH = rows * sysRowH;
        float sysTop = h - sysH;                       // 系统键网格顶（含行距，直接占底）

        // ① 系统键网格（先占底，十字键用剩下的空间）
        float keyW = (w - padX * 2f) / perRow;
        for (int i = 0; i < sysKeys.length; i++) {
            int row = i / perRow;
            int col = i % perRow;
            float l = padX + col * keyW;
            float t = sysTop + row * sysRowH + dp(4f);
            float rr = l + keyW - dp(8f);
            float b = sysTop + (row + 1) * sysRowH - dp(4f);
            addHit(l, t, rr, b, sysKeys[i]);
        }

        // ② 十字键（3×3 网格，居中于「说明行」与「系统键」之间）
        float areaTop = captionH + gapV;
        float areaBottom = sysTop - gapV;
        float areaH = Math.max(0f, areaBottom - areaTop);
        float cell = Math.min((w - padX * 2f) / 3f, areaH / 3f);
        float minCell = dp(48f);
        if (cell < minCell) cell = minCell;            // 触控目标下限（docs/09 §七）
        float cx = w / 2f;
        float cy = areaTop + areaH / 2f;
        float gx0 = cx - cell * 1.5f;
        float gy0 = cy - cell * 1.5f;
        float inset = dp(4f);
        // 上 / 左 / OK / 右 / 下（3×3 的十字五格）
        addHit(gx0 + cell + inset, gy0 + inset, gx0 + cell * 2f - inset, gy0 + cell - inset, K_UP);
        addHit(gx0 + inset, gy0 + cell + inset, gx0 + cell - inset, gy0 + cell * 2f - inset, K_LEFT);
        addHit(gx0 + cell + inset, gy0 + cell + inset, gx0 + cell * 2f - inset, gy0 + cell * 2f - inset, K_OK);
        addHit(gx0 + cell * 2f + inset, gy0 + cell + inset, gx0 + cell * 3f - inset, gy0 + cell * 2f - inset, K_RIGHT);
        addHit(gx0 + cell + inset, gy0 + cell * 2f + inset, gx0 + cell * 2f - inset, gy0 + cell * 3f - inset, K_DOWN);
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
        if (hitCount == 0) layout();
        int a = connected ? 255 : ALPHA_DISCONNECTED;

        drawCaption(c, a);
        for (int i = 0; i < hitCount; i++) {
            drawKey(c, hits[i], hitIds[i], i == pressedIdx, a);
        }
    }

    private void drawCaption(Canvas c, int a) {
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(InkTheme.serif());
        p.setTextSize(InkTheme.sp(getContext(), 12f));
        p.setColor(InkTheme.ink3(getContext()));
        p.setAlpha(a);
        c.drawText("方向键 / 确认键：先让墨水屏停在可操作的界面再按。", dp(16f), dp(18f), p);
        p.setAlpha(255);
        p.setTypeface(null);
    }

    private void drawKey(Canvas c, RectF box, int id, boolean pressed, int a) {
        boolean isOk = (id == K_OK);

        // 键面：OK 用竹青浅底强调（一句话：唯一"主操作"），其余 paper2
        p.setStyle(Paint.Style.FILL);
        p.setColor(isOk ? InkTheme.bambooWeak(getContext())
                : (pressed ? InkTheme.keyPressed(getContext()) : InkTheme.paper2(getContext())));
        p.setAlpha(a);
        c.drawRect(box, p);

        // 描边
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(0.5f));
        p.setColor(isOk ? InkTheme.bamboo(getContext()) : InkTheme.line(getContext()));
        p.setAlpha(a);
        c.drawRect(box, p);
        p.setStyle(Paint.Style.FILL);

        float cx = box.centerX();
        float cy = box.centerY();

        if (id >= 100) {
            // ── 系统键：只有文字标签 ──
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(InkTheme.serif());
            p.setTextSize(InkTheme.sp(getContext(), 15f));
            p.setColor(InkTheme.ink(getContext()));
            p.setAlpha(a);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(labelOf(id), cx, cy - (fm.descent + fm.ascent) / 2f, p);
        } else {
            // ── 十字键：方向符号（上）+ 文字标签（下）──
            String sym = (id == K_UP) ? "↑" : (id == K_DOWN) ? "↓" : (id == K_LEFT) ? "←" : "→";
            float symSize = Math.min(box.width(), box.height()) * 0.42f;
            float minSym = InkTheme.sp(getContext(), 22f);
            if (symSize < minSym) symSize = minSym;

            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(InkTheme.serif());
            p.setTextSize(symSize);
            p.setColor(InkTheme.bamboo(getContext()));   // 🔴 setColor 会重置 alpha，故之后必须再 setAlpha
            p.setAlpha(a);
            c.drawText(isOk ? "OK" : sym, cx, cy + symSize * 0.30f, p);

            p.setTextSize(InkTheme.sp(getContext(), 13f));
            p.setColor(InkTheme.ink2(getContext()));
            p.setAlpha(a);
            c.drawText(labelOf(id), cx, cy + symSize * 0.30f + InkTheme.sp(getContext(), 22f), p);

            p.setTypeface(null);
        }

        p.setTextAlign(Paint.Align.LEFT);
        p.setAlpha(255);
    }

    private String labelOf(int id) {
        switch (id) {
            case K_UP: return "上";
            case K_DOWN: return "下";
            case K_LEFT: return "左";
            case K_RIGHT: return "右";
            case K_OK: return "确认";
            default:
                for (int i = 0; i < sysKeys.length; i++) {
                    if (sysKeys[i] == id) return sysLabels[i];
                }
                return "";
        }
    }

    // ── 触摸 ──

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pressedIdx = hitTest(e.getX(), e.getY());
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int idx = hitTest(e.getX(), e.getY());
                // 只有"按下与抬起落在同一格"才算一次点击（防止误触）
                if (idx >= 0 && idx == pressedIdx) {
                    fire(hitIds[idx]);
                }
                pressedIdx = -1;
                invalidate();
                performClick();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                pressedIdx = -1;
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

    /** 命中哪个格（-1 = 没命中）。 */
    private int hitTest(float x, float y) {
        if (hitCount == 0) layout();
        for (int i = 0; i < hitCount; i++) {
            if (hits[i].contains(x, y)) return i;
        }
        return -1;
    }

    private void fire(int keyId) {
        if (listener != null) listener.onKey(keyId);
    }
}
