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
    val level = MutableStateFlow(0f)
    val engineName = MutableStateFlow("Demo 引擎")
    val boundApps = MutableStateFlow(0)
    val overlayVisible = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    fun clearError() {
        lastError.value = null
    }
}
