package com.inkread.weekread.feature;

import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * 卡片的触摸与滚动（TASK-007 从 {@link WeekCardView} 整段平移而来）：
 *
 * · 只有「本书」/「本记」形态需要接收触摸 —— 本书 = 右下角「打开」；
 *   本记 = 左下「上一条」+ 右下「换一条」（App 全屏档另有「导出」「筛选」与
 *   🆕 TASK-057 的「选书」格），且 v0.4.4 起本记态还要区分**滑动**（滚正文）与**点击**（按按钮）。
 * · v0.4.1 修的正是这里：原来开头一句 `if (!isBook()) return false;` 把本记态的
 *   触摸全放掉了，App 内的「换一条」因此是个**画上去的假按钮**（点它没有任何反应）。
 * · 周/月形态保持原样（不处理触摸）—— 桌面卡片那边整张卡是 NOT_TOUCHABLE，
 *   App 里也用不到卡片自身的点击。
 *
 * 🔴 手法与 TASK-006 一致：**字段全部留在壳里**，本类持 {@link #host} 引用按包级可见访问；
 * 代码逐行平移，除 `host.` 前缀外零改动 —— 拆分前后触摸行为必须完全一致。
 */
final class CardInteraction {

    final WeekCardView host;

    CardInteraction(WeekCardView host) { this.host = host; }

    /** 滑动判定阈值（px）—— 位移不超过它就算"点击"，不算滚动 */
    private static final float NOTE_SCROLL_SLOP = 12f;

    boolean touch(MotionEvent e) {
        if (!host.isBook() && !host.isNote()) {
            // 🆕 TASK-054（K9）：本月全屏页「整页可滑」—— 只有**真的溢出**（monthScrollMax > 0）
            // 才吃手势；装得下时返回 false，行为与加本卡之前**完全一致**（不吃、不挡、不位移）。
            if (host.fullscreen && host.isMonthly() && host.monthScrollMax > 0f) return monthTouch(e);
            return false;
        }
        if (host.isNote()) return noteTouch(e);          // v0.4.4：本记态有滚动，手势要先分流
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            if (host.book != null && hit(host.openBox, x, y)) {
                if (host.openListener != null) host.openListener.onOpen();
            }
            return true;
        }
        // 这两个形态下要把 DOWN/MOVE 也接住，否则收不到后面的 UP
        return true;
    }

    /**
     * 本记态的触摸（v0.4.4）：**先判滑动、再判点击**。
     *
     * 为什么顺序重要：正文区现在可滚了，如果按下就判按钮，用户想滚动时手指落在正文上
     * 会顺手触发按钮（或者想点按钮却因轻微手抖被判成滚动）。规则很简单 ——
     * 动作内纵向位移超过 {@link #NOTE_SCROLL_SLOP} 就**锁定为滚动**，松手时不再判按钮。
     *
     * 松手后**不做惯性**：墨水屏上一次滑动要几百毫秒才刷干净，惯性滑行只会拖出一串残影。
     */
    private boolean noteTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                host.noteDownY = e.getY();
                host.noteDownScroll = host.noteScrollY;
                host.noteDrag = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (host.noteScrollMax <= 0f) return true;       // 内容没超过一屏（整行口径）→ 没得滚
                float dy = host.noteDownY - e.getY();
                if (!host.noteDrag && Math.abs(dy) > NOTE_SCROLL_SLOP) host.noteDrag = true;
                if (host.noteDrag) setNoteScroll(host.noteDownScroll + dy);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (host.noteDrag) {
                    host.noteDrag = false;
                    return true;                                  // 这一下是滚动，不算点击
                }
                float x = e.getX(), y = e.getY();
                // 「筛选」格放在 note == null 之前判：空态下它是唯一的出路（切回「全部」）
                if (host.fullscreen && hit(host.filterBox, x, y)) {
                    if (host.noteListener != null) host.noteListener.onToggleIdeas();
                    return true;
                }
                if (host.note == null) return true;
                // 行末「展开▽ / 收起△」（TASK-017）：卡片档专属，放在正文矩形与其它按钮之间判 ——
                // 桌面卡片主体是 NOT_TOUCHABLE，点它靠 OverlayController 另开的透明小窗；
                // 这一句是给"卡片本体可触摸"的宿主（App / 预览）兜底的，顺序上先于底部按钮，
                // 因为它在正文区里，与底部按钮矩形不重叠，先判后判都不会抢。
                if (hit(host.expandBox, x, y)) {
                    host.toggleNoteExpanded();
                    return true;
                }
                if (hit(host.prevBox, x, y)) {
                    if (host.noteListener != null) host.noteListener.onPrevNote();
                } else if (hit(host.openBox, x, y)) {
                    if (host.noteListener != null) host.noteListener.onNextNote();
                } else if (host.fullscreen && hit(host.pickBox, x, y)) {
                    // 🆕 TASK-057（K11）：点「选书」格 ⇒ 宿主弹半屏选书列表
                    if (host.noteListener != null) host.noteListener.onPickNote();
                } else if (host.fullscreen && hit(host.exportBox, x, y)) {
                    if (host.noteListener != null) host.noteListener.onExportNote();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                host.noteDrag = false;
                return true;
            default:
                return true;
        }
    }

    /**
     * 滚动正文：钳制到 [0, noteScrollMax] + **吸附到整行**。
     *
     * 吸附不是"手感优化"而是正确性要求：裁剪窗的上下边界固定，内容按 scrollY 位移，
     * 只有 scrollY 是 lineH 的整数倍时两条边界才同时压在行边界上。否则手指停在半行，
     * 顶端会漏出上一行的一小条墨迹、底端会把下一行切一半 —— 正是要修的那个毛病。
     * 副作用是"按行步进"的手感，在墨水屏上反而更省刷新、更不容易留残影。
     */
    private void setNoteScroll(float v) {
        float max = host.noteScrollMax;      // 已在绘制时取整到整行，这里直接用，别再算一次
        if (v < 0f) v = 0f;
        if (v > max) v = max;
        if (host.noteLineH > 0f) {
            float snapped = Math.round(v / host.noteLineH) * host.noteLineH;
            if (snapped < 0f) snapped = 0f;
            if (snapped > max) snapped = max;
            v = snapped;
        }
        if (Math.abs(v - host.noteScrollY) < 1f) return;
        host.noteScrollY = v;
        host.invalidate();
    }

    /**
     * 🆕 TASK-054（K9）：**本月全屏页**的纵向拖动（整页滚：信息块 + 日历 + 摘要行 + 排名区）。
     *
     * 与 {@link #noteTouch} 同款取舍：
     * · **不做惯性** —— 墨水屏上一次滑动要几百毫秒才刷干净，惯性滑行只会拖出一串残影；
     * · **位移超过 {@link #NOTE_SCROLL_SLOP} 才算滚动**，否则视为点击（本页暂时没有点击目标，
     *   但保留这条判据，将来若在月页加可点元素就不用返工）。
     *
     * 🔴 与「本记正文滚动」的关键区别：本页**不做整行吸附** —— 排名区不是行式正文，
     * 滚动量本来就该跟手；裁剪边界由绘制侧的 clipRect 保证（不会画到分隔线以上）。
     */
    private boolean monthTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                host.monthDownY = e.getY();
                host.monthDownScroll = host.monthScrollY;
                host.monthDrag = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = host.monthDownY - e.getY();       // 手指上滑 ⇒ dy > 0 ⇒ 内容上移
                if (!host.monthDrag && Math.abs(dy) > NOTE_SCROLL_SLOP) host.monthDrag = true;
                if (host.monthDrag) setMonthScroll(host.monthDownScroll + dy);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                host.monthDrag = false;
                return true;
            default:
                return true;
        }
    }

    /** 钳制到 [0, monthScrollMax]（不吸附、无惯性）；位移 < 1px 直接吞掉（墨水屏最省刷新的做法）。 */
    private void setMonthScroll(float v) {
        if (v < 0f) v = 0f;
        float max = host.monthScrollMax;
        if (v > max) v = max;
        if (Math.abs(v - host.monthScrollY) < 1f) return;
        host.monthScrollY = v;
        host.invalidate();
    }

    /** 正文是否超出一屏（还能滚） */
    boolean scrollable() {
        return host.noteScrollMax > 0f;
    }

    private static boolean hit(RectF r, float x, float y) {
        return !r.isEmpty() && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
    }
}
