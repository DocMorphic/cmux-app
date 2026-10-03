package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Official MobileRPCControlFrameResendPolicy at 4c5272e9; new methods default to uncertain. */
internal object MobileControlResendPolicy {
    const val PROBE = "mobile.events.probe"
    private val reads = setOf("caffeine.status", "mobile.browser.list", "mobile.chat.history", "mobile.chat.sessions",
        "mobile.directory.list", "mobile.directory.search", PROBE, "mobile.host.status", "mobile.panel.artifact.stat",
        "mobile.rpc.methods", "mobile.simulator.devices.list", "mobile.simulator.list", "mobile.sync.fetch",
        "mobile.task.models.list", "mobile.terminal.artifact.list", "mobile.terminal.artifact.stat",
        "mobile.workspace.changes.file_diff", "mobile.workspace.changes.file_stat", "mobile.workspace.changes.files",
        "mobile.workspace.changes.summary", "mobile.workspace.list", "notification.feed.list", "phone_push.status.get", "workspace.list")
    fun allows(method: String, params: JSONObject): Boolean = method in reads ||
        (method in setOf("mobile.terminal.replay", "terminal.replay") &&
            listOf("client_id", "viewport_columns", "viewport_rows").none(params::has))
}
