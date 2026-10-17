package com.canglan.cyancodex.data

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 设置云端备份 / 恢复
 * ==========================================================================
 *
 * 把本机全部设置打包 → 用「备份码」派生的密钥加密 → 上传到自建服务
 * `https://cyancodex.bluestudio.cn`。换机 / 重装后用同一备份码拉回。
 *
 * ## 零知识 (zero-knowledge)
 *
 * 加密完全在设备上完成, **服务端只存密文**, 既不知道备份码也无法解密。
 * 上传用的记录 id 是 `Base32(SHA-256(备份码))` 的前 80 bit —— 服务端连
 * 备份码本身都拿不到, 拖库也只能得到一堆无法解密的字节。
 *
 * ## 为什么要解出明文密钥再打包
 *
 * [Crypto] 用的是 **Android Keystore**, 密钥与设备绑定 —— 换机后旧密文
 * 无法解密, 直接搬运 `token_enc` 之类的值等于把账号丢了。
 * 所以备份时先用 [Crypto] 解出明文放进包内, 恢复时再用**新机**的
 * Keystore 重新加密写回。包本身由备份码加密, 明文不会离开设备。
 *
 * ## 加密参数
 *
 *   PBKDF2-HMAC-SHA256(备份码, 随机 16B 盐, 210000 轮) -> 256 bit 密钥
 *   AES-256-GCM(随机 12B IV, 128 bit tag)
 *   落盘格式: `v2:<b64 salt>:<b64 iv>:<b64 ciphertext>` (密文是 gzip 后的 JSON)
 *             `v1:<b64 salt>:<b64 iv>:<b64 ciphertext>` (老版本产出, 明文 JSON)
 *
 * GCM 的认证标签同时充当「备份码是否正确」的校验 —— 码错了会直接
 * 解密失败, 不需要额外的校验字段。
 *
 * ## 为什么要先 gzip 再加密 (2026-10-16)
 *
 * 用户报「备份会提示数据过大」。排查发现备份项里有几个**随使用无限增长**的键:
 *
 * | 键 | 内容 | 增长上限 |
 * |---|---|---|
 * | `ai_convs` | 全部 AI 会话(正文 + 思考过程 + 上传文件正文 + 工具调用记录) | 无上限 |
 * | `ai_messages` | 老版单会话遗留(迁移后仍在) | 无上限 |
 * | `local_history` | 生成历史(表单输入 + 输出指令) | 200 条 |
 * | `ai_model_list` | 模型列表缓存 | 中等 |
 *
 * 重度用户很容易把明文 JSON 撑到几百 KB, 加密后再 base64 膨胀 4/3,
 * 直接撞上服务端 512 KB 上限 —— 备份整体失败。
 *
 * 这类 JSON 的 gzip 压缩率**实测 5.5 倍**(中文对话 + 大量重复的键名/角色名)。
 * 哪怕内容几乎随机(最坏情况)也仍有 1.6 倍 —— 而且压不动时会自动退回 v1,
 * **绝不会比不压缩更大**。换算下来, 480 KB 上限对应的明文容量从 0.35 MB 提到约 1.9 MB。
 *
 * 实测数据 (按 [collect] 的真实形态造负载, 6 条会话 × 14 轮 + 200 条生成历史):
 *
 * | 负载 | 明文 | 旧(v1)密文 | 新(v2)密文 |
 * |---|---|---|---|
 * | 口语化中文对话 | 409.8 KB | **546.5 KB ❌ 超限** | 99.8 KB ✅ |
 * | 最坏: 随机汉字 | 215.7 KB | 287.7 KB ✅ | 179.6 KB ✅ |
 *
 * ⚠️ 老备份 (`v1:`) 仍能正常解密恢复 —— 见 [decrypt] 的版本分派。
 */
object SettingsBackup {

    /** 备份服务地址 (可在调试面板通过 [K_BASE_OVERRIDE] 覆盖) */
    const val DEFAULT_BASE = "https://cyancodex.bluestudio.cn"

    private const val PREFS = "cyancodex"

    /** 调试用: 自定义备份服务地址 */
    private const val K_BASE_OVERRIDE = "backup_base_url"

