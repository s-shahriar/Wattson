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
 * Reduces history samples into the completed unplugged runs behind the cycles card.
 *
 * A run opens on the first discharging sample and closes on the next charging one, so
 * "cycle" here means one stretch on battery, not one charge of the cell. Durations come
 * from the gaps between samples, attributed to whichever screen state was in force at the
 * start of each gap — which is exact, since the reducer only emits a sample when one of
 * those states actually changes.
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

    val runs = ArrayList<Run>()
    var open: Run? = null

    points.forEachIndexed { index, point ->
        if (point.charging) {
            open?.let(runs::add)
            open = null
            return@forEachIndexed
        }
        val run = open ?: Run(point.timestampMs, point.level).also { open = it }
        run.endMs = point.timestampMs
        run.endLevel = point.level

        // A run ends at its last discharging sample, not at the charging one that closes
        // it. The gap between the two is not always the second it takes to notice a
        // plugged cable: a phone that dies at 0% logs nothing until it boots again, by
        // which time it is charging and back at 100. Reading the level off that sample
        // turned a 93% discharge into a 7% gain, and the run vanished from the card.
        val next = points.getOrNull(index + 1) ?: return@forEachIndexed
        if (next.charging) return@forEachIndexed

        val delta = next.timestampMs - point.timestampMs
        if (delta > 0) {
            if (point.screenOn) run.screenOnMs += delta else run.screenOffMs += delta
        }
    }
    // `open` is whatever run is still going, which is exactly the one to leave out.

    val complete = runs.filter { it.startMs != points.first().timestampMs }
    val formatter = DateTimeFormatter.ofPattern("d MMM h:mm a")

    val kept = complete
        .filter { it.durationMs >= MIN_CYCLE_MS && it.dropPercent >= MIN_CYCLE_DROP_PERCENT }
        .takeLast(MAX_CYCLES)

    // The bar is scaled against the longest run on show, so the card always uses its full
    // width and rows stay comparable with each other rather than with some fixed span.
    val longest = kept.maxOfOrNull { it.durationMs }?.takeIf { it > 0L } ?: return emptyList()

    return kept.asReversed().map { run ->
        CycleUi(
            label = Instant.ofEpochMilli(run.startMs).atZone(zone).format(formatter),
            screenOnMs = run.screenOnMs,
            screenOffMs = run.screenOffMs,
            onBatteryMs = run.durationMs,
            usedPercent = run.dropPercent,
            screenOnFraction = run.screenOnMs.toFloat() / run.durationMs,
            lengthFraction = run.durationMs.toFloat() / longest,
        )
    }
}

/** One unplugged run, accumulated in place while the samples are walked. */
private class Run(val startMs: Long, private val startLevel: Int) {
    var endMs = startMs
    var endLevel = startLevel
    var screenOnMs = 0L
    var screenOffMs = 0L

    val durationMs: Long get() = endMs - startMs

    /** Never negative: a gauge that reads higher after an hour on battery is noise. */
    val dropPercent: Int get() = (startLevel - endLevel).coerceAtLeast(0)
}
