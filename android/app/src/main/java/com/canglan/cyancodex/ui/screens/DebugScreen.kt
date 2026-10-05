package com.canglan.cyancodex.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.DebugStore
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassSwitch

/**
 * 调试面板（隐藏）。
 *
 * 入口：关于页连点版本号 7 次解锁 → 设置页最底部「调试」。
 *
 * 三块：
 * 1. 服务器地址（含端口）—— 指向本地 / 局域网 / 自建后端
 * 2. 日志 —— 开关 + 查看 / 复制 / 清空
 * 3. 网络请求 —— 每条请求的 method / 状态码 / 耗时 / URL / 错误
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(nav: NavHostController) {
    val ctx = LocalContext.current

    // 0=服务器 1=日志 2=请求 3=Cookie
    var tab by remember { mutableStateOf(0) }
    // ★ 总开关状态 —— 提到页面级: 它一变, 上面那条警告条和下面各 tab 的生效与否都要跟着变
    var master by remember { mutableStateOf(DebugStore.masterOn) }

    Scaffold(
        topBar = {
            AppTopBar(title = "调试面板", icon = Icons.Default.BugReport, onBack = { nav.popBackStack() })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            MasterSwitchCard(ctx, master) { master = it }

            // 总开关关掉时才显示的提示条 —— 告诉用户"为什么下面改了没反应"。
            // 光把开关关掉不解释, 用户会以为面板坏了。
            if (!master) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("调试能力已关闭：服务器地址、路径改写、Cookie 注入、日志记录全部暂停",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("服务器") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("日志 (${DebugStore.logs.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("请求 (${DebugStore.requests.size})") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Cookie") })
            }

            when (tab) {
                0 -> ServerTab(ctx)
                1 -> LogTab(ctx)
                2 -> RequestTab(ctx)
                else -> CookieTab(ctx)
            }
        }
    }
}

// ======================= 调试总开关（仿「开发者选项」） =======================

/**
 * **调试总开关** —— 仿安卓「开发者选项」页面顶部那个开关 (2026-10-05)。
 *
 * 用户原话:「调试改成类似安卓开发者设置一样有个关闭开关」。
 *
 * ★ 它与"解锁入口"是**两层**开关, 和安卓完全一致:
 *   - 解锁(关于页连点 7 次) = "我能看到开发者选项这个菜单项";
 *   - 本开关 = "这些能力**当下**要不要真的生效"。
 *   之前只有前者, 一旦解锁所有调试能力就永久常开、关不掉 —— 用户要的正是后者。
 *
 * ⚠️ 关掉之后的自检: 面板本身**仍然能进**(否则用户就再也开不回来了 —— 安卓也是这个逻辑)。
 */
