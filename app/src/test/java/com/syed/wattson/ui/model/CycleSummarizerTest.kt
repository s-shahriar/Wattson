package com.syed.wattson.ui.model

import com.syed.wattson.data.model.HistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The cycles card is built from the same reduced history the chart uses, so these
 * fixtures are shaped the way the reducer emits them: one sample per actual change in
 * level, charge state or screen state, and nothing in between.
 */
class CycleSummarizerTest {

    private val zone = ZoneId.of("Asia/Dhaka")
    private val start = 1_700_000_000_000L

    private fun at(minutes: Long) = start + minutes * 60_000

    private fun point(
        minutes: Long,
        level: Int,
        charging: Boolean = false,
        screenOn: Boolean = false,
    ) = HistoryPoint(at(minutes), level, charging, screenOn)

    private fun summarize(vararg points: HistoryPoint) = summarizeCycles(points.toList(), zone)

    /**
     * The last run is dropped for still being in progress, and a run that begins at the
     * oldest sample the buffer holds comes back flagged [CycleUi.partial] — so a fixture
     * that wants one plain row starts on a charging sample and ends mid-discharge.
     */
    @Test
    fun `splits runs on the charge transitions and times the screen states`() {
        val cycles = summarize(
            point(0, 100, charging = true),
            // Run under test: 100 minutes, 40 of them with the screen on, 90 -> 70.
            point(10, 90, screenOn = true),
            point(50, 85),
            point(110, 70),
            point(110, 70, charging = true),
            point(200, 95, charging = true),
            point(210, 95),
            point(400, 40),
        )

        assertEquals(1, cycles.size)
        val cycle = cycles.first()
        assertEquals(40 * 60_000L, cycle.screenOnMs)
        assertEquals(60 * 60_000L, cycle.screenOffMs)
        assertEquals(100 * 60_000L, cycle.onBatteryMs)
        assertEquals(20, cycle.usedPercent)
        assertEquals(0.4f, cycle.screenOnFraction, 0.001f)
    }

    /** Newest first, so the card reads top-down like the rest of the screen. */
    @Test
    fun `cycles come back newest first`() {
        val cycles = summarize(
            point(0, 100, charging = true),
            point(10, 99),
            point(310, 60),      // 300 minutes
            point(310, 60, charging = true),
            point(320, 90, charging = true),
            point(330, 90),
            point(480, 60),      // 150 minutes
            point(480, 60, charging = true),
            point(500, 95, charging = true),
            point(510, 95),
            point(600, 80),
        )

        assertEquals(listOf(150, 300), cycles.map { (it.onBatteryMs / 60_000).toInt() })
    }

    /**
     * A day of plugging and unplugging logs dozens of two-minute runs. They are not
     * cycles, and seven of them in a row would say nothing at all.
     */
    @Test
    fun `blips are not cycles`() {
        val cycles = summarize(
            point(0, 100, charging = true),
            // Long enough, but 2% — a phone left off the charger while it was idle.
            point(10, 60),
            point(90, 58, charging = true),
            point(100, 70, charging = true),
            // Steep enough, but over in ten minutes.
            point(110, 70),
            point(120, 60, charging = true),
            point(200, 90, charging = true),
            point(210, 90),
            point(300, 70),
        )

        assertTrue("blips were reported as cycles", cycles.isEmpty())
    }

    /** The run still going is the one the session card above is already reporting. */
    @Test
    fun `the run in progress is left out`() {
        val cycles = summarize(
            point(0, 100, charging = true),
            point(10, 99),
            point(200, 50),
            point(200, 50, charging = true),
            point(260, 95, charging = true),
            point(270, 95),
            point(500, 40),
        )

        assertEquals(1, cycles.size)
        assertEquals(190 * 60_000L, cycles.first().onBatteryMs)
    }

    /**
     * History that opens mid-discharge has a clipped first run, not a short one — and it
     * is still the only completed run there is on a phone whose buffer was reset once
     * since it was last unplugged. Dropping it emptied the card and gave no reason.
     */
    @Test
    fun `the run the buffer cut off comes back flagged`() {
        val cycles = summarize(
            point(0, 80),
            point(120, 40),
            point(120, 40, charging = true),
            point(180, 100, charging = true),
            point(190, 100),
            point(400, 50),
        )

        assertEquals(1, cycles.size)
        assertTrue("the clipped run was not flagged", cycles.first().partial)
        assertEquals(40, cycles.first().usedPercent)
        assertEquals(120 * 60_000L, cycles.first().onBatteryMs)
    }

    /** Only the run the buffer opens inside is a floor; the ones after it are exact. */
    @Test
    fun `runs the buffer holds whole are not flagged`() {
        val cycles = summarize(
            point(0, 100, charging = true),
            point(10, 99),
            point(200, 50),
            point(200, 50, charging = true),
            point(260, 95, charging = true),
            point(270, 95),
            point(500, 40),
        )

        assertEquals(1, cycles.size)
        assertTrue("a run wholly inside the buffer was flagged", !cycles.first().partial)
    }

