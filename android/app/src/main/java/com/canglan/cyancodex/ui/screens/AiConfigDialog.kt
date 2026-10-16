package com.canglan.cyancodex.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.canglan.cyancodex.ai.AI_PROVIDERS
import com.canglan.cyancodex.ai.AI_TOOL_ROUNDS_MAX
import com.canglan.cyancodex.ai.AI_TOOL_ROUNDS_MIN
import com.canglan.cyancodex.ai.AiClient
import com.canglan.cyancodex.ai.AiModelInfo
import com.canglan.cyancodex.ai.AiSearch
import com.canglan.cyancodex.ai.CUSTOM_PROVIDER_ID
import com.canglan.cyancodex.ai.SEARCH_ENGINES
import com.canglan.cyancodex.ai.THINK_LEVELS
import com.canglan.cyancodex.data.FreeAi
import com.canglan.cyancodex.ai.ThinkLevel
import com.canglan.cyancodex.ai.cleanApiKey
import com.canglan.cyancodex.ai.decodeModels
import com.canglan.cyancodex.ai.defaultModelOf
import com.canglan.cyancodex.ai.encodeModels
import com.canglan.cyancodex.ai.guessReasoning
import com.canglan.cyancodex.ai.matchAiProvider
import com.canglan.cyancodex.ai.normalizeAiBase
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.GlassSwitch
import com.canglan.cyancodex.ui.components.GlassTextButton
import com.canglan.cyancodex.ui.components.OutlinedTextField2
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * AI 设置面板。
 *
 * ## 布局（2026-10-01 六批后整理过）
 *
 * 改之前是一条竖直长龙：标题只有「思考强度」「智能搜索」两个 `labelLarge`，跟正文输入框的
 * label 一个字号；分隔线时有时无。用户反馈「设置有点乱」。现在按**三块**组织，
 * 每块一个编号徽章 + 标题 + 一句话说明（见 [CfgSection]）：
 *
 * ```
 * ① 选择服务商   官方 / 7 家预设 / 自定义 → 地址·Key·模型·[获取模型] [选择模型] [测试连接]
 * ② 模型行为     思考强度（仅自备 Key）+ 工具调用轮数上限（两条通道都生效）
 * ③ 联网搜索     可选，不配就没有联网能力 → Key·[去申请 Key] [粘贴] [测试搜索]
 * ─────────────
 * [保存] [去申请 Key] [清除配置]
 * ```
 *
 * ★★ **窄屏一律用 `FlowRow`**（2026-10-01 用户报的）——
 *   手机上「获取模型 / 选择模型 / 测试连接」三个按钮挤在一行时，**最后一个会被挤出可视区**
 *   （只剩一条边贴在右边框上），用户直接说"测试连接不见了，可能是没位置了"。
 *   `Row` 不会折行也不会报错，只会静默裁掉 —— 所以凡是"按钮数量不固定 / 文案长度不固定"的
 *   横向排布，一律 `FlowRow`。同理适用于联网搜索那一组和底部操作区。
 *
 * ★★ **没有「关闭」按钮**（2026-10-01 用户要求删掉）——
 *   它是冗余的：系统返回键、点对话框外部都能关。删掉之后「保存」和「清除配置」才排得进同一行。
 *   用户原话：「这个关闭删了用户会用返回键，清除设置和保存放在同一行」。
 *
 * ★ 「工具调用轮数上限」刻意放在 ② 而**不在**「自备 API」那个 `if` 里 ——
 *   官方通道一样会调工具（走约定 JSON），藏进 `if` 里用户就永远调不到。
 *
 * ★ 「测试连接」刻意放在 ① 的「模型」那一行，**不在**底部跟「保存」并排（2026-10-01 挪的）。
 *   它验证的是**当前这组 地址·Key·模型**能不能用，属于"配置模型"这一步的收尾动作；
 *   放最底下会被读成"测整个 App 的链路/网络"，用户点了报错就以为 App 坏了。
 *   自检结果也跟着就近显示，点完不用往下找。
 *
 * ## 相比原版那个内联的三输入框卡片, 这里解决七件事:
 *  1. **预设一键填** —— 7 家常见 OpenAI 兼容服务, 选中即填好地址和常用模型, 用户只贴 Key。
 *  2. **地址容错** —— 走 [normalizeAiBase], 用户填 `api.deepseek.com` 这种半截地址也能用。
 *  3. **Key 可见性切换** —— 原版是明文输入框, 贴错一个字符根本看不出来。
 *  4. **连通性自检** —— 保存前先打一次真实请求, 401/404/余额不足当场说清楚。
 *  5. **获取模型 / 选择模型** (2026-10-01) —— 打 `GET /models` 把这家服务商的模型全列出来,
 *     带「思考 / 视觉 / 上下文」标签, 点一下即选中; 免去用户手打模型名打错。
 *  6. **思考设置** —— 低/中/高三档 + 「实测思考支持」按钮。支不支持思考的判定优先级是
 *     **实测 > 上游声明 > 按模型名猜**; 实测会真发请求验证, 不再靠名字猜。
 *     就算判错了也不会把消息发挂, [AiClient.chat] 有 400 自动降级。
 *  7. **工具调用轮数上限** (2026-10-01 六批) —— 滑杆 2~12 轮, 默认 6。
 *     加「指令专家」之后模型一轮问答里可能要核指令、查百科、改完再核, 4 轮不够用。
 */
