package com.canglan.cyancodex.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Command
import com.canglan.cyancodex.data.Comment
import com.canglan.cyancodex.data.QuickCommand
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.AvatarIcon
import com.canglan.cyancodex.ui.components.BedrockHighlight
import com.canglan.cyancodex.ui.components.CommandCard
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.RemoteImage
import com.canglan.cyancodex.ui.components.categoryLabel
import com.canglan.cyancodex.ui.components.copyToClipboard
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandDetailScreen(nav: NavHostController, id: Long) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var cmd by remember { mutableStateOf<Command?>(null) }
    var fav by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var commentText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var commentError by remember { mutableStateOf("") }
    var buyError by remember { mutableStateOf("") }
    // 意见 #91: 加载失败(例如未通过审核的指令)时不能一直转圈, 要给出原因和返回按钮
    var loadError by remember { mutableStateOf("") }
    val user = AppState.user

    fun loadComments() {
        scope.launch {
            val r = safeApi { ApiClient.get().comments(id) }
            if (r.ok) comments = r.data ?: emptyList()
        }
    }

    fun sendComment() {
        val t = commentText.trim()
        if (t.isBlank()) return
        scope.launch {
            sending = true
            commentError = ""
            val r = safeApi { ApiClient.get().addComment(id, mapOf("content" to t)) }
            sending = false
            if (r.ok) {
                commentText = ""
                loadComments()
                cmd?.let { c -> cmd = c.copy(commentCount = c.commentCount + 1) }
            } else {
                commentError = r.error ?: "发表失败"
            }
        }
    }

    fun deleteComment(cid: Long) {
        scope.launch {
            val r = safeApi { ApiClient.get().deleteComment(cid) }
            if (r.ok) {
                comments = comments.filter { it.id != cid }
                cmd?.let { c -> cmd = c.copy(commentCount = (c.commentCount - 1).coerceAtLeast(0)) }
            }
        }
    }

    LaunchedEffect(id) {
        val r = safeApi { ApiClient.get().commandDetail(id) }
        if (r.ok) { cmd = r.data; loadError = "" } else loadError = r.error ?: "加载失败"
        loadComments()
    }

    if (deleted) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }

    Scaffold(
        topBar = {
            AppTopBar(title = cmd?.title ?: "指令详情", icon = Icons.Default.Description, onBack = { nav.popBackStack() })
        },
    ) { pad ->
        val c = cmd
        if (c == null) {
            if (loadError.isNotBlank()) {
                Column(
                    Modifier.padding(pad).fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("打不开这条指令", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(loadError, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("如果这是你自己上传、还没审核通过的指令，请到「指令库 → 我的」里查看；已上架的指令才能被其他人打开。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    GlassActionButton(filled = true, onClick = { nav.popBackStack() }) { Text("返回") }
                }
            } else {
                LoadingBox(Modifier.padding(pad))
            }
        } else {
            // ★ 2026-10-17 修「点赞闪退」：likeCommand 的返回体一旦字段/泛型对不上，
            //   `r.data.likeCount` 会在 safeApi 之外抛 ClassCastException（Gson 把 data
            //   反序列化成 LinkedTreeMap），直接把进程打崩。这里把整段后处理收进
            //   try/catch，失败只 Toast，不再闪退。
            fun doLike() {
                if (AppState.token == null) {
                    nav.navigate(Routes.LOGIN)
                    return
                }
                scope.launch {
                    try {
                        val r = safeApi { ApiClient.get().likeCommand(c.id) }
                        val d = r.data
                        if (r.ok && d != null) {
                            cmd = c.copy(likeCount = d.likeCount, liked = d.liked)
                        } else {
                            val rr = safeApi { ApiClient.get().commandDetail(c.id) }
                            if (rr.ok && rr.data != null) cmd = rr.data
                        }
                    } catch (e: Exception) {
                        com.canglan.cyancodex.data.DebugStore.logE(
                            "Like", "点赞异常: ${e.javaClass.simpleName}: ${e.message}"
                        )
                        android.widget.Toast.makeText(
                            ctx, "点赞失败，请稍后重试", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 元信息
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(c.categoryLabel()) })
                    Spacer(Modifier.width(8.dp))
                    if (c.source == "platform") {
                        AssistChip(onClick = {}, label = { Text(if (c.quality == "high") "平台·高质" else "平台·低质") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (c.quality == "high") MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant))
                    } else {
                        AssistChip(onClick = { if (c.userId != null) nav.navigate(Routes.user(c.userId)) },
                            label = { Text("@${c.author ?: "用户"}") })
                        // 官方认证徽章 (红色)
                        if (c.authorOfficial != 0) {
                            Spacer(Modifier.width(5.dp))
                            Surface(shape = MaterialTheme.shapes.small, color = Color(0xFFF87171).copy(alpha = 0.18f)) {
                                Text("官方", color = Color(0xFFF87171), style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("${c.likeCount}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("${c.viewCount}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // 意见 #102: 显示指令发布时间
                if (!c.createdAt.isNullOrBlank()) {
                    Text("发布于 ${c.createdAt}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 意见 #91: 作者本人看自己未上架的指令时, 明确显示审核状态
                if (c.status != "active") {
                    val rejected = c.status == "rejected"
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (rejected) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                if (rejected) "已驳回（未上架）" else "待审核（未上架）",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (rejected) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (rejected) {
                                    val note = c.reviewNote
                                    if (!note.isNullOrBlank()) "驳回原因：$note" else "审核未通过，可在下方点「编辑」修改后重新提交"
                                } else {
                                    "审核通过后才会在指令库公开，其他人暂时看不到这条指令。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (c.description.isNotBlank()) {
                    Text(c.description, style = MaterialTheme.typography.bodyMedium)
                }

                // 指令效果图
                if (!c.image.isNullOrBlank()) {
                    RemoteImage(c.image, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), corner = 12.dp)
                }

                // 附件 (可以只发附件: 有人用文件形式发指令)
                if (c.files.isNotEmpty()) {
                    val lockedPaid = c.price > 0 && c.locked
                    // 用户反馈「付费指令附件无法下载」：下载是跳浏览器/系统下载器，不会带 Authorization 头，
                    // 付费指令会被当游客 → 403。服务端已支持 ?token= 兜底，这里把登录令牌拼进链接。
                    val dlToken = com.canglan.cyancodex.ui.AppState.token ?: ""
                    fun fileLink(u: String): String {
                        val b = com.canglan.cyancodex.data.Session.baseUrl.trimEnd('/') + u
                        if (dlToken.isBlank()) return b
                        return b + (if (u.contains("?")) "&" else "?") + "token=" + dlToken
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text("附件（${c.files.size}）", style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                            c.files.forEach { f ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.name, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text(f.sizeText, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = {
                                        copyToClipboard(ctx, fileLink(f.url), "下载链接已复制（含登录令牌，粘贴到浏览器即可下载）")
                                    }) { Text("复制链接", style = MaterialTheme.typography.labelSmall) }
                                    if (!lockedPaid) {
                                        GlassActionButton(filled = true, onClick = {
                                            val link = fileLink(f.url)
                                            runCatching {
                                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                            }.onFailure {
                                                copyToClipboard(ctx, link, "已复制下载链接")
                                            }
                                        }) { Text("下载", style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                            }
                            if (lockedPaid) {
                                Text("解锁付费指令后可下载附件", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // 付费指令: 价格徽章 + 未购锁定提示
                if (c.price > 0) {
                    val isOwner = user != null && c.userId == user.id
                    val isAdmin = user?.role == "admin"
                    val unlocked = isOwner || isAdmin || c.purchased || !c.locked
                    Surface(shape = MaterialTheme.shapes.medium,
                        color = if (unlocked) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, Modifier.size(16.dp),
                                tint = if (unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (unlocked) "付费指令 · 已解锁（${c.price} 积分）"
                                else "付费指令 · ${c.price} 积分购买后可见",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (unlocked) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                // 指令内容: 语法高亮 + 按命令块分段, 每段可单独复制 (参考 CHelper/BNM)
                // #90: 付费未解锁时服务器 command=null, 需判空避免 NPE 闪退
                val rawCmd = c.command ?: ""
                val lines = rawCmd.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                if (c.locked && rawCmd.isBlank() && c.price > 0) {
                    // 付费未解锁: 隐藏内容
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0F0C))) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.Star, null, tint = Color(0xFFFBBF24), modifier = Modifier.size(40.dp))
                            Text("指令内容已锁定", color = Color(0xFFE7F2EA), style = MaterialTheme.typography.titleMedium)
                            Text("购买后可查看并复制完整指令",
                                color = Color(0xFF9DB4A5), style = MaterialTheme.typography.bodySmall)
                            if (buyError.isNotBlank()) {
                                Text(buyError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else if (lines.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0F0C))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            lines.forEachIndexed { idx, ln ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${idx + 1}", style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF4ADE80), modifier = Modifier.width(22.dp))
                                    Text(
                                        BedrockHighlight.highlight(ln, dark = true),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFE7F2EA),
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { copyToClipboard(ctx, ln, "已复制第 ${idx + 1} 段") },
                                        modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.ContentCopy, "复制第${idx + 1}段",
                                            tint = Color(0xFF9DB4A5), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // 操作行
                val isPaidLocked = c.locked && rawCmd.isBlank() && c.price > 0
                if (isPaidLocked) {
                    // 付费未解锁: 主操作 = 购买
                    GlassActionButton(
                        filled = true,
                        onClick = {
                            if (AppState.token == null) { nav.navigate(Routes.LOGIN); return@GlassActionButton }
                            if (user != null && user.points < c.price) {
                                buyError = "积分不足：需要 ${c.price} 分（当前 ${user.points} 分），可通过每日签到获取"
                                return@GlassActionButton
                            }
                            scope.launch {
                                buying = true
                                buyError = ""
                                val r = safeApi { ApiClient.get().purchaseCommand(c.id) }
                                buying = false
                                if (r.ok) {
                                    android.widget.Toast.makeText(ctx, "购买成功，积分已扣除", android.widget.Toast.LENGTH_SHORT).show()
                                    r.data?.points?.let { AppState.patchUser(points = it) }
                                    // 重新拉详情解锁
                                    val rr = safeApi { ApiClient.get().commandDetail(id) }
                                    if (rr.ok) cmd = rr.data
                                    buyError = ""
                                } else buyError = r.error ?: "购买失败"
                            }
                        },
                        enabled = !buying,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (buying) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Default.Star, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("花费 ${c.price} 积分购买并解锁")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        GlassActionButton(onClick = {
                            if (AppState.token == null) nav.navigate(Routes.LOGIN)
                            else scope.launch {
                                val r = safeApi { ApiClient.get().toggleFavorite(c.id) }
                                if (r.ok) fav = r.data?.favorited ?: !fav
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (fav) "已收藏" else "收藏")
                        }
                        GlassActionButton(onClick = { doLike() }, modifier = Modifier.weight(1f)) {
                            Icon(if (c.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                                Modifier.size(16.dp),
                                tint = if (c.liked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text(if (c.liked) "已赞" else "点赞")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassActionButton(filled = true, onClick = { copyToClipboard(ctx, c.command) },
                            modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (lines.size > 1) "复制全部(${lines.size}段)" else "复制指令")
                        }
                        GlassActionButton(onClick = {
                            if (AppState.token == null) nav.navigate(Routes.LOGIN)
                            else scope.launch {
                                val r = safeApi { ApiClient.get().toggleFavorite(c.id) }
                                if (r.ok) fav = r.data?.favorited ?: !fav
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (fav) "已收藏" else "收藏")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassActionButton(onClick = {
                            Session.addQuickCommand(QuickCommand(c.title, c.command))
                            Session.addRecent(c.command)
                            android.widget.Toast.makeText(ctx, "已加入快捷指令(输入法/悬浮窗可用)", android.widget.Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Star, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("加入快捷")
                        }
                        GlassActionButton(onClick = { doLike() }, modifier = Modifier.weight(1f)) {
                            Icon(if (c.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                                Modifier.size(16.dp),
                                tint = if (c.liked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text(if (c.liked) "已赞" else "点赞")
                        }
                    }
                }

                // 评论区
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("评论 (${comments.size})", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    IconButton(onClick = { loadComments() }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Refresh, "刷新评论", modifier = Modifier.size(16.dp))
                    }
                }
                if (commentError.isNotBlank()) {
                    Text(commentError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (comments.isEmpty()) {
                    Text("暂无评论，来抢沙发~", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                }
                comments.forEach { cm ->
                    CommentRow(
                        cm = cm,
                        mine = user != null && cm.userId == user.id,
                        onDelete = { deleteComment(cm.id) },
                        onAuthor = { nav.navigate(Routes.user(cm.userId)) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = commentText, onValueChange = { commentText = it },
                        placeholder = { Text(if (AppState.token == null) "登录后可评论" else "说点什么…") },
                        enabled = AppState.token != null,
                        modifier = Modifier.weight(1f), maxLines = 2,
                        shape = RoundedCornerShape(10.dp),
                    )
                    IconButton(
                        onClick = {
                            if (AppState.token == null) nav.navigate(Routes.LOGIN)
                            else sendComment()
                        },
                        enabled = !sending && (AppState.token != null) && commentText.isNotBlank(),
                    ) {
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Send, "发表评论")
                    }
                }

                // 本人上传: 编辑/删除
                if (c.source == "user" && user != null && c.userId == user.id) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassActionButton(onClick = { nav.navigate(Routes.editor(c.command, c.id)) },
                            modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("编辑")
                        }
                        GlassActionButton(onClick = {
                            scope.launch {
                                val r = safeApi { ApiClient.get().deleteCommand(c.id) }
                                if (r.ok) deleted = true
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CommentRow(
    cm: Comment,
    mine: Boolean,
    onDelete: () -> Unit,
    onAuthor: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        AvatarIcon(cm.avatar, 34.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cm.nickname.ifBlank { cm.username },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onAuthor),
                )
                // 管理员徽章 (意见 #43)
                if (cm.userRole == "admin") {
                    Spacer(Modifier.width(5.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = Color(0xFFFBBF24).copy(alpha = 0.18f)) {
                        Text("管理员", color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
                // 官方认证徽章 (红色)
                if (cm.userOfficial != 0) {
                    Spacer(Modifier.width(5.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = Color(0xFFF87171).copy(alpha = 0.18f)) {
                        Text("官方", color = Color(0xFFF87171), style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(cm.createdAt ?: "", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(cm.content, style = MaterialTheme.typography.bodyMedium)
        }
        if (mine) {
            IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Delete, "删除评论",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}
