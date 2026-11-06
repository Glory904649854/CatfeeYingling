package com.catfee.yingling.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val YingLingColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF04120E),
    primaryContainer = Color(0xFF12312B),
    onPrimaryContainer = Mint,
    secondary = Blue,
    onSecondary = Color(0xFF04121A),
    background = Bg,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = BorderDark,
    error = Danger,
    onError = Color(0xFF200606),
)

private val YingLingShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun YingLingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = YingLingColors,
        typography = YingLingTypography,
        shapes = YingLingShapes,
        content = content,
    )
}
