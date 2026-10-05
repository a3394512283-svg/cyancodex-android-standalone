package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.GenHistory
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.EmptyState
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.copyToClipboard
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

private fun GenHistory.typeLabel(): String = when (genType) {
    "tdisplay" -> "T显"; "enumerate" -> "穷举"; "scroller" -> "滚动字幕"; "music" -> "音乐"; "ai" -> "AI"; else -> genType
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var list by remember { mutableStateOf<List<GenHistory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    suspend fun load() {
        loading = true
        try {
            // 本地历史(离线/未登录都保存) + 服务器历史合并
            val local = Session.localHistory()
            val server = if (AppState.token != null) {
                val r = safeApi { ApiClient.get().history(null, 1, 50) }
                if (r.ok) r.data?.list ?: emptyList() else emptyList()
            } else emptyList()
            // 按生成时间倒序 (本地在列表头, 服务器按 createdAt 倒序)
            val merged = (local + server)
                .distinctBy { it.outputCommand }
                .sortedByDescending { it.createdAt?.take(19) ?: "" }
            // 本地条目(createdAt=null)放最前
            list = local.filter { it.createdAt == null } +
                merged.filter { m -> local.none { l -> l.outputCommand == m.outputCommand && l.createdAt == null } }
        } finally { loading = false }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            AppTopBar(title = "生成历史", icon = Icons.Default.History, onBack = { nav.popBackStack() })
        },
    ) { pad ->
        when {
            loading -> LoadingBox(Modifier.padding(pad))
            list.isEmpty() -> EmptyState("暂无生成记录\n去生成器里生成几条吧", Modifier.padding(pad))
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { "${it.outputCommand}_${it.id}" }) { h ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                AssistChip(onClick = {}, label = { Text(h.typeLabel()) })
                                Spacer(Modifier.weight(1f))
                                Text(h.createdAt?.take(16) ?: "本机记录", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(h.outputCommand.lines().firstOrNull().orEmpty(),
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary, maxLines = 2)
                            Spacer(Modifier.height(6.dp))
                            Row {
                                TextButton(onClick = {
                                    copyToClipboard(ctx, h.outputCommand, "已复制生成结果")
                                }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("复制") }
                                TextButton(onClick = {
                                    scope.launch {
                                        if (h.createdAt == null) {
                                            // 本地记录: 直接删除
                                            Session.removeLocalHistory(h.outputCommand)
                                            list = list.filter { it.outputCommand != h.outputCommand }
                                        } else {
                                            val r = safeApi { ApiClient.get().deleteHistory(h.id) }
                                            if (r.ok) list = list.filter { it.id != h.id }
                                        }
                                    }
                                }) { Icon(Icons.Default.Delete, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(4.dp)); Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }
}
