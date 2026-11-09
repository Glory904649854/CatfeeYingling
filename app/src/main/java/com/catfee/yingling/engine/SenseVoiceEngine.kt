package com.catfee.yingling.engine

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 日/韩 → 中文：SenseVoice 离线多语种识别（支持 zh/en/ja/ko/yue）+ Qwen 逐句翻译。
 *
 * SenseVoice 是非流式模型，用能量 VAD 切成 1~6 秒的语音段后整段识别；
 * 识别速度很快（RTF 远低于 Whisper），再交给 Qwen 翻译。
 */
class SenseVoiceEngine(
    private val model: SenseVoiceModel,
    private val qwenModelPath: String,
    private val sourceLanguage: String,
    private val targetLanguage: String,
) : RecognitionEngine {

    override val name: String = "SenseVoice + Qwen"
    override var onResult: ((RecognitionResult) -> Unit)? = null

    private val asrWorker = Executors.newSingleThreadExecutor { Thread(it, "yingling-sensevoice") }
    private val mtWorker = Executors.newSingleThreadExecutor { Thread(it, "yingling-qwen") }
    private val asrBusy = AtomicBoolean(false)
    private val mtBusy = AtomicBoolean(false)
    private val buffer = ShortBuffer()

    private var recognizer: OfflineRecognizer? = null
    private var qwenHandle = 0L

    @Volatile
    private var ready = false

    @Volatile
    private var paused = false

    @Volatile
    private var pendingText: String? = null

    private var speechMs = 0
    private var silenceMs = 0
    private var speaking = false

    override fun start() {
        asrWorker.execute {
            try {
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = model.model.absolutePath,
                            language = sourceLanguage,
                            useInverseTextNormalization = true,
                        ),
                        tokens = model.tokens.absolutePath,
                        numThreads = 2,
                        modelType = "sense_voice",
                    ),
                )
                // 绝对路径加载模型时 AssetManager 必须为 null
                val ctor = OfflineRecognizer::class.java.getConstructor(
                    AssetManager::class.java,
                    OfflineRecognizerConfig::class.java,
                )
                recognizer = ctor.newInstance(null, config) as OfflineRecognizer
            } catch (t: Throwable) {
                Log.e(TAG, "SenseVoice init failed", t)
                ready = false
                onResult?.invoke(RecognitionResult("", "SenseVoice 模型加载失败", "zh"))
                return@execute
            }

            qwenHandle = try {
                QwenBridge.nativeInit(qwenModelPath)
            } catch (t: Throwable) {
                Log.e(TAG, "Qwen init failed", t)
                0L
            }

            ready = qwenHandle != 0L
            Log.d(TAG, "ready=$ready qwen=$qwenHandle")
        }
    }

    override fun onAudio(pcm16k: ShortArray, level: Float) {
        if (paused || !ready || pcm16k.isEmpty()) return

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

            if (pcm.size >= MIN_SAMPLES && asrBusy.compareAndSet(false, true)) {
                runCatching {
                    asrWorker.execute { decode(pcm) }
                }.onFailure {
                    asrBusy.set(false)
                    Log.e(TAG, "queue failed", it)
                }
            }
        }
    }

    private fun decode(pcm: ShortArray) {
        try {
            val rec = recognizer ?: return
            val stream = rec.createStream()
            try {
                val samples = FloatArray(pcm.size) { pcm[it] / 32768.0f }
                stream.acceptWaveform(samples, 16_000)
                rec.decode(stream)
                val text = rec.getResult(stream).text.trim()
                Log.d(TAG, "asr ${pcm.size / 16}ms -> $text")
                if (text.isNotBlank()) {
                    onResult?.invoke(RecognitionResult(text, "", sourceLanguage))
                    translate(text)
                }
            } finally {
                runCatching { stream.release() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
        } finally {
            asrBusy.set(false)
        }
    }

    private fun translate(text: String) {
        if (mtBusy.getAndSet(true)) {
            pendingText = text
            return
        }
        mtWorker.execute { runTranslation(text) }
    }

    private fun runTranslation(text: String) {
        try {
            val begin = SystemClock.elapsedRealtime()
            val translated = try {
                QwenBridge.nativeTranslate(qwenHandle, text, targetLanguage)
            } catch (t: Throwable) {
                Log.e(TAG, "translate failed", t)
                ""
            }.let(::cleanTranslation)

            Log.d(TAG, "translate ${text.length} chars -> ${SystemClock.elapsedRealtime() - begin}ms: $translated")
            if (translated.isNotBlank()) {
                onResult?.invoke(RecognitionResult(text, translated, targetLanguage))
            }
        } finally {
            mtBusy.set(false)
            val next = pendingText
            pendingText = null
            if (next != null) translate(next)
        }
    }

    private fun cleanTranslation(raw: String): String {
        var text = raw.trim()
        text = text.replace(
            Regex("(?i)^(sure[,，]?\\s*)?(here is the translation[:：]?\\s*)"),
            "",
        )
        text = text.replace(Regex("(?i)^translation[:：]\\s*"), "")
        text = text.replace(Regex("(?i)^(english|chinese)[:：]\\s*"), "")
        text = text.trim().trim('"', '\'', '“', '”', '\n', ' ')

        // 目标中文却输出纯英文，说明模型没有翻译（常见于识别出的乱码），丢弃
        if (targetLanguage == "zh" && text.isNotEmpty() &&
            text.none { it.code in 0x4E00..0x9FFF }
        ) {
            Log.w(TAG, "drop non-Chinese output: $text")
            return ""
        }
        return text
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
        speechMs = 0
        silenceMs = 0
        speaking = false
        buffer.clear()
    }

    override fun stop() {
        paused = true
        ready = false
        asrWorker.execute {
            runCatching { recognizer?.release() }
            recognizer = null
        }
        mtWorker.execute {
            if (qwenHandle != 0L) {
                runCatching { QwenBridge.nativeRelease(qwenHandle) }
                qwenHandle = 0L
            }
        }
        asrWorker.shutdown()
        mtWorker.shutdown()
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
        const val TAG = "YingLingSenseVoice"
        const val SPEECH_LEVEL = 0.20f
        const val SILENCE_MS = 500
        const val MIN_SPEECH_MS = 700
        const val MAX_SPEECH_MS = 6_000
        const val MIN_SAMPLES = 16_000 / 2
    }
}
