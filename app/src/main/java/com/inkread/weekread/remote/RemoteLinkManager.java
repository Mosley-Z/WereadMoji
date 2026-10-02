package com.inkread.weekread.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.inkread.weekread.core.CardPrefs;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话生命周期（**按需连接策略唯一出口**，TASK-018）：
 *
 * <pre>
 * off（默认）──点「开始遥控」──▶ CONNECTING ──链路通──▶ CONNECTED
 *   ▲                            ▲   │                    │ 每条指令刷新 lastActiveAt
 *   │                            └───┼──对端断开（O1 回落）──┘
 *   │                                │
 *   └── CLOSED ◀──任一条件成立：点「结束」/ 空闲超时 / 重试耗尽（链路丢失）/ 服务被回收
 * </pre>
 *
 * 不做心跳（SO_KEEPALIVE 足够）；Client 重连由 {@link WifiTcpLink} 内建（RETRY_MAX 次），
 * 重试耗尽即链路丢失 → 会话结束，**不自动重启会话**（「开始遥控」永远由用户显式发起）。
 * 🔴 收到对端 `BYE` ⇒ 立即结束会话（O3）；单条连接断开 ⇒ 退回 CONNECTING 等重连（O1，不结束会话）。
 *
 * 线程模型：状态只在主线程变化（网络回调全部 post 回主线程），无需加锁。
 * 空闲超时兜底：连接中（CONNECTING）也计时 —— 一直连不上时超时自动退会话。
 */
public final class RemoteLinkManager {

    private static final String TAG = "RemoteLinkManager";

    /** 未在会话中（初始态）。 */
    public static final int STATE_IDLE = 0;
    /** 会话已建立，链路握手中（Server 等墨水屏连入 / Client 连接网关）。 */
    public static final int STATE_CONNECTING = 1;
    /** 链路已通。 */
    public static final int STATE_CONNECTED = 2;
    /** 会话已结束（detail 供设置页状态行显示）。 */
    public static final int STATE_CLOSED = 3;

    /** 状态回调（主线程）。设置页注册后立刻收到一次当前状态（无需手动刷新）。 */
    public interface StateListener {
        void onStateChanged(int state, String peerIp, String detail);
    }

    /** 注入层入口（eink 角色）：RemoteKeyService 挂载，收到翻页指令时调。 */
    public interface CommandSink {
        boolean inject(int cmd);
    }

    private static final RemoteLinkManager sInstance = new RemoteLinkManager();

