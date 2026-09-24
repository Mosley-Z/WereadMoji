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

    /** 卡片主体：整块穿透，不吃任何触摸 */
    public static WindowManager.LayoutParams params(int type) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.cardWidth(), CardSpec.cardHeight(), type, flags, PixelFormat.OPAQUE);
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
     * 左上角「抬头」触摸区：点它切换卡片周期（本周 ↔ 本月，① 的第二个入口）。
     *
     * 与刷新触摸区同样的取舍 —— 只圈住标题那一小块，**不**把整张卡片变成可触摸，
     * 否则桌面在 368×346 这一大片里的手势（滑动翻页、长按）就全废了。
     */
    public static WindowManager.LayoutParams paramsTitleTouch(int type) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.TITLE_TAP_W, CardSpec.TITLE_TAP_H, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.CARD_LEFT;
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
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.OPEN_BOX_W, CardSpec.OPEN_BOX_H, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.openBoxLeft();
        lp.y = CardSpec.openBoxTop();
        lp.setTitle("微读墨记·打开");
        return lp;
    }

    /**
     * 左下角「上一条」按钮的触摸区（只在**本记**形态出现，v0.4.2）。
     *
     * 与 {@link #paramsOpenTouch} 完全对称：同一水平线、同一尺寸，只是贴左边。
     * 位置由 {@link CardSpec#prevBoxLeft()} / {@link CardSpec#prevBoxTop()} 给，
     * 与 {@link WeekCardView} 画出来的框**严格对齐**（两边共用同一组常量）。
     */
    public static WindowManager.LayoutParams paramsPrevTouch(int type) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.OPEN_BOX_W, CardSpec.OPEN_BOX_H, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.prevBoxLeft();
        lp.y = CardSpec.prevBoxTop();
        lp.setTitle("微读墨记·上一条");
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
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CardSpec.cardWidth(), CardSpec.cardHeight(), type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = CardSpec.CARD_LEFT;
        lp.y = CardSpec.CARD_TOP;
        lp.setTitle("微读墨记·菜单");
        return lp;
    }
}
