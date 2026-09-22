package com.inkread.weekread;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.OutputStream;

/**
 * 「本记」导出图片（v0.4.0 起；v0.4.4 改成**一图到底的长图**）。
 *
 * ── v0.4.4 改了什么 ──
 * 旧版固定画成 480×800 一张（和屏幕等大），一条长划线会被截断。现在：
 *   ① **高度按内容算** —— 正文多少行，图就多高（上限 {@link WeekCardView#EXPORT_H_MAX}）；
 *   ② 正文改**两段式** —— 原文 + 想法（和屏幕上一致，含「想法」小标）；
 *   ③ 宽度仍是 480（与屏幕等比，文字锐利、体积小）。
 *
 * 纸张模板（素白 / 手账花框 / 卡纸）× 字号三档（小/中/大）× 署名 / 落款开关
 * 全部**保留**（设置在设置页「自定义」页），只是边框与画布跟着图高自适应。
 *
 * 保存走 MediaStore（targetSdk 30 分区存储的正规姿势）→ `Pictures/微读墨记/`，
 * 存完直接呼起系统分享面板。全程纯 Canvas，无第三方依赖。
 */
public final class NoteExport {

    private NoteExport() {
    }

    /** 导出图宽度 —— 与屏幕同宽（口径唯一来源在 {@link WeekCardView#EXPORT_W}），等比最稳 */
    private static final int W = WeekCardView.EXPORT_W;
    /** 左右留白 */
    private static final float PAD_X = 56f;
    /** 正文字号三档：小 / 中 / 大（导出图专用，与屏幕上那套"号"无关） */
    private static final float[] TIERS = {26f, 32f, 40f};
    /** 想法段的小标与屏幕保持同一个词 */
    private static final String IDEA_TAG = "想法";
    private static final String FOOT = "微读墨记 · 本记";

    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF555555;
    private static final int LIGHT = 0xFFAAAAAA;

