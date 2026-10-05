package com.canglan.cyancodex.data

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

/**
 * 客户端检测 (对标网易《我的世界》):
 * 校验自身 APK 签名是否为官方签名。第三方客户端/重打包/篡改 → 签名不符,
 * App 自检拦截 + 上报服务器封禁账号; 服务器同时校验 X-App-Signature 白名单。
 */
object TamperDetector {
    private const val TAG = "TamperDetector"

    // 官方签名 SHA-256 (hex 无冒号), XOR 预编码字节存储, 运行时解码 (源码/APK 均无明文)
    private val EXP_SIG: ByteArray = byteArrayOf(
        0x51, 0x50, 0x2D, 0x23, 0x2A, 0x52, 0x59, 0x74, 0x73, 0x00, 0x72, 0x5B, 0x22, 0x58, 0x55, 0x5A,
        0x51, 0x2A, 0x00, 0x01, 0x71, 0x07, 0x25, 0x27, 0x2F, 0x5F, 0x55, 0x55, 0x5A, 0x04, 0x00, 0x04,
        0x06, 0x5B, 0x20, 0x58, 0x5E, 0x5F, 0x59, 0x5B, 0x07, 0x05, 0x00, 0x77, 0x55, 0x57, 0x5B, 0x5F,
        0x29, 0x20, 0x2C, 0x77, 0x04, 0x0B, 0x00, 0x50, 0x25, 0x2D, 0x57, 0x29, 0x53, 0x2C, 0x74, 0x76
    )
    private const val XOR_KEY = "canglan2026"

    @Volatile private var cached: String? = null

    /** 在 Application.onCreate 调用, 缓存当前安装包签名 SHA-256 (大写 hex 无冒号) */
    fun init(context: Context) {
        cached = compute(context)
    }

    /** 当前安装包的签名 SHA-256 (可能为 null 表示无法获取) */
    fun ownSignature(): String? = cached

    /** 是否为官方签名 */
    fun isOfficial(): Boolean {
        val own = cached ?: return false
        return own.equals(xorDecode(EXP_SIG), ignoreCase = true)
    }

    private fun compute(context: Context): String? = try {
        val pm = context.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val certs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        val cert = certs?.firstOrNull() ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(cert.toByteArray()).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    } catch (e: Exception) {
        Log.e(TAG, "signature compute failed", e)
        null
    }

    private fun xorEncode(s: String): ByteArray {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        return ByteArray(b.size) { (b[it].toInt() xor XOR_KEY[it % XOR_KEY.length].code).toByte() }
    }

    private fun xorDecode(arr: ByteArray): String {
        val b = ByteArray(arr.size) { (arr[it].toInt() xor XOR_KEY[it % XOR_KEY.length].code).toByte() }
        return String(b, Charsets.ISO_8859_1)
    }
}
