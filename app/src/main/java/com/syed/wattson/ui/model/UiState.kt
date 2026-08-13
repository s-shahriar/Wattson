package com.syed.wattson.ui.model

/** Exhaustive set of things the battery screen can be showing. */
sealed interface UiState {
    data object Loading : UiState
    data class Ready(val model: BatteryUiModel) : UiState
    data class NoRoot(val detail: String) : UiState
    data class Failed(val message: String) : UiState
}
