package com.canglan.cyancodex.data

/**
 * 标准 MIDI 文件(SMF)解析器: 提取音符/速度/音色, 供音乐生成器导入 (参考 HASOC 工具箱的 MIDI 导入)
 * 支持格式 0/1, PPQ 与 SMPTE 时间基准, 运行状态(running status), meta 事件(速度/轨尾)
 */
object MidiParser {

    data class MidiNote(val tick: Int, val channel: Int, val note: Int, val velocity: Int)

    data class MidiSong(
        val ppq: Int,                    // 每四分音符的 tick 数
        val tempoUs: Int,                // 每四分音符微秒 (默认 500000 = 120 BPM)
        val notes: List<MidiNote>,       // 按 tick 排序
        val programs: IntArray,          // 每个通道的 GM 音色号 (-1 未指定)
    )

    fun parse(bytes: ByteArray): MidiSong? {
        if (bytes.size < 14) return null
        var i = 0
        fun read16(): Int {
            val v = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF); i += 2; return v
        }
        fun read32(): Int {
            val v = ((bytes[i].toInt() and 0xFF) shl 24) or ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF); i += 4; return v
        }
        fun readStr(n: Int): String { val s = String(bytes, i, n, Charsets.US_ASCII); i += n; return s }

        if (readStr(4) != "MThd") return null
        read32() // header length (通常 6)
        read16() // format (0/1/2)
        val ntrks = read16()
        val division = read16()
        val ppq = if (division and 0x8000 != 0) division and 0x7FFF else division
        if (ntrks <= 0) return null

        val allNotes = mutableListOf<MidiNote>()
        val programs = IntArray(16) { -1 }
        var tempoUs = 500000

        repeat(ntrks) {
            if (i + 8 > bytes.size) return@repeat
            if (readStr(4) != "MTrk") return@repeat
            val trackLen = read32()
            val end = minOf(i + trackLen, bytes.size)
            var tick = 0
            var running: Int? = null
            while (i < end) {
                val delta = readVlq(bytes, i) { i = it }
                tick += delta
                if (i >= end) break
                var b = bytes[i].toInt() and 0xFF
                if (b and 0x80 == 0) {
                    b = running ?: break
                } else {
                    i++
                    running = b
                }
                val type = b and 0xF0
                val ch = b and 0x0F
                when (type) {
                    0x80 -> { if (i + 2 <= bytes.size) i += 2 else break }   // note off
                    0x90 -> {                                                // note on
                        if (i + 2 > bytes.size) break
                        val n = bytes[i].toInt() and 0xFF; i++
                        val v = bytes[i].toInt() and 0xFF; i++
                        if (v > 0) allNotes.add(MidiNote(tick, ch, n, v))
                    }
                    0xA0, 0xB0, 0xE0 -> { if (i + 2 <= bytes.size) i += 2 else break }
                    0xC0 -> { if (i + 1 <= bytes.size) { programs[ch] = bytes[i].toInt() and 0xFF; i++ } else break }
                    0xD0 -> { if (i + 1 <= bytes.size) i += 1 else break }
                    0xF0, 0xF7 -> {
                        if (b == 0xFF) {                                      // meta 事件
                            if (i + 1 > bytes.size) break
                            val mtype = bytes[i].toInt() and 0xFF; i++
                            val len = readVlq(bytes, i) { i = it }
                            if (i + len > bytes.size) break
                            if (mtype == 0x51 && len >= 3) {                  // set tempo (微秒/四分音符)
                                tempoUs = ((bytes[i].toInt() and 0xFF) shl 16) or
                                    ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
                            }
                            i += len
                        } else {                                              // sysex
                            val len = readVlq(bytes, i) { i = it }
                            if (i + len > bytes.size) break
                            i += len
                        }
                    }
                    else -> return@repeat
                }
            }
        }

        if (allNotes.isEmpty()) return null
        return MidiSong(if (ppq > 0) ppq else 480, tempoUs, allNotes.sortedBy { it.tick }, programs)
    }

    /** 可变长数量: 每字节 7 位, 高位为续位 */
    private fun readVlq(bytes: ByteArray, start: Int, advance: (Int) -> Unit): Int {
        var v = 0
        var p = start
        while (p < bytes.size) {
            val b = bytes[p].toInt() and 0xFF
            p++
            v = (v shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) break
        }
        advance(p)
        return v
    }
}
