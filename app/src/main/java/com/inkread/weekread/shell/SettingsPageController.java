package com.inkread.weekread.shell;

import com.inkread.weekread.R;
import com.inkread.weekread.a11y.CardA11yService;
import com.inkread.weekread.core.AchievementPrefs;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.feature.NoteExport;
import com.inkread.weekread.feature.WeekCardView;
import com.inkread.weekread.net.UpdateChecker;
import com.inkread.weekread.net.WereadApi;
import com.inkread.weekread.remote.RemoteRole;
import com.inkread.weekread.update.ApkInstaller;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/**
 * 设置页控制器（🆕 TASK-045 / V1.2.0-beta 抽出）。
 *
 * <p>接管 {@code page_init} + {@code page_custom} 两块（初始化 + 自定义）的**全部**逻辑 ——
 * 即改造前 {@link SettingsActivity} 里 init/custom 相关段落的**逐字平移**：
 * API Key 管理、桌面卡片开关/周期/绑定/成就/本月呈现/切换方式/卡片池/本记字号/导出模板、
 * 版本与更新（含通道 + 云端备用源）、本机角色（install_role）。
 *
 * <p>🔴 为什么要抽：改造后设置页有两个宿主 ——
 * <ul>
 *   <li>{@link SettingsActivity}（phone 端入口）；</li>
 *   <li>{@link MainActivity}（reader 端大标签② 的 {@code mp_settings} 容器）。</li>
 * </ul>
 * 本类即两处共用的唯一实现（避免双份维护）。
 *
 * <p>⚠️ 宿主职责边界（本类**不做**）：
 * <ul>
 *   <li>先 {@code setContentView} 再调 {@link #bind()}（本类只对已 inflate 的视图树做 {@code findViewById}）；</li>
 *   <li>顶部页签（初始化/自定义/实验室）装配与切换 → 宿主；</li>
 *   <li>深色主题递归染色（phone 专属）→ 宿主；</li>
 *   <li>安装角色变更后的「重装页签 / 子标签 / 深色」→ 通过 {@link Listener#onInstallRoleChanged()} 回调宿主。</li>
 * </ul>
 */
public class SettingsPageController {

    /** 宿主回调：安装角色（{@code install_role}）变更后需要重装页签 / 子标签 / 深色主题等。 */
    public interface Listener {
        void onInstallRoleChanged();

        /**
         * 保存 API Key 之后。
         *
         * <p>原 {@link SettingsActivity} 在此 {@code finish()}（退回上一页，靠 onResume 重新取数）；
         * {@link MainActivity} 是 App 内嵌页、不能关页，应改为「切回阅读页 + 强制刷新」。
         */
        void onKeySaved();
    }

    private final Activity host;
    private final Listener listener;

    private EditText etKey;
    private CheckBox cbCard;
    private CheckBox cbSinglePage;   // TASK-011 仅一页模式
    private CheckBox cbBindWeek;     // TASK-012 刷新绑定
    private CheckBox cbBindMonth;
    private CheckBox cbBindBook;

    // ── v0.9（TASK-013）阅读成就提示 ──
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

    // ── v0.8.1（TASK-014）本月呈现方式 ──
    private RadioButton rbMonthStyleCheckin;   // 打卡网格
    private RadioButton rbMonthStyleHeatmap;   // 阅读热力图（默认）

    // ── v0.9（TASK-016）本记字号（卡片）──
    private SeekBar seekNoteCardSize;          // 12–24 号连续调节
    private TextView tvNoteCardSize;           // 当前值，如「17 号 · 20.4px」

    // ── V1.0.3-beta（TASK-025）卡片切换方式 + 桌面显示的卡片 ──
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

    // phone 角色整块隐藏「桌面卡片」分区（A7）
    private View sectionCardInit;        // 初始化页 ③ 桌面卡片
    private View sectionCardCustom;      // 自定义页「桌面卡片」
    private View sectionApiKeyInit;      // 🆕 TASK-043：初始化页 ① API Key 组（phone 隐藏）

    /** 初始化页 ⓪「本机角色」单选组（阅读器端 / 手机端）。 */
    private RadioGroup rgInstallRole;

