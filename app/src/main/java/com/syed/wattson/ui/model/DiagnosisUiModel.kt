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
 * One unbroken stretch of a window, on battery or on a charger.
 *
 * A window picked off the clock can hold several. The report is built from the ones on
 * battery alone — a charge is not a drain and has no business in an answer about where
 * the charge went — and this list is what lets the card show which parts of the window
 * those were, and which part it left out.
 */
data class WindowStretch(
    val startMs: Long,
    val endMs: Long,
    val charging: Boolean,
    val startLevel: Int,
    val endLevel: Int,
) {
    val durationMs: Long get() = endMs - startMs
}

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
    /** Level at each end of the whole window, charge included. */
    val startLevel: Int,
    val endLevel: Int,
    /**
     * Everything below is measured over [onBatteryMs] only.
     *
     * A window that straddles a charge has the charge cut out of it before any of this is
     * counted: the percentage used is summed across the stretches on battery, and so are
     * the app lists, the screen split and the system states. Measured end to end instead,
     * a window holding an overnight charge reported 16% -> 75% and no drain at all, and
     * credited the hour on the charger to whatever app happened to be open during it.
     */
    val usedPercent: Int,
    /** From the coulomb counter, absent on a device that does not report one. */
    val usedMah: Int?,
    val mahPerHour: Double?,
    val percentPerHour: Double,
    val screenOnMs: Long,
    val screenOffMs: Long,
    /** The window with any charge in it taken out. Equal to [durationMs] when there is none. */
    val onBatteryMs: Long,
    /** The window in order, alternating between battery and charger. */
    val stretches: List<WindowStretch>,
    /** Ranked by time on top with the screen actually on. */
    val screenTime: List<AppSlice>,
    /** Ranked by time holding the phone awake with the screen off. */
    val keptAwake: List<AppSlice>,
    val system: List<FlagSlice>,
    /**
     * How much of the window the phone spent on a charger.
     *
     * Zero for anything chosen by level — those windows are bounded by a single run on
     * battery. A clock range can straddle one, and when it does the drain figures are net
     * movement rather than drain, which the card has to say out loud.
     */
    val chargingMs: Long = 0,
) {
    /** Share of the time on battery that the screen was on, 0f..1f. */
    val screenOnFraction: Float
        get() = if (onBatteryMs <= 0) 0f else screenOnMs.toFloat() / onBatteryMs

    /** True when a charge cuts this window into more than one stretch. */
    val split: Boolean get() = stretches.size > 1
}
