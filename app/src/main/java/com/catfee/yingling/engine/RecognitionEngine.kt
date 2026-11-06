package com.catfee.yingling.engine

data class RecognitionResult(
    val sourceText: String,
    val translatedText: String,
    val langCode: String,
)

/**
 * 识别 + 翻译引擎抽象。
 *
 * 当前内置 [DemoRecognitionEngine]，用于在没有模型的情况下跑通
 * “采集 -> VAD -> 识别 -> 翻译 -> 字幕”整条链路。
 * 接入 sherpa-onnx / Whisper / Qwen 时实现本接口即可，Service 与 UI 不用改。
 */
interface RecognitionEngine {
    val name: String

    var onResult: ((RecognitionResult) -> Unit)?

    fun start()

    fun onAudio(pcm16k: ShortArray, level: Float)

    fun pause()

    fun resume()

    fun stop()
}
