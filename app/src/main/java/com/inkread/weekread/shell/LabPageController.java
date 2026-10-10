package com.inkread.weekread.shell;

import com.inkread.weekread.R;
import com.inkread.weekread.core.Bill;
import com.inkread.weekread.core.BillStore;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.LockPrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PowerSettingsManager;
import com.inkread.weekread.core.WallpaperPrefs;
import com.inkread.weekread.feature.BillWallpaper;
import com.inkread.weekread.remote.HidKeepAliveService;
import com.inkread.weekread.remote.HidLink;
import com.inkread.weekread.remote.RemoteKeyService;
import com.inkread.weekread.remote.RemoteLinkManager;
import com.inkread.weekread.remote.RemoteRole;
import com.inkread.weekread.remote.ShakeDetector;
import com.inkread.weekread.ui.FoldHintView;
import com.inkread.weekread.ui.InkTheme;
import com.inkread.weekread.ui.SegTabView;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 实验室页控制器（🆕 TASK-045 / V1.2.0-beta 抽出）。
 *
 * <p>接管 {@code page_lab} 容器（{@code seg_lab} + 3 个子容器：翻页 / 锁屏密码 / 续航优化）的
 * <b>全部</b>逻辑 —— 即改造前 {@link SettingsActivity} 里
 * lab 相关段落的**逐字平移**（遥控会话、晃动翻页、蓝牙 HID、应用级软锁、省电指令）。
 * 🆕 TASK-072：原「热点翻页」+「蓝牙控制」两页合并为「翻页」页内的「连接方式」三选一。
 *
 * <p>🔴 为什么要抽：改造后实验室有两个宿主 ——
 * <ul>
 *   <li>{@link SettingsActivity}（phone 端入口：{@code ConsoleActivity} 齿轮进它，只装「实验室」段）；</li>
 *   <li>{@link MainActivity}（reader 端大标签③ 的 {@code mp_lab} 容器）。</li>
 * </ul>
 * 若各写一份，1800+ 行逻辑会双份维护。本类即两处共用的唯一实现。
 *
 * <p>⚠️ 宿主职责边界（本类**不做**）：
 * <ul>
 *   <li>页面切换（顶部页签 / 大标签）→ 宿主；</li>
 *   <li>深色主题递归染色（phone 专属）→ {@link SettingsActivity}；</li>
 *   <li>{@link com.inkread.weekread.a11y.CardA11yService#noteOwnUiForeground(boolean)} → 宿主生命周期。</li>
 * </ul>
 */
public class LabPageController {

    private final Activity host;

    // ── 🆕 TASK-072：「连接方式」三选一（原「热点翻页」+「蓝牙控制」两页合并）──
    /** 连接方式：关闭 */
    private static final int CONN_OFF = 0;
    /** 连接方式：热点（= remote_role != OFF 且未开蓝牙） */
    private static final int CONN_HOTSPOT = 1;
    /** 连接方式：蓝牙（= bt_control_enabled） */
    private static final int CONN_BT = 2;

    private RadioGroup rgConnMode;
    private RadioButton rbConnOff;
    private RadioButton rbConnHotspot;
    private RadioButton rbConnBt;
    private TextView tvConnHint;         // 状态胶囊：当前通道 + 连接态
    private View llConnHotspot;          // 热点配置块（选「热点」才展开）
    private View llConnBt;               // 蓝牙配置块（= 原 page_lab_bt，选「蓝牙」才展开）
    /** 防回环：refreshConnUi() 回填单选时会触发监听，置位期间忽略回调。 */
    private boolean mConnUiSyncing;
    /** 防回环：refreshRoleUi() 回填角色单选时置位。 */
    private boolean mRoleUiSyncing;
    /** 状态胶囊用：最近一次会话状态文案 / 蓝牙状态文案（由各自刷新函数写入）。 */
    private String mLastSessionText;
    private String mLastBtStatus;

    // ── V1.0 Beta（TASK-018）实验室 · 遥控翻页 ──
    private RadioButton rbRoleOff;
    private RadioButton rbRoleEink;
    private RadioButton rbRolePhone;
    private TextView tvRoleHint;         // 按角色变化的说明文本
    private View llRemoteSession;        // 会话控件容器（role != off 才显示）
    private Button btnRemoteStart;
    private Button btnRemoteStop;
    private TextView tvRemoteStatus;     // 「状态：…」
    private EditText etRemoteIdle;       // 空闲自动断开（秒）

    // ── 🆕 TASK-029 实验室 · 遥控翻页 · 手机端「晃动翻页」──
    private View llShakeBlock;           // 晃动翻页块（仅 role=手机 显示）
    private CheckBox cbShakeEnabled;     // ① 总开关
    private RadioGroup rgShakeSens;      // 🆕 ② 灵敏度三档（低/中/高，默认中）
    private RadioButton rbShakeSensLow;
    private RadioButton rbShakeSensMid;
    private RadioButton rbShakeSensHigh;
    private CheckBox cbShakeLrRev;       // ③ 左右晃方向反转
    private CheckBox cbShakeUdRev;       // ④ 上下晃方向反转
    private View llShakeAxis;            // 🆕 TASK-042：响应方向行（左右 / 上下 多选）
    private CheckBox cbShakeAxisLr;      //   左右晃是否响应
    private CheckBox cbShakeAxisUd;      //   上下晃是否响应
    private TextView tvShakeMap;         // ⑤ 动态「当前映射」自证行
    /** 防回环：refreshShakeUi() 回填控件时会触发监听，置位期间忽略回调。 */
    private boolean mShakeUiSyncing;

    // ── 🆕 TASK-073 实验室 · 翻页 · 「本机晃动」（晃本机 ⇒ 翻本机上的微信读书）──
    private CheckBox cbShakeLocal;            // 本机晃动总开关
    private TextView tvShakeLocalNote;        // 动态自证行（已开启→当前映射 / 未开启）
    private TextView tvShakeLocalUnsupported; // 无加速度计时的如实提示
    /** 🆕 TASK-073（二改）· 「翻页方式」组（点击贴边 / 横向滑动）：仅开关打开且有加速度计时可见。 */
    private View llShakeLocalMode;
    private RadioGroup rgShakeLocalMode;
    private RadioButton rbShakeLocalTap;
    private RadioButton rbShakeLocalSwipe;
    /** 本机是否具备加速度计（无则整组置灰 + 提示，不假装能用）。bind 时探测一次。 */
    private boolean mHasAccel;
    /** 🆕 TASK-074 · 「四动作方向」自选（左/右/上/下 各一个单选，两行×两列）：与「翻页方式」同显隐（开关打开才可见）。 */
    private View llShakeLocalDir;
    private RadioGroup rgShakeDirLeft;
    private RadioGroup rgShakeDirRight;
    private RadioGroup rgShakeDirUp;
    private RadioGroup rgShakeDirDown;

    // ── 🆕 TASK-033 实验室 · 蓝牙控制（HID 外设通道）──
    private TextView tvBtIntro;          // 页顶简介（手机端 / 墨水屏端文案不同）
    private View llBtControls;           // 手机端控件块（墨水屏端隐藏）
    private CheckBox cbBtEnabled;        // 启用开关
    private TextView tvBtUnsupported;    // 「本机不支持」红字（SDK<28 或 ROM 无 HID profile）
    private TextView tvBtStatus;         // 「状态：…」
    private TextView tvBtReconnectGuide; // 🆕 TASK-034：断链恢复引导（仅「已注册未连接」时显示）
    private TextView tvBtPairGuide;      // 🆕 TASK-035：首次配对引导（仅「已启用但从未连过」时显示）
    private View llBtDiscover;           // 🆕 TASK-035：让本机可被发现按钮块（与配对引导同显隐）
    private Button btnBtDiscoverable;
    private View llBtTest;               // 测试翻页行（连接后才显示）
    private Button btnBtTestPrev;
    private Button btnBtTestNext;
    private TextView tvBtEinkNote;       // 墨水屏端说明（手机端隐藏）
    private View foldRoleMore;           // 🆕 TASK-065：手机角色「注意事项」折叠卡标题行
    private View tvRoleMore;             // 🆕 TASK-065：手机角色「注意事项」折叠卡内容
    /** 防回环：refreshBtUi() 回填勾选态时会触发监听，置位期间忽略回调。 */
    private boolean mBtUiSyncing;

    /** TASK-022：锁屏密码子页的控件（refreshLockUi 要用，故存字段） */
    private CheckBox cbLockEnabled;
    private TextView tvLockStatus;
    private TextView tvLockBgStatus;
    /** TASK-022-R1：锁屏背景图路径输入框（默认 Pictures/背景.jpg） */
    private EditText etLockBgPath;
    /** 回滚「未设密码就勾启用」时抑制监听递归 */
    private boolean lockUiSyncing;

    /** TASK-023：续航子页的控件（refreshPowerUi 要用，故存字段） */
    private CheckBox cbPowerWifiNotif;
    private CheckBox cbPowerAnim;
    private CheckBox cbPowerScreensaver;
    private TextView tvPowerPerm;
    private TextView tvPowerCmd;
    /** 同步续航勾选态时抑制监听递归 */
    private boolean powerUiSyncing;

    // ── 🆕 TASK-079 实验室 · 壁纸管家（数据层 = TASK-078 / 渲染 = feature.BillWallpaper）──
    private EditText etWallSrc;              // 收图源路径（照锁屏背景那套：可编辑路径，不走 SAF）
    private TextView tvWallPool;             // 图片池状态 + 已收列表
    private CheckBox cbWallRot;              // 轮换开关
    private EditText etWallInterval;         // 轮换间隔（天）
    private TextView tvWallRotStatus;        // 轮换自证行
    private RadioGroup rgWallScope;          // 应用范围（三项；Daydream 两项置灰）
    private RadioButton rbWallScopeSoft;
    private RadioButton rbWallScopeDream;
    private RadioButton rbWallScopeBoth;
    private RadioGroup rgBillwMode;          // 生成账单壁纸：周期（周 / 月）
    private RadioButton rbBillwWeek;
    private RadioButton rbBillwMonth;
    private TextView tvBillwSerial;          // 待生成的那一期（单号 · 区间）
    private Button btnBillwOlder;
    private Button btnBillwNewer;
    private Button btnBillwGen;
    private TextView tvBillwStatus;          // 生成进度 / 结果
    private TextView tvWallEffective;        // 当前生效（同软锁口径）
    private Button btnWallApply;
    /** 防回环：轮换开关 / 间隔回填时置位（与锁屏、续航同款）。 */
    private boolean mWallUiSyncing;
    /** 🆕 生成中标记：防重复点「生成」（生成走后台线程，不阻塞 UI）。 */
    private boolean mBillwBusy;
    /** 当前选中的账单期（秒）；≤0 = 未选 / 该周期无账单。 */
    private long mBillwStart;

    // ── 🆕 TASK-079：实验室子页装配结果（供「深链到指定子页」用）──
    private SegTabView labSeg;               // seg_lab
    private View[] labPage;                  // 逻辑子页容器（下标 = 原页下标）
    private int[] labKeep;                   // 可见表：UI 下标 → 原页下标
    private int labCount;                    // 可见子页数
    private ScrollView labScroll;            // 本页所在的滚动容器

    /** 申请「读取存储」权限的请求码（TASK-022-R1：载入背景图用）。 */
    private static final int REQ_PERM_BG = 3302;
    /** 🆕 TASK-035：ACTION_REQUEST_DISCOVERABLE 的请求码。 */
    private static final int REQ_BT_DISCOVERABLE = 3501;
    /** 🆕 TASK-079：收图读共享存储的权限请求码（与锁屏背景分开，避免回调串台）。 */
    private static final int REQ_PERM_WALL = 3303;

    public LabPageController(Activity host) {
        this.host = host;
    }

    // ══════════════════════ 装配 ══════════════════════

    /** 装配本页全部控件与监听（等价于改造前 {@link SettingsActivity#onCreate} 的 lab 段）。 */
    public void bind() {
        // ── 🆕 TASK-072：「连接方式」三选一（翻页页顶）──
        rgConnMode = (RadioGroup) host.findViewById(R.id.rg_conn_mode);
        rbConnOff = (RadioButton) host.findViewById(R.id.rb_conn_off);
        rbConnHotspot = (RadioButton) host.findViewById(R.id.rb_conn_hotspot);
        rbConnBt = (RadioButton) host.findViewById(R.id.rb_conn_bt);
        tvConnHint = (TextView) host.findViewById(R.id.tv_conn_hint);
        llConnHotspot = host.findViewById(R.id.ll_conn_hotspot);
        llConnBt = host.findViewById(R.id.page_lab_bt);   // 蓝牙分支（id 沿用，TASK-072）

        // ── V1.0 Beta（TASK-018）实验室 · 遥控翻页 ──
        rbRoleOff = (RadioButton) host.findViewById(R.id.rb_role_off);
        rbRoleEink = (RadioButton) host.findViewById(R.id.rb_role_eink);
        rbRolePhone = (RadioButton) host.findViewById(R.id.rb_role_phone);
        tvRoleHint = (TextView) host.findViewById(R.id.tv_role_hint);
        llRemoteSession = host.findViewById(R.id.ll_remote_session);
        btnRemoteStart = (Button) host.findViewById(R.id.btn_remote_start);
        btnRemoteStop = (Button) host.findViewById(R.id.btn_remote_stop);
        tvRemoteStatus = (TextView) host.findViewById(R.id.tv_remote_status);
        etRemoteIdle = (EditText) host.findViewById(R.id.et_remote_idle);
        llShakeBlock = host.findViewById(R.id.ll_shake_block);          // 🆕 TASK-029
        cbShakeEnabled = (CheckBox) host.findViewById(R.id.cb_shake_enabled);
        rgShakeSens = (RadioGroup) host.findViewById(R.id.rg_shake_sens);   // 🆕 灵敏度三档
        rbShakeSensLow = (RadioButton) host.findViewById(R.id.rb_shake_sens_low);
        rbShakeSensMid = (RadioButton) host.findViewById(R.id.rb_shake_sens_mid);
        rbShakeSensHigh = (RadioButton) host.findViewById(R.id.rb_shake_sens_high);
        cbShakeLrRev = (CheckBox) host.findViewById(R.id.cb_shake_lr_rev);
        cbShakeUdRev = (CheckBox) host.findViewById(R.id.cb_shake_ud_rev);
        llShakeAxis = host.findViewById(R.id.ll_shake_axis);               // 🆕 TASK-042
        cbShakeAxisLr = (CheckBox) host.findViewById(R.id.cb_shake_axis_lr);
        cbShakeAxisUd = (CheckBox) host.findViewById(R.id.cb_shake_axis_ud);
        tvShakeMap = (TextView) host.findViewById(R.id.tv_shake_map);

        // ── 🆕 TASK-073：「本机晃动」（与 role / 连接方式**都无关** ⇒ 不随 role 显隐）──
        cbShakeLocal = (CheckBox) host.findViewById(R.id.cb_shake_local);
        tvShakeLocalNote = (TextView) host.findViewById(R.id.tv_shake_local_note);
        tvShakeLocalUnsupported = (TextView) host.findViewById(R.id.tv_shake_local_unsupported);
        llShakeLocalMode = host.findViewById(R.id.ll_shake_local_mode);       // 🆕 翻页方式容器
        rgShakeLocalMode = (RadioGroup) host.findViewById(R.id.rg_shake_local_mode);
        rbShakeLocalTap = (RadioButton) host.findViewById(R.id.rb_shake_local_tap);
        rbShakeLocalSwipe = (RadioButton) host.findViewById(R.id.rb_shake_local_swipe);
        // 🆕 TASK-074 · 四动作方向自选（左/右/上/下）
        llShakeLocalDir = host.findViewById(R.id.ll_shake_local_dir);
        rgShakeDirLeft = (RadioGroup) host.findViewById(R.id.rg_shake_dir_left);
        rgShakeDirRight = (RadioGroup) host.findViewById(R.id.rg_shake_dir_right);
        rgShakeDirUp = (RadioGroup) host.findViewById(R.id.rg_shake_dir_up);
        rgShakeDirDown = (RadioGroup) host.findViewById(R.id.rg_shake_dir_down);
        // 🆕 折叠卡（TASK-065 同范式）：把两段说明（本机翻页 / 一起翻页）收进折叠，首屏只留「标题 + 开关」。
        //    🔴 只切内容容器可见性，不改任何功能行为。
        bindFold(R.id.fold_shake_local, R.id.ll_fold_shake_local, R.string.fold_shake_local);
        mHasAccel = hasAccelerometer();

        // ── 🆕 TASK-033 实验室 · 蓝牙控制 ──
        tvBtIntro = (TextView) host.findViewById(R.id.tv_bt_intro);
        llBtControls = host.findViewById(R.id.ll_bt_controls);
        cbBtEnabled = (CheckBox) host.findViewById(R.id.cb_bt_enabled);
        tvBtUnsupported = (TextView) host.findViewById(R.id.tv_bt_unsupported);
        tvBtStatus = (TextView) host.findViewById(R.id.tv_bt_status);
        tvBtReconnectGuide = (TextView) host.findViewById(R.id.tv_bt_reconnect_guide);
        tvBtPairGuide = (TextView) host.findViewById(R.id.tv_bt_pair_guide);
        // 🆕 TASK-066 · A3：墨水屏端**零彩色** —— 两句引导文案的「警示红」改为按角色取色。
        //    · phone 端 ⇒ 原值 #B00020，**逐像素不变**（验收 A5）；
        //    · 墨水屏端 ⇒ InkTheme.ink2(host) = 单色深灰 #3C3C3C（靠加粗/文案承担警示）。
        //    布局里的 #FFB00020 只是「未绑定前的默认值」，bind() 一定会覆盖。
        applyGuideColor(tvBtReconnectGuide);
        applyGuideColor(tvBtPairGuide);
        llBtDiscover = host.findViewById(R.id.ll_bt_discover);
        btnBtDiscoverable = (Button) host.findViewById(R.id.btn_bt_discoverable);
        llBtTest = host.findViewById(R.id.ll_bt_test);
        btnBtTestPrev = (Button) host.findViewById(R.id.btn_bt_test_prev);
        btnBtTestNext = (Button) host.findViewById(R.id.btn_bt_test_next);
        tvBtEinkNote = (TextView) host.findViewById(R.id.tv_bt_eink_note);

        // ── 🆕 TASK-065 折叠引导卡（首次配对 / 连不上怎么办）——
        //    长段说明收进折叠卡，首屏只留"状态 + 主操作 + 一句提示"（一屏三行内）。
        bindFold(R.id.fold_bt_pair, R.id.ll_fold_bt_pair, R.string.fold_bt_pair);
        bindFold(R.id.fold_bt_more, R.id.ll_fold_bt_more, R.string.fold_bt_more);

        // ── 🆕 TASK-065 热点翻页页的角色「注意事项」折叠卡（仅 role=手机 可见）──
        foldRoleMore = host.findViewById(R.id.fold_role_more);
        tvRoleMore = host.findViewById(R.id.tv_role_more);
        bindFold(R.id.fold_role_more, R.id.tv_role_more, R.string.fold_role_more);


        // ── 🆕 TASK-022 锁屏密码子页（应用级软锁）──
        // 落盘在 LockPrefs（盐 + SHA-256，非明文）；开关默认关 ⇒ 老用户升级后零差异。
        cbLockEnabled = (CheckBox) host.findViewById(R.id.cb_lock_enabled);
        tvLockStatus = (TextView) host.findViewById(R.id.tv_lock_status);
        tvLockBgStatus = (TextView) host.findViewById(R.id.tv_lock_bg_status);
        etLockBgPath = (EditText) host.findViewById(R.id.et_lock_bg_path);
        // 输入框只在这里初始化一次（不在 refreshLockUi 里回填，免得把用户正在输的内容冲掉）
        String bgCur = LockPrefs.getBgPath(host);
        etLockBgPath.setText(bgCur.length() == 0 ? LockPrefs.DEFAULT_BG_REL : bgCur);
        final EditText etLockPin = (EditText) host.findViewById(R.id.et_lock_pin);

        cbLockEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (lockUiSyncing) return;
                if (checked && !LockPrefs.hasPin(host)) {
                    // 没设密码就启用 = 一开屏就锁死且无密码可解 ⇒ 拒绝并回滚勾选
                    lockUiSyncing = true;
                    b.setChecked(false);
                    lockUiSyncing = false;
                    Toast.makeText(host, R.string.lock_need_pin, Toast.LENGTH_LONG).show();
                    return;
                }
                LockPrefs.setEnabled(host, checked);
                refreshLockUi();
            }
        });

        host.findViewById(R.id.btn_lock_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pin = etLockPin.getText().toString().trim();
                if (!LockPrefs.isValidPin(pin)) {
                    Toast.makeText(host, R.string.lock_pin_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                LockPrefs.setPin(host, pin);
                etLockPin.setText("");
                refreshLockUi();
                Toast.makeText(host, R.string.lock_saved, Toast.LENGTH_SHORT).show();
            }
        });

        host.findViewById(R.id.btn_lock_bg_load).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadLockBg();
            }
        });

        host.findViewById(R.id.btn_lock_bg_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LockPrefs.clearBg(host);
                etLockBgPath.setText(LockPrefs.DEFAULT_BG_REL);
                refreshLockUi();
            }
        });

        refreshLockUi();

        // ── 🆕 TASK-023 续航子页接线（省电指令）──
        // 写入需 WRITE_SECURE_SETTINGS（一次性 pm grant）。未授权 ⇒ 点击拦截 + 提示，不静默失败。
        tvPowerPerm = (TextView) host.findViewById(R.id.tv_power_perm);
        tvPowerCmd = (TextView) host.findViewById(R.id.tv_power_cmd);
        cbPowerWifiNotif = (CheckBox) host.findViewById(R.id.cb_power_wifi_notif);
        cbPowerAnim = (CheckBox) host.findViewById(R.id.cb_power_anim);
        cbPowerScreensaver = (CheckBox) host.findViewById(R.id.cb_power_screensaver);
        wirePowerItem(cbPowerWifiNotif, PowerSettingsManager.ITEM_WIFI_NET_NOTIF);
        wirePowerItem(cbPowerAnim, PowerSettingsManager.ITEM_ANIM);
        wirePowerItem(cbPowerScreensaver, PowerSettingsManager.ITEM_SCREENSAVER);
        host.findViewById(R.id.btn_power_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyGrantCmd();
            }
        });
        host.findViewById(R.id.btn_power_battery_opt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatteryOpt();
            }
        });
        refreshPowerUi();

        // ── 🆕 TASK-079 壁纸管家子页（数据层 = TASK-078；渲染 = feature/BillWallpaper）──
        //   ① 图片池（可编辑路径 + 收图 + 已收列表 + 清空） ② 轮换（开关 + 间隔天）
        //   ③ 应用范围（Daydream 两项**置灰**） ④ 生成账单壁纸（后台线程） ⑤ 当前生效
        etWallSrc = (EditText) host.findViewById(R.id.et_wall_src);
        tvWallPool = (TextView) host.findViewById(R.id.tv_wall_pool);
        cbWallRot = (CheckBox) host.findViewById(R.id.cb_wall_rot);
        etWallInterval = (EditText) host.findViewById(R.id.et_wall_interval);
        tvWallRotStatus = (TextView) host.findViewById(R.id.tv_wall_rot_status);
        rgWallScope = (RadioGroup) host.findViewById(R.id.rg_wall_scope);
        rbWallScopeSoft = (RadioButton) host.findViewById(R.id.rb_wall_scope_soft);
        rbWallScopeDream = (RadioButton) host.findViewById(R.id.rb_wall_scope_dream);
        rbWallScopeBoth = (RadioButton) host.findViewById(R.id.rb_wall_scope_both);
        rgBillwMode = (RadioGroup) host.findViewById(R.id.rg_billw_mode);
        rbBillwWeek = (RadioButton) host.findViewById(R.id.rb_billw_week);
        rbBillwMonth = (RadioButton) host.findViewById(R.id.rb_billw_month);
        tvBillwSerial = (TextView) host.findViewById(R.id.tv_billw_serial);
        btnBillwOlder = (Button) host.findViewById(R.id.btn_billw_older);
        btnBillwNewer = (Button) host.findViewById(R.id.btn_billw_newer);
        btnBillwGen = (Button) host.findViewById(R.id.btn_billw_gen);
        tvBillwStatus = (TextView) host.findViewById(R.id.tv_billw_status);
        tvWallEffective = (TextView) host.findViewById(R.id.tv_wall_effective);
        btnWallApply = (Button) host.findViewById(R.id.btn_wall_apply);
        // 收图源默认给锁屏那套相对路径的示范（**不预填**：空着更不容易误收无关文件）
        etWallSrc.setText("");

        host.findViewById(R.id.btn_wall_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collectWallImage();
            }
        });
        host.findViewById(R.id.btn_wall_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearWallPool();
            }
        });
        cbWallRot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mWallUiSyncing) return;
                WallpaperPrefs.setRotationOn(host, checked);
                refreshWallUi();
            }
        });
        // 间隔：逐字符解析，但只在"落在合法区间且值真变了"才落盘（照 etRemoteIdle 的做法）
        etWallInterval.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (mWallUiSyncing) return;
                String t = (s == null) ? "" : s.toString().trim();
                if (t.length() == 0) return;                 // 清空中间态：不落盘、不回填
                int v;
                try {
                    v = Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    return;
                }
                if (v < 1 || v > 365) return;                // 越界中间态
                if (v != WallpaperPrefs.intervalDays(host)) {
                    WallpaperPrefs.setIntervalDays(host, v);
                    // 🔴 只回填勾选 + 自证行，**不重设输入框**（否则会把光标顶走）
                    syncWallRotUi();
                }
            }
        });
        etWallInterval.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) refreshWallUi();              // 失焦回填成合法值
            }
        });
        rgWallScope.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                if (mWallUiSyncing) return;
                // 另两项在布局里 enabled=false ⇒ 手指点不到；这里只处理「仅软锁」
                if (checkedId == R.id.rb_wall_scope_soft) {
                    WallpaperPrefs.setScope(host, WallpaperPrefs.SCOPE_SOFT);
                }
                refreshWallUi();
            }
        });
        rgBillwMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                mBillwStart = -1;                            // 换周期 ⇒ 回到该周期最新一期
                refreshBillwPick();
            }
        });
        btnBillwOlder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepBillw(+1);
            }
        });
        btnBillwNewer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepBillw(-1);
            }
        });
        btnBillwGen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generateBillWallpaper();
            }
        });
        btnWallApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyWallToLock();
            }
        });
        refreshWallUi();

        // ── V1.0 Beta（TASK-018）：实验室 · 遥控翻页 ──
        //
        // 角色三选一：写 remote_role → 重算本页显隐（说明 / 会话控件 / 卡片分区）。
        // 🔴 不调 CardA11yService.sync()：遥控与卡片无关；卡片分区显隐只由本页决定。
        int roleNow = CardPrefs.getRemoteRole(host);
        rbRoleOff.setChecked(roleNow == CardPrefs.REMOTE_ROLE_OFF);
        rbRoleEink.setChecked(roleNow == CardPrefs.REMOTE_ROLE_EINK);
        rbRolePhone.setChecked(roleNow == CardPrefs.REMOTE_ROLE_PHONE);
        CompoundButton.OnCheckedChangeListener roleL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mRoleUiSyncing) return;      // 防回环：refreshRoleUi 回填时不落盘（TASK-072）
                if (!checked) return;            // 只管"被选中的那个"
                int id = b.getId();
                int role = (id == R.id.rb_role_eink) ? CardPrefs.REMOTE_ROLE_EINK
                        : (id == R.id.rb_role_phone) ? CardPrefs.REMOTE_ROLE_PHONE
                        : CardPrefs.REMOTE_ROLE_OFF;
                CardPrefs.setRemoteRole(host, role);
                if (role == CardPrefs.REMOTE_ROLE_OFF
                        && RemoteLinkManager.get().getState() != RemoteLinkManager.STATE_IDLE) {
                    RemoteLinkManager.get().stopSession();   // 关遥控顺带断会话
                }
                refreshRoleUi();
                // 🆕 TASK-072：角色直接决定「连接方式」派生值 ⇒ 同步单选 + 分支显隐
                refreshConnUi();
                ShakeDetector.sync(host);
            }
        };
        rbRoleOff.setOnCheckedChangeListener(roleL);
        rbRoleEink.setOnCheckedChangeListener(roleL);
        rbRolePhone.setOnCheckedChangeListener(roleL);

        // ── 🆕 TASK-072：「连接方式」三选一 —— 原两页的**唯一总开关** ──
        //
        // 派生规则（读物 = 两套既有 pref，不新增第六条偏好；卡片 R2 定案）：
        //   蓝牙（bt_control_enabled=true）优先；否则 remote_role != OFF ⇒ 热点；否则关闭。
        // 写入规则：
        //   关闭   ⇒ remote_role=OFF + bt=false（并收尾：断 TCP 会话、停 HID 保活）
        //   热点   ⇒ bt=false（停 HID）；remote_role 若为 OFF，按本机形态取默认（手机端=手机 / 阅读器端=墨水屏）
        //   蓝牙   ⇒ bt=true（启 HID）+ 断 TCP 会话；手机端顺带 remote_role=手机（否则音量键捕获不生效）
        // 🔴 切走热点 ⇒ 会话结束、45678 监听归 0；切走蓝牙 ⇒ HID 注册停止（验收 A3）。
        RadioGroup.OnCheckedChangeListener connL = new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                if (mConnUiSyncing) return;      // 防回环：refreshConnUi 回填时不落盘
                if (checkedId == R.id.rb_conn_off) applyConnOff();
                else if (checkedId == R.id.rb_conn_hotspot) applyConnHotspot();
                else if (checkedId == R.id.rb_conn_bt) applyConnBt();
                refreshConnUi();
                refreshRoleUi();
                refreshBtUi();
            }
        };
        rgConnMode.setOnCheckedChangeListener(connL);
        refreshConnUi();   // 初次按既有 pref 回填

        // 开始 / 结束遥控（按需连接策略的唯一入口）
        btnRemoteStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!RemoteKeyService.isAlive()) {
                    toast("先到系统无障碍里打开「微读墨记 · 遥控」，再点「开始遥控」");
                }
                // 🔴 兜底（TASK-018 上机实测教训）：这里是主线程按钮入口，任何未捕获异常都会
                //    **直接杀掉进程** —— 进程一死，本 App 的两个无障碍服务被系统一起解绑/停用，
                //    用户侧观感就是「两边无障碍一起崩溃关闭 + App 崩溃」，且要手动重开无障碍。
                //    故 fail-closed：把失败只写进状态行，不炸进程。
                try {
                    RemoteLinkManager.get().startSession(host);
                } catch (Throwable t) {
                    if (tvRemoteStatus != null) {
                        tvRemoteStatus.setText(host.getString(R.string.lab_status_prefix) + "启动失败：" + t);
                    }
                    return;
                }
                refreshSessionButtons(RemoteLinkManager.get().getState());
            }
        });
        btnRemoteStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RemoteLinkManager.get().stopSession();
            }
        });

        // 空闲超时：输入过程逐字符解析，但只在"落在合法区间且值真变了"才落盘
        // （否则打一个字就写一次 prefs）。失焦时把非法/空值回填成已存值。
        etRemoteIdle.setText(String.valueOf(CardPrefs.getRemoteIdleTimeout(host)));
        etRemoteIdle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String t = (s == null) ? "" : s.toString().trim();
                if (t.length() == 0) return;             // 清空中间态：不落盘、不回填
                int v;
                try {
                    v = Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    return;
                }
                if (v < CardPrefs.REMOTE_IDLE_MIN || v > CardPrefs.REMOTE_IDLE_MAX) return;  // 越界中间态
                if (v != CardPrefs.getRemoteIdleTimeout(host)) {
                    CardPrefs.setRemoteIdleTimeout(host, v);
                }
            }
        });
        etRemoteIdle.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {                          // 失焦回填成合法值
                    etRemoteIdle.setText(String.valueOf(CardPrefs.getRemoteIdleTimeout(host)));
                }
            }
        });

        // 去系统无障碍设置（角色切换后需重开对应服务 —— A8 文案硬约束）
        host.findViewById(R.id.btn_lab_a11y).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    host.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    toast("打不开无障碍设置：" + t);
                }
            }
        });

        // ── 🆕 TASK-029：手机端「晃动翻页」总开关 + 两个反转开关（仅 role=手机 显示）──
        //
        // 落盘在 CardPrefs（boolean，默认全 false）⇒ 老用户升级后**零差异**。
        // 每次改动：写偏好 → 刷「当前映射」自证行 → 通知捕获层重算门控
        //（总开关关掉要**立即停采样**；这就是设置页直接调 ShakeDetector.sync 的原因）。
        CompoundButton.OnCheckedChangeListener shakeL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mShakeUiSyncing) return;      // 防回环：refreshShakeUi 回填时不落盘
                int id = b.getId();
                if (id == R.id.cb_shake_enabled) {
                    CardPrefs.setShakeEnabled(host, checked);
                } else if (id == R.id.cb_shake_lr_rev) {
                    CardPrefs.setShakeLrRev(host, checked);
                } else if (id == R.id.cb_shake_axis_lr) {
                    // 🆕 TASK-042：禁止双不勾 —— 若这次取消会同时关掉两组，则弹回。
                    if (!checked && !CardPrefs.isShakeAxisUdEnabled(host)) {
                        mShakeUiSyncing = true;
                        try { cbShakeAxisLr.setChecked(true); } finally { mShakeUiSyncing = false; }
                        toast(host.getString(R.string.lab_shake_axis_need_one));
                        return;
                    }
                    CardPrefs.setShakeAxisLrEnabled(host, checked);
                } else if (id == R.id.cb_shake_axis_ud) {
                    if (!checked && !CardPrefs.isShakeAxisLrEnabled(host)) {
                        mShakeUiSyncing = true;
                        try { cbShakeAxisUd.setChecked(true); } finally { mShakeUiSyncing = false; }
                        toast(host.getString(R.string.lab_shake_axis_need_one));
                        return;
                    }
                    CardPrefs.setShakeAxisUdEnabled(host, checked);
                } else {
                    CardPrefs.setShakeUdRev(host, checked);
                }
                refreshShakeUi();
                refreshShakeLocalUi();      // 🆕 TASK-073：本机晃动映射行随同一套反转开关变
                ShakeDetector.sync(host);   // G2 即时生效
            }
        };
        cbShakeEnabled.setOnCheckedChangeListener(shakeL);
        cbShakeLrRev.setOnCheckedChangeListener(shakeL);
        cbShakeUdRev.setOnCheckedChangeListener(shakeL);
        cbShakeAxisLr.setOnCheckedChangeListener(shakeL);   // 🆕 TASK-042
        cbShakeAxisUd.setOnCheckedChangeListener(shakeL);

        // ── 🆕 TASK-073：本机晃动总开关（与上方对端晃动**各自独立**，可同时开）──
        //   写偏好 ⇒ 刷自证行 ⇒ sync 让捕获层即时启停本机采样（关掉要 ≤1s 停）。
        cbShakeLocal.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mShakeUiSyncing) return;      // 防回环：refreshShakeLocalUi 回填时不落盘
                CardPrefs.setShakeLocalEnabled(host, checked);
                refreshShakeLocalUi();
                ShakeDetector.sync(host);
            }
        });

        // 🆕 TASK-073（二改）· 翻页方式二选一（点击贴边 / 横向滑动）——
        //   只落盘偏好：注入层 injectLocal **每次现读** shake_local_mode ⇒ 无需通知/重建采样。
        rgShakeLocalMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                if (mShakeUiSyncing) return;      // 防回环：refreshShakeLocalUi 回填时不落盘
                int mode = (checkedId == R.id.rb_shake_local_swipe)
                        ? CardPrefs.SHAKE_LOCAL_MODE_SWIPE : CardPrefs.SHAKE_LOCAL_MODE_TAP;
                CardPrefs.setShakeLocalMode(host, mode);
            }
        });

        // 🆕 TASK-074 · 四动作方向自选（左/右/上/下 各一个单选，互不冲突）——
        //   每格独立落盘（setShakeLocalActNext）⇒ 四个动作互不牵连；
        //   ShakeDetector.fire **每次现读** ⇒ 改完立即生效，无需通知/重建采样。
        RadioGroup.OnCheckedChangeListener dirL = new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                if (mShakeUiSyncing) return;      // 防回环：refreshShakeLocalUi 回填时不落盘
                int id = g.getId();
                final int act;
                if (id == R.id.rg_shake_dir_left) {
                    act = CardPrefs.SHAKE_ACT_LEFT;
                } else if (id == R.id.rg_shake_dir_right) {
                    act = CardPrefs.SHAKE_ACT_RIGHT;
                } else if (id == R.id.rg_shake_dir_up) {
                    act = CardPrefs.SHAKE_ACT_UP;
                } else {
                    act = CardPrefs.SHAKE_ACT_DOWN;
                }
                boolean next = (checkedId == R.id.rb_shake_dir_left_next
                        || checkedId == R.id.rb_shake_dir_right_next
                        || checkedId == R.id.rb_shake_dir_up_next
                        || checkedId == R.id.rb_shake_dir_down_next);
                CardPrefs.setShakeLocalActNext(host, act, next);
                refreshShakeLocalUi();
            }
        };
        rgShakeDirLeft.setOnCheckedChangeListener(dirL);
        rgShakeDirRight.setOnCheckedChangeListener(dirL);
        rgShakeDirUp.setOnCheckedChangeListener(dirL);
        rgShakeDirDown.setOnCheckedChangeListener(dirL);

        // 🆕 TASK-029 手感优化：灵敏度三档（低/中/高，默认中）——
        //   改档立即落盘 + 通知捕获层**用新参数重建采样**（reload：正在跑才重建）。
        rgShakeSens.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                if (mShakeUiSyncing) return;      // 防回环：refreshShakeUi 回填时不落盘
                int sens = CardPrefs.SHAKE_SENS_MID;
                if (checkedId == R.id.rb_shake_sens_low) {
                    sens = CardPrefs.SHAKE_SENS_LOW;
                } else if (checkedId == R.id.rb_shake_sens_high) {
                    sens = CardPrefs.SHAKE_SENS_HIGH;
                }
                CardPrefs.setShakeSens(host, sens);
                ShakeDetector.reload(host);   // 档位变了 ⇒ 重建采样
            }
        });

        // ── 🆕 TASK-033：蓝牙控制（HID 外设通道）──
        // 启用开关 ⇒ 写偏好 + 启/停 HidKeepAliveService（前台服务注册 HID、保活）。
        // 🔴 开启时顺带结束 TCP 会话：两条通道**不并存**，避免串扰。
        cbBtEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mBtUiSyncing) return;      // 防回环：refreshBtUi 回填时不落盘
                CardPrefs.setBtControlEnabled(host, checked);
                if (checked) {
                    // 与热点通道互斥：开蓝牙控制前先结束 TCP 会话
                    try {
                        if (RemoteLinkManager.get().getState() != RemoteLinkManager.STATE_IDLE) {
                            RemoteLinkManager.get().stopSession();
                        }
                    } catch (Throwable ignored) {
                    }
                    HidKeepAliveService.start(host);
                } else {
                    HidKeepAliveService.stop(host);
                }
                refreshBtUi();
                ShakeDetector.sync(host);   // 通道就绪态变化 ⇒ 重算晃动门控
            }
        });

        // 测试翻页（连接成功后立即验证 HID 链路；不经会话门控，直发）
        btnBtTestPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendBtTest(false); }
        });
        btnBtTestNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendBtTest(true); }
        });

        // 🆕 TASK-035：让本机可被发现（配对用）—— 走系统弹窗（ACTION_REQUEST_DISCOVERABLE）。
        // ⛔ 只做"让系统主动把本机暴露给墨水屏"，**不做自动配对**（Android 不允许第三方静默配对）。
        btnBtDiscoverable.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestDiscoverable(); }
        });
    }

    /**
     * 🆕 TASK-065：装配一个**折叠引导卡** —— 标题行（自绘 {@link FoldHintView}）+ 内容容器
     * （初始 GONE）。点标题行切换内容可见性。**只动可见性，不改任何功能行为**（验收 A6）。
     *
     * <p>内容容器在布局里是普通 LinearLayout ⇒ 内部 TextView 的排版 / 绑定逻辑一律不动。
     *
     * @param headerId 标题行（{@code FoldHintView}）的资源 id
     * @param bodyId   内容容器（初始 {@code gone}）的资源 id
     * @param titleRes 标题文案资源 id
     */
    private void bindFold(int headerId, int bodyId, int titleRes) {
        final View body = host.findViewById(bodyId);
        View head = host.findViewById(headerId);
        if (!(head instanceof FoldHintView) || body == null) return;
        final FoldHintView fold = (FoldHintView) head;
        fold.setTitle(host.getString(titleRes));
        fold.setExpanded(false);
        body.setVisibility(View.GONE);
        fold.setListener(new FoldHintView.Listener() {
            @Override public void onToggle(boolean expanded) {
                body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            }
        });
    }

    /**
     * 🆕 TASK-066 · A3：蓝牙「引导文案」的取色 —— 墨水屏端**零彩色**。
     *
     * <p>布局里写的是 `#FFB00020`（Material 警示红）。手机端**原样保留**（逐像素不变，验收 A5）；
     * 墨水屏端换成 {@link InkTheme#ink2} 的单色深灰 —— 无彩色的机器上，警示靠**加粗 + 文案**承担。
     */
    private void applyGuideColor(TextView tv) {
        if (tv == null) return;
        tv.setTextColor(InkTheme.isPhone(host) ? WARN_RED : InkTheme.ink2(host));
    }

    /** 手机端蓝牙引导文案的警示红（= 布局里的 `#FFB00020`，原值）。 */
    private static final int WARN_RED = 0xFFB00020;

    /**
     * 🆕 TASK-031：按 {@code install_role} **动态装配**实验室子标签（bind 时与角色切换时调用）。
     *
     * <p>把原来写死的 3 段换成**可扩展表** {@code {labelRes, page, phoneRelevant}}：
     * <ul>
     *   <li><b>阅读器端</b> ⇒ 全部装配（改造前 3 项保持原位序，另加「蓝牙控制」只读页）；</li>
     *   <li><b>手机端</b> ⇒ 只装配 {@code phoneRelevant} 的项（当前 = 热点翻页 + 蓝牙控制）。</li>
     * </ul>
     * 🔴 子标签文案用资源 id（不写死字符串）。
     */
    public void bindLabTabs() {
        final boolean phone = CardPrefs.getInstallRole(host) == CardPrefs.INSTALL_ROLE_PHONE;

        // ── 可扩展表：新增手机端子标签，在下面追加一行并把 phoneRelevant 置 true 即可 ──
        // 🆕 TASK-072：「热点翻页」+「蓝牙控制」两页合并为「翻页」⇒ 子标签 **4 → 3**（072 验收 A1）。
        // 🆕 TASK-079：追加「壁纸管家」⇒ 子标签 **3 → 4**（翻页 / 锁屏密码 / 续航优化 / 壁纸管家）。
        final int[] labelRes = {
                R.string.lab_tab_remote,       // 翻页（原「热点翻页」+「蓝牙控制」合并）
                R.string.lab_tab_lockscreen,   // 锁屏密码
                R.string.lab_tab_power,        // 续航优化
                R.string.lab_tab_wallpaper };  // 🆕 TASK-079 壁纸管家
        final View[] page = {
                host.findViewById(R.id.page_lab_remote),
                host.findViewById(R.id.page_lab_lockscreen),
                host.findViewById(R.id.page_lab_power),
                host.findViewById(R.id.page_lab_wallpaper) };
        // 🔴 壁纸管家属**墨水屏端**功能 ⇒ 手机端不装配（与锁屏 / 续航一致；卡面 A3）
        final boolean[] phoneRelevant = { true, false, false, false };

        // 按角色过滤出"保留的下标"
        final int[] keep = new int[labelRes.length];
        int n = 0;
        for (int i = 0; i < labelRes.length; i++) {
            if (!phone || phoneRelevant[i]) keep[n++] = i;
        }

        final String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = host.getString(labelRes[keep[i]]);

        final SegTabView segLab = (SegTabView) host.findViewById(R.id.seg_lab);
        segLab.setLabels(labels);
        // 🔴 装配结果存字段：🆕 TASK-079 的「深链到指定子页」（selectLabPage）要用
        labSeg = segLab;
        labPage = page;
        labKeep = keep;
        labCount = n;
        labScroll = scrollOf(host.findViewById(R.id.page_lab));

        segLab.setListener(new SegTabView.Listener() {
            @Override
            public void onSegSelected(int index) {
                applyLabTab(index);
            }
        });

        // 复位到第 0 个可见子页（setLabels 只在越界时钳 selected，故这里显式置一次可见性）
        segLab.setSelected(0);
        applyLabTab(0);
    }

    /**
     * 🆕 TASK-079：把实验室切到**指定子页**（按子页容器 id）—— 供墨台「壁纸管家 · 管理」深链用。
     *
     * <p>🔴 为什么要显式走一次可见性：{@code SegTabView.setSelected} **同值早退、不回调 listener**
     * （与 {@code SettingsActivity.showTopPage} 同款坑）⇒ 必须自己走 {@link #applyLabTab}。
     *
     * @param pageId 子页容器的资源 id（如 {@code R.id.page_lab_wallpaper}）
     * @return true = 找到并切过去了；false = 该子页在本机形态下**不可见**（如手机端）
     */
    public boolean selectLabPage(int pageId) {
        if (labSeg == null || labKeep == null || labCount <= 0) return false;
        for (int i = 0; i < labCount; i++) {
            View v = labPage[labKeep[i]];
            if (v != null && v.getId() == pageId) {
                labSeg.setSelected(i);
                applyLabTab(i);
                return true;
            }
        }
        return false;
    }

    /** 切实验室子页：可见性 + 回页顶 + 按子页复核状态（listener 与 {@link #selectLabPage} 共用）。 */
    private void applyLabTab(int uiIndex) {
        if (labKeep == null || labCount <= 0) return;
        final int idx = (uiIndex < 0 || uiIndex >= labCount) ? 0 : uiIndex;
        for (int i = 0; i < labCount; i++) {
            View v = labPage[labKeep[i]];
            if (v != null) v.setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        if (labScroll != null) labScroll.scrollTo(0, 0);
        final int orig = labKeep[idx];
        // 🆕 TASK-072：翻页页 = 连接方式 + 热点配置 + 蓝牙配置 ⇒ 三处一起复核
        if (orig == 0) { refreshConnUi(); refreshRoleUi(); refreshBtUi(); }
        if (orig == 1) refreshLockUi();
        // TASK-023：进续航页复核一次 —— 勾选态一律读设备真值（不读偏好，防「显示已开/实际已关」）
        if (orig == 2) refreshPowerUi();
        // 🆕 TASK-079：进壁纸页复核一次（池 / 开关 / 待生成那一期都可能在别处被改）
        if (orig == 3) refreshWallUi();
    }

    /** 从 {@code start} 向上找最近的 ScrollView；找不到返回 null。 */
    private static ScrollView scrollOf(View start) {
        View p = start;
        while (p != null) {
            if (p instanceof ScrollView) return (ScrollView) p;
            ViewParent vp = p.getParent();
            p = (vp instanceof View) ? (View) vp : null;
        }
        return null;
    }

    // ══════════════════════ 生命周期 ══════════════════════

    /** 宿主 onResume 调用：注册 HID 状态监听 + 刷蓝牙子页（与会话监听一起由 {@link #refreshRoleUi()} 注册）。 */
    public void onResume() {
        HidKeepAliveService.addListener(mHidStateListener);
        refreshBtUi();
        refreshConnUi();   // 🆕 TASK-072：回页按既有 pref 复核单选 + 分支显隐
        refreshShakeLocalUi();   // 🆕 TASK-073：回页复核本机晃动开关（可能在别处被改）
    }

    /** 宿主 onPause 调用：摘掉本页持有的所有监听（离页不持引用）。 */
    public void onPause() {
        // 🔴 TASK-029 §2.2：用 removeStateListener —— 只摘自己这一个，不影响遥控服务的监听
        RemoteLinkManager.get().removeStateListener(mStateListener);
        HidKeepAliveService.removeListener(mHidStateListener);
    }

    /** 宿主 onResume 调用：锁屏 + 续航 + 壁纸子页状态复核。 */
    public void refreshAll() {
        refreshLockUi();
        refreshPowerUi();
        refreshWallUi();
    }

    /**
     * 宿主转发 {@code onActivityResult}。@return true = 本控制器已消费该请求码。
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_BT_DISCOVERABLE) {
            if (resultCode > 0) {
                Toast.makeText(host, host.getString(R.string.lab_bt_discoverable_ok, resultCode),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(host, R.string.lab_bt_discoverable_denied, Toast.LENGTH_LONG).show();
            }
            return true;
        }
        return false;
    }

    /**
     * 宿主转发 {@code onRequestPermissionsResult}。@return true = 本控制器已消费该请求码。
     */
    public boolean onRequestPermissionsResult(int req, String[] perms, int[] results) {
        // 🆕 TASK-079：收图（壁纸图片池）的读存储权限 —— 与锁屏背景**分开**，避免回调串台
        if (req == REQ_PERM_WALL) {
            boolean granted = results != null && results.length > 0
                    && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(host, R.string.lock_bg_perm_needed, Toast.LENGTH_LONG).show();
            }
            String p = (etWallSrc == null) ? "" : etWallSrc.getText().toString().trim();
            if (p.length() > 0) applyCollect(p);   // 无论给不给权限都试一次：私有目录兜底不依赖权限
            return true;
        }
        if (req != REQ_PERM_BG) return false;
        boolean ok = results != null && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok) {
            Toast.makeText(host, R.string.lock_bg_perm_needed, Toast.LENGTH_LONG).show();
        }
        String p = etLockBgPath.getText().toString().trim();
        if (p.length() == 0) p = LockPrefs.DEFAULT_BG_REL;
        applyLockBg(p);              // 无论给不给权限都试一次：私有目录兜底不依赖权限
        return true;
    }

    // ══════════════════════ 🆕 TASK-033 蓝牙控制（HID 外设通道）══════════════════════

    /**
     * 刷新「蓝牙控制」子页（bind / onResume / 开关变更 / HID 状态变化时调用）。
     *
     * <p>按 {@code install_role} 分两种形态：
     * <ul>
     *   <li><b>手机端</b> ⇒ 显示控件块；开关态回填偏好；SDK&lt;28 时开关置灰并显红字；状态行按
     *       「未运行 / 已启用待连 / 已连接」三态渲染（**读服务真值，不读偏好**）；仅「已连接」
     *       时才露出测试翻页行。</li>
     *   <li><b>阅读器端</b> ⇒ 隐藏控件块，只显示「本机=墨水屏端」说明（不启动任何后台）。</li>
     * </ul>
     */
    public void refreshBtUi() {
        if (tvBtIntro == null) return;

        boolean phone = CardPrefs.getInstallRole(host) == CardPrefs.INSTALL_ROLE_PHONE;
        boolean supported = HidLink.supported();

        // 页顶简介（手机端专用）；墨水屏端隐藏 —— 该端的说明由下方 tvBtEinkNote 承担（避免重复两遍）
        tvBtIntro.setText(R.string.lab_bt_intro);
        tvBtIntro.setVisibility(phone ? View.VISIBLE : View.GONE);
        // 手机端显示控件块；墨水屏端显示「本机=墨水屏端」说明
        llBtControls.setVisibility(phone ? View.VISIBLE : View.GONE);
        tvBtEinkNote.setVisibility(phone ? View.GONE : View.VISIBLE);
        if (!phone) {
            // 🆕 TASK-072：阅读器端不启动 HID ⇒ 状态胶囊不能沿用上一次手机端渲染的旧值
            mLastBtStatus = null;
            return;   // 墨水屏端到此为止（无开关、无状态行）
        }

        // 回填开关态（防回环：置位期间 cbBtEnabled 的 onCheckedChanged 会早退）
        mBtUiSyncing = true;
        cbBtEnabled.setChecked(CardPrefs.isBtControlEnabled(host));
        mBtUiSyncing = false;

        // 状态判定：以「服务是否运行 + HID 主机是否连接」为准（真值，不读偏好）
        HidLink hid = HidKeepAliveService.link();
        boolean running = HidKeepAliveService.isRunning() && hid != null;
        boolean connected = running && hid.isHostConnected();
        // 🆕 TASK-034：SDK 够但 ROM 裁掉了 HID profile ⇒ 显式提示「本机不支持」，
        //    否则开关打开后会永远停在「等待连接」，是假象。
        boolean noProfile = supported && running && hid != null && hid.profileUnavailable();
        boolean usable = supported && !noProfile;

        // 不可用（SDK<28 或 ROM 无 HID profile）：红字提示 + 开关置灰
        tvBtUnsupported.setText(noProfile ? R.string.lab_bt_noprofile : R.string.lab_bt_unsupported);
        tvBtUnsupported.setVisibility(usable ? View.GONE : View.VISIBLE);
        cbBtEnabled.setEnabled(usable);

        String status;
        if (!running || noProfile) {
            status = host.getString(R.string.lab_bt_status_off);          // 未启用（或本机不可用）
        } else if (!connected) {
            // 🆕 TASK-034：连过又断 ⇒「已断开」；从未连过 ⇒「等待连接」——两者引导语不同
            status = host.getString(hid.everConnected()
                    ? R.string.lab_bt_status_lost : R.string.lab_bt_status_registered);
        } else {
            // 🆕 审查修复（NG-4）：走 HidLink.safeName 兜底 —— host.getName()/getAddress() 在
            //   BLUETOOTH_CONNECT 未授予/被撤时会抛 SecurityException（此处无 try/catch 会带走进程）。
            String name = HidLink.safeName(hid.host());
            status = host.getString(R.string.lab_bt_status_connected, name);
        }
        tvBtStatus.setText(host.getString(R.string.lab_bt_status_prefix) + status);
        mLastBtStatus = status;   // 🆕 TASK-072：供状态胶囊拼「当前：蓝牙连接 · …」

        // 🆕 TASK-034 / TASK-035：两类「未连接」引导**互斥**（按"是否连过"分流，不混）——
        //   ① 从未连过（hid.everConnected()==false）⇒ **首次配对引导**（三步 + 「让本机可被发现」按钮）
        //   ② 连过又断（everConnected()==true）  ⇒ **断链恢复引导**（去墨水屏「之前连接的设备」点本机名）
        boolean needsPair = usable && running && !connected && !hid.everConnected();
        boolean lostLink = usable && running && !connected && hid.everConnected();
        tvBtPairGuide.setVisibility(needsPair ? View.VISIBLE : View.GONE);
        llBtDiscover.setVisibility(needsPair ? View.VISIBLE : View.GONE);
        tvBtReconnectGuide.setVisibility(lostLink ? View.VISIBLE : View.GONE);

        // 测试翻页行：仅"已连接"才露（未连无意义）
        llBtTest.setVisibility(connected ? View.VISIBLE : View.GONE);
    }

    /**
     * 测试翻页（设置页按钮直发，不经会话门控）：连接成功后立即验证 HID 链路是否真能翻页。
     *
     * <p>失败（未连接 / 发送未受理）时给同一句「重连由墨水屏发起」的引导，不静默失败。
     */
    private void sendBtTest(boolean next) {
        HidLink hid = HidKeepAliveService.link();
        if (hid == null || !hid.isHostConnected()) {
            Toast.makeText(host, R.string.lab_bt_reconnect_tip, Toast.LENGTH_LONG).show();
            return;
        }
        boolean ok = next ? hid.pageNext() : hid.pagePrev();
        if (!ok) {
            Toast.makeText(host, R.string.lab_bt_reconnect_tip, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 🆕 TASK-035：让本机「可被发现」，方便墨水屏把它扫出来配对。
     *
     * <p>走系统标准弹窗 {@code ACTION_REQUEST_DISCOVERABLE}（**用户必须自己点「允许」**，第三方 App
     * 无法静默开启）。回执 {@code resultCode} = 系统授予的可被发现秒数；{@code RESULT_CANCELED}(=0)
     * = 用户拒绝。⛔ 本方法**只让本机可被看见，不做任何自动配对**。
     */
    private void requestDiscoverable() {
        try {
            Intent it = new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            // 300s：够用户切到墨水屏完成扫描/点选；系统可能自行缩短，以回执为准
            it.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            host.startActivityForResult(it, REQ_BT_DISCOVERABLE);
        } catch (Throwable t) {
            // 极少数 ROM 无此 Activity：降级提示用户去系统蓝牙设置手动开启
            Toast.makeText(host, R.string.lab_bt_discoverable_fail, Toast.LENGTH_LONG).show();
        }
    }

    // ══════════════════════ 🆕 TASK-022 锁屏密码（应用级软锁） ══════════════════════

    /**
     * 按输入框里的路径载入锁屏背景图（TASK-022-R1）。
     *
     * <p>🔴 为什么<b>不再用</b> SAF：S4 ROM 里没有系统文件选择器（缺 {@code com.android.documentsui}），
     * {@code ACTION_OPEN_DOCUMENT / GET_CONTENT / PICK} 在真机上全部 {@code No activities found}
     * ⇒ 原来的「选择图片」按钮点了只会弹一句「本机没有可用的文件选择器」。
     *
     * <p>改为：用户填路径（默认 {@link LockPrefs#DEFAULT_BG_REL}）→ 先拿存储读权限 → 按路径读文件。
     * 拿不到权限也<b>不拦</b>用户：提示里直接给出 {@code adb pm grant} 一行命令，并仍试一次
     * （App 私有目录兜底不需要权限）。
     */
    private void loadLockBg() {
        String p = etLockBgPath.getText().toString().trim();
        if (p.length() == 0) p = LockPrefs.DEFAULT_BG_REL;   // 空 = 用默认路径
        if (hasStorageReadPermission()) {
            applyLockBg(p);
            return;
        }
        try {
            host.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM_BG);
        } catch (Throwable t) {
            Toast.makeText(host, R.string.lock_bg_perm_needed, Toast.LENGTH_LONG).show();
            applyLockBg(p);          // 授权流程起不来也先试一次（私有目录兜底不依赖权限）
        }
    }

    private boolean hasStorageReadPermission() {
        try {
            return host.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 真正落盘：写路径 → 解析文件 → 如实反馈（找到 / 没找到）。
     *
     * <p>🔴 <b>「未入库文件」的真实边界（2026-09-29 S4 实测，如实登记）</b>：
     * 路径没命中时先别急着报错 —— 根因是 Android 11 的 FUSE 对<b>还没被媒体库索引</b>的文件
     * 一律判"不存在"。这里仍尝试一次 {@link MediaScannerConnection#scanFile}（<b>对其他 ROM 有意义</b>），
     * 但 <b>S4 上实测无效</b>。S4 上可用的替代是 shell 侧那条
     * {@code am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://…}。
     */
    private void applyLockBg(String path) {
        LockPrefs.setBgPath(host, path);
        if (LockPrefs.resolveBgFile(host) != null) {
            Toast.makeText(host, R.string.lock_bg_loaded, Toast.LENGTH_SHORT).show();
            refreshLockUi();
            return;
        }
        final String abs = LockPrefs.toAbsolute(path).getAbsolutePath();
        try {
            MediaScannerConnection.scanFile(host, new String[]{abs}, null,
                    new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String p, Uri u) {
                            host.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    File f = LockPrefs.resolveBgFile(host);
                                    Toast.makeText(host, host.getString(f == null
                                            ? R.string.lock_bg_not_found : R.string.lock_bg_loaded),
                                            Toast.LENGTH_SHORT).show();
                                    refreshLockUi();
                                }
                            });
                        }
                    });
        } catch (Throwable t) {
            Toast.makeText(host, R.string.lock_bg_not_found, Toast.LENGTH_SHORT).show();
        }
        refreshLockUi();
    }

    /** 刷新锁屏子页的三处状态文案（bind / 改密 / 选图 / 开关后调用）。 */
    public void refreshLockUi() {
        if (cbLockEnabled != null) {
            lockUiSyncing = true;                       // 同步勾选态时不触发监听
            cbLockEnabled.setChecked(LockPrefs.isEnabled(host));
            lockUiSyncing = false;
        }
        if (tvLockStatus != null) {
            tvLockStatus.setText(host.getString(LockPrefs.hasPin(host)
                    ? R.string.lock_status_set : R.string.lock_status_unset));
        }
        if (tvLockBgStatus != null) {
            // 如实显示"当前实际生效"的那张图（含兜底命中）；没有则显示默认底
            File f = LockPrefs.resolveBgFile(host);
            tvLockBgStatus.setText(f == null
                    ? host.getString(R.string.lock_bg_default)
                    : host.getString(R.string.lock_bg_custom_fmt, f.getAbsolutePath()));
        }
    }

    // ══════════════════════ 🆕 TASK-023 续航（省电指令 · ADB 自助档 1） ══════════════════════

    /** 把一个勾选项接到一条省电条目上（勾选 = 写开启值，取消 = 显式还原）。 */
    private void wirePowerItem(final CheckBox cb, final PowerSettingsManager.Item item) {
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (powerUiSyncing) return;                        // 同步态不触发
                applyPowerItem(item, checked);
            }
        });
    }

    /** 真正执行一条条目的勾选 / 取消（未授权则提示并回滚显示，不静默失败）。 */
    private void applyPowerItem(PowerSettingsManager.Item item, boolean on) {
        if (!PowerSettingsManager.hasPermission(host)) {
            toast(host.getString(R.string.power_perm_deny));
            refreshPowerUi();                                      // 回滚勾选显示
            return;
        }
        boolean ok = PowerSettingsManager.setOn(host, item, on);
        if (!ok) toast(host.getString(R.string.power_write_failed));
        refreshPowerUi();
    }

    /**
     * 刷新续航子页：授权状态行 + 命令文本 + 3 个勾选项。
     * 🔴 勾选态一律取 {@link PowerSettingsManager#isOn}（设备真值），不读偏好记录 ——
     * 系统可能改回某项，只有读真值才不会「显示已开、实际已关」。
     */
    public void refreshPowerUi() {
        boolean perm = PowerSettingsManager.hasPermission(host);
        if (tvPowerPerm != null) {
            tvPowerPerm.setText(perm ? R.string.power_perm_ok : R.string.power_perm_deny);
        }
        if (tvPowerCmd != null) {
            tvPowerCmd.setText(PowerSettingsManager.grantCommand(host));
        }
        syncPowerCb(cbPowerWifiNotif, PowerSettingsManager.ITEM_WIFI_NET_NOTIF);
        syncPowerCb(cbPowerAnim, PowerSettingsManager.ITEM_ANIM);
        syncPowerCb(cbPowerScreensaver, PowerSettingsManager.ITEM_SCREENSAVER);
    }

    private void syncPowerCb(CheckBox cb, PowerSettingsManager.Item item) {
        if (cb == null) return;
        powerUiSyncing = true;
        cb.setChecked(PowerSettingsManager.isOn(host, item));
        powerUiSyncing = false;
    }

    /** 把一次性授权命令复制到剪贴板（ADB 自助 · 档 1）。 */
    private void copyGrantCmd() {
        try {
            ClipboardManager cm = (ClipboardManager) host.getSystemService(Activity.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("adb", PowerSettingsManager.grantCommand(host)));
            toast(host.getString(R.string.power_copied));
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }

    /** C1：跳到系统「电池优化」页（把本应用加入白名单，抵消省电模式的后台限制）。 */
    private void openBatteryOpt() {
        try {
            host.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            toast(host.getString(R.string.power_battery_opt_failed));
        }
    }

    // ══════════════════════ 🆕 TASK-079 壁纸管家（图片池 · 轮换 · 生成账单壁纸） ══════════════════════

    /**
     * 刷新壁纸管家子页：① 图片池 ② 轮换（开关 + 间隔 + 自证行） ③ 应用范围 ④ 待生成那一期 ⑤ 当前生效。
     *
     * <p>🔴 「当前生效」直接问 {@link WallpaperPrefs#effectiveSoftLockPath} ——
     * 那是**软锁绘背景时的同一个入口**（TASK-078 的唯一接缝）⇒ 页面上写的就是实际会加载的那张，
     * 不自造第二套口径。
     */
    public void refreshWallUi() {
        if (tvWallPool == null) return;

        // ① 图片池：张数 + 前几项文件名（够辨识即可）
        List<String> pool = WallpaperPrefs.pool(host);
        StringBuilder sb = new StringBuilder(host.getString(R.string.wall_pool_fmt, pool.size()));
        if (!pool.isEmpty()) {
            sb.append('\n').append(host.getString(R.string.wall_pool_list_prefix));
            int show = Math.min(3, pool.size());
            for (int i = 0; i < show; i++) {
                if (i > 0) sb.append('、');
                sb.append(new File(pool.get(i)).getName());
            }
            if (pool.size() > show) {
                sb.append(host.getString(R.string.wall_pool_more_fmt, pool.size() - show));
            }
        }
        tvWallPool.setText(sb.toString());

        // ② 轮换 + ③ 范围：回填（置位期间不写盘）
        mWallUiSyncing = true;
        cbWallRot.setChecked(WallpaperPrefs.isRotationOn(host));
        int iv = WallpaperPrefs.intervalDays(host);
        etWallInterval.setText(String.valueOf(iv > 0 ? iv : WallpaperPrefs.DEFAULT_INTERVAL));
        String sc = WallpaperPrefs.scope(host);
        rbWallScopeSoft.setChecked(WallpaperPrefs.SCOPE_SOFT.equals(sc));
        rbWallScopeDream.setChecked(WallpaperPrefs.SCOPE_DREAM.equals(sc));
        rbWallScopeBoth.setChecked(WallpaperPrefs.SCOPE_BOTH.equals(sc));
        mWallUiSyncing = false;
        tvWallRotStatus.setText(wallRotText());

        // ④ 待生成的账单期
        refreshBillwPick();

        // ⑤ 当前生效（= 软锁那一帧会加载的图；空 ⇒ 浅色网点）
        String eff = WallpaperPrefs.effectiveSoftLockPath(host);
        tvWallEffective.setText((eff == null || eff.length() == 0)
                ? host.getString(R.string.wall_eff_none)
                : host.getString(R.string.wall_eff_fmt, new File(eff).getName()));
    }

    /** 只回填「轮换开关 + 自证行」（间隔输入框正在被输入时不重设，免得把光标顶走）。 */
    private void syncWallRotUi() {
        mWallUiSyncing = true;
        cbWallRot.setChecked(WallpaperPrefs.isRotationOn(host));
        mWallUiSyncing = false;
        tvWallRotStatus.setText(wallRotText());
    }

    /**
     * 轮换自证行 —— **如实**：关就是关；开着但**范围不含软锁**、或**池不足 2 张**时，
     * 都把"现在其实不会换"写出来（对应卡面 R4 与 TASK-078 的 {@code rotatesSoftLock}）。
     */
    private String wallRotText() {
        int iv = WallpaperPrefs.intervalDays(host);
        if (iv <= 0) return host.getString(R.string.wall_rot_status_off);
        StringBuilder sb = new StringBuilder(host.getString(
                R.string.wall_rot_status_on, iv, WallpaperPrefs.scopeLabel(host)));
        if (!WallpaperPrefs.rotatesSoftLock(host)) {
            sb.append(host.getString(R.string.wall_rot_not_softlock));
        } else if (WallpaperPrefs.pool(host).size() < 2) {
            sb.append(host.getString(R.string.wall_rot_need_pool));
        }
        return sb.toString();
    }

    /** 当前「生成账单壁纸」用的周期（周 / 月）。 */
    private String billwMode() {
        return (rbBillwMonth != null && rbBillwMonth.isChecked())
                ? PeriodRange.MONTHLY : PeriodRange.WEEKLY;
    }

    /** 刷新「待生成的那一期」——把已生成的账单按期起点降序排，默认取最新一期。 */
    private void refreshBillwPick() {
        if (tvBillwSerial == null) return;
        List<Long> starts = BillStore.startsOf(host, billwMode());
        if (starts.isEmpty()) {
            mBillwStart = -1;
            tvBillwSerial.setText(R.string.wall_bill_none);
            tvBillwStatus.setText(R.string.wall_bill_hint_none);
            btnBillwGen.setEnabled(false);
            btnBillwOlder.setEnabled(false);
            btnBillwNewer.setEnabled(false);
            return;
        }
        int idx = starts.indexOf(mBillwStart);
        if (idx < 0) idx = 0;
        mBillwStart = starts.get(idx);
        tvBillwSerial.setText(host.getString(R.string.wall_bill_pick_fmt,
                Bill.serialOf(billwMode(), mBillwStart), Bill.rangeLabel(billwMode(), mBillwStart)));
        btnBillwGen.setEnabled(!mBillwBusy);
        btnBillwOlder.setEnabled(idx + 1 < starts.size());
        btnBillwNewer.setEnabled(idx > 0);
    }

    /** 「‹ 更早」（+1）/「更晚 ›」（-1）：在已生成的期里挪一格。 */
    private void stepBillw(int delta) {
        List<Long> starts = BillStore.startsOf(host, billwMode());
        int idx = starts.indexOf(mBillwStart);
        if (idx < 0) idx = 0;
        int ni = idx + delta;
        if (ni < 0 || ni >= starts.size()) return;
        mBillwStart = starts.get(ni);
        refreshBillwPick();
    }

    /**
     * 生成「账单壁纸」→ 存进图片池。
     *
     * <p>🔴 480×800 + PNG 压缩很耗时 ⇒ **纯后台线程**（照 {@code NoteExport} 的 C2 阶段做法），
     * 主线程只做落盘入库 + 刷新 UI ⇒ 生成期间界面不卡、不会 ANR（卡面 A4）。
     */
    private void generateBillWallpaper() {
        if (mBillwBusy) return;
        final String mode = billwMode();
        final long start = mBillwStart;
        if (start <= 0) return;
        mBillwBusy = true;
        btnBillwGen.setEnabled(false);
        tvBillwStatus.setText(R.string.wall_gen_running);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BillWallpaper.Result res = BillWallpaper.generate(host, mode, start);
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        postGenerate(res);
                    }
                });
            }
        }, "wallbill-gen").start();
    }

    /** 生成收尾（**主线程**）：入库 + 文案反馈 + 刷新。 */
    private void postGenerate(BillWallpaper.Result res) {
        if (host.isFinishing() || host.isDestroyed()) return;
        mBillwBusy = false;
        if (res != null && res.path != null) {
            // 🔴 用 replace 而非 add：重新生成同一期 ⇒ **同一路径、内容变了**，
            //    只走 add 会被"同一源去重"命中，池里留下旧画面（TASK-079 补的语义）
            boolean ok = WallpaperPrefs.replace(host, res.path);
            tvBillwStatus.setText(ok
                    ? host.getString(R.string.wall_gen_ok_fmt, new File(res.path).getName())
                    : host.getString(R.string.wall_gen_fail));
        } else {
            tvBillwStatus.setText(R.string.wall_gen_fail);
        }
        refreshWallUi();
    }

    /**
     * 「收图」入口：先要读共享存储的权限（照 {@code loadLockBg}），拿到就收，
     * 拿不到也**不拦** —— 私有目录里的图不依赖权限，仍试一次。
     */
    private void collectWallImage() {
        String p = etWallSrc.getText().toString().trim();
        if (p.length() == 0) {
            toast(host.getString(R.string.wall_add_need_path));
            return;
        }
        if (hasStorageReadPermission()) {
            applyCollect(p);
            return;
        }
        try {
            host.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM_WALL);
        } catch (Throwable t) {
            applyCollect(p);
        }
    }

    /** 真正收图：copy 进私有目录 + 入池（同一源天然去重）+ 如实反馈。 */
    private void applyCollect(String path) {
        boolean ok = WallpaperPrefs.add(host, path);
        toast(host.getString(ok ? R.string.wall_add_ok : R.string.wall_add_fail));
        if (ok) etWallSrc.setText("");
        refreshWallUi();
    }

    /** 清空图片池（只清池内容；轮换开关与范围不动 —— 那是用户偏好）。 */
    private void clearWallPool() {
        int n = WallpaperPrefs.clear(host);
        toast(host.getString(R.string.wall_clear_done_fmt, n));
        refreshWallUi();
    }

    /** 把池里当前那张设为**软锁背景**（写 {@code LockPrefs.bg_path}，与手填路径同一条落盘）。 */
    private void applyWallToLock() {
        String p = WallpaperPrefs.currentPath(host);
        if (p == null) {
            toast(host.getString(R.string.wall_apply_none));
            return;
        }
        LockPrefs.setBgPath(host, p);
        toast(host.getString(R.string.wall_apply_ok));
        refreshWallUi();
    }

    // ────────────────────── 🆕 TASK-072：连接方式（三选一）──────────────────────

    /** 当前派生连接方式（读物 = 既有两套 pref）：蓝牙 > 热点 > 关闭。 */
    private int currentConnMode() {
        if (CardPrefs.isBtControlEnabled(host)) return CONN_BT;
        if (CardPrefs.getRemoteRole(host) != CardPrefs.REMOTE_ROLE_OFF) return CONN_HOTSPOT;
        return CONN_OFF;
    }

    /** 选「关闭」：两套 pref 归零 + 收尾（断 TCP 会话、停 HID 保活）。 */
    private void applyConnOff() {
        CardPrefs.setBtControlEnabled(host, false);
        CardPrefs.setRemoteRole(host, CardPrefs.REMOTE_ROLE_OFF);
        stopSessionIfActive();
        HidKeepAliveService.stop(host);
    }

    /** 选「热点」：关蓝牙并停 HID；remote_role 若为 OFF，按本机形态取默认值。 */
    private void applyConnHotspot() {
        CardPrefs.setBtControlEnabled(host, false);
        HidKeepAliveService.stop(host);
        if (CardPrefs.getRemoteRole(host) == CardPrefs.REMOTE_ROLE_OFF) {
            int def = (CardPrefs.getInstallRole(host) == CardPrefs.INSTALL_ROLE_PHONE)
                    ? CardPrefs.REMOTE_ROLE_PHONE : CardPrefs.REMOTE_ROLE_EINK;
            CardPrefs.setRemoteRole(host, def);
        }
    }

    /** 选「蓝牙」：启 HID（手机端）；断 TCP 会话；手机端顺带设 remote_role=手机（音量键捕获才生效）。 */
    private void applyConnBt() {
        if (!HidLink.supported()) {
            // 🔴 本机不支持蓝牙键盘 ⇒ 如实提示并**不改状态**（refreshConnUi 会把单选拉回原态）。
            toast(host.getString(R.string.lab_bt_unsupported));
            return;
        }
        CardPrefs.setBtControlEnabled(host, true);
        stopSessionIfActive();
        boolean phone = CardPrefs.getInstallRole(host) == CardPrefs.INSTALL_ROLE_PHONE;
        if (phone) {
            HidKeepAliveService.start(host);
            CardPrefs.setRemoteRole(host, CardPrefs.REMOTE_ROLE_PHONE);
        }
    }

    /** 会话非 IDLE 才断（沿用 TASK-033 A6 判据，避免无谓 endSession）。 */
    private void stopSessionIfActive() {
        try {
            if (RemoteLinkManager.get().getState() != RemoteLinkManager.STATE_IDLE) {
                RemoteLinkManager.get().stopSession();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 刷新「连接方式」单选 / 两个分支块的显隐 / 状态胶囊。
     * bind / onResume / 通道变更 / HID 状态变化 / 会话状态变化 时调用。
     */
    public void refreshConnUi() {
        if (rgConnMode == null) return;
        int mode = currentConnMode();
        mConnUiSyncing = true;
        try {
            rgConnMode.check(mode == CONN_BT ? R.id.rb_conn_bt
                    : mode == CONN_HOTSPOT ? R.id.rb_conn_hotspot
                    : R.id.rb_conn_off);
        } finally {
            mConnUiSyncing = false;
        }
        if (llConnHotspot != null) {
            llConnHotspot.setVisibility(mode == CONN_HOTSPOT ? View.VISIBLE : View.GONE);
        }
        if (llConnBt != null) {
            llConnBt.setVisibility(mode == CONN_BT ? View.VISIBLE : View.GONE);
        }
        if (tvConnHint != null) {
            String s;
            if (mode == CONN_BT) {
                s = host.getString(R.string.lab_conn_capsule_bt, mLastBtStatus != null
                        ? mLastBtStatus : host.getString(R.string.lab_conn_state_none));
            } else if (mode == CONN_HOTSPOT) {
                s = host.getString(R.string.lab_conn_capsule_hotspot, mLastSessionText != null
                        ? mLastSessionText : host.getString(R.string.lab_conn_state_none));
            } else {
                s = host.getString(R.string.lab_conn_capsule_off);
            }
            tvConnHint.setText(s);
        }
    }

    // ────────────────────── V1.0 Beta（TASK-018）：实验室 · 遥控翻页 ──────────────────────

    /**
     * 按当前角色刷新本页显隐与文案（bind / onResume / 角色切换后调用）。
     *
     * <p>🔴 只负责**实验室侧**的三类联动：
     *   ① 角色说明文本（off / eink / phone 各一句，只写已验证事实）；
     *   ② 会话控件（role != off 才显示：开始·结束 / 状态 / 空闲超时 / 互斥提示）；
     *   ③ 晃动翻页块（仅 phone 角色可见）。
     *   <br>（原实现里「phone 角色隐藏两处桌面卡片分区」属**设置页**职责，已移到
     *   {@link SettingsPageController#refreshRoleVisibility()}。）
     *
     * <p>顺带注册会话状态监听 —— 主线程直推，页面不需要轮询。
     */
    public void refreshRoleUi() {
        RemoteRole role = RemoteRole.from(host);

        // 🆕 TASK-072：回填角色单选（防回环）—— 角色可能由「连接方式」切换连带设定（默认值）
        if (rbRoleOff != null) {
            mRoleUiSyncing = true;
            try {
                rbRoleOff.setChecked(role == RemoteRole.OFF);
                rbRoleEink.setChecked(role == RemoteRole.EINK);
                rbRolePhone.setChecked(role == RemoteRole.PHONE);
            } finally {
                mRoleUiSyncing = false;
            }
        }

        if (tvRoleHint != null) {
            tvRoleHint.setText(role == RemoteRole.EINK ? host.getString(R.string.lab_role_hint_eink)
                    : role == RemoteRole.PHONE ? host.getString(R.string.lab_role_hint_phone)
                    : host.getString(R.string.lab_role_hint_off));
        }
        if (llRemoteSession != null) {
            llRemoteSession.setVisibility(role == RemoteRole.OFF ? View.GONE : View.VISIBLE);
        }
        // 🆕 TASK-065：手机角色的「注意事项」折叠卡 —— 仅 role=手机 可见；其他角色连同内容一起收
        if (foldRoleMore != null) {
            boolean phoneRole = (role == RemoteRole.PHONE);
            foldRoleMore.setVisibility(phoneRole ? View.VISIBLE : View.GONE);
            if (!phoneRole && tvRoleMore != null) tvRoleMore.setVisibility(View.GONE);
        }
        // 🆕 TASK-029：晃动翻页块**仅手机角色**可见（A2 —— 墨水屏 / 关闭角色下不可见且零响应）
        if (llShakeBlock != null) {
            llShakeBlock.setVisibility(role == RemoteRole.PHONE ? View.VISIBLE : View.GONE);
        }
        refreshShakeUi();
        refreshShakeLocalUi();   // 🆕 TASK-073：本机晃动组与角色无关，但随页面刷新一起复核

        // 会话状态：注册监听会立刻回推一次当前状态（页面无需手动刷新）
        // 🔴 TASK-029 §2.2：单槽 setStateListener ⇒ addStateListener。否则本页监听会与遥控服务
        //    （晃动捕获门控）的监听互相顶掉。
        RemoteLinkManager.get().addStateListener(mStateListener);

        // 🆕 TASK-029：角色可能刚改过（G1）⇒ 重算晃动捕获层的注册/注销。
        ShakeDetector.sync(host);

        // 🆕 TASK-072：角色即「连接方式」派生来源 ⇒ 顺带刷单选框与分支显隐
        refreshConnUi();
    }

    /**
     * 🆕 TASK-029：刷新「晃动翻页」区块（bind / onResume / 角色切换 / 任一开关变更后调用）。
     *
     * <p>① 回填 3 个开关（期间置 {@link #mShakeUiSyncing} 防回环）；
     * ② **总开关关时把两个反转开关置灰**（保留可见，避免布局跳动）；
     * ③ 拼出「当前映射」自证行：用户不必靠"开关名 + 记忆"反推映射，**映射永远以屏幕上的字为准**。
     */
    public void refreshShakeUi() {
        if (cbShakeEnabled == null) return;
        boolean on = CardPrefs.isShakeEnabled(host);
        boolean lrRev = CardPrefs.isShakeLrRev(host);
        boolean udRev = CardPrefs.isShakeUdRev(host);
        boolean axLr = CardPrefs.isShakeAxisLrEnabled(host);   // 🆕 TASK-042
        boolean axUd = CardPrefs.isShakeAxisUdEnabled(host);
        int sens = CardPrefs.getShakeSens(host);
        mShakeUiSyncing = true;
        try {
            cbShakeEnabled.setChecked(on);
            cbShakeLrRev.setChecked(lrRev);
            cbShakeUdRev.setChecked(udRev);
            cbShakeAxisLr.setChecked(axLr);
            cbShakeAxisUd.setChecked(axUd);
            rgShakeSens.check(sens == CardPrefs.SHAKE_SENS_LOW ? R.id.rb_shake_sens_low
                    : sens == CardPrefs.SHAKE_SENS_HIGH ? R.id.rb_shake_sens_high
                    : R.id.rb_shake_sens_mid);
        } finally {
            mShakeUiSyncing = false;
        }
        cbShakeLrRev.setEnabled(on);      // 总开关关 ⇒ 置灰（可见）
        cbShakeUdRev.setEnabled(on);
        // 🆕 TASK-042：响应方向多选同样随总开关置灰
        cbShakeAxisLr.setEnabled(on);
        cbShakeAxisUd.setEnabled(on);
        // 🆕 灵敏度三档：总开关关时置灰
        rbShakeSensLow.setEnabled(on);
        rbShakeSensMid.setEnabled(on);
        rbShakeSensHigh.setEnabled(on);
        if (tvShakeMap != null) {
            String prev = host.getString(R.string.lab_shake_page_prev);
            String next = host.getString(R.string.lab_shake_page_next);
            String lrCol = lrRev ? next : prev;    // 左晃
            String rrCol = lrRev ? prev : next;    // 右晃
            String udCol = udRev ? next : prev;    // 上晃
            String ddCol = udRev ? prev : next;    // 下晃
            // 🆕 TASK-042：未使能的组在映射行里显式标注「不响应」，用户一眼看出为何甩了没反应
            if (!axLr) {
                String off = host.getString(R.string.lab_shake_map_axis_off,
                        host.getString(R.string.lab_shake_axis_lr));
                lrCol = off; rrCol = off;
            }
            if (!axUd) {
                String off = host.getString(R.string.lab_shake_map_axis_off,
                        host.getString(R.string.lab_shake_axis_ud));
                udCol = off; ddCol = off;
            }
            tvShakeMap.setText(host.getString(R.string.lab_shake_map_now, lrCol, rrCol, udCol, ddCol));
        }
    }

    /**
     * 🆕 TASK-073：刷新「本机晃动」组（bind / onResume / 角色切换 / 开关变更后调用）。
     *
     * <p>① <b>无可用加速度计</b>（真注册失败，见 {@link #hasAccelerometer()}）⇒ **只挂提醒、不拦操作**
     *   （🔴 用户定则 2026-10-09：墨水屏不强制置灰 —— 部分墨水屏是带传感器的）；
     * ② 开关一律可操作，按偏好如实回填；开启时拼「当前映射」（与对端晃动**共用同一套用户语义**：
     *   默认 左晃→上一页 / 右晃→下一页；方向差异已在 {@code ShakeDetector.fire} 内部按模式反转，
     *   UI 层看到的是**用户语义**，故两处映射行文案一致），未开启时显示「未开启」。
     */
    public void refreshShakeLocalUi() {
        if (cbShakeLocal == null) {
            return;
        }
        // 🔴 用户定则（2026-10-09）：「墨水屏上不需要强制置灰，只需要提醒即可，有些墨水屏可能有传感器」。
        //    ⇒ 无可用加速度计时**不强制置灰、不强制归 false** —— 开关一律可操作、如实回填当前偏好；
        //    仅在"真注册失败"时**多挂一条提醒**（判据见 hasAccelerometer()，非 UI 层硬拦）。
        boolean on = CardPrefs.isShakeLocalEnabled(host);
        mShakeUiSyncing = true;
        try {
            cbShakeLocal.setChecked(on);
            // 🆕 TASK-073（二改）：回填「翻页方式」单选（仅开关打开时才展示）
            if (rgShakeLocalMode != null) {
                boolean swipe = CardPrefs.getShakeLocalMode(host) == CardPrefs.SHAKE_LOCAL_MODE_SWIPE;
                rgShakeLocalMode.check(swipe ? R.id.rb_shake_local_swipe : R.id.rb_shake_local_tap);
            }
            // 🆕 TASK-074：回填「四动作方向」四个单选（左/右/上/下，各 2 选 1）
            checkDir(rgShakeDirLeft, R.id.rb_shake_dir_left_prev, R.id.rb_shake_dir_left_next,
                    CardPrefs.isShakeLocalActNext(host, CardPrefs.SHAKE_ACT_LEFT));
            checkDir(rgShakeDirRight, R.id.rb_shake_dir_right_prev, R.id.rb_shake_dir_right_next,
                    CardPrefs.isShakeLocalActNext(host, CardPrefs.SHAKE_ACT_RIGHT));
            checkDir(rgShakeDirUp, R.id.rb_shake_dir_up_prev, R.id.rb_shake_dir_up_next,
                    CardPrefs.isShakeLocalActNext(host, CardPrefs.SHAKE_ACT_UP));
            checkDir(rgShakeDirDown, R.id.rb_shake_dir_down_prev, R.id.rb_shake_dir_down_next,
                    CardPrefs.isShakeLocalActNext(host, CardPrefs.SHAKE_ACT_DOWN));
        } finally {
            mShakeUiSyncing = false;
        }
        cbShakeLocal.setEnabled(true);
        if (tvShakeLocalUnsupported != null) {
            // 无可用传感器 ⇒ 挂提醒（不拦操作）；有 ⇒ 隐藏
            tvShakeLocalUnsupported.setVisibility(mHasAccel ? View.GONE : View.VISIBLE);
        }
        if (llShakeLocalMode != null) {
            llShakeLocalMode.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (llShakeLocalDir != null) {                       // 🆕 TASK-074：与「翻页方式」同显隐
            llShakeLocalDir.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (tvShakeLocalNote == null) {
            return;
        }
        tvShakeLocalNote.setVisibility(View.VISIBLE);
        if (!on) {
            tvShakeLocalNote.setText(host.getString(R.string.lab_shake_local_note_off));
            return;
        }
        // 开启 ⇒ 拼「当前映射」自证行。
        // 🆕 TASK-074：改读**四动作自选**（不再读旧的左右/上下反转开关）——
        //   本机方向已由上面四个单选全权决定，与对端口径的 rev 开关彻底解耦。
        String prev = host.getString(R.string.lab_shake_page_prev);
        String next = host.getString(R.string.lab_shake_page_next);
        String lrCol = CardPrefs.isShakeLocalActNext(host, CardPrefs.SHAKE_ACT_LEFT)  ? next : prev;
        String rrCol = CardPrefs.isShakeLocalActNext(host, CardPrefs.SHAKE_ACT_RIGHT) ? next : prev;
        String udCol = CardPrefs.isShakeLocalActNext(host, CardPrefs.SHAKE_ACT_UP)    ? next : prev;
        String ddCol = CardPrefs.isShakeLocalActNext(host, CardPrefs.SHAKE_ACT_DOWN)  ? next : prev;
        if (!CardPrefs.isShakeAxisLrEnabled(host)) {
            String off = host.getString(R.string.lab_shake_map_axis_off,
                    host.getString(R.string.lab_shake_axis_lr));
            lrCol = off;
            rrCol = off;
        }
        if (!CardPrefs.isShakeAxisUdEnabled(host)) {
            String off = host.getString(R.string.lab_shake_map_axis_off,
                    host.getString(R.string.lab_shake_axis_ud));
            udCol = off;
            ddCol = off;
        }
        tvShakeLocalNote.setText(host.getString(R.string.lab_shake_local_note_on,
                host.getString(R.string.lab_shake_map_now, lrCol, rrCol, udCol, ddCol)));
    }

    /**
     * 🆕 TASK-073：本机加速度计**是否真的可用**（不是"清单里有"）。
     *
     * <p>🔴 判据必须是「**真注册一次 + 看返回值**」，**不能**只判
     * {@link SensorManager#getDefaultSensor}{@code (TYPE_ACCELEROMETER)} 非 null ——
     * 两者在 S4（阅星曈墨水屏）上**恰好相反**：`dumpsys sensorservice` 的 Sensor List 里
     * **列着** `Accelerometer sensor`、`pm list features` 也**声明**了
     * `android.hardware.sensor.accelerometer`，但 `registerListener` **恒返回 false**
     * （HAL 无后端：`/dev/mma8452_daemon` 缺失、无 `gsensor` input 设备；
     * 见 `验证记录/102` 与 `验证记录/182`）⇒ 只判 `getDefaultSensor` 会**误判为"有传感器"**，
     * 真机表现就是"开关能勾、勾了没反应"（正是本卡要避免的"假装可用"）。
     *
     * <p>做法：注册一个**空监听器**取返回值，随即注销（同步返回，无需等数据）——
     * 重载与 {@code ShakeDetector.start()} 对齐（先 3 参 {@code SENSOR_DELAY_GAME}，
     * 失败再退 {@code SENSOR_DELAY_NORMAL}），确保"这里说可用 ⇒ 检测器真能起来"。
     *
     * <p>🔴 fail-safe：任何异常一律返回 false（宁可"说不可用"也不给一个点了没反应的开关）。
     */
    private boolean hasAccelerometer() {
        SensorManager sm = null;
        SensorEventListener probe = null;
        try {
            sm = (SensorManager) host.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) {
                return false;
            }
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc == null) {
                return false;
            }
            probe = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            boolean ok = sm.registerListener(probe, acc, SensorManager.SENSOR_DELAY_GAME);
            if (!ok) {
                ok = sm.registerListener(probe, acc, SensorManager.SENSOR_DELAY_NORMAL);
            }
            Log.i("LabPage", "hasAccelerometer: getDefaultSensor!=null, registerListener=" + ok
                    + (ok ? " ⇒ 可用" : " ⇒ 判为不可用（HAL 无后端？见 验证记录/102）"));
            return ok;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (sm != null && probe != null) {
                    sm.unregisterListener(probe);
                }
            } catch (Throwable ignore) {
                // 注销失败不影响判据
            }
        }
    }

    /** 🆕 TASK-074：把某个「四动作方向」单选组回填到指定态（null 组安全跳过）。 */
    private static void checkDir(RadioGroup rg, int prevId, int nextId, boolean next) {
        if (rg == null) {
            return;
        }
        rg.check(next ? nextId : prevId);
    }

    /** 会话状态回调（主线程）—— 只更新状态行与两个按钮的可用性。 */
    private final RemoteLinkManager.StateListener mStateListener = new RemoteLinkManager.StateListener() {
        @Override
        public void onStateChanged(int state, String peerIp, String detail) {
            showSessionState(state, peerIp, detail);
        }
    };

    /** 🆕 TASK-033：HID 注册 / 连接态回调 —— 只刷「蓝牙控制」状态行（服务端推，非轮询）。
     *  🔴 回调来自 HID 的 binder 线程（`BluetoothHidDevice.Callback`）⇒ 必须切回主线程再动 View，
     *  否则触发 `CalledFromWrongThreadException`（会被服务端 try/catch 吞掉、状态行静默不刷新）。 */
    private final HidKeepAliveService.StateListener mHidStateListener = new HidKeepAliveService.StateListener() {
        @Override
        public void onHidStateChanged() {
            host.runOnUiThread(new Runnable() {
                @Override public void run() {
                    refreshBtUi();
                    refreshConnUi();   // 🆕 TASK-072：HID 连态变化 ⇒ 状态胶囊同步
                }
            });
        }
    };

    /** 把会话状态写进状态行，并设置开始/结束两个按钮的可用性。 */
    private void showSessionState(int state, String peerIp, String detail) {
        if (tvRemoteStatus == null) return;
        String text;
        switch (state) {
            case RemoteLinkManager.STATE_CONNECTING:
                text = (detail != null && detail.length() > 0) ? detail : "连接中…";
                break;
            case RemoteLinkManager.STATE_CONNECTED:
                text = (peerIp != null && peerIp.length() > 0)
                        ? host.getString(R.string.lab_status_connected, peerIp) : "已连接";
                break;
            case RemoteLinkManager.STATE_CLOSED:
                text = (detail != null && detail.length() > 0) ? detail : "已断开";
                break;
            case RemoteLinkManager.STATE_IDLE:
            default:
                text = host.getString(R.string.lab_status_idle);
                break;
        }
        tvRemoteStatus.setText(host.getString(R.string.lab_status_prefix) + text);
        mLastSessionText = text;   // 🆕 TASK-072：供状态胶囊拼「当前：热点连接 · …」
        refreshSessionButtons(state);
        refreshConnUi();           // 🆕 TASK-072：会话态变化 ⇒ 胶囊同步
    }

    /** 会话进行中（CONNECTING / CONNECTED）禁用「开始」—— 避免重复起会话。 */
    private void refreshSessionButtons(int state) {
        boolean active = (state == RemoteLinkManager.STATE_CONNECTING
                || state == RemoteLinkManager.STATE_CONNECTED);
        if (btnRemoteStart != null) btnRemoteStart.setEnabled(!active);
        if (btnRemoteStop != null) btnRemoteStop.setEnabled(active);
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_LONG).show();
    }
}
