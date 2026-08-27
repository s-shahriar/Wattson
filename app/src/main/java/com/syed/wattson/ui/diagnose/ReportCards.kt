package com.syed.wattson.ui.diagnose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syed.wattson.data.model.SystemFlag
import com.syed.wattson.ui.component.SectionCard
import com.syed.wattson.ui.component.StatTile
import com.syed.wattson.ui.component.chart.CycleBar
import com.syed.wattson.ui.model.AppSlice
import com.syed.wattson.ui.model.WindowReport
import com.syed.wattson.ui.theme.chartPalette
import com.syed.wattson.ui.util.formatDuration
import com.syed.wattson.ui.util.formatSpanCompact
import kotlin.math.roundToInt

/** The cards under the picker, in the order the question is usually asked. */
fun LazyListScope.reportCards(report: WindowReport, labels: AppLabels) {
    item(key = "drain") { DrainCard(report) }
    item(key = "screen-time") {
        AppListCard(
            title = "Screen time by app",
            subtitle = "of the ${formatDuration(report.screenOnMs)} the screen was on",
            slices = report.screenTime,
            labels = labels,
            empty = "The screen was never on in this window.",
        )
    }
    item(key = "kept-awake") {
        AppListCard(
            title = "Kept awake",
            subtitle = "of the ${formatDuration(report.screenOffMs)} the screen was off",
            slices = report.keptAwake,
            labels = labels,
            empty = "Nothing held the phone awake — it dozed the whole time.",
        )
    }
    item(key = "system") { SystemCard(report) }
}

@Composable
private fun DrainCard(report: WindowReport) {
    val palette = chartPalette()
    SectionCard(
        title = "Drain",
        subtitle = "over ${formatDuration(report.durationMs)}",
    ) {
        // The window leads the card. A level range is answered by whichever run through
        // it happened last, which can be days ago and a different battery to the one in
        // the phone right now — and reading these figures without knowing that is reading
        // the wrong answer to the right question.
        WindowHeading(report)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                value = report.usedMah?.let { "$it mAh" } ?: "${report.usedPercent}%",
                label = "Used",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = report.mahPerHour?.let { "${it.roundToInt()} mAh/h" }
                    ?: "${(report.percentPerHour * 10).roundToInt() / 10.0} %/h",
                label = "Rate",
                modifier = Modifier.weight(1f),
                accent = MaterialTheme.colorScheme.secondaryContainer,
                onAccent = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${report.startLevel}% → ${report.endLevel}% · " +
                "${(report.percentPerHour * 10).roundToInt() / 10.0} %/h",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        CycleBar(
            screenOnFraction = report.screenOnFraction,
            screenOnColor = palette.screenOn,
            screenOffColor = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${formatSpanCompact(report.screenOnMs)} screen on · " +
                "${formatSpanCompact(report.screenOffMs)} off",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppListCard(
    title: String,
    subtitle: String,
    slices: List<AppSlice>,
    labels: AppLabels,
    empty: String,
) {
    SectionCard(title = title, subtitle = subtitle) {
        if (slices.isEmpty()) {
            Text(
                text = empty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        slices.forEach { slice -> SliceRow(slice, labels) }
    }
}

@Composable
private fun SliceRow(slice: AppSlice, labels: AppLabels) {
    val name = labels.nameFor(slice.uid, slice.tag)
    // The tag is worth printing only when it says something the name does not — a package
    // that resolved to its own label repeats itself.
    val reason = labels.reasonFor(slice.uid, slice.tag)

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (reason != null) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatSpanCompact(slice.durationMs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${(slice.fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        ShareBar(slice.fraction)
    }
}


@Composable
private fun ShareBar(fraction: Float, height: androidx.compose.ui.unit.Dp = 6.dp) {
    val filled = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (filled > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(filled)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun SystemCard(report: WindowReport) {
    SectionCard(
        title = "Radio & system",
        subtitle = "share of the window each state was in force",
    ) {
        if (report.system.isEmpty()) {
            Text(
                text = "The phone was idle throughout.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        report.system.forEach { slice ->
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = slice.flag.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatSpanCompact(slice.durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${(slice.fraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = tintFor(slice.flag, slice.fraction),
                )
            }
            Spacer(Modifier.height(4.dp))
            ShareBar(slice.fraction, height = 4.dp)
        }
    }
}

/**
 * A share worth noticing is coloured; the rest are not.
 *
 * The radio straining for three-quarters of an hour is the answer to where that hour went.
 * The same figure for the screen is a person using their phone.
 */
@Composable
private fun tintFor(flag: SystemFlag, fraction: Float): Color = when {
    flag != SystemFlag.CELLULAR_HIGH_TX && flag != SystemFlag.CPU_RUNNING -> {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    fraction >= NOTABLE_SHARE -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private const val NOTABLE_SHARE = 0.5f
