package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.ChatMessage
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.AvatarIcon
import com.canglan.cyancodex.ui.components.ChatInputRow
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassChatInputBar
import com.canglan.cyancodex.ui.components.copyToClipboard
import com.canglan.cyancodex.ui.components.safeApi
import com.canglan.cyancodex.ui.liquidGlassActive
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 聊天室 (意见 #36): 公共频道, 3 秒限流, 5 秒轮询新消息 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    suspend fun loadInitial() {
        val r = safeApi { ApiClient.get().chatMessages(50) }
        if (r.ok) {
            messages = r.data ?: emptyList()
            err = ""
            // 打开时定位到聊天记录最底部
            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.size - 1)
            }
        } else if (messages.isEmpty()) {
            err = r.error ?: "加载失败"
        }
    }

    // 轮询新消息 (5 秒)
    LaunchedEffect(Unit) {
        loadInitial()
        while (true) {
            delay(5000)
            val lastId = messages.lastOrNull()?.id ?: 0
            val r = safeApi { ApiClient.get().chatAfter(lastId) }
            if (r.ok) {
                val fresh = r.data ?: emptyList()
                if (fresh.isNotEmpty()) {
                    messages = messages + fresh
                    // 若接近底部则跟随滚动
                    val nearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        ?.let { it >= (messages.size - fresh.size - 2) } ?: true
                    if (nearBottom) {
                        scope.launch {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                }
            }
        }
    }

    // 液态玻璃输入条 (2026-09-25)
    val glassInput = liquidGlassActive
    val inputBackdrop = rememberLayerBackdrop()
    var inputBarH by remember { mutableStateOf(0.dp) }

    val sendEnabled = !sending && input.isNotBlank()
    // 发送逻辑只写一份 —— 玻璃/非玻璃两种外观共用, 免得改一处漏一处
    val doSend: () -> Unit = {
        val text = input.trim()
        input = ""
        scope.launch {
            sending = true
            val r = safeApi { ApiClient.get().sendChat(mapOf("content" to text)) }
            sending = false
            if (r.ok) {
                r.data?.let { messages = messages + it }
                listState.animateScrollToItem(messages.size - 1)
            } else {
                err = r.error ?: "发送失败"
            }
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "聊天室", icon = Icons.Default.Forum, onBack = { nav.popBackStack() }) },
        bottomBar = {
            // 玻璃开启时输入条改走下面的浮层 —— 挂在 bottomBar 里的话 Scaffold 会把
            // body 顶上去, 玻璃正下方只剩 Scaffold 纯色底, 玻璃会退化成半透明矩形。
            // 非玻璃分支保持上游原样(逐像素不变)。
            if (!glassInput) {
                Surface(tonalElevation = 3.dp) {
                    ChatInputRow(
                        value = input, onValueChange = { input = it },
                        placeholder = "和大家聊点 MC 指令…",
                        sendEnabled = sendEnabled, onSend = doSend,
                        glass = false, backdrop = inputBackdrop,
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    // 玻璃开启时去掉底部内缩, 让列表一路排到屏幕底, 玻璃才真的压在内容上
                    .padding(if (glassInput) PaddingValues(top = pad.calculateTopPadding()) else pad)
                    .fillMaxSize()
                    // 采样源: 只包内容列。输入条是它的**兄弟节点**(画在后面) ——
                    // 绝不能含在被采样节点内部, 否则会采到上一帧的自己(拖影)。
                    .then(if (glassInput) Modifier.layerBackdrop(inputBackdrop) else Modifier)
            ) {
                if (err.isNotBlank() && messages.isEmpty()) {
                    Text(err, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp))
                }
                if (messages.isEmpty() && err.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有消息，来发第一条吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 玻璃开启时末尾补出输入条高度, 否则最后一条消息会被盖住。
                    // 非玻璃分支刻意保持 12dp —— 与上游一致。
                    contentPadding = PaddingValues(
                        start = 12.dp, top = 12.dp, end = 12.dp,
                        bottom = 12.dp + if (glassInput) inputBarH + 8.dp else 0.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        ChatBubble(m, nav, scope,
                            onDeleted = { mid ->
                                messages = messages.map { if (it.id == mid) it.copy(content = "[消息已删除]") else it }
                            },
                            onReported = { ok, text -> if (!ok) err = text ?: "举报失败" },
                        )
                    }
                }
            }
            GlassChatInputBar(
                glass = glassInput,
                backdrop = inputBackdrop,
                value = input, onValueChange = { input = it },
                placeholder = "和大家聊点 MC 指令…",
                sendEnabled = sendEnabled, onSend = doSend,
                onHeightChanged = { inputBarH = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    m: ChatMessage,
    nav: NavHostController,
    scope: CoroutineScope,
    onDeleted: (Long) -> Unit,
    onReported: (Boolean, String?) -> Unit,
) {
    val mine = m.userId != null && m.userId == AppState.user?.id
    val ctx = LocalContext.current
    var menuMsg by remember { mutableStateOf<ChatMessage?>(null) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        if (!mine) {
            AvatarIcon(m.avatar ?: "", 30.dp,
                onClick = { if (m.userId != null) nav.navigate(Routes.user(m.userId)) })
            Spacer(Modifier.width(6.dp))
        }
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            if (!mine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.nickname ?: "用户",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (m.role == "admin") Color(0xFFFBBF24) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (m.role == "admin") FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable {
                            if (m.userId != null) nav.navigate(Routes.user(m.userId))
                        },
                    )
                    // 官方认证徽章 (红色)
                    if (m.userOfficial != 0) {
                        Spacer(Modifier.width(4.dp))
                        Surface(shape = MaterialTheme.shapes.small, color = Color(0xFFF87171).copy(alpha = 0.18f)) {
                            Text("官方", color = Color(0xFFF87171), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp))
                        }
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = 12.dp, topEnd = 12.dp,
                    bottomStart = if (mine) 12.dp else 2.dp,
                    bottomEnd = if (mine) 2.dp else 12.dp,
                ),
                color = if (mine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { menuMsg = m },
                ),
            ) {
                Text(
                    m.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Text(
                m.createdAt?.take(16)?.replace("T", " ") ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }

    // 长按操作菜单 (意见 #67): 复制 / 删除(自己的消息或管理员) / 举报(他人)
    menuMsg?.let { mm ->
        val isAdminOrOfficial = AppState.user?.role == "admin" || AppState.user?.role == "official"
        val canDelete = mm.userId == AppState.user?.id || isAdminOrOfficial
        val canReport = mm.userId != AppState.user?.id
        AlertDialog(
            onDismissRequest = { menuMsg = null },
            title = { Text("消息操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        (mm.nickname ?: "用户") + "：" + mm.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                    Spacer(Modifier.height(4.dp))
                    // 删除 (自己的消息或管理员)
                    if (canDelete) {
                        GlassActionButton(onClick = {
                            menuMsg = null
                            scope.launch {
                                val r = safeApi { ApiClient.get().deleteChat(mm.id) }
                                if (r.ok) {
                                    onDeleted(mm.id)
                                    android.widget.Toast.makeText(ctx, "消息已删除", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    onReported(false, r.error ?: "删除失败")
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("删除此消息", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    // 举报 (他人的消息)
                    if (canReport) {
                        GlassActionButton(onClick = {
                            menuMsg = null
                            scope.launch {
                                val r = safeApi {
                                    ApiClient.get().reportChat(
                                        com.canglan.cyancodex.data.ReportChatReq(mm.id, "长按举报"))
                                }
                                if (r.ok) {
                                    onReported(true, null)
                                    android.widget.Toast.makeText(ctx, "举报成功，将尽快处理", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    onReported(false, r.error ?: "举报失败")
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("举报该消息", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    // 复制
                    GlassActionButton(onClick = {
                        menuMsg = null
                        copyToClipboard(ctx, mm.content, "已复制消息")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("复制内容")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuMsg = null }) { Text("取消") } },
        )
    }
}