@Composable
private fun MasterSwitchCard(ctx: Context, on: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(10.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BugReport, null, Modifier.size(18.dp),
                    tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("调试模式", style = MaterialTheme.typography.titleSmall,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                GlassSwitch(checked = on, onCheckedChange = {
                    onChange(it)
                    DebugStore.masterOn = it
                    // ⚠️ 地址 / 路径改写 / Cookie 注入都发生在**构建客户端**的时候,
                    //   开关一变必须重建, 否则要等下次冷启动才生效。
                    ApiClient.reset()
                    DebugStore.log("Debug", "调试模式 -> " + if (it) "开" else "关")
                    Toast.makeText(
                        ctx,
                        if (it) "调试能力已启用（重启 App 后完全生效）"
                        else "调试能力已关闭，已恢复默认行为",
                        Toast.LENGTH_SHORT,
                    ).show()
                })
            }
            Text(
                if (on) "服务器地址 / 路径改写 / Cookie 注入 / 日志记录 均已启用。"
                else "全部暂停：App 走内置默认地址，不注入 Cookie，不记录日志。",
                style = MaterialTheme.typography.labelSmall,
                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ======================= Cookie =======================

/**
 * **Cookie 标签页** (2026-10-05) —— 用户原话:「导出 cookie 在调试里面」。
 *
 * 四块:
 * 1. **注入** —— 直接在这里粘 cookie 保存 (2026-10-05 追加: 用户说「开调试模式就直开」,
 *    那调试面板自身当然要能输入, 不该只留登录页那个连点入口);
 * 2. **状态** —— 有没有注入、多少字符(永不显示原文, 除非用户点导出);
 * 3. **导出** —— 点了才把原文拷进剪贴板(带二次确认, 见按钮实现);
 * 4. **清除** —— 只清 cookie, 不动 token/用户信息。
 */
@Composable
private fun CookieTab(ctx: Context) {
    // 用一个 tick 触发重算: 清除后要立刻反映到 UI 上
    var tick by remember { mutableStateOf(0) }
    val status = remember(tick) { DebugStore.cookieStatusText() }
    val has = remember(tick) { runCatching { Session.hasCookie() }.getOrDefault(false) }
    var input by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("注入 Cookie", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text("粘贴浏览器里已登录的 Cookie（整行 `Cookie: a=1; b=2` 或多行 `a=1` 都能吃下）。" +
                    "保存后立即生效，无需重启。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Cookie") },
                    placeholder = { Text("a=1; b=2") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    maxLines = 6,
                    // ★ 密码型: 防肩窥与截屏外泄。这是账号级凭证, 不必常显。
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassActionButton(
                        onClick = {
                            val clean = Session.sanitizeCookie(input)
                            if (clean.isBlank()) {
                                Toast.makeText(ctx, "Cookie 为空（需要形如 a=1; b=2）", Toast.LENGTH_SHORT).show()
                            } else {
                                Session.cookie = clean
                                ApiClient.reset()          // cookie 拦截器判 active, 重建一次最稳
                                input = ""
                                tick++
                                DebugStore.log("Debug", "Cookie 已注入（${clean.length} 字符）")
                                Toast.makeText(ctx, "Cookie 已保存并生效", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存")
                    }
                    GlassActionButton(
                        onClick = {
                            Session.clearCookie()
                            ApiClient.reset()
                            tick++
                            DebugStore.log("Debug", "Cookie 已清除")
                            Toast.makeText(ctx, "已清除注入的 Cookie", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = has,
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("清除")
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("注入状态", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (has) Icons.Default.CheckCircle else Icons.Default.Lock, null,
                        tint = if (has) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                Text("生效条件：调试模式开着（见顶部开关）且请求发往本 App 的官方服务器域名。\n" +
                    "调试模式关掉后：Cookie 不再注入，登录页的 Cookie 入口也会同时消失。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("导出 / 清除", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text("「导出」会把注入的 Cookie 原文复制到剪贴板（换设备、贴进浏览器调试用）。\n" +
                    "「清除」只清 Cookie，不影响登录状态与用户信息。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassActionButton(
                        onClick = {
                            val ck = DebugStore.exportCookie()
                            if (ck.isBlank()) {
                                Toast.makeText(ctx, "还没有注入 Cookie", Toast.LENGTH_SHORT).show()
                            } else {
                                copyToClipboard(ctx, ck, "Cookie 已复制到剪贴板")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = has,
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出 Cookie")
                    }
                    GlassActionButton(
                        onClick = {
                            Session.clearCookie()
                            tick++
                            DebugStore.log("Debug", "Cookie 已清除")
                            Toast.makeText(ctx, "已清除注入的 Cookie", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = has,
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("清除")
                    }
                }
            }
        }

        Text("提示：Cookie 等同于完整账号权限。导出后请勿粘贴到公开渠道；" +
            "日志与请求记录里**不会**出现 Cookie 原文。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ======================= 服务器地址 =======================

@Composable
private fun ServerTab(ctx: Context) {
    var input by remember { mutableStateOf(DebugStore.customBaseUrl) }
    // ★ 用 key 触发重算: 保存后 savedUrl 变化 -> effective 重新读取
    var savedUrl by remember { mutableStateOf(DebugStore.customBaseUrl) }
    val effective = remember(savedUrl) { DebugStore.currentBaseUrlDisplay() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前生效地址", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(effective, style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
                Text(
                    if (Session.usingCustomBaseUrl) "来源：调试面板自定义" else "来源：内置默认（未自定义）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("自定义服务器地址", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text("完整地址，含协议与端口。例如 http://192.168.1.5:8080 或 http://10.0.2.2:3000\n" +
                    "留空 = 用内置默认地址。结尾斜杠会自动补。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("baseUrl") },
                    placeholder = { Text("http://192.168.1.5:8080") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    textStyle = MaterialTheme.typography.bodySmall,
                )

                // 快捷填充：模拟器里的宿主机
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { input = "http://10.0.2.2:8080" },
                        label = { Text("模拟器宿主", fontSize = 12.sp) })
                    AssistChip(onClick = { input = "http://127.0.0.1:8080" },
                        label = { Text("本机", fontSize = 12.sp) })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassActionButton(
                        onClick = {
                            val v = input.trim()
                            // 简单校验：非空时必须像 http(s)://host[:port]
                            if (v.isNotBlank() && !v.startsWith("http://") && !v.startsWith("https://")) {
                                Toast.makeText(ctx, "地址要以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                                return@GlassActionButton
                            }
                            DebugStore.customBaseUrl = v
                            savedUrl = v
                            ApiClient.reset()
                            DebugStore.log("Debug", "自定义地址 -> " + v.ifBlank { "(清空, 用默认)" })
                            Toast.makeText(ctx, "已保存，重启 App 生效", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        filled = true,
                    ) {
                        Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存")
                    }
                    GlassActionButton(
                        onClick = {
                            input = ""
                            savedUrl = ""
                            DebugStore.customBaseUrl = ""
                            ApiClient.reset()
                            DebugStore.log("Debug", "自定义地址已清除, 回到内置默认")
                            Toast.makeText(ctx, "已恢复默认地址", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("恢复默认")
                    }
                }
            }
        }

        PathRuleCard(ctx)

        Text("提示：改完地址务必重启 App —— Retrofit 客户端在首次请求时构建并缓存，\n" +
            "仅清缓存不足以让所有已注入的依赖换到新地址。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ======================= 接口路径改写 =======================

/**
 * 接口路径改写规则表。
 *
 * 用途：把某个接口打到别的路径上，例如 `/api/commands` → `/api/abc`。
 * ★ 前缀匹配 —— `/api/commands` 这条规则同时命中 `/api/commands/12/like`。
 * ★ 按顺序取**第一个**命中，不做链式替换。
 */
@Composable
private fun PathRuleCard(ctx: Context) {
    var rules by remember { mutableStateOf(DebugStore.pathRules) }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }

    fun persist(newRules: List<Pair<String, String>>) {
        rules = newRules
        DebugStore.pathRules = newRules
        ApiClient.reset()
        DebugStore.log("Debug", "路径规则更新: ${newRules.size} 条")
        Toast.makeText(ctx, "已保存，重启 App 生效", Toast.LENGTH_SHORT).show()
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("接口路径改写", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Text("把接口请求改打到别的路径。前置匹配，例如：\n" +
                "  /api/commands  →  /api/abc\n" +
                "命中后 query 原样保留；按顺序取第一个命中的规则。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 已有规则列表
            if (rules.isEmpty()) {
                Text("（还没有规则）", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                rules.forEachIndexed { i, (f, t) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("$f  →  ${t.ifBlank { "(空)" }}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { persist(rules.filterIndexed { j, _ -> j != i }) },
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedTextField(
                value = from, onValueChange = { from = it },
                label = { Text("原路径") }, placeholder = { Text("/api/commands") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = to, onValueChange = { to = it },
                label = { Text("新路径") }, placeholder = { Text("/api/abc") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )

            GlassActionButton(
                onClick = {
                    val f = from.trim()
                    if (f.isBlank()) {
                        Toast.makeText(ctx, "原路径不能为空", Toast.LENGTH_SHORT).show()
                        return@GlassActionButton
                    }
                    if (!f.startsWith("/")) {
                        Toast.makeText(ctx, "路径要以 / 开头", Toast.LENGTH_SHORT).show()
                        return@GlassActionButton
                    }
                    // 同源路径去重：覆盖已有的（避免两条规则打架，第一个命中永远生效）
                    val next = rules.filter { it.first != f } + (f to to.trim())
                    persist(next)
                    from = ""; to = ""
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = from.isNotBlank(),
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加规则")
            }

            if (rules.isNotEmpty()) {
                GlassActionButton(
                    onClick = { persist(emptyList()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("清空全部规则")
                }
            }
        }
    }
}

// ======================= 日志 =======================

@Composable
private fun LogTab(ctx: Context) {
    var enabled by remember { mutableStateOf(DebugStore.logEnabled) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记录日志", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            GlassSwitch(checked = enabled, onCheckedChange = {
                enabled = it
                DebugStore.logEnabled = it
            })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassActionButton(
                onClick = {
                    copyToClipboard(ctx, DebugStore.exportText(), "已复制全部日志")
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制全部")
            }
            GlassActionButton(
                onClick = {
                    DebugStore.clearLogs()
                    Toast.makeText(ctx, "日志已清空", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("清空")
            }
        }

        val logs = DebugStore.logs
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无日志", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // 最新的排最前（logs.reversed()），方便一眼看到最近发生了什么
            LazyColumn(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(logs.reversed()) { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

// ======================= 请求记录 =======================

@Composable
private fun RequestTab(ctx: Context) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassActionButton(
                onClick = { copyToClipboard(ctx, DebugStore.exportText(), "已复制全部记录") },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制全部")
            }
            GlassActionButton(
                onClick = {
                    DebugStore.clearRequests()
                    Toast.makeText(ctx, "请求记录已清空", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("清空")
            }
        }

        val reqs = DebugStore.requests
        if (reqs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无请求记录", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(reqs) { r ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 状态码配色：2xx 主色，4xx 橙，5xx/ERR 红
                                val codeColor = when {
                                    r.code == 0 -> Color(0xFFF87171)
                                    r.code in 200..299 -> MaterialTheme.colorScheme.primary
                                    r.code in 400..499 -> Color(0xFFF59E0B)
                                    else -> Color(0xFFF87171)
                                }
                                Text(if (r.code > 0) r.code.toString() else "ERR",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = codeColor, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.width(8.dp))
                                Text(r.method, style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.weight(1f))
                                Text("${r.costMs}ms", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(r.url, style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (r.error.isNotBlank()) {
                                Text(r.error, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(ctx: Context, text: String, toast: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cyancodex-debug", text))
        Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show() }
}
