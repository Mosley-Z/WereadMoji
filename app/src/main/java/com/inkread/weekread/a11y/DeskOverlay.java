package com.inkread.weekread.a11y;

import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.NavExtra;
import com.inkread.weekread.feature.DeskPageView;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * 🆕 TASK-075：**墨台**的全屏覆盖窗（**第三个独立窗**，非 Activity）。
 *
 * <p>照 {@link LockOverlay} 的范式（全屏、可触摸、自己显自己隐）。窗口纪律（定稿设计 §2.1）：
 * <ul>
 *   <li>🔴 墨台窗**不并入** {@link OverlayController} 的卡片管辖 —— 三窗（卡片 / 软锁 / 墨台）各自
 *       add/remove，互不干扰；</li>
 *   <li>🔴 墨台显示时属"自家 UI 前台" ⇒ **必须**调 {@link CardA11yService#noteOwnUiForeground}；
 *       本类在 {@link #show()} **保存**呼出前的状态、{@link #hide()} **还原**（否则从桌面呼出后
 *       关闭会把"自家 UI 在前台"错误地按下去，或在自家页面里呼出后关闭时把卡片错误地放回来）。</li>
 * </ul>
 *
 * <p>🔴 走 {@code TYPE_ACCESSIBILITY_OVERLAY}：与卡片/软锁同一套基建，**零新增权限、零新增服务**。
 * 与服务同进程 ⇒ 直接调 {@link CardA11yService} 的包内静态口。
 */
final class DeskOverlay {

    private final Context ctx;
    private final Handler ui;
    private WindowManager wm;
    private DeskPageView view;
    private boolean added = false;

    /** 呼出前"自家 UI 是否在前台" —— 关闭时还原（见类注释）。 */
    private boolean prevOwnUi = false;

    DeskOverlay(Context ctx, Handler ui) {
        this.ctx = ctx;
        this.ui = ui;
    }

    /** 墨台层此刻是否挂在屏幕上（供排查 / 自测断言）。 */
    boolean isShowing() {
        return added;
    }

    /** 呼出：把墨台层加进 WindowManager（已在屏上则**原地刷新**一次内容）。 */
    void show() {
        if (added) {
            // 🔴 已在屏上 ⇒ 只重建内容（幂等：自测可重复驱动 `am start`；生产无副作用）。
            //    绝不重跑下面的 prevOwnUi 保存 —— 此时 ownUi 已是 true，重存会把"呼出前状态"写坏。
            if (view != null) view.reset();
            return;
        }
        // 🔴 互斥（定稿设计 §8.3）：软锁正显示 ⇒ 墨台不叠加（判定与 LockOverlay 侧同源）。
        if (CardA11yService.lockShowing()) {
            CardDebug.note(ctx, "墨台呼出被拒：软锁正在显示（互斥）");
            return;
        }
        try {
            if (wm == null) wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (view == null) {
                view = new DeskPageView(ctx);
                view.setListener(new DeskPageView.Listener() {
                    @Override
                    public void onDeskClose() {
                        // 用主线程 post 摘窗口：别在触摸事件派发的当口把本视图从 WindowManager 拆掉
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                hide();
                            }
                        });
                    }

                    /**
                     * 🆕 TASK-079：「壁纸管家 → 管理」的宿主落点 ——
                     * **隐藏墨台 + 打开实验室「壁纸管家」子标签**（定稿设计 §5.5）。
                     *
                     * <p>用主线程 post：别在触摸事件派发的当口把本视图从 WindowManager 拆掉。
                     */
                    @Override
                    public void onWallManage() {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                CardDebug.note(ctx, "墨台：点「管理」→ 深链实验室·壁纸管家");
                                hide();
                                openWallLab();
                            }
                        });
                    }
                });
            }
            view.reset();
            wm.addView(view, params());
            added = true;
            prevOwnUi = CardA11yService.ownUiForeground();
            CardA11yService.noteOwnUiForeground(true);
            CardDebug.note(ctx, "墨台显示（第三覆盖窗）");
        } catch (Throwable t) {
            view = null;
            added = false;
            CardDebug.note(ctx, "墨台显示失败：" + t);
        }
    }

    /** 关闭：摘掉墨台层，并还原呼出前的"自家 UI 前台"状态。 */
    void hide() {
        if (!added) return;
        try {
            if (wm != null && view != null) wm.removeView(view);
        } catch (Throwable ignored) {
        }
        added = false;
        CardA11yService.noteOwnUiForeground(prevOwnUi);
        // 🔴 覆盖层被移除**不会**产生桌面窗口事件 ⇒ 从桌面呼出再关闭时，必须显式重算一次，
        //    否则卡片会卡在"让位"状态不回来。
        if (!prevOwnUi) CardA11yService.recomputeVisibilityNow();
        CardDebug.note(ctx, "墨台关闭");
    }

    /** 服务 unbind / destroy：摘窗口并放掉视图引用。 */
    void remove() {
        hide();
        view = null;
    }

    /**
     * 🆕 TASK-079：「管理」⇒ 打开实验室「壁纸管家」子标签（定稿设计 §5.5）。
     *
     * <p>🔴 跨包纪律：本类在 {@code a11y} 包，宿主 Activity 在 {@code shell} 包
     * （{@code a11y} **不依赖** {@code shell}）⇒ 用**类名字符串**建 Intent、键名走
     * {@link NavExtra}，两处都不 import {@code shell}。
     *
     * <p>🔴 启动 Act. 的背景限制：墨台窗是**可见窗口**（{@code TYPE_ACCESSIBILITY_OVERLAY}）
     * ⇒ 命中「有可见窗口」豁免；真机实测见 {@code 验证记录/192}（若被系统拦下，
     * 现象 = 点了「管理」墨台消失但没跳转 ⇒ 有留痕可查）。
     */
    private void openWallLab() {
        try {
            Intent it = new Intent();
            it.setClassName(ctx, "com.inkread.weekread.shell.MainActivity");
            it.putExtra(NavExtra.LAB_SUB, NavExtra.LAB_SUB_WALLPAPER);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(it);
        } catch (Throwable t) {
            CardDebug.note(ctx, "墨台：深链实验室·壁纸管家失败 " + t);
        }
    }

    private WindowManager.LayoutParams params() {
        // 全屏、可触摸（**故意不加** FLAG_NOT_TOUCHABLE —— 墨台是模态页，要吃掉触摸，
        // 否则横滑会穿透到桌面）；保留 FLAG_NOT_FOCUSABLE：不抢输入法焦点、不吃按键。
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags, PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.setTitle("微读墨记·墨台");
        return lp;
    }
}