    /** 最近一次备份的元信息 (本机留存, 不参与备份) */
    private const val K_LAST_CODE = "backup_last_code"
    private const val K_LAST_TIME = "backup_last_time"
    private const val K_LAST_SIZE = "backup_last_size"

    /** 本机专属键: 不打包, 恢复时也不覆盖 */
    private val LOCAL_ONLY = setOf(
        "device_id",
        K_BASE_OVERRIDE,
        K_LAST_CODE,
        K_LAST_TIME,
        K_LAST_SIZE,
        // 免费通道状态缓存 —— 是**缓存**不是设置, 换机后第一次探测自然会重新拿到。
        // 打包过去反而会把新机上的显示锁死在旧值上。
        "freeai_status_cache",

        // ---- ★★ 2026-10-17: 把 AI 会话从「设置备份」里剥离 ----
        // ai_convs 是备份体积超限的**头号元凶** —— 它没有上限, 聊得越多越大,
        // 而真正的设置只有几十 KB。两者捆在一起, 结果是"只想同步偏好设置"
        // 被几百 KB 聊天记录绑架, 明文一超 0.35 MB 就整体备份失败。
        //
        // 现在会话走独立的云同步 (见 ConvSync), 设置备份只管设置。
        // 顺带解决语义问题: 「恢复设置」不该把半年前的对话一起灌回来。
        "ai_convs",
        "ai_messages",          // 老版本的单会话字段, 迁移后已并入 ai_convs
        "ai_current_conv",      // 当前会话 id 是本地状态, 换机后应重新选

        // 配置中心与同步游标的缓存 —— 同样属于"换机会自己重新拿到"的东西
        "app_config_cache",
        "app_version_cache",
        "conv_sync_since",
        "conv_sync_last_at",
        "conv_sync_last_msg",
        // 注意: conv_sync_enabled **要**备份 —— 那是用户偏好, 不是缓存。
    )

    /** Keystore 加密键 —— 备份时解出明文, 恢复时用本机密钥重新加密 */
    private val SECRET_KEYS = listOf(
        "token_enc", "cookie_enc", "ai_api_key_enc", "ai_search_key_enc",
    )

    private const val PBKDF2_ITER = 210_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /**
     * 落盘格式版本 —— 见 [encrypt] / [decrypt]。
     *   [BLOB_V1] = 明文 JSON 直接加密 (老版本产出, 仍需能解)
     *   [BLOB_V2] = 先 gzip 再加密 (当前产出)
     */
    private const val BLOB_V1 = "v1"
    private const val BLOB_V2 = "v2"

    /** ★ 服务端上限 512 KB (backup-server 的 `MAX_BLOB_CHARS`), 这里留 32 KB 余量 */
    private const val MAX_BLOB = 480 * 1024

    /**
     * ★★ **最近一次 [collect] 的各备份项明文体积** (键 → UTF-8 字节数, 倒序)。
     *
     * 加它是为了回答「数据过大」时用户唯一会问的那个问题: **到底是谁占了空间**。
     * 只报一句"数据过大", 用户完全不知道该清什么; 有了它就能直接点出
     * `ai_convs 312.4 KB` 这种可行动的信息 —— 既进异常消息, 也进调试日志。
     *
     * ⚠️ 备份是串行操作(用户点一次走一次), 不做同步保护; 读它的地方只有
     *    [backup] 与 [inspect], 都在同一条调用链上。
     */
    @Volatile
    var lastItemSizes: List<Pair<String, Int>> = emptyList()
        private set

    /** Crockford Base32: 去掉 I L O U, 避免肉眼混淆 */
    private const val B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val gson = Gson()
    private val rnd = SecureRandom()

    // ----------------------------------------------------------------------
    // 结果类型
    // ----------------------------------------------------------------------

    /** 备份结果 */
    data class BackupResult(
        val code: String,
        val sizeBytes: Int,
        val updatedAt: Long,
        val keyCount: Int,
    )

    /** 恢复结果 */
    data class RestoreResult(
        val keyCount: Int,
        val secretCount: Int,
        val exportedAt: Long,
        val appVersion: String,
    )

