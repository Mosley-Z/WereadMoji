package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.DisplayMetrics;

import com.inkread.weekread.core.Bill;
import com.inkread.weekread.core.BillStore;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.MenuPrefs;
import com.inkread.weekread.core.PeriodRange;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 🆕 TASK-079：把一期**阅读账单**渲染成一张「账单海报」PNG（定稿设计 §5.4）。
 *
 * <h3>范式（照 {@link NoteExport} 的 C2 纯后台阶段）</h3>
 * <pre>
 *   纯 Canvas → Bitmap.createBitmap(W,H,RGB_565) → compress(PNG,100) → 落 App 私有目录
 * </pre>
 * 🔴 <b>全程不碰 UI</b>（调用方丢到工作线程）；🔴 **渲染期零网络** —— 只读
 * {@link BillStore} 落盘的快照 + {@link MenuPrefs} 的显示配置。
 *
 * <h3>产出位置</h3>
 * {@code files/wallbill/bill_<mode>_<periodStart>.png} —— <b>按「周期」定名</b> ⇒
 * 同一期重复生成**覆盖同一文件** ⇒ 交给 {@code WallpaperPrefs.add} 时（文件名取
 * sha1(绝对路径)）**天然去重**，不会把图片池撑大。
 *
 * <h3>画面</h3>
 * 纯白底 + 双线票据框 + 抬头（标题 / 单号）+ 副行（周结 · 区间 · N 本 · 时长）
 * + 表头 + 逐本（NO.xx 书名 ¥价 / 作者 · 时长 / 摘录）+ 整单备注 + 账单合计 + 落款。
 * 🔴 纯黑白 + 三档灰，无彩色 / 无渐变 / 无阴影（墨水屏铁律）。
 *
 * <h3>🔴 已知上限（如实登记，不假装没有）</h3>
 * 屏幕上放不下的部分（书目多且摘录长时）**按整块截断**并在末尾画一行
 * {@code 余 n 本未列出} —— 而不是把内容挤成看不清的小字。
 */
public final class BillWallpaper {

    private BillWallpaper() {
    }

    // 基准画布：真实屏尺寸优先，取不到就退回 480×800（S4 的实际规格，见 docs/01）
    private static final int BASE_W = 480;
    private static final int BASE_H = 800;

    private static final int INK   = 0xFF000000;
    private static final int GRAY  = 0xFF3C3C3C;
    private static final int LIGHT = 0xFFA8A8A8;
    private static final int LINE  = 0xFFD8D8D8;

    /** 落盘目录名（在 App 私有 {@code files/} 下）。 */
    private static final String DIR = "wallbill";

    /**
     * 生成结果。{@code path} 非空 = 成功（已落盘）；否则看 {@code err}。
     */
    public static final class Result {
        public String path;
        public String err;
        public int width;
        public int height;
        public int itemDrawn;    // 实际画进去的书目数（供验证记录留痕）
        public int itemTotal;
    }

    /**
     * 🔴 **纯后台**：算版面 + 分配位图 + 绘制 + PNG 压缩落盘。**不碰任何 UI**。
     *
     * @param mode        {@link PeriodRange#WEEKLY} / {@link PeriodRange#MONTHLY}
     * @param periodStart 该期起点（秒）
     * @return 结果对象（内部已回收位图）
     */
    public static Result generate(Context c, String mode, long periodStart) {
        Result r = new Result();
        if (c == null || periodStart <= 0) {
            r.err = "参数不合法";
            return r;
        }
        final String m = PeriodRange.MONTHLY.equals(mode) ? PeriodRange.MONTHLY : PeriodRange.WEEKLY;

        Bill b = BillStore.load(c, m, periodStart);
        if (b == null) {
            r.err = "这一期账单读不出来";
            return r;
        }

        int[] size = screenSize(c);
        final int w = size[0], h = size[1];
        r.width = w;
        r.height = h;

        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas cv = new Canvas(bmp);
            draw(ctx(c), cv, w, h, b, r);

            File dir = new File(c.getFilesDir(), DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                r.err = "建目录失败";
                return r;
            }
            File f = new File(dir, "bill_" + m + "_" + periodStart + ".png");
            FileOutputStream fo = null;
            try {
                fo = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.flush();
                r.path = f.getAbsolutePath();
            } finally {
                try { if (fo != null) fo.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            r.err = "渲染失败：" + t.getClass().getSimpleName();
            CardDebug.note(c, "wallbill: " + r.err);
        } finally {
            try { if (bmp != null) bmp.recycle(); } catch (Throwable ignored) { }
        }
        return r;
    }

    private static Context ctx(Context c) {
        return c.getApplicationContext();
    }

    /** 真实屏尺寸（取不到就退回 480×800）；同时把过小的值钳掉（防 0 宽位图）。 */
    private static int[] screenSize(Context c) {
        int w = BASE_W, h = BASE_H;
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            if (dm != null) {
                if (dm.widthPixels > 0) w = dm.widthPixels;
                if (dm.heightPixels > 0) h = dm.heightPixels;
            }
        } catch (Throwable ignored) {
        }
        if (w < 240) w = BASE_W;
        if (h < 320) h = BASE_H;
        return new int[] { w, h };
    }

