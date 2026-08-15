package com.syed.wattson.data.parser

import com.syed.wattson.data.model.BatteryNow
import com.syed.wattson.data.model.BatteryStats
import com.syed.wattson.data.model.MeasuredDischarge
import com.syed.wattson.data.model.PowerByState

/**
 * Parses the plain-text output of `dumpsys batterystats --charged` and `dumpsys battery`.
 *
 * The format is stable across Android releases but chatty, so every extraction is
 * defensive: a missing or reshaped line yields a null/zero rather than throwing.
 */
object BatteryStatsParser {

    private val DURATION = Regex("""(\d+)\s*(ms|d|h|m|s)""")
    private val GLOBAL_BUCKET = Regex("""^([a-z_]+):\s+([-\d.eE+]+)""")
    private val FIRST_NUMBER = Regex("""([\d.]+)""")
    private val SCREEN_ON_COUNT = Regex("""\)\s*(\d+)x""")
    private val CAPACITY = Regex("""Capacity:\s*(\d+)""")
    private val COMPUTED_DRAIN = Regex("""Computed drain:\s*(\d+)""")
    private val MAH_VALUE = Regex("""(-?\d+)\s*mAh""")

    /** Sums every `<n><unit>` token in [text], e.g. "21h 6m 10s 571ms" -> milliseconds. */
    fun parseDurationMs(text: String): Long =
        DURATION.findAll(text).sumOf { match ->
            val value = match.groupValues[1].toLongOrNull() ?: 0L
            when (match.groupValues[2]) {
                "ms" -> value
                "s" -> value * 1_000
                "m" -> value * 60_000
                "h" -> value * 3_600_000
                "d" -> value * 86_400_000
                else -> 0L
            }
        }

    /**
     * Pulls the duration that follows [label], stopping at the first "(" (a percentage)
     * or the word "realtime" — both mark the end of the first figure on these lines.
     */
    private fun durationAfter(line: String, label: String): Long {
        val raw = line.substringAfter(label, "").trim()
        if (raw.isEmpty()) return 0L
        var slice = raw.substringBefore('(')
        val realtimeAt = slice.indexOf("realtime")
        if (realtimeAt >= 0) slice = slice.substring(0, realtimeAt)
        return parseDurationMs(slice)
    }

    /** Reads the "<n> mAh" that follows [label], e.g. "Screen on discharge: 1463 mAh". */
    private fun mahAfter(line: String, label: String): Int? =
        MAH_VALUE.find(line.substringAfter(label, ""))?.groupValues?.get(1)?.toIntOrNull()

    fun parseStats(dump: String): BatteryStats {
        val lines = dump.lineSequence().toList()

        var startClock: String? = null
        var timeOnBattery = 0L
        var screenOff = 0L
        var screenOn = 0L
        var screenOnCount = 0
        var totalRunTime = 0L
        var dischargeMah: Int? = null
        var designCapacity: Int? = null
        var computedDrain: Int? = null

        // Coulomb-counter figures. Parsed separately from the modelled buckets because
        // they are measurements, not estimates, and the two disagree by an order of
        // magnitude whenever per-UID CPU tracking is unavailable.
        var screenOnDischarge: Int? = null
        var screenOffDischarge: Int? = null
        var screenDozeDischarge: Int? = null
        var lightDozeDischarge: Int? = null
        var deepDozeDischarge: Int? = null

        // Running totals for the four "(on/not on battery, screen on/off)" sub-blocks.
        val stateTotals = mutableMapOf<PowerState, Double>()
        var currentState: PowerState? = null

        for (rawLine in lines) {
            val line = rawLine.trim()

            when {
                line.startsWith("Start clock time:") && startClock == null ->
                    startClock = line.substringAfter("Start clock time:").trim()

                // Must be tested before the shorter "Time on battery:" prefix.
                line.startsWith("Time on battery screen off:") && screenOff == 0L ->
                    screenOff = durationAfter(line, "Time on battery screen off:")

                line.startsWith("Time on battery:") && timeOnBattery == 0L ->
                    timeOnBattery = durationAfter(line, "Time on battery:")

                line.startsWith("Total run time:") && totalRunTime == 0L ->
                    totalRunTime = durationAfter(line, "Total run time:")

                line.startsWith("Discharge:") && dischargeMah == null ->
                    dischargeMah = FIRST_NUMBER.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()

                line.startsWith("Screen on discharge:") && screenOnDischarge == null ->
                    screenOnDischarge = mahAfter(line, "Screen on discharge:")

                line.startsWith("Screen off discharge:") && screenOffDischarge == null ->
                    screenOffDischarge = mahAfter(line, "Screen off discharge:")

                line.startsWith("Screen doze discharge:") && screenDozeDischarge == null ->
                    screenDozeDischarge = mahAfter(line, "Screen doze discharge:")

                line.startsWith("Device light doze discharge:") && lightDozeDischarge == null ->
                    lightDozeDischarge = mahAfter(line, "Device light doze discharge:")

                line.startsWith("Device deep doze discharge:") && deepDozeDischarge == null ->
                    deepDozeDischarge = mahAfter(line, "Device deep doze discharge:")

                line.startsWith("Screen on:") && screenOn == 0L -> {
                    screenOn = durationAfter(line, "Screen on:")
                    screenOnCount = SCREEN_ON_COUNT.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }

                // Both figures live on the one line — "Capacity: 3979, Computed drain:
                // 1975, actual drain: 1975" — and `when` runs a single branch, so they
                // have to be read together rather than from two matching conditions.
                line.startsWith("Capacity:") -> {
                    if (designCapacity == null) {
                        designCapacity = CAPACITY.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    }
                    if (computedDrain == null) {
                        computedDrain = COMPUTED_DRAIN.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    }
                }

                // "(on battery, screen on)" and friends open a per-state sub-block.
                // These four totals are only a fallback for devices whose dump carries no
                // coulomb-counter lines; where it does, toDrainUi prefers those instead.
                line.startsWith("(") -> currentState = PowerState.from(line)

                // A UID's own indented lines must not fold into a state total.
                line.startsWith("UID ") -> currentState = null

                else -> {
                    val state = currentState
                    if (state != null) {
                        GLOBAL_BUCKET.find(line)?.let { match ->
                            val mah = match.groupValues[2].toDoubleOrNull() ?: 0.0
                            stateTotals[state] = (stateTotals[state] ?: 0.0) + mah
                        }
                    }
                }
            }
        }

        return BatteryStats(
            startClock = startClock,
            timeOnBatteryMs = timeOnBattery,
            screenOnMs = screenOn,
            screenOffMs = screenOff,
            screenOnCount = screenOnCount,
            totalRunTimeMs = totalRunTime,
            dischargeMah = dischargeMah,
            designCapacityMah = designCapacity,
            powerByState = PowerByState(
                onBatteryScreenOnMah = stateTotals[PowerState.ON_BATTERY_SCREEN_ON] ?: 0.0,
                onBatteryScreenOffMah = stateTotals[PowerState.ON_BATTERY_SCREEN_OFF] ?: 0.0,
                chargingScreenOnMah = stateTotals[PowerState.CHARGING_SCREEN_ON] ?: 0.0,
                chargingScreenOffMah = stateTotals[PowerState.CHARGING_SCREEN_OFF] ?: 0.0,
            ),
            measured = dischargeMah?.let {
                MeasuredDischarge(
                    totalMah = it,
                    screenOnMah = screenOnDischarge,
                    screenOffMah = screenOffDischarge,
                    screenDozeMah = screenDozeDischarge,
                    lightDozeMah = lightDozeDischarge,
                    deepDozeMah = deepDozeDischarge,
                )
            },
            computedDrainMah = computedDrain,
        )
    }

