package com.syed.wattson.data

import android.content.Context
import com.syed.wattson.data.model.BatteryNow
import com.syed.wattson.data.model.BatteryReport
import com.syed.wattson.data.model.ChargingInfo
import com.syed.wattson.data.model.HistoryPoint
import com.syed.wattson.data.model.LiveSnapshot
import com.syed.wattson.data.model.RootUnavailableException
import com.syed.wattson.data.model.StatsUnavailableException
import com.syed.wattson.data.model.BatteryStats
import com.syed.wattson.data.parser.BatteryHistoryParser
import com.syed.wattson.data.parser.BatteryStatsParser
import com.syed.wattson.data.source.LiveBatterySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Loads battery data on demand.
 *
 * Two entry points with very different costs:
 *  - [load] runs the full `dumpsys batterystats` dump (seconds, megabytes of text) and is
 *    only called for an explicit refresh.
 *  - [loadLive] reads a handful of sysfs nodes in one shell round-trip (milliseconds) and
 *    backs the 5-second foreground polling.
 *
 * Nothing here schedules itself; both are pull-only.
 */
class BatteryRepository(
    private val context: Context,
    private val shell: Shell = Shell,
) {

    private val capabilities = Capabilities(context, shell)
    private val liveSource = LiveBatterySource(context)

    /** Cached so a non-rooted device is not re-probed (and re-prompted) on every load. */
    @Volatile
    private var tier: DataTier? = null

    fun currentTier(): DataTier = tier ?: capabilities.detect().also { tier = it }

    /**
     * Full report for whatever tier this device supports.
     *
     * Never throws for a missing tier: BASIC simply returns live figures with no
     * historical stats, and the UI hides the sections it cannot fill.
     *
     * @throws StatsUnavailableException when dumpsys was reachable but produced nothing.
     */
    suspend fun load(): BatteryReport = withContext(Dispatchers.IO) {
        val activeTier = currentTier()

        // The two dumps are independent reads, and the history one alone costs ~6s.
        // Running them concurrently overlaps that with the --charged dump instead of
        // paying for both end to end. Same commands, same output, same accuracy.
        coroutineScope {
            val live = async { readLive(activeTier) }
            val stats = async {
                if (activeTier == DataTier.BASIC) null else readDumpsysStats(activeTier)
            }
            val history = async {
                if (activeTier == DataTier.BASIC) emptyList() else loadHistory(activeTier)
            }

            val snapshot = live.await()
            BatteryReport(
                tier = activeTier,
                now = snapshot.now,
                stats = stats.await(),
                charging = snapshot.charging,
                history = history.await(),
                capturedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Runs a command with whatever elevation the tier provides.
     *
     * ROOT goes through `su`. PRIVILEGED runs in the app's own shell, which suffices
     * because `dumpsys` executes as the caller and each service checks the caller's DUMP
     * permission — so the text is identical either way.
     */
    private fun runShell(command: String, activeTier: DataTier, timeoutSeconds: Long): Shell.Result =
        if (activeTier == DataTier.ROOT) {
            shell.runAsRoot(command, timeoutSeconds)
        } else {
            shell.runPlain(command, timeoutSeconds)
        }

    /** Full dumpsys parse — identical on ROOT and PRIVILEGED. */
    private fun readDumpsysStats(activeTier: DataTier): BatteryStats {
        val statsDump = runShell(CMD_BATTERY_STATS, activeTier, STATS_TIMEOUT_SECONDS)
        if (!statsDump.ok || statsDump.out.isBlank()) {
            throw StatsUnavailableException(
                statsDump.error.ifBlank { "dumpsys batterystats returned nothing" }
            )
        }
        return BatteryStatsParser.parseStats(statsDump.out)
    }

    /**
     * Battery level history, reduced on-device before it crosses the shell boundary.
     *
     * The raw dump is ~300k lines; the awk filter emits one record per actual change in
     * level, charge state or screen state, which brings it to a couple of thousand.
     * Returns empty rather than throwing — the chart simply hides if history is absent.
     */
    private fun loadHistory(activeTier: DataTier): List<HistoryPoint> {
        val dump = runShell(CMD_HISTORY, activeTier, HISTORY_TIMEOUT_SECONDS)
        if (!dump.ok || dump.out.isBlank()) return emptyList()
        return runCatching { BatteryHistoryParser.parse(dump.out) }.getOrDefault(emptyList())
    }

    /**
     * Cheap poll of just the live values — level, status, temperature and charge rate.
     *
     * Returns null when the read fails so the caller can simply keep the previous values
     * rather than flashing an error for a transient hiccup.
     */
    suspend fun loadLive(): LiveSnapshot? = withContext(Dispatchers.IO) {
        runCatching { readLive(currentTier()) }.getOrNull()
    }

    /**
     * One shell round-trip over `/sys/class/power_supply/battery`.
     *
     * Everything both live cards need comes from here, so the poll never has to touch
     * `dumpsys` — which would cost orders of magnitude more per tick.
     */
    /**
     * Live values come from public APIs on every tier — no privilege needed, and it keeps
     * the 5-second poll from forking a `su` process. On rooted devices the exact
     * charge_full/design pair is layered on top, since sysfs is more precise than the
     * platform's rounded state-of-health percentage.
     */
    private fun readLive(activeTier: DataTier): LiveSnapshot {
        val snapshot = liveSource.read()
        if (activeTier != DataTier.ROOT) return snapshot
        return runCatching { snapshot.withSysfsCapacity() }.getOrDefault(snapshot)
    }

    private fun LiveSnapshot.withSysfsCapacity(): LiveSnapshot {
        val dump = shell.runAsRoot(CMD_POWER_SUPPLY, timeoutSeconds = LIVE_TIMEOUT_SECONDS)
        val values = dump.out.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .toMap()

        fun intOf(key: String): Int? = values[key]?.toIntOrNull()

        return copy(
            charging = charging.copy(
                chargeFullMah = intOf("charge_full")?.div(MICRO_PER_MILLI),
                chargeFullDesignMah = intOf("charge_full_design")?.div(MICRO_PER_MILLI),
                cycleCount = intOf("cycle_count") ?: charging.cycleCount,
            ),
        )
    }

    private companion object {
        const val CMD_BATTERY_STATS = "dumpsys batterystats --charged"
        const val STATS_TIMEOUT_SECONDS = 60L
        const val HISTORY_TIMEOUT_SECONDS = 45L

        /**
         * Reduces the history dump to one line per change: `MM-DD HH:MM:SS.mmm LLL C|D 1|0`.
         *
         * `not-charging` is folded into `D` because chargers negotiate constantly and the
         * raw state flaps between them several times a second, which would otherwise
         * render as visual noise.
         */
        val CMD_HISTORY = """
            dumpsys batterystats --history | awk '
            {
              lvl = ${'$'}3
              if (lvl !~ /^[0-9][0-9][0-9]${'$'}/) next
              if (${'$'}0 ~ /status=charging/ || ${'$'}0 ~ /status=full/) st = "C"
              else if (${'$'}0 ~ /status=discharging/ || ${'$'}0 ~ /status=not-charging/) st = "D"
              if (${'$'}0 ~ / \+screen([^_a-zA-Z]|${'$'})/) sc = 1
              if (${'$'}0 ~ / -screen([^_a-zA-Z]|${'$'})/) sc = 0
              key = lvl "|" st "|" sc
              if (key != prev) { printf "%s %s %s %s %s\n", ${'$'}1, ${'$'}2, lvl, st, sc; prev = key }
            }'
        """.trimIndent()
        const val LIVE_TIMEOUT_SECONDS = 10L
        const val MICRO_PER_MILLI = 1_000

        /** Emits `key=value` lines for the nodes we care about, skipping any that are absent. */
        const val CMD_POWER_SUPPLY = """
            cd /sys/class/power_supply/battery 2>/dev/null || exit 1
            for f in capacity status health temp current_now voltage_now \
                     charge_full charge_full_design charge_counter cycle_count; do
              [ -r "${'$'}f" ] && printf '%s=%s\n' "${'$'}f" "${'$'}(cat ${'$'}f 2>/dev/null)"
            done
        """
    }
}
