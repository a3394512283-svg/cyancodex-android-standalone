package com.canglan.cyancodex.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.RechargeOrder
import com.canglan.cyancodex.data.RechargePlan
import com.canglan.cyancodex.data.RechargeStatus
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

/**
 * 积分充值（爱发电）
 *
 * 流程刻意做成「App 只负责告诉你怎么付 + 付完怎么领」，不在这里做支付：
 *   1. 选一个积分包 → 复制备注（CYANUID-你的ID）→ 跳爱发电下单（备注栏粘贴）
 *   2. 付完回来点「已付款，去领取」：默认自动到账（备注填对了的话）；
 *      备注没写对就填订单号领取，服务端会核对爱发电那边的订单状态。
 *
 * 为什么要有"复制备注"这一步：爱发电的订单里**没有我们的账号**，
 * 备注是唯一能把钱和账号对上的线索。填对一次之后账号就绑定了，以后自动到账。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RechargeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var plans by remember { mutableStateOf<List<RechargePlan>>(emptyList()) }
    var st by remember { mutableStateOf<RechargeStatus?>(null) }
    var howto by remember { mutableStateOf<List<String>>(emptyList()) }
    var configured by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var orderNo by remember { mutableStateOf("") }
    var showClaim by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            val p = safeApi { ApiClient.get().rechargePlans() }
            if (p.ok && p.data != null) {
                plans = p.data.plans
                howto = p.data.howto
                configured = p.data.configured
            } else {
                msg = p.error ?: "加载失败"
            }
            val s = safeApi { ApiClient.get().rechargeStatus() }
            if (s.ok) st = s.data
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    /** 付完款：先让服务端去爱发电拉一次订单（回调可能慢几秒），再刷新状态 */
    fun refreshAndCheck() {
        scope.launch {
            busy = true
            msg = ""
            val r = safeApi { ApiClient.get().rechargeRefresh() }
            busy = false
            if (r.ok && r.data != null) {
                st = r.data
                AppState.patchUser(points = r.data.points)
                val n = r.data.orders.count { it.status == "credited" }
                msg = if (n > 0) "已同步订单，当前余额 ${r.data.points} 分" else "还没有查到新的已支付订单"
            } else {
                msg = r.error ?: "刷新失败"
            }
        }
    }

    fun claim() {
        val no = orderNo.trim()
        if (no.isEmpty()) { msg = "请先填爱发电订单号"; return }
        scope.launch {
            busy = true
            msg = ""
            val r = safeApi { ApiClient.get().rechargeClaim(mapOf("order_no" to no)) }
            busy = false
            if (r.ok && r.data != null) {
                msg = "领取成功：+${r.data.points} 积分"
                orderNo = ""
                showClaim = false
                load()
            } else {
                msg = r.error ?: "领取失败"
            }
        }
    }

    Scaffold(topBar = {
        AppTopBar(
            title = "积分充值",
            icon = Icons.Default.AccountBalanceWallet,
            onBack = { nav.popBackStack() }
        )
    }) { pad ->
        if (loading) {
            LoadingBox(Modifier.padding(pad))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 余额 ----------
            Card(shape = RoundedCornerShape(14.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("当前积分", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${st?.points ?: AppState.user?.points ?: 0}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (st?.bound == true) {
                        AssistChip(onClick = {}, label = { Text("爱发电已绑定") })
                    }
                }
            }

            if (!configured) {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("充值通道正在配置中", fontWeight = FontWeight.Bold)
                        Text(
                            "暂时还不能充值，请稍后再来。这段时间可以用签到、看公益广告的方式获取积分。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---------- 怎么充（三步） ----------
            if (howto.isNotEmpty()) {
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("怎么充值", fontWeight = FontWeight.Bold)
                        howto.forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    st?.remark ?: "登录后显示",
                                    Modifier.padding(10.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = {
                                val text = st?.remark
                                if (!text.isNullOrBlank()) {
                                    clipboard.setText(AnnotatedString(text))
                                    msg = "备注已复制，粘到爱发电订单的备注栏里"
                                }
                            }) { Icon(Icons.Default.ContentCopy, contentDescription = "复制备注") }
                        }
                        Text(
                            "把它粘到爱发电下单页的「备注」里。填对一次，以后充值自动到账。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---------- 积分包 ----------
            if (plans.isEmpty() && configured) {
                Text("还没有可购买的积分包，稍后再来看看。", style = MaterialTheme.typography.bodySmall)
            }
            plans.forEach { p ->
                Card(shape = RoundedCornerShape(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title.ifBlank { "${p.totalPoints} 积分" }, fontWeight = FontWeight.Bold)
                            Text(
                                buildString {
                                    append("¥${p.priceYuan}  ·  到账 ${p.totalPoints} 积分")
                                    if (p.bonusPoints > 0) append("（含赠送 ${p.bonusPoints}）")
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (p.note.isNotBlank()) {
                                Text(p.note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        GlassActionButton(filled = true,
                            onClick = {
                                // 先复制备注再跳转：爱发电那边要手动粘贴，先复制好最省事
                                val text = st?.remark
                                if (!text.isNullOrBlank()) clipboard.setText(AnnotatedString(text))
                                if (p.itemUrl.isBlank()) {
                                    msg = "这个积分包还没配购买链接，请联系管理员"
                                } else {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(p.itemUrl))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.onFailure { msg = "打不开链接：${it.message}" }
                                    msg = "备注已复制。付款后回到这里点「我已付款」"
                                }
                            },
                            enabled = configured && p.itemUrl.isNotBlank()
                        ) {
                            Text("去付款")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.OpenInNew, contentDescription = null, Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ---------- 已付款 ----------
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("付完款了？", fontWeight = FontWeight.Bold)
                    Row {
                        GlassActionButton(filled = true, onClick = { refreshAndCheck() }, enabled = !busy) {
                            Text(if (busy) "处理中…" else "我已付款，刷新看看")
                        }
                        Spacer(Modifier.width(8.dp))
                        GlassActionButton(onClick = { showClaim = !showClaim }, enabled = !busy) {
                            Text(if (showClaim) "收起" else "用订单号领取")
                        }
                    }
                    if (showClaim) {
                        Text(
                            "备注里忘了写账号时用这个：在爱发电「我的订单」复制订单号，粘到这里。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = orderNo,
                            onValueChange = { orderNo = it },
                            label = { Text("爱发电订单号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        GlassActionButton(filled = true, onClick = { claim() }, enabled = !busy) { Text("领取积分") }
                    }
                }
            }

            if (msg.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            }

            // ---------- 我的充值记录 ----------
            val orders = st?.orders ?: emptyList()
            if (orders.isNotEmpty()) {
                Text("我的充值记录", fontWeight = FontWeight.Bold)
                orders.forEach { o -> OrderRow(o) }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "说明：付款在爱发电完成，App 不接触你的支付信息；积分到账后可在「积分明细」里核对。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun OrderRow(o: RechargeOrder) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.planTitle.ifBlank { "积分充值" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(o.statusText, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "¥${o.priceYuan}  ·  ${if (o.points > 0) "+${o.points} 积分" else "—"}" +
                    (o.createdAt?.let { "  ·  " + it.replace('T', ' ').take(16) } ?: ""),
                style = MaterialTheme.typography.bodySmall
            )
            Text(o.orderNo, style = MaterialTheme.typography.bodySmall)
        }
    }
}
