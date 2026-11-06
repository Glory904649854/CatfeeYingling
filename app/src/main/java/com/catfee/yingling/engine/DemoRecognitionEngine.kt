package com.catfee.yingling.engine

import android.os.SystemClock

/**
 * 演示引擎：不依赖任何模型，根据音量判断“说话/停顿”，
 * 在停顿后吐出一句预置台词，用来验证整条链路和 UI。
 *
 * 真正接入模型后，把本类替换为 SherpaRecognitionEngine / WhisperEngine 即可。
 */
class DemoRecognitionEngine(
    private val sourceLang: String,
) : RecognitionEngine {

    override val name: String = "Demo 引擎"
    override var onResult: ((RecognitionResult) -> Unit)? = null

    private var speechMs = 0
    private var silenceMs = 0
    private var speaking = false
    private var index = 0
    private var lastEmitAt = SystemClock.elapsedRealtime()
    private var paused = false

    override fun start() {
        speechMs = 0
        silenceMs = 0
        speaking = false
        lastEmitAt = SystemClock.elapsedRealtime()
    }

    override fun onAudio(pcm16k: ShortArray, level: Float) {
        if (paused) return

        // 16kHz：16 个采样约等于 1ms
        val chunkMs = pcm16k.size / 16
        if (level > LEVEL_SPEECH) {
            speaking = true
            speechMs += chunkMs
            silenceMs = 0
        } else if (speaking) {
            silenceMs += chunkMs
        }

        val now = SystemClock.elapsedRealtime()
        val sentenceEnded = speaking && silenceMs >= SILENCE_MS && speechMs >= MIN_SPEECH_MS
        val longSpeech = speaking && now - lastEmitAt >= MAX_SPEECH_MS
        if (sentenceEnded || longSpeech) {
            emit(now)
        }
    }

    private fun emit(now: Long) {
        val line = DemoScript.next(sourceLang, index++)
        onResult?.invoke(line)
        lastEmitAt = now
        speechMs = 0
        silenceMs = 0
        speaking = false
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
        lastEmitAt = SystemClock.elapsedRealtime()
    }

    override fun stop() {
        paused = true
    }

    private companion object {
        const val LEVEL_SPEECH = 0.24f
        const val SILENCE_MS = 520
        const val MIN_SPEECH_MS = 700
        const val MAX_SPEECH_MS = 6_500
    }
}
