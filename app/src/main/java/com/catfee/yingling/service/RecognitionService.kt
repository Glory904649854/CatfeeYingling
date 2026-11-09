package com.catfee.yingling.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.catfee.yingling.MainActivity
import com.catfee.yingling.R
import com.catfee.yingling.audio.AudioCaptureEngine
import com.catfee.yingling.core.AppState
import com.catfee.yingling.core.Languages
import com.catfee.yingling.core.Prefs
import com.catfee.yingling.core.SessionStatus
import com.catfee.yingling.core.SubtitleLine
import com.catfee.yingling.engine.CloudLlmConfig
import com.catfee.yingling.engine.DemoRecognitionEngine
import com.catfee.yingling.engine.ModelStore
import com.catfee.yingling.engine.SenseVoiceEngine
import com.catfee.yingling.engine.SherpaStreamingEngine
import com.catfee.yingling.engine.RecognitionEngine
import com.catfee.yingling.engine.RecognitionResult
import com.catfee.yingling.engine.Translator

/**
 * 监听会话服务：
 * 前台通知 + MediaProjection（仅用于解锁播放采集）+ AudioPlaybackCapture + 识别引擎 + 悬浮窗。
 *
 * Android 规定 AudioPlaybackCapture 必须绑定 MediaProjection，因此启动时会弹一次
 * 系统的“录制或投射屏幕”授权；应灵不会创建虚拟显示，也不会录屏。
 */
