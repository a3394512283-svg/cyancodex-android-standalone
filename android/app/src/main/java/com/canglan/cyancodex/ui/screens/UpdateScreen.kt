package com.canglan.cyancodex.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import com.canglan.cyancodex.BuildConfig
import com.canglan.cyancodex.R
import com.canglan.cyancodex.data.UpdateInfo
import com.canglan.cyancodex.data.Updater
import com.canglan.cyancodex.ui.components.versionNewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/* ============================================================================
 *  App 更新页 —— 独立的整屏页面
 *
 *  为什么单独一页：原来是 AlertDialog，进度只有一行「下载中 42%」，弱网下
 *  3 MB 的包要下几十秒，对话框看不清进度又容易被误关。
 *
 *  ★ 视觉用自己的品牌色（沧蓝工坊的深绿 + 草绿），不照抄任何系统升级页。
 *  ★★ 不要在页面里加 statusBarsPadding / navigationBarsPadding ——
 *    NavHost 外面那层 Scaffold 已经把 systemBars 的内缩算进 padding 了，
 *    再加一次就是顶部一条又粗又黑的多余空隙（上一版的实际表现）。
 * ========================================================================== */

private val BgTop = Color(0xFF101A15)
private val BgBottom = Color(0xFF0A110D)
private val CardBg = Color(0xFF16221C)
private val CardBgHi = Color(0xFF1E2D24)
private val CardLine = Color(0x1F4ADE80)
private val TextHi = Color(0xFFE7F2EA)
private val TextLo = Color(0xFF9DB4A5)
private val Green2 = Color(0xFF4ADE80)

/** 胶囊按钮调色板 —— 对齐 React 规格里的 zinc / green 值 */
private val PillBlack = Color(0xFF09090B)   // zinc-950
private val PillTrack = Color(0xFFE4E4E7)   // zinc-200
private val PillDone = Color(0xFF16A34A)    // green-600
// ★ 规格里的 disabled 是 zinc-300，那是配浅色预览底用的。这个页面是深绿，
//   浅灰胶囊会变成一块突兀的亮斑 —— 换成 zinc-800，观感一致又不抢眼。
private val PillDisabled = Color(0xFF27272A) // zinc-800
private val PillDisabledText = Color(0xFF71717A)
private val PillOnDark = Color(0xFFFAFAFA)  // 黑底上的字
private val PillOnLight = Color(0xFF18181B) // 浅轨上的字（mix-blend-difference 的等效）

private enum class Phase { CHECKING, NO_UPDATE, READY, DOWNLOADING, VERIFYING, DOWNLOADED, FAILED }

/** 胶囊按钮的三种视觉状态（对应 React 里的 idle / downloading / done） */
private enum class PillMode { Idle, Running, Done }

/** 一条更新日志：小标题 + 若干条正文 */
private data class NoteCard(val title: String, val lines: List<String>)

/**
 * 把服务端下发的 notes 拆成卡片。
 * 服务端写法是「标题：正文」（如「体积优化：APK 2.49 MB → 2.38 MB」），
 * 拆开才排得出「体积优化 / 界面」这种分块；没有冒号的行统一归到「更新内容」。
 */
private fun buildNoteCards(notes: List<String>): List<NoteCard> {
    val titled = mutableListOf<NoteCard>()
    val loose = mutableListOf<String>()
    for (raw in notes) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val idx = line.indexOfFirst { it == '：' || it == ':' }
        if (idx in 1..(line.length - 2)) {
            val t = line.substring(0, idx).trim()
            val body = line.substring(idx + 1).trim()
            if (t.length <= 12 && body.isNotEmpty()) {
                titled += NoteCard(t, listOf(body))
                continue
            }
        }
        loose += line
    }
    if (titled.isEmpty()) return listOf(NoteCard("更新内容", loose.ifEmpty { listOf("本版本为常规维护更新。") }))
    if (loose.isNotEmpty()) titled += NoteCard("其他", loose)
    return titled
}

