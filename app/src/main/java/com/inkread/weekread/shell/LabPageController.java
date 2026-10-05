package com.inkread.weekread.shell;

import com.inkread.weekread.R;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.LockPrefs;
import com.inkread.weekread.core.PowerSettingsManager;
import com.inkread.weekread.remote.HidKeepAliveService;
import com.inkread.weekread.remote.HidLink;
import com.inkread.weekread.remote.RemoteKeyService;
import com.inkread.weekread.remote.RemoteLinkManager;
import com.inkread.weekread.remote.RemoteRole;
import com.inkread.weekread.remote.ShakeDetector;
import com.inkread.weekread.ui.SegTabView;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
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

/**
 * 实验室页控制器（🆕 TASK-045 / V1.2.0-beta 抽出）。
 *
 * <p>接管 {@code page_lab} 容器（{@code seg_lab} + 4 个子容器：热点翻页 / 蓝牙控制 /
 * 锁屏密码 / 续航优化）的**全部**逻辑 —— 即改造前 {@link SettingsActivity} 里
 * lab 相关段落的**逐字平移**（遥控会话、晃动翻页、蓝牙 HID、应用级软锁、省电指令）。
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

    /** 申请「读取存储」权限的请求码（TASK-022-R1：载入背景图用）。 */
    private static final int REQ_PERM_BG = 3302;
    /** 🆕 TASK-035：ACTION_REQUEST_DISCOVERABLE 的请求码。 */
    private static final int REQ_BT_DISCOVERABLE = 3501;

    public LabPageController(Activity host) {
        this.host = host;
    }

    // ══════════════════════ 装配 ══════════════════════

    /** 装配本页全部控件与监听（等价于改造前 {@link SettingsActivity#onCreate} 的 lab 段）。 */
    public void bind() {
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

        // ── 🆕 TASK-033 实验室 · 蓝牙控制 ──
        tvBtIntro = (TextView) host.findViewById(R.id.tv_bt_intro);
        llBtControls = host.findViewById(R.id.ll_bt_controls);
        cbBtEnabled = (CheckBox) host.findViewById(R.id.cb_bt_enabled);
        tvBtUnsupported = (TextView) host.findViewById(R.id.tv_bt_unsupported);
        tvBtStatus = (TextView) host.findViewById(R.id.tv_bt_status);
        tvBtReconnectGuide = (TextView) host.findViewById(R.id.tv_bt_reconnect_guide);
        tvBtPairGuide = (TextView) host.findViewById(R.id.tv_bt_pair_guide);
        llBtDiscover = host.findViewById(R.id.ll_bt_discover);
        btnBtDiscoverable = (Button) host.findViewById(R.id.btn_bt_discoverable);
        llBtTest = host.findViewById(R.id.ll_bt_test);
        btnBtTestPrev = (Button) host.findViewById(R.id.btn_bt_test_prev);
        btnBtTestNext = (Button) host.findViewById(R.id.btn_bt_test_next);
        tvBtEinkNote = (TextView) host.findViewById(R.id.tv_bt_eink_note);

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
            }
        };
        rbRoleOff.setOnCheckedChangeListener(roleL);
        rbRoleEink.setOnCheckedChangeListener(roleL);
        rbRolePhone.setOnCheckedChangeListener(roleL);

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
                ShakeDetector.sync(host);   // G2 即时生效
            }
        };
        cbShakeEnabled.setOnCheckedChangeListener(shakeL);
        cbShakeLrRev.setOnCheckedChangeListener(shakeL);
        cbShakeUdRev.setOnCheckedChangeListener(shakeL);
        cbShakeAxisLr.setOnCheckedChangeListener(shakeL);   // 🆕 TASK-042
        cbShakeAxisUd.setOnCheckedChangeListener(shakeL);

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
        final int[] labelRes = {
                R.string.lab_tab_remote,       // 热点翻页
                R.string.lab_tab_bt,           // 蓝牙控制
                R.string.lab_tab_lockscreen,   // 锁屏密码
                R.string.lab_tab_power };      // 续航优化
        final View[] page = {
                host.findViewById(R.id.page_lab_remote),
                host.findViewById(R.id.page_lab_bt),
                host.findViewById(R.id.page_lab_lockscreen),
                host.findViewById(R.id.page_lab_power) };
        // 蓝牙控制：手机端（发键）与阅读器端（只读说明）都可见 ⇒ 两侧都装配
        final boolean[] phoneRelevant = { true, true, false, false };

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
        final int count = n;
        // 🔴 滚动容器不写死 id：实验室在 settings_layout.xml 里复用 sv_settings，
        //    在 page_lab.xml 里是自己的 sv_lab ⇒ 由 page_lab 向上找最近的 ScrollView。
        final ScrollView sv = scrollOf(host.findViewById(R.id.page_lab));
        segLab.setListener(new SegTabView.Listener() {
            @Override
            public void onSegSelected(int index) {
                for (int i = 0; i < count; i++) {
                    page[keep[i]].setVisibility(i == index ? View.VISIBLE : View.GONE);
                }
                if (sv != null) sv.scrollTo(0, 0);
                int orig = keep[index];
                if (orig == 1) refreshBtUi();      // TASK-033：蓝牙控制（按角色/连态刷状态行）
                if (orig == 2) refreshLockUi();
                // TASK-023：进续航页复核一次 —— 勾选态一律读设备真值（不读偏好，防「显示已开/实际已关」）
                if (orig == 3) refreshPowerUi();
            }
        });

        // 复位到第 0 个可见子页（setLabels 只在越界时钳 selected，故这里显式置一次可见性）
        segLab.setSelected(0);
        for (int i = 0; i < count; i++) {
            page[keep[i]].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        }
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
    }

    /** 宿主 onPause 调用：摘掉本页持有的所有监听（离页不持引用）。 */
    public void onPause() {
        // 🔴 TASK-029 §2.2：用 removeStateListener —— 只摘自己这一个，不影响遥控服务的监听
        RemoteLinkManager.get().removeStateListener(mStateListener);
        HidKeepAliveService.removeListener(mHidStateListener);
    }

    /** 宿主 onResume 调用：锁屏 + 续航子页状态复核。 */
    public void refreshAll() {
        refreshLockUi();
        refreshPowerUi();
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
        if (!phone) return;   // 墨水屏端到此为止（无开关、无状态行）

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

        if (tvRoleHint != null) {
            tvRoleHint.setText(role == RemoteRole.EINK ? host.getString(R.string.lab_role_hint_eink)
                    : role == RemoteRole.PHONE ? host.getString(R.string.lab_role_hint_phone)
                    : host.getString(R.string.lab_role_hint_off));
        }
        if (llRemoteSession != null) {
            llRemoteSession.setVisibility(role == RemoteRole.OFF ? View.GONE : View.VISIBLE);
        }
        // 🆕 TASK-029：晃动翻页块**仅手机角色**可见（A2 —— 墨水屏 / 关闭角色下不可见且零响应）
        if (llShakeBlock != null) {
            llShakeBlock.setVisibility(role == RemoteRole.PHONE ? View.VISIBLE : View.GONE);
        }
        refreshShakeUi();

        // 会话状态：注册监听会立刻回推一次当前状态（页面无需手动刷新）
        // 🔴 TASK-029 §2.2：单槽 setStateListener ⇒ addStateListener。否则本页监听会与遥控服务
        //    （晃动捕获门控）的监听互相顶掉。
        RemoteLinkManager.get().addStateListener(mStateListener);

        // 🆕 TASK-029：角色可能刚改过（G1）⇒ 重算晃动捕获层的注册/注销。
        ShakeDetector.sync(host);
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
                @Override public void run() { refreshBtUi(); }
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
        refreshSessionButtons(state);
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
