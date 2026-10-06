package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One per machine for the account lifetime: all views share its native slot. */
internal class CloudTerminalHost(parent: CoroutineScope, val machineId: String,
    private val current: () -> Boolean, connect: suspend () -> CloudTerminalLink,
    private val drafts: SshComposerPool = SshComposerPool()) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow<CloudRenderedTerminal?>(null)
    val selected = mutable.asStateFlow()
    private var closed = false
    private var foreground = false
    private val attachment = CloudTerminalAttachment(scope, { !closed && current() }, connect, { terminal, event ->
        mutable.value?.takeIf { it.remoteId == terminal }?.receive(event)
    })
    init { scope.launch { attachment.state.collect { state ->
        mutable.value?.takeIf { it.remoteId == state.terminalId }?.connection(state)
    } } }
    fun select(workspace: NativeWorkspace, terminal: NativeTerminal): CloudRenderedTerminal {
        check(!closed && current()) { "Cloud account changed" }
        val address = checkNotNull(CloudAddress.parse(terminal.id))
        check(address.machineId == machineId && address.component != null && workspace.terminals.any { it.id == terminal.id })
        mutable.value?.takeIf { it.id == terminal.id && it.composer.isActive() }?.let { it.update(terminal); return it }
        val next = CloudRenderedTerminal(terminal, checkNotNull(address.component), drafts.open(terminal.id), attachment) {
            !closed && foreground && current() && mutable.value?.id == terminal.id
        }
        val old = mutable.value
        mutable.value = next
        attachment.select(address.component)
        next.connection(attachment.state.value)
        old?.close()
        return next
    }
    fun reconcile(snapshot: CloudWorkspaceSnapshot?, inForeground: Boolean, tunnelReady: Boolean) {
        if (closed) return
        foreground = inForeground
        val selected = mutable.value
        if (selected != null && snapshot != null) {
            val terminal = snapshot.rows.flatMap { it.workspace.terminals }.singleOrNull { it.id == selected.id }
            if (terminal != null) selected.update(terminal)
            else if (snapshot.authoritative) {
                attachment.select(null)
                selected.unavailable(if (snapshot.machine.lifecycle == CloudMachineLifecycle.RUNNING)
                    "This Cloud terminal no longer exists." else "This Cloud machine is not running.")
            }
            if (snapshot.authoritative && snapshot.machine.lifecycle == CloudMachineLifecycle.RUNNING)
                drafts.discardWhere { id -> snapshot.catalog.terminals.none { CloudAddress(machineId, it.id).identifier == id } }
        }
        attachment.setAvailable(inForeground && tunnelReady && snapshot?.machine?.lifecycle == CloudMachineLifecycle.RUNNING &&
            snapshot.authoritative && snapshot.availability == NativeFeedAvailability.CONNECTED)
        // Pausing keeps the view; a resumed authoritative catalog can restore it.
        if (selected != null && snapshot?.authoritative == true && snapshot.catalog.terminals.any { it.id == selected.remoteId } &&
            attachment.state.value.terminalId == null) attachment.select(selected.remoteId)
    }
    fun replay() = attachment.replay()
    fun leave() { attachment.select(null); mutable.value?.close(); mutable.value = null }
    override fun close() {
        if (closed) return
        closed = true; attachment.close(); mutable.value?.close(); mutable.value = null
        drafts.close(); job.cancel()
    }
}

/** Supplies Cloud bytes to the same Ghostty surface, keyboard, toolbar and composer as other remote terminals. */
internal class CloudRenderedTerminal(private var terminal: NativeTerminal, val remoteId: String,
    override val composer: SshComposerPool.Draft, private val attachment: CloudTerminalAttachment,
    private val current: () -> Boolean) : SshTerminal {
    override val id get() = terminal.id
    override val title get() = terminal.title
    override val transportLabel = "Cloud"
    override val acceptsInputWhileOpening = true
    override val display = GhosttyVtTerminal(80, 24)
    override val bells = TerminalBellSignal()
    private val reducer = CloudTerminalOutputReducer()
    private val mutable = MutableStateFlow(SshShellState())
    override val state = mutable.asStateFlow()
    private var closed = false
    private var cellWidth = 1
    private var cellHeight = 1
    private fun changed() { mutable.value = mutable.value.copy(revision = mutable.value.revision + 1) }
    fun update(value: NativeTerminal) { if (!closed && value != terminal) { terminal = value; changed() } }
    fun connection(value: CloudAttachmentState) {
        if (closed) return
        val phase = when (value.phase) {
            CloudAttachmentPhase.READY -> SshShellPhase.RUNNING
            CloudAttachmentPhase.FAILED, CloudAttachmentPhase.EXITED, CloudAttachmentPhase.CLOSED -> SshShellPhase.ENDED
            else -> SshShellPhase.OPENING
        }
        mutable.value = mutable.value.copy(phase = phase, error = value.failure, revision = mutable.value.revision + 1)
    }
    fun unavailable(message: String) { if (!closed) mutable.value = mutable.value.copy(phase = SshShellPhase.ENDED, error = message) }
    fun receive(event: CloudTerminalOutput) {
        if (closed) return
        for (action in reducer.reduce(event)) when (action) {
            is CloudTerminalOutputReducer.Action.Grid -> display.resize(action.columns, action.rows, cellWidth, cellHeight)
            is CloudTerminalOutputReducer.Action.Write -> display.append(action.bytes)
            CloudTerminalOutputReducer.Action.Exited -> display.append("\r\n[process exited]\r\n".toByteArray())
        }
        val bell = display.takeBell()
        if (event.kind == 2 && bell) bells.ring()
        changed()
    }
    override fun send(text: String, paste: Boolean) = sendBytes((if (paste)
        TerminalKeyEncoding.paste(text, display.bracketedPaste) else text).toByteArray(Charsets.UTF_8))
    override fun sendBytes(bytes: ByteArray) = !closed && current() && attachment.state.value.terminalId == remoteId && attachment.send(bytes)
    override suspend fun submitText(text: String): Boolean = !closed && current() &&
        attachment.state.value.terminalId == remoteId && attachment.sendAndAwait(text.toByteArray(Charsets.UTF_8))
    override fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics) {
        if (closed) return
        cellWidth = cells.widthPx.toInt().coerceIn(1, 4096); cellHeight = cells.heightPx.toInt().coerceIn(1, 4096)
        display.setCellMetrics(cells); changed()
        if (attachment.state.value.terminalId == remoteId) attachment.resize(columns.coerceIn(1, 1000), rows.coerceIn(1, 1000))
    }
    override fun visible(visible: Boolean) { if (!closed && visible && current()) attachment.replay() }
    override suspend fun currentDirectory() = terminal.directory
    override fun close() { if (!closed) { closed = true; display.close() } }
}

internal data class CloudWorkspaceRoute(val host: CloudTerminalHost, val workspaceId: String, val catalogOwner: CloudWorkspaceController)
