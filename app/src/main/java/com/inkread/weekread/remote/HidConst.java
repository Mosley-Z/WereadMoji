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

    // ── 🆕 TASK-060 / TASK-061：扩展键盘 usage（取值 = HID Usage Tables, Keyboard/Keypad page 0x07）──
    //
    // 🔴 **usage ≠ Android KeyCode**：硬件层由 Android 的 HID 键位表把它翻成 KeyCode。
    //    几个**容易踩的映射**（真机实证见 `验证记录/171`、`验证记录/172`）：
    //    · {@link #KEY_HOME}=0x4A ⇒ Android `MOVE_HOME`（**行首**），**不是**系统 Home；
    //    · {@link #KEY_APP}=0x65  ⇒ Android `MENU`（应用键）；
    //    · 「返回 / 最近任务」在 HID 键盘 usage 表里 **无直接对应** ⇒ 本工程不提供。

    /** 回车 / 确认（Android `ENTER`）。 */
    public static final int KEY_ENTER = 0x28;
    /** 退出 / 关层（Android `ESCAPE`）。 */
    public static final int KEY_ESC = 0x29;
    /** 退格（Android `DEL`）。 */
    public static final int KEY_BACKSPACE = 0x2A;
    /** 跳格 / 换焦点（Android `TAB`）。 */
    public static final int KEY_TAB = 0x2B;
    /** 空格。 */
    public static final int KEY_SPACE = 0x2C;
    /** 行首（Android `MOVE_HOME`；⚠️ 非系统 Home）。 */
    public static final int KEY_HOME = 0x4A;
    /** 向前删除（Android `FORWARD_DEL`）。 */
    public static final int KEY_DELETE = 0x4C;
    /** 行尾（Android `MOVE_END`）。 */
    public static final int KEY_END = 0x4D;
    /** 方向：右（Android `DPAD_RIGHT`）。 */
    public static final int KEY_RIGHT = 0x4F;
    /** 方向：左（Android `DPAD_LEFT`）。 */
    public static final int KEY_LEFT = 0x50;
    /** 方向：下（Android `DPAD_DOWN`）。 */
    public static final int KEY_DOWN = 0x51;
    /** 方向：上（Android `DPAD_UP`）。 */
    public static final int KEY_UP = 0x52;
    /** 应用 / 菜单键（Android `MENU`）。 */
    public static final int KEY_APP = 0x65;

    // ── 🆕 TASK-061：可打印字符的「基准键」usage（供 `HidKeymap` 按偏移算字母 / 数字）──
    //
    //  · 字母 a..z = 0x04..0x1D（**大小写同 usage**，大写额外置 `MOD_LSHIFT`）；
    //  · 数字 1..9 = 0x1E..0x26，0 = 0x27（不连续，单独给）；
    //  · 符号键的**下档**用下列基准 usage，**上档**再加 `MOD_LSHIFT`（映射见 `HidKeymap.of`）。

    /** 字母基准：`a`=0x04 … `z`=0x1D。 */
    public static final int KEY_A = 0x04;
    /** 数字基准：`1`=0x1E … `9`=0x26。 */
    public static final int KEY_1 = 0x1E;
    /** 数字 `0`（不在连续段内，单独给）。 */
    public static final int KEY_0 = 0x27;
    /** 符号 减号 / 上档 下划线。 */
    public static final int KEY_MINUS = 0x2D;
    /** 符号 等号 / 上档 加号。 */
    public static final int KEY_EQUAL = 0x2E;
    /** 符号 左方括号 / 上档 左花括号。 */
    public static final int KEY_LBRACKET = 0x2F;
    /** 符号 右方括号 / 上档 右花括号。 */
    public static final int KEY_RBRACKET = 0x30;
    /** 符号 反斜杠 / 上档 竖线。 */
    public static final int KEY_BACKSLASH = 0x31;
    /** 符号 分号 / 上档 冒号。 */
    public static final int KEY_SEMICOLON = 0x33;
    /** 符号 单引号 / 上档 双引号。 */
    public static final int KEY_APOSTROPHE = 0x34;
    /** 符号 反引号 / 上档 波浪号。 */
    public static final int KEY_GRAVE = 0x35;
    /** 符号 逗号 / 上档 小于号。 */
    public static final int KEY_COMMA = 0x36;
    /** 符号 句点 / 上档 大于号。 */
    public static final int KEY_DOT = 0x37;
    /** 符号 斜杠 / 上档 问号。 */
    public static final int KEY_SLASH = 0x38;

    // ── 🆕 TASK-061：键盘修饰键位图（Report 第 0 字节；bit0=LCtrl … bit7=RGUI）──

    /** 无修饰键。 */
    public static final int MOD_NONE = 0x00;
    /** 左 Shift（打大写字母 / 上档符号用）。 */
    public static final int MOD_LSHIFT = 0x02;
    /** 右 Shift。 */
    public static final int MOD_RSHIFT = 0x20;

    // ── 消费者 usage（Consumer page 0x0C）——⏳ 未验证，本卡不用（经典键盘路优先）——

    /** 音量加（消费者路，未验证）。 */
    public static final int CONSUMER_VOLUME_UP = 0xE9;
    /** 音量减（消费者路，未验证）。 */
    public static final int CONSUMER_VOLUME_DOWN = 0xEA;

    private HidConst() {
    }
}
