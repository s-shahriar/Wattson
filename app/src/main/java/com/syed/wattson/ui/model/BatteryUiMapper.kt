package com.syed.wattson.ui.model

import com.syed.wattson.data.DataTier
import com.syed.wattson.data.model.BatteryReport
import com.syed.wattson.data.model.LiveSnapshot
import com.syed.wattson.ui.util.bucketLabel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How many contributing apps to list beneath each power category. */
private const val CONTRIBUTORS_PER_CATEGORY = 4

/** How many apps the Top apps list shows. */
private const val TOP_APPS = 10

/** Hours of history the rolling chart covers. */
private const val HISTORY_SPAN_HOURS = 24

/** Column count — roughly one per 12 minutes over a 24-hour span. */
private const val HISTORY_COLUMNS = 120

/** Number of clock labels along the x-axis. */
private const val HISTORY_LABELS = 4

private const val MILLIS_PER_HOUR = 3_600_000.0

/**
 * Projects the domain report into [BatteryUiModel], resolving every share up front.
 * Kept as a pure function so it stays trivially testable.
 *
 * Tolerates a null [BatteryReport.stats]: on the basic tier there is no historical
 * accounting at all, and the corresponding sections simply receive empty data.
 */
fun BatteryReport.toUiModel(): BatteryUiModel {
    val snapshot = stats
    val buckets = snapshot?.globalBuckets.orEmpty()
    val totalCategoryMah = buckets.sumOf { it.mah }
    val maxCategoryMah = buckets.maxOfOrNull { it.mah } ?: 0.0
    val allApps = snapshot?.apps.orEmpty()
    val totalAppMah = allApps.sumOf { it.mah }

    val categories = buckets.map { bucket ->
        CategoryUi(
            key = bucket.name,
            label = bucketLabel(bucket.name),
            mah = bucket.mah,
            share = bucket.mah.safeShareOf(totalCategoryMah),
            relativeToMax = bucket.mah.safeShareOf(maxCategoryMah),
            durationMs = bucket.durationMs?.takeIf { it > 0L },
            contributors = allApps
                .mapNotNull { app -> app.mahFor(bucket.name)?.let { ContributorUi(app.label, it) } }
                .sortedByDescending { it.mah }
                .take(CONTRIBUTORS_PER_CATEGORY),
        )
    }

    val topApps = allApps
        .take(TOP_APPS)
        .mapIndexed { index, app ->
            AppUi(
                rank = index + 1,
                label = app.label,
                mah = app.mah,
                share = app.mah.safeShareOf(totalAppMah),
                icon = app.icon,
            )
        }

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
        dischargeMah = snapshot?.dischargeMah,
        designCapacityMah = snapshot?.designCapacityMah,
        screenOnMs = snapshot?.screenOnMs ?: 0L,
        screenOffMs = snapshot?.screenOffMs ?: 0L,
        screenOnFraction = snapshot?.screenOnFraction ?: 0f,
        screenOnCount = snapshot?.screenOnCount ?: 0,
        categories = categories,
        totalCategoryMah = totalCategoryMah,
        topApps = topApps,
        totalAppMah = totalAppMah,
        drain = toDrainUi(),
        charging = toChargingUi(),
        historyCycle = toCycleHistory(),
        historyDay = toDayHistory(),
    )
}

/** Splits on-battery power into the screen-on and screen-off halves, plus their rates. */
private fun BatteryReport.toDrainUi(): DrainUi? {
    val snapshot = stats ?: return null
    val byState = snapshot.powerByState
    val total = byState.totalOnBatteryMah
    if (total <= 0.0) return null

    return DrainUi(
        screenOnMah = byState.onBatteryScreenOnMah,
        screenOffMah = byState.onBatteryScreenOffMah,
        screenOnShare = byState.onBatteryScreenOnMah.safeShareOf(total),
        screenOffShare = byState.onBatteryScreenOffMah.safeShareOf(total),
        screenOnRateMa = byState.onBatteryScreenOnMah.perHour(snapshot.screenOnOnBatteryMs),
        screenOffRateMa = byState.onBatteryScreenOffMah.perHour(snapshot.screenOffMs),
        totalOnBatteryMah = total,
        chargingUsageMah = byState.totalChargingMah,
    )
}

private fun BatteryReport.toChargingUi(): ChargingUi = ChargingUi(
    status = now.status,
    isCharging = now.isCharging,
    currentMa = charging.currentMilliAmps,
    voltageVolts = charging.voltageVolts,
    healthFraction = charging.healthFraction,
    chargeFullMah = charging.chargeFullMah ?: stats?.designCapacityMah,
    designCapacityMah = charging.chargeFullDesignMah,
    cycleCount = charging.cycleCount,
    hoursToFull = charging.hoursToFull(),
)

/**
 * Buckets raw history samples into fixed-width columns over an arbitrary window.
 *
 * Levels are carried forward across empty slices so a gap in sampling shows the last
 * known level rather than dropping to zero; slices with no sample at all are flagged so
 * the chart can render them as "device off" instead of a real reading.
 */
private fun BatteryReport.toHistoryUi(
    startMs: Long,
    endMs: Long,
    spanLabel: String,
): HistoryUi? {
    if (history.isEmpty() || endMs <= startMs) return null

    val windowed = history.filter { it.timestampMs in startMs..endMs }
    if (windowed.size < 2) return null

    val sliceMs = (endMs - startMs).toDouble() / HISTORY_COLUMNS
    if (sliceMs <= 0.0) return null

    var carriedLevel = history.lastOrNull { it.timestampMs < startMs }?.level
        ?: windowed.first().level
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
            carriedLevel = point.level
            if (point.charging) charging = true
            if (point.screenOn) screenOn = true
            seen = true
            cursor++
        }

        HistoryColumn(
            level = carriedLevel,
            charging = charging,
            screenOn = screenOn,
            hasData = seen,
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
 */
private fun BatteryReport.toCycleHistory(): HistoryUi? {
    val endMs = history.lastOrNull()?.timestampMs ?: return null
    val cycleStart = parseStartClock(stats?.startClock) ?: return null
    val oldestSample = history.first().timestampMs
    return toHistoryUi(
        startMs = maxOf(cycleStart, oldestSample),
        endMs = endMs,
        spanLabel = "This cycle",
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

/** mAh spread over a duration, expressed as an average milliamp draw. */
private fun Double.perHour(durationMs: Long): Double? {
    if (durationMs <= 0L) return null
    val hours = durationMs / MILLIS_PER_HOUR
    return if (hours <= 0.0) null else this / hours
}

/** Division guarded against a zero or negative denominator. */
private fun Double.safeShareOf(total: Double): Float =
    if (total <= 0.0) 0f else (this / total).toFloat().coerceIn(0f, 1f)

/**
 * Folds a cheap live poll into an existing model, touching only the fields the status
 * card reads. The historical sections are left untouched, so a 5-second tick never
 * re-runs or invalidates the expensive dump.
 */
fun BatteryUiModel.withLive(snapshot: LiveSnapshot): BatteryUiModel = copy(
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
        chargeFullMah = snapshot.charging.chargeFullMah ?: charging.chargeFullMah,
        designCapacityMah = snapshot.charging.chargeFullDesignMah ?: charging.designCapacityMah,
        cycleCount = snapshot.charging.cycleCount ?: charging.cycleCount,
        hoursToFull = snapshot.charging.hoursToFull(),
    ),
)

/** True when the tier can supply historical accounting at all. */
val BatteryUiModel.hasHistoricalStats: Boolean get() = tier != DataTier.BASIC
