package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.MessageItem
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

/** 站内信: 系统消息/管理员通知 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<MessageItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    suspend fun load() {
        val r = safeApi { ApiClient.get().messages(1, 50) }
        if (r.ok) list = r.data?.list ?: emptyList() else msg = r.error ?: "加载失败"
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    fun markRead(m: MessageItem) {
        if (m.isRead == 1) return
        scope.launch {
            safeApi { ApiClient.get().markMessageRead(m.id) }
            list = list.map { if (it.id == m.id) it.copy(isRead = 1) else it }
        }
    }

    Scaffold(
        // 用户反馈「站内信有大黑边」：原来沿用主题 background（深色下近黑），
        // 换成与卡片/底栏一致的 surface，整页观感统一
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(title = "站内信", icon = Icons.Default.MailOutline, onBack = { nav.popBackStack() })
        },
    ) { pad ->
        if (loading && list.isEmpty()) { LoadingBox(Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msg.isNotBlank()) item { Text(msg, color = MaterialTheme.colorScheme.error) }
            if (list.isEmpty()) item {
                Text("暂无站内信\n管理员发送的积分通知、活动消息都会出现在这里", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(list, key = { it.id }) { m ->
                val unread = m.isRead == 0
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (unread) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            markRead(m)
                            expandedId = if (expandedId == m.id) null else m.id
                        },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (unread) {
                                Box(Modifier.size(8.dp).background(Color(0xFF38BDF8), RoundedCornerShape(50)))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(m.title, style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f))
                            Text(m.createdAt?.take(16)?.replace("T", " ") ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (expandedId == m.id) {
                            Spacer(Modifier.height(6.dp))
                            Text(m.content, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
