package com.canglan.cyancodex.plugin;

/**
 * 积木插件规格接口 (插件工坊专用)
 *
 * 由「插件工坊」生成的插件 JAR 里带的是一份行为规格 (cyancodex-plugin.json),
 * PluginManager 在实例化插件后、读取 id 之前调用本方法把规格注入进去,
 * 插件再按规格执行动作 / 返回背景。普通手写插件不需要实现本接口。
 */
public interface SpecPlugin {
    /** 注入行为规格 JSON (来自插件 JAR 内的 cyancodex-plugin.json) */
    void attachSpec(String specJson);
}
