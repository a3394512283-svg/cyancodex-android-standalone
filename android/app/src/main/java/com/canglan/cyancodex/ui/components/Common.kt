package com.canglan.cyancodex.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.canglan.cyancodex.BuildConfig
import com.canglan.cyancodex.data.ApiResp
import com.canglan.cyancodex.data.Command
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.liquidGlassActive
import com.canglan.cyancodex.ui.liquidglass.LiquidButton
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 远程图片加载 (指令效果图等): 内存缓存 + 原始 OkHttp (无 Coil 依赖) */
private val remoteImageCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
private val remoteImageClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(com.canglan.cyancodex.data.ServerDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val fullUrl = if (url.isNullOrBlank()) null
        else if (url.startsWith("/")) Session.baseUrl.trimEnd('/') + url else url
    var bmp by remember(fullUrl) { mutableStateOf(remoteImageCache.get(fullUrl)) }
    LaunchedEffect(fullUrl) {
        if (fullUrl == null) return@LaunchedEffect
        if (bmp == null) {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val req = okhttp3.Request.Builder().url(fullUrl).build()
                    remoteImageClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                    }
                } catch (e: Exception) { null }
            }
            if (loaded != null) { remoteImageCache.put(fullUrl, loaded); bmp = loaded }
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            b.asImageBitmap(), null,
            modifier = modifier.clip(RoundedCornerShape(corner)),
            contentScale = ContentScale.Crop,
        )
    }
}

/** 头像素材库 (Material 图标 + 颜色, 替代 emoji) */
val avatarMap: Map<String, Pair<ImageVector, Color>> = mapOf(
    "person" to (Icons.Default.AccountCircle to Color(0xFF4ADE80)),
    "face" to (Icons.Default.Face to Color(0xFF38BDF8)),
    "smile" to (Icons.Default.EmojiEmotions to Color(0xFFFBBF24)),
    "pets" to (Icons.Default.Pets to Color(0xFFA78BFA)),
    "rocket" to (Icons.Default.Rocket to Color(0xFFF472B6)),
    "star" to (Icons.Default.Star to Color(0xFFF59E0B)),
    "bolt" to (Icons.Default.Bolt to Color(0xFFFBBF24)),
    "fire" to (Icons.Default.LocalFireDepartment to Color(0xFFF87171)),
    "moon" to (Icons.Default.Nightlight to Color(0xFF818CF8)),
    "game" to (Icons.Default.SportsEsports to Color(0xFF34D399)),
    "launch" to (Icons.Default.RocketLaunch to Color(0xFF60A5FA)),
    "diamond" to (Icons.Default.Diamond to Color(0xFF2DD4BF)),
    "heart" to (Icons.Default.Favorite to Color(0xFFF472B6)),
    "thumbs" to (Icons.Default.ThumbUp to Color(0xFF4ADE80)),
    "cake" to (Icons.Default.Cake to Color(0xFFFB923C)),
    "bug" to (Icons.Default.BugReport to Color(0xFFA3E635)),
)

/** 头像内存缓存 (10MB) */
private val avatarCache = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** 加载网络图片的小客户端 */
private val imageClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(com.canglan.cyancodex.data.ServerDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

/**
 * 服务器图片头像: code 以 "/" 开头表示服务器路径(/uploads/...), 否则按内置图标库渲染。
 */
@Composable
fun AvatarIcon(code: String, size: androidx.compose.ui.unit.Dp = 44.dp, onClick: (() -> Unit)? = null) {
    val url = if (code.startsWith("/")) Session.baseUrl.trimEnd('/') + code else null
    val baseMod = Modifier.size(size).clip(CircleShape)
    val clickMod = if (onClick != null) baseMod.clickable { onClick() } else baseMod
    if (url != null) {
        ServerImage(url, size, onClick = onClick)
    } else {
        val (icon, color) = avatarMap[code] ?: (Icons.Default.AccountCircle to Color(0xFF4ADE80))
        Box(
            clickMod.background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.62f))
        }
    }
}

