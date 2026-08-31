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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
    // Stepped, not dragged. A range slider on a 392dp screen moves about three percent per
    // pixel of thumb, so landing on 41 rather than 38 is a matter of luck; these are the
    // two numbers the question is actually about and they are worth being able to state.
    QuickRow(LEVEL_PRESETS) { preset -> viewModel.selectLastPercent(preset.points) }

    Spacer(Modifier.height(14.dp))
    LevelRow(
        label = "From",
        value = viewModel.levelFrom,
        onDelta = { delta -> viewModel.setLevels(viewModel.levelFrom + delta, viewModel.levelTo) },
    )
    Spacer(Modifier.height(8.dp))
    LevelRow(
        label = "down to",
        value = viewModel.levelTo,
        onDelta = { delta -> viewModel.setLevels(viewModel.levelFrom, viewModel.levelTo + delta) },
    )

    Spacer(Modifier.height(12.dp))
    PickerLabel(
        "${viewModel.levelFrom - viewModel.levelTo} points, " +
            "${viewModel.levelFrom}% → ${viewModel.levelTo}%",
    )
    Spacer(Modifier.height(4.dp))
    Caption(
        "Answered by the most recent single run on battery that covers the whole range. A " +
            "stretch spanning a charge is never used, so nothing below is half one cycle " +
            "and half the one before it.",
    )
}

/** One end of the level range: a name, a number, and a percent either side of it. */
@Composable
private fun LevelRow(label: String, value: Int, onDelta: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        StepButton("−") { onDelta(-1) }
        Text(
            text = "$value%",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(74.dp),
        )
        StepButton("+") { onDelta(+1) }
    }
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Text(text = glyph, style = MaterialTheme.typography.titleLarge)
    }
}

/** A scrolling strip of one-tap windows. Quicker to reach than any picker, and reversible. */
@Composable
private fun <T : Preset> QuickRow(presets: List<T>, onPick: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets) { preset ->
            SuggestionChip(onClick = { onPick(preset) }, label = { Text(preset.label) })
        }
    }
}

private interface Preset {
    val label: String
}

private data class LevelPreset(override val label: String, val points: Int) : Preset

private data class TimePreset(
    override val label: String,
    /** Minutes back from now, or null for "today so far". */
    val minutes: Long?,
) : Preset

private val LEVEL_PRESETS = listOf(
    LevelPreset("Last 5%", 5),
    LevelPreset("Last 10%", 10),
    LevelPreset("Last 15%", 15),
    LevelPreset("Last 20%", 20),
    LevelPreset("Last 30%", 30),
)

private val TIME_PRESETS = listOf(
    TimePreset("Last 15 min", 15),
    TimePreset("Last 30 min", 30),
    TimePreset("Last hour", 60),
    TimePreset("Last 2 hr", 120),
    TimePreset("Last 6 hr", 360),
    TimePreset("Today", null),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePicker(viewModel: DiagnoseViewModel) {
    var editing by remember { mutableStateOf<Editing?>(null) }

    QuickRow(TIME_PRESETS) { preset ->
        preset.minutes?.let(viewModel::selectLastMinutes) ?: viewModel.selectToday()
    }
    Spacer(Modifier.height(14.dp))

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
    // The dial leads. It is the face every alarm clock on the phone already uses, so the
    // gesture is known and the hour lands in one drag, where typing it means two fields, a
    // keyboard and an AM/PM. The keypad is one tap away for what a dial is bad at, which
    // is anything that has to be exact to the minute.
    var dial by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (dial) TimePicker(state = state) else TimeInput(state = state)
                TextButton(onClick = { dial = !dial }) {
                    Text(if (dial) "Type the time instead" else "Use the dial instead")
                }
            }
        },
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
