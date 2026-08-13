package com.syed.wattson.data.model

import android.graphics.drawable.Drawable

/** A single named power draw reported by batterystats, e.g. "screen" at 765 mAh. */
data class PowerBucket(
    val name: String,
    val mah: Double,
    val durationMs: Long? = null,
)

/** One bin of the screen-brightness histogram. */
data class BrightnessBin(
    val name: String,
    val durationMs: Long,
    val percent: Double,
)

/** Per-app battery attribution, resolved to a human label + icon where possible. */
data class AppUsage(
    val rawUid: String,
    val uid: Int?,
    val mah: Double,
    val packageName: String?,
    val label: String,
    val icon: Drawable?,
    val buckets: List<PowerBucket>,
) {
    /** How much of this app's draw a given category accounts for, or null if untracked. */
    fun mahFor(bucketName: String): Double? =
        buckets.firstOrNull { it.name == bucketName }?.mah
}

/** Everything parsed out of `dumpsys batterystats --charged`. */
data class BatteryStats(
    val startClock: String?,
    val timeOnBatteryMs: Long,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val screenOnCount: Int,
    val totalRunTimeMs: Long,
    val dischargeMah: Int?,
    val designCapacityMah: Int?,
    val globalBuckets: List<PowerBucket>,
    val brightness: List<BrightnessBin>,
    val apps: List<AppUsage>,
    val powerByState: PowerByState,
) {
    /** Screen-on time while actually on battery — the denominator for the on-drain rate. */
    val screenOnOnBatteryMs: Long
        get() = (timeOnBatteryMs - screenOffMs).coerceAtLeast(0L)

    /** Screen-on share of the measured window, 0f..1f. */
    val screenOnFraction: Float
        get() {
            val total = screenOnMs + screenOffMs
            return if (total <= 0L) 0f else (screenOnMs.toDouble() / total).toFloat()
        }
}

/** Live battery state from `dumpsys battery`. */
data class BatteryNow(
    val levelPercent: Int,
    val status: String,
    val health: String,
    val temperatureC: Double,
    val chargeCounterMah: Int?,
    val isCharging: Boolean,
)

/** The complete domain snapshot handed to the UI layer for mapping. */
data class BatteryReport(
    /** Which accounting sources were available for this load. */
    val tier: com.syed.wattson.data.DataTier,
    val now: BatteryNow,
    /** Null when the tier cannot read historical accounting at all. */
    val stats: BatteryStats?,
    val charging: ChargingInfo,
    val history: List<HistoryPoint>,
)

/** Raised when `su` is missing or refuses to elevate. */
class RootUnavailableException(message: String) : Exception(message)

/** Raised when dumpsys ran but produced nothing usable. */
class StatsUnavailableException(message: String) : Exception(message)

/**
 * Power attributed to each of the four states batterystats tracks separately.
 *
 * The on-battery pair is what actually drained the cell; the charging pair is power the
 * device consumed while plugged in, which never shows up as discharge.
 */
data class PowerByState(
    val onBatteryScreenOnMah: Double,
    val onBatteryScreenOffMah: Double,
    val chargingScreenOnMah: Double,
    val chargingScreenOffMah: Double,
) {
    val totalOnBatteryMah: Double get() = onBatteryScreenOnMah + onBatteryScreenOffMah
    val totalChargingMah: Double get() = chargingScreenOnMah + chargingScreenOffMah

    companion object {
        val EMPTY = PowerByState(0.0, 0.0, 0.0, 0.0)
    }
}

/** Live charging/health figures read from `/sys/class/power_supply/battery`. */
data class ChargingInfo(
    /** Signed microamps as reported; sign convention is device-specific. */
    val currentNowMicroAmps: Int?,
    val voltageMicroVolts: Int?,
    /** Present full-charge capacity in mAh (degrades with age). */
    val chargeFullMah: Int?,
    /** Factory design capacity in mAh. */
    val chargeFullDesignMah: Int?,
    val chargeCounterMah: Int?,
    val cycleCount: Int?,
    /** Platform-reported remaining capacity as a percent of design (API 34+). */
    val stateOfHealthPercent: Int? = null,
) {
    /** Absolute charge/discharge rate in mA, or null when unavailable. */
    val currentMilliAmps: Int? get() = currentNowMicroAmps?.let { kotlin.math.abs(it) / 1000 }

    val voltageVolts: Double? get() = voltageMicroVolts?.let { it / 1_000_000.0 }

    /**
     * Remaining capacity health as a 0f..1f ratio of design.
     *
     * Prefers the exact sysfs pair when a rooted read supplied it, falling back to the
     * platform's state-of-health percentage, which needs no privileges at all.
     */
    val healthFraction: Float?
        get() {
            val full = chargeFullMah
            val design = chargeFullDesignMah?.takeIf { it > 0 }
            if (full != null && design != null) {
                return (full.toFloat() / design).coerceIn(0f, 1f)
            }
            return stateOfHealthPercent?.let { (it / 100f).coerceIn(0f, 1f) }
        }

    /** Hours until full at the present rate, or null if not meaningfully charging. */
    fun hoursToFull(): Double? {
        val full = chargeFullMah ?: return null
        val counter = chargeCounterMah ?: return null
        val rate = currentMilliAmps?.takeIf { it > MIN_MEANINGFUL_MA } ?: return null
        val remaining = (full - counter).takeIf { it > 0 } ?: return null
        return remaining.toDouble() / rate
    }

    private companion object {
        const val MIN_MEANINGFUL_MA = 5
    }
}

/**
 * The subset of state that changes minute to minute.
 *
 * Polled on its own so the foreground refresh never has to re-run the expensive
 * batterystats dump just to move a percentage.
 */
data class LiveSnapshot(
    val now: BatteryNow,
    val charging: ChargingInfo,
)

/** One sample from the battery history: level plus what the device was doing. */
data class HistoryPoint(
    val timestampMs: Long,
    val level: Int,
    val charging: Boolean,
    val screenOn: Boolean,
)
