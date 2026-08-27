package com.syed.wattson.ui.diagnose

import android.content.Context
import com.syed.wattson.data.model.PackedSpans

/**
 * Turns what the history calls things into what a person calls them.
 *
 * The history names work the way the framework does —
 * `*job*r/#RestProxyWorker#@androidx.work.systemjobscheduler@com.truecaller/...`, and on
 * one of the longest holders on this device, the empty string. Behind each is a uid, and
 * a uid can be turned into an app three ways, tried in order of how much they say:
 *
 *  1. the platform's own names for the uids below the first app,
 *  2. the shell's view of the package manager, which package visibility does not filter,
 *  3. `PackageManager` directly, for whatever this app is allowed to see.
 *
 * Failing all three the tag is cut down to the part that identifies it, which is better
 * than nothing and much better than the whole thing.
 */
class AppLabels(context: Context) {

    private val packages = context.applicationContext.packageManager
    private val cache = HashMap<Int, String?>(64)

    /** The best name for this slice, given everything known about it. */
    fun nameFor(uid: Int, tag: String): String {
        SPECIAL[uid]?.let { return it }
        fromPackageManager(uid)?.let { return it }
        // The tag usually carries the package that the package manager would not hand
        // over: "*job*r/#ping_flush_one_off#@androidx.work.systemjobscheduler@
        // com.google.android.youtube/..." names its owner twice over.
        packageIn(tag)?.let { return prettify(it) }
        if (uid != PackedSpans.NO_UID) return "uid $uid"
        return shortenTag(tag).ifBlank { "Unattributed" }
    }

    /**
     * The tag, shortened, when it says something the name does not.
     *
     * A package that resolved to its own name only repeats itself, and a row that says
     * the same thing twice is a row that has wasted a line.
     */
    fun reasonFor(uid: Int, tag: String): String? {
        val name = nameFor(uid, tag)
        val short = shortenTag(tag)
        return short.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
    }

    private fun fromPackageManager(uid: Int): String? {
        if (uid == PackedSpans.NO_UID) return null
        return cache.getOrPut(uid) {
            runCatching {
                val first = packages.getPackagesForUid(uid)?.firstOrNull() ?: return@runCatching null
                prettify(first)
            }.getOrNull()
        }
    }

    /**
     * The package name inside a framework tag, if there is one.
     *
     * Several usually are, and the interesting one is never the framework's own:
     * `androidx.work.systemjobscheduler` and
     * `androidx.work.impl.background.systemjob.SystemJobService` are the machinery a job
     * ran on, not who ran it. What is left after those are dropped is the app.
     */
    private fun packageIn(tag: String): String? {
        val candidates = PACKAGE_SHAPED.findAll(tag)
            .map { it.value.trimEnd('.') }
            .filter { candidate -> FRAMEWORK_PREFIXES.none(candidate::startsWith) }
            .filter { it.count { char -> char == '.' } >= 1 }
            .toList()
        if (candidates.isEmpty()) return null
        // The owner tends to be named more than once; ties go to the first mention.
        return candidates.groupingBy { it }.eachCount().entries
            .maxByOrNull { (name, count) -> count * 1_000 - candidates.indexOf(name) }
            ?.key
    }

    /** A package name, given whatever label the package manager will part with. */
    private fun prettify(packageName: String): String {
        val label = runCatching {
            packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
        return label?.takeIf { it.isNotBlank() } ?: packageName
    }

    /**
     * The framework's name for a piece of work, cut to the part that identifies it.
     *
     * `*job*r/#RestProxyWorker#@androidx.work.systemjobscheduler@com.truecaller/androidx
     * .work.impl.background.systemjob.SystemJobService` is a job called RestProxyWorker,
     * and all the rest of it is scaffolding.
     */
    private fun shortenTag(tag: String): String {
        if (tag.isBlank()) return ""
        val hashed = tag.substringAfter('#', "").substringBefore('#')
        if (hashed.isNotBlank()) return hashed
        val slashed = tag.substringAfterLast('/')
        if (slashed.isNotBlank() && slashed != tag) return slashed.take(TAG_LIMIT)
        return tag.trim('*').take(TAG_LIMIT)
    }

    private companion object {
        const val TAG_LIMIT = 40
        const val FIRST_APP_UID = 10_000

        /**
         * Uids below the first app are the platform. Asking the package manager for them
         * gives back `android`, or a list of thirty system packages sharing the uid.
         */
        val PACKAGE_SHAPED = Regex("[a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+")

        /** Machinery a piece of work ran on, never the app that ran it. */
        val FRAMEWORK_PREFIXES = listOf(
            "androidx.", "android.", "com.android.internal.", "java.", "javax.", "kotlin.",
        )

        val SPECIAL = mapOf(
            0 to "Kernel",
            1000 to "Android system",
            1001 to "Phone / radio",
            1013 to "Media server",
            1021 to "GPS",
            1027 to "NFC",
            1041 to "Audio server",
            2000 to "Shell",
        )
    }
}
