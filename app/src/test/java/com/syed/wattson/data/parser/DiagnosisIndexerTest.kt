package com.syed.wattson.data.parser

import com.syed.wattson.data.model.EventKind
import com.syed.wattson.data.model.SystemFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Fixtures are real history lines, laid out at the columns dumpsys writes them:
 *
 * ```
 *   08-20 10:00:00.000 090 +top=u0a1:"com.a"
 *   0 2     8            21
 * ```
 */
class DiagnosisIndexerTest {

    private val zone = ZoneId.of("Asia/Dhaka")
    private val base = LocalDateTime.of(2026, 8, 20, 10, 0, 0)
    private val now = base.plusDays(1)

    private fun indexer() = DiagnosisIndexer(zone, now)

    private fun line(sec: Long, level: Int, rest: String = ""): String {
        val t = base.plusSeconds(sec)
        return String.format(
            "  %02d-%02d %02d:%02d:%02d.000 %03d %s",
            t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second, level, rest,
        )
    }

    private fun build(vararg lines: String) = indexer().let { ix ->
        lines.forEach(ix::accept)
        ix.build()
    }

    private fun spanList(index: com.syed.wattson.data.model.DiagnosisIndex, kind: EventKind) =
        index.spansOf(kind)!!.let { s ->
            (0 until s.size).map { Triple(s.startSec[it], s.endSec[it], index.nameOf(s.nameId[it])) }
        }

    private fun uidList(index: com.syed.wattson.data.model.DiagnosisIndex, kind: EventKind) =
        index.spansOf(kind)!!.let { s -> (0 until s.size).map { s.uid[it] } }

