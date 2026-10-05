package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.FeedItem
import com.canglan.cyancodex.ui.LocalGlassBottomInset
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.AvatarIcon
import com.canglan.cyancodex.ui.components.EmptyState
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

/** 动态广场: 最新用户上传 + 最新评论 合并流 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            loading = true
            err = ""
            val r = safeApi { ApiClient.get().feed(1, 50) }
            loading = false
            if (r.ok) items = r.data?.list ?: emptyList() else err = r.error ?: "加载失败"
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = { AppTopBar(title = "广场", icon = Icons.Default.Forum) },
    ) { pad ->
        when {
            loading -> LoadingBox(Modifier.padding(pad))
            items.isEmpty() -> EmptyState(
                if (err.isNotBlank()) err else "还没有动态\n去指令库上传第一条指令，或去评论一下别人的作品吧",
                Modifier.padding(pad))
            else -> LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp, top = 12.dp, end = 12.dp,
                    // 液态玻璃开启时底栏浮在内容之上, 末尾补出这段高度
                    bottom = 12.dp + LocalGlassBottomInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items) { it ->
                    FeedCard(
                        item = it,
                        onClick = {
                            val target = if (it.type == "comment") it.commandId else it.id
                            if (target != null && target > 0) nav.navigate(Routes.commandDetail(target))
                        },
                        onAuthor = { if (it.userId != null && it.userId > 0) nav.navigate(Routes.user(it.userId)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedCard(item: FeedItem, onClick: () -> Unit, onAuthor: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarIcon(item.authorAvatar ?: "", 34.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.author ?: "用户",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onAuthor),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(item.createdAt ?: "", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.type == "command") {
                        Spacer(Modifier.height(4.dp))
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        if (item.description.isNotBlank()) {
                            Text(item.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("${item.likeCount}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("${item.viewCount}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.Default.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("${item.commentCount}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Spacer(Modifier.height(2.dp))
                        Text("评论了「${item.commandTitle ?: "指令"}」", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(item.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