    // ══════════════════════ 绘制 ══════════════════════

    private static void draw(Context c, Canvas cv, int W, int H, Bill b, Result r) {
        // 基础字号 = 屏高 / 800（S4 ⇒ 1.0）；全部尺跟着它走 ⇒ 换分辨率不失衡
        final float s = H / 800f;
        final float sTitle  = 30f * s;
        final float sSerial = 18f * s;
        final float sSub    = 20f * s;
        final float sBody   = 22f * s;
        final float sMeta   = 16f * s;
        final float sSmall  = 15f * s;
        final float pad     = 34f * s;
        final float lineH   = 1.55f;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.LEFT);

        // ① 纯白底 + 双线票据框
        cv.drawColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(INK);
        p.setStrokeWidth(3f * s);
        cv.drawRect(14f * s, 14f * s, W - 14f * s, H - 14f * s, p);
        p.setStrokeWidth(1f * s);
        p.setColor(LINE);
        cv.drawRect(22f * s, 22f * s, W - 22f * s, H - 22f * s, p);
        p.setStyle(Paint.Style.FILL);

        final float left = pad;
        final float right = W - pad;
        final float avail = right - left;

        String title  = nz(MenuPrefs.title(c));
        if (title.length() == 0) title = "墨单";
        String serial = Bill.serialOf(b.mode, b.periodStart);
        String unitPref = MenuPrefs.unit(c);

        float y = 22f * s + pad;

