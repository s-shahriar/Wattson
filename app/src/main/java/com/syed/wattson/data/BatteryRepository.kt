package com.syed.wattson.data

import android.content.Context
import android.content.pm.PackageManager
import com.syed.wattson.data.model.AppUsage
import com.syed.wattson.data.model.BatteryNow
import com.syed.wattson.data.model.BatteryReport
import com.syed.wattson.data.model.ChargingInfo
import com.syed.wattson.data.model.HistoryPoint
import com.syed.wattson.data.model.LiveSnapshot
import com.syed.wattson.data.model.RootUnavailableException
import com.syed.wattson.data.model.StatsUnavailableException
import com.syed.wattson.data.parser.BatteryHistoryParser
import com.syed.wattson.data.parser.BatteryStatsParser
import kotlinx.coroutines.Dispatchers
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

    private val packageManager: PackageManager get() = context.packageManager

    /**
     * Full report: live figures plus the parsed historical stats.
     *
     * @throws RootUnavailableException when `su` is absent or denied.
     * @throws StatsUnavailableException when dumpsys yields nothing parseable.
     */
    suspend fun load(): BatteryReport = withContext(Dispatchers.IO) {
        if (!shell.hasRoot()) {
            throw RootUnavailableException(
                "Wattson reads the same data Android keeps internally " +
                    "(dumpsys batterystats), which needs root to access. Grant Wattson " +
                    "superuser permission in KernelSU/Magisk and pull to refresh."
            )
        }

        val statsDump = shell.runAsRoot(CMD_BATTERY_STATS, timeoutSeconds = STATS_TIMEOUT_SECONDS)
        if (!statsDump.ok || statsDump.out.isBlank()) {
            throw StatsUnavailableException(
                statsDump.error.ifBlank { "dumpsys batterystats returned nothing" }
            )
        }

        val stats = BatteryStatsParser.parseStats(statsDump.out)
        val live = readLive()

        BatteryReport(
            now = live.now,
            stats = stats.copy(
                apps = stats.apps
                    .filter { it.mah > 0.0 }
                    // Icons are only decoded for the apps that can actually appear in the
                    // list; the tail keeps its label and skips the Drawable entirely.
                    .mapIndexed { index, app -> resolveIdentity(app, withIcon = index < ICON_BUDGET) },
            ),
            charging = live.charging,
            history = loadHistory(),
        )
    }

    /**
     * Battery level history, reduced on-device before it crosses the shell boundary.
     *
     * The raw dump is ~300k lines; the awk filter emits one record per actual change in
     * level, charge state or screen state, which brings it to a couple of thousand.
     * Returns empty rather than throwing — the chart simply hides if history is absent.
     */
    private fun loadHistory(): List<HistoryPoint> {
        val dump = shell.runAsRoot(CMD_HISTORY, timeoutSeconds = HISTORY_TIMEOUT_SECONDS)
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
        runCatching { readLive() }.getOrNull()
    }

    /**
     * One shell round-trip over `/sys/class/power_supply/battery`.
     *
     * Everything both live cards need comes from here, so the poll never has to touch
     * `dumpsys` — which would cost orders of magnitude more per tick.
     */
    private fun readLive(): LiveSnapshot {
        val dump = shell.runAsRoot(CMD_POWER_SUPPLY, timeoutSeconds = LIVE_TIMEOUT_SECONDS)
        val values = dump.out.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .toMap()

        fun intOf(key: String): Int? = values[key]?.toIntOrNull()

        val status = values["status"].orEmpty().ifBlank { "Unknown" }

        return LiveSnapshot(
            now = BatteryNow(
                levelPercent = intOf("capacity") ?: 0,
                status = status,
                health = values["health"].orEmpty().ifBlank { "Unknown" },
                temperatureC = (intOf("temp") ?: 0) / 10.0,
                chargeCounterMah = intOf("charge_counter")?.div(MICRO_PER_MILLI),
                isCharging = status.equals("Charging", ignoreCase = true) ||
                    status.equals("Full", ignoreCase = true),
            ),
            charging = ChargingInfo(
                currentNowMicroAmps = intOf("current_now"),
                voltageMicroVolts = intOf("voltage_now"),
                chargeFullMah = intOf("charge_full")?.div(MICRO_PER_MILLI),
                chargeFullDesignMah = intOf("charge_full_design")?.div(MICRO_PER_MILLI),
                chargeCounterMah = intOf("charge_counter")?.div(MICRO_PER_MILLI),
                cycleCount = intOf("cycle_count"),
            ),
        )
    }

    /** Turns a bare UID into an app label + icon, falling back to the raw UID. */
    private fun resolveIdentity(app: AppUsage, withIcon: Boolean): AppUsage {
        val uid = app.uid ?: return app

        knownSystemUid(uid)?.let { return app.copy(label = it) }

        val primaryPackage = runCatching { packageManager.getPackagesForUid(uid) }
            .getOrNull()
            ?.firstOrNull()
            ?: return app.copy(label = "UID $uid")

        val info = runCatching { packageManager.getApplicationInfo(primaryPackage, 0) }.getOrNull()
            ?: return app.copy(packageName = primaryPackage, label = primaryPackage)

        return app.copy(
            packageName = primaryPackage,
            label = runCatching { packageManager.getApplicationLabel(info).toString() }
                .getOrDefault(primaryPackage),
            icon = if (withIcon) {
                runCatching { packageManager.getApplicationIcon(info) }.getOrNull()
            } else {
                null
            },
        )
    }

    private fun knownSystemUid(uid: Int): String? = when (uid) {
        0 -> "Root"
        1000 -> "Android System"
        1001 -> "Telephony"
        1002 -> "Bluetooth"
        1013 -> "Media server"
        1027 -> "NFC"
        else -> null
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

        /** Apps whose launcher icon is decoded — comfortably above the list length. */
        const val ICON_BUDGET = 15

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
