package com.inkread.weekread.remote;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.inkread.weekread.core.CardPrefs;

/**
 * 手机端「晃动翻页」捕获层（TASK-029 / V1.0.4-beta）。
 *
 * <p>与 {@link RemoteKeyService} 的「音量键捕获」并列，是 phone 角色的**第二条捕获路径**：
 * 检测到手机被甩动 ⇒ 经既有遥控链路（TCP 45678，{@code PAGE_NEXT}/{@code PAGE_PREV}）发指令
 * ⇒ 墨水屏端 {@code RemoteInjector} 翻页。<b>S4 端无需为晃动新增代码（复用既有注入路径）、零新增权限</b>
 * （同卡另顺带修 S4 侧 2 处既有缺陷：R10 几何 / R11 注入串行化，见验证记录 108/111/113）。
 *
 * <h3>🆕 双模式（TASK-073）——本机 / 对端，两个独立实例</h3>
 * 本类自 TASK-073 起同时承载**两种互不相干的用法**，各自一个实例、各自一套门控（见 {@link #sync}）：
 * <ul>
 *   <li><b>对端模式</b>（{@link #sPeer}）—— 晃本机 ⇒ 翻**墨水屏**上的书。需 {@code role=PHONE}
 *       + 总开关 + 有可发通道；投递走 {@link RemoteLinkManager#sendCommand}。</li>
 *   <li><b>本机模式</b>（{@link #sLocal}）—— 晃本机 ⇒ 翻**本机**上的微信读书。只需独立开关
 *       {@code CardPrefs.shake_local_enabled}，**不依赖**角色 / 连接方式；投递走
 *       {@link RemoteInjector#injectLocal}（无障碍手势）；方向由**四动作自选**决定
 *       （{@link CardPrefs#isShakeLocalActNext}，见 {@link #fire}）。</li>
 * </ul>
 * 两开关同时打开时两实例并行采样 ⇒ 两条路径**同时**触发（= TASK-074「一起翻页」口径）；
 * 🆕 此时**对端也切到「手机侧」方向口径** ⇒ 一次晃动两端**同向**翻（见 {@link #fire}）。
 *
 * <h3>算法</h3>
 * <ol>
 *   <li>加速度计 50Hz（{@link #RATE_US}）—— 实测 49.90Hz、抖动 ±0.2ms、零丢包。</li>
 *   <li>低通去重力 {@code g = α·g + (1−α)·a}，线性加速度 {@code lin = a − g}。</li>
 *   <li><b>双峰反转</b>：任一条轴通道上出现**反号峰**、间隔落在
 *       [最小峰间隔, 双峰窗口] ms、峰值幅度 ≥ 触发阈值 ⇒ 记为一次「甩动候选」（触发）。
 *       四个参数（阈值 / 冷却 / 窗口 / 峰间隔）由<b>灵敏度档位</b>决定，见 {@link #SENS_THRESHOLD}。</li>
 *   <li><b>方向判决</b>（🆕 2026-10-03 修复）：<b>不再取"触发轴首峰"</b>，改用
 *       「能量主导轴 + 主峰符号 + 盘整比门槛」——见 {@link #judge} 与
 *       {@code artifacts/2026-10-03_TASK029方向判定_标定结果与建议.md}（T029L 实测）。</li>
 *   <li><b>冷却</b>：发指令后这段时间内不再响应（防连翻）。弃权不占冷却。</li>
 * </ol>
 *
 * <h3>🔴 方向判决修复（TASK-029，2026-10-03，依据 T029L 四向标定）</h3>
 * 旧法（取"触发轴首峰"）有两个缺陷：
 * <ul>
 *   <li><b>串轴</b>：三通道「先到先得」⇒ 甩左右时 z 轴偶发先凑齐反转 ⇒ 非主轴截胡
 *       （T029L 段2 有 5/11 次被 z 轴截胡）。</li>
 *   <li><b>反向预摆</b>：甩动起手常先出现一个**小的反向预摆峰**（约 ±170，刚过阈值），
 *       随后才是**主冲峰**（±500~900）⇒ 取"首峰"被判反方向（段3「上晃」一致率仅 56%）。</li>
 * </ul>
 * 新法：<b>判轴</b>=窗口内三轴 {@code Σ|v|} 最大者；<b>判符号</b>=主导轴单点绝对值最大点的符号；
 * <b>门槛</b>=主导轴「主峰 / 异号次大峰」比 ≥ 组门槛（x 组 {@link #RATIO_X} / y·z 组 {@link #AMBIG_RATIO}），
 * 且主峰幅度 ≥ 组阈值（x 组 {@link #TH_X} / y·z 组档位阈值），不足则<b>弃权</b>。
 * 实测全局方向一致率 75.8% → 84.8%（不加门槛）/ ≈96%（加门槛，代价 ≈30% 弱甩被忽略）。
 * <p>🔴 方向标定（2026-10-04 修正，以**用户实际握持**为准）：T029L 原始标定是<b>屏幕朝自己</b>
 * 握持下做的（左晃 = x 主峰 +），但用户**实际使用是背面朝自己**，轴向符号整体相反 ⇒ 实机为
 * <b>左晃 = x 主峰 − / 右晃 = x 主峰 + / 上晃 = z 主峰 − / 下晃 = z 主峰 +</b>。
 * 对应指令见 {@link #fire}：默认（两个反转开关均关）=
 * <b>左晃→上一页 / 右晃→下一页 / 上晃→上一页 / 下晃→下一页</b>（与 {@code CardPrefs} 默认注释一致）。
 *
 * <h3>🆕 灵敏度三档（TASK-029 手感优化，2026-10-03）</h3>
 * 「低 / 中 / 高」三档，**默认中**；每档四个参数以中档为圆心同向偏移 ≈15%
 * （低 = 更难触发，高 = 更易触发）。档位存 {@code CardPrefs.remote_shake_sens}，
 * 改档后由设置页调 {@link #reload(Context)} 即时重建采样。
 *
 * <h3>🔴 标定对方案初值的三处修正（实测依据见 EVIDENCE §5 / §8）</h3>
 * <ul>
 *   <li><b>判轴改为 x / z</b>：方案初值写「{@code |lin_x| vs |lin_y|}」，但实测「上下晃（机身后仰/前倾）」
 *       的主轴是 <b>z</b>（左右晃才是 x）。故本实现**并行跑 x / y / z 三条通道**的检测器，
 *       谁先凑齐「双峰反转」谁生效 —— 比硬编码二选一更稳，换握姿也不会瞎判。</li>
     *   <li><b>冷却 1.4s → 0.8s → 1.0s → 0.85s</b>：实测相邻甩动间隔约 1.0–1.5s；1.4s 会**吞掉连甩**（先降到 0.8s），
     *       但 0.8s 下走路误触发较密（A10 实测间隔中位 1.17s / 最短 0.76s）⇒ 2026-10-03 先取 1.0s，
     *       同日随灵敏度重定把中档下调至 <b>0.85s</b>（见 {@link #SENS_COOLDOWN_MS}）。</li>
 *   <li><b>「≥2 次方向反转」→ 1 次反转（2 个峰）</b>：实测一次甩动的波形就是「一个主峰 + 一个回弹峰」
 *       （间隔 221–260ms）。若上机后误触发偏高，把 {@link #MIN_REVERSALS} 提到 2 即可（须先采证据）。</li>
 * </ul>
 *
 * <h3>🔴 已知边界（如实登记，文案不得超前）</h3>
 * <ul>
 *   <li><b>息屏 = 未定论（🔴 已推翻旧结论）</b>：旧标定（103）曾记「息屏 doze 下投递降到约 1/3 且带延迟」；
 *       但 <b>A11 实测（TASK-029，21 秒锁屏窗口）5 次晃动全部实时送达</b>（无障碍服务让本进程免于冻结，
 *       系统日志 `PolicyMaker uid=… reason=accessibility`）⇒ <b>长时间息屏仍未测</b>：
 *       既不承诺"息屏可用"，⛔ 也不写"息屏不生效"，<b>文案里干脆不提息屏</b>。</li>
 *   <li><b>App 必须在前台</b>：HyperOS 会冻结非前台的进程（实测采样降至 6.7%）
 *       ⇒ 本检测器只在「本 App 可见」时有效。</li>
 *   <li><b>误触发无法靠幅度区分</b>：实测走路/拿起放下的峰值（p90≈4.2、max≈13.6）**大于**有意甩动
 *       ⇒ 只能靠「双峰反转形态 + 冷却」压制，<b>不承诺零误触发</b>。</li>
 *   <li><b>横持未定义</b>：实测横持段无主轴、无方向偏置 ⇒ 不承诺横持下的方向语义。</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * 传感器回调跑在私有 {@link HandlerThread} 上（**不是主线程**）——
 * 这样 {@code RemoteLinkManager.sendCommand} 的 socket 写不会踩
 * {@code NetworkOnMainThreadException}（TASK-018 曾因此整进程崩溃）。
 */
