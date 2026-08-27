package com.syed.wattson.ui.model

import com.syed.wattson.data.model.DiagnosisIndex
import com.syed.wattson.data.model.EventKind
import com.syed.wattson.data.model.PackedSpans
import com.syed.wattson.data.model.SystemFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine behind the Diagnose tab, on fixtures small enough to check by hand.
 *
 * Seconds throughout: the index counts from its own epoch, so "600" is ten minutes in.
 */
class WindowAnalyzerTest {

    private val epoch = 1_700_000_000_000L
    private val names = arrayOf("com.a", "com.b", "com.c")

    /** start, end, nameId — the uid is derived from the name so each fixture app is one owner. */
    private fun spans(vararg triples: Triple<Int, Int, Int>) = PackedSpans(
        startSec = triples.map { it.first }.toIntArray(),
        endSec = triples.map { it.second }.toIntArray(),
        nameId = triples.map { it.third }.toIntArray(),
        uid = triples.map { 10_000 + it.third }.toIntArray(),
    )

    private fun index(
        endSec: Int = 3_600,
        top: PackedSpans = spans(),
        wake: PackedSpans = spans(),
        flags: Map<SystemFlag, IntArray> = emptyMap(),
        levels: List<Pair<Int, Int>> = listOf(0 to 100, 3_600 to 90),
        charges: List<Pair<Int, Int>> = listOf(0 to 4_000, 3_600 to 3_600),
    ) = DiagnosisIndex(
        epochMs = epoch,
        endSec = endSec,
        names = names,
        spans = mapOf(
            EventKind.FOREGROUND to top,
            EventKind.WAKELOCK to wake,
        ),
        flags = flags,
        chargeSec = charges.map { it.first }.toIntArray(),
        chargeMah = charges.map { it.second }.toIntArray(),
        levelSec = levels.map { it.first }.toIntArray(),
        levelPercent = levels.map { it.second }.toIntArray(),
    )

    @Test
    fun `drain comes from the coulomb counter and the gauge`() {
        val report = WindowAnalyzer.analyze(index(), 0, 3_600)!!
        assertEquals(100, report.startLevel)
        assertEquals(90, report.endLevel)
        assertEquals(10, report.usedPercent)
        assertEquals(400, report.usedMah)
        assertEquals(400.0, report.mahPerHour!!, 0.5)
        assertEquals(10.0, report.percentPerHour, 0.01)
    }

    /** A gauge that reads higher at the end of an hour on battery is noise, not a gain. */
    @Test
    fun `a level that rises reports no use rather than a negative one`() {
        val report = WindowAnalyzer.analyze(
            index(levels = listOf(0 to 50, 3_600 to 60), charges = listOf(0 to 2_000, 3_600 to 2_400)),
            0,
            3_600,
        )!!
        assertEquals(0, report.usedPercent)
        assertNull("a counter that went up is not a discharge", report.usedMah)
    }

    @Test
    fun `screen time is only counted while the screen is on`() {
        // com.a is on top for the whole hour, but the screen is on for the first ten
        // minutes only. The top activity does not change when the screen goes dark.
        val report = WindowAnalyzer.analyze(
            index(
                top = spans(Triple(0, 3_600, 0)),
                flags = mapOf(SystemFlag.SCREEN to intArrayOf(0, 600)),
            ),
            0,
            3_600,
        )!!
        assertEquals(600_000L, report.screenOnMs)
        assertEquals(3_000_000L, report.screenOffMs)
        assertEquals(1, report.screenTime.size)
        assertEquals(600_000L, report.screenTime.first().durationMs)
        assertEquals(1f, report.screenTime.first().fraction, 0.001f)
    }

    @Test
    fun `wakelocks are only counted while the screen is off`() {
        val report = WindowAnalyzer.analyze(
            index(
                wake = spans(Triple(0, 3_600, 1)),
                flags = mapOf(SystemFlag.SCREEN to intArrayOf(0, 600)),
            ),
            0,
            3_600,
        )!!
        assertEquals(1, report.keptAwake.size)
        // The 600 seconds with the screen on are the screen's doing, not the lock's.
        assertEquals(3_000_000L, report.keptAwake.first().durationMs)
        assertEquals(1f, report.keptAwake.first().fraction, 0.001f)
    }

