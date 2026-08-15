package com.syed.wattson.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses a verbatim slice of real `dumpsys batterystats --charged` output.
 *
 * The fixture is device output, not hand-written, because every regression this parser
 * has had came from the shape of the real text: a duration in days being read as minutes,
 * two `when` branches competing for the single "Capacity:" line, and modelled power being
 * presented as though it were measured drain.
 */
class BatteryStatsParserTest {

    private val dump: String by lazy {
        checkNotNull(javaClass.classLoader?.getResourceAsStream("batterystats-charged.txt"))
            .bufferedReader()
            .readText()
    }

    private val stats by lazy { BatteryStatsParser.parseStats(dump) }

    @Test
    fun `reads the coulomb-counter discharge figures`() {
        val measured = assertNotNull("measured discharge missing", stats.measured).let { stats.measured!! }
        assertEquals(2111, measured.totalMah)
        assertEquals(1463, measured.screenOnMah)
        assertEquals(648, measured.screenOffMah)
        assertEquals(0, measured.screenDozeMah)
        assertEquals(332, measured.lightDozeMah)
        assertEquals(230, measured.deepDozeMah)
    }

    /** The split has to reconcile, or the drain card is telling two different stories. */
    @Test
    fun `screen-on and screen-off discharge sum to the total`() {
        val m = stats.measured!!
        assertEquals(m.totalMah, m.screenOnMah!! + m.screenOffMah!! + m.screenDozeMah!!)
    }

    /**
     * Both values sit on one line, so a second matching `when` branch is unreachable.
     * Reading only one of them silently blanked the "Full capacity" tile.
     */
    @Test
    fun `design capacity and computed drain both come off the Capacity line`() {
        assertEquals(3979, stats.designCapacityMah)
        assertEquals(1975, stats.computedDrainMah)
    }

    @Test
    fun `durations covering more than a day are not truncated`() {
        // "Total run time: 7h 7m 3s 148ms"
        assertEquals(7 * 3_600_000L + 7 * 60_000L + 3_148L, stats.totalRunTimeMs)
        assertEquals(24 * 3_600_000L, BatteryStatsParser.parseDurationMs("1d"))
        assertEquals(
            26 * 3_600_000L + 50 * 60_000L + 43_000L,
            BatteryStatsParser.parseDurationMs("1d 2h 50m 43s"),
        )
    }

    @Test
    fun `screen on time and count are read from the same line`() {
        assertEquals(2 * 3_600_000L + 10 * 60_000L + 51_219L, stats.screenOnMs)
        assertEquals(46, stats.screenOnCount)
    }

    @Test
    fun `uid encodings decode to real uids`() {
        assertEquals(10428, BatteryStatsParser.decodeUid("u0a428"))
        assertEquals(1010005, BatteryStatsParser.decodeUid("u10a5"))
        assertEquals(1000, BatteryStatsParser.decodeUid("1000"))
    }

    /**
     * The modelled per-state split is now only a fallback for dumps with no
     * coulomb-counter lines, but it still has to add up when it is used.
     */
    @Test
    fun `per-state totals stay available as the drain fallback`() {
        val byState = stats.powerByState
        assertEquals(210.214, byState.onBatteryScreenOnMah, 0.001)
        assertEquals(43.557, byState.onBatteryScreenOffMah, 0.001)
    }

    @Test
    fun `start clock is captured verbatim for the header`() {
        assertEquals("2026-08-15-14-42-53", stats.startClock)
    }
}
