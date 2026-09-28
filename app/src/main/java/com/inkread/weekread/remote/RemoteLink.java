package com.inkread.weekread.remote;

/**
 * 传输层接口（三层架构的中层）：捕获层与注入层都只认它。
 * 将来若蓝牙传输可用，新增一个实现类即可，另两层一字不改（ADR-010 决定 3）。
 *
 * 本版唯一实现：{@link WifiTcpLink}（手机热点 + TCP，ADR-010）。
 */
public interface RemoteLink {

    /** 链路事件回调。**在网络线程上回调** —— 实现方（RemoteLinkManager）自行 post 到主线程。 */
    interface Listener {
        /** 一条 TCP 连接建立（Client connect 成功 / Server accept 到客户端，都算）。 */
        void onConnected(String peerIp);

        /** 收到一条可识别指令（已由 {@link RemoteProtocol#parse} 解析）。 */
        void onCommand(int cmd, String rawLine);

        /** 链路终结（重试耗尽 / 读失败 / 对端消失）。用户主动 stop() **不会**触发本回调。 */
        void onDisconnected(String reason);
    }

    void start(Listener listener);

    /** 断开并释放资源；幂等，可重复调用。 */
    void stop();

    /** 发一条指令；未建链 / 发送失败返回 false。 */
    boolean send(int cmd);

    boolean isConnected();
}
