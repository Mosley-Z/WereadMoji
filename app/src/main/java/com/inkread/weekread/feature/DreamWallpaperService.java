package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.View;

import com.inkread.weekread.core.BgImageUtil;
import com.inkread.weekread.core.WallpaperPrefs;

/**
 * 🆕 TASK-081：**Daydream 屏保**（第三方 {@code DreamService}）—— 把「壁纸池」当屏保轮换呈现。
 *
 * <p>由系统在进入屏保时绑定并托管（宿主 = {@code android.service.dreams.DreamActivity}）。
 * 声明见 {@code AndroidManifest.xml}（{@code permission=BIND_DREAM_SERVICE}，
 * 🔴 <b>不需要任何 uses-permission</b>）。
 *
 * <h3>🔴 关键设计</h3>
 * <ol>
 *   <li><b>零业务逻辑、零后台</b>：只画「池里当前那张」。取图走
 *       {@link WallpaperPrefs#effectiveDreamPath} —— 与软锁侧**共用同一个**幂等轮换引擎，
 *       本类不自己判日期、不自己推序号。</li>
 *   <li><b>不碰无障碍</b>：本类是普通 {@code DreamService}，与卡片窗 / 软锁窗 / 墨台窗
 *       三者都无交集（`TASK-081` A5）。</li>
 *   <li><b>池空 = 纯白</b>：墨水屏铁律（纯黑白 + 无渐变/圆角），不画任何装饰。</li>
 *   <li><b>可触摸关闭</b>：{@code setInteractive(false)} ⇒ 系统默认"一碰就退出屏保"，
 *       用户不会被锁在屏保里出不来。</li>
 * </ol>
 *
 * <p>⚠️ 触发条件（S0 实测结论，见 `验证记录/194`）：本 ROM 屏保**只在屏幕「自然超时熄灭」时**
 * 自动进入；按电源/休眠键**不会**进屏保。文案必须照此如实写。
 */
public class DreamWallpaperService extends DreamService {

    private static final String TAG = "DreamWall081";

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(new Screen(this));
        Log.i(TAG, "onAttachedToWindow");
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        Log.i(TAG, "onDreamingStarted path=" + WallpaperPrefs.effectiveDreamPath(this));
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        Log.i(TAG, "onDreamingStopped");
    }

    /** 全屏绘制：纯白底 + 池中当前那张（`centerCrop`）。按路径缓存解码结果，避免每帧重解。 */
    private static class Screen extends View {

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private String bgKey = null;
        private Bitmap bg = null;

        Screen(Context c) {
            super(c);
            setBackgroundColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            String key = WallpaperPrefs.effectiveDreamPath(getContext());
            if (key == null || key.length() == 0) {
                // 池空 / 范围不含屏保 ⇒ 纯白（墨水屏最省刷新）
                bgKey = null;
                bg = null;
                return;
            }
            if (bgKey == null || !bgKey.equals(key)) {
                bgKey = key;
                bg = BgImageUtil.load(getContext(), key, w, h);
            }
            if (bg != null && !bg.isRecycled()) {
                BgImageUtil.drawCenterCrop(c, bg, w, h, p);
            }
        }
    }
}
