package com.syed.wattson.ui.util

import kotlin.math.abs
import kotlin.math.roundToInt

/** "6h 26m", "44m 12s", "18s" — compact, at most two units. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

/** "6h 26m 7s" — used where the extra precision is worth the width. */
fun formatDurationLong(ms: Long): String {
    if (ms <= 0) return "0s"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (hours > 0) append("${hours}h ")
        if (hours > 0 || minutes > 0) append("${minutes}m ")
        append("${seconds}s")
    }.trim()
}

/** Three significant figures without trailing noise: 765, 64.1, 0.209. */
fun formatMah(value: Double): String = when {
    abs(value) >= 100 -> value.roundToInt().toString()
    abs(value) >= 10 -> String.format("%.1f", value)
    abs(value) >= 1 -> String.format("%.2f", value)
    else -> String.format("%.3f", value)
}

fun formatPercent(fraction: Float): String = "${(fraction * 100).roundToInt()}%"

/** "2026-08-12-13-01-16" -> "12 Aug, 13:01". */
fun formatStartClock(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split("-")
    if (parts.size < 5) return raw
    val months = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    val month = parts[1].toIntOrNull()?.let { months.getOrNull(it - 1) } ?: return raw
    val day = parts[2].toIntOrNull() ?: return raw
    val hour24 = parts[3].toIntOrNull() ?: return raw
    // dumpsys reports a 24-hour clock; the history chart labels use "h:mm a", and the two
    // sat side by side reading 14:42 and 2:42 PM for the same instant.
    val hour12 = (hour24 % 12).takeIf { it != 0 } ?: 12
    val meridiem = if (hour24 < 12) "AM" else "PM"
    return "$day $month, $hour12:${parts[4]} $meridiem"
}

/** Human label for the dumpsys bucket keys. */
fun bucketLabel(key: String): String = when (key) {
    "screen" -> "Screen"
    "cpu" -> "CPU"
    "video" -> "Video"
    "audio" -> "Audio"
    "wifi" -> "Wi-Fi"
    "mobile_radio" -> "Mobile radio"
    "phone" -> "Phone calls"
    "wakelock" -> "Wakelocks"
    "flashlight" -> "Flashlight"
    "camera" -> "Camera"
    "sensors" -> "Sensors"
    "gnss" -> "GPS"
    "bluetooth" -> "Bluetooth"
    "idle" -> "Idle"
    "memory" -> "Memory"
    else -> key.replaceFirstChar(Char::uppercase).replace('_', ' ')
}

/**
 * Renders a 0f..1f share as a percentage, dropping to one decimal below 1% so the long
 * tail of sub-percent categories does not all collapse to "0%".
 */
fun formatSharePercent(share: Float): String {
    val percent = share * 100
    return when {
        percent >= 1f -> "${percent.roundToInt()}%"
        percent > 0f -> String.format("%.1f%%", percent)
        else -> "0%"
    }
}

/** Average current draw, e.g. "143 mA" or "6.5 mA" when the figure is small. */
fun formatMilliAmps(value: Double): String = when {
    value >= 10 -> "${value.roundToInt()} mA"
    else -> String.format("%.1f mA", value)
}

/** Decimal hours as "9h 55m", or "48m" when under an hour. */
fun formatHours(hours: Double): String {
    val totalMinutes = (hours * 60).roundToInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
