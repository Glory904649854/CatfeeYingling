package com.catfee.yingling.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class HistoryEntry(
    val source: String,
    val translated: String,
    val timestamp: Long,
    val appPackage: String,
    val appName: String,
)

data class HistorySession(
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val sourceLang: String,
    val targetLang: String,
    val appPackage: String,
    val appName: String,
    val entries: List<HistoryEntry>,
)

/**
 * 翻译历史：按监听会话归档，原文 + 译文 + 来源 App + 时间。
 * 存应用私有目录的 JSON，进程重启后保留；最多 200 个会话 / 5000 条字幕。
 */
object HistoryStore {

    private const val MAX_SESSIONS = 200
    private const val MAX_ENTRIES = 5_000
    private const val FILE_NAME = "translation_history.json"

    private val _sessions = MutableStateFlow<List<HistorySession>>(emptyList())
    val sessions: StateFlow<List<HistorySession>> = _sessions.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "yingling-history") }
    private val saveQueued = AtomicBoolean(false)
    private var file: File? = null
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        file = target
        _sessions.value = runCatching {
            if (target.isFile) parse(target.readText()) else emptyList()
        }.getOrDefault(emptyList())
            .sortedByDescending { it.startedAt }
    }

    @Synchronized
    fun beginSession(
        context: Context,
        sourceLang: String,
        targetLang: String,
        appPackage: String,
        appName: String,
    ): String {
        ensureLoaded(context)
        val session = HistorySession(
            id = UUID.randomUUID().toString(),
            startedAt = System.currentTimeMillis(),
            endedAt = null,
            sourceLang = sourceLang,
            targetLang = targetLang,
            appPackage = appPackage,
            appName = appName,
            entries = emptyList(),
        )
        _sessions.value = trim(listOf(session) + _sessions.value)
        scheduleSave()
        return session.id
    }

    @Synchronized
    fun addEntry(
        sessionId: String,
        source: String,
        translated: String,
        timestamp: Long,
        appPackage: String,
        appName: String,
    ) {
        if (source.isBlank() && translated.isBlank()) return
        _sessions.value = trim(
            _sessions.value.map { session ->
                if (session.id != sessionId) {
                    session
                } else {
                    session.copy(
                        appPackage = session.appPackage.ifBlank { appPackage },
                        appName = session.appName.ifBlank { appName },
                        entries = session.entries + HistoryEntry(
                            source = source,
                            translated = translated,
                            timestamp = timestamp,
                            appPackage = appPackage,
                            appName = appName,
                        ),
                    )
                }
            },
        )
        scheduleSave()
    }

    @Synchronized
    fun endSession(sessionId: String, endedAt: Long) {
        _sessions.value = trim(
            _sessions.value.mapNotNull { session ->
                if (session.id != sessionId) {
                    session
                } else if (session.entries.isEmpty()) {
                    null
                } else {
                    session.copy(endedAt = endedAt)
                }
            },
        )
        scheduleSave()
    }

    @Synchronized
    fun clear() {
        _sessions.value = emptyList()
        scheduleSave()
    }

    private fun trim(sessions: List<HistorySession>): List<HistorySession> {
        var remainingEntries = MAX_ENTRIES
        val kept = ArrayList<HistorySession>(sessions.size)
        for (session in sessions.sortedByDescending { it.startedAt }) {
            if (kept.size >= MAX_SESSIONS || remainingEntries <= 0) break
            val entries = if (session.entries.size > remainingEntries) {
                session.entries.takeLast(remainingEntries)
            } else {
                session.entries
            }
            remainingEntries -= entries.size
            kept += session.copy(entries = entries)
        }
        return kept
    }

    private fun scheduleSave() {
        if (!saveQueued.compareAndSet(false, true)) return
        executor.execute {
            saveQueued.set(false)
            runCatching { writeSnapshot(_sessions.value) }
        }
    }

    private fun writeSnapshot(sessions: List<HistorySession>) {
        val target = file ?: return
        val root = JSONObject().apply {
            put("version", 1)
            put("sessions", JSONArray().apply {
                sessions.forEach { session ->
                    put(JSONObject().apply {
                        put("id", session.id)
                        put("startedAt", session.startedAt)
                        put("endedAt", session.endedAt ?: JSONObject.NULL)
                        put("sourceLang", session.sourceLang)
                        put("targetLang", session.targetLang)
                        put("appPackage", session.appPackage)
                        put("appName", session.appName)
                        put("entries", JSONArray().apply {
                            session.entries.forEach { entry ->
                                put(JSONObject().apply {
                                    put("source", entry.source)
                                    put("translated", entry.translated)
                                    put("timestamp", entry.timestamp)
                                    put("appPackage", entry.appPackage)
                                    put("appName", entry.appName)
                                })
                            }
                        })
                    })
                }
            })
        }

        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(target)) {
            target.writeText(root.toString())
            temp.delete()
        }
    }

    private fun parse(raw: String): List<HistorySession> {
        val root = JSONObject(raw)
        val array = root.optJSONArray("sessions") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val entriesJson = item.optJSONArray("entries") ?: JSONArray()
                val entries = buildList {
                    for (j in 0 until entriesJson.length()) {
                        val entry = entriesJson.optJSONObject(j) ?: continue
                        val source = entry.optString("source")
                        val translated = entry.optString("translated")
                        if (source.isBlank() && translated.isBlank()) continue
                        add(
                            HistoryEntry(
                                source = source,
                                translated = translated,
                                timestamp = entry.optLong("timestamp"),
                                appPackage = entry.optString("appPackage"),
                                appName = entry.optString("appName"),
                            ),
                        )
                    }
                }
                if (entries.isEmpty()) continue
                add(
                    HistorySession(
                        id = item.optString("id").ifBlank { "session-$i" },
                        startedAt = item.optLong("startedAt"),
                        endedAt = if (item.isNull("endedAt")) null else item.optLong("endedAt"),
                        sourceLang = item.optString("sourceLang"),
                        targetLang = item.optString("targetLang"),
                        appPackage = item.optString("appPackage"),
                        appName = item.optString("appName"),
                        entries = entries,
                    ),
                )
            }
        }
    }
}
