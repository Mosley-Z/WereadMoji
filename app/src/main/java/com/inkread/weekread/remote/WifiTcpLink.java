package com.inkread.weekread.remote;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
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

    /** 默认网关（DhcpInfo.gateway，热点下 = 手机地址）。返回点分十进制，取不到返回 null。 */
    static String gatewayIp(Context c) {
        WifiManager wm = (WifiManager) c.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return null;
        DhcpInfo d = wm.getDhcpInfo();
        if (d == null || d.gateway == 0) return null;
        int g = d.gateway;
        return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
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
