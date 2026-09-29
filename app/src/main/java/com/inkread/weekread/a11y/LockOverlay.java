package com.inkread.weekread.a11y;

import com.inkread.weekread.R;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.LockPrefs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.InputStream;

/**
 * 应用级「软锁」的全屏覆盖窗（TASK-022，V1.0.1-beta，方案乙）。
 *
 * <p>为什么<b>不是</b>一个独立的 {@code LockScreenActivity}（那是方案甲，已否）：<br>
 * Android 10（API 29）起<b>后台应用不允许启动 Activity</b>
 * （{@code ActivityStarter.shouldAbortBackgroundActivityStart()}），
 * 豁免清单里<b>没有</b>「无障碍服务」这一条 —— 无障碍服务由<b>系统</b>绑定，
 * 不满足"被一个<b>可见</b>应用绑定"。而这里做的是"亮屏后台弹锁屏"，
 * 正好踩在这条限制上（除非加 {@code SYSTEM_ALERT_WINDOW} 或常驻前台服务，
 * 两条都是本项目<b>刻意不要</b>的红线）。<br>
 * 走 {@code TYPE_ACCESSIBILITY_OVERLAY} 则完全不涉及 Activity 启动：
 * 服务已经在跑、窗口随时可加，<b>零新增权限、零新增服务</b>，与桌面卡片同一套基建。
 *
 * <p>⚠️ 如实边界（不吹）：
 * <ul>
 *   <li><b>可绕过</b>：Home / 返回键能退出 —— 本锁定位 = 防误触 / 防好奇，不是防破解；</li>
 *   <li><b>重启失效</b>：进程没起来就不会锁；</li>
 *   <li><b>依赖卡片无障碍服务</b>：该服务没开 ⇒ 锁屏不生效（文案已写明）；</li>
 *   <li><b>系统状态栏可能仍压在顶上</b>：覆盖窗盖不掉系统栏（后续可单独优化）。</li>
 * </ul>
 *
 * <p>窗口<b>不</b>进入 {@link OverlayController} 的管辖 —— 那边"只有它写 setVisibility"
 * 是**卡片**的纪律；本类是独立的第二个窗口（全屏、可触摸、自己显自己隐），
 * 互不干扰：卡片窗口带 {@code FLAG_NOT_TOUCHABLE}，锁屏窗口不透明且盖在它上面。
 */
final class LockOverlay {

    private final Context ctx;
    private final Handler ui;
    private WindowManager wm;
    private LockView view;
    private boolean added = false;

    LockOverlay(Context ctx, Handler ui) {
        this.ctx = ctx;
        this.ui = ui;
    }

    /** 锁屏层此刻是否挂在屏幕上（供排查用）。 */
    boolean isShowing() {
        return added;
    }

    /** 亮屏时调用：把锁屏层加进 WindowManager（已在屏幕上则什么都不做）。 */
    void show() {
        if (added) return;
        try {
            if (wm == null) wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (view == null) view = new LockView(ctx);
            view.reset();
            wm.addView(view, params());
            added = true;
            CardDebug.note(ctx, "锁屏显示（应用级软锁 · 可绕过/重启失效）");
        } catch (Throwable t) {
            view = null;
            added = false;
            CardDebug.note(ctx, "锁屏显示失败：" + t);
        }
    }

    /** 输对密码 / 服务销毁时调用：把锁屏层摘掉。 */
    void hide() {
        if (!added) return;
        try {
            if (wm != null && view != null) wm.removeView(view);
        } catch (Throwable ignored) {
        }
        added = false;
        CardDebug.note(ctx, "锁屏解除");
    }

    /** 服务 unbind / destroy：摘窗口并放掉视图引用。 */
    void remove() {
        hide();
        view = null;
    }

    private WindowManager.LayoutParams params() {
        // 全屏、可触摸（**故意不加** FLAG_NOT_TOUCHABLE —— 锁屏就是要吃掉触摸），
        // 但保留 FLAG_NOT_FOCUSABLE：不抢输入法焦点、不吃按键（Home/返回 仍可绕过 = 符合软锁定位）。
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags, PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.setTitle("微读墨记·锁屏");
        return lp;
    }

