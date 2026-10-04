package io.github.docmorphic.cmuxapp

import java.time.Instant

/** Explicit clipboard snapshot. No credential store, logs, host names or terminal content enter this type. */
internal data class NativeSupportInformation(
    val accountId: String? = null, val teamId: String? = null,
    val packageName: String, val channel: String, val version: String, val build: String,
    val osVersion: String, val model: String,
    val connectionState: String?, val transport: String?, val sourceRevision: String? = null,
    val reportedAt: Instant = Instant.now()
) {
    fun report(): String = buildString {
        appendLine("cmux Android (unofficial companion)")
        listOf("Account ID" to accountId, "Install ID" to null, "Device ID" to null, "Team ID" to teamId,
            "Bundle ID" to packageName, "App Channel" to channel, "App Version" to version, "Build Number" to build,
            "Android Version" to osVersion, "Device Model" to model, "Connection State" to connectionState,
            "Transport" to transport, "Source Revision" to sourceRevision).forEach { (label, value) ->
            append(label).append(": ").appendLine(value?.filterNot(Char::isISOControl)?.trim()?.take(512)
                ?.takeIf(String::isNotEmpty) ?: "<unavailable>")
        }
        append("Reported At (UTC): ").append(reportedAt)
    }
}

internal data class NativeSupportSession(val accountId: String? = null, val teamId: String? = null,
    val connected: Boolean = false, val transport: String? = null)
