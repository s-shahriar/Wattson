package com.syed.wattson.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syed.wattson.ui.model.BatteryUiModel
import com.syed.wattson.ui.model.UiState
import com.syed.wattson.ui.section.BatteryHistorySection
import com.syed.wattson.ui.section.BatteryStatusSection
import com.syed.wattson.ui.section.CapabilitySection
import com.syed.wattson.ui.section.CycleHistorySection
import com.syed.wattson.ui.section.SessionSection
import com.syed.wattson.ui.section.UpdateSection
import com.syed.wattson.ui.util.formatStartClock

/**
 * Screen shell: app bar, refresh affordance and state routing. All rendering of an actual
 * report lives in the `section` package.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryScreen(viewModel: BatteryViewModel = viewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val state = viewModel.state

    // Live cards tick only while this screen is actually resumed in front of the user.
    // Minimising, switching apps or quitting fires onPauseOrDispose and cancels the poll.
    LifecycleResumeEffect(viewModel) {
        viewModel.startLiveUpdates()
        onPauseOrDispose { viewModel.stopLiveUpdates() }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
            MediumTopAppBar(
                title = { TitleBlock(state) },
                actions = {
                    // Lives in the bar rather than a FAB: a floating button sat on top of
                    // the content at every scroll position.
                    FilledTonalIconButton(
                        onClick = viewModel::refresh,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
            // Only while the live card is up but the dumps have not landed yet.
            if (viewModel.isLoadingStats && state is UiState.Ready) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            // Only drive the pull indicator once a report is on screen. During the first
            // load the centred spinner is already showing, and both at once read as two
            // competing progress indicators.
            isRefreshing = viewModel.isRefreshing && state is UiState.Ready,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state) {
                UiState.Loading -> LoadingState()
                is UiState.NoRoot -> MessageState("Root required", state.detail)
                is UiState.Failed -> MessageState("Couldn't read stats", state.message)
                is UiState.Ready -> ReportContent(state.model)
            }
        }
    }
}

@Composable
private fun TitleBlock(state: UiState) {
    Column {
        Text(text = "Wattson", fontWeight = FontWeight.Bold)
        val since = (state as? UiState.Ready)?.model?.startClock?.let(::formatStartClock)
        if (since != null) {
            Text(
                text = "since last charge · $since",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportContent(model: BatteryUiModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "status") { BatteryStatusSection(model) }
        item(key = "capability") { CapabilitySection(model) }
        item(key = "history") { BatteryHistorySection(model) }
        item(key = "session") { SessionSection(model) }
        item(key = "cycles") { CycleHistorySection(model) }
        item(key = "updates") { UpdateSection() }
    }
}

@Composable
private fun LoadingState() {
    CenteredMessage {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text("Reading battery stats…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MessageState(title: String, detail: String) {
    CenteredMessage {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
