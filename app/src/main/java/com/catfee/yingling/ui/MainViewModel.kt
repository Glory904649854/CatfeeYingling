package com.catfee.yingling.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.catfee.yingling.core.AppState
import com.catfee.yingling.core.Languages
import com.catfee.yingling.core.Prefs
import com.catfee.yingling.core.SessionStatus
import com.catfee.yingling.data.AppCatalog
import com.catfee.yingling.data.AppEntry
import com.catfee.yingling.engine.ModelStore
import com.catfee.yingling.service.RecognitionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext
    private val prefs = Prefs(context)

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var selected by mutableStateOf(prefs.selectedPackages)
        private set
    var mode by mutableStateOf(prefs.mode)
        private set
    var sourceLang by mutableStateOf(prefs.sourceLang)
        private set
    var targetLang by mutableStateOf(prefs.targetLang)
        private set
    var cloudEnabled by mutableStateOf(prefs.cloudEnabled)
        private set
    var cloudBaseUrl by mutableStateOf(prefs.cloudBaseUrl)
        private set
    var cloudApiKey by mutableStateOf(prefs.cloudApiKey)
        private set
    var cloudModel by mutableStateOf(prefs.cloudModel)
        private set
    var fastReady by mutableStateOf(false)
        private set

    val status = AppState.status
    val direction = AppState.direction
    val sourceText = AppState.sourceText
    val translatedText = AppState.translatedText
    val level = AppState.level
    val boundApps = AppState.boundApps
    val overlayVisible = AppState.overlayVisible
    val engineName = AppState.engineName
    val lastError = AppState.lastError

    init {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { AppCatalog.load(context) }
            apps = list
            loading = false
        }
        viewModelScope.launch {
            refreshReady()
        }
    }

    private fun refreshReady() {
        val source = sourceLang
        val target = targetLang
        val cloud = cloudEnabled && cloudApiKey.isNotBlank()
        viewModelScope.launch {
            fastReady = withContext(Dispatchers.IO) {
                val hasTranslator = cloud || ModelStore.findQwenModel(context) != null
                if (!hasTranslator) return@withContext false
                if (target == "en") {
                    ModelStore.findSherpaModel(context) != null
                } else {
                    when (source) {
                        "ja", "ko", "en" -> ModelStore.findSenseVoiceModel(context) != null
                        "fr", "ru" -> false
                        else -> ModelStore.findSherpaModel(context) != null
                    }
                }
            }
        }
    }

    fun toggleApp(packageName: String) {
        selected = if (packageName in selected) {
            selected - packageName
        } else {
            selected + packageName
        }
        prefs.selectedPackages = selected
        if (isSessionActive()) {
            AppState.boundApps.value = selected.size
            RecognitionService.refresh(context)
        }
    }

    fun clearApps() {
        if (selected.isEmpty()) return
        selected = emptySet()
        prefs.selectedPackages = selected
        if (isSessionActive()) {
            AppState.boundApps.value = 0
            RecognitionService.refresh(context)
        }
    }

    fun selectMode(value: String) {
        if (mode == value) return
        mode = value
        prefs.mode = value
        if (isSessionActive()) RecognitionService.refresh(context)
    }

    fun selectSourceLang(code: String) {
        if (sourceLang == code) return
        sourceLang = code
        prefs.sourceLang = code
        AppState.direction.value = Languages.direction(code, targetLang)
        refreshReady()
        if (isSessionActive()) RecognitionService.refresh(context)
    }

    fun selectTargetLang(code: String) {
        if (targetLang == code) return
        targetLang = code
        prefs.targetLang = code
        AppState.direction.value = Languages.direction(sourceLang, code)
        refreshReady()
        if (isSessionActive()) RecognitionService.refresh(context)
    }

    fun isSessionActive(): Boolean =
        status.value == SessionStatus.RUNNING || status.value == SessionStatus.PAUSED

    fun selectCloudEnabled(value: Boolean) {
        if (cloudEnabled == value) return
        cloudEnabled = value
        prefs.cloudEnabled = value
        refreshReady()
        if (isSessionActive()) RecognitionService.refresh(context)
    }

    fun updateCloudBaseUrl(value: String) {
        cloudBaseUrl = value
        prefs.cloudBaseUrl = value
    }

    fun updateCloudApiKey(value: String) {
        cloudApiKey = value
        prefs.cloudApiKey = value
    }

    fun updateCloudModel(value: String) {
        cloudModel = value
        prefs.cloudModel = value
    }

    /** 编辑完地址/Key/模型后，让运行中的会话立即用新配置重建引擎。 */
    fun applyCloudConfig() {
        refreshReady()
        if (isSessionActive()) RecognitionService.refresh(context)
    }

    fun clearError() {
        AppState.clearError()
    }
}
