package com.inkread.weekread.feature;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.inkread.weekread.ui.InkTheme;

import java.util.Locale;

/**
 * 自绘的「手动录价」数字键盘（标题行 + 12 键）。
 *
 * <p>🆕 task-66（第七批 W2）：本类是 **从 {@link BillSection} 原样抽出的唯一实现**。
 * 起因：用户第七批把 ③(2) 改成「长按原价/免费区 ⇒ 可输入价格」，"阅读资产"也要用同一套键盘；
 * 而覆盖窗里塞不进 `View`/`EditText`（与墨单同一条约束）⇒ 键盘必须自绘，且**只应有一份**。
 *
 * <p><b>抽类判据</b>（为什么能抽、边界在哪）：
 * <ul>
 *   <li>键盘本体 —— 面板矩形、12 键排布、命中、输入串状态 —— 与宿主数据**完全无关**；
 *   <li>宿主差异只有两点：**命中哪一行**（宿主自己扫行表）与**落盘后怎么重建行**
 *       （宿主自己 `invalidate`）⇒ 两点都留在宿主；
 *   <li>本类**不碰** `Context`、不碰 `MenuPrice`、不写任何日志：{@link #press(int)} 只回一个结果码，
 *       由宿主决定落盘与提示。
 * </ul>
 *
 * <p>🔴 **几何一字未改**：{@link #W_RATIO}/{@link #KEY_H_UNITS}/{@link #TITLE_H_UNITS} 与 12 键
 * 排布、留白（`unit*10f` / `unit*3f` / `unit*12f`）全部照抄原实现 ⇒ 抽动不引入任何像素差异
 * （对照见 `验证记录/232`）。
 */
final class PricePad {

    /** 键码：0–9 用数字本身，其余用这四个常量。 */
    static final int PK_DOT = 10, PK_DEL = 11, PK_OK = 12, PK_CANCEL = 13;

    /** 面板占宽比 / 单键高 / 标题行高（×unit）。 */
    static final float W_RATIO = 0.70f;
    static final float KEY_H_UNITS = 36f;
    static final float TITLE_H_UNITS = 34f;

    /** {@link #press(int)} 的返回值。 */
    static final int R_EDIT = 0;      // 只改了输入串
    static final int R_CLEAR = 1;     // 空输入 + 「确定」 ⇒ 宿主要**清除**该行手动价
    static final int R_COMMIT = 2;    // 「确定」 ⇒ 宿主要落盘 {@link #resultFen()}
    static final int R_CANCEL = 3;    // 「取消」（或解析失败）⇒ 宿主关掉即可

    /** 标题行字号（×unit）；与墨单原值一致。 */
    private static final float SZ_TITLE = 15f;
    /** 书名提示行字号（×unit）；与墨单原值一致。 */
    private static final float SZ_BOOK = 12f;
    private static final int GRAY = 0xFF3C3C3C;

    private boolean open;
    private String bookId = "";
    private String title = "";
    private String text = "";
    private int fen;

    boolean isOpen() { return open; }

    String bookId() { return bookId; }

    String title() { return title; }

    /** 上一次 {@link #press(int)} 返回 {@link #R_COMMIT} 时解析出的**分**。 */
    int resultFen() { return fen; }

    /** 当前输入串（未提交的"元"文本）——宿主只读，不用于落盘。 */
    String text() { return text; }

    /** 打开键盘；{@code curFen > 0} ⇒ 回填现值（便于改价），否则从空开始。 */
    void open(String bookId, String title, int curFen) {
        this.open = true;
        this.bookId = (bookId == null) ? "" : bookId;
        this.title = (title == null) ? "" : title;
        this.text = (curFen > 0) ? fmt(curFen) : "";
        this.fen = 0;
    }

    void close() {
        open = false;
        bookId = "";
        title = "";
        text = "";
        fen = 0;
    }

    /** 键盘面板矩形（屏幕坐标；**画与命中共用**）。 */
    RectF panel(float w, float vh, float unit) {
        float pw = w * W_RATIO;
        float ph = TITLE_H_UNITS * unit + 5f * KEY_H_UNITS * unit + unit * 12f;
        float l = (w - pw) / 2f;
        float t = (vh - ph) / 2f;
        if (t < unit * 8f) t = unit * 8f;
        return new RectF(l, t, l + pw, t + ph);
    }

    /** 某个键的矩形；{@code key} 用 {@link #PK_DOT} 等常量或 0–9。 */
    RectF keyRect(float w, float vh, float unit, int key) {
        RectF panel = panel(w, vh, unit);
        float pad = unit * 10f;
        float colsW = panel.width() - pad * 2f;
        float colW = colsW / 3f;
        float top0 = panel.top + TITLE_H_UNITS * unit;
        if (key == PK_OK || key == PK_CANCEL) {
            float half = colsW / 2f;
            float y = top0 + 4f * KEY_H_UNITS * unit;
            if (key == PK_CANCEL) {
                return new RectF(panel.left + pad, y, panel.left + pad + half, y + KEY_H_UNITS * unit);
            }
            return new RectF(panel.left + pad + half, y, panel.right - pad, y + KEY_H_UNITS * unit);
        }
        int row, col;
        if (key >= 1 && key <= 9) { row = (key - 1) / 3; col = (key - 1) % 3; }
        else if (key == 0) { row = 3; col = 1; }
        else if (key == PK_DOT) { row = 3; col = 0; }
        else if (key == PK_DEL) { row = 3; col = 2; }
        else return new RectF();
        float l = panel.left + pad + col * colW;
        float y = top0 + row * KEY_H_UNITS * unit;
        return new RectF(l + unit * 3f, y + unit * 3f,
                l + colW - unit * 3f, y + KEY_H_UNITS * unit - unit * 3f);
    }

