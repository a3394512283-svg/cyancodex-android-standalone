package com.canglan.cyancodex.data

import java.io.ByteArrayOutputStream

/**
 * 标准 MIDI 文件(SMF)写入器 —— 与 [MidiParser] 配对, 输出格式 1:
 *   第 0 轨: 曲名 + 速度 (元事件)
 *   第 1 轨: 音色 + 音符 (note on/off)
 *
 * 为什么不引第三方库: 只需要"写单个旋律轨"这一种能力, 自己写 150 行比拉个依赖更可控,
 * 而且能跟已有的 MidiParser 组成"写完立刻读回来"的自测闭环。
 */
object MidiWriter {

    /** 一个音符: startTick 起, 持续 lengthTicks */
    data class Note(
        val startTick: Int,
        val lengthTicks: Int,
        val note: Int,
        val velocity: Int = 96,
    )

    /**
     * @param notes   已按 startTick 排好序的音符
     * @param ppq     每四分音符 tick 数
     * @param tempoUs 每四分音符微秒 (500000 = 120 BPM)
     * @param program GM 音色号 (0 = 钢琴)
     */
    fun build(
        notes: List<Note>,
        ppq: Int = 480,
        tempoUs: Int = 500000,
        program: Int = 0,
        channel: Int = 0,
        trackName: String = "CyanCodex",
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // ---- MThd ----
        out.writeAscii("MThd")
        writeInt(out, 6)
        writeShort(out, 1)          // format 1
        writeShort(out, 2)          // 2 轨
        writeShort(out, ppq)

        // ---- 轨 0: 曲名 + 速度 ----
        val t0 = Track()
        t0.meta(0x03, trackName.toByteArray(Charsets.UTF_8))
        t0.meta(0x51, byteArrayOf(
            ((tempoUs shr 16) and 0xFF).toByte(),
            ((tempoUs shr 8) and 0xFF).toByte(),
            (tempoUs and 0xFF).toByte(),
        ))
        t0.end()
        writeTrack(out, t0)

        // ---- 轨 1: 音色 + 音符 ----
        val t1 = Track()
        t1.meta(0x03, "melody".toByteArray(Charsets.UTF_8))
        t1.programChange(channel, program)
        // 同一 tick 上先关后开, 避免同音高重叠导致挂音
        val events = ArrayList<Triple<Int, Boolean, Note>>(notes.size * 2)
        notes.forEach { n ->
            val start = n.startTick.coerceAtLeast(0)
            val end = start + n.lengthTicks.coerceAtLeast(1)
            events.add(Triple(start, true, n))
            events.add(Triple(end, false, n))
        }
        events.sortWith(compareBy({ it.first }, { if (it.second) 1 else 0 }))
        events.forEach { (tick, on, n) ->
            val status = (if (on) 0x90 else 0x80) or (channel and 0x0F)
            t1.event(status, n.note.coerceIn(0, 127), n.velocity.coerceIn(1, 127), tick)
        }
        t1.end()
        writeTrack(out, t1)

        return out.toByteArray()
    }

    // ---------------- 内部工具 ----------------

    /** 一条轨: 自己记着上一笔 tick, 好算 delta */
    private class Track {
        val buf = ByteArrayOutputStream()
        private var lastTick = 0

        private fun delta(tick: Int) {
            writeVlq(buf, (tick - lastTick).coerceAtLeast(0))
            lastTick = tick
        }

        fun meta(type: Int, data: ByteArray) {
            delta(lastTick)
            buf.write(0xFF); buf.write(type); writeVlq(buf, data.size); buf.write(data)
        }

        fun programChange(channel: Int, program: Int) {
            delta(lastTick)
            buf.write(0xC0 or (channel and 0x0F)); buf.write(program.coerceIn(0, 127))
        }

        fun event(status: Int, d1: Int, d2: Int, tick: Int) {
            delta(tick)
            buf.write(status); buf.write(d1); buf.write(d2)
        }

        fun end() = meta(0x2F, ByteArray(0))
    }

    private fun writeTrack(out: ByteArrayOutputStream, t: Track) {
        out.writeAscii("MTrk")
        val data = t.buf.toByteArray()
        writeInt(out, data.size)
        out.write(data)
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) = write(s.toByteArray(Charsets.US_ASCII))

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF); out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }

    /** 可变长数量: 每字节 7 位, 高位为续位 */
    private fun writeVlq(out: ByteArrayOutputStream, value: Int) {
        var v = value.coerceAtLeast(0)
        val tmp = IntArray(4)
        var n = 0
        tmp[n++] = v and 0x7F
        v = v shr 7
        while (v > 0) {
            tmp[n++] = (v and 0x7F) or 0x80
            v = v shr 7
        }
        for (i in n - 1 downTo 0) out.write(tmp[i])
    }
}
