package com.catfee.yingling.core

data class LanguageOption(
    val code: String,
    val label: String,
    val short: String,
)

/**
 * 应灵支持的语种。
 * 源语言可自动识别或手动锁定；目标语言支持中文与英语。
 * 英语目标使用 Whisper 的 translate 任务直接翻译，模型未就绪时回退到中文。
 */
object Languages {
    val sources: List<LanguageOption> = listOf(
        LanguageOption("auto", "自动检测", "自动"),
        LanguageOption("zh", "中文", "中"),
        LanguageOption("en", "英语", "英"),
        LanguageOption("ja", "日语", "日"),
        LanguageOption("ko", "韩语", "韩"),
        LanguageOption("fr", "法语", "法"),
        LanguageOption("ru", "俄语", "俄"),
    )

    val targets: List<LanguageOption> = listOf(
        LanguageOption("zh", "中文 · 简体", "中"),
        LanguageOption("en", "英语", "英"),
    )

    val target = targets[0]

    fun label(code: String): String =
        sources.firstOrNull { it.code == code }?.label ?: "自动检测"

    fun short(code: String): String =
        sources.firstOrNull { it.code == code }?.short ?: "自动"

    fun targetLabel(code: String): String =
        targets.firstOrNull { it.code == code }?.label ?: "中文 · 简体"

    fun targetShort(code: String): String =
        targets.firstOrNull { it.code == code }?.short ?: "中"

    /** 悬浮窗上的紧凑方向，例如 "中 → 英"、"自动 → 中"。 */
    fun direction(sourceCode: String, targetCode: String = "zh"): String =
        "${short(sourceCode)} → ${targetShort(targetCode)}"
}
