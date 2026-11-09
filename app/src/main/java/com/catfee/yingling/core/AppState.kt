package com.catfee.yingling.core

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 服务与 UI 之间的单向状态总线。
 * 服务写入，Compose 与悬浮窗读取；不持有 Context，避免泄漏。
 */
object AppState {
    val status = MutableStateFlow(SessionStatus.IDLE)
    val direction = MutableStateFlow(Languages.direction("auto", "zh"))
    val sourceText = MutableStateFlow("")
    val translatedText = MutableStateFlow("")
    /** 最近几句的原文+译文，悬浮窗用来保留上下文。 */
    val history = MutableStateFlow<List<SubtitleLine>>(emptyList())
    /** 当前正在识别、还没翻译完的中文/原文。 */
    val liveSource = MutableStateFlow("")
    val level = MutableStateFlow(0f)
    val engineName = MutableStateFlow("Demo 引擎")
    val boundApps = MutableStateFlow(0)
    val overlayVisible = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    fun clearError() {
        lastError.value = null
    }
}
