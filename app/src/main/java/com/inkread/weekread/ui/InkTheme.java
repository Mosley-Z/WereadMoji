package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;

import com.inkread.weekread.core.CardPrefs;

/**
 * V1.1.1-beta · 手机端「遥控台」设计 token（Java 侧，**双色板**）。
 *
 * <p>权威定义 = {@code docs/09_手机端遥控台UI规范.md} §二（亮）/ §2.1（深）。隐喻 **纸 + 墨 + 一点青**：
 * 纸 = 暖白底（非纯白，减轻刺眼），墨 = 近黑文字，青 = 唯一强调色。深色版 = **墨青底 + 纸白字 + 朱砂 + 鎏金**。
 *
 * <p>🔴 仅**手机端**遥控台/设置页使用；墨水屏端一行不碰（本批阅读器端零差异）。XML 侧同名色值见
 * {@code res/values/colors.xml}（两处保持同步，改一处记得改另一处）。
 *
 * <h3>🆕 TASK-041：亮/深双色板</h3>
 * <ul>
 *   <li><b>亮色</b> = 既有静态常量 {@link #PAPER}…{@link #CLAY_WEAK}（**原值一字不改**，保持既有引用可用）；</li>
 *   <li><b>深色</b> = {@link #DARK_PAPER}…{@link #DARK_GOLD}（逐值对齐网页应用，见 docs/09 §2.1）；</li>
 *   <li>自绘 View 一律改走**语义 getter**（{@link #paper(Context)} 等）—— 由 {@link #isDark(Context)}
 *       二选一返回。默认（reader / 未开深色）恒返回亮色值 ⇒ 对现状**逐像素一致**（验收 A6）。</li>
 * </ul>
 *
 * <p>「书香气」来源 = 标题用**衬线**、纸张暖白底、留白充足；「科技感」来源 = 数字用**等宽**、
 * 状态点用几何圆、描边细、动效克制。
 */
public final class InkTheme {

    private InkTheme() {
    }

    // ── 色彩（亮 · 与 colors.xml 一一对应）──

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
    /** 翻页键按下态（亮）：纸-2 压深一档。 */
    public static final int KEY_PRESSED = 0xFFE7E0D0;

    // ── 色彩（深 · TASK-041；逐值对齐网页应用，docs/09 §2.1）──
    //
    // 🔴 只换色值、不换语义：状态点含义与亮色完全一致。

    /** 深色 · 墨：主背景。 */
    public static final int DARK_PAPER = 0xFF070A0F;
    /** 深色 · 墨-2：卡片 / 分组底。 */
    public static final int DARK_PAPER2 = 0xFF111A23;
    /** 深色 · 墨-1：次级面 / 凹陷底。 */
    public static final int DARK_SURFACE_DIM = 0xFF0C1117;
    /** 深色 · 纸：主文字。 */
    public static final int DARK_INK = 0xFFE9E4D8;
    /** 深色 · 纸-2：次要文字。 */
    public static final int DARK_INK2 = 0xFFB3ADA1;
    /** 深色 · 淡：占位 / 禁用。 */
    public static final int DARK_INK3 = 0xFF7E8894;
    /** 深色 · 界线：`rgba(233,228,216,.11)`。 */
    public static final int DARK_LINE = 0x1CE9E4D8;
    /** 深色 · 青玉（提亮）：唯一强调色。 */
    public static final int DARK_BAMBOO = 0xFF4ECDC4;
    /** 深色 · 青玉浅底：`rgba(78,205,196,.10)`。 */
    public static final int DARK_BAMBOO_WEAK = 0x1A4ECDC4;
    /** 深色 · 朱砂：警示（未连接 / 异常）。 */
    public static final int DARK_CLAY = 0xFFC2604F;
    /** 深色 · 朱砂浅底：`rgba(194,96,79,.13)`。 */
    public static final int DARK_CLAY_WEAK = 0x21C2604F;
    /** 深色 · 鎏金：刻度 / 次要强调。 */
    public static final int DARK_GOLD = 0xFFC9A76A;
    /** 深色 · 翻页键按下态：墨-2 压深一档。 */
    public static final int DARK_KEY_PRESSED = 0xFF1B2632;

    // ── 语义取色（亮/深二选一）──

    /**
     * 当前是否深色：**仅当** `install_role=phone` 且 `phone_dark_mode=true`。
     *
     * <p>🔴 阅读器端恒 false（默认）⇒ 所有语义 getter 返回亮色值，与现状逐像素一致（验收 A6）。
     * <p>任何异常兜底为 false（宁可亮色，也不让界面因读偏好失败而炸）。
     */
    public static boolean isDark(Context c) {
        if (c == null) return false;
        try {
            return CardPrefs.getInstallRole(c) == CardPrefs.INSTALL_ROLE_PHONE
                    && CardPrefs.isPhoneDarkMode(c);
        } catch (Throwable t) {
            return false;
        }
    }

    public static int paper(Context c) {
        return isDark(c) ? DARK_PAPER : PAPER;
    }

    public static int paper2(Context c) {
        return isDark(c) ? DARK_PAPER2 : PAPER2;
    }

    /** 次级面 / 凹陷底：亮色下与 {@link #paper2} 同值（亮色无此层）。 */
    public static int surfaceDim(Context c) {
        return isDark(c) ? DARK_SURFACE_DIM : PAPER2;
    }

    public static int ink(Context c) {
        return isDark(c) ? DARK_INK : INK;
    }

    public static int ink2(Context c) {
        return isDark(c) ? DARK_INK2 : INK2;
    }

    public static int ink3(Context c) {
        return isDark(c) ? DARK_INK3 : INK3;
    }

    public static int line(Context c) {
        return isDark(c) ? DARK_LINE : LINE;
    }

    public static int bamboo(Context c) {
        return isDark(c) ? DARK_BAMBOO : BAMBOO;
    }

    public static int bambooWeak(Context c) {
        return isDark(c) ? DARK_BAMBOO_WEAK : BAMBOO_WEAK;
    }

    public static int clay(Context c) {
        return isDark(c) ? DARK_CLAY : CLAY;
    }

    public static int clayWeak(Context c) {
        return isDark(c) ? DARK_CLAY_WEAK : CLAY_WEAK;
    }

    /** 鎏金：亮色下复用竹青（亮色无鎏金层）—— 用于版本号 / 分组标记。 */
    public static int gold(Context c) {
        return isDark(c) ? DARK_GOLD : BAMBOO;
    }

    /** 翻页键按下态底色。 */
    public static int keyPressed(Context c) {
        return isDark(c) ? DARK_KEY_PRESSED : KEY_PRESSED;
    }

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
