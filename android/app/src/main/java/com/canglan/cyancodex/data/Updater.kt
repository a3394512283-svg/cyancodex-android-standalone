package com.canglan.cyancodex.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 更新包下载/安装:
 * 发版约定 APK 托管在 服务器 /downloads/CyanCodex-vX.Y.Z.apk
 * 下载地址: 源站 fwq.pw0769.site (CDN app.bluestudio.cn 故障, 已回退源站)
 */
object Updater {

    private const val TAG = "CyanUpdater"

    /** 官方包名 (applicationId) */
    private const val OFFICIAL_PACKAGE = "com.canglan.cyancodex"

    /**
     * 是否为共存版 —— applicationId 与官方不一致。
     *
     * 共存版无法通过官方渠道自更新: 官方 APK 的 applicationId 是 [OFFICIAL_PACKAGE],
     * 装下去会变成"另一个 App", 而不是升级本版。所以共存版必须走手动更新,
     * 且不能参与"强制更新门禁"(否则会被永久锁在不可关闭的弹窗里)。
     *
     * 官方版下本方法恒为 false, 行为与改动前完全一致。
     */
    fun isCoexistBuild(context: Context): Boolean =
        context.packageName != OFFICIAL_PACKAGE

    /**
     * 本版能否走「下载官方包 → 调系统安装器」这条自更新通道。
     *
     * ★★ 2026-10-07 新增。**共存版不能走这条路** —— 官方包 applicationId 是
     * [OFFICIAL_PACKAGE]，本版是 `...cyancodex.com`，`install()` 里的 applicationId
     * 防呆会拒绝；而那个拒绝是**静默的**（只打一条 Log.w，用户零提示），
     * 调用方紧接着回退 `openInBrowser()` → **白下两遍**（约 3 MB × 2），
     * 最后装出来是**另一个 App**，本版一点没变。
     *
     * ⚠️ 注意签名校验那一关是**过得去**的：官方包与本版用同一个 keystore
     * （`21cdf37f…2bff`），`sameSignerAsSelf()` 返回 true，所以拦截点确实只在
     * applicationId —— 别以为签名能兜住。
     *
     * ★ 为什么做成方法，而不是让各 UI 自己判 [isCoexistBuild]：
     *   实测「下载并安装」入口有 **3 个**（关于页「检查更新」/ 首页公告弹窗 /
     *   公告列表页更新公告），而 [isCoexistBuild] 此前只在 AppNav 的**强制更新门禁**
     *   里用过一次 —— 那 3 个**全漏了**。判断散在 N 处必然漏一处，
     *   所以下沉到唯一一层：UI 只问"能不能自更新"，不问"我是不是共存版"。
     *
     * 官方版下本方法恒为 true，行为与改动前完全一致。
     */
    fun canSelfUpdate(context: Context): Boolean = !isCoexistBuild(context)

    /**
     * 共存版在「发现新版本」时应显示的说明。
     *
     * 放在这里而不是各 UI 里各写一份 —— 三处入口文案不一致的话，
     * 用户看到的行为解释会互相矛盾。
     */
    const val COEXIST_UPDATE_HINT: String =
        "本版是共存版（安装包标识与官方不同），官方安装包属于另一个应用，无法直接覆盖更新。" +
            "请下载新版本源码后重新打包替换。"

    private val client = OkHttpClient.Builder()
        .dns(ServerDns)                            // 安装包同样走直连高防主服务器
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 下载服务器 (源站) */
    private val downloadBases: List<String> by lazy {
        listOf(Session.baseUrl.trimEnd('/'))
    }

    /** 按发版约定生成下载地址 */
    fun apkUrl(version: String): String =
        downloadBases.first() + "/downloads/CyanCodex-v" + version.trimStart('v') + ".apk"

    /**
     * 服务器权威下载地址: 优先 /api/update/latest 返回的真实文件地址,
     * 失败时回退为按版本号拼接的约定地址。
     */
    suspend fun resolveUrl(fallbackVersion: String?): String? {
        val latest = try { ApiClient.get().latestUpdate() } catch (e: Exception) { null }
        val data = latest?.data
        if (data != null && data.url.isNotBlank()) {
            return downloadBases.first() + data.url
        }
        return fallbackVersion?.let { apkUrl(it) }
    }

