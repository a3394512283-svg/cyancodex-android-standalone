package dev.cyancodex.plugins.background;

import android.content.Context;
import com.canglan.cyancodex.plugin.BackgroundProvider;
import com.canglan.cyancodex.plugin.CyanPlugin;
import com.canglan.cyancodex.plugin.PluginBackground;

/**
 * CyanCodex 默认插件 #1: 自定义软件背景
 *
 * 功能: 给 App 主页套一层深绿色渐变背景, 更贴合沧蓝工坊(沧蓝)风格。
 * 开关: 优化设置 → 插件 → 「自定义背景」右侧开关。
 *
 * 演示要点:
 *  - 实现 CyanPlugin 接口 (getId/getName/getVersion/getDescription/onEnable/onDisable)
 *  - 额外实现 BackgroundProvider 接口, 通过 background() 返回 PluginBackground,
 *    App 会读取并应用到主页背景 (返回 null 表示不修改)
 */
public class BackgroundPlugin implements CyanPlugin, BackgroundProvider {

    @Override
    public String getId() {
        return "dev.cyancodex.background";
    }

    @Override
    public String getName() {
        return "自定义背景";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "给主页套一层深绿渐变背景, 更贴合沧蓝工坊风格";
    }

    @Override
    public void onEnable(Context context) {
        // 插件被启用时回调 (App 启动加载 / 用户在设置里打开开关)
        // 这里可以做一些初始化; 本插件没有额外初始化
    }

    @Override
    public void onDisable() {
        // 插件被禁用时回调, 恢复 App 默认背景由 App 自动处理
    }

    @Override
    public PluginBackground background() {
        // kind: gradient=上下渐变 / color=纯色
        // colorTop: 起始色, colorBottom: 结束色 (十六进制 #RRGGBB)
        return new PluginBackground("gradient", "#0B1A14", "#10352B");
    }
}
