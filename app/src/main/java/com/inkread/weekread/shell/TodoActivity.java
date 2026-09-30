package com.inkread.weekread.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.inkread.weekread.R;
import com.inkread.weekread.a11y.CardA11yService;
import com.inkread.weekread.core.TodoItem;
import com.inkread.weekread.core.TodoStore;
import com.inkread.weekread.ui.SegTabView;

import java.util.Calendar;
import java.util.List;

/**
 * 待办管理页（TASK-024 / V1.0.3-beta）：增删改 / 勾选完成 / 待办·已完成两态切换 / 排序态 ↑↓。
 *
 * 入口：主页第 5 个页签「待办」（{@code MainActivity}）。
 * 🔴 自家 Activity 在前台时必须 {@link CardA11yService#noteOwnUiForeground(boolean)} —— 否则桌面卡片会压在本页上（模块纪律）。
 *
 * 数据全走 {@link TodoStore}（零依赖 SharedPreferences + JSON），本类不碰存储细节。
 */
public class TodoActivity extends Activity {

    private SegTabView segTodo;
    private LinearLayout listBox;
    private TextView tvEmpty;
    private Button btnSort;

    /** 当前子页：0 = 待办列表 / 1 = 已完成列表 */
    private int tab = 0;
    /** 排序态：为 true 时每行右侧出现 ↑/↓，此时点条目本体**不弹编辑**（防误触） */
    private boolean sorting = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_todo);

        segTodo = (SegTabView) findViewById(R.id.seg_todo);
        listBox = (LinearLayout) findViewById(R.id.todo_list);
        tvEmpty = (TextView) findViewById(R.id.tv_todo_empty);
        btnSort = (Button) findViewById(R.id.btn_todo_sort);

        segTodo.setLabels(new String[]{
                getString(R.string.todo_tab_pending), getString(R.string.todo_tab_done) });
        segTodo.setListener(new SegTabView.Listener() {
            @Override
            public void onSegSelected(int index) {
                tab = index;
                sorting = false;                 // 换页退出排序态，避免状态串页
                refresh();
            }
        });

        ((Button) findViewById(R.id.btn_todo_add)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edit(null);                      // null = 新增
            }
        });

        btnSort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sorting = !sorting;
                refresh();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 本页在前台 ⇒ 让桌面卡片让位（模块纪律：新增自家 Activity 必须调）
        CardA11yService.noteOwnUiForeground(true);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CardA11yService.noteOwnUiForeground(false);
    }

    /** 重画列表（数据变了 / 切页 / 切排序态都走这里） */
    private void refresh() {
        btnSort.setText(sorting ? R.string.todo_sort_exit : R.string.todo_sort);

        List<TodoItem> items = (tab == 0) ? TodoStore.pending(this) : TodoStore.done(this);
        listBox.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);

        for (final TodoItem it : items) {
            View row = inf.inflate(R.layout.item_todo, listBox, false);
            CheckBox cb = (CheckBox) row.findViewById(R.id.cb_todo_item);
            TextView tvContent = (TextView) row.findViewById(R.id.tv_todo_content);
            TextView tvWhen = (TextView) row.findViewById(R.id.tv_todo_when);
            final TextView tvUp = (TextView) row.findViewById(R.id.tv_todo_up);
            final TextView tvDown = (TextView) row.findViewById(R.id.tv_todo_down);

            cb.setChecked(it.done);
            cb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    TodoStore.setDone(TodoActivity.this, it.id, ((CheckBox) v).isChecked());
                    refresh();                   // 勾选后条目换组 ⇒ 立刻重画
                }
            });

            tvContent.setText(it.content);
            String when = it.whenLabel();
            if (when.length() == 0) {
                tvWhen.setVisibility(View.GONE);          // 无日期时间 ⇒ 不显示这一列（A5）
            } else {
                tvWhen.setText(when);
            }

            if (sorting) {
                tvUp.setVisibility(View.VISIBLE);
                tvDown.setVisibility(View.VISIBLE);
                tvUp.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (TodoStore.move(TodoActivity.this, it.id, -1)) refresh();
                    }
                });
                tvDown.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (TodoStore.move(TodoActivity.this, it.id, +1)) refresh();
                    }
                });
            } else {
                // 普通态：点条目本体 → 编辑（排序态刻意不绑，避免误触删除/编辑）
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        edit(it);
                    }
                });
            }

            listBox.addView(row);
        }

        boolean empty = items.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) tvEmpty.setText(tab == 0 ? R.string.todo_empty_pending : R.string.todo_empty_done);
    }

    // ══════════════════════ 新增 / 编辑对话框 ══════════════════════

    /** @param it null = 新增；非 null = 编辑该条 */
    private void edit(final TodoItem it) {
        final boolean isNew = (it == null);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_todo_edit, null);
        final EditText etContent = (EditText) v.findViewById(R.id.et_todo_content);
        final TextView tvDate = (TextView) v.findViewById(R.id.tv_todo_date);
        final TextView tvTime = (TextView) v.findViewById(R.id.tv_todo_time);

        // 可变工作副本（对话框里改了、取消则不落盘）
        final long[] dateSec = { isNew ? 0L : it.dateSec };
        final int[] minute = { isNew ? TodoItem.NO_TIME : it.minuteOfDay };

        etContent.setText(isNew ? "" : it.content);
        etContent.setSelection(etContent.getText().length());
        applyDateLabel(tvDate, dateSec[0]);
        applyTimeLabel(tvTime, minute[0]);

        v.findViewById(R.id.btn_todo_pick_date).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { pickDate(dateSec, tvDate); }
        });
        v.findViewById(R.id.btn_todo_clear_date).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { dateSec[0] = 0L; applyDateLabel(tvDate, 0L); }
        });
        v.findViewById(R.id.btn_todo_pick_time).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { pickTime(minute, tvTime); }
        });
        v.findViewById(R.id.btn_todo_clear_time).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                minute[0] = TodoItem.NO_TIME;
                applyTimeLabel(tvTime, TodoItem.NO_TIME);
            }
        });

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(isNew ? R.string.todo_title_add : R.string.todo_title_edit)
                .setView(v)
                .setPositiveButton(R.string.todo_save, null)     // 手动接管，以便校验"内容非空"
                .setNegativeButton(R.string.todo_cancel, null);
        if (!isNew) {
            b.setNeutralButton(R.string.todo_delete, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    TodoStore.remove(TodoActivity.this, it.id);
                    refresh();
                }
            });
        }

        final AlertDialog dlg = b.create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        String content = etContent.getText().toString().trim();
                        if (content.length() == 0) {              // 内容必填（A1）
                            Toast.makeText(TodoActivity.this, R.string.todo_need_content,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (isNew) {
                            TodoStore.add(TodoActivity.this, content, dateSec[0], minute[0]);
                        } else {
                            TodoStore.update(TodoActivity.this, it.id, content, dateSec[0], minute[0]);
                        }
                        dlg.dismiss();
                        refresh();
                    }
                });
            }
        });
        dlg.show();
    }

    private void pickDate(final long[] dateSec, final TextView tv) {
        Calendar c = Calendar.getInstance();
        if (dateSec[0] > 0) c.setTimeInMillis(dateSec[0] * 1000L);
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker dp, int y, int m, int d) {
                Calendar cc = Calendar.getInstance();
                cc.set(y, m, d, 0, 0, 0);
                cc.set(Calendar.MILLISECOND, 0);
                dateSec[0] = cc.getTimeInMillis() / 1000L;
                applyDateLabel(tv, dateSec[0]);
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime(final int[] minute, final TextView tv) {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), mi = c.get(Calendar.MINUTE);
        if (minute[0] >= 0 && minute[0] < 1440) { h = minute[0] / 60; mi = minute[0] % 60; }
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker tp, int hh, int mm) {
                minute[0] = hh * 60 + mm;
                applyTimeLabel(tv, minute[0]);
            }
        }, h, mi, true).show();
    }

    private void applyDateLabel(TextView tv, long sec) {
        if (sec <= 0) { tv.setText(R.string.todo_no_date); return; }
        TodoItem tmp = new TodoItem();
        tmp.dateSec = sec;
        tv.setText(tmp.dateLabel());                 // 与卡片/列表同一口径
    }

    private void applyTimeLabel(TextView tv, int minute) {
        if (minute < 0 || minute >= 1440) { tv.setText(R.string.todo_no_time); return; }
        TodoItem tmp = new TodoItem();
        tmp.minuteOfDay = minute;
        tv.setText(tmp.timeLabel());
    }
}
