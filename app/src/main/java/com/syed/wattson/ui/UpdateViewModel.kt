package com.syed.wattson.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syed.wattson.BuildConfig
import com.syed.wattson.data.DownloadProgress
import com.syed.wattson.data.UpdateInfo
import com.syed.wattson.data.UpdateService
import kotlinx.coroutines.launch
import java.io.File

/** Where the update flow currently is. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val progress: DownloadProgress) : UpdateState
    data class ReadyToInstall(val file: File, val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Drives the self-update flow. Entirely user-initiated — nothing here polls or checks on
 * launch, so the app still makes no network request unless you press the button.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val service = UpdateService(application)

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    val currentVersion: String = BuildConfig.VERSION_NAME

    // Partial downloads are deliberately NOT cleared here: on a slow link a retry
    // should resume via a Range request rather than start over.

    fun check() {
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        viewModelScope.launch {
            state = UpdateState.Checking
            state = runCatching { service.checkForUpdate() }
                .fold(
                    onSuccess = { info ->
                        if (info.available) {
                            UpdateState.Available(info)
                        } else {
                            UpdateState.UpToDate(info.currentVersion)
                        }
                    },
                    onFailure = { UpdateState.Failed(it.message ?: "Update check failed") },
                )
        }
    }

    fun download(info: UpdateInfo) {
        if (state is UpdateState.Downloading) return
        viewModelScope.launch {
            state = UpdateState.Downloading(DownloadProgress(0f, 0L, null, 0L))
            state = runCatching {
                val file = service.download(info) { progress ->
                    state = UpdateState.Downloading(progress)
                }
                UpdateState.ReadyToInstall(file, info)
            }.getOrElse { UpdateState.Failed(it.message ?: "Download failed") }
        }
    }

    /** Hands the APK to the package installer; the system takes it from here. */
    fun install(file: File) {
        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { state = UpdateState.Failed(it.message ?: "Could not open installer") }
    }

    /** Opens the release page, for when the in-app install is refused. */
    fun openReleasePage() {
        val context = getApplication<Application>()
        val intent = Intent(
            Intent.ACTION_VIEW,
            UpdateService.releasePageUrl().toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun dismiss() {
        state = UpdateState.Idle
    }
}
