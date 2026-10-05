package com.canglan.cyancodex.data

import okhttp3.Dns
import java.net.InetAddress

/**
 * 直连高防主服务器:
 *  - 把官方 API 域名强制解析到内置的高防 IP (不查 DNS, 避免 DNS/CDN 异常影响连接)
 *  - 仍然使用域名做 HTTPS 证书校验(SNI), 所以不需要放宽容错证书
 *  - 同时把系统 DNS 解析出的地址追加在后面作为备用线路, 直连失败时自动回退
 *  - 其它域名(AI 接口/图片外链等)不受影响, 照常走系统 DNS
 */
object ServerDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        val forced = Session.forcedIpFor(hostname) ?: return system
        val direct = runCatching { InetAddress.getByName(forced) }.getOrNull() ?: return system
        // 直连 IP 优先, 备用线路去重后追加
        return listOf(direct) + system.filter { it.hostAddress != direct.hostAddress }
    }
}
