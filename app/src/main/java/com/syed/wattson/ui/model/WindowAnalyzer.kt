package com.syed.wattson.ui.model

import com.syed.wattson.data.model.DiagnosisIndex
import com.syed.wattson.data.model.EventKind
import com.syed.wattson.data.model.PackedSpans
import com.syed.wattson.data.model.SystemFlag

/**
 * Answers one question about one window: where did the charge go.
 *
 * Pure, and the only place the arithmetic happens — the cards below the picker render
 * what comes out of here and compute nothing themselves.
 *
 * Two rules run through all of it:
 *
 *  - **Overlapping spans of one app are one span.** An app holding three wakelocks at
 *    once kept the phone awake once. Summing them reported holders at 100% of a window
 *    five times over.
 *  - **A state is only counted where it can mean something.** Foreground time is
 *    intersected with screen-on, because the top activity does not change when the screen
 *    goes dark and would otherwise credit a whole night to whatever was last opened.
 *    Wakelocks are intersected with screen-off, where holding one is the whole story.
 */
object WindowAnalyzer {

    /** How many rows each list on the card carries. */
    private const val TOP_N = 5

    /** Below this a slice is rounding, not a cause. */
    private const val MIN_SLICE_SEC = 1

    /** Marks a group keyed by tag rather than by uid, so the two cannot collide. */
    private const val NAME_KEY = 1L shl 40

    fun analyze(index: DiagnosisIndex, fromSec: Int, toSec: Int): WindowReport? {
        if (!index.isUsable) return null
        val start = fromSec.coerceIn(0, index.endSec)
        val end = toSec.coerceIn(0, index.endSec)
        if (end - start < MIN_WINDOW_SEC) return null

        val totalSec = end - start

        // The charge comes out of the window before anything is counted. A window picked
        // by level cannot contain one; a window picked off the clock easily can, and
        // measured through it the report was nonsense — 16% to 75% and no drain to speak
        // of, with the hour on the charger credited to whatever app was open during it.
        // Everything below is measured over the stretches on battery alone, and the card
        // shows which stretches those were.
        val charging = clip(significantCharges(index), start, end)
        val chargingSec = span(charging)
        val onBattery = complement(charging, start, end)
        val onBatterySec = totalSec - chargingSec

        val screen = intersect(clip(index.flagOf(SystemFlag.SCREEN), start, end), onBattery)
        val screenOnSec = span(screen)
        val screenOff = intersect(complement(screen, start, end), onBattery)
        val screenOffSec = onBatterySec - screenOnSec

        val stretches = stretches(index, start, end, charging)
        val drained = stretches.filter { !it.charging }
        val usedPercent = drained.sumOf { (it.startLevel - it.endLevel).coerceAtLeast(0) }
        val usedMah = drainedMah(index, start, end, charging)
        val hours = onBatterySec / 3600.0

        return WindowReport(
            startMs = index.secondsToMs(start),
            endMs = index.secondsToMs(end),
            durationMs = totalSec * 1000L,
            startLevel = levelAt(index, start),
            endLevel = levelAt(index, end),
            usedPercent = usedPercent,
            usedMah = usedMah,
            mahPerHour = if (hours > 0) usedMah?.let { it / hours } else null,
            percentPerHour = if (hours > 0) usedPercent / hours else 0.0,
            screenOnMs = screenOnSec * 1000L,
            screenOffMs = screenOffSec * 1000L,
            onBatteryMs = onBatterySec * 1000L,
            stretches = stretches,
            screenTime = slices(index, EventKind.FOREGROUND, start, end, screen, screenOnSec),
            keptAwake = slices(index, EventKind.WAKELOCK, start, end, screenOff, screenOffSec),
            system = systemSlices(index, start, end, onBattery, onBatterySec),
            chargingMs = chargingSec * 1000L,
        )
    }

