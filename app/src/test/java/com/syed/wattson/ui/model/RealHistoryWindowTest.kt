package com.syed.wattson.ui.model

import com.syed.wattson.data.model.SystemFlag
import com.syed.wattson.data.parser.DiagnosisIndexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The whole pipeline over an hour of real history, against figures derived independently.
 *
 * `history-hour.txt` is a verbatim slice of `dumpsys batterystats --history` from a Redmi
 * K20 Pro, 09:55 to 11:05 on 27 August 2026, with only the records the indexer ignores
 * anyway removed — no record it reads was altered. The expected values below were
 * computed by a separate implementation written against the raw dump, so agreement here
 * means two independent readings of the same bytes arrived at the same answer.
 *
 * The synthetic fixtures elsewhere prove the rules. This proves they survive contact with
 * a real device: 2,165 records, forty distinct tags, a wakelock held under an empty name,
 * quoted job names containing slashes and hashes, and spans open at both ends.
 */
class RealHistoryWindowTest {

    private val zone = ZoneId.of("Asia/Dhaka")

    /** After the slice ends, so nothing in it is future-dated, and well inside the age limit. */
    private val now = LocalDateTime.of(2026, 8, 27, 12, 0)

    private fun index() = DiagnosisIndexer(zone, now).let { indexer ->
        val stream = javaClass.classLoader!!.getResourceAsStream("history-hour.txt")!!
        stream.bufferedReader().forEachLine(indexer::accept)
        indexer.build()
    }

    private fun secondsAt(hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 8, 27, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `an hour of real history reads the way an independent pass over it does`() {
        val index = index()
        assertTrue("the slice should be usable", index.isUsable)

        val from = index.msToSeconds(secondsAt(10, 0))
        val to = index.msToSeconds(secondsAt(11, 0))
        val report = WindowAnalyzer.analyze(index, from, to)
        assertNotNull(report)
        report!!

        // Drain, from the gauge and from the coulomb counter.
        assertEquals(18, report.startLevel)
        assertEquals(10, report.endLevel)
        assertEquals(8, report.usedPercent)
        assertEquals(305, report.usedMah)
        assertEquals(8.0, report.percentPerHour, 0.05)
        assertEquals(305.0, report.mahPerHour!!, 1.0)

        // Durations are asserted in seconds, with a second of slack per span: the indexer
        // rounds each record down to the second where the reference pass kept the
        // milliseconds, so every span boundary can move by up to one. The screen went on
        // and off fifteen times in this hour, and the two readings differ by four seconds.
        assertEquals(1_408.0, report.screenOnMs / 1000.0, 16.0)
        assertEquals(2_192.0, report.screenOffMs / 1000.0, 16.0)
        assertEquals(0.391f, report.screenOnFraction, 0.01f)

        // What was on top, of the 1,408 seconds the screen was actually on.
        assertEquals("com.android.launcher3", report.screenTime[0].tag)
        assertEquals(10_209, report.screenTime[0].uid)
        assertEquals(672.0, report.screenTime[0].durationMs / 1000.0, 6.0)
        assertEquals(0.477f, report.screenTime[0].fraction, 0.01f)
        assertEquals("com.facebook.katana", report.screenTime[1].tag)
        assertEquals(234.0, report.screenTime[1].durationMs / 1000.0, 6.0)

        // What held the phone awake, of the 2,192 seconds the screen was off. The winner
        // is a WorkManager job, exactly the shape of thing this card exists to find.
        val worst = report.keptAwake.first()
        assertTrue("expected a WorkManager job to lead", worst.tag.contains("RestProxyWorker"))
        assertEquals(10_290, worst.uid)
        assertEquals(1_079.0, worst.durationMs / 1000.0, 4.0)
        assertEquals(0.492f, worst.fraction, 0.01f)

        // The lock taken under an empty tag is still reported, on the strength of its uid.
        val unnamed = report.keptAwake.first { it.tag.isEmpty() }
        assertEquals(1_041, unnamed.uid)
        assertEquals(546.0, unnamed.durationMs / 1000.0, 4.0)

        // And the finding: the radio, not the screen, is where this hour went.
        val byFlag = report.system.associateBy { it.flag }
        assertEquals(0.756f, byFlag.getValue(SystemFlag.CELLULAR_HIGH_TX).fraction, 0.01f)
        assertEquals(0.439f, byFlag.getValue(SystemFlag.MOBILE_RADIO).fraction, 0.01f)
        assertEquals(0.722f, byFlag.getValue(SystemFlag.CPU_RUNNING).fraction, 0.01f)
        assertEquals(0.508f, byFlag.getValue(SystemFlag.AUDIO).fraction, 0.01f)
    }

    /** Every share is a share: nothing may claim more of a window than the window holds. */
    @Test
    fun `no slice claims more than the window`() {
        val index = index()
        val report = WindowAnalyzer.analyze(index, 0, index.endSec)!!
        (report.screenTime + report.keptAwake).forEach {
            assertTrue("${it.tag} claimed ${it.fraction}", it.fraction in 0f..1f)
        }
        report.system.forEach {
            assertTrue("${it.flag} claimed ${it.fraction}", it.fraction in 0f..1f)
        }
        assertEquals(index.endSec * 1000L, report.screenOnMs + report.screenOffMs)
    }
}
