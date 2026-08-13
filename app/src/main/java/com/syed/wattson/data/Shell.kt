package com.syed.wattson.data

import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * Minimal shell helper. Every call is one-shot and synchronous: a process is spawned,
 * read to completion, and reaped. Nothing is cached, scheduled, or left running.
 */
object Shell {

    /** How long to wait for the stderr drain thread after the process exits. */
    private const val ERROR_DRAIN_TIMEOUT_MS = 1_000L

    data class Result(val ok: Boolean, val out: String, val error: String)

    /** Runs [command] through `su`, feeding it on stdin (compatible with Magisk and KernelSU). */
    fun runAsRoot(command: String, timeoutSeconds: Long = 30): Result = runWith("su", command, timeoutSeconds)

    /** Runs [command] through the plain app shell (no elevation). */
    fun runPlain(command: String, timeoutSeconds: Long = 30): Result = runWith("sh", command, timeoutSeconds)

    private fun runWith(shell: String, command: String, timeoutSeconds: Long): Result {
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
            val errorBuffer = StringBuilder()
            val errorThread = Thread {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { errorBuffer.appendLine(it) }
                }
            }.apply { isDaemon = true; start() }

            val output = process.inputStream.bufferedReader().use(BufferedReader::readText)

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
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


    /** True when a `su` binary exists and actually grants uid 0. */
    fun hasRoot(): Boolean {
        val result = runAsRoot("id -u", timeoutSeconds = 15)
        return result.ok && result.out.trim().lineSequence().lastOrNull()?.trim() == "0"
    }
}
