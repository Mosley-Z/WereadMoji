package com.inkread.weekread.shell;

import com.inkread.weekread.R;
import com.inkread.weekread.a11y.CardA11yService;
import com.inkread.weekread.core.CardPrefs;
import com.inkread.weekread.core.FeatureGate;
import com.inkread.weekread.ui.InkTheme;
import com.inkread.weekread.ui.SegTabView;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;

/**
 * 设置页（**独立 Activity 外壳**）。
 *
 * <p>🆕 v1.2（TASK-045）**布局搬迁 + 控制器抽取**后，本类只剩「壳」职责：
 * <ul>
 *   <li>inflate {@code settings_layout.xml}（phone 端入口专用，**保持原样**）；</li>
 *   <li>顶部页签（初始化 / 自定义 / 实验室）的装配与切换（{@link #bindTopTabs} / {@link #showTopPage}）；</li>
 *   <li>手机端深色主题递归染色（{@link #applyDarkTheme()}，仅 {@code phone + phone_dark_mode} 生效）；</li>
 *   <li>生命周期转发给两个控制器。</li>
 * </ul>
 *
 * <p>🔴 页面**内容逻辑**已全部移到：
 * <ul>
 *   <li>{@link SettingsPageController} —— page_init + page_custom；</li>
 *   <li>{@link LabPageController} —— page_lab。</li>
 * </ul>
 * 这两个控制器被 {@link MainActivity}（reader 端 App 内导航）**共用** ⇒ 逻辑单份、无双份维护。
 *
 * <p>── 本类存在的意义 ──
 * phone 端（{@code install_role=phone}）由 {@code ConsoleActivity} 的齿轮进入本页；
 * 若删除，phone 端设置页将不可达。故**保留**，其主题（{@code Theme.WereadMoji.Settings}）也保留。
 *
 * <p>── ActionBar ──
 * 🔴 本页**保留** ActionBar（phone 端形态不变）。reader 端已改为 App 内大标签②，走
 * {@code MainActivity}（其 theme 本就是 {@code NoActionBar}）⇒ 自动无 ActionBar。
 */
public class SettingsActivity extends Activity {

    /** 主入口以「手机端」进入时携带：设置页直接定位到「实验室」页。 */
    public static final String EXTRA_OPEN_LAB = "open_lab";

    private SettingsPageController settingsCtrl;
    private LabPageController labCtrl;

    /** 🆕 TASK-043：顶部页签「可见表」—— 索引 index → 原页下标（0=初始化/1=自定义/2=实验室）。 */
    private int[] mTopPageKeep;

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

        // ── 控制器装配（init/custom + lab 两块内容逻辑）──
        settingsCtrl = new SettingsPageController(this, mListener);
        settingsCtrl.bind();
        labCtrl = new LabPageController(this);
        labCtrl.bind();

        // ── v0.4.3 顶部页签「初始化 / 自定义 / 实验室」；v0.7 加第三段「实验室」──
        // 三个页面是同一个 ScrollView 里的三个容器，切页只切 visibility。
        // 🆕 TASK-031：切换逻辑抽到 showTopPage()，便于「手机端入口」程序化定位到实验室页。
        // 🆕 TASK-043：顶部页签**按 install_role 装配** —— 手机端只留「实验室」
        //   （初始化里的 API Key / 桌面卡片、整个「自定义」页 = 阅读器专属，对手机端全是无效项）。
        final SegTabView seg = (SegTabView) findViewById(R.id.seg);
        bindTopTabs(seg);
        seg.setListener(new SegTabView.Listener() {
            @Override
            public void onSegSelected(int index) {
                showTopPage(index);
            }
        });

        // ── 实验室子标签（遥控翻页 | 蓝牙控制 | 锁屏密码 | 续航优化）──
        // 改为「按 install_role 动态装配」（手机端形态只装手机端关联子标签），详见 bindLabTabs()。
        labCtrl.bindLabTabs();

        // 首次进页即按角色定一次显隐（phone 角色要立刻隐藏「桌面卡片」分区）
        settingsCtrl.refreshRoleVisibility();
        labCtrl.refreshRoleUi();

        // 🆕 TASK-041：进页按偏好着色（仅 phone + phone_dark_mode 生效；否则整段不动 ⇒ 零差异）
        applyDarkTheme();

