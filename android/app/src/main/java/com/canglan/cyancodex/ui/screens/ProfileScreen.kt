package com.canglan.cyancodex.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.ime.CyanCodexImeSettings
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.LocalGlassBottomInset
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.AvatarIcon
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val user = AppState.user
    var unread by remember { mutableStateOf(-1) }   // -1 = 未加载
    // 积分转账 (意见 #42)
    var showTransfer by remember { mutableStateOf(false) }
    var tfTarget by remember { mutableStateOf("") }
    var tfAmount by remember { mutableStateOf("") }
    var tfBusy by remember { mutableStateOf(false) }
    var tfMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (AppState.token != null) {
            val r = safeApi { ApiClient.get().me() }
            if (r.ok) r.data?.let { AppState.patchUser(points = it.points, aiUnlocked = it.aiUnlocked, nickname = it.nickname) }
            val u = safeApi { ApiClient.get().unreadMessages() }
            if (u.ok) unread = u.data?.count ?: 0
        } else unread = 0
    }

    val unreadDesc = if (unread > 0) "$unread 条未读 · 系统通知 / 管理员消息" else "系统通知 / 管理员消息"

    // 账号中心概览: 设备数 / 绑定数 (用于菜单副标题)
    var deviceCount by remember { mutableStateOf(-1) }
    var bindingCount by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        if (AppState.token != null) {
            val o = safeApi { ApiClient.get().accountOverview() }
            if (o.ok) {
                deviceCount = o.data?.deviceCount ?: -1
                bindingCount = o.data?.bindingCount ?: -1
            }
        }
    }
    val idDesc = buildString {
        append("查看账号 ID、注册与最近登录")
        if (deviceCount > 0) append(" · 最近 ${deviceCount} 台设备")
    }
    val bindDesc = when {
        bindingCount > 0 -> "已绑定 $bindingCount 个账号 · QQ 机器人（微信后续开放）"
        bindingCount == 0 -> "未绑定 · 可在 QQ 里查积分 / 签到 / 搜指令"
        else -> "QQ 机器人绑定状态（微信后续开放）"
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "我的", icon = Icons.Default.Person)
        },
    ) { pad ->
        // padding 放在 verticalScroll 之后 = 内容底部留白(滚动区能铺到屏幕底), 而不是缩视口
        Column(
            Modifier.padding(pad).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalGlassBottomInset.current),
        ) {
            // 用户卡片
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarIcon(user?.avatar ?: "person", 54.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        if (user == null) {
                            Text("未登录", style = MaterialTheme.typography.titleMedium)
                            Text("登录后可上传指令、同步收藏、使用积分", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(user.nickname.ifBlank { user.username }, style = MaterialTheme.typography.titleMedium)
                            Text("@${user.username} · 沧蓝工坊", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!user.createdAt.isNullOrBlank()) {
                                val days = com.canglan.cyancodex.ui.components.daysSince(user.createdAt)
                                if (days >= 0) {
                                    Spacer(Modifier.height(2.dp))
                                    Text("${user.createdAt.take(10)} 注册 · 已陪伴 CyanCodex ${days} 天",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondary) {
                                    Text(" 积分 ${user.points} ", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondary)
                                }
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text(if (user.aiUnlocked) " AI 已解锁 " else " AI 未解锁 ", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    if (user == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GlassActionButton(filled = true, onClick = { nav.navigate(Routes.LOGIN) }) { Text("登录") }
                            GlassActionButton(onClick = { nav.navigate(Routes.LOGIN) }) { Text("注册") }
                        }
                    }
                }
            }

            // 菜单
            MenuRow(Icons.Default.Person, "个人中心", "修改头像 / 性别 / 昵称(必填)") { nav.navigate(Routes.PROFILE_EDIT) }
            MenuRow(Icons.Default.Badge, "我的 ID 与登录设备", idDesc) { nav.navigate(Routes.MY_ID) }
            MenuRow(Icons.Default.Link, "绑定账号", bindDesc) { nav.navigate(Routes.BINDINGS) }
            MenuRow(Icons.Default.EventAvailable, "每日签到", "每日签到领积分，积分可解锁 AI") { nav.navigate(Routes.CHECKIN) }
            MenuRow(Icons.Default.PlayCircle, "看广告得积分", "看完一条央视公益广告得积分，每天最多 20 次") { nav.navigate(Routes.AD_REWARD) }
            MenuRow(Icons.Default.Search, "快捷查询 ID", "输入中文查基岩版 ID（物品/方块/附魔…7977 条）") { nav.navigate(Routes.MC_ID) }
            MenuRow(Icons.Default.Favorite, "宝贝回家", "公益寻亲信息，让每个页面都是传播希望的窗口") { nav.navigate(Routes.BABY_HOME) }
            MenuRow(Icons.Default.AccountBalanceWallet, "积分明细", "查看积分增加 / 消耗记录") { nav.navigate(Routes.POINTS_LOG) }
            MenuRow(Icons.Default.Send, "积分转账", "转给好友，手续费 20%") { showTransfer = true }
            MenuRow(Icons.Default.MailOutline, "站内信", unreadDesc) { nav.navigate(Routes.MESSAGES) }
            MenuRow(Icons.Default.Lock, "修改密码", "更换登录密码") { nav.navigate(Routes.CHANGE_PASSWORD) }
            MenuRow(Icons.Default.AlternateEmail, "修改邮箱", "更换绑定邮箱(需验证码)") { nav.navigate(Routes.CHANGE_EMAIL) }
            MenuRow(Icons.Default.AutoAwesome, "AI Agent", "解锁状态 / 对话 / API 配置") { nav.navigate(Routes.AI) }
            MenuRow(Icons.Default.List, "生成历史", "T显/穷举/字幕/音乐记录") { nav.navigate(Routes.HISTORY) }
            MenuRow(Icons.Default.Star, "我的上传", "查看我在指令库上传的内容") {
                nav.navigate(Routes.LIBRARY)
            }
            MenuRow(Icons.Default.Keyboard, "快捷指令管理", "输入法与悬浮窗使用的快捷指令") {
                ctx.startActivity(Intent(ctx, CyanCodexImeSettings::class.java))
            }
            MenuRow(Icons.Default.Settings, "优化设置", "性能优化 / API 地址 / 输入法 / 悬浮窗") { nav.navigate(Routes.SETTINGS) }
            MenuRow(Icons.Default.Info, "关于", "沧蓝工坊 · CyanCodex") { nav.navigate(Routes.ABOUT) }

            if (user != null) {
                GlassActionButton(filled = true,
                    onClick = {
                        // 本地立即退出, 服务端令牌吊销由 AppState 在应用级协程里通知
                        AppState.logout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    // 玻璃路径读不到 ButtonColors(内部 API 是 internal), 定制配色要再给一份
                    glassTint = MaterialTheme.colorScheme.error,
                    glassContentColor = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text("退出登录") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // 积分转账对话框 (意见 #42, 手续费 20%)
    if (showTransfer) {
        AlertDialog(
            onDismissRequest = { showTransfer = false; tfMsg = "" },
            title = { Text("积分转账") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前积分：${user?.points ?: 0} · 手续费 20%（转 100 分对方到账 80 分）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = tfTarget, onValueChange = { tfTarget = it },
                        label = { Text("收款用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = tfAmount, onValueChange = { tfAmount = it.filter { ch -> ch.isDigit() }.take(6) },
                        label = { Text("转账积分") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    if (tfMsg.isNotBlank()) {
                        Text(tfMsg, color = if (tfMsg.startsWith("✓")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !tfBusy && tfTarget.isNotBlank() && (tfAmount.toIntOrNull() ?: 0) >= 1, onClick = {
                    scope.launch {
                        tfBusy = true; tfMsg = ""
                        val r = safeApi {
                            ApiClient.get().pointsTransfer(mapOf("target_username" to tfTarget.trim(), "amount" to tfAmount))
                        }
                        tfBusy = false
                        if (r.ok) {
                            tfMsg = "✓ 转账成功（手续费 ${r.data?.fee ?: 0} 分）"
                            AppState.patchUser(points = r.data?.balance ?: user?.points)
                            tfTarget = ""; tfAmount = ""
                        } else {
                            tfMsg = r.error ?: "转账失败"
                        }
                    }
                }) { Text(if (tfBusy) "转账中…" else "转账") }
            },
            dismissButton = { TextButton(onClick = { showTransfer = false; tfMsg = "" }) { Text("关闭") } },
        )
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
