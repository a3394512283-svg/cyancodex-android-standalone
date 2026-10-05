package com.canglan.cyancodex.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.BabyHomeItem
import com.canglan.cyancodex.data.BabyHomeResp
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.RemoteImage
import com.canglan.cyancodex.ui.components.TopBarAction
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

/**
 * 宝贝回家（公益寻亲）
 *  · 数据来源：宝贝回家寻子网（通过公益数据接口获取）
 *  · 拿不到数据时只展示公益提示与官方渠道，不编造任何寻亲信息
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BabyHomeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<BabyHomeResp?>(null) }
    var loading by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<BabyHomeItem?>(null) }

    fun load() {
        scope.launch {
            loading = true
            val r = safeApi { ApiClient.get().babyHome() }
            if (r.ok) data = r.data
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    fun openUrl(u: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "宝贝回家", icon = Icons.Default.Favorite, onBack = { nav.popBackStack() }, actions = {
                TopBarAction(onClick = { load() }) { Icon(Icons.Default.Refresh, "刷新", Modifier.size(20.dp)) }
            })
        },
    ) { pad ->
        if (loading && data == null) { LoadingBox(Modifier.padding(pad)); return@Scaffold }
        val d = data
        val rows = d?.items ?: emptyList()

        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("让每个页面都成为传播希望的窗口",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("本页信息来自「宝贝回家」寻子网，用于公益传播。如果发现疑似走失、被拐儿童，请立即拨打 110 报警，权威信息以警方发布为准。",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text("点开任意一条可以查看完整信息与原始登记页，也欢迎转发给更多人看到。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (rows.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (d?.keySet == false) "公益寻亲信息暂未开通" else "暂时没有拿到寻亲信息",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(d?.error?.takeIf { it.isNotBlank() } ?: "可直接前往宝贝回家寻子网查看与帮助。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                item {
                    Text("正在寻找的孩子 · ${rows.size} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(rows) { it ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().clickable { detail = it },
                    ) {
                        Row(Modifier.padding(12.dp)) {
                            if (it.photo.isNotBlank()) {
                                RemoteImage(it.photo, modifier = Modifier.size(84.dp), corner = 8.dp)
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(it.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    if (it.category.isNotBlank()) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.secondaryContainer) {
                                            Text(" ${it.category} ", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    }
                                }
                                if (it.gender.isNotBlank() || it.birthday.isNotBlank() || it.height.isNotBlank()) {
                                    Text(listOf(
                                        it.gender,
                                        if (it.birthday.isNotBlank()) "生日 ${it.birthday}" else "",
                                        if (it.height.isNotBlank()) "身高 ${it.height}" else "",
                                    ).filter { x -> x.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (it.lostTime.isNotBlank() || it.lostPlace.isNotBlank()) {
                                    Text("走失：${listOf(it.lostTime, it.lostPlace).filter { x -> x.isNotBlank() }.joinToString(" · ")}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (it.feature.isNotBlank()) {
                                    Text("特征：${it.feature}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }

            // 公益提示
            if (!(d?.tips.isNullOrEmpty())) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("公益提示", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            d!!.tips.forEach { t ->
                                Text("· $t", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassActionButton(filled = true, onClick = { openUrl(d?.site?.takeIf { it.isNotBlank() } ?: "https://www.baobeihuijia.com/") },
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("宝贝回家官网")
                    }
                    GlassActionButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:110"))) }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Phone, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("拨打 110")
                    }
                }
                Text(
                    "数据来源：${d?.source ?: "宝贝回家寻子网"}"
                        + (if (!d?.updatedAt.isNullOrBlank()) " · 更新于 ${d!!.updatedAt.replace("T", " ").take(16)}" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }

    // 详情
    detail?.let { it ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(it.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (it.photo.isNotBlank()) {
                        RemoteImage(it.photo, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), corner = 10.dp)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (it.gender.isNotBlank()) Text("性别：${it.gender}", style = MaterialTheme.typography.bodySmall)
                    if (it.birthday.isNotBlank()) Text("生日：${it.birthday}", style = MaterialTheme.typography.bodySmall)
                    if (it.height.isNotBlank()) Text("身高：${it.height}", style = MaterialTheme.typography.bodySmall)
                    if (it.category.isNotBlank()) Text("信息类别：${it.category}", style = MaterialTheme.typography.bodySmall)
                    if (it.lostTime.isNotBlank()) Text("走失时间：${it.lostTime}", style = MaterialTheme.typography.bodySmall)
                    if (it.lostPlace.isNotBlank()) Text("走失地点：${it.lostPlace}", style = MaterialTheme.typography.bodySmall)
                    if (it.feature.isNotBlank()) Text("体貌特征：${it.feature}", style = MaterialTheme.typography.bodySmall)
                    // 官方联系方式 (旧数据源会带, 新数据源没有就不显示这一块)
                    val hasContact = listOf(
                        it.contact.tel, it.contact.mobile, it.contact.email,
                        it.contact.address, it.contact.serviceTime, it.contact.qqGroup,
                    ).any { x -> x.isNotBlank() }
                    if (hasContact) {
                        Spacer(Modifier.height(8.dp))
                        Text("宝贝回家官方联系方式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        if (it.contact.tel.isNotBlank()) Text("座机：${it.contact.tel}", style = MaterialTheme.typography.bodySmall)
                        if (it.contact.mobile.isNotBlank()) Text("手机：${it.contact.mobile}", style = MaterialTheme.typography.bodySmall)
                        if (it.contact.email.isNotBlank()) Text("邮箱：${it.contact.email}", style = MaterialTheme.typography.bodySmall)
                        if (it.contact.address.isNotBlank()) Text("地址：${it.contact.address}", style = MaterialTheme.typography.bodySmall)
                        if (it.contact.serviceTime.isNotBlank()) Text("服务时间：${it.contact.serviceTime}", style = MaterialTheme.typography.bodySmall)
                        if (it.contact.qqGroup.isNotBlank()) Text("QQ 接待群：${it.contact.qqGroup}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (it.detailUrl.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        GlassActionButton(onClick = { openUrl(it.detailUrl) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("查看宝贝回家原始登记信息")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("如发现线索请先拨打 110，或通过宝贝回家官方渠道提供信息。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val txt = listOf(
                        it.name, it.gender, it.birthday,
                        if (it.height.isNotBlank()) "身高 ${it.height}" else "",
                        it.lostTime, it.lostPlace, it.feature,
                    ).filter { x -> x.isNotBlank() }.joinToString(" · ")
                    val link = it.detailUrl.ifBlank { "https://www.baobeihuijia.com/" }
                    runCatching {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "【宝贝回家·公益寻亲】$txt\n详情：$link\n官网：https://www.baobeihuijia.com/")
                        }, "分享这条寻亲信息"))
                    }
                }) { Text("分享") }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
        )
    }
}

/** 全局页脚: 每页底部的宝贝回家公益信息条 (点击进入详情页) */
@Composable
fun BabyHomeFooter(onClick: () -> Unit) {
    var data by remember { mutableStateOf<BabyHomeResp?>(null) }
    var idx by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val r = runCatching { safeApi { ApiClient.get().babyHome() } }.getOrNull()
            if (r != null && r.ok) data = r.data
            kotlinx.coroutines.delay(10 * 60 * 1000L)
        }
    }
    val items = data?.items ?: emptyList()
    LaunchedEffect(items.size) {
        if (items.size > 1) {
            while (true) {
                kotlinx.coroutines.delay(6000)
                idx = (idx + 1) % items.size
            }
        }
    }
    val text = if (items.isNotEmpty()) {
        val it = items[idx.coerceIn(0, items.size - 1)]
        "宝贝回家 · ${it.name} · ${listOf(it.lostTime, it.lostPlace).filter { x -> x.isNotBlank() }.joinToString(" · ")}"
    } else {
        "宝贝回家 · 一起帮走失的孩子回家（点击查看）"
    }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Favorite, null, Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("查看 >", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}