/** 网络图片加载 (圆形容器, 带缓存), 失败时显示占位人像 */
@Composable
fun ServerImage(url: String, size: androidx.compose.ui.unit.Dp, shape: Shape = CircleShape, onClick: (() -> Unit)? = null) {
    var bmp by remember(url) { mutableStateOf(avatarCache.get(url)) }
    LaunchedEffect(url) {
        if (bmp == null) {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val req = okhttp3.Request.Builder().url(url).build()
                    imageClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                    }
                } catch (e: Exception) { null }
            }
            if (loaded != null) {
                avatarCache.put(url, loaded)
                bmp = loaded
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            modifier = if (onClick != null) Modifier.size(size).clip(shape).clickable { onClick() }
                else Modifier.size(size).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            (if (onClick != null) Modifier.size(size).clip(shape).clickable { onClick() }
                else Modifier.size(size).clip(shape)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.6f))
        }
    }
}

/**
 * 顶栏玻璃按钮的采样源 —— 由 [AppTopBar] 提供。
 * null 表示当前不走玻璃(玻璃未开启 / 系统低于 API 31), 此时 [TopBarAction] 回落普通 IconButton。
 */
val LocalTopBarBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 顶栏右侧的图标按钮 —— 玻璃开启时是**玻璃小圆球**, 与左侧返回键同一套外观与尺寸;
 * 否则回落 Material3 的 [IconButton], 与原版逐字一致。
 *
 * 为什么要有这个组件: 各页面的 `actions` 槽里原来写的是 `IconButton` / `TextButton`,
 * 它们拿不到顶栏内部那个 backdrop, 所以只能靠这个组件把采样源接进来。
 * (2026-09-26 用户要求"部分页面有单独的按钮, 也要液态玻璃")
 */
@Composable
fun TopBarAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalTopBarBackdrop.current
    if (backdrop != null) {
        // LiquidButton 没有 enabled 参数(官方原样), 用 alpha + onClick 拦截来等效禁用态
        LiquidButton(
            onClick = { if (enabled) onClick() },
            backdrop = backdrop,
            modifier = modifier
                .size(40.dp)
                .alpha(if (enabled) 1f else 0.38f),
            height = 40.dp,
            contentPadding = 0.dp,
            surfaceColor = glassSurfaceColor(),
        ) {
            content()
        }
    } else {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            content()
        }
    }
}

/**
 * 顶栏右侧的**文字**动作 —— 与 [TopBarAction] 同一块玻璃采样源, 但形状随内容伸缩。
 *
 * 为什么不复用 [TopBarAction]: 它是 40dp **正圆**(size 与 height 同值 + contentPadding = 0),
 * "MIDI" / "JSON" / "复制指令" 这类文字塞进去会被裁掉。这里改成高度 36dp、左右内边距 14dp 的
 * 胶囊, 宽度由内容决定, 视觉上仍是"顶栏上的一块玻璃"。
 *
 * 非玻璃时回落 Material3 [TextButton], 与原版逐字一致。
 */
