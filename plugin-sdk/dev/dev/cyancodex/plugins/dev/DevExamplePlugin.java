package dev.cyancodex.plugins.dev;

import android.content.Context;
import android.util.Log;
import com.canglan.cyancodex.plugin.CyanPlugin;

/**
 * CyanCodex 默认插件 #2: 开发者示例插件 (带完整注释)
 *
 * 这是给插件开发者看的"教科书"插件: 它不做任何界面改动,
 * 只演示插件 API 的完整生命周期和规范写法, 供你在开发平台里对照学习。
 *
 * 想要自己写插件? 步骤:
 *  1) 实现 com.canglan.cyancodex.plugin.CyanPlugin 接口;
 *  2) 用 javac 编译 (类名/签名见本文件);
 *  3) 用 d8 把 .class 转成 classes.dex;
 *  4) 打包成 jar, 并在 jar 里放一个文本文件:
 *       META-INF/services/com.canglan.cyancodex.plugin.CyanPlugin
 *     内容是你的实现类完整类名 (一行, 如 dev.cyancodex.plugins.dev.DevExamplePlugin);
 *  5) 把 jar 放进插件目录, 在 App「优化设置 → 插件」里启用。
 *  详细文档: 优化设置 → 插件 → 开发平台。
 */
public class DevExamplePlugin implements CyanPlugin {

    @Override
    public String getId() {
        // 插件唯一 id: 全小写 + 点分命名, 不能与其它插件重复
        return "dev.cyancodex.example";
    }

    @Override
    public String getName() {
        // 在「插件」设置页显示的名称
        return "开发者示例插件";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        // 在「插件」设置页显示的简介
        return "演示插件 API 的完整示例(带注释), 供开发者参考";
    }

    @Override
    public void onEnable(Context context) {
        // 插件启用时回调: App 启动加载插件, 或用户在设置里打开开关时调用
        // 在这里做初始化 (读取配置/注册回调等); 不要做耗时操作, 不要弹窗打扰用户
        Log.i("CyanPlugin", "[" + getId() + "] onEnable: 插件已启用, 这是示例插件, 不会修改任何界面");
        // 提示: context 是 ApplicationContext, 可用来拿资源/SharedPreferences 等
        // 例如: context.getSharedPreferences("plugin_demo", Context.MODE_PRIVATE)
    }

    @Override
    public void onDisable() {
        // 插件禁用时回调: 在这里释放资源 (关闭线程/清理监听等)
        Log.i("CyanPlugin", "[" + getId() + "] onDisable: 插件已禁用");
    }
}
