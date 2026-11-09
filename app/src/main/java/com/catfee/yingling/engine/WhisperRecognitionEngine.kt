package com.catfee.yingling.engine

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 真实语音识别引擎：whisper.cpp（MIT）。
 *
 * - translateToEnglish = true 时使用 Whisper 的 translate 任务，中文语音直接输出英文；
 * - 否则输出源语言转写（本项目当前主要用于中文）。
 *
 * 采用能量 VAD 切段：连续说话 0.7s 以上、停顿 0.55s 后送推理；
 * 推理在单线程队列里执行，忙时丢弃新段，避免延迟无限堆积。
 */
class WhisperRecognitionEngine(
    private val modelPath: String,
    private val translateToEnglish: Boolean,
    private val sourceLanguage: String?,
) : RecognitionEngine {

    override val name: String = "Whisper small"
    override var onResult: ((RecognitionResult) -> Unit)? = null

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "yingling-whisper")
    }
    private val busy = AtomicBoolean(false)
    private val buffer = ShortBuffer()

    @Volatile
    private var paused = false

    @Volatile
    private var ready = false

    private var handle = 0L
    private var started = false

    private var speechMs = 0
    private var silenceMs = 0
    private var speaking = false

    override fun start() {
        if (started) return
        started = true
        worker.execute {
            val begin = SystemClock.elapsedRealtime()
            handle = try {
                WhisperBridge.nativeInit(modelPath)
            } catch (t: Throwable) {
                Log.e(TAG, "model init failed", t)
                0L
            }
            ready = handle != 0L
            Log.d(
                TAG,
                "model load ${SystemClock.elapsedRealtime() - begin}ms ready=$ready path=$modelPath",
            )
            if (!ready) {
                onResult?.invoke(RecognitionResult("", "Whisper 模型加载失败", "zh"))
            }
        }
    }

    override fun onAudio(pcm16k: ShortArray, level: Float) {
        if (paused || !ready) return

        val chunkMs = pcm16k.size / 16
        if (level > SPEECH_LEVEL) {
            speaking = true
            speechMs += chunkMs
            silenceMs = 0
        } else if (speaking) {
            silenceMs += chunkMs
        }

        if (speaking) buffer.append(pcm16k)

        val ended = speaking && silenceMs >= SILENCE_MS && speechMs >= MIN_SPEECH_MS
        val tooLong = speaking && speechMs >= MAX_SPEECH_MS
        if (ended || tooLong) {
            val pcm = buffer.toArray()
            buffer.clear()
            speechMs = 0
            silenceMs = 0
            speaking = false

            if (pcm.size >= MIN_SAMPLES) {
                if (!busy.compareAndSet(false, true)) {
                    Log.d(TAG, "drop segment ${pcm.size / 16}ms: busy")
                } else {
                    Log.d(TAG, "segment ${pcm.size / 16}ms -> queue")
                    runCatching {
                        worker.execute { transcribe(pcm) }
                    }.onFailure {
                        busy.set(false)
                        Log.e(TAG, "queue failed", it)
                    }
                }
            }
        }
    }

    private fun transcribe(pcm: ShortArray) {
        val begin = SystemClock.elapsedRealtime()
        try {
            val text = runCatching {
                WhisperBridge.nativeTranscribe(handle, pcm, translateToEnglish, sourceLanguage)
            }.getOrElse {
                Log.e(TAG, "nativeTranscribe failed", it)
                onResult?.invoke(RecognitionResult("", "Whisper 推理失败", "zh"))
                ""
            }
            val elapsed = SystemClock.elapsedRealtime() - begin
            Log.d(TAG, "transcribe ${pcm.size / 16}ms -> ${elapsed}ms chars=${text.length}")
            if (text.isBlank()) {
                Log.d(TAG, "empty result")
                return
            }
            val lang = if (translateToEnglish) "en" else (sourceLanguage ?: "zh")
            onResult?.invoke(
                RecognitionResult(
                    sourceText = if (translateToEnglish) "中文语音" else text,
                    translatedText = text,
                    langCode = lang,
                ),
            )
        } finally {
            busy.set(false)
        }
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
        resetVad()
    }

    override fun stop() {
        paused = true
        runCatching {
            worker.execute {
                if (handle != 0L) {
                    WhisperBridge.nativeRelease(handle)
                    handle = 0L
                }
            }
        }
        worker.shutdown()
    }

    private fun resetVad() {
        speechMs = 0
        silenceMs = 0
        speaking = false
        buffer.clear()
    }

    private class ShortBuffer {
        private var data = ShortArray(16_000 * 4)
        private var size = 0

        fun append(src: ShortArray) {
            ensure(size + src.size)
            src.copyInto(data, size)
            size += src.size
        }

        fun toArray(): ShortArray = data.copyOf(size)

        fun clear() {
            size = 0
        }

        private fun ensure(capacity: Int) {
            if (capacity <= data.size) return
            var next = data.size
            while (next < capacity) next *= 2
            data = data.copyOf(next)
        }
    }

    private companion object {
        const val TAG = "YingLingWhisper"
        const val SPEECH_LEVEL = 0.20f
        const val SILENCE_MS = 550
        const val MIN_SPEECH_MS = 700
        const val MAX_SPEECH_MS = 8_000
        const val MIN_SAMPLES = 16_000 / 2
    }
}
