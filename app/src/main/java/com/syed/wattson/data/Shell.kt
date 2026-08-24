package com.syed.wattson.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Minimal shell helper. Every call is one-shot and synchronous: a process is spawned,
 * read to completion, and reaped. Nothing is cached, scheduled, or left running.
 */
object Shell {

    /** How long to wait for the stderr drain thread after the process exits. */
    private const val ERROR_DRAIN_TIMEOUT_MS = 1_000L

    /**
     * Read buffer for the streaming path. The battery history dump is over 20 MB, and the
     * consumer has to keep up with it — see [streamAsRoot].
     */
    private const val STREAM_BUFFER_BYTES = 1 shl 16

    /** How often the streaming read checks its deadline, in lines. */
    private const val DEADLINE_CHECK_INTERVAL = 4_096L

    /** Ceiling on retained stderr. Only the first line of it is ever displayed. */
    private const val ERROR_LIMIT_CHARS = 8 shl 10

    data class Result(val ok: Boolean, val out: String, val error: String)

    /** Runs [command] through `su`, feeding it on stdin (compatible with Magisk and KernelSU). */
    fun runAsRoot(command: String, timeoutSeconds: Long = 30): Result = runWith("su", command, timeoutSeconds)

    /** Runs [command] through the plain app shell (no elevation). */
    fun runPlain(command: String, timeoutSeconds: Long = 30): Result = runWith("sh", command, timeoutSeconds)

    /**
     * Runs [command] through `su` and hands every output line to [onLine] as it arrives,
     * retaining none of it. [Result.out] is always empty.
     *
     * This exists for `dumpsys`, which enforces a timeout on the *service's* dump call and
     * kills it mid-stream when it expires. A dump that writes into a pipe is only as fast
     * as whatever is reading the other end, so a slow consumer makes dumpsys guillotine
     * the output — silently, since the truncation marker goes to stdout and the exit
     * status stays zero. Reading it straight into the JVM drains the pipe orders of
     * magnitude faster than an on-device `awk` filter can.
     */
    fun streamAsRoot(command: String, timeoutSeconds: Long = 30, onLine: (String) -> Unit): Result =
        runWith("su", command, timeoutSeconds, onLine)

    /** [streamAsRoot] without elevation. */
    fun streamPlain(command: String, timeoutSeconds: Long = 30, onLine: (String) -> Unit): Result =
        runWith("sh", command, timeoutSeconds, onLine)

    private fun runWith(
        shell: String,
        command: String,
        timeoutSeconds: Long,
        onLine: ((String) -> Unit)? = null,
    ): Result {
        var process: Process? = null
        return try {
            process = ProcessBuilder(shell).redirectErrorStream(false).start()

            // Feed the command, then exit so the process terminates on its own.
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(command)
                writer.write("\nexit\n")
                writer.flush()
            }

            // Drain stderr on a side thread so a chatty command cannot deadlock on a full pipe.
            // StringBuffer, not StringBuilder: the join below is bounded, so on a slow
            // drain this is read while that thread is still appending to it. Capped, because
            // nothing downstream shows more than the first line of it and a command that
            // fails per input line — an SELinux denial for every one of 350 000 records —
            // would otherwise grow this without limit.
            val errorBuffer = StringBuffer()
            val errorThread = Thread {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine {
                        if (errorBuffer.length < ERROR_LIMIT_CHARS) errorBuffer.appendLine(it)
                    }
                }
            }.apply { isDaemon = true; start() }

            var readTimedOut = false
            val output = if (onLine == null) {
                process.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                readTimedOut = drain(process.inputStream, timeoutSeconds, onLine)
                ""
            }

            val finished = !readTimedOut && process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                return Result(false, output, "Timed out after ${timeoutSeconds}s")
            }
            errorThread.join(ERROR_DRAIN_TIMEOUT_MS)

            Result(process.exitValue() == 0, output, errorBuffer.toString().trim())
        } catch (t: Throwable) {
            Result(false, "", t.message ?: t.javaClass.simpleName)
        } finally {
            // Always reap: a half-started or timed-out process would otherwise linger
            // holding its pipes, and these run every few seconds while the app is open.
            process?.destroyForcibly()
        }
    }


    /**
     * Reads [source] to EOF a line at a time, handing each to [onLine] and keeping none.
     * Returns true if it gave up on [timeoutSeconds] first.
     *
     * The deadline is what stops a stuck child from pinning this thread for the life of the
     * app: the read below blocks until the far end closes, so the caller's `waitFor` timeout
     * is never even reached while it is waiting. It is checked every
     * [DEADLINE_CHECK_INTERVAL] lines rather than every line — over a dump this size the
     * clock read is otherwise a measurable part of the work.
     */
    private fun drain(
        source: java.io.InputStream,
        timeoutSeconds: Long,
        onLine: (String) -> Unit,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        BufferedReader(InputStreamReader(source), STREAM_BUFFER_BYTES).use { reader ->
            var lines = 0L
            while (true) {
                val line = reader.readLine() ?: return false
                onLine(line)
                if (++lines % DEADLINE_CHECK_INTERVAL == 0L && System.nanoTime() > deadline) {
                    return true
                }
            }
        }
    }

    /** True when a `su` binary exists and actually grants uid 0. */
    fun hasRoot(): Boolean {
        val result = runAsRoot("id -u", timeoutSeconds = 15)
        return result.ok && result.out.trim().lineSequence().lastOrNull()?.trim() == "0"
    }
}