    /** 业务异常: message 可直接展示给用户 */
    class BackupException(val kind: Kind, message: String) : Exception(message) {
        enum class Kind { NETWORK, SERVER, CODE_INVALID, EMPTY, TOO_LARGE, PARSE }
    }

    // ----------------------------------------------------------------------
    // 备份码
    // ----------------------------------------------------------------------

    /**
     * 生成新备份码: 16 个 Crockford Base32 字符 = 80 bit 熵,
     * 展示形如 `K7QM-3XZP-9WVB-2HNT`。
     */
    fun newCode(): String = group(b32(randomBytes(10)))

    /** 归一化用户输入: 去空白/横线, 转大写, 再把易混字符纠正回字母表 */
    fun normalizeCode(raw: String): String {
        val cleaned = raw.uppercase()
            .replace(" ", "").replace("-", "").replace("\t", "")
            .replace("O", "0")     // Crockford 宽容解码
            .replace("I", "1")
            .replace("L", "1")
            .replace("U", "V")
        return group(cleaned)
    }

    /** 备份码是否合法 (归一化后 16 位且全在字母表内) */
    fun isValidCode(raw: String): Boolean {
        val c = normalizeCode(raw).replace("-", "")
        return c.length == 16 && c.all { B32.indexOf(it) >= 0 }
    }

    private fun group(s: String): String {
        val t = s.replace("-", "")
        if (t.length != 16) return t
        return t.chunked(4).joinToString("-")
    }

    // ----------------------------------------------------------------------
    // 打包 / 回写
    // ----------------------------------------------------------------------

    /**
     * 收集本机全部设置, 产出明文 JSON 字符串。
     *
     * 值带类型标签 (`t`), 避免 Gson 把 Int 反序列化成 Double:
     *   b=Boolean  i=Int  l=Long  f=Float  s=String  S=StringSet
     */
    fun collect(ctx: Context): String {
        val sp = prefs(ctx)
        val prefsObj = JsonObject()
        // ★ 顺手量每一项的体积 —— 见 [lastItemSizes] 的注释(超限时要能说出是谁占的)
        val sizes = ArrayList<Pair<String, Int>>(sp.all.size + SECRET_KEYS.size)
        var n = 0
        for ((k, v) in sp.all) {
            if (k in LOCAL_ONLY) continue
            if (k in SECRET_KEYS) continue          // 密钥单独走 secrets 段
            val item = JsonObject()
            when (v) {
                is Boolean -> { item.addProperty("t", "b"); item.addProperty("v", v) }
                is Int -> { item.addProperty("t", "i"); item.addProperty("v", v) }
                is Long -> { item.addProperty("t", "l"); item.addProperty("v", v) }
                is Float -> { item.addProperty("t", "f"); item.addProperty("v", v) }
                is String -> { item.addProperty("t", "s"); item.addProperty("v", v) }
                is Set<*> -> {
                    item.addProperty("t", "S")
                    val arr = com.google.gson.JsonArray()
                    v.forEach { if (it != null) arr.add(it.toString()) }
                    item.add("v", arr)
                }
                else -> continue
            }
            prefsObj.add(k, item)
            sizes.add(k to gson.toJson(item).toByteArray(Charsets.UTF_8).size)
            n++
        }

        // 解出 Keystore 密文里的明文 (换机后旧密文无解, 必须带走明文)
        val secretsObj = JsonObject()
        for (k in SECRET_KEYS) {
            val enc = sp.getString(k, null) ?: continue
            val plain = Crypto.decryptOrNull(enc) ?: continue
            if (plain.isNotBlank()) {
                secretsObj.addProperty(k, plain)
                sizes.add(k to plain.toByteArray(Charsets.UTF_8).size)
            }
        }
        lastItemSizes = sizes.sortedByDescending { it.second }

        val root = JsonObject()
        root.addProperty("schema", 1)
        root.addProperty("appId", ctx.packageName)
        root.addProperty("appVersion", appVersion(ctx))
        root.addProperty("exportedAt", System.currentTimeMillis())
        root.addProperty("keyCount", n)
        root.add("prefs", prefsObj)
        root.add("secrets", secretsObj)
        return gson.toJson(root)
    }

