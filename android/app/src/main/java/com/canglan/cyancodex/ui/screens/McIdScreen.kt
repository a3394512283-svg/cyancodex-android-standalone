package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.McRefItem
import com.canglan.cyancodex.data.McRefKind
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.copyToClipboard
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

/**
 * 快捷查询 ID
 *  · 输入中文名(或英文 id) 查出基岩版对应的 ID, 一键复制
 *  · 数据来自平台内置的《我的世界》基岩版参考表 (物品/方块/实体/附魔/药水效果/配方... 7977 条)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McIdScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var q by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }          // "" = 全部
    var kinds by remember { mutableStateOf<List<McRefKind>>(emptyList()) }
    var list by remember { mutableStateOf<List<McRefItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun doSearch() {
        val kw = q.trim()
        if (kw.isBlank()) { msg = "请输入中文名，例如：钻石剑 / 锋利 / 铜粒"; return }
        scope.launch {
            loading = true; msg = ""
            val r = safeApi { ApiClient.get().mcRefSearch(kw, kind.ifBlank { null }, 40) }
            loading = false
            searched = true
            if (r.ok && r.data != null) list = r.data.list else msg = r.error ?: "查询失败"
        }
    }

    LaunchedEffect(Unit) {
        val r = safeApi { ApiClient.get().mcRefKinds() }
        if (r.ok && r.data != null) kinds = r.data.list
    }

    Scaffold(
        topBar = { AppTopBar(title = "快捷查询 ID", icon = Icons.Default.Search, onBack = { nav.popBackStack() }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = q,
                        onValueChange = { q = it },
                        label = { Text("输入中文名 / 英文 ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            IconButton(onClick = { doSearch() }) { Icon(Icons.Default.Search, "查询") }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { doSearch() }),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 分类筛选
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassFilterChip(selected = kind.isBlank(), onClick = { kind = ""; if (searched) doSearch() },
                        label = { Text("全部", style = MaterialTheme.typography.labelSmall) })
                    kinds.take(14).forEach { k ->
                        GlassFilterChip(selected = kind == k.kind, onClick = { kind = k.kind; if (searched || q.isNotBlank()) doSearch() },
                            label = { Text(k.kind, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                if (msg.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(msg, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }

            when {
                loading && list.isEmpty() -> LoadingBox()
                !searched -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("输入中文就能查到 ID", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text("例：钻石剑 → diamond_sword　锋利 → sharpness\n共 ${kinds.sumOf { it.count }} 条基岩版参考数据",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有查到「${q.trim()}」\n换个说法再试试，或换个分类",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text("共 ${list.size} 条结果 · 点 ID 复制",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(list) { it ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(it.name, style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Surface(shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text(it.kind, style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }
                                if (it.tag.isNotBlank()) {
                                    Text(it.tag, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(6.dp))
                                it.ids.filter { x -> x.isNotBlank() }.forEach { id ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            copyToClipboard(ctx, id, "已复制 ID：$id")
                                        }.padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(id, style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f))
                                        Icon(Icons.Default.ContentCopy, "复制", Modifier.size(15.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
