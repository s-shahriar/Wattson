package com.syed.wattson.ui.model

import com.syed.wattson.data.model.HistoryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How many past cycles the card shows. */
private const val MAX_CYCLES = 7

/**
 * Floors below which an unplugged run is a top-up blip rather than a cycle.
 *
 * A phone that spends the day on and off a charger logs dozens of runs — this device had
 * thirty-one in six days, most of them a couple of minutes and two percent. Listing those
 * newest-first fills the card with rows that say nothing, and buries the overnight runs
 * that actually describe how the battery behaves.
 */
private const val MIN_CYCLE_MS = 15L * 60_000
private const val MIN_CYCLE_DROP_PERCENT = 5

/**
 * Floors below which a charge is not a charge, and the runs either side of it are one run.
 *
 * A cable brushed into the port, a laptop socket that negotiates and gives up, a charger
 * switched off at the wall a moment after it was switched on: each of these writes a
 * genuine `status=charging` into the history and each ends a run. One of them cut a
 * twenty-two hour cycle in half — a minute plugged into AC at 41% that gave back a single
 * percent, and the card then reported 16h38/59% and 5h18/42% as separate cycles, neither
 * of which the phone had actually had.
 *
 * The two thresholds are far from anything real. Across this device's buffer every
 * accidental charge lasted three minutes or less, and every deliberate one lasted eleven
 * minutes or more; the gain test is there for the fast charger that puts back a tenth of
 * the battery before five minutes are up.
 */
private const val MIN_CHARGE_MS = 5L * 60_000
private const val MIN_CHARGE_GAIN_PERCENT = 5

/**
 * Reduces history samples into the completed unplugged runs behind the cycles card.
 *
 * A run opens on the first discharging sample and closes on the next charge worth the
 * name, so "cycle" here means one stretch on battery, not one charge of the cell.
 * Durations come from the gaps between samples, attributed to whichever screen state was
 * in force at the start of each gap — which is exact, since the reducer only emits a
 * sample when one of those states actually changes.
 *
 * Two runs are deliberately never returned:
 *  - the one still in progress. It has no end, and the session card above already reports
 *    it in more detail than this card could.
 *  - the one holding the oldest sample, when the history begins mid-discharge. Its start
 *    level and its durations are both clipped by the buffer, so its row would understate
 *    a cycle rather than describe one.
 *
 * Pure and cheap: one pass over a couple of thousand samples, nothing retained.
 */
fun summarizeCycles(
    points: List<HistoryPoint>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<CycleUi> {
    if (points.size < 2) return emptyList()

    val segments = ArrayList<Segment>()
    var open: Segment? = null

    points.forEachIndexed { index, point ->
        if (point.charging) {
            open = null
            return@forEachIndexed
        }
        val segment = open ?: Segment(point.timestampMs, point.level)
            .also { open = it; segments.add(it) }
        segment.endMs = point.timestampMs
        segment.endLevel = point.level

        // A segment ends at its last discharging sample, not at the charging one that
        // closes it. The gap between the two is not always the second it takes to notice
        // a plugged cable: a phone that dies at 0% logs nothing until it boots again, by
        // which time it is charging and back at 100. Reading the level off that sample
        // turned a 93% discharge into a 7% gain, and the run vanished from the card.
        val next = points.getOrNull(index + 1) ?: return@forEachIndexed
        if (next.charging) return@forEachIndexed

        val delta = next.timestampMs - point.timestampMs
        if (delta > 0) {
            if (point.screenOn) segment.screenOnMs += delta else segment.screenOffMs += delta
        }
    }

    val runs = ArrayList<Run>()
    segments.forEach { segment ->
        val last = runs.lastOrNull()
        if (last != null && last.wasInterruptedBy(segment)) last.absorb(segment) else runs.add(Run(segment))
    }
    // `open` is whatever segment is still going; the run holding it is the one to leave out.
    // removeAt, not removeLast: List.removeLast resolves to the Java 21 SequencedCollection
    // method and throws NoSuchMethodError below API 35.
    if (open != null && runs.isNotEmpty()) runs.removeAt(runs.size - 1)

    val complete = runs.filter { it.startMs != points.first().timestampMs }
    val formatter = DateTimeFormatter.ofPattern("d MMM h:mm a")

    val kept = complete
        .filter { it.onBatteryMs >= MIN_CYCLE_MS && it.usedPercent >= MIN_CYCLE_DROP_PERCENT }
        .takeLast(MAX_CYCLES)

    return kept.asReversed().map { run ->
        CycleUi(
            label = Instant.ofEpochMilli(run.startMs).atZone(zone).format(formatter),
            screenOnMs = run.screenOnMs,
            screenOffMs = run.screenOffMs,
            onBatteryMs = run.onBatteryMs,
            // A run that swallowed a blip can have spent a percent more than the cell
            // holds, having been handed that percent back in the middle. True, and it
            // reads as a bug.
            usedPercent = run.usedPercent.coerceAtMost(100),
            screenOnFraction = run.screenOnMs.toFloat() / run.onBatteryMs,
        )
    }
}

/** One unbroken stretch of discharging samples, accumulated in place while they are walked. */
private class Segment(val startMs: Long, val startLevel: Int) {
    var endMs = startMs
    var endLevel = startLevel
    var screenOnMs = 0L
    var screenOffMs = 0L

    /** Never negative: a gauge that reads higher after an hour on battery is noise. */
    val dropPercent: Int get() = (startLevel - endLevel).coerceAtLeast(0)
}

/** One unplugged run: a segment, plus any that a blip rather than a charge separated from it. */
private class Run(first: Segment) {
    val startMs = first.startMs
    private var endMs = first.endMs
    private var endLevel = first.endLevel
    var screenOnMs = first.screenOnMs
        private set
    var screenOffMs = first.screenOffMs
        private set
    var usedPercent = first.dropPercent
        private set

    /**
     * Time unplugged. Summed from the segments rather than measured end to end, so the
     * minute spent plugged into a charger that did nothing is not counted as time on
     * battery.
     */
    val onBatteryMs: Long get() = screenOnMs + screenOffMs

    fun wasInterruptedBy(next: Segment): Boolean =
        next.startMs - endMs < MIN_CHARGE_MS &&
            next.startLevel - endLevel < MIN_CHARGE_GAIN_PERCENT

    fun absorb(next: Segment) {
        endMs = next.endMs
        endLevel = next.endLevel
        screenOnMs += next.screenOnMs
        screenOffMs += next.screenOffMs
        usedPercent += next.dropPercent
    }
}
