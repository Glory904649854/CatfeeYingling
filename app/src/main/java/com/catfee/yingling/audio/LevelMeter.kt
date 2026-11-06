package com.catfee.yingling.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** 把 RMS 映射成 0~1 的视觉电平（-60dBFS ~ 0dBFS）。 */
internal object LevelMeter {

    fun fromMeanSquare(meanSquare: Double): Float {
        val rms = sqrt(meanSquare.coerceAtLeast(1e-14))
        val db = 20.0 * log10(rms)
        return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }

    fun measure(mono: ShortArray, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            val v = mono[i] / 32768.0
            sum += v * v
        }
        return fromMeanSquare(sum / length)
    }
}
