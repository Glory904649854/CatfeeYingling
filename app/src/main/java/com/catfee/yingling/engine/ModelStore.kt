package com.catfee.yingling.engine

import android.content.Context
import java.io.File

/** 在应用私有目录里查找已放入的流式 ASR / 翻译模型。 */
object ModelStore {

    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

    /** sherpa-onnx 流式 zipformer 中英双语模型。 */
    fun findSherpaModel(context: Context): SherpaModel? {
        val dir = File(modelsDir(context), "sherpa-zh-en")
        val files = dir.listFiles() ?: return null
        fun pick(prefix: String): File? =
            files.firstOrNull { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".onnx", true) }
        val encoder = pick("encoder") ?: return null
        val decoder = pick("decoder") ?: return null
        val joiner = pick("joiner") ?: return null
        val tokens = files.firstOrNull { it.isFile && it.name == "tokens.txt" } ?: return null
        return SherpaModel(encoder, decoder, joiner, tokens)
    }

    /** Qwen GGUF 文本翻译模型。 */
    fun findQwenModel(context: Context): File? =
        modelsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) && it.length() > 50_000_000L }
            ?.maxByOrNull { it.length() }
}
