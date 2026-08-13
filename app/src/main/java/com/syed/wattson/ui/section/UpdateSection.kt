package com.syed.wattson.ui.section

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syed.wattson.ui.UpdateState
import com.syed.wattson.ui.UpdateViewModel
import com.syed.wattson.ui.component.SectionCard
import kotlin.math.roundToInt

/** "12.4 MB", "804 KB" — compact sizes for the download readout. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun formatEta(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}

/** Maximum release-note lines shown inline. */
private const val NOTES_MAX_LINES = 6

/**
 * Self-update card.
 *
 * Checks GitHub releases only when tapped — the app performs no network activity
 * otherwise, and never on launch.
 */
@Composable
fun UpdateSection(
    modifier: Modifier = Modifier,
    viewModel: UpdateViewModel = viewModel(),
) {
    val state = viewModel.state

    SectionCard(
        title = "Updates",
        subtitle = "Version ${viewModel.currentVersion}",
        modifier = modifier.animateContentSize(),
    ) {
        when (state) {
            UpdateState.Idle -> {
                Button(onClick = viewModel::check) { Text("Check for updates") }
            }

            UpdateState.Checking -> {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(0.dp))
                    Text(
                        text = "  Checking GitHub…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is UpdateState.UpToDate -> {
                Text(
                    text = "You're on the latest version.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = viewModel::check) { Text("Check again") }
            }

            is UpdateState.Available -> {
                Text(
                    text = "Version ${state.info.latestVersion} is available.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                state.info.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = NOTES_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.download(state.info) }) { Text("Download") }
                    TextButton(onClick = viewModel::openReleasePage) { Text("View release") }
                }
            }

            is UpdateState.Downloading -> {
                val p = state.progress
                Text(
                    text = "Downloading… ${(p.fraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(formatBytes(p.bytesDownloaded))
                        p.totalBytes?.let { append(" of ").append(formatBytes(it)) }
                        if (p.bytesPerSecond > 0) {
                            append(" · ").append(formatBytes(p.bytesPerSecond)).append("/s")
                        }
                        p.etaSeconds?.let { append(" · ").append(formatEta(it)).append(" left") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { p.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "A slow connection can take several minutes. If it stalls, " +
                        "retrying resumes where it left off.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is UpdateState.ReadyToInstall -> {
                Text(
                    text = "Version ${state.info.latestVersion} downloaded.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.install(state.file) }) { Text("Install") }
                    TextButton(onClick = viewModel::dismiss) { Text("Later") }
                }
            }

            is UpdateState.Failed -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = viewModel::check) { Text("Try again") }
                    TextButton(onClick = viewModel::openReleasePage) { Text("Open GitHub") }
                }
            }
        }
    }
}
