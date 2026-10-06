package com.inkread.weekread.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * 🆕 TASK-064：**运行期能力门** —— 「正式版 / Beta」两形态共用**一套源码**的开关总闸。
 *
 * <p>背景（用户 2026-10-07 拍板）：把 V1.0.0 正式版做成「V1.2.0-beta 禁用部分功能后的效果」——
 * <b>实验室</b>（整页）、<b>HID 蓝牙遥控 / 晃动翻页 / 音量键 / 遥控台</b>、<b>安装角色选择</b>
 * 三块在正式版**不可见**。实现方式 = 本门（而非物理剔除代码）：一套代码、两条发布线，
 * 回归面最小、可逆。
 *
 * <p>── 形态怎么判定 ──
 * <p>{@code tools/build.sh} 用 {@code aapt2 link --version-name "$VERSION_NAME"} 把版本名写进 APK
 * 的 manifest ⇒ 运行期 {@link PackageManager#getPackageInfo} 即可读到。
 * <b>版本名含 {@value #BETA_SUFFIX} ⇒ 完整形态</b>，与现有「正式 / beta」双通道口径天然一致：
 * <ul>
 *   <li>{@code 1.0.0}（正式）⇒ 精简形态（三关全关）；</li>
 *   <li>{@code 1.2.0-beta}（尝鲜）⇒ 完整形态（三关全开）。</li>
 * </ul>
 *
 * <p>── 三个开关互相独立 ──
 * <p>虽然当前都由「是否 Beta」派生，但刻意拆成三个（{@link #labVisible} / {@link #remoteVisible} /
 * {@link #rolePickVisible}），将来要单独放开某一项时只改一处判定，不必动调用方。
 *
 * <p>── dev 包自测覆写 ──
 * <p>{@code --dev} 构建出的包（{@code FLAG_DEBUGGABLE}）可用 prefs 键 {@value #K_DBG}
 * （{@code cfg} 内，0 = 精简 / 1 = 完整）在**同一台设备**上免重构建地切换两种形态。
 * 🔴 覆写**只在可调试包生效**：发布包恒按版本名判定，**不可能被绕过**。
 *
 * <p>🔴 读取失败（PackageManager 抛异常等）一律回落**精简形态**：正式版是面向所有用户的公开包，
 * 「少给功能」比「多给未发布功能」安全。
 */
public final class FeatureGate {

    /** 版本名里出现它 = 完整（Beta）形态。 */
    private static final String BETA_SUFFIX = "-beta";

    /** dev 包（可调试）专用覆写键：0 = 精简 / 1 = 完整 / 其它（含缺省）= 不覆写。 */
    private static final String K_DBG = "dbg_feature_gate";

    /** 判定缓存：-1 = 未判定；0 = 精简；1 = 完整。版本名进程内不会变，判一次即可。 */
    private static int sFull = -1;

    private FeatureGate() {
    }

    /** 是否**完整形态**（Beta）。三个开关当前的共同来源。 */
    private static synchronized boolean full(Context c) {
        if (sFull >= 0) return sFull == 1;
        boolean full = false;
        try {
            if (isDebuggable(c)) {
                int ov = c.getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt(K_DBG, -1);
                if (ov == 0 || ov == 1) {
                    sFull = ov;
                    return sFull == 1;
                }
            }
            String vn = versionName(c);
            full = (vn != null) && vn.contains(BETA_SUFFIX);
        } catch (Throwable ignored) {
            // 读不到版本名 ⇒ 回落精简（见类注释：宁少不多）
        }
        sFull = full ? 1 : 0;
        return full;
    }

    /**
     * 「实验室」是否可见 —— 管**设置页顶部「实验室」段** + **阅读器端大标签栏第 3 格**
     * （以及其内全部子标签：热点翻页 / 蓝牙控制 / 锁屏密码 / 续航优化）。
     */
    public static boolean labVisible(Context c) {
        return full(c);
    }

    /**
     * 「遥控」相关是否可见 —— 管 **HID 蓝牙遥控 / 晃动翻页 / 音量键 / 手机端遥控台**
     * （含 {@code remote_role} 全链路，见 {@link CardPrefs} 的读取门）。
     */
    public static boolean remoteVisible(Context c) {
        return full(c);
    }

    /** 「安装角色选择」是否可见 —— 管**首装二选一弹窗** + **设置页「本机角色」分区**。 */
    public static boolean rolePickVisible(Context c) {
        return full(c);
    }

    // ────────────────────── 内部工具 ──────────────────────

    private static boolean isDebuggable(Context c) {
        try {
            return (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String versionName(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }
}
