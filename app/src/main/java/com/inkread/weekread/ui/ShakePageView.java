package com.inkread.weekread.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.inkread.weekread.R;

/**
 * V1.3.0-Beta · TASK-084：手机遥控台「晃动」页（第 5 格，自绘）。
 *
 * <p>1:1 复刻「设置页 → 实验室 → 晃动翻页（手机端）」块的全部能力
 * （总开关 / 灵敏度三档 / 四动作方向 4×3 / 自证映射行），并按 {@code docs/09} 加
 * <b>一条通道门控提示条</b>与<b>一张可折叠说明卡</b>；D2=B ⇒ 再加一张本机卡（🆕 TASK-042 起名为
 * 「本机晃动翻页」，置顶、默认折叠、抬头走大标题档 —— 见下方 TASK-042 段）。
 * 🔴 <b>不含</b>「响应方向（轴使能）两轴」—— 该能力 2026-10-10 第三轮已整组删除
 * （{@code 验证记录/198}）⇒ 本类**没有** {@code onAxisChanged} / {@code axisLr,axisUd}
 * （{@code docs/11 §9.1} 骨架里那两行是删除前的历史残留，实现时**不得照抄**）。
 *
 * <p>🔴🔴 <b>「同一份配置」原则（用户追加要求原话：「保证所有选项会自动同步到实验室的翻页中。」）</b>：
 * 本类<b>不认识</b> {@code core.CardPrefs}（不 import、不读写偏好）—— 只抛<b>语义事件</b>，
 * 由宿主 {@code shell.ConsoleActivity} 映射到与实验室页<b>同一批</b> {@code CardPrefs} 键。
 * 文案也<b>全部复用</b> {@code lab_shake_*} 既有串 ⇒ 任一侧改动即时对另一侧生效，
 * <b>零镜像键、零本地缓存</b>。
 *
 * <p>🔴 <b>三层门控</b>（正交，勿混；四层已降为三层，见 {@code docs/11 §八}）：
 * <ul>
 *   <li><b>L1 角色/通道</b>：{@link State#gate} = {@link #GATE_OFF} ⇒ 提示条转 {@code clay}
 *       + 主体卡片 {@code alpha=0.4}（🔴 <b>置灰 ≠ 禁用</b>：控件仍可点、设置先存着，
 *       等通道就绪即生效 —— {@code docs/11 §5.3}）；</li>
 *   <li><b>L2 总开关</b>：{@link State#enabled}=false ⇒ 灵敏度 / 四动作方向块整块置灰
 *       （保留可见，不跳布局）；</li>
 *   <li><b>L3 单动作方向</b>：「关」({@link #DIR_NONE}) 由检测层判决照走、<b>投递层丢弃</b>
 *       （不发指令）—— 本类只负责把该三值写下去。</li>
 * </ul>
 *
 * <p>🆕 <b>TASK-042（V1.3.0-Beta 第四批 #4）本页折叠结构</b>（自上而下）：
 * ① 页标题 → ② <b>本机卡「本机晃动翻页」</b>
 * （<b>置顶 · 默认折叠 · 抬头取既有「分区抬头」档 = 衬线 16sp</b>）→ ③ 门控提示条
 * →（③b 无加速度计提示）→ ④ <b>遥控卡「晃动遥控翻页」</b>
 * （<b>可折叠 · 默认展开</b>；L1 置灰对象 = <b>整卡含抬头</b> {@link #llPeerBlock}）→ ⑤ 说明卡
 * 「晃动翻页说明」（保持最后）。🔴 折叠只切<b>可见性</b>，折叠态<b>不跨 {@link #rebuild()}</b>
 * （深色轮）保持 —— 与既有 {@code shell/LabPageController.bindFold} 同口径（重建即回默认）。
 *
 * <p>🆕 <b>TASK-049（V1.3.0-Beta 第五批 #6）门控提示条下移</b>：用户要求「未连接时顶端的提示框」
 * 改到「本机晃动翻页」折叠卡<b>下方</b>、「晃动遥控翻页」<b>上方</b> ⇒ 装配序
 * ① → ② 本机卡 → ③ 门控条 →（③b 无加速度计提示）→ ④ 遥控卡 → ⑤ 说明卡：
 * ③ 紧贴它门控的 ④ 遥控卡；③b 紧贴它解释的 ② 本机卡（默认 {@code GONE}，只有真机缺加速度计时
 * 才占位）。🔴 只挪 {@code addView} 装配位置：门控语义、默认折叠态、置灰对象（{@link #llPeerBlock}
 * 整卡 alpha）全部不变。
 *
 * <p>🆕 <b>无加速度计</b>（{@link State#sensorOk}=false）：页面级<b>如实提示</b>、
 * <b>不置灰、不硬拦</b>（{@code docs/09}「UI 层不硬拦能力缺失」/ {@code docs/11 §3.5}）。
 *
 * <p>🔴 只服务手机端（仅 {@code ConsoleActivity} 实例化）；阅读器端 / 墨水屏端零差异。
 */
public class ShakePageView extends LinearLayout {

