package com.catfee.yingling.core

import android.content.Context

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

    companion object {
        const val MODE_APPS = "apps"
        const val MODE_ALL = "all"

        private const val KEY_APPS = "selected_packages"
        private const val KEY_MODE = "listen_mode"
        private const val KEY_LANG = "source_lang"
        private const val KEY_TARGET_LANG = "target_lang"
    }
}
