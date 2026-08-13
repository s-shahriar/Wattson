package com.syed.wattson.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Expressive fallback palette — high-chroma violet / lime / coral triad, used when
// the device cannot supply a dynamic (Material You) scheme.
private val Violet80 = Color(0xFFD3BBFF)
private val Violet40 = Color(0xFF6D3FD4)
private val Lime80 = Color(0xFFC8E86A)
private val Lime40 = Color(0xFF4C6B00)
private val Coral80 = Color(0xFFFFB4A4)
private val Coral40 = Color(0xFF8E4B36)

val ExpressiveDark = darkColorScheme(
    primary = Violet80,
    onPrimary = Color(0xFF3A0D8E),
    primaryContainer = Color(0xFF5422B8),
    onPrimaryContainer = Color(0xFFEDDCFF),
    secondary = Lime80,
    onSecondary = Color(0xFF263500),
    secondaryContainer = Color(0xFF384D00),
    onSecondaryContainer = Color(0xFFE4FF92),
    tertiary = Coral80,
    onTertiary = Color(0xFF561F0F),
    tertiaryContainer = Color(0xFF723523),
    onTertiaryContainer = Color(0xFFFFDBD1),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF938F99),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

val ExpressiveLight = lightColorScheme(
    primary = Violet40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDDCFF),
    onPrimaryContainer = Color(0xFF260059),
    secondary = Lime40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4FF92),
    onSecondaryContainer = Color(0xFF141F00),
    tertiary = Coral40,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBD1),
    onTertiaryContainer = Color(0xFF3A0B01),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EB),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    outline = Color(0xFF79747E),
)

/**
 * Fixed semantic colours for the history chart.
 *
 * Deliberately NOT derived from the Material You scheme: dynamic colour can resolve
 * primary/secondary/tertiary into the same hue family, which made charging, discharging
 * and screen-on visually indistinguishable. Green/blue/amber stay separable under every
 * wallpaper, and the three series also sit in different rows of the chart so the
 * green-amber pairing remains readable for red-green colour blindness.
 */
data class ChartPalette(
    val charging: Color,
    val discharging: Color,
    val screenOn: Color,
    val idle: Color,
    val grid: Color,
)

val LightChartPalette = ChartPalette(
    charging = Color(0xFF15A34A),
    discharging = Color(0xFF2563EB),
    screenOn = Color(0xFFE08700),
    idle = Color(0xFFDDE3EA),
    grid = Color(0xFFBFC7D1),
)

val DarkChartPalette = ChartPalette(
    charging = Color(0xFF3DDC84),
    discharging = Color(0xFF64A5FF),
    screenOn = Color(0xFFFFC043),
    idle = Color(0xFF32363E),
    grid = Color(0xFF4A4F58),
)