        // ② 抬头：标题（左，衬线加粗） + 单号（右，等宽）
        p.setTypeface(Typeface.SERIF);
        p.setFakeBoldText(true);
        p.setColor(INK);
        p.setTextSize(sTitle);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText(fit(p, title, avail * 0.55f), left, y + sTitle, p);
        p.setFakeBoldText(false);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(sSerial);
        p.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(serial, right, y + sTitle * 0.78f, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(null);
        y += sTitle * 1.36f;

        // ③ 副行：周结 · 区间 · N 本 · 时长
        p.setTypeface(Typeface.MONOSPACE);
        p.setColor(GRAY);
        p.setTextSize(sSub);
        String sub = Bill.kindLabel(b.mode) + " · " + Bill.rangeLabel(b.mode, b.periodStart)
                + " · " + b.bookCount() + " 本"
                + (b.placeholder ? "" : (" · " + dur(b.totalSec, unitPref)));
        cv.drawText(fit(p, sub, avail), left, y + sSub, p);
        y += sSub * lineH;

        y = sep(cv, p, left, right, y, s);

        // ④ 占位账（Q10）：只写一句，绝不产 0 值海报
        if (b.placeholder) {
            p.setTypeface(Typeface.SERIF);
            p.setColor(GRAY);
            p.setTextSize(sBody);
            p.setTextAlign(Paint.Align.CENTER);
            cv.drawText("本期无阅读记录", (left + right) / 2f, y + sBody * 3f, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(null);
            footer(cv, p, W, H, s, b);
            return;
        }

        // ⑤ 表头（沿用菜单隐喻：品类 │ 主厨 │ 价格）+ 分隔线
        p.setTypeface(Typeface.MONOSPACE);
        p.setColor(GRAY);
        p.setTextSize(sMeta);
        cv.drawText("品类", left, y + sMeta, p);
        p.setTextAlign(Paint.Align.RIGHT);
        cv.drawText("价格", right, y + sMeta, p);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText("主厨", left + avail * 0.55f, y + sMeta, p);
        y += sMeta * lineH;
        y = sep(cv, p, left, right, y, s);

        // ⑥ 底部保留区（分隔线 + 备注 + 合计 + 落款），先算出来 ⇒ 书目区不会压住它
        String wholeNote = nz(MenuPrefs.footerNote(c));
        float tailH = 0f;
        if (wholeNote.length() > 0) tailH += sSmall * lineH;
        tailH += sBody * lineH;                     // 账单合计
        tailH += sSmall * lineH * 2f;               // 落款 + 水印
        tailH += s * 30f;
        final float limit = H - 22f * s - pad - tailH;

        // ⑦ 逐本：**先备料（含折行）再判放得下** —— 避免"画到一半才发现超界"
        r.itemTotal = b.items.size();
        final boolean showAuthor = MenuPrefs.showAuthor(c);
        final boolean showDuration = MenuPrefs.showDuration(c);
        int drawn = 0;
        for (int i = 0; i < b.items.size(); i++) {
            Bill.Item it = b.items.get(i);

            // 摘录：每条最多折 2 行（再长就截断加省略号）
            List<String> exLines = new ArrayList<String>();
            List<String> ex = excerptLines(c, it);
            if (!ex.isEmpty()) {
                p.setTypeface(Typeface.SERIF);
                p.setTextSize(sSmall);
                for (int k = 0; k < ex.size(); k++) {
                    exLines.addAll(wrap(p, ex.get(k), avail - s * 12f, 2));
                }
                p.setTypeface(null);
            }

            // 作者 · 时长（有才画 —— 不再"宽进严出"地白占一行）
            StringBuilder meta = new StringBuilder();
            if (showAuthor && nz(it.author).length() > 0) meta.append(it.author);
            if (showDuration) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(dur(it.readTimeSec, unitPref));
            }

            float need = sBody * lineH;
            if (meta.length() > 0) need += sMeta * lineH;
            need += exLines.size() * sSmall * lineH;
            if (y + need > limit) break;

            if (i > 0) y += s * 8f;                 // 条目之间一口气

            // NO.xx 书名 ……… ¥价
            p.setTypeface(Typeface.MONOSPACE);
            p.setFakeBoldText(true);
            p.setColor(INK);
            p.setTextSize(sBody);
            String no = String.format(java.util.Locale.US, "NO.%02d", i + 1);
            String price = "¥" + it.price;
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(price, right, y + sBody, p);
            float pw = p.measureText(price);
            p.setTextAlign(Paint.Align.LEFT);
            cv.drawText(fit(p, no + "  " + nz(it.title), avail - pw - s * 10f), left, y + sBody, p);
            p.setFakeBoldText(false);
            y += sBody * lineH;

            // 作者 · 时长
            if (meta.length() > 0) {
                p.setTypeface(Typeface.MONOSPACE);
                p.setColor(GRAY);
                p.setTextSize(sMeta);
                cv.drawText(fit(p, meta.toString(), avail), left, y + sMeta, p);
                y += sMeta * lineH;
            }

            // 摘录（🔴 本人划线**不加前缀**；公开热门必须标「热门：」，TASK-077/077b 口径）
            if (!exLines.isEmpty()) {
                p.setTypeface(Typeface.SERIF);
                p.setColor(GRAY);
                p.setTextSize(sSmall);
                for (int k = 0; k < exLines.size(); k++) {
                    cv.drawText(exLines.get(k), left + s * 12f, y + sSmall, p);
                    y += sSmall * lineH;
                }
                p.setTypeface(null);
            }
            drawn++;
        }
        r.itemDrawn = drawn;

        // 放不下的部分：如实说明，不硬挤
        if (drawn < b.items.size()) {
            p.setTypeface(Typeface.MONOSPACE);
            p.setColor(LIGHT);
            p.setTextSize(sSmall);
            cv.drawText("余 " + (b.items.size() - drawn) + " 本未列出（海报高度有限）",
                    left, y + sSmall, p);
            p.setTypeface(null);
            y += sSmall * lineH;
        }

        // ⑧ 票据尾：紧接书目（分隔线 / 备注 / 合计），落款另钉在页底
        float ty = y + s * 10f;
        ty = sep(cv, p, left, right, ty, s);
        if (wholeNote.length() > 0) {
            p.setTypeface(Typeface.MONOSPACE);
            p.setColor(GRAY);
            p.setTextSize(sSmall);
            List<String> nl = wrap(p, "整单备注：" + wholeNote, avail, 2);
            for (int i = 0; i < nl.size(); i++) {
                cv.drawText(fit(p, nl.get(i), avail), left, ty + sSmall, p);
                ty += sSmall * lineH;
            }
            p.setTypeface(null);
        }
        p.setTypeface(Typeface.MONOSPACE);
        p.setFakeBoldText(true);
        p.setColor(INK);
        p.setTextSize(sBody);
        cv.drawText("账单合计：¥" + b.totalPrice(), left, ty + sBody, p);
        p.setFakeBoldText(false);
        p.setTypeface(null);

        footer(cv, p, W, H, s, b);
    }