    /** 用浏览器打开下载地址 (下载失败时的兜底方案) */
    fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "无法打开下载页面", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 下载 APK 到 cache/downloads/, 返回文件; 失败返回 null。
     * url 为相对路径(/downloads/...)时按候选服务器逐个尝试(加速节点优先, 自动回退);
     * 完整地址则直接下载。onProgress: (已完成, 总大小) — 总大小未知时为 -1
     */
    suspend fun download(context: Context, url: String, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File? =
        withContext(Dispatchers.IO) {
            val urls = if (url.startsWith("http")) listOf(url)
                else downloadBases.map { it + url }
            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val target = File(dir, "CyanCodex-update.apk")
            for (u in urls) {
                val ok = try {
                    val req = okhttp3.Request.Builder().url(u).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        val body = resp.body ?: return@use false
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            FileOutputStream(target).use { fos ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    fos.write(buf, 0, n)
                                    done += n
                                    onProgress(done, total)
                                }
                            }
                        }
                        true
                    }
                } catch (e: Exception) {
                    target.delete()
                    false
                }
                if (ok) {
                    // 审计 F1 修复：下载完必须校验哈希（原来只看 HTTP 2xx 就安装）。
                    // 期望值取服务端 /api/update/latest 的 md5 / sha256，任一不匹配即删除文件、视为失败。
                    val expect = try { ApiClient.get().latestUpdate()?.data } catch (e: Exception) { null }
                    val wantMd5 = expect?.md5.orEmpty().trim()
                    val wantSha = expect?.sha256.orEmpty().trim()
                    if (wantMd5.isNotBlank() || wantSha.isNotBlank()) {
                        val (gotMd5, gotSha) = hashOf(target)
                        val bad = (wantMd5.isNotBlank() && !wantMd5.equals(gotMd5, ignoreCase = true)) ||
                            (wantSha.isNotBlank() && !wantSha.equals(gotSha, ignoreCase = true))
                        if (bad) {
                            target.delete()
                            return@withContext null
                        }
                    }
                    return@withContext target
                }
            }
            null
        }

    /** 计算文件的 md5 与 sha256（十六进制小写） */
    private fun hashOf(f: File): Pair<String, String> {
        val md = java.security.MessageDigest.getInstance("MD5")
        val sd = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                sd.update(buf, 0, n)
            }
        }
        fun hex(b: ByteArray) = buildString { for (x in b) append("%02x".format(x)) }
        return hex(md.digest()) to hex(sd.digest())
    }

    /** 待安装 APK 的签名是否与本机 App 一致（审计 F1：拒绝来路/被篡改的安装包） */
    private fun sameSignerAsSelf(context: Context, apk: File): Boolean = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val self = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        val info = pm.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
        val other = info?.signatures
        self != null && other != null && self.size == other.size &&
            self.indices.all { self[it].toByteArray().contentEquals(other[it].toByteArray()) }
    } catch (e: Exception) {
        false
    }

    /** 用系统安装器安装 (FileProvider 分享 APK) */
    fun install(context: Context, file: File): Boolean {
        // 审计 F1 修复：安装前核对签名，不一致直接拦下（哈希已下载时校验过，这里再兜一层）
        if (!sameSignerAsSelf(context, file)) {
            android.widget.Toast.makeText(context, "安装包校验未通过，已阻止安装（请从官方渠道重新下载）",
                android.widget.Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            // 防呆: 安装包的 applicationId 必须与本应用一致, 否则装下去是"另一个 App"。
            // 共存版下载到的官方包就属于这种情况 —— 直接拒绝, 让调用方回退到浏览器下载,
            // 而不是静默地多装一个官方版出来。
            //
            // ★ 2026-10-07: 这里原本**只有一条 Log.w**, 用户完全看不到 —— 于是表现为
            //   "点了更新, 转了一圈, 啥也没发生"。现在补一条可见提示。
            //   注意: 正常路径**不该走到这里**(UI 层已按 canSelfUpdate 分流),
            //   这条是安全网 —— 万一将来又新增了入口忘了分流, 至少不会静默。
            val archivePkg = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
            }.getOrNull()
            if (archivePkg != null && archivePkg != context.packageName) {
                Log.w(TAG, "拒绝安装非本应用的安装包: $archivePkg (本应用 ${context.packageName})")
                android.widget.Toast.makeText(
                    context,
                    "这是其他应用的安装包（$archivePkg），无法覆盖当前版本",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return false
            }
            val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
