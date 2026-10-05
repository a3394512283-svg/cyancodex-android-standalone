package com.canglan.cyancodex.plugin

import android.content.Context
import android.util.Log
import com.canglan.cyancodex.data.Session
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipFile

/**
 * 插件管理器: 扫描插件目录加载 JAR 插件 (DexClassLoader), 维护启用状态。
 *
 * 目录设计 (Android 禁止从外部存储加载可写 dex):
 *  - 外部目录 Android/data/com.canglan.cyancodex/files/plugins/ — 开发者把 jar 放这里 (可访问)
 *  - 内部目录 filesDir/plugins — 实际 dex 加载目录 (启动时把外部 jar/内置 jar 同步进来)
 *
 * JAR 结构约定:
 *  - classes.dex (插件字节码, 由 d8 转换)
 *  - META-INF/services/com.canglan.cyancodex.plugin.CyanPlugin — 实现类完整类名
 */
object PluginManager {
    private const val TAG = "CyanPlugin"
    private const val SERVICES_FILE = "META-INF/services/com.canglan.cyancodex.plugin.CyanPlugin"
    private const val SPEC_FILE = "cyancodex-plugin.json"   // 插件工坊生成的行为规格
    private const val ASSET_DIR = "plugins"

    data class LoadedPlugin(val plugin: CyanPlugin, val jarFile: File, val enabled: Boolean)

    @Volatile private var plugins: List<LoadedPlugin> = emptyList()

    /** 开发者放置插件 jar 的目录 (外部存储, 文件管理器/ADB 可访问) */
    fun pluginDir(context: Context): File =
        File(context.getExternalFilesDir(null), "plugins").apply { mkdirs() }

    /** dex 实际加载目录 (应用私有内部存储) */
    private fun loadDir(context: Context): File =
        File(context.filesDir, "plugins").apply { mkdirs() }

    /** 初始化: 同步内置 + 外部插件到内部目录, 再加载. 在 Application.onCreate 调用 */
    fun init(context: Context) {
        try {
            val loadDir = loadDir(context)
            copyAssets(context, loadDir)
            // 外部目录(开发者放置)的 jar 同步到内部目录
            pluginDir(context).listFiles { f -> f.isFile && f.extension.equals("jar", true) }?.forEach { src ->
                runCatching { src.copyTo(File(loadDir, src.name), overwrite = true) }
            }
            val loaded = mutableListOf<LoadedPlugin>()
            loadDir.listFiles { f -> f.isFile && f.extension.equals("jar", true) }?.sortedBy { it.name }?.forEach { jar ->
                loadJar(context, jar)?.let { loaded.addAll(it) }
            }
            plugins = loaded
            Log.i(TAG, "plugins loaded: ${plugins.size}")
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
        }
    }

    private fun copyAssets(context: Context, targetDir: File) {
        try {
            context.assets.list(ASSET_DIR)?.forEach { name ->
                val target = File(targetDir, name)
                if (!target.exists()) {
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "copyAssets failed", e) }
    }

    private fun loadJar(context: Context, jar: File): List<LoadedPlugin>? {
        return try {
            // Android 14+ 禁止从"可写"路径加载 dex: 先把 jar 置为只读
            runCatching { jar.setReadOnly() }
            // 读取 services 声明, 拿到实现类名; 同时读取积木插件的行为规格
            val implNames = mutableListOf<String>()
            var specJson: String? = null
            ZipFile(jar).use { zip ->
                zip.getEntry(SERVICES_FILE)?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().useLines { lines ->
                        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { implNames.add(it) }
                    }
                }
                zip.getEntry(SPEC_FILE)?.let { entry ->
                    specJson = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
            }
            if (implNames.isEmpty()) {
                Log.w(TAG, "jar ${jar.name} has no $SERVICES_FILE")
                return null
            }
            val optDir = File(jar.parentFile, "opt").apply { mkdirs() }
            val loader = DexClassLoader(jar.absolutePath, optDir.absolutePath, null, context.classLoader)
            implNames.mapNotNull { name ->
                try {
                    val clazz = Class.forName(name, true, loader)
                    val impl = clazz.getDeclaredConstructor().newInstance() as CyanPlugin
                    // 积木插件: 先注入行为规格 (插件的 id/名称/动作都在规格里), 再判断启用状态
                    if (impl is SpecPlugin && specJson != null) {
                        runCatching { impl.attachSpec(specJson!!) }
                            .onFailure { Log.w(TAG, "attachSpec failed for ${jar.name}", it) }
                    }
                    val enabled = Session.pluginEnabled(impl.id)
                    if (enabled) runCatching { impl.onEnable(context.applicationContext) }
                    LoadedPlugin(impl, jar, enabled)
                } catch (e: Exception) {
                    Log.e(TAG, "load impl $name from ${jar.name} failed", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadJar ${jar.name} failed", e)
            null
        }
    }

    /** 已加载插件列表 */
    fun list(): List<LoadedPlugin> = plugins

    /** 启用/禁用插件 (持久化 + 生命周期回调) */
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        Session.setPluginEnabled(id, enabled)
        val target = plugins.firstOrNull { it.plugin.id == id } ?: return
        if (enabled) runCatching { target.plugin.onEnable(context.applicationContext) }
        else runCatching { target.plugin.onDisable() }
        plugins = plugins.map { if (it.plugin.id == id) it.copy(enabled = enabled) else it }
    }

    /** 重新扫描加载 (开发者放入新 jar 后点"重新加载") */
    fun reload(context: Context) {
        plugins.forEach { if (it.enabled) runCatching { it.plugin.onDisable() } }
        init(context)
    }

    /**
     * 安装插件 JAR: 写入开发者插件目录 (外部 files/plugins) 并重新加载
     * 供「插件工坊」打包后直接安装使用
     */
    fun install(context: Context, fileName: String, bytes: ByteArray): File? {
        return try {
            val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
            val target = File(pluginDir(context), if (safe.endsWith(".jar")) safe else "$safe.jar")
            target.writeBytes(bytes)
            reload(context)
            Log.i(TAG, "installed plugin: ${target.name} (${bytes.size}B)")
            target
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            null
        }
    }

    /** 已安装插件 JAR 文件列表 */
    fun installedFiles(context: Context): List<File> =
        pluginDir(context).listFiles { f -> f.isFile && f.extension.equals("jar", true) }
            ?.sortedBy { it.name } ?: emptyList()

    /** 当前启用的背景插件提供的背景 (无则 null) */
    fun background(): PluginBackground? = try {
        plugins.firstOrNull { it.enabled && it.plugin is BackgroundProvider }
            ?.let { (it.plugin as BackgroundProvider).background() }
    } catch (e: Exception) {
        Log.e(TAG, "background() failed", e)
        null
    }
}
