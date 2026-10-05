package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.BuildConfig
import com.canglan.cyancodex.ui.components.AppTopBar

// ============================================================================
// 开源组件声明
//
// 维护须知: 这里必须和 app/build.gradle.kts 的 dependencies 保持一致。
// 新增/升级/移除依赖时, 请同步改本文件的 OPEN_SOURCE_GROUPS —— 漏报或版本
// 对不上属于许可证合规问题, 不是文案问题。
// ============================================================================

/** 一条第三方组件声明 */
private data class OpenSourceLib(
    /** 展示名 (库名) */
    val name: String,
    /** Maven 坐标, 便于核对 */
    val artifact: String,
    /** 使用的版本 */
    val version: String,
    /** 许可证标识 (SPDX) */
    val license: String,
    /** 源码 / 主页地址 */
    val url: String,
    /** 在本 App 中用来做什么 */
    val usage: String,
)

private data class OpenSourceGroup(val title: String, val libs: List<OpenSourceLib>)

private val APACHE = "Apache-2.0"

private val OPEN_SOURCE_GROUPS = listOf(
    OpenSourceGroup(
        title = "液态玻璃 (Liquid Glass)",
        libs = listOf(
            OpenSourceLib(
                name = "backdrop",
                artifact = "io.github.kyant0:backdrop",
                version = "2.0.0",
                license = APACHE,
                url = "https://github.com/Kyant0/AndroidLiquidGlass",
                usage = "底部导航栏与浮动控件的液态玻璃效果：背景模糊、边缘折射 (lens)、" +
                    "跟手高光、内外阴影。需 Android 12+，折射需 Android 13+。",
            ),
            OpenSourceLib(
                name = "shapes",
                artifact = "io.github.kyant0:shapes",
                version = "1.2.0",
                license = APACHE,
                url = "https://github.com/Kyant0/Shapes",
                usage = "连续曲率 (Continuous) 胶囊形状 Capsule()，用于玻璃条与按钮的轮廓。",
            ),
        ),
    ),
    OpenSourceGroup(
        title = "Android 官方组件 (AndroidX)",
        libs = listOf(
            OpenSourceLib(
                name = "Compose UI / Foundation / Material 3",
                artifact = "androidx.compose.ui:ui · material3 · material",
                version = "BOM 2026.02.01",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/compose",
                usage = "本 App 全部界面的声明式 UI 框架与 Material 3 设计组件。",
            ),
            OpenSourceLib(
                name = "Material Icons Extended",
                artifact = "androidx.compose.material:material-icons-extended",
                version = "1.6.8",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/compose-material",
                usage = "界面中的矢量图标。",
            ),
            OpenSourceLib(
                name = "Navigation Compose",
                artifact = "androidx.navigation:navigation-compose",
                version = "2.7.7",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/navigation",
                usage = "页面路由与返回栈管理。",
            ),
            OpenSourceLib(
                name = "Activity Compose",
                artifact = "androidx.activity:activity-compose",
                version = "1.8.0",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/activity",
                usage = "把 Compose 界面挂到 Activity 上，以及权限申请等 Activity Result 能力。",
            ),
            OpenSourceLib(
                name = "Lifecycle / ViewModel Compose",
                artifact = "androidx.lifecycle:lifecycle-runtime-ktx · lifecycle-viewmodel-compose",
                version = "2.6.1",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/lifecycle",
                usage = "生命周期感知 (如从系统设置页返回时刷新权限状态) 与 ViewModel 支持。",
            ),
            OpenSourceLib(
                name = "Core KTX",
                artifact = "androidx.core:core-ktx",
                version = "1.10.1",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/core",
                usage = "Android 平台 API 的 Kotlin 扩展。",
            ),
        ),
    ),
    OpenSourceGroup(
        title = "网络与数据",
        libs = listOf(
            OpenSourceLib(
                name = "Retrofit",
                artifact = "com.squareup.retrofit2:retrofit",
                version = "2.9.0",
                license = APACHE,
                url = "https://github.com/square/retrofit",
                usage = "与 CyanCodex 服务端通信的 HTTP 接口层。",
            ),
            OpenSourceLib(
                name = "Retrofit Gson Converter",
                artifact = "com.squareup.retrofit2:converter-gson",
                version = "2.9.0",
                license = APACHE,
                url = "https://github.com/square/retrofit",
                usage = "把服务端返回的 JSON 自动转换成数据对象。",
            ),
            OpenSourceLib(
                name = "OkHttp",
                artifact = "com.squareup.okhttp3:okhttp",
                version = "4.12.0",
                license = APACHE,
                url = "https://github.com/square/okhttp",
                usage = "Retrofit 底层使用的 HTTP 客户端 (连接池、HTTPS)。",
            ),
            OpenSourceLib(
                name = "Gson",
                artifact = "com.google.code.gson:gson",
                version = "随 converter-gson 引入",
                license = APACHE,
                url = "https://github.com/google/gson",
                usage = "JSON 序列化 / 反序列化。",
            ),
        ),
    ),
    OpenSourceGroup(
        title = "语言与运行时",
        libs = listOf(
            OpenSourceLib(
                name = "Kotlin 标准库",
                artifact = "org.jetbrains.kotlin:kotlin-stdlib",
                version = "2.2.10",
                license = APACHE,
                url = "https://github.com/JetBrains/kotlin",
                usage = "本 App 的编程语言与标准库。",
            ),
            OpenSourceLib(
                name = "Kotlin Coroutines",
                artifact = "org.jetbrains.kotlinx:kotlinx-coroutines-android",
                version = "随 Compose / Lifecycle 引入",
                license = APACHE,
                url = "https://github.com/Kotlin/kotlinx.coroutines",
                usage = "异步任务 (网络请求、动画帧同步 awaitFrame 等)。",
            ),
        ),
    ),
)

