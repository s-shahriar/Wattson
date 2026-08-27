package com.syed.wattson.ui.diagnose

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syed.wattson.data.BatteryRepository
import com.syed.wattson.data.model.DiagnosisIndex
import com.syed.wattson.ui.model.WindowAnalyzer
import com.syed.wattson.ui.model.WindowReport
import kotlinx.coroutines.Job
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Which of the two ways of choosing a window is showing. */
enum class WindowMode { LEVEL, TIME }

sealed interface DiagnoseState {
    /** Nothing has been asked for. This is what opening the tab looks like. */
    data object Idle : DiagnoseState

    data object Working : DiagnoseState

    data class Ready(val report: WindowReport) : DiagnoseState

    data class Failed(val message: String) : DiagnoseState
}

/**
 * Drives the Diagnose tab, and does absolutely nothing until asked.
 *
 * There is no `init` block, no poll, no observer and no work on construction: opening the
 * tab costs a `BatteryRepository` instance and nothing else. The dump runs on [analyze],
 * which is reachable only from the confirm button, and [release] drops the index the
 * moment the tab is left — so the megabyte it costs is held only while the answers built
 * from it are in front of somebody.
 */
class DiagnoseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BatteryRepository(application)

    /** Kept between queries so a second window does not pay for a second dump. */
    private var index: DiagnosisIndex? = null
    private var running: Job? = null

    val labels = AppLabels(application)

    var state by mutableStateOf<DiagnoseState>(DiagnoseState.Idle)
        private set

    var mode by mutableStateOf(WindowMode.LEVEL)
        private set

    /** Level range, as a pair of percentages. Defaults to the ten points above empty-ish. */
    var levelFrom by mutableStateOf(DEFAULT_LEVEL_FROM)
        private set
    var levelTo by mutableStateOf(DEFAULT_LEVEL_TO)
        private set

    /** Which day the clock times below belong to. */
    var day by mutableStateOf(LocalDate.now())
        private set

    /** Wall-clock start and end. An end before the start means it ran past midnight. */
    var fromTime by mutableStateOf(LocalTime.of(DEFAULT_FROM_HOUR, 0))
        private set
    var toTime by mutableStateOf(LocalTime.of(DEFAULT_TO_HOUR, 0))
        private set

    /** Days the picker offers, newest first — as far back as the history ever reaches. */
    val selectableDays: List<LocalDate>
        get() = (0 until DAYS_OFFERED).map { LocalDate.now().minusDays(it.toLong()) }

    /** True when the end time is earlier than the start, so the window crosses midnight. */
    val crossesMidnight: Boolean get() = !toTime.isAfter(fromTime)

    fun selectMode(next: WindowMode) {
        if (next == mode) return
        mode = next
        // The window on screen answers a question that is no longer being asked.
        if (state is DiagnoseState.Ready || state is DiagnoseState.Failed) {
            state = DiagnoseState.Idle
        }
    }

    fun setLevels(from: Int, to: Int) {
        levelFrom = from.coerceIn(1, 100)
        levelTo = to.coerceIn(0, 99)
        invalidate()
    }

    fun selectDay(next: LocalDate) {
        day = next
        invalidate()
    }

    fun selectFromTime(next: LocalTime) {
        fromTime = next
        invalidate()
    }

    fun selectToTime(next: LocalTime) {
        toTime = next
        invalidate()
    }

    /**
     * Results stop describing the sliders as soon as the sliders move.
     *
     * Left showing, they would read as the answer for the window now on screen, which is
     * the one thing they are not.
     */
    private fun invalidate() {
        if (state is DiagnoseState.Ready || state is DiagnoseState.Failed) {
            state = DiagnoseState.Idle
        }
    }

    /** The confirm button, and the only thing in this class that reads the device. */
    fun analyze() {
        if (running?.isActive == true) return
        state = DiagnoseState.Working
        running = viewModelScope.launch {
            val loaded = index ?: repository.loadDiagnosisIndex()?.also { index = it }
            if (loaded == null) {
                state = DiagnoseState.Failed(
                    "Couldn't read the history. It needs root or the DUMP permission, " +
                        "and a dump that dumpsys didn't cut short.",
                )
                return@launch
            }
            state = when (mode) {
                WindowMode.LEVEL -> fromLevels(loaded)
                WindowMode.TIME -> fromClock(loaded)
            }
        }
    }

    private fun fromLevels(loaded: DiagnosisIndex): DiagnoseState {
        val range = WindowAnalyzer.windowForLevels(loaded, levelFrom, levelTo)
            ?: return DiagnoseState.Failed(
                "The history doesn't hold a run from $levelFrom% down to $levelTo%.",
            )
        val report = WindowAnalyzer.analyze(loaded, range.first, range.last)
            ?: return DiagnoseState.Failed("That run was over too quickly to say anything about.")
        return DiagnoseState.Ready(report)
    }

    private fun fromClock(loaded: DiagnosisIndex): DiagnoseState {
        val zone = ZoneId.systemDefault()
        val start = day.atTime(fromTime).atZone(zone).toInstant().toEpochMilli()
        // An end at or before the start is a window that ran past midnight into the next
        // day, which is what most of a phone's idle drain does.
        val endDay = if (crossesMidnight) day.plusDays(1) else day
        val end = endDay.atTime(toTime).atZone(zone).toInstant().toEpochMilli()

        val fromSec = loaded.msToSeconds(start)
        val toSec = loaded.msToSeconds(end)
        if (toSec <= 0) {
            return DiagnoseState.Failed(
                "The history doesn't reach back to ${day.format(DAY_LABEL)}.",
            )
        }
        if (fromSec > loaded.endSec) {
            return DiagnoseState.Failed("That window hasn't happened yet.")
        }
        val report = WindowAnalyzer.analyze(loaded, fromSec, toSec)
            ?: return DiagnoseState.Failed(
                "That window is shorter than a minute, or outside what the history holds.",
            )
        return DiagnoseState.Ready(report)
    }

    /**
     * Drop everything the tab was holding.
     *
     * Called when the tab is left or the app stops being shown. A megabyte of spans is
     * worth keeping while somebody is reading the answers built from it and not one
     * moment longer.
     */
    fun release() {
        running?.cancel()
        running = null
        index = null
        state = DiagnoseState.Idle
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }

    private companion object {
        const val DEFAULT_LEVEL_FROM = 40
        const val DEFAULT_LEVEL_TO = 30
        const val DEFAULT_FROM_HOUR = 10
        const val DEFAULT_TO_HOUR = 11

        /** The buffer holds about a week on a quiet phone, and rather less on a busy one. */
        const val DAYS_OFFERED = 8

        val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
    }
}
