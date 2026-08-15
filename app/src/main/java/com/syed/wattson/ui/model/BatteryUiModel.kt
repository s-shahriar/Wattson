package com.syed.wattson.ui.model

import android.graphics.drawable.Drawable

/** One app's contribution inside a power category. */
data class ContributorUi(
    val label: String,
    val mah: Double,
)

/** A power category, already resolved to its display share, colour and contributors. */
data class CategoryUi(
    val key: String,
    val label: String,
    val mah: Double,
    /** Share of all category power, 0f..1f. */
    val share: Float,
    /** Share of the largest category, 0f..1f — drives bar width. */
    val relativeToMax: Float,
    val durationMs: Long?,
    val contributors: List<ContributorUi>,
)

/** A row in the Top apps list. */
data class AppUi(
    val rank: Int,
    val label: String,
    val mah: Double,
    /** Share of all app-attributed power, 0f..1f. */
    val share: Float,
    val icon: Drawable?,
)

/**
 * View-ready projection of a [com.syed.wattson.data.model.BatteryReport].
 *
 * Every percentage, ratio and colour is resolved here so the composables only lay out
 * values they are handed — no arithmetic in the recomposition path.
 */
data class BatteryUiModel(
    /** Which accounting sources were available when this was built. */
    val tier: com.syed.wattson.data.DataTier,

    // Live battery
    val levelPercent: Int,
    val status: String,
    val health: String,
    val temperatureC: Double,
    val chargeCounterMah: Int?,

    // Window
    val startClock: String?,
    val timeOnBatteryMs: Long,
    val totalRunTimeMs: Long,
    /** Mean current over the whole on-battery window, from measured discharge. */
    val avgDrainMa: Double?,
    val dischargeMah: Int?,
    val designCapacityMah: Int?,

    // Screen
    val screenOnMs: Long,
    val screenOffMs: Long,
    val screenOnFraction: Float,
    val screenOnCount: Int,

    // Breakdown
    val categories: List<CategoryUi>,
    val totalCategoryMah: Double,
    val topApps: List<AppUi>,
    val totalAppMah: Double,
    /** How much of real drain the per-app numbers actually cover. */
    val attribution: AttributionUi? = null,

    // Drain + charging
    val drain: DrainUi?,
    val charging: ChargingUi,
    /** Since the last stats reset — the default view. */
    val historyCycle: HistoryUi?,
    /** Rolling 24 hours, available via the toggle. */
    val historyDay: HistoryUi?,
)

/** Drain split between screen-on and screen-off, with the rate each ran at. */
data class DrainUi(
    val screenOnMah: Double,
    val screenOffMah: Double,
    val screenOnShare: Float,
    val screenOffShare: Float,
    /** Average milliamps drawn while the screen was on, or null if the window was empty. */
    val screenOnRateMa: Double?,
    val screenOffRateMa: Double?,
    val totalOnBatteryMah: Double,
    /** Power consumed while plugged in — never came out of the cell. */
    val chargingUsageMah: Double,
    /** True when these came from the coulomb counter rather than the power-profile model. */
    val fromMeasurement: Boolean = false,
) {
    /** How many times harder the screen-on state drew, e.g. 22× idle. */
    val rateMultiple: Double?
        get() {
            val on = screenOnRateMa ?: return null
            val off = screenOffRateMa?.takeIf { it > 0.0 } ?: return null
            return on / off
        }
}

/**
 * How complete the per-app and per-category attribution is.
 *
 * Android only breaks down the power it can model. Where the kernel does not report
 * per-UID CPU time, processor draw is absent from every app row and the modelled total
 * lands nowhere near the charge the cell actually gave up — so the breakdown has to say
 * what it does and does not cover instead of presenting itself as a full account.
 */
data class AttributionUi(
    /** Sum of every modelled bucket. */
    val attributedMah: Double,
    /** Coulomb-counter total, when the dump reported one. */
    val measuredTotalMah: Double?,
    /** False when no app carries a CPU term, i.e. processor draw is untracked. */
    val cpuTracked: Boolean,
) {
    /** Fraction of real drain the breakdown explains, 0f..1f, or null if unknowable. */
    val coverage: Float?
        get() {
            val total = measuredTotalMah?.takeIf { it > 0.0 } ?: return null
            return (attributedMah / total).coerceIn(0.0, 1.0).toFloat()
        }

    val unattributedMah: Double?
        get() = measuredTotalMah?.let { (it - attributedMah).coerceAtLeast(0.0) }

    /** Worth warning about only when the gap is large enough to mislead. */
    val isPartial: Boolean get() = (coverage ?: 1f) < 0.75f
}

/** Live charging state and cell health. */
data class ChargingUi(
    val status: String,
    val isCharging: Boolean,
    val currentMa: Int?,
    val voltageVolts: Double?,
    /** Present capacity as a fraction of design, 0f..1f. */
    val healthFraction: Float?,
    val chargeFullMah: Int?,
    val designCapacityMah: Int?,
    val cycleCount: Int?,
    /** Time left at the current rate: to full when charging, to empty when discharging. */
    val hoursRemaining: Double?,
)

/** One column of the history chart: a time slice with its level and what was happening. */
data class HistoryColumn(
    /** Battery percentage at the end of this slice, 0..100. */
    val level: Int,
    val charging: Boolean,
    val screenOn: Boolean,
    /** False when no sample fell in this slice (device off / no data). */
    val hasData: Boolean,
    /** Wall-clock label for this slice, e.g. "3:24 PM" — shown when inspected. */
    val timeLabel: String,
)

/** Battery level over time, bucketed into fixed columns ready to draw. */
data class HistoryUi(
    val columns: List<HistoryColumn>,
    /** Clock labels spread across the x-axis, oldest first. */
    val timeLabels: List<String>,
    /** Short name for this window, e.g. "This cycle". */
    val spanLabel: String,
    val durationMs: Long,
)