    /** 落款：`微读墨记 · 周结 2026-W40`（居中）+ 一行小字如实说明。 */
    private static void footer(Canvas cv, Paint p, int W, int H, float s, Bill b) {
        float base = H - 22f * s - 34f * s;
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.SERIF);
        p.setFakeBoldText(true);
        p.setColor(INK);
        p.setTextSize(18f * s);
        cv.drawText("微读墨记 · " + Bill.kindLabel(b.mode) + " " + Bill.serialOf(b.mode, b.periodStart),
                W / 2f, base, p);
        p.setFakeBoldText(false);
        p.setTypeface(Typeface.MONOSPACE);
        p.setColor(LIGHT);
        p.setTextSize(13f * s);
        cv.drawText("由「壁纸管家」生成 · 仅在本机使用", W / 2f, base + 22f * s, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(null);
    }

    /** 画一条 1px 分隔线并推进 y（返回新的 y）。 */
    private static float sep(Canvas cv, Paint p, float left, float right, float y, float s) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(LINE);
        cv.drawRect(left, y + s * 6f, right, y + s * 6f + 1f, p);
        return y + s * 16f;
    }

    // ══════════════════════ 摘录（与 BillSection 同一口径） ══════════════════════

    /**
     * 「摘录策略」4 档 → 要画的文本行。
     * 🔴 **个人划线不加前缀**（直接给原文）；**公开热门必须标「热门：」** —— 绝不让用户以为是自己划的。
     */
    private static List<String> excerptLines(Context c, Bill.Item it) {
        List<String> out = new ArrayList<String>();
        String mode = MenuPrefs.excerptMode(c);
        String mine = nz(it.mineText);
        String hot = nz(it.hotText);
        if (MenuPrefs.EX_MINE.equals(mode)) {
            if (mine.length() > 0) out.add(mine);
        } else if (MenuPrefs.EX_HOT_ONLY.equals(mode)) {
            if (hot.length() > 0) out.add("热门：" + hot);
        } else if (MenuPrefs.EX_HOT_FILL.equals(mode)) {
            if (mine.length() > 0) out.add(mine);
            else if (hot.length() > 0) out.add("热门：" + hot);
        } else {   // EX_ANY：个人优先，其后热门
            if (mine.length() > 0) out.add(mine);
            if (hot.length() > 0) out.add("热门：" + hot);
        }
        return out;
    }

    // ══════════════════════ 文本工具 ══════════════════════

    /** 时长文案：小时档 `3h39m` / 分钟档 `219m`（与墨台账单**同一个口径**）。 */
    private static String dur(int sec, String unit) {
        if (sec <= 0) return MenuPrefs.UNIT_M.equals(unit) ? "0m" : "0h00m";
        if (MenuPrefs.UNIT_M.equals(unit)) return (sec / 60) + "m";
        int h = sec / 3600, m = (sec % 3600) / 60;
        return h + "h" + (m < 10 ? "0" + m : String.valueOf(m)) + "m";
    }

    /** 折行（按实测宽度；最多 {@code maxLines} 行，末行截断加省略号）。 */
    private static List<String> wrap(Paint p, String sIn, float maxW, int maxLines) {
        List<String> out = new ArrayList<String>();
        String s = nz(sIn).replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() == 0 || maxW <= 0f) return out;
        float[] mw = new float[1];
        int guard = 0;
        while (s.length() > 0 && guard++ < 16) {
            if (maxLines > 0 && out.size() >= maxLines - 1) { out.add(fit(p, s, maxW)); return out; }
            int n = p.breakText(s, true, maxW, mw);
            if (n <= 0) n = 1;
            if (n >= s.length()) { out.add(s); return out; }
            out.add(s.substring(0, n).trim());
            s = s.substring(n).trim();
        }
        if (s.length() > 0) out.add(fit(p, s, maxW));
        return out;
    }

    /** 按实测宽度截断 + 省略号。 */
    private static String fit(Paint p, String s, float maxW) {
        if (s == null) return "";
        if (maxW <= 0f) return "";
        if (p.measureText(s) <= maxW) return s;
        for (int i = s.length() - 1; i > 0; i--) {
            String t = s.substring(0, i) + "…";
            if (p.measureText(t) <= maxW) return t;
        }
        return "…";
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
