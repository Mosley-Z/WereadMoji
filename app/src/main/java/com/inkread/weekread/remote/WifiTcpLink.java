package com.inkread.weekread.remote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;

/**
 * TCP 传输实现（ADR-010 D1：手机 = Server listen，墨水屏 = Client 连默认网关）。
 *
 * 拓扑依据（实测）：热点是手机提供的 ⇒ 手机地址稳、墨水屏 IP 会变（.76 → .168），
 * 故墨水屏连「默认路由的网关」（热点下网关 = 手机）；手填 IP 是设置页兜底，本类不负责。
 *
 * 连接策略：
 *  - Server：一直 listen；客户端断开后回到 accept 等墨水屏重连；
 *  - Client：connect 失败重试 {@link #RETRY_MAX} 次；连上又断 = 链路丢失，重新进入重试循环；
 *  - 不做应用层心跳（SO_KEEPALIVE 足够，按需连接策略）；
 *  - 用户 stop() 幂等，且**不会**触发 onDisconnected（重连策略归 RemoteLinkManager）。
 */
public class WifiTcpLink implements RemoteLink {

    private static final String TAG = "RemoteLink";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    /** Client 重试上限（3s 超时 + 1.5s 间隔 ≈ 54s），全失败按链路丢失上报。 */
    private static final int RETRY_MAX = 12;
    private static final int RETRY_GAP_MS = 1500;

    private final boolean mServer;
    private final String mGateway;            // client 形态：默认网关（= 手机地址）
    private volatile Listener mListener;
    private volatile boolean mRunning;
    private volatile boolean mStopped;        // 用户主动 stop ⇒ 不回调 onDisconnected
    private volatile Socket mSocket;
    private ServerSocket mServerSocket;

    /** @param server true = 手机端（listen）；false = 墨水屏端（connect 网关）。 */
    public WifiTcpLink(boolean server, String gateway) {
        mServer = server;
        mGateway = gateway;
    }

