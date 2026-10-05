package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Account-capable Mac mutation call sites explicitly omit an older narrowing ticket. */
internal enum class MobileAttachTicketPolicy { WHEN_COVERED, OMIT }

/** Supplemental selection context, never account or route authorization. No bearer in toString. */
internal class MobileAttachTicketContext(
    val workspaceId: String,
    val terminalId: String?,
    private val authToken: String?,
    val expiresAtMillis: Long?
) {
    init {
        require(workspaceId.length <= 1024 && (terminalId?.length ?: 0) <= 1024) { "Invalid attach-ticket selection" }
        require(authToken == null || (authToken.isNotBlank() && authToken.length <= 4096)) { "Invalid attach-ticket token" }
    }

    fun isExpired(nowMillis: Long) = expiresAtMillis?.let { it <= nowMillis } == true
    fun macMutationTicket(): NativeMacMutationTicket? =
        if (authToken != null && workspaceId.isBlank()) NativeMacMutationTicket(expiresAtMillis) else null

    /** Only for the enclosing Keystore-encrypted credential transaction. Never a public locator. */
    internal fun credentialJson(): JSONObject = JSONObject().put("version", 1).put("workspace", workspaceId)
        .put("terminal", terminalId ?: JSONObject.NULL).put("token", authToken ?: JSONObject.NULL)
        .put("expires", expiresAtMillis ?: JSONObject.NULL)

    companion object {
        internal fun fromCredentialJson(value: JSONObject): MobileAttachTicketContext {
            val fields = PairingTicketJson.decode(value.toString())
            require(fields.keys == setOf("version", "workspace", "terminal", "token", "expires")) { "Invalid ticket state" }
            require((fields["version"] as? java.math.BigDecimal)?.longValueExact() == 1L)
            fun string(key: String): String? = fields[key]?.let { it as? String ?: error("Invalid ticket state") }
            val expiry = fields["expires"]?.let {
                (it as? java.math.BigDecimal ?: error("Invalid ticket state")).longValueExact()
            }
            return MobileAttachTicketContext(checkNotNull(string("workspace")), string("terminal"), string("token"), expiry)
        }
    }

    fun allowsMacWorkspaceMutations(hostAuthorizesByAccount: Boolean, nowMillis: Long): Boolean =
        hostAuthorizesByAccount || (authToken != null && !isExpired(nowMillis) && workspaceId.isBlank())

    /** Matches upstream MobileCoreRPCClient's whenCovered policy, including its alias rules. */
    fun tokenFor(method: String, params: JSONObject, nowMillis: Long): String? {
        if (authToken == null || isExpired(nowMillis)) return null
        val workspace = selection(params, listOf("workspace_id"))
        val terminal = selection(params, listOf("surface_id", "terminal_id", "tab_id"))
        if (workspace.conflict || terminal.conflict || params.has("workspaceID") || params.has("terminalID")) return null
        val covered = when (method.trim()) {
            "mobile.workspace.list", "workspace.list", "mobile.task.models.list",
            "mobile.directory.list", "mobile.directory.search", "workspace.create", "mobile.task.attachment.upload",
            "workspace.move", "workspace.group.action", "workspace.group.create",
            "mobile.terminal.create", "terminal.create", "mobile.events.subscribe", "mobile.events.unsubscribe",
            "mobile.events.probe" -> true
            "workspace.action", "workspace.close", "mobile.surface.focus", "mobile.panel.artifact.stat",
            "mobile.panel.artifact.fetch", "mobile.panel.artifact.thumbnail", "mobile.browser.list", "mobile.browser.create" ->
                coversWorkspace(workspace.value)
            "mobile.terminal.input", "terminal.input", "mobile.terminal.paste", "terminal.paste",
            "mobile.terminal.paste_image", "terminal.paste_image", "mobile.terminal.replay", "terminal.replay",
            "mobile.terminal.viewport", "terminal.viewport", "mobile.terminal.reattach", "mobile.terminal.size_policy.set",
            "mobile.terminal.participant.disconnect", "mobile.terminal.artifact.scan", "mobile.terminal.artifact.stat",
            "mobile.terminal.artifact.fetch", "mobile.terminal.artifact.thumbnail" -> coversTerminal(workspace.value, terminal.value)
            "mobile.browser.stream.start", "mobile.browser.stream.stop", "mobile.browser.viewport", "mobile.browser.frame.ack",
            "mobile.browser.dialog.respond", "mobile.browser.input.pointer", "mobile.browser.input.scroll",
            "mobile.browser.input.key", "mobile.browser.input.text", "mobile.browser.navigate", "mobile.browser.back",
            "mobile.browser.forward", "mobile.browser.reload" -> workspaceId.isBlank()
            // Host status, both account-wide feeds and unknown/new verbs do not carry a selection token.
            else -> false
        }
        return authToken.trim().takeIf { covered }
    }

    private fun coversWorkspace(workspace: String?): Boolean = workspaceId.trim().let { it.isEmpty() || it == workspace }

    private fun coversTerminal(workspace: String?, terminal: String?): Boolean {
        val ticketWorkspace = workspaceId.trim()
        if (ticketWorkspace.isEmpty()) return true
        if (workspace != null && workspace != ticketWorkspace) return false
        val ticketTerminal = terminalId?.trim()?.takeIf { it.isNotEmpty() }
        return if (ticketTerminal != null) terminal == ticketTerminal else workspace == ticketWorkspace
    }

    private class Selection(val value: String?, val conflict: Boolean)
    private fun selection(params: JSONObject, keys: List<String>): Selection {
        var selected: String? = null
        for (key in keys) {
            val value = (params.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (selected != null && selected != value) return Selection(selected, true)
            selected = value
        }
        return Selection(selected, false)
    }

    override fun toString() = "MobileAttachTicketContext(redacted)"
}
