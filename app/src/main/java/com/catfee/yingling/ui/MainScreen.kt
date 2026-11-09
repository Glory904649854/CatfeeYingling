package com.catfee.yingling.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.catfee.yingling.ui.theme.Bg
import com.catfee.yingling.ui.theme.Blue
import com.catfee.yingling.ui.theme.BorderDark
import com.catfee.yingling.ui.theme.Cyan
import com.catfee.yingling.ui.theme.TextTertiary

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { BottomBar(tab) { tab = it } },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
            ) {
                when (tab) {
                    0 -> HomeScreen(
                        vm,
                        onOpenApps = { tab = 1 },
                        onOpenSettings = { tab = 3 },
                    )
                    1 -> AppsScreen(vm)
                    2 -> HistoryScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun AmbientBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF071633), Bg)))

        val cyanRadius = size.width * 0.9f
        val cyanCenter = Offset(size.width * 0.92f, size.height * 0.02f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Cyan.copy(alpha = 0.10f), Color.Transparent),
                center = cyanCenter,
                radius = cyanRadius,
            ),
            radius = cyanRadius,
            center = cyanCenter,
        )

        val blueRadius = size.width * 0.8f
        val blueCenter = Offset(size.width * 0.02f, size.height * 0.55f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Blue.copy(alpha = 0.06f), Color.Transparent),
                center = blueCenter,
                radius = blueRadius,
            ),
            radius = blueRadius,
            center = blueCenter,
        )
    }
}

@Composable
private fun BottomBar(current: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        "实时" to NavGlyph.Live,
        "应用" to NavGlyph.Apps,
        "历史" to NavGlyph.History,
        "设置" to NavGlyph.Settings,
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF20A0F13)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BorderDark.copy(alpha = 0.5f)),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEachIndexed { index, item ->
                val selected = current == index
                Row(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) Cyan.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { onSelect(index) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavGlyphView(item.second, if (selected) Cyan else TextTertiary)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = item.first,
                        color = if (selected) Cyan else TextTertiary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

private enum class NavGlyph { Live, Apps, History, Settings }

@Composable
private fun NavGlyphView(glyph: NavGlyph, color: Color, size: Dp = 17.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when (glyph) {
            NavGlyph.Live -> {
                val heights = listOf(0.34f, 0.72f, 1f, 0.56f, 0.28f)
                heights.forEachIndexed { index, fraction ->
                    val x = w * (0.14f + index * 0.18f)
                    drawLine(
                        color = color,
                        start = Offset(x, h * (0.5f - fraction / 2f)),
                        end = Offset(x, h * (0.5f + fraction / 2f)),
                        strokeWidth = w * 0.09f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            NavGlyph.Apps -> {
                val r = w * 0.155f
                listOf(
                    0.27f to 0.27f,
                    0.73f to 0.27f,
                    0.27f to 0.73f,
                    0.73f to 0.73f,
                ).forEach { (x, y) ->
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(w * x - r, h * y - r),
                        size = Size(r * 2, r * 2),
                        cornerRadius = CornerRadius(r * 0.5f, r * 0.5f),
                    )
                }
            }

            NavGlyph.History -> {
                drawCircle(
                    color = color,
                    radius = w * 0.34f,
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = Stroke(width = w * 0.075f),
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.5f),
                    end = Offset(w * 0.5f, h * 0.28f),
                    strokeWidth = w * 0.075f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.5f),
                    end = Offset(w * 0.65f, h * 0.57f),
                    strokeWidth = w * 0.075f,
                    cap = StrokeCap.Round,
                )
            }

            NavGlyph.Settings -> {
                listOf(0.3f, 0.5f, 0.7f).forEachIndexed { index, y ->
                    drawLine(
                        color = color,
                        start = Offset(w * 0.12f, h * y),
                        end = Offset(w * 0.88f, h * y),
                        strokeWidth = w * 0.07f,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = color,
                        radius = w * 0.09f,
                        center = Offset(w * (if (index == 1) 0.62f else 0.36f), h * y),
                    )
                }
            }
        }
    }
}
