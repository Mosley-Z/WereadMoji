package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.inkread.weekread.core.PagePrefs;
import com.inkread.weekread.core.WallpaperPrefs;

import java.util.List;

/**
 * 🆕 TASK-078：墨台「壁纸管家」分区（`DeskModules.WallModule` 的内容）。
 *
 * <p>只做**展示 + 开关 + 换一张**（卡面 §关键约束 6）；「管理」深链到实验室子标签
 * （子标签本体属 `TASK-079`，本卡先给可点按钮 + 宿主回调）。
 *
 * <p>🔴 **零网络 / 零后台**：只读 {@link WallpaperPrefs} + 一次 `File.isFile()` 过滤；
 * 轮换的推进**不在这里**（唯一时机 = 软锁绘背景，见 {@link WallpaperPrefs#effectiveSoftLockPath}），
 * 本区只显示状态；「换一张」= 用户显式动作（{@link WallpaperPrefs#rotateNow}）。
 */
final class WallSection implements InsightRenderer.Section {

    private static final float SZ_BODY  = 15f;
    private static final float SZ_SMALL = 12f;
    private static final float LH       = 1.7f;
    private static final float BTN_H    = 24f;      // ×unit
    private static final float BTN_W    = 66f;      // ×unit
    private static final float BTN_GAP  = 8f;       // ×unit

    private static final int INK   = 0xFF000000;
    private static final int GRAY  = 0xFF3C3C3C;
    private static final int LIGHT = 0xFFA8A8A8;
    private static final int LINE  = 0xFFD8D8D8;

    /** 命中结果。 */
    static final int ACT_NONE = -1;
    static final int ACT_ROT = 0;      // 点开关行 ⇒ 切换轮换
    static final int ACT_SWAP = 1;     // 换一张
    static final int ACT_MANAGE = 2;   // 管理（深链实验室子标签）

    private Context ctx;
    private String rightText = "";
    private String swText = "";
    private boolean canSwap;
    private boolean hasPool;

    public String title() { return PagePrefs.moduleName(PagePrefs.MOD_WALL); }

    /** 每帧呼出时重装料（只读本地）。 */
    void refresh(Context c) {
        this.ctx = c.getApplicationContext();
        List<String> pool = WallpaperPrefs.pool(c);
        int n = pool.size();
        hasPool = n > 0;
        int idx = WallpaperPrefs.index(c);
        if (idx < 0 || idx >= n) idx = 0;
        rightText = (n == 0) ? "未设置壁纸" : ("第 " + (idx + 1) + " / " + n + " 张");
        canSwap = n >= 2;

        boolean rot = WallpaperPrefs.isRotationOn(c);
        StringBuilder sb = new StringBuilder();
        sb.append("轮换：").append(rot ? "开" : "关");
        if (rot) sb.append(" · 每 ").append(WallpaperPrefs.intervalDays(c)).append(" 天");
        sb.append(" · ").append(WallpaperPrefs.scopeLabel(c));
        // 🔴 R4 如实提示：`scope=dream` 时轮换对软锁**无效果**（Dream 侧 TASK-081 才接）
        //    —— 不写"已支持屏保"，只标注"未启用"。UI 不置灰（用户定则：不硬拦能力缺失）。
        if (rot && !WallpaperPrefs.rotatesSoftLock(c)) sb.append("（软锁未启用）");
        swText = sb.toString();
    }

    // ══════════════════════ 几何（画与命中共用） ══════════════════════

    private static float headH(float unit) { return InsightRenderer.secHeadH(unit); }

    private static float statusTop(float unit, float top) { return top + headH(unit); }

    private static float btnTop(float unit, float top) { return statusTop(unit, top) + SZ_BODY * LH * unit; }

    private static float swTop(float unit, float top) { return btnTop(unit, top) + BTN_H * unit + unit * 6f; }

    private static RectF swapBtn(float w, float unit, float top) {
        float l = w * InsightRenderer.PAD_X_RATIO;
        float y = btnTop(unit, top);
        return new RectF(l, y, l + BTN_W * unit, y + BTN_H * unit);
    }

    private static RectF manageBtn(float w, float unit, float top) {
        float l = w * InsightRenderer.PAD_X_RATIO + (BTN_W + BTN_GAP) * unit;
        float y = btnTop(unit, top);
        return new RectF(l, y, l + BTN_W * unit, y + BTN_H * unit);
    }

