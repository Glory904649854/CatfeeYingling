package com.catfee.yingling.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catfee.yingling.core.Languages
import com.catfee.yingling.core.Prefs
import com.catfee.yingling.core.SessionStatus
import com.catfee.yingling.data.AppEntry
import com.catfee.yingling.service.RecognitionService
import com.catfee.yingling.ui.components.GhostAction
import com.catfee.yingling.ui.components.LiveWaveform
import com.catfee.yingling.ui.components.LogoMark
import com.catfee.yingling.ui.components.MiniTag
import com.catfee.yingling.ui.components.PrimaryAction
import com.catfee.yingling.ui.components.SectionCard
import com.catfee.yingling.ui.components.SectionTitle
import com.catfee.yingling.ui.components.StatusBadge
import com.catfee.yingling.ui.components.StatusDot
import com.catfee.yingling.ui.components.TagChip
import com.catfee.yingling.ui.theme.Blue
import com.catfee.yingling.ui.theme.BorderDark
import com.catfee.yingling.ui.theme.Danger
import com.catfee.yingling.ui.theme.Cyan
import com.catfee.yingling.ui.theme.IceCyan
import com.catfee.yingling.ui.theme.SurfaceHigh
import com.catfee.yingling.ui.theme.TextPrimary
import com.catfee.yingling.ui.theme.TextSecondary
import com.catfee.yingling.ui.theme.TextTertiary
import com.catfee.yingling.ui.theme.Warning
import com.catfee.yingling.util.Permissions
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenApps: () -> Unit,
) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val sourceText by vm.sourceText.collectAsStateWithLifecycle()
    val translatedText by vm.translatedText.collectAsStateWithLifecycle()
    val overlayVisible by vm.overlayVisible.collectAsStateWithLifecycle()
    val engineName by vm.engineName.collectAsStateWithLifecycle()
    val error by vm.lastError.collectAsStateWithLifecycle()
    val selectedApps = vm.apps.filter { it.packageName in vm.selected }

    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micGranted = remember(permissionTick) { Permissions.hasMic(context) }
    val overlayGranted = remember(permissionTick) { Permissions.hasOverlay(context) }
    val notificationsGranted = remember(permissionTick) { Permissions.hasNotifications(context) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionTick++ }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionTick++ }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            RecognitionService.start(context, result.resultCode, data)
        } else {
            Toast.makeText(
                context,
                "需要系统音频授权才能捕获其他应用的播放声音",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun startListening() {
        when {
            !micGranted -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            !overlayGranted -> context.startActivity(Permissions.overlayIntent(context))
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !notificationsGranted
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                // 系统只允许通过 MediaProjection 授权解锁其他应用的音频采集；应灵不录屏
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
    }

    val primaryText = when (status) {
        SessionStatus.RUNNING -> "暂停识别"
        SessionStatus.PAUSED -> "继续识别"
        SessionStatus.STARTING -> "启动中…"
        SessionStatus.ERROR -> "重新启动"
        SessionStatus.IDLE -> "启动监听"
    }

    val onPrimary: () -> Unit = {
        when (status) {
            SessionStatus.RUNNING -> RecognitionService.pause(context)
            SessionStatus.PAUSED -> RecognitionService.resume(context)
            SessionStatus.STARTING -> Unit
            else -> startListening()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Header(status)
        Spacer(Modifier.height(18.dp))

        error?.let { message ->
            ErrorBanner(message) { vm.clearError() }
            Spacer(Modifier.height(12.dp))
        }

        LiveCard(
            status = status,
            sourceText = sourceText,
            translatedText = translatedText,
            levelFlow = vm.level,
            overlayVisible = overlayVisible,
            engineLabel = when {
                engineName.contains("云端") -> "CLOUD"
                engineName.contains("Qwen") -> "FAST"
                engineName.contains("Whisper") -> "WHISPER"
                else -> "DEMO"
            },
            primaryText = primaryText,
            onPrimary = onPrimary,
            onStop = { RecognitionService.stop(context) },
            onToggleOverlay = { RecognitionService.toggleOverlay(context) },
        )

        Spacer(Modifier.height(14.dp))

        DirectionCard(
            sourceLang = vm.sourceLang,
            targetLang = vm.targetLang,
            fastReady = vm.fastReady,
            cloudEnabled = vm.cloudEnabled,
            onSelect = vm::selectSourceLang,
            onSelectTarget = vm::selectTargetLang,
        )

        Spacer(Modifier.height(14.dp))

        AppsCard(
            selectedApps = selectedApps,
            mode = vm.mode,
            onOpenApps = onOpenApps,
        )

        Spacer(Modifier.height(14.dp))

        PermissionsCard(
            micGranted = micGranted,
            overlayGranted = overlayGranted,
            notificationsGranted = notificationsGranted,
            onGrantMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onGrantOverlay = { context.startActivity(Permissions.overlayIntent(context)) },
            onGrantNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )

        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun Header(status: SessionStatus) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LogoMark(46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "应灵",
                    style = MaterialTheme.typography.displaySmall.copy(
                        brush = Brush.horizontalGradient(listOf(IceCyan, Cyan, Blue)),
                    ),
                )
                Spacer(Modifier.width(9.dp))
                MiniTag("BETA")
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(18.dp)
                        .height(1.5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Cyan, Blue))),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "YINGLING · LIVE CAPTION",
                    color = TextTertiary,
                    fontSize = 8.5f.sp,
                    letterSpacing = 1.6f.sp,
                )
            }
        }
        StatusBadge(status)
    }
}