class RecognitionService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }

    private var capture: AudioCaptureEngine? = null
    private var engine: RecognitionEngine? = null
    private var overlay: OverlayController? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var running = false
    private var paused = false

    private val overlayCallbacks = object : OverlayController.Callbacks {
        override fun onTogglePause() {
            setPaused(!paused)
        }

        override fun onStop() {
            stopSession()
        }

        override fun onHide() {
            overlay?.hide()
            AppState.overlayVisible.value = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED),
                data = intent.projectionData(),
            )
            ACTION_PAUSE -> if (running) setPaused(true)
            ACTION_RESUME -> if (running) setPaused(false)
            ACTION_STOP -> stopSession()
            ACTION_TOGGLE_OVERLAY -> toggleOverlay()
            ACTION_UPDATE_SUBTITLE -> {
                overlay?.setHoldMs(prefs.subtitleHoldSeconds * 1000L)
            }
            ACTION_REFRESH -> if (running) refreshConfiguration() else stopSelf()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------- session

    private fun startSession(resultCode: Int, data: Intent?) {
        if (running) {
            if (AppState.status.value == SessionStatus.ERROR && data != null) {
                capture?.stop()
                capture = null
                engine?.stop()
                engine = null
                releaseProjection()

                val projection = obtainProjection(resultCode, data)
                if (projection == null) {
                    AppState.lastError.value = "系统音频授权无效，请重试"
                    return
                }
                mediaProjection = projection
                registerProjectionCallback(projection)
                engine = buildEngine().also { it.start() }
                AppState.status.value = SessionStatus.STARTING

                val ok = startCapture(resolveUids())
                AppState.status.value = if (ok) {
                    SessionStatus.RUNNING
                } else {
                    AppState.lastError.value = getString(R.string.error_capture_failed)
                    SessionStatus.ERROR
                }
                overlay?.setStatus(AppState.status.value)
                updateNotification()
            }
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppState.status.value = SessionStatus.ERROR
            AppState.lastError.value = getString(R.string.error_no_mic)
            stopSelf()
            return
        }

        running = true
        paused = false
        AppState.lastError.value = null
        AppState.status.value = SessionStatus.STARTING
        AppState.direction.value = Languages.direction(prefs.sourceLang, prefs.targetLang)
        AppState.history.value = emptyList()
        AppState.liveSource.value = ""
        refreshScopeLabel()

        // Android 14+：mediaProjection 类型的前台服务必须先启动，再兑换 MediaProjection
        try {
            startForegroundCompat(buildNotification())
        } catch (_: Exception) {
            running = false
            AppState.status.value = SessionStatus.ERROR
            AppState.lastError.value = "无法启动前台服务，请从应用内重新启动"
            stopSelf()
            return
        }

        val projection = obtainProjection(resultCode, data)
        if (projection == null) {
            running = false
            AppState.status.value = SessionStatus.ERROR
            AppState.lastError.value = "缺少系统音频授权，请重新启动并允许「录制或投射屏幕」"
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        mediaProjection = projection
        registerProjectionCallback(projection)

        engine = buildEngine().also { it.start() }
        AppState.engineName.value = engine?.name ?: "Demo 引擎"
        ensureOverlay()

        val ok = startCapture(resolveUids())
        if (ok) {
            AppState.status.value = SessionStatus.RUNNING
            overlay?.setStatus(SessionStatus.RUNNING)
        } else {
            AppState.status.value = SessionStatus.ERROR
            AppState.lastError.value = getString(R.string.error_capture_failed)
            overlay?.setStatus(SessionStatus.ERROR)
        }
    }

    private fun buildEngine(): RecognitionEngine {
        val qwen = ModelStore.findQwenModel(this)
        val provider = prefs.activeProvider()
        val cloud = if (prefs.cloudEnabled && provider.apiKey.isNotBlank()) {
            CloudLlmConfig(
                baseUrl = provider.baseUrl,
                apiKey = provider.apiKey,
                model = provider.model,
                extraJson = provider.extraJson,
            )
        } else {
            null
        }
        val translator = Translator(cloud, qwen?.absolutePath)

        val engine = if (cloud == null && qwen == null) {
            AppState.lastError.value = "未找到 Qwen 翻译模型，且未开启云端 LLM"
            DemoRecognitionEngine(prefs.sourceLang)
        } else {
            when {
                prefs.targetLang == "en" -> {
                    val sherpa = ModelStore.findSherpaModel(this)
                    if (sherpa != null) {
                        SherpaStreamingEngine(sherpa, translator, "en")
                    } else {
                        AppState.lastError.value = "未找到 sherpa-zh-en 模型"
                        DemoRecognitionEngine(prefs.sourceLang)
                    }
                }

                prefs.sourceLang == "ja" || prefs.sourceLang == "ko" ||
                    prefs.sourceLang == "en" -> {
                    val sense = ModelStore.findSenseVoiceModel(this)
                    if (sense != null) {
                        SenseVoiceEngine(sense, translator, prefs.sourceLang, "zh")
                    } else {
                        AppState.lastError.value = "未找到 SenseVoice 模型"
                        DemoRecognitionEngine(prefs.sourceLang)
                    }
                }

                prefs.sourceLang == "fr" || prefs.sourceLang == "ru" -> {
                    AppState.lastError.value = "法语/俄语语音识别暂未接入"
                    DemoRecognitionEngine(prefs.sourceLang)
                }

                else -> {
                    val sherpa = ModelStore.findSherpaModel(this)
                    if (sherpa != null) {
                        SherpaStreamingEngine(sherpa, translator, "zh")
                    } else {
                        AppState.lastError.value = "未找到 sherpa-zh-en 模型"
                        DemoRecognitionEngine(prefs.sourceLang)
                    }
                }
            }
        }
        engine.onResult = ::publishResult
        return engine
    }

    private fun publishResult(result: RecognitionResult) {
        if (result.translatedText.isNotBlank() && result.sourceText.isNotBlank()) {
            val line = SubtitleLine(result.sourceText, result.translatedText)
            AppState.history.value = (AppState.history.value + line).takeLast(MAX_HISTORY)
            AppState.liveSource.value = ""
            AppState.sourceText.value = result.sourceText
            AppState.translatedText.value = result.translatedText
        } else if (result.sourceText.isNotBlank()) {
            AppState.liveSource.value = result.sourceText
            AppState.sourceText.value = result.sourceText
        }
        main.post {
            overlay?.setTranscript(AppState.history.value, AppState.liveSource.value)
        }
    }

    private fun setPaused(value: Boolean) {
        if (!running || paused == value) return
        paused = value

        if (value) {
            capture?.stop()
            capture = null
            AppState.level.value = 0f
            engine?.pause()
            AppState.status.value = SessionStatus.PAUSED
            overlay?.setPaused(true)
        } else {
            engine?.resume()
            val ok = startCapture(resolveUids())
            if (ok) {
                AppState.status.value = SessionStatus.RUNNING
                overlay?.setPaused(false)
            } else {
                AppState.status.value = SessionStatus.ERROR
                AppState.lastError.value = getString(R.string.error_capture_failed)
                overlay?.setStatus(SessionStatus.ERROR)
            }
        }
        updateNotification()
    }

    private fun stopSession() {
        running = false
        paused = false
        capture?.stop()
        capture = null
        engine?.stop()
        engine = null
        overlay?.hide()
        overlay = null
        releaseProjection()

        AppState.status.value = SessionStatus.IDLE
        AppState.sourceText.value = ""
        AppState.translatedText.value = ""
        AppState.history.value = emptyList()
        AppState.liveSource.value = ""
        AppState.level.value = 0f
        AppState.boundApps.value = 0
        AppState.overlayVisible.value = false

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshConfiguration() {
        if (!running) return
        AppState.direction.value = Languages.direction(prefs.sourceLang, prefs.targetLang)
        refreshScopeLabel()
        overlay?.setDirection(AppState.direction.value)

        engine?.stop()
        engine = buildEngine().also { it.start() }
        AppState.engineName.value = engine?.name ?: "Demo 引擎"
        if (paused) engine?.pause()

        if (!paused) {
            val ok = startCapture(resolveUids())
            if (ok) {
                if (AppState.status.value != SessionStatus.RUNNING) {
                    AppState.status.value = SessionStatus.RUNNING
                }
            } else {
                AppState.status.value = SessionStatus.ERROR
                AppState.lastError.value = getString(R.string.error_capture_failed)
                overlay?.setStatus(SessionStatus.ERROR)
            }
        }
        updateNotification()
    }

    // -------------------------------------------------------- media projection

    private fun obtainProjection(resultCode: Int, data: Intent?): MediaProjection? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return null
        return runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
    }

    private fun registerProjectionCallback(projection: MediaProjection) {
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    if (running) {
                        AppState.lastError.value = "系统已结束音频授权，监听已停止"
                        stopSession()
                    }
                }
            }
        }
        projection.registerCallback(callback, main)
        projectionCallback = callback
    }

    private fun releaseProjection() {
        val projection = mediaProjection ?: return
        projectionCallback?.let { callback ->
            runCatching { projection.unregisterCallback(callback) }
        }
        projectionCallback = null
        mediaProjection = null
        runCatching { projection.stop() }
    }

    // --------------------------------------------------------------- audio

    private fun startCapture(uids: List<Int>): Boolean {
        capture?.stop()
        val projection = mediaProjection ?: return false
        val cap = AudioCaptureEngine(
            mediaProjection = projection,
            uids = uids,
            onLevel = { level ->
                AppState.level.value = level
            },
            onPcm16k = { pcm, level ->
                if (!paused) engine?.onAudio(pcm, level)
            },
        )
        val ok = cap.start()
        capture = if (ok) cap else null
        if (!ok) cap.stop()
        return ok
    }

    private fun resolveUids(): List<Int> {
        if (prefs.mode == Prefs.MODE_ALL) return emptyList()
        return prefs.selectedPackages.mapNotNull { pkg ->
            runCatching { packageManager.getPackageUid(pkg, 0) }.getOrNull()
        }.distinct()
    }

    private fun refreshScopeLabel() {
        val count = if (prefs.mode == Prefs.MODE_ALL) 0 else resolveUids().size
        AppState.boundApps.value = count
        overlay?.setScope(scopeLabel())
    }

    private fun scopeLabel(): String {
        val count = AppState.boundApps.value
        return if (prefs.mode == Prefs.MODE_ALL || count == 0) {
            getString(R.string.notif_scope_all)
        } else {
            getString(R.string.notif_scope_apps, count)
        }
    }

    // ------------------------------------------------------------- overlay

    private fun ensureOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            AppState.overlayVisible.value = false
            AppState.lastError.value = getString(R.string.error_no_overlay)
            return
        }
        val controller = overlay ?: OverlayController(this, overlayCallbacks).also { overlay = it }
        val shown = controller.show(AppState.direction.value, scopeLabel())
        AppState.overlayVisible.value = shown
        controller.setStatus(AppState.status.value)
        controller.setTranscript(AppState.history.value, AppState.liveSource.value)
    }

    private fun toggleOverlay() {
        if (!running) return
        if (overlay?.isShowing == true) {
            overlay?.hide()
            AppState.overlayVisible.value = false
        } else {
            ensureOverlay()
        }
    }

    // -------------------------------------------------------- notification

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = getString(
            if (paused) R.string.notif_action_resume else R.string.notif_action_pause,
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecognitionService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecognitionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yingling)
            .setContentTitle(
                getString(
                    if (paused) R.string.notif_paused_title else R.string.notif_running_title,
                ),
            )
            .setContentText("${AppState.direction.value} · ${scopeLabel()}")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, toggleLabel, toggleIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        paused = false
        capture?.stop()
        capture = null
        engine?.stop()
        engine = null
        overlay?.hide()
        overlay = null
        releaseProjection()
        AppState.status.value = SessionStatus.IDLE
        AppState.overlayVisible.value = false
    }

    private fun Intent.projectionData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_RESULT_DATA)
        }

    companion object {
        const val ACTION_START = "com.catfee.yingling.action.START"
        const val ACTION_PAUSE = "com.catfee.yingling.action.PAUSE"
        const val ACTION_RESUME = "com.catfee.yingling.action.RESUME"
        const val ACTION_STOP = "com.catfee.yingling.action.STOP"
        const val ACTION_REFRESH = "com.catfee.yingling.action.REFRESH"
        const val ACTION_TOGGLE_OVERLAY = "com.catfee.yingling.action.TOGGLE_OVERLAY"
        const val ACTION_UPDATE_SUBTITLE = "com.catfee.yingling.action.UPDATE_SUBTITLE"

        private const val CHANNEL_ID = "yingling_session"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_HISTORY = 3
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"

        /**
         * 由 Activity 在拿到 MediaProjection 授权结果后调用。
         * Android 只允许通过这个授权解锁其他应用的音频采集。
         */
        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecognitionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)

        fun resume(context: Context) = send(context, ACTION_RESUME)

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun refresh(context: Context) = send(context, ACTION_REFRESH)

        fun toggleOverlay(context: Context) = send(context, ACTION_TOGGLE_OVERLAY)

        fun updateSubtitleHold(context: Context) = send(context, ACTION_UPDATE_SUBTITLE)

        private fun send(context: Context, action: String) {
            runCatching {
                context.startService(
                    Intent(context, RecognitionService::class.java).setAction(action),
                )
            }
        }
    }
}
