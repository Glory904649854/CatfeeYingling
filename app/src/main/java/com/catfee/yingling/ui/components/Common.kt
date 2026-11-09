package com.catfee.yingling.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catfee.yingling.R
import com.catfee.yingling.core.SessionStatus
import com.catfee.yingling.core.label
import com.catfee.yingling.ui.theme.Blue
import com.catfee.yingling.ui.theme.BorderDark
import com.catfee.yingling.ui.theme.Danger
import com.catfee.yingling.ui.theme.Cyan
import com.catfee.yingling.ui.theme.SurfaceHigh
import com.catfee.yingling.ui.theme.TextPrimary
import com.catfee.yingling.ui.theme.TextSecondary
import com.catfee.yingling.ui.theme.TextTertiary
import com.catfee.yingling.ui.theme.Warning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sin

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1D3E), Color(0xFF081733))))
            .border(1.dp, BorderDark, RoundedCornerShape(22.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BorderDark.copy(alpha = 0.65f)),
    )
}

@Composable
fun StatusDot(
    color: Color,
    size: Dp = 7.dp,
) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun StatusBadge(status: SessionStatus) {
    val color = when (status) {
        SessionStatus.RUNNING -> Cyan
        SessionStatus.PAUSED -> Warning
        SessionStatus.STARTING -> Blue
        SessionStatus.ERROR -> Danger
        SessionStatus.IDLE -> TextTertiary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text = status.label(),
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun TagChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) Cyan.copy(alpha = 0.14f) else SurfaceHigh
    val border = if (selected) Cyan.copy(alpha = 0.45f) else BorderDark
    val foreground = if (selected) Cyan else TextSecondary

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text = text, color = foreground, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun PrimaryAction(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Cyan, Blue)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color(0xFF04122E),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun GhostAction(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun MiniTag(text: String, color: Color = Cyan) {
    Text(
        text = text,
        color = color,
        fontSize = 8.5f.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun LogoMark(size: Dp = 46.dp) {
    Image(
        painter = painterResource(R.drawable.logo_mark),
        contentDescription = "应灵",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f)),
    )
}

/**
 * 实时声浪：独立协程按 30fps 推进历史数据，只在绘制阶段读状态，
 * 不会因为 20Hz 的电平更新而重组整个页面。
 */
@Composable
fun LiveWaveform(
    levelFlow: StateFlow<Float>,
    active: Boolean,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val level = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(levelFlow) {
        levelFlow.collect { level.floatValue = it }
    }

    val bars = remember { FloatArray(48) }
    val head = remember { intArrayOf(0) }
    val smooth = remember { floatArrayOf(0f) }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(33L)
            val target = if (active && !paused) level.floatValue else 0f
            val current = smooth[0]
            smooth[0] = current + (target - current) * if (target > current) 0.55f else 0.12f
            bars[head[0]] = smooth[0]
            head[0] = (head[0] + 1) % bars.size
            frame++
        }
    }

    Canvas(modifier) {
        // 读一次 frame，确保每帧重绘
        if (frame < 0) return@Canvas

        val n = bars.size
        val slot = size.width / n
        val barWidth = slot * 0.52f
        val brush = if (active && !paused) {
            Brush.horizontalGradient(listOf(Cyan, Blue))
        } else {
            Brush.horizontalGradient(listOf(Color(0xFF26375A), Color(0xFF1B2A4A)))
        }

        val idle = !active || paused
        val phase = frame.toFloat() * (if (paused) 0.03f else 0.07f)

        for (i in 0 until n) {
            val levelValue = bars[(head[0] + i) % n]
            // 空闲时给一条缓慢起伏的低波形，避免画面像一条虚线
            val idleValue = if (paused) {
                0.03f + 0.012f * (0.5f + 0.5f * sin(i * 0.6f))
            } else {
                0.05f + 0.04f * (0.5f + 0.5f * sin(i * 0.6f + phase))
            }
            val value = if (idle) idleValue else levelValue
            val minHeight = barWidth * 0.85f
            val barHeight = minHeight + value * (size.height - minHeight)
            val left = i * slot + (slot - barWidth) / 2f
            drawRoundRect(
                brush = brush,
                topLeft = Offset(left, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