private fun fmtSize(n: Long): String = when {
    n <= 0L -> "未知大小"
    n >= 1L shl 30 -> String.format("%.2f GB", n / 1073741824.0)
    n >= 1L shl 20 -> String.format("%.2f MB", n / 1048576.0)
    else -> String.format("%.0f KB", n / 1024.0)
}

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return c as? Activity
}

@Composable
fun UpdateScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var phase by remember { mutableStateOf(Phase.CHECKING) }
    var err by remember { mutableStateOf("") }
    var apk by remember { mutableStateOf<File?>(null) }
    var menu by remember { mutableStateOf(false) }
    // 下载完成后先让「已完成」的弹簧动画走完，再自动拉系统安装器
    var autoInstall by remember { mutableStateOf(false) }

    // 进度用 StateFlow：字节回调跑在 IO 线程，StateFlow 写入本身线程安全，
    // Compose 侧 collectAsState 再切回主线程。
    val progress = remember { MutableStateFlow(0f) }
    val bytes = remember { MutableStateFlow(0L to 0L) }
    val speed = remember { MutableStateFlow(0L) }
    val p by progress.collectAsState()
    val b by bytes.collectAsState()
    val sp by speed.collectAsState()

    // 深色页 + 浅色主题时状态栏图标会看不见 —— 进来强制浅色图标，离开还原
    val view = LocalView.current
    DisposableEffect(Unit) {
        val insets = ctx.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        val prev = insets?.isAppearanceLightStatusBars
        insets?.isAppearanceLightStatusBars = false
        onDispose { if (prev != null) insets?.isAppearanceLightStatusBars = prev }
    }

    val check: () -> Unit = {
        scope.launch {
            phase = Phase.CHECKING
            err = ""
            val up = withContext(Dispatchers.IO) { Updater.fetchLatest() }
            if (up == null) {
                phase = Phase.FAILED
                err = "检查更新失败，请检查网络后重试"
            } else {
                info = up
                phase = if (versionNewer(up.version)) Phase.READY else Phase.NO_UPDATE
            }
        }
    }

    LaunchedEffect(Unit) { check() }

    // 「已完成」停留 1.6 秒后自动拉系统安装器；被系统拦下则落到 FAILED 让用户重试
    LaunchedEffect(phase, autoInstall) {
        if (phase == Phase.DOWNLOADED && autoInstall) {
            delay(1700)
            autoInstall = false
            val f = apk
            if (f != null && !Updater.install(ctx, f)) {
                phase = Phase.FAILED
                err = "系统未允许安装，请允许「安装未知应用」后重试"
            }
        }
    }

    val download: () -> Unit = {
        val up = info
        if (up != null) {
            phase = Phase.DOWNLOADING
            err = ""
            progress.value = 0f
            bytes.value = 0L to 0L
            speed.value = 0L
            scope.launch {
                val url = if (up.url.isNotBlank()) Updater.absoluteUrl(up.url) else Updater.apkUrl(up.version)
                var lastPct = -1
                var lastT = System.currentTimeMillis()
                var lastB = 0L
                val f = withContext(Dispatchers.IO) {
                    Updater.download(ctx, url) { done, total ->
                        bytes.value = done to total
                        if (total > 0L) {
                            val pct = (done * 100 / total).toInt()
                            // 只在整百分点变化时写状态，避免 3 MB 的包触发上千次重组
                            if (pct != lastPct) {
                                lastPct = pct
                                progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                                val now = System.currentTimeMillis()
                                val dt = now - lastT
                                if (dt >= 400) {
                                    speed.value = (done - lastB) * 1000 / dt
                                    lastT = now
                                    lastB = done
                                }
                            }
                        }
                    }
                }
                if (f == null) {
                    phase = Phase.FAILED
                    err = "下载失败，可重试或改用浏览器下载"
                    return@launch
                }
                apk = f
                progress.value = 1f
                speed.value = 0L
                // Updater.download 内部已核对 md5 / sha256，这里只切状态，
                // 真正的系统安装器交给上面那个 LaunchedEffect 延迟拉起
                phase = Phase.DOWNLOADED
                autoInstall = true
            }
        }
    }

    val installNow: () -> Unit = {
        autoInstall = false
        val f = apk
        if (f != null && !Updater.install(ctx, f)) {
            phase = Phase.FAILED
            err = "系统未允许安装，请允许「安装未知应用」后重试"
        }
    }

    val openBrowser: () -> Unit = {
        val up = info
        val url = when {
            up == null -> null
            up.url.isNotBlank() -> Updater.absoluteUrl(up.url)
            up.version.isNotBlank() -> Updater.apkUrl(up.version)
            else -> null
        }
        if (url != null) Updater.openInBrowser(ctx, url)
        else android.widget.Toast.makeText(ctx, "暂无可用下载地址", android.widget.Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom)))) {
        Column(Modifier.fillMaxSize()) {

            // ---- 顶栏 ----
            Row(
                Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "返回",
                        tint = TextHi, modifier = Modifier.size(22.dp),
                    )
                }
                Text("检查更新", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextHi)
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, "更多", tint = TextHi, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        containerColor = CardBgHi,
                    ) {
                        DropdownMenuItem(
                            text = { Text("重新检查", color = TextHi, fontSize = 14.sp) },
                            onClick = { menu = false; check() },
                        )
                        DropdownMenuItem(
                            text = { Text("用浏览器下载", color = TextHi, fontSize = 14.sp) },
                            onClick = { menu = false; openBrowser() },
                        )
                    }
                }
            }

            // ---- 内容 ----
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(6.dp))

                // 应用标识
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.usericon),
                        contentDescription = "CyanCodex",
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "CyanCodex", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = TextHi,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "当前版本 v${BuildConfig.VERSION_NAME}",
                            fontSize = 12.sp, color = TextLo,
                        )
                    }
                }

                Spacer(Modifier.height(26.dp))

                // 主标题：状态一句话
                Text(
                    when (phase) {
                        Phase.CHECKING -> "正在检查更新…"
                        Phase.NO_UPDATE -> "当前已是最新版本"
                        Phase.FAILED -> "检查失败"
                        Phase.DOWNLOADING -> "正在下载"
                        Phase.VERIFYING -> "正在校验"
                        else -> "有新版本可用"
                    },
                    fontSize = 27.sp, fontWeight = FontWeight.Bold,
                    color = TextHi, letterSpacing = (-0.4).sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when (phase) {
                        Phase.NO_UPDATE -> "服务器上的最新版就是 v${info?.version ?: BuildConfig.VERSION_NAME}，无需更新。"
                        Phase.FAILED -> err
                        Phase.DOWNLOADED -> "安装包已下载并校验完成，稍后会自动拉起安装。"
                        Phase.VERIFYING -> "正在核对安装包完整性…"
                        Phase.DOWNLOADING -> "下载中，请保持网络连接。"
                        else -> "v${info?.version ?: BuildConfig.VERSION_NAME} 已发布，可直接覆盖安装。"
                    },
                    fontSize = 13.5.sp, color = TextLo, lineHeight = 20.sp,
                )

                Spacer(Modifier.height(20.dp))

                // 两个数据卡
                Row(Modifier.fillMaxWidth()) {
                    StatCard("最新版本", "v" + (info?.version ?: BuildConfig.VERSION_NAME), Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    StatCard("安装包", fmtSize(info?.size ?: 0L), Modifier.weight(1f))
                }

                Spacer(Modifier.height(20.dp))

                // 更新日志（即使已是最新也展示，方便回看本版改了什么）
                buildNoteCards(info?.notes ?: emptyList()).forEach { card ->
                    NoteCardView(card)
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "安装包由自建服务器下发，下载完成后会核对 MD5 / SHA-256 与签名。",
                    fontSize = 11.5.sp, color = Color(0xFF6E8478), lineHeight = 17.sp,
                )
                Spacer(Modifier.height(18.dp))
            }

            // ---- 底部：只有那颗胶囊按钮，直接贴在深色页面上 ----
            //   ★ 规格里的「浅色预览底」是 React 演示页的容器，不属于 App 页面，
            //     照搬过来就是底部一坨白色方块。这里只保留按钮本体。
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 6.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (phase == Phase.DOWNLOADING || phase == Phase.VERIFYING) {
                    Text(
                        buildString {
                            append(fmtSize(b.first))
                            if (b.second > 0) append(" / ").append(fmtSize(b.second))
                            if (sp > 0) append(" · ").append(fmtSize(sp)).append("/s")
                        },
                        fontSize = 11.5.sp,
                        color = TextLo,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                DownloadPill(
                    mode = when (phase) {
                        Phase.DOWNLOADING, Phase.VERIFYING -> PillMode.Running
                        Phase.DOWNLOADED -> PillMode.Done
                        else -> PillMode.Idle
                    },
                    fill = if (phase == Phase.VERIFYING) 1f else p,
                    enabled = phase == Phase.READY || phase == Phase.DOWNLOADED || phase == Phase.FAILED,
                    idleLabel = when (phase) {
                        Phase.CHECKING -> "请稍候"
                        Phase.NO_UPDATE -> "已是最新"
                        Phase.READY -> "下载并安装"
                        Phase.FAILED -> if (apk != null) "立即安装" else "重试"
                        else -> "立即安装"
                    },
                    doneLabel = "已完成",
                    onClick = {
                        when (phase) {
                            Phase.READY -> download()
                            Phase.DOWNLOADED -> installNow()
                            Phase.FAILED -> if (apk != null) installNow() else download()
                            else -> Unit
                        }
                    },
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * 黑色胶囊下载按钮
 *
 * 按 React + Tailwind + Framer Motion 的 DownloadButton 规格移植：
 *   · 尺寸 w-44 h-11 rounded-full  →  176dp × 44dp / 22dp 圆角
 *   · idle 黑底浅字；下载中底色转 zinc-200，黑色填充条从左往右 scaleX
 *   · 百分比用等宽字体；填充区域内的字反色 —— React 用 mix-blend-difference，
 *     Compose 没有混合模式，改成「同一串字画两遍 + clipRect 裁切」等效
 *   · 100% → #16A34A + Check +「已完成」，内容 spring(0.55, 700) 从 0.6 弹到 1
 *   · 1.6 秒后回 idle
 *   · 只动 transform / opacity（scaleX、alpha），不碰 layout，避免逐帧重排
 * ------------------------------------------------------------------------- */
@Composable
private fun DownloadPill(
    mode: PillMode,
    fill: Float,
    enabled: Boolean,
    idleLabel: String,
    doneLabel: String,
    onClick: () -> Unit,
) {
    // Done 只停留 1.6 秒，之后视觉回 idle（规格里的「回 idle」）
    var showDone by remember { mutableStateOf(false) }
    LaunchedEffect(mode) {
        if (mode == PillMode.Done) {
            showDone = true
            delay(1600)
            showDone = false
        } else {
            showDone = false
        }
    }

    val visual = when {
        mode == PillMode.Done && showDone -> PillMode.Done
        mode == PillMode.Running -> PillMode.Running
        else -> PillMode.Idle
    }

    val bg by animateColorAsState(
        targetValue = when {
            visual == PillMode.Done -> PillDone
            visual == PillMode.Running -> PillTrack
            !enabled -> PillDisabled
            else -> PillBlack
        },
        animationSpec = tween(300),
        label = "pillBg",
    )

    // 真实进度是跳变的（每 1% 才写一次状态），这里补间成线性滑动
    val fillAnim = animateFloatAsState(
        targetValue = when (visual) {
            PillMode.Done -> 1f
            PillMode.Running -> fill.coerceIn(0f, 1f)
            PillMode.Idle -> 0f
        },
        animationSpec = tween(200, easing = LinearEasing),
        label = "pillFill",
    )
    // 完成时填充层淡出，底色由 zinc-200 转绿的过程中不留一条黑边
    val fillAlphaAnim = animateFloatAsState(
        targetValue = if (visual == PillMode.Done) 0f else 1f,
        animationSpec = tween(250),
        label = "pillFillAlpha",
    )

    val contentScale = remember { Animatable(1f) }
    LaunchedEffect(visual) {
        if (visual == PillMode.Done) {
            contentScale.snapTo(0.6f)
            contentScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 700f))
        } else {
            contentScale.snapTo(1f)
        }
    }

    Box(
        Modifier
            .width(176.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // 进度轨道：黑色条，左端为原点做 scaleX
        Box(
            Modifier.matchParentSize()
                .graphicsLayer {
                    scaleX = fillAnim.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    alpha = fillAlphaAnim.value
                }
                .background(PillBlack),
        )

        Box(
            Modifier.matchParentSize().graphicsLayer {
                scaleX = contentScale.value
                scaleY = contentScale.value
            },
            contentAlignment = Alignment.Center,
        ) {
            WaitSwap(target = visual, modifier = Modifier.fillMaxSize()) { v ->
                when (v) {
                    PillMode.Idle -> PillLabel(idleLabel, if (enabled) PillOnDark else PillDisabledText)
                    PillMode.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check, null,
                            tint = PillOnDark, modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        PillLabel(doneLabel, PillOnDark)
                    }
                    PillMode.Running -> RunningLabel(fill = fill) { fillAnim.value }
                }
            }
        }
    }
}

/** 等价于 AnimatePresence mode="wait"：先淡出旧的，再淡入新的（用 delay 模拟等待） */
@Composable
private fun WaitSwap(
    target: PillMode,
    modifier: Modifier = Modifier,
    content: @Composable (PillMode) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            (
                fadeIn(tween(160, delayMillis = 130)) +
                    slideInVertically(tween(160, delayMillis = 130)) { it / 3 }
                ).togetherWith(
                fadeOut(tween(130)) + slideOutVertically(tween(130)) { -it / 3 },
            )
        },
        label = "pillSwap",
    ) { v ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content(v) }
    }
}

