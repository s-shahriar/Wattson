package com.syed.wattson.ui.model

import android.graphics.drawable.Drawable

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
