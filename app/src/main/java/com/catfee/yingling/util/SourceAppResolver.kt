package com.catfee.yingling.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Process

data class SourceApp(
    val packageName: String,
    val label: String,
)

/** 尽力解析当前正在播放音频的 App；解析不到时由调用方回退到监听范围标签。 */
object SourceAppResolver {

    fun resolve(context: Context, uids: List<Int>): SourceApp? {
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        val configs = runCatching { manager.activePlaybackConfigurations }.getOrNull()
            ?: return null

        val candidates = configs.asSequence()
            .filter { config -> isActive(config) }
            .mapNotNull { config ->
                val uid = clientUid(config) ?: return@mapNotNull null
                if (uid < 0 || uid == Process.myUid()) return@mapNotNull null
                if (uids.isNotEmpty() && uid !in uids) return@mapNotNull null
                uid to usageScore(config.audioAttributes?.usage)
            }
            .sortedByDescending { it.second }
            .toList()

        for ((uid, _) in candidates) {
            val pkg = packageForUid(context, uid) ?: continue
            if (pkg == context.packageName) continue
            return SourceApp(pkg, labelFor(context, pkg))
        }
        return resolveFromUsage(context)
    }

    /** 用户授予“使用情况访问”后，用最近的非自身前台事件补足来源 App。 */
    private fun resolveFromUsage(context: Context): SourceApp? {
        if (!usageAccessGranted(context)) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val events = runCatching {
            manager.queryEvents(end - 60_000L, end)
        }.getOrNull() ?: return null

        val ignored = ignoredPackages(context)
        var candidate: String? = null
        var candidateAt = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = event.packageName ?: continue
            if (pkg in ignored) continue
            if (event.timeStamp >= candidateAt) {
                candidate = pkg
                candidateAt = event.timeStamp
            }
        }

        val pkg = candidate ?: return null
        return SourceApp(pkg, labelFor(context, pkg))
    }

    fun usageAccessGranted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun usageAccessSettingsIntent(): Intent = Intent(
        android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS,
    )

    private fun ignoredPackages(context: Context): Set<String> {
        val result = mutableSetOf(
            context.packageName,
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.miui.securitycenter",
            "com.vivo.permissionmanager",
        )
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
                ?.let { result += it }
        }
        return result
    }

    fun labelFor(context: Context, packageName: String): String {
        val pm = context.packageManager
        val appInfo = runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }.getOrNull() ?: return packageName
        return runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrDefault(packageName)
    }

    private fun packageForUid(context: Context, uid: Int): String? {
        if (uid < 0) return null
        return runCatching {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull()
    }

    /** 公开 SDK 没暴露 UID / active，仅做兼容读取；失败就回退。 */
    private fun clientUid(config: AudioPlaybackConfiguration): Int? =
        runCatching {
            config.javaClass.getMethod("getClientUid").invoke(config) as? Int
        }.getOrNull()

    private fun isActive(config: AudioPlaybackConfiguration): Boolean {
        val direct = runCatching {
            config.javaClass.getMethod("isActive").invoke(config) as? Boolean
        }.getOrNull()
        if (direct != null) return direct

        val state = runCatching {
            config.javaClass.getMethod("getPlayerState").invoke(config) as? Int
        }.getOrNull()
        return state == PLAYER_STATE_STARTED
    }

    private fun usageScore(usage: Int?): Int = when (usage) {
        AudioAttributes.USAGE_MEDIA -> 3
        AudioAttributes.USAGE_GAME -> 2
        AudioAttributes.USAGE_UNKNOWN -> 1
        else -> 0
    }

    private const val PLAYER_STATE_STARTED = 2
}
