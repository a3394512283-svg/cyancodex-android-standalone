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
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import kotlinx.coroutines.launch
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
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("登录态") })
            }

            when (tab) {
                0 -> ServerTab(ctx)
                1 -> LogTab(ctx)
                2 -> RequestTab(ctx)
                else -> LoginStateTab(ctx)
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
                        if (it) "调试能力已启用"
                        // ★ 2026-10-05: 明说"设置里的入口也会一起消失" —— 这是用户要的语义
                        //   (「调试模式关上设置底下的调试就自动隐藏」), 也是安卓开发者选项的行为。
                        //   并给出回来的路, 免得他以为把自己锁在外面了。
                        else "调试能力已关闭，设置里的「调试」入口也已隐藏（在关于页点版本号可重新开启）",
                        Toast.LENGTH_LONG,
                    ).show()
                })
            }
            Text(
                if (on) "服务器地址 / 路径改写 / 登录态注入 / 日志记录 均已启用。"
                else "全部暂停：App 走内置默认地址，不注入 Cookie，不记录日志；设置页的「调试」入口同时隐藏。",
                style = MaterialTheme.typography.labelSmall,
                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ======================= 登录态 =======================

/**
 * **登录态标签页** (2026-10-05 重写) —— 用户原话:「我的调试的 cookie 一直导出不了,
 * 我的 cookie 是指登录态之类的, 反正输进去能进行登陆就行」。
 *
 * ## 为什么从「Cookie」改成「登录态」
 * 2026-10-05 实测确认: **服务器从不下发 `Set-Cookie`**（`api/auth/captcha` 与
 * `api/auth/login` 的响应头里都没有该字段），认证完全靠 `Authorization: Bearer <token>`。
 * 也就是说 —— **用账号密码登录时, App 里根本不存在 cookie**。
 * 原来那个「导出 Cookie」在这种状态下只能导出空串, 用户看到的就是
 * 「登录状态下获取和重新登陆一次再获取都不行」。
 *
 * 用户对"cookie"的定义很朴素: 「登录态之类的」。所以这里统一按**登录态**处理 ——
 * token 与 cookie 谁有导谁, 导入时自动识别。
 *
 * 四块:
 * 1. **导入** —— 粘 token 或 cookie, 保存后**立即换登录态**(调 `me()` 真验, 不是假装成功);
 * 2. **状态** —— 令牌/Cookie 各自有没有、多少字符（**永不显示原文**, 除非用户点导出）;
 * 3. **导出** —— 点了才把登录态拷进剪贴板（换设备用）;
 * 4. **清除** —— 令牌 + Cookie 一起清（用户资料保留）。
 */
@Composable
private fun LoginStateTab(ctx: Context) {
    // 用一个 tick 触发重算: 导入/清除后要立刻反映到 UI 上
    var tick by remember { mutableStateOf(0) }
    val summary = remember(tick) { Session.loginStateSummary() }
    val hasCookie = remember(tick) { runCatching { Session.hasCookie() }.getOrDefault(false) }
    val hasToken = remember(tick) { runCatching { Session.realToken() != null }.getOrDefault(false) }
    val hasAny = hasCookie || hasToken
    var input by remember { mutableStateOf("") }
    // ★★ 2026-10-05: 导入后**当场换登录态**的结果回显 —— 用户原话
    //   「输进去能进行登陆就行」。不给反馈的话, 用户不知道到底登进去没有。
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    // 当前是不是"用 cookie 登录"的会话 —— 决定状态卡怎么描述
    val cookieSession = remember(tick) { Session.token == Session.COOKIE_TOKEN }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导入登录态", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text("粘贴本页「导出登录态」得到的内容，或浏览器里已登录的 Cookie（整行 `Cookie: a=1; b=2` " +
                    "或多行 `a=1` 都能吃下）。\n令牌与 Cookie 都能识别；保存后会**立即向服务器验证并登录**，不是存下来就算。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("令牌 或 Cookie") },
                    placeholder = { Text("eyJhbGci… 或 a=1; b=2") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    maxLines = 6,
                    // ★ 密码型: 防肩窥与截屏外泄。这是账号级凭证, 不必常显。
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassActionButton(
                        onClick = {
                            // ★ 2026-10-05: 不再自己 sanitize 后当 cookie 存 —— 交给
                            //   [AppState.importLoginState] 统一解析(token / cookie / 带标签格式都行)。
                            scope.launch {
                                busy = true
                                result = ""
                                val r = AppState.importLoginState(input)
                                busy = false
                                val u = r.getOrNull()
                                if (r.isSuccess) {
                                    input = ""
                                    result = "✅ 已登录：${u?.nickname ?: u?.username ?: "当前账号"}"
                                } else {
                                    result = "❌ 未能登录：" + (r.exceptionOrNull()?.message ?: "未知原因")
                                }
                                tick++
                                Toast.makeText(
                                    ctx,
                                    if (r.isSuccess) "登录态导入成功" else "已保存，但登录未成功",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (busy) "登录中…" else "导入并登录")
                    }
                    GlassActionButton(
                        onClick = {
                            Session.clearLoginState()
                            ApiClient.reset()
                            tick++
                            result = ""
                            DebugStore.log("Debug", "登录态已清除")
                            Toast.makeText(ctx, "已清除登录态（令牌 + Cookie）", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        // ⛔ 别写成 enabled = hasCookie —— 账号密码登录**只有令牌没有 Cookie**,
                        //    那样按钮永远是灰的, 用户点了没反应 (2026-10-05 实测报障:
                        //    「清除登录态没用」, 真因就是这里, 不是功能坏了)。
                        enabled = hasAny && !busy,
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("清除登录态")
                    }
                }
                if (result.isNotBlank()) {
                    Text(
                        result,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (result.startsWith("✅")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前登录态", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (hasAny) Icons.Default.CheckCircle else Icons.Default.Lock, null,
                        tint = if (hasAny) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                }
                Text("生效条件：调试模式开着（见顶部开关）且请求发往本 App 的官方服务器域名。\n" +
                    "调试模式关掉后：Cookie 不再注入，登录页的导入入口也会同时消失。\n" +
                    // ★ 2026-10-05: 说清"服务器不发 Set-Cookie"这件事 —— 用户报的
                    //   "登录状态下导出不了"根源就在这里, 不解释清楚他会一直以为是 bug。
                    "说明：本服务器认证用 `Authorization: Bearer <令牌>`，**不下发 Set-Cookie**；" +
                    "所以账号密码登录时 App 里没有 Cookie，能复用的是**令牌**（见上面的「导出登录态」）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cookieSession) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("导出登录态", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text("把当前登录凭证导出成一段文本，换设备时粘到「导入登录态」即可复用登录。\n" +
                    "有令牌就导令牌，有 Cookie 就一并带上（两者都有时导出的是两行）。\n" +
                    // ★ 2026-10-05: 用户报"导出不了"。真因是**旧版只导 Cookie, 而服务器不下发
                    //   Set-Cookie** → 密码登录时永远是空串。现在改为导整个登录态, 并且
                    //   按钮**永远可点**, 没内容就用 Toast 说清为什么。
                    "「清除登录态」会同时清掉令牌与 Cookie（用户资料保留）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassActionButton(
                        onClick = {
                            val txt = DebugStore.exportLoginState()
                            if (txt.isBlank()) {
                                Toast.makeText(
                                    ctx,
                                    "当前没有可导出的登录态（未登录，且没有注入过 Cookie）",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                copyToClipboard(ctx, txt, "登录态已复制到剪贴板")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        // ⛔ 不设 enabled —— 灰着的按钮会让用户以为"功能坏了"。
                        //    永远可点, 没内容就用 Toast 说清楚。
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出登录态")
                    }
                    GlassActionButton(
                        onClick = {
                            Session.clearLoginState()
                            ApiClient.reset()
                            tick++
                            DebugStore.log("Debug", "登录态已清除")
                            Toast.makeText(ctx, "已清除登录态（令牌 + Cookie）", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        // 同上: 判 hasAny 而不是 hasCookie —— 密码登录场景下根本没有 Cookie。
                        enabled = hasAny,
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("清除登录态")
                    }
                }
            }
        }

        Text("提示：登录态等同于完整账号权限。导出后请勿粘贴到公开渠道；" +
            "日志与请求记录里**不会**出现凭证原文。",
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