    /**
     * 把备份包回写到本机设置。返回 (写入键数, 写入密钥数)。
     * ⚠️ 调用方应在成功后重启应用, 让主题/悬浮窗等状态重新加载。
     */
    fun apply(ctx: Context, json: String): RestoreResult {
        val root = try {
            JsonParser().parse(json).asJsonObject
        } catch (e: Exception) {
            throw BackupException(BackupException.Kind.PARSE, "备份内容无法解析")
        }
        val sp = prefs(ctx)
        val ed = sp.edit()
        var n = 0

        root.getAsJsonObject("prefs")?.let { prefsObj ->
            for ((k, el) in prefsObj.entrySet()) {
                if (k in LOCAL_ONLY) continue
                if (k in SECRET_KEYS) continue
                val item = el.asJsonObject
                val t = item.get("t")?.asString ?: continue
                val v = item.get("v") ?: continue
                try {
                    when (t) {
                        "b" -> ed.putBoolean(k, v.asBoolean)
                        "i" -> ed.putInt(k, v.asInt)
                        "l" -> ed.putLong(k, v.asLong)
                        "f" -> ed.putFloat(k, v.asFloat)
                        "s" -> ed.putString(k, if (v.isJsonNull) null else v.asString)
                        "S" -> ed.putStringSet(
                            k,
                            v.asJsonArray.mapNotNull { if (it.isJsonNull) null else it.asString }.toSet()
                        )
                        else -> continue
                    }
                    n++
                } catch (_: Exception) {
                    // 单键损坏不影响整体恢复
                }
            }
        }

        var m = 0
        root.getAsJsonObject("secrets")?.let { secretsObj ->
            for ((k, el) in secretsObj.entrySet()) {
                if (k !in SECRET_KEYS) continue
                val plain = if (el.isJsonNull) "" else el.asString
                if (plain.isBlank()) continue
                val enc = Crypto.encryptOrNull(plain) ?: continue   // 用本机 Keystore 重新加密
                ed.putString(k, enc)
                m++
            }
        }

        ed.commit()   // 用 commit: 恢复后马上重启, 必须确保已落盘

        val meta = sp.edit()
        meta.putLong(K_LAST_TIME, System.currentTimeMillis())
        meta.commit()

        return RestoreResult(
            keyCount = n,
            secretCount = m,
            exportedAt = root.get("exportedAt")?.asLong ?: 0L,
            appVersion = root.get("appVersion")?.asString ?: "",
        )
    }

    // ----------------------------------------------------------------------
    // 加解密
    // ----------------------------------------------------------------------

