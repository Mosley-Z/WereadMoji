package com.inkread.weekread.core;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 🆕 TASK-059：「**书籍排名只统计书架上的书**」的**唯一过滤口径** ——
 * 本月页（K9）与洞察页「读书排行」（K8）共用同一处实现，避免两处走偏。
 *
 * <p>三档判定（🔴 顺序即语义，别调换）：
 * <ol>
 *   <li><b>开关关闭</b>（默认）⇒ **原样返回**（连列表对象都不换 ⇒ 渲染层零差异）；</li>
 *   <li><b>没有全量书架清单</b>（文件缺失 / 读失败）⇒ 也**原样返回** ——
 *       此时过滤只会把整页清空（用户以为"我的书全没了"）；正确做法是回退"不过滤"，
 *       由 {@code MainActivity} 在后台**补拉一次** `/shelf/sync` 落盘后再重绘；</li>
 *   <li>两者都就绪 ⇒ 逐条保留 `bookId ∈ 书架`（**顺序不变**，不做任何重排）。</li>
 * </ol>
 *
 * <p>🔴 **`bookId` 为空的条目一律保留**：实测回包里存在只有 `albumInfo`（且为空对象）的条目
 * （有声书），其 `bookId == null` ⇒ **无法证实"不在书架"**。把它藏掉是不可逆的错觉
 * （可能正是用户的爱听书），而多留一行只是显示层的轻微噪音 —— 取"留"。
 *
 * <p>🔴 **数据源必须是「全量书架」**（{@link BookStore#shelfIds}），
 * <b>不能</b>用 {@link BookStore#shelf}（那是"最近读过的 200 本"）——
 * 后者做全时段过滤会把「二手时间」「娜塔莎之舞」这类**老书误删**（见 TASK-059 侦察结论）。
 */
public final class RankFilter {

    private RankFilter() {
    }

    /**
     * 按用户开关过滤一份 {@code readLongest} 列表。
     *
     * @param c   取偏好与书架清单用；null ⇒ 无法判断 ⇒ 原样返回
     * @param src 原始列表（可能为 null / 空）
     * @return 过滤后的**新列表**（顺序不变）；开关关 / 无清单 / 入参空 ⇒ 返回 {@code src} 本身
     */
    public static List<PeriodStats.Longest> apply(Context c, List<PeriodStats.Longest> src) {
        return apply(c, src, c != null && CardPrefs.isRankShelfOnly(c));
    }

    /**
     * 🆕 TASK-077：**显式给"是否只统计书架"开关**的重载。
     *
     * <p>为什么需要它：阅读账单的「去除不在书架的书」（{@link MenuPrefs#dropOffShelf}）是
     * **独立于**「书籍排名只统计书架」（{@link CardPrefs#isRankShelfOnly}）的另一项用户配置 ⇒
     * 不能借后者当开关；**但过滤实现必须只有一份**（`shelfIds` 缺失 ⇒ 不过滤的口径、
     * 以及"空 bookId 一律保留"的判据都在这儿）⇒ 于是把开关值交给调用方传。
     *
     * @param shelfOnly true = 启用过滤；false ⇒ 与开关关闭完全等价（原样返回）
     */
    public static List<PeriodStats.Longest> apply(Context c, List<PeriodStats.Longest> src, boolean shelfOnly) {
        if (src == null || src.isEmpty()) return src;
        if (c == null || !shelfOnly) return src;                      // ① 开关关 ⇒ 零差异
        Set<String> ids = BookStore.shelfIds(c);
        if (ids == null) return src;                                  // ② 无全量书架 ⇒ 回退不过滤（等补拉）

        List<PeriodStats.Longest> out = new ArrayList<PeriodStats.Longest>(src.size());
        for (int i = 0; i < src.size(); i++) {
            PeriodStats.Longest it = src.get(i);
            if (it == null) continue;
            String id = it.bookId;
            // 无 bookId（无法证实）或确在书架 ⇒ 保留；只有"能证实不在书架"的才剔除。
            if (id == null || id.length() == 0 || ids.contains(id)) out.add(it);
        }
        return out;
    }
}
