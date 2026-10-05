package com.inkread.weekread.shell;

import com.inkread.weekread.R;
import com.inkread.weekread.a11y.CardA11yService;
import com.inkread.weekread.core.AchievementPrefs;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.LockPrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PowerSettingsManager;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.feature.NoteExport;
import com.inkread.weekread.net.UpdateChecker;
import com.inkread.weekread.net.WereadApi;
import com.inkread.weekread.remote.HidKeepAliveService;
import com.inkread.weekread.remote.HidLink;
import com.inkread.weekread.remote.RemoteKeyService;
import com.inkread.weekread.remote.RemoteLinkManager;
import com.inkread.weekread.remote.RemoteRole;
import com.inkread.weekread.remote.ShakeDetector;
import com.inkread.weekread.ui.InkTheme;
import com.inkread.weekread.ui.SegTabView;
import com.inkread.weekread.update.ApkInstaller;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;


import java.io.File;
import java.util.Locale;

/**
 * 设置页：API Key + 桌面卡片开关 + **卡片显示周期（本周 / 本月）**。
 *
 * 全部内容控制在一屏内（800px 高 / 约 584dp），不需要滚动 —— 第一眼就能看全，
 * 不用去猜下面还藏着什么。
 *
 * v0.3 原来有「三种显示方式」三选，现已收敛：
 *   · 桌面壁纸模式 —— 本机实测桌面窗口不透明，壁纸在桌面上根本看不见，删掉；
 *   · ADB 悬浮窗   —— 需要电脑跑 adb 才能生效，且无法限定"只在桌面显示"，删掉；
 *   · 无障碍悬浮   —— 实测可用、无需额外权限、重启自动恢复，保留为唯一方式。
 *
 * v0.3.3 新增「卡片显示周期」单选（设计方案的①-B）：**这里定默认值**，
 * 桌面卡片左上角点按可临时切换 —— 两个入口改的是**同一个**偏好，不会打架。
 *
 * v0.4.3 按**使用流程**把设置拆成两页（顶部 {@link SegTabView} 切换）：
 *   · **初始化**（默认页，第一次装完照顺序做）：API Key（含取 Key 说明）→ 无障碍 → 桌面卡片
 *     （开关 + 显示周期 + 状态）→「怎么用」；
 *   · **自定义**（纯个性化）：本记导出模板，即时生效。
 * v0.7（TASK-000）再加第三页「实验室」（设备能力自检 · 只读）；
 * **v1.0 Beta（TASK-018）第三页改版**：
 *   · **实验室**：实验性功能页 —— 遥控翻页的角色开关 + 提示（原「设备能力自检」已按
 *     `docs/FEATURES/lab.md` 清除清单删除）。
 * TASK-020 在「初始化」页的**版本与更新**区加一行「更新通道」（正式版 / Beta）：
 *   · 两条通道共用同一包名/签名、同一个全局 vc 池，唯一差别 = 读哪一份清单；
 *   · 两个并排选项 + 一条常显说明行，**不做弹窗**；换通道后重置更新区状态。
 * 三个页面是同一 ScrollView 里的三个容器，切页只切 visibility —— 已填的 Key、已选的
 * 单选按钮状态天然保留，不需要在多个 Activity 之间搬运。
 */
public class SettingsActivity extends Activity {

    private EditText etKey;
    private CheckBox cbCard;
    private CheckBox cbSinglePage;   // TASK-011 仅一页模式（自定义页 · 桌面卡片分区）
    private CheckBox cbBindWeek;     // TASK-012 刷新绑定（自定义页 · 桌面卡片分区）
    private CheckBox cbBindMonth;
    private CheckBox cbBindBook;

    // ── v0.9（TASK-013）阅读成就提示（自定义页 · 桌面卡片分区）──
    private CheckBox cbAchv;
    private RadioButton rbAchvWeekOff;
    private RadioButton rbAchvWeekPerfect;
    private RadioButton rbAchvWeekBerserk;
    private RadioButton rbAchvWeekCustom;
    private RadioButton rbAchvMonthOff;
    private RadioButton rbAchvMonthPerfect;
    private RadioButton rbAchvMonthBerserk;
    private RadioButton rbAchvMonthCustom;
    private EditText etAchvWeekHours;    // 自定义周目标（小时，≤2 位小数）
    private EditText etAchvMonthHours;   // 自定义月目标

    private RadioButton rbWeek;
    private RadioButton rbMonth;
    private RadioButton rbBook;
    private RadioButton rbNote;

    // ── v0.8.1（TASK-014）本月呈现方式（自定义页 · 桌面卡片分区）──
    private RadioButton rbMonthStyleCheckin;   // 打卡网格
    private RadioButton rbMonthStyleHeatmap;   // 阅读热力图（默认）

    // ── v0.9（TASK-016）本记字号（卡片）（自定义页 · 桌面卡片分区）──
    private SeekBar seekNoteCardSize;          // 12–24 号连续调节
    private TextView tvNoteCardSize;           // 当前值，如「17 号 · 20.4px」

    // ── V1.0.3-beta（TASK-025）卡片切换方式 + 桌面显示的卡片（自定义页 · 桌面卡片分区）──
    private RadioButton rbSwitchLoop;          // 循环模式（默认）
    private RadioButton rbSwitchList;          // 列表模式
    private CheckBox cbPoolWeek;               // 卡片池：本周
    private CheckBox cbPoolMonth;              // 卡片池：本月
    private CheckBox cbPoolBook;               // 卡片池：本书
    private CheckBox cbPoolNote;               // 卡片池：本记
    private CheckBox cbPoolTodo;               // 卡片池：待办

    private TextView tvStatus;
    private TextView tvVersion;
    private TextView tvUpdateStatus;
    private Button btnUpdate;

    // ── TASK-020：更新通道（初始化页 · 版本与更新区）──
    private RadioButton rbChannelStable;      // 正式版（稳定，默认）
    private RadioButton rbChannelBeta;        // Beta（尝鲜）
    private TextView tvChannelTip;            // 常显说明行（按通道切换）
    private CheckBox cbCloudFallback;         // 云端备用更新源（TASK-030，默认开）

    // ── V1.0 Beta（TASK-018）实验室 · 遥控翻页 ──
    // 页面元素：角色三选一 + 按角色变化的说明 + 会话开关/状态 + 空闲超时 + 互斥提示。
    private RadioButton rbRoleOff;
    private RadioButton rbRoleEink;
    private RadioButton rbRolePhone;
    private TextView tvRoleHint;         // 按角色变化的说明文本
    private View llRemoteSession;        // 会话控件容器（role != off 才显示）
    private Button btnRemoteStart;
    private Button btnRemoteStop;
    private TextView tvRemoteStatus;     // 「状态：…」
    private EditText etRemoteIdle;       // 空闲自动断开（秒）
    // phone 角色整块隐藏「桌面卡片」分区（A7）
    private View sectionCardInit;        // 初始化页 ③ 桌面卡片
    private View sectionCardCustom;      // 自定义页「桌面卡片」

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

    /** 本会话是否处于 phone 角色（onCreate 时定一次；角色改变只在实验室页内发生）。 */
    private boolean remotePhone;

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

    // ── v0.5.0 更新区状态机 ──
    // 一个按钮走完全程（检查 → 下载并安装 → 下载中 xx%），按钮文字始终说明「下一步会发生什么」。
    // 墨水屏上弹确认对话框又笨又慢，用按钮文字表达意图更合适。
    private static final int U_IDLE = 0;
    private static final int U_CHECKING = 1;
    private static final int U_HAS = 2;
    private static final int U_DOWNLOADING = 3;
    private int uState = U_IDLE;
    private UpdateChecker.Info uInfo;

    // ── 🆕 TASK-031：安装角色（App 形态，与 remote_role 正交）──

    /** 主入口以「手机端」进入时携带：设置页直接定位到「实验室」页。 */
    public static final String EXTRA_OPEN_LAB = "open_lab";

    /** 初始化页 ⓪「本机角色」单选组（阅读器端 / 手机端）。 */
    private RadioGroup rgInstallRole;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 🆕 TASK-041：手机端 + 深色 ⇒ 换深色主题（顶栏/系统装饰一并转深）。
        //   🔴 必须在 setContentView 之前调用，否则不生效。reader 端不满足条件 ⇒ 主题不变（零差异）。
        if (CardPrefs.getInstallRole(this) == CardPrefs.INSTALL_ROLE_PHONE
                && CardPrefs.isPhoneDarkMode(this)) {
            setTheme(R.style.Theme_WereadMoji_Settings_Dark);
        }
        setContentView(R.layout.settings_layout);

        etKey = (EditText) findViewById(R.id.et_key);
        cbCard = (CheckBox) findViewById(R.id.cb_card);
        cbSinglePage = (CheckBox) findViewById(R.id.cb_single_page);
        cbBindWeek = (CheckBox) findViewById(R.id.cb_bind_week);
        cbBindMonth = (CheckBox) findViewById(R.id.cb_bind_month);
        cbBindBook = (CheckBox) findViewById(R.id.cb_bind_book);

        // ── v0.9（TASK-013）阅读成就提示 ──
        cbAchv = (CheckBox) findViewById(R.id.cb_achv);
        rbAchvWeekOff = (RadioButton) findViewById(R.id.rb_achv_week_off);
        rbAchvWeekPerfect = (RadioButton) findViewById(R.id.rb_achv_week_perfect);
        rbAchvWeekBerserk = (RadioButton) findViewById(R.id.rb_achv_week_berserk);
        rbAchvWeekCustom = (RadioButton) findViewById(R.id.rb_achv_week_custom);
        rbAchvMonthOff = (RadioButton) findViewById(R.id.rb_achv_month_off);
        rbAchvMonthPerfect = (RadioButton) findViewById(R.id.rb_achv_month_perfect);
        rbAchvMonthBerserk = (RadioButton) findViewById(R.id.rb_achv_month_berserk);
        rbAchvMonthCustom = (RadioButton) findViewById(R.id.rb_achv_month_custom);
        etAchvWeekHours = (EditText) findViewById(R.id.et_achv_week);
        etAchvMonthHours = (EditText) findViewById(R.id.et_achv_month);

        // v0.8.1（TASK-014）本月呈现方式
        rbMonthStyleCheckin = (RadioButton) findViewById(R.id.rb_month_style_checkin);
        rbMonthStyleHeatmap = (RadioButton) findViewById(R.id.rb_month_style_heatmap);

        // V1.0.3-beta（TASK-025）卡片切换方式 + 桌面显示的卡片
        rbSwitchLoop = (RadioButton) findViewById(R.id.rb_switch_loop);
        rbSwitchList = (RadioButton) findViewById(R.id.rb_switch_list);
        cbPoolWeek = (CheckBox) findViewById(R.id.cb_pool_week);
        cbPoolMonth = (CheckBox) findViewById(R.id.cb_pool_month);
        cbPoolBook = (CheckBox) findViewById(R.id.cb_pool_book);
        cbPoolNote = (CheckBox) findViewById(R.id.cb_pool_note);
        cbPoolTodo = (CheckBox) findViewById(R.id.cb_pool_todo);

