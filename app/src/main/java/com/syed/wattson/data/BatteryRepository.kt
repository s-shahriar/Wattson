package com.syed.wattson.data

import android.content.Context
import com.syed.wattson.data.model.BatteryNow
import com.syed.wattson.data.model.BatteryReport
import com.syed.wattson.data.model.ChargingInfo
import com.syed.wattson.data.model.DiagnosisIndex
import com.syed.wattson.data.model.HistoryPoint
import com.syed.wattson.data.model.LiveSnapshot
import com.syed.wattson.data.model.RootUnavailableException
import com.syed.wattson.data.model.StatsUnavailableException
import com.syed.wattson.data.model.BatteryStats
import com.syed.wattson.data.parser.BatteryHistoryReducer
import com.syed.wattson.data.parser.BatteryStatsParser
import com.syed.wattson.data.parser.DiagnosisIndexer
import com.syed.wattson.data.parser.DumpsysOutput
import com.syed.wattson.data.source.LiveBatterySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

        // The live read touches nothing dumpsys does, so it overlaps freely.
        coroutineScope {
            val live = async { readLive(activeTier) }

            // The two batterystats dumps do NOT overlap. They contend for the one
            // batterystats service lock, so running them together never actually
            // overlapped anything — it only put one of them in a queue, where dumpsys'
            // own per-service timeout ran out and killed it. That is what left the
            // --charged dump six lines long, with the session card and the cycle chart
            // silently hiding themselves for want of a "Start clock time".
            val stats = if (activeTier == DataTier.BASIC) null else readDumpsysStats(activeTier)
            val history = if (activeTier == DataTier.BASIC) emptyList() else loadHistory(activeTier)

            val snapshot = live.await()
            BatteryReport(
                tier = activeTier,
                now = snapshot.now,
                stats = stats,
                charging = snapshot.charging,
                history = history,
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

    /** [runShell] for a dump too large to hold, handing each line straight to [onLine]. */
    private fun streamShell(
        command: String,
        activeTier: DataTier,
        timeoutSeconds: Long,
        onLine: (String) -> Unit,
    ): Shell.Result =
        if (activeTier == DataTier.ROOT) {
            shell.streamAsRoot(command, timeoutSeconds, onLine)
        } else {
            shell.streamPlain(command, timeoutSeconds, onLine)
        }

    /** Full dumpsys parse — identical on ROOT and PRIVILEGED. */
    private fun readDumpsysStats(activeTier: DataTier): BatteryStats {
        val statsDump = runShell(CMD_BATTERY_STATS, activeTier, STATS_TIMEOUT_SECONDS)
        if (!statsDump.ok || statsDump.out.isBlank()) {
            throw StatsUnavailableException(
                statsDump.error.ifBlank { "dumpsys batterystats returned nothing" }
            )
        }
        // A dump dumpsys abandoned part-way through still parses: every figure it never
        // reached simply reads as absent, which the UI cannot tell apart from a device
        // that has none. Better an error the refresh can retry than a screen quietly
        // missing three of its cards.
        if (DumpsysOutput.isTruncated(statsDump.out)) {
            throw StatsUnavailableException("dumpsys cut the batterystats dump short")
        }
        return BatteryStatsParser.parseStats(statsDump.out)
    }

    /**
     * Battery level history, reduced line by line as the dump arrives.
     *
     * The raw dump is ~350k lines and over 20 MB; [BatteryHistoryReducer] keeps one sample
     * per actual change in level, charge state or screen state, which brings it to a
     * couple of thousand. Nothing else is retained, so the size of the dump never reaches
     * the heap.
     *
     * Returns empty rather than throwing — the chart hides if history is absent, and a
     * chart is worth less than the figures beside it. A dump dumpsys truncated is
     * discarded: it ends at an arbitrary moment in the past, so every window drawn from it
     * would be stale without looking stale.
     */
    private fun loadHistory(activeTier: DataTier): List<HistoryPoint> {
        val reducer = BatteryHistoryReducer()
        val dump = runCatching {
            streamShell(CMD_HISTORY, activeTier, HISTORY_TIMEOUT_SECONDS, reducer::accept)
        }.getOrNull() ?: return emptyList()

        if (!dump.ok || reducer.truncated) return emptyList()
        return reducer.points()
    }

    /**
     * The span index behind the Diagnose tab.
     *
     * Same one dump the history already streams, folded by a second reducer instead of
     * the first. Called only from the confirm tap: opening the tab runs nothing, and
     * nothing here schedules a repeat. The caller owns the result and is expected to drop
     * it when its answers leave the screen — see `DiagnoseViewModel`.
     *
     * Returns null rather than throwing when the dump fails or comes back truncated: a
     * window measured from a stream that stopped at an arbitrary moment in the past would
     * read as a quiet hour rather than as missing data.
     */
    suspend fun loadDiagnosisIndex(): DiagnosisIndex? = withContext(Dispatchers.IO) {
        val activeTier = currentTier()
        if (activeTier == DataTier.BASIC) return@withContext null

        val indexer = DiagnosisIndexer()
        var seen = 0L
        val dump = try {
            streamShell(CMD_HISTORY, activeTier, HISTORY_TIMEOUT_SECONDS) { line ->
                // Leaving the tab stops the dump where it stands rather than letting it
                // read out the remaining twenty megabytes for nobody. Throwing out of the
                // read loop is what Shell's reaping is waiting for: it unwinds into the
                // finally that force-destroys the process.
                if (++seen % CANCEL_CHECK_INTERVAL == 0L) ensureActive()
                indexer.accept(line)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            null
        } ?: return@withContext null

        if (!dump.ok || indexer.truncated) return@withContext null
        indexer.build().takeIf { it.isUsable }
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
     * The percentage showing on the phone right now, or null.
     *
     * Synchronous and free: `BatteryManager` and the sticky `ACTION_BATTERY_CHANGED`
     * broadcast are both already-published values, so this forks nothing, reads no dump
     * and starts nothing that outlives the call. It exists for the Diagnose tab's "last
     * 10%" presets, which have to know where the battery is to say where it came from.
     */
    fun levelNow(): Int? = runCatching { liveSource.read().now.levelPercent }
        .getOrNull()
        ?.takeIf { it in 0..100 }

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
        /**
         * How long dumpsys is told to let the service write for.
         *
         * Its default is ten seconds, and on a device whose history buffer has filled up
         * the history dump takes longer than that on its own. When it expires dumpsys
         * abandons the dump and exits zero, so the only symptom is a stream that stops
         * mid-record — which is why this is set explicitly on both commands rather than
         * left to the default.
         */
        const val DUMPSYS_TIMEOUT_SECONDS = 60

        /** Outlives [DUMPSYS_TIMEOUT_SECONDS], or the shell would give up on it first. */
        const val STATS_TIMEOUT_SECONDS = 75L
        const val HISTORY_TIMEOUT_SECONDS = 75L

        const val CMD_BATTERY_STATS =
            "dumpsys -t $DUMPSYS_TIMEOUT_SECONDS batterystats --charged"

        /** Reduced in-process by [BatteryHistoryReducer], not on the device — see its docs. */
        const val CMD_HISTORY =
            "dumpsys -t $DUMPSYS_TIMEOUT_SECONDS batterystats --history"

        /**
         * Lines between checks that the caller still wants this. A field read per line
         * would be free enough, but the dump is 350,000 lines and free enough times
         * 350,000 is not nothing.
         */
        const val CANCEL_CHECK_INTERVAL = 4_096L

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
