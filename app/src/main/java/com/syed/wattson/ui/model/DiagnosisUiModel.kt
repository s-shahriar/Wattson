package com.syed.wattson.ui.model

import com.syed.wattson.data.model.PackedSpans
import com.syed.wattson.data.model.SystemFlag

/** One app's share of a window, already divided. */
data class AppSlice(
    /** Android uid, for resolving a real name, or [PackedSpans.NO_UID]. */
    val uid: Int,
    /** The longest-held tag behind this share — a package, or a job or wakelock name. */
    val tag: String,
    val durationMs: Long,
    /** Share of whatever this list is measured against, 0f..1f. */
    val fraction: Float,
)

/** One device-wide state's share of a window. */
data class FlagSlice(
    val flag: SystemFlag,
    val durationMs: Long,
    val fraction: Float,
)

/**
 * Everything the Diagnose cards render for one window.
 *
 * Built by [WindowAnalyzer] on the confirm tap and by nothing else: there is no path that
 * produces one of these in the background, on a timer, or as a side effect of opening the
 * tab.
 */
data class WindowReport(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val startLevel: Int,
    val endLevel: Int,
    val usedPercent: Int,
    /** From the coulomb counter, absent on a device that does not report one. */
    val usedMah: Int?,
    val mahPerHour: Double?,
    val percentPerHour: Double,
    val screenOnMs: Long,
    val screenOffMs: Long,
    /** Ranked by time on top with the screen actually on. */
    val screenTime: List<AppSlice>,
    /** Ranked by time holding the phone awake with the screen off. */
    val keptAwake: List<AppSlice>,
    val system: List<FlagSlice>,
) {
    val screenOnFraction: Float
        get() = if (durationMs <= 0) 0f else screenOnMs.toFloat() / durationMs
}
