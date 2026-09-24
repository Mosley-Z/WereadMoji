package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地缓存：API Key + **按周期分键**的统计数据 + 卡片周期偏好。
 *
 * ⚠️ 为什么必须**分键**（设计方案 §4.3）：原来只有一个 `weekly_json`。
 * 一旦本月功能上线，"切到上月"会把"本周"那份缓存**覆盖掉** ——
 * 之后离线打开卡片就会显示错数据，而且用户完全看不出来。
 * 所以键一律是 `mode + ":" + periodStart`。
 *
 * 容量：最多留 {@link #CACHE_MAX} 个周期（按 ⑦：本周/本月 + 各自上 2 个）。
 * 超出时**优先淘汰非核心周期**（用户用选择器翻出来的历史周期），
 * 核心那 6 个是卡片离线可用的底线，宁可暂时超一点也不能删。
 */
public class StatsStore {

    private static final String PREFS = "cfg";

    private static final String K_API = "api_key";
    private static final String K_CARD_PERIOD = "card_period";
    private static final String K_ORDER = "cache_order";

    /** 最多缓存多少个周期（⑦：本周/本月 + 各自上 2 个 = 6） */
    private static final int CACHE_MAX = 6;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── API Key ──

    public static String getKey(Context c) {
        return sp(c).getString(K_API, "");
    }

    /**
     * 会话代次：**每次换 Key 自增**（v0.5.3，R05）。
     *
     * 异步请求在发起前记下当时的代次，回调里比对 —— 不等就说明"这是上一把 Key 的迟到结果"，
     * 直接丢弃，绝不让它写进新会话的缓存（严格实现见各调用点）。
     */
    private static volatile long sGen;

    public static long keyGen() {
        return sGen;
    }

    /**
     * 保存 API Key —— **Key 一变就统一失效全部个人数据缓存**（v0.5.3，R05）。
     *
     * 上一版的洞：这里只写了一个字段。而周/月统计、书架、书籍进度、章节目录、划线、想法、
     * 每日一签**都不按账号区分**：从账号 A 换成 B 之后，屏幕上仍是 A 的书与统计，
     * 而且 A 的缓存还会让 B 的请求"跳过预热"（以为已经拉过了）。更隐蔽的一层是抽取状态
     * （今日一签的日期键 / 序号 / 当前条 / 历史栈 / 两个批次文件）—— 不清的话，
     * 新账号第一天就会看到旧账号的"今日一签"，第二天才自愈。
     *
     * 不动的东西：卡片周期偏好、无障碍开关、更新器状态（这些跟账号无关）。
     *
     * @return true = Key 确实变了（已清缓存）；false = Key 与原来相同（什么都不做）
     */
    public static boolean setKey(Context c, String k) {
        String v = k == null ? "" : k.trim();
        String old = getKey(c);
        sp(c).edit().putString(K_API, v).commit();
        if (v.equals(old)) return false;
        sGen++;                                     // 作废在途请求的回写（见 keyGen）
        if (old.length() > 0) wipePersonalData(c);   // 原来是空的（首次填 Key）→ 本来就没数据
        return true;
    }

    /**
     * 失效**全部个人数据缓存**：周期统计 + 书架/进度/章节目录 + 划线/想法/抽取状态。
     *
     * 调用方只有 {@link #setKey}。`StatsStore.clearCache` 与 `NoteStore.clear` 早就写好了，
     * 但 v0.5.2 之前**全仓库没有任何地方调用过它们** —— 这就是 R05 的根因。
     */
    private static void wipePersonalData(Context c) {
        clearCache(c);
        NoteStore.clear(c);
        BookStore.clear(c);
    }

    // ── 卡片周期偏好（① B：设置页定默认，卡片左上角临时切换，改的是同一个值）──

    /**
     * 卡片显示哪个形态：weekly / monthly / **book**（v0.3.4 起三态）。
     *
     * 只认这三个字面量，其它一律回落 weekly —— 老版本写进去的值、手改的脏值都不会炸。
     */
    public static String getCardPeriod(Context c) {
        String v = sp(c).getString(K_CARD_PERIOD, PeriodRange.WEEKLY);
        if (PeriodRange.MONTHLY.equals(v)) return PeriodRange.MONTHLY;
        if (PeriodRange.BOOK.equals(v)) return PeriodRange.BOOK;
        if (PeriodRange.NOTE.equals(v)) return PeriodRange.NOTE;
        return PeriodRange.WEEKLY;
    }

    public static void setCardPeriod(Context c, String mode) {
        String v;
        if (PeriodRange.MONTHLY.equals(mode)) v = PeriodRange.MONTHLY;
        else if (PeriodRange.BOOK.equals(mode)) v = PeriodRange.BOOK;
        else if (PeriodRange.NOTE.equals(mode)) v = PeriodRange.NOTE;
        else v = PeriodRange.WEEKLY;
        sp(c).edit().putString(K_CARD_PERIOD, v).commit();
    }

    /**
     * 切到下一个形态：**本周 → 本月 → 本书 → 本周**（卡片左上角点按）。
     *
     * 三态循环而不是"两两互切"：三个形态地位对等，用户想看哪个都能一下点到，
     * 且不用记住当前在哪一态。
     */
    public static String toggleCardPeriod(Context c) {
        String now = getCardPeriod(c);
        String next;
        if (PeriodRange.WEEKLY.equals(now)) next = PeriodRange.MONTHLY;
        else if (PeriodRange.MONTHLY.equals(now)) next = PeriodRange.BOOK;
        else if (PeriodRange.BOOK.equals(now)) next = PeriodRange.NOTE;
        else next = PeriodRange.WEEKLY;
        setCardPeriod(c, next);
        return next;
    }

    /**
     * 卡片当前锚定的周期起点。
     *
     * 卡片**不提供历史周期**（④ + 拍板 F：选择器只放 App 内），
     * 所以锚点恒等于"当前周期的起点" —— 跨过周一/月初时它会自己跟上，
     * 不需要额外的 anchor 字段（设计方案里那个 card_anchor 因此不需要了）。
     */
    public static long cardPeriodStart(Context c) {
        String m = getCardPeriod(c);
        // 「本书」没有周期概念，这里按周兜底（调用方都只用它算周/月缓存键）
        if (PeriodRange.BOOK.equals(m)) m = PeriodRange.WEEKLY;
        return PeriodRange.startOf(m, 0);
    }

    // ── 统计缓存 ──

    private static String dataKey(String mode, long start) {
        return "d_" + mode + ":" + start;
    }

    private static String timeKey(String mode, long start) {
        return "t_" + mode + ":" + start;
    }

    /** 读某个周期的缓存；没有则 null（**注意：null 不等于"没读过"**，可能只是还没拉过） */
    public static PeriodStats load(Context c, String mode, long periodStart) {
        String raw = sp(c).getString(dataKey(mode, periodStart), null);
        if (raw == null || raw.length() == 0) return null;
        try {
            return PeriodStats.parse(new JSONObject(raw),
                    sp(c).getLong(timeKey(mode, periodStart), 0), mode, periodStart);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读"当前周期"的缓存（模式给全，起点自己算） */
    public static PeriodStats loadCurrent(Context c, String mode) {
        return load(c, mode, PeriodRange.startOf(mode, 0));
    }

    /**
     * 读卡片当前该显示的那份缓存。
     *
     * 「本书」形态**恒返回 null** —— 它的数据在 {@link BookStore} 里，不在周期缓存里。
     * 调用方（{@link CardA11yService}）按形态分别取，别指望这里能一肩挑。
     */
    public static PeriodStats loadCard(Context c) {
        String mode = getCardPeriod(c);
        if (PeriodRange.BOOK.equals(mode)) return null;
        return load(c, mode, PeriodRange.startOf(mode, 0));
    }

    public static void save(Context c, PeriodStats s) {
        if (s == null || s.baseTime <= 0) return;
        sp(c).edit()
                .putString(dataKey(s.mode, s.baseTime), s.rawJson == null ? "" : s.rawJson)
                .putLong(timeKey(s.mode, s.baseTime), s.fetchedAt)
                .commit();
        touch(c, s.mode, s.baseTime);
    }

    // ── 顺序表 + 淘汰 ──

    private static List<String> readOrder(SharedPreferences p) {
        List<String> out = new ArrayList<String>();
        String s = p.getString(K_ORDER, null);
        if (s == null) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                String k = a.optString(i, null);
                if (k != null && k.length() > 0) out.add(k);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 把刚写过的那个周期挪到最前（最近使用） */
    private static void touch(Context c, String mode, long start) {
        SharedPreferences p = sp(c);
        String me = dataKey(mode, start);
        List<String> order = readOrder(p);
        order.remove(me);
        order.add(0, me);
        prune(p, order);
        JSONArray a = new JSONArray();
        for (String s : order) a.put(s);
        p.edit().putString(K_ORDER, a.toString()).commit();
    }

    private static void prune(SharedPreferences p, List<String> order) {
        if (order.size() <= CACHE_MAX) return;
        List<String> core = coreKeys();
        for (int i = order.size() - 1; i >= 0 && order.size() > CACHE_MAX; i--) {
            if (!core.contains(order.get(i))) {
                erase(p, order.remove(i));
            }
        }
        while (order.size() > CACHE_MAX) {
            erase(p, order.remove(order.size() - 1));
        }
    }

    /** 卡片离线要用的那 6 个周期（本周/上1周/上2周 + 本月/上1月/上2月） */
    private static List<String> coreKeys() {
        List<String> l = new ArrayList<String>();
        long w = PeriodRange.startOf(PeriodRange.WEEKLY, 0);
        long m = PeriodRange.startOf(PeriodRange.MONTHLY, 0);
        for (int i = 0; i < 3; i++) {
            l.add(dataKey(PeriodRange.WEEKLY, PeriodRange.shift(PeriodRange.WEEKLY, w, -i)));
            l.add(dataKey(PeriodRange.MONTHLY, PeriodRange.shift(PeriodRange.MONTHLY, m, -i)));
        }
        return l;
    }

    /** 键长这样：`d_weekly:1756656000` → 顺手把配套的 `t_…` 也删掉 */
    private static void erase(SharedPreferences p, String dKey) {
        String t = "t_" + dKey.substring(2);
        p.edit().remove(dKey).remove(t).commit();
    }

    /** 清掉所有周期缓存（换 Key 时用得到） */
    public static void clearCache(Context c) {
        SharedPreferences p = sp(c);
        for (String k : readOrder(p)) erase(p, k);
        p.edit().remove(K_ORDER).commit();
    }
}
