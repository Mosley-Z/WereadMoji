package com.inkread.weekread.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * 🆕 TASK-061 · ASCII 可打印字符 → HID 键盘「一次击发」（usage + 修饰位图）。
 *
 * <p>用途：把手机上输入的文本，通过 HID 键盘"打"进墨水屏的输入框（如微信读书搜索框）。
 * 见 {@code docs/FEATURES/remote.md}「遥控台『键盘』页」、{@code 验证记录/172}。
 *
 * <h3>🔴 硬边界（协议层，非本类可绕过）</h3>
 * HID 键盘 report **只有「按键码(usage) + 修饰位图」**，**没有 Unicode / 字符串字段** ⇒
 * <b>只有 ASCII 可打印字符（0x20–0x7E）能表达</b>；<b>中文 / emoji 一律不可行</b>，
 * 本类对它们返回 {@code null}（{@link #compile} / {@link #skippedCount} 据此**跳过并计数**，
 * 供 UI 如实提示，⛔ 绝不静默丢字、绝不谎报成功）。
 *
 * <h3>取值依据</h3>
 * HID Usage Tables · Keyboard/Keypad page 0x07（标准表，非第三方代码）：
 * <ul>
 *   <li>字母 {@code a..z} = 0x04..0x1D（大写同 usage + {@link HidConst#MOD_LSHIFT}）；</li>
 *   <li>数字 {@code 1..9} = 0x1E..0x26、{@code 0} = 0x27；</li>
 *   <li>符号键 = 其「下档」usage，上档再加 {@code MOD_LSHIFT}。</li>
 * </ul>
 */
public final class HidKeymap {

    /** 一次「按下并抬起」：usage + 修饰位图（{@link HidConst#MOD_*}）。 */
    public static final class Stroke {
        /** HID 键盘 usage（{@link HidConst#KEY_*}）。 */
        public final int usage;
        /** 修饰位图（{@link HidConst#MOD_NONE} / {@code MOD_LSHIFT}）。 */
        public final int modifier;

        public Stroke(int usage, int modifier) {
            this.usage = usage;
            this.modifier = modifier;
        }
    }

    private HidKeymap() {
    }

    /**
     * 单个字符 → 一次击发；**不可映射**（非 ASCII 可打印字符，如中文 / emoji / 控制字符）返回 {@code null}。
     *
     * <p>{@code '\n'} / {@code '\r'} 按**回车**处理（多行输入里换行 = 回车键），{@code '\t'} = Tab。
     */
    public static Stroke of(char c) {
        // 字母（大小写同 usage）
        if (c >= 'a' && c <= 'z') return new Stroke(HidConst.KEY_A + (c - 'a'), HidConst.MOD_NONE);
        if (c >= 'A' && c <= 'Z') return new Stroke(HidConst.KEY_A + (c - 'A'), HidConst.MOD_LSHIFT);
        // 数字
        if (c >= '1' && c <= '9') return new Stroke(HidConst.KEY_1 + (c - '1'), HidConst.MOD_NONE);
        if (c == '0') return new Stroke(HidConst.KEY_0, HidConst.MOD_NONE);

        final int S = HidConst.MOD_LSHIFT;
        switch (c) {
            // 空白 / 控制
            case '\n':
            case '\r':   return new Stroke(HidConst.KEY_ENTER, HidConst.MOD_NONE);
            case '\t':   return new Stroke(HidConst.KEY_TAB, HidConst.MOD_NONE);
            case ' ':    return new Stroke(HidConst.KEY_SPACE, HidConst.MOD_NONE);
            // 符号（下档 / 上档）
            case '-':    return new Stroke(HidConst.KEY_MINUS, HidConst.MOD_NONE);
            case '_':    return new Stroke(HidConst.KEY_MINUS, S);
            case '=':    return new Stroke(HidConst.KEY_EQUAL, HidConst.MOD_NONE);
            case '+':    return new Stroke(HidConst.KEY_EQUAL, S);
            case '[':    return new Stroke(HidConst.KEY_LBRACKET, HidConst.MOD_NONE);
            case '{':    return new Stroke(HidConst.KEY_LBRACKET, S);
            case ']':    return new Stroke(HidConst.KEY_RBRACKET, HidConst.MOD_NONE);
            case '}':    return new Stroke(HidConst.KEY_RBRACKET, S);
            case '\\':   return new Stroke(HidConst.KEY_BACKSLASH, HidConst.MOD_NONE);
            case '|':    return new Stroke(HidConst.KEY_BACKSLASH, S);
            case ';':    return new Stroke(HidConst.KEY_SEMICOLON, HidConst.MOD_NONE);
            case ':':    return new Stroke(HidConst.KEY_SEMICOLON, S);
            case '\'':   return new Stroke(HidConst.KEY_APOSTROPHE, HidConst.MOD_NONE);
            case '"':    return new Stroke(HidConst.KEY_APOSTROPHE, S);
            case '`':    return new Stroke(HidConst.KEY_GRAVE, HidConst.MOD_NONE);
            case '~':    return new Stroke(HidConst.KEY_GRAVE, S);
            case ',':    return new Stroke(HidConst.KEY_COMMA, HidConst.MOD_NONE);
            case '<':    return new Stroke(HidConst.KEY_COMMA, S);
            case '.':    return new Stroke(HidConst.KEY_DOT, HidConst.MOD_NONE);
            case '>':    return new Stroke(HidConst.KEY_DOT, S);
            case '/':    return new Stroke(HidConst.KEY_SLASH, HidConst.MOD_NONE);
            case '?':    return new Stroke(HidConst.KEY_SLASH, S);
            // 数字行的上档（! @ # $ % ^ & * ( )）
            case '!':    return new Stroke(HidConst.KEY_1, S);
            case '@':    return new Stroke(HidConst.KEY_1 + 1, S);
            case '#':    return new Stroke(HidConst.KEY_1 + 2, S);
            case '$':    return new Stroke(HidConst.KEY_1 + 3, S);
            case '%':    return new Stroke(HidConst.KEY_1 + 4, S);
            case '^':    return new Stroke(HidConst.KEY_1 + 5, S);
            case '&':    return new Stroke(HidConst.KEY_1 + 6, S);
            case '*':    return new Stroke(HidConst.KEY_1 + 7, S);
            case '(':    return new Stroke(HidConst.KEY_1 + 8, S);
            case ')':    return new Stroke(HidConst.KEY_0, S);
            // 其余（中文 / emoji / 控制字符）⇒ 协议层不可行，跳过
            default:     return null;
        }
    }

    /** 整串 → **可映射**的击发序列（保持顺序；不可映射者**跳过**）。 */
    public static List<Stroke> compile(String text) {
        List<Stroke> out = new ArrayList<>();
        if (text == null) return out;
        for (int i = 0; i < text.length(); i++) {
            Stroke s = of(text.charAt(i));
            if (s != null) out.add(s);
        }
        return out;
    }

    /** 整串里**可映射**（= 实际会发出）的字符个数。 */
    public static int mappedCount(String text) {
        if (text == null) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (of(text.charAt(i)) != null) n++;
        }
        return n;
    }

    /** 整串里**不可映射**（会被跳过）的字符个数 —— 供 UI 如实提示"已跳过 N 个非 ASCII 字符"。 */
    public static int skippedCount(String text) {
        if (text == null) return 0;
        return text.length() - mappedCount(text);
    }
}
