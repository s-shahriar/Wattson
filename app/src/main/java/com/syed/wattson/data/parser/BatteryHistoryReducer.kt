package com.syed.wattson.data.parser

import com.syed.wattson.data.model.HistoryPoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Folds the raw `dumpsys batterystats --history` stream down to one sample per change.
 *
 * [accept] is fed every line of the dump as it arrives and keeps only the records where
 * the level, charge state or screen state actually differs from the last one kept — over
 * 350 000 lines and 20 MB of text on a device whose history buffer is full, against a
 * couple of thousand samples out.
 *
 * That reduction used to run on the device as an `awk` filter in the same pipeline as the
 * dump. It cannot: toybox `awk` manages about twelve thousand of these lines a second, so
 * the filter took a minute and a half, and because it was the thing reading the pipe the
 * dump ran at *its* speed — well past the ten seconds dumpsys allows a service before it
 * kills the dump and moves on. Every refresh got a stream cut off at an arbitrary point
 * hours or days in the past, with no error to show for it. Here the same reduction costs
 * a couple of seconds and the dump is never held up.
 *
 * Each raw record is laid out at fixed columns:
 *
 * ```
 *   08-17 20:40:34.427 058 status=discharging health=good ... +screen ...
 *   0 2     8            21
 * ```
 *
 * Fields are read by offset rather than through split/`DateTimeFormatter`, and the
 * `LocalDate` for each calendar day is built once and reused — at this line count both of
 * those dominated everything else. Zone conversion is still done properly per record, so
 * daylight-saving transitions remain correct.
 */
