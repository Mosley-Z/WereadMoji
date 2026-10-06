package com.inkread.weekread.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 🆕 TASK-061 · 遥控台「键盘」页（自绘容器 + 原生输入框）。
 *
 * <p>把**手机上的文本**通过 HID 键盘"打"进**墨水屏的输入框**（如微信读书搜索框）。两种模式：
 * <ol>
 *   <li><b>整段发送</b>（默认）：把输入框里的文字**逐字符**发出（≈ 替你超快敲一遍）；</li>
 *   <li><b>实时同步</b>（可选）：边打边发 —— 每个改动按"删几个 + 补几个"的**增量**发出。</li>
 * </ol>
 *
 * <h3>🔴 硬边界（如实呈现，⛔ 不静默丢字）</h3>
 * HID 键盘只能发**按键码** ⇒ <b>仅 ASCII（英文 / 数字 / 符号）可发</b>；
 * <b>中文 / emoji 会被跳过</b>（协议层不可行），跳过个数由宿主如实提示。
 * 另：墨水屏侧须**停在可聚焦输入框**，否则按键会落到别处。
 *
 * <h3>🔴 模块边界</h3>
 * 本类属 `ui` 包，**不认识** {@code remote.HidKeymap} / {@code HidConst}（映射与发送全在
 * `shell/ConsoleActivity`）⇒ 只往上抛**语义事件**：整段文本 / 常用键 id / 文本增量。
 *
 * <h3>增量算法（实时同步）</h3>
 * 记 {@code sentText} = 最近一次已同步的文本。每次变更取**公共前缀** {@code p}：
 * 抛 {@code onSyncDelta(sentText.substring(p), newText.substring(p))}（被删的尾巴 + 新增的尾巴）；
 * 宿主按"可映射字符数"补等量退格、再把新尾巴逐字发出。
 * 🔴 输入法**组字中**（拼音候选态）不处理 —— 否则拼音字母会被当正文发出去。
 */
public class KeyboardPageView extends LinearLayout {

    /** 常用键（**本类自己的键 id**，不是 HID usage；映射在 `ConsoleActivity`）。 */
    public static final int K_ENTER = 201;
    /** 常用键：退格。 */
    public static final int K_BACKSPACE = 202;
    /** 常用键：空格。 */
    public static final int K_SPACE = 203;
    /** 常用键：Tab（跳格）。 */
    public static final int K_TAB = 204;
    /** 常用键：向前删除（Del）。 */
    public static final int K_DELETE = 205;

    /** 语义回调（宿主负责翻译成 HID 按键并发出去）。 */
    public interface Listener {
        /** 整段发送：把 {@code text} 逐字符发出。 */
        void onSendAll(String text);

        /** 常用键（id 见 {@link #K_ENTER} 等）。 */
        void onCommonKey(int keyId);

        /** 实时同步的**增量**：删掉 {@code deletedTail} 对应的字符、补上 {@code inserted}。 */
        void onSyncDelta(String deletedTail, String inserted);

        /** 实时同步开关变化（宿主可据此提示/落盘）。 */
        void onRealtimeChanged(boolean on);
    }

    private static final String[] COMMON_KEYS = new String[] { "回车", "退格", "空格", "跳格", "删除" };
    private static final int[] COMMON_IDS = new int[] { K_ENTER, K_BACKSPACE, K_SPACE, K_TAB, K_DELETE };

    private EditText et;
    private TextView tvStatus;
    private TextView btnSend;
    private TextView btnClear;
    private CheckBox cbRealtime;

    private Listener listener;
    private boolean realtime = false;
    private boolean sending = false;
    /** 最近一次"已同步"的文本（实时模式下用于算增量）。 */
    private String sentText = "";

