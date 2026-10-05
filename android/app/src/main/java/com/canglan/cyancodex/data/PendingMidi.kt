package com.canglan.cyancodex.data

/**
 * 「MP3 转 MIDI / 曲库下载」到「音乐生成器」的交接。
 *
 * 为什么用这么个小对象: 音乐生成器目前只能从系统文件选择器拿 MIDI(ActivityResult),
 * 而我们要把**刚生成/刚下载的字节**直接送进去。放一个一次性信箱最省事,
 * 音乐生成器进页面时取一次就清空, 不引入新的路由参数和序列化负担。
 */
object PendingMidi {
    private var payload: ByteArray? = null
    private var label: String = ""

    fun set(bytes: ByteArray, label: String = "") {
        payload = bytes
        this.label = label
    }

    /** 取出并清空(取过一次就没了, 避免返回页面时重复导入) */
    fun take(): ByteArray? {
        val b = payload
        payload = null
        return b
    }

    val pendingLabel: String get() = label
}
