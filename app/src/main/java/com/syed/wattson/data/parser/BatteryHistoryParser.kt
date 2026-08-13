package com.syed.wattson.data.parser

import com.syed.wattson.data.model.HistoryPoint
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
 */
object BatteryHistoryParser {

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun parse(output: String): List<HistoryPoint> {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)

        return output.lineSequence()
            .mapNotNull { line -> parseLine(line.trim(), now, zone) }
            .sortedBy { it.timestampMs }
            .toList()
    }

    private fun parseLine(line: String, now: LocalDateTime, zone: ZoneId): HistoryPoint? {
        if (line.isEmpty()) return null
        val parts = line.split(' ')
        if (parts.size < 5) return null

        val level = parts[2].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val timestamp = parseTimestamp(parts[0], parts[1], now, zone) ?: return null

        return HistoryPoint(
            timestampMs = timestamp,
            level = level,
            charging = parts[3] == "C",
            screenOn = parts[4] == "1",
        )
    }

    /**
     * History timestamps carry no year. The current year is assumed, rolling back one
     * year when that would place the sample in the future (a December/January wrap).
     */
    private fun parseTimestamp(
        date: String,
        time: String,
        now: LocalDateTime,
        zone: ZoneId,
    ): Long? {
        val monthDay = date.split('-').takeIf { it.size == 2 } ?: return null
        val month = monthDay[0].toIntOrNull() ?: return null
        val day = monthDay[1].toIntOrNull() ?: return null

        val parsedTime = runCatching { java.time.LocalTime.parse(time, TIME_FORMAT) }.getOrNull()
            ?: return null

        val candidate = runCatching {
            MonthDay.of(month, day)
                .atYear(Year.from(now).value)
                .atTime(parsedTime)
        }.getOrNull() ?: return null

        val resolved = if (candidate.isAfter(now.plusDays(1))) candidate.minusYears(1) else candidate
        return resolved.atZone(zone).toInstant().toEpochMilli()
    }
}
