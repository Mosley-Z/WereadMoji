package com.inkread.weekread;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

/**
 * 「微读墨记」主页：页面选项卡（**本周 / 本月**） + 周期步进选择器 + 统计卡片 + 刷新/设置按钮。
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
                BookStats b = BookStore.load(MainActivity.this);
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
                showNote(true);                 // 手动插队抽下一条
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
        String k = getIntent() == null ? null : getIntent().getStringExtra("api_key");
        if (k != null && k.length() > 0) StatsStore.setKey(this, k);

        tabbar.setListener(new TabBarView.Listener() {
            @Override
            public void onTabSelected(int index) {
                tabMode = modeOf(index);
                show();
                // 该形态本地还没有缓存（第一次点开本月 / 第一次点开本书 / 翻到没看过的历史周期）→ 顺手拉一次
                if (PeriodRange.BOOK.equals(tabMode)) {
                    if (BookStore.load(MainActivity.this) == null) refresh();
                } else if (PeriodRange.NOTE.equals(tabMode)) {
                    showNote(false);                 // 池子空时 showNote 内部会自动起同步
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
            showNote(false);
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
            refreshNotes(key, force);
            return;
        }
        final String mode = tabMode;
        final long a = anchor();
        // 当前周期传 0（服务端归一化，第 7 轮已实测跑通）；历史周期才传锚点时间戳
        final long req = (a == PeriodRange.startOf(mode, 0)) ? 0L : a;

        card.setRefreshing(true);
        WereadApi.fetchDetail(key, mode, req, new WereadApi.Callback() {
            @Override
            public void onResult(PeriodStats stats, String rawJson, String error) {
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
        card.setRefreshing(true);
        WereadApi.fetchBook(this, key, force, new WereadApi.BookCallback() {
            @Override
            public void onResult(BookStats stats, String error) {
                if (stats == null) {
                    if (BookStore.load(MainActivity.this) == null) card.setError(error);
                    else card.setRefreshing(false);
                    return;
                }
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
     * 池子为空时自动起一轮同步；章节名缺了就异步反查（一本书只查一次）。
     */
    private void showNote(boolean manual) {
        showNoteItem(NoteStore.pick(this, manual, NoteStore.ideasOnly(this)));
    }

    /** 把某条内容放到本记页（「换一条」/「上一条」/ 常规展示共用，v0.4.2 抽出来） */
    private void showNoteItem(NoteStats n) {
        // 进度行按「当前模式」取数：只看想法模式下用的是另一套序号空间（见 NoteStore.pick）
        card.setNoteSlot(NoteStore.ideasOnly(this));
        card.setNote(n);
        if (n == null) {
            refreshNotes(StatsStore.getKey(this), false);
            return;
        }
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
        showNoteItem(NoteStore.pickPrev(this, NoteStore.ideasOnly(this)));
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
        showNote(false);          // 非 manual：目标档里已有"今天这条"就沿用，没有才新抽
        card.invalidate();        // 换格上的文字（全部 ↔ 想法）与反白状态
    }

    /**
     * 「本记」同步：索引 → 逐本预热。渐进式 —— 每轮补 12 本，几轮满库；
     * 手动刷新（force）多补一倍，让"我就要现在看别的书"更快达成。
     */
    private void refreshNotes(String key, boolean force) {
        if (key == null || key.length() == 0) return;
        card.setRefreshing(true);
        NoteSync.sync(this, key, force, force ? NoteSync.DEFAULT_PREFETCH * 2 : NoteSync.DEFAULT_PREFETCH,
                new NoteSync.Listener() {
                    @Override
                    public void onDone(int pool, int total, String error) {
                        card.setRefreshing(false);
                        if (PeriodRange.NOTE.equals(tabMode)) showNote(false);
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
