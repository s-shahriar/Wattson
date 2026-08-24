package com.syed.wattson.ui.model

import com.syed.wattson.data.DataTier
import com.syed.wattson.data.model.BatteryReport
import com.syed.wattson.data.model.LiveSnapshot
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Hours of history the rolling chart covers. */
private const val HISTORY_SPAN_HOURS = 24

/** Column count — roughly one per 12 minutes over a 24-hour span. */
private const val HISTORY_COLUMNS = 120

/** Number of clock labels along the x-axis. */
private const val HISTORY_LABELS = 4

private const val MILLIS_PER_HOUR = 3_600_000.0

/**
 * Widest window the cycle chart will draw, however old the cycle claims to be.
 *
 * A week over 120 columns is already 84 minutes a column; past that the chart stops
 * carrying information, and the only things that reach back further are a broken clock.
 */
private const val MAX_CYCLE_SPAN_MS = 7L * 24 * 3_600_000

/**
 * Projects the domain report into [BatteryUiModel], resolving every share up front.
 * Kept as a pure function so it stays trivially testable.
 *
 * Tolerates a null [BatteryReport.stats]: on the basic tier there is no historical
 * accounting at all, and the corresponding sections simply receive empty data.
 */
fun BatteryReport.toUiModel(): BatteryUiModel {
    val snapshot = stats
    val drain = toDrainUi()
    // One capacity behind all three session rates, so they stay comparable with each
    // other and with the "Discharged" figure they are derived from.
    val fullMah = (snapshot?.designCapacityMah ?: charging.chargeFullMah)?.takeIf { it > 0 }

    return BatteryUiModel(
        tier = tier,
        levelPercent = now.levelPercent,
        status = now.status,
        health = now.health,
        temperatureC = now.temperatureC,
        chargeCounterMah = now.chargeCounterMah,
        startClock = snapshot?.startClock,
        timeOnBatteryMs = snapshot?.timeOnBatteryMs ?: 0L,
        totalRunTimeMs = snapshot?.totalRunTimeMs ?: 0L,
        avgDrainPercentPerHour =
            snapshot?.dischargeMah.percentPerHour(fullMah, snapshot?.timeOnBatteryMs),
        screenOnDrainPercentPerHour =
            drain?.screenOnMah.percentPerHour(fullMah, snapshot?.screenOnOnBatteryMs),
        screenOffDrainPercentPerHour =
            drain?.screenOffMah.percentPerHour(fullMah, snapshot?.screenOffMs),
        dischargeMah = snapshot?.dischargeMah,
        screenOnMs = snapshot?.screenOnMs ?: 0L,
        screenOffMs = snapshot?.screenOffMs ?: 0L,
        screenOnFraction = snapshot?.screenOnFraction ?: 0f,
        screenOnCount = snapshot?.screenOnCount ?: 0,
        drain = drain,
        charging = toChargingUi(),
        historyCycle = toCycleHistory(),
        historyDay = toDayHistory(),
    )
}

/**
 * Splits on-battery drain into the screen-on and screen-off halves, plus their rates.
 *
 * Prefers the coulomb-counter figures ("Screen on discharge: 1463 mAh") over the
 * power-profile model. The model only sums the buckets it can attribute, which on a
 * device without per-UID CPU tracking is a small fraction of real drain — reporting it
 * here claimed 242 mAh for a cycle that actually took 2111 mAh out of the cell.
 */
private fun BatteryReport.toDrainUi(): DrainUi? {
    val snapshot = stats ?: return null
    val measured = snapshot.measured

    val screenOn: Double
    val screenOff: Double
    val fromMeasurement: Boolean

    if (measured?.screenOnMah != null && measured.screenOffMah != null) {
        screenOn = measured.screenOnMah
        // Doze is reported separately but is still screen-off time in the cell's view.
        screenOff = measured.screenOffMah + (measured.screenDozeMah ?: 0.0)
        fromMeasurement = true
    } else {
        val byState = snapshot.powerByState
        screenOn = byState.onBatteryScreenOnMah
        screenOff = byState.onBatteryScreenOffMah
        fromMeasurement = false
    }

    val total = screenOn + screenOff
    if (total <= 0.0) return null

    return DrainUi(
        screenOnMah = screenOn,
        screenOffMah = screenOff,
        screenOnShare = screenOn.safeShareOf(total),
        screenOffShare = screenOff.safeShareOf(total),
        totalOnBatteryMah = total,
        chargingUsageMah = snapshot.powerByState.totalChargingMah,
        fromMeasurement = fromMeasurement,
    )
}

/**
 * Charge as a percentage of [fullMah], spread over [durationMs].
 *
 * Deliberately percent rather than mAh per hour: the latter is the same quantity as a
 * milliamp draw, which the status card already reports, so the tiles would have restated
 * a number rather than added one. Against the cell's capacity it becomes a figure you can
 * divide the remaining charge by.
 *
 * Each screen state is measured over *its own* time, not the whole cycle — the point of
 * splitting them is to compare a screen-on hour against a screen-off hour.
 *
 * Null unless all three inputs are known and the window is non-empty: a rate over zero
 * elapsed time is not a small number, it is no number.
 */