    /**
     * The bug this whole class exists for. The history records the lock that kept the
     * device awake, not the stack, so `+wake_lock=-1:"screen"` is closed by
     * `-wake_lock=u0a201:"Scrims"`. Paired by name, every unmatched acquire stays open to
     * the end of the buffer — which read as several apps each holding the phone awake for
     * 100% of the same night.
     */
    @Test
    fun `a wakelock is one slot, closed by whatever name releases it`() {
        val index = build(
            line(0, 90, """+wake_lock=-1:"screen""""),
            line(60, 90, """-wake_lock=u0a201:"Scrims""""),
            line(120, 89, """+wake_lock=u0a5:"GCM_CONN_ALARM""""),
            line(300, 89, """-wake_lock=u0a9:"something_else""""),
        )
        assertEquals(
            listOf(Triple(0, 60, "screen"), Triple(120, 300, "GCM_CONN_ALARM")),
            spanList(index, EventKind.WAKELOCK),
        )
    }

    /** A new acquire while one is open closes the old one; they cannot overlap. */
    @Test
    fun `a wakelock acquired while one is held closes the one before it`() {
        val index = build(
            line(0, 90, """+wake_lock=u0a1:"first""""),
            line(100, 90, """+wake_lock=u0a2:"second""""),
            line(200, 90, """-wake_lock=u0a2:"second""""),
        )
        assertEquals(
            listOf(Triple(0, 100, "first"), Triple(100, 200, "second")),
            spanList(index, EventKind.WAKELOCK),
        )
    }

    /** One app is on top at a time, so foreground is a slot as well. */
    @Test
    fun `foreground is a single slot`() {
        val index = build(
            line(0, 90, """+top=u0a1:"com.a""""),
            line(60, 90, """+top=u0a2:"com.b""""),
            line(90, 90, """-top=u0a2:"com.b""""),
        )
        assertEquals(
            listOf(Triple(0, 60, "com.a"), Triple(60, 90, "com.b")),
            spanList(index, EventKind.FOREGROUND),
        )
    }

    @Test
    fun `system flags become start and end pairs`() {
        val index = build(
            line(0, 90, "+screen +running"),
            line(120, 90, "-screen"),
            line(300, 88, "-running"),
        )
        assertEquals(listOf(0, 120), index.flagOf(SystemFlag.SCREEN).toList())
        assertEquals(listOf(0, 300), index.flagOf(SystemFlag.CPU_RUNNING).toList())
    }

    /** `+screen_doze` is a different state; folding it in reports a dozing phone as awake. */
    @Test
    fun `screen doze is not screen on`() {
        val index = build(line(0, 90, "+screen_doze"), line(60, 90, "-screen_doze"))
        assertTrue(index.flagOf(SystemFlag.SCREEN).isEmpty())
    }

    /** A quoted tag can hold anything, including what looks like another token. */
    @Test
    fun `a plus inside a quoted name is not read as an event`() {
        val index = build(
            line(0, 90, """+wake_lock=u0a1:"weird +screen -running name""""),
            line(60, 90, """-wake_lock=u0a1:"weird +screen -running name""""),
        )
        assertTrue("a quoted tag was scanned for tokens", index.flagOf(SystemFlag.SCREEN).isEmpty())
        assertEquals(1, spanList(index, EventKind.WAKELOCK).size)
    }

    /** Tokens we do not model must still be stepped over, quotes and all. */
    @Test
    fun `unknown tokens are skipped without disturbing the ones after them`() {
        val index = build(
            line(0, 90, """-tmpwhitelist=1001:"d8487ec BOOT_COMPLETED/u0" +running"""),
            line(60, 90, "-running"),
        )
        assertEquals(listOf(0, 60), index.flagOf(SystemFlag.CPU_RUNNING).toList())
    }

    @Test
    fun `the coulomb counter and the level are read off the record`() {
        val index = build(
            line(0, 90, "charge=3600 temp=350"),
            line(3_600, 80, "charge=3200"),
        )
        assertEquals(listOf(0, 3_600), index.chargeSec.toList())
        assertEquals(listOf(3_600, 3_200), index.chargeMah.toList())
        assertEquals(listOf(90, 80), index.levelPercent.toList())
    }

    /** One entry per change: the level column repeats on every one of 350,000 records. */
    @Test
    fun `an unchanged level is not recorded twice`() {
        val index = build(line(0, 90), line(10, 90), line(20, 90), line(30, 89))
        assertEquals(listOf(0, 30), index.levelSec.toList())
    }

    /**
     * A lock still held when the dump was taken is still a lock that was held. Dropping
     * it would hide the one case that matters most: something that took the phone and
     * never gave it back.
     */
    @Test
    fun `spans still open at the end of the buffer are closed there`() {
        val index = build(
            line(0, 90, """+wake_lock=u0a1:"never_released""""),
            line(600, 85, "+running"),
            line(900, 80),
        )
        assertEquals(listOf(Triple(0, 900, "never_released")), spanList(index, EventKind.WAKELOCK))
        assertEquals(listOf(600, 900), index.flagOf(SystemFlag.CPU_RUNNING).toList())
    }

    /** `u0a138` is user 0, app 138, which is the uid 10138 PackageManager answers to. */
    @Test
    fun `uids are decoded the way Android encodes them`() {
        val index = build(
            line(0, 90, """+top=u0a138:"com.a""""),
            line(60, 90, """+top=1000:"com.b""""),
            line(120, 90, """+top=u10a5:"com.c""""),
            line(180, 90, """-top=u10a5:"com.c""""),
        )
        assertEquals(listOf(10_138, 1_000, 1_010_005), uidList(index, EventKind.FOREGROUND))
    }

    /** A lock taken under an empty tag is still a lock; only its uid says who took it. */
    @Test
    fun `a wakelock with an empty tag keeps its owner`() {
        val index = build(
            line(0, 90, """+wake_lock=1041:"""""),
            line(540, 88, """-wake_lock=1041:"""""),
        )
        assertEquals(listOf(Triple(0, 540, "")), spanList(index, EventKind.WAKELOCK))
        assertEquals(listOf(1_041), uidList(index, EventKind.WAKELOCK))
    }

    @Test
    fun `a truncated dump is flagged`() {
        val ix = indexer()
        ix.accept(line(0, 90, "+running"))
        ix.accept("*** SERVICE 'batterystats' DUMP TIMEOUT (10000ms) EXPIRED ***")
        assertTrue(ix.truncated)
    }

    @Test
    fun `an empty dump builds an index that admits it is unusable`() {
        assertFalse(build().isUsable)
        assertFalse(build(line(0, 90)).isUsable)
    }

    /** Records stamped 1970 by a battery pull sort to the head and drag the window back. */
    @Test
    fun `records from a broken clock are dropped`() {
        val index = build(
            "  01-01 06:00:18.156 060 +running",
            line(0, 90, "+screen"),
            line(60, 90, "-screen"),
        )
        assertEquals(listOf(0, 60), index.flagOf(SystemFlag.SCREEN).toList())
        assertTrue(index.flagOf(SystemFlag.CPU_RUNNING).isEmpty())
    }
}
