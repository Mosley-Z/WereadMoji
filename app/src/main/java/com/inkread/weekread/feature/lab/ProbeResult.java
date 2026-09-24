package com.inkread.weekread.feature.lab;

import java.util.ArrayList;
import java.util.List;

/**
 * 单项「设备能力探测」的结论模型（TASK-000）。
 *
 * 由 {@link CapabilityProbes} / {@link DefaultHomeProbe} 产出，{@link LabRunner} 汇总。
 *
 * 🔴 三档结论的语义必须严格区分（卡的验收第 1 条）：
 *   · {@link #AVAILABLE}   可用   —— 拿到了「这条路走得通」的确凿证据；
 *   · {@link #UNAVAILABLE} 不可用 —— 拿到了「这条路走不通」的确凿证据；
 *   · {@link #UNKNOWN}     不确定 —— 探针跑了，但证据不足以支持上面任何一档。
 *
 * 🔴 铁律：**探测失败 ≠ 不可用**。任何异常、任何读不到，都必须落成 {@link #UNKNOWN}。
 *   把「我没探到」写成「你没这个能力」，会让后续排期做出完全错误的取舍 ——
 *   本卡存在的意义是**把「能不能做」的问号消掉**，而不是造一批新的假问号。
 *   （同类教训见项目的「桌面让位」判据：宁可少让位一次，也不能把卡片藏死。）
 */
public final class ProbeResult {

    /** 可用 */
    public static final int AVAILABLE = 0;
    /** 不可用 */
    public static final int UNAVAILABLE = 1;
    /** 本次探测不确定 */
    public static final int UNKNOWN = 2;

    /** 项编号（卡的「要探的 5 件事」表里的 #） */
    public final int no;
    /** 项名（如「物理音量键」） */
    public final String title;
    /** 三档之一 */
    public int status;
    /** 一行结论（人话，不含原始值） */
    public String summary;
    /** 原始证据行：属性值 / 回调打印 / 异常类名 —— 逐条**原文**，不加工、不省略 */
    public final List<String> evidence = new ArrayList<String>();

    public ProbeResult(int no, String title, int status, String summary) {
        this.no = no;
        this.title = title;
        this.status = status;
        this.summary = summary;
    }

    /** 追加一条原始证据（链式，方便一行一条地拼） */
    public ProbeResult add(String line) {
        if (line != null) evidence.add(line);
        return this;
    }

    /** 三档的中文说法。页面与导出文本共用同一份措辞，避免两处写法不一致 */
    public String statusText() {
        switch (status) {
            case AVAILABLE:   return "可用";
            case UNAVAILABLE: return "不可用";
            default:          return "本次探测不确定";
        }
    }

    /** 导出用：`① 物理音量键 —— 可用` + 结论 + 缩进证据 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append(circle(no)).append(' ').append(title)
          .append(" —— ").append(statusText()).append('\n');
        if (summary != null && summary.length() > 0) {
            sb.append("    ").append(summary).append('\n');
        }
        for (int i = 0; i < evidence.size(); i++) {
            sb.append("    · ").append(evidence.get(i)).append('\n');
        }
        return sb.toString();
    }

    /** 1..9 → ①..⑨；超出范围退回 `N.` */
    private static String circle(int n) {
        if (n >= 1 && n <= 9) return String.valueOf((char) ('\u2460' + (n - 1)));
        return n + ".";
    }
}