// ★ 加 ExperimentalLayoutApi 是为了 FlowRow（窄屏按钮自动换行，见文件头说明）
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiConfigDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var providerId by remember { mutableStateOf(matchAiProvider(Session.aiApiBase)) }
    // 「官方 AI（消耗积分）」—— 显式选项。配了自备 Key 之后也得能切回来,
    // 否则想临时用一次官方能力就只能把 Key 删掉。
    var useOfficial by remember { mutableStateOf(Session.aiUseOfficial) }
    // 「限时免费」—— 二改自带的第三个通道 (见 data/FreeAi)。与上面两条互斥。
    var useFree by remember { mutableStateOf(Session.aiUseFree) }
    // 是否走「自备 Key」那条路 —— 下面多处分支都用它, 免得到处写 !useOfficial && !useFree
    val useOwnKey = !useOfficial && !useFree
    // 免费通道状态: 打开设置页时探测一次, 决定要不要显示那个选项
    var freeStatus by remember { mutableStateOf<FreeAi.Status?>(null) }
    LaunchedEffect(Unit) { freeStatus = FreeAi.status(ctx) }
    var base by remember { mutableStateOf(Session.aiApiBase) }
    var key by remember { mutableStateOf(Session.aiApiKey) }
    var model by remember { mutableStateOf(Session.aiModel) }
    var keyVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiClient.TestResult?>(null) }

    // ---- 模型清单 ----
    var models by remember { mutableStateOf(decodeModels(Session.aiModelList)) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelErr by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }

    // ---- 思考 ----
    var thinkLevel by remember { mutableStateOf(ThinkLevel.of(Session.aiThinkLevel)) }
    var probing by remember { mutableStateOf(false) }
    // 实测结果只在当前这组「地址 + Key + 模型」上有效 —— 任一改变就作废 (见下面各 onValueChange)
    var probe by remember { mutableStateOf<AiClient.ThinkProbe?>(null) }

    // ---- 智能搜索 (可选) ----
    var searchEngine by remember { mutableStateOf(Session.aiSearchEngine) }
    var searchKey by remember { mutableStateOf(Session.aiSearchKey) }
    var searchKeyVisible by remember { mutableStateOf(false) }
    var searchTesting by remember { mutableStateOf(false) }
    // 自检结果只在当前这组「引擎 + Key」上有效 —— 任一改变就作废 (见下面各 onValueChange)
    var searchTest by remember { mutableStateOf<AiSearch.SearchTest?>(null) }
    val curEngine = SEARCH_ENGINES.firstOrNull { it.id == searchEngine }

    // ---- 工具调用轮数上限 ----
    // 与「用哪家服务商」无关, 所以**不受 useOfficial 影响** —— 官方通道一样会调工具。
    var toolRounds by remember { mutableStateOf(Session.aiToolRounds) }
    // ★ 2026-10-05: 语法核对 / 客户端复查两个开关 —— 与输入框里的胶囊**同一份 Session**,
    //   两边都能改(胶囊随手切、这里给说明)。打开弹层时读一次当前值。
    var syntaxCheck by remember { mutableStateOf(Session.aiSyntaxCheckOn) }
    var recheckOn by remember { mutableStateOf(Session.aiRecheckOn) }

    val preset = AI_PROVIDERS.firstOrNull { it.id == providerId }
    // picked == null 说明这个模型不在已拉到的列表里 (用户手填的 / 没点过「获取模型」)
    val picked: AiModelInfo? = models.firstOrNull { it.id == model.trim() }
    val thinkSupported = picked?.reasoning ?: guessReasoning(model)

    // 「支不支持思考」的判定优先级: **实测** > 上游声明 > 按模型名猜。
    // 实测最可信 —— 上游声明的元数据和实际行为不一致是常事 (网关会把参数吃掉)。
    val pr = probe
    val pk = picked
    val (thinkStatus, thinkOk) = when {
        pr != null -> pr.note to (pr.support == AiClient.ThinkSupport.YES)
        // ⚠️ 写成 `pk != null && pk.probed` 而不是 `pk?.probed == true` ——
        //    后者 Kotlin **不会**把 pk 智能转换成非空, 下一行的 pk.reasoning 会编译不过
        pk != null && pk.probed ->
            (if (pk.reasoning) "实测：支持思考" else "实测：不支持思考") to pk.reasoning
        pk != null ->
            (if (pk.reasoning) "上游声明：支持思考" else "上游声明：不支持思考") to pk.reasoning
        model.isBlank() -> "先选一个模型" to false
        thinkSupported -> "按模型名推测：支持思考" to true
        else -> "按模型名推测：不支持思考" to false
    }

    /** 把实测结论写回模型清单并落盘 —— 下次开面板直接显示「实测」而不是「推测」 */
    fun saveProbe(p: AiClient.ThinkProbe) {
        // MAYBE / FAIL 都不是确定结论, 不能覆盖已有的判定
        if (p.support != AiClient.ThinkSupport.YES && p.support != AiClient.ThinkSupport.NO) return
        val idx = models.indexOfFirst { it.id == model.trim() }
        if (idx < 0) return
        val updated = models.toMutableList()
        updated[idx] = updated[idx].copy(
            reasoning = p.support == AiClient.ThinkSupport.YES, probed = true)
        models = updated
        Session.aiModelList = encodeModels(updated)
    }

    fun pick(id: String) {
        providerId = id
        result = null
        probe = null
        val p = AI_PROVIDERS.firstOrNull { it.id == id } ?: return
        base = p.baseUrl
        // 换供应商时把模型也带过去, 但用户自己填过的模型(不在预设里)不动
        if (model.isBlank() || AI_PROVIDERS.any { it.models.contains(model) }) {
            model = defaultModelOf(id)
        }
    }

    fun openConsole(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun loadModels() {
        modelErr = ""
        loadingModels = true
        scope.launch {
            try {
                val list = AiClient.fetchModels(base, key)
                models = list
                Session.aiModelList = encodeModels(list)   // 缓存, 下次开面板不用再等网络
                // 拉回来发现当前模型名不在列表里, 自动切到第一个, 免得用户对着空选
                if (list.none { it.id == model.trim() }) model = list.first().id
            } catch (e: Exception) {
                modelErr = e.message ?: "获取失败"
            } finally {
                loadingModels = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = 660.dp),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("AI 设置", style = MaterialTheme.typography.titleMedium)
                Text(
                    "两条路二选一：用官方 AI（消耗积分），或用你自己申请的 API Key 直连（不消耗积分）。" +
                        "选一家服务商会自动填好地址。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- ① 选择服务商 ----
                CfgSection("1", "选择服务商", "决定这次提问由谁来回答。")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        GlassFilterChip(
                            selected = useOfficial,
                            onClick = { useOfficial = true; result = null; probe = null },
                            label = {
                                Text("官方 AI · 消耗积分",
                                    style = MaterialTheme.typography.labelMedium)
                            },
                        )
                    }
                    // 「限时免费」—— 服务端开着、且本地有可用账号令牌时才出现。
                    // Cookie 登录没有真令牌 (见 FreeAi 注释), 这种情况下不显示。
                    if (freeStatus?.enabled == true && FreeAi.canUse()) {
                        item {
                            GlassFilterChip(
                                selected = useFree,
                                onClick = {
                                    useFree = true
                                    useOfficial = false
                                    result = null
                                    probe = null
                                },
                                label = {
                                    Text("限时免费", style = MaterialTheme.typography.labelMedium)
                                },
                            )
                        }
                    }
                    items(AI_PROVIDERS) { p ->
                        GlassFilterChip(
                            selected = useOwnKey && providerId == p.id,
                            onClick = { useOfficial = false; pick(p.id) },
                            label = { Text(p.name, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                    item {
                        GlassFilterChip(
                            selected = useOwnKey && providerId == CUSTOM_PROVIDER_ID,
                            onClick = {
                                useOfficial = false
                                providerId = CUSTOM_PROVIDER_ID
                                result = null
                            },
                            label = { Text("自定义", style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }

                // ---- 选了官方: 只把「自备 API」那一套（地址 / Key / 模型）收起来 ----
                // 摆着又用不上只会让人以为还要填点什么。
                // ⚠️ ② 模型行为里的「工具调用轮数上限」和 ③ 联网搜索**不收** ——
                //    它们跟用哪家模型无关, 官方通道下同样生效（搜索在客户端跑、
                //    工具走约定 JSON）。
                if (useOfficial) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("使用官方 AI", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "不填 Key 也能用，按积分计费（基础 5 积分 + 按 Tokens）。" +
                                    "需要登录，且积分余额足够。\n" +
                                    "下面的「模型行为」「联网搜索」照常生效 —— 搜索和工具都在客户端跑，" +
                                    "资料会并进这次提问里（因此会更费一点积分）。\n" +
                                    "切回自备 API 只需点上面任意一家服务商。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ---- 选了「限时免费」----
                if (useFree) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("使用限时免费通道", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildString {
                                    append(
                                        freeStatus?.announcement?.ifBlank { "限时免费体验中" }
                                            ?: "限时免费体验中"
                                    )
                                    append("\n由二改作者提供，不消耗积分、也不用自备 Key。")
                                    append("请求经作者服务器转发，上游密钥不会下发到本机。")
                                    freeStatus?.let { append("\n" + it.quotaText()) }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (useOwnKey) {
                    // ---- 地址 ----
                    OutlinedTextField2(base, { base = it; result = null; modelErr = ""; probe = null }, "API 地址",
                        placeholder = "https://api.deepseek.com/v1/")
                    if (base.isNotBlank() && normalizeAiBase(base) != base.trim()) {
                        Text(
                            "实际请求：${normalizeAiBase(base)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // ---- Key ----
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it; result = null; modelErr = ""; probe = null },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    if (keyVisible) "隐藏" else "显示",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // ---- 模型 ----
                    OutlinedTextField2(model, { model = it; result = null; probe = null }, "模型",
                        placeholder = "deepseek-chat")
                    // ★ FlowRow 而不是 Row —— 窄屏放不下时**折到下一行**，而不是把最后一个按钮
                    //   裁出可视区。用户就是这么发现「测试连接」不见了的（截图里只剩右边框一条边）。
                    FlowRow(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassActionButton(
                            onClick = { loadModels() },
                            enabled = !loadingModels && base.isNotBlank() && key.isNotBlank(),
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (loadingModels) "获取中…" else "获取模型", style = MaterialTheme.typography.labelMedium)
                        }
                        if (models.isNotEmpty()) {
                            GlassActionButton(onClick = { showPicker = true }) {
                                Text("选择模型 (${models.size})", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        // ---- 测试连接 (2026-10-01 从底部挪上来) ----
                        // 它测的是**上面这组 地址·Key·模型**能不能用, 属于"配置模型"这一步,
                        // 不是全局动作。原来跟「保存」并排放在最底下, 用户会以为它在测整个 App
                        // 的链路/网络。放这里就近反馈, 也不会被误读。
                        // 官方通道没有"连接"可测 —— 藏起来比给个点了就报错的按钮强。
                        if (useOwnKey) {
                            GlassActionButton(
                                onClick = {
                                    testing = true
                                    result = null
                                    scope.launch {
                                        result = AiClient.testConnection(base, key, model)
                                        testing = false
                                    }
                                },
                                enabled = !testing && base.isNotBlank() && key.isNotBlank() && model.isNotBlank(),
                            ) { Text(if (testing) "测试中…" else "测试连接", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    // 自检结果**就近**显示 —— 点完按钮眼睛不用往下找
                    result?.let { r ->
                        Text(
                            (if (r.ok) "✓ " else "✗ ") + r.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (r.ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (models.isEmpty() && !loadingModels) {
                        Text(
                            "点「获取模型」可列出这家服务商全部可用模型；不点也行，直接手填模型名。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (modelErr.isNotBlank()) {
                        Text(modelErr, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error)
                    }
                    // 预设自带的推荐模型 (没拉过列表时的快捷入口)
                    if (models.isEmpty() && preset != null && preset.models.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(preset.models) { m ->
                                GlassFilterChip(
                                    selected = model == m,
                                    onClick = { model = m; result = null },
                                    label = { Text(m, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }

                }   // ← 闭合「自备 API」那一整块（地址 / Key / 模型）

                // ---- ② 模型行为 ----
                // ★ 「工具调用轮数上限」放在这一块、**不在**上面那个 if 里 ——
                //   官方通道一样会调工具（走约定 JSON），藏进 if 里用户就永远调不到它。
                CfgSection("2", "模型行为", "跟用哪家服务商无关，两条通道都生效。")

                // 思考强度只在自备 Key 通道下可调 —— 官方通道的思考由服务端控制。
                if (useOwnKey) {
                    // 开关在输入框那个「深度思考」chip 上 (跟 DeepSeek 客户端一致, 随手可切);
                    // 这里只放"开到什么强度"。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("思考强度", style = MaterialTheme.typography.labelLarge)
                        GlassActionButton(
                            onClick = {
                                probing = true
                                probe = null
                                scope.launch {
                                    val p = AiClient.probeThinking(base, key, model, thinkLevel)
                                    probe = p
                                    saveProbe(p)
                                    probing = false
                                }
                            },
                            enabled = !probing && base.isNotBlank() && key.isNotBlank() && model.isNotBlank(),
                        ) {
                            Text(if (probing) "实测中…" else "实测思考支持",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    // 「支不支持思考」的结论摊在这里, 别让用户猜
                    Text(
                        thinkStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (thinkOk) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(THINK_LEVELS) { lv ->
                            GlassFilterChip(
                                selected = thinkLevel == lv,
                                onClick = { thinkLevel = lv },
                                label = { Text(lv.label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Text(
                        "开关在聊天输入框的「深度思考」按钮上，随手可切。这里选的是开启后用的强度；" +
                            "上游不认这个参数时会自动忽略后重试。\n" +
                            "「实测思考支持」会真的发两次最小请求（一次带思考参数、一次不带）来判定，" +
                            "比按模型名猜准；测不出确定结论时会如实说明，不硬给一个答案。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ---- 工具调用轮数上限 ----
                // 用户报过「已连续调用工具 4 轮仍未得出结论」—— 加了「指令专家」之后,
                // 一轮问答里模型可能要 check_command 核一遍、mc_wiki 查一次、改完再核一遍,
                // 4 轮不够。给个滑杆, 用户自己权衡"答得更透"和"更慢/更费"。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("工具调用轮数上限", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$toolRounds 轮",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = toolRounds.toFloat(),
                    onValueChange = { toolRounds = it.roundToInt() },
                    valueRange = AI_TOOL_ROUNDS_MIN.toFloat()..AI_TOOL_ROUNDS_MAX.toFloat(),
                    // steps = 区间内的**中间**刻度数 → (12-2)-1 = 9, 正好落成整数 2..12
                    steps = AI_TOOL_ROUNDS_MAX - AI_TOOL_ROUNDS_MIN - 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "开启「智能搜索」或「指令专家」后，模型最多「调工具 → 看结果 → 再调」几轮。" +
                        "调高 = 复杂问题更容易答透，但更慢；官方通道每一轮都是一次计费请求，会更费积分。\n" +
                        "正常问答 1~2 轮就收敛，撞到这个上限说明问题太大或工具没给出有用结果。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- 语法核对 / 客户端复查 (2026-10-05) ----
                // 用户要求「ai 加一个专门核对语法的…可以切换」+ 输入框里的胶囊。
                // 这里给出**说明与默认值**, 实际切换在输入框的胶囊上(随手可切, 不用进来)。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("语法核对", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "开启后给「指令专家」补上 check_command 工具：AI 输出指令前会先把指令交给它" +
                                "核对语法与方块/物品 ID。关掉则只查资料、不强制自查，可省一轮等待。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    GlassSwitch(checked = syntaxCheck, onCheckedChange = { syntaxCheck = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("客户端复查", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "AI 给出最终答案后，App 自己再扫一遍输出里的指令，核对方块 / 物品 ID" +
                                "（不信 AI 说「我查过了」）。发现问题会再让它改一次。关掉可省一轮。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    GlassSwitch(checked = recheckOn, onCheckedChange = { recheckOn = it })
                }

                // ---- ③ 联网搜索 (可选) ----
                CfgSection(
                    "3", "联网搜索",
                    "可选。不配就没有联网能力；配置后输入框会出现「智能搜索」按钮。",
                    status = if (searchEngine.isNotBlank() && searchKey.isNotBlank()) "已配置" else "",
                    statusOk = true,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        GlassFilterChip(
                            selected = searchEngine.isBlank(),
                            onClick = { searchEngine = ""; searchKey = ""; searchTest = null },
                            label = { Text("不用", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    items(SEARCH_ENGINES) { e ->
                        GlassFilterChip(
                            selected = searchEngine == e.id,
                            // ★ 换引擎必须跟着换 Key 框里的内容 —— Key 是按引擎分开存的,
                            //   否则上一家的 Key 会顶着新引擎的标签留着, 用户还看不出来(掩码)。
                            onClick = {
                                searchEngine = e.id
                                searchKey = Session.searchKeyFor(e.id)
                                searchTest = null
                            },
                            label = { Text(e.name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                if (curEngine != null) {
                    Text(curEngine.hint, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (curEngine.warn.isNotBlank()) {
                        Text(curEngine.warn, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(
                        value = searchKey,
                        onValueChange = { searchKey = it; searchTest = null },
                        label = { Text("${curEngine.name} API Key") },
                        singleLine = true,
                        visualTransformation = if (searchKeyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        // ★ capitalization = None 是必需的: Key 都以小写 `sk-` 开头,
                        //   输入法自动首字母大写会把它变成 `Sk-`, 服务端只回一句
                        //   "Invalid or expired API key" —— 用户完全看不出是自己被输入法坑了。
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            capitalization = KeyboardCapitalization.None,
                        ),
                        trailingIcon = {
                            IconButton(onClick = { searchKeyVisible = !searchKeyVisible }) {
                                Icon(
                                    if (searchKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    if (searchKeyVisible) "隐藏" else "显示",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 字符数摊出来 —— Key 抄错一个字符时, 长度对不上是一眼就能看出的线索。
                    // ★ 这里报的是**洗完之后的**长度: 若与原始长度不同, 说明里面有不可见字符,
                    //   直接点出来, 免得用户对着"看起来一模一样"的两串字符怀疑人生。
                    if (searchKey.isNotBlank()) {
                        val ck = cleanApiKey(searchKey)
                        val dirty = ck.length != searchKey.trim().length
                        Text(
                            "已填 ${ck.length} 个字符（开头是「${ck.take(3)}」）" +
                                (if (dirty) " · 已自动清掉 ${searchKey.trim().length - ck.length} 个不可见字符" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (dirty) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // ★ 顺序 (2026-10-01 用户要求): 「去申请 Key」**优先级最高** ——
                    //   没 Key 后面两个按钮全是摆设, 所以它紧跟 Key 输入框; 「粘贴」「测试搜索」排后面。
                    //   整组走 FlowRow: 窄屏折行, 不会把「测试搜索」裁出可视区。
                    FlowRow(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassTextButton(onClick = { openConsole(curEngine.consoleUrl) }) {
                            Text("去申请 Key", style = MaterialTheme.typography.labelMedium)
                        }
                        GlassTextButton(onClick = {
                            // 走系统剪贴板而不是 Compose 的 LocalClipboardManager ——
                            // 后者在新版 Compose 里已被替换, 换个 BOM 版本就可能编译不过。
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = cm?.primaryClip?.let { c ->
                                if (c.itemCount > 0) c.getItemAt(0).coerceToText(ctx).toString() else ""
                            }
                            if (!clip.isNullOrBlank()) {
                                searchKey = cleanApiKey(clip)
                                searchTest = null
                            }
                        }) {
                            Text("粘贴", style = MaterialTheme.typography.labelMedium)
                        }
                        GlassActionButton(
                            onClick = {
                                searchTesting = true
                                searchTest = null
                                scope.launch {
                                    searchTest = AiSearch.test(searchEngine, searchKey)
                                    searchTesting = false
                                }
                            },
                            enabled = !searchTesting && searchKey.isNotBlank(),
                        ) {
                            Text(if (searchTesting) "测试中…" else "测试搜索",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    // 结果紧跟按钮 —— 点完眼睛不用往下找
                    val st = searchTest
                    if (st != null) {
                        Text(
                            (if (st.ok) "✓ " else "✗ ") + st.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (st.ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!curEngine.verified) {
                        Text(
                            "该引擎接口形态未经实测",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // ---- 操作区 ----
                // 一条分隔线把「设置项」和「动作」切开 —— 之前保存按钮紧贴在搜索 Key 下面,
                // 视觉上像是搜索那块的按钮。
                // ★ 「测试连接」不在这里 —— 2026-10-01 挪到上面「模型」那一行了(见那里的注释)。
                //   它测的是某一组 地址·Key·模型, 放在这里跟「保存」并排会被当成"测整个链路"。
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                // ★ 2026-10-01 用户要求: 「保存」和「清除配置」放**同一行**, 并**删掉「关闭」**。
                //   关闭本来就冗余 —— 系统返回键、点对话框外部都能关, 摆着只会把「保存」挤到单独一行。
                //   「去申请 Key」(当前服务商的控制台) 也收进这一行。窄屏由 FlowRow 自动折行。
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassActionButton(
                        filled = true,
                        onClick = {
                            Session.aiUseOfficial = useOfficial
                            Session.aiUseFree = useFree
                            // 选了官方就**别动**用户已经存好的自备配置 —— 下次切回来还在
                            if (useOwnKey) {
                                Session.aiApiBase = normalizeAiBase(base)
                                Session.aiApiKey = cleanApiKey(key)
                                Session.aiModel = model.trim().ifBlank { "gpt-4o-mini" }
                                Session.aiThinkLevel = thinkLevel.id
                            }
                            Session.aiSearchEngine = searchEngine
                            // 只写当前这家引擎的那一格; searchEngine 为空(不用)时不写, 老 Key 留着
                            if (searchEngine.isNotBlank()) {
                                Session.setSearchKeyFor(searchEngine, searchKey.trim())
                            }
                            // 工具轮数上限 —— 两条通道共用, 所以**不受 useOfficial 影响**
                            Session.aiToolRounds = toolRounds
                            // ★ 2026-10-05: 两个新增开关也落盘
                            Session.aiSyntaxCheckOn = syntaxCheck
                            Session.aiRecheckOn = recheckOn
                            onDismiss()
                        },
                    ) { Text("保存") }
                    if (useOwnKey && preset?.consoleUrl?.isNotBlank() == true) {
                        GlassTextButton(onClick = { openConsole(preset.consoleUrl) }) {
                            Text("去申请 Key", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (useOwnKey) {
                        GlassTextButton(
                            onClick = {
                                base = ""
                                key = ""
                                model = ""
                                providerId = CUSTOM_PROVIDER_ID
                                result = null
                                models = emptyList()
                                modelErr = ""
                                Session.aiApiBase = "https://api.openai.com/v1/"
                                Session.aiApiKey = ""
                                Session.aiModel = "gpt-4o-mini"
                                Session.aiModelList = ""
                                // 配置清空了, 自然回落到官方 AI
                                Session.aiUseOfficial = true
                                useOfficial = true
                                Session.aiUseFree = false
                                useFree = false
                            },
                        ) { Text("清除配置", style = MaterialTheme.typography.labelMedium) }
                    }
                }

                Text(
                    when {
                        useFree ->
                            "免费通道：提问经二改作者的服务器转发，不消耗积分、无需自备 Key。" +
                                "上游密钥只在服务端，不会下发到本机。"
                        useOfficial ->
                            "官方通道：提问会经本 App 的服务器转发给官方模型，按积分计费。"
                        else ->
                            "Key 只存在本机 (加密保存)。请求由本机直连服务商, 不经过本 App 的服务器。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showPicker) {
        AiModelPickerDialog(
            models = models,
            current = model.trim(),
            onPick = { model = it; result = null; showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * 设置面板的**分区标题**（2026-10-01 六批后整理）。
 *
 * 加它是因为用户反馈"设置有点乱" —— 改之前整个面板是一条竖直长龙，标题只有
 * 「思考强度」「智能搜索」两个 `labelLarge`，跟正文输入框的 label 一个字号，
 * 而且 `HorizontalDivider` 时有时无（思考段有、供应商段没有），扫下来找不到分界。
 *
 * 现在统一成：**分隔线 + 编号徽章 + 标题 +（可选）一句话说明 / 右侧状态**。
 * 编号是刻意的 —— 面板有 3 块，编号让"我从哪进来、还剩几块"一眼可见。
 *
 * @param step   编号徽章（"1" / "2" / "3"）
 * @param title  分区标题
 * @param hint   标题下的一行说明；空串 = 不显示
 * @param status 标题右侧的状态字（如「已配置」）；空串 = 不显示
 */
@Composable
private fun CfgSection(
    step: String,
    title: String,
    hint: String = "",
    status: String = "",
    statusOk: Boolean = false,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                step,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (status.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (statusOk) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (hint.isNotBlank()) {
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 模型选择列表 —— 带能力标签, 点一下即选中 */
@Composable
private fun AiModelPickerDialog(
    models: List<AiModelInfo>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .heightIn(max = 560.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择模型", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${models.size} 个可用模型。标签来自服务商声明的能力。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(models) { m ->
                        val sel = m.id == current
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (sel) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(m.id) },
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(
                                    m.id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                val tags = buildList {
                                    // 实测 > 上游声明 > 按名猜
                                    if (m.probed) add(if (m.reasoning) "实测支持思考" else "实测不支持思考")
                                    else if (m.reasoning) add(if (m.guessed) "可能支持思考" else "支持思考")
                                    if (m.vision) add("视觉")
                                    if (m.contextLen > 0) add(fmtCtx(m.contextLen))
                                }
                                if (tags.isNotEmpty()) {
                                    Text(
                                        tags.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Row {
                    Spacer(Modifier.weight(1f))
                    GlassTextButton(onClick = onDismiss) {
                        Text("关闭", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** 上下文长度人类化: 1048576 → "1M", 262144 → "256K" */
private fun fmtCtx(n: Long): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}M 上下文"
    n >= 1024 -> "${n / 1024}K 上下文"
    else -> "$n 上下文"
}