    /**
     * 加密: 返回 `v2:<b64 salt>:<b64 iv>:<b64 ct>` —— **ct 是 gzip 之后的 JSON**。
     *
     * ★★ 2026-10-16 修「备份提示数据过大」: 原先直接把明文 JSON 加密上传,
     *    而 [collect] 收进来的 `ai_convs` / `local_history` 是随使用无限增长的,
     *    重度用户撞上服务端 512 KB 上限后备份整体失败。改为先 gzip 再加密 ——
     *    压缩率**实测 5.5 倍**(最坏 1.6 倍), 一个备份项都不用砍。详见类注释。
     *
     * ⚠️ gzip 对**极小**数据反而会大二十来字节(GZIP 头 + 尾部校验), 所以压完更大就退回 [BLOB_V1]。
     */
    fun encrypt(code: String, plain: String): String {
        val raw = plain.toByteArray(Charsets.UTF_8)
        val gz = gzipOrNull(raw)
        val ver: String
        val payload: ByteArray
        if (gz != null && gz.size < raw.size) {
            ver = BLOB_V2; payload = gz
        } else {
            ver = BLOB_V1; payload = raw
        }

        val salt = randomBytes(SALT_LEN)
        val iv = randomBytes(IV_LEN)
        val key = deriveKey(code, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(payload)
        return "$ver:${b64(salt)}:${b64(iv)}:${b64(ct)}"
    }

    /**
     * 解密: 备份码不对会抛 [BackupException.Kind.CODE_INVALID]。
     *
     * 按前缀分派格式 —— `v1` = 明文 JSON, `v2` = gzip 后 JSON。
     * 老备份 (`v1:`) 必须一直能解: 用户换机时手上拿的很可能是旧版本存的备份。
     *
     * ⚠️ **解压失败不能报成"备份码不正确"** —— 那是两件完全不同的事,
     *    报错了用户会跑去反复核对备份码。这里分开报。
     */
    fun decrypt(code: String, blob: String): String {
        val p = blob.split(":")
        if (p.size != 4 || (p[0] != BLOB_V1 && p[0] != BLOB_V2)) {
            throw BackupException(BackupException.Kind.PARSE, "备份数据格式异常")
        }
        val ver = p[0]
        val salt: ByteArray
        val iv: ByteArray
        val ct: ByteArray
        try {
            salt = unb64(p[1]); iv = unb64(p[2]); ct = unb64(p[3])
        } catch (_: Exception) {
            throw BackupException(BackupException.Kind.PARSE, "备份数据格式异常")
        }
        // 这一段只可能是"码不对/数据被篡改" —— GCM 认证标签会直接失败
        val payload = try {
            val key = deriveKey(code, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ct)
        } catch (_: Exception) {
            throw BackupException(BackupException.Kind.CODE_INVALID, "备份码不正确, 或备份已损坏")
        }
        if (ver == BLOB_V2) {
            return try {
                String(gunzip(payload), Charsets.UTF_8)
            } catch (_: Exception) {
                throw BackupException(BackupException.Kind.PARSE, "备份内容解压失败, 备份可能已损坏")
            }
        }
        return String(payload, Charsets.UTF_8)
    }

    /** gzip 压缩; 任何异常都返回 null(调用方会退回不压缩的老格式)。 */
    private fun gzipOrNull(data: ByteArray): ByteArray? = try {
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        GZIPOutputStream(out).use { it.write(data) }
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    /** gzip 解压。 */
    private fun gunzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 4 + 64)
        GZIPInputStream(data.inputStream()).use { it.copyTo(out) }
        return out.toByteArray()
    }

    /** 人读体积, 例 `312.4 KB` / `880 B`。 */
    private fun kb(n: Int): String =
        if (n >= 1024) String.format(Locale.US, "%.1f KB", n / 1024.0) else "$n B"

    private fun deriveKey(code: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(
            normalizeCode(code).replace("-", "").toCharArray(),
            salt, PBKDF2_ITER, 256
        )
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = skf.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    // ----------------------------------------------------------------------
    // 网络
    // ----------------------------------------------------------------------

    /** 备份码 -> 服务端记录 id (Base32(SHA-256(code)) 前 80 bit) */
    fun recordId(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizeCode(code).replace("-", "").toByteArray(Charsets.UTF_8))
        return group(b32(digest.copyOf(10)))
    }

    /**
     * 上传备份。
     * @throws BackupException NETWORK / SERVER / TOO_LARGE
     */
    fun backup(ctx: Context, code: String): BackupResult {
        val norm = normalizeCode(code)
        if (!isValidCode(norm)) {
            throw BackupException(BackupException.Kind.CODE_INVALID, "备份码格式不正确")
        }
        val plain = collect(ctx)
        val blob = encrypt(norm, plain)
        if (blob.length > MAX_BLOB) {
            // ★ 只说"数据过大"用户没法行动 —— 把最大的几项直接点出来 (见 [lastItemSizes])。
            //   注意这里量的是**明文**体积: 密文已 gzip, 说密文大小没意义。
            val top = lastItemSizes.take(4).joinToString("、") { "${it.first} ${kb(it.second)}" }
            throw BackupException(
                BackupException.Kind.TOO_LARGE,
                "设置数据过大（压缩后 ${kb(blob.length)}，上限 ${kb(MAX_BLOB)}）" +
                    if (top.isBlank()) "" else "。占用最大的项：$top",
            )
        }
        val id = recordId(norm)

        val meta = JsonObject()
        meta.addProperty("app_version", appVersion(ctx))
        meta.addProperty("keys", plain.length)
        meta.addProperty("platform", "android")
        meta.addProperty("zip", blob.startsWith(BLOB_V2 + ":"))

        val body = JsonObject()
        body.addProperty("id", id)
        body.addProperty("blob", blob)
        body.add("meta", meta)

        val resp = request(ctx, "POST", "/api/v1/backup", gson.toJson(body))
        val updatedAt = resp.get("updated_at")?.asLong ?: (System.currentTimeMillis() / 1000)

        val sp = prefs(ctx)
        sp.edit()
            .putString(K_LAST_CODE, norm)
            .putLong(K_LAST_TIME, updatedAt)
            .putInt(K_LAST_SIZE, blob.length)
            .apply()

        // ★ 体积明细一并进日志 —— 下次再遇到"过大", 日志里直接有答案, 不用再排查一遍
        DebugStore.logI(
            "Backup",
            "上传成功 id=$id 密文=${kb(blob.length)} 明文=${kb(plain.length)} " +
                "最大项=" + lastItemSizes.take(3).joinToString(",") { "${it.first}:${it.second}" },
        )

        val keyCount = JsonParser().parse(plain).asJsonObject
            .get("keyCount")?.asInt ?: 0
        return BackupResult(norm, blob.length, updatedAt, keyCount)
    }