private fun Double?.percentPerHour(fullMah: Int?, durationMs: Long?): Double? {
    val mah = this ?: return null
    val capacity = fullMah ?: return null
    val ms = durationMs ?: return null
    if (ms <= 0L) return null
    return (mah / capacity * 100.0) / (ms / MILLIS_PER_HOUR)
}

private fun BatteryReport.toChargingUi(): ChargingUi {
    // Resolved once so the tile and the ETA agree; computing the ETA straight off
    // ChargingInfo used a field only the rooted path ever fills, which blanked
    // "Until full" for the whole of every charge on an unrooted device.
    val fullMah = charging.chargeFullMah
        ?: stats?.designCapacityMah
        ?: charging.inferredFullMah(now.levelPercent)

    return ChargingUi(
        status = now.status,
        isCharging = now.isCharging,
        currentMa = charging.currentMilliAmps,
        voltageVolts = charging.voltageVolts,
        healthFraction = charging.healthFraction,
        chargeFullMah = fullMah,
        designCapacityMah = charging.chargeFullDesignMah,
        cycleCount = charging.cycleCount,
        hoursRemaining = charging.hoursRemaining(now.isCharging, fullMah),
    )
}

/**
 * Buckets raw history samples into fixed-width columns over an arbitrary window.
 *
 * Levels are carried forward across empty slices so a gap in sampling shows the last
 * known level rather than dropping to zero; slices with no sample at all are flagged so
 * the chart can render them as "device off" instead of a real reading.
 *
 * [fillGaps] suppresses that flagging, and carries the charge and screen state forward
 * as well. It is for windows the device is known to have been running through, where an
 * empty slice only means nothing changed — see [toCycleHistory].
 */
private fun BatteryReport.toHistoryUi(
    startMs: Long,
    endMs: Long,
    spanLabel: String,
    fillGaps: Boolean = false,
): HistoryUi? {
    if (history.isEmpty() || endMs <= startMs) return null

    val windowed = history.filter { it.timestampMs in startMs..endMs }

    // A window needs one known state to start from: either a sample inside it, or the
    // last one before it to carry in. Requiring two samples *inside* is what made a
    // minutes-old cycle — which has barely had time to log a level change — resolve to
    // nothing and silently hand the chart over to the rolling 24-hour window.
    var carried = history.lastOrNull { it.timestampMs < startMs }
        ?: windowed.firstOrNull()
        ?: return null
    if (!fillGaps && windowed.size < 2) return null

    val sliceMs = (endMs - startMs).toDouble() / HISTORY_COLUMNS
    if (sliceMs <= 0.0) return null

    var cursor = 0

    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("h:mm a")

    val columns = (0 until HISTORY_COLUMNS).map { index ->
        val sliceEnd = startMs + ((index + 1) * sliceMs).toLong()
        var charging = false
        var screenOn = false
        var seen = false

        while (cursor < windowed.size && windowed[cursor].timestampMs <= sliceEnd) {
            val point = windowed[cursor]
            carried = point
            if (point.charging) charging = true
            if (point.screenOn) screenOn = true
            seen = true
            cursor++
        }

        val inherited = fillGaps && !seen
        HistoryColumn(
            level = carried.level,
            charging = if (inherited) carried.charging else charging,
            screenOn = if (inherited) carried.screenOn else screenOn,
            hasData = seen || fillGaps,
            timeLabel = Instant.ofEpochMilli(sliceEnd).atZone(zone).format(formatter),
        )
    }

    val labels = (0 until HISTORY_LABELS).map { index ->
        val at = startMs + (endMs - startMs) * index / (HISTORY_LABELS - 1)
        Instant.ofEpochMilli(at).atZone(zone).format(formatter)
    }

    return HistoryUi(
        columns = columns,
        timeLabels = labels,
        spanLabel = spanLabel,
        durationMs = endMs - startMs,
    )
}

/** Rolling 24-hour window, ending at the newest sample. */
private fun BatteryReport.toDayHistory(): HistoryUi? {
    val endMs = history.lastOrNull()?.timestampMs ?: return null
    return toHistoryUi(
        startMs = endMs - HISTORY_SPAN_HOURS * MILLIS_PER_HOUR.toLong(),
        endMs = endMs,
        spanLabel = "Last ${HISTORY_SPAN_HOURS}h",
    )
}

/**
 * The ongoing battery cycle — everything since the stats last reset, which is the same
 * window the rest of the screen reports on. Clamped to the oldest sample the history
 * buffer still holds, since that buffer is finite and can be shorter than the cycle.
 *
 * Runs to the capture instant rather than to the newest history record: a cycle a few
 * minutes old has logged one level change at most, so ending on that record cut the
 * chart off minutes before the "On battery" figure beside it. Gaps are filled for the
 * same reason — inside one cycle the device has been awake and unplugged throughout, so
 * a slice with no record of its own is a quiet slice, not a powered-off one.
 */
