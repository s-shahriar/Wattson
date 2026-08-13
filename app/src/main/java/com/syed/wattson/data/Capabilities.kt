package com.syed.wattson.data

import android.content.Context
import android.content.pm.PackageManager

/**
 * How much of Android's battery accounting this device will let Wattson see.
 *
 * Ordered least to most capable; the repository always uses the best available and the
 * UI hides whatever the current tier cannot supply.
 */
enum class DataTier {
    /**
     * Public APIs only. Live charge state, rate and capacity health — no historical
     * accounting, because batterystats is not readable by an ordinary app.
     */
    BASIC,

    /**
     * `DUMP` granted over adb. The app can run `dumpsys` in its own shell, which yields
     * exactly the same text root gives — so every feature works, without root.
     */
    PRIVILEGED,

    /** Root. Full `dumpsys batterystats` text, including the level history timeline. */
    ROOT,
}

/**
 * Detects the tier once per load.
 *
 * Root detection is deliberately last and cached by the caller — probing it spawns a
 * process, and on a non-rooted device that can block until a superuser prompt times out.
 */
class Capabilities(
    private val context: Context,
    private val shell: Shell = Shell,
) {

    fun detect(): DataTier = debugOverride ?: when {
        hasRoot() -> DataTier.ROOT
        hasDumpAccess() -> DataTier.PRIVILEGED
        else -> DataTier.BASIC
    }

    /**
     * Three separate gates govern an unprivileged `dumpsys batterystats`, each verified
     * on device by the error the previous one produced:
     *  - DUMP            lets the app invoke dumpsys at all
     *  - BATTERY_STATS   checked by the batterystats service
     *  - PACKAGE_USAGE_STATS  checked by its dump handler, as an appop
     */
    fun hasDumpAccess(): Boolean =
        granted(PERMISSION_DUMP) &&
            granted(PERMISSION_BATTERY_STATS) &&
            granted(PERMISSION_USAGE_STATS)

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasRoot(): Boolean = runCatching { shell.hasRoot() }.getOrDefault(false)

    companion object {
        /**
         * Debug-only tier pin, set from MainActivity's launch intent. Lets the lower
         * tiers be exercised on a rooted device without uninstalling root.
         */
        @Volatile
        var debugOverride: DataTier? = null

        const val PERMISSION_DUMP = "android.permission.DUMP"
        const val PERMISSION_BATTERY_STATS = "android.permission.BATTERY_STATS"

        const val PERMISSION_USAGE_STATS = "android.permission.PACKAGE_USAGE_STATS"

        /**
         * All three grants, in the order the service checks them. Setting the usage-access
         * appop alone is not sufficient — BatteryStatsService performs a plain permission
         * check, so PACKAGE_USAGE_STATS must be granted here too.
         */
        fun grantCommand(packageName: String): String = listOf(
            PERMISSION_DUMP,
            PERMISSION_BATTERY_STATS,
            PERMISSION_USAGE_STATS,
        ).joinToString("\n") { "adb shell pm grant $packageName $it" }
    }
}
