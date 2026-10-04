package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale

internal sealed interface NativeSidebarTarget {
    data class Action(val kind: RoutedSidebarActionKind) : NativeSidebarTarget
    data class Workspace(val mac: NativeCredentialStore.PairedMac, val id: String) : NativeSidebarTarget
    data class Ssh(val row: SshFeedRow) : NativeSidebarTarget
    data class Notification(val entry: NativeFeedEntry) : NativeSidebarTarget
}
internal data class NativeSidebarInput(val sources: List<NativeFeedSource>, val ssh: List<SshFeedRow>,
    val computers: List<NativeSortComputer>, val sort: NativeWorkspaceSortState,
    val sshAvailability: Map<java.util.UUID, NativeFeedAvailability> = emptyMap(),
    val appearances: NativeMacAppearances = NativeMacAppearances(), val locale: Locale = Locale.getDefault(),
    val actions: Set<RoutedSidebarActionKind> = emptySet(), val pendingMoves: Map<String, Int> = emptyMap())
internal data class NativeSidebarPresentation(val computer: String? = null, val notifications: Boolean = false,
    val workspaceQuery: String = "", val notificationQuery: String = "", val workspaceUnread: Boolean = false,
    val notificationUnread: Boolean = false, val machines: Set<String> = emptySet(),
    val projection: NativeFeedProjection = NativeFeedProjection(), val collapsedGroups: Map<String, Boolean> = emptyMap())

/** Main-process history, retained with the feed session across parent recreation. */
internal class NativeSidebarHistory {
    private var owner: Any? = null
    var projection = NativeFeedProjection()
    var requested: Set<String>? = null
    fun bind(value: Any) { if (owner != value) { clear(); owner = value } }
    fun clear() { owner = null; projection = NativeFeedProjection(); requested = null }
}

