package com.canglan.cyancodex.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 插件工坊: 积木模型 / 行为规格 JSON / 等价 Java 代码 / JAR 打包
 *
 * 工作原理: 积木拼出来的插件共用 App 内置的运行时类 (assets/plugins/builder.jar 里的
 * dev.cyancodex.blocks.GeneratedPlugin), 每个插件的差异写在 JAR 内的 cyancodex-plugin.json,
 * 加载时由 PluginManager 注入。因此打包完全离线, 不依赖编译器, 任何 Android 版本都能用。
 */

/** 积木类型 */
enum class BlockKind(
    val type: String,
    val label: String,
    val hint: String,
    val group: String,
) {
    LOG("log", "记录日志", "在日志里输出一句话", "基础"),
    TOAST("toast", "弹出提示", "在屏幕上弹一句提示", "提示"),
    SET_PREF("setPref", "保存配置", "把一条配置写进插件存储", "配置"),
    LOG_PREF("logPref", "读取配置并记录", "读出配置项写进日志", "配置"),
    CLEAR_PREF("clearPref", "清除配置", "删除一条配置项", "配置"),
}

/** 一块积木 (参数按类型使用)
 *  意见 #95: 参数必须用 Compose 可观察状态 (mutableStateOf)。
 *  之前是普通 data class 的 var, 放在 mutableStateListOf 里改属性不会触发重组,
 *  结果积木里的输入框「字写不进去、也删不掉」。 */
class Block(
    val kind: BlockKind,
    message: String = "",
    key: String = "",
    value: String = "",
    label: String = "",
) {
    var message by mutableStateOf(message)
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
    var label by mutableStateOf(label)
}