public final class ShakeDetector implements SensorEventListener {

    private static final String TAG = "ShakeDetector";

    // ── 全局常量（三档共用；标定依据：验证记录/103 + _probe/shake_phone/EVIDENCE.md §5）──

    /** 采样周期（微秒）。实测 50Hz 档 = 49.90Hz、零丢包、抖动 ±0.2ms。 */
    private static final int RATE_US = 20000;
    /** 低通去重力系数（沿用方案初值，未独立标定）。 */
    private static final float ALPHA = 0.8f;
    /** 判定一次甩动所需的「方向反转」次数（1 = 两个反号峰，实测波形；调高更严、更难误触发）。 */
    private static final int MIN_REVERSALS = 1;

    /**
     * 🆕 方向判决的「盘整比」门槛（TASK-029 修复）：主导轴上「主峰 / 异号次大峰」幅度比
     * 低于此值 ⇒ 判为**模糊甩动、弃权不发**（宁可"没反应"也不错发反方向）。
     *
     * <p>标定依据 T029L：≥1.2 时发出指令的方向准确率 ≈96%，代价是约 3 成**弱甩**被静默忽略
     * （正常力度甩动不受影响，被忽略的多是"轻抖"）。调低 = 更少忽略但更多错判。
     */
    private static final float AMBIG_RATIO = 1.2f;

    /**
     * 🆕 **X 组（左右轴）独立放宽**（TASK-029 手感优化 · 第二轮，2026-10-03）：
     * x 轴（左右晃）单独使用的**触发阈值**与**盘整比门槛**，比 y/z 组（上下）更松。
     *
     * <p>依据 T029M 复验：左右晃（x）的加速度天然比前后甩（z）小（实测左右峰 200~665 vs 上下 376~1028），
     * 沿用同一套门槛会把多数左右晃挡在<b>触发层</b>（阈值 1.6：8 次左晃只有 3 次进判决）与
     * <b>门槛层</b>（盘整比 1.2：右晃 x 主导的 3 次 ratio 仅 1.14/1.15 被弃权）之外。
     * <p>用户拍板「左右晃是主要使用场景，单独降」⇒ x 轴用 {@link #TH_X} / {@link #RATIO_X}，
     * y/z 仍用档位阈值 / {@link #AMBIG_RATIO}。代价：左右组的误触发 / 错方向概率略升。
     * <p>🆕 2026-10-03 第二轮：{@code RATIO_X} 1.05 → <b>1.0</b>（T029N 复验仍有 3 条 x 主导甩动
     * ratio 仅 1.00/1.01/1.04 被弃权）⇒ 放行那几条极接近的双峰；{@link #TH_X} 维持 1.1。
     */
    private static final float TH_X = 1.1f;
    private static final float RATIO_X = 1.0f;

