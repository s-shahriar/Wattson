package com.syed.wattson.data.parser

import com.syed.wattson.data.model.HistoryPoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Parses the pre-filtered battery-history stream.
 *
 * The raw `dumpsys batterystats --history` output runs to hundreds of thousands of lines,
 * so it is reduced on-device (see `BatteryRepository.CMD_HISTORY`) to one record per
 * actual change. Each line looks like:
 *
 * ```
 * 08-13 12:40:36.153 023 C 1
 * ```
 *
 * date, time, level, charge state (C/D), screen flag (1/0).
 *
 * Fields are read by position rather than through `DateTimeFormatter`, and the
 * `LocalDate` for each calendar day is built once and reused — a few thousand records
 * typically span only a handful of days, and formatter parsing dominated this step
 * otherwise. Zone conversion is still done properly per record, so daylight-saving
 * transitions remain correct.
 */
object BatteryHistoryParser {

    fun parse(output: String): List<HistoryPoint> {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val currentYear = now.year
        val dateCache = HashMap<Int, LocalDate>(8)

        val points = ArrayList<HistoryPoint>(EXPECTED_RECORDS)
        output.lineSequence().forEach { raw ->
            parseLine(raw.trim(), zone, now, currentYear, dateCache)?.let(points::add)
        }
        points.sortBy { it.timestampMs }
        return points
    }

    private fun parseLine(
        line: String,
        zone: ZoneId,
        now: LocalDateTime,
        currentYear: Int,
        dateCache: HashMap<Int, LocalDate>,
    ): HistoryPoint? {
        // "MM-DD HH:MM:SS.mmm LLL S F" — shortest valid form is 24 chars.
        if (line.length < 24) return null

        val month = twoDigits(line, 0) ?: return null
        val day = twoDigits(line, 3) ?: return null
        val hour = twoDigits(line, 6) ?: return null
        val minute = twoDigits(line, 9) ?: return null
        val second = twoDigits(line, 12) ?: return null
        val millis = threeDigits(line, 15) ?: return null

        // Remaining fields are whitespace-separated after the timestamp.
        val rest = line.substring(18).trim().split(' ')
        if (rest.size < 3) return null
        val level = rest[0].toIntOrNull()?.takeIf { it in 0..100 } ?: return null

        // One LocalDate per calendar day, keyed by month*100+day.
        val date = dateCache.getOrPut(month * 100 + day) {
            val candidate = runCatching { LocalDate.of(currentYear, month, day) }.getOrNull()
                ?: return null
            // History carries no year; roll back when that would be in the future.
            if (candidate.atStartOfDay().isAfter(now.plusDays(1))) {
                candidate.minusYears(1)
            } else {
                candidate
            }
        }

        val timestamp = date
            .atTime(hour, minute, second, millis * NANOS_PER_MILLI)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        return HistoryPoint(
            timestampMs = timestamp,
            level = level,
            charging = rest[1] == "C",
            screenOn = rest[2] == "1",
        )
    }

    /** Reads two ASCII digits at [at], or null if they are not digits. */
    private fun twoDigits(s: String, at: Int): Int? {
        val a = s[at]
        val b = s[at + 1]
        if (a < '0' || a > '9' || b < '0' || b > '9') return null
        return (a - '0') * 10 + (b - '0')
    }

    private fun threeDigits(s: String, at: Int): Int? {
        val a = s[at]
        val b = s[at + 1]
        val c = s[at + 2]
        if (a < '0' || a > '9' || b < '0' || b > '9' || c < '0' || c > '9') return null
        return (a - '0') * 100 + (b - '0') * 10 + (c - '0')
    }

    private const val NANOS_PER_MILLI = 1_000_000
    private const val EXPECTED_RECORDS = 4096
}
