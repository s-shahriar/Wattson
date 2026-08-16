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
        assertEquals(2111.0, measured.totalMah, 0.001)
        assertEquals(1463.0, measured.screenOnMah!!, 0.001)
        assertEquals(648.0, measured.screenOffMah!!, 0.001)
        assertEquals(0.0, measured.screenDozeMah!!, 0.001)
        assertEquals(332.0, measured.lightDozeMah!!, 0.001)
        assertEquals(230.0, measured.deepDozeMah!!, 0.001)
    }

    /** The split has to reconcile, or the drain card is telling two different stories. */
    @Test
    fun `screen-on and screen-off discharge sum to the total`() {
        val m = stats.measured!!
        assertEquals(m.totalMah, m.screenOnMah!! + m.screenOffMah!! + m.screenDozeMah!!, 0.001)
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

    /**
     * batterystats formats charge by magnitude — two decimals below 10 mAh, one below
     * 100, whole numbers above — so the fixture above, which happens to be all whole
     * numbers, exercises only one of the three shapes.
     *
     * An integer-only pattern did not miss the other two, which would have been obvious.
     * It skipped the whole number and matched the digits *after* the point: "93.4 mAh"
     * read as 4, and "5.56 mAh" read as 56 — off by a factor of ten, in the direction
     * that makes a sleeping phone look like it is drawing 2.3 A.
     */
    @Test
    fun `charge is read at all three magnitudes dumpsys formats`() {
        val decimal = BatteryStatsParser.parseStats(
            """
              Discharge: 144 mAh
              Screen off discharge: 5.56 mAh
              Screen doze discharge: 0 mAh
              Screen on discharge: 93.4 mAh
              Device light doze discharge: 12.6 mAh
              Device deep doze discharge: 0 mAh
            """.trimIndent()
        )
        val measured = decimal.measured!!

        assertEquals(144.0, decimal.dischargeMah!!, 0.001)
        assertEquals(93.4, measured.screenOnMah!!, 0.001)
        assertEquals(5.56, measured.screenOffMah!!, 0.001)
        assertEquals(12.6, measured.lightDozeMah!!, 0.001)
    }

    /** Negative figures still parse — the pattern's sign group has to survive the fix. */
    @Test
    fun `signed charge keeps its sign`() {
        val negative = BatteryStatsParser.parseStats(
            """
              Discharge: 12.5 mAh
              Screen on discharge: -1.25 mAh
              Screen off discharge: 0 mAh
            """.trimIndent()
        )

        assertEquals(-1.25, negative.measured!!.screenOnMah!!, 0.001)
    }
}