    // ══════════════════════ 锁屏内容（纯代码绘制） ══════════════════════
    //
    // 为什么不用 XML：只有"背景 + 4 个点 + 12 个键"，纯 Canvas 画在墨水屏上比
    // 主题化的 Button 更可控（无点击涟漪、无圆角阴影、一次刷新一帧），也省掉一个布局文件。

    private final class LockView extends View {

        /** 键盘布局：3×4。索引 9 = 空格位，索引 11 = 删除。 */
        private static final int KEY_BLANK = 9;
        private static final int KEY_DEL = 11;
        private static final String DELETE_LABEL = "删除";

        private final String[] keys = {
                "1", "2", "3",
                "4", "5", "6",
                "7", "8", "9",
                "", "0", "del"
        };

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final StringBuilder input = new StringBuilder();
        private String error = null;
        /** 抖动帧计数：0 = 不抖；>0 = 正在抖，抖完清空输入 */
        private int shake = 0;

        /** 自选背景位图缓存（随 {@code lock_bg_uri} 变化重载） */
        private Bitmap bg;
        private String bgKey = null;

        // 键盘几何（onDraw 与命中测试共用同一套，避免"画在这儿、点在那儿"）
        private float padLeft, padTop, padW, padH, cellW, cellH, keyR;

        LockView(Context c) {
            super(c);
            setBackgroundColor(0xFFFFFFFF);
        }

        /** 每次弹出都从干净状态开始（上一次输错的红字 / 抖动不要留到下一次）。 */
        void reset() {
            input.setLength(0);
            error = null;
            shake = 0;
            removeCallbacks(shakeTick);
            invalidate();
        }

        private final Runnable shakeTick = new Runnable() {
            @Override
            public void run() {
                if (shake == 0) return;
                shake++;
                if (shake > 9) {
                    shake = 0;
                    input.setLength(0);      // 抖完清空，让用户重新输（P5：无限重试，不锁定）
                }
                invalidate();
                if (shake > 0) postDelayed(this, 40);
            }
        };

        private void startShake() {
            shake = 1;
            removeCallbacks(shakeTick);
            postDelayed(shakeTick, 30);
        }

        private void layoutPad(float w, float h) {
            padW = w * 0.80f;
            padH = h * 0.40f;
            padLeft = (w - padW) / 2f;
            padTop = h * 0.50f;
            cellW = padW / 3f;
            cellH = padH / 4f;
            keyR = Math.min(cellW, cellH) * 0.36f;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            layoutPad(w, h);
            drawBackground(c, w, h);
            drawTitle(c, w, h);
            drawDots(c, w, h);
            drawError(c, w, h);
            drawKeypad(c, w, h);
        }

        private void drawBackground(Canvas c, float w, float h) {
            String uri = LockPrefs.getBgUri(getContext());
            if (bgKey == null || !bgKey.equals(uri)) {
                bgKey = uri;
                bg = (uri.length() == 0) ? null : decode(uri, (int) w, (int) h);
            }
            if (bg == null) {
                // 默认：浅色底 + 细网点（墨水屏上最干净、层次清楚）
                c.drawColor(0xFFFFFFFF);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFECECEC);
                float step = h * 0.03f;
                for (float y = step; y < h; y += step) {
                    for (float x = step; x < w; x += step) {
                        c.drawCircle(x, y, 1.2f, p);
                    }
                }
                return;
            }
            // 自选图片：centerCrop 铺满 + 半透明遮罩（保证 4 个点与键盘清晰可读）
            float sc = Math.max(w / bg.getWidth(), h / bg.getHeight());
            float dw = bg.getWidth() * sc, dh = bg.getHeight() * sc;
            float left = (w - dw) / 2f, top = (h - dh) / 2f;
            c.drawBitmap(bg, null, new RectF(left, top, left + dw, top + dh), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xB3FFFFFF);
            c.drawRect(0, 0, w, h, p);
        }

