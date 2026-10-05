package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.AiHistoryItem
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.EmptyState
import com.canglan.cyancodex.ui.components.GlassTextButton
import com.canglan.cyancodex.ui.components.copyToClipboard
import com.canglan.cyancodex.ui.components.safeApi

/**
 * AI 对话历史: 查看与复制历史问答
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiHistoryScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<AiHistoryItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = safeApi { ApiClient.get().aiHistory(1, 50) }
        if (r.ok) list = r.data?.list ?: emptyList() else msg = r.error ?: "加载失败"
        loaded = true
    }

    Scaffold(
        topBar = { AppTopBar(title = "AI 对话历史", icon = Icons.Default.AutoAwesome, onBack = { nav.popBackStack() }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (msg.isNotBlank()) {
                Text(msg, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                list.isEmpty() -> EmptyState("暂无 AI 对话记录\n去 AI Agent 聊一聊吧")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { h ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Q: ${h.prompt}", style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f))
                                    Text(h.createdAt?.take(16) ?: "", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(h.result, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 6,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                // 玻璃化: 采样源 = 卡片底色(surface, 即默认值)
                                GlassTextButton(onClick = { copyToClipboard(ctx, h.result, "已复制 AI 回复") },
                                    modifier = Modifier.align(Alignment.End)) {
                                    Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("复制回复")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
