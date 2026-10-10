package com.inkread.weekread.core;

/**
 * 🆕 TASK-079：跨包**深链 Intent extra 的键名 / 取值**（只有字符串常量，零依赖）。
 *
 * <p>为什么要单独一个类：发起方在 {@code a11y}（墨台覆盖窗的「管理」按钮），
 * 接收方在 {@code shell}（{@code MainActivity}），而 {@code a11y} **不依赖** {@code shell}
 * —— 把键名放在 {@code core}（双方都依赖它）就谁都不用反向 import。
 *
 * <p>用法：
 * <pre>
 *   Intent it = new Intent();
 *   it.setClassName(ctx, "com.inkread.weekread.shell.MainActivity");
 *   it.putExtra(NavExtra.LAB_SUB, NavExtra.LAB_SUB_WALLPAPER);
 * </pre>
 */
public final class NavExtra {

    /** {@code --es wb_lab_sub &lt;名&gt;}：打开后**定位到实验室的某个子标签**。 */
    public static final String LAB_SUB = "wb_lab_sub";

    /** 子标签名：壁纸管家（对应 {@code R.id.page_lab_wallpaper}）。 */
    public static final String LAB_SUB_WALLPAPER = "wallpaper";

    private NavExtra() {
    }
}