    /** The window in order, alternating between battery and charger. */
    private fun stretches(
        index: DiagnosisIndex,
        start: Int,
        end: Int,
        charging: IntArray,
    ): List<WindowStretch> {
        val out = ArrayList<WindowStretch>(4)
        fun add(from: Int, to: Int, onCharger: Boolean) {
            if (to <= from) return
            out.add(
                WindowStretch(
                    startMs = index.secondsToMs(from),
                    endMs = index.secondsToMs(to),
                    charging = onCharger,
                    startLevel = levelAt(index, from),
                    endLevel = levelAt(index, to),
                ),
            )
        }

        var cursor = start
        var i = 0
        while (i + 1 < charging.size) {
            add(cursor, charging[i], onCharger = false)
            add(maxOf(cursor, charging[i]), charging[i + 1], onCharger = true)
            cursor = maxOf(cursor, charging[i + 1])
            i += 2
        }
        add(cursor, end, onCharger = false)
        return out
    }

    /**
     * Coulombs spent across the stretches on battery, or null.
     *
     * Summed stretch by stretch rather than end to end, because end to end it is the
     * charge that dominates: a night that spent 400 mAh and took 2000 back reads as a
     * gain, which the old guard turned into no figure at all.
     *
     * A leg whose counter finished higher than it started is noise and contributes
     * nothing, and a total of nothing is reported as nothing rather than as a measured
     * zero — "0 mAh" claims a precision that a gauge running backwards has not earned.
     */
    private fun drainedMah(index: DiagnosisIndex, start: Int, end: Int, charging: IntArray): Int? {
        if (index.chargeSec.isEmpty()) return null
        var total = 0
        var cursor = start
        var i = 0
        fun leg(from: Int, to: Int): Boolean {
            if (to <= from) return true
            val a = chargeAt(index, from) ?: return false
            val b = chargeAt(index, to) ?: return false
            if (a >= b) total += a - b
            return true
        }
        while (i + 1 < charging.size) {
            if (!leg(cursor, charging[i])) return null
            cursor = maxOf(cursor, charging[i + 1])
            i += 2
        }
        if (!leg(cursor, end)) return null
        return total.takeIf { it > 0 }
    }

    /**
     * The window a level range picks out: the most recent single run on battery that
     * covers the whole of it.
     *
     * The search is bounded by one run on purpose. A phone that went 100 -> 50, charged to
     * 55 and then ran 55 -> 30 has no run that covers 70 -> 40, but the level samples
     * alone do: walked backwards they find 40 in the second run, keep walking for 70,
     * cross the charge and land in the first. The window that comes out spans the tail of
     * one run, a charge, and the head of another, and the report built on it counts the
     * charge as time on battery and reads as nonsense. Asked for a range no single run
     * covers, the honest answer is that the history does not hold it.
     */
    fun windowForLevels(index: DiagnosisIndex, fromPercent: Int, toPercent: Int): IntRange? {
        val hi = maxOf(fromPercent, toPercent)
        val lo = minOf(fromPercent, toPercent)
        if (index.levelSec.isEmpty()) return null

        // Newest first: a range like 40 -> 31 recurs every cycle, and the one being asked
        // about is all but always the last one.
        for (run in dischargeRuns(index).asReversed()) {
            descentWithin(index, run, hi, lo)?.let { return it }
        }
        return null
    }

    /**
     * The stretches this buffer spent off a charger, oldest first.
     *
     * Charges under [MIN_CHARGE_SEC] are not treated as breaks: a cable brushed into the
     * port writes a genuine `status=charging` and would otherwise cut a run in two, which
     * is the same blip the cycles card had to learn to ignore.
     */
    private fun dischargeRuns(index: DiagnosisIndex): List<IntRange> {
        val spans = significantCharges(index)
        val runs = ArrayList<IntRange>(8)
        var cursor = 0
        var i = 0
        while (i + 1 < spans.size) {
            if (spans[i] > cursor) runs.add(cursor..spans[i])
            if (spans[i + 1] > cursor) cursor = spans[i + 1]
            i += 2
        }
        if (index.endSec > cursor) runs.add(cursor..index.endSec)
        return runs
    }