        // ── 🆕 TASK-031：从主入口以「手机端」路由进来时，直接定位到「实验室」页 ──
        // 🔴 SegTabView.setSelected 同值早退、不回调 listener ⇒ 必须手动走一次 showTopPage()。
        // 🆕 TASK-043-R1：手机端顶部为「初始化 + 实验室」两页 ⇒ 实验室可见表下标 = 1（reader 端仍是 2）。
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_LAB, false)) {
            int labIdx = labTabIndex();
            // 🆕 TASK-064：正式版无「实验室」段（labIdx = -1）⇒ 不定位，停在默认页（初始化）。
            if (labIdx >= 0) {
                seg.setSelected(labIdx);
                showTopPage(labIdx);
            }
        }
    }

    /** 控制器 → 宿主回调（角色变更 / 保存 Key）。 */
    private final SettingsPageController.Listener mListener = new SettingsPageController.Listener() {
        @Override
        public void onInstallRoleChanged() {
            bindTopTabs((SegTabView) findViewById(R.id.seg));   // 🆕 TASK-043：顶部页签随角色重装
            labCtrl.bindLabTabs();        // 实验室可见子标签随之变化
            labCtrl.refreshBtUi();        // 🆕 TASK-033：蓝牙控制页按新角色重刷
            settingsCtrl.refreshRoleVisibility();   // 🆕 TASK-043：重算 API Key / 桌面卡片分区显隐
            labCtrl.refreshRoleUi();
            applyDarkTheme();             // 🆕 TASK-041：角色变化影响深色适用范围（phone 才可能深色）
        }

        @Override
        public void onKeySaved() {
            finish();                     // 独立设置页：退回上一页（原行为）
        }
    };

    // ────────────────────── 🆕 TASK-031：安装角色（App 形态）──────────────────────

    /**
     * 顶部三段（初始化 / 自定义 / 实验室）切换 —— 切可见性 + 回到页顶 + 按角色刷新显隐。
     *
     * <p>抽成方法（TASK-031）：既供 {@link SegTabView} 的 listener 用，也供「手机端」入口
     * 程序化定位到实验室页用（`setSelected` 不会回调 listener）。
     */
    private void showTopPage(int index) {
        // 🆕 TASK-043：顶部页签按 install_role 装配，索引 → 页容器要走**可见表**（reader = 全 3 段，
        //   phone = 仅实验室）。mTopPageKeep 由 bindTopTabs() 填；越界时退回全量（防御）。
        final int[] keep = (mTopPageKeep != null) ? mTopPageKeep : new int[]{0, 1, 2};
        final int orig = (index >= 0 && index < keep.length) ? keep[index] : 0;
        findViewById(R.id.page_init).setVisibility(orig == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.page_custom).setVisibility(orig == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.page_lab).setVisibility(orig == 2 ? View.VISIBLE : View.GONE);
        // 切页回到顶部：各页高度不同，留着旧滚动位置会看着像"卡住了"
        ((ScrollView) findViewById(R.id.sv_settings)).scrollTo(0, 0);
        // 🆕 TASK-043-R1：切页后统一刷一次角色/状态显示。
        settingsCtrl.refreshRoleVisibility();
        labCtrl.refreshRoleUi();
    }

    /**
     * 🆕 TASK-043：按 {@code install_role} 装配**顶部页签**（初始化 / 自定义 / 实验室）。
     *
     * <p>可扩展表 {@code {label, page 下标}} + 可见判定：
     * <ul>
     *   <li><b>阅读器端</b> ⇒ 三段全装（与改造前逐项一致，A6）；</li>
     *   <li><b>手机端</b> ⇒ 装「初始化」+「实验室」两段（🆕 TASK-043-R1 调整）。</li>
     * </ul>
     * 结果写回 {@link #mTopPageKeep}（可见表）供 {@link #showTopPage(int)} 反查页容器。
     */
    private void bindTopTabs(SegTabView seg) {
        final boolean phone = CardPrefs.getInstallRole(this) == CardPrefs.INSTALL_ROLE_PHONE;
        //  下标 0=初始化 / 1=自定义 / 2=实验室（与 showTopPage 的容器一一对应）
        final int[] labelRes = {R.string.tab_init, R.string.tab_custom, R.string.tab_lab};
        // 🔴 TASK-043-R1：手机端保留「初始化」（本机角色 + 检查更新）与「实验室」（蓝牙控制等）；
        //   「自定义」整页对手机端无意义 ⇒ 仍隐藏。
        // 🆕 TASK-064：段③「实验室」再按能力门过滤 —— 正式版（labVisible=false）不装该段。
        final boolean[] show = {true, !phone, FeatureGate.labVisible(this)};
        final int[] keep = new int[labelRes.length];
        int n = 0;
        for (int i = 0; i < labelRes.length; i++) {
            if (show[i]) keep[n++] = i;
        }
        final int[] keepTrim = new int[n];
        System.arraycopy(keep, 0, keepTrim, 0, n);
        mTopPageKeep = keepTrim;
        final String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = getString(labelRes[keepTrim[i]]);
        seg.setLabels(labels);
        // 🔴 setSelected 同值会早退、不回调 listener ⇒ 必须显式把三个页容器的可见性对齐可见表，
        //    否则手机端会出现"页签写着「实验室」、显示的却是 page_init（XML 默认 VISIBLE）"。
        seg.setSelected(0);
        showTopPage(0);
    }

    /** 🆕 TASK-043：「实验室」在**当前可见表**里的下标（reader=2 / phone=1）。🆕 TASK-064：不可见时返回 -1。 */
    private int labTabIndex() {
        final int[] keep = mTopPageKeep;
        if (keep != null) {
            for (int i = 0; i < keep.length; i++) {
                if (keep[i] == 2) return i;
            }
        }
        return -1;   // 🆕 TASK-064：正式版无「实验室」段 ⇒ 无下标（调用方据此早退）
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
     *
     * <p>🔴🔴 **染色必须"无副作用"**：所有背景改动都走 {@link #darkCopyOf}（先克隆再改），
     * **禁止**就地修改 {@code v.getBackground()} 得到的 drawable —— 那是 {@code Resources} 的
     * **进程级共享缓存对象**，改了会永久污染同进程内后续所有实例（TASK-063 根因）。
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
            if (v instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) v;
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
            // 背景：命中映射才替换 —— 🔴 **必须换一份克隆件**，绝不能就地改（根因见 darkCopyOf）
            Drawable nb = darkCopyOf(v.getBackground(), null);
            if (nb != null) v.setBackground(nb);

            // XML 里 many 按钮用 @color/btn_ink_text（ColorStateList：按下白 / 常态黑）作文字色。
            // getCurrentTextColor() 拿到的是解析后的"黑"，可以映射；但按下态仍是白 —— 一并把
            // 该 ColorStateList 的两个档都换掉，保证按下也不刺眼。
            if (v instanceof android.widget.TextView) {
                android.content.res.ColorStateList tsl = tvTextColors((android.widget.TextView) v);
                if (tsl != null && tsl.isStateful()) darkenTextStateList((android.widget.TextView) v, tsl);
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
    private static android.content.res.ColorStateList tvTextColors(android.widget.TextView tv) {
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
    private static void darkenTextStateList(android.widget.TextView tv,
                                            android.content.res.ColorStateList src) {
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
     * 把「亮色背景」换成等价的**深色背景**，**返回一份新建的、本视图独占的 drawable**；
     * 无需替换（未命中映射 / 非可识别类型）或无法克隆时返回 {@code null}（调用方保持原背景，零副作用）。
     *
     * <p>🔴🔴 **根因说明（TASK-063 · 「亮色设置页大面积黑底」）**
     * <p>{@code v.getBackground()} 返回的 drawable 来自 {@code Resources} 的**进程级 drawable 缓存**
     * （纯色走 {@code ResourcesImpl#mColorDrawableCache}，键 =（颜色, assetCookie），**与主题无关**）。
     * 旧实现直接对它 {@code ColorDrawable#setColor()} / {@code GradientDrawable#setColor()}，
     * 等于把这份"公共白底"**永久改黑** —— 之后同进程内**任何**新建实例（哪怕亮色）inflate 同一布局时
     * 都会复用同一份已被污染的 drawable ⇒ **亮色下整片黑底**（真机复现：亮色实例 {@code rootBg=0xff070a0f}，
     * 而 {@code applyDarkTheme dark=false}）。
     *
     * <p>因此本方法**只改克隆件**：{@code getConstantState().newDrawable().mutate()} 克隆，
     * 再在克隆件上改色；共享对象一个字节都不动。
     *
     * @param stateSet 该 drawable 在父 selector 里的状态集（用于判定"按下"档），无则 {@code null}
     */
    private static Drawable darkCopyOf(Drawable d, int[] stateSet) {
        if (d == null) return null;
        try {
            // ① 纯色：命中映射才换（新建实例，天然不与缓存共享）
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                int cur = ((android.graphics.drawable.ColorDrawable) d).getColor();
                int next = darkOf(cur);
                return (next == -1) ? null : new android.graphics.drawable.ColorDrawable(next);
            }
            // ② 描边按钮 selector：逐 item **克隆**后改色，再装进新的 StateListDrawable
            if (d instanceof android.graphics.drawable.StateListDrawable) {
                android.graphics.drawable.StateListDrawable s =
                        (android.graphics.drawable.StateListDrawable) d;
                android.graphics.drawable.StateListDrawable out =
                        new android.graphics.drawable.StateListDrawable();
                boolean changed = false;
                for (int i = 0; i < s.getStateCount(); i++) {
                    int[] ss = s.getStateSet(i);
                    Drawable item = s.getStateDrawable(i);
                    Drawable one = darkCopyOf(item, ss);
                    if (one != null) {
                        changed = true;
                        out.addState(ss, one);
                    } else {
                        out.addState(ss, item);
                    }
                }
                return changed ? out : null;
            }
            // ③ 裸 GradientDrawable：克隆件上套"深色描边按钮"形态
            if (d instanceof android.graphics.drawable.GradientDrawable) {
                Drawable copy = cloneDrawable(d);
                if (copy == null) return null;
                applyInkButtonShape((android.graphics.drawable.GradientDrawable) copy, stateSet);
                return copy;
            }
            // ④ 其它（RippleDrawable / LayerDrawable / ClipDrawable / 位图…）**一律不动** ——
            //    宁可少染，也绝不污染共享对象。
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 克隆一个 drawable（含其独占的 ConstantState）。
     *
     * <p>用 {@code getConstantState().newDrawable().mutate()} 而非直接引用：前者保证后续
     * {@code setColor}/{@code setStroke} 只作用于副本，不会回写 {@code Resources} 的共享缓存。
     * 克隆失败返回 {@code null}（调用方放弃改动 ⇒ 宁可不染）。
     */
    @SuppressWarnings("deprecation")
    private static Drawable cloneDrawable(Drawable d) {
        try {
            android.graphics.drawable.Drawable.ConstantState cs = d.getConstantState();
            if (cs == null) return null;
            Drawable copy = cs.newDrawable();
            if (copy == null) return null;
            copy.mutate();
            return copy;
        } catch (Throwable t) {
            return null;
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

    // ────────────────────── 生命周期（转发给控制器）──────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        // 和主页一样：告诉服务"用户在自家界面"，桌面卡片要让位
        CardA11yService.noteOwnUiForeground(true);
        settingsCtrl.refreshRoleVisibility();   // 按角色重算显隐（phone 角色隐藏「桌面卡片」分区）
        labCtrl.refreshRoleUi();                // 实验室侧：角色说明 / 会话控件 / 晃动块
        applyDarkTheme();                       // 🆕 TASK-041：深色偏好可能在遥控台改过，回前台重染
        settingsCtrl.refreshStatus();
        settingsCtrl.refreshChannelTip();       // TASK-020：说明行按当前通道刷新（无弹窗）
        settingsCtrl.refreshUpdateUi();
        labCtrl.refreshLockUi();                // TASK-022：锁屏子页状态（可能在别处改过偏好）
        labCtrl.refreshPowerUi();               // TASK-023：续航子页（易失项以设备真值为准复核）
        // 🆕 TASK-033：蓝牙控制子页（订阅 HID 状态变化；先单刷一次拿当前真值）
        labCtrl.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CardA11yService.noteOwnUiForeground(false);
        labCtrl.onPause();                      // 摘掉会话 / HID 监听（离页不持引用）
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        labCtrl.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        labCtrl.onRequestPermissionsResult(req, perms, results);
    }
}
