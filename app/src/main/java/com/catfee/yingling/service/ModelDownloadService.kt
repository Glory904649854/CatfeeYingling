package com.catfee.yingling.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.catfee.yingling.MainActivity
import com.catfee.yingling.R
import com.catfee.yingling.engine.ModelCatalog
import com.catfee.yingling.engine.ModelDownloadState
import com.catfee.yingling.engine.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 模型下载前台服务：锁屏/切后台也能继续下载，通知栏显示总进度。
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var lastPercent = -1
    private var lastFile = ""
    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try {
                    startForegroundCompat(buildNotification(ModelDownloader.state.value))
                } catch (t: Throwable) {
                    Log.e(TAG, "start foreground failed", t)
                    stopSelf()
                    return START_NOT_STICKY
                }
                ModelDownloader.start(this, scope)
                observe()
            }

            ACTION_CANCEL -> {
                ModelDownloader.cancel()
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            ModelDownloader.state.collectLatest { state ->
                if (state.running) {
                    maybeNotify(state)
                } else {
                    notify(state)
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun maybeNotify(state: ModelDownloadState) {
        val now = System.currentTimeMillis()
        val percent = state.percent
        if (percent != lastPercent || state.currentFile != lastFile || now - lastNotifyAt > 1000L) {
            lastPercent = percent
            lastFile = state.currentFile
            lastNotifyAt = now
            notify(state)
        }
    }

    private fun notify(state: ModelDownloadState) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: ModelDownloadState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            state.completed -> "离线模型已就绪"
            state.error != null -> "模型下载失败"
            state.canceled -> "模型下载已暂停"
            else -> "正在下载离线模型"
        }
        val text = when {
            state.completed -> "共 ${ModelCatalog.humanSize(state.totalBytes)}，已可离线使用"
            state.error != null -> state.error ?: "请打开应用重试"
            state.canceled -> "已保留下载进度，可继续"
            state.currentFile.isNotBlank() ->
                "${state.currentFile} · ${state.percent}%"
            else -> "准备中…"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yingling)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(state.running)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(
                100,
                state.percent,
                state.running && state.totalBytes <= 0L,
            )
            .apply {
                if (state.running) {
                    addAction(0, "取消", cancelIntent)
                }
            }
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "离线模型下载",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "下载语音识别和翻译模型"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun stopForegroundCompat() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "YingLingModelService"
        private const val CHANNEL_ID = "yingling_models"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.catfee.yingling.action.MODEL_DOWNLOAD"
        private const val ACTION_CANCEL = "com.catfee.yingling.action.MODEL_CANCEL"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ModelDownloadService::class.java).setAction(ACTION_START),
                )
            }
        }

        fun cancel(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
                )
            }
        }
    }
}