@Composable
fun TopBarTextAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = LocalTopBarBackdrop.current
    if (backdrop != null) {
        LiquidButton(
            onClick = { if (enabled) onClick() },
            backdrop = backdrop,
            modifier = modifier.alpha(if (enabled) 1f else 0.38f),
            height = 36.dp,
            contentPadding = 14.dp,
            surfaceColor = glassSurfaceColor(),
        ) {
            // ★ 必须与回落分支的 TextButton 对齐, 否则一开玻璃文字就"变绿 + 大一号":
            //   字色 = onSurfaceVariant(TextButtonTokens.LabelColor, 已反编译核实, 不是 Primary);
            //   字号 = labelLarge(LiquidButton 是普通 Row, 不提供 LocalTextStyle,
            //          不补就掉回 Material3 默认的 bodyLarge 16sp)。
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                LocalTextStyle provides MaterialTheme.typography.labelLarge,
            ) { content() }
        }
    } else {
        TextButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

/**
 * 统一装饰顶栏: 顶部渐变底色 + 标题左侧图标 + 可选返回键。
 * 替代各界面裸的 CenterAlignedTopAppBar。
 *
 * [subtitle] 是可选的第二行小字 (2026-10-01 加)。只有 AI Agent 页在用 ——
 * 那一页主标题要固定成模块名「AI Agent」, 对话名没地方放, 就压成副标题。
 * 默认 null, 其余 41 个调用点外观一字不变。
 *
 * [navIcon] / [onNav] 是左侧槽位的第二种用法 (2026-10-01 加): 一级页面(底栏 Tab)没有
 * 返回键, 但可能有侧边栏 —— 用它挂一个菜单键。同样是可选, 默认 null 时行为不变。
 *
 * [onSubtitleClick] 是副标题的**可点**能力 (2026-10-05 加): AI Agent 页把「会话模式」徽标
 * 挤在副标题里, 点它就地重选模式。默认 null = 副标题只是一行说明文字, 与之前逐字节一致;
 * 传了非 null 才在副标题外包一层 `clickable` 并加一个 ⌄ 提示可点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onBack: (() -> Unit)? = null,
    navIcon: ImageVector? = null,
    onNav: (() -> Unit)? = null,
    onSubtitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // 液态玻璃顶栏 (2026-09-26)
    // 这里是**全局共用**的顶栏 —— 41 个文件都用它, 所以改这一处就覆盖了全 App 的返回键。
    // ★ 2026-09-26 修正: 判据原来写作 `liquidGlassActive && onBack != null`, 于是**没有返回键的
    //   页面(AI Agent 等)整条顶栏都不建采样源** → 右侧 actions 槽里的 TopBarAction 拿不到
    //   backdrop, 全部退化成 Material 圆形按钮(用户反馈"你要不看看顶部呢")。
    //   返回键**是否渲染**才该看 onBack, 而**顶栏要不要玻璃**只看开关本身。
    val glass = liquidGlassActive
    // ★ 2026-09-26: 自定义背景图时**自动隐藏顶栏渐变与不透明底色**。
    //   原版顶栏铺两层: 不透明 background(挡住壁纸) + primaryContainer 渐变(品牌绿)。
    //   用户设了壁纸后, 这两层把图片顶部整块糊掉, 且品牌绿与壁纸色调打架
    //   (用户反馈"顶部的颜色(渐变)…自定义壁纸时强制关闭, 让 ui 背景更好看")。
    //   判据直接读背景图状态, 不额外加开关 —— 免得用户还要记得去关另一个键。
    val hasCustomBg = AppState.backgroundImage.isNotBlank()
    // 采样源: 顶栏自己的背景层, 做成一个**独立兄弟节点**。
    // ⚠️ 玻璃按钮绝不能画在采样节点内部 —— layerBackdrop 录的是该节点全部绘制内容,
    //    按钮若在里面就会采到上一帧的自己 → 静止怪像 + 拖影(底栏踩过同一个坑)。
    val backdrop = rememberLayerBackdrop()
    val gradient = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0f),
        )
    )
    Box(Modifier.fillMaxWidth()) {
        // 背景层 = 采样源。多铺一层不透明底的理由: 采样纹理带 alpha 的话,
        // 模糊/折射会把"透明"一起采进来, 玻璃会发灰。
        // 全部页面用的都是 Scaffold 默认底色(= colorScheme.background), 所以这层不改变外观。
        // ⚠️ 有壁纸时**必须整层隐身**(透明), 否则图片顶部被这两层盖住, 主题也就无从谈起。
        Box(
            Modifier
                .matchParentSize()
                .then(if (glass) Modifier.layerBackdrop(backdrop) else Modifier)
                .then(
                    if (hasCustomBg) Modifier
                    else Modifier.background(MaterialTheme.colorScheme.background).background(gradient)
                )
        )
        // 把采样源交给 actions 槽里的 TopBarAction —— 右侧按钮与左侧返回键共用同一个 backdrop,
        // 视觉上才是"同一块玻璃上的两个球"。
        CompositionLocalProvider(LocalTopBarBackdrop provides if (glass) backdrop else null) {
        CenterAlignedTopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    // 没有副标题时保持原来的单行 Text —— 不加 Column 就不会改变行高,
                    // 其余 41 个页面(全是单标题)的顶栏高度与基线原样不动。
                    if (subtitle.isNullOrBlank()) {
                        Text(title, maxLines = 1)
                    } else {
                        Column {
                            Text(title, maxLines = 1)
                            // ★ 2026-10-05: onSubtitleClick != null 时副标题可点。
                            //   加个 ⌄ 是因为"一行灰字能点"在视觉上毫无提示 —— 用户不会去试。
                            //   Spacer 与省略号宽度都极短, 默认(null)分支一字未动。
                            val subMod = Modifier
                                .widthIn(max = 180.dp)
                                .then(
                                    if (onSubtitleClick != null)
                                        Modifier.clickable(onClick = onSubtitleClick)
                                    else Modifier
                                )
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = subMod) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (onSubtitleClick != null) {
                                    Spacer(Modifier.width(2.dp))
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        "切换会话模式",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            navigationIcon = {
                // 左侧槽位两种用法, 共用同一套玻璃处理:
                //   ① onBack —— 二级页面的返回键 (41 处调用点, 行为与外观一字不变)
                //   ② onNav  —— 一级页面(底栏 Tab)的**菜单键**。
                //      2026-10-01 用户反馈: "那个聊天记录应该改成一个在左边的三条横杠的侧边栏"。
                //      原先它挤在右侧 actions 里当第 2 个圆钮 (Icons.Default.Forum —— 实心对话
                //      气泡带个左下小尾巴), 夹在 + 和 ⚙ 两个描边图标中间看着就是歪的;
                //      而且"开侧边栏"这个动作本来就该在左边。
                val leadIcon = if (onBack != null) Icons.AutoMirrored.Filled.ArrowBack else navIcon
                val leadClick = onBack ?: onNav
                val leadDesc = if (onBack != null) "返回" else "菜单"
                if (leadIcon != null && leadClick != null) {
                    if (glass) {
                        // 官方 LiquidButton 做正圆: size 与 height 同值 + contentPadding = 0。
                        // 表面色用中性玻璃色(与指令库 FAB 一致), 不加 tint —— 返回/菜单键是"中性玻璃球",
                        // 着主色会读起来像一个"动作按钮", 与发送键抢视觉。
                        LiquidButton(
                            onClick = leadClick,
                            backdrop = backdrop,
                            modifier = Modifier.size(40.dp),
                            height = 40.dp,
                            contentPadding = 0.dp,
                            surfaceColor = glassSurfaceColor(),
                        ) {
                            Icon(
                                leadIcon, leadDesc,
                                // ★ 与回落分支的 IconButton 对齐: 顶栏 navigationIcon 槽的
                                //   contentColor 是 colorScheme.onSurface(实测 0xFF16221C,
                                //   而 onSurfaceVariant 是 0xFF4E6156 —— 别搞混)。
                                //   原先这里写死纯黑/纯白(照搬官方 demo 的底栏做法), 一开玻璃
                                //   返回箭头就从 onSurface 变成 #000000, 属于"颜色出现问题"的一项。
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        IconButton(onClick = leadClick) { Icon(leadIcon, leadDesc) }
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
        )
        }
    }
}

/**
 * 全局背景图 (意见 id=22) —— 画在整个 App 的**最底层**, 所有页面共用同一张。
 *
 * 2026-09-26 用户要求"背景图改成全局"。原先它只画在主页自己的内容层里, 所以只有主页看得到。
 * 现在提到 MainActivity 的 Theme 之内、AppNav 之外, 并配套把 `colorScheme.background` 置为
 * 全透明(见 `CyanCodexTheme`) —— 否则各页 Scaffold 的默认容器色会把它整个盖住。
 *
 * 不做任何缓存以外的处理: 文件是设置页从相册拷进 filesDir 的 `custom_bg.jpg`, 用 Crop 铺满。
 */
@Composable
fun GlobalBackgroundImage() {
    val path = AppState.backgroundImage
    if (path.isBlank()) return
    // ⚠️ decodeFile 只看文件头、不看扩展名 —— 设置页是"原样拷贝源文件字节"到 custom_bg.jpg,
    //    所以这里拿到的可能是 PNG 内容配 .jpg 名字, 这没问题(BitmapFactory 能识别)。
    val bmp = remember(path) {
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    } ?: return
    Image(
        bmp.asImageBitmap(), null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
}

/** 版本号比较: 公告版本 > 当前安装版本(BuildConfig.VERSION_NAME) */
fun versionNewer(v: String?): Boolean {
    if (v.isNullOrBlank()) return false
    val cur = BuildConfig.VERSION_NAME.split('.').mapNotNull { it.toIntOrNull() }
    val nv = v.trim().removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(cur.size, nv.size)) {
        val c = cur.getOrElse(i) { 0 }
        val n = nv.getOrElse(i) { 0 }
        if (n != c) return n > c
    }
    return false
}

/** 版本号比较: 当前版本 < 最低版本 (用于强制更新判断) */
fun versionBelow(cur: String?, min: String?): Boolean {
    if (min.isNullOrBlank()) return false
    val cv = cur?.trim()?.removePrefix("v")?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
    val mv = min.trim().removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(cv.size, mv.size)) {
        val c = cv.getOrElse(i) { 0 }
        val m = mv.getOrElse(i) { 0 }
        if (c != m) return c < m
    }
    return false
}

