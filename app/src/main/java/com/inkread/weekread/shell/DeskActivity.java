package com.inkread.weekread.shell;

import com.inkread.weekread.a11y.CardA11yService;
import com.inkread.weekread.core.CardDebug;
import com.inkread.weekread.feature.DeskPageView;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * 🆕 TASK-088 Phase 1（`task-94`，2026-10-13）：**墨台页**。
 *
 * <p>本页原先是 {@code a11y/DeskOverlay} 的「第三覆盖窗」（{@code TYPE_ACCESSIBILITY_OVERLAY}），
 * 本卡把它迁成自家 Activity —— 用户 2026-10-13 指令：墨台入口只留桌面卡片按钮。
 *
 * <p>为什么改成 Activity（Phase 0 真机探针 `task-90` / `验证记录/245` 的实测结论）：
 * <ul>
 *   <li>P1 绿：从 a11y 服务上下文 {@code startActivity(…, FLAG_ACTIVITY_NEW_TASK)} **不被**
 *       Android 11 的后台启动限制拦（真机 {@code Displayed …DeskActivity} 可见）；</li>
 *   <li>P2 绿：起 Activity 前把卡片覆盖窗让位（本页 {@link #onResume} 的
 *       {@link CardA11yService#noteOwnUiForeground(boolean)} 负责；实测覆盖窗从 4 可见 → 0 可见）；</li>
 *   <li>P3 是**唯一的坑**：Activity 收尾（{@code onDestroy}）与本页让位都**不产生桌面窗口事件**，
 *       而 {@code noteOwnUiForeground(false)} 刻意不重算 ⇒ 必须由 {@link CardA11yService#endDesk(int)}
 *       显式补一次重算，卡片才会从"让位"状态回来。见本类 {@link #onDestroy()}。</li>
 * </ul>
 *
 * <p>🔴 三条硬纪律（`a11y/CardA11yService.java` 顶部注释 + 本卡卡面）：
 * <ol>
 *   <li>{@link #onResume}/{@link #onPause} **必须**调 {@code noteOwnUiForeground(true/false)} ——
 *       卡片覆盖窗层级恒高于 Activity，不调这句墨台页会被自家卡片压住；</li>
 *   <li>{@link #onDestroy()} 里用**实例身份 + 会话序号**守卫，旧实例收尾**不得**关掉新会话
 *       （用户「退出 → 马上再点卡片」竞态）；</li>
 *   <li>零新增权限、不读窗口内容、不动 {@code res/xml/a11y_card_service.xml}。</li>
 * </ol>
 */
public final class DeskActivity extends Activity implements DeskPageView.Listener {

    /**
     * 当前活着的实例（供 {@link CardA11yService#hideDesk()} / {@code isDeskShowing()} 用）。
     * 只在主线程读写（Activity 生命周期均在主线程）。
     */
    private static DeskActivity sInstance;

    /**
     * 本实例认领的**会话序号** —— 向 {@link CardA11yService#currentDeskEpoch()} 对表。
     *
     * <p>每次 {@code showDesk()} 起一次新会话就 +1；收尾时带着它去
     * {@link CardA11yService#endDesk(int)}，序号不符者**整段作废**（老实例的回调不得关掉新会话）。
     * 在 {@link #onCreate}、{@link #onResume}、{@link #onNewIntent} 三处对表：单实例
     * （{@code launchMode=singleTask}）被复用时序号会变，靠这三处跟上。
     */
    private int epoch;

    /** 本页此刻是否活着（{@code CardA11yService.isDeskShowing()} 的口）。 */
    public static boolean isAlive() {
        return sInstance != null;
    }

    /** 🔴 D1（task-94）：墨台页字段 —— {@code onNewIntent} 复用时要复位它。 */
    private DeskPageView page;

    /** 关闭墨台（{@code wb_desk_hide} 与 {@code CardA11yService.hideDesk()} 的口）。 */
    public static void finishIfAlive() {
        DeskActivity a = sInstance;
        if (a != null) a.finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 🆕 task-94（2026-10-13，inset 归因）：覆盖窗形态的墨台窗口是 `content=[0,0][480,800]`
        // （`a11y/DeskOverlay.java:161-173` = MATCH_PARENT + FLAG_LAYOUT_IN_SCREEN，不给状态栏留位），
        // 迁成普通 Activity 后窗口 content 变成 `[0,52][480,800]` ⇒ 整页下移 52px、可视高度少 52px，
        // 且状态栏与墨台自带顶栏叠成两条栏。门禁 `regress/t087_asset.sh:180` / `t087_filter.sh:190`
        // 用的墨台顶栏坐标（20,27 / 437,27）是覆盖窗时代的量测值 ⇒ 设置页打不开（条目9 用例② 失败根因）。
        // 最小改法（不引入 AndroidX、不动主题）：隐藏状态栏并让内容铺满整屏，恢复覆盖窗几何。
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        sInstance = this;
        epoch = CardA11yService.currentDeskEpoch();
        page = new DeskPageView(this);
        page.setListener(this);
        setContentView(page);
        // 🔴 D1：每次展出都复位（等价 HEAD `a11y/DeskOverlay.java:53` 的 `view.reset()`）——
        // 不复位 ⇒ 分区不装配，只剩「暂无内容」空态（task-94 现场：帧 14,645 B）。
        page.reset();
        CardDebug.note(this, "DeskActivity onCreate（墨台 = Activity 形态，epoch=" + epoch + "）");
    }

    /**
     * {@code singleTask} 被复用时的入口 —— 用户连点卡片按钮只出**一个**墨台页（不叠层）。
     * 这里要把会话序号重新对表，否则收尾时会被自己认成"旧实例"而整段作废（卡片回不来）。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        epoch = CardA11yService.currentDeskEpoch();
        // 🔴 D1：复用入口同样复位（等价 HEAD `a11y/DeskOverlay.java:89`）—— 否则残留上次的滚动位/展开态。
        if (page != null) page.reset();
        CardDebug.note(this, "DeskActivity onNewIntent（单实例复用，epoch=" + epoch + "）");
    }

    @Override
    protected void onResume() {
        super.onResume();
        sInstance = this;
        epoch = CardA11yService.currentDeskEpoch();
        // 🔴 硬要求：进自家界面 ⇒ 卡片立刻让位（否则覆盖窗压在墨台页上面）。见类注释。
        CardA11yService.noteOwnUiForeground(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CardA11yService.noteOwnUiForeground(false);
        // 🔴 onDestroy 兜底：只在**本页正在收尾**（finish / 被回收）时补一次会话收尾，
        //    纯粹的暂停（拉分享面板 / 灭屏）不算结束 —— 那时用户还在墨台里，
        //    提前 endDesk 会让回来后的那次收尾变成幂等空操作，卡片就再也回不来。
        //    与 onDestroy 的调用是幂等的（见 `CardA11yService.endDesk`）。
        if (isFinishing()) CardA11yService.endDesk(epoch);
    }

    @Override
    protected void onDestroy() {
        final boolean owner = (sInstance == this);
        if (owner) sInstance = null;
        CardDebug.note(this, "DeskActivity onDestroy（会话持有者=" + owner + "，epoch=" + epoch + "）");
        // 🔴 只有**当前会话持有者**才收尾：旧实例（已退位给新会话）不得关掉新会话的卡片显隐。
        if (owner) CardA11yService.endDesk(epoch);
        super.onDestroy();
    }

    // ── DeskPageView.Listener ──

    /** 左滑 / 点 `‹ 返回`：关闭本页（原覆盖窗形态是摘窗，现在是 finish）。 */
    @Override
    public void onDeskClose() {
        finish();
    }

    /**
     * 本页即将起**外部界面**（系统分享面板）。
     *
     * <p>原覆盖窗形态下宿主必须先摘窗（{@code TYPE_ACCESSIBILITY_OVERLAY} 层级高于 ChooserActivity，
     * 不让位则面板被盖住）；迁成 Activity 后本页与面板是**普通 Activity 叠放**，
     * 且 {@link #onPause} 已把让位交给卡片显隐链 ⇒ 这里无需再做任何事，留空即可
     * （{@code DeskPageView.startShare()} 自己带 {@code FLAG_ACTIVITY_NEW_TASK} 起面板）。
     */
    @Override
    public void onShareRequested() {
    }
}
