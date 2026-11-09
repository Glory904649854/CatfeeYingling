package com.catfee.yingling.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 轻量偏好存储：监听范围、源语言、悬浮窗开关。 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("yingling_prefs", Context.MODE_PRIVATE)

    var selectedPackages: Set<String>
        get() = sp.getStringSet(KEY_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            sp.edit().putStringSet(KEY_APPS, value).apply()
        }

    /** [MODE_APPS] 只监听勾选的应用；[MODE_ALL] 按媒体类型监听全部可采集应用。 */
    var mode: String
        get() = sp.getString(KEY_MODE, MODE_APPS) ?: MODE_APPS
        set(value) {
            sp.edit().putString(KEY_MODE, value).apply()
        }

    var sourceLang: String
        get() = sp.getString(KEY_LANG, "auto") ?: "auto"
        set(value) {
            sp.edit().putString(KEY_LANG, value).apply()
        }

    /** 目标语言：zh（默认）或 en（Whisper translate）。 */
    var targetLang: String
        get() = sp.getString(KEY_TARGET_LANG, "zh") ?: "zh"
        set(value) {
            sp.edit().putString(KEY_TARGET_LANG, value).apply()
        }

    var cloudEnabled: Boolean
        get() = sp.getBoolean(KEY_CLOUD_ENABLED, false)
        set(value) {
            sp.edit().putBoolean(KEY_CLOUD_ENABLED, value).apply()
        }

    var cloudBaseUrl: String
        get() = sp.getString(KEY_CLOUD_BASE_URL, "https://api.deepseek.com/v1")
            ?: "https://api.deepseek.com/v1"
        set(value) {
            sp.edit().putString(KEY_CLOUD_BASE_URL, value).apply()
        }

    var cloudApiKey: String
        get() = sp.getString(KEY_CLOUD_API_KEY, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_CLOUD_API_KEY, value).apply()
        }

    var cloudModel: String
        get() = sp.getString(KEY_CLOUD_MODEL, "deepseek-chat") ?: "deepseek-chat"
        set(value) {
            sp.edit().putString(KEY_CLOUD_MODEL, value).apply()
        }

    /** 每句字幕在悬浮窗停留的秒数，超时后渐隐。 */
    var subtitleHoldSeconds: Int
        get() = sp.getInt(KEY_SUBTITLE_HOLD, 10).coerceIn(4, 60)
        set(value) {
            sp.edit().putInt(KEY_SUBTITLE_HOLD, value.coerceIn(4, 60)).apply()
        }

    var activeProviderId: String
        get() = sp.getString(KEY_ACTIVE_PROVIDER, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_ACTIVE_PROVIDER, value).apply()
        }

    /**
     * 多供应商配置。旧版本的 cloud_base_url / cloud_api_key / cloud_model
     * 会在第一次读取时迁移成「DeepSeek 官方」供应商，原 Key 不丢。
     */
    fun cloudProviders(): List<CloudProvider> {
        val raw = sp.getString(KEY_CLOUD_PROVIDERS, null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val id = item.optString("id").ifBlank { "provider-$i" }
                        add(
                            CloudProvider(
                                id = id,
                                name = item.optString("name").ifBlank { "供应商 ${i + 1}" },
                                baseUrl = item.optString("baseUrl"),
                                apiKey = item.optString("apiKey"),
                                model = item.optString("model"),
                                extraJson = item.optString("extraJson"),
                            ),
                        )
                    }
                }
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }

        return listOf(
            CloudProvider(
                id = "deepseek",
                name = "DeepSeek 官方",
                baseUrl = cloudBaseUrl,
                apiKey = cloudApiKey,
                model = cloudModel,
            ),
        )
    }

    fun saveCloudProviders(providers: List<CloudProvider>) {
        val array = JSONArray()
        providers.forEach { provider ->
            array.put(
                JSONObject().apply {
                    put("id", provider.id)
                    put("name", provider.name)
                    put("baseUrl", provider.baseUrl)
                    put("apiKey", provider.apiKey)
                    put("model", provider.model)
                    put("extraJson", provider.extraJson)
                },
            )
        }
        sp.edit().putString(KEY_CLOUD_PROVIDERS, array.toString()).apply()
        if (providers.isNotEmpty() && providers.none { it.id == activeProviderId }) {
            activeProviderId = providers.first().id
        }
    }

    fun activeProvider(): CloudProvider {
        val providers = cloudProviders()
        return providers.firstOrNull { it.id == activeProviderId } ?: providers.first()
    }

    companion object {
        const val MODE_APPS = "apps"
        const val MODE_ALL = "all"

        val SUBTITLE_HOLD_OPTIONS = listOf(6, 10, 15, 20, 30)

        private const val KEY_APPS = "selected_packages"
        private const val KEY_MODE = "listen_mode"
        private const val KEY_LANG = "source_lang"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_CLOUD_ENABLED = "cloud_enabled"
        private const val KEY_CLOUD_BASE_URL = "cloud_base_url"
        private const val KEY_CLOUD_API_KEY = "cloud_api_key"
        private const val KEY_CLOUD_MODEL = "cloud_model"
        private const val KEY_CLOUD_PROVIDERS = "cloud_providers"
        private const val KEY_ACTIVE_PROVIDER = "cloud_active_provider"
        private const val KEY_SUBTITLE_HOLD = "subtitle_hold_seconds"
    }
}