/** 公告类型徽章 (首页/公告列表共用) */
@Composable
fun AnnBadge(type: String) {
    val (label, color) = when (type) {
        "update" -> "更新" to Color(0xFF4ADE80)
        "punish" -> "处罚" to Color(0xFFF87171)
        "activity" -> "活动" to Color(0xFFFBBF24)
        else -> "公告" to Color(0xFF38BDF8)
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.18f)) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/**
 * 计算从注册日期(yyyy-MM-dd[ HH:mm:ss])到今天的自然天数; 解析失败返回 -1。
 * 用于「已陪伴 CyanCodex N 天」。
 */
fun daysSince(dateStr: String): Int = try {
    val d = dateStr.trim().take(10).split("-")
    val reg = java.time.LocalDate.of(d[0].toInt(), d[1].toInt(), d[2].toInt())
    java.time.temporal.ChronoUnit.DAYS.between(reg, java.time.LocalDate.now()).toInt()
} catch (e: Exception) { -1 }

fun copyToClipboard(context: Context, text: String, hint: String = "已复制") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("command", text))
    Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
}

/**
 * 网络错误文案: 连接类错误给出中文提示; 其他错误去掉服务器地址/IP, 避免暴露后端地址
 */
fun errText(e: Exception?): String {
    var msg = e?.message?.trim().orEmpty()
    val lower = msg.lowercase()
    if (msg.isNotBlank() && (
            lower.contains("failed to connect") ||
            lower.contains("connectexception") ||
            lower.contains("sockettimeout") ||
            lower.contains("timeout") ||
            lower.contains("refused") ||
            lower.contains("unknownhost") ||
            lower.contains("unreachable") ||
            lower.contains("socketexception"))) {
        return "无法连接服务器，请检查网络后重试（生成器/编辑器等离线功能不受影响）"
    }
    if (msg.isBlank()) msg = "网络错误，请检查网络连接"
    // 兜底: 去掉 URL 与 IP(含端口), 统一显示为"服务器"
    msg = msg.replace(Regex("""https?://[^\s/),;：]+"""), "服务器")
    msg = msg.replace(Regex("""\d{1,3}(\.\d{1,3}){3}(:\d{1,5})?"""), "服务器")
    msg = msg.trim()
    if (msg.isBlank()) msg = "无法连接服务器，请稍后重试"
    return msg
}

