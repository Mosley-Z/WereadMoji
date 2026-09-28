package com.inkread.weekread.remote;

/**
 * 线上指令（极简文本行协议，零框架依赖 —— 项目零依赖铁律）。
 *
 * 消息格式（docs/FEATURES/remote.md「传输层选型」）：
 *   HELLO &lt;role&gt; &lt;ip&gt; &lt;port&gt;   建链后 Client 上报对端地址（为下版反向遥控留口；本版收到即忽略）
 *   PAGE_NEXT                        翻下一页
 *   PAGE_PREV                        翻上一页
 *   BYE                              结束会话
 */
public final class RemoteProtocol {

    /** 两端同端口（跨设备 listen 互不冲突）；固定端口，本版不做发现协议（NSD/广播都是非目标）。 */
    public static final int PORT = 45678;

    public static final String HELLO = "HELLO";
    public static final String PAGE_NEXT = "PAGE_NEXT";
    public static final String PAGE_PREV = "PAGE_PREV";
    public static final String BYE = "BYE";

    public static final int CMD_NONE = 0;
    public static final int CMD_PAGE_NEXT = 1;
    public static final int CMD_PAGE_PREV = 2;
    public static final int CMD_BYE = 3;

    private RemoteProtocol() {
    }

    /** 解析一行文本为指令字；空行 / HELLO / 不认识的行一律 {@link #CMD_NONE}（忽略）。 */
    public static int parse(String line) {
        if (line == null) return CMD_NONE;
        String s = line.trim();
        if (PAGE_NEXT.equals(s)) return CMD_PAGE_NEXT;
        if (PAGE_PREV.equals(s)) return CMD_PAGE_PREV;
        if (BYE.equals(s)) return CMD_BYE;
        return CMD_NONE;
    }

    /** 指令字的日志名（不认识返回原样数字）。 */
    public static String name(int cmd) {
        switch (cmd) {
            case CMD_PAGE_NEXT: return PAGE_NEXT;
            case CMD_PAGE_PREV: return PAGE_PREV;
            case CMD_BYE: return BYE;
            default: return "CMD#" + cmd;
        }
    }
}
