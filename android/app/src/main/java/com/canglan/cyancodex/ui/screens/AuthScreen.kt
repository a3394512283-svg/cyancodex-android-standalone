package com.canglan.cyancodex.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.DebugStore
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 登录/注册页 —— 液态玻璃(Liquid Glass)风格
 * 注册: 图形验证码 + 邮箱验证码(邮件含QQ群链接)
 * asGate=true 时作为「登录门禁」全屏展示: 未登录/未注册无法使用 App;
 * 首次进入默认「注册」, 曾注册过的用户默认「登录」; 成功后不 popBackStack(由门禁状态切换)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(nav: NavHostController, asGate: Boolean = false) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var isLogin by remember { mutableStateOf(!asGate || Session.hasRegistered) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var needTotp by remember { mutableStateOf(false) }   // 两步验证: 需身份验证器验证码
    var totpCode by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var captchaId by remember { mutableStateOf("") }
    var captchaImage by remember { mutableStateOf("") }
    var captchaText by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // 协议/隐私政策: 登录页可能渲染在 NavHost 之外(门禁), 不能用 nav 跳转, 改为本页内联展示
    var legalDoc by remember { mutableStateOf<String?>(null) }

    // ---------- Cookie 注入入口 (2026-10-05) ----------
    //
    // ★★ 2026-10-05 用户定稿: 「cookie 你就应该在开启调试模式的时候直接开启…调试模式关闭时
    //    也要同时隐藏入口，类似安卓开发者模式一样」。
    //
    // 于是入口规则改成**跟着调试模式走**, 和安卓「开发者选项」一致:
    //   - 调试模式开 ([DebugStore.active]) → 登录页**直接出现**「Cookie 登录」按钮, 一点就能填;
    //   - 调试模式关 → 入口**彻底消失**(连 10 连点也失效), 不再是"藏着但能用"。
    //   - 另保留「连点图标 10 下」作为**快捷方式** —— 但同样只在调试模式开着时有效。
    //
    // 为什么要连点: cookie 等于完整账号权限。调试模式是用户主动开的, 所以开了就直给;
    //   没开的时候连点也不该放行 —— 否则"隐藏入口"就成了摆设。
    //
    // ⚠️ 计数带**时间窗**: 间隔超过 1.2 秒就从头数 —— 否则用户正常点几下图标
    //   (比如误触) 累积多了会莫名弹框。这是竞态类 bug 的常见来源, 别省这个判断。
    var tapCount by remember { mutableStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }
    var showCookieBox by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }
    // ★★ 2026-10-05: 保存 cookie 时**当场换登录态** —— 用户原话「输进去能进行登陆就行」。
    var cookieBusy by remember { mutableStateOf(false) }
    var cookieMsg by remember { mutableStateOf("") }
    // 调试模式是否生效 —— 读 [DebugStore.activeState] 而不是 [DebugStore.active]:
    // 前者是 Compose 可观察的, 在调试面板里开关一改、回到登录页立刻反映;
    // 后者读 SharedPreferences, 订阅不到变化(在返回栈里的页面会一直显示旧状态)。
    val cookieEntryOn = DebugStore.activeState

    /** 点一下图标。调试模式开着时, 满 10 下弹框; 超时则重新计数。
     *
     *  ⛔ **全程静默**: 不给任何 Toast / 提示 / 视觉反馈。
     *     2026-10-05 用户明确要求「打开面板无须提示，调试用的不用给提示」——
     *     这是调试入口, 提示会暴露它的存在, 就失去"隐藏"的意义了。
     *  ⛔ 调试模式关着时**直接不计数** —— 入口要真的消失, 不是藏起来。
     */
    fun onIconTap() {
        if (!cookieEntryOn) return
        val now = System.currentTimeMillis()
        tapCount = if (now - lastTapAt > 1200L) 1 else tapCount + 1
        lastTapAt = now
        if (tapCount >= 10) {
            tapCount = 0
            cookieInput = ""
            showCookieBox = true
        }
    }

    suspend fun refreshCaptcha() {
        val r = safeApi { ApiClient.get().captcha() }
        if (r.ok) {
            captchaId = r.data?.id ?: ""
            captchaImage = r.data?.image ?: ""
            captchaText = ""
        } else {
            error = r.error ?: "验证码获取失败"
        }
    }

    LaunchedEffect(isLogin) {
        if (!isLogin) refreshCaptcha()
    }

    // 发送邮箱验证码 (60s 倒计时)
    fun sendCode() {
        if (!email.contains("@")) { error = "请先填写正确的邮箱"; return }
        scope.launch {
            sending = true
            val r = safeApi { ApiClient.get().sendEmailCode(mapOf("email" to email.trim())) }
            sending = false
            if (r.ok) {
                error = ""
                countdown = 60
            } else {
                error = r.error ?: "发送失败"
            }
        }
    }

    LaunchedEffect(countdown) {
        if (countdown > 0) { delay(1000); countdown-- }
    }

    fun submit() {
        if (username.isBlank() || password.isBlank()) { error = "请填写用户名和密码"; return }
        if (needTotp && totpCode.isBlank()) { error = "请输入两步验证码"; return }
        scope.launch {
            busy = true
            error = ""
            try {
                val r = if (isLogin) {
                    val body = mutableMapOf("username" to username.trim(), "password" to password)
                    if (needTotp) body["totp_code"] = totpCode.trim()
                    safeApi { ApiClient.get().login(body) }
                } else {
                    if (captchaText.isBlank()) { error = "请填写图形验证码"; busy = false; return@launch }
                    if (emailCode.isBlank()) { error = "请填写邮箱验证码"; busy = false; return@launch }
                    safeApi {
                        ApiClient.get().register(mapOf(
                            "username" to username.trim(),
                            "password" to password,
                            "nickname" to nickname.ifBlank { username.trim() },
                            "email" to email.trim(),
                            "captcha_id" to captchaId,
                            "captcha_text" to captchaText.trim(),
                            "email_code" to emailCode.trim(),
                        ))
                    }
                }
                val d = r.data
                if (r.ok && d != null && d.token != null && d.user != null) {
                    // 登录/注册成功均标记: 本设备已有账号 (首次进入引导注册, 之后默认登录)
                    Session.hasRegistered = true
                    needTotp = false
                    totpCode = ""
                    AppState.setLogin(d.user, d.token)
                    if (!asGate) nav.popBackStack()
                } else if (r.needTotp) {
                    // 账号开启了两步验证: 显示验证码输入框, 重新提交
                    needTotp = true
                    error = r.error ?: "请输入两步验证码"
                } else {
                    error = r.error ?: "操作失败"
                    if (!isLogin) refreshCaptcha()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "网络错误"
            } finally { busy = false }
        }
    }

    // 内联协议页 (返回键/返回箭头均可关闭)
    val openDoc = legalDoc
    if (openDoc != null) {
        BackHandler { legalDoc = null }
        if (openDoc == "terms") TermsScreen(nav, onBack = { legalDoc = null })
        else PrivacyScreen(nav, onBack = { legalDoc = null })
        return
    }

    // ---------------- 隐藏 cookie 注入框 (连点图标 10 下, 2026-10-05) ----------------
    //
    // 用途: 从浏览器拷出已登录 cookie, 当**登录凭证**用 —— 免去在 App 里重新登录。
    // ⚠️ cookie = 完整账号权限, 所以这里: ① 输入框是密码型(遮点); ② 不回显已存的 cookie;
    //    ③ 只展示"已设置(N 字符)"。
    if (showCookieBox) {
        val saved = Session.cookie
        AlertDialog(
            onDismissRequest = { showCookieBox = false },
            title = { Text(if (saved == null) "导入登录态" else "Cookie 已设置") },
            text = {
                Column {
                    Text(
                        if (saved == null) "粘贴调试面板「导出登录态」得到的内容，或浏览器里已登录的 Cookie（形如 a=1; b=2）。令牌与 Cookie 都能识别。"
                        else "当前已注入 cookie（${saved.length} 字符）。重新粘贴可覆盖，或点「清除」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        label = { Text("Cookie") },
                        placeholder = { Text("a=1; b=2") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        maxLines = 8,
                        // ★ 密码型: 防止旁人瞥见. 这不是"防自己", 是防肩窥与截屏外泄。
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "❗ cookie 等于完整账号权限：只发往本 App 的官方服务器域名，不会发给第三方 AI / 搜索服务。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (cookieBusy || cookieMsg.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (cookieBusy) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (cookieBusy) "正在用 Cookie 登录…" else cookieMsg,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (cookieMsg.startsWith("✅")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !cookieBusy,
                    // 「调试用的不用给提示」—— 成功则静默关框; 失败**必须**说原因,
                    // 否则用户不知道是 cookie 不对还是功能没生效(这正是本次要修的问题)。
                    onClick = {
                        // ★ 2026-10-05: 不再自己 sanitize 后就当 cookie 存 —— 交给
                        //   [AppState.importLoginState] 统一解析。这样这里**令牌和 Cookie 都能收**,
                        //   与调试面板的「导入登录态」行为一致(用户从那边导出的东西能直接粘这里)。
                        scope.launch {
                            cookieBusy = true
                            cookieMsg = ""
                            val r = AppState.importLoginState(cookieInput)
                            cookieBusy = false
                            if (r.isSuccess) {
                                val u = r.getOrNull()
                                cookieInput = ""
                                cookieMsg = "✅ 已登录：${u?.nickname ?: u?.username ?: "当前账号"}"
                                showCookieBox = false
                                // 门禁模式下登录态一变就会自动放行(见外层门禁判定)
                            } else {
                                cookieMsg = "❌ 未能登录：" +
                                    (r.exceptionOrNull()?.message ?: "未知原因")
                            }
                        }
                    },
                ) { Text(if (cookieBusy) "登录中…" else "保存并登录") }
            },
            dismissButton = {
                Row {
                    if (saved != null) {
                        androidx.compose.material3.TextButton(
                            enabled = !cookieBusy,
                            onClick = {
                                Session.clearCookie(); ApiClient.reset()
                                cookieMsg = ""; showCookieBox = false
                            },
                        ) { Text("清除", color = MaterialTheme.colorScheme.error) }
                    }
                    androidx.compose.material3.TextButton(
                        enabled = !cookieBusy,
                        onClick = { cookieMsg = ""; showCookieBox = false },
                    ) { Text("取消") }
                }
            },
        )
    }

    // 液态玻璃背景
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1A14), Color(0xFF10352B), Color(0xFF0B1A14))))) {
        // 装饰光斑
        Box(Modifier.size(260.dp).offset(x = (-60).dp, y = 60.dp)
            .blur(70.dp).background(Brush.radialGradient(listOf(Color(0x554ADE80), Color.Transparent)), CircleShape))
        Box(Modifier.size(300.dp).offset(x = 700.dp, y = 300.dp)
            .blur(80.dp).background(Brush.radialGradient(listOf(Color(0x4438BDF8), Color.Transparent)), CircleShape))
        Box(Modifier.size(220.dp).offset(x = 120.dp, y = 1500.dp)
            .blur(70.dp).background(Brush.radialGradient(listOf(Color(0x44FBBF24), Color.Transparent)), CircleShape))

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            Image(
                painter = androidx.compose.ui.res.painterResource(com.canglan.cyancodex.R.drawable.usericon),
                contentDescription = "CyanCodex",
                modifier = Modifier.size(56.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    // ★ 快捷入口: 连点 10 下打开 cookie 注入框 (2026-10-05)。
                    //   用 clickable 而非 pointerInput —— 与关于页版本号那个入口同一套写法,
                    //   行为可预期 (ripple 关掉, 免得暴露"这里可以点")。
                    //   ⛔ 调试模式关着时**整块不挂 clickable** —— 连点也点不开 (入口真消失)。
                    .then(
                        if (cookieEntryOn) Modifier.clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { onIconTap() } else Modifier
                    ),
            )
            Text("CyanCodex", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("沧蓝工坊 · 我的世界指令助手", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(24.dp))

            // 液态玻璃卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.10f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 登录/注册切换 (固定位置: 左=登录, 右=注册)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassChip("登录", selected = isLogin) { isLogin = true; error = "" }
                        GlassChip("注册", selected = !isLogin) { isLogin = false; error = "" }
                    }

                    GlassField(username, { username = it }, if (isLogin) "用户名 / 邮箱" else "用户名")
                    GlassField(password, { password = it }, "密码", isPassword = true)

                    if (isLogin && needTotp) {
                        GlassField(totpCode, { totpCode = it }, "两步验证码（身份验证器）")
                        Text("该账号已开启两步验证，请输入身份验证器中的 6 位验证码",
                            fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
                    }

                    if (!isLogin) {
                        GlassField(nickname, { nickname = it }, "昵称(可选)")
                        GlassField(email, { email = it }, "邮箱")

                        // 图形验证码
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassField(captchaText, { captchaText = it }, "图形验证码", modifier = Modifier.weight(1f))
                            val bmp = remember(captchaImage) {
                                runCatching {
                                    val b64 = captchaImage.substringAfter("base64,")
                                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }.getOrNull()
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "验证码",
                                    modifier = Modifier.size(96.dp, 42.dp).clip(RoundedCornerShape(8.dp))
                                        .clickable { scope.launch { refreshCaptcha() } },
                                )
                            } else {
                                Box(Modifier.size(96.dp, 42.dp).clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                    Text("加载中…", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                                }
                            }
                            IconButton(onClick = { scope.launch { refreshCaptcha() } }) {
                                Icon(Icons.Default.Refresh, "刷新验证码", tint = Color.White.copy(alpha = 0.8f))
                            }
                        }

                        // 邮箱验证码
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassField(emailCode, { emailCode = it }, "邮箱验证码", modifier = Modifier.weight(1f))
                            OutlinedButton(
                                onClick = { sendCode() },
                                enabled = !sending && countdown == 0,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            ) {
                                Text(if (countdown > 0) "${countdown}s" else if (sending) "发送中…" else "发送验证码",
                                    fontSize = 12.sp)
                            }
                        }
                    }

                    if (error.isNotBlank()) {
                        Text(error, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }

                    Button(
                        onClick = { submit() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4ADE80),
                            contentColor = Color(0xFF052E16),
                        ),
                    ) { Text(if (busy) "请稍候…" else if (isLogin) "登录" else "注册并登录", fontWeight = FontWeight.Bold) }

                    Text(if (isLogin) "支持用户名或邮箱登录 · 未注册请先注册"
                        else "注册即送 100 积分 · 需填写全部信息",
                        fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth())

                    // 用户协议 + 隐私政策 (注册/登录即表示同意)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Text("继续即表示同意", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                        Text(
                            "《用户协议》",
                            fontSize = 11.sp,
                            color = Color(0xFF8AB4FF),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { legalDoc = "terms" },
                        )
                        Text("和", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                        Text(
                            "《隐私政策》",
                            fontSize = 11.sp,
                            color = Color(0xFF8AB4FF),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { legalDoc = "privacy" },
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            // QQ 群超链接 (直接唤起 QQ)
            Text(
                "加入我们的QQ群 $QQ_GROUP_NUMBER",
                color = Color(0xFF8AB4FF),
                fontSize = 13.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        joinQqGroup(ctx)
                    },
            )
            // ★★ 调试模式专属: 登录态导入入口 (2026-10-05)
            //
            // 用户原话:「cookie 你就应该在开启调试模式的时候直接开启…调试模式关闭时也要同时
            //   隐藏入口，类似安卓开发者模式一样」。
            // 所以:**调试模式开着才出现**, 关着时这整块不存在 —— 不是藏起来, 是没了。
            //
            // ★ 2026-10-05 改名「Cookie 登录」→「导入登录态」: 实测服务器不下发 Set-Cookie,
            //   光叫 Cookie 会让用户以为"只有 cookie 能用", 而真正能复用登录的其实是令牌。
            //   现在这里令牌与 Cookie 都收, 与调试面板的导出格式一一对应。
            //
            // 样式刻意做得像"调试工具"而非普通登录方式: 深色描边 + 小字说明,
            // 免得用户误以为这是给所有人的登录入口。
            if (cookieEntryOn) {
                Spacer(Modifier.height(10.dp))
                val cookieSaved = Session.hasCookie()
                val tokenSaved = Session.realToken() != null
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { cookieInput = ""; showCookieBox = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Icon(
                        if (cookieSaved || tokenSaved) Icons.Default.CheckCircle else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (cookieSaved || tokenSaved) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            cookieSaved -> "已注入 Cookie（${Session.cookie?.length ?: 0} 字符）· 点击修改"
                            tokenSaved -> "令牌登录中 · 点击导入其它登录态"
                            else -> "导入登录态（调试模式）"
                        },
                        fontSize = 12.sp,
                        color = if (cookieSaved || tokenSaved) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.08f),
        contentColor = if (selected) Color(0xFF052E16) else Color.White.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, if (selected) Color.Transparent else Color.White.copy(alpha = 0.2f)),
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
    }
}

@Composable
private fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.White.copy(alpha = 0.6f)) },
        singleLine = true,
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = TextStyle(color = Color.White),
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White.copy(alpha = 0.55f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
            focusedContainerColor = Color.White.copy(alpha = 0.07f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
            cursorColor = Color.White,
            focusedLabelColor = Color.White.copy(alpha = 0.85f),
        ),
    )
}
