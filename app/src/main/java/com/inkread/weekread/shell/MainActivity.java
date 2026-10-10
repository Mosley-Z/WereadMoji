package com.inkread.weekread.shell;

import com.inkread.weekread.R;
import com.inkread.weekread.a11y.CardA11yService;
import com.inkread.weekread.core.BillScheduler;
import com.inkread.weekread.core.BillStore;
import com.inkread.weekread.core.BookStats;
import com.inkread.weekread.core.BookStore;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.CoverStore;
import com.inkread.weekread.core.FeatureGate;
import com.inkread.weekread.core.LockPrefs;
import com.inkread.weekread.core.MenuPrefs;
import com.inkread.weekread.core.NavExtra;
import com.inkread.weekread.core.NoteStats;
import com.inkread.weekread.core.NoteStore;
import com.inkread.weekread.core.PagePrefs;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PeriodStats;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.core.WallpaperPrefs;
import com.inkread.weekread.feature.BookPickView;
import com.inkread.weekread.feature.InsightPageView;
import com.inkread.weekread.feature.NoteExport;
import com.inkread.weekread.feature.NotePickView;
import com.inkread.weekread.feature.WeekCardView;
import com.inkread.weekread.net.NoteSync;
import com.inkread.weekread.net.UpdateChecker;
import com.inkread.weekread.net.WereadApi;
import com.inkread.weekread.remote.RemoteRole;
import com.inkread.weekread.ui.NavDropView;
import com.inkread.weekread.ui.PeriodPickerView;
import com.inkread.weekread.ui.SegTabView;
import com.inkread.weekread.update.ApkInstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import java.io.File;

/**
 * 「微读墨记」主页。
 *
 * <p>🆕 v1.2（TASK-044）**App 内导航重构**：从「5 段页签」改为**四大标签壳 + 阅读页内下拉**两层：
 *
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │ 阅读   │  设置   │  实验室   │  待办          │  大标签栏 44dp（SegTabView 复用，4 等分）
 * ├──────────────────────────────────────────────┤
 * │  本周  ▽                                     │  下拉行 40dp（NavDropView）
 * ├──────────────────────────────────────────────┤   ← 点击原地展开 5 项
 * │  ← 2026年9月14日–9月20日 →                    │  周期选择器 44dp（仅本周/本月可见）
 * ├──────────────────────────────────────────────┤
 * │              卡片内容区                       │  WeekCardView weight=1
 * └──────────────────────────────────────────────┘
 * </pre>
 *
 * 🔴 **两层解耦**（本卡最高风险点）：
 * <ul>
 *   <li><b>大标签层</b> = {@link #showMainPage(int)} —— 只切 {@code page_reader/settings/lab/todo}
 *       四个容器的 {@code visibility}，**不碰 {@link #tabMode}**；</li>
 *   <li><b>下拉层</b> = {@link #showReaderPage()} —— 只切 {@link #tabMode} 并渲染卡片，
 *       **不碰页面容器**；</li>
 *   <li>两层互不干扰：切大标签不重置下拉选择，切下拉不重置大标签。</li>
 * </ul>
 *
 * <p>手机端形态（{@code install_role=phone}）**不走本导航**：{@link #enterApp()} 早退直达
 * {@link ConsoleActivity}（TASK-043 起的现状，本次一行不改）。
 *
 * <p>── 周期状态怎么存 ──
 * 两个形态**各记一个锚点**（{@link #anchorWeek} / {@link #anchorMonth}），
 * 这样在"本月"里翻到 3 月、切到"本周"看一眼、再切回来，仍然停在 3 月。
 * 锚点是 {@code periodStart} 时间戳（秒），一律由 {@link PeriodRange} 算，不手写月份天数。
 * 锚点只活在内存里 —— 进程重启就回到"当前周期"，因为"打开 App 总是先看当下"才是默认预期。
 *
 * <p>── 取数 ──
 * 当前周期仍按**实测跑通**的 `baseTime=0` 走；只有翻到历史周期才把锚点时间戳传下去
 * （`baseTime` 传周期内任意时刻，服务端会归一化到周期起点，接口零改动）。
 */
public class MainActivity extends Activity {

    // ── 🆕 TASK-044：大标签下标（阅读 / 设置 / 实验室 / 待办）──
    private static final int MP_READER = 0;
    private static final int MP_SETTINGS = 1;
    private static final int MP_LAB = 2;
    private static final int MP_TODO = 3;

    private WeekCardView card;
    /** 🆕 TASK-044：大标签栏（4 段，复用通用 {@link SegTabView}）。 */
    private SegTabView segMain;
    /** 🆕 TASK-044：阅读页顶部下拉（本周/本月/本书/本记/洞察）。 */
    private NavDropView navDrop;
    private PeriodPickerView picker;

    /** 🆕 TASK-044：四大标签的页面容器（只切 visibility）。
     *  🔴 TASK-045：id 前缀用 mp_*（不是 page_*）——避免与 page_settings.xml /
     *  page_lab.xml 里的 page_init/page_custom/page_lab **内层容器** id 在同一视图树里相撞。 */
    private View pageReader;
    private View pageSettings;
    private View pageLab;
    private View pageTodo;
    /** 🆕 TASK-045：设置页子页签（初始化 | 自定义），位于 page_settings.xml。 */
    private SegTabView segSettings;
    /** 🆕 TASK-045：三页控制器（与 {@link SettingsActivity} / {@link TodoActivity} 共用同一实现）。 */
    private SettingsPageController settingsCtrl;
    private LabPageController labCtrl;
    private TodoPageController todoCtrl;
    /** 🆕 TASK-048：洞察页容器（本卡 = 区块骨架 + 5 分区空态；内容由 K4~K8/K10 填）。 */
    private InsightPageView insightPage;
    /**
     * 🆕 TASK-057（K11）：本记页「选书」弹层（半屏列表 + 搜索）。
     *
     * 只属于 App 阅读页（挂在 `mp_reader` 下）；桌面悬浮卡那条链路不装配它 ⇒ A6 桌面零影响。
     * 收起态 `GONE`；`onPickNote()` 里 {@code open(bookList, pickedBook)} 后置 `VISIBLE`。
     */
    private NotePickView notePick;
    /** 🆕 TASK-069：「本书」选书弹层（与 {@link #notePick} 同款模态半屏，但料不同） */
    private BookPickView bookPick;
    /** 🆕 TASK-051（K6）：年度首拉在途标记 —— 防止反复进洞察页时重复发请求（卡面 A4）。 */
    private boolean annualLoading;
    /** 🆕 TASK-052（K7）：累计首拉在途标记 —— 与 {@link #annualLoading} 同法（卡面 A5）。 */
    private boolean overallLoading;
    /** 🆕 TASK-059：全量书架 id 补拉在途标记 —— 防止反复进出排名页时重复发 `/shelf/sync`。 */
    private boolean shelfIdsLoading;
    /** 🆕 TASK-044：当前大标签下标（阅读/设置/实验室/待办）。 */
    private int mainPage = MP_READER;
    /**
     * 🆕 TASK-064：大标签**可见表**（UI 下标 → 逻辑页 {@code MP_*}）。
     *
     * <p>完整形态 = {@code {0,1,2,3}}（阅读/设置/实验室/待办）；正式版（实验室不可见）= {@code {0,1,3}}。
     * 由 {@link #rebuildMainTabKeep()} 填；{@code null} 时按完整形态兜底（防御）。
     */
    private int[] mMainTabKeep;
    /**
     * 🆕 TASK-044-R1：大标签整行**文案签名**（各段文案用 {@code |} 连接）——
     * 只在签名变化时重设，免无谓 invalidate。🆕 TASK-064：由"仅第 0 段文案"扩为整行（段数会变）。
     */
    private String lastMainTabSig;

    /**
     * 当前选项卡对应的形态：weekly（第 0 屏）/ monthly（第 1 屏）/ book（第 2 屏）。
     *
     * 「本书」不是周期 —— 它没有"上一个月"这种概念，所以下面的 {@link PeriodPickerView}
     * 在它这一屏会整条隐藏（{@link #showReaderPage()}）。
     */
    private String tabMode = PeriodRange.WEEKLY;
    /** 两个选项卡各自的周期锚点（periodStart，秒）。≤0 视为"当前周期" */
    private long anchorWeek;
    private long anchorMonth;

    /**
     * 最近一轮「本记」同步的结果状态（v0.5.3，R02）。
     * 只用来区分空态该说哪句话 —— 退避期内不能再发起同步，就得靠它说明"上次为什么没成"。
     */
    private int lastNoteState = NoteSync.STATE_OK;

    /** TASK-018：本机是否处于 phone 角色（手机端遥控器）—— 是则隐藏卡片相关 UI（A7）。 */
    private boolean remotePhone;

    /**
     * 🆕 TASK-031：本机被安装为「手机端」形态（控制面板）。
     *
     * <p>与 {@link #remotePhone} 是**两条轴**（App 形态 vs TCP 传输角色，见 `ADR-012`）：
     * 本形态下卡片 UI 根本不装配，`onResume`/`onPause` 需据此早退（否则拿到 null 卡片会 NPE）。
     * 置位后立即 {@code finish()} 并跳 {@link ConsoleActivity 遥控台}（V1.1.1-beta 起），
     * 所以它主要保护"即将销毁的这一帧"。
     */
    private boolean installPhone;

    /**
     * 阅读器端卡片 UI 是否已装配（{@link #setupReaderUi()} 跑完才置 true）。
     *
     * <p>用于保护生命周期回调：首装弹窗期间 / 手机端形态下 UI 未建，`onResume` 若照常执行
     * 会对 null 的 navDrop/card 抛 NPE（TASK-031 真机复现过）。
     */
    private boolean readerUiReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ── 🆕 TASK-031：安装角色 —— 首装引导 + 入口分流 ──
        // 🔴 首装判定必须放在**任何可能写 cfg 的动作之前**：本工程启动早期唯一可能写 cfg 的是
        //    UpdateChecker.autoCheck（它与 CardPrefs 共用 "cfg" prefs，写盘在后台线程 + 联网成功后）。
        //    先跑它再读 `isFreshInstall()` 会有竞态误判（改了 prefs ⇒ 被判成"非首装"）。
        final boolean firstRun =
                FeatureGate.rolePickVisible(this)
                        && !CardPrefs.isInstallRoleChosen(this) && CardPrefs.isFreshInstall(this);

        // v0.5.0：清掉上次没下完的更新残包；并静默查一次更新。
        // 静默检查每天至多一次，结果落盘 —— 用户进设置页自然看到「有新版本」，全程不弹窗。
        ApkInstaller.cleanupPartial(this);
        UpdateChecker.autoCheck(this);