    /**
     * 供调试面板/排查用: 列出当前各备份项的**明文体积**(倒序)。
     *
     * 会真的跑一遍 [collect] —— 它只读 SharedPreferences + 解 Keystore 密文, 不改任何东西。
     * 返回的正是 [lastItemSizes]。
     */
    fun inspect(ctx: Context): List<Pair<String, Int>> {
        collect(ctx)
        return lastItemSizes
    }

    /**
     * 拉取并恢复。
     * @throws BackupException NETWORK / SERVER / CODE_INVALID / PARSE
     */
    fun restore(ctx: Context, code: String): RestoreResult {
        val norm = normalizeCode(code)
        if (!isValidCode(norm)) {
            throw BackupException(BackupException.Kind.CODE_INVALID, "备份码格式不正确")
        }
        val id = recordId(norm)
        val resp = request(ctx, "GET", "/api/v1/backup/$id", null)
        val blob = resp.get("blob")?.asString
            ?: throw BackupException(BackupException.Kind.PARSE, "服务端返回内容异常")
        val plain = decrypt(norm, blob)     // 码不对在这里抛 CODE_INVALID
        val r = apply(ctx, plain)
        DebugStore.logI("Backup", "恢复成功 键=${r.keyCount} 密钥=${r.secretCount}")
        return r
    }

    /** 最近一次备份码 (仅本机记录, 方便下次直接备份) */
    fun lastCode(ctx: Context): String = prefs(ctx).getString(K_LAST_CODE, "") ?: ""

    /** 最近一次备份时间 (秒), 0 = 从未备份 */
    fun lastTime(ctx: Context): Long = prefs(ctx).getLong(K_LAST_TIME, 0L)

    fun baseUrl(ctx: Context): String {
        val custom = prefs(ctx).getString(K_BASE_OVERRIDE, "") ?: ""
        return (if (custom.isBlank()) DEFAULT_BASE else custom).trimEnd('/')
    }

    // ----------------------------------------------------------------------
    // 内部工具
    // ----------------------------------------------------------------------

    private fun request(ctx: Context, method: String, path: String, body: String?): JsonObject {
        val url = URL(baseUrl(ctx) + path)
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 25_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CyanCodex-Android/" + appVersion(ctx))
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = when (code) {
                    404 -> "没有找到该备份码对应的数据"
                    413 -> "备份数据超过服务端限制"
                    429 -> "请求过于频繁, 请稍后再试"
                    in 500..599 -> "服务端暂时不可用"
                    else -> "请求失败 (HTTP $code)"
                }
                throw BackupException(BackupException.Kind.SERVER, msg)
            }
            return try {
                JsonParser().parse(text).asJsonObject
            } catch (_: Exception) {
                throw BackupException(BackupException.Kind.PARSE, "服务端返回内容异常")
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            DebugStore.logW("Backup", "网络失败: " + e.message)
            throw BackupException(
                BackupException.Kind.NETWORK,
                "网络连接失败, 请检查网络后重试"
            )
        } finally {
            conn?.disconnect()
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** Crockford Base32 编码 (大端, 5 bit/字符, 末尾补零) */
    private fun b32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }
}