        rbWeek = (RadioButton) findViewById(R.id.rb_period_week);
        rbMonth = (RadioButton) findViewById(R.id.rb_period_month);
        rbBook = (RadioButton) findViewById(R.id.rb_period_book);
        rbNote = (RadioButton) findViewById(R.id.rb_period_note);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvVersion = (TextView) findViewById(R.id.tv_version);
        tvUpdateStatus = (TextView) findViewById(R.id.tv_update_status);
        btnUpdate = (Button) findViewById(R.id.btn_update);
        rbChannelStable = (RadioButton) findViewById(R.id.rb_channel_stable);
        rbChannelBeta = (RadioButton) findViewById(R.id.rb_channel_beta);
        tvChannelTip = (TextView) findViewById(R.id.tv_channel_tip);
        cbCloudFallback = (CheckBox) findViewById(R.id.cb_cloud_fallback);

        // ── V1.0 Beta（TASK-018）实验室 · 遥控翻页 ──
        rbRoleOff = (RadioButton) findViewById(R.id.rb_role_off);
        rbRoleEink = (RadioButton) findViewById(R.id.rb_role_eink);
        rbRolePhone = (RadioButton) findViewById(R.id.rb_role_phone);
        tvRoleHint = (TextView) findViewById(R.id.tv_role_hint);
        llRemoteSession = findViewById(R.id.ll_remote_session);
        btnRemoteStart = (Button) findViewById(R.id.btn_remote_start);
        btnRemoteStop = (Button) findViewById(R.id.btn_remote_stop);
        tvRemoteStatus = (TextView) findViewById(R.id.tv_remote_status);
        etRemoteIdle = (EditText) findViewById(R.id.et_remote_idle);
        sectionCardInit = findViewById(R.id.section_card_init);
        sectionCardCustom = findViewById(R.id.section_card_custom);
        llShakeBlock = findViewById(R.id.ll_shake_block);          // 🆕 TASK-029
        cbShakeEnabled = (CheckBox) findViewById(R.id.cb_shake_enabled);
        rgShakeSens = (RadioGroup) findViewById(R.id.rg_shake_sens);   // 🆕 灵敏度三档
        rbShakeSensLow = (RadioButton) findViewById(R.id.rb_shake_sens_low);
        rbShakeSensMid = (RadioButton) findViewById(R.id.rb_shake_sens_mid);
        rbShakeSensHigh = (RadioButton) findViewById(R.id.rb_shake_sens_high);
        cbShakeLrRev = (CheckBox) findViewById(R.id.cb_shake_lr_rev);
        cbShakeUdRev = (CheckBox) findViewById(R.id.cb_shake_ud_rev);
        llShakeAxis = findViewById(R.id.ll_shake_axis);               // 🆕 TASK-042
        cbShakeAxisLr = (CheckBox) findViewById(R.id.cb_shake_axis_lr);
        cbShakeAxisUd = (CheckBox) findViewById(R.id.cb_shake_axis_ud);
        tvShakeMap = (TextView) findViewById(R.id.tv_shake_map);

        // ── 🆕 TASK-033 实验室 · 蓝牙控制 ──
        tvBtIntro = (TextView) findViewById(R.id.tv_bt_intro);
        llBtControls = findViewById(R.id.ll_bt_controls);
        cbBtEnabled = (CheckBox) findViewById(R.id.cb_bt_enabled);
        tvBtUnsupported = (TextView) findViewById(R.id.tv_bt_unsupported);
        tvBtStatus = (TextView) findViewById(R.id.tv_bt_status);
        tvBtReconnectGuide = (TextView) findViewById(R.id.tv_bt_reconnect_guide);
        tvBtPairGuide = (TextView) findViewById(R.id.tv_bt_pair_guide);
        llBtDiscover = findViewById(R.id.ll_bt_discover);
        btnBtDiscoverable = (Button) findViewById(R.id.btn_bt_discoverable);
        llBtTest = findViewById(R.id.ll_bt_test);
        btnBtTestPrev = (Button) findViewById(R.id.btn_bt_test_prev);
        btnBtTestNext = (Button) findViewById(R.id.btn_bt_test_next);
        tvBtEinkNote = (TextView) findViewById(R.id.tv_bt_eink_note);

        // ── v0.4.3 顶部页签「初始化 / 自定义 / 实验室」；v0.7 加第三段「实验室」──
        // 三个页面是同一个 ScrollView 里的三个容器，切页只切 visibility。
        // 🆕 TASK-031：切换逻辑抽到 showTopPage()，便于「手机端入口」程序化定位到实验室页。
        final SegTabView seg = (SegTabView) findViewById(R.id.seg);
        // SegTabView 默认只给两段标签，这里显式扩到三段（它本来就是通用 N 段控件）
        seg.setLabels(new String[]{"初始化", "自定义", "实验室"});
        seg.setListener(new SegTabView.Listener() {
            @Override
            public void onSegSelected(int index) {
                showTopPage(index);
            }
        });

        // ── 🆕 TASK-021 / 022 / 023 实验室子标签（遥控翻页 | 锁屏密码 | 续航优化）──
        // 🆕 TASK-031：改为「按 install_role 动态装配」（手机端形态只装手机端关联子标签）。
        //   作用与顶部 seg 相同：同一个 ScrollView 内，切换子页容器的 visibility。详见 bindLabTabs()。
        bindLabTabs();

        // ── 🆕 TASK-022 锁屏密码子页（应用级软锁）──
        // 落盘在 LockPrefs（盐 + SHA-256，非明文）；开关默认关 ⇒ 老用户升级后零差异（验收 A6）。
        cbLockEnabled = (CheckBox) findViewById(R.id.cb_lock_enabled);
        tvLockStatus = (TextView) findViewById(R.id.tv_lock_status);
        tvLockBgStatus = (TextView) findViewById(R.id.tv_lock_bg_status);
        etLockBgPath = (EditText) findViewById(R.id.et_lock_bg_path);
        // 输入框只在这里初始化一次（不在 refreshLockUi 里回填，免得把用户正在输的内容冲掉）
        String bgCur = LockPrefs.getBgPath(this);
        etLockBgPath.setText(bgCur.length() == 0 ? LockPrefs.DEFAULT_BG_REL : bgCur);
        final EditText etLockPin = (EditText) findViewById(R.id.et_lock_pin);

        cbLockEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (lockUiSyncing) return;
                if (checked && !LockPrefs.hasPin(SettingsActivity.this)) {
                    // 没设密码就启用 = 一开屏就锁死且无密码可解 ⇒ 拒绝并回滚勾选
                    lockUiSyncing = true;
                    b.setChecked(false);
                    lockUiSyncing = false;
                    Toast.makeText(SettingsActivity.this, R.string.lock_need_pin, Toast.LENGTH_LONG).show();
                    return;
                }
                LockPrefs.setEnabled(SettingsActivity.this, checked);
                refreshLockUi();
            }
        });

        ((Button) findViewById(R.id.btn_lock_save)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pin = etLockPin.getText().toString().trim();
                if (!LockPrefs.isValidPin(pin)) {
                    Toast.makeText(SettingsActivity.this, R.string.lock_pin_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                LockPrefs.setPin(SettingsActivity.this, pin);
                etLockPin.setText("");
                refreshLockUi();
                Toast.makeText(SettingsActivity.this, R.string.lock_saved, Toast.LENGTH_SHORT).show();
            }
        });

        ((Button) findViewById(R.id.btn_lock_bg_load)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadLockBg();
            }
        });

        ((Button) findViewById(R.id.btn_lock_bg_clear)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LockPrefs.clearBg(SettingsActivity.this);
                etLockBgPath.setText(LockPrefs.DEFAULT_BG_REL);
                refreshLockUi();
            }
        });

        refreshLockUi();

        // ── 🆕 TASK-023 续航子页接线（省电指令）──
        // 写入需 WRITE_SECURE_SETTINGS（一次性 pm grant）。未授权 ⇒ 点击拦截 + 提示，不静默失败。
        tvPowerPerm = (TextView) findViewById(R.id.tv_power_perm);
        tvPowerCmd = (TextView) findViewById(R.id.tv_power_cmd);
        cbPowerWifiNotif = (CheckBox) findViewById(R.id.cb_power_wifi_notif);
        cbPowerAnim = (CheckBox) findViewById(R.id.cb_power_anim);
        cbPowerScreensaver = (CheckBox) findViewById(R.id.cb_power_screensaver);
        wirePowerItem(cbPowerWifiNotif, PowerSettingsManager.ITEM_WIFI_NET_NOTIF);
        wirePowerItem(cbPowerAnim, PowerSettingsManager.ITEM_ANIM);
        wirePowerItem(cbPowerScreensaver, PowerSettingsManager.ITEM_SCREENSAVER);
        ((Button) findViewById(R.id.btn_power_copy)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyGrantCmd();
            }
        });
        ((Button) findViewById(R.id.btn_power_battery_opt)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatteryOpt();
            }
        });
        refreshPowerUi();
        cbCard.setChecked(CardPrefs.isEnabled(this));
        String cp = StatsStore.getCardPeriod(this);
        rbWeek.setChecked(PeriodRange.WEEKLY.equals(cp));
        rbMonth.setChecked(PeriodRange.MONTHLY.equals(cp));
        rbBook.setChecked(PeriodRange.BOOK.equals(cp));
        rbNote.setChecked(PeriodRange.NOTE.equals(cp));

        // ── 本记导出模板 ──
        final RadioButton pPlain = (RadioButton) findViewById(R.id.rb_paper_plain);
        final RadioButton pJournal = (RadioButton) findViewById(R.id.rb_paper_journal);
        final RadioButton pCard = (RadioButton) findViewById(R.id.rb_paper_card);
        final RadioButton sS = (RadioButton) findViewById(R.id.rb_nsize_s);
        final RadioButton sM = (RadioButton) findViewById(R.id.rb_nsize_m);
        final RadioButton sL = (RadioButton) findViewById(R.id.rb_nsize_l);
        final CheckBox cbSign = (CheckBox) findViewById(R.id.cb_note_sign);
        final CheckBox cbDate = (CheckBox) findViewById(R.id.cb_note_date);
        int paper = NoteExport.paper(this);
        pPlain.setChecked(paper == 0);
        pJournal.setChecked(paper == 1);
        pCard.setChecked(paper == 2);
        int tier = NoteExport.sizeTier(this);
        sS.setChecked(tier == 0);
        sM.setChecked(tier == 1);
        sL.setChecked(tier == 2);
        cbSign.setChecked(NoteExport.showSign(this));
        cbDate.setChecked(NoteExport.showDate(this));
        CompoundButton.OnCheckedChangeListener paperL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;
                int id = b.getId();
                NoteExport.setPaper(SettingsActivity.this,
                        id == R.id.rb_paper_plain ? 0 : id == R.id.rb_paper_journal ? 1 : 2);
            }
        };
        pPlain.setOnCheckedChangeListener(paperL);
        pJournal.setOnCheckedChangeListener(paperL);
        pCard.setOnCheckedChangeListener(paperL);
        CompoundButton.OnCheckedChangeListener sizeL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;
                int id = b.getId();
                NoteExport.setSizeTier(SettingsActivity.this,
                        id == R.id.rb_nsize_s ? 0 : id == R.id.rb_nsize_m ? 1 : 2);
            }
        };
        sS.setOnCheckedChangeListener(sizeL);
        sM.setOnCheckedChangeListener(sizeL);
        sL.setOnCheckedChangeListener(sizeL);
        cbSign.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                NoteExport.setShowSign(SettingsActivity.this, checked);
            }
        });
        cbDate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                NoteExport.setShowDate(SettingsActivity.this, checked);
            }
        });

        cbCard.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                CardPrefs.setEnabled(SettingsActivity.this, checked);
                CardA11yService.sync();
                refreshStatus();
            }
        });

        // TASK-011 仅一页模式：改完立即重算一次显隐（与卡片开关同款既有路径）
        cbSinglePage.setChecked(CardPrefs.isSinglePageMode(this));
        cbSinglePage.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                CardPrefs.setSinglePageMode(SettingsActivity.this, checked);
                CardA11yService.sync();
                refreshStatus();
            }
        });

        // ── TASK-012 刷新绑定：三个勾选 = bind_targets 位掩码 ──
        // 只写偏好，不 CardA11yService.sync()、不重绘 —— 下次点「更新于…」时才读它。
        // 全不勾 = 0 = 刷新退回"只拉当前形态"的旧行为。
        int bindTargets = CardPrefs.getBindTargets(this);
        cbBindWeek.setChecked((bindTargets & CardPrefs.BIND_WEEK) != 0);
        cbBindMonth.setChecked((bindTargets & CardPrefs.BIND_MONTH) != 0);
        cbBindBook.setChecked((bindTargets & CardPrefs.BIND_BOOK) != 0);
        CompoundButton.OnCheckedChangeListener bindL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                int t = 0;
                if (cbBindWeek.isChecked()) t |= CardPrefs.BIND_WEEK;
                if (cbBindMonth.isChecked()) t |= CardPrefs.BIND_MONTH;
                if (cbBindBook.isChecked()) t |= CardPrefs.BIND_BOOK;
                CardPrefs.setBindTargets(SettingsActivity.this, t);
            }
        };
        cbBindWeek.setOnCheckedChangeListener(bindL);
        cbBindMonth.setOnCheckedChangeListener(bindL);
        cbBindBook.setOnCheckedChangeListener(bindL);

        // ── TASK-013 阅读成就提示（自定义页 · 桌面卡片分区）──
        // 总开关 + 周/月各一组四选（关｜完美｜狂暴｜自定义）+ 自定义小时输入框。
        // 任一变更 → 写 achv → CardA11yService.sync()：卡片只重算内容（成就行随 prefs 重算），
        // 不重发请求、不重开无障碍。默认全关 = 卡片版面与 v0.8.0 逐像素一致。
        cbAchv.setChecked(AchievementPrefs.isEnabled(this));
        cbAchv.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                AchievementPrefs.setEnabled(SettingsActivity.this, checked);
                CardA11yService.sync();
            }
        });

        int wk = AchievementPrefs.getWeekKind(this);
        int mk = AchievementPrefs.getMonthKind(this);
        rbAchvWeekOff.setChecked(wk == AchievementPrefs.KIND_OFF);
        rbAchvWeekPerfect.setChecked(wk == AchievementPrefs.KIND_PERFECT);
        rbAchvWeekBerserk.setChecked(wk == AchievementPrefs.KIND_BERSERK);
        rbAchvWeekCustom.setChecked(wk == AchievementPrefs.KIND_CUSTOM);
        rbAchvMonthOff.setChecked(mk == AchievementPrefs.KIND_OFF);
        rbAchvMonthPerfect.setChecked(mk == AchievementPrefs.KIND_PERFECT);
        rbAchvMonthBerserk.setChecked(mk == AchievementPrefs.KIND_BERSERK);
        rbAchvMonthCustom.setChecked(mk == AchievementPrefs.KIND_CUSTOM);
        CompoundButton.OnCheckedChangeListener achvWeekL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;               // 只管"被选中的那个"
                int id = b.getId();
                int kind = (id == R.id.rb_achv_week_perfect) ? AchievementPrefs.KIND_PERFECT
                        : (id == R.id.rb_achv_week_berserk) ? AchievementPrefs.KIND_BERSERK
                        : (id == R.id.rb_achv_week_custom) ? AchievementPrefs.KIND_CUSTOM
                        : AchievementPrefs.KIND_OFF;
                AchievementPrefs.setWeekKind(SettingsActivity.this, kind);
                etAchvWeekHours.setEnabled(kind == AchievementPrefs.KIND_CUSTOM);
                CardA11yService.sync();
            }
        };
        rbAchvWeekOff.setOnCheckedChangeListener(achvWeekL);
        rbAchvWeekPerfect.setOnCheckedChangeListener(achvWeekL);
        rbAchvWeekBerserk.setOnCheckedChangeListener(achvWeekL);
        rbAchvWeekCustom.setOnCheckedChangeListener(achvWeekL);
        CompoundButton.OnCheckedChangeListener achvMonthL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;
                int id = b.getId();
                int kind = (id == R.id.rb_achv_month_perfect) ? AchievementPrefs.KIND_PERFECT
                        : (id == R.id.rb_achv_month_berserk) ? AchievementPrefs.KIND_BERSERK
                        : (id == R.id.rb_achv_month_custom) ? AchievementPrefs.KIND_CUSTOM
                        : AchievementPrefs.KIND_OFF;
                AchievementPrefs.setMonthKind(SettingsActivity.this, kind);
                etAchvMonthHours.setEnabled(kind == AchievementPrefs.KIND_CUSTOM);
                CardA11yService.sync();
            }
        };
        rbAchvMonthOff.setOnCheckedChangeListener(achvMonthL);
        rbAchvMonthPerfect.setOnCheckedChangeListener(achvMonthL);
        rbAchvMonthBerserk.setOnCheckedChangeListener(achvMonthL);
        rbAchvMonthCustom.setOnCheckedChangeListener(achvMonthL);

        // 自定义小时输入框：先回填再挂监听（回填不触发解析）；输入过程逐字符解析，
        // 但只在"分钟值真的变了"才落盘+sync（见 hoursWatcher）—— 否则打一个字卡片闪一次。
        etAchvWeekHours.setText(minToHours(AchievementPrefs.getWeekMin(this)));
        etAchvWeekHours.setEnabled(wk == AchievementPrefs.KIND_CUSTOM);
        etAchvWeekHours.addTextChangedListener(hoursWatcher(true));
        etAchvMonthHours.setText(minToHours(AchievementPrefs.getMonthMin(this)));
        etAchvMonthHours.setEnabled(mk == AchievementPrefs.KIND_CUSTOM);
        etAchvMonthHours.addTextChangedListener(hoursWatcher(false));

        // ── v0.8.1（TASK-014）本月呈现方式：打卡 / 热力图（默认热力图） ──
        // 与成就单选同款：先回填（不触发回调），再挂监听。切换 = 一次整卡重绘。
        int ms = CardPrefs.getMonthStyle(this);
        rbMonthStyleCheckin.setChecked(ms == CardPrefs.MONTH_STYLE_CHECKIN);
        rbMonthStyleHeatmap.setChecked(ms == CardPrefs.MONTH_STYLE_HEATMAP);
        CompoundButton.OnCheckedChangeListener monthStyleL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;               // 只管"被选中的那个"
                int style = (b.getId() == R.id.rb_month_style_checkin)
                        ? CardPrefs.MONTH_STYLE_CHECKIN : CardPrefs.MONTH_STYLE_HEATMAP;
                CardPrefs.setMonthStyle(SettingsActivity.this, style);
                CardA11yService.sync();
            }
        };
        rbMonthStyleCheckin.setOnCheckedChangeListener(monthStyleL);
        rbMonthStyleHeatmap.setOnCheckedChangeListener(monthStyleL);

        // ── V1.0.3-beta（TASK-025）卡片切换方式：循环 / 列表 ──
        // 只影响"点抬头"的行为，不改卡片内容 ⇒ 只写偏好、不 sync（省一次墨水屏闪烁）。
        int swm = CardPrefs.getSwitchMode(this);
        rbSwitchLoop.setChecked(swm == CardPrefs.SWITCH_LOOP);
        rbSwitchList.setChecked(swm == CardPrefs.SWITCH_LIST);
        CompoundButton.OnCheckedChangeListener switchModeL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;               // 只管"被选中的那个"
                int m = (b.getId() == R.id.rb_switch_list)
                        ? CardPrefs.SWITCH_LIST : CardPrefs.SWITCH_LOOP;
                CardPrefs.setSwitchMode(SettingsActivity.this, m);
            }
        };
        rbSwitchLoop.setOnCheckedChangeListener(switchModeL);
        rbSwitchList.setOnCheckedChangeListener(switchModeL);

        // ── V1.0.3-beta（TASK-025）桌面显示的卡片（卡片池）：多选，至少留一张 ──
        // 勾选变化 → 写掩码；若**当前卡被移出池** ⇒ 立刻落到池里第一张（与 toggleCardPeriod
        // 的兜底同口径）——否则用户会停在"设置里明明没勾、桌面却还显示着"的矛盾状态。
        int poolMask = CardPrefs.getCardPoolMask(this);
        cbPoolWeek.setChecked((poolMask & CardPrefs.POOL_WEEK) != 0);
        cbPoolMonth.setChecked((poolMask & CardPrefs.POOL_MONTH) != 0);
        cbPoolBook.setChecked((poolMask & CardPrefs.POOL_BOOK) != 0);
        cbPoolNote.setChecked((poolMask & CardPrefs.POOL_NOTE) != 0);
        cbPoolTodo.setChecked((poolMask & CardPrefs.POOL_TODO) != 0);
        CompoundButton.OnCheckedChangeListener poolL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                int mask = 0;
                if (cbPoolWeek.isChecked()) mask |= CardPrefs.POOL_WEEK;
                if (cbPoolMonth.isChecked()) mask |= CardPrefs.POOL_MONTH;
                if (cbPoolBook.isChecked()) mask |= CardPrefs.POOL_BOOK;
                if (cbPoolNote.isChecked()) mask |= CardPrefs.POOL_NOTE;
                if (cbPoolTodo.isChecked()) mask |= CardPrefs.POOL_TODO;
                if (mask == 0) {
                    // 「至少保留一张」：把刚被取消的那张原样勾回去（会再次回调，那次 mask 已非 0）
                    b.setChecked(true);
                    Toast.makeText(SettingsActivity.this, R.string.card_pool_min_one,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                CardPrefs.setCardPoolMask(SettingsActivity.this, mask);
                String cur = StatsStore.getCardPeriod(SettingsActivity.this);
                if ((mask & StatsStore.poolBitOf(cur)) == 0) {
                    java.util.List<String> modes = StatsStore.poolModes(SettingsActivity.this);
                    if (!modes.isEmpty()) StatsStore.setCardPeriod(SettingsActivity.this, modes.get(0));
                }
                CardA11yService.sync();          // 桌面卡片立刻按新池子重绘 / 切卡
            }
        };
        cbPoolWeek.setOnCheckedChangeListener(poolL);
        cbPoolMonth.setOnCheckedChangeListener(poolL);
        cbPoolBook.setOnCheckedChangeListener(poolL);
        cbPoolNote.setOnCheckedChangeListener(poolL);
        cbPoolTodo.setOnCheckedChangeListener(poolL);

        // ── TASK-016 本记字号（卡片）：SeekBar 连续调节（12–24 号，默认 17）──
        //
        // 🔴 **墨水屏取舍：拖动中只更新数字，松手才落盘 + 让卡片重绘一次。**
        // 卡片每重绘一次就是一次肉眼可见的全屏闪；拖一次滑杆要闪十几下，那不是"体验略差"，
        // 是根本没法用。所以：onProgressChanged 只改这一行文字，真正的
        // setNoteCardSize + sync() 放在 onStopTrackingTouch（松手那一瞬间只闪一次）。
        // sync() 是既有路径（成就/本月呈现都走它）⇒ 服务 refresh 时由
        // CardContentController#applyNoteFont 按新偏好重塞字号，零新增请求。
        seekNoteCardSize = (SeekBar) findViewById(R.id.sb_note_card_size);
        tvNoteCardSize = (TextView) findViewById(R.id.tv_note_card_size);
        seekNoteCardSize.setMax(CardPrefs.NOTE_CARD_SIZE_MAX - CardPrefs.NOTE_CARD_SIZE_MIN);
        int noteCardSize = CardPrefs.getNoteCardSize(this);
        seekNoteCardSize.setProgress(noteCardSize - CardPrefs.NOTE_CARD_SIZE_MIN);
        tvNoteCardSize.setText(noteCardSizeLabel(noteCardSize));
        seekNoteCardSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                // 拖动中只动这一行字 —— 不落盘、不 sync（理由见上）
                tvNoteCardSize.setText(noteCardSizeLabel(progress + CardPrefs.NOTE_CARD_SIZE_MIN));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                // 不需要额外处理
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                CardPrefs.setNoteCardSize(SettingsActivity.this,
                        sb.getProgress() + CardPrefs.NOTE_CARD_SIZE_MIN);
                CardA11yService.sync();          // 桌面卡片立刻按新字号重绘（只此一次）
            }
        });

        CompoundButton.OnCheckedChangeListener periodListener =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        if (!checked) return;           // 只管"被选中的那个"
                        int id = b.getId();
                        String m = (id == R.id.rb_period_month) ? PeriodRange.MONTHLY
                                : (id == R.id.rb_period_book) ? PeriodRange.BOOK
                                : (id == R.id.rb_period_note) ? PeriodRange.NOTE
                                : PeriodRange.WEEKLY;
                        StatsStore.setCardPeriod(SettingsActivity.this, m);
                        CardA11yService.sync();          // 桌面卡片立刻换帧
                        refreshStatus();
                    }
                };
        rbWeek.setOnCheckedChangeListener(periodListener);
        rbMonth.setOnCheckedChangeListener(periodListener);
        rbBook.setOnCheckedChangeListener(periodListener);
        rbNote.setOnCheckedChangeListener(periodListener);

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String k = etKey.getText().toString().trim();
                if (k.length() == 0) {
                    toast("请先填入 API Key");
                    return;
                }
                // v0.5.3（R05）：换 Key 会统一失效个人数据缓存（统计 / 书架 / 进度 / 章节 /
                // 划线 / 想法 / 抽取状态），否则屏幕上会继续显示上一个账号的数据。
                boolean changed = StatsStore.setKey(SettingsActivity.this, k);
                toast(changed ? "已保存，个人数据缓存已清除" : "已保存");
                finish();
            }
        });

        // ── v0.4.2：粘贴（墨水屏上手打 27 位 Key 太难受，直接读剪贴板）──
        ((Button) findViewById(R.id.btn_paste)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });

        // ── v0.4.2：测试连接（填完立刻验一次，别靠"卡片空白"去猜）──
        ((Button) findViewById(R.id.btn_test)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });

        // ── v0.4.2：怎么用（内置使用说明）──
        ((Button) findViewById(R.id.btn_help)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(SettingsActivity.this, HelpActivity.class));
                } catch (Throwable t) {
                    toast("打不开说明页：" + t);
                }
            }
        });

        ((Button) findViewById(R.id.btn_a11y)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    toast("打不开无障碍设置：" + t);
                }
            }
        });

        // ── v0.6.0：卡片显示状态的出口 ──
        // 「翻离第 1 页就收起」靠桌面翻页事件判定，事件投递不是 100% 可靠。
        // 万一漏投、卡片停在隐藏状态，用户在这里一键回到"按当前前台重算"。
        ((Button) findViewById(R.id.btn_reset_card)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!CardA11yService.isConnected()) {
                    toast("卡片服务没在运行 —— 先打开上面的无障碍开关");
                    return;
                }
                CardA11yService.resetPageGate();
                toast("已重新同步 —— 回桌面看一眼");
            }
        });

        // ── v0.5.0：版本与更新 ──
        btnUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onUpdateButton();
            }
        });

        // ── TASK-020：更新通道（正式版 / Beta）──
        // 两个并排选项（RadioGroup 自带选中态高亮）+ 一条常显说明行。🔴 无弹窗（墨水屏约定）。
        // 先回填（不触发回调）再挂监听。换通道 ⇒ 重置更新区状态（缓存里的远端信息属于旧通道）。
        String chNow = CardPrefs.getUpdateChannel(this);
        rbChannelStable.setChecked(!CardPrefs.CHANNEL_BETA.equals(chNow));
        rbChannelBeta.setChecked(CardPrefs.CHANNEL_BETA.equals(chNow));
        refreshChannelTip();
        CompoundButton.OnCheckedChangeListener channelL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;            // 只管"被选中的那个"
                String v = (b.getId() == R.id.rb_channel_beta)
                        ? CardPrefs.CHANNEL_BETA : CardPrefs.CHANNEL_STABLE;
                CardPrefs.setUpdateChannel(SettingsActivity.this, v);
                // 归零更新区：uInfo 属于旧通道，别让它串台；按钮回到「检查更新」
                uState = U_IDLE;
                uInfo = null;
                btnUpdate.setText(getString(R.string.btn_check_update));
                tvUpdateStatus.setText("");
                refreshChannelTip();
                refreshUpdateUi();
            }
        };
        rbChannelStable.setOnCheckedChangeListener(channelL);
        rbChannelBeta.setOnCheckedChangeListener(channelL);

        // ── TASK-030：云端备用更新源（默认开）──
        // 只决定"GitHub 两源都失败时是否再试云端源"，与通道选择无关；切换后重置更新区状态。
        cbCloudFallback.setChecked(CardPrefs.isCloudFallbackEnabled(this));
        cbCloudFallback.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                CardPrefs.setCloudFallbackEnabled(SettingsActivity.this, checked);
                uState = U_IDLE;
                uInfo = null;
                btnUpdate.setText(getString(R.string.btn_check_update));
                tvUpdateStatus.setText("");
            }
        });

        // ── V1.0 Beta（TASK-018）：实验室 · 遥控翻页 ──
        //
        // 角色三选一：写 remote_role → 重算本页显隐（说明 / 会话控件 / 卡片分区）。
        // 🔴 不调 CardA11yService.sync()：遥控与卡片无关；卡片分区显隐只由本页决定。
        int roleNow = CardPrefs.getRemoteRole(this);
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
                CardPrefs.setRemoteRole(SettingsActivity.this, role);
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
                    RemoteLinkManager.get().startSession(SettingsActivity.this);
                } catch (Throwable t) {
                    if (tvRemoteStatus != null) {
                        tvRemoteStatus.setText(getString(R.string.lab_status_prefix) + "启动失败：" + t);
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
        etRemoteIdle.setText(String.valueOf(CardPrefs.getRemoteIdleTimeout(this)));
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
                if (v != CardPrefs.getRemoteIdleTimeout(SettingsActivity.this)) {
                    CardPrefs.setRemoteIdleTimeout(SettingsActivity.this, v);
                }
            }
        });
        etRemoteIdle.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {                          // 失焦回填成合法值
                    etRemoteIdle.setText(
                            String.valueOf(CardPrefs.getRemoteIdleTimeout(SettingsActivity.this)));
                }
            }
        });

        // 去系统无障碍设置（角色切换后需重开对应服务 —— A8 文案硬约束）
        ((Button) findViewById(R.id.btn_lab_a11y)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    toast("打不开无障碍设置：" + t);
                }
            }
        });

        // ── 🆕 TASK-029：手机端「晃动翻页」总开关 + 两个反转开关（仅 role=手机 显示）──
        //
        // 落盘在 CardPrefs（boolean，默认全 false）⇒ 老用户升级后**零差异**（验收 A1）。
        // 每次改动：写偏好 → 刷「当前映射」自证行 → 通知捕获层重算门控
        //（总开关关掉要**立即停采样**，验收 A13；这就是设置页直接调 ShakeDetector.sync 的原因）。
        CompoundButton.OnCheckedChangeListener shakeL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mShakeUiSyncing) return;      // 防回环：refreshShakeUi 回填时不落盘
                int id = b.getId();
                if (id == R.id.cb_shake_enabled) {
                    CardPrefs.setShakeEnabled(SettingsActivity.this, checked);
                } else if (id == R.id.cb_shake_lr_rev) {
                    CardPrefs.setShakeLrRev(SettingsActivity.this, checked);
                } else if (id == R.id.cb_shake_axis_lr) {
                    // 🆕 TASK-042：禁止双不勾 —— 若这次取消会同时关掉两组，则弹回。
                    if (!checked && !CardPrefs.isShakeAxisUdEnabled(SettingsActivity.this)) {
                        mShakeUiSyncing = true;
                        try { cbShakeAxisLr.setChecked(true); } finally { mShakeUiSyncing = false; }
                        toast(getString(R.string.lab_shake_axis_need_one));
                        return;
                    }
                    CardPrefs.setShakeAxisLrEnabled(SettingsActivity.this, checked);
                } else if (id == R.id.cb_shake_axis_ud) {
                    if (!checked && !CardPrefs.isShakeAxisLrEnabled(SettingsActivity.this)) {
                        mShakeUiSyncing = true;
                        try { cbShakeAxisUd.setChecked(true); } finally { mShakeUiSyncing = false; }
                        toast(getString(R.string.lab_shake_axis_need_one));
                        return;
                    }
                    CardPrefs.setShakeAxisUdEnabled(SettingsActivity.this, checked);
                } else {
                    CardPrefs.setShakeUdRev(SettingsActivity.this, checked);
                }
                refreshShakeUi();
                ShakeDetector.sync(SettingsActivity.this);   // G2 即时生效
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
                CardPrefs.setShakeSens(SettingsActivity.this, sens);
                ShakeDetector.reload(SettingsActivity.this);   // 档位变了 ⇒ 重建采样
            }
        });

        // ── 🆕 TASK-033：蓝牙控制（HID 外设通道）──
        // 启用开关 ⇒ 写偏好 + 启/停 HidKeepAliveService（前台服务注册 HID、保活）。
        // 🔴 开启时顺带结束 TCP 会话：两条通道**不并存**，避免串扰（验收 A6）。
        cbBtEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mBtUiSyncing) return;      // 防回环：refreshBtUi 回填时不落盘
                CardPrefs.setBtControlEnabled(SettingsActivity.this, checked);
                if (checked) {
                    // 与热点通道互斥：开蓝牙控制前先结束 TCP 会话
                    try {
                        if (RemoteLinkManager.get().getState() != RemoteLinkManager.STATE_IDLE) {
                            RemoteLinkManager.get().stopSession();
                        }
                    } catch (Throwable ignored) {
                    }
                    HidKeepAliveService.start(SettingsActivity.this);
                } else {
                    HidKeepAliveService.stop(SettingsActivity.this);
                }
                refreshBtUi();
                ShakeDetector.sync(SettingsActivity.this);   // 通道就绪态变化 ⇒ 重算晃动门控
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

        // ── 🆕 TASK-031：本机角色切换（初始化页 · ⓪ 本机角色）──
        // 一键切 install_role ⇒ 入口与"实验室可见子标签"随之变化；提示重开 App 生效。
        // 🔴 install_role（App 形态）与 remote_role（TCP 传输角色）**正交**，此处只动前者。
        rgInstallRole = (RadioGroup) findViewById(R.id.rg_install_role);
        rgInstallRole.check(CardPrefs.getInstallRole(this) == CardPrefs.INSTALL_ROLE_PHONE
                ? R.id.rb_inst_phone : R.id.rb_inst_reader);
        rgInstallRole.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                int role = (checkedId == R.id.rb_inst_phone)
                        ? CardPrefs.INSTALL_ROLE_PHONE : CardPrefs.INSTALL_ROLE_READER;
                if (role == CardPrefs.getInstallRole(SettingsActivity.this)) return;   // 无变化不处理
                CardPrefs.setInstallRole(SettingsActivity.this, role);
                bindLabTabs();        // 实验室可见子标签随之变化
                refreshBtUi();        // 🆕 TASK-033：蓝牙控制页按新角色重刷（手机端控件块 ↔ 墨水屏端说明）
                applyDarkTheme();     // 🆕 TASK-041：角色变化影响深色适用范围（phone 才可能深色）
                Toast.makeText(SettingsActivity.this, R.string.install_role_changed,
                        Toast.LENGTH_LONG).show();
            }
        });

        // 首次进页即按角色定一次显隐（phone 角色要立刻隐藏「桌面卡片」分区）
        refreshRoleUi();

        // 🆕 TASK-041：进页按偏好着色（仅 phone + phone_dark_mode 生效；否则整段不动 ⇒ 零差异）
        applyDarkTheme();

        // ── 🆕 TASK-031：从主入口以「手机端」路由进来时，直接定位到「实验室」页 ──
        // 🔴 SegTabView.setSelected 同值早退、不回调 listener ⇒ 必须手动走一次 showTopPage()。
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_LAB, false)) {
            seg.setSelected(2);
            showTopPage(2);
        }
    }

    // ────────────────────── 🆕 TASK-031：安装角色（App 形态）──────────────────────

    /**
     * 顶部三段（初始化 / 自定义 / 实验室）切换 —— 切可见性 + 回到页顶 + 进实验室页时刷新角色显隐。
     *
     * <p>抽成方法（TASK-031）：既供 {@link SegTabView} 的 listener 用，也供「手机端」入口
     * 程序化定位到实验室页用（`setSelected` 不会回调 listener）。
     */
    private void showTopPage(int index) {
        findViewById(R.id.page_init).setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.page_custom).setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.page_lab).setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        // 切页回到顶部：各页高度不同，留着旧滚动位置会看着像"卡住了"
        ((ScrollView) findViewById(R.id.sv_settings)).scrollTo(0, 0);
        // 进实验室页时刷一次角色/状态显示 —— 不轮询、不常驻（会话状态由 Listener 推）
        if (index == 2) refreshRoleUi();
    }

    /**
     * 🆕 TASK-041：按 {@code install_role=phone + phone_dark_mode} **运行时递归染色**整个设置页。
     *
     * <p>为什么不用 {@code setTheme()} / {@code values-night}：本页是**原生 XML**，颜色是**硬编码
     * 十六进制**（如 {@code #FF000000}），主题属性无法覆盖硬编码值。故采用**递归遍历视图树 +
     * 颜色映射**：把已知的"亮色键"替换为对应深色值，其余颜色不动。
     *
     * <p>映射（亮 → 深，来源 docs/09 §2.1）：
     * <ul>
     *   <li>{@code #FF000000}（正文/标题/控件文字 · buttonTint） → {@code #E9E4D8}</li>
     *   <li>{@code #FF3C3C3C}（说明文字） → {@code #B3ADA1}</li>
     *   <li>{@code #FF6A6A6A}（次要说明） → {@code #B3ADA1}</li>
     *   <li>{@code #FF9A9A9A}（占位/灰字） → {@code #7E8894}</li>
     *   <li>{@code #FFFFFFFF}（页底） → {@code #070A0F}；仅对"容器背景"生效</li>
     *   <li>{@code #FFD8D8D8} / {@code #FFE0E0E0}（分隔线） → {@code #1CE9E4D8}</li>
     * </ul>
     *
     * <p>🔴 **只对 phone + 深色启用**；reader 分支**一行不改**（验收 A6）。深色关闭时本方法直接返回，
     * 不改动任何颜色 —— 保证亮色下与改造前**逐像素一致**。
     */
    private void applyDarkTheme() {
        boolean dark = CardPrefs.getInstallRole(this) == CardPrefs.INSTALL_ROLE_PHONE
                && CardPrefs.isPhoneDarkMode(this);
        // 顶部两枚页签控件（自绘）——总是显式同步（dark=false 时回到亮色，避免从深色切回残留）
        View segTop = findViewById(R.id.seg);
        View segLab = findViewById(R.id.seg_lab);
        if (segTop instanceof SegTabView) ((SegTabView) segTop).setDark(dark);
        if (segLab instanceof SegTabView) ((SegTabView) segLab).setDark(dark);
        if (!dark) return;            // 🔴 亮色：到此为止，不碰任何原生控件 ⇒ 零差异

        View root = findViewById(R.id.sv_settings);
        // 顶层 LinearLayout（settings_layout 根）也要染 —— 它是 ScrollView 的父
        if (root != null && root.getParent() instanceof View) {
            recolorTree((View) root.getParent(), true);
        }
        if (root != null) recolorTree(root, false);
    }

    /** 亮→深 颜色映射；返回 {@code -1} 表示"无需替换"。 */
    private static int darkOf(int color) {
        switch (color) {
            case 0xFF000000: return InkTheme.DARK_INK;
            case 0xFF3C3C3C: return InkTheme.DARK_INK2;
            case 0xFF6A6A6A: return InkTheme.DARK_INK2;
            case 0xFF9A9A9A: return InkTheme.DARK_INK3;
            case 0xFFFFFFFF: return InkTheme.DARK_PAPER;
            case 0xFFD8D8D8: return InkTheme.DARK_LINE;
            case 0xFFE0E0E0: return InkTheme.DARK_LINE;
            default: return -1;
        }
    }

    /**
     * 递归染色：对 TextView 系控件改 textColor / buttonTint；对容器改 background。
     *
     * @param isContainerChain true = 这条子树里的 {@code #FFFFFFFF} 视为"页面/容器背景"（染成墨底）
     */
    private void recolorTree(View v, boolean isContainerChain) {
        if (v == null) return;
        try {
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                int next = darkOf(tv.getCurrentTextColor());
                if (next != -1) tv.setTextColor(next);
                // 单选/复选框的按钮着色（XML 用 buttonTint；仅 CompoundButton 有该 API）
                try {
                    if (tv instanceof android.widget.CompoundButton) {
                        android.content.res.ColorStateList tint =
                                ((android.widget.CompoundButton) tv).getButtonTintList();
                        if (tint != null && !tint.isStateful()) {
                            int cur = tint.getDefaultColor();
                            int tc = darkOf(cur);
                            if (tc != -1) {
                                ((android.widget.CompoundButton) tv)
                                        .setButtonTintList(android.content.res.ColorStateList.valueOf(tc));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // 背景：递归处理纯色 / StateListDrawable / LayerDrawable，命中映射才替换
            if (v.getBackground() != null) darkenDrawable(v.getBackground());

            // XML 里 many 按钮用 @color/btn_ink_text（ColorStateList：按下白 / 常态黑）作文字色。
            // getCurrentTextColor() 拿到的是解析后的"黑"，可以映射；但按下态仍是白 —— 一并把
            // 该 ColorStateList 的两个档都换掉，保证按下也不刺眼。
            if (v instanceof TextView) {
                android.content.res.ColorStateList tsl = tvTextColors((TextView) v);
                if (tsl != null && tsl.isStateful()) darkenTextStateList((TextView) v, tsl);
            }

            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) recolorTree(g.getChildAt(i), false);
            }
        } catch (Throwable ignored) {
            // 染色是纯视觉增强：任何单个控件失败都不应中断整页
        }
    }

    /** 取 TextView 当前 textColor；Android 无公开 getter 时返回 null。 */
    private static android.content.res.ColorStateList tvTextColors(TextView tv) {
        try {
            return tv.getTextColors();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把 {@code ColorStateList} 文字色里的"亮色档"换成暗色档。
     *
     * <p>规则：常态档（default）命中映射即替换；"按下态白"也顺势换成暗底亮字（用 DARK_PAPER），
     * 避免深色页面上按下出现一块白字。
     */
    private static void darkenTextStateList(TextView tv, android.content.res.ColorStateList src) {
        try {
            int pressed = src.getColorForState(new int[]{android.R.attr.state_pressed}, 0);
            int normal = src.getColorForState(new int[]{}, 0);
            int np = darkOf(pressed);
            int nn = darkOf(normal);
            if (np == -1 && nn == -1) return;
            // 按下态白 → 暗底亮字（用纸白 DARK_INK，视觉上"按下去字变亮"）
            int newPressed = (np != -1)
                    ? (pressed == 0xFFFFFFFF ? InkTheme.DARK_INK : np)
                    : (pressed == 0xFFFFFFFF ? InkTheme.DARK_INK : pressed);
            int newNormal = (nn != -1) ? nn : normal;
            tv.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_pressed}, {}},
                    new int[]{newPressed, newNormal}));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 递归把 Drawable 树里的"亮色"换成暗色：支持 ColorDrawable / StateListDrawable / LayerDrawable / GradientDrawable。
     *
     * <p>设置页按钮底是 {@code @drawable/btn_ink}（selector：常态白底黑边、按下黑底）—— 只有逐个 item 拆开
     * 才能把"白底"换掉；{@link GradientDrawable} 还能改描边色。
     */
    private static void darkenDrawable(android.graphics.drawable.Drawable d) {
        if (d == null) return;
        try {
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                int cur = ((android.graphics.drawable.ColorDrawable) d).getColor();
                int bc = darkOf(cur);
                if (bc != -1) ((android.graphics.drawable.ColorDrawable) d).setColor(bc);
            } else if (d instanceof android.graphics.drawable.GradientDrawable) {
                // 裸 GradientDrawable（非 selector 包装）：按其"填充色是否亮"处理不可靠，
                // 统一按描边按钮形态处理（常态墨底界线）。
                applyInkButtonShape((android.graphics.drawable.GradientDrawable) d, null);
            } else if (d instanceof android.graphics.drawable.StateListDrawable) {
                android.graphics.drawable.StateListDrawable s = (android.graphics.drawable.StateListDrawable) d;
                int n = s.getStateCount();
                for (int i = 0; i < n; i++) {
                    android.graphics.drawable.Drawable item = s.getStateDrawable(i);
                    if (item instanceof android.graphics.drawable.GradientDrawable) {
                        applyInkButtonShape((android.graphics.drawable.GradientDrawable) item, s.getStateSet(i));
                    } else {
                        darkenDrawable(item);
                    }
                }
            } else if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable l = (android.graphics.drawable.LayerDrawable) d;
                for (int i = 0; i < l.getNumberOfLayers(); i++) darkenDrawable(l.getDrawable(i));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * btn_ink 这类"描边按钮"：亮色 = 白底 + 黑边；深色 = 墨-2 底 + 界线边（按下 = 青玉底 + 青玉边）。
     *
     * @param stateSet 该 item 的 state（含 {@code state_pressed} 视作按下态）
     */
    private static void applyInkButtonShape(android.graphics.drawable.GradientDrawable g, int[] stateSet) {
        try {
            boolean pressed = false;
            if (stateSet != null) {
                for (int st : stateSet) if (st == android.R.attr.state_pressed) pressed = true;
            }
            int fill = pressed ? InkTheme.DARK_BAMBOO : InkTheme.DARK_PAPER2;
            int edge = pressed ? InkTheme.DARK_BAMBOO : InkTheme.DARK_LINE;
            g.setColor(fill);
            g.setStroke(Math.max(1, g.getIntrinsicHeight() > 0 ? 1 : 1), edge);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 🆕 TASK-031：按 {@code install_role} **动态装配**实验室子标签（onCreate 与角色切换时调用）。
     *
     * <p>把原来写死的 3 段换成**可扩展表** {@code {labelRes, page, phoneRelevant}}：
     * <ul>
     *   <li><b>阅读器端</b> ⇒ 全部装配（改造前 3 项保持原位序，另加「蓝牙控制」只读页）；</li>
     *   <li><b>手机端</b> ⇒ 只装配 {@code phoneRelevant} 的项（当前 = 热点翻页 + 蓝牙控制）。</li>
     * </ul>
     * 🔴 子标签文案用资源 id（不写死字符串）：TASK-033 改 {@code lab_tab_remote} 字面量后自动生效。
     */
    private void bindLabTabs() {
        final boolean phone = CardPrefs.getInstallRole(this) == CardPrefs.INSTALL_ROLE_PHONE;

        // ── 可扩展表：新增手机端子标签，在下面追加一行并把 phoneRelevant 置 true 即可 ──
        final int[] labelRes = {
                R.string.lab_tab_remote,       // 热点翻页（TASK-033 改名）
                R.string.lab_tab_bt,           // 蓝牙控制（TASK-033 新增）
                R.string.lab_tab_lockscreen,   // 锁屏密码
                R.string.lab_tab_power };      // 续航优化
        final View[] page = {
                findViewById(R.id.page_lab_remote),
                findViewById(R.id.page_lab_bt),
                findViewById(R.id.page_lab_lockscreen),
                findViewById(R.id.page_lab_power) };
        // 蓝牙控制：手机端（发键）与阅读器端（只读说明）都可见 ⇒ 两侧都装配
        final boolean[] phoneRelevant = { true, true, false, false };

        // 按角色过滤出"保留的下标"
        final int[] keep = new int[labelRes.length];
        int n = 0;
        for (int i = 0; i < labelRes.length; i++) {
            if (!phone || phoneRelevant[i]) keep[n++] = i;
        }

        final String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = getString(labelRes[keep[i]]);

        final SegTabView segLab = (SegTabView) findViewById(R.id.seg_lab);
        segLab.setLabels(labels);
        final int count = n;
        final ScrollView sv = (ScrollView) findViewById(R.id.sv_settings);
        segLab.setListener(new SegTabView.Listener() {
            @Override
            public void onSegSelected(int index) {
                for (int i = 0; i < count; i++) {
                    page[keep[i]].setVisibility(i == index ? View.VISIBLE : View.GONE);
                }
                sv.scrollTo(0, 0);
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

    // ══════════════════════ 🆕 TASK-033 蓝牙控制（HID 外设通道）══════════════════════

    /**
     * 刷新「蓝牙控制」子页（onCreate / onResume / 开关变更 / HID 状态变化时调用）。
     *
     * <p>按 {@code install_role} 分两种形态：
     * <ul>
     *   <li><b>手机端</b> ⇒ 显示控件块；开关态回填偏好；SDK&lt;28 时开关置灰并显红字；状态行按
     *       「未运行 / 已启用待连 / 已连接」三态渲染（**读服务真值，不读偏好**）；仅「已连接」
     *       时才露出测试翻页行。</li>
     *   <li><b>阅读器端</b> ⇒ 隐藏控件块，只显示「本机=墨水屏端」说明（不启动任何后台，验收 A10）。</li>
     * </ul>
     */
    private void refreshBtUi() {
        if (tvBtIntro == null) return;

        boolean phone = CardPrefs.getInstallRole(this) == CardPrefs.INSTALL_ROLE_PHONE;
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
        cbBtEnabled.setChecked(CardPrefs.isBtControlEnabled(this));
        mBtUiSyncing = false;

        // 状态判定：以「服务是否运行 + HID 主机是否连接」为准（真值，不读偏好）
        HidLink hid = HidKeepAliveService.link();
        boolean running = HidKeepAliveService.isRunning() && hid != null;
        boolean connected = running && hid.isHostConnected();
        // 🆕 TASK-034（A5 缺口修复）：SDK 够但 ROM 裁掉了 HID profile ⇒ 显式提示「本机不支持」，
        //    否则开关打开后会永远停在「等待连接」，是假象。
        boolean noProfile = supported && running && hid != null && hid.profileUnavailable();
        boolean usable = supported && !noProfile;

        // 不可用（SDK<28 或 ROM 无 HID profile）：红字提示 + 开关置灰
        tvBtUnsupported.setText(noProfile ? R.string.lab_bt_noprofile : R.string.lab_bt_unsupported);
        tvBtUnsupported.setVisibility(usable ? View.GONE : View.VISIBLE);
        cbBtEnabled.setEnabled(usable);

        String status;
        if (!running || noProfile) {
            status = getString(R.string.lab_bt_status_off);          // 未启用（或本机不可用）
        } else if (!connected) {
            // 🆕 TASK-034：连过又断 ⇒「已断开」；从未连过 ⇒「等待连接」——两者引导语不同
            status = getString(hid.everConnected()
                    ? R.string.lab_bt_status_lost : R.string.lab_bt_status_registered);
        } else {
            // 🆕 审查修复（NG-4）：走 HidLink.safeName 兜底 —— host.getName()/getAddress() 在
            //   BLUETOOTH_CONNECT 未授予/被撤时会抛 SecurityException（此处无 try/catch 会带走进程）。
            String name = HidLink.safeName(hid.host());
            status = getString(R.string.lab_bt_status_connected, name);
        }
        tvBtStatus.setText(getString(R.string.lab_bt_status_prefix) + status);

        // 🆕 TASK-034 / TASK-035：两类「未连接」引导**互斥**（按"是否连过"分流，不混）——
        //   ① 从未连过（hid.everConnected()==false）⇒ **首次配对引导**（三步 + 「让本机可被发现」按钮）
        //   ② 连过又断（everConnected()==true）  ⇒ **断链恢复引导**（去墨水屏「之前连接的设备」点本机名）
        //   时长/文案与真机行为对齐：见 ADR-012 决定 3、验证记录/129。
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
            Toast.makeText(this, R.string.lab_bt_reconnect_tip, Toast.LENGTH_LONG).show();
            return;
        }
        boolean ok = next ? hid.pageNext() : hid.pagePrev();
        if (!ok) {
            Toast.makeText(this, R.string.lab_bt_reconnect_tip, Toast.LENGTH_LONG).show();
        }
    }

    /** 🆕 TASK-035：ACTION_REQUEST_DISCOVERABLE 的请求码。 */
    private static final int REQ_BT_DISCOVERABLE = 3501;

    /**
     * 🆕 TASK-035：让本机「可被发现」，方便墨水屏把它扫出来配对。
     *
     * <p>走系统标准弹窗 {@code ACTION_REQUEST_DISCOVERABLE}（**用户必须自己点「允许」**，第三方 App
     * 无法静默开启）。回执 {@code resultCode} = 系统授予的可被发现秒数；{@code RESULT_CANCELED}(=0)
     * = 用户拒绝。⛔ 本方法**只让本机可被看见，不做任何自动配对**（Android 不允许第三方静默配对）。
     */
    private void requestDiscoverable() {
        try {
            Intent it = new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            // 300s：够用户切到墨水屏完成扫描/点选；系统可能自行缩短，以回执为准
            it.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(it, REQ_BT_DISCOVERABLE);
        } catch (Throwable t) {
            // 极少数 ROM 无此 Activity：降级提示用户去系统蓝牙设置手动开启
            Toast.makeText(this, R.string.lab_bt_discoverable_fail, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BT_DISCOVERABLE) {
            if (resultCode > 0) {
                Toast.makeText(this, getString(R.string.lab_bt_discoverable_ok, resultCode), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.lab_bt_discoverable_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 和主页一样：告诉服务"用户在自家界面"，桌面卡片要让位
        CardA11yService.noteOwnUiForeground(true);
        refreshRoleUi();          // 按角色重算显隐（phone 角色隐藏「桌面卡片」分区）
        applyDarkTheme();         // 🆕 TASK-041：深色偏好可能在遥控台改过，回前台重染
        refreshStatus();
        refreshChannelTip();       // TASK-020：说明行按当前通道刷新（无弹窗）
        refreshUpdateUi();
        refreshLockUi();           // TASK-022：锁屏子页状态（可能在别处改过偏好）
        refreshPowerUi();          // TASK-023：续航子页（易失项以设备真值为准复核）
        // 🆕 TASK-033：蓝牙控制子页（订阅 HID 状态变化；先单刷一次拿当前真值）
        HidKeepAliveService.addListener(mHidStateListener);
        refreshBtUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CardA11yService.noteOwnUiForeground(false);
        // 页面不在前台就不再收会话状态（避免持引用）；回来时 onResume 会重新注册
        // 🔴 TASK-029 §2.2：改成 removeStateListener —— 只摘自己这一个，不影响遥控服务的监听
        RemoteLinkManager.get().removeStateListener(mStateListener);
        // 🆕 TASK-033：摘 HID 状态监听（离页不持引用；回来时 onResume 重新注册）
        HidKeepAliveService.removeListener(mHidStateListener);
    }

    // ══════════════════════ 🆕 TASK-022 锁屏密码（应用级软锁） ══════════════════════

    /** 申请「读取存储」权限的请求码（TASK-022-R1：载入背景图用）。 */
    private static final int REQ_PERM_BG = 3302;

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
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM_BG);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.lock_bg_perm_needed, Toast.LENGTH_LONG).show();
            applyLockBg(p);          // 授权流程起不来也先试一次（私有目录兜底不依赖权限）
        }
    }

    private boolean hasStorageReadPermission() {
        try {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_PERM_BG) return;
        boolean ok = results != null && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok) {
            Toast.makeText(this, R.string.lock_bg_perm_needed, Toast.LENGTH_LONG).show();
        }
        String p = etLockBgPath.getText().toString().trim();
        if (p.length() == 0) p = LockPrefs.DEFAULT_BG_REL;
        applyLockBg(p);              // 无论给不给权限都试一次：私有目录兜底不依赖权限
    }

    /**
     * 真正落盘：写路径 → 解析文件 → 如实反馈（找到 / 没找到）。
     *
     * <p>🔴 <b>「未入库文件」的真实边界（2026-09-29 S4 实测，如实登记）</b>：
     * 路径没命中时先别急着报错 —— 根因是 Android 11 的 FUSE 对<b>还没被媒体库索引</b>的文件
     * 一律判"不存在"（日志原话 {@code MediaProvider: Couldn't find file: …/背景.jpg}）。
     * 正常途径放进来的图（{@code adb push} / 复制）本机会自动入库，属个别情况。
     * 这里仍尝试一次 {@link MediaScannerConnection#scanFile}（<b>对其他 ROM 有意义</b>），
     * 但 <b>S4 上实测无效</b>：MediaProvider 的 ModernMediaScanner 自己也 stat 不到该文件
     * （{@code NoSuchFileException}）。S4 上可用的替代是 shell 侧那条
     * {@code am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://…}。
     */
    private void applyLockBg(String path) {
        LockPrefs.setBgPath(this, path);
        if (LockPrefs.resolveBgFile(this) != null) {
            Toast.makeText(this, R.string.lock_bg_loaded, Toast.LENGTH_SHORT).show();
            refreshLockUi();
            return;
        }
        final String abs = LockPrefs.toAbsolute(path).getAbsolutePath();
        try {
            MediaScannerConnection.scanFile(this, new String[]{abs}, null,
                    new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String p, Uri u) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    File f = LockPrefs.resolveBgFile(SettingsActivity.this);
                                    Toast.makeText(SettingsActivity.this, getString(f == null
                                            ? R.string.lock_bg_not_found : R.string.lock_bg_loaded),
                                            Toast.LENGTH_SHORT).show();
                                    refreshLockUi();
                                }
                            });
                        }
                    });
        } catch (Throwable t) {
            Toast.makeText(this, R.string.lock_bg_not_found, Toast.LENGTH_SHORT).show();
        }
        refreshLockUi();
    }

    /** 刷新锁屏子页的三处状态文案（进页 / 改密 / 选图 / 开关后调用）。 */
    private void refreshLockUi() {
        if (cbLockEnabled != null) {
            lockUiSyncing = true;                       // 同步勾选态时不触发监听
            cbLockEnabled.setChecked(LockPrefs.isEnabled(this));
            lockUiSyncing = false;
        }
        if (tvLockStatus != null) {
            tvLockStatus.setText(getString(LockPrefs.hasPin(this)
                    ? R.string.lock_status_set : R.string.lock_status_unset));
        }
        if (tvLockBgStatus != null) {
            // 如实显示"当前实际生效"的那张图（含兜底命中）；没有则显示默认底
            File f = LockPrefs.resolveBgFile(this);
            tvLockBgStatus.setText(f == null
                    ? getString(R.string.lock_bg_default)
                    : getString(R.string.lock_bg_custom_fmt, f.getAbsolutePath()));
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
        if (!PowerSettingsManager.hasPermission(this)) {
            toast(getString(R.string.power_perm_deny));
            refreshPowerUi();                                      // 回滚勾选显示
            return;
        }
        boolean ok = PowerSettingsManager.setOn(this, item, on);
        if (!ok) toast(getString(R.string.power_write_failed));
        refreshPowerUi();
    }

    /**
     * 刷新续航子页：授权状态行 + 命令文本 + 3 个勾选项。
     * 🔴 勾选态一律取 {@link PowerSettingsManager#isOn}（设备真值），不读偏好记录 ——
     * 系统可能改回某项，只有读真值才不会「显示已开、实际已关」。
     */
    private void refreshPowerUi() {
        boolean perm = PowerSettingsManager.hasPermission(this);
        if (tvPowerPerm != null) {
            tvPowerPerm.setText(perm ? R.string.power_perm_ok : R.string.power_perm_deny);
        }
        if (tvPowerCmd != null) {
            tvPowerCmd.setText(PowerSettingsManager.grantCommand(this));
        }
        syncPowerCb(cbPowerWifiNotif, PowerSettingsManager.ITEM_WIFI_NET_NOTIF);
        syncPowerCb(cbPowerAnim, PowerSettingsManager.ITEM_ANIM);
        syncPowerCb(cbPowerScreensaver, PowerSettingsManager.ITEM_SCREENSAVER);
    }

    private void syncPowerCb(CheckBox cb, PowerSettingsManager.Item item) {
        if (cb == null) return;
        powerUiSyncing = true;
        cb.setChecked(PowerSettingsManager.isOn(this, item));
        powerUiSyncing = false;
    }

    /** 把一次性授权命令复制到剪贴板（ADB 自助 · 档 1）。 */
    private void copyGrantCmd() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("adb", PowerSettingsManager.grantCommand(this)));
            toast(getString(R.string.power_copied));
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }

    /** C1：跳到系统「电池优化」页（把本应用加入白名单，抵消省电模式的后台限制）。 */
    private void openBatteryOpt() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            toast(getString(R.string.power_battery_opt_failed));
        }
    }

    /**
     * 「17 号 · 20.4px」—— 本记字号（卡片）滑杆当前值的显示文案（v0.9，TASK-016）。
     *
     * 为什么要把 px 也写出来：「号」是本项目的内部单位，用户没有这个概念；
     * 只写"17 号"他无从判断大小。px 用 {@link com.inkread.weekread.feature.WeekCardView#UNIT_RATIO}
     * 换算（"号 → px"的唯一真源，别在这里另抄一份 0.0015）。
     */
    private String noteCardSizeLabel(int num) {
        float px = num * getResources().getDisplayMetrics().heightPixels
                * com.inkread.weekread.feature.WeekCardView.UNIT_RATIO;
        return num + " 号 · " + String.format(Locale.CHINA, "%.1f", px) + "px";
    }

    /**
     * 自定义目标（小时）输入框的监听器（v0.9，TASK-013）。
     *
     * 口径（方案 §1/§4）：输入小时、≤2 位小数（"1.25"）；校验 0 &lt; h ≤ 1000；
     * 落盘 = round(h×60) 整数分钟；解析不出（空串 / 非法 / 越界）→ 存 0 =
     * 「未设置」→ targetSec 返回 0 → 成就行不画（不会出现"0 目标恒达成"）。
     *
     * 🔴 只在解析出的分钟数**与现存值不同**时才写 prefs + sync —— 输入是逐字符触发的，
     * 不去抖的话每敲一个字卡片就重绘一遍（墨水屏上不可接受）。
     */
    private TextWatcher hoursWatcher(final boolean week) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int ct, int af) { }
            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                String t = (s == null) ? "" : s.toString().trim();
                int min = 0;
                if (t.length() > 0) {
                    try {
                        double h = Double.parseDouble(t);
                        if (h > 0 && h <= 1000.0) min = (int) Math.round(h * 60.0);
                    } catch (NumberFormatException e) {
                        min = 0;                // 打字中间态（"1."之类）不算错，按"未设置"
                    }
                }
                int old = week ? AchievementPrefs.getWeekMin(SettingsActivity.this)
                        : AchievementPrefs.getMonthMin(SettingsActivity.this);
                if (min == old) return;
                if (week) AchievementPrefs.setWeekMin(SettingsActivity.this, min);
                else AchievementPrefs.setMonthMin(SettingsActivity.this, min);
                CardA11yService.sync();
            }
        };
    }

    /** 分钟 → 小时显示（≤2 位小数；整数不带小数点）。只在进设置页回填输入框时用 */
    private static String minToHours(int min) {
        if (min <= 0) return "";
        double h = min / 60.0;
        if (h == Math.floor(h)) return String.valueOf((long) h);
        return String.valueOf(Math.round(h * 100.0) / 100.0);
    }

    /** 把卡片的真实状态写出来，别让用户以为"开关打开就一定看得见" */
    private void refreshStatus() {
        if (!CardPrefs.isEnabled(this)) {
            tvStatus.setText("卡片已关闭。打开上面的开关即可在桌面显示。");
            return;
        }
        if (CardA11yService.isConnected()) {
            tvStatus.setText("运行中 ✓　只在桌面显示，切到别的应用自动隐藏。");
        } else if (CardA11yService.isEnabledInSystem(this)) {
            tvStatus.setText("已授权，等待系统拉起…（回桌面看一眼，没有就重开一次开关）");
        } else {
            // v0.4.3：状态行在「桌面卡片」块末尾，无障碍按钮在它上方，故说"上面"
            tvStatus.setText("未开启 —— 点上面的「打开系统无障碍设置」，在「已下载的服务」里打开「微读墨记」。");
        }
    }

    // ────────────────────── V1.0 Beta（TASK-018）：实验室 · 遥控翻页 ──────────────────────

    /**
     * 按当前角色刷新本页显隐与文案（onCreate / onResume / 角色切换后调用）。
     *
     * 三类联动：
     *   ① 角色说明文本（off / eink / phone 各一句，只写已验证事实）；
     *   ② 会话控件（role != off 才显示：开始·结束 / 状态 / 空闲超时 / 互斥提示）；
     *   ③ 🔴 phone 角色整块隐藏两处「桌面卡片」分区（A7；卡片在该机没有使用场景）。
     *
     * 顺带注册会话状态监听 —— 主线程直推，页面不需要轮询。
     */
    private void refreshRoleUi() {
        RemoteRole role = RemoteRole.from(this);
        remotePhone = (role == RemoteRole.PHONE);

        if (tvRoleHint != null) {
            tvRoleHint.setText(role == RemoteRole.EINK ? getString(R.string.lab_role_hint_eink)
                    : role == RemoteRole.PHONE ? getString(R.string.lab_role_hint_phone)
                    : getString(R.string.lab_role_hint_off));
        }
        if (llRemoteSession != null) {
            llRemoteSession.setVisibility(role == RemoteRole.OFF ? View.GONE : View.VISIBLE);
        }
        // 🔴 phone 角色隐藏「桌面卡片」分区（A7）
        int cardVis = (role == RemoteRole.PHONE) ? View.GONE : View.VISIBLE;
        if (sectionCardInit != null) sectionCardInit.setVisibility(cardVis);
        if (sectionCardCustom != null) sectionCardCustom.setVisibility(cardVis);
        // 🆕 TASK-029：晃动翻页块**仅手机角色**可见（A2 —— 墨水屏 / 关闭角色下不可见且零响应）
        if (llShakeBlock != null) {
            llShakeBlock.setVisibility(role == RemoteRole.PHONE ? View.VISIBLE : View.GONE);
        }
        refreshShakeUi();

        // 会话状态：注册监听会立刻回推一次当前状态（页面无需手动刷新）
        // 🔴 TASK-029 §2.2：单槽 setStateListener ⇒ addStateListener。
        //    否则本页监听会与遥控服务（晃动捕获门控）的监听互相顶掉（验收 A18）。
        RemoteLinkManager.get().addStateListener(mStateListener);

        // 🆕 TASK-029：角色可能刚改过（G1）⇒ 重算晃动捕获层的注册/注销。
        //    例：会话已 CONNECTED 时把角色从「墨水屏」改成「手机」，这里要立刻开始采样。
        ShakeDetector.sync(this);
    }

    /**
     * 🆕 TASK-029：刷新「晃动翻页」区块（onCreate / onResume / 角色切换 / 任一开关变更后调用）。
     *
     * <p>① 回填 3 个开关（期间置 {@link #mShakeUiSyncing} 防回环）；
     * ② **总开关关时把两个反转开关置灰**（保留可见，避免布局跳动 —— 方案 §4.2）；
     * ③ 拼出「当前映射」自证行：用户不必靠"开关名 + 记忆"反推映射，**映射永远以屏幕上的字为准**，
     *    也便于上机验收逐字比对（A4–A8）。
     */
    private void refreshShakeUi() {
        if (cbShakeEnabled == null) return;
        boolean on = CardPrefs.isShakeEnabled(this);
        boolean lrRev = CardPrefs.isShakeLrRev(this);
        boolean udRev = CardPrefs.isShakeUdRev(this);
        boolean axLr = CardPrefs.isShakeAxisLrEnabled(this);   // 🆕 TASK-042
        boolean axUd = CardPrefs.isShakeAxisUdEnabled(this);
        int sens = CardPrefs.getShakeSens(this);
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
        // 🆕 灵敏度三档：总开关关时置灰（与两个反转开关同规则；文字色硬编码黑故文字不变灰，同 A13 已知口径）
        rbShakeSensLow.setEnabled(on);
        rbShakeSensMid.setEnabled(on);
        rbShakeSensHigh.setEnabled(on);
        if (tvShakeMap != null) {
            String prev = getString(R.string.lab_shake_page_prev);
            String next = getString(R.string.lab_shake_page_next);
            String lrCol = lrRev ? next : prev;    // 左晃
            String rrCol = lrRev ? prev : next;    // 右晃
            String udCol = udRev ? next : prev;    // 上晃
            String ddCol = udRev ? prev : next;    // 下晃
            // 🆕 TASK-042：未使能的组在映射行里显式标注「不响应」，用户一眼看出为何甩了没反应
            if (!axLr) {
                String off = getString(R.string.lab_shake_map_axis_off, getString(R.string.lab_shake_axis_lr));
                lrCol = off; rrCol = off;
            }
            if (!axUd) {
                String off = getString(R.string.lab_shake_map_axis_off, getString(R.string.lab_shake_axis_ud));
                udCol = off; ddCol = off;
            }
            tvShakeMap.setText(getString(R.string.lab_shake_map_now, lrCol, rrCol, udCol, ddCol));
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
            runOnUiThread(new Runnable() {
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
                        ? getString(R.string.lab_status_connected, peerIp) : "已连接";
                break;
            case RemoteLinkManager.STATE_CLOSED:
                text = (detail != null && detail.length() > 0) ? detail : "已断开";
                break;
            case RemoteLinkManager.STATE_IDLE:
            default:
                text = getString(R.string.lab_status_idle);
                break;
        }
        tvRemoteStatus.setText(getString(R.string.lab_status_prefix) + text);
        refreshSessionButtons(state);
    }

    /** 会话进行中（CONNECTING / CONNECTED）禁用「开始」—— 避免重复起会话。 */
    private void refreshSessionButtons(int state) {
        boolean active = (state == RemoteLinkManager.STATE_CONNECTING
                || state == RemoteLinkManager.STATE_CONNECTED);
        if (btnRemoteStart != null) btnRemoteStart.setEnabled(!active);
        if (btnRemoteStop != null) btnRemoteStop.setEnabled(active);
    }

    // ────────────────────── v0.5.0：版本与更新 ──────────────────────

    /**
     * 更新通道的常显说明行（TASK-020 验收 A4）。
     *
     * 🔴 必须含「两条通道的版本号不可直接比较」的等价表述 —— 否则用户看到
     * 「正式版 0.9.1」与「Beta 1.0.0-beta.1」会以为前者更旧而误判。
     * 用常显行承担知情同意，**不做弹窗**（沿用「墨水屏上弹窗尤其讨厌」约定）。
     */
    private void refreshChannelTip() {
        if (tvChannelTip == null) return;
        boolean beta = CardPrefs.CHANNEL_BETA.equals(CardPrefs.getUpdateChannel(this));
        tvChannelTip.setText(getString(
                beta ? R.string.channel_tip_beta : R.string.channel_tip_stable));
    }

    /**
     * 「已是最新」或「降级窗口」的状态行文案（TASK-020 验收 A5 / ⑤）。
     *
     * 🔴 本机是 Beta 出身（versionName 含 "-beta"）且通道 = 正式版、而正式版 vc ≤ 本机 vc 时，
     *    如实说「无法直接退回」，**不写空头承诺** —— App 拿不到安装成功/失败回调
     *    （`ApkInstaller` 一发 intent 即走），必须**在点安装之前**就把限制讲清。
     */
    private String latestText(UpdateChecker.Info info) {
        boolean stable = !CardPrefs.CHANNEL_BETA.equals(CardPrefs.getUpdateChannel(this));
        boolean localBeta = UpdateChecker.currentVersionName(this).contains("-beta");
        if (stable && localBeta
                && info != null && info.versionName != null && info.versionName.length() > 0) {
            return getString(R.string.upd_downgrade_blocked, info.versionName);
        }
        return getString(stable ? R.string.upd_latest_stable : R.string.upd_latest_beta,
                UpdateChecker.currentVersionName(this));
    }

    /**
     * 进设置页时刷新版本区。
     *
     * 这里是「静默检查」的存在意义：启动时顺手查一次、结果落盘，用户打开设置页立刻就能
     * 看到「有新版本」，全程没有弹窗打断（墨水屏上弹窗尤其讨厌）。
     */
    private void refreshUpdateUi() {
        tvVersion.setText(getString(R.string.upd_current,
                UpdateChecker.currentVersionName(this), UpdateChecker.currentVersionCode(this)));
        if (uState != U_IDLE) {
            return;                     // 有进行中的流程，别覆盖状态文字
        }
        if (UpdateChecker.hasKnownUpdate(this)) {
            String s = getString(R.string.upd_found, UpdateChecker.remoteVersionName(this));
            String notes = UpdateChecker.remoteNotes(this);
            if (notes.length() > 0) {
                s = s + "\n" + notes;
            }
            tvUpdateStatus.setText(s);
            // TASK-004：按钮文字必须跟上状态。
            // 缓存里只有版本号、没有下载地址（所以 uInfo 仍是 null），但点下去发生的事是
            // 「重新取一次清单 → 拿到地址 → 自动接着下载」（onUpdateButton → startCheck(true)
            // → startDownload），也就是「下载并安装」。
            // 按钮若还写着「检查更新」，就与点下去会发生的事对不上了（验证记录/27 §3.3、
            // 验证记录/35 §5 把它列为"仍未修"）。
            btnUpdate.setText(getString(R.string.upd_btn_download,
                    UpdateChecker.remoteVersionName(this)));
        }
    }

    private void onUpdateButton() {
        if (uState == U_CHECKING || uState == U_DOWNLOADING) {
            return;                     // 正在忙，忽略连点
        }
        if (uState == U_HAS) {
            startDownload();
            return;
        }
        startCheck(UpdateChecker.hasKnownUpdate(this));
    }

    /** @param autoDownload 查到新版后直接接着下载（用于「已知有新版」时一键走完） */
    private void startCheck(final boolean autoDownload) {
        uState = U_CHECKING;
        btnUpdate.setText(getString(R.string.upd_checking));
        tvUpdateStatus.setText("");
        UpdateChecker.check(this, true, new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.Info info, String error) {
                if (error != null) {
                    uState = U_IDLE;
                    btnUpdate.setText(getString(R.string.btn_check_update));
                    tvUpdateStatus.setText(getString(R.string.upd_failed, error));
                    return;
                }
                if (info != null && UpdateChecker.isNewer(SettingsActivity.this, info)) {
                    uInfo = info;
                    uState = U_HAS;
                    btnUpdate.setText(getString(R.string.upd_btn_download, info.versionName));
                    String s = getString(R.string.upd_found, info.versionName);
                    if (info.notes.length() > 0) {
                        s = s + "\n" + info.notes;
                    }
                    tvUpdateStatus.setText(s);
                    if (autoDownload) {
                        startDownload();
                    }
                } else {
                    uState = U_IDLE;
                    uInfo = null;
                    btnUpdate.setText(getString(R.string.btn_check_update));
                    tvUpdateStatus.setText(latestText(info));   // TASK-020：通道相关 + 降级窗口如实
                }
            }
        });
    }

    private void startDownload() {
        if (uInfo == null || uInfo.urls.length == 0) {
            // 只从缓存知道「有新版」、还没拿到下载地址 —— 重新取清单，回来会自动接着下载
            startCheck(true);
            return;
        }
        // Android 8.0 起，「安装未知应用」是逐个应用授权的，需要引导用户去开
        if (!ApkInstaller.canInstall(this)) {
            tvUpdateStatus.setText(getString(R.string.upd_need_perm));
            try {
                Intent it = ApkInstaller.unknownSourcesIntent(this);
                if (it != null) {
                    startActivity(it);
                }
            } catch (Throwable t) {
                toast("打不开设置页：" + t);
            }
            return;
        }

        uState = U_DOWNLOADING;
        btnUpdate.setText(getString(R.string.upd_downloading, 0));
        tvUpdateStatus.setText("");
        ApkInstaller.download(this, uInfo, new ApkInstaller.Progress() {
            @Override
            public void onProgress(int percent) {
                if (percent >= 0) {
                    btnUpdate.setText(getString(R.string.upd_downloading, percent));
                }
            }

            @Override
            public void onDone(File apk, String error) {
                uState = U_IDLE;
                btnUpdate.setText(getString(R.string.btn_check_update));
                if (apk == null) {
                    tvUpdateStatus.setText(getString(R.string.upd_dl_failed,
                            (error == null) ? "未知错误" : error));
                    return;
                }
                tvUpdateStatus.setText(getString(R.string.upd_install_now));
                // 此刻设置页在前台（sOwnUiForeground=true），桌面卡片本就收起，
                // 不会压到系统安装界面上。
                try {
                    ApkInstaller.install(SettingsActivity.this, apk);
                } catch (Throwable t) {
                    tvUpdateStatus.setText("拉起安装界面失败：" + t);
                }
            }
        });
    }

    /**
     * 从系统剪贴板取 Key（v0.4.2 · 用户拍板 A3）。
     *
     * 前台 Activity 读剪贴板**不需要任何权限**（Android 10 起只有后台读剪贴板才受限）。
     * 只认 `wrk-` 开头的内容，免得把别的东西粘进输入框；不匹配也说清楚，
     * 而不是默默没反应 —— 墨水屏上"点了没动静"最让人摸不着头脑。
     */
    private void pasteFromClipboard() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                toast("剪贴板是空的 —— 先复制 API Key 再点这里");
                return;
            }
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            String s = (cs == null) ? "" : cs.toString().trim();
            if (s.length() == 0) {
                toast("剪贴板是空的 —— 先复制 API Key 再点这里");
                return;
            }
            if (!s.startsWith("wrk-")) {
                toast("剪贴板里的内容不是 API Key（应以 wrk- 开头）");
                return;
            }
            etKey.setText(s);
            etKey.setSelection(s.length());
            toast("已粘贴，建议点「测试连接」验一下");
        } catch (Throwable t) {
            toast("读取剪贴板失败：" + t);
        }
    }

    /**
     * 测一次连接（v0.4.2 · 用户拍板 D2）。
     *
     * 阻塞调用丢后台线程，结果用 Toast 报出（用户拍板用弹窗，不做页内状态行）。
     * 文案由 {@link WereadApi#testKey} 按网关**实测**的错误码生成 ——
     * 成功会报出书架本数与划线总数，失败会区分"Key 无效 / 网络不通 / 其它"。
     */
    private void testConnection() {
        final String k = etKey.getText().toString().trim();
        if (k.length() == 0) {
            toast("请先填入 API Key");
            return;
        }
        toastShort("正在测试…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String msg = WereadApi.testKey(k);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(msg);
                    }
                });
            }
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /**
     * 短提示。给「正在测试…」这类**过渡**文案用 ——
     * 用 LENGTH_LONG 的话结果 Toast 要排队等 3.5 秒才出现，用户会以为卡住了。
     */
    private void toastShort(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