        if (firstRun) {
            // 🔴 首装：**先把阅读器 UI 建好**，二选一弹窗叠在其上。
            //    为什么不能在弹窗定案后再建：onStart/onResume 会在 onCreate 返回后**立刻**跑
            //    （弹窗不阻塞生命周期），那时若 UI 未建，showReaderPage() 会对 null 的 navDrop 抛 NPE。
            //    先建好 ⇒ 选「阅读器」即刻可用；选「手机端」⇒ enterApp() 里立即转设置-实验室并 finish，
            //    那一瞬的阅读器 UI 无副作用（全新安装还没有 Key，refresh() 直接空转）。
            setupReaderUi();
            askInstallRole();
            maybeDeskForDebug();     // 🔴 TASK-075 临时入口（TASK-080 落真触发端后回收）
            return;
        }
        enterApp();
        applyNavExtra(getIntent());  // 🆕 TASK-079：跨包深链（须在临时调试钩子之前，深链优先）
        maybeDeskForDebug();         // 🔴 TASK-075 临时入口（TASK-080 落真触发端后回收）
    }

    /**
     * 🔴 **TASK-075 自测临时**（TASK-080 回收）：已在最前时 `am start` 走这条，
     * 让调试 extra 可重复驱动（不必每次重建 Activity）。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyNavExtra(intent);       // 🆕 TASK-079：深链（同一 Activity 复用时走这条）
        maybeDeskForDebug();
    }

    /**
     * 🆕 TASK-079：处理**跨包深链** —— 目前只有一条：墨台「壁纸管家 → 管理」，
     * 期望「打开大标签③ 实验室 + 切到壁纸管家子标签」（定稿设计 §5.5）。
     *
     * <p>🔴 键名走 {@link NavExtra}（{@code core}）：发起方在 {@code a11y}，本类在 {@code shell}，
     * 两边都只依赖 {@code core} ⇒ 不会形成反向依赖。未知 {@code lab_sub} 值**静默忽略**
     * （只定位到实验室页，不乱跳）。
     */
    private void applyNavExtra(Intent it) {
        if (it == null) return;
        String sub = it.getStringExtra(NavExtra.LAB_SUB);
        if (sub == null || sub.length() == 0) return;
        if (!FeatureGate.labVisible(this) || labCtrl == null) {
            CardDebug.note(this, "深链实验室被拒：本形态实验室不可见（sub=" + sub + "）");
            return;
        }
        showMainPage(MP_LAB);
        if (NavExtra.LAB_SUB_WALLPAPER.equals(sub)) {
            boolean ok = labCtrl.selectLabPage(R.id.page_lab_wallpaper);
            CardDebug.note(this, "深链实验室·壁纸管家 " + (ok ? "OK" : "FAIL(子标签未装配)"));
        }
    }

    /**
     * 🔴 **TASK-075/076 自测临时入口**（TASK-080 落"卡片左上角按钮"真触发端后**回收**）。
     *
     * <p>不产生任何 UI、不改布局 —— 只读启动 Intent 的调试 extra（adb 驱动）：
     * <ul>
     *   <li>{@code --ez wb_desk true} ⇒ 呼出墨台；{@code --ez wb_desk_hide true} ⇒ 关闭墨台</li>
     *   <li>{@code --es wb_desk_bg <路径>} ⇒ 先落盘墨台背景路径（验证 A2 自选图）</li>
     *   <li>{@code --ei wb_desk_veil <0..100>} ⇒ 落盘白纱不透明度</li>
     *   <li>🆕 TASK-076：{@code --ez wb_desk_enabled <bool>} / {@code --ei wb_desk_on_mask <int>} /
     *       {@code --es wb_desk_order <csv>} ⇒ 直接落盘模块总开关 / 开启掩码 / 顺序表
     *       （本卡验「顺序即渲染序、关掉不占高」用；这些键将来由设置页「墨台」分区负责）</li>
     * </ul>
     * 例：{@code adb shell am start -n com.inkread.weekread/.shell.MainActivity --ez wb_desk true}
     */
    private void maybeDeskForDebug() {
        Intent it = getIntent();
        if (it == null) return;
        if (it.hasExtra("wb_desk_bg")) {
            PagePrefs.setDeskBgPath(this, it.getStringExtra("wb_desk_bg"));
        }
        if (it.hasExtra("wb_desk_veil")) {
            PagePrefs.setDeskBgVeil(this, it.getIntExtra("wb_desk_veil", PagePrefs.DEFAULT_BG_VEIL));
        }
        // 🔴 TASK-076 临时：直接落盘模块配置（设置页「墨台」分区落码后由 UI 负责）
        if (it.hasExtra("wb_desk_enabled")) {
            PagePrefs.setDeskEnabled(this, it.getBooleanExtra("wb_desk_enabled", true));
        }
        if (it.hasExtra("wb_desk_on_mask")) {
            PagePrefs.setDeskOnMask(this, it.getIntExtra("wb_desk_on_mask", PagePrefs.DEFAULT_ON_MASK));
        }
        if (it.hasExtra("wb_desk_order")) {
            String csv = it.getStringExtra("wb_desk_order");
            PagePrefs.setDeskOrder(this, csv == null ? null : csv.split(","));
        }
        // 🔴 TASK-077 临时：直接落盘「阅读账单」的配置（13 项 + 视图/备注两个内部状态），
        //    并可**强制重建全部账单**。理由：设置页入口**不在本卡范围**
        //   （`tasks/TASK-077` §涉及文件未列 `SettingsPageController`）⇒ A7/A8/A9/空态/备注
        //    这几项只能靠这里驱动。TASK-080 回收临时钩子时连同本段一并删除。
        if (it.hasExtra("wb_menu")) {
            applyMenuDebug(it.getStringExtra("wb_menu"));
        }
        if (it.getBooleanExtra("wb_bill_regen", false)) {
            BillStore.clear(this);                     // 只清账单段（不动 api_key / 统计缓存）
            BillScheduler.ensureBills(this);           // 立刻按当前配置重建
        }
        // 🔴 TASK-078 临时：壁纸管家的**夹具 + 状态探针**（TASK-080 回收临时钩子时一并删除）。
        //    理由：壁纸池的"收图"入口在 TASK-079（实验室子标签）；本卡验 A1/A2/A3/A5 只能从这里驱动。
        //    收图源必须是**本进程可读**的路径（推荐先 run-as 推到自己 files/ 下再传绝对路径）。
        applyWallDebug(it);
        // 🔴 TASK-075 临时：模拟"从桌面呼出"——先 finish 本页（ownUi→false），再延后呼出墨台；
        //    TASK-080 落真触发端后连同本整段一并回收。
        if (it.getBooleanExtra("wb_desk_late", false)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    CardA11yService.showDesk();
                }
            }, 1200);
            finish();
            return;
        }
        if (it.getBooleanExtra("wb_desk_hide", false)) {
            CardA11yService.hideDesk();
        } else if (it.getBooleanExtra("wb_desk", false)) {
            CardA11yService.showDesk();
        }
    }

    /**
     * 🔴 **TASK-077 临时钩子**（TASK-080 回收）—— 把 `k=v;k=v` 落进 {@code MenuPrefs}。
     *
     * <p>支持键：{@code title / unit / top_n / min_min / drop_shelf / note_src / excerpt /
     * show_author / show_duration / show_progress / footer / blocks / title_serif / body_mono /
     * fs_title / fs_body / fs_serial / empty / view / footer_note / book_note}。
     * 未知键静默忽略（验收脚本写错不该把 App 弄崩）。
     */
    private void applyMenuDebug(String spec) {
        if (spec == null || spec.length() == 0) return;
        String[] kvs = spec.split(";");
        for (int i = 0; i < kvs.length; i++) {
            String kv = kvs[i];
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq).trim();
            String v = kv.substring(eq + 1).trim();
            if ("title".equals(k))              MenuPrefs.setTitle(this, v);
            else if ("unit".equals(k))          MenuPrefs.setUnit(this, v);
            else if ("top_n".equals(k))         MenuPrefs.setTopN(this, dInt(v, 5));
            else if ("min_min".equals(k))       MenuPrefs.setMinMinutes(this, dInt(v, 0));
            else if ("drop_shelf".equals(k))    MenuPrefs.setDropOffShelf(this, dBool(v));
            else if ("note_src".equals(k))      MenuPrefs.setNoteSrc(this, v);
            else if ("excerpt".equals(k))       MenuPrefs.setExcerptMode(this, v);
            else if ("show_author".equals(k))   MenuPrefs.setShowAuthor(this, dBool(v));
            else if ("show_duration".equals(k)) MenuPrefs.setShowDuration(this, dBool(v));
            else if ("show_progress".equals(k)) MenuPrefs.setShowProgress(this, dBool(v));
            else if ("footer".equals(k))        MenuPrefs.setFooter(this, v);
            else if ("blocks".equals(k))        MenuPrefs.setBlocks(this, dInt(v, MenuPrefs.BLOCK_ALL));
            else if ("title_serif".equals(k))   MenuPrefs.setTitleSerif(this, dBool(v));
            else if ("body_mono".equals(k))     MenuPrefs.setBodyMono(this, dBool(v));
            else if ("fs_title".equals(k))      MenuPrefs.setFsTitle(this, dInt(v, 1));
            else if ("fs_body".equals(k))       MenuPrefs.setFsBody(this, dInt(v, 1));
            else if ("fs_serial".equals(k))     MenuPrefs.setFsSerial(this, dInt(v, 1));
            else if ("empty".equals(k))         MenuPrefs.setEmptyAction(this, v);
            else if ("view".equals(k))          MenuPrefs.setView(this, dInt(v, 0));
            else if ("footer_note".equals(k))   MenuPrefs.setFooterNote(this, v);
            else if ("book_note".equals(k)) {
                int c2 = v.indexOf(':');        // `book_note=<bookId>:<备注>`
                if (c2 > 0) MenuPrefs.setNoteOf(this, v.substring(0, c2), v.substring(c2 + 1));
            }
        }
    }

    private static int dInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
    private static boolean dBool(String s) {
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    /**
     * 🔴 **TASK-078 临时钩子**（TASK-080 回收）—— 壁纸管家的夹具 + 状态探针。
     *
     * <p>支持键（全部可选）：
     * <ul>
     *   <li>{@code --es wb_wall_add <绝对路径>} ⇒ 收图进池（源须本进程可读；重复调用同一源**不会重复收**）</li>
     *   <li>{@code --ei wb_wall_interval <天>} ⇒ 轮换间隔（&le;0 = 关）</li>
     *   <li>{@code --es wb_wall_scope <soft-lock|dream|both>} ⇒ 应用范围</li>
     *   <li>{@code --ei wb_wall_index <n>} ⇒ 当前序号</li>
     *   <li>{@code --es wb_wall_last <yyyy-MM-dd>} ⇒ 上次轮换日期（验幂等时把日期改成"昨天"）</li>
     *   <li>{@code --ez wb_wall_rotate true} ⇒ 立即换一张</li>
     *   <li>{@code --ez wb_wall_clear true} ⇒ 清空池（不动间隔 / 范围）</li>
     *   <li>{@code --ez wb_wall_dump true} ⇒ 把当前状态写进 {@code card_debug.log}（免 run-as）</li>
     *   <li>{@code --ez wb_wall_effective true} ⇒ **显式触发一次** {@code effectiveSoftLockPath}
     *       （= 软锁取背景时的同一入口）并把返回值写进日志 —— 用于验 A2 幂等 / A3 循环而不必真出软锁</li>
     *   <li>{@code --es wb_lock_bg <路径>} ⇒ 落盘软锁背景路径（A4 像素对照的"底图"）</li>
     *   <li>{@code --es wb_lock_pin <4位>} ⇒ 启用软锁并设密码（**亮屏才会弹软锁** ⇒ A4 对照用）；
     *       {@code --ez wb_lock_off true} ⇒ 关软锁并清密码（**收尾必调**）</li>
     * </ul>
     */
    private void applyWallDebug(Intent it) {
        try {
            if (it.hasExtra("wb_lock_pin")) {
                String pin = it.getStringExtra("wb_lock_pin");
                boolean ok = LockPrefs.setPin(this, pin);
                LockPrefs.setEnabled(this, true);
                CardDebug.note(this, "wall: lock enable pin set=" + ok + " active=" + LockPrefs.isActive(this));
            }
            if (it.hasExtra("wb_lock_off") && it.getBooleanExtra("wb_lock_off", false)) {
                LockPrefs.setEnabled(this, false);
                LockPrefs.clearPin(this);
                LockPrefs.clearBg(this);            // 连背景路径一并清 ⇒ 键都不留（收尾还原用）
                CardDebug.note(this, "wall: lock disabled active=" + LockPrefs.isActive(this)
                        + " bg=" + LockPrefs.getBgPath(this));
            }
            if (it.hasExtra("wb_lock_bg")) {
                LockPrefs.setBgPath(this, it.getStringExtra("wb_lock_bg"));
                CardDebug.note(this, "wall: lock bg=" + it.getStringExtra("wb_lock_bg"));
            }
            if (it.hasExtra("wb_wall_clear") && it.getBooleanExtra("wb_wall_clear", false)) {
                int n = WallpaperPrefs.clear(this);
                CardDebug.note(this, "wall: clear 删除 " + n + " 张");
            }
            if (it.hasExtra("wb_wall_add")) {
                String p = it.getStringExtra("wb_wall_add");
                boolean ok = WallpaperPrefs.add(this, p);
                CardDebug.note(this, "wall: add " + (ok ? "OK" : "FAIL") + " src=" + p);
            }
            if (it.hasExtra("wb_wall_interval")) {
                WallpaperPrefs.setIntervalDays(this, it.getIntExtra("wb_wall_interval", WallpaperPrefs.DEFAULT_INTERVAL));
            }
            if (it.hasExtra("wb_wall_scope")) {
                WallpaperPrefs.setScope(this, it.getStringExtra("wb_wall_scope"));
            }
            if (it.hasExtra("wb_wall_index")) {
                WallpaperPrefs.setIndex(this, it.getIntExtra("wb_wall_index", 0));
            }
            if (it.hasExtra("wb_wall_last")) {
                WallpaperPrefs.setLastDate(this, it.getStringExtra("wb_wall_last"));
            }
            if (it.hasExtra("wb_wall_rotate") && it.getBooleanExtra("wb_wall_rotate", false)) {
                WallpaperPrefs.rotateNow(this);
            }
            if (it.hasExtra("wb_wall_dump") && it.getBooleanExtra("wb_wall_dump", false)) {
                java.util.List<String> p = WallpaperPrefs.pool(this);
                CardDebug.note(this, "wall: dump n=" + p.size()
                        + " index=" + WallpaperPrefs.index(this)
                        + " interval=" + WallpaperPrefs.intervalDays(this)
                        + " scope=" + WallpaperPrefs.scope(this)
                        + " last=" + WallpaperPrefs.lastDate(this));
                for (int i = 0; i < p.size(); i++) {
                    File f = new File(p.get(i));
                    CardDebug.note(this, "wall:   [" + i + "] " + p.get(i) + " bytes=" + f.length());
                }
            }
            if (it.hasExtra("wb_wall_effective") && it.getBooleanExtra("wb_wall_effective", false)) {
                String eff = WallpaperPrefs.effectiveSoftLockPath(this);
                String base = LockPrefs.getBgPath(this);
                CardDebug.note(this, "wall: effective=" + eff
                        + "  base=" + base
                        + "  same=" + (eff == null ? base == null : eff.equals(base))
                        + "  index=" + WallpaperPrefs.index(this)
                        + "  last=" + WallpaperPrefs.lastDate(this));
            }
        } catch (Throwable t) {
            CardDebug.note(this, "wall: debug 钩子异常 " + t);
        }
    }

    /**
     * 🆕 TASK-031：首次安装引导 —— 二选一「为手机端安装 / 为阅读器安装」。
     *
     * <p>只在「全新安装 + `install_role` 从未写入」时调用一次。**关掉不选 / 按返回**一律记
     * 「阅读器端」（= 默认值），确保不会因为用户没理弹窗而卡住。选完直接 {@link #enterApp()}。
     */
    private void askInstallRole() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.install_role_dialog_title)
                .setMessage(R.string.install_role_dialog_msg)
                .setCancelable(true)
                .setPositiveButton(R.string.install_role_pick_phone, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        CardPrefs.setInstallRole(MainActivity.this, CardPrefs.INSTALL_ROLE_PHONE);
                        enterApp();
                    }
                })
                .setNegativeButton(R.string.install_role_pick_reader, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        CardPrefs.setInstallRole(MainActivity.this, CardPrefs.INSTALL_ROLE_READER);
                        enterApp();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        CardPrefs.setInstallRole(MainActivity.this, CardPrefs.INSTALL_ROLE_READER);
                        enterApp();
                    }
                })
                .show();
    }

    /**
     * 🆕 TASK-031：入口分流 —— 按 `install_role` 决定"落地页"。
     *
     * <ul>
     *   <li><b>手机端</b>（`phone`）⇒ 立即转 {@link ConsoleActivity 遥控台} 并 {@code finish()}
     *       （纯控制面板，返回即退出）；置 {@link #installPhone} 让 onResume/onPause 早退
     *       （卡片 UI 根本没装配，防 NPE）。
     *       <p>🔴 V1.1.1-beta（TASK-037）改：落地页由「设置-实验室」升级为**独立遥控台**。</li>
     *   <li><b>阅读器端</b>（`reader`，默认）⇒ {@link #setupReaderUi()}，与现状**零差异**。</li>
     * </ul>
     */
    private void enterApp() {
        // 🆕 TASK-064：正式版（能力门关）**永不进手机端形态** —— 先把门加在这里（入口分流处），
        //   再加上 CardPrefs.getInstallRole 的读取门（存量 phone 值也拦），双保险。
        if (FeatureGate.remoteVisible(this)
                && CardPrefs.getInstallRole(this) == CardPrefs.INSTALL_ROLE_PHONE) {
            installPhone = true;
            Intent it = new Intent(this, ConsoleActivity.class);
            startActivity(it);
            finish();
            return;
        }
        // 阅读器端：装好 UI（首装路径已在 onCreate 里先建好，避免重复装配）
        if (!readerUiReady) setupReaderUi();
    }

    /**
     * 阅读器端落地页（= TASK-031 之前的 {@code onCreate} 主体，**内容零改动**）。
     * 手机端形态下**不调用**本方法 ⇒ 卡片 UI / 无障碍让位 / 取数整条不装配。
     */
    private void setupReaderUi() {
        card = (WeekCardView) findViewById(R.id.card);
        segMain = (SegTabView) findViewById(R.id.tab_main);
        navDrop = (NavDropView) findViewById(R.id.nav_drop);
        // 🆕 TASK-044-R1：扳机行搬到大标签① 本体 ⇒ 本控件退化为「只在展开时存在的浮层」，
        //    收起态高度 = 0（不再占那 40dp）。
        navDrop.setTriggerless(true);
        picker = (PeriodPickerView) findViewById(R.id.picker);
        insightPage = (InsightPageView) findViewById(R.id.insight);
        notePick = (NotePickView) findViewById(R.id.note_pick);
        bookPick = (BookPickView) findViewById(R.id.book_pick);        // 🆕 TASK-069
        pageReader = findViewById(R.id.mp_reader);
        pageSettings = findViewById(R.id.mp_settings);
        pageLab = findViewById(R.id.mp_lab);
        pageTodo = findViewById(R.id.mp_todo);

        // ── TASK-018：手机端遥控器角色（remote_role=phone）⇒ 隐藏卡片相关 UI（A7）──
        // 手机上这个 App 只当遥控器用，统计卡片没有使用场景（ADR-010 决定 4：
        // 「phone = 隐藏卡片相关 UI、不启用卡片悬浮服务」）。
        // 只保留「设置」入口（用户要在那里把角色改回来），其余卡片 UI 整块隐藏。
        if (FeatureGate.remoteVisible(this) && RemoteRole.from(this) == RemoteRole.PHONE) {
            remotePhone = true;
            applyPhoneMode();
            // 🆕 TASK-045：大标签栏与 ②③④ 内容**仍需装配** —— 原靠底部「设置」按钮回到设置页
            //   把角色改回来，该按钮已删 ⇒ 现在唯一入口就是大标签②，不装配就等于把用户锁死。
            wireMainTabs();
            setupMainPages();
            // 🆕 postreview（F-11）：对齐字段语义 —— 本分支 UI 确已装配完毕（走的是 phone 版）。
            //    当前无副作用（onResume/onPause 的 remotePhone 守卫先早退），防将来新增
            //    「仅当 readerUiReady 才做某事」的逻辑踩坑。
            readerUiReady = true;
            return;
        }

        // 本记页的「全部 / 只看想法」筛选（v0.4.4 是屏顶独占一行的页签，
        // v0.4.5 起并进卡片内的按钮行，由 WeekCardView 画成一格两态的「筛选」按钮）。
        // 切的是 NoteStore 的抽取档位 —— 桌面卡片仍走默认档，不受 App 里的浏览动作影响。

        // App 内是整屏，用"全屏大版"排版（桌面卡片仍用默认的紧凑档，两处互不影响）
        card.setFullscreen(true);

        // App 内「打开」按钮：同样跳微信读书阅读页（桌面卡片的 openView 走的是
        // CardA11yService.openBook，两边共用同一个深链 weread://reading?bId=）
        card.setOpenListener(new WeekCardView.OpenListener() {
            @Override
            public void onOpen() {
                // 「打开」跟着**屏幕上这一本**走（v0.5.3，R06）。
                // 上一版只读 BookStore.load：在"刚刷新完但还没落盘"的窗口里会去打开旧书，
                // 更早的版本干脆因为 load==null 直接返回（点了没反应）。
                BookStats b = displayedBook();
                if (b == null || b.bookId == null || b.bookId.length() == 0) return;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("weread://reading?bId=" + b.bookId)));
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onPickBook() {
                // 🆕 TASK-071：卡片**左下角**那个「选书」框 ⇒ 弹半屏候选列表
                //（TASK-069 的 {@link BookPickView} 弹层原样复用，只是换了触发点）。
                showBookPick();
            }
        });

        // 本记页的两个页内按钮（v0.4.1）：
        // 之前「换一条」在 App 内是个假按钮 —— WeekCardView.onTouchEvent 只认「本书」形态，
        // 本记态的触摸被整条放掉了。现在两个按钮都由 NoteListener 分发。
        card.setNoteListener(new WeekCardView.NoteListener() {
            @Override
            public void onNextNote() {
                // 手动插队抽下一条；空池时立即放行同步（清退避）
                if (!showNote(true)) noteSync(true);
            }

            @Override
            public void onPrevNote() {
                prevNote();                     // v0.4.2：沿来时的路退回上一条
            }

            @Override
            public void onExportNote() {
                exportNote();
            }

            @Override
            public void onToggleIdeas() {
                toggleIdeas();                  // v0.4.5：卡片最左那格「筛选」
            }

            @Override
            public void onPickNote() {
                showNotePick();                 // 🆕 TASK-057（K11）：底部「选书」格 ⇒ 弹半屏列表
            }
        });

        // ── 🆕 TASK-057（K11）：选书弹层（半屏列表 + 搜索）──
        // 选中 ⇒ 写 prefs（NoteStore.setPickedBook）后**重新抽一条**（showNote(false)），
        // 否则屏幕还停在旧随机池抽出来的那一条上，"选了没反应"。
        if (notePick != null) {
            notePick.setListener(new NotePickView.Listener() {
                @Override
                public void onPick(String bookId) {
                    chooseBook(bookId);
                }

                @Override
                public void onDismiss() {
                    hideNotePick();
                }
            });
        }

        // ── 🆕 TASK-069：「本书」手动选书（半屏弹层）──
        // 选中 ⇒ 写 CardPrefs#setBookPick 后**重新取一次本书**（force=true：立刻用新偏好走一遍
        // 书架→进度链）；选「自动」⇒ 写空串，行为与改造前一致。
        //
        // 🔴 TASK-071：触发改到**卡片左下角那个「选书」框**（WeekCardView 画、CardInteraction 判），
        //   见下面 openListener 的 onPickBook()。原来刷新行里的 `btn_book_pick` 已删除
        //   ⇒「刷新」恢复独占整行（= TASK-069 之前的版面）。
        if (bookPick != null) {
            bookPick.setListener(new BookPickView.Listener() {
                @Override
                public void onPick(String bookId) {
                    chooseBookPick(bookId);
                }

                @Override
                public void onDismiss() {
                    hideBookPick();
                }
            });
        }
        // 搜索框要能顶出软键盘：把窗口软键盘模式钉成 adjustResize —— 键盘弹出时阅读区被压缩，
        // 弹层（MATCH_PARENT）跟着变矮、搜索框始终留在键盘上方（adjustPan 会把整页顶出去）。
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        anchorWeek = PeriodRange.startOf(PeriodRange.WEEKLY, 0);
        anchorMonth = PeriodRange.startOf(PeriodRange.MONTHLY, 0);

        // 调试/导入入口：am start ... --es api_key wrk-xxx
        //
        // 🔴 v0.6.1（TASK-003 / R04）：**只在 dev 构建里接受**。
        // MainActivity 是 exported 的 Launcher（必须），任何本机应用都能 `am start` 它；
        // 发布包若无条件读这个 extra，就等于「**任何本机应用都能改写已存的 Key**」——
        // 27 位的 Key 在墨水屏上手打不现实，所以我们不能干脆删掉这条通道。
        // 闸门 = FLAG_DEBUGGABLE：发布包不带 android:debuggable ⇒ 恒 false ⇒ 入口关闭；
        // dev 包由 `bash tools/build.sh --dev` 加 aapt2 `--debug-mode` 打开（详见 tools/build.sh）。
        String k = getIntent() == null ? null : getIntent().getStringExtra("api_key");
        if (k != null && k.length() > 0 && isDebuggableBuild()) StatsStore.setKey(this, k);

        // ── 🆕 TASK-044：大标签栏（阅读 / 设置 / 实验室 / 待办）—— **只切页面容器**，不碰 tabMode ──
        wireMainTabs();

        // ── 🆕 TASK-044：阅读页下拉（本周 / 本月 / 本书 / 本记 / 洞察）—— **只切 tabMode**，不碰页面容器 ──
        navDrop.setLabels(new String[]{
                getString(R.string.nav_week), getString(R.string.nav_month),
                getString(R.string.nav_book), getString(R.string.nav_note),
                getString(R.string.nav_insight)});
        navDrop.setListener(new NavDropView.Listener() {
            @Override
            public void onPicked(int index) {
                tabMode = modeOf(index);
                showReaderPage();
                // 该形态本地还没有缓存（第一次点开本月 / 第一次点开本书 / 翻到没看过的历史周期）→ 顺手拉一次
                if (PeriodRange.BOOK.equals(tabMode)) {
                    if (BookStore.load(MainActivity.this) == null) refresh();
                } else if (PeriodRange.NOTE.equals(tabMode)) {
                    // 渲染落空才起同步 —— 绝不在渲染函数里发请求（v0.5.3，R02）
                    if (!showNote(false)) noteSync(false);
                } else if (PeriodRange.INSIGHT.equals(tabMode)) {
                    // 洞察页本卡是占位（内容由 TASK-048 填）⇒ 不取数
                } else if (StatsStore.load(MainActivity.this, tabMode, anchor()) == null) {
                    refresh();
                }
            }
        });

        picker.setListener(new PeriodPickerView.Listener() {
            @Override
            public void onShift(int delta) {
                long next = PeriodRange.shift(tabMode, anchor(), delta);
                long cur = PeriodRange.startOf(tabMode, 0);
                if (next > cur) next = cur;      // 不看未来（选择器那边也已把 ▶ 画成浅灰）
                setAnchor(next);
                showReaderPage();
                if (StatsStore.load(MainActivity.this, tabMode, next) == null) refresh();
            }
        });

        ((Button) findViewById(R.id.btn_refresh)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);                      // 手动点刷新 = 强制，书架缓存也允许更频繁地重拉
            }
        });

        // ── 🆕 TASK-045：②③④ 三大标签的内容由控制器装配（与独立页共用同一实现）──
        // 原 TASK-044 的三个「占位跳转按钮」与底部「设置」按钮**已随占位容器一起删除**。
        setupMainPages();

        readerUiReady = true;      // TASK-031：UI 装配完成，生命周期回调自此可安全跑 show()/refresh()
    }

    /**
     * 🆕 TASK-045：装配大标签栏（阅读 / 设置 / 实验室 / 待办）—— **只切页面容器，不碰 tabMode**。
     *
     * <p>抽成方法：正常阅读器路径与 {@code remote_role=phone} 路径**都要装**（后者已无底部
     * 「设置」按钮，大标签② 是其唯一回设置页的入口）。
     *
     * <p>🆕 TASK-044-R1：**第 0 段即下拉扳机** —— 显示成「当前界面名 + ▽」（如「本周▽」）。
     * 点击行为按「当前是否已在阅读页」分流：
     * <ul>
     *   <li>不在阅读页 ⇒ {@link #showMainPage(int) 进入阅读页}（不展开）；</li>
     *   <li>已在阅读页 ⇒ 展开 / 收起 {@link #navDrop} 浮层（{@link NavDropView#toggle()}）。</li>
     * </ul>
     * 其余三段仍是普通页面切换。
     */
    private void wireMainTabs() {
        rebuildMainTabKeep();       // 🆕 TASK-064：先按能力门定「可见表」（正式版 4 格 → 3 格）
        refreshMainTabLabels();
        segMain.setListener(new SegTabView.Listener() {
            @Override
            public void onSegSelected(int index) {
                final int page = mainTabPageOf(index);   // 🆕 TASK-064：UI 下标 → 逻辑页
                if (page == MP_READER) {
                    // 🔴 TASK-044-R1：本段是下拉扳机，不是普通页签。
                    //    （SegTabView 对"再点已选中段"也会回调 —— 这里靠 mainPage 自己判断分流。）
                    if (mainPage == MP_READER && !remotePhone) {
                        hideNotePick();      // 🆕 TASK-057：别让下拉浮层跟选书弹层叠在一起
                        navDrop.toggle();
                        return;
                    }
                    showMainPage(MP_READER);
                    return;
                }
                showMainPage(page);
            }
        });
    }

    /**
     * 🆕 TASK-064：按 {@link FeatureGate#labVisible} 重建大标签**可见表**（UI 下标 → 逻辑页）。
     *
     * <p>完整形态（Beta）= {@code {阅读, 设置, 实验室, 待办}}（4 格，与改造前逐项一致）；
     * 正式版（实验室不可见）= {@code {阅读, 设置, 待办}}（3 格）。
     * 结果写回 {@link #mMainTabKeep}，供 {@link #mainTabPageOf(int)} / {@link #mainTabIndexOf(int)} 互查。
     */
    private void rebuildMainTabKeep() {
        final int[] all = {MP_READER, MP_SETTINGS, MP_LAB, MP_TODO};
        final boolean[] show = {true, true, FeatureGate.labVisible(this), true};
        final int[] keep = new int[all.length];
        int n = 0;
        for (int i = 0; i < all.length; i++) if (show[i]) keep[n++] = all[i];
        final int[] trimmed = new int[n];
        System.arraycopy(keep, 0, trimmed, 0, n);
        mMainTabKeep = trimmed;
    }

    /** 🆕 TASK-064：大标签 UI 下标 → 逻辑页（越界 / 未建表一律退回阅读页）。 */
    private int mainTabPageOf(int index) {
        final int[] keep = mMainTabKeep;
        if (keep == null || index < 0 || index >= keep.length) return MP_READER;
        return keep[index];
    }

    /** 🆕 TASK-064：逻辑页 → 大标签 UI 下标（找不到退回 0）。 */
    private int mainTabIndexOf(int page) {
        final int[] keep = mMainTabKeep;
        if (keep != null) {
            for (int i = 0; i < keep.length; i++) if (keep[i] == page) return i;
        }
        return 0;
    }

    /**
     * 🆕 TASK-044-R1：刷新大标签栏文案 —— 第 0 段 = 「当前形态名 + ▽」（下拉扳机），
     * 其余段仍是页面名。**形态切换后必须重调**，否则标签上还写着上一形态。
     *
     * <p>🆕 TASK-064：段数与内容改按**可见表**（{@link #mMainTabKeep}）生成 —— 正式版
     * 无「实验室」段（4 格 → 3 格），且段数变化时也要重设。
     *
     * <p>只在整行文案签名真变时 {@code setLabels}（后者内部会 invalidate）。
     */
    private void refreshMainTabLabels() {
        if (segMain == null) return;
        final int[] keep = (mMainTabKeep != null)
                ? mMainTabKeep : new int[]{MP_READER, MP_SETTINGS, MP_LAB, MP_TODO};
        final String readerLabel = getString(navLabelResOf(indexOf(tabMode))) + "▽";
        final String[] labels = new String[keep.length];
        final StringBuilder sig = new StringBuilder(readerLabel);
        for (int i = 0; i < keep.length; i++) {
            final int page = keep[i];
            final String s;
            if (page == MP_READER) s = readerLabel;
            else if (page == MP_SETTINGS) s = getString(R.string.main_tab_settings);
            else if (page == MP_LAB) s = getString(R.string.main_tab_lab);
            else s = getString(R.string.main_tab_todo);
            labels[i] = s;
            sig.append('|').append(s);
        }
        final String signature = sig.toString();
        if (signature.equals(lastMainTabSig)) return;
        lastMainTabSig = signature;
        segMain.setLabels(labels);
    }

    /** 下拉下标 → 字符串资源（大标签① 上显示的那个形态名）。与 {@link #modeOf(int)} 一一对应。 */
    private static int navLabelResOf(int idx) {
        if (idx == 1) return R.string.nav_month;
        if (idx == 2) return R.string.nav_book;
        if (idx == 3) return R.string.nav_note;
        if (idx == 4) return R.string.nav_insight;
        return R.string.nav_week;
    }

    /**
     * 🆕 TASK-045：装配「设置 / 实验室 / 待办」三个大标签的内容。
     *
     * <p>三块内容由 **与 {@link SettingsActivity} / {@link TodoActivity} 共用的控制器**驱动
     * （{@link SettingsPageController} / {@link LabPageController} / {@link TodoPageController}）⇒
     * 逻辑只有一份，不会双份维护。
     *
     * <p>🔴 顶部子页签的分工：
     * <ul>
     *   <li>设置页内的「初始化 | 自定义」段（{@code seg}）由**本类**装配 —— 因为
     *       实验室已是**独立大标签**，不再参与这条子页签（reader 端 2 段，而非 3 段）；</li>
     *   <li>实验室内的 3 段（{@code seg_lab}，🆕 TASK-072 起「翻页 | 锁屏密码 | 续航优化」）
     *       由 {@link LabPageController#bindLabTabs()} 装配；</li>
     *   <li>待办内的 2 段（{@code seg_todo}）由 {@link TodoPageController#bind()} 装配。</li>
     * </ul>
     */
    private void setupMainPages() {
        // ② 设置（page_init + page_custom）
        settingsCtrl = new SettingsPageController(this, new SettingsPageController.Listener() {
            @Override
            public void onInstallRoleChanged() {
                // 本页内不需要重装顶部页签 / 实验室子标签（那些是独立页的职责）；
                // 只需重算设置页自身的分区显隐。
                settingsCtrl.refreshRoleVisibility();
            }

            @Override
            public void onKeySaved() {
                // App 内嵌页不能 finish()：切回阅读页并按新 Key 强制取数
                showMainPage(MP_READER);
                refresh(true);
            }
        });
        settingsCtrl.bind();

        // 设置页子页签（初始化 | 自定义）—— reader 端仅 2 段（实验室已独立成大标签）
        segSettings = (SegTabView) findViewById(R.id.seg);
        segSettings.setLabels(new String[]{
                getString(R.string.tab_init), getString(R.string.tab_custom)});
        segSettings.setListener(new SegTabView.Listener() {
            @Override
            public void onSegSelected(int index) {
                showSettingsPage(index);
            }
        });

        // ③ 实验室（page_lab + seg_lab + 4 子容器）
        // 🆕 TASK-064：**仅完整形态装配** —— 正式版（labVisible=false）不 new 控制器、不 bind、
        //   不注册任何 HID / 蓝牙 / 会话监听（`labCtrl` 保持 null，生命周期回调已全部 null 安全）。
        if (FeatureGate.labVisible(this)) {
            labCtrl = new LabPageController(this);
            labCtrl.bind();
            labCtrl.bindLabTabs();
        }

        // ④ 待办（seg_todo + 列表 + 底部按钮）
        todoCtrl = new TodoPageController(this);
        todoCtrl.bind();
    }

    /**
     * 🆕 TASK-045：设置页内的「初始化 / 自定义」切换（只切 visibility + 回到页顶）。
     * 实验室是独立大标签，不在此列表内。
     */
    private void showSettingsPage(int index) {
        int orig = (index == 1) ? 1 : 0;
        findViewById(R.id.page_init).setVisibility(orig == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.page_custom).setVisibility(orig == 1 ? View.VISIBLE : View.GONE);
        // 切页回到顶部：各页高度不同，留着旧滚动位置会看着像"卡住了"
        View sv = findViewById(R.id.sv_settings);
        if (sv instanceof android.widget.ScrollView) ((android.widget.ScrollView) sv).scrollTo(0, 0);
        if (settingsCtrl != null) settingsCtrl.refreshRoleVisibility();
    }

    /**
     * 下拉下标 → 形态（🆕 TASK-044：扩到 6 个界面）。
     * 0=本周 1=本月 2=本书 3=本记 4=洞察。
     * ⚠️「待办」不再是阅读页形态 —— 它已独立为大标签④（见 {@link #showMainPage(int)}）。
     */
    private static String modeOf(int index) {
        if (index == 1) return PeriodRange.MONTHLY;
        if (index == 2) return PeriodRange.BOOK;
        if (index == 3) return PeriodRange.NOTE;
        if (index == 4) return PeriodRange.INSIGHT;
        return PeriodRange.WEEKLY;
    }

    /** 形态 → 下拉下标（🔴 被 {@link #showReaderPage()} 调用，改错会白屏）。 */
    private static int indexOf(String mode) {
        if (PeriodRange.MONTHLY.equals(mode)) return 1;
        if (PeriodRange.BOOK.equals(mode)) return 2;
        if (PeriodRange.NOTE.equals(mode)) return 3;
        if (PeriodRange.INSIGHT.equals(mode)) return 4;
        return 0;
    }

    /**
     * 「打开」按钮该打开哪本书（v0.5.3，R06）：
     * **屏幕上正在显示的那本**优先，退回的是落盘缓存（防止"还没刷新过就直接点"）。
     */
    private BookStats displayedBook() {
        BookStats b = card.getBook();
        if (b != null && b.bookId != null && b.bookId.length() > 0) return b;
        return BookStore.load(this);
    }

    /** 当前选项卡的周期锚点（≤0 时回落到"当前周期"）。「本书」没有周期，恒为 0 */
    private long anchor() {
        if (PeriodRange.BOOK.equals(tabMode)) return 0L;
        long a = PeriodRange.MONTHLY.equals(tabMode) ? anchorMonth : anchorWeek;
        return a > 0 ? a : PeriodRange.startOf(tabMode, 0);
    }

    private void setAnchor(long v) {
        if (PeriodRange.MONTHLY.equals(tabMode)) anchorMonth = v;
        else anchorWeek = v;
    }

    /**
     * 本包是不是 debuggable 构建（v0.6.1，TASK-003 / R04）。
     *
     * <p>用途**单一**：给上面那条 `am start … --es api_key` 调试入口当闸门。
     * <ul>
     *   <li><b>发布包</b> —— manifest 里没有 {@code android:debuggable} ⇒ 恒 {@code false} ⇒ 入口关闭；</li>
     *   <li><b>dev 包</b> —— {@code tools/build.sh --dev} 给 aapt2 传 {@code --debug-mode}，
     *       manifest 被插入 {@code android:debuggable="true"} ⇒ {@code true} ⇒ 入口可用。</li>
     * </ul>
     *
     * <p>🔴 定位要说清：这是**访问控制**，不是"安全边界"。它挡的是"任何本机应用**随手**就能改 Key"，
     * 挡不住"有 root / 能跑 adb 的人"。真正的防护是**发布包根本不带这个能力**。
     */
    private boolean isDebuggableBuild() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /**
     * 把「本月呈现 = 打卡 / 热力图」偏好塞给卡片（TASK-014）。
     *
     * 与桌面卡片侧 {@code CardContentController.applyMonthStyle} 同款口径：
     * 只读本地 prefs，渲染端不读 prefs。App 主页与桌面卡片**共用同一个 WeekCardView 类**，
     * 但设内容走的是两条路径 —— 所以这里必须**各自补一次**，否则两者观感会不一致。
     */
    private void applyMonthStyle() {
        if (card == null) return;
        card.setMonthHeatmap(
                CardPrefs.getMonthStyle(this) == CardPrefs.MONTH_STYLE_HEATMAP);
    }

    /**
     * 🆕 TASK-044：**大标签层** —— 切页面容器 visibility，**不碰 {@link #tabMode}**。
     *
     * <p>进入「阅读」时按当前 {@code tabMode} 重放一帧；离开阅读页时收起并隐藏下拉浮层
     * （下拉只在阅读页有意义）。两层互不干扰：切大标签不会重置下拉选择。
     */
    private void showMainPage(int index) {
        if (index < 0 || index > MP_TODO) index = MP_READER;
        // 🆕 TASK-064：正式版无「实验室」标签 ⇒ 该页不可达（越界请求一律回落阅读页）。
        if (index == MP_LAB && !FeatureGate.labVisible(this)) index = MP_READER;
        mainPage = index;
        segMain.setSelected(mainTabIndexOf(index));   // 🆕 TASK-064：逻辑页 → UI 下标（走可见表）
        pageReader.setVisibility(index == MP_READER ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(index == MP_SETTINGS ? View.VISIBLE : View.GONE);
        pageLab.setVisibility(index == MP_LAB ? View.VISIBLE : View.GONE);
        pageTodo.setVisibility(index == MP_TODO ? View.VISIBLE : View.GONE);
        if (index == MP_READER) {
            navDrop.setVisibility(View.VISIBLE);
            showReaderPage();              // 回到阅读页 ⇒ 按当前 tabMode 重放一帧
        } else {
            navDrop.collapse();
            navDrop.setVisibility(View.GONE);
        }
        // 🆕 TASK-045：进入非阅读页时按需刷新（内容由控制器驱动）——
        // 数据可能在别处变过（如控制台改过角色 / 遥控服务状态变化），进页复核一次。
        if (index == MP_SETTINGS) {
            showSettingsPage(0);                       // 复位到「初始化」+ 重算分区显隐
        } else if (index == MP_LAB) {
            if (labCtrl != null) {
                labCtrl.refreshRoleUi();               // 角色说明 / 会话控件 / 晃动块
                labCtrl.refreshBtUi();                 // 蓝牙状态行（读服务真值）
            }
        } else if (index == MP_TODO) {
            if (todoCtrl != null) todoCtrl.refresh();  // 待办列表重画
        }
    }

    /**
     * 🆕 TASK-044：**下拉层** —— 按当前 {@link #tabMode} + 锚点渲染阅读页一帧
     * （缓存里没有就走空态，不会画错数据）。内容即改造前的 {@code show()}，
     * **只把 {@code tabbar.setSelected} 换成 {@code navDrop.setSelected}**，其余取数/渲染零改动。
     */
    private void showReaderPage() {
        // 🔴 postreview-TASK-044/045（审查 F-1）：`remote_role=phone` 形态下**本方法一律空转**。
        //    phone 形态的阅读页只剩「遥控提示行」（`applyPhoneMode()` 已把 card/picker/btn_refresh
        //    置 GONE）；若放行，下面 `card`/`picker` 的 `setVisibility(VISIBLE)` 会把它们**重新显示**
        //    （审查复现的回归：点大标签① 或设置页存 Key 都会走到这里）。
        //    修法取审查首选：在此一刀切早退 —— 它是本方法**所有**调用点的公共入口。
        if (remotePhone) return;

        // 🆕 TASK-057（K11）：任何页面切换（大标签 / 下拉形态）都先收起选书弹层 ——
        // 它是模态，留着会盖在别的形态上。放在这里 = 所有导航的公共入口，不会漏路径。
        hideNotePick();
        hideBookPick();             // 🆕 TASK-069：同理收起「本书」选书弹层

        navDrop.setSelected(indexOf(tabMode));
        refreshMainTabLabels();     // 🆕 TASK-044-R1：大标签① 文案跟随形态（「本周▽」→「本月▽」…）

        // 洞察页（🆕 TASK-048 K3）：没有周期、不画卡片 —— 换成自绘滚动容器
        // （5 分区骨架 + 空态；各分区内容由 K4~K8/K10 陆续填）
        if (PeriodRange.INSIGHT.equals(tabMode)) {
            picker.setVisibility(View.GONE);
            card.setVisibility(View.GONE);
            insightPage.setVisibility(View.VISIBLE);
            bindInsightAnnual();             // 🆕 TASK-051 K6：把「年度视图」的缓存喂进去
            bindInsightOverall();            // 🆕 TASK-052 K7：把「累计视图」的缓存喂进去
            bindInsightProfile();            // 🆕 TASK-049 K4 / TASK-050 K5：把「画像」三件套的料喂进去
            ensureAnnualLoaded();            // 🆕 TASK-051 K6：年度缓存缺 ⇒ 触发一次拉取
            ensureOverallLoaded();           // 🆕 TASK-052 K7：累计缓存缺 ⇒ 触发一次拉取
            ensureShelfIdsLoaded();          // 🆕 TASK-059：排行过滤要的全量书架缺 ⇒ 触发一次补拉
            insightPage.resetScroll();       // 每次进入都从顶部看起
            // 🆕 postreview（F-2）：洞察页无数据可取 ⇒ 刷新键是「可见但无反应」的死键，一并隐藏。
            findViewById(R.id.btn_refresh).setVisibility(View.GONE);
            return;
        }
        // 🆕 postreview（F-2）：非洞察形态恢复刷新键（原先从不被本方法触碰 ⇒ 等价于恒 VISIBLE）。
        findViewById(R.id.btn_refresh).setVisibility(View.VISIBLE);
        card.setVisibility(View.VISIBLE);
        insightPage.setVisibility(View.GONE);

        card.setMode(tabMode);
        applyMonthStyle();       // 本月呈现（打卡/热力图）随偏好刷新（TASK-014）

        // 「全部 / 只看想法」不再独占屏顶一行（v0.4.5）—— 它是卡片内按钮行最左边那一格，
        // 由 WeekCardView 自己画，这里只需要把状态同步给它（进度行与筛选格都读这个标记）
        boolean isNote = PeriodRange.NOTE.equals(tabMode);
        card.setNoteSlot(NoteStore.slotFor(this));

        if (PeriodRange.BOOK.equals(tabMode)) {
            // 「本书」没有周期可选 —— 步进选择器整条收掉，把高度让给进度条
            picker.setVisibility(View.GONE);
            BookStats b = BookStore.load(this);
            card.setBook(b);
            loadCover(b);
            return;
        }
        if (isNote) {
            // 「本记」同样没有周期：位置让给「全部 / 只看想法」。
            // 导出与换一条都是**页内按钮**（画在卡片里），底部只留刷新与设置
            picker.setVisibility(View.GONE);
            if (!showNote(false)) noteSync(false);
            return;
        }
        picker.setVisibility(View.VISIBLE);
        long a = anchor();
        PeriodStats st = StatsStore.load(this, tabMode, a);
        card.setStats(st, emptyNote(tabMode, a, st));
        picker.setPeriod(tabMode, a);
        // 🆕 TASK-059：本月页有排名区 ⇒ 开关开着且缺全量书架时补拉一次（周页无排名，不发）
        if (PeriodRange.MONTHLY.equals(tabMode)) ensureShelfIdsLoaded();
    }

    // ══════════════ 🆕 TASK-049（K4）+ TASK-050（K5）：洞察页「画像」三件套的取数 ══════════════

    /**
     * 口径链：**累计 → 年度 → 本月 → 本周**，取第一个「画像三件套里有任一料」的档
     * （判据 {@link InsightPageView#hasProfile}）。
     *
     * <p>为什么是这个顺序（实测依据，见 `验证记录/159`、`验证记录/161`）：
     * · `preferCategory` 按 `parentCategoryTitle` 收拢后，本账号各档分别只有
     *   **累计 5 类 / 年度 4 类 / 本月 2 类 / 本周 0 类**（上游周回包根本没有该字段）；
     * · `preferAuthor` **仅年度/累计**有；`preferTime` **仅累计**有（周/月都没有）。
     * ⇒ 单用周/月这一格常年近乎空白，所以**优先用最全的档**（累计最全）。
     *
     * <p>🔴 **只读缓存，零新增网络请求** —— 年度/累计的**首次拉取**归
     * {@code TASK-051} / {@code TASK-052}（本卡不越界）。它们落码后本方法**无需改动**
     * 就自动变丰富（链路取到的档自然前移）。
     */
    private void bindInsightProfile() {
        // 🆕 TASK-055（K10）：画像判定要的两个**非偏好类**量 —— 与口径链无关，先各自算好：
        //   · 想法字数：来自本记索引（`TASK-056`，🔴 零新增网络请求，只读本地索引）；
        //   · 累计年数：取「累计」档的 `registTime`，**固定用累计档**（与画像口径链无关，
        //     年份本就是"陪你多久"这种跨周期量，换档不该变）。
        int noteChars = NoteStore.totalIdeaChars(this);
        int years = yearsOf(StatsStore.loadOverall(this));

        String[] chain = { PeriodRange.OVERALL, PeriodRange.ANNUALLY,
                           PeriodRange.MONTHLY, PeriodRange.WEEKLY };
        for (int i = 0; i < chain.length; i++) {
            PeriodStats st = loadProfileScope(chain[i]);
            if (InsightPageView.hasProfile(st)) {
                insightPage.setProfile(st.preferCategory, st.preferAuthor,
                        st.preferTime, scopeWordOf(chain[i]), noteChars, years);
                CardDebug.note(this, "insight profile: scope=" + chain[i]
                        + " cats=" + (st.preferCategory == null ? 0 : st.preferCategory.size())
                        + " authors=" + (st.preferAuthor == null ? 0 : st.preferAuthor.size())
                        + " timeMax=" + maxSec(st.preferTime)
                        + " noteChars=" + noteChars + " years=" + years);
                return;
            }
        }
        // 全档都无偏好料 ⇒ 三件套空态；但判定块仍可凭 noteChars / years 出结果
        insightPage.setProfile(null, null, null, null, noteChars, years);
        CardDebug.note(this, "insight profile: 偏好三件套空态（四档缓存都没有偏好数据）"
                + " noteChars=" + noteChars + " years=" + years);
    }

    /**
     * 注册年数（`registTime` 手算；口径与 `InsightRenderer.yearsWithYouText` **一致**）。
     *
     * @return 年数（≥0；0 = 注册于今年）；**-1 = 未知**（无累计缓存 / 回包未带 `registTime`）
     */
    private static int yearsOf(PeriodStats st) {
        if (st == null || st.registTimeMs <= 0) return -1;
        int n = PeriodRange.yearOf(0) - PeriodRange.yearOf(st.registTimeMs / 1000L);
        return n > 0 ? n : 0;
    }

    /** 24 桶里的最大秒数（0 = 无时段数据）—— 仅用于日志。 */
    private static int maxSec(int[] a) {
        int mx = 0;
        if (a == null) return 0;
        for (int i = 0; i < a.length; i++) if (a[i] > mx) mx = a[i];
        return mx;
    }

    /** 读某一档的缓存：累计 → `loadOverall`；年度 → `loadAnnual(今年)`；其余 → 当前周期缓存。 */
    private PeriodStats loadProfileScope(String mode) {
        if (PeriodRange.OVERALL.equals(mode)) return StatsStore.loadOverall(this);
        if (PeriodRange.ANNUALLY.equals(mode)) {
            // 🔴 `PeriodRange.startOf` 只认周/月 ⇒ 年度必须走 `yearOf`（0 = 现在所在年）
            return StatsStore.loadAnnual(this, PeriodRange.yearOf(0));
        }
        return StatsStore.loadCurrent(this, mode);
    }

    /** 兴趣雷达的口径范围词（🔴 必须带 —— 同 TASK-047 摘要行的教训：不同档的数不可混读）。 */
    private static String scopeWordOf(String mode) {
        if (PeriodRange.OVERALL.equals(mode)) return "累计";
        if (PeriodRange.ANNUALLY.equals(mode)) return "今年";
        if (PeriodRange.MONTHLY.equals(mode)) return "本月";
        return "本周";
    }

    // ══════════════ 🆕 TASK-051（K6）：洞察页「年度视图」的取数 ══════════════

    /** 把「今年」的年度缓存喂给分区②（没有 ⇒ 空态）。 */
    private void bindInsightAnnual() {
        PeriodStats st = StatsStore.loadAnnual(this, PeriodRange.yearOf(0));
        insightPage.setAnnual(st);
        CardDebug.note(this, st == null
                ? "insight annual: 空态（无年度缓存）"
                : "insight annual: year=" + PeriodRange.yearOf(0) + " total=" + st.totalSec);
    }

    /**
     * 🔴 本卡**唯一的真机项**：年度缓存缺失 ⇒ 触发**一次** `mode=annually` 拉取。
     *
     * <p>缓存命中 / 在途 / 无 Key ⇒ 直接返回（**零请求**）。成功后落 `StatsStore.saveAnnual`，
     * 重绑分区②，并**重绑兴趣雷达** —— 年度到货后 K4 的口径链（累计→年度→本月→本周）
     * 可能前移（年度比本月更全），§⑤ 会随之更新。
     *
     * <p>⚠️ `annually` **只返 `baseTime` 所在自然年** ⇒ 必须传「**当年 1 月 1 日**」
     * （{@link PeriodRange#yearStartOf}）；传 0 会落到服务端的默认年（去年）。
     */
    private void ensureAnnualLoaded() {
        final int year = PeriodRange.yearOf(0);
        if (StatsStore.loadAnnual(this, year) != null) return;   // 命中 ⇒ 零请求（卡面 A5）
        if (annualLoading) return;                               // 在途 ⇒ 不重复发（卡面 A4）
        final String key = StatsStore.getKey(this);
        if (key.length() == 0) return;                           // 无 Key ⇒ 无从拉（等设置页存 Key）
        annualLoading = true;
        final long gen = StatsStore.keyGen();                    // R05：换 Key ⇒ 丢弃迟到结果
        CardDebug.note(this, "insight annual: 发起拉取 year=" + year
                + " baseTime=" + PeriodRange.yearStartOf(year));
        WereadApi.fetchDetail(key, PeriodRange.ANNUALLY, PeriodRange.yearStartOf(year),
                new WereadApi.Callback() {
                    @Override
                    public void onResult(PeriodStats stats, String rawJson, String error) {
                        annualLoading = false;
                        if (gen != StatsStore.keyGen()) return;      // 换过 Key ⇒ 丢弃
                        if (error != null || stats == null) {
                            CardDebug.note(MainActivity.this, "insight annual: 拉取失败 " + error);
                            return;
                        }
                        StatsStore.saveAnnual(MainActivity.this, stats);
                        bindInsightAnnual();          // 年度分区换真实数据
                        bindInsightProfile();         // 画像口径可能前移（见方法注释）
                        CardDebug.note(MainActivity.this, "insight annual: fetched OK total="
                                + stats.totalSec);
                    }
                });
    }

    // ══════════════ 🆕 TASK-052（K7）：洞察页「累计视图」的取数 ══════════════

    /** 把「累计」缓存喂给分区③（没有 ⇒ 空态）。🆕 TASK-055：顺带喂「想法 N 字」（A5 的第二展示位）。 */
    private void bindInsightOverall() {
        PeriodStats st = StatsStore.loadOverall(this);
        int noteChars = NoteStore.totalIdeaChars(this);
        insightPage.setOverall(st, noteChars);
        CardDebug.note(this, st == null
                ? "insight overall: 空态（无累计缓存）noteChars=" + noteChars
                : "insight overall: total=" + st.totalSec + " readDays=" + st.readDays
                  + " medals=" + (st.medals == null ? 0 : st.medals.size())
                  + " regMs=" + st.registTimeMs + " noteChars=" + noteChars);
    }

    /**
     * 🔴 本卡**唯一的真机项**：累计缓存缺失 ⇒ 触发**一次** `mode=overall` 拉取。
     *
     * <p>缓存命中 / 在途 / 无 Key ⇒ 直接返回（**零请求**）。成功后落 {@link StatsStore#saveOverall}，
     * 重绑分区③，并**重绑兴趣雷达** —— 累计是 K4 口径链（**累计**→年度→本月→本周）的**最全档**，
     * 到货后 §⑤ 会从「今年」前移到「累计」，条数与百分比随之更新（同 TASK-051 年度到货的处理）。
     *
     * <p>⚠️ `overall` **无周期概念** ⇒ `baseTime` 固定传 **0**（服务端忽略该值）。
     */
    private void ensureOverallLoaded() {
        if (StatsStore.loadOverall(this) != null) return;       // 命中 ⇒ 零请求（卡面 A6）
        if (overallLoading) return;                             // 在途 ⇒ 不重复发
        final String key = StatsStore.getKey(this);
        if (key.length() == 0) return;                          // 无 Key ⇒ 无从拉（等设置页存 Key）
        overallLoading = true;
        final long gen = StatsStore.keyGen();                   // R05：换 Key ⇒ 丢弃迟到结果
        CardDebug.note(this, "insight overall: 发起拉取 baseTime=0");
        WereadApi.fetchDetail(key, PeriodRange.OVERALL, 0L,
                new WereadApi.Callback() {
                    @Override
                    public void onResult(PeriodStats stats, String rawJson, String error) {
                        overallLoading = false;
                        if (gen != StatsStore.keyGen()) return;      // 换过 Key ⇒ 丢弃
                        if (error != null || stats == null) {
                            CardDebug.note(MainActivity.this, "insight overall: 拉取失败 " + error);
                            return;
                        }
                        StatsStore.saveOverall(MainActivity.this, stats);
                        bindInsightOverall();         // 累计分区换真实数据
                        bindInsightProfile();         // 画像口径前移到累计档（见方法注释）
                        CardDebug.note(MainActivity.this, "insight overall: fetched OK total="
                                + stats.totalSec);
                    }
                });
    }

    // ══════════════ 🆕 TASK-059：书籍排名「只统计书架上的书」的全量书架补拉 ══════════════

    /**
     * 开关打开、但本地还没有**全量书架清单**时，补拉一次 `/shelf/sync`（用户拍板：**需要时自动补拉**）。
     *
     * <p>为什么必须补拉而不是就地过滤：{@link BookStore#shelf}（"最近 200 本"）**不能**用作全时段过滤
     * （会把老书误删）；过滤所需的全量 id 只在 {@link BookStore#saveShelfIds} 落过盘之后才有。
     * 之前如果没进过「本书」页，这份清单就还不存在 ⇒ 这里补一次（≈462KB，一次性）。
     *
     * <p>四道门（任一命中即**零请求**返回，与 {@code ensureAnnualLoaded} 同法）：
     * ① 开关关；② 清单已存在；③ 在途；④ 无 Key。
     * 成功后**重放当前阅读页一帧**，让过滤立即生效。
     */
    private void ensureShelfIdsLoaded() {
        if (!CardPrefs.isRankShelfOnly(this)) return;      // ① 开关关 ⇒ 零请求
        if (BookStore.hasShelfIds(this)) return;           // ② 已有全量清单 ⇒ 零请求
        if (shelfIdsLoading) return;                       // ③ 在途 ⇒ 不重复发
        final String key = StatsStore.getKey(this);
        if (key.length() == 0) return;                     // ④ 无 Key ⇒ 无从拉（等设置页存 Key）
        shelfIdsLoading = true;
        final String modeAt = tabMode;                     // 只在还停在同一形态时重绘
        final long gen = StatsStore.keyGen();              // R05：换 Key ⇒ 丢弃迟到结果
        CardDebug.note(this, "rank shelf: 发起 /shelf/sync 补拉（全量书架 id 缺失）mode=" + modeAt);
        WereadApi.fetchShelfIds(this, key, new WereadApi.DoneCallback() {
            @Override
            public void onDone(boolean ok) {
                shelfIdsLoading = false;
                if (gen != StatsStore.keyGen()) return;     // 换过 Key ⇒ 丢弃
                CardDebug.note(MainActivity.this, "rank shelf: 补拉完成 ok=" + ok);
                if (ok && modeAt.equals(tabMode)) showReaderPage();   // 过滤生效（重放一帧）
            }
        });
    }

    /**
     * 历史周期没数据时的提示文案（设计方案 §6.4）。返回 null = 照常画图。
     *
     * 为什么要拦一道：`readDays=0` 的历史月如果照常画，会得到**一张全空的日历**，
     * 用户分不清"这月确实没读"和"加载失败"。所以历史周期干脆改画一行字。
     * 当前周期**不拦** —— "本周还没读"用空柱 / 空格表达更直观，画表格也更有信息量。
     */
    private String emptyNote(String mode, long a, PeriodStats st) {
        if (st == null) return null;                       // 交给已有的"暂无数据 / 正在获取"分支
        if (st.totalSec > 0 || st.readDays > 0) return null;
        long cur = PeriodRange.startOf(mode, 0);
        if (a > cur) return PeriodRange.MONTHLY.equals(mode) ? "这个月还没到" : "这一周还没到";
        if (a == cur) return null;
        return PeriodRange.MONTHLY.equals(mode) ? "这个月没有阅读记录" : "这一周没有阅读记录";
    }

    /**
     * phone 角色：整块隐藏卡片相关 UI，只留一句提示 + 设置入口（A7，TASK-018）。
     *
     * 「卡片悬浮服务不启动」= 该机在系统无障碍里只需打开「微读墨记 · 遥控」，
     * 不需要打开卡片服务（卡片分区在设置页也被隐藏，见 {@code SettingsActivity#refreshRoleUi}）。
     */
    private void applyPhoneMode() {
        // 🔴 TASK-045：**不再隐藏大标签栏**（原 TASK-044 会藏 tab_main / sep_top）。
        //    理由：原「设置」入口 = 底部按钮，该按钮已随 TASK-045 删除；若连大标签栏一起藏掉，
        //    remote_role=phone 形态下用户将**无法到达设置页把角色改回来**（等于把自己锁死）。
        //    ⇒ 大标签栏保留可见，遥控提示行仍替代卡片区（阅读页内）。install_role=phone 走的是
        //    enterApp() 的早退分支（本方法根本不会被调到），故此改动对手机端安装形态零影响。
        findViewById(R.id.nav_drop).setVisibility(View.GONE);
        findViewById(R.id.picker).setVisibility(View.GONE);
        findViewById(R.id.sep_mid).setVisibility(View.GONE);
        findViewById(R.id.card).setVisibility(View.GONE);
        findViewById(R.id.insight).setVisibility(View.GONE);
        findViewById(R.id.sep_card).setVisibility(View.GONE);
        findViewById(R.id.btn_refresh).setVisibility(View.GONE);
        findViewById(R.id.tv_remote_notice).setVisibility(View.VISIBLE);
        // 🆕 TASK-057：手机端形态下卡片相关的 UI 全隐 ⇒ 选书弹层一并收起（不留可见残影）
        findViewById(R.id.note_pick).setVisibility(View.GONE);
        findViewById(R.id.book_pick).setVisibility(View.GONE);        // 🆕 TASK-069
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 🆕 TASK-077：账单**幂等补齐**（后台线程；已有账单不重算、缺的期补上，不写假账）。
        //    与 `CardA11yService.onServiceConnected` 各调一次 —— 谁先谁生效，重复无害。
        BillScheduler.ensureBills(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (remotePhone || installPhone || !readerUiReady) {
            return;                 // 卡片 UI 未装配（手机端形态 / 遥控手机角色 / 首装弹窗期间）⇒ 无需让位/同步/取数
        }
        // 先声明"用户现在在我们自己的界面里" —— 桌面卡片必须让位。
        // 必须在 sync() 之前：sync() 会让服务重算一次可见性，
        // 而服务自己无法从无障碍事件里知道"当前前台是本 App"
        // （TYPE_WINDOW_STATE_CHANGED 只在窗口变化时发，服务重连时不补发）。
        CardA11yService.noteOwnUiForeground(true);
        showMainPage(mainPage);     // 🆕 TASK-044：按当前大标签重放一帧（阅读页则渲染卡片）
        // 回到前台时把桌面卡片对齐一次（可能刚在设置页开关/改过周期）
        CardA11yService.sync();
        refresh();
        // 🆕 TASK-045：回前台顺带把 ②③④ 三大标签的内容刷新一遍（数据可能在别处变过）
        refreshEmbeddedPages();
    }

    /**
     * 🆕 TASK-045：刷新三个内嵌页（设置 / 实验室 / 待办）。
     *
     * <p>内容由控制器驱动，这里只做「进前台 / 切页时复核」，不改变各自的取数时机。
     */
    private void refreshEmbeddedPages() {
        if (settingsCtrl != null) {
            settingsCtrl.refreshRoleVisibility();
            settingsCtrl.refreshChannelTip();
            settingsCtrl.refreshUpdateUi();
        }
        if (labCtrl != null) {
            labCtrl.refreshRoleUi();
            labCtrl.refreshLockUi();
            labCtrl.refreshPowerUi();
            labCtrl.onResume();     // 注册 HID 状态监听 + 刷蓝牙状态行
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (remotePhone || installPhone || !readerUiReady) {
            return;
        }
        CardA11yService.noteOwnUiForeground(false);
        if (labCtrl != null) labCtrl.onPause();     // 🆕 TASK-045：摘掉会话 / HID 监听（离页不持引用）
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 🆕 TASK-045：实验室页的蓝牙「可被发现」回执
        if (labCtrl != null) labCtrl.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        // 🆕 TASK-045：实验室页「锁屏背景图」的存储读权限回执
        if (labCtrl != null) labCtrl.onRequestPermissionsResult(req, perms, results);
    }

    private void refresh() { refresh(false); }

    /** @param force true = 用户手动点刷新（本书的书架缓存可按更短的间隔重拉） */
    private void refresh(boolean force) {
        // 🆕 postreview-TASK-044/045（审查 F-1 附带，用户拍板「按推荐」）：`remote_role=phone`
        //    形态没有卡片可填 ⇒ 一律空转（否则 `onKeySaved` 会往已 GONE 的卡片空跑一次取数）。
        if (remotePhone) return;
        // 🆕 TASK-044：洞察页本卡只做占位（内容 → TASK-048）⇒ 无数据可取，直接跳过（不触发绑定补发）
        if (PeriodRange.INSIGHT.equals(tabMode)) return;
        final String key = StatsStore.getKey(this);
        if (key.length() == 0) {
            card.setStats(null, null);
            return;
        }
        // 🔴 v0.8.1：App 内刷新也按「刷新绑定」补发其它形态（此前只有桌面卡片绑定了，
        // App 侧从不读 bind_targets）。当前 page 的形态照旧走下面的完整路径（含 UI），
        // 其余被勾选的形态由 BindRefresher **静默补发**（只写缓存、不碰 UI —— 与 TASK-012 同口径）。
        int fired = com.inkread.weekread.core.BindRefresher.fireBound(this, key, tabMode);
        if (fired > 0) {
            CardDebug.note(this, "app refresh: bind fired " + fired + " req(s), cur=" + tabMode);
        }
        if (PeriodRange.BOOK.equals(tabMode)) {
            refreshBook(key, force);
            return;
        }
        if (PeriodRange.NOTE.equals(tabMode)) {
            noteSync(force);        // 手动刷新（force）会清掉退避，立即放行
            return;
        }
        final String mode = tabMode;
        final long a = anchor();
        // 当前周期传 0（服务端归一化，第 7 轮已实测跑通）；历史周期才传锚点时间戳
        final long req = (a == PeriodRange.startOf(mode, 0)) ? 0L : a;
        final long gen = StatsStore.keyGen();          // R05：记下会话代次，迟到结果不许写回

        card.setRefreshing(true);
        WereadApi.fetchDetail(key, mode, req, new WereadApi.Callback() {
            @Override
            public void onResult(PeriodStats stats, String rawJson, String error) {
                if (gen != StatsStore.keyGen()) return;        // 换过 Key → 丢弃旧会话结果
                if (error != null) {
                    if (StatsStore.load(MainActivity.this, mode, a) == null) card.setError(error);
                    else card.setRefreshing(false);
                    return;
                }
                if (stats == null) {
                    card.setRefreshing(false);
                    return;
                }
                StatsStore.save(MainActivity.this, stats);
                // 拉取期间用户可能切了选项卡 / 又翻了周期 —— 只认当前那一屏
                if (mode.equals(tabMode) && a == anchor()) {
                    card.setMode(stats.mode);
                    card.setStats(stats, emptyNote(mode, a, stats));
                    applyMonthStyle();   // 本月呈现随新数据同步（TASK-014）
                    picker.setPeriod(mode, a);
                }
                // 数据更新后同步到桌面卡片
                CardA11yService.sync();
            }
        });
    }

    /**
     * 「本书」取数：书架 → 最近在读那本 → 进度 → 章节目录，三步串起来（{@link WereadApi#fetchBook}）。
     *
     * 书架回包 462KB，所以 {@link WereadApi#fetchBook} 内部有 6 小时缓存策略，
     * 只有书架过期时才真的重下 —— 这里只管拿到结果后刷新一帧。
     */
    private void refreshBook(String key, boolean force) {
        final long gen = StatsStore.keyGen();          // R05
        card.setRefreshing(true);
        WereadApi.fetchBook(this, key, force, new WereadApi.BookCallback() {
            @Override
            public void onResult(BookStats stats, String error) {
                if (gen != StatsStore.keyGen()) return;        // 换过 Key → 丢弃旧会话结果
                if (stats == null) {
                    if (BookStore.load(MainActivity.this) == null) card.setError(error);
                    else card.setRefreshing(false);
                    return;
                }
                // 🔴 先落盘再刷画面（v0.5.3，R06）。
                // 上一版只 `card.setBook(stats)` —— 屏幕上有书，但 BookStore 里什么都没有，
                // 于是「打开」按钮（读 BookStore.load）直接返回、桌面卡片与下次进入仍是旧进度。
                // 全仓库原本只有无障碍服务的取数回调会 save，App 内的取数链是个只读的孤岛。
                BookStore.save(MainActivity.this, stats);
                // 拉取期间用户可能切走了 —— 只认当前这一屏
                if (PeriodRange.BOOK.equals(tabMode)) {
                    card.setMode(PeriodRange.BOOK);
                    card.setBook(stats);
                    loadCover(stats);
                }
                CardA11yService.sync();
            }
        });
    }

    // ══════════════════════ 本记（v0.4.0）══════════════════════

    /**
     * 导出当前这条划线（v0.4.1：从底部通用按钮区搬进本记页内 —— 它本来就只对这一页有意义）。
     */
    private void exportNote() {
        NoteStats n = card.getNote();
        if (n == null) {
            android.widget.Toast.makeText(this, "还没有可导出的划线",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // 🔴 C2：导出的重活（480×H 位图分配 + 绘制 + PNG 压缩）挪到工作线程，避免主线程 ANR；
        // 主线程只做 UI 收尾（Toast / 分享面板），并在界面已结束时不动作（守卫见下）。
        new Thread(new Runnable() {
            @Override
            public void run() {
                final NoteExport.ExportResult r =
                        NoteExport.renderAndSave(getApplicationContext(), n);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;   // 导出期间用户退了界面
                        NoteExport.presentResult(MainActivity.this, r);
                    }
                });
            }
        }).start();
    }

    /**
     * 把当前该展示的划线放到全屏卡片上（与桌面 {@code CardA11yService.showNote} 同源）。
     * 章节名缺了就异步反查（一本书只查一次）。
     *
     * @return true = 抽到了一条内容
     */
    private boolean showNote(boolean manual) {
        NoteStats n = NoteStore.pick(this, manual, NoteStore.ideasOnly(this));
        showNoteItem(n);
        return n != null;
    }

    /** 把某条内容放到本记页（「换一条」/「上一条」/ 常规展示共用，v0.4.2 抽出来） */
    private void showNoteItem(NoteStats n) {
        // 进度行按「当前模式」取数：只看想法模式下用的是另一套序号空间（见 NoteStore.pick）
        card.setNoteSlot(NoteStore.slotFor(this));
        // v0.9（TASK-016）：App 内本记页的字号由「导出字号」档决定（小/中/大 = 15/17/19 号），
        // 不再按字数自动分档。
        // 🔴 **两条注入路径里的"App 那条"**（桌面卡片那条在
        // `CardContentController#applyNoteFont`，走 CardPrefs.note_card_size）。
        // 这里读的是 prefs，但由 Activity 注入字段 —— 渲染函数本身仍不碰 prefs（docs/03 §3）。
        card.setNoteFullSize(NoteExport.tierToNum(NoteExport.sizeTier(this)));
        if (n == null) {
            // 🔴 渲染函数**绝不发起同步**（v0.5.3，R02）。
            // 上一版这里是 `refreshNotes(...)`，而同步结束的回调又会回到本函数：
            // 池子为空时形成 `渲染 → 同步 → 回调 → 渲染 → 同步 …` 的**无界环**（一轮 43 次请求，
            // 用的是用户自己的 Key）。现在这里只把画面置空，同步一律由 {@link #noteSync} 从
            // 明确入口发起（进页面 / 切档 / 手动刷新 / 渲染落空后的那一次补齐）。
            if (card.getNote() != null) card.setNote(null);
            return;
        }
        card.setNoteHint(null);            // 有内容了，空态提示作废
        card.setNote(n);
        final NoteStats fn = n;
        NoteSync.resolveChapter(this, StatsStore.getKey(this), n, new Runnable() {
            @Override
            public void run() {
                if (PeriodRange.NOTE.equals(tabMode) && card.getNote() == fn) card.setNote(fn);
            }
        });
    }

    /** 点了「上一条」（v0.4.2）—— 沿来时的路退回去 */
    private void prevNote() {
        NoteStats n = NoteStore.pickPrev(this, NoteStore.ideasOnly(this));
        showNoteItem(n);
        if (n == null) noteSync(true);          // 历史栈空且这一把也读不出来 → 手动放行同步
    }

    /**
     * 点了左下角那格「筛选」（v0.4.5）—— 在「全部 / 只看想法」之间切换。
     *
     * 原来这两个选项是屏顶独占一行的页签（44dp ≈ 60px），现在并进卡片内的按钮行，
     * 把那一行整个让给了正文。
     *
     * 切档只是换一套抽取状态（{@link NoteStore#pick} 的 {@code ideasSlot}）：
     * 两套状态各有自己的一把、自己的序号，所以切回来时**上一档看到哪儿还在哪儿**；
     * 桌面卡片的「今日一签」跟着默认档走，不会被 App 里的浏览动作改掉。
     */
    private void toggleIdeas() {
        NoteStore.setIdeasOnly(this, !NoteStore.ideasOnly(this));
        card.setNoteSlot(NoteStore.slotFor(this));
        // 非 manual：目标档里已有"今天这条"就沿用，没有才新抽；抽不到 → 起一次同步
        // （「只看想法」的池子小得多，第一次切过去很可能还没预热到本地）
        if (!showNote(false)) noteSync(false);
        card.invalidate();        // 换格上的文字（全部 ↔ 想法）与反白状态
    }

    // ══════════════ 🆕 TASK-057（K11）：选书弹层 ══════════════

    /**
     * 点底部「选书」格 ⇒ 弹半屏列表。
     *
     * 料 = {@link NoteStore#bookList}（索引原序，**零新请求**）；当前选中项 = {@link NoteStore#pickedBook}。
     * 详情见 {@link NotePickView}。
     */
    private void showNotePick() {
        if (notePick == null) return;
        notePick.open(NoteStore.bookList(this), NoteStore.pickedBook(this));
        notePick.setVisibility(View.VISIBLE);
    }

    /** 收起弹层（点空白 / 关闭 / 选中后 / 离开本记页都走这里） */
    private void hideNotePick() {
        if (notePick == null || notePick.getVisibility() != View.VISIBLE) return;
        notePick.onHidden();                 // 清焦点 + 收软键盘
        notePick.setVisibility(View.GONE);
        // 弹层盖过的那块要重画：GONE 之后底层卡片/浮层的像素不会自己回来，显式 invalidate 一次
        if (card != null) card.invalidate();
        if (pageReader != null) pageReader.invalidate();
    }

    /**
     * 在弹层里选了某本书（`bookId` 空串 = 「全部书籍」）。
     *
     * 🔴 顺序不能反：先 {@link NoteStore#setPickedBook}（写 prefs + 作废当前批次 + 抽签状态归零），
     * 再收起弹层，最后 {@link #showNote(boolean)} 重新抽一条 —— 否则屏幕还停着旧池的那一条。
     * `showNote(false)` 里 `pick` 的"每日一签"短路已被 `setPickedBook` 清掉 `day` 键，
     * 所以一定会按新档重抽（详见 `NoteStore#setPickedBook` 注释）。
     */
    private void chooseBook(String bookId) {
        NoteStore.setPickedBook(this, bookId);
        hideNotePick();
        if (bookId.length() == 0) {
            // 恢复「全部书籍」⇒ 走原随机池；顺带把「只看想法」的档位同步回去（它没被动过）
            card.setNoteSlot(NoteStore.slotFor(this));
        }
        if (!showNote(false)) noteSync(false);   // 选的书本地还没预热 ⇒ 起一次同步补齐
        card.invalidate();                       // 进度行（第 N / 共 M 条）跟着换池子
        // 🆕 TASK-057 R1（2026-10-06）：桌面卡片也跟随「选书」⇒ 通知它按新池子立刻重绘。
        // 此刻 App 在前台、桌面卡片被压成 GONE（ownUi）⇒ 这次 sync 只是把内容换好，回桌面即见。
        CardA11yService.sync();
    }

    // ══════════════ 🆕 TASK-069（V1.2.1-beta）：「本书」手动选书 ══════════════

    /**
     * 点「本书」页的「选书」⇒ 弹半屏候选列表。
     *
     * <p>料 = {@link BookStore#bookCandidates}（**只读本地书架快照，零新增网络请求**）；
     * 当前档 = {@link CardPrefs#getBookPick}（空串 = 自动）。
     */
    private void showBookPick() {
        if (bookPick == null) return;
        bookPick.open(BookStore.bookCandidates(this, BookStore.BOOK_CANDIDATES),
                CardPrefs.getBookPick(this));
        bookPick.setVisibility(View.VISIBLE);
    }

    /** 收起弹层（点空白 / 关闭 / 选中后 / 离开本书页都走这里） */
    private void hideBookPick() {
        if (bookPick == null || bookPick.getVisibility() != View.VISIBLE) return;
        bookPick.onHidden();
        bookPick.setVisibility(View.GONE);
        // 弹层盖过的那块要重画：GONE 之后底层卡片的像素不会自己回来
        if (card != null) card.invalidate();
        if (pageReader != null) pageReader.invalidate();
    }

    /**
     * 在弹层里选了某本（`bookId` 空串 = 「自动（最近在读）」）。
     *
     * <p>🔴 顺序不能反：先落偏好 → 收起弹层 → 再**强制取一次**本书。
     * {@link WereadApi#fetchBook} 在链路里**读偏好**决定优先试哪本，早取会取到旧档。
     * 取完 {@code refreshBook} 内部会 {@code CardA11yService.sync()} ⇒ 桌面卡片同步换书。
     *
     * <p>选「自动」= 写空串 ⇒ 行为与改造前**逐像素一致**（验收 A4）。
     */
    private void chooseBookPick(String bookId) {
        CardPrefs.setBookPick(this, bookId);
        hideBookPick();
        refresh(true);                          // force：立刻按新偏好走一遍 书架→进度 链
        CardDebug.note(this, "book pick=" + (bookId.length() == 0 ? "auto" : bookId));
    }

    /**
     * 本记同步的**唯一入口**（v0.5.3，R02/R08）。
     *
     * 三道闸门叠起来才堵住了上一版的无界环（见 {@link NoteSync} 类注释）：
     *   ① 渲染函数不再发请求（{@link #showNoteItem}）；
     *   ② {@link NoteSync#sync} 内部 in-flight 去重 —— 已在跑就直接返回；
     *   ③ 空 / 失败后 {@link NoteSync#BACKOFF_MS} 内不再**自动**发起；
     *      手动刷新（{@code force=true}）清掉退避立即放行。
     */
    private void noteSync(boolean force) {
        final String key = StatsStore.getKey(this);
        if (key == null || key.length() == 0) {
            card.setRefreshing(false);
            card.setNoteHint("还没填 API Key");
            return;
        }
        if (NoteSync.isRunning()) return;                      // ② 已经在同步 → 等它回调
        if (!force && !NoteSync.canAutoStart()) {              // ③ 退避期内 → 停在空态
            card.setRefreshing(false);
            card.setNoteHint(lastNoteState == NoteSync.STATE_ERROR
                    ? "同步失败，请检查网络" : noteEmptyHint(null));
            return;
        }
        if (force) NoteSync.clearBackoff();
        refreshNotes(key, force);
    }

    /**
     * 空态提示文案（v0.5.3）。三种"空"要分开说 —— 上一版只有一句
     * 「本记还没有准备好」，用户分不清是没网还是真没有（R02/R08）。
     */
    private String noteEmptyHint(String error) {
        if (error != null) return "同步失败，请检查网络";
        if (NoteStore.ideasOnly(this)) return "还没有写过想法";
        if (NoteStore.index(this) == null) return "还没有同步过笔记";
        return "本地还没有可回顾的笔记";
    }

    /**
     * 「本记」同步：索引 → 逐本预热。渐进式 —— 每轮补 18 本，几轮满库；
     * 手动刷新（force）多补一倍，并连索引一起重拉。
     */
    private void refreshNotes(String key, boolean force) {
        if (key == null || key.length() == 0) return;
        final long gen = StatsStore.keyGen();          // R05：换 Key 后旧会话的迟到结果不许写回
        card.setRefreshing(true);
        NoteSync.sync(this, key, force, force ? NoteSync.DEFAULT_PREFETCH * 2 : NoteSync.DEFAULT_PREFETCH,
                new NoteSync.Listener() {
                    @Override
                    public void onDone(int pool, int ideas, int total, String error, int state) {
                        if (gen != StatsStore.keyGen()) return;               // 换过 Key → 丢弃
                        if (isFinishing() || isDestroyed()) return;            // 页面没了 → 丢弃过期 UI 回调
                        card.setRefreshing(false);
                        if (state == NoteSync.STATE_BUSY) return;        // 别的入口在跑，这轮不算数
                        lastNoteState = state;
                        if (!PeriodRange.NOTE.equals(tabMode)) return;   // 用户已切走 → 只记状态
                        // 拿到内容就换上；仍为空则**停在空态**（这里不会再起同步 —— 环已断）
                        boolean got = showNote(false);
                        card.setNoteHint(got ? null
                                : noteEmptyHint(state == NoteSync.STATE_ERROR ? error : null));
                    }
                });
    }

    /** 上次"缓存缺封面 URL → 补拉书架"的时刻（防抖：5 分钟内最多一次） */
    private static long sCoverNudgeAt = 0L;

    /** 封面接到全屏卡片上（v0.3.5.1）：缓存命中同步出图，否则异步下载后重绘 */
    private void loadCover(BookStats b) {
        if (b == null || b.bookId == null || b.bookId.length() == 0) return;
        if (b.coverUrl == null || b.coverUrl.length() == 0) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - sCoverNudgeAt > 300_000L) {
                sCoverNudgeAt = now;
                refreshBook(StatsStore.getKey(this), false);   // 补拉书架拿 cover URL
            }
            return;
        }
        CoverStore.loadAsync(this, b.bookId, b.coverUrl, 256, new CoverStore.Callback() {
            @Override
            public void onCover(android.graphics.Bitmap bmp) {
                card.setCoverBitmap(bmp, b.bookId);
            }
        });
    }
}