    // ── v0.5.0 更新区状态机 ──
    // 一个按钮走完全程（检查 → 下载并安装 → 下载中 xx%），按钮文字始终说明「下一步会发生什么」。
    // 墨水屏上弹确认对话框又笨又慢，用按钮文字表达意图更合适。
    private static final int U_IDLE = 0;
    private static final int U_CHECKING = 1;
    private static final int U_HAS = 2;
    private static final int U_DOWNLOADING = 3;
    private int uState = U_IDLE;
    private UpdateChecker.Info uInfo;

    public SettingsPageController(Activity host, Listener listener) {
        this.host = host;
        this.listener = listener;
    }

    // ══════════════════════ 装配 ══════════════════════

    /** 装配本页全部控件与监听（等价于改造前 {@link SettingsActivity#onCreate} 的 init/custom 段）。 */
    public void bind() {
        etKey = (EditText) host.findViewById(R.id.et_key);
        // 🆕 TASK-044-R1：**回填已保存的 API Key**。
        // 此前输入框恒为空（全流程只有「粘贴」会写它），用户看不出"到底存没存"——
        // 屏幕上卡片有数据、设置页却是个空框，观感自相矛盾（尤其重装后怀疑 Key 丢了）。
        // 只在框为空时回填 ⇒ 不覆盖用户正在编辑、尚未保存的内容。
        if (etKey != null && etKey.getText().toString().trim().length() == 0) {
            String savedKey = StatsStore.getKey(host);
            if (savedKey != null && savedKey.length() > 0) etKey.setText(savedKey);
        }
        cbCard = (CheckBox) host.findViewById(R.id.cb_card);
        cbSinglePage = (CheckBox) host.findViewById(R.id.cb_single_page);
        cbBindWeek = (CheckBox) host.findViewById(R.id.cb_bind_week);
        cbBindMonth = (CheckBox) host.findViewById(R.id.cb_bind_month);
        cbBindBook = (CheckBox) host.findViewById(R.id.cb_bind_book);

        // ── v0.9（TASK-013）阅读成就提示 ──
        cbAchv = (CheckBox) host.findViewById(R.id.cb_achv);
        rbAchvWeekOff = (RadioButton) host.findViewById(R.id.rb_achv_week_off);
        rbAchvWeekPerfect = (RadioButton) host.findViewById(R.id.rb_achv_week_perfect);
        rbAchvWeekBerserk = (RadioButton) host.findViewById(R.id.rb_achv_week_berserk);
        rbAchvWeekCustom = (RadioButton) host.findViewById(R.id.rb_achv_week_custom);
        rbAchvMonthOff = (RadioButton) host.findViewById(R.id.rb_achv_month_off);
        rbAchvMonthPerfect = (RadioButton) host.findViewById(R.id.rb_achv_month_perfect);
        rbAchvMonthBerserk = (RadioButton) host.findViewById(R.id.rb_achv_month_berserk);
        rbAchvMonthCustom = (RadioButton) host.findViewById(R.id.rb_achv_month_custom);
        etAchvWeekHours = (EditText) host.findViewById(R.id.et_achv_week);
        etAchvMonthHours = (EditText) host.findViewById(R.id.et_achv_month);

        // v0.8.1（TASK-014）本月呈现方式
        rbMonthStyleCheckin = (RadioButton) host.findViewById(R.id.rb_month_style_checkin);
        rbMonthStyleHeatmap = (RadioButton) host.findViewById(R.id.rb_month_style_heatmap);

        // V1.0.3-beta（TASK-025）卡片切换方式 + 桌面显示的卡片
        rbSwitchLoop = (RadioButton) host.findViewById(R.id.rb_switch_loop);
        rbSwitchList = (RadioButton) host.findViewById(R.id.rb_switch_list);
        cbPoolWeek = (CheckBox) host.findViewById(R.id.cb_pool_week);
        cbPoolMonth = (CheckBox) host.findViewById(R.id.cb_pool_month);
        cbPoolBook = (CheckBox) host.findViewById(R.id.cb_pool_book);
        cbPoolNote = (CheckBox) host.findViewById(R.id.cb_pool_note);
        cbPoolTodo = (CheckBox) host.findViewById(R.id.cb_pool_todo);

        rbWeek = (RadioButton) host.findViewById(R.id.rb_period_week);
        rbMonth = (RadioButton) host.findViewById(R.id.rb_period_month);
        rbBook = (RadioButton) host.findViewById(R.id.rb_period_book);
        rbNote = (RadioButton) host.findViewById(R.id.rb_period_note);
        tvStatus = (TextView) host.findViewById(R.id.tv_status);
        tvVersion = (TextView) host.findViewById(R.id.tv_version);
        tvUpdateStatus = (TextView) host.findViewById(R.id.tv_update_status);
        btnUpdate = (Button) host.findViewById(R.id.btn_update);
        rbChannelStable = (RadioButton) host.findViewById(R.id.rb_channel_stable);
        rbChannelBeta = (RadioButton) host.findViewById(R.id.rb_channel_beta);
        tvChannelTip = (TextView) host.findViewById(R.id.tv_channel_tip);
        cbCloudFallback = (CheckBox) host.findViewById(R.id.cb_cloud_fallback);
        sectionCardInit = host.findViewById(R.id.section_card_init);
        sectionCardCustom = host.findViewById(R.id.section_card_custom);
        sectionApiKeyInit = host.findViewById(R.id.section_api_key_init);   // 🆕 TASK-043

        cbCard.setChecked(CardPrefs.isEnabled(host));
        String cp = StatsStore.getCardPeriod(host);
        rbWeek.setChecked(PeriodRange.WEEKLY.equals(cp));
        rbMonth.setChecked(PeriodRange.MONTHLY.equals(cp));
        rbBook.setChecked(PeriodRange.BOOK.equals(cp));
        rbNote.setChecked(PeriodRange.NOTE.equals(cp));

        // ── 本记导出模板 ──
        final RadioButton pPlain = (RadioButton) host.findViewById(R.id.rb_paper_plain);
        final RadioButton pJournal = (RadioButton) host.findViewById(R.id.rb_paper_journal);
        final RadioButton pCard = (RadioButton) host.findViewById(R.id.rb_paper_card);
        final RadioButton sS = (RadioButton) host.findViewById(R.id.rb_nsize_s);
        final RadioButton sM = (RadioButton) host.findViewById(R.id.rb_nsize_m);
        final RadioButton sL = (RadioButton) host.findViewById(R.id.rb_nsize_l);
        final CheckBox cbSign = (CheckBox) host.findViewById(R.id.cb_note_sign);
        final CheckBox cbDate = (CheckBox) host.findViewById(R.id.cb_note_date);
        int paper = NoteExport.paper(host);
        pPlain.setChecked(paper == 0);
        pJournal.setChecked(paper == 1);
        pCard.setChecked(paper == 2);
        int tier = NoteExport.sizeTier(host);
        sS.setChecked(tier == 0);
        sM.setChecked(tier == 1);
        sL.setChecked(tier == 2);
        cbSign.setChecked(NoteExport.showSign(host));
        cbDate.setChecked(NoteExport.showDate(host));
        CompoundButton.OnCheckedChangeListener paperL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;
                int id = b.getId();
                NoteExport.setPaper(host,
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
                NoteExport.setSizeTier(host,
                        id == R.id.rb_nsize_s ? 0 : id == R.id.rb_nsize_m ? 1 : 2);
            }
        };
        sS.setOnCheckedChangeListener(sizeL);
        sM.setOnCheckedChangeListener(sizeL);
        sL.setOnCheckedChangeListener(sizeL);
        cbSign.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                NoteExport.setShowSign(host, checked);
            }
        });
        cbDate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                NoteExport.setShowDate(host, checked);
            }
        });

        // ── 🆕 TASK-059：书籍排名「只统计书架上的书」 ──
        // 只写偏好（App 内两处排名读它）：切回「阅读」页时 showReaderPage() 会重放一帧加载它；
        // 若本地还没有全量书架清单，MainActivity 会顺手补拉一次 /shelf/sync。
        // 🔴 不调 CardA11yService.sync() —— 本项**不影响桌面卡片**（排名只在 App 全屏页 / 洞察页）。
        final CheckBox cbRankShelfOnly = (CheckBox) host.findViewById(R.id.cb_rank_shelf_only);
        cbRankShelfOnly.setChecked(CardPrefs.isRankShelfOnly(host));
        cbRankShelfOnly.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                CardPrefs.setRankShelfOnly(host, checked);
            }
        });

        cbCard.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                CardPrefs.setEnabled(host, checked);
                CardA11yService.sync();
                refreshStatus();
            }
        });

        // TASK-011 仅一页模式：改完立即重算一次显隐（与卡片开关同款既有路径）
        cbSinglePage.setChecked(CardPrefs.isSinglePageMode(host));
        cbSinglePage.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                CardPrefs.setSinglePageMode(host, checked);
                CardA11yService.sync();
                refreshStatus();
            }
        });

        // ── TASK-012 刷新绑定：三个勾选 = bind_targets 位掩码 ──
        // 只写偏好，不 CardA11yService.sync()、不重绘 —— 下次点「更新于…」时才读它。
        int bindTargets = CardPrefs.getBindTargets(host);
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
                CardPrefs.setBindTargets(host, t);
            }
        };
        cbBindWeek.setOnCheckedChangeListener(bindL);
        cbBindMonth.setOnCheckedChangeListener(bindL);
        cbBindBook.setOnCheckedChangeListener(bindL);

        // ── TASK-013 阅读成就提示 ──
        cbAchv.setChecked(AchievementPrefs.isEnabled(host));
        cbAchv.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                AchievementPrefs.setEnabled(host, checked);
                CardA11yService.sync();
            }
        });

        int wk = AchievementPrefs.getWeekKind(host);
        int mk = AchievementPrefs.getMonthKind(host);
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
                AchievementPrefs.setWeekKind(host, kind);
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
                AchievementPrefs.setMonthKind(host, kind);
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
        etAchvWeekHours.setText(minToHours(AchievementPrefs.getWeekMin(host)));
        etAchvWeekHours.setEnabled(wk == AchievementPrefs.KIND_CUSTOM);
        etAchvWeekHours.addTextChangedListener(hoursWatcher(true));
        etAchvMonthHours.setText(minToHours(AchievementPrefs.getMonthMin(host)));
        etAchvMonthHours.setEnabled(mk == AchievementPrefs.KIND_CUSTOM);
        etAchvMonthHours.addTextChangedListener(hoursWatcher(false));

        // ── v0.8.1（TASK-014）本月呈现方式：打卡 / 热力图（默认热力图） ──
        int ms = CardPrefs.getMonthStyle(host);
        rbMonthStyleCheckin.setChecked(ms == CardPrefs.MONTH_STYLE_CHECKIN);
        rbMonthStyleHeatmap.setChecked(ms == CardPrefs.MONTH_STYLE_HEATMAP);
        CompoundButton.OnCheckedChangeListener monthStyleL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;               // 只管"被选中的那个"
                int style = (b.getId() == R.id.rb_month_style_checkin)
                        ? CardPrefs.MONTH_STYLE_CHECKIN : CardPrefs.MONTH_STYLE_HEATMAP;
                CardPrefs.setMonthStyle(host, style);
                CardA11yService.sync();
            }
        };
        rbMonthStyleCheckin.setOnCheckedChangeListener(monthStyleL);
        rbMonthStyleHeatmap.setOnCheckedChangeListener(monthStyleL);

        // ── V1.0.3-beta（TASK-025）卡片切换方式：循环 / 列表 ──
        // 只影响"点抬头"的行为，不改卡片内容 ⇒ 只写偏好、不 sync（省一次墨水屏闪烁）。
        int swm = CardPrefs.getSwitchMode(host);
        rbSwitchLoop.setChecked(swm == CardPrefs.SWITCH_LOOP);
        rbSwitchList.setChecked(swm == CardPrefs.SWITCH_LIST);
        CompoundButton.OnCheckedChangeListener switchModeL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;               // 只管"被选中的那个"
                int m = (b.getId() == R.id.rb_switch_list)
                        ? CardPrefs.SWITCH_LIST : CardPrefs.SWITCH_LOOP;
                CardPrefs.setSwitchMode(host, m);
            }
        };
        rbSwitchLoop.setOnCheckedChangeListener(switchModeL);
        rbSwitchList.setOnCheckedChangeListener(switchModeL);

        // ── V1.0.3-beta（TASK-025）桌面显示的卡片（卡片池）：多选，至少留一张 ──
        // 勾选变化 → 写掩码；若**当前卡被移出池** ⇒ 立刻落到池里第一张（与 toggleCardPeriod
        // 的兜底同口径）——否则用户会停在"设置里明明没勾、桌面却还显示着"的矛盾状态。
        int poolMask = CardPrefs.getCardPoolMask(host);
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
                    Toast.makeText(host, R.string.card_pool_min_one, Toast.LENGTH_SHORT).show();
                    return;
                }
                CardPrefs.setCardPoolMask(host, mask);
                String cur = StatsStore.getCardPeriod(host);
                if ((mask & StatsStore.poolBitOf(cur)) == 0) {
                    java.util.List<String> modes = StatsStore.poolModes(host);
                    if (!modes.isEmpty()) StatsStore.setCardPeriod(host, modes.get(0));
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
        seekNoteCardSize = (SeekBar) host.findViewById(R.id.sb_note_card_size);
        tvNoteCardSize = (TextView) host.findViewById(R.id.tv_note_card_size);
        seekNoteCardSize.setMax(CardPrefs.NOTE_CARD_SIZE_MAX - CardPrefs.NOTE_CARD_SIZE_MIN);
        int noteCardSize = CardPrefs.getNoteCardSize(host);
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
                CardPrefs.setNoteCardSize(host, sb.getProgress() + CardPrefs.NOTE_CARD_SIZE_MIN);
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
                        StatsStore.setCardPeriod(host, m);
                        CardA11yService.sync();          // 桌面卡片立刻换帧
                        refreshStatus();
                    }
                };
        rbWeek.setOnCheckedChangeListener(periodListener);
        rbMonth.setOnCheckedChangeListener(periodListener);
        rbBook.setOnCheckedChangeListener(periodListener);
        rbNote.setOnCheckedChangeListener(periodListener);

        host.findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String k = etKey.getText().toString().trim();
                if (k.length() == 0) {
                    toast("请先填入 API Key");
                    return;
                }
                // v0.5.3（R05）：换 Key 会统一失效个人数据缓存（统计 / 书架 / 进度 / 章节 /
                // 划线 / 想法 / 抽取状态），否则屏幕上会继续显示上一个账号的数据。
                boolean changed = StatsStore.setKey(host, k);
                toast(changed ? "已保存，个人数据缓存已清除" : "已保存");
                // 由宿主决定去向：独立设置页 = finish() 退回上一页；App 内嵌页 = 切回阅读页 + 刷新。
                if (listener != null) listener.onKeySaved();
            }
        });

        // ── v0.4.2：粘贴（墨水屏上手打 27 位 Key 太难受，直接读剪贴板）──
        host.findViewById(R.id.btn_paste).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });

        // ── v0.4.2：测试连接（填完立刻验一次，别靠"卡片空白"去猜）──
        host.findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });

        // ── v0.4.2：怎么用（内置使用说明）──
        host.findViewById(R.id.btn_help).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    host.startActivity(new Intent(host, HelpActivity.class));
                } catch (Throwable t) {
                    toast("打不开说明页：" + t);
                }
            }
        });

        host.findViewById(R.id.btn_a11y).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    host.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    toast("打不开无障碍设置：" + t);
                }
            }
        });

        // ── v0.6.0：卡片显示状态的出口 ──
        // 「翻离第 1 页就收起」靠桌面翻页事件判定，事件投递不是 100% 可靠。
        // 万一漏投、卡片停在隐藏状态，用户在这里一键回到"按当前前台重算"。
        host.findViewById(R.id.btn_reset_card).setOnClickListener(new View.OnClickListener() {
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
        String chNow = CardPrefs.getUpdateChannel(host);
        rbChannelStable.setChecked(!CardPrefs.CHANNEL_BETA.equals(chNow));
        rbChannelBeta.setChecked(CardPrefs.CHANNEL_BETA.equals(chNow));
        refreshChannelTip();
        CompoundButton.OnCheckedChangeListener channelL = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) return;            // 只管"被选中的那个"
                String v = (b.getId() == R.id.rb_channel_beta)
                        ? CardPrefs.CHANNEL_BETA : CardPrefs.CHANNEL_STABLE;
                CardPrefs.setUpdateChannel(host, v);
                // 归零更新区：uInfo 属于旧通道，别让它串台；按钮回到「检查更新」
                uState = U_IDLE;
                uInfo = null;
                btnUpdate.setText(host.getString(R.string.btn_check_update));
                tvUpdateStatus.setText("");
                refreshChannelTip();
                refreshUpdateUi();
            }
        };
        rbChannelStable.setOnCheckedChangeListener(channelL);
        rbChannelBeta.setOnCheckedChangeListener(channelL);

        // ── TASK-030：云端备用更新源（默认开）──
        // 只决定"GitHub 两源都失败时是否再试云端源"，与通道选择无关；切换后重置更新区状态。
        cbCloudFallback.setChecked(CardPrefs.isCloudFallbackEnabled(host));
        cbCloudFallback.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                CardPrefs.setCloudFallbackEnabled(host, checked);
                uState = U_IDLE;
                uInfo = null;
                btnUpdate.setText(host.getString(R.string.btn_check_update));
                tvUpdateStatus.setText("");
            }
        });

        // ── 🆕 TASK-031：本机角色切换（初始化页 · ⓪ 本机角色）──
        // 一键切 install_role ⇒ 入口与"实验室可见子标签"随之变化；提示重开 App 生效。
        // 🔴 install_role（App 形态）与 remote_role（TCP 传输角色）**正交**，此处只动前者。
        rgInstallRole = (RadioGroup) host.findViewById(R.id.rg_install_role);
        rgInstallRole.check(CardPrefs.getInstallRole(host) == CardPrefs.INSTALL_ROLE_PHONE
                ? R.id.rb_inst_phone : R.id.rb_inst_reader);
        rgInstallRole.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                int role = (checkedId == R.id.rb_inst_phone)
                        ? CardPrefs.INSTALL_ROLE_PHONE : CardPrefs.INSTALL_ROLE_READER;
                if (role == CardPrefs.getInstallRole(host)) return;   // 无变化不处理
                CardPrefs.setInstallRole(host, role);
                // 🆕 TASK-043：顶部页签随角色重装；实验室可见子标签随之变化；
                //   蓝牙控制页按新角色重刷；重算 API Key / 桌面卡片分区显隐；深色适用范围可能变。
                if (listener != null) listener.onInstallRoleChanged();
                Toast.makeText(host, R.string.install_role_changed, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ══════════════════════ 刷新 ══════════════════════

    /**
     * 🆕 TASK-043：按 {@code install_role} 重算**设置页侧**的区块显隐。
     *
     * <p>① phone 角色整块隐藏两处「桌面卡片」分区（A7）；
     * ② 🆕 TASK-043：手机端（install_role=phone）隐藏「初始化 · API Key」整块（手机不取数）；
     * ③ 🆕 TASK-043-R1：手机端「本机角色」恒留在「初始化」页顶部（两端一致，不再随角色搬运）。
     */
    public void refreshRoleVisibility() {
        RemoteRole role = RemoteRole.from(host);
        // 🔴 phone 角色隐藏「桌面卡片」分区（A7）
        int cardVis = (role == RemoteRole.PHONE) ? View.GONE : View.VISIBLE;
        if (sectionCardInit != null) sectionCardInit.setVisibility(cardVis);
        if (sectionCardCustom != null) sectionCardCustom.setVisibility(cardVis);
        // 🆕 TASK-043：手机端（install_role=phone）隐藏「初始化 · API Key」整块
        //   —— 手机不取数，Key 对其无用；reader 端一字不改（A6）。
        if (sectionApiKeyInit != null) {
            sectionApiKeyInit.setVisibility(
                    CardPrefs.getInstallRole(host) == CardPrefs.INSTALL_ROLE_PHONE
                            ? View.GONE : View.VISIBLE);
        }
        relocateInstallRoleBlock();
    }

    /**
     * 🆕 TASK-043-R1：「本机角色」整块**恒留在「初始化」页**（index 0）。
     *
     * <p>用「整块搬运」而非「复制一份」：只有一个 {@code rg_install_role} 实例、一份监听器，
     * 避免同 id 双控件导致 {@code findViewById} 取错 / 勾选串台。
     */
    private void relocateInstallRoleBlock() {
        View block = host.findViewById(R.id.section_install_role_init);
        if (block == null) return;
        // 🆕 TASK-043-R1：恒回「初始化」页顶部（手机端 / 阅读器端一致）。
        ViewGroup wantParent = (ViewGroup) host.findViewById(R.id.page_init);
        if (wantParent == null) return;
        if (block.getParent() == wantParent) return;     // 已在位：不动（避免每帧重排）
        if (block.getParent() instanceof ViewGroup) {
            ((ViewGroup) block.getParent()).removeView(block);
        }
        wantParent.addView(block, 0);                    // 置顶
    }

    /** 把卡片的真实状态写出来，别让用户以为"开关打开就一定看得见" */
    public void refreshStatus() {
        if (tvStatus == null) return;
        if (!CardPrefs.isEnabled(host)) {
            tvStatus.setText("卡片已关闭。打开上面的开关即可在桌面显示。");
            return;
        }
        if (CardA11yService.isConnected()) {
            tvStatus.setText("运行中 ✓　只在桌面显示，切到别的应用自动隐藏。");
        } else if (CardA11yService.isEnabledInSystem(host)) {
            tvStatus.setText("已授权，等待系统拉起…（回桌面看一眼，没有就重开一次开关）");
        } else {
            // v0.4.3：状态行在「桌面卡片」块末尾，无障碍按钮在它上方，故说"上面"
            tvStatus.setText("未开启 —— 点上面的「打开系统无障碍设置」，在「已下载的服务」里打开「微读墨记」。");
        }
    }

    /**
     * 更新通道的常显说明行（TASK-020 验收 A4）。
     *
     * 🔴 必须含「两条通道的版本号不可直接比较」的等价表述 —— 否则用户看到
     * 「正式版 0.9.1」与「Beta 1.0.0-beta.1」会以为前者更旧而误判。
     * 用常显行承担知情同意，**不做弹窗**（沿用「墨水屏上弹窗尤其讨厌」约定）。
     */
    public void refreshChannelTip() {
        if (tvChannelTip == null) return;
        boolean beta = CardPrefs.CHANNEL_BETA.equals(CardPrefs.getUpdateChannel(host));
        tvChannelTip.setText(host.getString(
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
        boolean stable = !CardPrefs.CHANNEL_BETA.equals(CardPrefs.getUpdateChannel(host));
        boolean localBeta = UpdateChecker.currentVersionName(host).contains("-beta");
        if (stable && localBeta
                && info != null && info.versionName != null && info.versionName.length() > 0) {
            return host.getString(R.string.upd_downgrade_blocked, info.versionName);
        }
        return host.getString(stable ? R.string.upd_latest_stable : R.string.upd_latest_beta,
                UpdateChecker.currentVersionName(host));
    }

    /**
     * 刷新版本区。
     *
     * 这里是「静默检查」的存在意义：启动时顺手查一次、结果落盘，用户打开设置页立刻就能
     * 看到「有新版本」，全程没有弹窗打断（墨水屏上弹窗尤其讨厌）。
     */
    public void refreshUpdateUi() {
        if (tvVersion == null) return;
        tvVersion.setText(host.getString(R.string.upd_current,
                UpdateChecker.currentVersionName(host), UpdateChecker.currentVersionCode(host)));
        if (uState != U_IDLE) {
            return;                     // 有进行中的流程，别覆盖状态文字
        }
        if (UpdateChecker.hasKnownUpdate(host)) {
            String s = host.getString(R.string.upd_found, UpdateChecker.remoteVersionName(host));
            String notes = UpdateChecker.remoteNotes(host);
            if (notes.length() > 0) {
                s = s + "\n" + notes;
            }
            tvUpdateStatus.setText(s);
            // TASK-004：按钮文字必须跟上状态。
            btnUpdate.setText(host.getString(R.string.upd_btn_download,
                    UpdateChecker.remoteVersionName(host)));
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
        startCheck(UpdateChecker.hasKnownUpdate(host));
    }

    /** @param autoDownload 查到新版后直接接着下载（用于「已知有新版」时一键走完） */
    private void startCheck(final boolean autoDownload) {
        uState = U_CHECKING;
        btnUpdate.setText(host.getString(R.string.upd_checking));
        tvUpdateStatus.setText("");
        UpdateChecker.check(host, true, new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.Info info, String error) {
                if (error != null) {
                    uState = U_IDLE;
                    btnUpdate.setText(host.getString(R.string.btn_check_update));
                    tvUpdateStatus.setText(host.getString(R.string.upd_failed, error));
                    return;
                }
                if (info != null && UpdateChecker.isNewer(host, info)) {
                    uInfo = info;
                    uState = U_HAS;
                    btnUpdate.setText(host.getString(R.string.upd_btn_download, info.versionName));
                    String s = host.getString(R.string.upd_found, info.versionName);
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
                    btnUpdate.setText(host.getString(R.string.btn_check_update));
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
        if (!ApkInstaller.canInstall(host)) {
            tvUpdateStatus.setText(host.getString(R.string.upd_need_perm));
            try {
                Intent it = ApkInstaller.unknownSourcesIntent(host);
                if (it != null) {
                    host.startActivity(it);
                }
            } catch (Throwable t) {
                toast("打不开设置页：" + t);
            }
            return;
        }

        uState = U_DOWNLOADING;
        btnUpdate.setText(host.getString(R.string.upd_downloading, 0));
        tvUpdateStatus.setText("");
        ApkInstaller.download(host, uInfo, new ApkInstaller.Progress() {
            @Override
            public void onProgress(int percent) {
                if (percent >= 0) {
                    btnUpdate.setText(host.getString(R.string.upd_downloading, percent));
                }
            }

            @Override
            public void onDone(File apk, String error) {
                uState = U_IDLE;
                btnUpdate.setText(host.getString(R.string.btn_check_update));
                if (apk == null) {
                    tvUpdateStatus.setText(host.getString(R.string.upd_dl_failed,
                            (error == null) ? "未知错误" : error));
                    return;
                }
                tvUpdateStatus.setText(host.getString(R.string.upd_install_now));
                // 此刻设置页在前台（sOwnUiForeground=true），桌面卡片本就收起，
                // 不会压到系统安装界面上。
                try {
                    ApkInstaller.install(host, apk);
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
            ClipboardManager cm =
                    (ClipboardManager) host.getSystemService(Activity.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                toast("剪贴板是空的 —— 先复制 API Key 再点这里");
                return;
            }
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(host);
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
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(msg);
                    }
                });
            }
        }).start();
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
                int old = week ? AchievementPrefs.getWeekMin(host)
                        : AchievementPrefs.getMonthMin(host);
                if (min == old) return;
                if (week) AchievementPrefs.setWeekMin(host, min);
                else AchievementPrefs.setMonthMin(host, min);
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

    /**
     * 「17 号 · 20.4px」—— 本记字号（卡片）滑杆当前值的显示文案（v0.9，TASK-016）。
     *
     * 为什么要把 px 也写出来：「号」是本项目的内部单位，用户没有这个概念；
     * 只写"17 号"他无从判断大小。px 用 {@link WeekCardView#UNIT_RATIO} 换算
     * （"号 → px"的唯一真源，别在这里另抄一份 0.0015）。
     */
    private String noteCardSizeLabel(int num) {
        float px = num * host.getResources().getDisplayMetrics().heightPixels
                * WeekCardView.UNIT_RATIO;
        return num + " 号 · " + String.format(Locale.CHINA, "%.1f", px) + "px";
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_LONG).show();
    }

    /**
     * 短提示。给「正在测试…」这类**过渡**文案用 ——
     * 用 LENGTH_LONG 的话结果 Toast 要排队等 3.5 秒才出现，用户会以为卡住了。
     */
    private void toastShort(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}
