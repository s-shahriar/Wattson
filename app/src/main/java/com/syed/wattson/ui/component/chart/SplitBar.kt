package com.syed.wattson.ui.component.chart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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

/** Two-segment proportional bar, e.g. the screen-on vs screen-off split. */
@Composable
fun SplitBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 20.dp,
    leftColor: Color = MaterialTheme.colorScheme.primary,
    rightColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = ExpressiveSpring,
        label = "splitBar",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Segments below the threshold are dropped rather than drawn as slivers.
        if (animated > MIN_SEGMENT) {
            Segment(weight = animated, color = leftColor, height = height)
        }
        if (animated < 1f - MIN_SEGMENT) {
            Segment(weight = 1f - animated, color = rightColor, height = height)
        }
    }
}

@Composable
private fun RowScope.Segment(
    weight: Float,
    color: Color,
    height: Dp,
) {
    Box(
        Modifier
            .weight(weight)
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(color),
    )
}

private const val MIN_SEGMENT = 0.01f
