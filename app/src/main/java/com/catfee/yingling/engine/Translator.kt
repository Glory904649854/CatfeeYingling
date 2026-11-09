package com.catfee.yingling.engine

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class CloudLlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val extraJson: String = "",
)

/**
 * 翻译统一入口：云端 LLM 优先，未开启或失败时回退本地 Qwen。
 */
class Translator(
    private val cloud: CloudLlmConfig?,
    private val qwenModelPath: String?,
) {

    private var handle = 0L

    @Volatile
    private var ready = false

    val isReady: Boolean get() = ready
    val isCloud: Boolean get() = cloud != null

    fun start() {
        val path = qwenModelPath
        if (path != null) {
            handle = runCatching { QwenBridge.nativeInit(path) }.getOrDefault(0L)
        }
        // 云端开启时本地模型只作为失败兜底，不影响就绪状态
        ready = cloud != null || handle != 0L
        Log.d(TAG, "ready=$ready cloud=${cloud != null} localFallback=${handle != 0L}")
    }

    fun translate(text: String, targetLanguage: String): String {
        val config = cloud
        if (config != null) {
            val cloudResult = runCatching {
                CloudClient.translate(config, text, targetLanguage)
            }.onFailure {
                Log.e(
                    TAG,
                    "cloud translate failed (${it.javaClass.simpleName}: ${it.message}), fallback local",
                    it,
                )
            }.getOrDefault("")

            if (cloudResult.isNotBlank()) return cloudResult
            if (handle != 0L) {
                return runCatching {
                    QwenBridge.nativeTranslate(handle, text, targetLanguage)
                }.getOrDefault("")
            }
            return ""
        }

        if (handle == 0L) return ""
        return runCatching {
            QwenBridge.nativeTranslate(handle, text, targetLanguage)
        }.getOrDefault("")
    }

    fun close() {
        if (handle != 0L) {
            runCatching { QwenBridge.nativeRelease(handle) }
            handle = 0L
        }
        ready = false
    }

    private companion object {
        const val TAG = "YingLingTranslator"
    }
}

/** OpenAI 兼容的 chat/completions 调用。 */
object CloudClient {

    private val CORE_KEYS = setOf("model", "messages", "stream", "temperature", "max_tokens")

    data class TestResult(
        val ok: Boolean,
        val message: String,
        val elapsedMs: Long,
    )

    /** 拉取 GET /models，返回模型 ID 列表。 */
    fun listModels(config: CloudLlmConfig): List<String> {
        val base = baseUrl(config)
        if (config.apiKey.isBlank()) throw IOException("请先填写 API Key")

        val conn = URL("$base/models").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.setRequestProperty("Accept", "application/json")

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code: ${body.take(200)}")
            }
            val data = JSONObject(body).optJSONArray("data")
                ?: throw IOException("接口没有返回 data 列表")
            return buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }.distinct().sorted()
        } finally {
            conn.disconnect()
        }
    }

    /** 发一条最小对话请求，测连通性、鉴权和模型是否可用。 */
    fun test(config: CloudLlmConfig): TestResult {
        val start = android.os.SystemClock.elapsedRealtime()
        return try {
            val payload = buildPayload(
                config = config,
                system = null,
                userText = "Reply with OK.",
                maxTokens = 32,
            )
            val (code, body) = post(config, payload)
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            if (code !in 200..299) {
                TestResult(false, "HTTP $code: ${body.take(200)}", elapsed)
            } else {
                val content = extractContent(body).ifBlank { "连接成功" }
                TestResult(true, content.take(160), elapsed)
            }
        } catch (t: Throwable) {
            TestResult(
                ok = false,
                message = t.message?.takeIf { it.isNotBlank() } ?: "连接失败",
                elapsedMs = android.os.SystemClock.elapsedRealtime() - start,
            )
        }
    }

    fun translate(config: CloudLlmConfig, text: String, targetLanguage: String): String {
        val system = if (targetLanguage == "zh") {
            "Translate the user sentence into Simplified Chinese. Output only the Chinese translation."
        } else {
            "Translate the Chinese subtitle into concise English. Output only the translation, no Chinese."
        }
        val payload = buildPayload(config, system, text, 256)
        val (code, body) = post(config, payload)
        if (code !in 200..299) {
            throw IOException("HTTP $code: ${body.take(200)}")
        }
        return extractContent(body).trim()
    }

    private fun buildPayload(
        config: CloudLlmConfig,
        system: String?,
        userText: String,
        maxTokens: Int,
    ): JSONObject {
        baseUrl(config)
        if (config.apiKey.isBlank() || config.model.isBlank()) {
            throw IOException("cloud config incomplete")
        }

        return JSONObject().apply {
            // 供应商自定义参数（如 enable_thinking / reasoning_effort）先放，
            // 下面再用核心字段覆盖，避免把 model/messages 写坏。
            if (config.extraJson.isNotBlank()) {
                runCatching { JSONObject(config.extraJson) }.getOrNull()?.let { extra ->
                    val keys = extra.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key !in CORE_KEYS) put(key, extra.get(key))
                    }
                }
            }
            put("model", config.model)
            put("temperature", 0)
            put("max_tokens", maxTokens)
            put("stream", false)
            put("messages", JSONArray().apply {
                if (system != null) {
                    put(JSONObject().put("role", "system").put("content", system))
                }
                put(JSONObject().put("role", "user").put("content", userText))
            })
        }
    }

    private fun post(config: CloudLlmConfig, payload: JSONObject): Pair<Int, String> {
        val base = baseUrl(config)
        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8_000
        conn.readTimeout = 25_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")

        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun extractContent(body: String): String =
        JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()

    private fun baseUrl(config: CloudLlmConfig): String {
        val base = config.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) throw IOException("请先填写 Base URL")
        return base
    }
}