    /**
     * Three overlapping locks held by one app kept the phone awake once. Summing them
     * reported holders at several hundred percent of the same night.
     */
    @Test
    fun `overlapping spans of one app count once`() {
        val report = WindowAnalyzer.analyze(
            index(wake = spans(Triple(0, 600, 0), Triple(300, 900, 0), Triple(880, 1_000, 0))),
            0,
            3_600,
        )!!
        assertEquals(1, report.keptAwake.size)
        assertEquals(1_000_000L, report.keptAwake.first().durationMs)
        assertTrue("a share cannot exceed the window", report.keptAwake.first().fraction <= 1f)
    }

    /**
     * One app takes a dozen differently-named locks over a night. The question the card
     * answers is which app, so they are one row, labelled by whichever tag held longest.
     */
    @Test
    fun `spans from one app under different tags are one row`() {
        val owned = PackedSpans(
            startSec = intArrayOf(0, 600, 1_200),
            endSec = intArrayOf(300, 900, 1_800),
            nameId = intArrayOf(0, 1, 2),
            uid = intArrayOf(10_050, 10_050, 10_050),
        )
        val report = WindowAnalyzer.analyze(index(wake = owned), 0, 3_600)!!
        assertEquals(1, report.keptAwake.size)
        assertEquals(10_050, report.keptAwake.first().uid)
        assertEquals(1_200_000L, report.keptAwake.first().durationMs)
        assertEquals("com.c", report.keptAwake.first().tag)
    }

    /** Spans the history left unowned still have their tags to be told apart by. */
    @Test
    fun `unowned spans group by tag instead`() {
        val unowned = PackedSpans(
            startSec = intArrayOf(0, 600),
            endSec = intArrayOf(300, 900),
            nameId = intArrayOf(0, 1),
            uid = intArrayOf(PackedSpans.NO_UID, PackedSpans.NO_UID),
        )
        val report = WindowAnalyzer.analyze(index(wake = unowned), 0, 3_600)!!
        assertEquals(2, report.keptAwake.size)
    }

    @Test
    fun `apps are ranked by time and cut to five`() {
        val many = (0 until 3).map { Triple(it * 100, it * 100 + (3 - it) * 60, it) }
        val report = WindowAnalyzer.analyze(index(wake = spans(*many.toTypedArray())), 0, 3_600)!!
        assertEquals(listOf("com.a", "com.b", "com.c"), report.keptAwake.map { it.tag })
        assertTrue(report.keptAwake.zipWithNext().all { (a, b) -> a.durationMs >= b.durationMs })
    }

    @Test
    fun `system flags are ranked by share of the window`() {
        val report = WindowAnalyzer.analyze(
            index(
                flags = mapOf(
                    SystemFlag.CPU_RUNNING to intArrayOf(0, 1_800),
                    SystemFlag.MOBILE_RADIO to intArrayOf(0, 900),
                    SystemFlag.CELLULAR_HIGH_TX to intArrayOf(0, 3_600),
                ),
            ),
            0,
            3_600,
        )!!
        assertEquals(
            listOf(SystemFlag.CELLULAR_HIGH_TX, SystemFlag.CPU_RUNNING, SystemFlag.MOBILE_RADIO),
            report.system.map { it.flag },
        )
        assertEquals(1f, report.system.first().fraction, 0.001f)
        assertEquals(0.25f, report.system.last().fraction, 0.001f)
    }

    /** Spans that begin before the window or end after it are clipped, not dropped. */
    @Test
    fun `spans straddling the window edges are clipped to it`() {
        val report = WindowAnalyzer.analyze(
            index(wake = spans(Triple(0, 3_600, 0))),
            1_200,
            2_400,
        )!!
        assertEquals(1_200_000L, report.keptAwake.first().durationMs)
    }

    @Test
    fun `a window shorter than a minute has nothing to say`() {
        assertNull(WindowAnalyzer.analyze(index(), 100, 130))
        assertNull(WindowAnalyzer.analyze(index(), 500, 500))
    }

    /**
     * A level range recurs every cycle. The one being asked about is all but always the
     * most recent, so the search runs backwards.
     */
    @Test
    fun `a level range resolves to its most recent occurrence`() {
        val levels = listOf(
            0 to 40, 600 to 31,          // an older pass through the same range
            1_200 to 100,
            5_000 to 40, 8_000 to 31,    // the one that should win
            9_000 to 25,
        )
        val range = WindowAnalyzer.windowForLevels(index(endSec = 9_000, levels = levels), 40, 31)
        assertNotNull(range)
        assertEquals(5_000, range!!.first)
        assertEquals(8_000, range.last)
    }

    @Test
    fun `a level range the buffer never reached resolves to nothing`() {
        assertNull(WindowAnalyzer.windowForLevels(index(), 20, 10))
    }
}
