package com.inkread.weekread.remote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;

/**
 * 注入层：{@code dispatchGesture} 左右滑让墨水屏微信读书翻页。
 *
 * 参数来自 Step 0-a 上机实测（`_probe/a11y_gestureprobe/EVIDENCE.md`）：
 * **420↔60 / y=400 / 200ms**，正反向可逆（右滑后与基线逐像素一致）。
 *
 * 失败兜底（规格备选）：performAction(ACTION_SCROLL_FORWARD/BACKWARD) 打到
 * 微信读书的 RecyclerView —— 仅当上机发现 dispatchGesture 不可靠时再启用，本版不预写。
 */
public final class RemoteInjector {

    private static final String TAG = "RemoteInjector";
    private static final float SWIPE_Y = 400f;
    private static final float X_LEFT = 60f;
    private static final float X_RIGHT = 420f;
    private static final long SWIPE_MS = 200;

    private RemoteInjector() {
    }

    /**
     * 把指令翻译成翻页手势并在主线程派发。
     *
     * @return 指令被接受派发返回 true；非翻页指令（BYE 等）或系统拒绝返回 false。
     */
    public static boolean inject(AccessibilityService svc, int cmd) {
        final boolean isNext;
        if (cmd == RemoteProtocol.CMD_PAGE_NEXT) {
            isNext = true;    // 左滑 = 下一页
        } else if (cmd == RemoteProtocol.CMD_PAGE_PREV) {
            isNext = false;   // 右滑 = 上一页
        } else {
            return false;
        }
        Path path = new Path();
        path.moveTo(isNext ? X_RIGHT : X_LEFT, SWIPE_Y);
        path.lineTo(isNext ? X_LEFT : X_RIGHT, SWIPE_Y);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
                .build();
        boolean accepted = svc.dispatchGesture(gd, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.i(TAG, "inject COMPLETED next=" + isNext);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "inject CANCELLED next=" + isNext);
            }
        }, null);
        Log.i(TAG, "inject next=" + isNext + " accepted=" + accepted);
        return accepted;
    }
}
