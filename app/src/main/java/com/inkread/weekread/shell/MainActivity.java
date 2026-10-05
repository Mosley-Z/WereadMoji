package com.inkread.weekread.shell;

import com.inkread.weekread.R;
import com.inkread.weekread.a11y.CardA11yService;
import com.inkread.weekread.core.BookStats;
import com.inkread.weekread.core.BookStore;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.CoverStore;
import com.inkread.weekread.core.NoteStats;
import com.inkread.weekread.core.NoteStore;
import com.inkread.weekread.core.PeriodRange;
import com.inkread.weekread.core.PeriodStats;
import com.inkread.weekread.core.StatsStore;
import com.inkread.weekread.feature.InsightPageView;
import com.inkread.weekread.feature.NoteExport;
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
    /** 🆕 TASK-051（K6）：年度首拉在途标记 —— 防止反复进洞察页时重复发请求（卡面 A4）。 */
    private boolean annualLoading;
    /** 🆕 TASK-044：当前大标签下标（阅读/设置/实验室/待办）。 */
    private int mainPage = MP_READER;
    /**
     * 🆕 TASK-044-R1：大标签① 当前文案（「本周▽」之类）—— 只在变化时重设，免无谓 invalidate。
     */
    private String lastReaderTabLabel;

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
                !CardPrefs.isInstallRoleChosen(this) && CardPrefs.isFreshInstall(this);

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
            return;
        }
        enterApp();
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
        if (CardPrefs.getInstallRole(this) == CardPrefs.INSTALL_ROLE_PHONE) {
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
        pageReader = findViewById(R.id.mp_reader);
        pageSettings = findViewById(R.id.mp_settings);
        pageLab = findViewById(R.id.mp_lab);
        pageTodo = findViewById(R.id.mp_todo);

        // ── TASK-018：手机端遥控器角色（remote_role=phone）⇒ 隐藏卡片相关 UI（A7）──
        // 手机上这个 App 只当遥控器用，统计卡片没有使用场景（ADR-010 决定 4：
        // 「phone = 隐藏卡片相关 UI、不启用卡片悬浮服务」）。
        // 只保留「设置」入口（用户要在那里把角色改回来），其余卡片 UI 整块隐藏。
        if (RemoteRole.from(this) == RemoteRole.PHONE) {
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
        });

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
        refreshMainTabLabels();
        segMain.setListener(new SegTabView.Listener() {
            @Override
            public void onSegSelected(int index) {
                if (index == MP_READER) {
                    // 🔴 TASK-044-R1：本段是下拉扳机，不是普通页签。
                    //    （SegTabView 对"再点已选中段"也会回调 —— 这里靠 mainPage 自己判断分流。）
                    if (mainPage == MP_READER && !remotePhone) {
                        navDrop.toggle();
                        return;
                    }
                    showMainPage(MP_READER);
                    return;
                }
                showMainPage(index);
            }
        });
    }

    /**
     * 🆕 TASK-044-R1：刷新大标签栏文案 —— 第 0 段 = 「当前形态名 + ▽」（下拉扳机），
     * 其余三段仍是页面名。**形态切换后必须重调**，否则标签上还写着上一形态。
     *
     * <p>只在文案真变时 {@code setLabels}（后者内部会 invalidate）。
     */
    private void refreshMainTabLabels() {
        if (segMain == null) return;
        String cur = getString(navLabelResOf(indexOf(tabMode)));
        String readerLabel = cur + "▽";
        if (readerLabel.equals(lastReaderTabLabel)) return;
        lastReaderTabLabel = readerLabel;
        segMain.setLabels(new String[]{
                readerLabel,
                getString(R.string.main_tab_settings),
                getString(R.string.main_tab_lab),
                getString(R.string.main_tab_todo)});
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
     *   <li>实验室内的 4 段（{@code seg_lab}）由 {@link LabPageController#bindLabTabs()} 装配；</li>
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
        labCtrl = new LabPageController(this);
        labCtrl.bind();
        labCtrl.bindLabTabs();

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
        mainPage = index;
        segMain.setSelected(index);
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

        navDrop.setSelected(indexOf(tabMode));
        refreshMainTabLabels();     // 🆕 TASK-044-R1：大标签① 文案跟随形态（「本周▽」→「本月▽」…）

        // 洞察页（🆕 TASK-048 K3）：没有周期、不画卡片 —— 换成自绘滚动容器
        // （5 分区骨架 + 空态；各分区内容由 K4~K8/K10 陆续填）
        if (PeriodRange.INSIGHT.equals(tabMode)) {
            picker.setVisibility(View.GONE);
            card.setVisibility(View.GONE);
            insightPage.setVisibility(View.VISIBLE);
            bindInsightAnnual();             // 🆕 TASK-051 K6：把「年度视图」的缓存喂进去
            bindInsightInterest();           // 🆕 TASK-049 K4：把「兴趣雷达」的料喂进去
            ensureAnnualLoaded();            // 🆕 TASK-051 K6：年度缓存缺 ⇒ 触发一次拉取
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
        card.setNoteSlot(NoteStore.ideasOnly(this));

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
    }

    // ══════════════ 🆕 TASK-049（K4）：洞察页「画像」兴趣雷达的取数 ══════════════

    /**
     * 口径链：**累计 → 年度 → 本月 → 本周**，取第一个「收拢过滤后有料」的档。
     *
     * <p>为什么是这个顺序（实测依据，见 `验证记录/159`）：
     * `preferCategory` 按 `parentCategoryTitle` 收拢后，本账号各档分别只有
     * **累计 5 类 / 年度 4 类 / 本月 2 类 / 本周 0 类**（上游周回包根本没有该字段）。
     * ⇒ 单用周/月这一格常年近乎空白，所以**优先用最全的档**。
     *
     * <p>🔴 **只读缓存，零新增网络请求** —— 年度/累计的**首次拉取**归
     * {@code TASK-051} / {@code TASK-052}（本卡不越界）。它们落码后本方法**无需改动**
     * 就自动变丰富（链路取到的档自然前移）。
     */
    private void bindInsightInterest() {
        String[] chain = { PeriodRange.OVERALL, PeriodRange.ANNUALLY,
                           PeriodRange.MONTHLY, PeriodRange.WEEKLY };
        for (int i = 0; i < chain.length; i++) {
            PeriodStats st = loadInsightInterest(chain[i]);
            if (InsightPageView.hasInterest(st)) {
                insightPage.setInterest(st.preferCategory, scopeWordOf(chain[i]));
                CardDebug.note(this, "insight interest: scope=" + chain[i]);
                return;
            }
        }
        insightPage.setInterest(null, null);          // 全档都无料 ⇒ 空态
        CardDebug.note(this, "insight interest: 空态（四档缓存都没有可统计的分类）");
    }

    /** 读某一档的缓存：累计 → `loadOverall`；年度 → `loadAnnual(今年)`；其余 → 当前周期缓存。 */
    private PeriodStats loadInsightInterest(String mode) {
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
                        bindInsightInterest();        // 兴趣雷达口径可能前移（见方法注释）
                        CardDebug.note(MainActivity.this, "insight annual: fetched OK total="
                                + stats.totalSec);
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
            settingsCtrl.refreshStatus();
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
        card.setNoteSlot(NoteStore.ideasOnly(this));
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
        card.setNoteSlot(NoteStore.ideasOnly(this));
        // 非 manual：目标档里已有"今天这条"就沿用，没有才新抽；抽不到 → 起一次同步
        // （「只看想法」的池子小得多，第一次切过去很可能还没预热到本地）
        if (!showNote(false)) noteSync(false);
        card.invalidate();        // 换格上的文字（全部 ↔ 想法）与反白状态
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