    private static final int IDX_X = 0;
    private static final int IDX_Y = 1;
    private static final int IDX_Z = 2;

    // ── 🔧 临时标定探针（TASK-029 方向判定修复 · 第①步）──
    // 目的：把"方向判定"这个黑箱打开。**只加日志、不改任何判定逻辑。**
    // 每次命中打印：① 本轴两峰（更早峰 p1 / 更晚峰 p2）的符号+幅度+间隔；
    //              ② 同一时刻三轴各自"最近的超阈峰"快照；③ 最近 40 帧原始波形。
    // 🔴 2026-10-03 判决已定案（判据 E + 门槛）⇒ 用户拍板「不用的探针可以关闭」，置 false。
    //    波形环形缓冲（mBuf*）仍常开 —— 它是 judge() 的输入，与探针无关。
    private static final boolean PROBE = false;
    /** 探针波形缓冲深度（帧）。50Hz ⇒ 40 帧 ≈ 800ms，覆盖一次甩动全过程。 */
    private static final int PROBE_N = 40;

    // ── 🆕 TASK-029 手感优化：灵敏度三档（0=低 / 1=中 / 2=高，默认 1=中）──
    //
    // 每档四个参数以「中档」为圆心**同向**偏移 ≈15%：
    //   低档 = 更难触发（阈值↑ 冷却↑ 窗口↓ 峰间隔↑）——走路/掏兜更不易误翻，代价是要甩更用力；
    //   高档 = 更易触发（四项全反向）——更灵敏，代价是误触发更多。
    // 🔴 **三档都压不住走路误触**：实测走路峰值 p90=4.23 > 有意甩动 p90=3.89，幅度不可分离
    //    ⇒ 「低档」的真实效果只是"要甩更用力"，**不是**"走路不翻"。真压误触需 MIN_REVERSALS=2。
    // 口径（🆕 2026-10-03 重定，用户拍板「把中档参数提到原高档水平，确定中档后再定低/高」）：
    //   中档 = 原高档值（TH 1.35 / CD 850 / WIN 400 / GAP 60）；低/高 = 以新中档为圆心 ±15% 同向偏移。
    private static final String[] SENS_NAME = {"低", "中", "高"};
    private static final float[]  SENS_THRESHOLD   = {1.56f, 1.35f, 1.14f};
    private static final long[]   SENS_COOLDOWN_MS = {980L,  850L,  720L};
    private static final long[]   SENS_WINDOW_MS   = {340L,  400L,  460L};
    private static final long[]   SENS_PEAK_GAP_MS = {70L,   60L,   50L};

    // ── 单例（由 sync() 按门控启停）──
    //
    // 🆕 TASK-073：由"单实例"改为**两个独立实例** —— 对端模式（晃本机 ⇒ 翻墨水屏上的书）
    //   与本机模式（晃本机 ⇒ 翻本机上的微信读书）**解耦**（用户 2026-10-09 Q2 拍板）：
    //   两个开关各自独立生效，同时打开时两条路径**同时**触发（= TASK-074「一起翻页」口径）。

    /** 对端模式实例（G1 role=phone + G2 总开关 + G3 有可发通道）。 */
    private static ShakeDetector sPeer;
    /** 本机模式实例（只认 {@code CardPrefs.shake_local_enabled}，不依赖角色/通道）。 */
    private static ShakeDetector sLocal;

    /**
     * 🔴 唯一的启停入口：按各道门控重新评估「该不该采样」，该开则开、该停则停。
     *
     * <pre>
     * 对端模式（peer）：
     *   G1 role == PHONE          —— 晃动是手机端的捕获方式（墨水屏/关闭角色下不注册）
     *   G2 remote_shake_enabled   —— 总开关（默认关 ⇒ 装后与现状零差异）
     *   G3 有可发通道就绪          —— 仅"能发指令"时采样（加速度计是常驻 50Hz 源，不白耗电）
     *                               🆕 TASK-033：判据 = {@code RemoteLinkManager.canSend()}
     * 本机模式（local，🆕 TASK-073）：
     *   G2' {@code shake_local_enabled} —— 独立开关，**不依赖** G1 角色 / G3 通道 / 连接方式。
     * </pre>
     *
     * 调用点：① 遥控服务连接/销毁；② 会话状态变化（经 {@code addStateListener}）；
     * ③ 设置页改角色或改开关后。三处都只是"通知"，真实门控在此处集中判定。
     */
    public static synchronized void sync(Context c) {
        if (c == null) {
            return;
        }
        boolean peerWant;
        boolean localWant;
        try {
            peerWant = RemoteRole.from(c) == RemoteRole.PHONE
                    && CardPrefs.isShakeEnabled(c)
                    && RemoteLinkManager.get().canSend();
            localWant = CardPrefs.isShakeLocalEnabled(c);
        } catch (Throwable t) {
            Log.w(TAG, "sync: gate check failed(swallowed): " + t);
            return;
        }
        ensureMode(c, true, peerWant);
        ensureMode(c, false, localWant);
    }

