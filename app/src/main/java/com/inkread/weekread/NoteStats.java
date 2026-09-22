package com.inkread.weekread;

/**
 * 「本记」的一条内容（v0.4.0 划线；v0.4.4 起兼顾**想法/点评**）。
 *
 * ── 两种条目 ──
 *   · {@link #KIND_MARK} 纯划线 —— 数据来自 `/book/bookmarklist` 的 `updated[]`；
 *   · {@link #KIND_IDEA} 带想法 —— 数据来自 `/review/list/mine` 的 `reviews[]`。
 *
 * 想法条目里 {@link #markText} 是**原文**（接口字段 `review.abstract`），
 * {@link #ideaText} 是**用户自己写的那段话**（`review.content`）。
 * 两者都可能有值，也可能原文为空（整本书评 / 章节点评就没有对应原文，
 * 全库 189 条想法里这类占 8%）。
 *
 * ── 为什么要合并成一条而不是两个字段各占一条 ──
 * 用户对某段划线写了想法时，微信读书会同时留下"划线"和"想法"两份记录。
 * 若各自入池，同一条内容会在本记里出现两次。{@link NoteStore#itemsOfBook} 按
 * **(bookId, range)** 把它们**配对合并**成一条带想法的划线，配不上的想法才独立成条。
 *
 * 定长小对象，全库 5500 余条常驻内存也就几 MB，够用。
 */
public class NoteStats {

    /** 纯划线（只有原文，没有想法） */
    public static final String KIND_MARK = "mark";
    /** 带想法（原文可有可无，但一定有 {@link #ideaText}） */
    public static final String KIND_IDEA = "idea";

    /** 划线的唯一 id（bookmarkId）或想法的唯一 id（reviewId）—— 去重与"是否已抽过"都用它 */
    public String bookmarkId = "";
    public String bookId = "";
    public String title = "";
    public String author = "";
    public String coverUrl = "";

    /** 划线正文（最重要的一块）。想法条目里它是**原文**（`review.abstract`），可能为空 */
    public String markText = "";
    /** 想法正文（v0.4.4）。非空 = 这条带想法 */
    public String ideaText = "";
    /** {@link #KIND_MARK} / {@link #KIND_IDEA} */
    public String kind = KIND_MARK;

    /** 原文位置范围（如 `"2959-3007"`）—— 与划线配对、去重都用它 */
    public String range = "";
    /** 想法对应的评分（0-5，-1 = 无。整本书评才有值） */
    public int star = -1;

    public int chapterUid;
    public int chapterIdx;
    /** 章节名：来自 chapterinfo 反查，没查到时是「第 N 章」 */
    public String chapterTitle = "";
    /** 划线/想法的创建时间（秒） */
    public long createTime;

    /** 这条是否带想法 */
    public boolean hasIdea() {
        return ideaText != null && ideaText.trim().length() > 0;
    }

    /**
     * **排版分档用的字数**：原文 + 想法，再给「想法」标记与段间距折算一点开销。
     *
     * 为什么不直接用 {@code markText.length()}：想法条目是两段式排版，
     * 段间要空一行、还要画一个「想法」小标，光看字数会低估它占的高度，
     * 结果就是"想法条目被分了过大的字号、最后一屏装不下"。
     */
    public int displayChars() {
        int n = (markText == null ? 0 : markText.trim().length())
                + (ideaText == null ? 0 : ideaText.trim().length());
        if (hasIdea()) n += IDEA_OVERHEAD;
        return n;
    }

    /** 「想法」两个字 + 段间距 + 小标行的折算字数开销 */
    private static final int IDEA_OVERHEAD = 8;

    /** 展示用的短日期，如 2026-09-22（本地时区） */
    public String dateText() {
        if (createTime <= 0) return "";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
        return f.format(new java.util.Date(createTime * 1000L));
    }

    public String dump() {
        return "note[" + kind + " " + bookmarkId + " " + title
                + " chars=" + displayChars() + " idea=" + (hasIdea() ? ideaText.length() : 0)
                + " t=" + dateText() + "]";
    }
}
