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
 * One cycle's bar: its length is the time that cycle spent on battery against the longest
 * cycle on show, and the split inside it is screen-on against screen-off.
 *
 * Two facts in one row of pixels — how long the phone lasted, and where the time went —
 * so the column of bars can be read as a shape before any of the numbers are.
 */
@Composable
fun CycleBar(
    lengthFraction: Float,
    screenOnFraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    screenOnColor: Color = MaterialTheme.colorScheme.primary,
    screenOffColor: Color = MaterialTheme.colorScheme.outline,
) {
    val animated by animateFloatAsState(
        // Never zero-width: a forty-minute cycle beside a twenty-hour one is a sliver,
        // and a sliver still says "this one was short" where nothing at all says nothing.
        targetValue = lengthFraction.coerceIn(MIN_LENGTH, 1f),
        animationSpec = ExpressiveSpring,
        label = "cycleBar",
    )
    val screenOn = screenOnFraction.coerceIn(0f, 1f)

    // No track behind the bar. A tinted remainder is a third tone in a drawing that has
    // only two things to say, and it reads as a third category rather than as the space
    // a shorter cycle did not need.
    Box(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Row(
            Modifier
                .fillMaxWidth(animated)
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

private const val MIN_LENGTH = 0.03f
private const val MIN_SEGMENT = 0.02f
