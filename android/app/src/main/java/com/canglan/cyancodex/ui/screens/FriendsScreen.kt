package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Conversation
import com.canglan.cyancodex.data.DmMessage
import com.canglan.cyancodex.data.FriendItem
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.AvatarIcon
import com.canglan.cyancodex.ui.components.ChatInputRow
import com.canglan.cyancodex.ui.components.EmptyState
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassChatInputBar
import com.canglan.cyancodex.ui.components.safeApi
import com.canglan.cyancodex.ui.liquidGlassActive
import com.canglan.cyancodex.ui.liquidglass.LiquidBottomTab
import com.canglan.cyancodex.ui.liquidglass.LiquidBottomTabs
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class FriendsTab(val label: String, val icon: ImageVector) {
    FRIENDS("好友", Icons.Default.People),
    REQUESTS("申请", Icons.Default.PersonAdd),
    CHATS("私聊", Icons.AutoMirrored.Filled.Chat),
}

/**
 * 好友页玻璃底栏的实测高度 —— 由 [FriendsScreen] 提供, 三个列表读它补 `contentPadding.bottom`,
 * 否则最后一项会被浮着的玻璃条压住。
 * 不硬编码: LiquidBottomTabs 内部高度固定(64dp), 但外边距/是否让开手势区由调用方决定。
 */
private val LocalFriendsBarInset = staticCompositionLocalOf { 0.dp }