    // ── 模板偏好 ──

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("notes", Context.MODE_PRIVATE);
    }

    /** 纸张模板：0 素白 / 1 手账花框 / 2 卡纸 */
    public static int paper(Context c) {
        return prefs(c).getInt("paper", 1);
    }

    public static void setPaper(Context c, int v) {
        prefs(c).edit().putInt("paper", v).commit();
    }

    /** 正文字号档：0 小 / 1 中 / 2 大 */
    public static int sizeTier(Context c) {
        return prefs(c).getInt("sizeTier", 1);
    }

    public static void setSizeTier(Context c, int v) {
        prefs(c).edit().putInt("sizeTier", v).commit();
    }

    /** 是否画署名（书名 + 作者） */
    public static boolean showSign(Context c) {
        return prefs(c).getBoolean("showSign", true);
    }

    public static void setShowSign(Context c, boolean v) {
        prefs(c).edit().putBoolean("showSign", v).commit();
    }

    /** 是否画落款日期（划线日期） */
    public static boolean showDate(Context c) {
        return prefs(c).getBoolean("showDate", true);
    }

    public static void setShowDate(Context c, boolean v) {
        prefs(c).edit().putBoolean("showDate", v).commit();
    }

    // ── 导出 ──

    /**
     * 排版结果（先算高、再画 —— 长图必须两遍走）。
     *
     * 所有纵向基线都在这里定好，{@link #draw} 只负责照着画，
     * 避免"边画边算"导致算出来的高度和实际画的不一致。
     */
    private static final class Layout {
        int height;
        float textSize, tagSize, lineH, tagBlock;
        float tx, textW, top;
        float lineY, signBase, metaBase, footBase;
        String[] quote = new String[0];
        String[] idea = new String[0];
    }

    /**
     * 排版：把这条内容（原文 + 想法）摊平算高度。
     *
     * 折行用 {@link WeekCardView#wrapAll} —— 和屏幕上**同一套**逐字折行，
     * 所以长图里每一行的断点与手机上看到的完全一致。
     */
    private static Layout layout(Context c, NoteStats n) {
        Layout L = new Layout();

        int tier = sizeTier(c);
        if (tier < 0) tier = 0;
        if (tier > 2) tier = 2;
        L.textSize = TIERS[tier];
        L.lineH = L.textSize * 1.75f;
        L.tagSize = L.textSize * 0.62f;
        L.tagBlock = L.tagSize * 2.6f;

        L.top = (paper(c) == 0) ? 70f : 92f;

        // 大引号的实际宽度 —— 正文起点要给它让位（与屏幕同一个算法：×0.7 的重叠量）
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(android.graphics.Typeface.SERIF);
        p.setTextSize(56f);
        float quoteW = p.measureText("\u201c");

        String q = (n.markText == null) ? "" : n.markText.trim();
        String idea = (n.ideaText == null) ? "" : n.ideaText.trim();
        boolean hasQ = q.length() > 0;
        boolean hasI = idea.length() > 0;

        // 没有原文（整本书评 / 章节点评）就不留引号位，正文从左留白起排
        L.tx = hasQ ? PAD_X + quoteW * 0.7f : PAD_X;
        L.textW = W - L.tx - PAD_X;
        L.quote = hasQ ? WeekCardView.wrapAll(q, L.textW, L.textSize) : new String[0];
        L.idea = hasI ? WeekCardView.wrapAll(idea, L.textW, L.textSize) : new String[0];

        float bodyH = L.quote.length * L.lineH;
        if (L.idea.length > 0) {
            if (L.quote.length > 0) bodyH += L.tagBlock;
            bodyH += L.idea.length * L.lineH;
        }
        if (bodyH < L.textSize) bodyH = L.textSize;          // 至少给一行的高度

        float bodyBottom = L.top + bodyH;
        L.lineY = bodyBottom + L.textSize * 1.05f;           // 分隔细线
        L.signBase = L.lineY + L.textSize * 1.15f;           // 署名基线
        L.metaBase = L.signBase + L.textSize * 0.95f;        // 落款基线
        L.footBase = L.metaBase + L.textSize * 1.05f;        // 水印基线

        L.height = Math.round(L.footBase + L.textSize * 0.85f);
        if (L.height < 300) L.height = 300;
        if (L.height > WeekCardView.EXPORT_H_MAX) L.height = WeekCardView.EXPORT_H_MAX;
        return L;
    }

    /**
     * 画图并存进相册，成功后呼起分享面板。返回 null = 成功；否则是给 toast 的错误文案。
     */
    public static String exportAndShare(Context c, NoteStats n) {
        String q = (n == null || n.markText == null) ? "" : n.markText.trim();
        String idea = (n == null || n.ideaText == null) ? "" : n.ideaText.trim();
        if (q.length() == 0 && idea.length() == 0) return "没有可导出的内容";

        Layout L = layout(c, n);
        Bitmap bmp = Bitmap.createBitmap(W, L.height, Bitmap.Config.RGB_565);
        draw(c, new Canvas(bmp), n, L);

        Uri uri = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "benji_" + System.currentTimeMillis() + ".png");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/微读墨记");
            uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                OutputStream os = c.getContentResolver().openOutputStream(uri);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.flush();
                os.close();
            }
        } catch (Throwable t) {
            uri = null;
        }
        if (uri == null) {
            // 兜底：写进应用自己的外部目录（不用任何权限），至少别让用户白等
            try {
                java.io.File dir = new java.io.File(c.getExternalFilesDir(null), "export");
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, "benji_" + System.currentTimeMillis() + ".png");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.flush();
                fo.close();
                Toast.makeText(c, "已保存到应用目录：" + f.getPath(), Toast.LENGTH_LONG).show();
                return null;
            } catch (Throwable t2) {
                return "保存失败：" + t2.getClass().getSimpleName();
            }
        }

        // 告诉用户成品多大 —— 长图的高度是内容决定的，这个数字本身有信息量
        Toast.makeText(c, "已导出 " + W + "×" + L.height + " 的图片", Toast.LENGTH_SHORT).show();

        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(Intent.createChooser(share, "分享本记"));
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ── 绘制 ──

    private static void draw(Context c, Canvas cv, NoteStats n, Layout L) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int paper = paper(c);

        // 底色
        cv.drawColor(paper == 2 ? 0xFFF7F3E8 : 0xFFFFFFFF);

        // 纸张边框（跟着图高自适应）
        if (paper == 1) {
            drawJournalFrame(cv, p, L.height);
        } else if (paper == 2) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            p.setColor(INK);
            cv.drawRect(26, 26, W - 26, L.height - 26, p);
            p.setStrokeWidth(1f);
            cv.drawRect(34, 34, W - 34, L.height - 34, p);
        }

        // ── 大引号（只在有原文时画 —— 独立想法没有"引文"可引）──
        if (L.quote.length > 0) {
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(android.graphics.Typeface.SERIF);
            p.setColor(LIGHT);
            p.setTextSize(56f);
            cv.drawText("\u201c", PAD_X, L.top + 50f, p);
        }

        // ── 原文段 ──
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(android.graphics.Typeface.SERIF);
        p.setColor(INK);
        p.setTextSize(L.textSize);
        float y = L.top + L.textSize;
        for (int i = 0; i < L.quote.length; i++) {
            cv.drawText(L.quote[i], L.tx, y, p);
            y += L.lineH;
        }

        // ── 想法段（小标 + 正文）──
        if (L.idea.length > 0) {
            float segEnd = L.top + L.quote.length * L.lineH;
            p.setTypeface(android.graphics.Typeface.DEFAULT);
            p.setColor(GRAY);
            p.setTextSize(L.tagSize);
            cv.drawText(IDEA_TAG, L.tx, segEnd + L.tagSize * 1.6f, p);
            p.setTypeface(android.graphics.Typeface.SERIF);
            p.setColor(INK);
            p.setTextSize(L.textSize);
            float iy = segEnd + L.tagBlock + L.textSize;
            for (int i = 0; i < L.idea.length; i++) {
                cv.drawText(L.idea[i], L.tx, iy, p);
                iy += L.lineH;
            }
        }

        // ── 分隔细线 ──
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f);
        p.setColor(0xFFBBBBBB);
        cv.drawLine(PAD_X, L.lineY, W - PAD_X, L.lineY, p);
        p.setStyle(Paint.Style.FILL);

        // ── 署名 ──
        float avail = W - PAD_X * 2;
        if (showSign(c)) {
            p.setTypeface(android.graphics.Typeface.DEFAULT);
            p.setFakeBoldText(true);
            p.setColor(INK);
            p.setTextSize(26f);
            String title = (n.title == null || n.title.length() == 0) ? "(未命名)" : n.title;
            String author = (n.author == null) ? "" : n.author.trim();
            String sign = author.length() > 0 ? title + " · " + author : title;
            if (p.measureText(sign) > avail) sign = ellip(p, sign, avail);
            cv.drawText(sign, PAD_X, L.signBase, p);
            p.setFakeBoldText(false);
        }

        // ── 落款：章节 · 日期 ──
        if (showDate(c)) {
            p.setTypeface(android.graphics.Typeface.SERIF);
            p.setColor(GRAY);
            p.setTextSize(20f);
            String ch = (n.chapterTitle == null || n.chapterTitle.length() == 0) ? "" : n.chapterTitle;
            String date = n.dateText();
            String meta = ch + (date.length() > 0 ? (ch.length() > 0 ? " · " : "") + date : "");
            if (n.hasIdea()) meta = meta + (meta.length() > 0 ? " · " : "") + IDEA_TAG;
            if (meta.length() > 0) {
                if (p.measureText(meta) > avail) meta = ellip(p, meta, avail);
                cv.drawText(meta, PAD_X, L.metaBase, p);
            }
        }

        // ── 水印落款 ──
        p.setTypeface(android.graphics.Typeface.DEFAULT);
        p.setColor(0xFF999999);
        p.setTextSize(18f);
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText(FOOT, W / 2f, L.footBase, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 手账风花框：外圆角框 + 内细框 + 四角小圆点（高度随内容） */
    private static void drawJournalFrame(Canvas cv, Paint p, int H) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(INK);
        p.setStrokeWidth(2.5f);
        cv.drawRoundRect(new RectF(18, 18, W - 18, H - 18), 26, 26, p);
        p.setStrokeWidth(1f);
        cv.drawRoundRect(new RectF(28, 28, W - 28, H - 28), 20, 20, p);
        p.setStyle(Paint.Style.FILL);
        float[] dots = {28, 28, W - 28, 28, 28, H - 28, W - 28, H - 28};
        for (int i = 0; i < dots.length; i += 2) {
            cv.drawCircle(dots[i], dots[i + 1], 4f, p);
        }
    }

    private static String ellip(Paint p, String text, float maxW) {
        if (p.measureText(text) <= maxW) return text;
        float tail = p.measureText("…");
        int n = text.length();
        while (n > 1 && p.measureText(text.substring(0, n)) + tail > maxW) n--;
        return text.substring(0, n) + "…";
    }
}