    /** 按目标态启停某一个模式实例（幂等；同一模式已运行则不重建）。 */
    private static void ensureMode(Context c, boolean peer, boolean want) {
        ShakeDetector cur = peer ? sPeer : sLocal;
        if (want) {
            if (cur == null) {
                // 🔴🔴 注意语义换算：本方法入参 `peer` = **是否对端模式**，
                //   而构造器第 3 参要的是 **是否本机模式** ⇒ 必须取反。
                //   （TASK-073 上机实测踩过：直接传 `peer` ⇒ 两个实例的 mLocal 整体反了 ——
                //    开「本机晃动」建出来的实例 mLocal=false，会走去对端的 sendCommand 分支。）
                final boolean isLocal = !peer;
                ShakeDetector d = new ShakeDetector(
                        c.getApplicationContext(), CardPrefs.getShakeSens(c), isLocal);
                if (d.start()) {
                    if (peer) {
                        sPeer = d;
                    } else {
                        sLocal = d;
                    }
                }
            }
        } else if (cur != null) {
            cur.stop();
            if (peer) {
                sPeer = null;
            } else {
                sLocal = null;
            }
        }
    }

    /** 强制停采样（服务被回收 / 会话结束 / 总开关关闭都走这里）。可重复调用。 */
    public static synchronized void shutdown() {
        ShakeDetector p = sPeer;
        ShakeDetector l = sLocal;
        sPeer = null;
        sLocal = null;
        if (p != null) {
            p.stop();
        }
        if (l != null) {
            l.stop();
        }
    }

    /**
     * 🆕 灵敏度档位变更后调用：**正在采样则用新参数重建**（几毫秒空窗），未采样则只留待下次 {@link #sync}。
     *
     * <p>重建会清掉「上次命中时间 / 冷却态」⇒ 改档后紧接着甩一下会立即触发（不受旧冷却限制），可接受。
     */
    public static synchronized void reload(Context c) {
        if (sPeer != null || sLocal != null) {
            shutdown();
            sync(c);
        }
    }

    /** 探针/调试用：采样是否在跑（任一模式）。 */
    public static boolean isRunning() {
        return sPeer != null || sLocal != null;
    }

    /** 🆕 TASK-073：本机模式是否在采样（设置页状态提示用）。 */
    public static boolean isLocalRunning() {
        return sLocal != null;
    }

    // ── 实例 ──

    private final Context mApp;
    private final SensorManager mSm;
    /** 🆕 TASK-073：true = 本机模式（晃本机翻本机）；false = 对端模式（晃本机翻墨水屏）。 */
    private final boolean mLocal;

    // ── 档位参数（构造时从 CardPrefs 载入；🔴 非 static，以支持三档切换）──
    private final int mSens;
    private final float mThreshold;
    private final long mCooldownMs;
    private final long mWindowMs;
    private final long mMinPeakGapMs;

    private HandlerThread mThread;
    private Handler mHandler;

    /** 重力估计（低通），下标 0/1/2 = x/y/z。 */
    private final float[] mG = new float[3];
    private boolean mGInit;

    /** 三条轴通道各跑一个「双峰反转」检测器 —— 谁先凑齐谁生效（见类注释「判轴改为 x/z」）。 */
    private final AxisDetector mDetX = new AxisDetector("x", IDX_X);
    private final AxisDetector mDetY = new AxisDetector("y", IDX_Y);
    private final AxisDetector mDetZ = new AxisDetector("z", IDX_Z);

    /** 最近一次命中时间（ms，{@code elapsedRealtimeNanos} 口径）—— 三条通道共享，实现全局冷却。 */
    private long mLastFireAt;

    // ── 最近 800ms 波形环形缓冲 —— **方向判决（judge）+ 探针**共用 ──
    private final long[]  mBufT = new long[PROBE_N];
    private final float[] mBufX = new float[PROBE_N];
    private final float[] mBufY = new float[PROBE_N];
    private final float[] mBufZ = new float[PROBE_N];
    private int mBufHead;      // 下一个写入位
    private int mBufCount;     // 已填充帧数（≤ PROBE_N）

    private ShakeDetector(Context app, int sens, boolean local) {
        mApp = app;
        mLocal = local;
        mSm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        if (sens < 0 || sens >= SENS_NAME.length) {
            sens = CardPrefs.SHAKE_SENS_MID;          // 越界兜底 = 中档
        }
        mSens = sens;
        mThreshold = SENS_THRESHOLD[sens];
        mCooldownMs = SENS_COOLDOWN_MS[sens];
        mWindowMs = SENS_WINDOW_MS[sens];
        mMinPeakGapMs = SENS_PEAK_GAP_MS[sens];
    }

