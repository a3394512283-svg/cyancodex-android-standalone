package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.BindingItem
import com.canglan.cyancodex.data.SupportedBinding
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.EmptyState
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.SectionTitle
import com.canglan.cyancodex.ui.components.TopBarAction
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

/**
 * 绑定账号（目前支持 QQ 机器人，微信等后续开放）
 *  · 显示当前绑定状态与已绑定的第三方账号
 *  · 可解绑（只影响机器人侧，不影响 App 账号）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindingsScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<BindingItem>>(emptyList()) }
    var supported by remember { mutableStateOf<List<SupportedBinding>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<BindingItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        loading = true; err = ""
        val r = safeApi { ApiClient.get().accountBindings() }
        if (r.ok) {
            items = r.data?.list ?: emptyList()
            supported = r.data?.supported ?: emptyList()
        } else err = r.error ?: "加载失败"
        loading = false
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "绑定账号", icon = Icons.Default.Link, onBack = { nav.popBackStack() }, actions = {
                TopBarAction(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "刷新") }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            // ---- 当前绑定状态 ----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (items.isEmpty()) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (items.isEmpty()) Icons.Default.LinkOff else Icons.Default.Link, null,
                        tint = if (items.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (loading) "读取中…"
                            else if (items.isEmpty()) "尚未绑定任何账号" else "已绑定 ${items.size} 个账号",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (items.isEmpty()) "绑定 QQ 机器人后，可以直接在 QQ 里查积分、签到、搜指令"
                            else "绑定后可在 QQ 里查积分 / 签到 / 搜指令，与 App 数据实时互通",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (toast.isNotBlank()) {
                Text(toast, style = MaterialTheme.typography.labelMedium,
                    color = if (toast.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }

            // ---- 已绑定账号 ----
            SectionTitle("已绑定的账号")
            when {
                loading && items.isEmpty() -> LoadingBox()
                items.isEmpty() -> EmptyState("还没有绑定任何第三方账号")
                else -> items.forEach { b ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.platformLabel.ifBlank { b.platform },
                                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { confirm = b }) { Text("解绑") }
                            }
                            Text("账号标识 ${b.openidMasked.ifBlank { "—" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("绑定时间 ${b.boundAt.orEmpty().ifBlank { "—" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ---- 可绑定的平台 ----
            SectionTitle("支持的平台")
            supported.forEach { p ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.label.ifBlank { p.platform },
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (p.available) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(if (p.available) " 可绑定 " else " 敬请期待 ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (p.available) MaterialTheme.colorScheme.onSecondary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(p.howto, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Text("解绑只解除第三方平台与当前账号的关联，App 账号、积分、指令等内容不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
            Spacer(Modifier.height(20.dp))
        }
    }

    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirm = null },
            title = { Text("解除绑定") },
            text = {
                Text("确定要解绑「${b.platformLabel.ifBlank { b.platform }}（${b.openidMasked}）」吗？\n解绑后该 QQ 将无法再查询本账号的信息，需要时可重新绑定。")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true; toast = ""
                        val r = safeApi { ApiClient.get().unbindAccount(b.id) }
                        busy = false
                        confirm = null
                        if (r.ok) { toast = "✓ 已解绑"; tick++ } else toast = r.error ?: "解绑失败"
                    }
                }) { Text(if (busy) "处理中…" else "确认解绑") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}
