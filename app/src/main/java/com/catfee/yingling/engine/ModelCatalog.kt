package com.catfee.yingling.engine

import android.content.Context
import java.io.File

data class ModelFileSpec(
    val name: String,
    val url: String,
    val bytes: Long,
)

data class ModelGroupSpec(
    val id: String,
    val title: String,
    val dir: String,
    val files: List<ModelFileSpec>,
)

data class MissingModelFile(
    val group: ModelGroupSpec,
    val file: ModelFileSpec,
    val target: File,
    val existingBytes: Long,
) {
    val remainingBytes: Long get() = (file.bytes - existingBytes).coerceAtLeast(0L)
}

/**
 * 应用内自动下载的模型清单。全部走 hf-mirror，国内网络可直接访问。
 */
object ModelCatalog {

    private const val SHERPA =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main"
    private const val SENSE =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
    private const val QWEN =
        "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main"

    val groups = listOf(
        ModelGroupSpec(
            id = "sherpa",
            title = "中英流式识别",
            dir = "sherpa-zh-en",
            files = listOf(
                ModelFileSpec(
                    "encoder-epoch-99-avg-1.onnx",
                    "$SHERPA/encoder-epoch-99-avg-1.onnx",
                    330_083_505L,
                ),
                ModelFileSpec(
                    "decoder-epoch-99-avg-1.onnx",
                    "$SHERPA/decoder-epoch-99-avg-1.onnx",
                    13_876_452L,
                ),
                ModelFileSpec(
                    "joiner-epoch-99-avg-1.onnx",
                    "$SHERPA/joiner-epoch-99-avg-1.onnx",
                    12_833_618L,
                ),
                ModelFileSpec("tokens.txt", "$SHERPA/tokens.txt", 56_317L),
            ),
        ),
        ModelGroupSpec(
            id = "sensevoice",
            title = "日/韩/英多语种识别",
            dir = "sensevoice",
            files = listOf(
                ModelFileSpec(
                    "model.int8.onnx",
                    "$SENSE/model.int8.onnx",
                    239_233_841L,
                ),
                ModelFileSpec("tokens.txt", "$SENSE/tokens.txt", 315_894L),
            ),
        ),
        ModelGroupSpec(
            id = "qwen",
            title = "本地 Qwen 翻译",
            dir = "",
            files = listOf(
                ModelFileSpec(
                    "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                    "$QWEN/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                    491_400_032L,
                ),
            ),
        ),
    )

    val totalBytes: Long = groups.sumOf { group -> group.files.sumOf { it.bytes } }

    fun missing(context: Context): List<MissingModelFile> {
        val root = ModelStore.modelsDir(context)
        return groups.flatMap { group ->
            group.files.mapNotNull { spec ->
                val dir = if (group.dir.isBlank()) root else File(root, group.dir)
                val target = File(dir, spec.name)
                val length = if (target.isFile) target.length() else 0L
                if (length == spec.bytes) {
                    null
                } else {
                    MissingModelFile(group, spec, target, length)
                }
            }
        }
    }

    fun neededBytes(context: Context): Long = missing(context).sumOf { it.remainingBytes }

    fun isReady(context: Context): Boolean = missing(context).isEmpty()

    fun humanSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
