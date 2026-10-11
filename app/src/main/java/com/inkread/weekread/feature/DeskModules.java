package com.inkread.weekread.feature;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.inkread.weekread.core.PagePrefs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 🆕 TASK-076：**墨台模块注册表** —— 本版 **2 个**模块的**唯一登记处**（定稿设计 §3.1）。
 *
 * <p>注册表与本包内的渲染器/容器一起构成"加模块只改一处"的闭环：
 * <pre>
 *   新增模块 = ① 本类里加一个 {@link DeskModule} 实现
 *              ②（若引入新 id）{@code PagePrefs} 里加 MOD_* / BIT_* / moduleName
 *              —— 容器（DeskPageView）、渲染器（DeskRenderer）、设置页都不用动
 * </pre>
 *
 * <p>🔴 **数据全部读本地缓存**（`StatsStore` / `NoteStore` / `AssetStore` / `BillStore`）⇒ 零网络请求
 * （定稿设计 §2.3）。缓存为空时画**各自的空态**（不是崩、不是白屏）。
 *
 * <p>🆕 本卡（076）落地的 5 个模块里，**`bill`（TASK-077）与 `rank`（TASK-086 第六批B）** 是真模块；
 * 其余三个已在后续批次下线（见下）。
 *
 * <p>🆕 TASK-086 第六批B（用户 ③ / ⑤）：
 * <ul>
 *   <li>`rank`（② ）由「读书排行」**就地**换成 {@link AssetSection}「阅读资产」——
 *       id / 位 / 顺序 / 开关**零迁移**（D16），洞察页 K8 的 {@code RankSection} 不受影响（D17）；</li>
 *   <li>`todo`（原 ③ 待办摘要）**整块下线** —— id / `BIT_TODO = 4` / `moduleName` 分支都已从
 *       {@code PagePrefs} 删除（**位 4 不复用**）；手机端待办页与卡片池的「待办」不受影响（D25）。
 *       原实现已归档到 `_archive/废案/2026-10-11_待办摘要模块/`。</li>
 * </ul>
 *
 * <p>🆕 第七批 W1（2026-10-12 用户拍板）：`profile`（阅读画像）/ `note`（今日一签）/
 * `annual`（年度视图）**三个模块内部类整体删除**（原 ③ / ④ / ⑤）—— 对应 `PagePrefs` 的
 * `MOD_*` / `BIT_*`（16/32/64 不复用）/ `moduleName` 分支同步下线，老配置由净化逻辑零迁移丢掉。
 * ⚠️ 洞察页**不受影响**：`InsightRenderer.profileSection` / `annualSection` /
 * `aggregateCategories` / `aggregateAuthors` / `judgePortrait` 仍由 `ui/InsightPageView` 在用。
 */
final class DeskModules {

    // ── 正文小尺（与 InsightRenderer 同尺：正文 15 · 行距 2.1）──

    private static final float SZ_BODY = 15f;
    private static final float LINE_UNITS = 2.1f;
    private static final int GRAY = 0xFF3C3C3C;

    // ⚠️ 第七批 W1：原「今日一签」模块（唯一使用本尺的正文模块之一）已删除；
    //    本尺与下面的 `TextSection` 作为**通用小区块**保留（下线成本低、后续文字型模块可直接复用）。

    private static final List<DeskModule> ALL = Arrays.<DeskModule>asList(
            new BillModule(),
            new RankModule());

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
     * 🆕 TASK-086 第六批B：「阅读资产」分区的**常驻实例**（原「读书排行」的常驻实例，D16 就地替换）。
     *
     * <p>常驻的原因：顶部那三枚**排序键**（时长 / 原价 / 收入，D12）与「展开 ▽ / 收起 △」（D13）的
     * 命中判定由容器（{@link DeskPageView}）做，容器得先拿到同一个分区实例；且"当前按什么排、
     * 是否展开"要跨帧记住（存在实例里，免得每帧重建）。
     */
    static AssetSection assetSection() { return RankModule.SECTION; }

    /**
     * 🆕 TASK-077：「阅读账单」分区的**常驻实例**。
     *
     * <p>同样要常驻的原因：顶部那枚**自绘页签条**（摘录菜单 / 读书菜单）的命中判定由容器做，
     * 容器得先拿到同一个分区实例；且"当前看哪个菜单"要跨帧记住（值落 {@code MenuPrefs}，
     * 实例里也留一份，免得每帧读盘）。
     */
    static BillSection billSection() { return BillModule.SECTION; }

    // 🔴 第七批 W1 删除：`private static int yearsOf(PeriodStats)`（原 `:92-96`）
    //    它只为已下线的「年度视图」模块服务（口径与 `shell/MainActivity.yearsOf` 一致，
    //    但**两者无调用关系**，全树 grep 确认删后零引用）⇒ 纯死代码，随三模块一起下线。

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

    // ══════════════════════ ② 阅读资产（🆕 TASK-086 第六批B · 用户 ③ / D9–D19）══════════════════════

    private static final class RankModule implements DeskModule {
        /** 常驻分区实例（排序键 / 展开态跨帧保留）。 */
        static final AssetSection SECTION = new AssetSection();

        public String id() { return PagePrefs.MOD_RANK; }

        public InsightRenderer.Section build(Context c) {
            // 🔴 只读本地：`AssetStore`（`/shelf/sync` + 逐本 `/book/getprogress` 的缓存）+
            //    `MenuPrice` / `NoteStore` 的**本地**价格索引 ⇒ 本渲染路径**零网络**。
            //    续补在 `AssetRefresher.ensure`（幂等 + TTL 闸门 + 后台线程，D19 与本月卡片同时机）。
            SECTION.refresh(c);
            return SECTION;
        }
    }

    // ══════════════════════ ③④⑤ 已删除（第七批 W1，用户 2026-10-12 拍板）══════════════════════
    //
    // 原 `ProfileModule`（阅读画像）/ `NoteModule`（今日一签）/ `AnnualModule`（年度视图）三个内部类
    // 已整块删除，配套的 `PagePrefs.MOD_PROFILE` / `MOD_NOTE` / `MOD_ANNUAL` 与
    // `BIT_PROFILE = 16` / `BIT_NOTE = 32` / `BIT_ANNUAL = 64` 同步下线（**三位不复用**，同 D24 口径）。
    //
    // 🔴 只删"墨台模块"，**洞察页不受影响**：`InsightRenderer.profileSection` / `annualSection` /
    //    `aggregateCategories` / `aggregateAuthors` / `judgePortrait` 仍由 `ui/InsightPageView` 在用，
    //    不得跟着删。手机端待办页 / 卡片池的对应内容同样与本注册表无关。

    // ══════════════════════ 通用小区块（标题 + 若干行 / 空态）══════════════════════

    /**
     * 「标题 + 左对齐若干行」的轻量分区 —— 供墨台的文字型模块（今日一签 / 占位）共用。
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