        private Bitmap decode(String uriStr, int w, int h) {
            try {
                Uri u = Uri.parse(uriStr);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                InputStream in = getContext().getContentResolver().openInputStream(u);
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
                in.close();
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) {
                    sample *= 2;
                }
                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inSampleSize = sample;
                InputStream in2 = getContext().getContentResolver().openInputStream(u);
                if (in2 == null) return null;
                Bitmap bm = BitmapFactory.decodeStream(in2, null, opt);
                in2.close();
                return bm;
            } catch (Throwable t) {
                return null;                  // 图片读不出来就退回默认底，绝不因此弹不出锁屏
            }
        }

        private void drawTitle(Canvas c, float w, float h) {
            p.setStyle(Paint.Style.FILL);
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(h * 0.030f);
            p.setColor(0xFF000000);
            c.drawText(getContext().getString(R.string.lock_screen_prompt), w / 2f, h * 0.20f, p);
            p.setFakeBoldText(false);
        }

        private void drawDots(Canvas c, float w, float h) {
            float r = h * 0.014f;
            float step = r * 4.4f;
            float total = step * (LockPrefs.PIN_LENGTH - 1) + r * 2f;
            float x0 = (w - total) / 2f + r;
            float cy = h * 0.32f;
            float dx = (shake > 0) ? (float) Math.sin(shake * Math.PI / 3d) * (h * 0.018f) : 0f;

            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < LockPrefs.PIN_LENGTH; i++) {
                float x = x0 + i * step + dx;
                if (i < input.length()) {
                    p.setColor(0xFF000000);
                    c.drawCircle(x, cy, r, p);
                } else {
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(x, cy, r, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.5f);
                    p.setColor(0xFF000000);
                    c.drawCircle(x, cy, r, p);
                    p.setStyle(Paint.Style.FILL);
                }
            }
        }

        private void drawError(Canvas c, float w, float h) {
            if (error == null) return;
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(h * 0.024f);
            p.setColor(0xFFCC0000);
            c.drawText(error, w / 2f, h * 0.415f, p);
        }

        private void drawKeypad(Canvas c, float w, float h) {
            p.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < keys.length; i++) {
                if (i == KEY_BLANK) continue;
                int row = i / 3, col = i % 3;
                float cx = padLeft + cellW * col + cellW / 2f;
                float cy = padTop + cellH * row + cellH / 2f;

                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, keyR, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f);
                p.setColor(0xFF000000);
                c.drawCircle(cx, cy, keyR, p);

                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF000000);
                boolean del = (i == KEY_DEL);
                p.setTextSize(del ? keyR * 0.58f : keyR * 0.95f);
                Paint.FontMetrics fm = p.getFontMetrics();
                float baseline = cy - (fm.ascent + fm.descent) / 2f;
                c.drawText(del ? DELETE_LABEL : keys[i], cx, baseline, p);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return true;
            layoutPad(w, h);
            if (e.getX() < padLeft || e.getX() > padLeft + padW
                    || e.getY() < padTop || e.getY() > padTop + padH) {
                return true;
            }
            int col = (int) ((e.getX() - padLeft) / cellW);
            int row = (int) ((e.getY() - padTop) / cellH);
            if (col < 0) col = 0;
            if (col > 2) col = 2;
            if (row < 0) row = 0;
            if (row > 3) row = 3;
            onKeyPress(row * 3 + col);
            return true;
        }

        private void onKeyPress(int idx) {
            if (shake > 0) return;                       // 抖动动画期间不接受输入
            if (idx == KEY_BLANK) return;

            if (idx == KEY_DEL) {
                if (input.length() > 0) input.setLength(input.length() - 1);
                error = null;
                invalidate();
                return;
            }
            if (input.length() >= LockPrefs.PIN_LENGTH) return;

            input.append(keys[idx]);
            error = null;

            if (input.length() == LockPrefs.PIN_LENGTH) {
                if (LockPrefs.verifyPin(getContext(), input.toString())) {
                    CardDebug.note(getContext(), "锁屏：密码正确 → 解锁");
                    // 用主线程 post 摘窗口：别在触摸事件派发的当口把本视图从 WindowManager 拆掉
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hide();
                        }
                    });
                } else {
                    error = getContext().getString(R.string.lock_screen_wrong);
                    CardDebug.note(getContext(), "锁屏：密码错误（可无限重试）");
                    startShake();
                }
            }
            invalidate();
        }
    }
}
