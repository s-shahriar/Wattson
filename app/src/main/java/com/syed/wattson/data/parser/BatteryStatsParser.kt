package com.syed.wattson.data.parser

import com.syed.wattson.data.model.AppUsage
import com.syed.wattson.data.model.BatteryNow
import com.syed.wattson.data.model.BatteryStats
import com.syed.wattson.data.model.BrightnessBin
import com.syed.wattson.data.model.PowerBucket
import com.syed.wattson.data.model.PowerByState

/**
 * Parses the plain-text output of `dumpsys batterystats --charged` and `dumpsys battery`.
 *
 * The format is stable across Android releases but chatty, so every extraction is
 * defensive: a missing or reshaped line yields a null/zero rather than throwing.
 */
object BatteryStatsParser {

    private val DURATION = Regex("""(\d+)\s*(ms|h|m|s)""")
    private val GLOBAL_BUCKET = Regex("""^([a-z_]+):\s+([-\d.eE+]+)""")
    private val UID_HEADER = Regex("""^UID\s+(\S+):\s+([-\d.eE+]+)""")
    private val KEY_VALUE = Regex("""(?:^|\s)([a-z_]+)=([-\d.eE+]+)""")
    private val BRIGHTNESS = Regex("""^(dark|dim|medium|light|bright)\s+(.+?)\s*\(([\d.]+)%\)$""")

    /** Sums every `<n><unit>` token in [text], e.g. "21h 6m 10s 571ms" -> milliseconds. */
    fun parseDurationMs(text: String): Long =
        DURATION.findAll(text).sumOf { match ->
            val value = match.groupValues[1].toLongOrNull() ?: 0L
            when (match.groupValues[2]) {
                "ms" -> value
                "s" -> value * 1_000
                "m" -> value * 60_000
                "h" -> value * 3_600_000
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

        val globals = mutableListOf<PowerBucket>()
        val brightness = mutableListOf<BrightnessBin>()
        val apps = mutableListOf<AppUsage>()

        var inGlobalBlock = false
        var inBrightnessBlock = false

        // Running totals for the four "(on/not on battery, screen on/off)" sub-blocks.
        val stateTotals = mutableMapOf<PowerState, Double>()
        var currentState: PowerState? = null

        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            val line = rawLine.trim()

            when {
                line.startsWith("Start clock time:") ->
                    startClock = line.substringAfter("Start clock time:").trim()

                // Must be tested before the shorter "Time on battery:" prefix.
                line.startsWith("Time on battery screen off:") ->
                    screenOff = durationAfter(line, "Time on battery screen off:")

                line.startsWith("Time on battery:") ->
                    timeOnBattery = durationAfter(line, "Time on battery:")

                line.startsWith("Total run time:") ->
                    totalRunTime = durationAfter(line, "Total run time:")

                line.startsWith("Discharge:") ->
                    dischargeMah = Regex("""([\d.]+)""").find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()

                line.startsWith("Screen on:") -> {
                    screenOn = durationAfter(line, "Screen on:")
                    screenOnCount = Regex("""\)\s*(\d+)x""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }

                line.startsWith("Capacity:") ->
                    designCapacity = Regex("""Capacity:\s*(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()

                line.startsWith("Screen brightnesses:") -> {
                    inBrightnessBlock = true
                    inGlobalBlock = false
                }

                line == "Global" -> {
                    inGlobalBlock = true
                    inBrightnessBlock = false
                }

                // "(on battery, screen on)" and friends open a per-state sub-block.
                line.startsWith("(") -> {
                    inGlobalBlock = false
                    inBrightnessBlock = false
                    currentState = PowerState.from(line)
                }

                inBrightnessBlock -> {
                    val match = BRIGHTNESS.find(line)
                    if (match != null) {
                        brightness += BrightnessBin(
                            name = match.groupValues[1],
                            durationMs = parseDurationMs(match.groupValues[2]),
                            percent = match.groupValues[3].toDoubleOrNull() ?: 0.0,
                        )
                    } else {
                        inBrightnessBlock = false
                    }
                }

                inGlobalBlock -> {
                    // The per-state repeats "(on battery, screen on)" end the aggregate block.
                    if (line.startsWith("(") || line.startsWith("UID ")) {
                        inGlobalBlock = false
                        currentState = PowerState.from(line)
                    } else {
                        val match = GLOBAL_BUCKET.find(line)
                        if (match != null) {
                            val mah = match.groupValues[2].toDoubleOrNull() ?: 0.0
                            val duration = line.substringAfter("duration:", "")
                                .takeIf { it.isNotBlank() }
                                ?.let { parseDurationMs(it) }
                            globals += PowerBucket(match.groupValues[1], mah, duration)
                        }
                    }
                }

                // Inside a per-state sub-block: accumulate that state's total.
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

            // A UID header owns the indented lines that follow it.
            val uidMatch = UID_HEADER.find(line)
            if (uidMatch != null) {
                inGlobalBlock = false
                inBrightnessBlock = false
                currentState = null

                val rawUid = uidMatch.groupValues[1]
                val mah = uidMatch.groupValues[2].toDoubleOrNull() ?: 0.0

                // The first following line holds the aggregate breakdown; per-state
                // repeats begin with "(" and are skipped.
                val buckets = mutableListOf<PowerBucket>()
                var cursor = index + 1
                while (cursor < lines.size) {
                    val detail = lines[cursor].trim()
                    if (detail.isEmpty() || detail.startsWith("UID ") || UID_HEADER.containsMatchIn(detail)) break
                    if (detail.startsWith("(")) break
                    KEY_VALUE.findAll(detail).forEach { pair ->
                        val value = pair.groupValues[2].toDoubleOrNull() ?: 0.0
                        if (value > 0.0) buckets += PowerBucket(pair.groupValues[1], value)
                    }
                    cursor++
                }

                apps += AppUsage(
                    rawUid = rawUid,
                    uid = decodeUid(rawUid),
                    mah = mah,
                    packageName = null,
                    label = rawUid,
                    icon = null,
                    buckets = buckets.sortedByDescending { it.mah },
                )
            }

            index++
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
            globalBuckets = globals.sortedByDescending { it.mah },
            brightness = brightness,
            apps = apps.sortedByDescending { it.mah },
            powerByState = PowerByState(
                onBatteryScreenOnMah = stateTotals[PowerState.ON_BATTERY_SCREEN_ON] ?: 0.0,
                onBatteryScreenOffMah = stateTotals[PowerState.ON_BATTERY_SCREEN_OFF] ?: 0.0,
                chargingScreenOnMah = stateTotals[PowerState.CHARGING_SCREEN_ON] ?: 0.0,
                chargingScreenOffMah = stateTotals[PowerState.CHARGING_SCREEN_OFF] ?: 0.0,
            ),
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
