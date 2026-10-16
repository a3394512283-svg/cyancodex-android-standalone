package com.canglan.cyancodex.data

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
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
 *   落盘格式: `v1:<b64 salt>:<b64 iv>:<b64 ciphertext>`
 *
 * GCM 的认证标签同时充当「备份码是否正确」的校验 —— 码错了会直接
 * 解密失败, 不需要额外的校验字段。
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
    )

    /** Keystore 加密键 —— 备份时解出明文, 恢复时用本机密钥重新加密 */
    private val SECRET_KEYS = listOf(
        "token_enc", "cookie_enc", "ai_api_key_enc", "ai_search_key_enc",
    )

    private const val PBKDF2_ITER = 210_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val BLOB_PREFIX = "v1"
    private const val MAX_BLOB = 480 * 1024          // 与服务端 512KB 上限留余量

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
            n++
        }

        // 解出 Keystore 密文里的明文 (换机后旧密文无解, 必须带走明文)
        val secretsObj = JsonObject()
        for (k in SECRET_KEYS) {
            val enc = sp.getString(k, null) ?: continue
            val plain = Crypto.decryptOrNull(enc) ?: continue
            if (plain.isNotBlank()) secretsObj.addProperty(k, plain)
        }

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

    /** 加密: 返回 `v1:<b64 salt>:<b64 iv>:<b64 ct>` */
    fun encrypt(code: String, plain: String): String {
        val salt = randomBytes(SALT_LEN)
        val iv = randomBytes(IV_LEN)
        val key = deriveKey(code, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$BLOB_PREFIX:${b64(salt)}:${b64(iv)}:${b64(ct)}"
    }

    /** 解密: 备份码不对会抛 [BackupException.Kind.CODE_INVALID] */
    fun decrypt(code: String, blob: String): String {
        val p = blob.split(":")
        if (p.size != 4 || p[0] != BLOB_PREFIX) {
            throw BackupException(BackupException.Kind.PARSE, "备份数据格式异常")
        }
        val salt: ByteArray
        val iv: ByteArray
        val ct: ByteArray
        try {
            salt = unb64(p[1]); iv = unb64(p[2]); ct = unb64(p[3])
        } catch (_: Exception) {
            throw BackupException(BackupException.Kind.PARSE, "备份数据格式异常")
        }
        return try {
            val key = deriveKey(code, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            throw BackupException(BackupException.Kind.CODE_INVALID, "备份码不正确, 或备份已损坏")
        }
    }

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
            throw BackupException(BackupException.Kind.TOO_LARGE, "设置数据过大, 无法上传")
        }
        val id = recordId(norm)

        val meta = JsonObject()
        meta.addProperty("app_version", appVersion(ctx))
        meta.addProperty("keys", plain.length)
        meta.addProperty("platform", "android")

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

        DebugStore.logI("Backup", "上传成功 id=$id size=${blob.length}")

        val keyCount = JsonParser().parse(plain).asJsonObject
            .get("keyCount")?.asInt ?: 0
        return BackupResult(norm, blob.length, updatedAt, keyCount)
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
