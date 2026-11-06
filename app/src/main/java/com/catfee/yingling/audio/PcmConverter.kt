package com.catfee.yingling.audio

/**
 * 48kHz 单声道 -> 16kHz 单声道。
 * 3:1 整数抽取，用 3 点均值做最简单的抗混叠，足够喂给 VAD / ASR。
 */
internal class PcmConverter(
    inputRate: Int,
    outputRate: Int,
) {
    private val factor = (inputRate / outputRate).coerceAtLeast(1)
    private var pending = ShortArray(0)

    fun convert(input: ShortArray, length: Int): ShortArray {
        if (length <= 0) return ShortArray(0)
        if (factor == 1) return input.copyOf(length)

        val merged = ShortArray(pending.size + length)
        pending.copyInto(merged)
        input.copyInto(merged, pending.size, 0, length)

        val outCount = merged.size / factor
        if (outCount == 0) {
            pending = merged
            return ShortArray(0)
        }

        val out = ShortArray(outCount)
        for (i in 0 until outCount) {
            var acc = 0
            val base = i * factor
            for (j in 0 until factor) {
                acc += merged[base + j]
            }
            out[i] = (acc / factor).toShort()
        }

        val consumed = outCount * factor
        pending = if (consumed < merged.size) {
            merged.copyOfRange(consumed, merged.size)
        } else {
            ShortArray(0)
        }
        return out
    }

    fun reset() {
        pending = ShortArray(0)
    }
}
