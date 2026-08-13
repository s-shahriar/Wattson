package com.syed.wattson.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive styling on the stable material3 1.4.0 line.
 *
 * Note: the `MaterialExpressiveTheme` wrapper and `MotionScheme` are still `internal` in
 * 1.4.0 — going public on them requires material3 1.5.x, which pulls Compose 1.12 and so
 * demands AGP 9.x + compileSdk 37. Rather than drag the project onto an alpha toolchain,
 * the expressive language is applied directly here: high-chroma tonal roles, the oversized
 * corner scale, and spring-driven motion on the individual components.
 */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun WattsonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> ExpressiveDark
        else -> ExpressiveLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        content = content,
    )
}

/** Chart colours for the current light/dark mode. */
@Composable
fun chartPalette(darkTheme: Boolean = isSystemInDarkTheme()): ChartPalette =
    if (darkTheme) DarkChartPalette else LightChartPalette
