package com.syed.wattson.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syed.wattson.data.BatteryRepository
import com.syed.wattson.data.model.StatsUnavailableException
import com.syed.wattson.ui.model.UiState
import com.syed.wattson.ui.model.toLiveOnlyUiModel
import com.syed.wattson.ui.model.toUiModel
import com.syed.wattson.ui.model.withLive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How often the live cards refresh while the app is in the foreground. */
private const val LIVE_INTERVAL_MS = 5_000L

/**
 * Holds the report for the lifetime of the screen.
 *
 * Two kinds of work, both strictly pull-based:
 *  - [refresh] does the full, expensive load (first composition and pull-to-refresh).
 *  - [startLiveUpdates]/[stopLiveUpdates] drive a 5-second poll of only the live values,
 *    started when the screen resumes and cancelled the moment it pauses.
 *
 * `viewModelScope` is cancelled when the activity is finished, so quitting the app tears
 * down the poll even if a stop call were somehow missed.
 */
class BatteryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BatteryRepository(application)

    var state by mutableStateOf<UiState>(UiState.Loading)
        private set

    var isRefreshing by mutableStateOf(false)
        private set

    /** True while the slow dumpsys reads are still running behind a live-only model. */
    var isLoadingStats by mutableStateOf(false)
        private set

    private var liveJob: Job? = null

    init {
        refresh()
    }

    /** Full reload: re-runs the batterystats dump and rebuilds every section. */
    fun refresh() {
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            isLoadingStats = true

            // First paint: the live snapshot costs microseconds, so show the status card
            // immediately rather than holding a blank screen for the multi-second dumps.
            if (state !is UiState.Ready) {
                val quick = runCatching {
                    repository.loadLive()?.toLiveOnlyUiModel(repository.currentTier())
                }.getOrNull()
                if (quick != null) state = UiState.Ready(quick)
            }

            state = loadState()
            isLoadingStats = false
            isRefreshing = false
        }
    }

    /**
     * Begins polling the live values. Idempotent — calling it while already running is a
     * no-op, so repeated resume events cannot stack up multiple loops.
     */
    fun startLiveUpdates() {
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            while (isActive) {
                delay(LIVE_INTERVAL_MS)
                // Only meaningful once a full report exists to merge into.
                val current = state as? UiState.Ready ?: continue
                val snapshot = repository.loadLive() ?: continue
                state = UiState.Ready(current.model.withLive(snapshot))
            }
        }
    }

    /** Stops polling. Called when the screen leaves the foreground. */
    fun stopLiveUpdates() {
        liveJob?.cancel()
        liveJob = null
    }

    override fun onCleared() {
        stopLiveUpdates()
        super.onCleared()
    }

    private suspend fun loadState(): UiState = try {
        UiState.Ready(repository.load().toUiModel())
    } catch (e: StatsUnavailableException) {
        UiState.Failed(e.message.orEmpty())
    } catch (e: Exception) {
        UiState.Failed(e.message ?: "Unexpected failure")
    }
}
