package com.syed.wattson.data.model

/**
 * Things one app does that a window can be blamed on.
 *
 * Both are single-slot states in the history — one app on top, one lock keeping the
 * device awake — which is why neither can overlap itself. Jobs and syncs were indexed
 * here too, twenty thousand spans of them and a third of everything this index costs, and
 * nothing read them once the card that showed them was dropped. The jobs that matter to a
 * battery reach this list anyway, as the `*job*r/...` wakelocks they take out.
 */
enum class EventKind { FOREGROUND, WAKELOCK }

/** Device-wide states a window can be blamed on, with no app attached. */
enum class SystemFlag(val label: String) {
    SCREEN("Screen on"),
    CPU_RUNNING("CPU awake"),
    CELLULAR_HIGH_TX("Cellular straining"),
    MOBILE_RADIO("Mobile data active"),
    WIFI_SCAN("Wi-Fi scanning"),
    AUDIO("Audio"),
    VIDEO("Video"),
    SENSOR("Sensors"),
    BLE_SCAN("Bluetooth scanning"),
    GPS("GPS"),
}

/**
 * Spans of one kind, packed into parallel arrays.
 *
 * Seconds from the index epoch rather than epoch milliseconds, and an id into a shared
 * name pool rather than a string per span: the buffer holds around a hundred thousand of
 * these, and as objects they would cost more than the samples the rest of the app keeps
 * for everything else put together.
 */
class PackedSpans(
    val startSec: IntArray,
    val endSec: IntArray,
    val nameId: IntArray,
    /**
     * Owner of the span in Android's encoding — `u0a138` is 10138 — or [NO_UID].
     *
     * Kept because the tag alone identifies nobody. Half of them read
     * `*job*r/#RestProxyWorker#@androidx.work.systemjobscheduler@com.google...`, and one
     * of the longest holders on this device took its lock under an empty string. The uid
     * is what turns those into an app name.
     */
    val uid: IntArray,
) {
    val size: Int get() = startSec.size

    companion object {
        /** The history named no owner, or named one that is not an app. */
        const val NO_UID = -1
    }
}

/**
 * Everything a window query needs, and nothing a dump touched.
 *
 * Built in one streaming pass and held only while its answers are on screen — see
 * `DiagnoseViewModel`, which drops it when the tab is left. Nothing in here schedules,
 * observes or subscribes to anything.
 */
class DiagnosisIndex(
    /** Wall clock of second zero. */
    val epochMs: Long,
    /** Last second the buffer covers. */
    val endSec: Int,
    private val names: Array<String>,
    private val spans: Map<EventKind, PackedSpans>,
    /** Alternating start,end seconds for each state the device was in. */
    private val flags: Map<SystemFlag, IntArray>,
    /** Coulomb counter, in mAh remaining, at [chargeSec]. */
    val chargeSec: IntArray,
    val chargeMah: IntArray,
    /** Battery percentage at [levelSec], one entry per change. */
    val levelSec: IntArray,
    val levelPercent: IntArray,
    /**
     * Alternating start,end seconds of every stretch the phone spent on a charger.
     *
     * Not a [SystemFlag]: it is not a drain and has no business in the list of states a
     * window can be blamed on. It is here because a window that contains one is a window
     * whose figures mean something different — a level range must never be answered by a
     * stretch that includes a charge, and a clock range that happens to include one has
     * to say so.
     */
    val chargingSpans: IntArray = IntArray(0),
) {
    fun nameOf(id: Int): String = names.getOrElse(id) { "" }

    fun spansOf(kind: EventKind): PackedSpans? = spans[kind]

    fun flagOf(flag: SystemFlag): IntArray = flags[flag] ?: EMPTY

    /** True when there is enough here to answer anything at all. */
    val isUsable: Boolean get() = levelSec.size >= 2 && endSec > 0

    fun secondsToMs(sec: Int): Long = epochMs + sec * 1000L

    fun msToSeconds(ms: Long): Int = ((ms - epochMs) / 1000L).toInt()

    private companion object {
        val EMPTY = IntArray(0)
    }
}
