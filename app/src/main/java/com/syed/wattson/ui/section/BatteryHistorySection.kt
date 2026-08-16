package com.syed.wattson.ui.section

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.syed.wattson.ui.component.MeterRow
import com.syed.wattson.ui.component.SectionCard
import com.syed.wattson.ui.component.chart.BatteryHistoryChart
import com.syed.wattson.ui.model.BatteryUiModel
import com.syed.wattson.ui.model.HistoryUi
import com.syed.wattson.ui.theme.chartPalette
import com.syed.wattson.ui.util.formatDuration
import com.syed.wattson.ui.util.formatMah
import com.syed.wattson.ui.util.formatSharePercent

/** Which window the chart is showing. */
private enum class HistoryRange(val label: String) {
    Cycle("This cycle"),
    Day("Last 24h"),
}

/**
 * Battery level over time, with the screen-on drain it cost.
 *
 * Defaults to the ongoing battery cycle, which matches the window every other figure on
 * the screen is measured over. The rolling 24-hour view is one tap away for when the
 * cycle is very short or very long.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryHistorySection(
    model: BatteryUiModel,
    modifier: Modifier = Modifier,
) {
    var range by remember { mutableStateOf(HistoryRange.Cycle) }

    // Fall back to whichever window actually has data.
    val selected: HistoryUi = when (range) {
        HistoryRange.Cycle -> model.historyCycle ?: model.historyDay
        HistoryRange.Day -> model.historyDay ?: model.historyCycle
    } ?: return

    val palette = chartPalette()

    SectionCard(
        title = "Battery history",
        subtitle = "${selected.spanLabel} · ${formatDuration(selected.durationMs)}",
        modifier = modifier,
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            HistoryRange.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = range == option,
                    onClick = { range = option },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = HistoryRange.entries.size,
                    ),
                    label = { Text(option.label) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        BatteryHistoryChart(
            columns = selected.columns,
            timeLabels = selected.timeLabels,
        )

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot("Charging", palette.charging)
            LegendDot("Discharging", palette.discharging)
            LegendDot("Screen on", palette.screenOn)
        }

        Spacer(Modifier.height(8.dp))

        model.drain?.let { drain ->
            // Amber matches the "Screen on" legend directly above; the screen-off row
            // takes a neutral tone rather than the charging green, which the legend has
            // already claimed for a different meaning.
            MeterRow(
                label = "Screen on drain",
                value = "${formatMah(drain.screenOnMah)} mAh",
                trailing = formatSharePercent(drain.screenOnShare),
                fraction = drain.screenOnShare,
                color = palette.screenOn,
            )
            MeterRow(
                label = "Screen off drain",
                value = "${formatMah(drain.screenOffMah)} mAh",
                trailing = formatSharePercent(drain.screenOffShare),
                fraction = drain.screenOffShare,
                color = MaterialTheme.colorScheme.outline,
            )
            if (drain.fromMeasurement) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Measured from the charge counter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
