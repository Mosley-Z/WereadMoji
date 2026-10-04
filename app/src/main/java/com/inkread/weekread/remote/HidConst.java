package com.inkread.weekread.remote;

/**
 * TASK-033 · 蓝牙 HID 常量表（唯一来源，避免散落魔数）。
 *
 * <p>取值全部来自 **HID 规范 / Android 公开 API**，非任何第三方代码（`ADR-012` 合规要求）：
 * <ul>
 *   <li>{@link #PROFILE_HID_DEVICE} = {@code BluetoothProfile.HID_DEVICE} = 19（0x13）；</li>
 *   <li>{@link #SUBCLASS1_COMBO} = {@code BluetoothHidDevice.SUBCLASS1_COMBO} = 0xC0；</li>
 *   <li>键盘 usage（HID Usage Tables，Keyboard/Keypad page 0x07）：PAGE_UP / PAGE_DOWN 等；</li>
 *   <li>消费者 usage（Consumer page 0x0C）：音量键 —— ⏳ **未验证**，经典键盘路本卡不用。</li>
 * </ul>
 *
 * <p>🔴 落码基线 = `_probe/bt_hidprobe`（唯一真机端到端验证过的组合）：
 * 47 字节标准启动键盘描述符 + {@code sendReport(dev, reportId=1, 8字节)}。
 */
public final class HidConst {

    /** {@code BluetoothProfile.HID_DEVICE} = 19（0x13）：手机当 HID 外设用的 profile。 */
    public static final int PROFILE_HID_DEVICE = 19;

    /** {@code BluetoothHidDevice.SUBCLASS1_COMBO} = 0xC0：键盘 + 小键盘组合。 */
    public static final byte SUBCLASS1_COMBO = (byte) 0xC0;

    /** 键盘 Report ID（与 47 字节描述符里的 Report ID 一致）。 */
    public static final int REPORT_ID_KEYBOARD = 1;

    // ── 键盘 usage（Keyboard/Keypad page 0x07）——

    /** 上一页（键盘 PageUp）。 */
    public static final int KEY_PAGE_UP = 0x4B;
    /** 下一页（键盘 PageDown）。 */
    public static final int KEY_PAGE_DOWN = 0x4E;

    // ── 消费者 usage（Consumer page 0x0C）——⏳ 未验证，本卡不用（经典键盘路优先）——

    /** 音量加（消费者路，未验证）。 */
    public static final int CONSUMER_VOLUME_UP = 0xE9;
    /** 音量减（消费者路，未验证）。 */
    public static final int CONSUMER_VOLUME_DOWN = 0xEA;

    private HidConst() {
    }
}
