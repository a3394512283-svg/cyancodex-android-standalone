package com.canglan.cyancodex.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Plugin
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.EmptyState
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.TopBarAction
import com.canglan.cyancodex.ui.components.safeApi
import com.canglan.cyancodex.ui.liquidGlassActive
import com.canglan.cyancodex.ui.liquidglass.LiquidBottomTab
import com.canglan.cyancodex.ui.liquidglass.LiquidBottomTabs
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件库的底部页签 —— 2026-09-26 用户报「插件库底部没有液态玻璃」。
 *
 * ⚠️ 必须放在**文件顶层**当常量, 不能在 composable 里 `listOf(...)`:
 *    选中态要用 `remember(tabs) { derivedStateOf { ... } }`, 而每次组合都新建的 List
 *    会让 remember 的 key 一直变 → 与"传普通 Int"是同一个病(药丸高光不动)。
 */
private data class PluginTab(val key: String, val label: String, val icon: ImageVector)

private val PluginTabs = listOf(
    PluginTab("active", "插件", Icons.Default.Extension),
    PluginTab("mine", "我的", Icons.Default.Person),
)

/** 意见 #86: 按适用版本校验文件后缀 */
private fun pluginFileOk(name: String, platform: String): Boolean {
    val n = name.lowercase()
    return if (platform == "bedrock") {
        n.endsWith(".mcpack") || n.endsWith(".mcaddon") || n.endsWith(".zip")
    } else {
        n.endsWith(".jar")
    }
}

