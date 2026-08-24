package com.syed.wattson.data.parser

import com.syed.wattson.data.model.HistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Feeds the reducer verbatim `dumpsys batterystats --history` records.
 *
 * Every record here is real device output, including the 1970-stamped block a battery swap
 * left behind — that block is the whole reason the age check exists, and a hand-written
 * approximation of it would not have caught what it does.
 *
 * The dump indents each record by two spaces and the reducer reads its fields by column,
 * so [reduce] puts that indent back rather than have every fixture carry it.
 */
class BatteryHistoryReducerTest {

    private val zone = ZoneId.of("Asia/Dhaka")
    private val now = LocalDateTime.of(2026, 8, 24, 12, 0)

    private fun reducer(at: LocalDateTime = now) = BatteryHistoryReducer(zone, at)

    private fun reduce(vararg records: String): BatteryHistoryReducer =
        reducer().apply { records.forEach { accept("  $it") } }

    private fun HistoryPoint.at(): String =
        Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDateTime().toString()

    @Test
    fun `keeps one sample per change and drops the records in between`() {
        val points = reduce(
            "08-24 08:36:14.717 075 status=discharging +screen",
            "08-24 08:36:15.001 075 temp=402 volt=3607",
            "08-24 08:36:35.806 075 -screen",
            "08-24 08:37:50.011 075 +screen",
            "08-24 08:38:29.975 074",
        ).points()

        assertEquals(4, points.size)
        assertEquals(listOf(75, 75, 75, 74), points.map { it.level })
        assertEquals(listOf(true, false, true, true), points.map { it.screenOn })
        assertEquals("2026-08-24T08:36:14.717", points.first().at())
    }

    /** `not-charging` flaps against `charging` several times a second on a live charger. */
    @Test
    fun `not-charging is folded into discharging`() {
        val points = reduce(
            "08-18 08:32:50.939 069 status=charging health=good plug=ac temp=382 +plugged +charging",
            "08-18 09:24:20.797 091 status=not-charging health=overheat",
        ).points()

        assertEquals(listOf(true, false), points.map { it.charging })
    }

    /** A dozing device is not a device with its screen on. */
    @Test
    fun `screen_doze does not count as screen on`() {
        val points = reduce(
            "08-24 08:36:14.717 075 status=discharging +screen",
            "08-24 08:40:00.000 075 -screen +screen_doze",
            """08-24 08:41:00.000 074 +wake_lock=-1:"screen"""",
        ).points()

        assertEquals(listOf(true, false, false), points.map { it.screenOn })
    }

    /**
     * Pulling the battery cuts power to the RTC, so the records either side of a swap are
     * stamped 1970. The dump prints no year, so they arrive as "01-01" and would otherwise
     * be read as samples from January of the current year — eight months of empty chart
     * hanging off the front of the history.
     */
    @Test
    fun `records written before the clock was set are dropped`() {
        val points = reduce(
            "01-01 06:00:18.156 TIME: 1970-01-01-06-00-18",
            "01-01 06:00:20.596 060 status=discharging health=good plug=none temp=312 +running",
            """01-01 06:00:21.872 060 +wake_lock=-1:"screen" +screen brightness=bright""",
            "08-18 09:23:27.789 091 status=charging",
        ).points()

        assertEquals(1, points.size)
        assertEquals("2026-08-18T09:23:27.789", points.first().at())
        // The screen came on in a record that was thrown away; the state it reported is
        // still real, so the surviving sample has to carry it.
        assertTrue("screen state from a dropped record was lost", points.first().screenOn)
    }

    @Test
    fun `future-dated records are dropped`() {
        assertTrue(reduce("08-25 13:00:00.000 075 status=discharging").points().isEmpty())
    }

    /**
     * dumpsys exits zero after abandoning a dump, so this marker is the only evidence that
     * the stream stopped short of the present.
     */
    @Test
    fun `the dumpsys truncation marker is reported`() {
        assertFalse(reduce("08-24 08:36:14.717 075 status=discharging").truncated)

        val cut = reducer().apply {
            accept("  08-24 08:36:14.717 075 status=discharging")
            accept("*** SERVICE 'batterystats' DUMP TIMEOUT (10000ms) EXPIRED ***")
        }
        assertTrue(cut.truncated)
        assertEquals(1, cut.points().size)
    }

    /** A wakelock named `*walarm*:DhcpClient.wlan0.TIMEOUT` is on this device's every dump. */
    @Test
    fun `a wakelock with TIMEOUT in its name is not a truncation`() {
        val reduced = reduce(
            """08-18 18:04:42.953 066 +wake_lock=1073:"*walarm*:DhcpClient.wlan0.TIMEOUT" +wifi_scan""",
        )
        assertFalse(reduced.truncated)
        assertEquals(1, reduced.points().size)
    }

    /** History carries no year, so a December record read in January belongs to last year. */
    @Test
    fun `a record from the far side of new year rolls back a year`() {
        val points = reducer(LocalDateTime.of(2026, 1, 2, 9, 0))
            .apply { accept("  12-31 23:50:00.000 044 status=discharging") }
            .points()

        assertEquals(1, points.size)
        assertEquals("2025-12-31T23:50", points.first().at())
    }
}
