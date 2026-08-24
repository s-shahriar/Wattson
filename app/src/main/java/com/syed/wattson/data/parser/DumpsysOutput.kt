package com.syed.wattson.data.parser

/**
 * Recognises dumpsys' own "I gave up" marker.
 *
 * `dumpsys` puts a timeout on every service dump — ten seconds unless `-t` says
 * otherwise — and when it expires it stops reading the service, prints
 *
 * ```
 * *** SERVICE 'batterystats' DUMP TIMEOUT (10000ms) EXPIRED ***
 * ```
 *
 * and exits **zero**. So a truncated dump looks exactly like a complete one: partial
 * text, no error, no failure status. This marker is the only evidence, and everything
 * that reads a dump has to check for it rather than trust its own parse.
 */
internal object DumpsysOutput {

    private const val MARKER_PREFIX = "***"
    private const val MARKER_BODY = "DUMP TIMEOUT"

    /**
     * True when [line] is the marker itself.
     *
     * Both halves are tested because service and wakelock names reach the dump verbatim —
     * this device logs a `*walarm*:DhcpClient.wlan0.TIMEOUT` wakeup alarm, which a search
     * for "TIMEOUT" alone flags on every single dump.
     */
    fun isTruncationMarker(line: String): Boolean =
        line.startsWith(MARKER_PREFIX) && line.contains(MARKER_BODY)

    /**
     * True when [dump] was cut short by that timeout.
     *
     * Gated on a plain substring scan, which allocates nothing: splitting a dump of this
     * size into lines to prove the absence of a marker is work almost every dump does not
     * need to pay for.
     */
    fun isTruncated(dump: String): Boolean =
        dump.contains(MARKER_BODY) && dump.lineSequence().any(::isTruncationMarker)
}