object PluginBuilder {
    /** 内置运行时类名 (与 assets/plugins/builder.jar 内一致) */
    const val CLASS_NAME = "dev.cyancodex.blocks.GeneratedPlugin"
    const val SERVICE_PATH = "META-INF/services/com.canglan.cyancodex.plugin.CyanPlugin"
    const val SPEC_PATH = "cyancodex-plugin.json"
    const val TEMPLATE_ASSET = "plugin_template.jar"
    const val ID_PREFIX = "dev.cyancodex.blocks."

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    /** 插件 id: 前缀 + 名称 slug */
    fun makeId(name: String): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(24)
        return ID_PREFIX + (slug.ifBlank { "my_plugin" })
    }

    /** 校验; 返回错误提示, null 表示通过 */
    fun validate(name: String, version: String, onEnable: List<Block>, onDisable: List<Block>): String? {
        if (name.isBlank()) return "请填写插件名称"
        if (name.length > 24) return "插件名称不要超过 24 个字"
        if (!Regex("^\\d+(\\.\\d+){0,2}$").matches(version.trim())) return "版本号格式应形如 1.0.0"
        (onEnable + onDisable).forEach { b ->
            when (b.kind) {
                BlockKind.LOG, BlockKind.TOAST -> if (b.message.isBlank()) return "「${b.kind.label}」还缺内容"
                BlockKind.SET_PREF -> {
                    if (b.key.isBlank()) return "「保存配置」还缺配置名"
                }
                BlockKind.LOG_PREF, BlockKind.CLEAR_PREF -> if (b.key.isBlank()) return "「${b.kind.label}」还缺配置名"
            }
        }
        return null
    }

    fun validateColors(top: String, bottom: String): String? {
        if (!HEX.matches(top.trim())) return "起始颜色需形如 #0B1A14"
        if (!HEX.matches(bottom.trim())) return "结束颜色需形如 #10352B"
        return null
    }

    // ---------------- 行为规格 JSON ----------------
    fun specJson(
        name: String,
        version: String,
        description: String,
        bgEnabled: Boolean,
        bgKind: String,
        colorTop: String,
        colorBottom: String,
        onEnable: List<Block>,
        onDisable: List<Block>,
    ): String {
        val root = JSONObject()
        root.put("id", makeId(name))
        root.put("name", name.trim().ifBlank { "积木插件" })
        root.put("version", version.trim().ifBlank { "1.0.0" })
        root.put("description", description.trim())
        root.put("generator", "CyanCodex 插件工坊")
        root.put("schema", 1)

        root.put("background", JSONObject().apply {
            put("enabled", bgEnabled)
            put("kind", if (bgKind == "color") "color" else "gradient")
            put("colorTop", colorTop.trim().uppercase())
            put("colorBottom", colorBottom.trim().uppercase())
        })
        root.put("onEnable", blocksToJson(onEnable))
        root.put("onDisable", blocksToJson(onDisable))
        return root.toString(2)
    }

    private fun blocksToJson(blocks: List<Block>): JSONArray {
        val arr = JSONArray()
        blocks.forEach { b ->
            val o = JSONObject()
            o.put("type", b.kind.type)
            when (b.kind) {
                BlockKind.LOG, BlockKind.TOAST -> o.put("message", b.message)
                BlockKind.SET_PREF -> {
                    o.put("key", b.key)
                    o.put("value", b.value)
                }
                BlockKind.LOG_PREF -> {
                    o.put("key", b.key)
                    o.put("label", b.label.ifBlank { "${b.key} = " })
                }
                BlockKind.CLEAR_PREF -> o.put("key", b.key)
            }
            arr.put(o)
        }
        return arr
    }

    // ---------------- 等价 Java 代码 (学习/参考) ----------------
    fun javaPreview(
        name: String,
        version: String,
        description: String,
        bgEnabled: Boolean,
        bgKind: String,
        colorTop: String,
        colorBottom: String,
        onEnable: List<Block>,
        onDisable: List<Block>,
    ): String {
        val id = makeId(name)
        val sb = StringBuilder()
        sb.append("package ").append(ID_PREFIX.trimEnd('.')).append(";\n\n")
        sb.append("import android.content.Context;\n")
        sb.append("import android.content.SharedPreferences;\n")
        sb.append("import android.util.Log;\n")
        sb.append("import android.widget.Toast;\n")
        sb.append("import com.canglan.cyancodex.plugin.CyanPlugin;\n")
        if (bgEnabled) sb.append("import com.canglan.cyancodex.plugin.BackgroundProvider;\n")
        sb.append("import com.canglan.cyancodex.plugin.PluginBackground;\n\n")
        sb.append("/** 由 CyanCodex 插件工坊生成的等价代码 (参考用) */\n")
        sb.append("public class GeneratedPlugin implements CyanPlugin")
        if (bgEnabled) sb.append(", BackgroundProvider")
        sb.append(" {\n")
        sb.append("    private static final String TAG = \"CyanPlugin\";\n\n")
        sb.append("    @Override public String getId() { return \"").append(id).append("\"; }\n")
        sb.append("    @Override public String getName() { return \"").append(name.ifBlank { "积木插件" }).append("\"; }\n")
        sb.append("    @Override public String getVersion() { return \"").append(version.ifBlank { "1.0.0" }).append("\"; }\n")
        sb.append("    @Override public String getDescription() { return \"").append(description.replace("\"", "\\\"")).append("\"; }\n\n")
        sb.append("    @Override public void onEnable(Context context) {\n")
        sb.append(actionsJava(onEnable, "        "))
        sb.append("    }\n\n")
        sb.append("    @Override public void onDisable() {\n")
        sb.append(actionsJava(onDisable, "        "))
        sb.append("    }\n\n")
        if (bgEnabled) {
            sb.append("    @Override public PluginBackground background() {\n")
            val bottom = if (bgKind == "color") colorTop else colorBottom
            sb.append("        return new PluginBackground(\"").append(if (bgKind == "color") "color" else "gradient")
                .append("\", \"").append(colorTop.uppercase()).append("\", \"").append(bottom.uppercase()).append("\");\n")
            sb.append("    }\n\n")
        }
        sb.append("    private SharedPreferences prefs(Context ctx) {\n")
        sb.append("        return ctx.getSharedPreferences(\"plugin_\" + getId(), Context.MODE_PRIVATE);\n")
        sb.append("    }\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun actionsJava(blocks: List<Block>, ind: String): String {
        if (blocks.isEmpty()) return ind + "// 这里没有积木\n"
        val sb = StringBuilder()
        blocks.forEach { b ->
            when (b.kind) {
                BlockKind.LOG -> sb.append(ind).append("Log.i(TAG, \"").append(b.message.replace("\"", "\\\"")).append("\");\n")
                BlockKind.TOAST -> sb.append(ind).append("Toast.makeText(context, \"")
                    .append(b.message.replace("\"", "\\\"")).append("\", Toast.LENGTH_SHORT).show();\n")
                BlockKind.SET_PREF -> sb.append(ind).append("prefs(context).edit().putString(\"")
                    .append(b.key.replace("\"", "\\\"")).append("\", \"").append(b.value.replace("\"", "\\\"")).append("\").apply();\n")
                BlockKind.LOG_PREF -> sb.append(ind).append("Log.i(TAG, \"")
                    .append(b.label.ifBlank { "${b.key} = " }.replace("\"", "\\\"")).append("\" + prefs(context).getString(\"")
                    .append(b.key.replace("\"", "\\\"")).append("\", \"(未设置)\"));\n")
                BlockKind.CLEAR_PREF -> sb.append(ind).append("prefs(context).edit().remove(\"")
                    .append(b.key.replace("\"", "\\\"")).append("\").apply();\n")
            }
        }
        return sb.toString()
    }

    // ---------------- 打包 JAR ----------------
    fun fileNameFor(name: String): String = makeId(name).removePrefix(ID_PREFIX) + ".jar"

    /**
     * 用内置模板 (assets/plugins/builder.jar) 复制 classes.dex 与 services 声明,
     * 再写入本次积木的行为规格, 生成可直接加载的插件 JAR
     */
    fun packJar(context: Context, specJson: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            context.assets.open(TEMPLATE_ASSET).use { input ->
                ZipInputStream(input).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name != SPEC_PATH) {
                            zip.putNextEntry(ZipEntry(e.name))
                            zin.copyTo(zip)
                            zip.closeEntry()
                        }
                        zin.closeEntry()
                        e = zin.nextEntry
                    }
                }
            }
            zip.putNextEntry(ZipEntry(SPEC_PATH))
            zip.write(specJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    // ---------------- 草稿 ----------------
    private const val DRAFT_PREF = "plugin_builder"
    private const val DRAFT_KEY = "draft_v1"

    fun saveDraft(context: Context, json: String) {
        runCatching {
            context.getSharedPreferences(DRAFT_PREF, Context.MODE_PRIVATE)
                .edit().putString(DRAFT_KEY, json).apply()
        }
    }

    fun loadDraft(context: Context): String? = runCatching {
        context.getSharedPreferences(DRAFT_PREF, Context.MODE_PRIVATE).getString(DRAFT_KEY, null)
    }.getOrNull()

    fun clearDraft(context: Context) {
        runCatching {
            context.getSharedPreferences(DRAFT_PREF, Context.MODE_PRIVATE).edit().remove(DRAFT_KEY).apply()
        }
    }
}