    /** 实际上线：起采样线程并注册加速度计。失败则整体放弃（不假装成功）。@return 是否真的注册上 */
    private boolean start() {
        Sensor acc = (mSm == null) ? null : mSm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) {
            Log.w(TAG, "start: 本机没有加速度计 —— 不注册（mode=" + (mLocal ? "local" : "peer") + "）");
            return false;
        }
        HandlerThread thread = new HandlerThread("shake-detector");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        boolean ok;
        try {
            ok = mSm.registerListener(this, acc, RATE_US, h);   // 4 参公开重载：回调落在 h 的线程
            if (!ok) {
                ok = mSm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME, h);
            }
        } catch (Throwable t) {
            Log.w(TAG, "start: registerListener failed(swallowed): " + t);
            ok = false;
        }
        if (!ok) {
            Log.w(TAG, "start: 注册失败 —— 不启动");
            thread.quit();
            return false;
        }
        mThread = thread;
        mHandler = h;
        Log.i(TAG, "START shake detector(" + (mLocal ? "local" : "peer") + "): 50Hz, sens="
                + SENS_NAME[mSens] + " th=" + mThreshold + " win=" + mWindowMs + "ms cd="
                + mCooldownMs + "ms gap=" + mMinPeakGapMs + "ms");
        return true;
    }

    private void stop() {
        try {
            if (mSm != null) {
                mSm.unregisterListener(this);
            }
        } catch (Throwable t) {
            Log.w(TAG, "stop: unregister failed(swallowed): " + t);
        }
        try {
            if (mThread != null) {
                mThread.quit();
            }
        } catch (Throwable ignored) {
            // quit 失败无所谓：线程会随进程回收
        }
        mThread = null;
        mHandler = null;
        Log.i(TAG, "STOP shake detector(" + (mLocal ? "local" : "peer")
                + ")（传感器已注销，无残留采样）");
    }

    // ── 传感器回调（跑在私有后台线程）──

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e == null || e.values == null || e.values.length < 3) {
            return;
        }
        try {
            final float ax = e.values[0];
            final float ay = e.values[1];
            final float az = e.values[2];
            if (!mGInit) {
                mG[0] = ax;
                mG[1] = ay;
                mG[2] = az;
                mGInit = true;
            }
            mG[0] = ALPHA * mG[0] + (1f - ALPHA) * ax;
            mG[1] = ALPHA * mG[1] + (1f - ALPHA) * ay;
            mG[2] = ALPHA * mG[2] + (1f - ALPHA) * az;
            final float lx = ax - mG[0];
            final float ly = ay - mG[1];
            final float lz = az - mG[2];

            final long t = e.timestamp / 1000000L;   // ns → ms（单调，跨样本可比）
            // 🆕 最近 800ms 波形缓冲：**方向判决（judge）与探针共用** ⇒ 常开写入（50Hz×4 数组，开销可忽略）
            mBufT[mBufHead] = t;
            mBufX[mBufHead] = lx;
            mBufY[mBufHead] = ly;
            mBufZ[mBufHead] = lz;
            mBufHead = (mBufHead + 1) % PROBE_N;
            if (mBufCount < PROBE_N) {
                mBufCount++;
            }
            mDetX.push(t, lx);
            mDetY.push(t, ly);
            mDetZ.push(t, lz);
        } catch (Throwable t) {
            // 传感器回调里任何异常都可能带走进程 —— 就地吞掉，只留日志
            Log.w(TAG, "onSensorChanged failed(swallowed): " + t);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要
    }

    // ── 单轴「双峰反转」检测器 ──

    /**
     * 在**一条轴通道**上找「双峰反转」：把信号按半周切分（符号翻转即换半周），
     * 每个半周记一个峰；连续 {@link #MIN_REVERSALS} 次「反号峰」且每次间隔都落在
     * [最小峰间隔, 双峰窗口] ms 内、峰值幅度 ≥ 触发阈值，
     * 就判为一次甩动 —— 方向取**这段链里最先出现的那个峰**的符号。
     */
    private final class AxisDetector {
        private final String mName;
        private final int mIdx;

        private boolean mInit;
        private int mSign;          // 当前半周方向（+1 / −1）
        private float mPeak;        // 当前半周峰值（带符号）
        private long mPeakT;

        private boolean mHavePrev;
        private int mPrevSign;
        private long mPrevT;
        private int mReversals;     // 已累计的反转次数

        // ── 探针用（只读记录，不参与判定）──
        private float mPrevPeak;        // 上一个（更早的）超阈峰，带符号 —— 供判据 A/B 对比
        private boolean mHaveLastPeak;
        private float mLastPeakVal;     // 最近一个超阈峰，带符号 —— 供三轴快照
        private long mLastPeakT;

        AxisDetector(String name, int idx) {
            mName = name;
            mIdx = idx;
        }

        void push(long t, float v) {
            // 🆕 TASK-042：未使能的轴**直接不进检测**（连半周切分都不做）⇒
            //   它既不产生候选、也不影响判轴能量（judge 侧另有独立屏蔽）。
            //   🔴 每次 push 实时读偏好：用户在设置页勾完立刻生效，不必重开会话。
            if (!isAxisEnabled(mIdx)) {
                return;
            }
            final int s = (v > 0f) ? 1 : (v < 0f ? -1 : 0);
            if (s == 0) {
                return;                       // 恰为 0：不打断半周
            }
            if (!mInit) {
                mInit = true;
                mSign = s;
                mPeak = v;
                mPeakT = t;
                return;
            }
            if (s != mSign) {
                onHalfCycleEnd(mPeakT, mSign, mPeak);   // 上一半周收口
                mSign = s;
                mPeak = v;
                mPeakT = t;
                return;
            }
            if (Math.abs(v) >= Math.abs(mPeak)) {       // 同向且更大 ⇒ 更新峰
                mPeak = v;
                mPeakT = t;
            }
        }

        /** 一个半周结束：拿它的峰去和前一个峰凑「双峰反转」。 */
        private void onHalfCycleEnd(long pt, int ps, float pv) {
            if (Math.abs(pv) < thrOf(mIdx)) {
                return;                                  // 小峰不计（噪声 / 余波；x 轴用放宽的 TH_X）
            }
            if (PROBE) {                                 // 探针：记录本轴最近超阈峰（不改判定）
                mLastPeakVal = pv;
                mLastPeakT = pt;
                mHaveLastPeak = true;
            }
            if (!mHavePrev || mPrevSign == ps) {
                // 链断：重新起头（把当前峰作为"上一个峰"）
                mPrevSign = ps;
                mPrevT = pt;
                mPrevPeak = pv;
                mHavePrev = true;
                mReversals = 0;
                return;
            }
            final long gap = pt - mPrevT;
            if (gap < mMinPeakGapMs || gap > mWindowMs) {
                mPrevSign = ps;                          // 间隔不对：当前峰另起一条链
                mPrevT = pt;
                mPrevPeak = pv;
                mReversals = 0;
                return;
            }
            mReversals++;
            if (mReversals < MIN_REVERSALS) {
                mPrevSign = ps;
                mPrevT = pt;
                mPrevPeak = pv;
                return;
            }
            if (PROBE) {                                 // 探针：命中/被冷却前先落全量证据
                probeDump(mName, mPrevPeak, pv, mPrevT, pt);
            }
            mHavePrev = false;
            mReversals = 0;
            // 🆕 方向判决（TASK-029 修复）：不再用"触发轴首峰"，改用
            //    「能量主导轴 + 主峰符号 + 盘整比门槛」（实测方向一致率 76% → ≈96%）。
            final int[] d = judge(mName);
            if (d == null) {
                return;                                  // 模糊甩动：静默忽略（不占冷却，用户可再甩）
            }
            if (pt - mLastFireAt < mCooldownMs) {
                Log.i(TAG, "cooldown suppressed axis=" + mName);
                return;                                  // 冷却期内：丢弃，不排队补发
            }
            mLastFireAt = pt;
            fire(d[0], d[1]);
        }
    }

    // ── 🆕 方向判决（TASK-029 修复）──

    /**
     * 🆕 TASK-042：某条轴通道**当前是否响应**。
     *
     * <p>分组：{@code x} = 左右；{@code y}/{@code z} = 上下（两条一起开关，见 {@code TASK-029} 的判轴结论）。
     * 偏好存放于 {@code CardPrefs.shake_axis_lr_enabled} / {@code shake_axis_ud_enabled}（默认都 true
     * ⇒ 未改设置时行为与 TASK-041 前完全一致）。
     *
     * <p>🔴 读失败一律**返回 true**（放行）—— 宁可多响应，也不用一个异常把晃动整个静掉。
     * <p>🔴 实时读（不缓存）⇒ 设置页勾选后立即生效，无需重开会话。
     */
    private boolean isAxisEnabled(int idx) {
        try {
            return (idx == IDX_X) ? CardPrefs.isShakeAxisLrEnabled(mApp)
                                  : CardPrefs.isShakeAxisUdEnabled(mApp);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 一次「甩动候选」的方向判决，三步：
     * <ol>
     *   <li><b>判轴</b> —— 最近 {@link #PROBE_N} 帧（≈800ms）内三轴 {@code Σ|v|} 最大者为<b>主导轴</b>
     *       （取代"三通道先到先得"，压掉串轴）。</li>
     *   <li><b>判符号</b> —— 主导轴上单点绝对值最大点（<b>主峰</b>）的符号
     *       （取代"首峰"，压掉起手的反向预摆）。</li>
     *   <li><b>门槛</b> —— 主导轴「主峰 / 异号次大峰」幅度比 ≥ {@link #AMBIG_RATIO}，
     *       且主峰幅度 ≥ 触发阈值；否则<b>弃权</b>（返回 {@code null}）。</li>
     * </ol>
     *
     * <p>线程：跑在传感器回调线程（{@link #mThread}），与写缓冲同线程 ⇒ 天然串行、无锁。
     *
     * @param trig 触发轴名（仅用于日志定位）
     * @return {@code {轴下标, 方向符号(+1/−1)}}；模糊或样本不足 ⇒ {@code null}
     */
    private int[] judge(String trig) {
        final int n = mBufCount;
        if (n < 8) {
            Log.i(TAG, "JUDGE trig=" + trig + " → 弃权(样本不足 n=" + n + ")");
            return null;
        }
        final int start = (((mBufHead - n) % PROBE_N) + PROBE_N) % PROBE_N;
        final float[] energy = new float[3];
        final float[] maxPos = new float[3];      // 各轴最大正峰（≥0）
        final float[] maxNeg = new float[3];      // 各轴最大负峰（≤0）
        for (int i = 0; i < n; i++) {
            final int k = (start + i) % PROBE_N;
            final float vx = mBufX[k], vy = mBufY[k], vz = mBufZ[k];
            energy[IDX_X] += Math.abs(vx);
            energy[IDX_Y] += Math.abs(vy);
            energy[IDX_Z] += Math.abs(vz);
            if (vx > maxPos[IDX_X]) maxPos[IDX_X] = vx;
            if (vx < maxNeg[IDX_X]) maxNeg[IDX_X] = vx;
            if (vy > maxPos[IDX_Y]) maxPos[IDX_Y] = vy;
            if (vy < maxNeg[IDX_Y]) maxNeg[IDX_Y] = vy;
            if (vz > maxPos[IDX_Z]) maxPos[IDX_Z] = vz;
            if (vz < maxNeg[IDX_Z]) maxNeg[IDX_Z] = vz;
        }
        // 🆕 TASK-042：未使能的轴**不进判轴**。理由：判轴取「能量最大者」——
        //   若只勾「左右」但恰好上下抖得厉害，未屏蔽时主导轴会落到 z ⇒ 要么误判上下、要么比例
        //   门槛不过而弃权（表现为"左右晃没反应"）。把未使能轴的能量清零 ⇒ 主导轴必落在使能组内。
        if (!isAxisEnabled(IDX_X)) {
            energy[IDX_X] = 0f;
        }
        if (!isAxisEnabled(IDX_Y)) {
            energy[IDX_Y] = 0f;
        }
        if (!isAxisEnabled(IDX_Z)) {
            energy[IDX_Z] = 0f;
        }
        int dom = IDX_X;
        for (int a = 1; a < 3; a++) {
            if (energy[a] > energy[dom]) {
                dom = a;
            }
        }
        // 双不勾（脏值/异常）兜底：仍以 x 为准，至少不让 judge 崩（CardPrefs 的联合兜底已保证
        // 正常读入时至少一组为 true；此处只防"两条都 false"的极端脏数据）。
        if (energy[dom] <= 0f && !isAxisEnabled(dom)) {
            Log.i(TAG, "JUDGE trig=" + trig + " → 弃权(全部轴未使能)");
            return null;
        }
        final float ampPos = maxPos[dom];
        final float ampNeg = -maxNeg[dom];                 // 负峰转成正幅度
        final float peak = Math.max(ampPos, ampNeg);       // 主峰幅度
        final float oppo = Math.min(ampPos, ampNeg);       // 异号次大峰幅度
        final int sign = (ampPos >= ampNeg) ? 1 : -1;
        final float ratio = (oppo <= 1e-3f) ? Float.MAX_VALUE : (peak / oppo);
        // 🆕 X 组（左右）用独立放宽的门槛；y/z 组（上下）仍用档位阈值 / {@link #AMBIG_RATIO}。
        final float thr = thrOf(dom);
        final float ratioGate = (dom == IDX_X) ? RATIO_X : AMBIG_RATIO;
        final boolean pass = (peak >= thr) && (ratio >= ratioGate);
        Log.i(TAG, "JUDGE trig=" + trig + " dom=" + nameOf(dom)
                + " peak=" + q(sign * peak) + " oppo=" + q(-sign * oppo)
                + " ratio=" + (ratio == Float.MAX_VALUE
                        ? "inf" : String.valueOf(Math.round(ratio * 100f) / 100f))
                + " [th=" + thr + " r=" + ratioGate + "]"
                + (pass ? " → " + (sign < 0 ? "PREV" : "NEXT") : " → 弃权(模糊)"));   // 默认映射：sign− = 上一页（rev 勾选时相反，以 fire() 的 SHAKE 日志为准）
        return pass ? new int[]{dom, sign} : null;
    }

    /**
     * 某轴通道适用的**触发阈值**：x（左右）用放宽的 {@link #TH_X}，
     * y/z（上下）用档位阈值 {@link #mThreshold}。
     */
    private float thrOf(int idx) {
        return (idx == IDX_X) ? TH_X : mThreshold;
    }

    /** 轴名（判决日志用）。 */
    private static String nameOf(int idx) {
        return idx == IDX_X ? "x" : (idx == IDX_Y ? "y" : "z");
    }

    // ── 🔧 标定探针（只读，不参与判定；PROBE=false 时整个方法不被调用）──

    /**
     * 落一次命中的完整证据（两行）：
     * <pre>
     * PROBE fire ax=x p1=-234 p2=310 gap=225 | snap x=310@0 y=-42@-120 z=102@-40
     * PROBE wave ax=x n=40 -400:-12,3,45 -380:...（每帧 t:lx,ly,lz）
     * </pre>
     * 口径：幅度一律 ×100 取整（{@code -234} = −2.34 m/s²，符号即方向）；{@code @dt} = 峰相对本次命中的毫秒偏移（负=更早）。
     * {@code p1} = 更早的峰（判据 A 取它），{@code p2} = 更晚的峰（两峰幅度可直接比大小 ⇒ 判据 B）。
     *
     * @param ax 命中的轴名（x/y/z）
     * @param m1 更早峰的**带符号**幅度、{@code t1} 其时间
     * @param m2 更晚峰的**带符号**幅度、{@code t2} 其时间（≈命中时刻）
     */
    private void probeDump(String ax, float m1, float m2, long t1, long t2) {
        try {
            Log.i(TAG, "PROBE fire ax=" + ax
                    + " p1=" + q(m1)                      // 更早峰（带符号）
                    + " p2=" + q(m2)                      // 更晚峰（带符号）
                    + " gap=" + (t2 - t1)
                    + " | snap x=" + snapDescOf(mDetX, t2)
                    + " y=" + snapDescOf(mDetY, t2)
                    + " z=" + snapDescOf(mDetZ, t2));
            final StringBuilder w = new StringBuilder(1024);
            w.append("PROBE wave ax=").append(ax).append(" n=").append(mBufCount);
            final int start = (((mBufHead - mBufCount) % PROBE_N) + PROBE_N) % PROBE_N;   // 最旧帧
            for (int i = 0; i < mBufCount; i++) {
                final int k = (start + i) % PROBE_N;
                w.append(' ').append(mBufT[k] - t2).append(':')
                 .append(q(mBufX[k])).append(',').append(q(mBufY[k])).append(',').append(q(mBufZ[k]));
            }
            Log.i(TAG, w.toString());
        } catch (Throwable t) {
            Log.w(TAG, "probeDump failed(swallowed): " + t);
        }
    }

    /** 幅度 ×100 取整（探针文本口径；保留符号）。 */
    private static int q(float v) {
        return Math.round(v * 100f);
    }

    /** 单轴快照：该轴最近一个超阈峰（带符号 ×100）+ 相对 {@code nowT} 的毫秒偏移；无则 {@code n/a}。 */
    private static String snapDescOf(AxisDetector d, long nowT) {
        if (!d.mHaveLastPeak) {
            return "n/a";
        }
        return q(d.mLastPeakVal) + "@" + (d.mLastPeakT - nowT);
    }

    // ── 命中 → 指令 ──

    /**
     * 一次甩动**判决通过** ⇒ 决定指令，再按<b>本机 / 对端</b>分流投递。
     *
     * <h4>① 方向基准（🆕 TASK-074：条件式，两模式不再"整体相反"）</h4>
     * <p>同一套 {@link #judge} 输出（主导轴 {@code idx} + 主峰符号 {@code sign}）先由
     * {@link #actionOf} 归一为**四个动作**之一（左/右/上/下晃，口径 = 手机"屏幕正对自己"），
     * 再按下列条件取指令：
     * <ul>
     *   <li><b>「手机侧」口径</b>（本机实例 {@link #mLocal} = true，**或**对端实例但本机开关
     *       {@code shake_local_enabled} 已打开）—— 四个动作各查 {@link CardPrefs#isShakeLocalActNext}
     *       的**自选方向**（默认 左/上→上一页、右/下→下一页）。
     *       <p>🔴 对端实例在「本机开关打开」时**也切到手机侧**：这就是 TASK-074「一起翻页」
     *       —— 手机与墨水屏并排都正对使用者，一次晃动两端**必须同向翻**（TASK-073 的双实例
     *       各自按不同握持标定 ⇒ 曾出现"一前一后"，见验证记录 183）。</li>
     *   <li><b>「对端口径」</b>（仅对端实例、且本机开关**关闭**时）—— 沿用 TASK-029 标定
     *       （背面朝自己）：{@code eff = rev ? −sign : sign}；{@code rev} 取
     *       {@code remote_shake_lr_rev} / {@code remote_shake_ud_rev}。**本机开关关着时行为与
     *       TASK-073 之前逐位一致**（用户 2026-10-09：「未打开时按之前确定的翻页逻辑来」）。</li>
     * </ul>
     *
     * <h4>② 投递路径</h4>
     * <ul>
     *   <li>对端 ⇒ {@link RemoteLinkManager#sendCommand}（TCP 45678 / HID 蓝牙，发指令给墨水屏）。</li>
     *   <li>本机 ⇒ {@link RemoteInjector#injectLocal}（在本机微信读书上用无障碍手势合成翻页，
     *       与 {@link RemoteInjector#inject} 共享同一条 FIFO 串行队列）。</li>
     * </ul>
     *
     * @param idx  **主导轴**下标（来自 {@link #judge}），不再是"触发轴"
     * @param sign 主峰符号（+1/−1）
     *
     * <p>🔴 每次命中都**复读偏好**（而不是启动时缓存一次）—— 用户在设置页改完开关
     * 立刻生效，不会出现「改了要重开会话才管用」的隐性坑（验收 A13）。
     */
    private void fire(int idx, int sign) {
        final boolean lr = (idx == IDX_X);
        final int act = actionOf(idx, sign);
        int cmd;
        boolean phoneSide;
        try {
            // 🔴 TASK-074：方向基准是**条件式**的 ——
            //   ① 本机实例（mLocal）永远按「手机侧」口径；
            //   ② 对端实例：**本机晃动开关打开**时也按「手机侧」口径（= 一起翻页，
            //      手机正对使用者 ⇒ 两端必须同向）；开关关闭时才回落 TASK-029 的原始口径。
            phoneSide = mLocal || CardPrefs.isShakeLocalEnabled(mApp);
            if (phoneSide) {
                cmd = CardPrefs.isShakeLocalActNext(mApp, act)
                        ? RemoteProtocol.CMD_PAGE_NEXT : RemoteProtocol.CMD_PAGE_PREV;
            } else {
                final boolean rev = lr ? CardPrefs.isShakeLrRev(mApp) : CardPrefs.isShakeUdRev(mApp);
                final int eff = rev ? -sign : sign;   // 对端：默认 左/上→上一页、右/下→下一页
                cmd = (eff < 0) ? RemoteProtocol.CMD_PAGE_PREV : RemoteProtocol.CMD_PAGE_NEXT;
            }
        } catch (Throwable t) {
            Log.w(TAG, "fire: read prefs failed(swallowed): " + t);
            phoneSide = false;
            cmd = (sign < 0) ? RemoteProtocol.CMD_PAGE_PREV : RemoteProtocol.CMD_PAGE_NEXT;
        }
        boolean sent = false;
        if (mLocal) {
            // 本机模式：不走遥控链路（不需要 role=PHONE / 通道就绪），直接在本机合成翻页手势。
            try {
                sent = RemoteInjector.injectLocal(RemoteKeyService.instance(), cmd);
            } catch (Throwable t) {
                Log.w(TAG, "fire: injectLocal failed(swallowed): " + t);
            }
        } else {
            try {
                sent = RemoteLinkManager.get().sendCommand(cmd);
            } catch (Throwable t) {
                Log.w(TAG, "fire: sendCommand failed(swallowed): " + t);
            }
        }
        Log.i(TAG, "SHAKE(mode=" + (mLocal ? "local" : "peer") + ") axis=" + (lr ? "LR" : "UD")
                + "(" + idx + ") sign=" + sign + " act=" + actName(act)
                + " src=" + (phoneSide ? "phone" : "peer") + " → "
                + RemoteProtocol.name(cmd) + " sent=" + sent);
    }

    /**
     * 🆕 TASK-074：(主导轴, 主峰符号) → 本机四动作之一。
     *
     * <p>口径 = **手机「屏幕正对自己」** 的握持（与 TASK-073 的 A5 标定一致）：
     * <ul>
     *   <li>x 轴（左右）：{@code sign>0} = 左晃 / {@code sign<0} = 右晃；</li>
     *   <li>y·z 轴（上下）：{@code sign>0} = 上晃 / {@code sign<0} = 下晃。</li>
     * </ul>
     * 🔴 该映射**只负责"是哪个动作"**，与"翻上一页还是下一页"解耦 ——
     * 后者由 {@link CardPrefs#isShakeLocalActNext} 的四动作单选决定。
     */
    private static int actionOf(int idx, int sign) {
        if (idx == IDX_X) {
            return (sign > 0) ? CardPrefs.SHAKE_ACT_LEFT : CardPrefs.SHAKE_ACT_RIGHT;
        }
        return (sign > 0) ? CardPrefs.SHAKE_ACT_UP : CardPrefs.SHAKE_ACT_DOWN;
    }

    /** 动作名（日志用）。 */
    private static String actName(int act) {
        switch (act) {
            case CardPrefs.SHAKE_ACT_LEFT:  return "LEFT";
            case CardPrefs.SHAKE_ACT_RIGHT: return "RIGHT";
            case CardPrefs.SHAKE_ACT_UP:    return "UP";
            case CardPrefs.SHAKE_ACT_DOWN:  return "DOWN";
            default:                        return "?";
        }
    }
}