    // ── 语义常量 ────────────────────────────────────────────────────────────────
    // 🔴 下列三组值的**真值源是 core.CardPrefs**，此处只做「本类不认识 CardPrefs」的等价镜像；
    //    映射一律发生在 shell.ConsoleActivity（同 KeyPadView/KeyboardPageView 的既有口径）。
    //    ⇒ 若 CardPrefs 改了这些字面值，必须同步改这里（编译期无法校验）。

    /** 四动作：左晃 / 右晃 / 上晃 / 下晃 —— 值 == {@code CardPrefs.SHAKE_ACT_*}。 */
    public static final int ACT_LEFT = 0;
    public static final int ACT_RIGHT = 1;
    public static final int ACT_UP = 2;
    public static final int ACT_DOWN = 3;

    /** 单动作三值方向 —— 值 == {@code CardPrefs.SHAKE_DIR_*}。 */
    public static final int DIR_PREV = 0;
    public static final int DIR_NEXT = 1;
    /** 「关」：判决照走、投递层丢弃（{@code CardPrefs.SHAKE_DIR_NONE}）。 */
    public static final int DIR_NONE = 2;

    /** 通道门控态。 */
    public static final int GATE_OK = 0;
    /** 连接方式 = 关闭 ⇒ 提示条转 clay + 主体置灰（不拦设置）。 */
    public static final int GATE_OFF = 1;

    /** 本机「翻页方式」—— 值 == {@code CardPrefs.SHAKE_LOCAL_MODE_*}。 */
    public static final int MODE_TAP = 0;
    public static final int MODE_SWIPE = 1;

    /**
     * 交互回调（全部为**语义事件**，落盘与 {@code ShakeDetector} 通知由宿主 shell 负责）。
     */
    public interface Listener {
        /** L2 总开关。 */
        void onShakeEnabled(boolean on);

        /** 灵敏度：0 低 / 1 中 / 2 高。 */
        void onSensitivity(int level);

        /** 遥控（对端）某动作的三值方向。 */
        void onActDirChanged(int act, int dir);

        /** 本机晃动总开关。 */
        void onLocalEnabled(boolean on);

        /** 本机翻页方式（{@link #MODE_TAP} / {@link #MODE_SWIPE}）。 */
        void onLocalMode(int mode);

        /** 本机某动作的三值方向。 */
        void onLocalActDirChanged(int act, int dir);

        /** 点门控提示条 ⇒ 跳「连接」页。 */
        void onOpenConnect();
    }

    /**
     * 渲染入参（纯数据值对象；由 shell 从 {@code CardPrefs} 组装，本类只读不写）。
     */
    public static final class State {
        /** {@link #GATE_OK} / {@link #GATE_OFF}。 */
        public int gate = GATE_OK;
        /** 正常态提示条里的连接方式短名（热点连接 / 蓝牙连接）。 */
        public String gateArg = "";
        /** L2 总开关。 */
        public boolean enabled;
        /** 灵敏度 0/1/2。 */
        public int sens = 1;
        /** 四动作三值方向，下标 = {@link #ACT_LEFT}/{@link #ACT_RIGHT}/{@link #ACT_UP}/{@link #ACT_DOWN}。 */
        public int[] dir = {DIR_PREV, DIR_NEXT, DIR_PREV, DIR_NEXT};
        /** 加速度计**真注册探针**结果（false ⇒ 页面级如实提示，不置灰）。 */
        public boolean sensorOk = true;
        /** 本机晃动总开关（与角色/通道无关）。 */
        public boolean localEnabled;
        /** 本机翻页方式。 */
        public int localMode = MODE_TAP;
        /** 本机四动作三值方向（下标同 {@link #dir}）。 */
        public int[] localDir = {DIR_PREV, DIR_NEXT, DIR_PREV, DIR_NEXT};
    }

    // ── 状态 ────────────────────────────────────────────────────────────────────

    private Listener listener;

    /**
     * 🔴 <b>防回环</b>：{@link #setState} 期间回填控件会触发 {@code OnCheckedChange}
     * ⇒ 必须与 {@code LabPageController.mShakeUiSyncing} 同款布尔守卫，
     * 否则一次回填会把同样的值再写一遍盘（甚至自激）。
     */
    private boolean mSyncing;

    /** 最近一次 setState 的入参 —— 供 {@link #rebuild()}（深色切换）后原样重放。 */
    private State mLast;

    /** 加速度计真可用性（🔴 探针只在构造时跑一次；判据见 {@link #probeAccelerometer}）。 */
    private boolean mHasAccel = true;

