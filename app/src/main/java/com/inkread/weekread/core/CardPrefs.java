package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

/** 桌面卡片的用户偏好。只剩一件事可配：卡片开不开。 */
public final class CardPrefs {

    /** 默认开：装好、配好 Key 就该看见卡片，别让用户再去别处找开关 */
    public static final boolean DEFAULT_ENABLED = true;

    private CardPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean("card_enabled", DEFAULT_ENABLED);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("card_enabled", v).commit();
    }
}
