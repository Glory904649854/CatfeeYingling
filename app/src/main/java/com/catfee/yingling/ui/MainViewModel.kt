package com.catfee.yingling.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.catfee.yingling.core.AppState
import com.catfee.yingling.core.CloudProvider
import com.catfee.yingling.core.Languages
import com.catfee.yingling.core.Prefs
import com.catfee.yingling.core.SessionStatus
import com.catfee.yingling.data.AppCatalog
import com.catfee.yingling.data.AppEntry
import com.catfee.yingling.engine.ModelCatalog
import com.catfee.yingling.engine.ModelDownloader
import com.catfee.yingling.engine.ModelStore
import com.catfee.yingling.service.ModelDownloadService
import com.catfee.yingling.service.RecognitionService
import com.catfee.yingling.util.NetworkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
    var providers by mutableStateOf<List<CloudProvider>>(emptyList())
        private set
    var activeProviderId by mutableStateOf("")
        private set
    var cloudName by mutableStateOf("")
        private set
    var cloudBaseUrl by mutableStateOf("")
        private set
    var cloudApiKey by mutableStateOf("")
        private set
    var cloudModel by mutableStateOf("")
        private set
    var cloudExtraJson by mutableStateOf("")
        private set
    var subtitleHoldSeconds by mutableStateOf(prefs.subtitleHoldSeconds)
        private set
    var fastReady by mutableStateOf(false)
        private set
    var modelsReady by mutableStateOf(false)
        private set
    var modelMissingBytes by mutableStateOf(0L)
        private set
    var modelMissingCount by mutableStateOf(0)
        private set
    var modelStatusLoaded by mutableStateOf(false)
        private set

    val downloadState = ModelDownloader.state

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
        loadProviders()
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { AppCatalog.load(context) }
            apps = list
            loading = false
        }
        viewModelScope.launch {
            refreshReady()
        }
        refreshModelStatus()
        viewModelScope.launch {
            var wasCompleted = false
            ModelDownloader.state.collect { state ->
                if (state.completed && !wasCompleted) {
                    wasCompleted = true
                    refreshModelStatus()
                    if (isSessionActive()) RecognitionService.refresh(context)
                } else if (!state.completed) {
                    wasCompleted = false
                }
            }
        }
    }

    fun refreshModelStatus() {
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) { ModelCatalog.missing(context) }
            modelsReady = missing.isEmpty()
            modelMissingBytes = missing.sumOf { it.remainingBytes }
            modelMissingCount = missing.size
            modelStatusLoaded = true
            refreshReady()
            if (missing.isNotEmpty()) {
                maybeAutoDownload()
            }
        }
    }

    private fun maybeAutoDownload() {
        val state = ModelDownloader.state.value
        if (state.running || state.canceled || state.error != null) return
        if (NetworkStatus.isUnmetered(context)) {
            ModelDownloadService.start(context)
        }
    }

    fun startModelDownload() {
        ModelDownloadService.start(context)
    }

    fun cancelModelDownload() {
        ModelDownloadService.cancel(context)
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
        saveDraft()
        cloudEnabled = value
        prefs.cloudEnabled = value
        refreshReady()
        if (isSessionActive()) RecognitionService.refresh(context)
    }

    fun updateCloudName(value: String) {
        cloudName = value
    }

    fun updateCloudBaseUrl(value: String) {
        cloudBaseUrl = value
    }

    fun updateCloudApiKey(value: String) {
        cloudApiKey = value
    }

    fun updateCloudModel(value: String) {
        cloudModel = value
    }

    fun updateCloudExtraJson(value: String) {
        cloudExtraJson = value
    }

    fun selectProvider(id: String) {
        if (id == activeProviderId) return
        saveDraft()
        activateProvider(id)
        refreshReady()
        if (isSessionActive()) RecognitionService.refresh(context)
    }

    fun addProvider() {
        saveDraft()
        val provider = CloudProvider(
            id = UUID.randomUUID().toString(),
            name = "供应商 ${providers.size + 1}",
            baseUrl = "",
            apiKey = "",
            model = "",
        )
        providers = providers + provider
        prefs.saveCloudProviders(providers)
        activateProvider(provider.id)
        refreshReady()
        if (isSessionActive()) RecognitionService.refresh(context)
    }

    fun deleteActiveProvider() {
        if (providers.size <= 1) return
        val remaining = providers.filterNot { it.id == activeProviderId }
        providers = remaining
        prefs.saveCloudProviders(remaining)
        activateProvider(remaining.first().id)
        refreshReady()
        if (isSessionActive()) RecognitionService.refresh(context)
    }

    private fun loadProviders() {
        val loaded = prefs.cloudProviders()
        providers = loaded
        val active = prefs.activeProviderId
            .takeIf { candidate -> loaded.any { it.id == candidate } }
            ?: loaded.first().id
        activateProvider(active, persist = false)
    }

    private fun activateProvider(id: String, persist: Boolean = true) {
        val provider = providers.firstOrNull { it.id == id } ?: return
        activeProviderId = provider.id
        cloudName = provider.name
        cloudBaseUrl = provider.baseUrl
        cloudApiKey = provider.apiKey
        cloudModel = provider.model
        cloudExtraJson = provider.extraJson
        if (persist) prefs.activeProviderId = provider.id
    }

    private fun saveDraft() {
        if (providers.isEmpty()) return
        val updated = providers.map { provider ->
            if (provider.id == activeProviderId) {
                provider.copy(
                    name = cloudName.ifBlank { "未命名供应商" },
                    baseUrl = cloudBaseUrl.trim(),
                    apiKey = cloudApiKey.trim(),
                    model = cloudModel.trim(),
                    extraJson = cloudExtraJson.trim(),
                )
            } else {
                provider
            }
        }
        providers = updated
        prefs.saveCloudProviders(updated)
        prefs.activeProviderId = activeProviderId
    }

    fun selectSubtitleHold(seconds: Int) {
        val next = seconds.coerceIn(4, 60)
        if (subtitleHoldSeconds == next) return
        subtitleHoldSeconds = next
        prefs.subtitleHoldSeconds = next
        if (isSessionActive()) RecognitionService.updateSubtitleHold(context)
    }

    /** 编辑完地址/Key/模型后，让运行中的会话立即用新配置重建引擎。 */
    fun applyCloudConfig() {
        saveDraft()
        refreshReady()
        if (isSessionActive()) RecognitionService.refresh(context)
    }

    fun clearError() {
        AppState.clearError()
    }
}
