package com.inkread.weekread.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 待办清单的本地存储（TASK-024 / V1.0.3-beta）。
 *
 * 🔴 **零依赖**：`SharedPreferences("cfg")` 里存一个 JSON 数组（键 {@link #K_LIST}）——
 * 与 {@link StatsStore} / {@code BookStore} / {@code NoteStore} 同一套口径，
 * **不引 SQLite、不引第三方库**。写盘一律 {@code commit()}（重启不丢，验收 A1）。
 *
 * 列表约定：**统一按 {@link TodoItem#order} 升序**存与读；{@link #pending} / {@link #done}
 * 只是按完成态过滤后的视图（不改变底层顺序）。
 */
public final class TodoStore {

    private static final String PREFS = "cfg";
    /** 待办数组（JSON） */
    private static final String K_LIST = "todo_list";
    /** 下一个可分配的 id（单调递增，删除后不复用） */
    private static final String K_NEXT_ID = "todo_next_id";

    private TodoStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 全量读取（按 order 升序）。解析失败的条目**跳过**，不因一条坏数据丢整张清单。 */
    public static List<TodoItem> load(Context c) {
        List<TodoItem> out = new ArrayList<TodoItem>();
        String raw = sp(c).getString(K_LIST, null);
        if (raw == null || raw.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                try {
                    out.add(TodoItem.fromJson(o));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        sortByOrder(out);
        return out;
    }

    /** 全量写回（调用方负责传"已排好序"的列表；本方法也会再排一次，稳妥） */
    public static void save(Context c, List<TodoItem> list) {
        sortByOrder(list);
        JSONArray arr = new JSONArray();
        for (TodoItem it : list) arr.put(it.toJson());
        sp(c).edit().putString(K_LIST, arr.toString()).commit();
    }

    /** 未完成项（按 order 升序）—— 桌面卡片与 APP「待办列表」都用它 */
    public static List<TodoItem> pending(Context c) {
        List<TodoItem> out = new ArrayList<TodoItem>();
        for (TodoItem it : load(c)) if (!it.done) out.add(it);
        return out;
    }

    /** 已完成项（按 order 升序） */
    public static List<TodoItem> done(Context c) {
        List<TodoItem> out = new ArrayList<TodoItem>();
        for (TodoItem it : load(c)) if (it.done) out.add(it);
        return out;
    }

    /** 追加一条（id 与 order 由本方法分配：order = 当前最大 + 1，落在末尾） */
    public static TodoItem add(Context c, String content, long dateSec, int minuteOfDay) {
        List<TodoItem> list = load(c);
        long id = sp(c).getLong(K_NEXT_ID, 1L);
        sp(c).edit().putLong(K_NEXT_ID, id + 1L).commit();
        TodoItem it = new TodoItem(id, content);
        it.dateSec = dateSec;
        it.minuteOfDay = minuteOfDay;
        long maxOrder = -1L;
        for (TodoItem x : list) if (x.order > maxOrder) maxOrder = x.order;
        it.order = maxOrder + 1L;
        list.add(it);
        save(c, list);
        return it;
    }

    /** 按 id 更新内容 / 日期 / 时间（完成态与顺序不动） */
    public static void update(Context c, long id, String content, long dateSec, int minuteOfDay) {
        List<TodoItem> list = load(c);
        for (TodoItem it : list) {
            if (it.id == id) {
                it.content = content;
                it.dateSec = dateSec;
                it.minuteOfDay = minuteOfDay;
            }
        }
        save(c, list);
    }

    public static void remove(Context c, long id) {
        List<TodoItem> list = load(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id == id) list.remove(i);
        }
        save(c, list);
    }

    /** 勾选 / 取消勾选（验收 A2：勾选移入已完成、取消移回待办） */
    public static void toggleDone(Context c, long id) {
        List<TodoItem> list = load(c);
        for (TodoItem it : list) if (it.id == id) it.done = !it.done;
        save(c, list);
    }

    /** 直接设完成态（桌面卡片点击勾选用，避免"读到旧值"的竞态） */
    public static void setDone(Context c, long id, boolean done) {
        List<TodoItem> list = load(c);
        for (TodoItem it : list) if (it.id == id) it.done = done;
        save(c, list);
    }

    /**
     * 排序态 ↑↓：把 {@code id} 那条在**全量列表**里与前/后一条互换位置
     * （交换 {@link TodoItem#order} 值，再归一化为 0..n-1）。
     *
     * @param delta -1 = 上移，+1 = 下移
     * @return true = 真的动了（已在顶/底时返回 false，UI 据此不重绘）
     */
    public static boolean move(Context c, long id, int delta) {
        List<TodoItem> list = load(c);
        int idx = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id == id) idx = i;
        if (idx < 0) return false;
        // 🔴 只在**同一完成态**的组内找相邻项 —— 待办视图与已完成视图各自过滤，
        // 若直接与全量列表的相邻项交换，会跨组乱序（"已完成"夹在中间时尤为明显）。
        boolean d = list.get(idx).done;
        int to = -1;
        if (delta < 0) {
            for (int i = idx - 1; i >= 0; i--) if (list.get(i).done == d) { to = i; break; }
        } else {
            for (int i = idx + 1; i < list.size(); i++) if (list.get(i).done == d) { to = i; break; }
        }
        if (to < 0) return false;                        // 已是同组第一条 / 最后一条
        java.util.Collections.swap(list, idx, to);
        for (int i = 0; i < list.size(); i++) list.get(i).order = i;    // 归一化
        save(c, list);
        return true;
    }

    private static void sortByOrder(List<TodoItem> list) {
        java.util.Collections.sort(list, new java.util.Comparator<TodoItem>() {
            @Override
            public int compare(TodoItem a, TodoItem b) {
                if (a.order != b.order) return a.order < b.order ? -1 : 1;
                return a.id < b.id ? -1 : (a.id == b.id ? 0 : 1);
            }
        });
    }
}
