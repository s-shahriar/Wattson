package com.syed.wattson.ui.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syed.wattson.ui.component.SectionCard
import com.syed.wattson.ui.component.chart.CycleBar
import com.syed.wattson.ui.model.CycleUi
import com.syed.wattson.ui.model.BatteryUiModel
import com.syed.wattson.ui.theme.chartPalette
import com.syed.wattson.ui.util.formatSpanCompact

/** Column weights: the timestamp needs the most room, the percentage the least. */
private const val LABEL_WEIGHT = 1.75f
private const val FIGURE_WEIGHT = 1f
private const val USED_WEIGHT = 0.8f

/** Keeps the right-aligned columns from running into each other. */
private val COLUMN_GUTTER = 6.dp

/**
 * The runs on battery that came before this one.
 *
 * Sits directly under "This session" because it answers the question that card provokes:
 * whether today is normal. Each row is one unplugged run — the same quantities the
 * session card reports, for a cycle that has already finished.
 */
@Composable
fun CycleHistorySection(
    model: BatteryUiModel,
    modifier: Modifier = Modifier,
) {
    val cycles = model.cycles
    if (cycles.isEmpty()) return

    val palette = chartPalette()
    val screenOff = MaterialTheme.colorScheme.outline

    SectionCard(
        title = if (cycles.size == 1) "Last cycle" else "Last ${cycles.size} cycles",
        subtitle = "one run on battery per row · orange is screen on",
        modifier = modifier,
    ) {
        // "screen on" and "screen off" do not fit a column this narrow, so the heading
        // is halved and the missing word carried by colour — the same two tones the
        // figures and the bar under them use.
        Row(Modifier.fillMaxWidth()) {
            HeaderCell("screen", LABEL_WEIGHT)
            HeaderCell("on", FIGURE_WEIGHT, color = palette.screenOn)
            HeaderCell("off", FIGURE_WEIGHT, color = screenOff)
            HeaderCell("battery", FIGURE_WEIGHT)
            HeaderCell("used", USED_WEIGHT)
        }
        cycles.forEach { cycle ->
            CycleRow(
                cycle = cycle,
                screenOnColor = palette.screenOn,
                screenOffColor = screenOff,
            )
        }
    }
}

@Composable
private fun CycleRow(
    cycle: CycleUi,
    screenOnColor: Color,
    screenOffColor: Color,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = cycle.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(LABEL_WEIGHT),
            )
            // The two durations carry the same colours as the bar beneath them, so the
            // split can be read off either one.
            FigureCell(formatSpanCompact(cycle.screenOnMs), screenOnColor, FIGURE_WEIGHT)
            FigureCell(formatSpanCompact(cycle.screenOffMs), screenOffColor, FIGURE_WEIGHT)
            FigureCell(
                text = formatSpanCompact(cycle.onBatteryMs),
                color = MaterialTheme.colorScheme.onSurface,
                weight = FIGURE_WEIGHT,
            )
            FigureCell(
                text = "${cycle.usedPercent}%",
                color = MaterialTheme.colorScheme.onSurface,
                weight = USED_WEIGHT,
                bold = true,
            )
        }
        Spacer(Modifier.height(6.dp))
        CycleBar(
            screenOnFraction = cycle.screenOnFraction,
            screenOnColor = screenOnColor,
            screenOffColor = screenOffColor,
        )
    }
}

@Composable
private fun RowScope.HeaderCell(
    text: String,
    weight: Float,
    align: TextAlign = TextAlign.End,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight).padding(start = COLUMN_GUTTER),
    )
}

@Composable
private fun RowScope.FigureCell(
    text: String,
    color: Color,
    weight: Float,
    bold: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.weight(weight).padding(start = COLUMN_GUTTER),
    )
}
