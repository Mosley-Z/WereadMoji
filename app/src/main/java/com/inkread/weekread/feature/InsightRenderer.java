package com.inkread.weekread.feature;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.inkread.weekread.core.PeriodStats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🆕 TASK-048（K3）：洞察页的**分区渲染器** —— 只管「怎么排、怎么画」，**不管滚动**（滚动在
 * {@link InsightPageView}）。
 *
 * 结构 = 一小段上边距 + 若干 {@link Section}，自上而下依次排布。
 * 🔴 **没有大标题**（用户 2026-10-06 当场要求删掉「阅读洞察」—— 下拉里已显示「洞察▽」，重复且占高）。
 * 本卡只注册 5 个**占位分区**（各自带一句专属空态文案）；K4~K8/K10 各自新增一个 {@code Section}
 * 实现、在 {@link InsightPageView#rebuildSections()} 里把对应的占位行换掉即可 ——
 * **容器与滚动逻辑零改动**（分区边界是稳定的扩展点）。
 *
 * 🔴 墨水屏铁律（`docs/03`）：纯黑白 + 三档灰（INK / GRAY / LIGHT），**无动画、无阴影、无渐变**。
 *    分区之间只用 1px 实线分隔；不做卡片投影、不做圆角。
 *
 * 🔴 为什么要「视口裁剪」（{@link #draw} 里那段 if）：墨水屏一次全刷很贵，
 *    把完全滚出屏幕的分区直接跳过，能明显缩小重绘面积。
 */
final class InsightRenderer {

    /**
     * 一个分区：标题 + 自量高度 + 自绘内容。
     *
     * @param vh 视口高（像素）—— 排行区这类「按屏高百分比占位」的分区要用它定高
     * @param top 该分区顶边的 y（**已含滚动位移**，可为负 = 有一部分在屏幕上方）
     */
    interface Section {
        /** 分区标题（画在分区左上）。 */
        String title();

        /** 该分区占的纵向高度（含标题行与下边距）。 */
        float height(float w, float vh, float unit);

        /** 从 {@code top} 起画。 */
        void draw(Canvas c, float w, float vh, float top, float unit, Paint p);
    }

    // ── 尺（与 CardRenderer 的同名字号对齐，保证两页目视一致）──
    private static final float SZ_SEC   = 17f;   // 分区标题（加粗）
    private static final float SZ_BODY  = 15f;   // 分区正文 / 空态

    /**
     * 抬头区高 —— 🔴 **只留一小段上边距**。
     *
     * 原来这里是一行大标题「阅读洞察」（`SZ_TITLE=19` 加粗）+ 一条 1px 分隔线，共 `19×unit×3.2 ≈ 73px`。
     * 用户 2026-10-06 当场要求**去掉大标题**（下拉里已经写着「洞察▽」，再顶一行大字纯属重复占高），
     * 所以整个抬头块缩成这一段留白。
     */
    private static final float HEAD_PAD_UNITS = 12f;   // ×unit ⇒ ≈14px @480×800

    private static final int INK   = 0xFF000000;
    private static final int GRAY  = 0xFF3C3C3C;
    private static final int LIGHT = 0xFFA8A8A8;

    /** 左右内边距比例 —— 与主页阅读区同尺（`WeekCardView.padXRatio` 默认 0.05）。 */
    static final float PAD_X_RATIO = 0.05f;

    private final List<Section> secs = new ArrayList<Section>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── 分区注册（给 InsightPageView 用）──

    void clear() { secs.clear(); }
    void add(Section s) { secs.add(s); }
    /** 分区数 —— 验收项 A2 要断言「5 个」。 */
    int count() { return secs.size(); }
    /** 第 i 个分区的标题（给自动化断言用）。 */
    String titleOf(int i) { return secs.get(i).title(); }

    // ── 量高 ──

    /** 抬头区高（只有一小段上边距 —— 大标题已删，见 {@link #HEAD_PAD_UNITS}）。 */
    float headerHeight(float unit) { return HEAD_PAD_UNITS * unit; }

    /** 全部内容总高（含抬头）。容器据此决定「能不能滚」。 */
    float contentHeight(float w, float vh, float unit) {
        float y = headerHeight(unit);
        for (int i = 0; i < secs.size(); i++) y += secs.get(i).height(w, vh, unit);
        return y;
    }

    // ── 画一帧 ──

    /**
     * @param scrollY 已滚过的像素（0 = 顶部）。容器负责先钳进 [0, contentHeight − vh]。
     */
    void draw(Canvas c, float w, float vh, float unit, float scrollY) {
        float pad = w * PAD_X_RATIO;
        float left = pad, right = w - pad;
        float y = -scrollY;

        // ── 抬头：**只剩留白**（大标题「阅读洞察」+ 抬头分隔线已按用户要求删除）──
        y += headerHeight(unit);

        // ── 各分区（视口外的直接跳过）──
        for (int i = 0; i < secs.size(); i++) {
            Section s = secs.get(i);
            float sh = s.height(w, vh, unit);
            if (y + sh > 0f && y < vh) s.draw(c, w, vh, y, unit, p);
            y += sh;

            // 分区之间的分隔线：只有「这条线真的在屏上」才画
            if (y - scrollY > 0f && y - scrollY < vh) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(LIGHT);
                c.drawRect(left, y - 1f, right, y, p);
            }
        }

        p.setTextAlign(Paint.Align.LEFT);
    }

    // ── 分区绘制的小工具（各 Section 实现共用）──

    /** 分区标题行高。 */
    static float secHeadH(float unit) { return SZ_SEC * unit * 2.2f; }

    /**
     * 画分区标题（左对齐加粗 INK），返回正文起始 y。
     * 所有 Section 都从它起手 ⇒ 标题的字号/基线在 5 个分区之间天然一致。
     */
    static float drawSectionHead(Canvas c, float w, float top, float unit, Paint p, String title) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(INK);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(SZ_SEC * unit);
        c.drawText(title, w * PAD_X_RATIO, top + SZ_SEC * unit * 1.5f, p);
        p.setFakeBoldText(false);
        return top + secHeadH(unit);
    }

    /** 在 [top, top + boxH] 这个盒子里画一句居中的灰字（空态 / 占位文案）。 */
    static void drawCenteredIn(Canvas c, float w, float top, float boxH, float unit, Paint p, String text) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(GRAY);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(false);
        float size = SZ_BODY * unit;
        p.setTextSize(size);
        // 盒子高度都装不下时按比例缩字（和卡片页的底部统计行同一套兜底思路）
        float maxW = w * (1f - 2f * PAD_X_RATIO);
        while (p.measureText(text) > maxW && size > 11f * unit) {
            size -= 0.4f;
            p.setTextSize(size);
        }
        c.drawText(text, w / 2f, top + boxH * 0.5f + size * 0.36f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ── 占位分区工厂（本卡用；后序卡用真实 Section 顶替）──

    /** 占位分区：正文高度 = `lines` 行（按正文字号 ×1.9 行距算）。 */
    static Section placeholderLines(final String title, final String empty, final float lines) {
        return new Section() {
            public String title() { return title; }
            public float height(float w, float vh, float unit) {
                return secHeadH(unit) + SZ_BODY * unit * 1.9f * lines;
            }
            public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
                float bodyTop = drawSectionHead(c, w, top, unit, p, title);
                drawCenteredIn(c, w, bodyTop, SZ_BODY * unit * 1.9f * lines, unit, p, empty);
            }
        };
    }

    /**
     * 占位分区：正文高度 = **视口高的 `ratio` 倍**。
     * 排行区（K8/`TASK-053`）要按「40~45% 屏高」预留，就是走这条 ——
     * 让它的高度**只由屏高决定**，不随后序卡改文案而漂移。
     */
    static Section placeholderRatio(final String title, final String empty, final float ratio) {
        return new Section() {
            public String title() { return title; }
            public float height(float w, float vh, float unit) {
                return secHeadH(unit) + vh * ratio;
            }
            public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
                float bodyTop = drawSectionHead(c, w, top, unit, p, title);
                drawCenteredIn(c, w, bodyTop, vh * ratio, unit, p, empty);
            }
        };
    }

    // ══════════════════════ K4（TASK-049）：兴趣雷达 ══════════════════════

    /** 雷达最多画几条（收拢后不足就画几条 —— 🔴 「Top 8」是**上限**，不是保证；见验证记录 §1）。 */
    static final int RADAR_TOP_N = 8;

    private static final float RADAR_ROW_H   = 22f;    // ×unit ⇒ ≈26px @480×800（卡面行距）
    private static final float RADAR_BAR_H   = 12f;    // ×unit ⇒ ≈14px（卡面条高）
    private static final float RADAR_NAME_W  = 0.20f;  // ×w    ⇒ ≈96px（卡面分类名宽）
    private static final float RADAR_GAP     = 6f;     // ×unit ⇒ ≈7px（名↔轨 / 轨↔%）
    private static final float RADAR_PCT_SZ  = 13f;    // ×unit ⇒ ≈15.6px（百分比字号）
    private static final float RADAR_CAP_SZ  = 12f;    // ×unit ⇒ ≈14.4px（子块标题字号）
    private static final float RADAR_CAP_GAP = 7f;     // ×unit（子块标题 → 首行）

    /** 收拢后的一根条。 */
    static final class Bar {
        /** 分类名（`parentCategoryTitle`，空值回落 `categoryTitle`，再空 ⇒ 「其它」） */
        final String name;
        /** 收拢后的阅读秒数 */
        final int sec;
        /** 条长占比 —— `值 / 最大值` ⇒ 最长条恒为 `1.0`（A5：最长条 = 100% 轨宽） */
        final float frac;
        /** 百分比 —— `值 / 过滤后总和`（口径同官方「阅读偏好」的占比） */
        final float pct;

        Bar(String name, int sec, float frac, float pct) {
            this.name = name; this.sec = sec; this.frac = frac; this.pct = pct;
        }
    }

    /**
     * 把 `preferCategory` 收拢成雷达用的 Top-N（卡面设计要点 1 的五步，逐条对上）。
     *
     * <pre>
     *   ① 按 parentCategoryTitle 收拢求和（空 ⇒ 回落 categoryTitle ⇒ 再空归「其它」，卡面 R3）
     *   ② 过滤 readingTime &lt;= 0        （E16：回包里带 6 个全 0 的"候选分类"）
     *   ③ 降序（同值按名称升序 —— 保证多次渲染顺序稳定、可与离线脚本逐行对拍）
     *   ④ 取 Top N
     *   ⑤ frac = 值/最大值（**条长**）；pct = 100×值/过滤后总和（**百分比**，卡面设计要点 3）
     * </pre>
     *
     * 🔴 条长与百分比**分母不同**，别混：卡面 ASCII 里最长条（45.1%）也不是满轨 —— 见 A5 的两半。
     * 🔴 与 `_probe/t049/radar_probe.py` **逐条同构**，两边必须一起改。
     */
    static List<Bar> aggregateCategories(List<PeriodStats.PreferCat> cats, int topN) {
        List<Bar> out = new ArrayList<Bar>();
        if (cats == null || cats.isEmpty()) return out;

        Map<String, Integer> sum = new HashMap<String, Integer>();
        for (int i = 0; i < cats.size(); i++) {
            PeriodStats.PreferCat c = cats.get(i);
            if (c == null) continue;
            int t = c.readTimeSec;
            if (t <= 0) continue;                                        // ②
            String n = c.parent;
            if (n == null || n.length() == 0) n = c.name;
            if (n == null || n.length() == 0) n = "其它";
            Integer old = sum.get(n);
            sum.put(n, (old == null ? 0 : old.intValue()) + t);           // ①
        }
        if (sum.isEmpty()) return out;

        List<Map.Entry<String, Integer>> es =
                new ArrayList<Map.Entry<String, Integer>>(sum.entrySet());
        Collections.sort(es, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int d = b.getValue().intValue() - a.getValue().intValue();   // ③ 降序
                return d != 0 ? d : a.getKey().compareTo(b.getKey());
            }
        });

        long total = 0;
        for (int i = 0; i < es.size(); i++) total += es.get(i).getValue();
        int mx = es.get(0).getValue();
        int n = Math.min(topN <= 0 ? RADAR_TOP_N : topN, es.size());      // ④
        for (int i = 0; i < n; i++) {
            Map.Entry<String, Integer> e = es.get(i);
            int v = e.getValue().intValue();
            float frac = mx > 0 ? (float) v / (float) mx : 0f;            // ⑤ 条长
            float pct = total > 0 ? 100f * v / (float) total : 0f;        //   百分比
            out.add(new Bar(e.getKey(), v, frac, pct));
        }
        return out;
    }

    /**
     * 分区⑤「阅读画像」—— **K4 兴趣雷达**（真实实现，顶替 `TASK-048` 的占位）。
     *
     * @param bars  聚合后的 Top-N；null / 空 ⇒ 画 {@code empty} 空态
     * @param scope 口径范围词（累计 / 今年 / 本月 / 本周），拼进子块标题
     */
    static Section interestRadar(final List<Bar> bars, final String scope, final String empty) {
        final String title = "阅读画像";
        return new Section() {
            public String title() { return title; }

            public float height(float w, float vh, float unit) {
                float head = secHeadH(unit);
                if (bars == null || bars.isEmpty()) {
                    return head + SZ_BODY * unit * 1.9f * 2f;             // 与 draw 的空态盒同高
                }
                return head + (RADAR_CAP_SZ + RADAR_CAP_GAP) * unit
                        + RADAR_ROW_H * unit * bars.size() + unit * 4f;
            }

            public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
                drawInterestRadar(c, w, top, unit, p, bars, scope, empty);
            }
        };
    }

    /**
     * 画兴趣雷达：分区标题 + 子块标题（带口径）+ N 行 `分类名 ─ [轨|填充] ─ 百分比`。
     *
     * 🔴 **纯黑白**：条体（轨与填充）坐标**全部取整** ⇒ 那部分像素只有 `0x00` / `0xFF`；
     *    文字仍走全 App 统一的 `ANTI_ALIAS_FLAG`（与其它页同口径，见 TASK-048 §3-A5）。
     */
    private static void drawInterestRadar(Canvas c, float w, float top, float unit, Paint p,
                                          List<Bar> bars, String scope, String empty) {
        float pad = w * PAD_X_RATIO;
        float left = pad, right = w - pad;
        float y = drawSectionHead(c, w, top, unit, p, "阅读画像");

        if (bars == null || bars.isEmpty()) {
            drawCenteredIn(c, w, y, SZ_BODY * unit * 1.9f * 2f, unit, p, empty);
            return;
        }

        // ── 子块标题「兴趣雷达 · <口径>」──
        float capSz = RADAR_CAP_SZ * unit;
        p.setStyle(Paint.Style.FILL);
        p.setColor(GRAY);
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(capSz);
        c.drawText((scope == null || scope.length() == 0) ? "兴趣雷达" : ("兴趣雷达 · " + scope),
                left, y + capSz * 1.25f, p);
        y += (RADAR_CAP_SZ + RADAR_CAP_GAP) * unit;

        // ── 行几何（一次算好，各行共用）──
        float nameW = w * RADAR_NAME_W;
        float pctSz = RADAR_PCT_SZ * unit;
        p.setTextSize(pctSz);
        float pctW = p.measureText("100.0%");                 // 固定槽宽 ⇒ 数值变化不抖
        float barL = left + nameW + RADAR_GAP * unit;
        float barR = right - pctW - RADAR_GAP * unit;
        float trackW = Math.max(unit * 24f, barR - barL);
        float barH = RADAR_BAR_H * unit;
        float rowH = RADAR_ROW_H * unit;
        float bodySz = SZ_BODY * unit;

        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            float cy = y + i * rowH + rowH * 0.5f;

            // 条体 —— 整数对齐（0.5 偏移让 1px 描边落在像素中心 ⇒ 无 AA 灰边）
            int t0 = Math.round(cy - barH * 0.5f);
            int t1 = t0 + Math.round(barH);
            int x0 = Math.round(barL);
            int x1 = x0 + Math.round(trackW);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(INK);
            c.drawRect(x0 + 0.5f, t0 + 0.5f, x1 - 0.5f, t1 - 0.5f, p);   // 轨（空槽外框）
            p.setStyle(Paint.Style.FILL);
            c.drawRect(x0, t0, x0 + Math.max(1, Math.round(trackW * b.frac)), t1, p);  // 填充

            // 分类名（左；超宽按 0.4 步长缩字，下限 11×unit）
            p.setColor(INK);
            p.setTextAlign(Paint.Align.LEFT);
            float ns = bodySz;
            p.setTextSize(ns);
            while (ns > 11f * unit && p.measureText(b.name) > nameW - RADAR_GAP * unit) {
                ns -= 0.4f;
                p.setTextSize(ns);
            }
            c.drawText(b.name, left, cy + ns * 0.36f, p);

            // 百分比（右）
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(pctSz);
            c.drawText(fmtPct(b.pct), right, cy + pctSz * 0.36f, p);
        }

        p.setTextAlign(Paint.Align.LEFT);
        p.setStyle(Paint.Style.FILL);
    }

    /** 百分比文案：固定一位小数（`45.1%`）—— 与卡面 ASCII 一致。 */
    private static String fmtPct(float pct) {
        int t = Math.round(pct * 10f);
        return (t / 10) + "." + Math.abs(t % 10) + "%";
    }

    // ══════════════════════ K6（TASK-051）：年度视图 ══════════════════════

    /** 12 柱柱图段高（×unit ⇒ ≈ 84px @480×800）。 */
    private static final float ANNUAL_CHART_UNITS = 70f;
    /** 锚点标签行高（×unit）。 */
    private static final float ANNUAL_LABEL_UNITS = 24f;
    /** 柱间距（×unit）。 */
    private static final float ANNUAL_GAP_UNITS = 3f;
    /** 月份锚点（1 起）—— 只标这 4 个，12 个数字会挤成一团。 */
    private static final int[] ANNUAL_ANCHORS = { 1, 4, 7, 10 };

    /** 空态盒高（= 2 行正文），供「无数据」两种分支共用，保证 `height()` 与 `draw()` 同高。 */
    private static float annualEmptyBox(float unit) { return SZ_BODY * unit * 1.9f * 2f; }

    /** 当年 12 桶里的最大月秒数（0 = 整年无记录）。 */
    private static int annualMaxMonth(PeriodStats st) {
        int mx = 0;
        if (st == null || st.monthSec == null) return 0;
        for (int i = 0; i < st.monthSec.length; i++) if (st.monthSec[i] > mx) mx = st.monthSec[i];
        return mx;
    }

    /** `readStat` 拼成一行（无则 null）—— `counts` 已带单位，原样拼。 */
    private static String statLine(PeriodStats st) {
        if (st == null || st.readStat == null || st.readStat.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < st.readStat.size(); i++) {
            PeriodStats.StatItem it = st.readStat.get(i);
            if (it == null || it.stat == null) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(it.stat).append(' ').append(it.counts == null ? "" : it.counts);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 有真年份数据（至少一个月 > 0）—— 空态与有数据的分水岭。 */
    static boolean hasAnnual(PeriodStats st) { return annualMaxMonth(st) > 0; }

    /**
     * 分区②「年度视图」—— **K6 真实实现**（顶替 `TASK-048` 的占位）。
     *
     * 自上而下三段：
     *   ① 汇总行「今年 共读 N 小时 · N 天」（`totalReadTime` + `readDays`）；
     *   ② **12 桶按月柱图**（柱高 = 该月秒数 / 当年最大月；等宽分布，1/4/7/10 月锚点）；
     *   ③ 四段摘要（`readStat`，`counts` 带单位原样拼）。
     *
     * 🔴 **不画 `preferCategory`**：分类偏好已是分区⑤「兴趣雷达」的主料（`TASK-049`），
     *    年度分区再画一遍属**重复占位**（见 `验证记录/160` §1 的设计决策 D1）。
     *
     * @param st    当年的 `PeriodStats`（`mode=annually`）；null ⇒ 画 {@code empty}
     * @param empty 空态文案（"没有缓存"用）
     */
    static Section annualSection(final PeriodStats st, final String empty) {
        final String title = "年度视图";
        return new Section() {
            public String title() { return title; }

            public float height(float w, float vh, float unit) {
                float head = secHeadH(unit);
                if (!hasAnnual(st)) return head + annualEmptyBox(unit);
                float h = head
                        + SZ_BODY * unit * 1.9f          // 汇总行
                        + ANNUAL_CHART_UNITS * unit      // 柱图
                        + ANNUAL_LABEL_UNITS * unit;     // 锚点标签
                if (statLine(st) != null) h += SZ_BODY * unit * 1.9f;
                return h + unit * 4f;
            }

            public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
                drawAnnualSection(c, w, top, unit, p, st, empty);
            }
        };
    }

    /**
     * 画年度分区。
     *
     * 🔴 **纯黑白**：柱体坐标全部 `Math.round` 取整 ⇒ 柱体像素只有 `0x00` / `0xFF`；
     *    基线用 `LIGHT` 档 1px（三档灰之内）；文字仍走全 App 统一 AA（与其它页同口径）。
     */
    private static void drawAnnualSection(Canvas c, float w, float top, float unit, Paint p,
                                          PeriodStats st, String empty) {
        float pad = w * PAD_X_RATIO;
        float left = pad, right = w - pad;
        float y = drawSectionHead(c, w, top, unit, p, "年度视图");

        if (st == null) {
            drawCenteredIn(c, w, y, annualEmptyBox(unit), unit, p, empty);
            return;
        }
        if (annualMaxMonth(st) == 0) {                       // 有回包但整年无记录
            drawCenteredIn(c, w, y, annualEmptyBox(unit), unit, p, "今年还没有阅读记录");
            return;
        }

        // ── ① 汇总行 ──
        float bodySz = SZ_BODY * unit;
        p.setStyle(Paint.Style.FILL);
        p.setColor(INK);
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(bodySz);
        String sum = "今年 共读 " + CardLayout.fmtTotal(st.totalSec) + " · " + st.readDays + " 天";
        c.drawText(sum, left, y + bodySz * 1.2f, p);
        y += bodySz * 1.9f;

        // ── ② 12 桶柱图 ──
        float chartH = ANNUAL_CHART_UNITS * unit;
        int baseY = Math.round(y + chartH);                  // 基线（整行）
        float gap = ANNUAL_GAP_UNITS * unit;
        float colW = (right - left - gap * 11f) / 12f;
        if (colW < 2f * unit) colW = 2f * unit;
        int mx = annualMaxMonth(st);

        for (int i = 0; i < 12; i++) {
            int xa = Math.round(left + i * (colW + gap));
            int xb = Math.round(left + i * (colW + gap) + colW);
            int sec = st.monthSec[i];
            int hpx = sec > 0 ? Math.max(1, Math.round(chartH * sec / (float) mx)) : 0;
            if (hpx > 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(INK);
                c.drawRect(xa, baseY - hpx, xb, baseY, p);
            }
        }
        // 基线（LIGHT 档 1px 实线）
        p.setStyle(Paint.Style.FILL);
        p.setColor(LIGHT);
        c.drawRect(left, baseY, right, baseY + 1, p);
        y = baseY + 1f;

        // 锚点标签（1/4/7/10 月）
        float lblSz = 12f * unit;
        p.setColor(GRAY);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(lblSz);
        for (int i = 0; i < ANNUAL_ANCHORS.length; i++) {
            int mon = ANNUAL_ANCHORS[i];
            float cx = left + (mon - 1) * (colW + gap) + colW * 0.5f;
            c.drawText(mon + "月", cx, y + lblSz * 1.35f, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
        y += ANNUAL_LABEL_UNITS * unit;

        // ── ③ readStat 行 ──
        String stat = statLine(st);
        if (stat != null) {
            float sz = bodySz;
            p.setColor(GRAY);
            p.setTextSize(sz);
            while (p.measureText(stat) > (right - left) && sz > 11f * unit) {
                sz -= 0.4f;
                p.setTextSize(sz);
            }
            c.drawText(stat, left, y + sz * 1.05f, p);
        }

        p.setTextAlign(Paint.Align.LEFT);
        p.setStyle(Paint.Style.FILL);
    }
}