/** 插件库 (意见 #37): 浏览/上传/下载插件 (意见 #86: 支持基岩版) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<Plugin>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("active") }
    var showUpload by remember { mutableStateOf(false) }
    var upTitle by remember { mutableStateOf("") }
    var upDesc by remember { mutableStateOf("") }
    var upVersion by remember { mutableStateOf("1.0.0") }
    // 意见 #86: 适用版本 java=Java 版 / bedrock=基岩版
    var upPlatform by remember { mutableStateOf("java") }
    var upFile by remember { mutableStateOf<Uri?>(null) }
    var upFileName by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    // 列表筛选: 全部 / Java 版 / 基岩版
    var platFilter by remember { mutableStateOf("all") }

    suspend fun load() {
        // 「我的」需要登录: 未登录时不要退化成"全部插件"
        if (tab == "mine" && AppState.token == null) {
            list = emptyList(); loading = false; err = "登录后查看自己上传的插件"; return
        }
        loading = true
        val r = safeApi {
            ApiClient.get().plugins(if (tab == "active") "active" else "mine",
                platFilter.takeIf { it != "all" }, 1, 100)
        }
        loading = false
        if (r.ok) { list = r.data?.list ?: emptyList(); err = "" }
        else err = r.error ?: "加载失败"
    }
    LaunchedEffect(tab, platFilter) { load() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            upFile = uri
            upFileName = (ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else "plugin.jar"
            }) ?: "plugin.jar"
        }
    }

    // ===== 液态玻璃底部页签 (2026-09-26) =====
    // 与好友页 FriendsScreen 同一套写法: 官方 LiquidBottomTabs + 内容层当采样源。
    val glass = liquidGlassActive
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val barContentColor = if (dark) Color.White else Color.Black
    // ★ 必须 derivedStateOf —— 见 FriendsScreen 里那段长注释(传普通 Int 会让药丸高光不动)
    val selectedIndexState = remember(PluginTabs) {
        derivedStateOf { PluginTabs.indexOfFirst { it.key == tab }.coerceAtLeast(0) }
    }
    var barH by remember { mutableStateOf(0.dp) }

    Scaffold(
        topBar = {
            AppTopBar(title = "插件库", icon = Icons.Default.Extension, onBack = { nav.popBackStack() },
                actions = {
                    TopBarAction(onClick = {
                        if (AppState.token == null) nav.navigate(Routes.LOGIN) else showUpload = true
                    }) { Icon(Icons.Default.CloudUpload, null, Modifier.size(20.dp)) }
                })
        },
        bottomBar = {
            // 玻璃开启时底栏改走下面的浮层 —— 挂在 bottomBar 里 Scaffold 会把内容顶上去,
            // 玻璃正下方只剩 Scaffold 纯色底, 就退化成一块半透明矩形。
            // 非玻璃分支保持上游原样, 只补上图标(上游 icon 槽里塞的是纯文字, 一个图标都没有)。
            if (!glass) {
                NavigationBar {
                    PluginTabs.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t.key,
                            onClick = { tab = t.key },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                // 玻璃开启时去掉底部内缩, 让列表一路排到屏幕底, 玻璃才真的压在内容上
                .padding(if (glass) PaddingValues(top = pad.calculateTopPadding()) else pad)
                .fillMaxSize()
                // 采样源: 只包内容。玻璃条是它的**兄弟节点**(画在后面) ——
                // 绝不能含在采样节点内部, 否则会采到上一帧的自己(拖影)。
                .then(if (glass) Modifier.layerBackdrop(backdrop) else Modifier)
        ) {
            when {
                loading -> LoadingBox()
                list.isEmpty() -> EmptyState(
                    if (err.isNotBlank()) err else "还没有插件\n上传你的 .jar 插件分享给大家（需审核）")
                else -> LazyColumn(Modifier.fillMaxSize(),
                    // 玻璃开启时列表末尾要补出玻璃条的高度, 否则最后一项被浮着的玻璃压住
                    contentPadding = PaddingValues(
                        start = 12.dp, top = 12.dp, end = 12.dp,
                        bottom = 12.dp + if (glass) barH + 12.dp else 0.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 适用版本筛选 (意见 #86)
                    item {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("适用版本", style = MaterialTheme.typography.labelMedium)
                            listOf("all" to "全部", "java" to "Java 版", "bedrock" to "基岩版").forEach { (k, v) ->
                                GlassFilterChip(selected = platFilter == k, onClick = { platFilter = k },
                                    label = { Text(v, style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                    }
                    items(list, key = { it.id }) { pl ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(pl.title, style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Surface(shape = MaterialTheme.shapes.small,
                                        color = if (pl.platform == "bedrock") MaterialTheme.colorScheme.tertiaryContainer
                                        else MaterialTheme.colorScheme.primaryContainer) {
                                        Text(if (pl.platform == "bedrock") "基岩版" else "Java 版",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    if (pl.status == "pending") {
                                        Surface(shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)) {
                                            Text("待审核", style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    } else if (pl.status == "rejected") {
                                        Surface(shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)) {
                                            Text("已驳回", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                if (pl.description.isNotBlank()) {
                                    Text(pl.description, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                                Text("v${pl.version} · ${pl.author ?: "?"} · 下载 ${pl.downloads} · ${pl.createdAt?.take(10) ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                pl.reviewNote?.takeIf { it.isNotBlank() }?.let {
                                    Text("驳回理由：$it", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                if (pl.status == "active") {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val r = safeApi { ApiClient.get().pluginDetail(pl.id) }
                                            // 下载: 直接打开浏览器下载
                                            if (r.ok) {
                                                val base = com.canglan.cyancodex.data.Session.baseUrl.trimEnd('/')
                                                com.canglan.cyancodex.ui.components.copyToClipboard(ctx,
                                                    "$base/api/plugins/${pl.id}/download", "下载链接已复制，粘贴到浏览器下载")
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.Download, null, Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (pl.platform == "bedrock") "下载插件包" else "下载 JAR")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 浮动的玻璃页签 —— 与主底栏 / 好友页同一套官方组件。
        // 它是内容层的**兄弟节点**(画在后面), 所以采样源里不含它自己 → 不会拖影。
        if (glass) {
            LiquidBottomTabs(
                selectedTabIndex = { selectedIndexState.value },
                onTabSelected = { i -> PluginTabs.getOrNull(i)?.let { tab = it.key } },
                backdrop = backdrop,
                tabsCount = PluginTabs.size,
                // 本 App 深浅色是用户设置项, 官方默认的 isSystemInDarkTheme() 会判断错
                isLightTheme = !dark,
                // 官方写死亮蓝; 本 App 绿色主题, 换成主色(官方预留的着色入口)
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { barH = with(density) { it.height.toDp() } }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                PluginTabs.forEach { t ->
                    LiquidBottomTab({ tab = t.key }) {
                        Icon(
                            imageVector = t.icon,
                            contentDescription = t.label,
                            modifier = Modifier.size(22.dp),
                            tint = barContentColor,
                        )
                        Text(
                            text = t.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = barContentColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        }
    }

    // 上传对话框
    if (showUpload) {
        AlertDialog(
            onDismissRequest = { showUpload = false },
            title = { Text("上传插件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (upPlatform == "bedrock") "上传基岩版插件包（.mcpack / .mcaddon / .zip，通过安全扫描后需审核上架）"
                        else "上传 Java 版 .jar 插件（通过安全扫描后需审核上架）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 适用版本 (意见 #86)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("适用版本", style = MaterialTheme.typography.labelMedium)
                        listOf("java" to "Java 版(JAR)", "bedrock" to "基岩版").forEach { (k, v) ->
                            GlassFilterChip(selected = upPlatform == k,
                                onClick = { upPlatform = k; upFile = null; upFileName = "" },
                                label = { Text(v, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    OutlinedTextField(value = upTitle, onValueChange = { upTitle = it },
                        label = { Text("插件名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = upDesc, onValueChange = { upDesc = it },
                        label = { Text("描述(可选)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = upVersion, onValueChange = { upVersion = it },
                        label = { Text("版本") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    GlassActionButton(onClick = {
                        if (upPlatform == "bedrock") filePicker.launch(arrayOf("application/zip", "*/*"))
                        else filePicker.launch(arrayOf("application/java-archive", "application/zip", "*/*"))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (upFileName.isNotBlank()) "已选择: $upFileName"
                            else if (upPlatform == "bedrock") "选择基岩版插件包" else "选择 .jar 文件")
                    }
                    if (upFileName.isNotBlank() && !pluginFileOk(upFileName, upPlatform)) {
                        Text(if (upPlatform == "bedrock") "请选择 .mcpack / .mcaddon / .zip 文件" else "请选择 .jar 文件",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = upTitle.isNotBlank() && upFile != null &&
                        pluginFileOk(upFileName, upPlatform) && !uploading,
                    onClick = {
                        scope.launch {
                            uploading = true
                            // 意见 #100/#101: 先看文件大小再读进内存, 超大文件直接提示, 不闪退
                            val declared = upFile?.let { uri ->
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        ctx.contentResolver.query(
                                            uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null,
                                        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
                                    }.getOrDefault(-1L)
                                }
                            } ?: -1L
                            if (declared > 5 * 1024 * 1024) {
                                err = "文件超过 5MB，请压缩后再上传"
                                uploading = false
                                return@launch
                            }
                            val bytes = upFile?.let { uri ->
                                withContext(Dispatchers.IO) {
                                    runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                                }
                            }
                            if (bytes == null) { err = "文件读取失败"; uploading = false; return@launch }
                            if (bytes.size > 5 * 1024 * 1024) {
                                err = "文件超过 5MB，请压缩后再上传"
                                uploading = false
                                return@launch
                            }
                            val b64 = runCatching {
                                android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                            }.getOrNull()
                            if (b64 == null) { err = "文件编码失败（文件可能过大）"; uploading = false; return@launch }
                            val r = safeApi {
                                ApiClient.get().uploadPlugin(mapOf(
                                    "title" to upTitle, "description" to upDesc,
                                    "version" to upVersion.ifBlank { "1.0.0" },
                                    "platform" to upPlatform,
                                    "filename" to upFileName,
                                    "data" to b64))
                            }
                            uploading = false
                            if (r.ok) {
                                showUpload = false
                                upTitle = ""; upDesc = ""; upVersion = "1.0.0"; upFileName = ""; upFile = null
                                android.widget.Toast.makeText(ctx, "上传成功，等待审核", android.widget.Toast.LENGTH_SHORT).show()
                                load()
                            } else {
                                err = r.error ?: "上传失败"
                                android.widget.Toast.makeText(ctx, r.error ?: "上传失败", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) { Text(if (uploading) "上传中…" else "上传") }
            },
            dismissButton = { TextButton(onClick = { showUpload = false }) { Text("取消") } },
        )
    }
}
