package com.syed.wattson.data.model

/**
 * Charge actually taken out of the cell, as measured by the coulomb counter.
 *
 * This is a different and far more trustworthy quantity than [PowerByState], which is
 * modelled from the power profile. On devices where the kernel does not expose per-UID
 * CPU time the model can only account for a small fraction of real drain, so anything
 * presented as a total must come from here.
 */
data class MeasuredDischarge(
    val totalMah: Double,
    val screenOnMah: Double?,
    val screenOffMah: Double?,
    val screenDozeMah: Double?,
    val lightDozeMah: Double?,
    val deepDozeMah: Double?,
)

/** Everything parsed out of `dumpsys batterystats --charged`. */
data class BatteryStats(
    val startClock: String?,
    val timeOnBatteryMs: Long,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val screenOnCount: Int,
    val totalRunTimeMs: Long,
    /**
     * Charge taken out of the cell this cycle. Kept fractional: batterystats prints these
     * to two decimals below 10 mAh and one below 100, and rounding to a whole number
     * throws away most of the figure for the first minutes of a cycle.
     */
    val dischargeMah: Double?,
    val designCapacityMah: Int?,
    val powerByState: PowerByState,
    val measured: MeasuredDischarge? = null,
    /** "Computed drain" from the estimated-power header — the model's own grand total. */
    val computedDrainMah: Int? = null,
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

    /**
     * Hours left at the present rate — until full when charging, until empty when not.
     *
     * The two directions measure opposite quantities: charging counts the gap up to
     * [fullCapacityMah], discharging counts the charge actually left in the cell. Using
     * the charging formula while on battery reported "time to full" against a current
     * that was flowing the other way, which is why a discharging phone showed an ETA.
     *
     * [fullCapacityMah] is passed in rather than read off [chargeFullMah]: only a rooted
     * sysfs read populates that field, so computing it here left every non-rooted device
     * with a blank tile for the whole of a charge. The caller resolves the same capacity
     * it displays.
     *
     * Linear at the present current, so it runs optimistic near a full cell — charging
     * tapers through the constant-voltage phase and this does not model that.
     */
    fun hoursRemaining(isCharging: Boolean, fullCapacityMah: Int?): Double? {
        val counter = chargeCounterMah ?: return null
        val rate = currentMilliAmps?.takeIf { it > MIN_MEANINGFUL_MA } ?: return null
        val remaining = if (isCharging) {
            (fullCapacityMah ?: return null) - counter
        } else {
            counter
        }
        return remaining.takeIf { it > 0 }?.toDouble()?.div(rate)
    }

    /**
     * Full-charge capacity inferred from the charge counter and level.
     *
     * Last resort for devices that expose neither a rooted `charge_full` nor a dumpsys
     * design capacity. Accurate to a few percent mid-range; ignored at very low levels
     * where the counter's own rounding dominates.
     */
    fun inferredFullMah(levelPercent: Int): Int? {
        val counter = chargeCounterMah?.takeIf { it > 0 } ?: return null
        if (levelPercent < MIN_INFERENCE_LEVEL || levelPercent > 100) return null
        return (counter * 100.0 / levelPercent).toInt()
    }

    private companion object {
        const val MIN_MEANINGFUL_MA = 5

        /** Below this the counter's rounding swamps the ratio. */
        const val MIN_INFERENCE_LEVEL = 5
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
