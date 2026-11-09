package com.catfee.yingling.engine

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 流式方案：sherpa-onnx（zipformer 中英双语）+ Qwen 小模型逐句翻译。
 *
 * - zipformer 边解码边出中文 partial，悬浮窗先显示中文；
 * - 端点检测到一句结束后，把整句交给 Qwen 翻成英文；
 * - 翻译只保留最新一句，避免延迟堆积。
 */
class SherpaStreamingEngine(
    private val model: SherpaModel,
    private val translator: Translator,
    private val targetLanguage: String,
) : RecognitionEngine {

    override val name: String = if (translator.isCloud) "Sherpa + 云端 LLM" else "Sherpa + Qwen"
    override var onResult: ((RecognitionResult) -> Unit)? = null

    private val asrWorker = Executors.newSingleThreadExecutor { Thread(it, "yingling-sherpa") }
    private val mtWorker = Executors.newSingleThreadExecutor { Thread(it, "yingling-qwen") }
    private val mtBusy = AtomicBoolean(false)

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    @Volatile
    private var ready = false

    @Volatile
    private var paused = false

    @Volatile
    private var pendingText: String? = null

    private var lastPartial = ""
    private var lastPartialAt = 0L
    private var segmentStartAt = 0L

    override fun start() {
        asrWorker.execute {
            try {
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = model.encoder.absolutePath,
                            decoder = model.decoder.absolutePath,
                            joiner = model.joiner.absolutePath,
                        ),
                        tokens = model.tokens.absolutePath,
                        numThreads = 2,
                        modelType = "zipformer",
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                )
                // 绝对路径加载模型时必须传 null，否则原生层会直接 abort
                val ctor = OnlineRecognizer::class.java.getConstructor(
                    android.content.res.AssetManager::class.java,
                    OnlineRecognizerConfig::class.java,
                )
                recognizer = ctor.newInstance(null, config) as OnlineRecognizer
                stream = recognizer?.createStream()
            } catch (t: Throwable) {
                Log.e(TAG, "Sherpa init failed", t)
                ready = false
                onResult?.invoke(RecognitionResult("", "Sherpa 模型加载失败", "zh"))
                return@execute
            }

            translator.start()
            ready = translator.isReady && stream != null
            Log.d(TAG, "ready=$ready cloud=${translator.isCloud}")
            if (!ready) {
                onResult?.invoke(RecognitionResult("", "翻译模型加载失败", "zh"))
            }
        }
    }

    override fun onAudio(pcm16k: ShortArray, level: Float) {
        if (paused || !ready || pcm16k.isEmpty()) return
        val rec = recognizer ?: return
        val st = stream ?: return

        val samples = FloatArray(pcm16k.size) { pcm16k[it] / 32768.0f }

        try {
            st.acceptWaveform(samples, 16_000)
            while (rec.isReady(st)) {
                rec.decode(st)
            }

            val text = rec.getResult(st).text
            val now = SystemClock.elapsedRealtime()

            if (text.isNotBlank() && segmentStartAt == 0L) {
                segmentStartAt = now
            }

            if (text.isNotBlank() && text != lastPartial && now - lastPartialAt >= PARTIAL_INTERVAL_MS) {
                lastPartial = text
                lastPartialAt = now
                onResult?.invoke(RecognitionResult(text, "", "zh"))
            }

            // 课程讲解经常长时间不停顿；超过 6 秒强制断句，保证英文能持续输出
            val forcedEnd = text.isNotBlank() && segmentStartAt > 0L &&
                now - segmentStartAt >= MAX_SEGMENT_MS

            if (rec.isEndpoint(st) || forcedEnd) {
                val finalText = text.trim()
                rec.reset(st)
                lastPartial = ""
                lastPartialAt = 0L
                segmentStartAt = 0L
                if (finalText.isNotBlank()) {
                    Log.d(TAG, "final: $finalText")
                    translate(finalText)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Sherpa decode failed", t)
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
                translator.translate(text, targetLanguage)
            } catch (t: Throwable) {
                Log.e(TAG, "translate failed", t)
                ""
            }.let { cleanTranslation(it, targetLanguage) }
            val elapsed = SystemClock.elapsedRealtime() - begin
            Log.d(TAG, "translate ${text.length} chars -> ${elapsed}ms: $translated")
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

    private fun cleanTranslation(raw: String, target: String): String {
        var text = raw.trim()
        text = text.replace(
            Regex("(?i)^(sure[,，]?\\s*)?(here is the translation[:：]?\\s*)"),
            "",
        )
        text = text.replace(Regex("(?i)^translation[:：]\\s*"), "")
        text = text.replace(Regex("(?i)^(english|chinese)[:：]\\s*"), "")
        text = text.trim().trim('"', '\'', '“', '”', '\n', ' ')

        // 目标英文时，模型偶尔会把中文原样吐回来；这种情况宁可保留上一条英文
        if (target == "en" && text.isNotEmpty() && cjkRatio(text) > 0.2f) {
            Log.w(TAG, "drop Chinese output: $text")
            return ""
        }
        // 目标中文却输出纯英文，说明没有真正翻译，丢弃
        if (target == "zh" && text.isNotEmpty() &&
            text.none { it.code in 0x4E00..0x9FFF }
        ) {
            Log.w(TAG, "drop non-Chinese output: $text")
            return ""
        }
        return text
    }

    private fun cjkRatio(text: String): Float {
        var total = 0
        var cjk = 0
        for (ch in text) {
            if (ch.isWhitespace() || ch in ".,!?;:'\"-—()[]{}") continue
            total++
            if (ch.code in 0x3400..0x4DBF || ch.code in 0x4E00..0x9FFF) cjk++
        }
        return if (total == 0) 0f else cjk.toFloat() / total
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
        lastPartial = ""
        lastPartialAt = 0L
        segmentStartAt = 0L
        runCatching { stream?.let { recognizer?.reset(it) } }
    }

    override fun stop() {
        paused = true
        ready = false
        asrWorker.execute {
            runCatching { stream?.release() }
            runCatching { recognizer?.release() }
            stream = null
            recognizer = null
        }
        mtWorker.execute {
            translator.close()
        }
        asrWorker.shutdown()
        mtWorker.shutdown()
    }

    private companion object {
        const val TAG = "YingLingSherpa"
        const val PARTIAL_INTERVAL_MS = 250L
        const val MAX_SEGMENT_MS = 3_000L
    }
}
