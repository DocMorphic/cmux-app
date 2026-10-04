package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale

internal sealed interface NativeSidebarTarget {
    data class Workspace(val mac: NativeCredentialStore.PairedMac, val id: String) : NativeSidebarTarget
    data class Ssh(val row: SshFeedRow) : NativeSidebarTarget
    data class Notification(val entry: NativeFeedEntry) : NativeSidebarTarget
}
internal data class NativeSidebarInput(val sources: List<NativeFeedSource>, val ssh: List<SshFeedRow>,
    val computers: List<NativeSortComputer>, val sort: NativeWorkspaceSortState,
    val sshAvailability: Map<java.util.UUID, NativeFeedAvailability> = emptyMap(),
    val appearances: NativeMacAppearances = NativeMacAppearances(), val locale: Locale = Locale.getDefault())
internal data class NativeSidebarPresentation(val computer: String? = null, val notifications: Boolean = false,
    val text: String = "", val unread: Boolean = false)

/** Uses the same ordering, search, group and notification projection policies as the main screen. */
internal class NativeRoutedSidebarHost(override val owner: Any, private val salt: String,
    private val input: () -> NativeSidebarInput?, private val retainFeed: () -> RoutedSidebarLease,
    private val navigate: (NativeSidebarTarget) -> Unit,
    private val initial: () -> NativeSidebarPresentation = { NativeSidebarPresentation() },
    private val adoptPresentation: (NativeSidebarPresentation) -> Unit = {}) : RoutedSidebarHost {
    private fun id(vararg values: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(JSONArray(listOf(salt) + values).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun computer(key: String) = id("computer", key)
    private fun workspace(mac: NativeCredentialStore.PairedMac, key: String) = id("workspace", mac.origin, mac.code, key)
    private fun group(source: NativeFeedSource, key: String) = id("group", source.mac.origin, source.mac.code, key)
    private fun ssh(row: SshFeedRow) = id("ssh", row.key, row.generation, row.registry, row.host.endpoint,
        row.host.keyId, row.host.jumpHostId)
    private fun notification(entry: NativeFeedEntry) = id("notification", entry.source.mac.origin, entry.source.mac.code,
        entry.notification.id, entry.notification.workspaceId, entry.notification.surfaceId)
    override fun retain() = retainFeed()
    override fun current() = input() != null
    override fun initialQuery() = initial().let { RoutedSidebarQuery(it.notifications, it.text, it.computer?.let(::computer), it.unread) }
    override fun adopt(query: RoutedSidebarQuery) {
        val value = input() ?: return
        val selection = query.computer?.let { key -> value.computers.singleOrNull { computer(it.id) == key }?.id }
        if (query.computer != null && selection == null) return
        adoptPresentation(NativeSidebarPresentation(selection, query.notifications, NativeSearchText.boundQuery(query.text), query.unread))
    }

    override fun read(query: RoutedSidebarQuery): RoutedSidebarSnapshot? {
        val value = input() ?: return null
        val ordered = orderWorkspaceComputers(value.computers, value.sort, value.locale)
        val selected = query.computer?.let { key -> ordered.singleOrNull { computer(it.id) == key }?.id }
        // A disappeared scoped computer must not silently broaden a filtered list.
        val validScope = query.computer == null || selected != null
        val sources = value.sources.filter { validScope && (selected == null || workspaceMacFilterId(it.mac.deviceId, it.mac.instanceTag) == selected) }
        val sshRows = value.ssh.filter { validScope && (selected == null || workspaceSshFilterId(it.host.id) == selected) }
        val entries = aggregateNativeFeed(sources, computerName = value.appearances::name)
        val unread = entries.count { !it.notification.isRead }
        val rows = if (query.notifications) notifications(entries, query, value.locale) else workspaces(value, sources, sshRows, query, selected == null)
        val offline = sources.filter { it.availability == NativeFeedAvailability.OFFLINE }
        return RoutedSidebarSnapshot(ordered.map { RoutedSidebarComputer(computer(it.id), it.name, it.buildLabel) }, rows, unread,
            sources.any { it.availability == NativeFeedAvailability.CONNECTING },
            when {
                !validScope -> "This computer is no longer available. Choose another computer."
                offline.isNotEmpty() -> "Unavailable: ${offline.joinToString { value.appearances.name(it.mac) }}. Showing the last received updates."
                else -> null
            })
    }
    private fun workspaces(value: NativeSidebarInput, sources: List<NativeFeedSource>, sshRows: List<SshFeedRow>,
        query: RoutedSidebarQuery, all: Boolean): List<RoutedSidebarRow> {
        val matches = NativeSearchIndex(workspaceSearchRows(sources, value.appearances::name), value.locale).matches(query.text)
        val sshMatches = NativeSearchIndex(sshRows.map { it.key to it.searchFields() }, value.locale).matches(query.text)
        val collapsed = sources.flatMap { source -> source.groups.map { item ->
            WorkspaceListEntry.Header(source, item).key to (query.groupExpansion[group(source, item.id)]?.not() ?: item.isCollapsed)
        } }.toMap()
        return sortedWorkspaceRows(sources, sshRows.filter { it.key in sshMatches && (!query.unread || it.workspace.hasUnread) },
            value.computers, value.sort, all, matches, query.text.isNotBlank(), query.unread, collapsed, value.locale).map { row ->
            when (row) {
                is NativeWorkspaceDisplayRow.Ssh -> displayWorkspace(ssh(row.row), row.row.workspace, row.row.host.name,
                    (value.sshAvailability[row.row.host.id] ?: NativeFeedAvailability.OFFLINE), 0, row.row.openTarget() != null)
                is NativeWorkspaceDisplayRow.Mac -> when (val entry = row.entry) {
                    is WorkspaceListEntry.Workspace -> displayWorkspace(workspace(entry.source.mac, entry.workspace.id), entry.workspace,
                        value.appearances.name(entry.source.mac), entry.source.availability, if (entry.indented) 1 else 0, true)
                    is WorkspaceListEntry.Header -> RoutedSidebarRow(group(entry.source, entry.group.id), "group", entry.group.name,
                        unread = entry.unread.isUnread, count = entry.unread.count, pinned = entry.group.isPinned,
                        iconSymbol = entry.group.iconSymbol, expanded = !entry.group.isCollapsed, canOpen = entry.group.liveAnchorWorkspaceId?.let { id -> entry.source.workspaces.any { it.id == id } } == true)
                    is WorkspaceListEntry.Footer -> RoutedSidebarRow(id("footer", entry.key), "footer", entry.group.name, canOpen = false)
                }
            }
        }
    }
    private fun displayWorkspace(key: String, workspace: NativeWorkspace, computer: String,
        availability: NativeFeedAvailability, depth: Int, canOpen: Boolean) = RoutedSidebarRow(key, "workspace", workspace.title,
        workspace.description, workspace.preview ?: workspace.terminals.firstOrNull()?.title ?: workspace.title, computer,
        workspace.hasUnread, workspace.unreadCount, workspace.isPinned, workspace.color, workspace.lastActivityAt,
        workspace.previewAt, availability, depth, canOpen = canOpen)

    private fun notifications(entries: List<NativeFeedEntry>, query: RoutedSidebarQuery, locale: Locale): List<RoutedSidebarRow> {
        val matches = NativeSearchIndex(entries.map { it.id to it.searchFields() }, locale, notification = true).matches(query.text)
        val projection = NativeFeedProjection.build(entries, query.unread, matches, ZoneId.systemDefault(), 2000, NativeFeedProjection())
        return buildList {
            projection.days.forEach { day ->
                add(RoutedSidebarRow(id("day", day.date), "heading", day.date?.toString() ?: "Earlier", canOpen = false))
                day.groups.forEach { group ->
                    val key = id("updates", group.id)
                    val expanded = key in query.expanded
                    (if (expanded) group.entries else group.entries.take(1)).forEachIndexed { index, entry ->
                        val presentation = entry.presentation(locale)
                        add(RoutedSidebarRow(notification(entry), "notification", presentation.headline, presentation.source,
                            presentation.preview, entry.computer, !entry.notification.isRead, activity = entry.notification.createdAt,
                            availability = entry.source.availability, depth = if (index == 0) 0 else 1))
                    }
                    if (group.entries.size > 1) add(RoutedSidebarRow(key, "updates", "${group.entries.size} updates",
                        count = group.entries.size.toLong(), expanded = expanded, canOpen = false))
                }
            }
        }
    }
    override fun resolve(key: String): (() -> Unit)? {
        fun target(): NativeSidebarTarget? {
            val value = input() ?: return null
            value.sources.forEach { source ->
                source.workspaces.firstOrNull { workspace(source.mac, it.id) == key }?.let { return NativeSidebarTarget.Workspace(source.mac, it.id) }
                source.groups.firstOrNull { group(source, it.id) == key }?.liveAnchorWorkspaceId?.let { id ->
                    if (source.workspaces.any { it.id == id }) return NativeSidebarTarget.Workspace(source.mac, id)
                }
            }
            value.ssh.firstOrNull { ssh(it) == key && it.openTarget() != null }?.let { return NativeSidebarTarget.Ssh(it) }
            aggregateNativeFeed(value.sources).firstOrNull { notification(it) == key }?.let { return NativeSidebarTarget.Notification(it) }
            return null
        }
        target() ?: return null
        // The returned callback can outlive resolution while an Activity finishes.
        return { target()?.let(navigate) ?: error("This sidebar destination is no longer available.") }
    }
}
