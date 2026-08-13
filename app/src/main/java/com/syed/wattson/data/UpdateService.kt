package com.syed.wattson.data

import android.content.Context
import com.syed.wattson.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What a release check found. */
data class UpdateInfo(
    val available: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val downloadUrl: String?,
    val assetName: String?,
    val notes: String?,
)

/**
 * Checks GitHub releases for a newer APK and installs it.
 *
 * Uses the releases **atom feed** rather than api.github.com: the REST API allows only
 * 60 unauthenticated requests per hour per IP, which a carrier-NAT connection burns
 * through collectively. github.com itself imposes no such limit.
 */
class UpdateService(private val context: Context) {

    suspend fun checkForUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        val feed = fetch(RELEASES_FEED)
        val entry = feed.split("<entry>").getOrNull(1)
            ?: throw IllegalStateException("No releases published yet")

        val tag = TAG_PATTERN.find(entry)?.groupValues?.get(1)?.let(::decodeEntities)
            ?: throw IllegalStateException("Could not read the latest version")

        val latest = tag.removePrefix("v")
        val current = BuildConfig.VERSION_NAME
        val assetName = "$ASSET_PREFIX-$tag.apk"

        UpdateInfo(
            available = compareVersions(latest, current) > 0,
            latestVersion = latest,
            currentVersion = current,
            downloadUrl = "$RELEASE_DOWNLOAD_BASE/$tag/$assetName",
            assetName = assetName,
            notes = NOTES_PATTERN.find(entry)?.groupValues?.get(1)?.let(::htmlToPlainText),
        )
    }

    /** Downloads the APK into cache and returns the file, reporting 0f..1f progress. */
    suspend fun download(
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val url = info.downloadUrl ?: throw IllegalStateException("No download URL")
        val target = File(context.cacheDir, info.assetName ?: "wattson-update.apk")

        // Any earlier attempt is stale the moment a new one starts.
        target.delete()

        openConnection(url).run {
            val total = contentLengthLong.takeIf { it > 0 }
            inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        total?.let { onProgress((copied.toFloat() / it).coerceIn(0f, 1f)) }
                    }
                }
            }
            disconnect()
        }
        target
    }

    /** Removes any APKs left behind by a previous update attempt. */
    fun clearStaleDownloads() {
        runCatching {
            context.cacheDir.listFiles()
                ?.filter { it.name.endsWith(".apk") }
                ?.forEach { it.delete() }
        }
    }

    /** Semantic-ish compare; missing components count as zero. */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split(".").map { it.toIntOrNull() ?: 0 }
        val right = b.split(".").map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(left.size, right.size)) { index ->
            val diff = (left.getOrNull(index) ?: 0) - (right.getOrNull(index) ?: 0)
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        return 0
    }

    private fun fetch(url: String): String =
        openConnection(url).run {
            try {
                inputStream.bufferedReader().readText()
            } finally {
                disconnect()
            }
        }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/atom+xml")
            setRequestProperty("User-Agent", "Wattson")
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                throw IllegalStateException("GitHub returned HTTP $code")
            }
        }

    /** Undo one round of XML escaping. `&amp;` must come last or it double-decodes. */
    private fun decodeEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun htmlToPlainText(html: String): String = decodeEntities(html)
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n• ")
        .replace(Regex("</(p|h[1-6]|li|ul|ol|div|tr)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    companion object {
        const val REPO = "s-shahriar/Wattson"

        /** Release assets must be named `Wattson-vX.Y.Z.apk` for this to resolve. */
        const val ASSET_PREFIX = "Wattson"

        private const val RELEASES_FEED = "https://github.com/$REPO/releases.atom"
        private const val RELEASE_DOWNLOAD_BASE = "https://github.com/$REPO/releases/download"
        private const val TIMEOUT_MS = 15_000
        private const val DOWNLOAD_BUFFER = 16 * 1024

        private val TAG_PATTERN = Regex("""href="[^"]*/releases/tag/([^"]+)"""")
        private val NOTES_PATTERN = Regex("""<content[^>]*>([\s\S]*?)</content>""")
    }
}
