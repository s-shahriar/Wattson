package com.syed.wattson.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remaining-time tile has been wrong in both directions, so both are pinned here.
 *
 * Discharging once ran the charging formula against a current flowing the other way, and
 * charging read a capacity field that only the rooted sysfs path ever populates — which
 * left the tile blank for the whole of every charge on an unrooted device.
 */
class ChargingInfoTest {

    private fun info(
        currentUa: Int? = -356_000,
        counterMah: Int? = 1_671,
        fullMah: Int? = null,
    ) = ChargingInfo(
        currentNowMicroAmps = currentUa,
        voltageMicroVolts = 3_640_000,
        chargeFullMah = fullMah,
        chargeFullDesignMah = 4_000,
        chargeCounterMah = counterMah,
        cycleCount = 2,
    )

    @Test
    fun `discharging counts the charge left in the cell`() {
        // 1671 mAh left at 356 mA.
        assertEquals(4.694, info().hoursRemaining(isCharging = false, fullCapacityMah = 3_979)!!, 0.001)
    }

    /** Discharging must not depend on a capacity the device may never report. */
    @Test
    fun `discharging works with no known full capacity`() {
        assertEquals(4.694, info().hoursRemaining(isCharging = false, fullCapacityMah = null)!!, 0.001)
    }

    @Test
    fun `charging counts the gap up to full`() {
        // (3979 - 1671) = 2308 mAh to add, at 1500 mA.
        val charging = info(currentUa = 1_500_000)
        assertEquals(1.539, charging.hoursRemaining(isCharging = true, fullCapacityMah = 3_979)!!, 0.001)
    }

    /**
     * The regression: the caller passes a resolved capacity, so a device whose
     * ChargingInfo carries none still gets an estimate.
     */
    @Test
    fun `charging uses the capacity handed in, not the sysfs-only field`() {
        val noSysfsCapacity = info(currentUa = 1_500_000, fullMah = null)
        assertNull(noSysfsCapacity.chargeFullMah)
        assertEquals(
            1.539,
            noSysfsCapacity.hoursRemaining(isCharging = true, fullCapacityMah = 3_979)!!,
            0.001,
        )
    }

    @Test
    fun `charging with no capacity anywhere yields no estimate rather than a wrong one`() {
        assertNull(info(currentUa = 1_500_000).hoursRemaining(true, fullCapacityMah = null))
    }

    @Test
    fun `a full cell has nothing left to add`() {
        val full = info(currentUa = 1_500_000, counterMah = 3_979)
        assertNull(full.hoursRemaining(isCharging = true, fullCapacityMah = 3_979))
    }

    @Test
    fun `negligible current gives no estimate`() {
        assertNull(info(currentUa = 2_000).hoursRemaining(false, 3_979))
    }

    @Test
    fun `current sign convention does not affect the magnitude`() {
        val drainingNegative = info(currentUa = -356_000).hoursRemaining(false, 3_979)!!
        val drainingPositive = info(currentUa = 356_000).hoursRemaining(false, 3_979)!!
        assertEquals(drainingNegative, drainingPositive, 0.0001)
    }

    @Test
    fun `full capacity is inferred from counter and level when nothing reports it`() {
        // 1671 mAh at 42% implies roughly a 3979 mAh cell.
        assertEquals(3_978, info().inferredFullMah(levelPercent = 42))
    }

    @Test
    fun `inference is refused where the counter's rounding would dominate`() {
        assertNull(info().inferredFullMah(levelPercent = 2))
        assertNull(info(counterMah = null).inferredFullMah(levelPercent = 50))
    }
}