    /** 键盘命中：返回键码；{@code -1} = 没中。 */
    int hit(float x, float y, float w, float vh, float unit) {
        if (!open) return -1;
        int[] keys = { 1, 2, 3, 4, 5, 6, 7, 8, 9, PK_DOT, 0, PK_DEL, PK_CANCEL, PK_OK };
        if (!panel(w, vh, unit).contains(x, y)) return -1;
        for (int i = 0; i < keys.length; i++) {
            if (keyRect(w, vh, unit, keys[i]).contains(x, y)) return keys[i];
        }
        return -1;
    }

    /**
     * 按一个键（**只改瞬时输入串，不落任何盘**）。
     *
     * <p>🔴 录入单位是**元**（最多两位小数），{@link #resultFen()} 给出**分** ⇒ 收尾一行四舍五入，
     * 绝不出现半分的浮点残渣（与墨单原实现同一条）。
     * <p>🆕 原实现（收口 F3）：删空 + 点「确定」= **清除**该行手动价 ⇒ 这里回 {@link #R_CLEAR}，
     * 由宿主执行 `MenuPrice.set(..., -1)`。
     */
    int press(int key) {
        if (!open) return R_CANCEL;
        if (key >= 0 && key <= 9) {
            if (text.length() < 8) {
                if (text.indexOf('.') < 0 && text.length() >= 4) return R_EDIT;   // 上限 ¥9999
                text = text + key;
            }
            return R_EDIT;
        }
        if (key == PK_DOT) {
            if (text.length() == 0) text = "0";
            if (text.indexOf('.') < 0 && text.length() < 6) text = text + ".";
            return R_EDIT;
        }
        if (key == PK_DEL) {
            if (text.length() > 0) text = text.substring(0, text.length() - 1);
            return R_EDIT;
        }
        if (key == PK_CANCEL) return R_CANCEL;
        if (key == PK_OK) {
            if (text.length() == 0) return R_CLEAR;      // 空输入 + 确定 = 清除
            try {
                double yuan = Double.parseDouble(text);
                int f = (int) Math.round(yuan * 100.0);
                if (f < 0) f = 0;
                fen = f;
                return R_COMMIT;
            } catch (Throwable ignored) {
                return R_CANCEL;                          // 解析不出来 ⇒ 等同取消（原实现也走 closePad）
            }
        }
        return R_EDIT;
    }

    /**
     * 画键盘（**宿主最后画**，压在内容之上）。
     *
     * @param headText 标题行左端的固定标题（宿主读 `R.string.menu_price_manual_title`）
     * @param bookTitle 标题行下方的书名（已由本类按面板宽截断）
     */
    void draw(Canvas c, float w, float vh, float unit, Paint p, String headText, String bookTitle) {
        if (!open) return;
        RectF panel = panel(w, vh, unit);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawRect(panel, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(0xFF000000);
        c.drawRect(panel.left + 0.75f, panel.top + 0.75f, panel.right - 0.75f, panel.bottom - 0.75f, p);
        p.setStyle(Paint.Style.FILL);

        // 标题行：固定标题 + 正在输入的值
        p.setTypeface(InkTheme.serif());
        p.setFakeBoldText(true);
        p.setTextSize(SZ_TITLE * unit);
        p.setColor(0xFF000000);
        p.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm = p.getFontMetrics();
        float ty = panel.top + TITLE_H_UNITS * unit * 0.52f;
        c.drawText(headText == null ? "" : headText, panel.left + unit * 10f,
                ty - (fm.ascent + fm.descent) / 2f, p);
        p.setFakeBoldText(false);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextAlign(Paint.Align.RIGHT);
        String shown = (text.length() == 0) ? "—" : ("¥" + text);
        c.drawText(shown, panel.right - unit * 10f, ty - (fm.ascent + fm.descent) / 2f, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(InkTheme.serif());
        p.setTextSize(SZ_BOOK * unit);
        p.setColor(GRAY);
        c.drawText(BillSection.fit(p, bookTitle == null ? "" : bookTitle, panel.width() - unit * 20f),
                panel.left + unit * 10f, ty + unit * 15f, p);

        // 键位
        int[] keys = { 1, 2, 3, 4, 5, 6, 7, 8, 9, PK_DOT, 0, PK_DEL, PK_CANCEL, PK_OK };
        String[] labs = { "1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "←", "取消", "确定" };
        for (int i = 0; i < keys.length; i++) {
            RectF r = keyRect(w, vh, unit, keys[i]);
            boolean primary = (keys[i] == PK_OK);
            p.setStyle(Paint.Style.FILL);
            p.setColor(primary ? 0xFF000000 : 0xFFFFFFFF);
            c.drawRect(r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(0xFF000000);
            c.drawRect(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, p);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.MONOSPACE);
            p.setFakeBoldText(primary);
            p.setColor(primary ? 0xFFFFFFFF : 0xFF000000);
            float sz = (keys[i] == PK_OK || keys[i] == PK_CANCEL) ? 16f * unit : 20f * unit;
            p.setTextSize(sz);
            p.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics kf = p.getFontMetrics();
            c.drawText(labs[i], r.centerX(), r.centerY() - (kf.ascent + kf.descent) / 2f, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(false);
        p.setTypeface(null);
    }

    /** 分 ⇒ "元" 文本（去尾零）。 */
    private static String fmt(int fen) {
        return String.format(Locale.US, "%.2f", fen / 100.0).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