@Composable
private fun PillLabel(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
    )
}

/**
 * 下载中的百分比。
 * clipFill 在 draw 阶段才读取（不触发重组），裁切边界跟着动画走；
 * 文字本身按 1% 粒度更新，所以整串字只在百分点变化时重画。
 */
@Composable
private fun RunningLabel(fill: Float, clipFill: () -> Float) {
    val text = "${(fill * 100).toInt()}%"
    val st = TextStyle(
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.5.sp,
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 浅色轨道上的字
        Text(text = text, style = st, color = PillOnLight, maxLines = 1, softWrap = false)
        // 黑色填充条上的字：按填充比例裁切后叠上去
        Box(
            Modifier.fillMaxSize().drawWithContent {
                // clipRect 的 lambda 接收者是 DrawScope，会遮蔽 ContentDrawScope.drawContent()，
                // 必须显式指定接收者，否则编译报 "cannot be called in this context"
                clipRect(right = size.width * clipFill().coerceIn(0f, 1f)) {
                    this@drawWithContent.drawContent()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, style = st, color = PillOnDark, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(CardBg)
            .border(1.dp, CardLine, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label, fontSize = 11.5.sp, color = TextLo)
        Spacer(Modifier.height(6.dp))
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextHi)
    }
}

@Composable
private fun NoteCardView(card: NoteCard) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg)
            .border(1.dp, CardLine, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Text(card.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Green2)
        Spacer(Modifier.height(8.dp))
        card.lines.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Box(
                    Modifier.padding(top = 8.dp, end = 9.dp).size(4.dp)
                        .clip(CircleShape).background(TextLo),
                )
                Text(line, fontSize = 13.5.sp, color = TextHi.copy(alpha = 0.86f), lineHeight = 21.sp)
            }
        }
    }
}