    /**
     * The charges long enough to count, measured before any window clips them.
     *
     * Before, not after: a window that catches the last two minutes of an hour on the
     * charger has still caught an hour on the charger, and dropping it for being short
     * would put those two minutes back into the drain.
     */
    private fun significantCharges(index: DiagnosisIndex): IntArray {
        val spans = index.chargingSpans
        val out = ArrayList<Int>(spans.size)
        var i = 0
        while (i + 1 < spans.size) {
            if (spans[i + 1] - spans[i] >= MIN_CHARGE_SEC) {
                out.add(spans[i])
                out.add(spans[i + 1])
            }
            i += 2
        }
        return out.toIntArray()
    }

    /** The descent from [hi] down to [lo] inside one run, or null if the run has none. */
    private fun descentWithin(
        index: DiagnosisIndex,
        run: IntRange,
        hi: Int,
        lo: Int,
    ): IntRange? {
        val times = index.levelSec
        val levels = index.levelPercent
        // floorIndex at both ends, so the sample carrying the level *in force* when the
        // run opened is in scope. The gauge is only written down when it moves, so a run
        // that comes off the charger at 84% has no sample of its own saying 84 until the
        // battery reaches 83 — the last one that says it was written during the charge
        // before. Searching strictly inside the run, "from 84%" matched nothing in the
        // run it obviously meant and was answered by one a day older.
        val first = floorIndex(times, run.first)
        val last = floorIndex(times, run.last)
        if (first > last) return null

        var i = last
        while (i >= first && levels[i] > lo) i--
        if (i < first) return null

        // The window closes where the level first *reached* the floor, not at whatever
        // lower point it went on to. Asking about 40 -> 31 on a phone that carried on
        // down to 25 is asking about the nine percent, not the fifteen.
        var end = times[i]
        while (i >= first && levels[i] <= lo) {
            end = times[i]
            i--
        }

        // And it opens at the last sample still at the ceiling, which is the moment the
        // descent being asked about began.
        while (i >= first && levels[i] < hi) i--
        if (i < first) return null

        // Either of those samples can predate the run it was read through. The window
        // still belongs to the run: clamped, never widened past it.
        val from = maxOf(times[i], run.first)
        val to = maxOf(end, run.first)
        return if (to > from) from..to else null
    }

    /**
     * Ranked shares of [availableSec], grouped by the app that owns them.
     *
     * Grouped by uid, not by tag: one app takes a dozen differently-named locks over a
     * night and the question is which app, not which tag. The longest-held tag is carried
     * along as the reason. Spans the history left unowned fall back to grouping by tag,
     * which is all they have.
     */
    private fun slices(
        index: DiagnosisIndex,
        kind: EventKind,
        start: Int,
        end: Int,
        mask: IntArray?,
        availableSec: Int,
    ): List<AppSlice> {
        val spans = index.spansOf(kind) ?: return emptyList()
        if (availableSec <= 0) return emptyList()

        val byOwner = HashMap<Long, Owner>(64)
        for (i in 0 until spans.size) {
            val from = maxOf(spans.startSec[i], start)
            val to = minOf(spans.endSec[i], end)
            if (to <= from) continue
            val uid = spans.uid[i]
            val key = if (uid == PackedSpans.NO_UID) NAME_KEY or spans.nameId[i].toLong() else uid.toLong()
            val owner = byOwner.getOrPut(key) { Owner(uid) }
            owner.intervals.add(from)
            owner.intervals.add(to)
            // Whichever tag held the longest is the one worth printing beside the app.
            if (to - from > owner.bestSpan) {
                owner.bestSpan = to - from
                owner.nameId = spans.nameId[i]
            }
        }
        if (byOwner.isEmpty()) return emptyList()

        return byOwner.values
            .map { owner ->
                val intervals = owner.intervals.toIntArray()
                val masked = if (mask == null) intervals else intersect(intervals, mask)
                owner to span(masked)
            }
            .filter { it.second >= MIN_SLICE_SEC }
            .sortedByDescending { it.second }
            .take(TOP_N)
            .map { (owner, seconds) ->
                AppSlice(
                    uid = owner.uid,
                    tag = index.nameOf(owner.nameId),
                    durationMs = seconds * 1000L,
                    fraction = (seconds.toFloat() / availableSec).coerceIn(0f, 1f),
                )
            }
    }

    /** Accumulator for one app's spans while they are being gathered. */
    private class Owner(val uid: Int) {
        val intervals = ArrayList<Int>(8)
        var nameId = 0
        var bestSpan = -1
    }

