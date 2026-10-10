package com.inkread.weekread.feature;

import android.content.Context;

/**
 * 🆕 TASK-076：**墨台模块** —— 「墨台」页面上一个可开关、可调序的内容块。
 *
 * <p>契约刻意做得很薄：**id + 一个造分区的工厂**（{@link #build(Context)}）。排版、量高、绘制
 * 全部交给返回的 {@link InsightRenderer.Section} —— 于是"新增一个墨台模块" = 写一个 {@code Section}
 * + 在 {@link DeskModules} 注册一处，不用碰容器（{@link DeskPageView}）/ 渲染器（{@link DeskRenderer}）
 * / 设置页（{@code SettingsPageController}）三处。
 *
 * <p>🔴 **元信息（显示名 / 位 / 默认开关）不在本接口里** —— 它们在 {@code core.PagePrefs}
 * （{@code MOD_*} / {@code BIT_*} / {@code moduleName()}）。原因：设置页在 {@code shell} 包，
 * 引用不到本包（本接口返回的 {@code Section} 是**包内可见**类型）⇒ 让「壳」只依赖 {@code core} 的
 * 纯数据注册表，渲染实现在本包内闭环。
 *
 * <p>🔴 **渲染函数永不发请求**（定稿设计 §2.3）：{@link #build(Context)} 只许读**本地缓存 / 本地库**
 * （{@code StatsStore} / {@code TodoStore} / {@code NoteStore} / {@code BillStore}），
 * 一律不得触发网络 —— 墨台是"呼出即显示"的覆盖层，等网络必白屏。
 */
interface DeskModule {

    /** 模块 id（= {@code PagePrefs.MOD_*} 之一）。 */
    String id();

    /**
     * 造出本模块这一帧的分区。
     *
     * @return 分区实例；**{@code null} = 本模块本帧无可画内容**（容器会跳过它，不占高）。
     *         已实现的模块也可返回"空态分区"（标题 + 空态行）—— 二者都合法，区别只是
     *         "连标题都不出现"还是"标题在位 + 空态行"。本版取后者（用户能看见模块确实开着）。
     */
    InsightRenderer.Section build(Context c);
}
