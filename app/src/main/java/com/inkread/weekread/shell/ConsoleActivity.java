package com.inkread.weekread.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.inkread.weekread.R;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.remote.HidKeepAliveService;
import com.inkread.weekread.remote.HidLink;
import com.inkread.weekread.remote.RemoteLinkManager;
import com.inkread.weekread.remote.RemoteProtocol;
import com.inkread.weekread.ui.ConnectPageView;
import com.inkread.weekread.ui.FlipKeyView;
import com.inkread.weekread.ui.InkTheme;
import com.inkread.weekread.ui.StatusChipView;

/**
 * V1.2.0-beta · 手机端「遥控台」（Phone Console）。
 *
 * <p>TASK-037 立形态：启动直达本页 —— **整屏巨型翻页键** + **常驻状态胶囊** + 右上角齿轮进设置。
 * <p>TASK-038 补交互：滑动翻页 / 长按连翻（间隔可调）/ 方向交换 / 上下排布 ——
 *   三项偏好落 {@link CardPrefs#isBtFlipSwap}/{@link CardPrefs#isBtFlipVertical}/{@link CardPrefs#getBtFlipRepeatMs}，
 *   与热点通道的方向设置**命名隔离、互不影响**。
 * <p>TASK-040 补「连接」页：底部导航在「翻页 / 连接」两页之间切换；连接页 = 自检 + 设备列表 +
 *   实时日志 + 可被发现 / 电池白名单入口（{@link ConnectPageView}）。
 *
 * <p>🔴 **只服务手机端**（{@code install_role=phone}）；阅读器端不实例化本类、本布局，行为零差异。
 * <p>🔴 发送一律走既有 {@link RemoteLinkManager#sendCommand(int)}（HID 优先分派，一行不改）。
 * <p>🔴 **本类不新增任何能力**：状态是只读订阅（{@link HidKeepAliveService.StateListener}），不碰协议/权限。
 */
public class ConsoleActivity extends Activity {

    /** 🆕 TASK-040：ACTION_REQUEST_DISCOVERABLE 的请求码（与设置页 TASK-035 同款语义）。 */
    private static final int REQ_BT_DISCOVERABLE = 3601;

    private StatusChipView chip;
    private FlipKeyView flip;
    private TextView gear;
    private TextView darkBtn;
    private View flipPage;
    private View connectScroll;
    private View nav;
    private View topbar;
    private View root;
    private ConnectPageView connectPage;
    private TextView navFlip;
    private TextView navConnect;
    private boolean showingConnect = false;

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
        darkBtn = (TextView) findViewById(R.id.console_dark);
        root = findViewById(R.id.console_root);
        topbar = findViewById(R.id.console_topbar);
        nav = findViewById(R.id.console_nav);
        flipPage = findViewById(R.id.console_flip);
        connectScroll = findViewById(R.id.console_connect_scroll);
        connectPage = (ConnectPageView) findViewById(R.id.console_connect);
        navFlip = (TextView) findViewById(R.id.console_nav_flip);
        navConnect = (TextView) findViewById(R.id.console_nav_connect);