    /**
     * 命中判定 —— 🔴 **与绘制共用同一套几何**（上机标定过：注入坐标与 View 坐标一致，
     * 偏差只可能来自两边几何不一致，所以这里绝不另算一套）。
     *
     * @return {@link #ACT_ROT} / {@link #ACT_SWAP} / {@link #ACT_MANAGE} / {@link #ACT_NONE}
     */
    int hitAction(float x, float y, float w, float unit, float top) {
        if (canSwap && swapBtn(w, unit, top).contains(x, y)) return ACT_SWAP;
        if (manageBtn(w, unit, top).contains(x, y)) return ACT_MANAGE;
        float t = swTop(unit, top);
        float b = t + SZ_SMALL * LH * unit;
        if (y >= t - unit * 5f && y <= b + unit * 5f
                && x >= w * InsightRenderer.PAD_X_RATIO && x <= w - w * InsightRenderer.PAD_X_RATIO) {
            return ACT_ROT;
        }
        return ACT_NONE;
    }

    // ══════════════════════ 动作执行（由容器在命中后调用） ══════════════════════

    /**
     * 执行一个**本地**动作（`ACT_ROT` 切开关 / `ACT_SWAP` 换一张）。
     *
     * <p>🔴 副作用集中在这里 ⇒ 容器（{@link DeskPageView}）不必知道任何 prefs 细节；
     *    两动作都只改 prefs、**零网络零后台**。`ACT_MANAGE` 要走宿主回调，**不在本方法处理**
     *    （本方法对它无操作）。
     */
    void applyAction(Context c, int act) {
        if (act == ACT_ROT) {
            WallpaperPrefs.setRotationOn(c, !WallpaperPrefs.isRotationOn(c));
        } else if (act == ACT_SWAP) {
            WallpaperPrefs.rotateNow(c);
        }
    }

    // ══════════════════════ 量高 / 画 ══════════════════════

    public float height(float w, float vh, float unit) {
        return headH(unit)
                + SZ_BODY * LH * unit
                + BTN_H * unit + unit * 6f
                + SZ_SMALL * LH * unit
                + unit * 6f;
    }

    public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
        InsightRenderer.drawSectionHead(c, w, top, unit, p, title());
        float l = w * InsightRenderer.PAD_X_RATIO;
        float r = w - w * InsightRenderer.PAD_X_RATIO;

        // ① 状态行：缩略占位方块 + 「当前壁纸」 + 右端序号
        float y0 = statusTop(unit, top);
        float blk = unit * 22f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(LINE);
        c.drawRect(l, y0 + unit * 2f, l + unit * 16f, y0 + unit * 2f + blk, p);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(null);
        p.setTextSize(SZ_BODY * unit);
        p.setFakeBoldText(false);
        p.setColor(GRAY);
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = y0 + (SZ_BODY * LH * unit) / 2f - (fm.ascent + fm.descent) / 2f;
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("当前壁纸", l + unit * 22f, base, p);
        p.setColor(hasPool ? INK : LIGHT);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(rightText, r, base, p);

        // ② 按钮行
        drawBtn(c, swapBtn(w, unit, top), "换一张", canSwap, unit, p);
        drawBtn(c, manageBtn(w, unit, top), "管理", true, unit, p);

        // ③ 开关行（可点）
        float y2 = swTop(unit, top);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(SZ_SMALL * unit);
        p.setColor(GRAY);
        Paint.FontMetrics fm2 = p.getFontMetrics();
        float base2 = y2 + (SZ_SMALL * LH * unit) / 2f - (fm2.ascent + fm2.descent) / 2f;
        c.drawText(swText, l, base2, p);
        p.setColor(LIGHT);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("点此切换", r, base2, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawBtn(Canvas c, RectF box, String label, boolean enabled, float unit, Paint p) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(enabled ? INK : LIGHT);
        c.drawRect(box, p);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(null);
        p.setTextSize(SZ_SMALL * unit);
        p.setFakeBoldText(false);
        p.setColor(enabled ? INK : LIGHT);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = box.centerY() - (fm.ascent + fm.descent) / 2f;
        c.drawText(label, box.centerX(), base, p);
        p.setTextAlign(Paint.Align.LEFT);
    }
}
