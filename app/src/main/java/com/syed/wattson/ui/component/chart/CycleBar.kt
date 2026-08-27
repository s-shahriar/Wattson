package com.syed.wattson.ui.component.chart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syed.wattson.ui.component.ExpressiveSpring

/**
 * One cycle's bar: the whole width is that cycle, split into screen-on and screen-off.
 *
 * The bar used to be scaled to the cycle's length as well, so a short cycle stopped part
 * way across and left the rest of the row empty. Nobody read that emptiness as "this
 * cycle was shorter" — it read as a third state the legend had forgotten to name, and it
 * was asked about twice. It was redundant besides: the row already prints the hours on
 * battery a column to the left. Every bar now runs the full width, which is what makes
 * the orange lengths mean the same thing on every row: a third of one bar is a third of
 * its cycle, whether that cycle ran three hours or twenty.
 */
@Composable
fun CycleBar(
    screenOnFraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    screenOnColor: Color = MaterialTheme.colorScheme.primary,
    screenOffColor: Color = MaterialTheme.colorScheme.outline,
) {
    val screenOn by animateFloatAsState(
        targetValue = screenOnFraction.coerceIn(0f, 1f),
        animationSpec = ExpressiveSpring,
        label = "cycleBar",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(RoundedCornerShape(percent = 50)),
        ) {
            // Below the threshold a segment is thinner than the rounding on its own
            // corners, so it is dropped rather than drawn as a smear.
            if (screenOn > MIN_SEGMENT) Segment(screenOn, screenOnColor)
            if (screenOn < 1f - MIN_SEGMENT) Segment(1f - screenOn, screenOffColor)
        }
    }
}

@Composable
private fun RowScope.Segment(weight: Float, color: Color) {
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .background(color),
    )
}

private const val MIN_SEGMENT = 0.02f
