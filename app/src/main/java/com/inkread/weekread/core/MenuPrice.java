package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 🆕 `TASK-086` Q7：**手动录入的书价**（独立 prefs 文件 `menu_prices`）。
 *
 * <h3>为什么单独一个 prefs 文件、且键 = `bookId`</h3>
 * <ul>
 *   <li>**键 = `bookId`**（🔴 绝不按 `NO.01~NO.05` 序号存）—— 借 ReadTrace 自认的反面教材：
 *       它"按位置存会错位"，一改 Top-N 顺序就张冠李戴（`tasks/TASK-077` §配置 #9 红线）。</li>
 *   <li>**独立文件**（不复用 `cfg`）：手动价是一份"用户资产"，与配置项的生命周期不同 ——
 *       清配置不该顺手把录进来的价清掉。</li>
 * </ul>
 *
 * <h3>为什么必须"优先级最高"</h3>
 * 自导入书 / 网文在微信读书系统内**本来就没有定价**，接口永远给不出价。用户手填的那一笔
 * 是**唯一真相** ⇒ 刷新 / 重同步**一律不覆盖**（借 huibenlema 的 `PriceSource.MANUAL` 口径）。
 *
 * <p>🔴 存的是**分**（int）—— 与 {@code Bill.Item.marketPriceFen}、{@code BillMoney} 同一单位，
 * 避免"元/分/float"三套单位在渲染期互相换算出错（huibenlema 那边就是统一成 `Fen` 的）。
 */
public final class MenuPrice {

    private static final String PREFS = "menu_prices";

    private MenuPrice() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 取手动录入价（**分**）。
     *
     * @return {@code >= 0} = 已录入（`0` 表示用户明确标为免费）；{@code -1} = 没录过
     */
    public static int fen(Context c, String bookId) {
        if (c == null || bookId == null || bookId.length() == 0) return -1;
        try {
            return sp(c).getInt(bookId, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 录一笔价（分）；{@code fen < 0} ⇒ 删除该项。 */
    public static void set(Context c, String bookId, int fen) {
        if (c == null || bookId == null || bookId.length() == 0) return;
        SharedPreferences.Editor ed = sp(c).edit();
        if (fen < 0) ed.remove(bookId);
        else ed.putInt(bookId, fen);
        ed.commit();
    }

    public static boolean has(Context c, String bookId) {
        return fen(c, bookId) >= 0;
    }

    /** 已录入几本（设置页 / 验证留痕用）。 */
    public static int count(Context c) {
        if (c == null) return 0;
        try {
            return sp(c).getAll().size();
        } catch (Throwable t) {
            return 0;
        }
    }
}