    public static RemoteLinkManager get() {
        return sInstance;
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private RemoteLink mLink;
    private int mState = STATE_IDLE;
    private String mPeerIp;
    private String mDetail;
    private long mLastActiveAt;
    private int mIdleTimeoutMs;
    /**
     * 🔴 **多监听**（TASK-029 §2.2 修复）：设置页（状态行）与遥控服务（晃动捕获门控）
     * 各注册一个，互不顶掉。此前是单槽 {@code mListener}。
     */
    private final CopyOnWriteArrayList<StateListener> mListeners =
            new CopyOnWriteArrayList<StateListener>();
    private CommandSink mCommandSink;
    private Context mAppContext;
    private boolean mServer;                  // 本端是否 Server（连接断开文案区分「等待重连」/「正在重连」）

    private final Runnable mIdleCheck = new Runnable() {
        @Override
        public void run() {
            if (mState != STATE_CONNECTED && mState != STATE_CONNECTING) {
                return;
            }
            long idle = System.currentTimeMillis() - mLastActiveAt;
            if (idle >= mIdleTimeoutMs) {
                Log.i(TAG, "session: IDLE_TIMEOUT closed (idleMs=" + idle + ")");
                endSessionInternal("已断开（空闲超时）");
            } else {
                mHandler.postDelayed(this, mIdleTimeoutMs - idle);
            }
        }
    };

    private RemoteLinkManager() {
    }

    // ── 对设置页（状态显示）──

    /**
     * 注册状态监听（**多监听**：设置页 + 遥控服务各一个）。注册后**立刻回推一次**当前状态。
     *
     * <p>🔴 TASK-029 §2.2 修复：此前是**单槽** {@code setStateListener} ——
     * {@code SettingsActivity} 每进一次设置页就覆盖一次，TASK-029 的晃动捕获门控
     * 再注册时两边会**互相顶掉**（后注册者赢），表现为「状态行不刷新」或「晃动不激活」，
     * 且**不报错**。改成 add/remove 后两个监听可共存。
     */
    public void addStateListener(StateListener l) {
        if (l == null) {
            return;
        }
        mListeners.addIfAbsent(l);
        l.onStateChanged(mState, mPeerIp, mDetail);   // 立刻回推一次（页面无需手动刷新）
    }

    /** 注销状态监听（页面 onPause / 遥控服务 onDestroy）。 */
    public void removeStateListener(StateListener l) {
        if (l != null) {
            mListeners.remove(l);
        }
    }

    public int getState() {
        return mState;
    }

    // ── 对 RemoteKeyService（注入层挂载 / 生命周期联动）──

    public void setCommandSink(CommandSink sink) {
        mCommandSink = sink;
    }

    /** 无障碍服务被系统回收 ⇒ 会话随之结束（规格 §按需连接，日志留痕）。 */
    public void onServiceDestroyed() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mState == STATE_CONNECTING || mState == STATE_CONNECTED) {
                    endSessionInternal("无障碍服务已回收");
                }
            }
        });
    }

    // ── 会话控制（设置页「开始 / 结束遥控」按钮）──

    /**
     * 用户点「开始遥控」。角色决定链路形态：phone = Server listen；eink = Client 连默认网关。
     * 墨水屏端取不到网关（热点未连）时直接失败退出 —— fail-closed，不进入半死会话。
     */
    public void startSession(Context c) {
        RemoteRole role = RemoteRole.from(c);
        if (role == RemoteRole.OFF) {
            Log.w(TAG, "session: role=off，忽略开始");
            return;
        }
        if (mState == STATE_CONNECTING || mState == STATE_CONNECTED) {
            Log.i(TAG, "session: already active");
            return;
        }
        mAppContext = c.getApplicationContext();
        mIdleTimeoutMs = CardPrefs.getRemoteIdleTimeout(c) * 1000;
        boolean server = (role == RemoteRole.PHONE);
        mServer = server;
        String gateway = server ? null : WifiTcpLink.gatewayIp(mAppContext);
        if (!server && (gateway == null || gateway.length() == 0)) {
            Log.w(TAG, "session: no gateway —— 墨水屏还没连上热点？");
            setState(STATE_CLOSED, null, "未找到网关：请先连上手机热点再开始");
            return;
        }
        Log.i(TAG, "session: START role=" + role + " server=" + server
                + (server ? "" : " gateway=" + gateway));
        mLastActiveAt = System.currentTimeMillis();
        WifiTcpLink link = new WifiTcpLink(server, gateway);
        mLink = link;
        setState(STATE_CONNECTING, null, server ? "等待墨水屏连接…" : "正在连接手机…");
        link.start(new RemoteLink.Listener() {
            @Override
            public void onConnected(final String peerIp) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.i(TAG, "session: CONNECTED peer=" + peerIp);
                        mLastActiveAt = System.currentTimeMillis();
                        setState(STATE_CONNECTED, peerIp, null);
                        scheduleIdleCheck();
                    }
                });
            }

            @Override
            public void onCommand(final int cmd, final String rawLine) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cmd == RemoteProtocol.CMD_NONE) {
                            return;
                        }
                        Log.i(TAG, "recv " + rawLine);
                        // 🔴 O3 修复：对端主动结束会话 ⇒ 本端立即结束。
                        //    此前 BYE 被送进 CommandSink（注入层只认翻页指令）后**静默丢弃**，
                        //    对端点「结束遥控」本端毫无感知，状态行一直显示「已连接」，
                        //    直到空闲超时（默认 300s）才跳出「已断开（空闲超时）」。
                        if (cmd == RemoteProtocol.CMD_BYE) {
                            Log.i(TAG, "session: recv BYE → peer ended，本端结束会话");
                            endSessionInternal("对端已结束");
                            return;
                        }
                        mLastActiveAt = System.currentTimeMillis();
                        scheduleIdleCheck();
                        CommandSink sink = mCommandSink;
                        if (sink != null) {
                            sink.inject(cmd);
                        }
                    }
                });
            }

            @Override
            public void onConnectionLost(final String reason) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // 🔴 O1 修复：单条连接断了（对端消失 / 链路异常）⇒ 从「已连接」退回「等待重连」。
                        //    会话**不**结束：Server 仍在 listen、Client 仍在重试，重连上会再回调
                        //    onConnected 回到「已连接」；真正的终结交给重试耗尽（onDisconnected）
                        //    或空闲超时兜底（规格 remote.md「重连失败 N 次则退出会话」）。
                        if (mState != STATE_CONNECTED) {
                            return;   // 会话已结束 / 尚未建立 ⇒ 忽略
                        }
                        Log.w(TAG, "session: connection lost (" + reason + ")，等待重连");
                        setState(STATE_CONNECTING, null,
                                mServer ? "对端断开，等待重连…" : "连接中断，正在重连…");
                    }
                });
            }

            @Override
            public void onDisconnected(final String reason) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mState == STATE_CONNECTING || mState == STATE_CONNECTED) {
                            Log.w(TAG, "session: link lost (" + reason + ")");
                            endSessionInternal("已断开（" + reason + "）");
                        }
                    }
                });
            }
        });
        scheduleIdleCheck();
    }

    /** 用户点「结束遥控」：先礼貌发 BYE（对端好收尾），再本地断开。 */
    public void stopSession() {
        Log.i(TAG, "session: STOP by user");
        RemoteLink link = mLink;
        if (link != null) {
            link.send(RemoteProtocol.CMD_BYE);
        }
        endSessionInternal("已结束");
    }

    /** phone 角色：捕获层把音量键翻译成指令后调这里。会话不在 CONNECTED 返回 false。 */
    public boolean sendCommand(int cmd) {
        if (mState != STATE_CONNECTED) {
            return false;
        }
        mLastActiveAt = System.currentTimeMillis();
        RemoteLink link = mLink;
        boolean ok = link != null && link.send(cmd);
        if (ok) {
            scheduleIdleCheck();
        }
        return ok;
    }

    // ── 内部 ──

    private void endSessionInternal(String detail) {
        RemoteLink link = mLink;
        mLink = null;
        if (link != null) {
            link.stop();
        }
        setState(STATE_CLOSED, null, detail);
    }

    private void scheduleIdleCheck() {
        mHandler.removeCallbacks(mIdleCheck);
        mHandler.postDelayed(mIdleCheck, mIdleTimeoutMs);
    }

    private void setState(int state, String peerIp, String detail) {
        mState = state;
        mPeerIp = (state == STATE_CONNECTED) ? peerIp : null;
        mDetail = detail;
        for (StateListener l : mListeners) {
            l.onStateChanged(mState, mPeerIp, mDetail);
        }
    }
}
