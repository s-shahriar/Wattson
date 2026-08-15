package com.syed.wattson.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The headline number the whole "By category" card rests on. Modelled attribution
     * covering a small slice of real drain is exactly the case the UI must disclose.
     */
    @Test
    fun `attribution coverage reflects how little the model explains`() {
        assertEquals(253.84, stats.attributedMah, 0.01)
        val unattributed = assertNotNull(stats.unattributedMah).let { stats.unattributedMah!! }
        assertEquals(2111 - 253.84, unattributed, 0.01)
        assertTrue("model should not claim to cover the cycle", stats.attributedMah < 2111 * 0.75)
    }

    /** No `cpu=` term anywhere means processor draw is absent from every app row. */
    @Test
    fun `cpu attribution is detected as unavailable on this dump`() {
        assertFalse(stats.apps.any { it.mahFor("cpu") != null })
    }

    @Test
    fun `uid encodings decode to real uids`() {
        assertEquals(10428, BatteryStatsParser.decodeUid("u0a428"))
        assertEquals(1010005, BatteryStatsParser.decodeUid("u10a5"))
        assertEquals(1000, BatteryStatsParser.decodeUid("1000"))
    }

    @Test
    fun `apps are ranked by draw and carry their buckets`() {
        val top = stats.apps.first()
        assertEquals("u0a383", top.rawUid)
        assertEquals(38.9, top.mah, 0.01)
        assertEquals(31.3, top.mahFor("screen")!!, 0.01)
    }

    @Test
    fun `start clock is captured verbatim for the header`() {
        assertEquals("2026-08-15-14-42-53", stats.startClock)
    }
}