    private final TextWatcher watcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
        @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }

        @Override public void afterTextChanged(Editable e) {
            String now = (e == null) ? "" : e.toString();
            if (!realtime || listener == null || sending) {
                sentText = now;          // 非实时：只记账（切回实时时不致"补发一大段"）
                return;
            }
            if (isComposing(e)) return;  // 输入法组字中：不处理（避免拼音字母被发出）
            int p = commonPrefix(sentText, now);
            String del = sentText.substring(p);
            String ins = now.substring(p);
            sentText = now;
            if (del.length() > 0 || ins.length() > 0) listener.onSyncDelta(del, ins);
        }
    };

    public KeyboardPageView(Context c) {
        this(c, null);
    }

    public KeyboardPageView(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(VERTICAL);
        int pad = (int) InkTheme.dp(c, 16f);
        setPadding(pad, pad, pad, pad);
        buildUi();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 当前输入框文本。 */
    public String getText() {
        return et == null ? "" : et.getText().toString();
    }

    /** 实时同步开关（默认关 —— 默认走"整段发送"这条更可控的路）。 */
    public boolean isRealtime() {
        return realtime;
    }

    public void setRealtime(boolean on) {
        realtime = on;
        if (cbRealtime != null && cbRealtime.isChecked() != on) cbRealtime.setChecked(on);
        if (on) sentText = getText();   // 开的时候对齐基线，不"补发历史"
    }

    /** 状态行（"已发送 N 个字符 · 跳过 M 个非 ASCII" 之类）。 */
    public void setStatus(String s) {
        if (tvStatus != null) tvStatus.setText(s == null ? "" : s);
    }

    /** 发送中：禁用两个主按钮并把「发送」改文案（防连点导致错乱）。 */
    public void setSending(boolean on) {
        this.sending = on;
        if (btnSend != null) {
            btnSend.setEnabled(!on);
            btnSend.setText(on ? "发送中…" : "发送");
            btnSend.setTextColor(on ? InkTheme.ink3(getContext()) : InkTheme.bamboo(getContext()));
        }
        if (btnClear != null) btnClear.setEnabled(!on);
    }

    /** 🆕 TASK-041：色板切换后重建（子 View 着色在 buildUi 时定死 ⇒ 只能重建）。 */
    public void rebuild() {
        String keep = getText();
        boolean rt = realtime;
        realtime = false;              // 🔴 重建期间屏蔽增量（setText 会触发 watcher）
        removeAllViews();
        int pad = (int) InkTheme.dp(getContext(), 16f);
        setPadding(pad, pad, pad, pad);
        buildUi();
        if (et != null) et.setText(keep);
        sentText = keep;
        setRealtime(rt);
    }

    // ── 构建（一次性）──

    private void buildUi() {
        Context c = getContext();

        // ① 说明（如实写边界）
        TextView note = new TextView(c);
        note.setText("把手机上的文字打到墨水屏 —— 先让墨水屏停在输入框（如搜索框），再输入并点「发送」。\n"
                + "⚠ 仅英文 / 数字 / 符号可发（蓝牙键盘协议限制）；中文会被跳过并计数。");
        note.setTextColor(InkTheme.ink3(c));
        note.setTextSize(12f);
        note.setLineSpacing(0f, 1.35f);
        note.setPadding(0, 0, 0, (int) InkTheme.dp(c, 10f));
        addView(note);

        // ② 多行输入框（吸收剩余空间）
        et = new EditText(c);
        et.setHint("在此输入要发送的文字…");
        et.setHintTextColor(InkTheme.ink3(c));
        et.setTextColor(InkTheme.ink(c));
        et.setTextSize(16f);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        et.setMinimumHeight((int) InkTheme.dp(c, 96f));
        GradientDrawable box = new GradientDrawable();
        box.setColor(InkTheme.paper2(c));
        box.setCornerRadius(InkTheme.dp(c, 14f));
        box.setStroke(Math.max(1, (int) InkTheme.dp(c, 0.5f)), InkTheme.line(c));
        et.setBackground(box);
        int ep = (int) InkTheme.dp(c, 12f);
        et.setPadding(ep, ep, ep, ep);
        LayoutParams elp = new LayoutParams(LayoutParams.MATCH_PARENT, 0);
        elp.weight = 1f;
        et.setLayoutParams(elp);
        et.addTextChangedListener(watcher);
        addView(et);

        // ③ 状态行（宿主写入；默认给一句"待发送"）
        tvStatus = new TextView(c);
        tvStatus.setText("");
        tvStatus.setTextColor(InkTheme.ink3(c));
        tvStatus.setTextSize(12f);
        tvStatus.setLineSpacing(0f, 1.3f);
        tvStatus.setPadding(0, (int) InkTheme.dp(c, 6f), 0, 0);
        addView(tvStatus);

        // ④ 实时同步开关
        cbRealtime = new CheckBox(c);
        cbRealtime.setText("实时同步输入（边打边发；出问题就关掉用「发送」）");
        cbRealtime.setTextColor(InkTheme.ink(c));
        cbRealtime.setTextSize(13f);
        cbRealtime.setChecked(realtime);
        cbRealtime.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                realtime = checked;
                sentText = getText();
                if (listener != null) listener.onRealtimeChanged(checked);
            }
        });
        LayoutParams clp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        clp.topMargin = (int) InkTheme.dp(c, 6f);
        cbRealtime.setLayoutParams(clp);
        addView(cbRealtime);

        // ⑤ 两个主操作：发送 / 清空
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        LayoutParams rlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) InkTheme.dp(c, 8f);
        row.setLayoutParams(rlp);

        btnSend = button("发送", true, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener == null || sending) return;
                hideSoftKeyboard();
                listener.onSendAll(getText());
            }
        });
        btnClear = button("清空", false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sending) return;
                setText("");       // 只清**输入框**；实时模式下墨水屏会随之回删
                setStatus("");
            }
        });
        row.addView(btnSend, weighted(1f, true));
        row.addView(btnClear, weighted(1f, false));
        addView(row);

        // ⑥ 常用键一行（回车 / 退格 / 空格 / 跳格 / 删除）
        TextView kl = new TextView(c);
        kl.setText("常用键");
        kl.setTextColor(InkTheme.ink2(c));
        kl.setTextSize(13f);
        kl.setPadding(0, (int) InkTheme.dp(c, 12f), 0, (int) InkTheme.dp(c, 4f));
        addView(kl);

        LinearLayout krow = new LinearLayout(c);
        krow.setOrientation(HORIZONTAL);
        krow.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < COMMON_KEYS.length; i++) {
            final int id = COMMON_IDS[i];
            TextView k = button(COMMON_KEYS[i], false, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideSoftKeyboard();
                    if (!sending && listener != null) listener.onCommonKey(id);
                }
            });
            k.setTextSize(14f);
            krow.addView(k, weighted(1f, i > 0));
        }
        addView(krow);
    }

    private void setText(String s) {
        if (et != null) et.setText(s);
    }

    // ── 小部件工厂 ──

    /** 描边按钮（docs/09 §5.6）：主操作给竹青弱底，次操作给纸底。 */
    private TextView button(String label, boolean primary, OnClickListener l) {
        Context c = getContext();
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(primary ? InkTheme.bamboo(c) : InkTheme.ink(c));
        tv.setTextSize(15f);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? InkTheme.bambooWeak(c) : InkTheme.paper(c));
        bg.setCornerRadius(InkTheme.dp(c, 14f));
        bg.setStroke(Math.max(1, (int) InkTheme.dp(c, 1f)), InkTheme.line(c));
        tv.setBackground(bg);
        int vp = (int) InkTheme.dp(c, 12f);
        tv.setPadding(vp, vp, vp, vp);
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    private LayoutParams weighted(float w, boolean leftGap) {
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, w);
        if (leftGap) lp.leftMargin = (int) InkTheme.dp(getContext(), 8f);
        return lp;
    }

    private void hideSoftKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    // ── 纯字符串工具 ──

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /** 输入法是否处于**组字中**（拼音候选态）—— 是则不把当前内容当正文处理。 */
    private static boolean isComposing(Editable e) {
        try {
            return BaseInputConnection.getComposingSpanStart(e) >= 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
