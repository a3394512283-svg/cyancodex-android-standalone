package com.canglan.cyancodex.data

/**
 * 「App 现在对用户可见吗」—— 一个**零依赖**的前台判定。
 *
 * 为什么不用 `ProcessLifecycleOwner`: 那要额外引 `lifecycle-process` 依赖;
 * 而这里只要一个布尔, 用 [android.app.Application.ActivityLifecycleCallbacks]
 * 数一下"有几个 Activity 处于 started"就够了(见 CyanCodexApp.onCreate 的注册)。
 *
 * 计数而不是布尔: 将来若出现第二个 Activity(设置页/编辑器独立窗口),
 * 一个 started、另一个 stopped 时布尔会误判成"不可见"。
 *
 * 用途: [com.canglan.cyancodex.ai.AiTaskService.finish] 决定要不要补一条
 * 「AI 回答好了」通知 —— 人就在界面里看着的时候不该再弹。
 */
object AppForeground {

    @Volatile
    var startedActivities: Int = 0

    val isVisible: Boolean get() = startedActivities > 0
}