@Composable
private fun LiveCard(
    status: SessionStatus,
    sourceText: String,
    translatedText: String,
    levelFlow: StateFlow<Float>,
    overlayVisible: Boolean,
    engineLabel: String,
    primaryText: String,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    onToggleOverlay: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(statusColor(status), 7.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "实时监听",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            MiniTag(engineLabel)
        }

        Spacer(Modifier.height(14.dp))

        LiveWaveform(
            levelFlow = levelFlow,
            active = status == SessionStatus.RUNNING,
            paused = status == SessionStatus.PAUSED,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = when {
                sourceText.isNotBlank() -> sourceText
                status == SessionStatus.IDLE -> "启动后开始捕获系统播放的音频"
                status == SessionStatus.ERROR -> "采集异常，请检查权限或目标应用"
                else -> "正在等待语音…"
            },
            color = TextTertiary,
            fontSize = 11.5f.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = translatedText.ifBlank { "—" },
            color = TextPrimary,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryAction(
                text = primaryText,
                enabled = status != SessionStatus.STARTING,
                modifier = Modifier.weight(1f),
                onClick = onPrimary,
            )
            Spacer(Modifier.width(10.dp))
            GhostAction(
                text = "停止",
                enabled = status != SessionStatus.IDLE,
                onClick = onStop,
            )
        }

        if (status == SessionStatus.RUNNING || status == SessionStatus.PAUSED) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (overlayVisible) Cyan else TextTertiary, 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (overlayVisible) "悬浮字幕窗已显示" else "悬浮字幕窗已隐藏",
                    color = TextTertiary,
                    fontSize = 10.5f.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (overlayVisible) "隐藏" else "显示",
                    color = Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onToggleOverlay),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectionCard(
    sourceLang: String,
    targetLang: String,
    fastReady: Boolean,
    cloudEnabled: Boolean,
    onSelect: (String) -> Unit,
    onSelectTarget: (String) -> Unit,
) {
    SectionCard {
        SectionTitle("翻译方向") {
            Text(
                text = if (sourceLang == "auto") "自动识别语种" else "已锁定 ${Languages.label(sourceLang)}",
                color = TextTertiary,
                fontSize = 10.5f.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Languages.sources.forEach { option ->
                TagChip(
                    text = option.label,
                    selected = sourceLang == option.code,
                    onClick = { onSelect(option.code) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("输出", color = TextTertiary, fontSize = 10.5f.sp)
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Languages.targets.forEach { option ->
                    TagChip(
                        text = option.label,
                        selected = targetLang == option.code,
                        onClick = { onSelectTarget(option.code) },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    fastReady && cloudEnabled && targetLang == "zh" &&
                        (sourceLang == "ja" || sourceLang == "ko" || sourceLang == "en") ->
                        "SenseVoice + 云端"
                    fastReady && cloudEnabled -> "Sherpa + 云端"
                    fastReady && targetLang == "zh" &&
                        (sourceLang == "ja" || sourceLang == "ko" || sourceLang == "en") ->
                        "SenseVoice + Qwen"
                    fastReady -> "Sherpa + Qwen 流式"
                    targetLang == "zh" && (sourceLang == "fr" || sourceLang == "ru") ->
                        "法/俄暂不支持"
                    else -> "模型未就绪"
                },
                color = TextTertiary,
                fontSize = 9.5f.sp,
            )
        }

        if (!fastReady && !(targetLang == "zh" && (sourceLang == "fr" || sourceLang == "ru"))) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "未检测到对应的 ASR 模型或翻译模型，启动后会回退到演示引擎",
                color = Warning,
                fontSize = 10f.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppsCard(
    selectedApps: List<AppEntry>,
    mode: String,
    onOpenApps: () -> Unit,
) {
    SectionCard {
        SectionTitle("监听应用") {
            Text(
                text = "管理 ›",
                color = Cyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onOpenApps),
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = when {
                mode == Prefs.MODE_ALL -> "全部媒体应用（按类型过滤）"
                selectedApps.isEmpty() -> "未指定应用，默认监听全部媒体音频"
                else -> "已选择 ${selectedApps.size} 个应用"
            },
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )

        if (selectedApps.isNotEmpty() && mode == Prefs.MODE_APPS) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selectedApps.take(4).forEach { app -> BoundAppChip(app) }
                if (selectedApps.size > 4) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(SurfaceHigh)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "+${selectedApps.size - 4}",
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "提示：目标应用若设置 allowAudioPlaybackCapture=false 或使用 DRM，将无法被捕获。",
            color = TextTertiary,
            fontSize = 10.5f.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun BoundAppChip(app: AppEntry) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(SurfaceHigh)
            .border(1.dp, BorderDark, RoundedCornerShape(50))
            .padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BorderDark),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = app.label,
            color = TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PermissionsCard(
    micGranted: Boolean,
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    onGrantMic: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantNotifications: () -> Unit,
) {
    SectionCard {
        SectionTitle("权限")

        Spacer(Modifier.height(6.dp))

        PermissionRow("麦克风", micGranted, onGrantMic)
        PermissionRow("悬浮窗", overlayGranted, onGrantOverlay)
        PermissionRow("通知", notificationsGranted, onGrantNotifications)

        Spacer(Modifier.height(6.dp))

        Text(
            text = "启动时系统会弹出「录制或投射屏幕」授权：Android 只允许通过它获取其他应用的音频，应灵不会录屏、不会上传。",
            color = TextTertiary,
            fontSize = 10.5f.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (granted) Cyan else Warning),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (granted) "已授权" else "去授权",
            color = if (granted) Cyan.copy(alpha = 0.7f) else Cyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = if (granted) Modifier else Modifier.clickable(onClick = onGrant),
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Danger.copy(alpha = 0.10f))
            .border(1.dp, Danger.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = Danger,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "知道了",
            color = Danger,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onDismiss),
        )
    }
}

private fun statusColor(status: SessionStatus): Color = when (status) {
    SessionStatus.RUNNING -> Cyan
    SessionStatus.PAUSED -> Warning
    SessionStatus.STARTING -> Blue
    SessionStatus.ERROR -> Danger
    SessionStatus.IDLE -> TextTertiary
}
