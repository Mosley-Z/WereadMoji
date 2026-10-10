package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.inkread.weekread.core.NoteStats;
import com.inkread.weekread.core.NoteStore;
import com.inkread.weekread.core.PagePrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PeriodStats;
import com.inkread.weekread.core.RankFilter;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.core.TodoItem;
import com.inkread.weekread.core.TodoStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 🆕 TASK-076：**墨台模块注册表** —— 6 个模块的**唯一登记处**（定稿设计 §3.1）。
 *
 * <p>注册表与本包内的渲染器/容器一起构成"加模块只改一处"的闭环：
 * <pre>
 *   新增模块 = ① 本类里加一个 {@link DeskModule} 实现
 *              ②（若引入新 id）{@code PagePrefs} 里加 MOD_* / BIT_* / moduleName
 *              —— 容器（DeskPageView）、渲染器（DeskRenderer）、设置页都不用动
 * </pre>
 *
 * <p>🔴 **数据全部读本地缓存**（`StatsStore` / `TodoStore` / `NoteStore`）⇒ 零网络请求（定稿设计 §2.3）。
 * 缓存为空时画**各自的空态**（不是崩、不是白屏）。
 *
 * <p>🆕 本卡（076）已落地 5 个真实模块（`rank` / `todo` / `profile` / `note` / `annual`）；
 * `bill`（TASK-077）已换成真账单。共 **6 个**模块。
 */
final class DeskModules {

    // ── 正文小尺（与 InsightRenderer 同尺：正文 15 · 行距 2.1）──

    private static final float SZ_BODY = 15f;
    private static final float LINE_UNITS = 2.1f;
    private static final int GRAY = 0xFF3C3C3C;

    /** 待办摘要最多列几项（再多就"还有 N 项"）。 */
    private static final int TODO_MAX = 5;
    // 🔴 「今日一签」**不做行数截断**（不设 NOTE_MAX_LINES）：定稿设计未规定截断，而卡片侧
    //    `CardLayout.layoutNote` 的截断是"原文段 / 想法段"分段封顶、且卡片档另有「展开▽」可看全文；
    //    墨台只有一块文字区，按总行数硬截会把末尾的「—— 书名」署名一起截掉（有引文无出处，更差）。
    //    墨台本身可纵向滚动 ⇒ 长一签只影响占高，不构成排版缺陷。

    private static final List<DeskModule> ALL = Arrays.<DeskModule>asList(
            new BillModule(),
            new RankModule(),
            new TodoModule(),
            new ProfileModule(),
            new NoteModule(),
            new AnnualModule());

    private DeskModules() {
    }

