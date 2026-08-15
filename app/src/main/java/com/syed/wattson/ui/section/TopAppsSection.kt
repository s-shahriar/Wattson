package com.syed.wattson.ui.section

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.syed.wattson.ui.component.CompactAppRow
import com.syed.wattson.ui.component.SectionCard
import com.syed.wattson.ui.model.AppUi
import com.syed.wattson.ui.model.BatteryUiModel
import com.syed.wattson.ui.util.formatMah
import com.syed.wattson.ui.util.formatSharePercent

/** Icon bitmap size; the row renders at 24.dp so this covers xxhdpi without banding. */
private const val ICON_PX = 72

/** Dense ranked list of the heaviest apps. */
@Composable
fun TopAppsSection(
    model: BatteryUiModel,
    modifier: Modifier = Modifier,
) {
    if (model.topApps.isEmpty()) return

    val heaviest = model.topApps.first().mah.takeIf { it > 0.0 } ?: 1.0

    SectionCard(
        title = "Top apps",
        subtitle = "Share of ${formatMah(model.totalAppMah)} mAh Android could attribute",
        modifier = modifier,
    ) {
        Column {
            model.topApps.forEach { app ->
                CompactAppRow(
                    rank = app.rank,
                    name = app.label,
                    value = "${formatMah(app.mah)} mAh",
                    percent = formatSharePercent(app.share),
                    fraction = (app.mah / heaviest).toFloat(),
                    // Single hue fading down the ranking, matching By category.
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = (1f - (app.rank - 1) * RANK_FADE).coerceAtLeast(MIN_RANK_ALPHA),
                    ),
                    icon = app.iconContent(),
                )
            }
        }
    }
}

/**
 * Wraps the app's drawable into a composable, or null when the icon is unavailable.
 *
 * The bitmap is remembered against the drawable: without it the live 5-second tick
 * recomposed this list and re-rasterised every icon on each pass.
 */
private fun AppUi.iconContent(): (@Composable () -> Unit)? {
    val drawable = icon ?: return null
    return {
        val bitmap = remember(drawable) {
            drawable.toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Alpha lost per rank step. */
private const val RANK_FADE = 0.07f

/** Floor so the lowest-ranked bar stays visible. */
private const val MIN_RANK_ALPHA = 0.35f
