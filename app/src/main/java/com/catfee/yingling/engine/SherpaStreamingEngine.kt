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
    private val qwenModelPath: String,
) : RecognitionEngine {

    override val name: String = "Sherpa + Qwen"
    override var onResult: ((RecognitionResult) -> Unit)? = null

    private val asrWorker = Executors.newSingleThreadExecutor { Thread(it, "yingling-sherpa") }
    private val mtWorker = Executors.newSingleThreadExecutor { Thread(it, "yingling-qwen") }
    private val mtBusy = AtomicBoolean(false)

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var qwenHandle = 0L

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

            qwenHandle = try {
                QwenBridge.nativeInit(qwenModelPath)
            } catch (t: Throwable) {
                Log.e(TAG, "Qwen init failed", t)
                0L
            }

            ready = qwenHandle != 0L && stream != null
            Log.d(TAG, "ready=$ready qwen=$qwenHandle")
            if (!ready) {
                onResult?.invoke(RecognitionResult("", "Qwen 模型加载失败", "zh"))
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
            val english = try {
                QwenBridge.nativeTranslate(qwenHandle, text)
            } catch (t: Throwable) {
                Log.e(TAG, "translate failed", t)
                ""
            }.let(::cleanTranslation)
            val elapsed = SystemClock.elapsedRealtime() - begin
            Log.d(TAG, "translate ${text.length} chars -> ${elapsed}ms: $english")
            if (english.isNotBlank()) {
                onResult?.invoke(RecognitionResult(text, english, "en"))
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
        return text.trim().trim('"', '\'', '“', '”', '\n', ' ')
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
            if (qwenHandle != 0L) {
                runCatching { QwenBridge.nativeRelease(qwenHandle) }
                qwenHandle = 0L
            }
        }
        asrWorker.shutdown()
        mtWorker.shutdown()
    }

    private companion object {
        const val TAG = "YingLingSherpa"
        const val PARTIAL_INTERVAL_MS = 250L
        const val MAX_SEGMENT_MS = 4_000L
    }
}
