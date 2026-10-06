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

    // ── 单色语义层（墨水屏端 · 🆕 TASK-066）──
    //
    // 墨水屏端**无彩色**（`docs/03_墨水屏UI规范.md` 硬规则）⇒ 把手机端那套语义名
    // **一一对应地黑白化**，两端「同一套语义、不同色板」；将来调整墨水屏灰阶只改这一处。
    //
    // 🔴 只在 `install_role != phone` 时生效（下面的语义 getter 会按角色二选一返回）。
    //    phone 端**走原来的双色板分支、逐像素不变**（TASK-066 验收 A5）。
    // ⚠️ 值刻意沿用墨水屏端**既有**的灰阶（`CardRenderer` / `SegTabView` 里散落的字面量），
    //    本次只做「收敛成 token」，**不改观感**。

    /** 单色 · 纸：纯白底（墨水屏不需要暖白 —— 暖白反而降对比）。 */
    public static final int MONO_PAPER = 0xFFFFFFFF;
    /** 单色 · 次级面 / 凹陷底（= 布局里既有的 `#F2F2F2`）。 */
    public static final int MONO_SURFACE = 0xFFF2F2F2;
    /** 单色 · 墨：主文字 / 强调（= 手机端「墨」；墨水屏端即纯黑）。 */
    public static final int MONO_INK = 0xFF000000;
    /** 单色 · 淡墨：次要文字 / 结构线（= 手机端「淡墨」；墨水屏端深灰）。 */
    public static final int MONO_INK2 = 0xFF3C3C3C;
    /** 单色 · 提示：占位 / 禁用 / 未选中（= 手机端「提示」；墨水屏端浅灰）。 */
    public static final int MONO_INK3 = 0xFF9A9A9A;
    /** 单色 · 界线：分隔线 / 描边（= 手机端「界线」）。 */
    public static final int MONO_LINE = 0xFFD8D8D8;

    // ── 语义取色（按角色 + 亮/深二选一）──

    /**
     * 本机是否「手机端」角色（🆕 TASK-066）。
     *
     * <p>= `CardPrefs.getInstallRole(c) == INSTALL_ROLE_PHONE`。异常兜底 **false** ——
     * 读偏好失败时按**墨水屏端**处理（无彩色最安全，绝不因读偏好失败而放出彩色）。
     */
    public static boolean isPhone(Context c) {
        if (c == null) return false;
        try {
            return CardPrefs.getInstallRole(c) == CardPrefs.INSTALL_ROLE_PHONE;
        } catch (Throwable t) {
            return false;
        }
    }

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

    // 🔴 下面每个 getter 的第一行都是「非手机 ⇒ 单色 token」。phone 分支 = 改造前的原表达式，
    //    一个字都没动 ⇒ 手机端逐像素零差异。

    public static int paper(Context c) {
        if (!isPhone(c)) return MONO_PAPER;
        return isDark(c) ? DARK_PAPER : PAPER;
    }

    public static int paper2(Context c) {
        if (!isPhone(c)) return MONO_SURFACE;
        return isDark(c) ? DARK_PAPER2 : PAPER2;
    }

    /** 次级面 / 凹陷底：亮色下与 {@link #paper2} 同值（亮色无此层）。 */
    public static int surfaceDim(Context c) {
        if (!isPhone(c)) return MONO_SURFACE;
        return isDark(c) ? DARK_SURFACE_DIM : PAPER2;
    }

    public static int ink(Context c) {
        if (!isPhone(c)) return MONO_INK;
        return isDark(c) ? DARK_INK : INK;
    }

    public static int ink2(Context c) {
        if (!isPhone(c)) return MONO_INK2;
        return isDark(c) ? DARK_INK2 : INK2;
    }

    public static int ink3(Context c) {
        if (!isPhone(c)) return MONO_INK3;
        return isDark(c) ? DARK_INK3 : INK3;
    }

    public static int line(Context c) {
        if (!isPhone(c)) return MONO_LINE;
        return isDark(c) ? DARK_LINE : LINE;
    }

    /** 强调：墨水屏端无彩色 ⇒ 降级为「墨」（靠反白 / 加粗 / 描边承担强调）。 */
    public static int bamboo(Context c) {
        if (!isPhone(c)) return MONO_INK;
        return isDark(c) ? DARK_BAMBOO : BAMBOO;
    }

    /** 强调浅底：墨水屏端无彩色 ⇒ 降级为「次级面」。 */
    public static int bambooWeak(Context c) {
        if (!isPhone(c)) return MONO_SURFACE;
        return isDark(c) ? DARK_BAMBOO_WEAK : BAMBOO_WEAK;
    }

    /** 警示：墨水屏端无彩色 ⇒ 降级为「淡墨」（靠文案 / 加粗承担警示）。 */
    public static int clay(Context c) {
        if (!isPhone(c)) return MONO_INK2;
        return isDark(c) ? DARK_CLAY : CLAY;
    }

    /** 警示浅底：墨水屏端无彩色 ⇒ 降级为「次级面」。 */
    public static int clayWeak(Context c) {
        if (!isPhone(c)) return MONO_SURFACE;
        return isDark(c) ? DARK_CLAY_WEAK : CLAY_WEAK;
    }

    /** 鎏金：亮色下复用竹青（亮色无鎏金层）—— 用于版本号 / 分组标记。墨水屏端 ⇒ 淡墨。 */
    public static int gold(Context c) {
        if (!isPhone(c)) return MONO_INK2;
        return isDark(c) ? DARK_GOLD : BAMBOO;
    }

    /** 翻页键按下态底色。墨水屏端 ⇒ 次级面（无彩色下的「压深一档」）。 */
    public static int keyPressed(Context c) {
        if (!isPhone(c)) return MONO_SURFACE;
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
