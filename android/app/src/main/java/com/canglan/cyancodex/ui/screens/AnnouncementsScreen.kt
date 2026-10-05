package com.canglan.cyancodex.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.Announcement
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.data.Updater
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AnnBadge
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.EmptyState
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.safeApi
import com.canglan.cyancodex.ui.components.versionNewer
import kotlinx.coroutines.launch

/** 全部公告列表: 更新公告点击自动下载安装, 普通公告点击查看详情 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementsScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<Announcement>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var showDetail by remember { mutableStateOf<Announcement?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var dlProgress by remember { mutableStateOf(-1f) }

    LaunchedEffect(Unit) {
        val r = safeApi { ApiClient.get().announcements("all") }
        if (r.ok) {
            list = r.data ?: emptyList()
            list.maxOfOrNull { it.id }?.let { Session.lastSeenAnnouncementId = it }
        } else {
            err = r.error ?: "加载失败"
        }
        loading = false
    }

    fun handleTap(a: Announcement) {
        if (a.type == "activity") {
            // 活动公告 (开学季福利等): 跳转活动页
            nav.navigate(Routes.SCHOOL_SHARE)
        } else if (a.type == "update" && versionNewer(a.version)) {
            // 更新公告: 自动下载并安装
            scope.launch {
                downloading = true
                dlProgress = 0f
                val url = Updater.resolveUrl(a.version)
                if (url == null) {
                    downloading = false
                    Toast.makeText(ctx, "获取更新地址失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val file = Updater.download(ctx, url) { done, total ->
                    dlProgress = if (total > 0) done.toFloat() / total else -2f
                }
                downloading = false
                if (file == null) {
                    Toast.makeText(ctx, "下载失败，已尝试用浏览器打开下载页", Toast.LENGTH_SHORT).show()
                    Updater.openInBrowser(ctx, url)
                } else if (!Updater.install(ctx, file)) {
                    Toast.makeText(ctx, "安装失败，已尝试用浏览器打开下载页", Toast.LENGTH_LONG).show()
                    Updater.openInBrowser(ctx, url)
                }
            }
        } else {
            showDetail = a
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "公告", icon = Icons.Default.Campaign, onBack = { nav.popBackStack() }) },
    ) { pad ->
        when {
            loading -> LoadingBox(Modifier.padding(pad))
            list.isEmpty() -> EmptyState(if (err.isNotBlank()) err else "暂无公告", Modifier.padding(pad))
            else -> LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { a ->
                    Card(
                        onClick = { handleTap(a) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AnnBadge(a.type)
                                Spacer(Modifier.width(8.dp))
                                Text(a.title, style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f), maxLines = 1)
                                Text(a.createdAt?.take(10) ?: "", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(a.content, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
                            if (a.type == "update") {
                                Spacer(Modifier.height(4.dp))
                                Text(if (versionNewer(a.version)) "点击自动下载更新 →" else "已是最新版本",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (versionNewer(a.version)) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    // 普通公告详情
    showDetail?.let { a ->
        AlertDialog(
            onDismissRequest = { showDetail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnnBadge(a.type)
                    Spacer(Modifier.width(8.dp))
                    Text(a.title)
                }
            },
            // 公告可以很长: 内容区必须能滚动, 否则下面的字永远看不到 (意见 #93)
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) { Text(a.content) }
            },
            confirmButton = { TextButton(onClick = { showDetail = null }) { Text("知道了") } },
        )
    }

    // 自动更新下载进度
    if (downloading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载更新包") },
            text = {
                Column {
                    Text(if (dlProgress == -2f) "下载中…" else "下载中 ${(dlProgress * 100).toInt()}%")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = if (dlProgress > 0f) dlProgress else 0f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {},
        )
    }
}
