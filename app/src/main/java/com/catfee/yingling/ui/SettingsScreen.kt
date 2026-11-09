package com.catfee.yingling.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catfee.yingling.core.Prefs
import com.catfee.yingling.core.SessionStatus
import com.catfee.yingling.service.RecognitionService
import com.catfee.yingling.ui.components.HairLine
import com.catfee.yingling.ui.components.GhostAction
import com.catfee.yingling.ui.components.ModelSetupCard
import com.catfee.yingling.ui.components.SectionCard
import com.catfee.yingling.ui.components.SectionTitle
import com.catfee.yingling.ui.components.TagChip
import com.catfee.yingling.ui.theme.BorderDark
import com.catfee.yingling.ui.theme.Cyan
import com.catfee.yingling.ui.theme.Danger
import com.catfee.yingling.ui.theme.SurfaceHigh
import com.catfee.yingling.ui.theme.TextPrimary
import com.catfee.yingling.ui.theme.TextSecondary
import com.catfee.yingling.ui.theme.TextTertiary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val overlayVisible by vm.overlayVisible.collectAsStateWithLifecycle()
    val active = status == SessionStatus.RUNNING || status == SessionStatus.PAUSED
    var showDeleteDialog by remember { mutableStateOf(false) }

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
                selected = !vm.fastReady,
                planned = false,
                onClick = {},
            )
            HairLine()
            EngineRow(
                title = "多语种流式（Sherpa / SenseVoice）",
                subtitle = if (vm.fastReady) {
                    "中/英/日/韩 → 中文，中 → 英 · 本地或云端翻译"
                } else {
                    "未找到对应的 ASR 模型或翻译模型"
                },
                selected = vm.fastReady,
                planned = false,
                onClick = {
                    Toast.makeText(
                        context,
                        "在实时页选择源语言和目标语言即可",
                        Toast.LENGTH_SHORT,
                    ).show()
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

        ModelSetupCard(vm, alwaysShow = true)

        Spacer(Modifier.height(14.dp))

        SectionCard {
            SectionTitle("翻译引擎")
            Spacer(Modifier.height(4.dp))
            EngineRow(
                title = "本地 Qwen（默认）",
                subtitle = if (vm.cloudEnabled) {
                    "当前使用云端，本地模型作为失败兜底"
                } else {
                    "0.5B · 离线运行 · 每句约 1.5~3 秒"
                },
                selected = !vm.cloudEnabled,
                planned = false,
                onClick = { vm.selectCloudEnabled(false) },
            )
            HairLine()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "云端 LLM（OpenAI 兼容）",
                        color = if (vm.cloudEnabled) TextPrimary else TextSecondary,
                        fontSize = 13.5f.sp,
                        fontWeight = if (vm.cloudEnabled) FontWeight.Medium else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "开启后优先云端翻译，失败自动回退本地",
                        color = TextTertiary,
                        fontSize = 10.5f.sp,
                    )
                }
                Switch(
                    checked = vm.cloudEnabled,
                    onCheckedChange = vm::selectCloudEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF04122E),
                        checkedTrackColor = Cyan,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = SurfaceHigh,
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.providers.forEach { provider ->
                    TagChip(
                        text = provider.name,
                        selected = provider.id == vm.activeProviderId,
                        onClick = { vm.selectProvider(provider.id) },
                    )
                }
                TagChip(
                    text = "+ 新增",
                    selected = false,
                    onClick = vm::addProvider,
                )
            }

            Spacer(Modifier.height(10.dp))
            CloudField(
                label = "供应商名称",
                value = vm.cloudName,
                onValueChange = vm::updateCloudName,
                placeholder = "DeepSeek 官方",
            )
            Spacer(Modifier.height(8.dp))
            CloudField(
                label = "Base URL",
                value = vm.cloudBaseUrl,
                onValueChange = vm::updateCloudBaseUrl,
                placeholder = "https://api.deepseek.com/v1",
            )
            Spacer(Modifier.height(8.dp))
            CloudField(
                label = "API Key",
                value = vm.cloudApiKey,
                onValueChange = vm::updateCloudApiKey,
                placeholder = "sk-...",
                secret = true,
            )
            Spacer(Modifier.height(8.dp))
            CloudField(
                label = "模型",
                value = vm.cloudModel,
                onValueChange = vm::updateCloudModel,
                placeholder = "deepseek-chat",
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostAction(
                    text = if (vm.modelListLoading) "拉取中…" else "拉取模型",
                    enabled = !vm.modelListLoading,
                    onClick = vm::fetchModels,
                    modifier = Modifier.weight(1f),
                )
                GhostAction(
                    text = if (vm.cloudTestRunning) "拨测中…" else "拨测",
                    enabled = !vm.cloudTestRunning,
                    onClick = vm::testProvider,
                    modifier = Modifier.weight(1f),
                )
            }
            vm.cloudTestResult?.let { result ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = result,
                    color = if (vm.cloudTestSuccess == true) Cyan else Danger,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceHigh)
                    .clickable {
                        vm.applyCloudConfig()
                        Toast.makeText(context, "云端配置已应用", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "应用配置",
                    color = Cyan,
                    fontSize = 12.5f.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "每个供应商独立保存 Key，切换不会覆盖其他供应商；Key 只保存在本机应用私有存储。",
                color = TextTertiary,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "自定义供应商会自动关闭思考（reasoning_effort=none）；DeepSeek 官方 chat/flash 本身不思考。",
                color = TextTertiary,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
            if (vm.providers.size > 1) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "删除当前供应商",
                    color = Danger,
                    fontSize = 10.5f.sp,
                    modifier = Modifier.clickable { showDeleteDialog = true },
                )
            }
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("删除供应商") },
                text = {
                    Text(
                        "只会删除「${vm.cloudName.ifBlank { "当前供应商" }}」的配置，其他供应商的 Key 不受影响。",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.deleteActiveProvider()
                            showDeleteDialog = false
                        },
                    ) {
                        Text("删除", color = Danger)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }

        if (vm.modelPickerVisible) {
            val modelError = vm.modelListError
            AlertDialog(
                onDismissRequest = vm::dismissModelPicker,
                title = { Text("选择模型") },
                text = {
                    when {
                        vm.modelListLoading -> {
                            Text("正在拉取模型列表…")
                        }

                        modelError != null -> {
                            Text(modelError, color = Danger)
                        }

                        else -> {
                            Column(
                                Modifier
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    text = "共 ${vm.modelOptions.size} 个模型",
                                    color = TextTertiary,
                                    fontSize = 10.5.sp,
                                )
                                Spacer(Modifier.height(6.dp))
                                vm.modelOptions.forEach { model ->
                                    Text(
                                        text = model,
                                        color = if (model == vm.cloudModel) Cyan else TextPrimary,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { vm.selectCloudModel(model) }
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = vm::dismissModelPicker) {
                        Text("关闭")
                    }
                },
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
                        checkedThumbColor = Color(0xFF04122E),
                        checkedTrackColor = Cyan,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = SurfaceHigh,
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard {
            SectionTitle("字幕显示")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "每句停留",
                    color = TextPrimary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${vm.subtitleHoldSeconds} 秒",
                    color = Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Prefs.SUBTITLE_HOLD_OPTIONS.forEach { seconds ->
                    TagChip(
                        text = "${seconds}秒",
                        selected = vm.subtitleHoldSeconds == seconds,
                        onClick = { vm.selectSubtitleHold(seconds) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "超时后渐隐移除；数值越大，上一句保留越久。",
                color = TextTertiary,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard {
            SectionTitle("运行方式")
            Spacer(Modifier.height(10.dp))
            InfoLine("音频来源", "AudioPlaybackCapture · 48kHz → 16kHz")
            InfoLine("前台服务", "microphone · Android 14+ 需应用可见时启动")
            InfoLine("字幕链路", "VAD → ASR → 翻译 → 悬浮字幕")
            InfoLine(
                "数据去向",
                if (vm.cloudEnabled) {
                    "音频仍在本机识别；仅识别文本发往自定义云端翻译"
                } else {
                    "全部在本机处理，不发起云端请求"
                },
            )
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
private fun CloudField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = { Text(placeholder, color = TextTertiary, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (secret) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        textStyle = MaterialTheme.typography.bodySmall,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cyan,
            unfocusedBorderColor = BorderDark,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = Cyan,
            focusedLabelColor = Cyan,
            unfocusedLabelColor = TextTertiary,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
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
                .border(1.5.dp, if (selected) Cyan else BorderDark, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Cyan),
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
                color = Cyan,
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
