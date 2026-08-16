package com.syed.wattson.ui.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syed.wattson.ui.component.SectionCard
import com.syed.wattson.ui.component.StatTile
import com.syed.wattson.ui.component.chart.SplitBar
import com.syed.wattson.ui.model.BatteryUiModel
import com.syed.wattson.ui.theme.chartPalette
import com.syed.wattson.ui.util.formatDuration
import com.syed.wattson.ui.util.formatMah
import com.syed.wattson.ui.util.formatPercent
import com.syed.wattson.ui.util.formatMilliAmps

private const val EMPTY = "—"

/**
 * Everything measured since the last full charge, in one card.
 *
 * Screen split and the headline figures used to be two separate blocks — one carded, one
 * not — which broke the page rhythm for no reason. They describe the same window.
 */
@Composable
fun SessionSection(
    model: BatteryUiModel,
    modifier: Modifier = Modifier,
) {
    // Nothing to show on the basic tier: these all come from batterystats.
    if (model.timeOnBatteryMs <= 0L) return
    val palette = chartPalette()

    SectionCard(
        title = "This session",
        subtitle = "${model.screenOnCount} screen-ons since last charge",
        modifier = modifier,
    ) {
        SplitBar(
            fraction = model.screenOnFraction,
            leftColor = palette.screenOn,
            rightColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Figure(
                value = formatDuration(model.screenOnMs),
                label = "Screen on · ${formatPercent(model.screenOnFraction)}",
                valueColor = palette.screenOn,
                modifier = Modifier.weight(1f),
            )
            Figure(
                value = formatDuration(model.screenOffMs),
                label = "Screen off · ${formatPercent(1f - model.screenOnFraction)}",
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    value = formatDuration(model.timeOnBatteryMs),
                    label = "On battery",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = model.dischargeMah?.let { "${formatMah(it)} mAh" } ?: EMPTY,
                    label = "Discharged",
                    modifier = Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.tertiaryContainer,
                    onAccent = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Not "Total run time": this cycle starts at unplug, so run time and
                // on-battery time are the same clock and the tile said nothing twice.
                StatTile(
                    value = model.avgDrainMa?.let { formatMilliAmps(it) } ?: EMPTY,
                    label = "Avg drain",
                    modifier = Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.secondaryContainer,
                    onAccent = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                StatTile(
                    value = model.designCapacityMah?.let { "$it mAh" } ?: EMPTY,
                    label = "Full capacity",
                    modifier = Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.surfaceContainerHighest,
                    onAccent = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun Figure(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
