package com.catfee.yingling.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catfee.yingling.core.SessionStatus
import com.catfee.yingling.service.RecognitionService
import com.catfee.yingling.ui.components.HairLine
import com.catfee.yingling.ui.components.SectionCard
import com.catfee.yingling.ui.components.SectionTitle
import com.catfee.yingling.ui.theme.BorderDark
import com.catfee.yingling.ui.theme.Mint
import com.catfee.yingling.ui.theme.SurfaceHigh
import com.catfee.yingling.ui.theme.TextPrimary
import com.catfee.yingling.ui.theme.TextSecondary
import com.catfee.yingling.ui.theme.TextTertiary

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val overlayVisible by vm.overlayVisible.collectAsStateWithLifecycle()
    val active = status == SessionStatus.RUNNING || status == SessionStatus.PAUSED

    fun planned() {
        Toast.makeText(
            context,
            "本地模型接入说明见 docs/MODEL_INTEGRATION.md",
            Toast.LENGTH_SHORT,
        ).show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "引擎、悬浮窗与运行方式",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(Modifier.height(18.dp))

        SectionCard {
            SectionTitle("识别引擎")
            Spacer(Modifier.height(4.dp))
            EngineRow(
                title = "Demo 引擎（内置）",
                subtitle = "按音量模拟断句，用于跑通 UI 与采集链路",
                selected = !(vm.targetLang == "en" && vm.whisperReady),
                planned = false,
                onClick = { vm.selectTargetLang("zh") },
            )
            HairLine()
            EngineRow(
                title = "Whisper small（中 → 英）",
                subtitle = if (vm.whisperReady) {
                    "模型已就绪 · whisper.cpp translate"
                } else {
                    "未找到模型：放入 models/ggml-small-q5_1.bin"
                },
                selected = vm.targetLang == "en" && vm.whisperReady,
                planned = false,
                onClick = {
                    if (vm.whisperReady) {
                        vm.selectTargetLang("en")
                    } else {
                        Toast.makeText(
                            context,
                            "请先把 ggml-small-q5_1.bin 放入应用 models 目录",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            )
            HairLine()
            EngineRow(
                title = "SenseVoice Small",
                subtitle = "中英日韩更快更准，法俄需 Whisper 兜底",
                selected = false,
                planned = true,
                onClick = { planned() },
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard {
            SectionTitle("翻译引擎")
            Spacer(Modifier.height(4.dp))
            EngineRow(
                title = "本地 Qwen3",
                subtitle = "0.6B / 1.7B，六语种直译中文，可带上下文",
                selected = false,
                planned = true,
                onClick = { planned() },
            )
            HairLine()
            EngineRow(
                title = "云端翻译（可选）",
                subtitle = "质量最好，但需要联网与账号，默认关闭",
                selected = false,
                planned = true,
                onClick = { planned() },
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard {
            SectionTitle("悬浮窗")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "显示悬浮字幕窗",
                        color = TextPrimary,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (active) "显示声浪、状态、译文与暂停/停止" else "启动监听后可开启",
                        color = TextTertiary,
                        fontSize = 10.5f.sp,
                    )
                }
                Switch(
                    checked = overlayVisible,
                    enabled = active,
                    onCheckedChange = { RecognitionService.toggleOverlay(context) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF04120E),
                        checkedTrackColor = Mint,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = SurfaceHigh,
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard {
            SectionTitle("运行方式")
            Spacer(Modifier.height(10.dp))
            InfoLine("音频来源", "AudioPlaybackCapture · 48kHz → 16kHz")
            InfoLine("前台服务", "microphone · Android 14+ 需应用可见时启动")
            InfoLine("字幕链路", "VAD → ASR → 翻译 → 悬浮字幕")
            InfoLine("数据去向", "全部在本机处理，不发起云端请求")
        }

        Spacer(Modifier.height(14.dp))

        SectionCard {
            SectionTitle("关于")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "应灵 · CatfeeAudio",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "v0.1.0 · Local-first",
                color = TextTertiary,
                fontSize = 10.5f.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "开源栈：sherpa-onnx / Whisper / Qwen / Silero VAD / OpenCC。当前版本未内置模型文件。",
                color = TextTertiary,
                fontSize = 10.5f.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun EngineRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    planned: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (selected) Mint else BorderDark, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Mint),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = 13.5f.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextTertiary,
                fontSize = 10.5f.sp,
            )
        }

        if (planned) {
            Text(
                text = "PLANNED",
                color = TextTertiary,
                fontSize = 8.5f.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceHigh)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        } else if (selected) {
            Text(
                text = "当前",
                color = Mint,
                fontSize = 10.5f.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = TextTertiary,
            fontSize = 10.5f.sp,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            color = TextSecondary,
            fontSize = 10.5f.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f),
        )
    }
}
