package com.catfee.yingling.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class ModelDownloadState(
    val running: Boolean = false,
    val completed: Boolean = false,
    val canceled: Boolean = false,
    val error: String? = null,
    val currentGroup: String = "",
    val currentFile: String = "",
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val fileDownloaded: Long = 0L,
    val fileTotal: Long = 0L,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val startedAt: Long = 0L,
) {
    val percent: Int
        get() = if (totalBytes <= 0L) {
            if (completed) 100 else 0
        } else {
            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        }
}

/**
 * 应用内模型下载器：顺序下载缺失文件，支持 .part 断点续传。
 * 状态是进程级单例，Activity 重建时进度不会丢。
 */
object ModelDownloader {

    private const val TAG = "YingLingModels"

    private val _state = MutableStateFlow(ModelDownloadState())
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    private var job: Job? = null

    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        _state.value = ModelDownloadState(
            running = true,
            startedAt = SystemClock.elapsedRealtime(),
        )
        job = scope.launch(Dispatchers.IO) {
            try {
                downloadAll(app)
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false, canceled = true) }
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "download failed", t)
                _state.update {
                    it.copy(
                        running = false,
                        error = t.message?.takeIf { msg -> msg.isNotBlank() } ?: "下载失败，请重试",
                    )
                }
            }
        }
    }

    fun cancel() {
        if (job?.isActive == true) {
            job?.cancel()
            _state.update { it.copy(running = false, canceled = true) }
        }
    }

    private suspend fun downloadAll(context: Context) {
        val missing = ModelCatalog.missing(context)
        if (missing.isEmpty()) {
            _state.update {
                it.copy(running = false, completed = true, totalBytes = 0, downloadedBytes = 0)
            }
            return
        }

        val totalBytes = missing.sumOf { it.file.bytes }
        val root = ModelStore.modelsDir(context)
        root.mkdirs()
        val free = root.usableSpace
        if (free > 0L && free < totalBytes + 200L * 1024 * 1024) {
            throw IOException(
                "存储空间不足：需要 ${ModelCatalog.humanSize(totalBytes)}，可用 ${ModelCatalog.humanSize(free)}",
            )
        }

        _state.update {
            it.copy(
                running = true,
                completed = false,
                canceled = false,
                error = null,
                currentGroup = "",
                currentFile = "",
                fileIndex = 0,
                fileCount = missing.size,
                fileDownloaded = 0,
                fileTotal = 0,
                downloadedBytes = 0,
                totalBytes = totalBytes,
                bytesPerSecond = 0,
                startedAt = SystemClock.elapsedRealtime(),
            )
        }

        var completedBytes = 0L
        missing.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            item.target.parentFile?.mkdirs()
            val part = File(item.target.parentFile, item.target.name + ".part")

            if (item.target.isFile && item.target.length() != item.file.bytes) {
                item.target.delete()
            }

            var offset = if (part.isFile) part.length() else 0L
            if (offset > item.file.bytes) {
                part.delete()
                offset = 0L
            }
            var fileDownloaded = offset

            _state.update {
                it.copy(
                    currentGroup = item.group.title,
                    currentFile = item.file.name,
                    fileIndex = index + 1,
                    fileDownloaded = fileDownloaded,
                    fileTotal = item.file.bytes,
                    downloadedBytes = completedBytes + fileDownloaded,
                    bytesPerSecond = 0,
                )
            }

            downloadFile(item, part, offset) { downloaded, speed ->
                fileDownloaded = downloaded
                _state.update {
                    it.copy(
                        fileDownloaded = downloaded,
                        downloadedBytes = completedBytes + downloaded,
                        bytesPerSecond = speed,
                    )
                }
            }

            if (part.length() != item.file.bytes) {
                throw IOException(
                    "${item.file.name} 下载不完整（${part.length()} / ${item.file.bytes}）",
                )
            }
            if (item.target.exists()) item.target.delete()
            if (!part.renameTo(item.target)) {
                part.copyTo(item.target, overwrite = true)
                part.delete()
            }

            completedBytes += item.file.bytes
            _state.update {
                it.copy(
                    downloadedBytes = completedBytes,
                    fileDownloaded = item.file.bytes,
                    bytesPerSecond = 0,
                )
            }
            Log.d(TAG, "downloaded ${item.file.name} (${item.file.bytes} bytes)")
        }

        _state.update {
            it.copy(
                running = false,
                completed = true,
                currentFile = "",
                currentGroup = "",
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                bytesPerSecond = 0,
            )
        }
        Log.d(TAG, "all models ready")
    }

    private suspend fun downloadFile(
        item: MissingModelFile,
        part: File,
        initialOffset: Long,
        onProgress: (downloaded: Long, bytesPerSecond: Long) -> Unit,
    ) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(item.file.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "CatfeeYingling/1.0")
                if (initialOffset > 0L) {
                    setRequestProperty("Range", "bytes=$initialOffset-")
                }
            }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAVAILABLE || code == 416) {
                part.delete()
                throw IOException("下载源暂不可用（HTTP $code），请重试")
            }
            if (code !in 200..299) {
                throw IOException("下载失败：HTTP $code")
            }

            val append = code == 206 && initialOffset > 0L
            var offset = if (append) initialOffset else 0L
            if (!append && initialOffset > 0L) {
                onProgress(0L, 0L)
            }

            RandomAccessFile(part, "rw").use { raf ->
                if (append) {
                    raf.seek(offset)
                } else {
                    raf.setLength(0)
                }

                val input = connection.inputStream
                val buffer = ByteArray(128 * 1024)
                var downloaded = offset
                var lastUpdate = 0L
                var lastBytes = downloaded
                var lastTime = SystemClock.elapsedRealtime()

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    raf.write(buffer, 0, read)
                    downloaded += read

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 200L) {
                        val elapsed = (now - lastTime).coerceAtLeast(1L)
                        val speed = ((downloaded - lastBytes) * 1000L / elapsed).coerceAtLeast(0L)
                        onProgress(downloaded, speed)
                        lastUpdate = now
                        lastBytes = downloaded
                        lastTime = now
                    }
                }
                raf.fd.sync()
                onProgress(downloaded, 0L)
            }
        } finally {
            connection?.disconnect()
        }
    }
}
