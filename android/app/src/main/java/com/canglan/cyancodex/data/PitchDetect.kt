package com.canglan.cyancodex.data

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 音频 -> 音符 的纯算法部分（不依赖 Android，能在 JVM 单测里直接跑）。
 *
 * 流程: 逐帧 RMS 门限 -> YIN 基频 -> 频率转 MIDI 音高 -> 按音高切分音符 -> 估计速度 -> 量化到网格
 * 面向的是"单旋律"(人声哼唱 / 独奏 / 手机外放的一段旋律), 不是多声部扒谱。
 */
object PitchDetect {

    /** 一个音符事件(毫秒) */
    data class NoteEvent(
        val startMs: Int,
        val endMs: Int,
        val midi: Int,
        val velocity: Int,
        val confidence: Float,
    ) {
        val lengthMs: Int get() = (endMs - startMs).coerceAtLeast(0)
    }

    /** 分帧后的中间结果 */
    private data class Frame(val timeMs: Int, val f0: Float, val rms: Float)

    /**
     * YIN 基频估计。返回 Hz；0 表示这一帧没有可靠音高。
     * 参考 de Cheveigné & Kawahara (2002) 的 cumulative mean normalized difference。
     */
    fun yin(frame: FloatArray, sampleRate: Int, fMin: Float = 65f, fMax: Float = 1200f, threshold: Float = 0.15f): Float {
        val maxTau = (sampleRate / fMin).toInt().coerceAtMost(frame.size / 2)
        val minTau = (sampleRate / fMax).toInt().coerceAtLeast(2)
        val w = frame.size - maxTau
        if (w <= minTau || maxTau <= minTau) return 0f

        val diff = FloatArray(maxTau + 1)
        for (tau in minTau..maxTau) {
            var sum = 0f
            var j = 0
            while (j < w) {
                val d = frame[j] - frame[j + tau]
                sum += d * d
                j++
            }
            diff[tau] = sum
        }

        val cmnd = FloatArray(maxTau + 1)
        cmnd[0] = 1f
        var running = 0f
        for (tau in 1..maxTau) {
            running += diff[tau]
            cmnd[tau] = if (running <= 0f) 1f else diff[tau] * tau / running
        }

        // 找第一个低于阈值的局部最小
        var tau = minTau
        while (tau < maxTau) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 < maxTau && cmnd[tau + 1] < cmnd[tau]) tau++
                // 抛物线插值提高精度
                val x0 = cmnd[if (tau - 1 < 0) tau else tau - 1]
                val x2 = cmnd[if (tau + 1 > maxTau) tau else tau + 1]
                val denom = 2f * (2f * cmnd[tau] - x2 - x0)
                val shift = if (abs(denom) < 1e-9f) 0f else (x2 - x0) / denom
                val refined = tau + shift
                val f = sampleRate / refined
                return if (f in fMin..fMax) f else 0f
            }
            tau++
        }
        return 0f
    }

    fun rms(frame: FloatArray, from: Int = 0, until: Int = frame.size): Float {
        var sum = 0.0
        var n = 0
        for (i in from until until) {
            val v = frame[i].toDouble()
            sum += v * v
            n++
        }
        return if (n == 0) 0f else sqrt(sum / n).toFloat()
    }

    /** 频率 -> MIDI 音高(带小数) */
    fun freqToMidi(f: Float): Float = (69f + 12f * log2(f / 440f))

    /** MIDI 音高 -> 频率 */
    fun midiToFreq(m: Int): Float = (440.0 * Math.pow(2.0, (m - 69) / 12.0)).toFloat()

    /**
     * 从单声道 PCM(float, -1..1) 提取音符。
     * 面向单旋律: 相邻帧音高相同(允许 ±1 半音抖动)就并成一个音符。
     */
    fun detectNotes(
        pcm: FloatArray,
        sampleRate: Int,
        frameSize: Int = 2048,
        hop: Int = 512,
        minNoteMs: Int = 70,
        rmsGate: Float = 0.012f,
        maxNoteMs: Int = 4000,
    ): List<NoteEvent> {
        if (pcm.size < frameSize) return emptyList()
        val frames = ArrayList<Frame>()
        var pos = 0
        while (pos + frameSize <= pcm.size) {
            val frame = pcm.copyOfRange(pos, pos + frameSize)
            val level = rms(frame)
            val f0 = if (level < rmsGate) 0f else yin(frame, sampleRate)
            frames.add(Frame((pos * 1000L / sampleRate).toInt(), f0, level))
            pos += hop
        }
        if (frames.isEmpty()) return emptyList()

        // 先做一次 5 帧中值滤波, 抹掉八度跳变/瞬时误判
        val smoothed = FloatArray(frames.size)
        for (i in frames.indices) {
            val lo = max(0, i - 2)
            val hi = minOf(frames.size - 1, i + 2)
            val win = (lo..hi).map { freqToMidiOrZero(frames[it].f0) }.sorted()
            smoothed[i] = win[win.size / 2]
        }

        val hopMs = hop * 1000f / sampleRate
        val notes = ArrayList<NoteEvent>()
        var i = 0
        while (i < frames.size) {
            val m = smoothed[i]
            val level = frames[i].rms
            if (m <= 0f || level < rmsGate) { i++; continue }
            var j = i + 1
            var levelSum = level
            var count = 1
            while (j < frames.size && smoothed[j] > 0f && frames[j].rms >= rmsGate &&
                abs(smoothed[j] - m) <= 1f
            ) {
                levelSum += frames[j].rms
                count++
                j++
            }
            val startMs = frames[i].timeMs
            val endMs = minOf(frames[j - 1].timeMs + hopMs.toInt(), frames.last().timeMs + hopMs.toInt())
            val midi = m.roundToInt().coerceIn(21, 108)
            val avgLevel = levelSum / count
            // 音量 -> 力度(40..115), 用开方压一下动态范围
            val vel = (40 + sqrt(avgLevel.coerceIn(0f, 1f)) * 95).roundToInt().coerceIn(40, 115)
            val length = endMs - startMs
            if (length >= minNoteMs) {
                val conf = (1f - (avgLevel / (avgLevel + rmsGate + 1e-6f))).coerceIn(0f, 1f)
                notes.add(NoteEvent(startMs, minOf(endMs, startMs + maxNoteMs), midi, vel, conf))
            }
            i = if (j > i) j else i + 1
        }
        return notes
    }

    private fun freqToMidiOrZero(f: Float): Float = if (f <= 0f) 0f else freqToMidi(f)

    /**
     * 用音符起始间隔估计速度。
     * 思路: 相邻起点的差值取中位数当作一个"最小时值", 再把它归到常见 BPM 上;
     * 估不准也没关系, 量化时用户可以自己选速度。
     */
    fun estimateBpm(notes: List<NoteEvent>, fallback: Int = 120): Int {
        if (notes.size < 3) return fallback
        val gaps = ArrayList<Int>()
        for (i in 1 until notes.size) {
            val g = notes[i].startMs - notes[i - 1].startMs
            if (g in 50..2000) gaps.add(g)
        }
        if (gaps.isEmpty()) return fallback
        gaps.sort()
        val median = gaps[gaps.size / 2].toDouble()
        // 中位间隔当成 1/4 拍(十六分音符)来估, 落在 60..180 之间
        var bpm = 60000.0 / (median * 4)
        while (bpm < 60) bpm *= 2
        while (bpm > 180) bpm /= 2
        return (bpm / 5).roundToInt() * 5
    }

    /**
     * 量化成 MIDI 音符: 起点吸附到 1/16 网格(网格可由 stepDiv 调),
     * 时值至少一个网格, 结果按 tick 升序。
     */
    fun toMidiNotes(
        notes: List<NoteEvent>,
        bpm: Int = 120,
        ppq: Int = 480,
        stepDiv: Int = 4,
        maxTicks: Int = 0,
    ): List<MidiWriter.Note> {
        val safeBpm = bpm.coerceIn(30, 300)
        val msPerTick = 60000.0 / (safeBpm * ppq)
        val grid = (ppq.toDouble() / stepDiv).coerceAtLeast(1.0)
        val out = ArrayList<MidiWriter.Note>(notes.size)
        for (n in notes) {
            val startTickRaw = n.startMs / msPerTick
            val lenTickRaw = n.lengthMs / msPerTick
            var start = (Math.round(startTickRaw / grid) * grid).toInt()
            var len = (Math.round(lenTickRaw / grid) * grid).toInt().coerceAtLeast(grid.toInt())
            if (maxTicks > 0) {
                if (start >= maxTicks) continue
                len = minOf(len, maxTicks - start)
            }
            if (start < 0) start = 0
            out.add(MidiWriter.Note(startTick = start, lengthTicks = len, note = n.midi.coerceIn(0, 127), velocity = n.velocity))
        }
        return out.sortedBy { it.startTick }
    }

    /**
     * 重采样到目标采样率(线性插值)。
     * 解码出来的 PCM 可能是 44.1k/48k 立体声, 统一换成单声道 22050Hz 再做基频检测,
     * 既省一半计算量, 也能滤掉一部分高频噪声(对基频检测更友好)。
     */
    fun toMonoResampled(
        interleaved: FloatArray,
        channels: Int,
        srcRate: Int,
        dstRate: Int = 22050,
        maxSeconds: Double = 180.0,
    ): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = interleaved.size / ch
        val maxFrames = minOf(frames, (srcRate * maxSeconds).toInt())
        if (ch == 1 && srcRate == dstRate) return interleaved.copyOf(maxFrames)

        val outLen = (maxFrames.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = srcRate.toDouble() / dstRate
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceIn(0, maxFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(maxFrames - 1)
            val frac = (srcPos - i0).toFloat()
            var sum = 0f
            for (c in 0 until ch) {
                val a = interleaved[i0 * ch + c]
                val b = interleaved[i1 * ch + c]
                sum += a + (b - a) * frac
            }
            out[i] = sum / ch
        }
        return out
    }
}