/** 简化网络调用包装 (协程取消必须继续传播, 不能当业务错误吞掉) */
suspend fun <T> safeApi(block: suspend () -> ApiResp<T>): ApiResp<T> =
    try { block() }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) {
        com.canglan.cyancodex.data.DebugStore.log("Api", "调用异常: ${e.javaClass.simpleName}: ${e.message}")
        ApiResp(ok = false, error = errText(e))
    }

/**
 * 解析指令 TXT 文件:
 * - `#` 开头 = 注释/标题(可作下一条指令的标题)
 * - `/` 开头 = 一条指令
 * - 其余行忽略
 * 返回 (标题, 指令) 列表
 */
fun parseCommandTxt(content: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    var pendingTitle: String? = null
    content.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("#")) {
            val t = line.removePrefix("#").trim()
            if (t.isNotBlank()) pendingTitle = t.take(128)
            return@forEach
        }
        if (line.startsWith("/")) {
            out.add((pendingTitle ?: line.take(20)) to line)
            pendingTitle = null
        }
    }
    return out
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun CopyButton(text: String, modifier: Modifier = Modifier, hint: String = "已复制") {
    val ctx = LocalContext.current
    Button(
        onClick = { copyToClipboard(ctx, text, hint) },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("复制", style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 解析指令属性 (意见 #27): tags 中编码的 __block=xx|__chain=xx|__redstone=xx|__delay=xx
 * 返回 (方块类型, 方块颜色, 附加属性列表[(文本, 颜色)])
 */
fun parseCommandProps(tags: String): Triple<String, Color, List<Pair<String, Color>>> {
    val props = tags.split(",").map { it.trim() }
        .filter { it.startsWith("__") }
        .associate { kv ->
            val idx = kv.indexOf('=')
            if (idx > 0) kv.substring(2, idx) to kv.substring(idx + 1) else "" to ""
        }
    val block = props["block"] ?: ""
    val blockColor = when (block) {
        "pulse" -> Color(0xFFFBBF24)   // 脉冲 黄
        "chain" -> Color(0xFF4ADE80)   // 连锁 绿
        "cycle" -> Color(0xFFC084FC)   // 循环 紫
        "chat" -> Color(0xFF94A3B8)    // 聊天栏 灰
        else -> Color(0xFF94A3B8)
    }
    val extras = mutableListOf<Pair<String, Color>>()
    when (props["chain"]) {
        "on" -> extras.add("连锁" to Color(0xFF4ADE80))
        "off" -> extras.add("无连锁" to Color(0xFF94A3B8))
    }
    when (props["redstone"]) {
        "keep" -> extras.add("保持开启" to Color(0xFF4ADE80))
        "need" -> extras.add("需要红石" to Color(0xFFF87171))
    }
    val delay = props["delay"]
    if (delay != null && delay != "0") extras.add("延迟${delay}刻" to Color(0xFF38BDF8))
    return Triple(block, blockColor, extras)
}

/** 指令属性徽章 (颜色标识, 意见 #27) */
@Composable
private fun PropBadge(text: String, color: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.18f)) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** 指令卡片: 列表项通用样式 */
@Composable
fun CommandCard(
    cmd: Command,
    onClick: () -> Unit,
    favorited: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onAuthorClick: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cmd.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (cmd.source == "user" && cmd.status == "pending") {
                    StatusBadge("审核中", Color(0xFFFBBF24))
                    Spacer(Modifier.width(6.dp))
                } else if (cmd.source == "user" && cmd.status == "rejected") {
                    StatusBadge("已驳回", Color(0xFFF87171))
                    Spacer(Modifier.width(6.dp))
                }
                if (cmd.source == "platform") {
                    AssistChip(
                        onClick = {},
                        label = { Text(if (cmd.quality == "high") "平台·高质" else "平台·低质", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (cmd.quality == "high")
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    )
                }
            }
            // 驳回理由 (审核员驳回时填写, 展示给作者)
            if (cmd.source == "user" && cmd.status == "rejected" && !cmd.reviewNote.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Surface(shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)) {
                    Text("驳回理由：${cmd.reviewNote}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            if (cmd.description.isNotBlank()) {
                Text(cmd.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            // 命令方块属性彩色徽章 (意见 #27)
            val (blockKey, blockColor, propExtras) = parseCommandProps(cmd.tags)
            if (blockKey.isNotBlank() || propExtras.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (blockKey.isNotBlank()) {
                        PropBadge(
                            when (blockKey) {
                                "pulse" -> "脉冲"; "chain" -> "连锁"; "cycle" -> "循环"; "chat" -> "聊天栏"
                                else -> blockKey
                            }, blockColor)
                    }
                    propExtras.forEach { (t, c) -> PropBadge(t, c) }
                }
            }
            if (!cmd.image.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                RemoteImage(cmd.image, modifier = Modifier.fillMaxWidth().height(150.dp), corner = 8.dp)
            }
            Spacer(Modifier.height(6.dp))
            val darkPreview = BedrockHighlight.isDarkSurface(MaterialTheme.colorScheme.surface)
            Text(
                BedrockHighlight.highlight(cmd.command.lines().firstOrNull().orEmpty(), darkPreview),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val authorText = buildString {
                    append(cmd.categoryLabel())
                    if (cmd.mcVersion.isNotBlank()) append(" · ").append(cmd.mcVersion)
                }
                if (cmd.author?.isNotBlank() == true && cmd.source == "user") {
                    Text(
                        authorText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "@${cmd.author}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(enabled = onAuthorClick != null) { onAuthorClick?.invoke() }
                            .padding(horizontal = 2.dp),
                    )
                    // 管理员徽章 (意见 #43)
                    if (cmd.authorRole == "admin") {
                        Spacer(Modifier.width(4.dp))
                        Surface(shape = MaterialTheme.shapes.small, color = Color(0xFFFBBF24).copy(alpha = 0.18f)) {
                            Text("管理员", color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                    // 官方认证徽章 (红色)
                    if (cmd.authorOfficial != 0) {
                        Spacer(Modifier.width(4.dp))
                        Surface(shape = MaterialTheme.shapes.small, color = Color(0xFFF87171).copy(alpha = 0.18f)) {
                            Text("官方", color = Color(0xFFF87171), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                } else {
                    Text(
                        authorText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onFavorite != null) {
                    IconButton(onClick = onFavorite, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (favorited) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${cmd.likeCount}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (cmd.commentCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("${cmd.commentCount}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 附件角标 (可以只发附件)
                if (cmd.fileCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AttachFile, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("${cmd.fileCount}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                // 一键复制指令 (参考 CHelper/BNM: 复制原始命令文本)
                if (onCopy != null) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, "复制指令",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** 指令审核状态徽章 */
@Composable
fun StatusBadge(label: String, color: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.18f)) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

fun Command.categoryLabel(): String = when (category) {
    "survival" -> "生存"; "adventure" -> "冒险"; "redstone" -> "红石"; "scoreboard" -> "计分板"; "gamerule" -> "游戏规则"; else -> "其他"
}

@Composable
fun OutlinedTextField2(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    placeholder: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotBlank()) { { Text(placeholder) } } else null,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(10.dp),
    )
}

@Composable
fun CommandOutputBox(commands: List<String>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val text = commands.joinToString("\n")
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("生成结果 (${commands.size} 条)", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Button(onClick = { copyToClipboard(ctx, text) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("全部复制", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            // 每条命令单独展示, 可分别复制 (参考 CHelper/BNM)
            commands.forEachIndexed { idx, line ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF0A0F0C), RoundedCornerShape(8.dp))
                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)) {
                    Text("${idx + 1}", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4ADE80), modifier = Modifier.width(22.dp))
                    Text(line, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFE7F2EA),
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { copyToClipboard(ctx, line, "已复制第 ${idx + 1} 条") },
                        modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.ContentCopy, "复制第${idx + 1}条",
                            tint = Color(0xFF9DB4A5), modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
