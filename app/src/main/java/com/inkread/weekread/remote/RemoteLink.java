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

        /**
         * 一条**已建立的连接**断开（对端正常 close / 读 IOException），但**链路本身未终结**：
         * Server 退回 accept 等重连、Client 进入重连重试。
         *
         * 🔴 与 {@link #onDisconnected} 区分：本回调是"当前这一条连接没了"，会话层据此把 UI
         *   从「已连接」退出（退回「等待重连」），**不**结束会话；只有重试耗尽 / 用户 stop /
         *   空闲超时才走 {@link #onDisconnected} 真正终结（规格 `docs/FEATURES/remote.md`
         *   「对端消失（自动重连，重连失败 N 次则退出会话）」，TASK-018 观察项 O1 修复）。
         */
        void onConnectionLost(String reason);

        /**
         * 链路**终结**（Server 停止 / Client 重试耗尽）。
         * 用户主动 stop() **不会**触发本回调（重连 / 收尾策略归 RemoteLinkManager）。
         */
        void onDisconnected(String reason);
    }

    void start(Listener listener);

    /** 断开并释放资源；幂等，可重复调用。 */
    void stop();

    /** 发一条指令；未建链 / 发送失败返回 false。 */
    boolean send(int cmd);

    boolean isConnected();
}
