package com.syed.wattson.data.source

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.syed.wattson.data.model.BatteryNow
import com.syed.wattson.data.model.ChargingInfo
import com.syed.wattson.data.model.LiveSnapshot

/**
 * Live battery state from public APIs — no root, no permissions, no setup.
 *
 * `BatteryManager` plus the sticky `ACTION_BATTERY_CHANGED` broadcast cover everything
 * the status card shows. Android 14 added state-of-health and cycle count, which is what
 * makes the capacity figure possible without reading sysfs.
 *
 * Where the platform falls short (charge_full against design on pre-14 devices) the
 * repository can still layer a rooted sysfs read on top; nothing here requires it.
 */
class LiveBatterySource(private val context: Context) {

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    fun read(): LiveSnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = intProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: intent?.scaledLevel()
            ?: 0
        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val healthCode = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

        val chargeCounterUah = intProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val currentNowUa = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        // Android 14+ exposes remaining capacity as a percentage of design directly.
        val stateOfHealth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intProperty(BATTERY_PROPERTY_STATE_OF_HEALTH)?.takeIf { it in 1..100 }
        } else {
            null
        }

        val cycles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent?.getIntExtra(EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
        } else {
            null
        }

        return LiveSnapshot(
            now = BatteryNow(
                levelPercent = level,
                status = statusLabel(statusCode),
                health = healthLabel(healthCode),
                temperatureC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
                chargeCounterMah = chargeCounterUah?.div(MICRO_PER_MILLI),
                isCharging = statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
                    statusCode == BatteryManager.BATTERY_STATUS_FULL,
            ),
            charging = ChargingInfo(
                currentNowMicroAmps = currentNowUa,
                voltageMicroVolts = intent
                    ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                    ?.takeIf { it > 0 }
                    ?.times(MICRO_PER_MILLI),
                // Derived rather than read: state-of-health is a percentage, so pair it
                // with the design capacity the platform reports to get both figures.
                chargeFullMah = null,
                chargeFullDesignMah = null,
                chargeCounterMah = chargeCounterUah?.div(MICRO_PER_MILLI),
                cycleCount = cycles,
                stateOfHealthPercent = stateOfHealth,
            ),
        )
    }

    /**
     * getIntProperty reports Integer.MIN_VALUE for properties the device lacks — but not
     * every property is freely readable. STATE_OF_HEALTH in particular throws
     * SecurityException without BATTERY_STATS, verified on device, so each read is
     * guarded individually: one gated property must not take down the whole snapshot.
     */
    private fun intProperty(property: Int): Int? =
        runCatching { batteryManager.getIntProperty(property) }
            .getOrNull()
            ?.takeIf { it != Int.MIN_VALUE }

    private fun Intent.scaledLevel(): Int {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) 0 else level * 100 / scale
    }

    private fun statusLabel(code: Int) = when (code) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        else -> "Unknown"
    }

    private fun healthLabel(code: Int) = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        else -> "Unknown"
    }

    private companion object {
        const val MICRO_PER_MILLI = 1_000

        /** BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH, added in API 34. */
        const val BATTERY_PROPERTY_STATE_OF_HEALTH = 12

        /** BatteryManager.EXTRA_CYCLE_COUNT, added in API 34. */
        const val EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT"
    }
}