    @Override
    public void start(final Listener listener) {
        mListener = listener;
        mRunning = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runLink();
            }
        }, "WifiTcpLink");
        t.setDaemon(true);
        t.start();
    }

    private void runLink() {
        try {
            if (mServer) {
                runServer();
            } else {
                runClient();
            }
        } catch (IOException e) {
            Log.w(TAG, "link error " + e);
        }
        closeQuietly();
        Listener l = mListener;
        if (l != null && !mStopped) {
            l.onDisconnected("link lost");
        }
    }

    /** 手机端：listen → accept → serve → 回到 accept（墨水屏断线重连再进来）。 */
    private void runServer() throws IOException {
        ServerSocket ss = new ServerSocket(RemoteProtocol.PORT);
        mServerSocket = ss;
        Log.i(TAG, "session: listening port=" + RemoteProtocol.PORT);
        while (mRunning) {
            Socket s = ss.accept();
            Log.i(TAG, "session: accepted from " + s.getInetAddress().getHostAddress());
            serve(s);
        }
    }

    /** 墨水屏端：connect → serve → 断后重试；连不满次数全失败 = 链路丢失。 */
    private void runClient() {
        int tries = 0;
        while (mRunning && !mStopped && tries < RETRY_MAX) {
            tries++;
            try {
                Log.i(TAG, "session: connecting " + mGateway + ":" + RemoteProtocol.PORT
                        + " (try " + tries + "/" + RETRY_MAX + ")");
                Socket s = new Socket();
                s.connect(new InetSocketAddress(mGateway, RemoteProtocol.PORT), CONNECT_TIMEOUT_MS);
                tries = 0;   // 连上过就重置计数：断线后重新给满重试次数
                serve(s);
                Log.w(TAG, "session: link lost, reconnecting");
            } catch (IOException e) {
                Log.w(TAG, "connect failed: " + e);
            }
            if (mRunning && !mStopped) {
                try {
                    Thread.sleep(RETRY_GAP_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
        if (tries >= RETRY_MAX) {
            Log.w(TAG, "session: connect failed after " + RETRY_MAX + " tries");
        }
    }

    /** 单条连接的读写循环，阻塞到连接断开。Client 建链后先发 HELLO（对端本版忽略）。 */
    private void serve(Socket s) {
        try {
            mSocket = s;
            s.setKeepAlive(true);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "UTF-8"));
            OutputStream w = s.getOutputStream();
            mOutCache = w;
            if (!mServer) {
                String ip = localIp();
                w.write(("HELLO " + (ip == null ? "unknown" : ip) + "\n").getBytes("UTF-8"));
                w.flush();
            }
            Listener l = mListener;
            if (l != null) {
                l.onConnected(s.getInetAddress().getHostAddress());
            }
            String line;
            while (mRunning && (line = r.readLine()) != null) {
                Listener ll = mListener;
                if (ll == null) break;
                int cmd = RemoteProtocol.parse(line);
                if (cmd != RemoteProtocol.CMD_NONE) {
                    ll.onCommand(cmd, line);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "session io " + e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            if (mSocket == s) {
                mSocket = null;
            }
            mOutCache = null;
        }
    }

    @Override
    public boolean send(int cmd) {
        String text;
        switch (cmd) {
            case RemoteProtocol.CMD_PAGE_NEXT: text = RemoteProtocol.PAGE_NEXT; break;
            case RemoteProtocol.CMD_PAGE_PREV: text = RemoteProtocol.PAGE_PREV; break;
            case RemoteProtocol.CMD_BYE:       text = RemoteProtocol.BYE; break;
            default: return false;
        }
        OutputStream w = mOutCache;
        if (w == null || !isConnected()) return false;
        try {
            synchronized (this) {
                w.write((text + "\n").getBytes("UTF-8"));
                w.flush();
            }
            Log.i(TAG, "send " + text);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "send failed " + e);
            return false;
        }
    }

    private volatile OutputStream mOutCache;   // 当前连接的输出流（serve 建立 / 断开清空）

    @Override
    public boolean isConnected() {
        Socket s = mSocket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    @Override
    public void stop() {
        mStopped = true;
        mRunning = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            Socket s = mSocket;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
        try {
            ServerSocket ss = mServerSocket;
            if (ss != null) ss.close();
        } catch (IOException ignored) {
        }
    }

    // ── 工具 ──

    /**
     * 默认网关（热点下 = 手机地址）。返回**点分十进制 IPv4**，取不到返回 null。
     *
     * 🔴 为什么**不用** {@code WifiManager.getDhcpInfo()}（TASK-018 上机实测踩坑记录）：
     *   该 API 受 {@code android.permission.ACCESS_WIFI_STATE} 保护，而本包**未声明**它
     *   ⇒ 真机直接抛
     *   {@code SecurityException: WifiService: Neither user … nor current process has
     *   android.permission.ACCESS_WIFI_STATE}。
     *   调用点在主线程（设置页「开始遥控」按钮）⇒ 未捕获异常**直接杀掉进程**；进程一死，
     *   本 App 的两个无障碍服务被系统一起解绑/停用，用户侧观感就是
     *   「手机 + 墨水屏两边无障碍一起崩溃关闭 + App 崩溃」。
     *   改用 ConnectivityManager 的**默认路由**取网关：只需已声明的
     *   {@code ACCESS_NETWORK_STATE}，**不新增任何权限**（权限说明保持干净）。
     *
     * 🔴🔴 必须**只认 IPv4 默认路由**（TASK-018 上机实测，墨水屏 S4 二次踩坑）：
     *   同一个 Wi-Fi 网络的 {@link LinkProperties#getRoutes()} 里**同时有两条 default**，
     *   且 **IPv6 那条排在前面**（实测 `dumpsys connectivity`）：
     *   <pre>
     *   Routes: [ fe80::/64 -> ::                       ← IPv6 前缀路由
     *             ::/0 -> fe80::f0df:66ff:fe3c:3db      ← IPv6 默认路由（RA，链路本地）★排第一
     *             10.116.3.0/24 -> 0.0.0.0
     *             0.0.0.0/0 -> 10.116.3.131 ]           ← IPv4 默认路由（真正的热点网关）
     *   </pre>
     *   如果像前一版那样「取第一条 default 路由」，拿到的会是 IPv6 **链路本地**地址
     *   （由手机 MAC 推的 EUI-64），而链路本地地址**不带 scope id 无法 connect**
     *   ⇒ 12 次重试全部 {@code ConnectException: EINVAL}，客户端永远连不上。
     *   故此处跳过非 {@link Inet4Address} 的网关；若整个网络没有 IPv4 默认路由则返回 null，
     *   由调用方 fail-closed 提示用户（本版拓扑是 IPv4 热点，够用）。
     */
    static String gatewayIp(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network net = cm.getActiveNetwork();
            LinkProperties lp = (net == null) ? null : cm.getLinkProperties(net);
            if (lp == null) return null;
            for (RouteInfo r : lp.getRoutes()) {
                if (!r.isDefaultRoute()) continue;
                InetAddress gw = r.getGateway();
                // 只认 IPv4：IPv6 默认路由在热点下是链路本地地址，connect 必然 EINVAL
                if (!(gw instanceof Inet4Address)) continue;
                String ip = gw.getHostAddress();
                if (ip != null && ip.length() > 0) return ip;
            }
        } catch (Throwable t) {
            // fail-closed：取不到网关一律返回 null，交给调用方给用户提示，
            // 绝不把异常抛回主线程（抛回 = 炸进程 = 连带停用无障碍）
            Log.w(TAG, "gatewayIp failed: " + t);
        }
        return null;
    }

    /** 本机 IPv4（HELLO 用，非环回）。取不到返回 null。 */
    static String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                Enumeration<InetAddress> addrs = nis.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