    // ── 子 View ─────────────────────────────────────────────────────────────────
    private TextView tvGate;
    private TextView tvSensorWarn;
    /** 🆕 TASK-042：遥控卡折叠块（抬头 {@link #foldPeer} + 内容 {@link #cardPeer}）—— L1 置灰的对象。 */
    private LinearLayout llPeerBlock;
    private FoldHintView foldPeer;
    private LinearLayout cardPeer;
    private CheckBox cbEnabled;
    private LinearLayout llSens;
    private RadioGroup rgSens;
    private RadioButton rbSensLow;
    private RadioButton rbSensMid;
    private RadioButton rbSensHigh;
    private LinearLayout llPeerDir;
    private final RadioGroup[] rgPeerDir = new RadioGroup[4];
    private final RadioButton[] rbPeerPrev = new RadioButton[4];
    private final RadioButton[] rbPeerNext = new RadioButton[4];
    private final RadioButton[] rbPeerOff = new RadioButton[4];
    private TextView tvPeerMap;
    private FoldHintView foldHint;
    private TextView tvHintBody;
    private FoldHintView foldLocal;
    private LinearLayout llLocalBody;
    private CheckBox cbLocal;
    private LinearLayout llLocalMode;
    private RadioGroup rgLocalMode;
    private RadioButton rbLocalTap;
    private RadioButton rbLocalSwipe;
    private LinearLayout llLocalDir;
    private final RadioGroup[] rgLocalDir = new RadioGroup[4];
    private final RadioButton[] rbLocalPrev = new RadioButton[4];
    private final RadioButton[] rbLocalNext = new RadioButton[4];
    private final RadioButton[] rbLocalOff = new RadioButton[4];
    private TextView tvLocalNote;

    /** 四行动作标签资源（左/右/上/下）—— 索引 == {@link #ACT_LEFT}..{@link #ACT_DOWN}。 */
    private static final int[] ACT_LABEL = {
            R.string.lab_shake_local_dir_left,
            R.string.lab_shake_local_dir_right,
            R.string.lab_shake_local_dir_up,
            R.string.lab_shake_local_dir_down};

    /**
     * 🆕 TASK-042 · 折叠卡抬头「大标题」档 = 既有**分区抬头**档（衬线 16sp）。
     * 先例：{@code ui/ConnectPageView.java} 的卡片抬头 {@code title.setTextSize(16f)} + {@code InkTheme.serif()}；
     * 页标题 20sp 只给页面本身（见 {@code buildUi} ①）⇒ 抬头取 16sp，不与页标题同级。
     *
     * <p>🔴 手法说明：{@link FoldHintView} 手机端标度<b>写死 15sp</b> 且<b>没有字号 API</b>，
     * 而该文件属别卡写域（TASK-042 写域 = 本文件 + 布局 + strings）⇒ 这里用
     * <b>字号倍率上下文</b>换算：只改 {@code Configuration.fontScale}（15 × 16/15 = 16），
     * 纸张 / 墨色 / 深色 / 角色判定（{@link FoldHintView} 读的 {@code install_role}）全部不受影响；
     * 倍率按<b>当前</b> fontScale 相乘 ⇒ 系统字体放大设置照旧生效（不做绝对值覆盖）。
     */
    private static final float FOLD_TITLE_SP = 16f;

    /** {@link FoldHintView} 手机端自带标度（sp）—— 见 {@code ui/FoldHintView.java} 的 {@code recompute()}。 */
    private static final float FOLD_BASE_SP = 15f;

    /** 见 {@link #FOLD_TITLE_SP}：返回把折叠卡抬头字号换算到 16sp 的等价上下文。 */
    private static Context foldTitleContext(Context c) {
        Configuration cur = c.getResources().getConfiguration();
        Configuration cfg = new Configuration(cur);
        cfg.fontScale = cur.fontScale * (FOLD_TITLE_SP / FOLD_BASE_SP);
        return c.createConfigurationContext(cfg);
    }

    // ── 构造 / 重建 ─────────────────────────────────────────────────────────────

    public ShakePageView(Context c) {
        this(c, null);
    }

    public ShakePageView(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(VERTICAL);
        int pad = (int) InkTheme.dp(c, 16f);
        setPadding(pad, pad, pad, pad);
        buildUi();
        mHasAccel = probeAccelerometer(c);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * 本页加速度计判据（供 shell 组装 {@link State#sensorOk}）。
     *
     * <p>🔴 与 {@code LabPageController.hasAccelerometer()} <b>同一判据的复制品</b>
     * （{@code docs/11 §9.3} 备选方案：不把方法提升进 {@code remote.ShakeDetector}
     * —— 那属于已验收 {@code TASK-083} 的写域，须另开卡）。两处**必须**同步修改。
     */
    public boolean hasUsableAccelerometer() {
        return mHasAccel;
    }

    /**
     * 🆕 TASK-041 同款口径：色板切换后**重建内部视图树**（所有子 View 的着色在 {@link #buildUi()}
     * 时定死，不像自绘 View 那样每次 onDraw 重读 token）⇒ 换色只能重建；
     * 重建后**原样重放**最近一次 {@link State}（否则切深色会丢选中态）。
     */
    public void rebuild() {
        State keep = mLast;
        removeAllViews();
        int pad = (int) InkTheme.dp(getContext(), 16f);
        setPadding(pad, pad, pad, pad);
        buildUi();
        if (keep != null) {
            setState(keep);
        }
    }

    // ── 构建（一次性）────────────────────────────────────────────────────────────

    private void buildUi() {
        final Context c = getContext();

        // ① 页标题「晃动翻页（手机端）」（衬线 20sp / ink / 高 48dp）—— docs/11 §六 #1
        TextView title = new TextView(c);
        title.setText(R.string.lab_shake_label);
        title.setTextSize(20f);
        title.setTypeface(InkTheme.serif());
        title.setTextColor(InkTheme.ink(c));
        title.setGravity(Gravity.CENTER_VERTICAL);
        addView(title, lp((int) InkTheme.dp(c, 48f), 0));

        // ③ 通道门控提示条：正常 bambooWeak 底 + bamboo 字；异常 clayWeak 底 + clay 字；
        //    点整条 ⇒ 跳「连接」页（docs/11 §5.2/§5.3、§六 #2）。
        //    🆕 TASK-049：创建留在原处，**装配位置**已下移到 ② 本机卡之后（见下方「③ 装配」段）。
        tvGate = new TextView(c);
        tvGate.setTextSize(13f);
        tvGate.setGravity(Gravity.CENTER_VERTICAL);
        tvGate.setSingleLine(true);
        tvGate.setEllipsize(TextUtils.TruncateAt.END);
        int gpx = (int) InkTheme.dp(c, 12f);
        tvGate.setPadding(gpx, 0, gpx, 0);
        tvGate.setClickable(true);
        tvGate.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onOpenConnect();
            }
        });
        LayoutParams glp = lp((int) InkTheme.dp(c, 48f), (int) InkTheme.dp(c, 10f));

