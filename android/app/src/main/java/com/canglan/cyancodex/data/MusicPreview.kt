package com.canglan.cyancodex.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.canglan.cyancodex.generators.Generators.NoteEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * 音乐生成器试听: 用合成音色按节奏播放音符网格
 * 音高: Minecraft 音阶 F#3(369.99Hz) 起, note 0..24
 * 节奏: tempoMs = 每拍时长(毫秒), 16 列 = 16 拍
 */
object MusicPreview {
    private const val SAMPLE_RATE = 44100
    @Volatile private var playing = false

    fun isPlaying(): Boolean = playing

    fun stop() { playing = false }

    /** 试听整段网格 */
    fun play(events: List<NoteEvent>, tempoMs: Int, onDone: () -> Unit) {
        if (events.isEmpty()) return
        Thread {
            playing = true
            try {
                val maxTick = events.maxOfOrNull { it.tick } ?: 0
                val totalMs = (maxTick + 1) * tempoMs.toLong()
                val totalSamples = (totalMs * SAMPLE_RATE / 1000).toInt() + 1
                val buf = ShortArray(totalSamples)
                val beatSamples = tempoMs.toLong() * SAMPLE_RATE / 1000
                events.groupBy { it.tick }.forEach { (tick, evs) ->
                    val start = (tick.toLong() * beatSamples).toInt()
                    evs.forEach { ev -> addNote(buf, start, ev, tempoMs) }
                }
                // 归一化音量
                var max = 0
                for (s in buf) { val a = abs(s.toInt()); if (a > max) max = a }
                val gain = if (max > 0) 30000.0 / max else 1.0
                val at = buildTrack()
                at.play()
                val chunk = ShortArray(4410)
                var i = 0
                while (i < buf.size && playing) {
                    val n = minOf(chunk.size, buf.size - i)
                    for (j in 0 until n) chunk[j] = (buf[i + j] * gain).toInt().toShort()
                    at.write(chunk, 0, n)
                    i += n
                }
                try { at.stop() } catch (e: Exception) {}
                at.release()
            } catch (e: Exception) {
            } finally {
                playing = false
                onDone()
            }
        }.start()
    }

    /** 点击网格单音试听: 播放一个音符(不打断整段试听) */
    fun playNote(instrument: String, note: Int, durationMs: Int = 500) {
        Thread {
            try {
                val f = 369.99 * Math.pow(2.0, note.coerceIn(0, 24) / 12.0)
                val n = (durationMs * SAMPLE_RATE / 1000).coerceAtLeast(100)
                val buf = ShortArray(n)
                val noise = instrument == "snare" || instrument == "hat"
                for (i in 0 until n) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = exp(-4.0 * t * 1000.0 / durationMs)
                    val v = wave(instrument, f, t, noise) * env
                    buf[i] = (v * Short.MAX_VALUE * 0.5).toInt().coerceIn(-32768, 32767).toShort()
                }
                val at = buildTrack()
                at.play()
                at.write(buf, 0, buf.size)
                try { at.stop() } catch (e: Exception) {}
                at.release()
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun buildTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
        .setBufferSizeInBytes(SAMPLE_RATE * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    /** 音色波形: 噪音(军鼓/踩镲), 方波(贝斯/底鼓), 谐波(铃类/拨弦等), 正弦(其余) */
    private fun wave(instrument: String, f: Double, t: Double, noise: Boolean): Double {
        if (noise) return Math.random() * 2 - 1
        return when (instrument) {
            "bass", "bd" -> (if (sin(2 * PI * f * t) >= 0) 1.0 else -1.0) * 0.55
            "bell", "chime", "pling", "xylophone", "iron_xylophone", "cow_bell", "bit", "banjo" ->
                (sin(2 * PI * f * t) + 0.5 * sin(4 * PI * f * t)) * 0.7
            else -> sin(2 * PI * f * t)
        }
    }

    private fun addNote(buf: ShortArray, start: Int, ev: NoteEvent, tempoMs: Int) {
        val f = 369.99 * Math.pow(2.0, ev.note.coerceIn(0, 24) / 12.0)
        val durSamples = (tempoMs * SAMPLE_RATE / 1000).toInt()
        val noise = ev.instrument == "snare" || ev.instrument == "hat"
        for (i in 0 until durSamples) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-3.5 * t * 1000.0 / tempoMs)
            val v = wave(ev.instrument, f, t, noise) * env
            buf[idx] = (buf[idx] + (v * Short.MAX_VALUE * 0.6)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
