package com.catfee.yingling.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catfee.yingling.engine.ModelCatalog
import com.catfee.yingling.ui.MainViewModel
import com.catfee.yingling.ui.theme.Cyan
import com.catfee.yingling.ui.theme.SurfaceHigh
import com.catfee.yingling.ui.theme.TextPrimary
import com.catfee.yingling.ui.theme.TextSecondary
import com.catfee.yingling.ui.theme.TextTertiary
import com.catfee.yingling.ui.theme.Warning
import com.catfee.yingling.util.NetworkStatus

/**
 * 离线模型下载卡片：首次使用、进度、失败重试、已就绪状态。
 *
 * @param alwaysShow 设置页始终显示；首页只在未就绪/下载中/失败时显示。
 */
@Composable
fun ModelSetupCard(
    vm: MainViewModel,
    alwaysShow: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by vm.downloadState.collectAsStateWithLifecycle()
    if (!vm.modelStatusLoaded) return
    if (!alwaysShow && vm.modelsReady && !state.running && state.error == null) return

    val wifi = NetworkStatus.isUnmetered(context)
    val total = ModelCatalog.totalBytes
    val need = if (vm.modelMissingBytes > 0L) vm.modelMissingBytes else total
    val error = state.error

    SectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "离线模型",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            MiniTag(
                when {
                    state.running -> "下载中"
                    vm.modelsReady -> "已就绪"
                    error != null -> "失败"
                    state.canceled -> "已暂停"
                    else -> "未下载"
                },
                color = when {
                    error != null -> Warning
                    state.running -> Cyan
                    vm.modelsReady -> Cyan
                    else -> TextTertiary
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        when {
            state.running -> {
                Text(
                    text = state.currentFile.ifBlank { "准备下载…" },
                    color = TextPrimary,
                    fontSize = 12.5f.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append("${state.fileIndex}/${state.fileCount} 个文件")
                        if (state.percent > 0) append(" · ${state.percent}%")
                        if (state.bytesPerSecond > 0) {
                            append(" · ${formatSpeed(state.bytesPerSecond)}")
                            val eta = (state.totalBytes - state.downloadedBytes)
                                .coerceAtLeast(0L) / state.bytesPerSecond
                            if (eta > 0) append(" · 剩余 ${formatDuration(eta)}")
                        }
                    },
                    color = TextTertiary,
                    fontSize = 10.5f.sp,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        if (state.totalBytes <= 0L) 0f
                        else (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50)),
                    color = Cyan,
                    trackColor = SurfaceHigh,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${ModelCatalog.humanSize(state.downloadedBytes)} / " +
                        ModelCatalog.humanSize(state.totalBytes),
                    color = TextSecondary,
                    fontSize = 10.5f.sp,
                )
                Spacer(Modifier.height(10.dp))
                GhostAction(
                    text = "暂停下载",
                    onClick = vm::cancelModelDownload,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            vm.modelsReady -> {
                Text(
                    text = "语音识别和本地翻译模型已就绪，断网也能使用。",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "共 ${ModelCatalog.humanSize(total)} · 已启用",
                    color = TextTertiary,
                    fontSize = 10.5f.sp,
                )
                Spacer(Modifier.height(10.dp))
                GhostAction(
                    text = "重新校验",
                    onClick = vm::refreshModelStatus,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> {
                Text(
                    text = if (error != null) {
                        error
                    } else {
                        "需要下载 ${ModelCatalog.humanSize(need)} 离线模型" +
                            "（${vm.modelMissingCount} 个文件）。下载后自动启用，不需要电脑。"
                    },
                    color = if (error != null) Warning else TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (wifi) {
                        "已连接 Wi-Fi，可直接下载，下载完成后自动启用"
                    } else {
                        "当前网络可能计费，下载会消耗约 ${ModelCatalog.humanSize(need)}"
                    },
                    color = TextTertiary,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryAction(
                    text = when {
                        state.canceled -> "继续下载"
                        error != null -> "重试下载"
                        wifi -> "下载模型"
                        else -> "使用移动网络下载"
                    },
                    onClick = vm::startModelDownload,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_048_576L -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
    bytesPerSec >= 1024L -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}

private fun formatDuration(seconds: Long): String = when {
    seconds >= 3600L -> "${seconds / 3600L}小时${(seconds % 3600L) / 60L}分"
    seconds >= 60L -> "${seconds / 60L}分${seconds % 60L}秒"
    else -> "${seconds.coerceAtLeast(1L)}秒"
}
