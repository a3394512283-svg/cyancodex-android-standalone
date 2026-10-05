package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.PointsLogItem
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.safeApi

/** 积分明细: 查看积分增加/消耗记录 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsLogScreen(nav: NavHostController) {
    var list by remember { mutableStateOf<List<PointsLogItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = safeApi { ApiClient.get().pointsLog(1, 50) }
        if (r.ok) list = r.data?.list ?: emptyList() else msg = r.error ?: "加载失败"
        loading = false
    }

    Scaffold(topBar = {
        AppTopBar(
            title = "积分明细",
            icon = Icons.Default.AccountBalanceWallet,
            onBack = { nav.popBackStack() },
            // 积分不够时最自然的诉求就是"怎么弄积分"，入口直接放在这里
            actions = {
                TextButton(onClick = { nav.navigate(Routes.RECHARGE) }) { Text("充值") }
            },
        )
    }) { pad ->
        if (loading && list.isEmpty()) { LoadingBox(Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msg.isNotBlank()) item { Text(msg, color = MaterialTheme.colorScheme.error) }
            if (list.isEmpty()) item {
                Text("暂无积分记录\n签到、AI 解锁、管理员调整都会记录在这里", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(list, key = { it.id }) { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.reason, style = MaterialTheme.typography.titleSmall)
                            Text("${p.createdAt?.take(16)?.replace("T", " ") ?: ""} · 余额 ${p.balance}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (p.delta >= 0) "+${p.delta}" else "${p.delta}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (p.delta >= 0) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