/** 好友/私聊中心 (意见 #38) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(nav: NavHostController) {
    var tab by remember { mutableStateOf(FriendsTab.FRIENDS) }

    // 液态玻璃底部菜单栏 (2026-09-26): 直接用官方 LiquidBottomTabs, 与 App 主底栏同一套组件。
    // 比原版多出图标 —— 原版是 NavigationBar + 纯文字(icon 槽里塞的就是 Text), 没有任何图标。
    val glass = liquidGlassActive
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val barContentColor = if (dark) Color.White else Color.Black
    val tabList = FriendsTab.entries
    // ★★ 必须用 derivedStateOf 包一层 —— 2026-09-26 用户报「点底部『申请』高光不动、界面切换出错」。
    //
    // 官方 LiquidBottomTabs 内部是这样观察选中项的:
    //     var currentIndex by remember(selectedTabIndex) { mutableIntStateOf(selectedTabIndex()) }
    //     LaunchedEffect(dampedDragAnimation) { snapshotFlow { currentIndex }... { onTabSelected(index) } }
    // 它把 `selectedTabIndex` 这个 **lambda 本身**当 remember 的 key。
    //
    // 之前传的是 `{ selectedIndex }`, 其中 selectedIndex 是组合期算好的**普通 Int** ——
    // 这个 lambda 每次重组都是**新实例**, key 一直在变, 于是:
    //   · `remember(selectedTabIndex)` 每次重组都重建 currentIndex;
    //   · `LaunchedEffect(dampedDragAnimation)`(key 恒定, 只启动一次)里那个
    //     `snapshotFlow { currentIndex }` 盯着的可能是**被重建掉的旧 state 对象**
    //     → 永远收不到变化 → 药丸不跟手、高光不动。
    // 这就是"点了没反应"的根因。主底栏(AppNav)当初踩的是同一个坑, 修法也一样。
    //
    // 修法: 用 derivedStateOf 把"读 tab"这个动作放进 lambda **内部** ——
    //   lambda 变成稳定实例(key 不再乱变), 而且 selectedTabIndex() 是**真状态读取**,
    //   snapshotFlow 才观察得到。`remember(tabList)` 的 key 不能省(与 AppNav 同理)。
    val selectedIndexState = remember(tabList) {
        derivedStateOf { tabList.indexOf(tab).coerceAtLeast(0) }
    }
    var barH by remember { mutableStateOf(0.dp) }

    Scaffold(
        topBar = {
            AppTopBar(title = "好友", icon = Icons.Default.Person, onBack = { nav.popBackStack() })
        },
        bottomBar = {
            // 玻璃开启时底栏改走下面的浮层 —— 挂在 bottomBar 里 Scaffold 会把内容顶上去,
            // 玻璃正下方只剩 Scaffold 纯色底, 就退化成一块半透明矩形。
            // 非玻璃分支保持上游原样(逐像素不变)。
            if (!glass) {
                NavigationBar {
                    tabList.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            // ★ 2026-09-26 用户要求: 关掉玻璃后也要**保留图标**。
                            //   原版上游是 `icon = { Text(t.label) } + label = {}` —— 一个图标都没有,
                            //   纯文字页签。这里与玻璃态对齐(图标 + 文字)。
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        CompositionLocalProvider(LocalFriendsBarInset provides if (glass) barH + 12.dp else 0.dp) {
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
                    when (tab) {
                        FriendsTab.FRIENDS -> FriendsListScreen(nav)
                        FriendsTab.REQUESTS -> RequestsScreen()
                        FriendsTab.CHATS -> ChatsListScreen(nav)
                    }
                }
                if (glass) {
                    LiquidBottomTabs(
                        selectedTabIndex = { selectedIndexState.value },
                        onTabSelected = { i -> tabList.getOrNull(i)?.let { tab = it } },
                        backdrop = backdrop,
                        tabsCount = tabList.size,
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
                        tabList.forEach { t ->
                            LiquidBottomTab({ tab = t }) {
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
    }
}

// ============ 好友列表 ============
@Composable
private fun FriendsListScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var friends by remember { mutableStateOf<List<FriendItem>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf<FriendItem?>(null) }
    suspend fun load() {
        val r = safeApi { ApiClient.get().friends("accepted") }
        if (r.ok) friends = r.data ?: emptyList() else msg = r.error ?: "加载失败"
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        if (msg.isNotBlank()) Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
        if (friends.isEmpty() && msg.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有好友\n在用户主页点「+ 关注/加好友」发送申请", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = 12.dp, top = 12.dp, end = 12.dp,
            bottom = 12.dp + LocalFriendsBarInset.current,
        ),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(friends, key = { it.peerId ?: it.id }) { f ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AvatarIcon(f.avatar ?: "", 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.nickname ?: "用户", style = MaterialTheme.typography.titleSmall)
                            Text("@${f.username ?: "?"}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { nav.navigate(Routes.user(f.peerId ?: 0)) }) { Text("主页") }
                        TextButton(onClick = {
                            nav.navigate(Routes.dm(f.peerId ?: 0, f.nickname ?: ""))
                        }) { Text("私聊") }
                        IconButton(onClick = { confirmDel = f }) {
                            Icon(Icons.Default.Delete, "删除好友", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    // 删除好友确认 (意见 #84)
    confirmDel?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text("删除好友") },
            text = { Text("确定删除好友「${f.nickname ?: "用户"}」吗？删除后将不再是好友，也无法继续私聊。") },
            confirmButton = {
                TextButton(onClick = {
                    val peer = f.peerId ?: 0
                    confirmDel = null
                    scope.launch {
                        val r = safeApi { ApiClient.get().friendRemove(mapOf("friend_id" to peer.toString())) }
                        if (r.ok) {
                            friends = friends.filter { (it.peerId ?: it.id) != peer }
                            android.widget.Toast.makeText(ctx, "已删除好友", android.widget.Toast.LENGTH_SHORT).show()
                        } else msg = r.error ?: "删除失败"
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } },
        )
    }
}

// ============ 好友申请 ============
@Composable
private fun RequestsScreen() {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<FriendItem>>(emptyList()) }
    suspend fun load() {
        val r = safeApi { ApiClient.get().friends("pending") }
        if (r.ok) requests = r.data ?: emptyList()
    }
    LaunchedEffect(Unit) { load() }

    if (requests.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无好友申请", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
        start = 12.dp, top = 12.dp, end = 12.dp,
        bottom = 12.dp + LocalFriendsBarInset.current,
    ),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(requests, key = { it.id }) { f ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarIcon(f.avatar ?: "", 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.nickname ?: "用户", style = MaterialTheme.typography.titleSmall)
                        Text("@${f.username ?: "?"} 请求添加你为好友", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GlassActionButton(filled = true, onClick = {
                        scope.launch {
                            safeApi { ApiClient.get().friendHandle(mapOf("friend_id" to (f.userId ?: 0).toString(), "accept" to "true")) }
                            load()
                        }
                    }) { Text("接受") }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = {
                        scope.launch {
                            safeApi { ApiClient.get().friendHandle(mapOf("friend_id" to (f.userId ?: 0).toString(), "accept" to "false")) }
                            load()
                        }
                    }) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

// ============ 会话列表 ============
@Composable
private fun ChatsListScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var convs by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    suspend fun load() {
        val r = safeApi { ApiClient.get().dmConversations() }
        if (r.ok) convs = r.data ?: emptyList()
    }
    LaunchedEffect(Unit) { load() }

    if (convs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无私聊\n和好友点「私聊」开始对话", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
        start = 12.dp, top = 12.dp, end = 12.dp,
        bottom = 12.dp + LocalFriendsBarInset.current,
    ),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(convs, key = { it.id }) { c ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().clickable {
                    nav.navigate(Routes.dm(c.peerId ?: 0, c.nickname ?: ""))
                }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarIcon(c.avatar ?: "", 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.nickname ?: "用户", style = MaterialTheme.typography.titleSmall)
                            // 未读私信小红点（用户要求：私信不再进站内信，改在好友/私聊卡片上提示）
                            if (c.unread > 0) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.error,
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(
                                        if (c.unread > 99) "99+" else c.unread.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onError,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                        Text(c.content, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text(c.createdAt?.take(16)?.replace("T", " ") ?: "", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ============ 私聊窗口 ============
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmScreen(nav: NavHostController, peerId: Long, peerName: String) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<DmMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    suspend fun loadInitial() {
        val r = safeApi { ApiClient.get().dmMessages(peerId, 0) }
        if (r.ok) messages = r.data ?: emptyList() else err = r.error ?: "加载失败"
    }

    LaunchedEffect(Unit) {
        loadInitial()
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
        // 轮询新消息
        while (true) {
            delay(4000)
            val lastId = messages.lastOrNull()?.id ?: 0
            val r = safeApi { ApiClient.get().dmAfter(peerId, lastId) }
            if (r.ok) {
                val fresh = r.data ?: emptyList()
                if (fresh.isNotEmpty()) {
                    messages = messages + fresh
                    if (messages.isNotEmpty()) {
                        listState.scrollToItem(messages.size - 1)
                    }
                }
            }
        }
    }

    // 液态玻璃输入条 (2026-09-25) —— 与聊天室共用 ui/components/ChatInputBar.kt
    val glassInput = liquidGlassActive
    val inputBackdrop = rememberLayerBackdrop()
    var inputBarH by remember { mutableStateOf(0.dp) }

    val sendEnabled = !sending && input.isNotBlank()
    val doSend: () -> Unit = {
        val text = input.trim()
        input = ""
        scope.launch {
            sending = true
            val r = safeApi { ApiClient.get().sendDm(mapOf("peer_id" to peerId.toString(), "content" to text)) }
            sending = false
            if (r.ok) {
                messages = messages + DmMessage(id = r.data?.id ?: 0, fromId = AppState.user?.id, toId = peerId, content = text)
                listState.scrollToItem(messages.size - 1)
            } else err = r.error ?: "发送失败"
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = peerName, onBack = { nav.popBackStack() }) },
        bottomBar = {
            // 玻璃开启时输入条改走下面的浮层(原因见 GlassChatInputBar 注释)
            if (!glassInput) {
                Surface(tonalElevation = 3.dp) {
                    ChatInputRow(
                        value = input, onValueChange = { input = it },
                        placeholder = "发送私聊…",
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
                    .padding(if (glassInput) PaddingValues(top = pad.calculateTopPadding()) else pad)
                    .fillMaxSize()
                    // 采样源只包内容列; 输入条是兄弟节点, 不能含在里面(会采到上一帧的自己)
                    .then(if (glassInput) Modifier.layerBackdrop(inputBackdrop) else Modifier)
            ) {
                if (err.isNotBlank() && messages.isEmpty()) {
                    Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 玻璃开启时末尾补出输入条高度; 非玻璃保持 12dp 与上游一致
                    contentPadding = PaddingValues(
                        start = 12.dp, top = 12.dp, end = 12.dp,
                        bottom = 12.dp + if (glassInput) inputBarH + 8.dp else 0.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        val mine = m.fromId != null && m.fromId == AppState.user?.id
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (mine) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            ) {
                                Text(m.content, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
            }
            GlassChatInputBar(
                glass = glassInput,
                backdrop = inputBackdrop,
                value = input, onValueChange = { input = it },
                placeholder = "发送私聊…",
                sendEnabled = sendEnabled, onSend = doSend,
                onHeightChanged = { inputBarH = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
