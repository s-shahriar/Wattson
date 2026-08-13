package com.syed.wattson.ui.component

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Expressive motion: low stiffness with a touch of bounce, so bars and gauges overshoot
 * slightly and settle rather than easing linearly into place. Shared by every animated
 * component so the whole screen moves with one personality.
 */
internal val ExpressiveSpring: AnimationSpec<Float> = spring(
    dampingRatio = 0.62f,
    stiffness = Spring.StiffnessMediumLow,
)
