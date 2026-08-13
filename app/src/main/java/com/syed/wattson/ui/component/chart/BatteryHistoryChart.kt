package com.syed.wattson.ui.component.chart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syed.wattson.ui.component.ExpressiveSpring
import com.syed.wattson.ui.model.HistoryColumn
import com.syed.wattson.ui.theme.chartPalette

/** Sentinel for "nothing selected". */
private const val NO_SELECTION = -1

/**
 * Battery level over time.
 *
 * One column per time slice, height proportional to charge level and coloured by whether
 * the device was charging. A strip underneath marks slices where the screen was on, so
 * charge behaviour and usage read off a single timeline.
 *
 * Touch or drag across the plot to inspect a slice; the readout above the chart reports
 * that slice's time, level and state.
 */
@Composable
fun BatteryHistoryChart(
    columns: List<HistoryColumn>,
    timeLabels: List<String>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 150.dp,
    activityHeight: Dp = 16.dp,
) {
    if (columns.isEmpty()) return

    val palette = chartPalette()

    // animateFloatAsState seeds its initial value from the first target, so animating
    // toward a constant 1f never moved. Flip a flag after first composition instead.
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = ExpressiveSpring,
        label = "historyReveal",
    )

    var selected by remember(columns.size) { mutableIntStateOf(NO_SELECTION) }
    val selectedColumn = columns.getOrNull(selected)

    // Drag and tap both map an x position onto a column index.
    val inspect = remember(columns.size) {
        Modifier
            .pointerInput(columns.size) {
                detectTapGestures { offset ->
                    selected = offset.x.toColumnIndex(size.width, columns.size)
                }
            }
            .pointerInput(columns.size) {
                // Horizontal-only: vertical drags still scroll the list underneath.
                detectHorizontalDragGestures(
                    onDragEnd = { },
                    onDragCancel = { selected = NO_SELECTION },
                ) { change, _ ->
                    selected = change.position.x.toColumnIndex(size.width, columns.size)
                }
            }
    }

    Column(modifier.fillMaxWidth()) {
        InspectionReadout(column = selectedColumn, palette.charging, palette.discharging)

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth()) {
            Canvas(
                Modifier
                    .weight(1f)
                    .height(chartHeight)
                    .then(inspect),
            ) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 10f))

                LEVEL_LINES.forEach { level ->
                    val y = size.height * (1f - level / 100f)
                    drawLine(
                        color = palette.grid,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                }

                val slotWidth = size.width / columns.size
                val barWidth = (slotWidth * BAR_FILL).coerceAtLeast(1f)

                columns.forEachIndexed { index, column ->
                    val x = index * slotWidth + (slotWidth - barWidth) / 2
                    val levelFraction = (column.level / 100f).coerceIn(0f, 1f) * progress
                    val barHeight = (size.height * levelFraction).coerceAtLeast(1f)
                    val base = when {
                        !column.hasData -> palette.idle
                        column.charging -> palette.charging
                        else -> palette.discharging
                    }
                    // Unselected columns dim slightly once something is selected.
                    val color = if (selected == NO_SELECTION || selected == index) {
                        base
                    } else {
                        base.copy(alpha = DIMMED_ALPHA)
                    }

                    drawRect(
                        color = color,
                        topLeft = Offset(x, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                    )
                }

                // Selection marker spanning the full plot height.
                if (selected != NO_SELECTION) {
                    val markerX = (selected + 0.5f) * slotWidth
                    drawLine(
                        color = palette.screenOn,
                        start = Offset(markerX, 0f),
                        end = Offset(markerX, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }

                drawLine(
                    color = palette.grid,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Absolutely positioned: the levels are unevenly spaced, so even
            // distribution would drift away from the lines they label.
            Box(
                Modifier
                    .height(chartHeight)
                    .width(LABEL_GUTTER)
                    .padding(start = 8.dp),
            ) {
                LEVEL_LINES.forEach { level ->
                    val offsetY = (chartHeight * (1f - level / 100f)) - LABEL_HALF_HEIGHT
                    AxisLabel(
                        text = "$level%",
                        modifier = Modifier.offset(
                            y = offsetY.coerceIn(0.dp, chartHeight - LABEL_HALF_HEIGHT * 2),
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth()) {
            Canvas(
                Modifier
                    .weight(1f)
                    .height(activityHeight)
                    .then(inspect),
            ) {
                drawRect(color = palette.idle, size = size)
                val slotWidth = size.width / columns.size
                columns.forEachIndexed { index, column ->
                    if (column.screenOn) {
                        drawRect(
                            color = palette.screenOn,
                            topLeft = Offset(index * slotWidth, 0f),
                            size = Size(slotWidth.coerceAtLeast(1f), size.height),
                        )
                    }
                }
            }
            Spacer(Modifier.width(LABEL_GUTTER))
        }

        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            timeLabels.forEach { AxisLabel(it) }
        }
    }
}

/**
 * Fixed-height readout above the plot so selecting a column never shifts the layout.
 * Falls back to a hint when nothing is selected.
 */
@Composable
private fun InspectionReadout(
    column: HistoryColumn?,
    chargingColor: Color,
    dischargingColor: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (column == null) {
            Text(
                text = "Tap or drag the chart to inspect",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }

        Text(
            text = column.timeLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Dot()
        Text(
            text = "${column.level}%",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Dot()
        Text(
            text = when {
                !column.hasData -> "No data"
                column.charging -> "Charging"
                else -> "Discharging"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (column.charging) chargingColor else dischargingColor,
        )
        if (column.screenOn) {
            Dot()
            Text(
                text = "Screen on",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Dot() {
    Text(
        text = " · ",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun AxisLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Maps a touch x-coordinate onto a column index, clamped to the valid range. */
private fun Float.toColumnIndex(widthPx: Int, count: Int): Int {
    if (widthPx <= 0 || count <= 0) return NO_SELECTION
    val ratio = (this / widthPx).coerceIn(0f, 0.999f)
    return (ratio * count).toInt().coerceIn(0, count - 1)
}

/** Percentage guides drawn across the plot, high to low. */
private val LEVEL_LINES = listOf(100, 70, 50, 30, 10)

/** Width reserved for the percentage scale on the right. */
private val LABEL_GUTTER = 46.dp

/** Half a label's line height, used to centre it on its gridline. */
private val LABEL_HALF_HEIGHT = 7.dp

/** Fraction of each slot the bar occupies, leaving a hairline gap between columns. */
private const val BAR_FILL = 0.82f

/** Opacity applied to columns other than the selected one. */
private const val DIMMED_ALPHA = 0.35f
