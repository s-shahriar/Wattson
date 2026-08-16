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
import com.syed.wattson.ui.component.MeterRow
import com.syed.wattson.ui.component.SectionCard
import com.syed.wattson.ui.component.StatTile
import com.syed.wattson.ui.component.chart.BatteryRing
import com.syed.wattson.ui.model.BatteryUiModel
import com.syed.wattson.ui.theme.chartPalette
import com.syed.wattson.ui.util.formatHours
import com.syed.wattson.ui.util.formatSharePercent

private const val EMPTY = "—"

/**
 * The single live card: charge level, flow rate and cell health.
 *
 * Previously split across "Battery now" and "Charging", which repeated the status string
 * three times between them and pushed everything else a screen further down.
 */
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
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BatteryRing(
                fraction = model.levelPercent / 100f,
                label = "${model.levelPercent}%",
                caption = charging.status,
                ringColor = accent,
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                value = charging.currentMa?.let { "$it mA" } ?: EMPTY,
                label = if (charging.isCharging) "Flowing in" else "Flowing out",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = charging.hoursRemaining?.let(::formatHours) ?: EMPTY,
                label = if (charging.isCharging) "Until full" else "Until empty",
                modifier = Modifier.weight(1f),
                accent = MaterialTheme.colorScheme.secondaryContainer,
                onAccent = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoChip("${model.temperatureC} °C")
            charging.voltageVolts?.let { InfoChip(String.format("%.2f V", it)) }
            InfoChip(
                text = model.health,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            charging.cycleCount?.let { InfoChip("$it cycles") }
        }

        charging.healthFraction?.let { health ->
            Spacer(Modifier.height(18.dp))
            // Both halves or neither. The percentage can come from the platform's
            // state-of-health alone, while the design capacity behind it is a rooted
            // sysfs read — so on an unrooted Android 14 device the old unconditional
            // line rendered as "3979 of 0 mAh design" underneath a valid percentage.
            val full = charging.chargeFullMah
            val design = charging.designCapacityMah?.takeIf { it > 0 }
            MeterRow(
                label = "Capacity health",
                value = formatSharePercent(health),
                fraction = health,
                color = MaterialTheme.colorScheme.primary,
                detail = if (full != null && design != null) {
                    "$full of $design mAh design"
                } else {
                    null
                },
            )
        }
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
