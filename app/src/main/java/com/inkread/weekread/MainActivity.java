package com.inkread.weekread;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

/**
 * 「微读墨记」主页：页面选项卡（**本周 / 本月 / 本书 / 本记**） + 周期步进选择器 + 统计卡片 + 刷新/设置按钮。
 *
 * v0.3.3 起两件事一起做完：
 * ① 「本月」不再是占位页，而且是**全屏大版**（{@link WeekCardView#setFullscreen}：
 *    上方信息块、下方大号日历，格边长目标 52px，设计方案 §2.3 / §3.2）；
 * ② 选项卡下面多一条 {@link PeriodPickerView} —— 周选择器 / 年月选择器（含 `◀◀ ▶▶` 年快跳），
 *    按拍板 ④ 这**只在 App 内**出现，桌面卡片上不放。
 *
 * ── 周期状态怎么存 ──
 * 两个选项卡**各记一个锚点**（{@link #anchorWeek} / {@link #anchorMonth}），
 * 这样在"本月"里翻到 3 月、切到"本周"看一眼、再切回来，仍然停在 3 月。
 * 锚点是 {@code periodStart} 时间戳（秒），一律由 {@link PeriodRange} 算，不手写月份天数。
 * 锚点只活在内存里 —— 进程重启就回到"当前周期"，因为"打开 App 总是先看当下"才是默认预期。
 *
 * ── 取数 ──
 * 当前周期仍按**实测跑通**的 `baseTime=0` 走；只有翻到历史周期才把锚点时间戳传下去
 * （`baseTime` 传周期内任意时刻，服务端会归一化到周期起点，接口零改动，见设计方案 §4.1）。
 */
public class MainActivity extends Activity {

    private WeekCardView card;
    private TabBarView tabbar;
    private PeriodPickerView picker;

    /**
     * 当前选项卡对应的形态：weekly（第 0 屏）/ monthly（第 1 屏）/ book（第 2 屏）。
     *
     * 「本书」不是周期 —— 它没有"上一个月"这种概念，所以下面的 {@link PeriodPickerView}
     * 在它这一屏会整条隐藏（{@link #show()}）。
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // v0.5.0：清掉上次没下完的更新残包；并静默查一次更新。
        // 静默检查每天至多一次，结果落盘 —— 用户进设置页自然看到「有新版本」，全程不弹窗。
        ApkInstaller.cleanupPartial(this);
        UpdateChecker.autoCheck(this);

        card = (WeekCardView) findViewById(R.id.card);
        tabbar = (TabBarView) findViewById(R.id.tabbar);
        picker = (PeriodPickerView) findViewById(R.id.picker);

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

        tabbar.setListener(new TabBarView.Listener() {
            @Override
            public void onTabSelected(int index) {
                tabMode = modeOf(index);
                show();
                // 该形态本地还没有缓存（第一次点开本月 / 第一次点开本书 / 翻到没看过的历史周期）→ 顺手拉一次
                if (PeriodRange.BOOK.equals(tabMode)) {
                    if (BookStore.load(MainActivity.this) == null) refresh();
                } else if (PeriodRange.NOTE.equals(tabMode)) {
                    // 渲染落空才起同步 —— 绝不在渲染函数里发请求（v0.5.3，R02）
                    if (!showNote(false)) noteSync(false);
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
                show();
                if (StatsStore.load(MainActivity.this, tabMode, next) == null) refresh();
            }
        });

        ((Button) findViewById(R.id.btn_refresh)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);                      // 手动点刷新 = 强制，书架缓存也允许更频繁地重拉
            }
        });

        ((Button) findViewById(R.id.btn_settings)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
    }

    /** 选项卡下标 → 形态 */
    private static String modeOf(int index) {
        if (index == 1) return PeriodRange.MONTHLY;
        if (index == 2) return PeriodRange.BOOK;
        if (index == 3) return PeriodRange.NOTE;
        return PeriodRange.WEEKLY;
    }

    /** 形态 → 选项卡下标 */
    private static int indexOf(String mode) {
        if (PeriodRange.MONTHLY.equals(mode)) return 1;
        if (PeriodRange.BOOK.equals(mode)) return 2;
        if (PeriodRange.NOTE.equals(mode)) return 3;
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

    /** 按当前选项卡 + 锚点渲染一帧（缓存里没有就走空态，不会画错数据） */
    private void show() {
        tabbar.setSelected(indexOf(tabMode));
        card.setMode(tabMode);

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

    @Override
    protected void onResume() {
        super.onResume();
        // 先声明"用户现在在我们自己的界面里" —— 桌面卡片必须让位。
        // 必须在 sync() 之前：sync() 会让服务重算一次可见性，
        // 而服务自己无法从无障碍事件里知道"当前前台是本 App"
        // （TYPE_WINDOW_STATE_CHANGED 只在窗口变化时发，服务重连时不补发）。
        CardA11yService.noteOwnUiForeground(true);
        show();
        // 回到前台时把桌面卡片对齐一次（可能刚在设置页开关/改过周期）
        CardA11yService.sync();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CardA11yService.noteOwnUiForeground(false);
    }

    private void refresh() { refresh(false); }

    /** @param force true = 用户手动点刷新（本书的书架缓存可按更短的间隔重拉） */
    private void refresh(boolean force) {
        final String key = StatsStore.getKey(this);
        if (key.length() == 0) {
            card.setStats(null, null);
            return;
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
        String err = NoteExport.exportAndShare(this, n);
        if (err != null) {
            android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show();
        }
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