/** Uses the same ordering, search, group and notification projection policies as the main screen. */
internal class NativeRoutedSidebarHost(override val owner: Any, private val salt: String,
    private val input: () -> NativeSidebarInput?, private val retainFeed: () -> RoutedSidebarLease,
    private val navigate: (NativeSidebarTarget) -> Unit,
    private val initial: () -> NativeSidebarPresentation = { NativeSidebarPresentation() },
    private val adoptPresentation: (NativeSidebarPresentation) -> Unit = {},
    private val saveSort: ((NativeWorkspaceSortMode?, List<String>?) -> Unit)? = null,
    private val readNotification: (suspend (NativeFeedEntry, Boolean, () -> Boolean) -> Unit)? = null,
    private val readAllNotifications: (suspend (List<NativeCredentialStore.PairedMac>, () -> Boolean) -> Unit)? = null,
    private val refreshNotifications: (suspend () -> Unit)? = null,
    private val history: NativeSidebarHistory = NativeSidebarHistory(),
    private val mutateWorkspace: (suspend (NativeSidebarMutationTarget, RoutedSidebarMutation, () -> Boolean) -> Unit)? = null,
    private val moveWorkspace: (suspend (NativeFeedSource, String, NativeWorkspaceMove, () -> Boolean) -> Unit)? = null) : RoutedSidebarHost {
    private fun id(vararg values: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(JSONArray(listOf(salt) + values).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun action(kind: RoutedSidebarActionKind) = id("action", kind.name)
    private fun computer(key: String) = id("computer", key)
    private fun workspace(mac: NativeCredentialStore.PairedMac, key: String) = id("workspace", mac.origin, mac.code, key)
    private fun group(source: NativeFeedSource, key: String) = id("group", source.mac.origin, source.mac.code, key)
    private fun ssh(row: SshFeedRow) = id("ssh", row.key, row.generation, row.registry, row.host.endpoint,
        row.host.keyId, row.host.jumpHostId)
    private fun notification(entry: NativeFeedEntry) = id("notification", entry.source.mac.origin, entry.source.mac.code,
        entry.notification.id, entry.notification.workspaceId, entry.notification.surfaceId)
    override fun retain() = retainFeed()
    override fun current() = input() != null
    private fun updates(group: NativeFeedGroup) = id("updates", notification(group.entries.first { it.id == group.id }))
    private fun expanded(projection: NativeFeedProjection) = projection.days.flatMap { it.groups }
        .filter { it.id in projection.expanded }.map(::updates).toSet()
    private fun validProjection(projection: NativeFeedProjection, value: NativeSidebarInput): NativeFeedProjection {
        val days = projection.days.map { day -> day.copy(groups = day.groups.filter { group ->
            group.entries.all { entry -> value.sources.any { it.mac == entry.source.mac } }
        }) }.filter { it.groups.isNotEmpty() }
        return projection.copy(days = days, expanded = projection.expanded.intersect(days.flatMap { it.groups }.map { it.id }.toSet()))
    }
    override fun initialQuery(): RoutedSidebarQuery {
        val value = input() ?: return RoutedSidebarQuery()
        val state = initial()
        history.bind(owner)
        history.projection = validProjection(state.projection, value)
        history.requested = expanded(history.projection)
        val groups = value.sources.flatMap { source -> source.groups.mapNotNull { item ->
            state.collapsedGroups[WorkspaceListEntry.Header(source, item).key]?.let { group(source, item.id) to !it }
        } }.toMap()
        return RoutedSidebarQuery(state.notifications, state.workspaceQuery, state.notificationQuery,
            state.computer?.let(::computer), state.workspaceUnread, state.notificationUnread, state.machines.map(::computer).toSet(),
            checkNotNull(history.requested), groups)
    }
    private fun machines(value: NativeSidebarInput) = (value.sources.filter { it.workspaces.isNotEmpty() }.mapNotNull {
        workspaceMacFilterId(it.mac.deviceId, it.mac.instanceTag)
    } + value.ssh.map { workspaceSshFilterId(it.host.id) }).toSet()
    private fun filter(value: NativeSidebarInput, query: RoutedSidebarQuery) = NativeWorkspaceFilter(query.workspaceUnread, query.machines)
        .forMenu(machines(value).map(::computer).toSet(), query.computer != null)
    override fun sort(command: RoutedSidebarSort) {
        val save = checkNotNull(saveSort) { "Sidebar sorting is unavailable" }
        val value = checkNotNull(input()) { "Sidebar account changed" }
        when (command) {
            is RoutedSidebarSort.Mode -> save(command.mode, null)
            is RoutedSidebarSort.Order -> {
                val current = value.computers.associateBy { computer(it.id) }
                check(command.keys.distinct().size == command.keys.size && command.keys.toSet() == current.keys) {
                    "Computers changed. Reopen Computer Order."
                }
                save(null, command.keys.map { current.getValue(it).id })
            }
        }
    }
    override fun adopt(query: RoutedSidebarQuery) {
        val value = input() ?: return
        val selection = query.computer?.let { key -> value.computers.singleOrNull { computer(it.id) == key }?.id }
        if (query.computer != null && selection == null) return
        val selectedMachines = filter(value, query).machines
        adoptPresentation(NativeSidebarPresentation(selection, query.notifications,
            NativeSearchText.boundQuery(query.workspaceQuery), NativeSearchText.boundQuery(query.notificationQuery),
            query.workspaceUnread, query.notificationUnread,
            value.computers.filter { computer(it.id) in selectedMachines }.map { it.id }.toSet(),
            project(aggregateNativeFeed(notificationSources(value, query), computerName = value.appearances::name), query, value),
            value.sources.flatMap { source -> source.groups.mapNotNull { item ->
                query.groupExpansion[group(source, item.id)]?.let { WorkspaceListEntry.Header(source, item).key to !it }
            } }.toMap()))
    }

    private fun notificationSources(value: NativeSidebarInput, query: RoutedSidebarQuery): List<NativeFeedSource> {
        val selection = query.computer?.let { key -> value.computers.singleOrNull { computer(it.id) == key }?.id
            ?: error("This computer is no longer available") }
        return value.sources.filter { selection == null || workspaceMacFilterId(it.mac.deviceId, it.mac.instanceTag) == selection }
    }
    private fun readAllKey(value: NativeSidebarInput, query: RoutedSidebarQuery): String = id("notification-read-all", query.computer,
        notificationSources(value, query).map { listOf(it.mac.origin, it.mac.code) }.sortedBy { it.first() })
    override suspend fun notifications(command: RoutedSidebarNotification, query: RoutedSidebarQuery, canSend: () -> Boolean) {
        check(query.notifications && canSend()) { "Notifications are no longer visible" }
        val value = checkNotNull(input()) { "Sidebar account changed" }
        when (command) {
            is RoutedSidebarNotification.Read -> {
                val entry = aggregateNativeFeed(notificationSources(value, query)).singleOrNull { notification(it) == command.key }
                    ?: error("This notification changed. Refresh the sidebar.")
                check(entry.source.availability == NativeFeedAvailability.CONNECTED) { "This computer is offline" }
                checkNotNull(readNotification)(entry, command.read) {
                    canSend() && input()?.let { current -> aggregateNativeFeed(current.sources).any {
                        notification(it) == command.key && it.source.mac == entry.source.mac && it.source.availability == NativeFeedAvailability.CONNECTED
                    } } == true
                }
            }
            is RoutedSidebarNotification.ReadAll -> {
                check(command.key == readAllKey(value, query)) { "Computers changed. Confirm Mark All Read again." }
                checkNotNull(readAllNotifications)(notificationSources(value, query).map { it.mac }) {
                    canSend() && input()?.let { runCatching { readAllKey(it, query) == command.key }.getOrDefault(false) } == true
                }
            }
            RoutedSidebarNotification.Refresh -> checkNotNull(refreshNotifications).invoke()
        }
    }
    private fun mutationTarget(value: NativeSidebarInput, command: RoutedSidebarMutation): NativeSidebarMutationTarget? {
        if (mutateWorkspace == null) return null
        value.sources.forEach { source ->
            source.workspaces.singleOrNull { workspace(source.mac, it.id) == command.key }?.let { item ->
                if (command.kind in source.sidebarWorkspaceMutations(item)) return NativeSidebarMutationTarget(source.mac, item.id, false)
            }
            source.groups.singleOrNull { group(source, it.id) == command.key }?.let { item ->
                if (command.kind in source.sidebarGroupMutations(item)) return NativeSidebarMutationTarget(source.mac, item.id, true)
            }
        }
        return null
    }
    override suspend fun mutate(command: RoutedSidebarMutation, canSend: () -> Boolean) {
        command.validate()
        check(canSend()) { "Workspace sidebar is no longer visible" }
        if (command.kind == RoutedSidebarMutationKind.MOVE_TO_GROUP) {
            val context = groupContext(command.key)
            check(command.menuRevision == context.revision) { "Group menu changed. Reopen Move to Group." }
            val destination = command.destination?.let { key ->
                context.menu.entries.singleOrNull { it.isEnabled && group(context.source, it.group.id) == key }?.group?.id
                    ?: error("This group is no longer a move destination.")
            }
            check(destination != null || context.menu.canRemoveFromGroup) { "This workspace is no longer grouped." }
            val source = context.source
            // The shared queue normalizes this proposal once against the captured source.
            val intent = NativeWorkspaceMove(destination, null)
            checkNotNull(moveWorkspace).invoke(source, context.workspace, intent) {
                // The shared move queue checks the captured order inside the coordinator lock.
                // Its own optimistic prediction must not revoke this presentation's authority.
                canSend() && input()?.sources?.any { it.mac == source.mac && it.canReorderWorkspaces() &&
                    it.workspaces.any { row -> row.id == context.workspace } } == true
            }
            return
        }
        val target = mutationTarget(checkNotNull(input()) { "Sidebar account changed" }, command)
            ?: error("Workspace action changed. Refresh the sidebar.")
        checkNotNull(mutateWorkspace).invoke(target, command) {
            canSend() && input()?.let { mutationTarget(it, command) == target } == true
        }
    }
    private data class GroupContext(val source: NativeFeedSource, val workspace: String,
        val menu: NativeWorkspaceGroupMoveMenu, val revision: String)
    private fun groupContext(key: String): GroupContext {
        checkNotNull(moveWorkspace) { "Group moves are unavailable" }
        val value = checkNotNull(input()) { "Sidebar account changed" }
        val candidates = value.sources.flatMap { source -> source.workspaces.filter { workspace(source.mac, it.id) == key }.map { source to it } }
        val (source, item) = candidates.singleOrNull() ?: error("Workspace changed. Refresh the sidebar.")
        val menu = NativeWorkspaceGroupMoveMenu.forWorkspace(source, item.id, value.pendingMoves[source.mac.origin] ?: 0)
        check(!menu.isEmpty) { "Group moves are unavailable. Refresh the sidebar." }
        // Android JSONObject.wrap does not serialize arbitrary Kotlin objects like the
        // JVM test implementation. Hash explicit primitive fields on both runtimes.
        return GroupContext(source, item.id, menu, id("group-menu", key,
            source.mac.let { listOf(it.code, it.deviceId, it.name, it.instanceTag, it.accountUserId, it.accountTeamId,
                it.stableOrigin, it.previousOrigins.sorted()) },
            source.workspaces.map { listOf(it.id, it.groupId, it.isPinned, it.windowId) },
            source.groups.map { listOf(it.id, it.name, it.isPinned, it.isCollapsed, it.anchorWorkspaceId, it.isEmpty, it.iconSymbol) }))
    }
    override fun groupMenu(key: String, revision: String?, offset: Int): RoutedSidebarGroupPage {
        require(key.length in 1..128 && key.none(Char::isISOControl) && offset >= 0)
        require(revision == null || revision.length in 1..128 && revision.none(Char::isISOControl))
        val context = groupContext(key)
        check((offset == 0 && revision == null) || revision == context.revision) { "Group menu changed. Reopen Move to Group." }
        return RoutedSidebarGroupWire.page(context.revision, context.menu.entries.map {
            RoutedSidebarGroupChoice(group(context.source, it.group.id), it.group.name, it.group.iconSymbol, it.isCurrent, it.isEnabled)
        }, context.menu.canRemoveFromGroup, offset)
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
        val filter = filter(value, query)
        val projection = project(entries, query, value)
        val rows = if (query.notifications) notifications(projection, value.locale) else workspaces(value,
            sources.filter { source -> filter.matches(workspaceMacFilterId(source.mac.deviceId, source.mac.instanceTag)?.let(::computer), true) },
            sshRows.filter { filter.matches(computer(workspaceSshFilterId(it.host.id)), it.workspace.hasUnread) },
            query, selected == null, filter.active)
        val offline = sources.filter { it.availability == NativeFeedAvailability.OFFLINE }
        return RoutedSidebarSnapshot(ordered.map { RoutedSidebarComputer(computer(it.id), it.name, it.buildLabel) }, rows, unread,
            sources.any { it.availability == NativeFeedAvailability.CONNECTING },
            when {
                !validScope -> "This computer is no longer available. Choose another computer."
                offline.isNotEmpty() -> "Unavailable: ${offline.joinToString { value.appearances.name(it.mac) }}. Showing the last received updates."
                else -> null
            }, filterMachines = if (query.computer == null) ordered.filter { it.id in machines(value) }
                .map { RoutedSidebarComputer(computer(it.id), it.name, it.buildLabel) } else emptyList(),
            selectedMachines = filter.machines,
            sortMode = value.sort.mode.takeIf { saveSort != null && query.computer == null && !query.notifications },
            actions = RoutedSidebarActionKind.entries.filter { it in value.actions && (!query.notifications || it != RoutedSidebarActionKind.NEW_TASK) }
                .map { RoutedSidebarAction(action(it), it) },
            readAll = if (query.notifications && validScope && unread > 0 && readAllNotifications != null)
                RoutedSidebarReadAll(readAllKey(value, query), ordered.singleOrNull { it.id == selected }?.name ?: "All Computers") else null,
            canRefresh = query.notifications && refreshNotifications != null, expanded = expanded(projection))
    }
    private fun workspaces(value: NativeSidebarInput, sources: List<NativeFeedSource>, sshRows: List<SshFeedRow>,
        query: RoutedSidebarQuery, all: Boolean, filtering: Boolean): List<RoutedSidebarRow> {
        val matches = NativeSearchIndex(workspaceSearchRows(sources, value.appearances::name), value.locale).matches(query.text)
        val sshMatches = NativeSearchIndex(sshRows.map { it.key to it.searchFields() }, value.locale).matches(query.text)
        val collapsed = sources.flatMap { source -> source.groups.map { item ->
            WorkspaceListEntry.Header(source, item).key to (query.groupExpansion[group(source, item.id)]?.not() ?: item.isCollapsed)
        } }.toMap()
        val movable = if (moveWorkspace == null) emptyMap() else sources.associate { source -> source.mac.origin to
            NativeWorkspaceGroupMoveMenu.availableWorkspaceIds(source, value.pendingMoves[source.mac.origin] ?: 0) }
        return sortedWorkspaceRows(sources, sshRows.filter { it.key in sshMatches && (!query.unread || it.workspace.hasUnread) },
            value.computers, value.sort, all, matches, query.text.isNotBlank() || filtering, query.unread, collapsed, value.locale).map { row ->
            when (row) {
                is NativeWorkspaceDisplayRow.Ssh -> displayWorkspace(ssh(row.row), row.row.workspace, row.row.host.name,
                    (value.sshAvailability[row.row.host.id] ?: NativeFeedAvailability.OFFLINE), 0, row.row.openTarget() != null)
                is NativeWorkspaceDisplayRow.Mac -> when (val entry = row.entry) {
                    is WorkspaceListEntry.Workspace -> displayWorkspace(workspace(entry.source.mac, entry.workspace.id), entry.workspace,
                        value.appearances.name(entry.source.mac), entry.source.availability, if (entry.indented) 1 else 0, true)
                        .copy(mutations = buildSet {
                            if (mutateWorkspace != null) addAll(entry.source.sidebarWorkspaceMutations(entry.workspace))
                            if (entry.workspace.id in movable[entry.source.mac.origin].orEmpty()) add(RoutedSidebarMutationKind.MOVE_TO_GROUP)
                        })
                    is WorkspaceListEntry.Header -> RoutedSidebarRow(group(entry.source, entry.group.id), "group", entry.group.name,
                        unread = entry.unread.isUnread, count = entry.unread.count, pinned = entry.group.isPinned,
                        mutations = if (mutateWorkspace != null) entry.source.sidebarGroupMutations(entry.group) else emptySet(),
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

    private fun project(entries: List<NativeFeedEntry>, query: RoutedSidebarQuery, value: NativeSidebarInput): NativeFeedProjection {
        history.bind(owner)
        var previous = validProjection(history.projection, value)
        // Repeated polls may still carry the old anchor until the response is adopted.
        // Apply changed user intent once; keep membership-based reconciliation between polls.
        if (history.requested != query.expanded) previous = previous.copy(expanded = previous.days.flatMap { it.groups }
            .filter { updates(it) in query.expanded }.map { it.id }.toSet())
        history.requested = query.expanded
        val matches = NativeSearchIndex(entries.map { it.id to it.searchFields() }, value.locale, notification = true).matches(query.notificationQuery)
        return NativeFeedProjection.build(entries, query.notificationUnread, matches, ZoneId.systemDefault(), 2000, previous)
            .also { history.projection = it }
    }
    private fun notifications(projection: NativeFeedProjection, locale: Locale): List<RoutedSidebarRow> {
        return buildList {
            projection.days.forEach { day ->
                add(RoutedSidebarRow(id("day", day.date), "heading", day.date?.toString() ?: "Earlier", canOpen = false))
                day.groups.forEach { group ->
                    val key = updates(group)
                    val expanded = group.id in projection.expanded
                    (if (expanded) group.entries else group.entries.take(1)).forEachIndexed { index, entry ->
                        val presentation = entry.presentation(locale)
                        val rowValue = entry.rowValue(locale)
                        add(RoutedSidebarRow(notification(entry), "notification", presentation.headline, presentation.source,
                            presentation.preview, entry.computer, !entry.notification.isRead, count = group.entries.size.toLong().takeIf { index == 0 && it > 1 }, activity = entry.notification.createdAt,
                            availability = entry.source.availability, depth = if (index == 0) 0 else 1,
                            canRead = readNotification != null && entry.source.availability == NativeFeedAvailability.CONNECTED,
                            notificationContext = rowValue.nestedUnder(if (index > 0) group.entries.first().rowValue(locale) else null, locale)))
                        if (index == 0 && group.entries.size > 1) add(RoutedSidebarRow(key, "updates", "${group.entries.size} updates",
                            count = group.entries.size.toLong(), expanded = expanded, canOpen = false))
                    }
                }
            }
        }
    }
    override fun resolve(key: String): (() -> Unit)? {
        fun target(): NativeSidebarTarget? {
            val value = input() ?: return null
            value.actions.singleOrNull { action(it) == key }?.let { return NativeSidebarTarget.Action(it) }
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
