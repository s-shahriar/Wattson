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
        val screen = clip(index.flagOf(SystemFlag.SCREEN), start, end)
        val screenOnSec = span(screen)
        val screenOff = complement(screen, start, end)
        val screenOffSec = totalSec - screenOnSec

        val startLevel = levelAt(index, start)
        val endLevel = levelAt(index, end)
        val startMah = chargeAt(index, start)
        val endMah = chargeAt(index, end)
        val hours = totalSec / 3600.0

        val usedMah = if (startMah != null && endMah != null && startMah >= endMah) {
            startMah - endMah
        } else {
            null
        }

        return WindowReport(
            startMs = index.secondsToMs(start),
            endMs = index.secondsToMs(end),
            durationMs = totalSec * 1000L,
            startLevel = startLevel,
            endLevel = endLevel,
            usedPercent = (startLevel - endLevel).coerceAtLeast(0),
            usedMah = usedMah,
            mahPerHour = usedMah?.let { it / hours },
            percentPerHour = (startLevel - endLevel).coerceAtLeast(0) / hours,
            screenOnMs = screenOnSec * 1000L,
            screenOffMs = screenOffSec * 1000L,
            screenTime = slices(index, EventKind.FOREGROUND, start, end, screen, screenOnSec),
            keptAwake = slices(index, EventKind.WAKELOCK, start, end, screenOff, screenOffSec),
            system = systemSlices(index, start, end, totalSec),
        )
    }

    /** The window a level range picks out, most recent occurrence first. */
    fun windowForLevels(index: DiagnosisIndex, fromPercent: Int, toPercent: Int): IntRange? {
        val hi = maxOf(fromPercent, toPercent)
        val lo = minOf(fromPercent, toPercent)
        val times = index.levelSec
        val levels = index.levelPercent
        if (times.isEmpty()) return null

        // Walked backwards: a range like 40 -> 31 recurs every cycle, and the one being
        // asked about is all but always the last one.
        var i = times.size - 1
        while (i >= 0 && levels[i] > lo) i--
        if (i < 0) return null

        // The window closes where the level first *reached* the floor, not at whatever
        // lower point it went on to. Asking about 40 -> 31 on a phone that carried on
        // down to 25 is asking about the nine percent, not the fifteen.
        var end = times[i]
        while (i >= 0 && levels[i] <= lo) {
            end = times[i]
            i--
        }

        // And it opens at the last sample still at the ceiling, which is the moment the
        // descent being asked about began.
        while (i >= 0 && levels[i] < hi) i--
        if (i < 0) return null
        return times[i]..end
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
        totalSec: Int,
    ): List<FlagSlice> = SystemFlag.entries
        .map { flag -> flag to span(clip(index.flagOf(flag), start, end)) }
        .filter { it.second >= MIN_SLICE_SEC }
        .sortedByDescending { it.second }
        .map { (flag, seconds) ->
            FlagSlice(
                flag = flag,
                durationMs = seconds * 1000L,
                fraction = (seconds.toFloat() / totalSec).coerceIn(0f, 1f),
            )
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
}