    /** 按 id 取模块；不认识 / null ⇒ null（🔴 模块的**顺序**只认 {@code PagePrefs.MODULE_ORDER}，
     *  本类不另出"有序全集"访问器 —— 免得两处顺序表各说各话）。 */
    static DeskModule byId(String id) {
        if (id == null) return null;
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).id().equals(id)) return ALL.get(i);
        }
        return null;
    }

    /**
     * 「读书排行」分区的**常驻实例**。
     *
     * <p>与 {@link InsightPageView} 同做法：分区自带**内滚位移**，每帧 new 会把位移丢掉 ⇒
     * 容器（{@link DeskPageView}）持有同一个实例做手势分区判定，本类只负责换料。
     */
    static InsightRenderer.RankSection rankSection() { return RankModule.SECTION; }

    /**
     * 🆕 TASK-077：「阅读账单」分区的**常驻实例**。
     *
     * <p>同样要常驻的原因：顶部那枚**自绘页签条**（摘录菜单 / 读书菜单）的命中判定由容器做，
     * 容器得先拿到同一个分区实例；且"当前看哪个菜单"要跨帧记住（值落 {@code MenuPrefs}，
     * 实例里也留一份，免得每帧读盘）。
     */
    static BillSection billSection() { return BillModule.SECTION; }

    /** 注册年数（`registTime` 手算；-1 = 未知）—— 口径与 `MainActivity.yearsOf` **一致**。 */
    private static int yearsOf(PeriodStats st) {
        if (st == null || st.registTimeMs <= 0) return -1;
        return PeriodRange.yearOf(0) - PeriodRange.yearOf(st.registTimeMs / 1000L);
    }

    // ══════════════════════ ① 阅读账单（TASK-077）══════════════════════

    private static final class BillModule implements DeskModule {
        /** 常驻分区实例（页签条的命中判定与"当前视图"跨帧保留都靠它）。 */
        static final BillSection SECTION = new BillSection();

        public String id() { return PagePrefs.MOD_BILL; }

        public InsightRenderer.Section build(Context c) {
            // 🔴 只读本地（`BillStore` + `MenuPrefs`）⇒ 零网络。
            //    账单的**生成 / 热门预热**在 `BillScheduler.ensureBills`（服务创建 / onStart）时机，
            //    与本渲染路径彻底分离（`tasks/TASK-077` §关键约束 4）。
            SECTION.refresh(c);
            return SECTION;
        }
    }

    // ══════════════════════ ② 读书排行（♻️ 复用 RankSection）══════════════════════

    private static final class RankModule implements DeskModule {
        /** 常驻分区实例（内滚位移跨帧保留）。 */
        static final InsightRenderer.RankSection SECTION = new InsightRenderer.RankSection();

        public String id() { return PagePrefs.MOD_RANK; }

        public InsightRenderer.Section build(Context c) {
            // 料 = 当年年度回包的 longest[]（`StatsStore` 本地缓存，零请求）；
            // 「只统计书架上的书」开关与洞察页**同口径**（RankFilter 是唯一过滤口径）。
            PeriodStats ann = StatsStore.loadAnnual(c, PeriodRange.yearOf(PeriodRange.nowSec()));
            SECTION.setItems(ann == null ? null : RankFilter.apply(c, ann.longest));
            return SECTION;
        }
    }

    // ══════════════════════ ③ 待办摘要（读 TodoStore）══════════════════════

    private static final class TodoModule implements DeskModule {
        public String id() { return PagePrefs.MOD_TODO; }

        public InsightRenderer.Section build(Context c) {
            List<TodoItem> pending = TodoStore.pending(c);
            List<String> lines = new ArrayList<String>();
            int n = Math.min(pending == null ? 0 : pending.size(), TODO_MAX);
            for (int i = 0; i < n; i++) {
                TodoItem t = pending.get(i);
                String when = (t == null) ? "" : t.whenLabel();
                String body = (t == null || t.content == null) ? "" : t.content.trim();
                lines.add((when != null && when.length() > 0) ? (when + "  " + body) : body);
            }
            int total = (pending == null) ? 0 : pending.size();
            if (total > n) lines.add("… 还有 " + (total - n) + " 项");
            return new TextSection(PagePrefs.moduleName(id()), lines, "暂无待办");
        }
    }

    // ══════════════════════ ④ 阅读画像（♻️ 复用 profileSection）══════════════════════

    private static final class ProfileModule implements DeskModule {
        public String id() { return PagePrefs.MOD_PROFILE; }

        public InsightRenderer.Section build(Context c) {
            // 口径 = 累计档（`StatsStore` 本地缓存；与洞察页的"画像"分区同源，零新增请求）。
            PeriodStats st = StatsStore.loadOverall(c);
            int noteChars = NoteStore.totalIdeaChars(c);
            int years = yearsOf(st);
            return InsightRenderer.profileSection(
                    InsightRenderer.aggregateCategories(
                            st == null ? null : st.preferCategory, InsightRenderer.RADAR_TOP_N),
                    InsightRenderer.aggregateAuthors(
                            st == null ? null : st.preferAuthor, InsightRenderer.AUTHOR_TOP_N),
                    st == null ? null : st.preferTime,
                    "累计", "暂无阅读画像",
                    InsightRenderer.judgePortrait(
                            st == null ? null : st.preferCategory, noteChars, years));
        }
    }

    // ══════════════════════ ⑤ 今日一签（读 NoteStore）══════════════════════

    private static final class NoteModule implements DeskModule {
        public String id() { return PagePrefs.MOD_NOTE; }

        public InsightRenderer.Section build(Context c) {
            // 🔴 只读**本地池**（`NoteStore.pick(c,false)` 是抽一条已缓存内容，不发请求）。
            NoteStats pick = NoteStore.pick(c, false);
            List<String> lines = new ArrayList<String>();
            if (pick != null) {
                String text = pick.hasIdea() ? pick.ideaText : pick.markText;
                if (text == null || text.trim().length() == 0) text = pick.markText;
                if (text != null && text.trim().length() > 0) {
                    lines.add("「" + text.trim().replaceAll("\\s+", " ") + "」");
                }
                String from = pick.title;
                if (from != null && from.length() > 0) lines.add("—— " + from);
            }
            return new TextSection(PagePrefs.moduleName(id()), lines, "暂无本记");
        }
    }

    // ══════════════════════ ⑥ 年度视图（♻️ 复用 annualSection）══════════════════════

    private static final class AnnualModule implements DeskModule {
        public String id() { return PagePrefs.MOD_ANNUAL; }

        public InsightRenderer.Section build(Context c) {
            PeriodStats ann = StatsStore.loadAnnual(c, PeriodRange.yearOf(PeriodRange.nowSec()));
            return InsightRenderer.annualSection(ann, "暂无年度数据");
        }
    }

    // ══════════════════════ 通用小区块（标题 + 若干行 / 空态）══════════════════════

    /**
     * 「标题 + 左对齐若干行」的轻量分区 —— 供墨台的文字型模块（待办 / 一签 / 占位）共用。
     *
     * <p>🔴 折行按 `Paint.breakText` 实测（不是按字数估），因为墨台是**比例化**排版：
     * 同一段文字在不同屏宽下每行装得下的字数不同；按字数估会在窄屏溢出、宽屏浪费。
     * 折行结果按 (w, unit) 缓存 —— 每帧 `height()` 与 `draw()` 用同一份，不会重复测量。
     *
     * <p>🔴 纯黑白 / 三档灰，无圆角阴影；行距与洞察页正文同尺（2.1 倍字号）。
     */
    private static final class TextSection implements InsightRenderer.Section {

        private final String title;
        private final List<String> src;      // 原始行（未折行）
        private final String empty;          // src 为空时居中显示的文案（可 null ⇒ 只画标题）

        private final Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cw = -1f, cu = -1f;
        private List<String> laid = null;

        TextSection(String title, List<String> src, String empty) {
            this.title = title;
            this.src = new ArrayList<String>();
            if (src != null) {
                for (String s : src) if (s != null && s.trim().length() > 0) this.src.add(s);
            }
            this.empty = empty;
        }

        public String title() { return title; }

        /** 折行（按 (w, unit) 缓存）。 */
        private List<String> laid(float w, float unit) {
            if (laid != null && cw == w && cu == unit) return laid;
            cw = w;
            cu = unit;
            float maxW = w * (1f - 2f * InsightRenderer.PAD_X_RATIO);
            mp.setTextSize(SZ_BODY * unit);
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < src.size(); i++) wrapInto(out, src.get(i), maxW);
            laid = out;
            return out;
        }

        private void wrapInto(List<String> out, String s, float maxW) {
            String rest = s.replace('\n', ' ').trim();
            if (rest.length() == 0) return;
            float[] mw = new float[1];
            int guard = 0;
            while (rest.length() > 0 && guard++ < 12) {
                int n = mp.breakText(rest, true, maxW, mw);
                if (n <= 0) n = 1;
                if (n >= rest.length()) { out.add(rest); return; }
                out.add(rest.substring(0, n).trim());
                rest = rest.substring(n).trim();
            }
            if (rest.length() > 0) out.add(rest);      // 兜底：极端长行
        }

        public float height(float w, float vh, float unit) {
            float head = InsightRenderer.secHeadH(unit);
            if (src.isEmpty()) {
                return (empty == null) ? head : head + SZ_BODY * unit * 3.8f;
            }
            List<String> ls = laid(w, unit);
            return head + Math.max(1, ls.size()) * SZ_BODY * unit * LINE_UNITS + unit * 5f;
        }

        public void draw(Canvas c, float w, float vh, float top, float unit, Paint p) {
            float y = InsightRenderer.drawSectionHead(c, w, top, unit, p, title);
            if (src.isEmpty()) {
                if (empty != null) {
                    InsightRenderer.drawCenteredIn(c, w, y, SZ_BODY * unit * 3.8f, unit, p, empty);
                }
                return;
            }
            List<String> ls = laid(w, unit);
            float sz = SZ_BODY * unit;
            float lh = sz * LINE_UNITS;
            float left = w * InsightRenderer.PAD_X_RATIO;
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(null);
            p.setFakeBoldText(false);
            p.setColor(GRAY);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(sz);
            Paint.FontMetrics fm = p.getFontMetrics();
            for (int i = 0; i < ls.size(); i++) {
                float cy = y + lh * i + lh * 0.5f;
                c.drawText(ls.get(i), left, cy - (fm.ascent + fm.descent) / 2f, p);
            }
        }
    }
}
