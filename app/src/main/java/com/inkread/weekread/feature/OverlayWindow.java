package com.inkread.weekread.feature;

import com.inkread.weekread.core.CardSpec;

import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * 桌面悬浮卡的窗口参数。
 *
 * 卡片主体**不响应触摸**（FLAG_NOT_TOUCHABLE）：手指落在卡片上也不会被它吃掉，
 * 桌面手势（长按加卡片、滑动翻页）照常。
 *
 * 从 v0.3.1 起，卡片的**右上角「更新于…」那一小块**单独开了一个透明的小窗口
 * （{@link #paramsTouch(int)}），它是整张卡片唯一吃掉触摸的地方 ——
 * 点它就是手动刷新。这样做而不是给整张卡片去掉 NOT_TOUCHABLE，是因为
 * 卡片主体占 368×346，去掉的话桌面在这一大片里的手势就全废了。
 */
public final class OverlayWindow {

    private OverlayWindow() {
    }

    /** 无障碍悬浮窗：TYPE_ACCESSIBILITY_OVERLAY，**不需要 SYSTEM_ALERT_WINDOW 权限** */
    public static int typeAccessibility() {
        return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    }

    /** 卡片主体（收起态）：整块穿透，不吃任何触摸 */
    public static WindowManager.LayoutParams params(int type) {
        return params(type, CardSpec.cardHeight());
    }

    /**
     * 卡片主体，高度由调用方给（TASK-017：本记「展开▽」后窗口要变高）。
     *
     * 🔴 卡片主体**始终**带 `FLAG_NOT_TOUCHABLE` —— 桌面手势（长按加卡片、滑动翻页）
     * 必须穿透。窗口变高只是把"画的地方"变大，**不会**多吃一片触摸
     * （这也是 TASK-017 敢把卡片拉到 660px 高的前提，见 `验证记录/59` 项④）。
     */
    public static WindowManager.LayoutParams params(int type, int h) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.cardWidth(), h, type, flags, PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.CARD_LEFT;
        lp.y = CardSpec.CARD_TOP;
        lp.setTitle("微读墨记");
        return lp;
    }

    /**
     * 右上角「更新于…」触摸区：透明、不画任何东西，只负责接住点击。
     * 保留 FLAG_NOT_FOCUSABLE（不吃键盘、不抢焦点），但**故意不加** FLAG_NOT_TOUCHABLE。
     */
    public static WindowManager.LayoutParams paramsTouch(int type) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.TAP_W, CardSpec.TAP_H, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.tapLeft();
        lp.y = CardSpec.tapTop();
        lp.setTitle("微读墨记·刷新");
        return lp;
    }

    /**
     * 🆕 TASK-080：左上角「打开墨台」按钮的触摸区 —— **四种形态都有**（它是墨台的唯一常驻入口）。
     *
     * 与 {@link #paramsTitleTouch} 并列在抬头行：按钮在最左、抬头窗在其右。
     * 位置由 {@link CardSpec#deskBoxLeft()} / {@link CardSpec#deskTouchTop()} 给，
     * 与 {@link WeekCardView} 画出来的那个小框同源（改几何只改 `CardSpec`）。
     *
     * 🔴 **不承载长按** —— 「长按 = 隐藏时长菜单」仍然只归 `titleView`（见 `OverlayController`）。
     */
    public static WindowManager.LayoutParams paramsDeskTouch(int type) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.DESK_TOUCH_W, CardSpec.DESK_TOUCH_H, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.deskBoxLeft();
        lp.y = CardSpec.deskTouchTop();
        lp.setTitle("微读墨记·打开墨台");
        return lp;
    }

    /**
     * 左上角「抬头」触摸区：点它切换卡片周期（本周 ↔ 本月，① 的第二个入口）。
     *
     * 与刷新触摸区同样的取舍 —— 只圈住标题那一小块，**不**把整张卡片变成可触摸，
     * 否则桌面在 368×346 这一大片里的手势（滑动翻页、长按）就全废了。
     *
     * 🔴 🆕 TASK-080：抬头窗**左沿右移**到「打开墨台」按钮的右侧（{@link CardSpec#titleTapLeft()}），
     * 宽度随之收到 {@link CardSpec#TITLE_TAP_W} = 124 —— 两个窗（按钮 / 抬头）**不许重叠**，
     * 否则点按钮会误触切形态、点切换会误触呼出墨台。
     */
    public static WindowManager.LayoutParams paramsTitleTouch(int type) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.TITLE_TAP_W, CardSpec.TITLE_TAP_H, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.titleTapLeft();
        lp.y = CardSpec.titleTapTop();
        lp.setTitle("微读墨记·切周期");
        return lp;
    }

    /**
     * 右下角「打开」按钮的触摸区（只在**本书**形态出现）。
     *
     * 与 {@link #paramsTouch} / {@link #paramsTitleTouch} 同款取舍 —— 只圈住那个小框，
     * 卡片其它区域照旧穿透给桌面。位置由 {@link CardSpec#openBoxLeft()} /
     * {@link CardSpec#openBoxTop()} 给，与 {@link WeekCardView} 画出来的框**严格对齐**
     * （两边共用同一组常量，改几何只改 CardSpec）。
     */
    public static WindowManager.LayoutParams paramsOpenTouch(int type) {
        return paramsOpenTouch(type, CardSpec.cardHeight());
    }

    /**
     * 同上，卡片的**当前高度**由调用方给（TASK-017）。
     *
     * 本记展开后卡片变高，页内按钮按 `h` 定位**随下沿下移**（用户拍板②）⇒
     * 这个触摸窗也得跟着下移，否则它会停在收起态的位置，与画出来的框错开 ——
     * 表现为"点框没反应、点框上方空白处反而有反应"。
     */
    public static WindowManager.LayoutParams paramsOpenTouch(int type, int h) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.OPEN_BOX_W, CardSpec.OPEN_BOX_H, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.openBoxLeft();
        lp.y = CardSpec.openBoxTop(h);
        lp.setTitle("微读墨记·打开");
        return lp;
    }

    /**
     * **左下角按钮位**的触摸区 —— 一个窗，两个形态复用：
     * · 「本记」形态 = 「上一条」（v0.4.2）；
     * · 🆕「本书」形态 = 「选书」（TASK-071，入口唯一化到左下角）。
     *
     * 与 {@link #paramsOpenTouch} 完全对称：同一水平线、同一尺寸，只是贴左边。
     * 位置由 {@link CardSpec#prevBoxLeft()} / {@link CardSpec#prevBoxTop()} 给 ——
     * 那是这一格的**唯一权威定义**，与 {@link WeekCardView} 画出来的框严格对齐
     * （绘制侧同样取 {@code CardSpec.PREV_BOX_MARGIN_L}，两边不许各留一份坐标）。
     *
     * ⚠️ 方法名沿用 v0.4.2 的 `prev*`（与 {@code OverlayController#prevView} 同规：
     * 按主要角色命名）。窗口标题也保持「微读墨记·上一条」不变 —— 它是 dumpsys 排查用的
     * 稳定标识，不为这次多角色改名。
     */
    public static WindowManager.LayoutParams paramsPrevTouch(int type) {
        return paramsPrevTouch(type, CardSpec.cardHeight());
    }

    /** 同上，卡片的**当前高度**由调用方给（TASK-017）—— 与「打开」同一条水平线 */
    public static WindowManager.LayoutParams paramsPrevTouch(int type, int h) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.OPEN_BOX_W, CardSpec.OPEN_BOX_H, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.prevBoxLeft();
        lp.y = CardSpec.prevBoxTop(h);
        lp.setTitle("微读墨记·上一条");
        return lp;
    }

    /**
     * 本记**行末**「展开▽ / 收起△」的触摸区（TASK-017）。
     *
     * 🔴 与其余触摸窗不同：这个按钮**没有固定坐标** —— 它挂在正文末行的行末，
     * 落在第几行取决于这条划线有多长。所以位置与尺寸全部由绘制时回写的
     * {@link WeekCardView#expandBox}（已换算成**屏幕坐标**）给，每次变化都
     * `updateViewLayout`。写死坐标必然对不齐（用户点了没反应）。
     */
    public static WindowManager.LayoutParams paramsExpandTouch(int type, int x, int y, int w, int h) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;
        lp.setTitle("微读墨记·展开");
        return lp;
    }

    /**
     * 待办**逐条勾选框**的触摸区（V1.0.3-beta，TASK-024）。
     *
     * 与 {@link #paramsExpandTouch} 完全同款：位置没有固定坐标 —— 每条待办的勾选框随
     * 条目增减整体位移，所以坐标由绘制时回写的 {@code WeekCardView#todoBoxes}
     * （已换算成**屏幕坐标**）给。**每条一个窗**（一屏最多五六条）。
     */
    public static WindowManager.LayoutParams paramsTodoTouch(int type, int x, int y, int w, int h) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;
        lp.setTitle("微读墨记·勾选");
        return lp;
    }

    /**
     * 长按菜单窗口 —— **整张卡片那么大，但只有中间那个框是不透明的**。
     *
     * 为什么要铺满卡片而不是只铺菜单框：铺满之后，点在框外的部分能被本窗口接住，
     * 于是"点空白处关掉菜单"就是天然行为（{@link CardMenuView} 会把框外点击当取消）。
     * 铺满的代价只是菜单打开的那几秒内、卡片区域内的桌面手势被吃掉 ——
     * 菜单 8 秒自动消失，且用户此刻本来就是在操作卡片。
     */
    public static WindowManager.LayoutParams paramsMenu(int type) {
        return paramsMenu(type, CardSpec.cardHeight());
    }

    /** 同上，卡片的**当前高度**由调用方给（TASK-017：菜单铺满卡片，展开态要跟着变高） */
    public static WindowManager.LayoutParams paramsMenu(int type, int h) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.cardWidth(), h, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.CARD_LEFT;
        lp.y = CardSpec.CARD_TOP;
        lp.setTitle("微读墨记·菜单");
        return lp;
    }
}