        // 🆕 TASK-040：连接页——「让本机可被发现」由本 Activity 发 startActivityForResult
        connectPage.setListener(new ConnectPageView.Listener() {
            @Override
            public void onRequestDiscoverable() {
                requestDiscoverable();
            }
        });
        navFlip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(false);
            }
        });
        navConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(true);
            }
        });

        flip.setListener(new FlipKeyView.Listener() {
            @Override
            public void onFlip(boolean next) {
                send(next);
            }
        });

        chip.setListener(new StatusChipView.Listener() {
            @Override
            public void onChipTap() {
                // 🆕 TASK-040：点胶囊 ⇒ 直接进「连接」页（自助排障），不再只是一句 Toast
                showPage(true);
            }

            @Override
            public void onSwapTap() {
                toggleSwap();
            }

            @Override
            public void onMoreTap() {
                showOptions();
            }
        });

        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConsoleActivity.this, SettingsActivity.class));
            }
        });

        // 🆕 TASK-041：状态栏深色模式一键切 —— 写偏好 + **立即重绘**（不 recreate、不闪烁）。
        darkBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !CardPrefs.isPhoneDarkMode(ConsoleActivity.this);
                CardPrefs.setPhoneDarkMode(ConsoleActivity.this, next);
                applyTheme();
                Toast.makeText(ConsoleActivity.this,
                        next ? R.string.console_dark_on : R.string.console_dark_off,
                        Toast.LENGTH_SHORT).show();
            }
        });

        applyTheme();          // TASK-041：进页先按偏好着色（先于渲染）
        applyFlipPrefs();      // TASK-038：把换向/排布/间隔灌进翻页键（先于渲染）
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HidKeepAliveService.addListener(mHidListener);
        // 🆕 TASK-040「保活自愈」：用户已启用「蓝牙遥控」却发现保活没在跑（进程被系统杀过 / 首次进遥控台）
        //   ⇒ 在**前台**主动拉起（`start()` 幂等；FGS 必须在 App 前台时发起，见 HidKeepAliveService 注释）。
        //   🔴 仅在用户开关为「开」时生效，绝不自作主张开启。
        if (CardPrefs.isBtControlEnabled(this) && !HidKeepAliveService.isRunning()) {
            HidKeepAliveService.start(this);
        }
        applyTheme();          // TASK-041：设置页可能改过深色偏好，回前台重着色
        applyFlipPrefs();      // 设置页可能改过（若将来暴露），回前台重灌一次
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
        if (showingConnect && connectPage != null) {
            connectPage.refresh();     // 🆕 TASK-040：连接页在前台时同步刷新
        }
    }

    /**
     * 🆕 TASK-041：按当前深色偏好**重新着色整屏**（幂等，可反复调）。
     *
     * <p>两条路径：
     * <ul>
     *   <li><b>自绘 View</b>（{@link StatusChipView}/{@link FlipKeyView}）每次 onDraw 都从
     *       {@link InkTheme} 语义 getter 取色 ⇒ 只需 {@code invalidate()}；</li>
     *   <li><b>原生 View</b>（根布局 / 顶栏 / 底栏 / 齿轮·月亮图标 / 连接页）着色在 XML/构造时定死
     *       ⇒ 需显式重设；连接页内部子视图多，直接 {@link ConnectPageView#rebuild()} 重建。</li>
     * </ul>
     * 🔴 **只服务手机端**（本 Activity 只在 phone 形态实例化）⇒ 阅读器端零影响。
     */
    private void applyTheme() {
        boolean dark = InkTheme.isDark(this);

        if (root != null) root.setBackgroundColor(InkTheme.paper(this));
        if (topbar != null) topbar.setBackgroundColor(InkTheme.paper(this));
        if (nav != null) nav.setBackgroundColor(InkTheme.paper2(this));

        // 顶栏两枚图标：月亮（亮色下显示"点了会变暗"）/ 太阳（深色下显示"点了会变亮"）
        if (darkBtn != null) {
            darkBtn.setText(dark ? "☀" : "☾");
            darkBtn.setTextColor(InkTheme.ink2(this));
        }
        if (gear != null) gear.setTextColor(InkTheme.ink2(this));

        // 连接页：子视图着色定死在 buildUi ⇒ 重建（未构建过则跳过）
        if (connectPage != null) connectPage.rebuild();

        // 自绘 View 重画
        if (chip != null) chip.invalidate();
        if (flip != null) flip.invalidate();

        applyNavColors();
    }

    /** 底部导航选中态着色（供 {@link #showPage} 与 {@link #applyTheme} 共用）。 */
    private void applyNavColors() {
        if (navFlip == null || navConnect == null) return;
        navFlip.setTextColor(showingConnect ? InkTheme.ink2(this) : InkTheme.bamboo(this));
        navConnect.setTextColor(showingConnect ? InkTheme.bamboo(this) : InkTheme.ink2(this));
    }

    // ── 🆕 TASK-040：两页切换（翻页 / 连接）──

    /** 在「翻页」页与「连接」页之间切换，并同步底部导航选中态（docs/09 §5.5）。 */
    private void showPage(boolean connect) {
        showingConnect = connect;
        flipPage.setVisibility(connect ? View.GONE : View.VISIBLE);
        connectScroll.setVisibility(connect ? View.VISIBLE : View.GONE);
        applyNavColors();
        if (connect && connectPage != null) connectPage.refresh();
    }

    @Override
    public void onBackPressed() {
        // 在「连接」页时，返回先回「翻页」页（避免一按就退出遥控台）
        if (showingConnect) {
            showPage(false);
            return;
        }
        super.onBackPressed();
    }

    /**
     * 🆕 TASK-040：让本机「可被发现」（复用 TASK-035 路径）。
     *
     * <p>走系统标准弹窗 {@code ACTION_REQUEST_DISCOVERABLE}（**用户必须自己点「允许」**，第三方 App
     * 无法静默开启）。⛔ 只让本机可被看见，**不做任何自动配对**。
     */
    private void requestDiscoverable() {
        try {
            Intent it = new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            it.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(it, REQ_BT_DISCOVERABLE);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.lab_bt_discoverable_fail, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BT_DISCOVERABLE) {
            if (resultCode > 0) {
                Toast.makeText(this, getString(R.string.lab_bt_discoverable_ok, resultCode),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.lab_bt_discoverable_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ── TASK-038：翻页交互偏好 ──

    /** 读三项偏好灌进翻页键（幂等，可在 onResume 反复调）。 */
    private void applyFlipPrefs() {
        boolean swap = CardPrefs.isBtFlipSwap(this);
        flip.setSwapped(swap);
        flip.setVertical(CardPrefs.isBtFlipVertical(this));
        flip.setRepeatMs(CardPrefs.getBtFlipRepeatMs(this));
        chip.setSwapChecked(swap);
    }

    /** 换向：一键交换左右/上下方向（落盘 + 立即生效）。 */
    private void toggleSwap() {
        boolean next = !CardPrefs.isBtFlipSwap(this);
        CardPrefs.setBtFlipSwap(this, next);
        flip.setSwapped(next);
        chip.setSwapChecked(next);
        Toast.makeText(this, next ? R.string.console_swap_on : R.string.console_swap_off,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 「更多」弹层：排布（左右/上下）+ 长按连翻间隔（快/中/慢）。
     *
     * <p>用系统 {@link AlertDialog} + 两个 {@link RadioGroup}，**零新增布局资源**、零依赖；
     * 改动即时落盘并生效（不设"确定/取消"——单选即时应用，符合遥控台"少一步"的取向）。
     */
    private void showOptions() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) InkTheme.dp(this, 20f);
        box.setPadding(pad, (int) InkTheme.dp(this, 8f), pad, 0);

        // ── 排布：左右 / 上下 ──
        box.addView(sectionLabel(R.string.console_opt_layout));
        final RadioGroup rgLayout = new RadioGroup(this);
        final RadioButton rbHz = radio(R.string.console_layout_hz);
        final RadioButton rbVt = radio(R.string.console_layout_vt);
        rbHz.setId(View.generateViewId());
        rbVt.setId(View.generateViewId());
        rgLayout.addView(rbHz);
        rgLayout.addView(rbVt);
        boolean vertical = CardPrefs.isBtFlipVertical(this);
        rgLayout.check(vertical ? rbVt.getId() : rbHz.getId());
        rgLayout.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                boolean vt = (id == rbVt.getId());
                CardPrefs.setBtFlipVertical(ConsoleActivity.this, vt);
                flip.setVertical(vt);
            }
        });
        box.addView(rgLayout);

        // ── 长按连翻间隔：快 / 中 / 慢 ──
        box.addView(sectionLabel(R.string.console_opt_repeat));
        final RadioGroup rgRepeat = new RadioGroup(this);
        final RadioButton rbFast = radio(R.string.console_repeat_fast);
        final RadioButton rbMid = radio(R.string.console_repeat_mid);
        final RadioButton rbSlow = radio(R.string.console_repeat_slow);
        rbFast.setId(View.generateViewId());
        rbMid.setId(View.generateViewId());
        rbSlow.setId(View.generateViewId());
        rgRepeat.addView(rbFast);
        rgRepeat.addView(rbMid);
        rgRepeat.addView(rbSlow);
        int ms = CardPrefs.getBtFlipRepeatMs(this);
        rgRepeat.check(ms <= 160 ? rbFast.getId()
                : (ms >= 320 ? rbSlow.getId() : rbMid.getId()));
        rgRepeat.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                int v = (id == rbFast.getId()) ? CardPrefs.BT_FLIP_REPEAT_MIN
                        : (id == rbSlow.getId() ? CardPrefs.BT_FLIP_REPEAT_MAX
                        : CardPrefs.BT_FLIP_REPEAT_DEFAULT);
                CardPrefs.setBtFlipRepeatMs(ConsoleActivity.this, v);
                flip.setRepeatMs(v);
            }
        });
        box.addView(rgRepeat);

        // ── TASK-039：音量键翻页（蓝牙通道）──
        box.addView(sectionLabel(R.string.console_opt_volkey));
        final CheckBox cbVol = new CheckBox(this);
        cbVol.setText(R.string.console_volkey_enable);
        cbVol.setTextColor(InkTheme.ink(this));
        cbVol.setChecked(CardPrefs.isBtVolkeyEnabled(this));
        cbVol.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                CardPrefs.setBtVolkeyEnabled(ConsoleActivity.this, checked);
            }
        });
        box.addView(cbVol);
        box.addView(noteLabel(R.string.console_volkey_note));

        new AlertDialog.Builder(this)
                .setTitle(R.string.console_opt_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private TextView sectionLabel(int strId) {
        TextView tv = new TextView(this);
        tv.setText(strId);
        tv.setTextSize(13f);
        tv.setTextColor(InkTheme.ink2(this));
        tv.setPadding(0, (int) InkTheme.dp(this, 14f), 0, (int) InkTheme.dp(this, 4f));
        return tv;
    }

    private RadioButton radio(int strId) {
        RadioButton rb = new RadioButton(this);
        rb.setText(strId);
        rb.setTextColor(InkTheme.ink(this));
        return rb;
    }

    /** 说明小字（低于正文一档、淡墨色）。 */
    private TextView noteLabel(int strId) {
        TextView tv = new TextView(this);
        tv.setText(strId);
        tv.setTextSize(12f);
        tv.setTextColor(InkTheme.ink3(this));
        tv.setLineSpacing(0f, 1.3f);
        tv.setPadding(0, (int) InkTheme.dp(this, 2f), 0, 0);
        return tv;
    }

    private void send(boolean next) {
        int cmd = next ? RemoteProtocol.CMD_PAGE_NEXT : RemoteProtocol.CMD_PAGE_PREV;
        boolean ok = RemoteLinkManager.get().sendCommand(cmd);
        if (!ok) {
            Toast.makeText(this, R.string.console_not_connected, Toast.LENGTH_SHORT).show();
        }
    }
}
