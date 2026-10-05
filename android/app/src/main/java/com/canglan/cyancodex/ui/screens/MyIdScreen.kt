package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.AccountOverview
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.LoginDevice
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.CopyButton
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.SectionTitle
import com.canglan.cyancodex.ui.components.TopBarAction
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

/**
 * 我的 ID 与登录设备
 *  · 账号 ID (可复制) / 用户名 / 注册时间 / 最近登录
 *  · 当前设备 + 最近登录过的设备列表 (只本人可见)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyIdScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var ov by remember { mutableStateOf<AccountOverview?>(null) }
    var devices by remember { mutableStateOf<List<LoginDevice>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var renamingBusy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        loading = true; err = ""
        val o = safeApi { ApiClient.get().accountOverview() }
        if (o.ok) ov = o.data else err = o.error ?: "加载失败"
        val d = safeApi { ApiClient.get().accountDevices(20) }
        if (d.ok) devices = d.data?.list ?: emptyList()
        loading = false
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "我的 ID", icon = Icons.Default.Badge, onBack = { nav.popBackStack() }, actions = {
                TopBarAction(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "刷新") }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (loading && ov == null) {
                LoadingBox()
            } else if (ov == null) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(err.ifBlank { "加载失败，请检查网络" }, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    GlassActionButton(filled = true, onClick = { tick++ }) { Text("重试") }
                }
            } else {
                val o = ov!!
                // ---- 账号 ID 卡片 ----
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("账号 ID", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${o.id}", style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(10.dp))
                            CopyButton(text = "${o.id}", hint = "已复制账号 ID")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(if (o.nickname.isBlank()) "@${o.username}" else "${o.nickname} · @${o.username}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (o.email.isNotBlank()) {
                            Text("邮箱 ${o.email}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }

                // ---- 账号信息 ----
                SectionTitle("账号信息")
                InfoCard(
                    listOf(
                        "注册时间" to o.createdAt.orEmpty().ifBlank { "—" },
                        "最近登录" to o.lastLoginAt.orEmpty().ifBlank { "—" },
                        "积分" to "${o.points}",
                        "两步验证" to if (o.totpEnabled) "已开启" else "未开启",
                        "身份" to buildString {
                            append(if (o.isOfficial) "官方" else if (o.isReviewer) "审核员" else "普通用户")
                            append(" · ")
                            append(if (o.status == "active") "正常" else o.status)
                        },
                    )
                )

                // ---- 当前设备 ----
                SectionTitle("当前设备（本机）")
                val cur = o.currentDevice
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(cur?.deviceName?.ifBlank { Session.deviceName } ?: Session.deviceName,
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            CurrentBadge()
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("IP ${cur?.lastIp?.ifBlank { o.currentIp } ?: o.currentIp}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("最近活跃 ${cur?.lastSeenAt.orEmpty().ifBlank { "—" }} · 登录 ${cur?.loginCount ?: 1} 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("App 版本 v${com.canglan.cyancodex.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        GlassActionButton(onClick = {
                            nameInput = cur?.deviceName?.ifBlank { Session.deviceName } ?: Session.deviceName
                            renaming = true
                        }) {
                            Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("给这台设备起个名字")
                        }
                    }
                }

                // ---- 设备列表 ----
                SectionTitle("最近登录设备（${o.deviceCount} 台）")
                if (devices.isEmpty()) {
                    Text("暂无记录", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp))
                } else {
                    devices.forEach { d -> DeviceCard(d) }
                }

                Text("以上信息仅你自己可见。发现不认识的设备，请立刻到「修改密码」更换密码。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { if (!renamingBusy) renaming = false },
            title = { Text("设备名称") },
            text = {
                Column {
                    Text("给这台设备起个名字，之后在设备列表里就能一眼认出它（如「我的手机」）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = nameInput, onValueChange = { nameInput = it.take(20) },
                        label = { Text("设备名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (toast.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(toast, style = MaterialTheme.typography.labelSmall,
                            color = if (toast.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !renamingBusy && nameInput.isNotBlank(), onClick = {
                    scope.launch {
                        renamingBusy = true; toast = ""
                        Session.deviceNameOverride = nameInput.trim()
                        val r = safeApi { ApiClient.get().renameDevice(mapOf("name" to nameInput.trim())) }
                        renamingBusy = false
                        if (r.ok) {
                            toast = "✓ 已保存"
                            renaming = false
                            tick++
                        } else {
                            toast = r.error ?: "保存失败"
                        }
                    }
                }) { Text(if (renamingBusy) "保存中…" else "保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = false; toast = "" }) { Text("取消") } },
        )
    }
}

@Composable
private fun CurrentBadge() {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
        Text(" 本机 ", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(d: LoginDevice) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.deviceName.ifBlank { "未知设备" },
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (d.current) CurrentBadge()
            }
            Spacer(Modifier.height(4.dp))
            Text("IP ${d.lastIp.ifBlank { "未知" }} · 最近活跃 ${d.lastSeenAt.orEmpty().ifBlank { "—" }}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("登录 ${d.loginCount} 次 · 首次 ${d.firstSeenAt.orEmpty().ifBlank { "—" }}" +
                    if (d.appVersion.isNotBlank()) " · v${d.appVersion}" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
