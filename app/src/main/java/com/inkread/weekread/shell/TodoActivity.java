package com.inkread.weekread.shell;

import android.app.Activity;
import android.os.Bundle;

import com.inkread.weekread.R;
import com.inkread.weekread.a11y.CardA11yService;

/**
 * 待办管理页（**独立 Activity 外壳** · TASK-024 / V1.0.3-beta）。
 *
 * <p>🆕 v1.2（TASK-045）：列表 / 增删改 / 勾选完成 / 待办·已完成两态 / 排序态 / 时间弹窗等
 * **全部逻辑已移到** {@link TodoPageController}，本类只剩外壳 ——
 * 目的是让「App 内大标签④」与「本独立页」**共用同一实现**（防双份维护）。
 *
 * <p>入口：本页仍保留（{@code AndroidManifest.xml} 已注册；桌面卡片的待办形态若引用它则继续可用）。
 *
 * <p>🔴 自家 Activity 在前台时必须 {@link CardA11yService#noteOwnUiForeground(boolean)} ——
 * 否则桌面卡片会压在本页上（模块纪律）。
 */
public class TodoActivity extends Activity {

    private TodoPageController ctrl;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_todo);

        ctrl = new TodoPageController(this);
        ctrl.bind();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 本页在前台 ⇒ 让桌面卡片让位（模块纪律：新增自家 Activity 必须调）
        CardA11yService.noteOwnUiForeground(true);
        ctrl.refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CardA11yService.noteOwnUiForeground(false);
    }
}