/** 开源组件声明页面 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceScreen(nav: NavHostController, onBack: (() -> Unit)? = null) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = "开源组件声明",
                icon = Icons.Default.Code,
                onBack = { onBack?.invoke() ?: nav.popBackStack() },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("CyanCodex 开源组件声明", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
            Text("更新日期：2026-09-24 · 适用于 CyanCodex v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("CyanCodex 基于开源软件构建。以下列出本 App 使用的全部第三方开源组件、" +
                "所用版本与许可证。我们向所有开源作者致谢，并按其许可证要求保留版权与许可声明。",
                style = MaterialTheme.typography.bodyMedium)

            // 关于"逐字搬运"的特别说明 —— 这是许可证合规的关键一句, 不要删。
            OpenSourceSection("关于液态玻璃组件的来源说明") {
                OpenSourceLine("官方开源库 io.github.kyant0:backdrop 本身只提供底层效果（模糊 / 折射 / " +
                    "高光 / 阴影），并不包含按钮、底栏这类高层控件——这一点在该项目 README 中已明确说明。")
                OpenSourceLine("因此本 App 的玻璃底栏 (LiquidBottomTabs)、玻璃按钮 (LiquidButton)、" +
                    "按压弹簧动画 (DampedDragAnimation)、跟手光斑 (InteractiveHighlight) 等高层控件，" +
                    "均取自该项目的官方示例工程源码（仓库 Kyant0/AndroidLiquidGlass，Apache-2.0）。")
                OpenSourceLine("对应文件位于本 App 源码的 ui/liquidglass/ 目录下，每个文件头部均标注了出处与改动。" +
                    "改动范围限于：① 包名；② 补充出处注释；③ 把官方写死的「深/浅色判断」和" +
                    "「按钮高度、左右内边距」提成可传入的参数（默认值与原版一致，不传参时行为等同原版）；" +
                    "④ 官方是 Kotlin 多平台工程，其协程工具类的 expect/actual 两文件合并为一份；" +
                    "⑤ 官方使用较新的 Kotlin 编译器，本 App 所用版本中个别时间 API 仍属实验性，" +
                    "为此补了相应的编译期注解。上述改动均不涉及效果的实现逻辑。")
            }

            OPEN_SOURCE_GROUPS.forEach { group ->
                OpenSourceSection(group.title) {
                    group.libs.forEachIndexed { i, lib ->
                        if (i > 0) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        OpenSourceItem(lib)
                    }
                }
            }

            OpenSourceSection("许可证全文") {
                OpenSourceLine("以上组件除特别注明外，均采用 Apache License 2.0 许可证授权。")
                OpenSourceLine("许可证全文可在各项目源码仓库的 LICENSE 文件中查看，或访问：")
                LinkLine("https://www.apache.org/licenses/LICENSE-2.0")
                OpenSourceLine("Apache-2.0 允许在保留版权声明、许可证文本与变更说明的前提下自由使用、" +
                    "修改与分发，包括商业用途。本 App 未修改上述库本身的源代码。")
            }

            OpenSourceSection("联系我们") {
                OpenSourceLine("若你是上述组件的作者，认为本 App 的署名方式需要调整，" +
                    "或发现遗漏的组件，欢迎通过「设置 → 意见申诉」或官方 QQ 群与我们联系，" +
                    "我们会尽快更正。")
            }

            Spacer(Modifier.height(8.dp))
            Text("© 2026 世纪集团 · 沧蓝工坊工作室", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 单个组件的声明条目：名称 / 版本 / Maven 坐标 / 用途 / 许可证 / 源码链接 */
@Composable
private fun OpenSourceItem(lib: OpenSourceLib) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lib.name, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            LicenseBadge(lib.license)
        }
        Text("${lib.artifact}:${lib.version}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(lib.usage, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.clickable { runCatching { uri.openUri(lib.url) } }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("查看源码", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp))
        }
        Text(lib.url, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

/** 许可证徽标 */
@Composable
private fun LicenseBadge(license: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(license, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun OpenSourceSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun OpenSourceLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 可点击打开的链接行 */
@Composable
private fun LinkLine(url: String) {
    val uri = LocalUriHandler.current
    Text(url, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { runCatching { uri.openUri(url) } })
}