class BatteryHistoryReducer(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: LocalDateTime = LocalDateTime.now(zone),
) {

    private val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    private val points = ArrayList<HistoryPoint>(EXPECTED_RECORDS)
    private val dateCache = HashMap<Int, LocalDate?>(16)

    /**
     * Charge and screen state carry forward: the dump prints them only when they change,
     * so most records say nothing about either.
     */
    private var charging = false
    private var screenOn = false

    /** Level, charge and screen state of the last kept sample, packed for comparison. */
    private var lastKey = NO_KEY

    /** True once dumpsys admits it cut the stream short — see [DumpsysOutput]. */
    var truncated = false
        private set

    /** Samples in chronological order. */
    fun points(): List<HistoryPoint> = points.sortedBy { it.timestampMs }

    fun accept(line: String) {
        // Cheap enough to run on every line: it fails on the first character for all but
        // the marker, and the marker is the only sign that the rest of the dump is missing.
        if (DumpsysOutput.isTruncationMarker(line)) {
            truncated = true
            return
        }
        if (line.length < MIN_RECORD_LENGTH) return

        val level = threeDigits(line, LEVEL_AT)?.takeIf { it in 0..100 } ?: return

        // "status=" appears on a few hundred of the records; the four comparisons behind
        // this guard would otherwise run on all 350 000.
        if (line.contains(STATUS)) {
            when {
                line.contains(STATUS_CHARGING) || line.contains(STATUS_FULL) -> charging = true
                // not-charging is folded into discharging: chargers negotiate constantly
                // and the raw state flaps between the two several times a second, which
                // would render as visual noise.
                line.contains(STATUS_DISCHARGING) || line.contains(STATUS_NOT_CHARGING) ->
                    charging = false
            }
        }

        if (line.contains(SCREEN)) {
            when {
                line.hasFlag(SCREEN_ON) -> screenOn = true
                line.hasFlag(SCREEN_OFF) -> screenOn = false
            }
        }

        val key = (level shl 2) or (if (charging) 2 else 0) or (if (screenOn) 1 else 0)
        if (key == lastKey) return

        val timestamp = timestampOf(line) ?: return

        points.add(
            HistoryPoint(
                timestampMs = timestamp,
                level = level,
                charging = charging,
                screenOn = screenOn,
            ),
        )
        lastKey = key
    }

    /**
     * Wall clock of the record, or null when it cannot be trusted.
     *
     * Records carry no year, so the current one is assumed and rolled back when that
     * would put the record in the future. What is left after that is a clock that was
     * genuinely wrong when the record was written: pulling the battery cuts the RTC's
     * power, so the first records after a swap are stamped 1970 and arrive here as
     * "01-01 06:00" — three of them are still sitting in this device's buffer. Dated to
     * the current year they become samples eight months old, which sort to the head of
     * the history and drag the start of the charted window back with them.
     *
     * The buffer holds days, not months, so anything outside [MAX_RECORD_AGE_MS] is a
     * broken clock rather than old data. State transitions on such a record are still
     * real and have already been folded in above; only its position in time is discarded.
     */
    private fun timestampOf(line: String): Long? {
        val month = twoDigits(line, MONTH_AT) ?: return null
        val day = twoDigits(line, DAY_AT) ?: return null
        val hour = twoDigits(line, HOUR_AT) ?: return null
        val minute = twoDigits(line, MINUTE_AT) ?: return null
        val second = twoDigits(line, SECOND_AT) ?: return null
        val millis = threeDigits(line, MILLIS_AT) ?: return null

        // One LocalDate per calendar day, keyed by month*100+day. Cached even when it
        // resolves to nothing, so a run of records on a bad date is rejected once.
        val date = dateCache.getOrPut(month * 100 + day) {
            val candidate = runCatching { LocalDate.of(now.year, month, day) }.getOrNull()
            // History carries no year; roll back when that would be in the future.
            if (candidate != null && candidate.atStartOfDay().isAfter(now.plusDays(1))) {
                candidate.minusYears(1)
            } else {
                candidate
            }
        } ?: return null

        val timestamp = date
            .atTime(hour, minute, second, millis * NANOS_PER_MILLI)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        if (timestamp > nowMs + MAX_CLOCK_SKEW_MS) return null
        if (timestamp < nowMs - MAX_RECORD_AGE_MS) return null
        return timestamp
    }

    /**
     * True when [flag] stands on its own in this line, e.g. `+screen` but not
     * `+screen_doze` — those are separate states and folding them together would report
     * a dozing device as awake with its screen on.
     */
    private fun String.hasFlag(flag: String): Boolean {
        var from = indexOf(flag)
        while (from >= 0) {
            val after = from + flag.length
            val next = if (after < length) this[after] else ' '
            if (from > 0 && this[from - 1] == ' ' && next != '_' && !next.isLetter()) return true
            from = indexOf(flag, from + 1)
        }
        return false
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

    private companion object {
        /** Column offsets of "  MM-DD HH:MM:SS.mmm LLL". */
        const val MONTH_AT = 2
        const val DAY_AT = 5
        const val HOUR_AT = 8
        const val MINUTE_AT = 11
        const val SECOND_AT = 14
        const val MILLIS_AT = 17
        const val LEVEL_AT = 21

        /** A record is at least "  MM-DD HH:MM:SS.mmm LLL" long. */
        const val MIN_RECORD_LENGTH = LEVEL_AT + 3

        const val STATUS = "status="
        const val STATUS_CHARGING = "status=charging"
        const val STATUS_FULL = "status=full"
        const val STATUS_DISCHARGING = "status=discharging"
        const val STATUS_NOT_CHARGING = "status=not-charging"
        const val SCREEN = "screen"
        const val SCREEN_ON = "+screen"
        const val SCREEN_OFF = "-screen"

        const val NO_KEY = -1
        const val NANOS_PER_MILLI = 1_000_000
        const val EXPECTED_RECORDS = 4096

        /** Clock skew allowed on a record before it counts as future-dated. */
        const val MAX_CLOCK_SKEW_MS = 60_000L

        /** The history buffer is a few megabytes; a fortnight is well beyond what it holds. */
        const val MAX_RECORD_AGE_MS = 14L * 24 * 3_600_000
    }
}