    /**
     * The shape this device was in on 31 August, and the bug it exposed: `batterystats`
     * had reset its buffer at midday, the phone ran to 4% that night, charged, and was
     * still on that charge when the card was drawn. One run clipped, one in progress —
     * and with the clipped one dropped the card had nothing to draw and vanished.
     */
    @Test
    fun `one clipped run and one in progress still fill the card`() {
        val cycles = summarize(
            point(0, 90),
            point(700, 4),
            point(700, 4, charging = true),
            point(760, 84, charging = true),
            point(770, 84),
            point(1_360, 53),
        )

        assertEquals(1, cycles.size)
        assertTrue(cycles.first().partial)
        assertEquals(86, cycles.first().usedPercent)
    }

    /**
     * Verbatim shape of what this device logged on 24 August: the phone ran to 0%, shut
     * down, and logged nothing more until it booted two hours later already charging at
     * 100. Closing the run on that sample read the discharge as a 7% *gain* and dropped
     * the biggest cycle of the week off the card.
     */
    @Test
    fun `a run that ends in a flat battery is measured to its last discharging sample`() {
        val cycles = summarize(
            point(0, 100, charging = true),
            point(10, 95),
            point(100, 60),
            point(100, 60, charging = true),
            point(140, 93, charging = true),
            // The run under test: 93% down to nothing over twenty hours.
            point(150, 93),
            point(1_390, 0),
            // Two hours off, then a boot at a full battery.
            point(1_520, 100, charging = true),
            point(1_600, 100),
            point(1_700, 90),
        )

        // Two complete runs here; the flat-battery one is the newer, so it leads.
        assertEquals(2, cycles.size)
        val cycle = cycles.first()
        assertEquals(93, cycle.usedPercent)
        assertEquals(1_240 * 60_000L, cycle.onBatteryMs)
    }

    /**
     * Verbatim shape of what this device logged on 25 August: a minute plugged into AC at
     * 41% that gave back one percent. It ended the run, and a twenty-two hour cycle was
     * reported as 16h38/59% followed by 5h18/42% — two cycles the phone never had.
     */
    @Test
    fun `a charge too brief to be a charge does not end a run`() {
        val cycles = summarize(
            point(0, 100, charging = true),
            // One run: 100 -> 41, a minute on a charger, then 42 -> 0.
            point(10, 100, screenOn = true),
            point(1_000, 41),
            point(1_001, 41, charging = true),
            point(1_002, 42),
            point(1_320, 0),
            // A real charge, and a fresh run to keep this one off the in-progress rule.
            point(1_460, 100, charging = true),
            point(1_470, 100),
            point(1_600, 60),
        )

        assertEquals(1, cycles.size)
        val cycle = cycles.first()
        // 990 minutes before the blip and 318 after it. The minute on the charger is in
        // neither: it was not time on battery.
        assertEquals(1_308 * 60_000L, cycle.onBatteryMs)
        assertEquals(990 * 60_000L, cycle.screenOnMs)
        // 59 down to the blip and 42 after it, which is one percent more than the cell
        // holds, because the blip handed one back.
        assertEquals(100, cycle.usedPercent)
    }

    /** Brief, but a tenth of the battery back is a charge whatever the clock says. */
    @Test
    fun `a short charge that puts real charge back does end a run`() {
        val cycles = summarize(
            point(0, 100, charging = true),
            point(10, 90),
            point(200, 50),
            point(201, 50, charging = true),
            point(203, 62, charging = true),
            point(210, 62),
            point(400, 20),
            point(460, 100, charging = true),
            point(470, 100),
            point(500, 90),
        )

        assertEquals(listOf(190, 190), cycles.map { (it.onBatteryMs / 60_000).toInt() })
        assertEquals(listOf(42, 40), cycles.map { it.usedPercent })
    }

    @Test
    fun `no history means no card`() {
        assertTrue(summarizeCycles(emptyList(), zone).isEmpty())
        assertTrue(summarize(point(0, 100)).isEmpty())
    }

    /** Seven rows is what the card has room for. */
    @Test
    fun `at most seven cycles come back`() {
        val points = ArrayList<HistoryPoint>()
        points.add(point(0, 100, charging = true))
        var minute = 10L
        repeat(12) {
            points.add(point(minute, 100))
            points.add(point(minute + 120, 60))
            points.add(point(minute + 120, 60, charging = true))
            points.add(point(minute + 180, 100, charging = true))
            minute += 190
        }
        points.add(point(minute, 100))
        points.add(point(minute + 60, 80))

        assertEquals(7, summarizeCycles(points, zone).size)
    }
}
