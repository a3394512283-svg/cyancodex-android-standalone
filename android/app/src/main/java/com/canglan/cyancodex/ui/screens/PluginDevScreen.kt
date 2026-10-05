package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.plugin.PluginManager
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton

/**
 * 开发平台: 插件开发文档 (如何编写/打包/安装插件)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDevScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = { AppTopBar(title = "插件开发平台", icon = Icons.Default.Extension, onBack = { nav.popBackStack() }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("插件是什么", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("插件是独立打包的 Java 代码(JAR), 可以在不更新 App 的情况下扩展功能。\n" +
                "App 内置 2 个默认插件(自定义背景 / 开发者示例), 也支持你写的插件。",
                style = MaterialTheme.typography.bodyMedium)

            // 图形化制作插件: 插件工坊
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("不会写代码？用「插件工坊」", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    Text("图形化(积木式)拼出插件行为, 点一下就能打包成可加载的 JAR, 全程离线, 不需要编译器。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(8.dp))
                    GlassActionButton(filled = true, onClick = { nav.navigate(Routes.PLUGIN_BUILDER) }) { Text("打开插件工坊") }
                }
            }

            Text("插件目录", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(PluginManager.pluginDir(ctx).absolutePath, style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.secondary)
            Text("把 jar 放进这个目录, 然后在「优化设置 → 插件」里点「重新加载」即可生效。\n" +
                "Android 11+ 该目录通常需要电脑/ADB 或文件管理器授权访问。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("插件 API (需实现)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    "public interface CyanPlugin {\n" +
                    "    String getId();          // 插件唯一 id\n" +
                    "    String getName();        // 显示名称\n" +
                    "    String getVersion();     // 版本号\n" +
                    "    String getDescription(); // 简介\n" +
                    "    void onEnable(Context ctx);  // 启用时回调\n" +
                    "    void onDisable();            // 禁用时回调\n" +
                    "}\n" +
                    "// 额外接口: BackgroundProvider.background() 可自定义主页背景",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Text("开发步骤", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("1. 写 Java 类实现 CyanPlugin (参考内置「开发者示例插件」源码, 在 .plugin-src 目录)\n" +
                "2. 编译: javac --release 8 -cp android.jar -d classes MyPlugin.java\n" +
                "3. 转 dex: d8 --lib android.jar --output dexout classes\n" +
                "4. 打包: jar 内放 classes.dex + META-INF/services/com.canglan.cyancodex.plugin.CyanPlugin\n" +
                "   (services 文件内容 = 实现类完整类名, 一行)\n" +
                "5. 把 jar 放进上面的插件目录 → 设置里重新加载 → 打开开关",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("内置示例插件", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("「自定义背景」: 演示 BackgroundProvider 扩展, 返回渐变色套到主页。\n" +
                "「开发者示例插件」: 带完整中文注释的教科书插件, 源码见工程 .plugin-src 目录。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(8.dp))
        }
    }
}
