package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * V1.2.0-beta · 手机端「遥控台」设计 token（Java 侧，纯静态常量）。
 *
 * <p>权威定义 = {@code docs/09_手机端遥控台UI规范.md} §二/§三/§四。隐喻 **纸 + 墨 + 一点青**：
 * 纸 = 暖白底（非纯白，减轻刺眼），墨 = 近黑文字，青 = 唯一强调色。
 *
 * <p>🔴 仅**手机端**遥控台使用；墨水屏端一行不碰（本批阅读器端零差异）。XML 侧同名色值见
 * {@code res/values/colors.xml}（两处保持同步，改一处记得改另一处）。
 *
 * <p>「书香气」来源 = 标题用**衬线**、纸张暖白底、留白充足；「科技感」来源 = 数字用**等宽**、
 * 状态点用几何圆、描边细、动效克制。
 */
public final class InkTheme {

    private InkTheme() {
    }

    // ── 色彩（与 colors.xml 一一对应）──

    /** 纸白：主背景（暖白，非纯白）。 */
    public static final int PAPER = 0xFFF7F3EA;
    /** 次级纸：卡片 / 分组底。 */
    public static final int PAPER2 = 0xFFEFE9DC;
    /** 墨：主文字。 */
    public static final int INK = 0xFF23201B;
    /** 淡墨：次要文字。 */
    public static final int INK2 = 0xFF6B6459;
    /** 提示：占位 / 禁用。 */
    public static final int INK3 = 0xFF9A9285;
    /** 界线：分隔线 / 描边。 */
    public static final int LINE = 0xFFDCD4C4;
    /** 竹青：**唯一强调色**（主操作 / 已连接 / 选中）。 */
    public static final int BAMBOO = 0xFF2E6B5E;
    /** 竹青浅底（选中态）。 */
    public static final int BAMBOO_WEAK = 0xFFE4EDE8;
    /** 赭石：仅警示（未连接 / 异常），不用于装饰。 */
    public static final int CLAY = 0xFFB0763C;
    /** 赭石浅底。 */
    public static final int CLAY_WEAK = 0xFFF3E7D6;

    // ── 字体 ──

    /** 衬线（标题用 —— 书香气来源）。 */
    public static Typeface serif() {
        return Typeface.SERIF;
    }

    /** 等宽（数字 / 版本用 —— 科技感来源）。 */
    public static Typeface mono() {
        return Typeface.MONOSPACE;
    }

    // ── 尺寸换算 ──

    /** dp → px。 */
    public static float dp(Context c, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                c.getResources().getDisplayMetrics());
    }

    /** sp → px。 */
    public static float sp(Context c, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                c.getResources().getDisplayMetrics());
    }
}
