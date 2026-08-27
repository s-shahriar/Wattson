package com.syed.wattson.ui.diagnose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syed.wattson.ui.component.SectionCard
import com.syed.wattson.ui.model.WindowReport
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The Diagnose tab: choose a window, confirm it, read what happened in it.
 *
 * Nothing below the picker exists until the confirm button has been pressed, and moving
 * either slider takes it away again — a report that stays on screen while the sliders move
 * reads as the answer for the window now showing, which is the one thing it is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnoseScreen(
    modifier: Modifier = Modifier,
    viewModel: DiagnoseViewModel = viewModel(),
) {
    val state = viewModel.state

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "picker") { WindowPicker(viewModel, state) }

        when (state) {
            is DiagnoseState.Ready -> reportCards(state.report, viewModel.labels)
            is DiagnoseState.Failed -> item(key = "failed") { Notice(state.message) }
            DiagnoseState.Working -> item(key = "working") { Working() }
            DiagnoseState.Idle -> item(key = "idle") { Notice(IDLE_HINT) }
        }
    }
}

private const val IDLE_HINT =
    "Pick a window and press Analyse. Nothing is read from the phone until you do — " +
        "Wattson takes one dump of the battery history on that tap and holds it only " +
        "while these answers are on screen."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowPicker(viewModel: DiagnoseViewModel, state: DiagnoseState) {
    SectionCard(
        title = "Window",
        subtitle = "what stretch of the history to look at",
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            WindowMode.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = viewModel.mode == entry,
                    onClick = { viewModel.selectMode(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, WindowMode.entries.size),
                ) {
                    Text(if (entry == WindowMode.LEVEL) "By level" else "By time")
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        when (viewModel.mode) {
            WindowMode.LEVEL -> LevelPicker(viewModel)
            WindowMode.TIME -> TimePicker(viewModel)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = viewModel::analyze,
            enabled = state != DiagnoseState.Working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state == DiagnoseState.Working) "Reading the history…" else "Analyse")
        }
    }
}

@Composable
private fun LevelPicker(viewModel: DiagnoseViewModel) {
    // The slider runs low to high because that is the direction an axis runs; the window
    // it describes runs the other way, which is what the caption says out loud.
    val from = viewModel.levelTo.toFloat()
    val to = viewModel.levelFrom.toFloat()
    PickerLabel("From ${viewModel.levelFrom}% down to ${viewModel.levelTo}%")
    RangeSlider(
        value = from..to,
        onValueChange = { range ->
            viewModel.setLevels(range.endInclusive.roundToInt(), range.start.roundToInt())
        },
        valueRange = 0f..100f,
        modifier = Modifier.fillMaxWidth(),
    )
    Caption(
        "Measures the last completed run through that range — which may be days ago, and " +
            "is never the run the battery is on now.",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePicker(viewModel: DiagnoseViewModel) {
    var editing by remember { mutableStateOf<Editing?>(null) }

    Text(
        text = "Day",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    // A row of days rather than a calendar: the history holds about a week, so the whole
    // of what can be asked about fits on one line and needs no dialog to reach.
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(viewModel.selectableDays) { date ->
            FilterChip(
                selected = viewModel.day == date,
                onClick = { viewModel.selectDay(date) },
                label = { Text(dayLabel(date)) },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ClockField("From", viewModel.fromTime, { editing = Editing.FROM }, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        ClockField("To", viewModel.toTime, { editing = Editing.TO }, Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
    Caption(
        if (viewModel.crossesMidnight) {
            // An end at or before the start ran into the next day, which is what most of
            // a phone's idle drain does.
            "${dayLabel(viewModel.day)} ${clock(viewModel.fromTime)} → " +
                "${dayLabel(viewModel.day.plusDays(1))} ${clock(viewModel.toTime)}"
        } else {
            "${dayLabel(viewModel.day)}, ${clock(viewModel.fromTime)} → ${clock(viewModel.toTime)}"
        },
    )

    editing?.let { which ->
        ClockDialog(
            initial = if (which == Editing.FROM) viewModel.fromTime else viewModel.toTime,
            onDismiss = { editing = null },
            onPick = { picked ->
                if (which == Editing.FROM) {
                    viewModel.selectFromTime(picked)
                } else {
                    viewModel.selectToTime(picked)
                }
                editing = null
            },
        )
    }
}

private enum class Editing { FROM, TO }

@Composable
private fun ClockField(
    label: String,
    time: LocalTime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = clock(time), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        // Typed, not dialled: a clock face is charming and slow, and this is a field
        // somebody is going to change three times in a row while narrowing something down.
        text = { TimeInput(state = state) },
    )
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

private fun clock(time: LocalTime): String = time.format(CLOCK)

private fun dayLabel(date: LocalDate): String = when (date) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> date.format(DateTimeFormatter.ofPattern("d MMM"))
}


@Composable
private fun PickerLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Working() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Reading the battery history",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "one dump, a few seconds, nothing kept after",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun Notice(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp),
    )
}

/** The window's own heading, so a report can be told apart from the sliders that made it. */
@Composable
fun WindowHeading(report: WindowReport) {
    val zone = ZoneId.systemDefault()
    val dated = DateTimeFormatter.ofPattern("d MMM h:mm a")
    val timeOnly = DateTimeFormatter.ofPattern("h:mm a")
    val began = Instant.ofEpochMilli(report.startMs).atZone(zone)
    val ended = Instant.ofEpochMilli(report.endMs).atZone(zone)
    val endFormat = if (ended.toLocalDate() == began.toLocalDate()) timeOnly else dated
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = began.format(dated),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "→ ${ended.format(endFormat)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
