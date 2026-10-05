package com.canglan.cyancodex.plugin

import android.content.Context

/**
 * CyanCodex 插件 API v1
 *
 * 插件约定:
 * - 用 Java 编写, 实现本接口 (Kotlin 的 val 属性在 Java 里是 getId()/getName()/...)
 * - 打包为 JAR: 内含 classes.dex (用 d8 转换) + META-INF/services/com.canglan.cyancodex.plugin.CyanPlugin
 *   (内容为实现类的完整类名, 如 dev.cyancodex.plugins.background.BackgroundPlugin)
 * - 放到 App 插件目录 (Android/data/com.canglan.cyancodex/files/plugins/), 设置→插件 里启用
 */
interface CyanPlugin {
    /** 插件唯一 id, 如 dev.cyancodex.background (不能与其它插件重复) */
    val id: String

    /** 插件显示名称 */
    val name: String

    /** 插件版本号 */
    val version: String

    /** 插件简介 (在设置页显示) */
    val description: String

    /** 插件被启用时回调 (App 启动加载 / 用户在设置里打开开关) */
    fun onEnable(context: Context) {}

    /** 插件被禁用时回调 (用户在设置里关掉开关) */
    fun onDisable() {}
}

/**
 * 背景插件扩展接口: 实现它即可自定义 App 主页背景 (内置的"自定义背景"插件就是用它)。
 * 注意: 插件 JAR 里实现本接口时, 方法签名为 background()。
 */
interface BackgroundProvider {
    /** 返回背景效果; 返回 null 表示不修改背景 */
    fun background(): PluginBackground?
}

/**
 * 积木插件规格接口 (插件工坊专用)
 *
 * 「插件工坊」图形化生成的 JAR 里带的是行为规格 (cyancodex-plugin.json),
 * PluginManager 在实例化后、读取 id 之前把规格注入插件, 插件再按规格执行动作。
 * 手写插件无需实现本接口。
 */
interface SpecPlugin {
    /** 注入行为规格 JSON */
    fun attachSpec(specJson: String)
}

/** 插件提供的背景效果描述 */
data class PluginBackground(
    val kind: String = "gradient",          // gradient=渐变 color=纯色 image=图片(预留)
    val colorTop: String = "#0B1A14",       // 起始色
    val colorBottom: String = "#10352B",    // 结束色 (纯色时取 colorTop)
)