private fun BatteryReport.toCycleHistory(): HistoryUi? {
    val newestSample = history.lastOrNull()?.timestampMs ?: return null
    val cycleStart = parseStartClock(stats?.startClock) ?: return null
    val oldestSample = history.first().timestampMs
    val endMs = maxOf(capturedAtMs, newestSample)
    return toHistoryUi(
        // The floor is not only about a cycle outliving the history buffer. The start
        // clock is wall clock as it read when the cycle began, and pulling the battery
        // cuts power to the RTC — so the first cycle after a swap reports having started
        // in 1970, and a window eight months wide renders as one flat line with everything
        // that actually happened crushed into the last column.
        startMs = maxOf(cycleStart, oldestSample, endMs - MAX_CYCLE_SPAN_MS),
        endMs = endMs,
        spanLabel = "This cycle",
        fillGaps = true,
    )
}

/** Parses the batterystats reset stamp, e.g. "2026-08-12-13-01-16". */
private fun parseStartClock(raw: String?): Long? {
    val parts = raw?.split("-") ?: return null
    if (parts.size < 6) return null
    val numbers = parts.take(6).map { it.toIntOrNull() ?: return null }
    return runCatching {
        LocalDateTime.of(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5])
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}

/** Division guarded against a zero or negative denominator. */
private fun Double.safeShareOf(total: Double): Float =
    if (total <= 0.0) 0f else (this / total).toFloat().coerceIn(0f, 1f)

/**
 * Folds a cheap live poll into an existing model, touching only the fields the status
 * card reads. The historical sections are left untouched, so a 5-second tick never
 * re-runs or invalidates the expensive dump.
 */
fun BatteryUiModel.withLive(snapshot: LiveSnapshot): BatteryUiModel {
    // Carry forward the capacity already resolved by the full load: the live snapshot
    // alone never carries one, and the poll must not blank a tile the dump had filled.
    val fullMah = snapshot.charging.chargeFullMah
        ?: charging.chargeFullMah
        ?: snapshot.charging.inferredFullMah(snapshot.now.levelPercent)

    return copy(
        levelPercent = snapshot.now.levelPercent,
        status = snapshot.now.status,
        health = snapshot.now.health,
        temperatureC = snapshot.now.temperatureC,
        chargeCounterMah = snapshot.now.chargeCounterMah,
        charging = ChargingUi(
            status = snapshot.now.status,
            isCharging = snapshot.now.isCharging,
            currentMa = snapshot.charging.currentMilliAmps,
            voltageVolts = snapshot.charging.voltageVolts,
            healthFraction = snapshot.charging.healthFraction ?: charging.healthFraction,
            chargeFullMah = fullMah,
            designCapacityMah = snapshot.charging.chargeFullDesignMah ?: charging.designCapacityMah,
            cycleCount = snapshot.charging.cycleCount ?: charging.cycleCount,
            hoursRemaining = snapshot.charging.hoursRemaining(snapshot.now.isCharging, fullMah),
        ),
    )
}

/** True when the tier can supply historical accounting at all. */
val BatteryUiModel.hasHistoricalStats: Boolean get() = tier != DataTier.BASIC

/**
 * Builds a model from just the live snapshot, with every historical figure empty.
 *
 * Used for the first paint: BatteryManager answers in microseconds, so the status card
 * can be on screen while the multi-second dumpsys reads are still running. The sections
 * that need those reads hide themselves until the full model replaces this one.
 */
fun LiveSnapshot.toLiveOnlyUiModel(tier: DataTier): BatteryUiModel {
    // No dumpsys here, so design capacity is unavailable and inference is all there is.
    val fullMah = charging.chargeFullMah ?: charging.inferredFullMah(now.levelPercent)

    return BatteryUiModel(
        tier = tier,
        levelPercent = now.levelPercent,
        status = now.status,
        health = now.health,
        temperatureC = now.temperatureC,
        chargeCounterMah = now.chargeCounterMah,
        startClock = null,
        timeOnBatteryMs = 0L,
        totalRunTimeMs = 0L,
        avgDrainPercentPerHour = null,
        screenOnDrainPercentPerHour = null,
        screenOffDrainPercentPerHour = null,
        dischargeMah = null,
        screenOnMs = 0L,
        screenOffMs = 0L,
        screenOnFraction = 0f,
        screenOnCount = 0,
        drain = null,
        charging = ChargingUi(
            status = now.status,
            isCharging = now.isCharging,
            currentMa = charging.currentMilliAmps,
            voltageVolts = charging.voltageVolts,
            healthFraction = charging.healthFraction,
            chargeFullMah = fullMah,
            designCapacityMah = charging.chargeFullDesignMah,
            cycleCount = charging.cycleCount,
            hoursRemaining = charging.hoursRemaining(now.isCharging, fullMah),
        ),
        historyCycle = null,
        historyDay = null,
    )
}
