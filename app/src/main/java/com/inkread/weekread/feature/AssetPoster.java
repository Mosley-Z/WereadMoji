package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.inkread.weekread.core.MenuPrefs;
import com.inkread.weekread.ui.InkTheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 🆕 TASK-087-A2（用户诉求 ①）：把**阅读资产**整表渲染成一张「资产海报」位图 —— 供墨台内
 * **预览 + 保存 / 分享**（复用 {@link BillWallpaper} 那套预览态与落盘链路）。
 *
 * <h3>范式（照 {@link BillWallpaper}）</h3>
 * <pre>
 *   纯 Canvas → Bitmap.createBitmap(W,H,RGB_565) → （预览留内存 / 保存时 compress(PNG,100)）
 * </pre>
 * 🔴 <b>全程不碰 UI</b>（调用方丢到工作线程）；🔴 <b>渲染期零网络、零落盘</b> —— 只读
 * {@link AssetSection#snapshot} 从本地缓存（{@code AssetStore} / {@code NoteStore} /
 * {@code BillStore} / {@code StatsStore}）合成的同一份条目。
 *
 * <h3>🔴 与屏幕同源（A11 口径）</h3>
 * 条目集合 = {@link AssetSection#snapshot}（**同一份**筛选 + 价格链 + 收入算法 + 合计读数）；
 * 排序 = {@link AssetSection#sorter}（与屏上同一个排序键，由调用方传入）；文本 = 共享
 * {@link AssetSection#priceText} / {@link AssetSection#incomeText} / {@link AssetSection#totalMain}
 * / {@link AssetSection#totalNote} / {@link AssetSection#excerptOf} ⇒ **本类一个价格/收入数字都不自己算**。
 *
 * <h3>尺寸与版面</h3>
 * 画布 / 双线框 / 边距 / 字号 / 行距 / 价格列宽**逐值照 {@link BillWallpaper}**（同屏尺寸、
 * {@code s = H / 800f}、3px INK 框 + 1px LINE 框、{@code pad = 34f * s}、
 * {@code SZ_TITLE / SZ_SERIAL / SZ_BODY / SZ_SMALL / LH / PRICE_COL_W}）；区别只有三处：
 * <ul>
 *   <li>抬头右侧槽位画<b>「阅读资产」</b>（墨单画单号），副行画 {@code 阅读资产 · N 本 · 时长}；</li>
 *   <li>尾部合计是<b>一行三列</b>（左「合计 N 本 · HhMm」/ 中「原价合计」/ 右「收入合计」）；</li>
 *   <li>放不下时只画 {@code … 还有 N 本}（**不画**「展开 / 收起」—— 那是屏上交互，纸上无意义）。</li>
 * </ul>
 * 🔴 纯黑白 + 三档灰，无彩色 / 无渐变 / 无阴影（墨水屏铁律）。
 */
public final class AssetPoster {

    private AssetPoster() {
    }

    private static final int INK   = 0xFF000000;
    private static final int GRAY  = 0xFF3C3C3C;
    private static final int LIGHT = InkTheme.INK2;
    private static final int LINE  = 0xFFD8D8D8;

    /** 渲染结果。{@code bmp} 非空 = 成功（**调用方负责 recycle**）；否则看 {@code err}。 */
    public static final class Poster {
        public Bitmap bmp;
        public String err;
        public int width;
        public int height;
        /** 实际画进去的书目数（供验证记录留痕）。 */
        public int itemDrawn;
        /** 过滤后总本数。 */
        public int itemTotal;
    }

    /**
     * 🔴 **纯后台**：读同一份资产快照 → 算版面 → 分配位图 → 绘制。**不碰 UI、不落盘**。
     *
     * @param sortKey 与屏上同一个排序键（{@link AssetSection#SORT_TIME} / {@code SORT_PRICE} /
     *                {@code SORT_INCOME}）—— 由墨台把当前分区状态传进来，保证「所见即所印」。
     * @param view    {@code MenuPrefs.VIEW_EXCERPT}（摘录皮肤：带价格列 + 摘录行）/
     *                {@code VIEW_READING}（读书皮肤：书名折行、无价格列）。
     */
    public static Poster render(Context c, int sortKey, int view) {
        Poster r = new Poster();
        if (c == null) {
            r.err = "参数不合法";
            return r;
        }
        final int v = (view == MenuPrefs.VIEW_READING) ? MenuPrefs.VIEW_READING : MenuPrefs.VIEW_EXCERPT;

        AssetSection.Snapshot snap;
        try {
            snap = AssetSection.snapshot(c);
        } catch (Throwable t) {
            r.err = "阅读资产读不出来";
            return r;
        }
        if (snap == null) {
            r.err = "阅读资产读不出来";
            return r;
        }

        // 🔴 排序与屏上同源（同一个 `sorter`、同一个排序键）
        List<AssetSection.Book> order = new ArrayList<AssetSection.Book>(snap.items);
        Collections.sort(order, AssetSection.sorter(sortKey));

        int[] size = BillWallpaper.screenSize(c);
        r.width = size[0];
        r.height = size[1];
        r.itemTotal = order.size();

        try {
            r.bmp = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.RGB_565);
            draw(ctx(c), new Canvas(r.bmp), size[0], size[1], snap, order, v, r);
        } catch (Throwable t) {
            if (r.bmp != null) {
                try { r.bmp.recycle(); } catch (Throwable ignored) { }
                r.bmp = null;
            }
            r.err = "渲染失败：" + t.getClass().getSimpleName();
        }
        return r;
    }

    /** 渲染用的 Context：优先带主题的应用上下文（与 {@link BillWallpaper} 同一处理）。 */
    private static Context ctx(Context c) {
        try {
            Context a = c.getApplicationContext();
            return (a != null) ? a : c;
        } catch (Throwable ignored) {
            return c;
        }
    }

    private static void draw(Context c, Canvas cv, int W, int H,
                             AssetSection.Snapshot snap, List<AssetSection.Book> order,
                             int view, Poster r) {
        // 基础尺 = 屏高 / 800（S4 ⇒ 1.0）；全部尺跟着它走 ⇒ 换分辨率不失衡（照 BillWallpaper）
        final float s = H / 800f;
        final float sTitle  = BillSection.SZ_TITLE  * s;
        final float sSerial = BillSection.SZ_SERIAL * s;
        final float sBody   = BillSection.SZ_BODY   * s;
        final float sHead   = BillSection.SZ_BODY   * s;
        final float sMeta   = BillSection.SZ_SMALL  * s;
        final float sSmall  = BillSection.SZ_SMALL  * s;
        final float sTotal  = BillSection.SZ_BODY   * s;
        final float pad     = 34f * s;
        final float lineH   = BillSection.LH;
        // 条目行高：海报专用，比票据尺 1.7 略紧 ⇒ 尾部之外多出一行空间多放一本（墨单海报复用 BillSection.LH，不受影响）
        final float itemLH  = 1.55f;

        final boolean excerpt = (view != MenuPrefs.VIEW_READING);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.LEFT);

        // ① 纯白底 + 双线票据框（逐值照墨单海报）
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
        final float pCol = BillSection.PRICE_COL_W * s;

        float y = 22f * s + pad;

        // ② 抬头：标题（左，衬线加粗） + 「阅读资产」（右，等宽）—— 槽位照墨单海报的单号槽
        String title = AssetSection.nz(MenuPrefs.title(c));
        if (title.length() == 0) title = MenuPrefs.DEFAULT_TITLE;
        p.setTypeface(Typeface.SERIF);
        p.setFakeBoldText(true);
        p.setColor(INK);
        p.setTextSize(sTitle);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText(BillWallpaper.fit(p, title, avail * 0.55f), left, y + sTitle, p);
        p.setFakeBoldText(false);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(sSerial);
        p.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(BillWallpaper.fit(p, "阅读资产", avail * 0.4f), right, y + sTitle * 0.78f, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(null);
        y += sTitle * 1.36f;

        // ③ 副行已删（`阅读资产 · N 本 · 时长` 与页底「合计」行同数字 ⇒ 冗余，空间让给书目）
        y = BillWallpaper.sep(cv, p, left, right, y, s);

        // ④ 空表（一本书都没有）：只写一句，绝不产 0 值海报
        if (order.isEmpty()) {
            p.setTypeface(Typeface.SERIF);
            p.setColor(GRAY);
            p.setTextSize(sBody);
            p.setTextAlign(Paint.Align.CENTER);
            cv.drawText("还没有可统计的阅读资产", (left + right) / 2f, y + sBody * 3f, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(null);
            footer(cv, p, W, H, s);
            return;
        }

        // ⑤ 表头（只在摘录皮肤画 —— 读书皮肤没有价格列，与墨单海报同规则）
        if (excerpt) {
            p.setTypeface(Typeface.MONOSPACE);
            p.setColor(GRAY);
            p.setTextSize(sHead);
            cv.drawText("序号", left, y + sHead, p);
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("收入", right, y + sHead, p);
            cv.drawText("原价", right - pCol, y + sHead, p);
            p.setTextAlign(Paint.Align.LEFT);
            cv.drawText("品类", left + avail * 0.34f, y + sHead, p);
            y += sHead * lineH;
            y = BillWallpaper.sep(cv, p, left, right, y, s);
        }

        // ⑥ 尾部预留（截断行 + 三计数小字 + 合计一行 + 装饰条 + 落款），先算出来 ⇒ 书目区不压尾部
        float tailH = sSmall * lineH                      // `… 还有 N 本`
                    + sSmall * lineH                      // 三计数小字（与屏上同源）
                    + sTotal * lineH                      // 合计一行三列
                    + BillWallpaper.barsH(s)              // 装饰条形码一行
                    + sSmall * lineH                      // 落款
                    + s * 14f;                            // 页底安全余量（原 30 ⇒ 14，空间让给书目）
        final float limit = H - 22f * s - pad - tailH;

        // ⑦ 逐本：**先备料再判放得下** —— 避免「画到一半才发现超界」
        int drawn = 0;
        for (int i = 0; i < order.size(); i++) {
            AssetSection.Book b = order.get(i);
            final String no = (i + 1) + ". ";

            // 标题：摘录皮肤 = 单行（右侧留给价格两列）；读书皮肤 = 折 2 行（与屏上 `Row.wrap(...,2)` 同）
            List<String> titleLines = new ArrayList<String>();
            p.setTypeface(Typeface.MONOSPACE);
            p.setFakeBoldText(true);
            p.setTextSize(sBody);
            final float titleMax = excerpt ? (avail - 2f * pCol - s * 8f) : avail;
            if (excerpt) {
                titleLines.add(BillWallpaper.fit(p, no + AssetSection.nz(b.title), titleMax));
            } else {
                titleLines.addAll(BillWallpaper.wrap(p, no + "「" + AssetSection.nz(b.title) + "」", titleMax, 2));
            }
            p.setFakeBoldText(false);
            p.setTypeface(null);

            // meta：作者 · 时长 · 进度%（与屏上 `AssetSection#meta` 逐字同源）
            final String metaTxt = metaText(b);
            // 摘录行：与屏上同源（手填摘录 → 本地划线 → 想法）
            final String noteTxt = excerpt ? AssetSection.excerptOf(c, b.id) : null;

            float need = titleLines.size() * sBody * itemLH + sMeta * itemLH;
            if (noteTxt != null) need += sSmall * itemLH;
            if (i > 0) need += s * 4f;
            if (y + need > limit) break;

            if (i > 0) y += s * 4f;                        // 条目之间一口气（原 8 ⇒ 4）

            // 标题行（+ 摘录皮肤右端两列，列宽固定 ⇒ 与表头严格成列）
            final float moneyMax = pCol - s * 4f;
            p.setTypeface(Typeface.MONOSPACE);
            p.setFakeBoldText(true);
            p.setColor(INK);
            p.setTextSize(sBody);
            for (int k = 0; k < titleLines.size(); k++) {
                cv.drawText(titleLines.get(k), left, y + sBody, p);
                if (k == 0 && excerpt) {
                    p.setTextAlign(Paint.Align.RIGHT);
                    final String ptxt = AssetSection.priceText(b);
                    final String dtxt = AssetSection.incomeText(b);
                    final float scP = InkTheme.moneyScaleX(p, ptxt, moneyMax);
                    p.setTextScaleX(scP);
                    cv.drawText((scP < 1f) ? ptxt : BillWallpaper.fit(p, ptxt, moneyMax), right - pCol, y + sBody, p);
                    p.setTextScaleX(1f);
                    final float scD = InkTheme.moneyScaleX(p, dtxt, moneyMax);
                    p.setTextScaleX(scD);
                    cv.drawText((scD < 1f) ? dtxt : BillWallpaper.fit(p, dtxt, moneyMax), right, y + sBody, p);
                    p.setTextScaleX(1f);
                    p.setTextAlign(Paint.Align.LEFT);
                }
                y += sBody * itemLH;
            }
            p.setFakeBoldText(false);
            p.setTypeface(null);

            // meta 行（小字，缩进 1 字，照屏上 `meta()` 的 LIGHT 色）
            p.setTypeface(Typeface.MONOSPACE);
            p.setColor(LIGHT);
            p.setTextSize(sMeta);
            cv.drawText(BillWallpaper.fit(p, metaTxt, avail - s * 12f), left + s * 12f, y + sMeta, p);
            y += sMeta * itemLH;

            // 摘录行（衬线、灰、缩进 1 字）
            if (noteTxt != null) {
                p.setTypeface(Typeface.SERIF);
                p.setColor(GRAY);
                p.setTextSize(sSmall);
                cv.drawText(BillWallpaper.fit(p, noteTxt, avail - s * 12f), left + s * 12f, y + sSmall, p);
                y += sSmall * itemLH;
            }
            p.setTypeface(null);
            drawn++;
        }
        r.itemDrawn = drawn;

        // ⑧ 截断行（🔴 只画 `… 还有 N 本`，不画「展开 / 收起」）
        if (drawn < order.size()) {
            p.setTypeface(Typeface.MONOSPACE);
            p.setColor(LIGHT);
            p.setTextSize(sSmall);
            cv.drawText("… 还有 " + (order.size() - drawn) + " 本", left, y + sSmall, p);
            p.setTypeface(null);
            y += sSmall * lineH;
        }

        // ⑨ 票据尾：分隔线 → 合计一行三列 → 三计数小字 → 装饰条形码 → 落款（钉在页底）
        float ty = y + s * 10f;
        ty = BillWallpaper.sep(cv, p, left, right, ty, s);

        p.setTypeface(Typeface.MONOSPACE);
        p.setFakeBoldText(true);
        p.setColor(INK);
        p.setTextSize(sTotal);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText(BillWallpaper.fit(p, AssetSection.totalMain(snap.items.size(), snap.secSum), avail * 0.5f),
                left, ty + sTotal, p);
        p.setTextAlign(Paint.Align.RIGHT);
        final String priceSumTxt = AssetSection.money(snap.priceSum);
        final String incomeSumTxt = AssetSection.money(snap.incomeSum);
        final float scPs = InkTheme.moneyScaleX(p, priceSumTxt, pCol - s * 4f);
        p.setTextScaleX(scPs);
        cv.drawText((scPs < 1f) ? priceSumTxt : BillWallpaper.fit(p, priceSumTxt, pCol - s * 4f),
                right - pCol, ty + sTotal, p);
        p.setTextScaleX(1f);
        final float scIs = InkTheme.moneyScaleX(p, incomeSumTxt, pCol - s * 4f);
        p.setTextScaleX(scIs);
        cv.drawText((scIs < 1f) ? incomeSumTxt : BillWallpaper.fit(p, incomeSumTxt, pCol - s * 4f),
                right, ty + sTotal, p);
        p.setTextScaleX(1f);
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(false);
        p.setTypeface(null);
        ty += sTotal * lineH;

        // 三计数小字（与屏上同源：已计价 / 原价未知 / 进度未取到）
        p.setTypeface(Typeface.MONOSPACE);
        p.setColor(GRAY);
        p.setTextSize(sSmall);
        cv.drawText(BillWallpaper.fit(p, AssetSection.totalNote(snap), avail), left, ty + sSmall, p);
        p.setTypeface(null);
        ty += sSmall * lineH;

        ty = BillWallpaper.bars(cv, p, left, right, ty, s);

        footer(cv, p, W, H, s);
    }

    /** 第二行小字：作者 · 时长 · 进度（**与屏上 {@code AssetSection#meta} 逐字同源**）。 */
    private static String metaText(AssetSection.Book b) {
        StringBuilder sb = new StringBuilder();
        if (b.author != null && b.author.length() > 0) sb.append(b.author).append(" · ");
        sb.append(AssetSection.dur(b.sec));
        if (b.pct >= 0) sb.append(" · ").append(b.pct).append("%");
        return sb.toString();
    }

    /** 落款：{@code 微读墨记 · 阅读资产}（居中，几何照 {@link BillWallpaper} 的落款行）。 */
    private static void footer(Canvas cv, Paint p, int W, int H, float s) {
        float base = H - 22f * s - 34f * s;
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.SERIF);
        p.setFakeBoldText(true);
        p.setColor(INK);
        p.setTextSize(BillSection.SZ_SMALL * s);
        cv.drawText("微读墨记 · 阅读资产", W / 2f, base, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(null);
        p.setFakeBoldText(false);
    }
}
