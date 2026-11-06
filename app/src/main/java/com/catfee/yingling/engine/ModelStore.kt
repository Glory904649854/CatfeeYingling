package com.catfee.yingling.engine

import android.content.Context
import java.io.File

/** 在应用私有目录里查找已经放入的 whisper.cpp 模型。 */
object ModelStore {

    private val MODEL_NAMES = listOf(
        "ggml-small-q5_1.bin",
        "ggml-small.bin",
        "ggml-base-q5_1.bin",
        "ggml-base.bin",
        "ggml-medium-q5_0.bin",
    )

    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

    fun findModel(context: Context): File? {
        val dir = modelsDir(context)
        return MODEL_NAMES
            .asSequence()
            .map { File(dir, it) }
            .firstOrNull { it.isFile && it.length() > 10_000_000L }
    }
}
