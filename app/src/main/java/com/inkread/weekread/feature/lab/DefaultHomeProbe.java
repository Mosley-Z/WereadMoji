package com.inkread.weekread.feature.lab;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统默认桌面探测（TASK-000 并入项 B3）。
 *
 * 🔴 **语义严格限定为「系统认定的默认桌面」**（即 HOME intent 的 `MATCH_DEFAULT_ONLY`
 *   解析结果），**不是**「当前前台是谁」。窗口内容能力只有 a11y 的设置页探测
 *   （TASK-009）在用，本探针刻意不读前台 —— 写出来只会误导。这正是本项目**踩过两次**的坑
 *   （把 A 桌面的事件当成 B 桌面的，见 docs/04）。
 *
 * 为什么值得在实验室页常显一行：下次判断「桌面让位」出问题时，
 * 先看一眼「现在谁是系统默认桌面」，比事后翻日志快得多。
 *
 * 🔴 2026-09-25 真机实测修正（**务必保留这段结论**）：
 *   `ResolveInfo.isDefault` **不可作判据**，而且**两侧读数根本不一致**：
 *     · App 侧（`queryIntentActivities(home, 0)`，不带 MATCH_DEFAULT_ONLY）→ 3 个候选**全为 false**；
 *     · adb 侧（`cmd package query-activities`，同一台机器）→ 同样 3 个候选**全为 true**。
 *   根因：该字段需 `MATCH_DEFAULT_ONLY` 才会被置位 ⇒ 它**根本不区分「谁是默认」**。
 *   真正的判据只有一个：`resolveActivity` **选中的那一个**。
 *   （原方案里的「候选数 = 1 时标『唯一』」也已作废 —— 本机候选恒为 3，永不触发。）
 */
public final class DefaultHomeProbe {

    /** 探测结果（纯数据，不持 Context） */
    public static final class Result {
        /** 被 resolveActivity 选中的组件扁平名（与 `adb … --brief` 同格式），null 表示没解析出来 */
        public String resolved;
        /** HOME 候选总数 */
        public int candidateCount;
        /** 候选明细：每行 = 扁平组件名 + `isDefault=true/false`（原文） */
        public final List<String> candidates = new ArrayList<String>();
        /** 非 null ⇒ 这次没算出来（页面应原样显示，不要吞掉） */
        public String error;
    }

    private DefaultHomeProbe() { }

    /**
     * 算一次默认桌面。**只读、不加权限、不轮询** —— 调用方只在页面可见时调一次。
     *
     * 判据与 `cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME`
     * 同语义。（⚠️ 那条命令**必须带 `-a MAIN`**：只给 `-c HOME` 时实测返回
     * `No activity found`，见 TASK-000 方案 §9.1。）
     */
    public static Result probe(Context c) {
        Result r = new Result();
        try {
            PackageManager pm = c.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);

            ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            r.resolved = (ri == null) ? null : flat(ri.activityInfo);

            List<ResolveInfo> all = pm.queryIntentActivities(home, 0);
            if (all != null) {
                r.candidateCount = all.size();
                for (int i = 0; i < all.size(); i++) {
                    ResolveInfo x = all.get(i);
                    r.candidates.add(flat(x.activityInfo)
                            + "  isDefault=" + x.isDefault
                            + "  priority=" + x.priority);
                }
            }
            if (r.resolved == null) {
                r.error = "resolveActivity 返回空（无默认桌面？）—— 候选 " + r.candidateCount + " 个";
            }
        } catch (Throwable t) {
            // 探测失败 ⇒ 报错原文，不假装"没有默认桌面"
            r.error = "探测失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " / " + t.getMessage());
        }
        return r;
    }

    /** 常显行的显示文本（页面用）。多行：第一行是默认桌面，后面是候选清单。 */
    public static String toDisplay(Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.error != null) {
            sb.append("当前系统默认桌面：读取失败\n").append(r.error).append('\n');
        } else {
            sb.append("当前系统默认桌面：").append(r.resolved).append('\n');
        }
        sb.append("HOME 候选 ").append(r.candidateCount).append(" 个：\n");
        for (int i = 0; i < r.candidates.size(); i++) {
            sb.append("  ").append(r.candidates.get(i)).append('\n');
        }
        sb.append("（判据 = resolveActivity 选中的那个。isDefault 不采用：实测 App 侧恒为 false、"
                + "adb 侧恒为 true，两侧读数就不一致）");
        return sb.toString();
    }

    /** 导出文本用（比页面再紧凑一点，省得贴出去太长） */
    public static String toText(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("系统默认桌面：").append(r.error == null ? r.resolved : ("读取失败 / " + r.error))
          .append("（候选 ").append(r.candidateCount).append(" 个）").append('\n');
        for (int i = 0; i < r.candidates.size(); i++) {
            sb.append("    · ").append(r.candidates.get(i)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 组件扁平名。刻意模仿 `adb shell cmd package resolve-activity --brief` 的输出格式
     * （`com.pkg/.Cls`）—— 卡的验收要求「与 adb 输出**逐字一致**」，格式不齐就对不上。
     */
    private static String flat(ActivityInfo ai) {
        if (ai == null) return "(unknown)";
        String p = ai.packageName;
        String n = ai.name;
        if (n == null) return p;
        if (n.startsWith(p + ".")) return p + "/." + n.substring(p.length() + 1);
        return p + "/" + n;
    }
}
