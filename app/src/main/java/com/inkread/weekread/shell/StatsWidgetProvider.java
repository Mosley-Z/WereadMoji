package com.inkread.weekread.shell;

import com.inkread.weekread.R;
import com.inkread.weekread.core.PeriodStats;
import com.inkread.weekread.core.StatsStore;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

/**
 * 标准 AppWidget（供支持小组件的设备使用）。
 *
 * ⚠️ **本机（阅星曈 S4）用不上，别再投入**：两个桌面（ELauncher / Tomo）的 APK 里
 * 都没有 appwidget 宿主，`dumpsys appwidget` 的 `Hosts:` 为空 —— 也就是系统里
 * 根本没有能承载标准小组件的桌面。这个类只做最小维护（跟着 StatsStore 的签名改），
 * 不要在上面加功能。详见 HANDOFF §21.8 / MEMORY.md。
 */
public class StatsWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        PeriodStats s = StatsStore.loadCard(context);
        for (int id : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_stats);
            views.setTextViewText(R.id.w_main, s == null ? "未配置"
                    : (s.isMonthly() ? "本月 " : "本周 ")
                      + (s.totalSec / 3600) + "小时" + ((s.totalSec % 3600) / 60) + "分");
            appWidgetManager.updateAppWidget(id, views);
        }
    }
}
