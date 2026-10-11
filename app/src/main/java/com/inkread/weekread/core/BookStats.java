package com.inkread.weekread.core;

import org.json.JSONObject;

/**
 * 「本书」模式的数据 —— 最近在读的那一本书的阅读进度。
 *
 * 三个接口拼出来的（全链路 2026-09-22 实测打通，见设计文档）：
 *   ① `/shelf/sync`        —— 按 `readUpdateTime` 降序排，第一本就是"最近在读"；
 *   ② `/book/getprogress`  —— `progress`(百分比) / `readingTime`(累计秒) /
 *                              `chapterUid` / `chapterIdx`；
 *   ③ `/book/chapterinfo`  —— 按 `chapterUid` 反查章节标题（如「第三卷 酒色之徒」）。
 *
 * 不显示小节名：`getprogress` 只给到"章节 + 章节内偏移"，没有可靠的小节定位，
 * 硬猜会指错地方，所以只显示章节名（用户拍板③的第二行也只要求"章节名"）。
 */
public class BookStats {

    public String bookId = "";
    public String title = "";
    public String author = "";
    /** 书架给的 https 深链，兜底跳微信读书用（正常用 weread:// scheme） */
    public String deepLink = "";
    /** 封面图 URL（书架数据自带，v0.3.5.1 起随 {@link BookStore} 缓存，供封面绘制） */
    public String coverUrl = "";
    /** 阅读进度百分比 0–100 */
    public int progress;
    /** 累计阅读时长（秒） */
    public int readingSec;
    public int chapterUid;
    public int chapterIdx;
    public String chapterTitle = "";
    /** 书架里这本书的最后阅读时间（秒） */
    public long readUpdateTime;
    /** 拉取时间（毫秒），用于「更新于 HH:MM」 */
    public long fetchedAt;
    /**
     * 🆕 第六批A（D20–D22，2026-10-11）：**读完时间**（`/book/getprogress` 的 `book.finishTime`，秒）。
     *
     * <p>{@code 0} = 接口没给 / 该书未读完（未读完时该字段缺失）。只当"是否已读完"的判据用，
     * 不参与展示。见 {@link #applyFinishTime(BookStats, JSONObject)}。
     */
    public int finishTime;
    /**
     * 🆕 第六批A（D20–D22）：**已读完** —— `finishTime > 0`（`/book/getprogress`）**或**
     * 书架 `finishReading == 1`（`/shelf/sync`）：两处任一处成立即为 true。
     *
     * <p>🔴 它只影响**计费口径**：见 {@link BillMoney#progressForBilling(int, boolean)} ——
     * 已读完的书一律按 100% 计，避免"界面 100%、账单里 99%"。
     */
    public boolean finished;

    /**
     * 百分比，钳到 0–100（服务端偶尔会给 101 这种值）。
     *
     * <p>🔴 第六批A（D20–D22）：**渲染口径与账单口径必须同一处判定** —— 末尾一律过
     * {@link BillMoney#progressForBilling(int, boolean)}（≥99 或已读完 ⇒ 100）。
     * {@code progress < 0}（未知）原样保留旧行为：这里返回 0（「本书」卡显示 0%），
     * 而账单侧走的是"未知 ⇒ `—`"，两者语义不同、各自成立。
     */
    public int percent() {
        if (progress < 0) return 0;
        return BillMoney.progressForBilling(progress, finished);   // ≥99 / 已读完 ⇒ 100
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("bookId", bookId);
            o.put("title", title);
            o.put("author", author);
            o.put("deepLink", deepLink);
            o.put("coverUrl", coverUrl);
            o.put("progress", progress);
            o.put("readingSec", readingSec);
            o.put("chapterUid", chapterUid);
            o.put("chapterIdx", chapterIdx);
            o.put("chapterTitle", chapterTitle);
            o.put("readUpdateTime", readUpdateTime);
            o.put("fetchedAt", fetchedAt);
            o.put("finishTime", finishTime);          // 🆕 第六批A（D20–D22）
            o.put("finished", finished);              // 🆕 第六批A（D20–D22）
        } catch (Exception ignored) {
        }
        return o;
    }

    public static BookStats fromJson(JSONObject o) {
        if (o == null) return null;
        BookStats b = new BookStats();
        b.bookId = o.optString("bookId", "");
        b.title = o.optString("title", "");
        b.author = o.optString("author", "");
        b.deepLink = o.optString("deepLink", "");
        b.coverUrl = o.optString("coverUrl", "");
        b.progress = o.optInt("progress", 0);
        b.readingSec = o.optInt("readingSec", 0);
        b.chapterUid = o.optInt("chapterUid", 0);
        b.chapterIdx = o.optInt("chapterIdx", 0);
        b.chapterTitle = o.optString("chapterTitle", "");
        b.readUpdateTime = o.optLong("readUpdateTime", 0);
        b.fetchedAt = o.optLong("fetchedAt", 0);
        b.finishTime = o.optInt("finishTime", 0);                    // 🆕 第六批A
        b.finished = o.optBoolean("finished", false) || b.finishTime > 0;   // 🆕 第六批A（自洽兜底）
        return b;
    }

    /**
     * 🆕 第六批A（D20–D22）：从 `/book/getprogress` 的 `book` 对象解析「读完时间」。
     *
     * <p>这就是"零新增接口"的取数面：该书**读完**时接口才带 {@code finishTime}（秒），
     * 未读完则字段缺失 ⇒ {@code 0}。调用点在 {@link BillScheduler} 的进度解析里
     * （墨单链路），第六批B 的实时链路可同样调它。
     */
    public static void applyFinishTime(BookStats s, JSONObject book) {
        if (s == null || book == null) return;
        long ft = book.optLong("finishTime", 0L);
        if (ft > 0) {
            s.finishTime = (int) Math.min(Integer.MAX_VALUE, ft);
            s.finished = true;
        }
    }

    /**
     * 🆕 第六批A（D20–D22）：从 `/shelf/sync` 的书架项解析 {@code finishReading == 1}（= 已读完）。
     *
     * <p>与 {@link #applyFinishTime} 是"两条独立来源、任一处成立即 true"（卡面 D20 定的口径）——
     * 书架条目多数不带 `finishTime`，只有这个标志。
     */
    public static void applyFinishReading(BookStats s, JSONObject shelfBook) {
        if (s == null || shelfBook == null) return;
        if (shelfBook.optInt("finishReading", 0) == 1) s.finished = true;
    }

    /** 一行摘要，写进 card_debug.log */
    public String dump() {
        return "book=" + bookId + " 「" + title + "」 ch=" + chapterTitle
                + " progress=" + progress + "% read=" + readingSec + "s"
                + (finished ? " finished(finishTime=" + finishTime + ")" : "");
    }
}