    /** The four state headers batterystats emits inside "Estimated power use". */
    private enum class PowerState {
        ON_BATTERY_SCREEN_ON,
        ON_BATTERY_SCREEN_OFF,
        CHARGING_SCREEN_ON,
        CHARGING_SCREEN_OFF,
        ;

        companion object {
            /** Maps a header like "(not on battery, screen off/doze)" onto a state. */
            fun from(header: String): PowerState? {
                val charging = header.contains("not on battery")
                val screenOff = header.contains("screen off") || header.contains("doze")
                if (!header.contains("battery")) return null
                return when {
                    charging && screenOff -> CHARGING_SCREEN_OFF
                    charging -> CHARGING_SCREEN_ON
                    screenOff -> ON_BATTERY_SCREEN_OFF
                    else -> ON_BATTERY_SCREEN_ON
                }
            }
        }
    }

    /** "u0a428" -> 10428, "u10a5" -> 1010005, "1000" -> 1000. */
    fun decodeUid(raw: String): Int? {
        Regex("""^u(\d+)a(\d+)$""").find(raw)?.let {
            val user = it.groupValues[1].toIntOrNull() ?: return null
            val appId = it.groupValues[2].toIntOrNull() ?: return null
            return user * 100_000 + 10_000 + appId
        }
        Regex("""^u(\d+)i(\d+)$""").find(raw)?.let {
            val user = it.groupValues[1].toIntOrNull() ?: return null
            val appId = it.groupValues[2].toIntOrNull() ?: return null
            return user * 100_000 + 99_000 + appId
        }
        return raw.toIntOrNull()
    }

    fun parseNow(dump: String): BatteryNow {
        fun intOf(key: String): Int? =
            Regex("""^\s*$key:\s*(-?\d+)""", RegexOption.MULTILINE).find(dump)
                ?.groupValues?.get(1)?.toIntOrNull()

        val level = intOf("level") ?: 0
        val scale = intOf("scale") ?: 100
        val statusCode = intOf("status") ?: 1
        val healthCode = intOf("health") ?: 1
        val tenthsOfDegree = intOf("temperature") ?: 0
        val chargeCounterUah = Regex("""Charge counter:\s*(\d+)""").find(dump)
            ?.groupValues?.get(1)?.toIntOrNull()

        return BatteryNow(
            levelPercent = if (scale > 0) (level * 100 / scale) else level,
            status = when (statusCode) {
                2 -> "Charging"
                3 -> "Discharging"
                4 -> "Not charging"
                5 -> "Full"
                else -> "Unknown"
            },
            health = when (healthCode) {
                2 -> "Good"
                3 -> "Overheat"
                4 -> "Dead"
                5 -> "Over voltage"
                6 -> "Failure"
                7 -> "Cold"
                else -> "Unknown"
            },
            temperatureC = tenthsOfDegree / 10.0,
            chargeCounterMah = chargeCounterUah?.let { it / 1000 },
            isCharging = statusCode == 2 || statusCode == 5,
        )
    }
}
