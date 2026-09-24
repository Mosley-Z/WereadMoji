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

    /** 百分比，钳到 0–100（服务端偶尔会给 101 这种值） */
    public int percent() {
        if (progress < 0) return 0;
        if (progress > 100) return 100;
        return progress;
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
        return b;
    }

    /** 一行摘要，写进 card_debug.log */
    public String dump() {
        return "book=" + bookId + " 「" + title + "」 ch=" + chapterTitle
                + " progress=" + progress + "% read=" + readingSec + "s";
    }
}