        // ③b 页面级「无加速度计」如实提示（复用既有 lab_shake_local_unsupported；
        //     🔴 不在 ② 的「本机晃动翻页」折叠卡里重复一次 —— 全页只出现一处）。
        //     🆕 TASK-049：装配位置紧贴 ② 本机卡下方（见下方「③ 装配」段）。
        tvSensorWarn = new TextView(c);
        tvSensorWarn.setText(R.string.lab_shake_local_unsupported);
        tvSensorWarn.setTextSize(12f);
        tvSensorWarn.setTextColor(InkTheme.clay(c));
        tvSensorWarn.setLineSpacing(0f, 1.4f);
        tvSensorWarn.setVisibility(View.GONE);

        // ② 本机卡（🆕 TASK-042：**置顶**、**默认折叠**、抬头用既有「分区抬头」档 = 衬线 16sp）
        //    🔴 本机晃动**不依赖角色与通道**（CardPrefs 注释口径）⇒ 不受 L1 门控置灰影响。
        //    正文（本机开关 + 翻页方式 + 本机 4×3 + 自证行）在本方法后段挂进 llLocalBody。
        foldLocal = new FoldHintView(foldTitleContext(c));
        foldLocal.setTitle(getResources().getString(R.string.fold_shake_local_title));
        foldLocal.setContentDescription(getResources().getString(R.string.fold_shake_local_title));
        foldLocal.setExpanded(false);                                        // 默认折叠
        addView(foldLocal, lp((int) InkTheme.dp(c, 44f), 0));
        llLocalBody = new LinearLayout(c);
        llLocalBody.setOrientation(VERTICAL);
        llLocalBody.setVisibility(View.GONE);
        addView(llLocalBody, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        foldLocal.setListener(new FoldHintView.Listener() {
            @Override
            public void onToggle(boolean expanded) {
                llLocalBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
            }
        });

        // ③ 装配（🆕 TASK-049 / 用户第五批 #6）：门控提示条 + 无加速度计提示 插到
        //    ② 本机卡 与 ④ 遥控卡 **之间** —— 本轮**只挪这两行 addView**，其它分支一行未改。
        //    · ③ 门控条紧贴 ④ 遥控卡之上：它门控的就是遥控卡（顺序断言见 验证记录/225）；
        //    · ③b 无加速度计提示紧贴 ② 本机卡之下：它只解释「本机晃动」不可用，与遥控卡无关；
        //      默认 GONE ⇒ 只有真机缺加速度计时才占位（该提示位置的取舍理由见 225）。
        addView(tvSensorWarn, lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 4f)));
        addView(tvGate, glp);

        // ④ 遥控卡（🆕 TASK-042）：可折叠、抬头「晃动遥控翻页」、**默认展开**；
        //    整卡（抬头 + 内容）受 L1 门控整体置灰（置灰对象 = llPeerBlock，见 setState ②）。
        //    内容 = 总开关 → 灵敏度三档 → 四动作 4×3 → 自证映射行（TASK-084 原样，未改）。
        llPeerBlock = new LinearLayout(c);
        llPeerBlock.setOrientation(VERTICAL);
        addView(llPeerBlock, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        foldPeer = new FoldHintView(c);
        foldPeer.setTitle(getResources().getString(R.string.fold_shake_remote_title));
        foldPeer.setContentDescription(getResources().getString(R.string.fold_shake_remote_title));
        foldPeer.setExpanded(true);                                          // 默认展开
        llPeerBlock.addView(foldPeer, lp((int) InkTheme.dp(c, 44f), (int) InkTheme.dp(c, 12f)));
        cardPeer = cardInto(llPeerBlock);
        foldPeer.setListener(new FoldHintView.Listener() {
            @Override
            public void onToggle(boolean expanded) {
                cardPeer.setVisibility(expanded ? View.VISIBLE : View.GONE);
            }
        });

        cbEnabled = new CheckBox(c);
        cbEnabled.setText(R.string.console_shake_enable);   // 「启用晃动翻页」
        cbEnabled.setTextSize(14f);
        cbEnabled.setTextColor(InkTheme.ink(c));
        cbEnabled.setContentDescription(getResources().getString(R.string.console_shake_enable));
        cbEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mSyncing) return;
                if (listener != null) listener.onShakeEnabled(checked);
            }
        });
        cardPeer.addView(cbEnabled, lp((int) InkTheme.dp(c, 48f), 0));

        // 灵敏度：低 / 中 / 高（横向一行，行高 48dp）
        llSens = new LinearLayout(c);
        llSens.setOrientation(HORIZONTAL);
        llSens.setGravity(Gravity.CENTER_VERTICAL);
        TextView sensLabel = text(R.string.lab_shake_sens_label, 14f, InkTheme.ink(c));
        llSens.addView(sensLabel, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        rgSens = new RadioGroup(c);
        rgSens.setOrientation(HORIZONTAL);
        rbSensLow = radio(R.string.lab_shake_sens_low, 14f, true);
        rbSensMid = radio(R.string.lab_shake_sens_mid, 14f, true);
        rbSensHigh = radio(R.string.lab_shake_sens_high, 14f, true);
        rgSens.addView(rbSensLow);
        rgSens.addView(rbSensMid);
        rgSens.addView(rbSensHigh);
        rgSens.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                if (mSyncing) return;
                if (listener == null) return;
                if (id == rbSensLow.getId()) listener.onSensitivity(0);
                else if (id == rbSensHigh.getId()) listener.onSensitivity(2);
                else if (id == rbSensMid.getId()) listener.onSensitivity(1);
            }
        });
        llSens.addView(rgSens, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        cardPeer.addView(llSens, lp((int) InkTheme.dp(c, 48f), 0));

        // 四动作 × 三值（四行单列 —— 三单选并排已 ≈267px，两列会溢出；与实验室页同形）
        llPeerDir = new LinearLayout(c);
        llPeerDir.setOrientation(VERTICAL);
        llPeerDir.addView(text(R.string.lab_shake_peer_dir_label, 14f, InkTheme.ink(c)),
                lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 4f)));
        for (int i = 0; i < 4; i++) {
            final int act = i;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(dirLabel(ACT_LABEL[i]),
                    new LayoutParams((int) InkTheme.dp(c, 44f), LayoutParams.WRAP_CONTENT));
            rgPeerDir[i] = new RadioGroup(c);
            rgPeerDir[i].setOrientation(HORIZONTAL);
            final String rowName = getResources().getString(ACT_LABEL[i]);
            rbPeerPrev[i] = radio(R.string.lab_shake_page_prev, 14f, true);
            rbPeerNext[i] = radio(R.string.lab_shake_page_next, 14f, true);
            rbPeerOff[i] = radio(R.string.lab_shake_page_off, 14f, true);
            // 每个可点元素带 contentDescription（docs/09 §七）—— 读屏需「动作 + 选项」才不歧义
            rbPeerPrev[i].setContentDescription(rowName + " " + getResources().getString(R.string.lab_shake_page_prev));
            rbPeerNext[i].setContentDescription(rowName + " " + getResources().getString(R.string.lab_shake_page_next));
            rbPeerOff[i].setContentDescription(rowName + " " + getResources().getString(R.string.lab_shake_page_off));
            rgPeerDir[i].addView(rbPeerPrev[i]);
            rgPeerDir[i].addView(rbPeerNext[i]);
            rgPeerDir[i].addView(rbPeerOff[i]);
            rgPeerDir[i].setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup g, int id) {
                    if (mSyncing || id < 0) return;
                    int dir = (id == rbPeerOff[act].getId()) ? DIR_NONE
                            : (id == rbPeerNext[act].getId() ? DIR_NEXT : DIR_PREV);
                    if (listener != null) listener.onActDirChanged(act, dir);
                }
            });
            row.addView(rgPeerDir[i], new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            llPeerDir.addView(row, lp((int) InkTheme.dp(c, 48f), 0));
        }
        cardPeer.addView(llPeerDir, lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 6f)));

        // 自证映射行（12sp ink3 / 行高 1.3 —— 映射永远以屏上的字为准）
        tvPeerMap = text(0, 12f, InkTheme.ink3(c));
        tvPeerMap.setLineSpacing(0f, 1.3f);
        cardPeer.addView(tvPeerMap, lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 4f)));

        // ⑤ 说明卡「晃动翻页说明」（TASK-042：**保持最后**，默认收起；内容 = 复用 lab_shake_hint，主屏不铺段落）
        foldHint = new FoldHintView(c);
        foldHint.setTitle(getResources().getString(R.string.fold_shake_peer));
        foldHint.setContentDescription(getResources().getString(R.string.fold_shake_peer));
        LayoutParams hlp = lp((int) InkTheme.dp(c, 44f), (int) InkTheme.dp(c, 12f));
        addView(foldHint, hlp);
        tvHintBody = text(R.string.lab_shake_hint, 12f, InkTheme.ink2(c));
        tvHintBody.setLineSpacing(0f, 1.6f);
        tvHintBody.setVisibility(View.GONE);
        addView(tvHintBody, lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 6f)));
        foldHint.setListener(new FoldHintView.Listener() {
            @Override
            public void onToggle(boolean expanded) {
                tvHintBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
            }
        });

        // ②（续）本机卡正文：本机开关 + 翻页方式 + 本机 4×3 + 自证行 —— 挂进 ② 建好的 llLocalBody
        //    （🆕 TASK-042：卡头/正文容器已上移到 ②，此处只负责正文，内容一行未改）

        TextView intro = text(R.string.lab_shake_local_intro, 12f, InkTheme.ink2(c));
        intro.setLineSpacing(0f, 1.5f);
        llLocalBody.addView(intro, lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 6f)));
        TextView combine = text(R.string.lab_shake_local_combine_tip, 12f, InkTheme.ink2(c));
        combine.setLineSpacing(0f, 1.5f);
        llLocalBody.addView(combine, lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 4f)));

        LinearLayout cardLocal = cardInto(llLocalBody);

        cbLocal = new CheckBox(c);
        cbLocal.setText(R.string.lab_shake_local_label);
        cbLocal.setTextSize(14f);
        cbLocal.setTextColor(InkTheme.ink(c));
        cbLocal.setContentDescription(getResources().getString(R.string.lab_shake_local_label));
        cbLocal.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mSyncing) return;
                if (listener != null) listener.onLocalEnabled(checked);
            }
        });
        cardLocal.addView(cbLocal, lp((int) InkTheme.dp(c, 48f), 0));

        // 翻页方式（竖排二选一，与实验室页同形）
        llLocalMode = new LinearLayout(c);
        llLocalMode.setOrientation(VERTICAL);
        llLocalMode.addView(text(R.string.lab_shake_local_mode_label, 14f, InkTheme.ink(c)),
                lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 4f)));
        rgLocalMode = new RadioGroup(c);
        rgLocalMode.setOrientation(VERTICAL);
        rbLocalTap = radio(R.string.lab_shake_local_mode_tap, 14f, true);
        rbLocalSwipe = radio(R.string.lab_shake_local_mode_swipe, 14f, true);
        rgLocalMode.addView(rbLocalTap);
        rgLocalMode.addView(rbLocalSwipe);
        rgLocalMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                if (mSyncing || id < 0 || listener == null) return;
                listener.onLocalMode(id == rbLocalSwipe.getId() ? MODE_SWIPE : MODE_TAP);
            }
        });
        llLocalMode.addView(rgLocalMode, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        llLocalMode.addView(text(R.string.lab_shake_local_portrait_note, 12f, InkTheme.ink3(c)),
                lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 2f)));
        cardLocal.addView(llLocalMode, lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 4f)));

        // 本机四动作 × 三值
        llLocalDir = new LinearLayout(c);
        llLocalDir.setOrientation(VERTICAL);
        llLocalDir.addView(text(R.string.lab_shake_local_dir_label, 14f, InkTheme.ink(c)),
                lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 4f)));
        for (int i = 0; i < 4; i++) {
            final int act = i;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(dirLabel(ACT_LABEL[i]),
                    new LayoutParams((int) InkTheme.dp(c, 44f), LayoutParams.WRAP_CONTENT));
            rgLocalDir[i] = new RadioGroup(c);
            rgLocalDir[i].setOrientation(HORIZONTAL);
            final String rowName = getResources().getString(ACT_LABEL[i]);
            rbLocalPrev[i] = radio(R.string.lab_shake_page_prev, 14f, true);
            rbLocalNext[i] = radio(R.string.lab_shake_page_next, 14f, true);
            rbLocalOff[i] = radio(R.string.lab_shake_page_off, 14f, true);
            rbLocalPrev[i].setContentDescription(rowName + " " + getResources().getString(R.string.lab_shake_page_prev));
            rbLocalNext[i].setContentDescription(rowName + " " + getResources().getString(R.string.lab_shake_page_next));
            rbLocalOff[i].setContentDescription(rowName + " " + getResources().getString(R.string.lab_shake_page_off));
            rgLocalDir[i].addView(rbLocalPrev[i]);
            rgLocalDir[i].addView(rbLocalNext[i]);
            rgLocalDir[i].addView(rbLocalOff[i]);
            rgLocalDir[i].setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup g, int id) {
                    if (mSyncing || id < 0) return;
                    int dir = (id == rbLocalOff[act].getId()) ? DIR_NONE
                            : (id == rbLocalNext[act].getId() ? DIR_NEXT : DIR_PREV);
                    if (listener != null) listener.onLocalActDirChanged(act, dir);
                }
            });
            row.addView(rgLocalDir[i], new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            llLocalDir.addView(row, lp((int) InkTheme.dp(c, 48f), 0));
        }
        cardLocal.addView(llLocalDir, lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 6f)));

        // 本机自证行（未开启 / 已开启 · 当前映射）
        tvLocalNote = text(0, 12f, InkTheme.ink3(c));
        tvLocalNote.setLineSpacing(0f, 1.3f);
        cardLocal.addView(tvLocalNote, lp(LayoutParams.WRAP_CONTENT, (int) InkTheme.dp(c, 4f)));
    }

    // ── 状态回填 ────────────────────────────────────────────────────────────────

    /**
     * 幂等回填（宿主每次 {@code refresh()} / {@code onResume()} 调）。
     *
     * <p>🔴 全流程置于 {@link #mSyncing} 守卫内，回填**绝不回写偏好**；
     * 置灰 / 显隐这类不触发监听的操作放在守卫外（与 {@code LabPageController.refreshShakeUi} 同序）。
     */
    public void setState(State s) {
        if (s == null || tvGate == null) return;
        mLast = s;
        final Context c = getContext();

        mSyncing = true;
        try {
            cbEnabled.setChecked(s.enabled);
            rgSens.check(s.sens == 0 ? rbSensLow.getId()
                    : (s.sens == 2 ? rbSensHigh.getId() : rbSensMid.getId()));
            for (int i = 0; i < 4; i++) {
                rgPeerDir[i].check(s.dir[i] == DIR_NEXT ? rbPeerNext[i].getId()
                        : (s.dir[i] == DIR_NONE ? rbPeerOff[i].getId() : rbPeerPrev[i].getId()));
            }
            cbLocal.setChecked(s.localEnabled);
            rgLocalMode.check(s.localMode == MODE_SWIPE ? rbLocalSwipe.getId() : rbLocalTap.getId());
            for (int i = 0; i < 4; i++) {
                rgLocalDir[i].check(s.localDir[i] == DIR_NEXT ? rbLocalNext[i].getId()
                        : (s.localDir[i] == DIR_NONE ? rbLocalOff[i].getId() : rbLocalPrev[i].getId()));
            }
        } finally {
            mSyncing = false;
        }

        // ① 门控提示条（正常 bambooWeak/bamboo ↔ 异常 clayWeak/clay；点整条 ⇒ 跳连接页）
        final boolean off = (s.gate == GATE_OFF);
        String gateText = off
                ? getResources().getString(R.string.console_shake_gate_off)
                : getResources().getString(R.string.console_shake_gate_ready, s.gateArg);
        gateText = gateText + "  →  " + getResources().getString(R.string.console_nav_connect);
        tvGate.setText(gateText);
        tvGate.setContentDescription(gateText);
        tvGate.setTextColor(off ? InkTheme.clay(c) : InkTheme.bamboo(c));
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(off ? InkTheme.clayWeak(c) : InkTheme.bambooWeak(c));
        gb.setCornerRadius(InkTheme.dp(c, 14f));
        tvGate.setBackground(gb);

        // ② L1：遥控卡**整卡**（抬头 + 内容）整体 40% 置灰 —— 🔴 置灰 ≠ 禁用（控件仍可点，设置先存着）
        //    🆕 TASK-042：置灰对象从内容卡 cardPeer 改为折叠块 llPeerBlock ⇒ 折叠抬头一并置灰。
        if (llPeerBlock != null) llPeerBlock.setAlpha(off ? 0.4f : 1f);

        // ③ L2：总开关关 ⇒ 灵敏度 / 四动作方向块整块置灰（保留可见，不跳布局）
        setEnabledDeep(llSens, s.enabled);
        setEnabledDeep(llPeerDir, s.enabled);
        if (tvPeerMap != null) tvPeerMap.setEnabled(s.enabled);

        // ④ 无加速度计 ⇒ 页面级如实提示（不置灰、不硬拦）
        if (tvSensorWarn != null) {
            tvSensorWarn.setVisibility(s.sensorOk ? View.GONE : View.VISIBLE);
        }

        // ⑤ 自证映射行（与 LabPageController.refreshShakeUi 同一口径）
        if (tvPeerMap != null) {
            String prev = getResources().getString(R.string.lab_shake_page_prev);
            String next = getResources().getString(R.string.lab_shake_page_next);
            String offS = getResources().getString(R.string.lab_shake_page_off);
            tvPeerMap.setText(getResources().getString(R.string.lab_shake_map_now,
                    dirLabel(s.dir[ACT_LEFT], prev, next, offS),
                    dirLabel(s.dir[ACT_RIGHT], prev, next, offS),
                    dirLabel(s.dir[ACT_UP], prev, next, offS),
                    dirLabel(s.dir[ACT_DOWN], prev, next, offS)));
        }

        // ⑥ 本机：开关关 ⇒ 方式 / 方向块**隐藏**（与实验室页一致，非置灰）；自证行照写
        llLocalMode.setVisibility(s.localEnabled ? View.VISIBLE : View.GONE);
        llLocalDir.setVisibility(s.localEnabled ? View.VISIBLE : View.GONE);
        if (tvLocalNote != null) {
            if (!s.localEnabled) {
                tvLocalNote.setText(R.string.lab_shake_local_note_off);
            } else {
                String prev = getResources().getString(R.string.lab_shake_page_prev);
                String next = getResources().getString(R.string.lab_shake_page_next);
                String offS = getResources().getString(R.string.lab_shake_page_off);
                String map = getResources().getString(R.string.lab_shake_map_now,
                        dirLabel(s.localDir[ACT_LEFT], prev, next, offS),
                        dirLabel(s.localDir[ACT_RIGHT], prev, next, offS),
                        dirLabel(s.localDir[ACT_UP], prev, next, offS),
                        dirLabel(s.localDir[ACT_DOWN], prev, next, offS));
                tvLocalNote.setText(getResources().getString(R.string.lab_shake_local_note_on, map));
            }
        }
    }

    // ── 小部件工厂（口径与 ConnectPageView 一致）────────────────────────────────

    /**
     * 一张分组卡（paper2 底 + 0.5dp line 描边 + 14dp 圆角 + 16dp 内边距），挂到指定父容器。
     *
     * <p>🆕 TASK-042：两张卡都改成挂进各自的折叠块（遥控卡 → 遥控卡折叠块、本机卡 → 本机卡折叠块）
     * ⇒ 原先「直接挂本页」的 {@code card()} 包装已无调用点，一并删除（不引入死代码）。
     */
    private LinearLayout cardInto(LinearLayout parent) {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(InkTheme.paper2(c));
        bg.setCornerRadius(InkTheme.dp(c, 14f));
        bg.setStroke(Math.max(1, (int) InkTheme.dp(c, 0.5f)), InkTheme.line(c));
        box.setBackground(bg);
        int p = (int) InkTheme.dp(c, 16f);
        box.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) InkTheme.dp(c, 12f);
        box.setLayoutParams(lp);
        parent.addView(box);
        return box;
    }

    private TextView text(int res, float sizeSp, int color) {
        TextView tv = new TextView(getContext());
        if (res != 0) tv.setText(res);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        return tv;
    }

    /** 单选按钮（🔴 行高由容器 48dp 承担；本页无墨水屏近黑 accent 问题 ⇒ 不设 buttonTint）。 */
    private RadioButton radio(int res, float sizeSp, boolean clickable) {
        RadioButton rb = new RadioButton(getContext());
        rb.setId(View.generateViewId());
        rb.setText(res);
        rb.setTextSize(sizeSp);
        rb.setTextColor(InkTheme.ink(getContext()));
        rb.setGravity(Gravity.CENTER_VERTICAL);
        rb.setClickable(clickable);
        return rb;
    }

    /** 行首动作标签（固定 44dp 宽，左对齐 —— docs/11 §六 #7）。 */
    private TextView dirLabel(int res) {
        TextView tv = text(res, 13f, InkTheme.ink(getContext()));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        return tv;
    }

    /** 行 LayoutParams：高 = heightDp，topMargin = topDp。 */
    private LayoutParams lp(int heightPx, int topMarginPx) {
        LayoutParams p = new LayoutParams(LayoutParams.MATCH_PARENT, heightPx);
        p.topMargin = topMarginPx;
        return p;
    }

    // ── 工具 ────────────────────────────────────────────────────────────────────

    /** 三值方向 → 显示文案（由调用方传三个文案，避免每格重查资源）—— 同 LabPageController.dirLabel。 */
    private static String dirLabel(int dir, String prev, String next, String off) {
        if (dir == DIR_NEXT) return next;
        if (dir == DIR_NONE) return off;
        return prev;
    }

    /** 递归置灰 / 恢复一整块（含容器与全部子 View）—— 同 LabPageController.setEnabledDeep。 */
    private static void setEnabledDeep(View v, boolean enabled) {
        if (v == null) return;
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                setEnabledDeep(g.getChildAt(i), enabled);
            }
        }
    }

    /**
     * 加速度计**是否真的可用**（不是"清单里有"）—— {@code LabPageController.hasAccelerometer()}
     * 的逐行复制（交叉引用：{@code docs/11 §9.3} 备选方案；提升进 {@code remote.ShakeDetector}
     * 属 {@code TASK-083} 写域，本卡不碰）。
     *
     * <p>🔴 判据必须是「**真注册一次 + 看返回值**」：S4（墨水屏）上 {@code getDefaultSensor}
     * 非 null、{@code pm list features} 也声明了加速度计，但 {@code registerListener} 恒 false
     * （HAL 无后端，见 {@code 验证记录/102}、{@code 验证记录/182}）⇒ 只判前者会**误判为可用**，
     * 真机表现就是"开关能勾、勾了没反应"。
     */
    private static boolean probeAccelerometer(Context c) {
        SensorManager sm = null;
        SensorEventListener probe = null;
        try {
            sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return false;
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc == null) return false;
            probe = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            boolean ok = sm.registerListener(probe, acc, SensorManager.SENSOR_DELAY_GAME);
            if (!ok) {
                ok = sm.registerListener(probe, acc, SensorManager.SENSOR_DELAY_NORMAL);
            }
            Log.i("ShakePage", "hasAccelerometer: getDefaultSensor!=null, registerListener=" + ok
                    + (ok ? " ⇒ 可用" : " ⇒ 判为不可用（HAL 无后端？见 验证记录/102）"));
            return ok;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (sm != null && probe != null) sm.unregisterListener(probe);
            } catch (Throwable ignore) {
                // 注销失败不影响判据
            }
        }
    }
}
