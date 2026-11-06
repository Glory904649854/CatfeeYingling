package com.catfee.yingling.engine

private data class DemoLine(
    val lang: String,
    val source: String,
    val translation: String,
)

/** Demo 用的六语种台词，轮播展示识别方向与字幕效果。 */
object DemoScript {

    private val lines = listOf(
        DemoLine(
            "en",
            "Welcome back. Today we're looking at how on-device AI is changing live translation.",
            "欢迎回来。今天我们来看看端侧 AI 正在如何改变实时翻译。",
        ),
        DemoLine(
            "ja",
            "今日はいい天気ですね。ちょっと散歩しませんか。",
            "今天天气真不错呢，要不要去散散步？",
        ),
        DemoLine(
            "ko",
            "오늘 회의는 세 시에 시작합니다. 준비되셨나요?",
            "今天的会议三点开始，你准备好了吗？",
        ),
        DemoLine(
            "fr",
            "Ce film est vraiment magnifique. Je voudrais le regarder encore une fois.",
            "这部电影真的很美，我想再看一遍。",
        ),
        DemoLine(
            "ru",
            "Сегодня мы поговорим о новых технологиях перевода.",
            "今天我们来谈谈新的翻译技术。",
        ),
        DemoLine(
            "zh",
            "这次更新优化了悬浮字幕的延迟和功耗。",
            "这次更新优化了悬浮字幕的延迟和功耗。",
        ),
    )

    fun next(sourceLang: String, index: Int): RecognitionResult {
        val pool = if (sourceLang == "auto") {
            lines
        } else {
            lines.filter { it.lang == sourceLang }.ifEmpty { lines }
        }
        val line = pool[index % pool.size]
        return RecognitionResult(line.source, line.translation, line.lang)
    }
}
