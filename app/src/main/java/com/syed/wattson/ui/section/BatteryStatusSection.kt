package com.syed.wattson.ui.section

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.syed.wattson.ui.component.InfoChip
import com.syed.wattson.ui.component.SectionCard
import com.syed.wattson.ui.component.StatTile
import com.syed.wattson.ui.component.chart.BatteryRing
import com.syed.wattson.ui.model.BatteryUiModel
import com.syed.wattson.ui.theme.chartPalette
import com.syed.wattson.ui.util.formatHours

private const val EMPTY = "—"

/**
 * The single live card: charge level, flow rate and the cell's vital signs.
 *
 * Previously split across "Battery now" and "Charging", which repeated the status string
 * three times between them and pushed everything else a screen further down.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatteryStatusSection(
    model: BatteryUiModel,
    modifier: Modifier = Modifier,
) {
    val charging = model.charging
    val palette = chartPalette()
    val accent = if (charging.isCharging) palette.charging else palette.discharging

    SectionCard(
        title = if (charging.isCharging) "Charging" else "On battery",
        subtitle = null,
        modifier = modifier,
        trailing = { LiveDot() },
    ) {
        // Ring on the left rather than centred over the whole card: side by side, the
        // gauge and the two figures it explains occupy the height of the gauge alone.
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryRing(
                fraction = model.levelPercent / 100f,
                label = "${model.levelPercent}%",
                caption = charging.status,
                ringColor = accent,
                diameter = 124.dp,
                strokeWidth = 14.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile(
                    value = charging.currentMa?.let { "$it mA" } ?: EMPTY,
                    label = if (charging.isCharging) "Flowing in" else "Flowing out",
                    modifier = Modifier.fillMaxWidth(),
                )
                StatTile(
                    value = charging.hoursRemaining?.let(::formatHours) ?: EMPTY,
                    label = if (charging.isCharging) "Until full" else "Until empty",
                    modifier = Modifier.fillMaxWidth(),
                    accent = MaterialTheme.colorScheme.secondaryContainer,
                    onAccent = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // FlowRow, not Row: four chips overflow 360dp and the fourth used to be clipped
        // off the edge of the card.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoChip("${model.temperatureC} °C")
            charging.voltageVolts?.let { InfoChip(String.format("%.2f V", it)) }
            InfoChip(
                text = model.health,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            charging.cycleCount?.let { InfoChip("$it cycles") }
        }
        // Capacity health used to close this card. It is a property of the cell that
        // moves a percent a year, and it was costing a fifth of the screen to say so.
    }
}

/** Slowly pulsing dot: signals that this card is the one refreshing on its own. */
@Composable
private fun LiveDot() {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveAlpha",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .alpha(alpha)
                .size(7.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(chartPalette().charging),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
