package com.inkread.weekread.feature.lab;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 五项「设备能力」只读探针（TASK-000）。
 *
 * 🔴 全部在 **App 内**完成 —— 卡的「测试命令」写的是 `adb shell dumpsys …`，那是
 *   **开发者在验证阶段的交叉核对**手段，App 自己跑不了 shell 命令。两者互不替代：
 *   App 内出「用户看到的结论」，adb 出「同一件事的独立第二意见」。
 *
 * 🔴 红线（卡 §非目标 + §风险）：不改系统设置、不申请新权限、不常驻、不轮询、**不主动开蓝牙**。
 *   因此**每一项**都独立 try/catch，**任何异常都落成 {@link ProbeResult#UNKNOWN}**，
 *   绝不把「探不到」写成「不可用」。
 *
 * 探针与未来卡的对应关系（写进 ADR-004~008）：
 *   ① 音量键        → TASK-按键增强
 *   ② 按键过滤放行  → TASK-按键增强
 *   ③ 无线调试      → TASK-系统隐藏设置（Shizuku）
 *   ④ 蓝牙 HID      → TASK-蓝牙 HID 遥控
 *   ⑤ EPD 接口      → TASK-全局刷新
 */
public final class CapabilityProbes {

    /** 蓝牙代理回调的等待上限。超过就判「不确定」——不赌、也不拖慢整轮自检 */
    private static final long BLE_TIMEOUT_MS = 400L;

    /** 墨水屏节点名的候选关键词（命中任一即记一条证据） */
    private static final String[] EPD_KEYS = {"epd", "eink", "epaper", "ink"};

    private CapabilityProbes() { }

    // ───────────────────────── ① 物理音量键 ─────────────────────────

    /**
     * ① 机身有没有物理音量键。
     *
     * 两条独立证据交叉：
     *   (a) **运行时真值** —— 每个 {@link InputDevice} 自报「我支持不支持这两个键」（{@code hasKeys}）；
     *   (b) **映射层** —— `/system/usr/keylayout/*.kl` 里有没有 `VOLUME_UP/DOWN`。
     *
     * ⚠️ `.kl` 只证「映射表定义了」，**不等于**「硬件装了」；所以 (b) 单独命中**不足以**判可用。
     */
    public static ProbeResult volumeKeys(Context c) {
        ProbeResult r = new ProbeResult(1, "物理音量键", ProbeResult.UNKNOWN, "");
        try {
            boolean anyPhysical = false;   // 物理设备自报支持（最强证据）
            boolean anyVirtual = false;    // 只有虚拟设备自报支持
            int[] ids = InputDevice.getDeviceIds();
            r.add("InputDevice 数量 = " + (ids == null ? 0 : ids.length));
            if (ids != null) {
                for (int i = 0; i < ids.length; i++) {
                    int id = ids[i];
                    InputDevice d = InputDevice.getDevice(id);
                    if (d == null) { r.add("InputDevice#" + id + " = null"); continue; }
                    boolean isKbd = (d.getSources() & InputDevice.SOURCE_KEYBOARD) != 0;
                    boolean[] has = d.hasKeys(KeyEvent.KEYCODE_VOLUME_UP,
                            KeyEvent.KEYCODE_VOLUME_DOWN);
                    boolean virt = isVirtual(d);
                    r.add("InputDevice#" + id
                            + " name=" + d.getName()
                            + " sources=0x" + Integer.toHexString(d.getSources())
                            + (virt ? " [虚拟]" : " [物理]")
                            + " keyboard=" + isKbd
                            + " VOLUME_UP=" + has[0] + " VOLUME_DOWN=" + has[1]);
                    if (has[0] || has[1]) {
                        if (virt) anyVirtual = true; else anyPhysical = true;
                    }
                }
            }
            int klHits = scanKeyLayouts(r);

            if (anyPhysical) {
                r.status = ProbeResult.AVAILABLE;
                r.summary = "至少一个**物理**输入设备在运行时自报支持 VOLUME_UP/DOWN"
                        + " ⇒ 机身确有音量键，可作触发源。";
            } else if (anyVirtual) {
                r.status = ProbeResult.UNKNOWN;
                r.summary = "只有**虚拟**输入设备报告支持（物理设备没有）"
                        + " ⇒ 不能据此判定机身在实体上装了音量键。";
            } else if (klHits > 0) {
                r.status = ProbeResult.UNKNOWN;
                r.summary = "按键映射表里有音量键，但没有任何输入设备自报支持 ——"
                        + "说不清是硬件没接、还是没暴露给 App。";
            } else {
                r.status = ProbeResult.UNKNOWN;
                r.summary = "未发现任何音量键线索（不代表没有：可能命名不同或未暴露）。";
            }
        } catch (Throwable t) {
            r.status = ProbeResult.UNKNOWN;
            r.summary = "探测抛异常 —— 无法判断。";
            r.add("异常：" + t.getClass().getName() + " / " + t.getMessage());
        }
        return r;
    }

    // ───────────────────────── ② ROM 是否放行按键过滤 ─────────────────────────

    /**
     * ② ROM 放不放行「按键过滤」（`flagRequestFilterKeyEvents` + `onKeyEvent`）。
     *
     * 🔴 **本卡走「甲案」：只读，因此结论恒为「不确定」** —— 这是**如实**，不是失败。
     *   理由（真机实测三条）：本项目无障碍配置为 `accessibilityFlags="flagDefault"`、
     *   全源码没有 `onKeyEvent`、`dumpsys accessibility` 里本服务 `capabilities=0`。
     *   ⇒ 只读**只能证**「本服务当前没申请」；要判「ROM 放不放行」**必须真的申请一次**，
     *   那既是主动实验、又是**用户可见的能力升级** ⇒ 命中卡的红线，另行出卡。
     *
     * 本探针能给的是**间接旁证**：看**别的已装无障碍服务**有没有声明这条能力。
     *   有 ⇒ 说明 ROM 在「服务声明」这一层没禁用该 flag（弱旁证）；
     *   ⚠️ 但「声明了」≠「框架真把按键送过去了」⇒ 仍不足以判可用。
     */
    public static ProbeResult keyFilter(Context c) {
        ProbeResult r = new ProbeResult(2, "ROM 是否放行按键过滤", ProbeResult.UNKNOWN, "");
        try {
            AccessibilityManager am =
                    (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) {
                r.summary = "拿不到 AccessibilityManager —— 无法判断。";
                r.add("getSystemService(ACCESSIBILITY_SERVICE) = null");
                return r;
            }
            List<AccessibilityServiceInfo> list = am.getInstalledAccessibilityServiceList();
            r.add("已安装无障碍服务数 = " + (list == null ? -1 : list.size()));

            String selfPkg = c.getPackageName();
            boolean selfHas = false;
            int othersHave = 0;
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    AccessibilityServiceInfo info = list.get(i);
                    String pkg = pkgOf(info);
                    int caps = info.getCapabilities();
                    boolean filter = (caps
                            & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0;
                    r.add(pkg + " capabilities=0x" + Integer.toHexString(caps)
                            + " filterKey=" + filter);
                    if (selfPkg.equals(pkg)) {
                        selfHas = filter;
                    } else if (filter) {
                        othersHave++;
                    }
                }
            }
            r.status = ProbeResult.UNKNOWN;
            r.summary = "本服务当前" + (selfHas ? "已" : "未") + "声明按键过滤能力"
                    + "；另有 " + othersHave + " 个已装服务声明了该能力。"
                    + "「ROM 是否真放行」必须主动申请后实测 —— 本卡只读面给不出结论。";
            r.add("（需主动实验：dev 变体加 flagRequestFilterKeyEvents + onKeyEvent，"
                    + "看设置页是否多出能力项、按键是否真送达 ⇒ 另行出卡）");
        } catch (Throwable t) {
            r.summary = "探测抛异常 —— 无法判断。";
            r.add("异常：" + t.getClass().getName() + " / " + t.getMessage());
        }
        return r;
    }

    // ───────────────────────── ③ 无线调试 ─────────────────────────

    /**
     * ③ ROM 有没有「无线调试」这套能力。
     *
     * 读三个只读的 Global 设置键（**不需要任何权限**，只是 query）。
     * ⚠️ 键**存在**只说明 ROM 提供了这套开关，**不代表当前开着**；当前值照样打印出来。
     * 全为 `null` ⇒ 既可能「ROM 没有」，也可能「尚未初始化」⇒ 只能判**不确定**。
     */
    public static ProbeResult wirelessAdb(Context c) {
        ProbeResult r = new ProbeResult(3, "无线调试", ProbeResult.UNKNOWN, "");
        try {
            ContentResolver cr = c.getContentResolver();
            String adb = Settings.Global.getString(cr, "adb_enabled");
            String wifi = Settings.Global.getString(cr, "adb_wifi_enabled");
            String dev = Settings.Global.getString(cr, "development_settings_enabled");
            r.add("adb_enabled = " + q(adb));
            r.add("adb_wifi_enabled = " + q(wifi));
            r.add("development_settings_enabled = " + q(dev));

            if (wifi != null) {
                r.status = ProbeResult.AVAILABLE;
                r.summary = "ROM 提供无线调试开关（`adb_wifi_enabled` 键存在，当前值 "
                        + wifi + "）⇒ 能力存在。";
            } else if (adb != null) {
                r.status = ProbeResult.UNKNOWN;
                r.summary = "只看到 USB 调试键（`adb_enabled`），没有 `adb_wifi_enabled` ——"
                        + "说不清是 ROM 无此功能、还是键尚未初始化。";
            } else {
                r.status = ProbeResult.UNKNOWN;
                r.summary = "三个键都读不到 —— 无法判断。";
            }
        } catch (Throwable t) {
            r.summary = "探测抛异常 —— 无法判断。";
            r.add("异常：" + t.getClass().getName() + " / " + t.getMessage());
        }
        return r;
    }

    // ───────────────────────── ④ 蓝牙 HID Device profile ─────────────────────────

    /**
     * ④ ROM 提不提供蓝牙 **HID Device** profile（把本机当蓝牙键盘/遥控器）。
     *
     * ⚠️ **不主动开蓝牙**（用户可见的状态变化）⇒ 蓝牙关着就判「不确定」。
     * ⚠️ 回调**超时也判「不确定」**——有些 ROM 会把失败静默掉，超时 ≠ 不支持。
     * 🔴 预期结果：本工程 manifest **未声明 BLUETOOTH 权限**（本卡明文不加权限）⇒
     *   大概率在这里抛 `SecurityException` ⇒ 结论「不确定」，并**如实记下**。
     *   这本身就是一条有价值的结论：**要探/要用蓝牙 HID，必须先加 BLUETOOTH（normal 权限）**。
     */
    public static ProbeResult bluetoothHid(Context c) {
        ProbeResult r = new ProbeResult(4, "蓝牙 HID Device", ProbeResult.UNKNOWN, "");
        BluetoothAdapter ad = null;
        boolean requested = false;
        BluetoothProfile proxy = null;
        try {
            ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) {
                r.status = ProbeResult.UNAVAILABLE;
                r.summary = "本机没有蓝牙适配器（getDefaultAdapter 返回 null）"
                        + "⇒ 蓝牙 HID 这条路走不通。";
                r.add("BluetoothAdapter.getDefaultAdapter() = null");
                return r;
            }
            int st = ad.getState();
            r.add("BluetoothAdapter：name=" + ad.getName() + " state=" + st
                    + "（10=OFF 11=TURNING_ON 12=ON 13=TURNING_OFF）");
            if (st != BluetoothAdapter.STATE_ON) {
                r.status = ProbeResult.UNKNOWN;
                r.summary = "蓝牙当前未开启 —— 不擅自开启（用户可见的状态变化），本轮不判。";
                return r;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final BluetoothProfile[] got = new BluetoothProfile[1];
            BluetoothProfile.ServiceListener l = new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile p) {
                    got[0] = p;
                    latch.countDown();
                }
                @Override
                public void onServiceDisconnected(int profile) {
                    latch.countDown();
                }
            };
            requested = ad.getProfileProxy(c, l, BluetoothProfile.HID_DEVICE);
            r.add("getProfileProxy(ctx, l, HID_DEVICE=" + BluetoothProfile.HID_DEVICE
                    + ") 返回 = " + requested);

            boolean done = latch.await(BLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            r.add("回调在 " + BLE_TIMEOUT_MS + "ms 内"
                    + (done ? "已返回" : "未返回（超时）"));
            proxy = got[0];

            if (!done) {
                r.status = ProbeResult.UNKNOWN;
                r.summary = "回调超时 —— ROM 可能静默吞掉了失败，不判「不可用」。";
            } else if (proxy != null) {
                r.status = ProbeResult.AVAILABLE;
                r.summary = "拿到非空 HID_DEVICE 代理 ⇒ ROM 支持 HID Device profile。";
                r.add("proxy = " + proxy.getClass().getName());
            } else {
                r.status = ProbeResult.UNAVAILABLE;
                r.summary = "回调返回但代理为空 ⇒ 本 ROM 未提供 HID Device profile。";
            }
        } catch (Throwable t) {
            r.status = ProbeResult.UNKNOWN;
            r.summary = "探测抛异常（多半是缺 BLUETOOTH 权限 —— 本卡明文不加权限）⇒ 不判。";
            r.add("异常：" + t.getClass().getName() + " / " + t.getMessage());
        } finally {
            // 只读原则：借到的代理原样还回去（失败无所谓，不影响结论）
            if (requested && ad != null && proxy != null) {
                try {
                    ad.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy);
                } catch (Throwable ignore) { }
            }
        }
        return r;
    }

    // ───────────────────────── ⑤ EPD（墨水屏）接口 ─────────────────────────

    /**
     * ⑤ EPD（墨水屏）接口在哪里。
     *
     * App 内能查的一半：`/dev`、`/sys/class`、`/sys/devices/platform`、`/proc/devices`
     * 里含 `epd/eink/epaper/ink` 的项。
     * 🔴 另一半（**Binder 服务名**）App 内**拿不到**：`ServiceManager` 是 @hide 且需 DUMP 权限，
     *   且 API 28+ 拦 hidden-API 反射 ⇒ **由验证阶段用 adb 补证**，写进 ADR 证据，**不做进 App**。
     * ⚠️ 无命中**不构成**「没有 EPD」的证据（可能命名不同）⇒ 判**不确定**，不判不可用。
     */
    public static ProbeResult epd(Context c) {
        ProbeResult r = new ProbeResult(5, "EPD（墨水屏）接口", ProbeResult.UNKNOWN, "");
        try {
            int[] readable = {0};      // 成功列出的目录数（区分「读到了但没命中」与「根本读不到」）
            int hits = 0;
            hits += scanDir("/dev", r, readable);
            hits += scanDir("/sys/class", r, readable);
            hits += scanDir("/sys/devices/platform", r, readable);

            boolean procOk = false;
            String pd = readText("/proc/devices");
            if (pd == null) {
                r.add("/proc/devices 读取失败");
            } else {
                procOk = true;
                String[] lines = pd.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String low = lines[i].trim().toLowerCase(Locale.US);
                    for (int k = 0; k < EPD_KEYS.length; k++) {
                        if (low.contains(EPD_KEYS[k])) {
                            r.add("/proc/devices: " + lines[i].trim());
                            hits++;
                            break;
                        }
                    }
                }
            }

            if (hits > 0) {
                r.status = ProbeResult.AVAILABLE;
                r.summary = "发现 " + hits + " 处 epd/eink/epaper 线索 ⇒"
                        + "本机有墨水屏相关节点，可据此进一步研究刷新接口。";
            } else if (readable[0] == 0 && !procOk) {
                // 🔴 真机实测：untrusted_app 读 /dev、/sys、/proc 全部被 SELinux 拒（avc denied）
                // ⇒ 这种情形下「没找到」**不等于**「不存在」，必须如实写成「读不到」
                r.status = ProbeResult.UNKNOWN;
                r.summary = "候选路径**全部读不到**（SELinux 拒绝 untrusted_app 访问"
                        + " /dev、/sys、/proc）⇒ App 内无法探测，只能靠 adb 补证（见 ADR-008）。";
            } else {
                r.status = ProbeResult.UNKNOWN;
                r.summary = "在可读的 " + readable[0] + " 个路径里未发现以 epd/eink/epaper"
                        + " 命名的节点 —— 可能命名不同，不足以断定「没有接口」。";
            }
            r.add("（Binder 服务名 App 内拿不到：ServiceManager 为 @hide 且需 DUMP 权限 ⇒"
                    + " 由开发者在验证阶段用 adb 补证，写进 ADR-008）");
        } catch (Throwable t) {
            r.summary = "探测抛异常 —— 无法判断。";
            r.add("异常：" + t.getClass().getName() + " / " + t.getMessage());
        }
        return r;
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    /**
     * 扫 `/system/usr/keylayout/*.kl`，列出含 VOLUME_UP/DOWN 的行。
     * @return 命中行数；目录不可列为 -1
     */
    private static int scanKeyLayouts(ProbeResult r) {
        File dir = new File("/system/usr/keylayout");
        File[] fs = dir.listFiles();
        if (fs == null) {
            r.add("/system/usr/keylayout 不可列（不存在或无权限）");
            return -1;
        }
        int hits = 0;
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (!f.getName().endsWith(".kl")) continue;
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    String up = line.trim().toUpperCase(Locale.US);
                    if (up.contains("VOLUME_UP") || up.contains("VOLUME_DOWN")) {
                        r.add(f.getName() + ": " + line.trim());
                        hits++;
                    }
                }
            } catch (Throwable t) {
                r.add(f.getName() + " 读取失败：" + t.getClass().getSimpleName());
            } finally {
                closeQuietly(br);
            }
        }
        if (hits == 0) {
            r.add("/system/usr/keylayout 下未见 VOLUME_UP/VOLUME_DOWN");
        }
        return hits;
    }

    /**
     * 列一个目录的一级子项，命中 {@link #EPD_KEYS} 任一即记证据。
     * @param readableDirs 长度 1 的计数器：成功列出时 +1 —— 用来区分「读到了但没命中」
     *                     与「根本读不到」（后者绝不能写成「不存在」）
     * @return 命中数
     */
    private static int scanDir(String path, ProbeResult r, int[] readableDirs) {
        File dir = new File(path);
        File[] fs = dir.listFiles();
        if (fs == null) {
            r.add(path + " 不可列（不存在或无权限）");
            return 0;
        }
        if (readableDirs != null && readableDirs.length > 0) readableDirs[0]++;
        int hits = 0;
        for (int i = 0; i < fs.length; i++) {
            String name = fs[i].getName().toLowerCase(Locale.US);
            for (int k = 0; k < EPD_KEYS.length; k++) {
                if (name.contains(EPD_KEYS[k])) {
                    r.add(path + "/" + fs[i].getName());
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    private static String readText(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(br);
        }
    }

    private static void closeQuietly(BufferedReader br) {
        if (br != null) {
            try { br.close(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 虚拟输入设备（无障碍/输入法注入的）**不算**「机身实体键」。
     *
     * 🔴 真机实测教训：本机 5 个输入设备里 `Virtual`（id=1）也自报 VOLUME_UP/DOWN=true，
     *   若不加区分，会把「虚拟设备支持」误读成「机身装了音量键」。
     *   真正能证明机身在实体上有键的是 `adc-keys` 这类**物理**设备。
     */
    private static boolean isVirtual(InputDevice d) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return d.isVirtual();
        } catch (Throwable ignore) { }
        // API 28 及以下没有公开 API ⇒ 退回名字法（本机虚拟设备名就叫 "Virtual"）
        String n = d.getName();
        return n != null && n.toLowerCase(Locale.US).contains("virtual");
    }

    /** 从 AccessibilityServiceInfo 里取包名（任何一层缺失都返回占位串，不抛） */
    private static String pkgOf(AccessibilityServiceInfo info) {
        try {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                return info.getResolveInfo().serviceInfo.packageName;
            }
        } catch (Throwable ignore) { }
        return "(unknown)";
    }

    /** 值 → 可打印字符串：`null` 必须显式可见（`""` 与 `null` 含义完全不同） */
    private static String q(String v) {
        return (v == null) ? "<null>" : ("\"" + v + "\"");
    }
}
