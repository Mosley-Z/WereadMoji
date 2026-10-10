package com.inkread.weekread.feature;

import com.inkread.weekread.R;
import com.inkread.weekread.core.PeriodRange;

import android.graphics.Paint;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 卡片的排版与几何辅助（TASK-007 从 {@link WeekCardView} 整段平移而来）：
 *
 * · **文本适配** —— fit / ellipsize / wrapLines，共用宿主的画笔 {@code host.p}
 *   （「调用前先把 p 的字号设成最终字号」的老约定原样保留）；
 * · **页内按钮几何** —— boxW / boxH / boxMarginB（App 全屏档放大一号，桌面档沿用 CardSpec）；
 * · **抬头文案** —— titleText / updatedLabel；
 * · **静态文本度量** —— layoutNote / wrapAll / wrapMax / measurePaint（与 {@link NoteExport}
 *   共用同一套折行，断点口径必须与屏幕完全一致）与时长格式化 fmtTotal / fmtShort。
 *
 * 🔴 手法与 TASK-006 一致：**字段全部留在壳里**，本类持 {@link #host} 引用按包级可见访问；
 * 代码逐行平移，除 `host.` 前缀外零改动 —— 拆分前后像素级行为必须完全一致。
 */
final class CardLayout {

    final WeekCardView host;

    CardLayout(WeekCardView host) { this.host = host; }

    /** 把字号缩到刚好装得下 maxW（下限 minS），返回实际字号 */
    float fit(String text, float size, float minS, float maxW) {
        host.p.setTextSize(size);
        while (host.p.measureText(text) > maxW && size > minS) {
            size -= 0.5f;
            host.p.setTextSize(size);
        }
        return size;
    }

    /** 超宽就砍到加省略号。**调用前必须先把 p 的字号设成最终字号** */
    String ellipsize(String text, float maxW) {
        if (host.p.measureText(text) <= maxW) return text;
        String tail = "…";
        float tailW = host.p.measureText(tail);
        int n = text.length();
        while (n > 1 && host.p.measureText(text.substring(0, n)) + tailW > maxW) n--;
        return text.substring(0, n) + tail;
    }

    /**
     * 逐字折行（中文友好：不用空格断词）。最多 maxLines 行，
     * 最后还放不下的部分截断加「…」。返回数组长度恒为 maxLines，空位为 null。
     * 调用前 p 的字号会被本方法设为 size。
     */
    String[] wrapLines(String text, float maxW, int maxLines, float size) {
        host.p.setTextSize(size);
        String[] out = new String[maxLines];
        if (host.p.measureText(text) <= maxW) {
            out[0] = text;
            return out;
        }
        float ellW = host.p.measureText("…");
        int n = text.length(), start = 0, line = 0;
        for (int i = 1; i <= n && line < maxLines; i++) {
            if (host.p.measureText(text, start, i) <= maxW) continue;
            if (line == maxLines - 1) {
                // 已是最后一行：剩余文字放不下 → 回退几个字换省略号
                int e = i - 1;
                while (e > start && host.p.measureText(text, start, e) + ellW > maxW) e--;
                out[line] = text.substring(start, e) + "…";
                return out;
            }
            out[line++] = text.substring(start, i - 1);
            start = i - 1;
        }
        if (line < maxLines && start < n) out[line] = text.substring(start);
        return out;
    }

    // ── 页内按钮尺寸：App 全屏档放大一号（屏幕宽、手指更好按）；桌面卡片沿用 CardSpec ──

    float boxW() { return host.fullscreen ? 88f : com.inkread.weekread.core.CardSpec.OPEN_BOX_W; }

    float boxH() { return host.fullscreen ? 38f : com.inkread.weekread.core.CardSpec.OPEN_BOX_H; }

    float boxMarginB() { return host.fullscreen ? 18f : com.inkread.weekread.core.CardSpec.OPEN_BOX_MARGIN_B; }

    // ── 抬头 ──

    /**
     * 抬头文字。
     *
     * <p>🆕 TASK-080（用户 2026-10-09 第二轮拍板 Q2）：「卡片形态的标题简化，
     * **和二字形态名一致**」⇒ 四种"有卡面"的形态改取 {@code nav_*} 二字名
     * （`本周 / 本月 / 本书 / 本记`），替代原先的长名（如「本书阅读进度」）。
     *
     * <p>🔴 **只在抬头绘制处换** —— {@link PeriodRange#title} 本身一个字没动
     * （它还有别的消费方，如导出与设置页）。
     *
     * <p>其余形态（待办 / 洞察 / 年 / 全时段）**没有**二字名 ⇒ 原样沿用长名，
     * 既不新增文案、也不改既有呈现。
     */
    String titleText() {
        int nav = navRes(host.mode);
        if (nav != 0) return host.getContext().getString(nav);
        long start = (host.stats != null && host.stats.baseTime > 0)
                ? host.stats.baseTime : PeriodRange.startOf(host.mode, 0);
        return PeriodRange.title(host.mode, start, PeriodRange.isCurrent(host.mode, start));
    }

    /** 形态 → 二字名资源（只有四种"有卡面"的形态有；其余返 0 = 沿用长名） */
    private static int navRes(String mode) {
        if (PeriodRange.WEEKLY.equals(mode)) return R.string.nav_week;
        if (PeriodRange.MONTHLY.equals(mode)) return R.string.nav_month;
        if (PeriodRange.BOOK.equals(mode)) return R.string.nav_book;
        if (PeriodRange.NOTE.equals(mode)) return R.string.nav_note;
        return 0;
    }

    String updatedLabel() {
        if (host.dataTime() <= 0) return "";
        String t = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(host.dataTime()));
        return "更新于 " + t;
    }

    // ══════════════════════ 本记正文排版（v0.4.4，屏幕与导出共用）══════════════════════

    /**
     * 本记正文的排版结果：**原文段 + 想法段**两段。
     *
     * 屏幕绘制（CardRenderer 的本记分支）与长图导出（{@link NoteExport}）**共用**这个结果 ——
     * 折行口径只要有一处不同，导出的图就会和在屏幕上看到的不一样。
     */
    static final class NoteBody {
        /** 原文行（已按需截断） */
        String[] quote = new String[0];
        /** 想法行 */
        String[] idea = new String[0];
        float lineH;        // 行高（两段同字号）
        float tagSize;      // 「想法」小标的字号
        float tagBlock;     // 两段之间的竖向间隔（含小标那一行）
        float height;       // 正文总高
    }

    /**
     * 排版本记正文（包级可见 —— {@link NoteExport} 复用同一套折行）。
     *
     * @param quote  原文（可为空：整本书评/章节点评没有原文）
     * @param idea   想法（可为空：纯划线）
     * @param textW  正文可用宽度（px，已扣掉大引号）
     * @param tSize  正文字号（px）
     * @param tagSize「想法」小标字号（px）
     * @param maxQ   原文最多几行（≤0 = 不限）
     * @param maxI   想法最多几行（≤0 = 不限）
     */
    static NoteBody layoutNote(String quote, String idea, float textW, float tSize,
                               float tagSize, int maxQ, int maxI) {
        return layoutNote(quote, idea, textW, tSize, tagSize, maxQ, maxI, 0f, 0f);
    }

    /**
     * 同上，另给两段各自的**末行预留宽度**（v0.9，TASK-017）。
     *
     * 用途：行末要挂「展开▽ / 收起△」时，末行的可用宽度得先扣掉按钮那一截 ——
     * 否则省略号会顶到行尾、按钮被挤出行外（或被裁剪框切掉）。
     *
     * 🔴 **只给"要挂按钮的那一段"传非 0**（渲染侧自己判是哪一段），另一段传 0：
     * 给两段都预留会让"没挂按钮那段"的末行也平白少几个字，那是无谓的像素变化。
     *
     * ⚠️ 预留**只会让末行变短，不会让行数变多** —— 行数已被 `maxLines` 封顶，
     * 所以调用方按"不预留"算出来的行预算（{@code fit}）在预留后依然成立，不会顶到署名。
     */
    static NoteBody layoutNote(String quote, String idea, float textW, float tSize,
                               float tagSize, int maxQ, int maxI,
                               float reserveQ, float reserveI) {
        NoteBody b = new NoteBody();
        b.lineH = tSize * 1.55f;
        b.tagSize = tagSize;
        // 段间间隔**取一整行**（而不是 tagSize 的倍数）—— 这样"想法"段的每一行
        // 与原文段一样落在 lineH 的整数倍上，滚动裁剪才能永远压在行边界。
        // 「想法」小标就画在这一行的空位里。
        b.tagBlock = b.lineH;

        boolean hasQ = quote != null && quote.trim().length() > 0;
        boolean hasI = idea != null && idea.trim().length() > 0;
        float y = 0f;
        if (hasQ) {
            b.quote = (maxQ <= 0) ? wrapAll(quote.trim(), textW, tSize)
                    : wrapMax(quote.trim(), textW, maxQ, tSize, reserveQ);
            y += b.quote.length * b.lineH;
        }
        if (hasI) {
            // 🔴 **只要画想法，就先占下「想法」小标那一行**（v0.5.3，R09）——
            // 上一版写成 `if (hasQ) y += b.tagBlock;`：没有原文的独立想法（整本书评 /
            // 章节点评，占全库想法的约 59%）排版时少算一整行，而绘制侧
            // （CardRenderer 本记分支的 `segEnd + tagBlock + tSize`）**总是**先留出小标行。
            // 结果：屏幕滚动上限少一行 → 长独立想法的最后一行滚不出来；导出图矮一截、
            // 末行被页脚压住。判定必须与绘制同源：**有没有想法**，而不是有没有原文。
            y += b.tagBlock;
            b.idea = (maxI <= 0) ? wrapAll(idea.trim(), textW, tSize)
                    : wrapMax(idea.trim(), textW, maxI, tSize, reserveI);
            y += b.idea.length * b.lineH;
        }
        b.height = y;
        return b;
    }

    /** 折行，**不限行数**（返回实际行数组，无 null 尾）—— 给 App 全屏档与长图导出用 */
    static String[] wrapAll(String text, float maxW, float size) {
        Paint mp = measurePaint(size);
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        int n = text.length(), start = 0;
        while (start < n) {
            int i = Math.min(start + 1, n);
            while (i <= n && mp.measureText(text, start, i) <= maxW) i++;
            int end = i - 1;
            if (end <= start) end = start + 1;        // 单个超宽字符也硬放
            out.add(text.substring(start, end));
            start = end;
        }
        return out.toArray(new String[out.size()]);
    }

    /**
     * 折行，最多 {@code maxLines} 行，末行放不下就截断加「…」。
     * 返回**实际行数**的数组（无 null 尾）—— 卡片档的"上文下想法都限行数"靠它。
     */
    static String[] wrapMax(String text, float maxW, int maxLines, float size) {
        return wrapMax(text, maxW, maxLines, size, 0f);
    }

    /**
     * 同上，另给**末行**预留 {@code reserveLast} 的宽度（v0.9，TASK-017）。
     *
     * 只有"最多 {@code maxLines} 行里最后那一行"按 `maxW − reserveLast` 折，前面各行照旧 ——
     * 这样预留不会把正文挤成更多行，只会让末行（以及它的省略号位置）少几个字。
     *
     * 🔴 兜底：预留后连一个「…」都放不下时（`lastW ≤ ellW`）**放弃预留**，
     * 宁可让按钮压住末行尾部，也不能折出一堆单字行。
     */
    static String[] wrapMax(String text, float maxW, int maxLines, float size, float reserveLast) {
        Paint mp = measurePaint(size);
        if (maxLines <= 0) return new String[]{text};
        if (mp.measureText(text) <= maxW) return new String[]{text};
        float ellW = mp.measureText("…");
        float lastW = maxW - reserveLast;
        if (lastW <= ellW) lastW = maxW;
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        int n = text.length(), start = 0;
        while (start < n && out.size() < maxLines) {
            boolean lastLine = (out.size() == maxLines - 1);
            float lineW = lastLine ? lastW : maxW;
            int i = Math.min(start + 1, n);
            while (i <= n && mp.measureText(text, start, i) <= lineW) i++;
            int end = i - 1;
            if (end <= start) end = start + 1;
            boolean last = lastLine || (end >= n);
            if (last && end < n) {
                while (end > start && mp.measureText(text, start, end) + ellW > lineW) end--;
                out.add(text.substring(start, end) + "…");
                break;
            }
            out.add(text.substring(start, end));
            start = end;
        }
        return out.toArray(new String[out.size()]);
    }

    /** 折行测量用的画笔（衬线体，与正文一致）。静态方法里用，不动实例的画笔 {@code host.p} */
    private static Paint measurePaint(float size) {
        Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
        mp.setTypeface(android.graphics.Typeface.SERIF);
        mp.setTextSize(size);
        return mp;
    }

    // ── 时长格式化（绘制与导出共用）──

    static String fmtTotal(int sec) {
        int hh = sec / 3600, mm = (sec % 3600) / 60;
        if (hh > 0) return hh + "小时" + mm + "分";
        if (mm > 0) return mm + "分钟";
        return sec + "秒";
    }

    static String fmtShort(int sec) {
        if (sec >= 3600) {
            float v = sec / 3600f;
            return String.format(Locale.CHINA, "%.1f时", v);
        }
        int m = sec / 60;
        if (m < 1) m = 1;
        return m + "分";
    }
}
