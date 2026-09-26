package com.inkread.weekread.shell;

import com.inkread.weekread.R;
import com.inkread.weekread.a11y.CardA11yService;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.feature.NoteExport;
import com.inkread.weekread.feature.lab.DefaultHomeProbe;
import com.inkread.weekread.feature.lab.LabRunner;
import com.inkread.weekread.feature.lab.ProbeResult;
import com.inkread.weekread.net.UpdateChecker;
import com.inkread.weekread.net.WereadApi;
import com.inkread.weekread.ui.SegTabView;
import com.inkread.weekread.update.ApkInstaller;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import java.io.File;
import java.util.List;

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
 * v0.7（TASK-000）再加第三页：
 *   · **实验室**：设备能力自检（🔴 只读）+ 常显一行「当前系统默认桌面」。
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
    private RadioButton rbWeek;
    private RadioButton rbMonth;
    private RadioButton rbBook;
    private RadioButton rbNote;
    private TextView tvStatus;
    private TextView tvVersion;
    private TextView tvUpdateStatus;
    private Button btnUpdate;

    // ── v0.7（TASK-000）实验室 · 设备能力自检（🔴 只读）──
    private TextView tvLabHome;      // 常显行：当前系统默认桌面
    private TextView tvLabResult;    // 五项结论 + 原始证据
    private Button btnLabRun;
    private boolean labRunning = false;
    private String labText;          // 最近一次汇总文本（供「复制结果」）

    // ── v0.5.0 更新区状态机 ──
    // 一个按钮走完全程（检查 → 下载并安装 → 下载中 xx%），按钮文字始终说明「下一步会发生什么」。
    // 墨水屏上弹确认对话框又笨又慢，用按钮文字表达意图更合适。
    private static final int U_IDLE = 0;
    private static final int U_CHECKING = 1;
    private static final int U_HAS = 2;
    private static final int U_DOWNLOADING = 3;
    private int uState = U_IDLE;
    private UpdateChecker.Info uInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_layout);

        etKey = (EditText) findViewById(R.id.et_key);
        cbCard = (CheckBox) findViewById(R.id.cb_card);
        cbSinglePage = (CheckBox) findViewById(R.id.cb_single_page);
        cbBindWeek = (CheckBox) findViewById(R.id.cb_bind_week);
        cbBindMonth = (CheckBox) findViewById(R.id.cb_bind_month);
        cbBindBook = (CheckBox) findViewById(R.id.cb_bind_book);
        rbWeek = (RadioButton) findViewById(R.id.rb_period_week);
        rbMonth = (RadioButton) findViewById(R.id.rb_period_month);
        rbBook = (RadioButton) findViewById(R.id.rb_period_book);
        rbNote = (RadioButton) findViewById(R.id.rb_period_note);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvVersion = (TextView) findViewById(R.id.tv_version);
        tvUpdateStatus = (TextView) findViewById(R.id.tv_update_status);
        btnUpdate = (Button) findViewById(R.id.btn_update);
        tvLabHome = (TextView) findViewById(R.id.tv_lab_home);
        tvLabResult = (TextView) findViewById(R.id.tv_lab_result);
        btnLabRun = (Button) findViewById(R.id.btn_lab_run);

        // ── v0.4.3 顶部页签「初始化 / 自定义」；v0.7 加第三段「实验室」──
        // 三个页面是同一个 ScrollView 里的三个容器，切页只切 visibility。
        final View pageInit = findViewById(R.id.page_init);
        final View pageCustom = findViewById(R.id.page_custom);
        final View pageLab = findViewById(R.id.page_lab);
        final ScrollView svSettings = (ScrollView) findViewById(R.id.sv_settings);
        SegTabView seg = (SegTabView) findViewById(R.id.seg);
        // SegTabView 默认只给两段标签，这里显式扩到三段（它本来就是通用 N 段控件）
        seg.setLabels(new String[]{"初始化", "自定义", "实验室"});
        seg.setListener(new SegTabView.Listener() {
            @Override
            public void onSegSelected(int index) {
                pageInit.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
                pageCustom.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
                pageLab.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
                // 切页回到顶部：各页高度不同，留着旧滚动位置会看着像"卡住了"
                svSettings.scrollTo(0, 0);
                // 常显行只在页面可见时算一次 —— 不轮询、不常驻
                if (index == 2) refreshLabHome();
            }
        });

        etKey.setText(StatsStore.getKey(this));
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

        // ── v0.7（TASK-000）：实验室 · 设备能力自检（🔴 只读）──
        btnLabRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runLab();
            }
        });
        ((Button) findViewById(R.id.btn_lab_copy)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLabResult();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 和主页一样：告诉服务"用户在自家界面"，桌面卡片要让位
        CardA11yService.noteOwnUiForeground(true);
        refreshStatus();
        refreshUpdateUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CardA11yService.noteOwnUiForeground(false);
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

    // ────────────────────── v0.7（TASK-000）：实验室 · 设备能力自检 ──────────────────────

    /**
     * 常显行：当前**系统默认桌面**（TASK-000 并入项 B3）。
     *
     * 只在实验室页可见时算一次 —— 不轮询、不常驻、不加权限。
     *
     * 🔴 刻意只说「系统默认桌面」，**不说「当前前台是谁」**：窗口内容能力只有
     *   a11y 的设置页探测（TASK-009）在用，实验室页刻意不读前台，写出来只会误导。
     */
    private void refreshLabHome() {
        try {
            tvLabHome.setText(DefaultHomeProbe.toDisplay(DefaultHomeProbe.probe(this)));
        } catch (Throwable t) {
            tvLabHome.setText("当前系统默认桌面：读取失败\n" + t);
        }
    }

    /** 跑一轮设备能力自检（下后台线程，见 {@link LabRunner}）；期间按钮禁用，避免连点。 */
    private void runLab() {
        if (labRunning) return;
        labRunning = true;
        btnLabRun.setEnabled(false);
        btnLabRun.setText(getString(R.string.lab_running));
        tvLabResult.setText("");
        final long t0 = System.currentTimeMillis();
        try {
            LabRunner.run(this, new LabRunner.Callback() {
                @Override
                public void onDone(String text, List<ProbeResult> results,
                                   DefaultHomeProbe.Result home) {
                    labRunning = false;
                    labText = text;
                    btnLabRun.setEnabled(true);
                    btnLabRun.setText(getString(R.string.btn_lab_run));
                    // 顺手把常显行也刷一遍 —— 用的就是这一轮的探测结果，不额外算
                    tvLabHome.setText(DefaultHomeProbe.toDisplay(home));
                    tvLabResult.setText(text);
                    toast("自检完成（" + (System.currentTimeMillis() - t0) + "ms），可点「复制结果」");
                }
            });
        } catch (Throwable t) {
            labRunning = false;
            btnLabRun.setEnabled(true);
            btnLabRun.setText(getString(R.string.btn_lab_run));
            toast("自检启动失败：" + t);
        }
    }

    /** 把最近一次自检结果整段复制到剪贴板（用户要贴给对话用）。 */
    private void copyLabResult() {
        if (labText == null || labText.length() == 0) {
            toast("先点「开始设备能力自检」");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("拿不到剪贴板服务");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("微读墨记 · 设备能力自检", labText));
            toast("已复制到剪贴板");
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }

    // ────────────────────── v0.5.0：版本与更新 ──────────────────────

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
                    tvUpdateStatus.setText(getString(R.string.upd_latest));
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
