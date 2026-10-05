package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Command
import com.canglan.cyancodex.data.UserProfile
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.AvatarIcon
import com.canglan.cyancodex.ui.components.CommandCard
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

private fun genderLabel(g: String): String = when (g) {
    "male" -> "男"; "female" -> "女"; else -> "保密"
}

/** 用户主页: 资料 + 关注/取关 + TA 上传的指令 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(nav: NavHostController, userId: Long) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val me = AppState.user
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var commands by remember { mutableStateOf<List<Command>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var followBusy by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            val r = safeApi { ApiClient.get().userProfile(userId) }
            if (r.ok) profile = r.data
            val rc = safeApi { ApiClient.get().userCommands(userId, 1, 50) }
            if (rc.ok) commands = rc.data ?: emptyList()
            loading = false
        }
    }

    LaunchedEffect(userId) { load() }

    Scaffold(
        topBar = { AppTopBar(title = "用户主页", icon = Icons.Default.Person, onBack = { nav.popBackStack() }) },
    ) { pad ->
        val p = profile
        if (loading && p == null) { LoadingBox(Modifier.padding(pad)); return@Scaffold }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (p != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarIcon(p.avatar, 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.nickname.ifBlank { p.username }, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text("@${p.username} · ${genderLabel(p.gender)}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!p.createdAt.isNullOrBlank()) {
                            val days = com.canglan.cyancodex.ui.components.daysSince(p.createdAt)
                            Text(
                                if (days >= 0) "${p.createdAt.take(10)} 注册 · 已陪伴 CyanCodex ${days} 天"
                                else "${p.createdAt.take(10)} 注册",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (me != null && me.id != p.id) {
                        GlassActionButton(filled = true,
                            onClick = {
                                if (AppState.token == null) { nav.navigate(Routes.LOGIN); return@GlassActionButton }
                                scope.launch {
                                    followBusy = true
                                    val next = !p.isFollowing
                                    val r = if (next) safeApi { ApiClient.get().follow(p.id) }
                                    else safeApi { ApiClient.get().unfollow(p.id) }
                                    followBusy = false
                                    if (r.ok) {
                                        profile = p.copy(
                                            isFollowing = next,
                                            followerCount = (p.followerCount + if (next) 1 else -1).coerceAtLeast(0),
                                        )
                                    }
                                }
                            },
                            enabled = !followBusy,
                            colors = if (p.isFollowing) ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                            else ButtonDefaults.buttonColors(),
                            // 玻璃路径读不到 ButtonColors(internal), 定制配色要再给一份
                            glassTint = if (p.isFollowing) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.primary,
                            glassContentColor = if (p.isFollowing) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onPrimary,
                        ) {
                            if (followBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(if (p.isFollowing) "已关注" else "关注")
                        }
                        // 加好友 (意见 #38)
                        Spacer(Modifier.width(8.dp))
                        GlassActionButton(onClick = {
                            if (AppState.token == null) { nav.navigate(Routes.LOGIN); return@GlassActionButton }
                            scope.launch {
                                val r = safeApi { ApiClient.get().friendRequest(mapOf("target_id" to p.id.toString())) }
                                if (r.ok) {
                                    android.widget.Toast.makeText(ctx, "好友申请已发送", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(ctx, r.error ?: "发送失败", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Text("加好友") }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox("指令", p.commandCount, Modifier.weight(1f))
                    StatBox("粉丝", p.followerCount, Modifier.weight(1f))
                    StatBox("关注", p.followingCount, Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                Text("TA 的指令 (${commands.size})", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            if (commands.isEmpty()) {
                Text("还没有上传过指令", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
            commands.forEach { c ->
                CommandCard(
                    cmd = c,
                    onClick = { nav.navigate(Routes.commandDetail(c.id)) },
                    onCopy = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("command", c.command))
                        android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