    private fun systemSlices(
        index: DiagnosisIndex,
        start: Int,
        end: Int,
        onBattery: IntArray,
        onBatterySec: Int,
    ): List<FlagSlice> {
        if (onBatterySec <= 0) return emptyList()
        return SystemFlag.entries
            .map { flag -> flag to span(intersect(clip(index.flagOf(flag), start, end), onBattery)) }
            .filter { it.second >= MIN_SLICE_SEC }
            .sortedByDescending { it.second }
            .map { (flag, seconds) ->
                FlagSlice(
                    flag = flag,
                    durationMs = seconds * 1000L,
                    fraction = (seconds.toFloat() / onBatterySec).coerceIn(0f, 1f),
                )
            }
    }

    // --- interval arithmetic, all on flat [start, end, start, end, ...] arrays ---

    private fun clip(pairs: IntArray, start: Int, end: Int): IntArray {
        val out = ArrayList<Int>(pairs.size)
        var i = 0
        while (i + 1 < pairs.size) {
            val from = maxOf(pairs[i], start)
            val to = minOf(pairs[i + 1], end)
            if (to > from) {
                out.add(from)
                out.add(to)
            }
            i += 2
        }
        return out.toIntArray()
    }

    /** Total covered seconds, counting overlap once. */
    private fun span(pairs: IntArray): Int {
        if (pairs.isEmpty()) return 0
        val order = (0 until pairs.size / 2).sortedBy { pairs[it * 2] }
        var total = 0
        var openFrom = pairs[order[0] * 2]
        var openTo = pairs[order[0] * 2 + 1]
        for (k in 1 until order.size) {
            val from = pairs[order[k] * 2]
            val to = pairs[order[k] * 2 + 1]
            if (from <= openTo) {
                if (to > openTo) openTo = to
            } else {
                total += openTo - openFrom
                openFrom = from
                openTo = to
            }
        }
        return total + (openTo - openFrom)
    }

    private fun intersect(a: IntArray, b: IntArray): IntArray {
        if (a.isEmpty() || b.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>(minOf(a.size, b.size))
        var i = 0
        while (i + 1 < a.size) {
            var j = 0
            while (j + 1 < b.size) {
                val from = maxOf(a[i], b[j])
                val to = minOf(a[i + 1], b[j + 1])
                if (to > from) {
                    out.add(from)
                    out.add(to)
                }
                j += 2
            }
            i += 2
        }
        return out.toIntArray()
    }

    /** Everything in [start, end] that [pairs] does not cover. */
    private fun complement(pairs: IntArray, start: Int, end: Int): IntArray {
        val out = ArrayList<Int>(pairs.size + 2)
        val order = (0 until pairs.size / 2).sortedBy { pairs[it * 2] }
        var cursor = start
        for (k in order) {
            val from = pairs[k * 2]
            val to = pairs[k * 2 + 1]
            if (from > cursor) {
                out.add(cursor)
                out.add(from)
            }
            if (to > cursor) cursor = to
        }
        if (cursor < end) {
            out.add(cursor)
            out.add(end)
        }
        return out.toIntArray()
    }

    private fun levelAt(index: DiagnosisIndex, sec: Int): Int {
        val at = floorIndex(index.levelSec, sec)
        return index.levelPercent.getOrElse(at) { index.levelPercent.firstOrNull() ?: 0 }
    }

    private fun chargeAt(index: DiagnosisIndex, sec: Int): Int? {
        if (index.chargeSec.isEmpty()) return null
        val at = floorIndex(index.chargeSec, sec)
        return index.chargeMah.getOrNull(at)
    }

    /** Index of the last entry at or before [sec], or 0. */
    private fun floorIndex(times: IntArray, sec: Int): Int {
        var lo = 0
        var hi = times.size - 1
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= sec) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    /** Under a minute there is nothing to see: the gauge moves in whole percent. */
    const val MIN_WINDOW_SEC = 60

    /** Shorter than this and a charge is a cable brushed into the port, not a break. */
    private const val MIN_CHARGE_SEC = 5 * 60
}
