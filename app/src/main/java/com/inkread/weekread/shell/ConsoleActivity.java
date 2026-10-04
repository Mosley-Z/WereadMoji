package com.inkread.weekread.shell;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.inkread.weekread.R;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.remote.HidKeepAliveService;
import com.inkread.weekread.remote.HidLink;
import com.inkread.weekread.remote.RemoteLinkManager;
import com.inkread.weekread.remote.RemoteProtocol;
import com.inkread.weekread.ui.FlipKeyView;
import com.inkread.weekread.ui.StatusChipView;

/**
 * V1.2.0-beta · 手机端「遥控台」（Phone Console，TASK-037）。
 *
 * <p>把手机端的遥控从"设置页里的一个功能"升级为**独立产品形态**（竞品分析结论"形态差 > 功能差"）：
 * 启动直达本页 —— **整屏巨型翻页键** + **常驻状态胶囊** + 右上角齿轮进设置。
 *
 * <p>🔴 **只服务手机端**（{@code install_role=phone}）；阅读器端不实例化本类、本布局，行为零差异。
 * <p>🔴 发送一律走既有 {@link RemoteLinkManager#sendCommand(int)}（HID 优先分派，一行不改）。
 * <p>🔴 **本类不新增任何能力**：状态是只读订阅（{@link HidKeepAliveService.StateListener}），不碰协议/权限。
 */
public class ConsoleActivity extends Activity {

    private StatusChipView chip;
    private FlipKeyView flip;
    private TextView gear;

    /**
     * HID 状态订阅（TASK-033 既有回调）。🔴 回调可能在 binder 线程 ⇒ 一律切主线程再改 View。
     */
    private final HidKeepAliveService.StateListener mHidListener = new HidKeepAliveService.StateListener() {
        @Override
        public void onHidStateChanged() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_console);

        chip = (StatusChipView) findViewById(R.id.console_chip);
        flip = (FlipKeyView) findViewById(R.id.console_flip);
        gear = (TextView) findViewById(R.id.console_gear);

        flip.setListener(new FlipKeyView.Listener() {
            @Override
            public void onFlip(boolean next) {
                send(next);
            }
        });

        chip.setListener(new StatusChipView.Listener() {
            @Override
            public void onChipTap() {
                openConnectOrHint();
            }

            @Override
            public void onSwapTap() {
                // TASK-038 接线（本卡先占位，避免误触无声）
            }
        });

        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConsoleActivity.this, SettingsActivity.class));
            }
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HidKeepAliveService.addListener(mHidListener);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        HidKeepAliveService.removeListener(mHidListener);
    }

    /**
     * 按**服务真值**（不读偏好）刷新状态胶囊与翻页键可用性。
     *
     * <p>四态：未启用（灰）/ 等待连接（赭石）/ 已断开（赭石）/ 已连接（竹青）——
     * 与设置页「蓝牙控制」的状态判定同源（{@code SettingsActivity.refreshBtUi}）。
     */
    private void refresh() {
        HidLink hid = HidKeepAliveService.link();
        boolean running = HidKeepAliveService.isRunning() && hid != null;
        boolean connected = running && hid.isHostConnected();

        int st;
        String text;
        if (!running) {
            st = StatusChipView.ST_OFF;
            text = getString(R.string.console_st_off);
        } else if (connected) {
            st = StatusChipView.ST_CONNECTED;
            text = getString(R.string.console_st_connected, HidLink.safeName(hid.host()));
        } else if (hid.everConnected()) {
            st = StatusChipView.ST_LOST;
            text = getString(R.string.console_st_lost);
        } else {
            st = StatusChipView.ST_WAITING;
            text = getString(R.string.console_st_waiting);
        }
        chip.setStatus(st, text);
        flip.setConnected(connected);
    }

    private void send(boolean next) {
        int cmd = next ? RemoteProtocol.CMD_PAGE_NEXT : RemoteProtocol.CMD_PAGE_PREV;
        boolean ok = RemoteLinkManager.get().sendCommand(cmd);
        if (!ok) {
            Toast.makeText(this, R.string.console_not_connected, Toast.LENGTH_SHORT).show();
        }
    }

    /** TASK-037 阶段：连接管理尚未进遥控台（→ TASK-040），先给去设置的路标。 */
    private void openConnectOrHint() {
        Toast.makeText(this, R.string.console_connect_hint, Toast.LENGTH_LONG).show();
    }
}
