package com.catfee.yingling.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection

/**
 * 通过 AudioPlaybackCapture 捕获其他应用播放的音频。
 *
 * 注意：在当前 Android SDK 上 AudioPlaybackCaptureConfiguration 必须绑定
 * MediaProjection（系统“屏幕录制”授权）。应灵只用它取音频，不创建虚拟显示、不录屏。
 *
 * - [uids] 非空：只捕获这些应用（addMatchingUid）
 * - [uids] 为空：按 USAGE_MEDIA / GAME / UNKNOWN 捕获所有允许采集的应用
 *
 * 输出的 16k 单声道 PCM 直接喂给识别引擎；level 用于声浪可视化。
 */
class AudioCaptureEngine(
    private val mediaProjection: MediaProjection,
    private val uids: List<Int>,
    private val onLevel: (Float) -> Unit,
    private val onPcm16k: (ShortArray, Float) -> Unit,
) {
    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val converter = PcmConverter(SAMPLE_RATE, TARGET_RATE)

    fun start(): Boolean {
        if (running) return true

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        if (minBuffer <= 0) return false

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection).apply {
            if (uids.isEmpty()) {
                addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                addMatchingUsage(AudioAttributes.USAGE_GAME)
                addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            } else {
                uids.forEach { addMatchingUid(it) }
            }
        }.build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(ENCODING)
            .setChannelMask(CHANNEL_MASK)
            .build()

        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, BUFFER_BYTES))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (_: Exception) {
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        record = rec
        running = true
        return try {
            rec.startRecording()
            thread = Thread({ readLoop(rec) }, "yingling-capture").apply { start() }
            true
        } catch (_: Exception) {
            running = false
            runCatching { rec.release() }
            record = null
            false
        }
    }

    private fun readLoop(rec: AudioRecord) {
        val buffer = ShortArray(4096)
        val mono = ShortArray(buffer.size / CHANNELS)
        try {
            while (running) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                val frames = read / CHANNELS
                if (frames <= 0) continue

                var sum = 0.0
                for (i in 0 until frames) {
                    val left = buffer[i * CHANNELS]
                    val right = buffer[i * CHANNELS + 1]
                    val mixed = ((left.toInt() + right.toInt()) / 2).toShort()
                    mono[i] = mixed
                    val v = mixed / 32768.0
                    sum += v * v
                }

                val level = LevelMeter.fromMeanSquare(sum / frames)
                onLevel(level)
                val pcm = converter.convert(mono, frames)
                if (pcm.isNotEmpty()) onPcm16k(pcm, level)
            }
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    fun stop() {
        running = false
        val rec = record
        record = null
        thread = null
        if (rec != null) {
            // 让阻塞中的 read() 立刻返回，由采集线程自己释放
            runCatching { rec.stop() }
        }
        converter.reset()
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val TARGET_RATE = 16_000
        private const val CHANNELS = 2
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        private const val BUFFER_BYTES = 64 * 1024
    }
}
