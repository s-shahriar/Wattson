package com.syed.wattson.data.parser

import com.syed.wattson.data.model.DiagnosisIndex
import com.syed.wattson.data.model.EventKind
import com.syed.wattson.data.model.PackedSpans
import com.syed.wattson.data.model.SystemFlag
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Folds the raw history stream into the span index behind the Diagnose tab.
 *
 * Fed the same way [BatteryHistoryReducer] is — a line at a time, as the dump arrives, so
 * the twenty megabytes never reach the heap. What it keeps is roughly a hundred thousand
 * spans in packed integer arrays, about a megabyte, and only for as long as the answers
 * built from it are on screen.
 *
 * Two of the states the history reports are a single slot rather than a set:
 *
 *  - **Foreground.** One app is on top at a time.
 *  - **Wakelocks.** The history records the lock that kept the device awake, not the
 *    stack: `+wake_lock=-1:"screen"` is closed by `-wake_lock=u0a201:"Scrims"`. Pairing
 *    those by name leaves every unmatched acquire open to the end of the buffer, which
 *    read as five different apps each holding the phone awake for 100% of the same night.
 *
 * Jobs and syncs really are concurrent, so those are paired by name.
 */
class DiagnosisIndexer(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: LocalDateTime = LocalDateTime.now(zone),
) {

    private val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    private val dateCache = HashMap<Int, LocalDate?>(16)

    private var epochMs = Long.MIN_VALUE
    private var lastSec = 0

    private val namePool = HashMap<String, Int>(512)
    private val names = ArrayList<String>(512)

    private val spanStarts = EventKind.entries.associateWith { IntBag() }
    private val spanEnds = EventKind.entries.associateWith { IntBag() }
    private val spanNames = EventKind.entries.associateWith { IntBag() }
    private val spanUids = EventKind.entries.associateWith { IntBag() }

    /** Open span for each kind: start second, name id and uid. */
    private val openSlot = HashMap<EventKind, Triple<Int, Int, Int>>(4)

    private val flagBags = SystemFlag.entries.associateWith { IntBag() }
    private val flagOpen = HashMap<SystemFlag, Int>(16)

    private val chargeSec = IntBag()
    private val chargeMah = IntBag()
    private val levelSec = IntBag()
    private val levelPercent = IntBag()
    private var lastLevel = -1

    private val chargingSpans = IntBag()

    /** Second the current charge began, or [NOT_CHARGING]. */
    private var chargingSince = NOT_CHARGING

    var truncated = false
        private set

    fun accept(line: String) {
        if (DumpsysOutput.isTruncationMarker(line)) {
            truncated = true
            return
        }
        if (line.length < MIN_RECORD_LENGTH) return
        val level = threeDigits(line, LEVEL_AT)?.takeIf { it in 0..100 } ?: return
        val timestamp = timestampOf(line) ?: return

        if (epochMs == Long.MIN_VALUE) epochMs = timestamp
        val sec = ((timestamp - epochMs) / 1000L).toInt()
        if (sec < lastSec) return
        lastSec = sec

        if (level != lastLevel) {
            levelSec.add(sec)
            levelPercent.add(level)
            lastLevel = level
        }

        // Behind the guard for the same reason the reducer puts it there: "status=" is on
        // a few hundred records and the four comparisons would otherwise run on all of
        // them. not-charging is folded into discharging — a negotiating charger flaps
        // between the two several times a second, and each flap would otherwise open and
        // close a span.
        if (line.indexOf(STATUS, LEVEL_AT) > 0) {
            when {
                line.contains(STATUS_CHARGING) || line.contains(STATUS_FULL) ->
                    if (chargingSince == NOT_CHARGING) chargingSince = sec
                line.contains(STATUS_DISCHARGING) || line.contains(STATUS_NOT_CHARGING) ->
                    closeCharge(sec)
            }
        }

        // The coulomb counter is the only exact figure in the dump: percentages are a
        // gauge's opinion, mAh remaining is a measurement.
        val charge = valueAfter(line, CHARGE)
        if (charge != null) {
            chargeSec.add(sec)
            chargeMah.add(charge)
        }

        if (line.indexOf('+', LEVEL_AT) < 0 && line.indexOf('-', LEVEL_AT) < 0) return
        scanTokens(line, sec)
    }

    /**
     * Walks the `+name=uid:"label"` / `-name` tokens after the level column.
     *
     * Hand-rolled rather than a regex: this runs on every one of three hundred and fifty
     * thousand lines, and a `Regex.findAll` over that allocates a match object per token.
     */
    private fun scanTokens(line: String, sec: Int) {
        var i = LEVEL_AT + 3
        while (i < line.length) {
            val c = line[i]
            if ((c != '+' && c != '-') || (i > 0 && line[i - 1] != ' ')) {
                i++
                continue
            }
            var j = i + 1
            while (j < line.length && (line[j].isLetter() || line[j] == '_')) j++
            if (j == i + 1) {
                i++
                continue
            }
            val token = line.substring(i + 1, j)
            val added = c == '+'

            var label: String? = null
            var uid = PackedSpans.NO_UID
            var next = j
            if (j < line.length && line[j] == '=') {
                val quote = line.indexOf('"', j)
                val close = if (quote > 0) line.indexOf('"', quote + 1) else -1
                if (quote > 0 && close > quote && quote - j < UID_FIELD_LIMIT) {
                    label = line.substring(quote + 1, close)
                    uid = parseUid(line, j + 1, quote - 1)
                    next = close + 1
                } else {
                    var k = j + 1
                    while (k < line.length && line[k] != ' ') k++
                    next = k
                }
            }

            val kind = KINDS[token]
            if (kind != null) {
                record(kind, added, label, uid, sec)
            } else {
                FLAGS[token]?.let { flag ->
                    if (added) {
                        flagOpen.putIfAbsent(flag, sec)
                    } else {
                        flagOpen.remove(flag)?.let { start ->
                            flagBags.getValue(flag).add(start)
                            flagBags.getValue(flag).add(sec)
                        }
                    }
                }
            }
            i = next
        }
    }

    private fun record(kind: EventKind, added: Boolean, label: String?, uid: Int, sec: Int) {
        // Single slot: whatever was open closes here, whatever the new token is called.
        openSlot.remove(kind)?.let { (start, nameId, owner) ->
            close(kind, start, sec, nameId, owner)
        }
        if (added && label != null) openSlot[kind] = Triple(sec, idOf(label), uid)
    }

    private fun closeCharge(sec: Int) {
        val start = chargingSince
        chargingSince = NOT_CHARGING
        if (start == NOT_CHARGING || sec <= start) return
        chargingSpans.add(start)
        chargingSpans.add(sec)
    }

    private fun close(kind: EventKind, start: Int, end: Int, nameId: Int, uid: Int) {
        if (end <= start) return
        spanStarts.getValue(kind).add(start)
        spanEnds.getValue(kind).add(end)
        spanNames.getValue(kind).add(nameId)
        spanUids.getValue(kind).add(uid)
    }

    /**
     * The uid text between [from] and [to], as Android encodes it.
     *
     * `u0a138` is user 0, app 138, which is uid 10138 — the number `PackageManager` will
     * answer to. A bare number is already a uid. Anything else is nobody.
     */
    private fun parseUid(line: String, from: Int, to: Int): Int {
        if (to < from) return PackedSpans.NO_UID
        if (line[from] == 'u') {
            var i = from + 1
            var user = 0
            while (i <= to && line[i].isDigit()) {
                user = user * 10 + (line[i] - '0')
                i++
            }
            if (i > to || line[i] != 'a') return PackedSpans.NO_UID
            i++
            var app = 0
            var digits = 0
            while (i <= to && line[i].isDigit()) {
                app = app * 10 + (line[i] - '0')
                i++
                digits++
            }
            if (digits == 0) return PackedSpans.NO_UID
            return user * PER_USER_RANGE + FIRST_APP_UID + app
        }
        var i = from
        var value = 0
        var digits = 0
        while (i <= to && line[i].isDigit()) {
            value = value * 10 + (line[i] - '0')
            i++
            digits++
        }
        return if (digits == 0) PackedSpans.NO_UID else value
    }


    private fun idOf(label: String): Int = namePool.getOrPut(label) {
        names.add(label)
        names.size - 1
    }

    /**
     * The index, with every span still open closed at the end of the buffer.
     *
     * A lock held when the dump was taken is still a lock that was held; dropping it would
     * hide the one case that matters most, an app that took the phone and never let go.
     */
    fun build(): DiagnosisIndex {
        openSlot.forEach { (kind, open) -> close(kind, open.first, lastSec, open.second, open.third) }
        flagOpen.forEach { (flag, start) ->
            if (lastSec > start) {
                flagBags.getValue(flag).add(start)
                flagBags.getValue(flag).add(lastSec)
            }
        }
        openSlot.clear()
        flagOpen.clear()
        // A phone dumped while it is plugged in has been charging since it was plugged in
        // and still is; the span runs to the end of the buffer like any other.
        closeCharge(lastSec)

        return DiagnosisIndex(
            epochMs = if (epochMs == Long.MIN_VALUE) nowMs else epochMs,
            endSec = lastSec,
            names = names.toTypedArray(),
            spans = EventKind.entries.associateWith { kind ->
                PackedSpans(
                    startSec = spanStarts.getValue(kind).toArray(),
                    endSec = spanEnds.getValue(kind).toArray(),
                    nameId = spanNames.getValue(kind).toArray(),
                    uid = spanUids.getValue(kind).toArray(),
                )
            },
            flags = SystemFlag.entries.associateWith { flagBags.getValue(it).toArray() },
            chargeSec = chargeSec.toArray(),
            chargeMah = chargeMah.toArray(),
            levelSec = levelSec.toArray(),
            levelPercent = levelPercent.toArray(),
            chargingSpans = chargingSpans.toArray(),
        )
    }

    /** Integer value of `key=NNN` in this line, or null. */
    private fun valueAfter(line: String, key: String): Int? {
        val at = line.indexOf(key)
        if (at < 0) return null
        var i = at + key.length
        if (i >= line.length || !line[i].isDigit()) return null
        var value = 0
        while (i < line.length && line[i].isDigit()) {
            value = value * 10 + (line[i] - '0')
            i++
        }
        return value
    }

    /** Same rules as [BatteryHistoryReducer]: no year in the record, and clocks that lie. */
    private fun timestampOf(line: String): Long? {
        val month = twoDigits(line, MONTH_AT) ?: return null
        val day = twoDigits(line, DAY_AT) ?: return null
        val hour = twoDigits(line, HOUR_AT) ?: return null
        val minute = twoDigits(line, MINUTE_AT) ?: return null
        val second = twoDigits(line, SECOND_AT) ?: return null

        val date = dateCache.getOrPut(month * 100 + day) {
            val candidate = runCatching { LocalDate.of(now.year, month, day) }.getOrNull()
            if (candidate != null && candidate.atStartOfDay().isAfter(now.plusDays(1))) {
                candidate.minusYears(1)
            } else {
                candidate
            }
        } ?: return null

        val timestamp = date.atTime(hour, minute, second).atZone(zone).toInstant().toEpochMilli()
        if (timestamp > nowMs + MAX_CLOCK_SKEW_MS) return null
        if (timestamp < nowMs - MAX_RECORD_AGE_MS) return null
        return timestamp
    }

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

    /** Growable int array. ArrayList<Int> would box every one of a hundred thousand. */
    private class IntBag {
        private var data = IntArray(256)
        private var count = 0

        fun add(value: Int) {
            if (count == data.size) data = data.copyOf(data.size * 2)
            data[count++] = value
        }

        fun toArray(): IntArray = data.copyOf(count)
    }

    private companion object {
        const val MONTH_AT = 2
        const val DAY_AT = 5
        const val HOUR_AT = 8
        const val MINUTE_AT = 11
        const val SECOND_AT = 14
        const val LEVEL_AT = 21
        const val MIN_RECORD_LENGTH = LEVEL_AT + 3
        const val CHARGE = "charge="
        const val STATUS = "status="
        const val STATUS_CHARGING = "status=charging"
        const val STATUS_FULL = "status=full"
        const val STATUS_DISCHARGING = "status=discharging"
        const val STATUS_NOT_CHARGING = "status=not-charging"

        const val NOT_CHARGING = -1

        /** Android packs uids as user * 100000 + 10000 + app. */
        const val PER_USER_RANGE = 100_000
        const val FIRST_APP_UID = 10_000

        /** `+top=u0a138:"..."` — beyond this the `=` belongs to something else. */
        const val UID_FIELD_LIMIT = 12

        const val MAX_CLOCK_SKEW_MS = 60_000L
        const val MAX_RECORD_AGE_MS = 14L * 24 * 3_600_000

        val KINDS = mapOf(
            "top" to EventKind.FOREGROUND,
            "wake_lock" to EventKind.WAKELOCK,
        )

        val FLAGS = mapOf(
            "screen" to SystemFlag.SCREEN,
            "running" to SystemFlag.CPU_RUNNING,
            "cellular_high_tx_power" to SystemFlag.CELLULAR_HIGH_TX,
            "mobile_radio" to SystemFlag.MOBILE_RADIO,
            "wifi_scan" to SystemFlag.WIFI_SCAN,
            "audio" to SystemFlag.AUDIO,
            "video" to SystemFlag.VIDEO,
            "sensor" to SystemFlag.SENSOR,
            "ble_scan" to SystemFlag.BLE_SCAN,
            "gps" to SystemFlag.GPS,
        )
    }
}
