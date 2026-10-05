package com.canglan.cyancodex.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MP3(以及其它系统能解码的音频) -> MIDI，**全在手机本地完成**，不上传任何音频。
 *
 * 流程:
 *   MediaExtractor 解封装 -> MediaCodec 解码成 PCM -> 混单声道 + 降到 22.05kHz
 *   -> [PitchDetect] 逐帧 YIN 基频 -> 音符切分 -> 估速 -> 量化 -> [MidiWriter] 写 SMF
 *
 * 说明: 这是"单旋律扒谱", 适合人声哼唱、独奏、外放的一段旋律;
 * 多声部/强打击乐的音频转出来会丢信息 —— 界面上有写明, 不吹成"专业扒谱"。
 */
object AudioToMidi {

    data class Result(
        val file: File,
        val bytes: ByteArray,
        val noteCount: Int,
        val durationSec: Int,
        val bpm: Int,
    )

    /** 分析时长上限(秒): 再长就截断, 免得低端机 OOM */
    const val MAX_ANALYZE_SECONDS = 90

    class ConvertError(message: String) : Exception(message)

    /**
     * @param onProgress (0..1, 中文进度说明)
     */
    fun convert(
        ctx: Context,
        uri: Uri,
        bpmOverride: Int = 0,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result {
        onProgress(0.02f, "正在打开音频…")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ctx, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
            }
            if (trackIndex < 0 || format == null) throw ConvertError("这个文件里没有音频轨道")
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw ConvertError("读不到音频编码格式")
            extractor.selectTrack(trackIndex)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val chunks = ArrayList<FloatArray>(256)
            var totalSamples = 0
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val capSamples = MAX_ANALYZE_SECONDS * 48000          // 以最高常见采样率兜住上限
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 再等一轮 */ }
                    else -> if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            val mono = readMono(outBuf, info, channels)
                            if (mono.isNotEmpty()) {
                                chunks.add(mono)
                                totalSamples += mono.size
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        val pct = (totalSamples.toFloat() / capSamples).coerceIn(0f, 1f)
                        onProgress(0.05f + pct * 0.55f, "正在解码音频… ${(pct * 100).toInt()}%")
                        if (totalSamples >= capSamples) sawOutputEos = true
                    }
                }
            }

            if (totalSamples == 0) throw ConvertError("没有解出音频数据（文件可能损坏或用了不支持的编码）")

            onProgress(0.62f, "正在合成单声道…")
            val joined = FloatArray(totalSamples)
            var off = 0
            for (c in chunks) { System.arraycopy(c, 0, joined, off, c.size); off += c.size }
            chunks.clear()

            val monoSr = 22050
            val mono = PitchDetect.toMonoResampled(joined, 1, sampleRate, monoSr, MAX_ANALYZE_SECONDS.toDouble())
            val durationSec = (mono.size.toFloat() / monoSr).toInt()

            onProgress(0.7f, "正在识别音高（这段最费时间）…")
            val events = PitchDetect.detectNotes(mono, monoSr, rmsGate = 0.012f)
            if (events.isEmpty()) throw ConvertError("没听出旋律：试试人声哼唱、独奏片段，或音量更大的片段")

            onProgress(0.9f, "正在整理节拍…")
            val bpm = if (bpmOverride > 0) bpmOverride else PitchDetect.estimateBpm(events)
            val notes = PitchDetect.toMidiNotes(events, bpm = bpm)
            val midi = MidiWriter.build(
                notes,
                ppq = 480,
                tempoUs = (60_000_000.0 / bpm).toInt(),
                program = 0,
                trackName = "MP3 to MIDI",
            )

            val dir = File(ctx.filesDir, "midi").apply { mkdirs() }
            val file = File(dir, "mp3tomidi-${System.currentTimeMillis()}.mid")
            file.writeBytes(midi)
            onProgress(1f, "完成：${events.size} 个音符")
            return Result(file, midi, events.size, durationSec, bpm)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 把一块解码输出转成单声道 float(-1..1); 16bit 与 float 两种编码都处理 */
    private fun readMono(buf: ByteBuffer, info: MediaCodec.BufferInfo, channels: Int): FloatArray {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val ch = channels.coerceAtLeast(1)
        val shortCount = info.size / 2
        if (shortCount < ch) return FloatArray(0)
        val frames = shortCount / ch
        val out = FloatArray(frames)
        val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) sum += shorts.get(i * ch + c).toInt()
            out[i] = (sum.toFloat() / ch) / 32768f
        }
        return out
    }
}
