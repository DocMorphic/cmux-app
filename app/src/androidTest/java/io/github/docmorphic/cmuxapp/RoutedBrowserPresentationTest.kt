package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Production Activity + bound service + proxy. Generated host only; never touches credentials. */
class RoutedBrowserPresentationTest {
    private val beganAt = System.currentTimeMillis()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    // Set window dimensions before the bare Compose test host is launched. Unlike
    // MainActivity, that host has no onCreate content restoration after a resize.
    private var scenarioName = ""
    @get:Rule(order = 0) val display = object : org.junit.rules.TestWatcher() {
        private var originalSize: String? = null
        override fun starting(description: org.junit.runner.Description) {
            scenarioName = description.methodName
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Disposable emulator required" }
            if (description.methodName.startsWith("globalSidebar")) {
                originalSize = device.executeShellCommand("wm size").lineSequence()
                    .firstOrNull { it.startsWith("Override") }?.substringAfter(":")?.trim() ?: "reset"
                device.executeShellCommand("wm size 2400x1600")
            }
        }
        override fun finished(description: org.junit.runner.Description) {
            originalSize?.let { device.executeShellCommand("wm size $it") }
        }
    }
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paths = CopyOnWriteArrayList<String>()
    private val visits = CopyOnWriteArrayList<RecordedRequest>()
    private val uploads = CopyOnWriteArrayList<RecordedRequest>()
    private val targets = CopyOnWriteArrayList<String>()
    private val holds = AtomicInteger()
    private val releases = AtomicInteger()
    private val probes = CopyOnWriteArrayList<Boolean>()
    private val sidebarActive = CopyOnWriteArrayList<Boolean>()
    private val sidebarReleases = AtomicInteger()
    private val sidebarDestinations = CopyOnWriteArrayList<String>()
    private var sidebarAdopted: RoutedSidebarQuery? = null
    private var sidebarRows = listOf(RoutedSidebarRow("other", "workspace", "Other computer workspace", preview = "Remote preview"))
    private var sidebarAllows = true
    private var projectedSidebar: NativeRoutedSidebarHost? = null
    private var browserSidebarSelection: NativeSidebarSelection? = null
    private val openedCreation = CopyOnWriteArrayList<NativeSidebarTarget>()
    private var creationSsh: NativeSshSession? = null
    private var creationVault: File? = null
    private var creationTargets = emptyList<NativeSshCreateTarget>()
    private var sshCloseRows = emptyList<SshFeedRow>()
    private var sshCloseOnline = true
    private var sshCloseReject = true
    private var sshCloseHold = false
    private val sshCloseStarted = CompletableDeferred<Unit>()
    private val sshCloseContinue = CompletableDeferred<Unit>()
    private val sshCloseWrites = CopyOnWriteArrayList<String>()
    private val sidebarDisplayName = "fixture-sidebar-display-${UUID.randomUUID()}"
    private val sidebarDisplayTitle = "A long workspace title that must wrap across several lines inside the browser sidebar"
    private val sidebarDisplayPreview = "First preview line\nSecond preview line"
    private var creationBusy = false
    private var groupForeground = "A"
    private val groupAttempts = CopyOnWriteArrayList<String>()
    private val createdGroupOwners = CopyOnWriteArrayList<String>()
    private var rejectNewGroup = true
    private var holdNewGroup = true
    private val newGroupStarted = CompletableDeferred<Unit>()
    private val newGroupContinue = CompletableDeferred<Unit>()
    private var globalActions = RoutedSidebarActionKind.entries.toSet()
    private val openedActions = CopyOnWriteArrayList<RoutedSidebarActionKind>()
    private var noticeSources = emptyList<NativeFeedSource>()
    private val workspaceWrites = CopyOnWriteArrayList<String>()
    private var rejectWorkspaceRename = true
    private var rejectWorkspaceMove = true
    private val groupMoves = CopyOnWriteArrayList<NativeWorkspaceMove>()
    private val sidebarDescription = "Full description " + "x".repeat(2800) + " end of Mac description"
    private var rejectSidebarDescription = true
    private val sidebarCustomizationWrites = CopyOnWriteArrayList<Pair<String, WorkspaceCustomizationField>>()
    private val sidebarCustomizationStarted = CompletableDeferred<Unit>()
    private val sidebarCustomizationCancelled = CompletableDeferred<Unit>()
    private val changesReads = CopyOnWriteArrayList<String>()
    private val noticeWrites = CopyOnWriteArrayList<String>()
    private val noticeBulk = CopyOnWriteArrayList<List<String>>()
    private var failNoticeWrite = true
    private val noticeRefreshes = AtomicInteger()
    private var adoptedPresentation: NativeSidebarPresentation? = null
    private var sortJson: String? = null
    private val sortStore = NativeWorkspaceSortStore({ sortJson }, { sortJson = it })
    private val sidebarHost: RoutedSidebarHost = object : RoutedSidebarHost {
        override val owner: Any = "fixture-owner"
        override fun withSelection(selection: NativeSidebarSelection?): RoutedSidebarHost = object : RoutedSidebarHost by this {
            override fun read(query: RoutedSidebarQuery) = projectedSidebar?.withSelection(selection)?.read(query)
                ?: this@RoutedBrowserPresentationTest.sidebarHost.read(query)
        }
        override fun current() = projectedSidebar?.current() ?: true
        override fun initialQuery() = projectedSidebar?.initialQuery() ?: RoutedSidebarQuery()
        override fun changes(key: String) = checkNotNull(projectedSidebar).changes(key)
        override fun read(query: RoutedSidebarQuery) = if (projectedSidebar != null) projectedSidebar!!.read(query) else RoutedSidebarSnapshot(listOf(RoutedSidebarComputer("other-mac", "Other Mac")),
            if (query.notifications) listOf(RoutedSidebarRow("notice", "notification", "Remote notification", unread = true))
            else sidebarRows.filter { query.text.isBlank() || it.title.contains(query.text, ignoreCase = true) })
        override fun resolve(key: String): (() -> Unit)? = if (projectedSidebar != null) projectedSidebar!!.resolve(key) else if (sidebarAllows && (key == "notice" || sidebarRows.any { it.key == key }))
            ({ sidebarDestinations += key }) else null
        override fun adopt(query: RoutedSidebarQuery) { sidebarAdopted = query; projectedSidebar?.adopt(query) }
        override suspend fun mutate(command: RoutedSidebarMutation, canSend: () -> Boolean) {
            checkNotNull(projectedSidebar).mutate(command, canSend)
        }
        override suspend fun drop(command: RoutedSidebarDrop, query: RoutedSidebarQuery, canSend: () -> Boolean) {
            checkNotNull(projectedSidebar).drop(command, query, canSend)
        }
        override fun groupMenu(key: String, revision: String?, offset: Int) = checkNotNull(projectedSidebar).groupMenu(key, revision, offset)
        override fun customization(key: String) = checkNotNull(projectedSidebar).customization(key)
        override fun sort(command: RoutedSidebarSort) { checkNotNull(projectedSidebar).sort(command) }
        override suspend fun notifications(command: RoutedSidebarNotification, query: RoutedSidebarQuery, canSend: () -> Boolean) {
            checkNotNull(projectedSidebar).notifications(command, query, canSend)
        }
        override fun retain() = RoutedSidebarLease({ sidebarActive += it }, { sidebarReleases.incrementAndGet() })
    }
    private val key = LocalBrowserKey("generated-account", "generated-team", "generated-mac", "workspace")
    private val workspace = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"workspace","title":"Fixture workspace","terminals":[{"id":"terminal","title":"Fixture shell"}]}]}""")).single().copy(browsers = listOf(NativeBrowser("first", "First"), NativeBrowser("second", "Second")),
        surfaces = listOf(NativeSurface("first", "browser", "First"), NativeSurface("second", "browser", "Second")))
    private lateinit var network: NativeMacBrowserNetwork
    private lateinit var navigation: LocalBrowserNavigation
    private lateinit var server: MockWebServer
    private lateinit var surface: LocalBrowserSurface
    private var route: NativeWorkspaceRoute? = null
    private var browserState by mutableStateOf(NativeBrowserPickerState())
    private var menuWorkspace by mutableStateOf(workspace)
    private var menuCreationEnabled by mutableStateOf(true)
    private var menuCustomizationEnabled by mutableStateOf(false)
    private val customizationRequests = CopyOnWriteArrayList<WorkspaceCustomizationDraft>()
    private var failCustomization = false
    private var holdCustomization = false
    private val customizationStarted = CompletableDeferred<Unit>()
    private val customizationCancelled = CompletableDeferred<Unit>()
    private val creationRequests = CopyOnWriteArrayList<String>()
    private var frozenDestination by mutableStateOf<LocalBrowserDestination?>(null)
    private fun created(kind: String) { creationRequests += kind; navigation.leave(close = true) }
    private fun selectedRow(label: String): Boolean {
        // Compose exports Selected for non-tab menu items as Android checked state on the clickable parent.
        val row = generateSequence(text(label)) { it.parent }.firstOrNull { it.isClickable }
        return row?.isCheckable == true && row.isChecked
    }
    private fun capturePicker(name: String) {
        val shots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        device.dumpWindowHierarchy(File(shots, "$name.xml"))
        assertTrue(device.takeScreenshot(File(shots, "$name.png")))
    }
    private fun <T> main(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun text(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 30_000)) { "Missing: $value" }
    private fun browser(value: String): UiObject2 {
        // The parent uses Compose's virtual clock; the other process uses real frames.
        // Pump the parent until its asynchronous registration actually launches the child.
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        return text(value)
    }
    private fun desc(value: String) = checkNotNull(device.wait(Until.findObject(By.desc(value)), 15_000)) { "Missing: $value" }
    private fun until(predicate: () -> Boolean) = runBlocking { withTimeout(15_000) { while (!predicate()) delay(100) } }
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Disposable emulator required" }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path!!
                    visits += request
                    if (request.path == "/upload") {
                        uploads += request
                        return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody("<title>Upload received</title><h1>Upload received through the Mac route</h1>")
                    }
                    if (request.path == "/form") return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Upload form</title>
                            <style>body{background:#164f3b;color:white}button{display:block;margin:24px 0;font-size:24px}</style>
                            <form method="post" action="/upload" enctype="multipart/form-data">
                            <input id="file" name="attachment" type="file" accept="text/plain" hidden onchange="if(this.files.length)document.title='Selected file'">
                            <button type="button" onclick="document.getElementById('file').click()">Choose upload file</button>
                            <button>Upload selected file</button></form>""".trimIndent())
                    val next = request.path!!.startsWith("/next")
                    return MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store")
                        .setBody("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>${if(next) "Next" else "Routed fixture"}</title><body style="background:#164f3b;color:white;font:24px sans-serif"><h1>Mac route fixture</h1><a style="color:white" href="/next">Open next page</a><p><button onclick="window.draft=true;renderDraft()">Keep draft</button></p><p><a style="color:white" href="/form">Open upload form</a></p><script>function renderDraft(){if(window.draft)document.title='Draft '+(innerWidth>innerHeight?'landscape':'portrait')}addEventListener('resize',renderDraft);document.body.dataset.cookie=document.cookie;document.cookie='presentation=kept;path=/'</script>""")
                }
            }; start()
        }
        main {
            if (scenarioName == "globalSidebarChangesSheetReadsExactMacAndRetainsBrowserThroughDismissalAndRevocation") {
                val a = NativeCredentialStore.PairedMac("changes-a", "A", "Mac A")
                val b = NativeCredentialStore.PairedMac("changes-b", "B", "Mac B")
                noticeSources = listOf(a, b).mapIndexed { index, mac -> NativeFeedSource(mac,
                    workspaces = listOf(workspace.copy(title = "Changes workspace ${mac.deviceId}")),
                    availability = NativeFeedAvailability.CONNECTED, capabilities = setOf(WORKSPACE_CHANGES_CAPABILITY),
                    changes = mapOf(workspace.id to WorkspaceChangesChip(index + 1L, (index + 1L) * 11, index + 1L))) }
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-changes", {
                    NativeSidebarInput(noticeSources, emptyList(), noticeSources.map { NativeSortComputer(it.mac.deviceId, it.mac.name) }, NativeWorkspaceSortState())
                }, { RoutedSidebarLease({}) {} }, { error("Changes must not navigate the workspace") },
                    readChanges = { mac, row, gate -> WorkspaceChangesAccess(row.id, row.title, gate) { request ->
                        changesReads += "${mac.deviceId}:${request.javaClass.simpleName}"
                        when (request) {
                            WorkspaceChangesRead.Files -> JSONObject().put("workspace_id", row.id).put("repo_root", "/fixture")
                                .put("files", org.json.JSONArray().put(JSONObject().put("path", "README.md").put("status", "modified").put("additions", 1).put("deletions", 1)))
                            is WorkspaceChangesRead.Diff -> JSONObject().put("path", request.path)
                                .put("unified_diff", "@@ -1 +1 @@\n-old-${mac.deviceId}\n+fresh-${mac.deviceId}\n").put("truncated", false)
                            else -> error("Unexpected content request")
                        }
                    } })
            }
            if (scenarioName == "globalSidebarSelectionFollowsCapturedOwnerAndLiveAnchorWithoutReload") {
                val a = NativeCredentialStore.PairedMac("selection-a", "A", "Mac A")
                val b = NativeCredentialStore.PairedMac("selection-b", "B", "Mac B")
                noticeSources = listOf(a, b).map { mac -> NativeFeedSource(mac,
                    workspaces = listOf(workspace.copy(title = "Selected on ${mac.deviceId}")), availability = NativeFeedAvailability.CONNECTED) }
                browserSidebarSelection = NativeSidebarSelection.Mac(a, workspace.id)
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-selection", {
                    NativeSidebarInput(noticeSources, emptyList(), noticeSources.map { NativeSortComputer(it.mac.deviceId, it.mac.name) }, NativeWorkspaceSortState())
                }, { RoutedSidebarLease({}) {} }, {})
            }
            if (scenarioName == "globalSidebarDisplayPreferencesUpdateWhileParentPausedAndPreserveDraft") {
                val preferences = context.getSharedPreferences(sidebarDisplayName, Context.MODE_PRIVATE)
                preferences.edit().clear().commit()
                val mac = NativeCredentialStore.PairedMac("fixture-display", "display", "Display Mac")
                val source = NativeFeedSource(mac, workspaces = listOf(
                    NativeWorkspace("display", sidebarDisplayTitle, emptyList(), null, false, null, null, false,
                        emptyList(), null, sidebarDisplayPreview, null),
                    NativeWorkspace("next", "Next workspace", emptyList(), null, false, null, null, false, emptyList(), null, "Next preview", null)))
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-display", {
                    NativeSidebarInput(listOf(source), emptyList(), listOf(NativeSortComputer("display", "Display Mac")),
                        NativeWorkspaceSortState(), display = NativeDisplayPreferences.read(preferences))
                }, { RoutedSidebarLease({}) {} }, {})
            }
            if (scenarioName.startsWith("globalSidebarSshClose")) {
                val a = SshHostRecord(name = "SSH A", endpoint = SshEndpoint("a.invalid", 22, "user"))
                val b = a.copy(id = UUID.randomUUID(), name = "SSH B", endpoint = a.endpoint.copy(host = "b.invalid"))
                sshCloseRows = listOf(a, b).flatMap { host ->
                    val tmux = sshTmuxFeedRows(host, listOf(SshTmuxWorkspace(42, 2, 100L, "Tmux ${host.name}", emptyList()))).single()
                    listOf(tmux, tmux.copy(key = "shell-${host.id}", title = "Shell ${host.name}", kind = SshWorkspaceKind.SHELL),
                        tmux.copy(key = "cmux-${host.id}", title = "Cmux ${host.name}", kind = SshWorkspaceKind.CMUX_TUI))
                }
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-ssh-close", {
                    NativeSidebarInput(emptyList(), sshCloseRows, listOf(a, b).map {
                        NativeSortComputer(workspaceSshFilterId(it.id), it.name)
                    }, NativeWorkspaceSortState(), sshAvailability = listOf(a, b).associate { it.id to
                        if (sshCloseOnline) NativeFeedAvailability.CONNECTED else NativeFeedAvailability.OFFLINE })
                }, { RoutedSidebarLease({}) {} }, { error("Close must preserve the page") },
                    mutateWorkspace = { _, _, _ -> error("SSH close must not use Mac RPC") },
                    canCloseSsh = { sshCloseOnline }, closeSsh = { row, canSend ->
                        if (sshCloseHold) { sshCloseStarted.complete(Unit); sshCloseContinue.await() }
                        check(canSend()) { "SSH close target changed" }
                        if (row.kind == SshWorkspaceKind.TMUX && sshCloseReject) {
                            sshCloseReject = false; error("Fixture SSH close rejected")
                        }
                        sshCloseWrites += row.title
                        sshCloseRows = sshCloseRows.filterNot { it.sameOwner(row) }
                    })
            }
            if (scenarioName.startsWith("globalSidebarNewGroup")) {
                noticeSources = listOf("A", "B").map { name -> NativeFeedSource(
                    NativeCredentialStore.PairedMac("fixture-group-create-$name", name, "Mac $name"),
                    availability = NativeFeedAvailability.CONNECTED,
                    capabilities = setOf(WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY, "workspace.group_create.v1")) }
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-new-group", {
                    NativeSidebarInput(noticeSources, emptyList(), noticeSources.map {
                        NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name)
                    }, NativeWorkspaceSortState(), creation = NativeSidebarCreation(
                        foregroundMac = noticeSources.singleOrNull { it.mac.deviceId == groupForeground }?.mac))
                }, { RoutedSidebarLease({}) {} }, {}, createWorkspaceGroup = { mac, canSend ->
                    check(canSend()); groupAttempts += mac.deviceId
                    if (scenarioName == "globalSidebarNewGroupCancelsAQueuedCreateWhenComputerScopeChanges" && holdNewGroup) {
                        newGroupStarted.complete(Unit); newGroupContinue.await()
                    }
                    check(canSend()) { "Group creation is no longer active" }
                    if (scenarioName == "globalSidebarNewGroupUsesCapturedMacAndPreservesPageThroughRetry" && rejectNewGroup) {
                        rejectNewGroup = false; error("Fixture group creation rejected")
                    }
                    createdGroupOwners += mac.deviceId
                    noticeSources = noticeSources.map { source -> if (source.mac != mac) source else source.copy(
                        groups = source.groups + NativeGroup("new-${source.groups.size}", "New group ${mac.deviceId}", false, false)) }
                })
            }
            if (scenarioName.startsWith("globalSidebarCreation")) {
                noticeSources = listOf("A", "B").map { name -> NativeFeedSource(
                    NativeCredentialStore.PairedMac("fixture-create-$name", name, "Mac $name"),
                    workspaces = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"anchor","title":"Anchor $name","group_id":"g"}]}""")),
                    groups = listOf(NativeGroup("g", "Group $name", false, false, anchorWorkspaceId = "anchor")),
                    availability = NativeFeedAvailability.CONNECTED,
                    capabilities = setOf(WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY, "workspace.create_in_group.v1")) }
                if (scenarioName == "globalSidebarCreationOffersSshKindsAndRejectsAChangedHost") {
                    val hosts = SshHostStore({ null }, {})
                    val host = SshHostRecord(name = "SSH fixture", endpoint = SshEndpoint("fixture.invalid", username = "test"))
                    hosts.upsert(host)
                    val directory = File(context.cacheDir, "sidebar-create-${UUID.randomUUID()}").apply { mkdirs() }
                    creationVault = directory
                    val session = NativeSshSession(hosts, SshKeyVault(directory, { true }, {}), owner, admitted = { true })
                    creationSsh = session
                    creationTargets = listOf(NativeSshCreateTarget(session, host, null, listOf(
                        SshWorkspaceKindOption(SshWorkspaceKind.CMUX_TUI),
                        SshWorkspaceKindOption(SshWorkspaceKind.TMUX, "tmux is not installed on this computer."),
                        SshWorkspaceKindOption(SshWorkspaceKind.SHELL))))
                }
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-creation", {
                    NativeSidebarInput(noticeSources, emptyList(), noticeSources.map {
                        NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name)
                    } + creationTargets.map { NativeSortComputer(workspaceSshFilterId(it.host.id), it.name) },
                        NativeWorkspaceSortState(), creation = NativeSidebarCreation(creationBusy, creationTargets))
                }, { RoutedSidebarLease({}) {} }, { openedCreation += it })
            }
            if (scenarioName.startsWith("globalSidebarCustomization")) {
                noticeSources = listOf("A", "B").map { name -> NativeFeedSource(
                    NativeCredentialStore.PairedMac("fixture-custom-$name", name, "Mac $name"),
                    workspaces = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"same","title":"Custom $name","window_id":"window"}]}""")).map {
                        it.copy(description = sidebarDescription, color = "#123456")
                    }, availability = NativeFeedAvailability.CONNECTED,
                    capabilities = setOf("workspace.actions.v1", WORKSPACE_METADATA_CAPABILITY)) }
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-customization", {
                    NativeSidebarInput(noticeSources, emptyList(), noticeSources.map {
                        NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name)
                    }, NativeWorkspaceSortState())
                }, { RoutedSidebarLease({}) {} }, {}, customizeWorkspace = { target, baseline, submitted, canSend ->
                    check(canSend())
                    if (scenarioName == "globalSidebarCustomizationRevocationCancelsSaveWithoutReload") {
                        sidebarCustomizationStarted.complete(Unit)
                        try { awaitCancellation() } finally { sidebarCustomizationCancelled.complete(Unit) }
                    }
                    saveWorkspaceCustomization(baseline, submitted, read = {
                        check(canSend()); WorkspaceCustomizationDraft.from(noticeSources.single { it.mac == target.mac }.workspaces.single())
                    }, write = { field, draft ->
                        check(canSend()); sidebarCustomizationWrites += target.mac.deviceId to field
                        if (field == WorkspaceCustomizationField.DESCRIPTION && rejectSidebarDescription) {
                            rejectSidebarDescription = false; error("Fixture description rejected")
                        }
                        noticeSources = noticeSources.map { source -> if (source.mac != target.mac) source else source.copy(
                            workspaces = source.workspaces.map { row -> when (field) {
                                WorkspaceCustomizationField.NAME -> row.copy(title = draft.name)
                                WorkspaceCustomizationField.DESCRIPTION -> row.copy(description = draft.description)
                                WorkspaceCustomizationField.COLOR -> row.copy(color = draft.color)
                                WorkspaceCustomizationField.PINNED -> row.copy(isPinned = draft.pinned)
                            } }) }
                    })
                })
            }
            if (scenarioName == "globalSidebarSharesFiltersSortOrderAndBothSearchScopesOnReturn") {
                fun source(id: String) = NativeFeedSource(NativeCredentialStore.PairedMac("generated-$id", id, "Mac $id"),
                    workspaces = parseWorkspaces(JSONObject("""{"workspaces":[
                        {"id":"alpha","title":"Alpha $id","has_unread":true},
                        {"id":"read","title":"Alpha read $id","has_unread":false},
                        {"id":"beta","title":"Beta $id","has_unread":true}]}""")), availability = NativeFeedAvailability.CONNECTED)
                val sources = listOf(source("A"), source("B"))
                val computers = sources.map { NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name) }
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-salt",
                    { NativeSidebarInput(sources, emptyList(), computers, sortStore.state.value) },
                    { RoutedSidebarLease({}) {} }, {},
                    initial = { NativeSidebarPresentation(workspaceQuery = "Alpha", notificationQuery = "notice", machines = setOf(computers.first().id)) },
                    adoptPresentation = { adoptedPresentation = it },
                    saveSort = { mode, order -> mode?.let(sortStore::setMode); order?.let(sortStore::setPriority) })
            }
            if (scenarioName == "globalSidebarOpensSharedSettingsComputersAndTaskFlows") {
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-actions",
                    { NativeSidebarInput(emptyList(), emptyList(), emptyList(), NativeWorkspaceSortState(), actions = globalActions) },
                    { RoutedSidebarLease({}) {} }, { target ->
                        openedActions += (target as NativeSidebarTarget.Action).kind
                    })
            }
            if (scenarioName in setOf("globalSidebarGroupMovesStayAnchoredRejectStaleMenusAndPreserveDraft", "globalSidebarDragOrdersGroupsAndKeepsBrowserDraft")) {
                noticeSources = listOf(NativeFeedSource(NativeCredentialStore.PairedMac("fixture-pairing", "A", "Mac A"),
                    workspaces = parseWorkspaces(JSONObject("""{"workspaces":[
                        {"id":"w","title":"Move workspace","window_id":"window"},
                        {"id":"anchor","title":"Group anchor","group_id":"g","window_id":"window"},
                        {"id":"child","title":"Grouped child","group_id":"g","window_id":"window"},
                        {"id":"target","title":"Target anchor","group_id":"h","window_id":"window"}]}""")),
                    availability = NativeFeedAvailability.CONNECTED,
                    groups = listOf(NativeGroup("g", "Original group", false, false, "anchor", false, "folder"),
                        NativeGroup("h", "Destination group", false, false, "target", false, "folder")),
                    capabilities = setOf("workspace.move.v1", WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY)))
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-group-moves", {
                    NativeSidebarInput(noticeSources, emptyList(), listOf(NativeSortComputer(workspaceMacFilterId("A", null)!!, "Mac A")), NativeWorkspaceSortState())
                }, { RoutedSidebarLease({}) {} }, {}, moveWorkspace = { captured, id, intent, canSend ->
                    check(canSend()); groupMoves += intent
                    if (rejectWorkspaceMove) { rejectWorkspaceMove = false; error("Fixture group move rejected") }
                    noticeSources = noticeSources.map { source -> if (source.mac != captured.mac) source else source.copy(
                        workspaces = NativeWorkspaceMovePolicy(source.workspaces, source.groups).applying(intent, id)) }
                })
            }
            if (scenarioName == "globalSidebarWorkspaceAndGroupMutationsPreservePageAndRequireConfirmation") {
                noticeSources = listOf(NativeFeedSource(NativeCredentialStore.PairedMac("fixture-pairing", "A", "Mac A"),
                    workspaces = parseWorkspaces(JSONObject("""{"workspaces":[
                        {"id":"w","title":"Action workspace","has_unread":true},
                        {"id":"anchor","title":"Group anchor","group_id":"g"},
                        {"id":"child","title":"Grouped child","group_id":"g"}]}""")),
                    availability = NativeFeedAvailability.CONNECTED,
                    groups = listOf(NativeGroup("g", "Action group", false, false, "anchor", false)),
                    capabilities = setOf("workspace.actions.v1", "workspace.close.v1", "workspace.read_state.v1", "workspace.group_actions.v1", WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY)))
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-mutations", {
                    NativeSidebarInput(noticeSources, emptyList(), listOf(NativeSortComputer(workspaceMacFilterId("A", null)!!, "Mac A")), NativeWorkspaceSortState())
                }, { RoutedSidebarLease({}) {} }, {}, mutateWorkspace = { target, command, canSend ->
                    check(canSend()); workspaceWrites += "${target.id}:${command.kind.verb}:${command.title.orEmpty()}"
                    if (command.kind == RoutedSidebarMutationKind.RENAME && !target.group && rejectWorkspaceRename) {
                        rejectWorkspaceRename = false; error("Fixture workspace update rejected")
                    }
                    noticeSources = noticeSources.map { source -> if (source.mac != target.mac) source else if (target.group) {
                        val remove = command.kind in setOf(RoutedSidebarMutationKind.UNGROUP, RoutedSidebarMutationKind.DELETE_GROUP)
                        source.copy(groups = if (remove) source.groups.filter { it.id != target.id } else source.groups.map {
                            if (it.id != target.id) it else when (command.kind) {
                                RoutedSidebarMutationKind.RENAME -> it.copy(name = checkNotNull(command.title))
                                RoutedSidebarMutationKind.PIN -> it.copy(isPinned = true)
                                RoutedSidebarMutationKind.UNPIN -> it.copy(isPinned = false)
                                else -> it
                            }
                        }, workspaces = if (command.kind == RoutedSidebarMutationKind.DELETE_GROUP) source.workspaces.filter { it.groupId != target.id }
                            else if (command.kind == RoutedSidebarMutationKind.UNGROUP) source.workspaces.map { if (it.groupId == target.id) it.copy(groupId = null) else it }
                            else source.workspaces)
                    } else source.copy(workspaces = if (command.kind == RoutedSidebarMutationKind.CLOSE) source.workspaces.filter { it.id != target.id }
                        else source.workspaces.map { if (it.id != target.id) it else when (command.kind) {
                            RoutedSidebarMutationKind.RENAME -> it.copy(title = checkNotNull(command.title))
                            RoutedSidebarMutationKind.PIN -> it.copy(isPinned = true)
                            RoutedSidebarMutationKind.UNPIN -> it.copy(isPinned = false)
                            RoutedSidebarMutationKind.MARK_READ -> it.copy(hasUnread = false, unreadCount = 0)
                            RoutedSidebarMutationKind.MARK_UNREAD -> it.copy(hasUnread = true, unreadCount = 1)
                            else -> it
                        } }) }
                })
            }
            if (scenarioName == "globalSidebarNotificationsShareRowsMutateAndConfirmCapturedScopeWithoutReload" ||
                scenarioName == "globalSidebarRestoresExpansionThroughRetentionAndReturnsGroupState") {
                val now = System.currentTimeMillis() / 1000.0
                noticeSources = listOf("A", "B").map { name ->
                    NativeFeedSource(NativeCredentialStore.PairedMac("generated-$name", name, "Mac $name"),
                        workspaces = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","title":"Tasks $name"}]}""")),
                        items = listOf(NativeNotification("new", "w", null, "Agent", "Latest $name", false, createdAt = now - 60),
                            NativeNotification("old", "w", null, "Agent", "Earlier $name", false, createdAt = now - 120)),
                        availability = if (name == "A") NativeFeedAvailability.CONNECTED else NativeFeedAvailability.OFFLINE)
                }
                if (scenarioName == "globalSidebarRestoresExpansionThroughRetentionAndReturnsGroupState") {
                    noticeSources = noticeSources.take(1).map { source -> source.copy(
                        items = source.items.take(1) + source.items.first().copy(id = "middle", body = "Middle A", createdAt = now - 90) + source.items.takeLast(1),
                        workspaces = source.workspaces.map { it.copy(groupId = "g") },
                        groups = listOf(NativeGroup("g", "Tasks group", false, false, "w", false))) }
                    val entries = aggregateNativeFeed(noticeSources)
                    val projection = NativeFeedProjection.build(entries, false, entries.map { it.id }.toSet(), java.time.ZoneId.systemDefault(), 100, NativeFeedProjection())
                    adoptedPresentation = NativeSidebarPresentation(notifications = true,
                        projection = projection.toggle(projection.days.single().groups.single().id),
                        collapsedGroups = mapOf(WorkspaceListEntry.Header(noticeSources.first(), noticeSources.first().groups.first()).key to true))
                }
                projectedSidebar = NativeRoutedSidebarHost("fixture-owner", "fixture-notifications",
                    { NativeSidebarInput(noticeSources, emptyList(), noticeSources.map {
                        NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name)
                    }, NativeWorkspaceSortState(), actions = RoutedSidebarActionKind.entries.toSet()) },
                    { RoutedSidebarLease({}) {} }, {}, initial = { adoptedPresentation ?: NativeSidebarPresentation(notifications = true) },
                    adoptPresentation = { adoptedPresentation = it },
                    readNotification = { entry, read, canSend ->
                        check(canSend()); noticeWrites += "${entry.source.mac.deviceId}:${entry.notification.id}:$read"
                        if (failNoticeWrite) { failNoticeWrite = false; error("Fixture notification update rejected") }
                        noticeSources = noticeSources.map { source -> if (source.mac == entry.source.mac) source.copy(items = source.items.map {
                            if (it.id == entry.notification.id) it.copy(isRead = read) else it
                        }) else source }
                    }, readAllNotifications = { macs, canSend ->
                        check(canSend()); noticeBulk += macs.map { it.deviceId!! }
                        noticeSources = noticeSources.map { source -> if (source.mac in macs) source.copy(items = source.items.map { it.copy(isRead = true) }) else source }
                    }, refreshNotifications = { noticeRefreshes.incrementAndGet(); Unit })
            }
            network = NativeMacBrowserNetwork(owner, object : MacBrowserAccess {
                override suspend fun availability() = MacBrowserAvailability.AVAILABLE
                override suspend fun listeningPorts() = BrowserTunnelProtocol.ListeningPorts(emptyList(), false)
                override suspend fun use(host: String, port: Int, connected: suspend (BrowserTunnelLane) -> Unit) {
                    targets += "$host:$port"
                    NioBrowserSocket.direct.use("127.0.0.1", server.port, connected)
                }
            }, { true })
            navigation = LocalBrowserNavigation(owner, LocalBrowserStore(defaultUrl = "http://localhost:34876/start"))
            navigation.restoreRemembered(key, workspace)
            surface = navigation.state.value.local!!.surface
        }
        compose.setContent { CompositionLocalProvider(LocalRoutedSidebarHost provides sidebarHost) { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            val state by navigation.state.collectAsState()
            val destination = frozenDestination ?: state.local
            if (destination == null) Button(onClick = { navigation.restoreRemembered(key, workspace) }) { Text("Reopen fixture") }
            else RoutedLocalBrowserWorkspaceView(destination, navigation, menuWorkspace, { network }, {
                holds.incrementAndGet()
                RoutedBrowserHostLease({ holds.decrementAndGet(); releases.incrementAndGet() }, { probes += it })
            }, {}, { route = it }, browserModes = true, sidebarSelection = browserSidebarSelection,
                onNewWorkspace = { created("workspace") }, onNewTerminal = { created("terminal") }, onNewBrowser = { created("browser") },
                menuSource = { RoutedBrowserMenu(menuWorkspace, menuCreationEnabled, browserState = browserState, customizationEnabled = menuCustomizationEnabled) },
                customizeWorkspace = { baseline, submitted ->
                    customizationRequests += submitted
                    if (holdCustomization) {
                        customizationStarted.complete(Unit)
                        try { awaitCancellation() } finally { customizationCancelled.complete(Unit) }
                    }
                    if (failCustomization) {
                        failCustomization = false
                        WorkspaceCustomizationResult(false, baseline, submitted, "Fixture save rejected")
                    } else {
                        menuWorkspace = menuWorkspace.copy(title = submitted.name, description = submitted.description,
                            color = submitted.color, isPinned = submitted.pinned)
                        WorkspaceCustomizationResult(true)
                    }
                })
        } } } }
    }
    private fun wideSidebar(block: () -> Unit) {
        check(device.displayWidth >= 2400)
        try { block() } catch (failure: Throwable) {
            capturePicker("routed-sidebar-failure"); throw failure
        }
    }
    @Test fun globalSidebarKeepsBrowserDraftWhileHiddenAndNavigatesValidatedDestination() {
        browser("Routed fixture ▾")
        text("Keep draft").click()
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Draft ")), 5_000))
        val loads = paths.count { it == "/start" }
        wideSidebar {
            text("Other computer workspace")
            until { sidebarActive.lastOrNull() == true }
            capturePicker("routed-global-sidebar")
            desc("Hide sidebar").click(); desc("Show sidebar")
            until { sidebarActive.lastOrNull() == false }
            desc("Show sidebar").click(); text("Other computer workspace")
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("Draft ")), 5_000))
            assertEquals(loads, paths.count { it == "/start" })
            assertEquals(1, holds.get()); assertEquals(0, releases.get())
            text("Other computer workspace").click()
            compose.waitForIdle(); text("Reopen fixture")
            until { holds.get() == 0 && sidebarReleases.get() == 1 }
            assertEquals(listOf("other"), sidebarDestinations.toList())
            assertEquals(false, sidebarActive.last())
        }
    }
    @Test fun globalSidebarReadsLivePausedParentAndRejectsStaleDestinationBeforeLeaving() {
        browser("Routed fixture ▾")
        wideSidebar {
            text("Other computer workspace")
            main { sidebarAllows = false }
            text("Other computer workspace").click()
            text("This destination changed. Refresh the sidebar.")
            assertTrue(sidebarDestinations.isEmpty()); assertEquals(1, holds.get())
            main { sidebarAllows = true; sidebarRows = listOf(RoutedSidebarRow("other", "workspace", "Renamed on Mac")) }
            text("Renamed on Mac")
            text("Notifications").click(); text("Remote notification")
            text("Remote notification").click()
            compose.waitForIdle(); text("Reopen fixture")
            until { holds.get() == 0 }
            assertEquals(listOf("notice"), sidebarDestinations.toList())
            assertTrue(sidebarAdopted?.notifications == true)
        }
    }
    @Test fun globalSidebarSharesFiltersSortOrderAndBothSearchScopesOnReturn() {
        browser("Routed fixture ▾")
        text("Alpha A"); assertFalse(device.hasObject(By.text("Alpha B")))
        desc("Filter workspaces").click(); text("Unread").click(); device.pressBack()
        assertTrue(device.wait(Until.gone(By.text("Alpha read A")), 5_000))
        desc("Filter workspaces").click(); text("Mac B").click(); device.pressBack(); text("Alpha B")
        desc("Filter workspaces").click(); text("Recent Activity").click(); device.pressBack()
        until { sortStore.state.value.mode == NativeWorkspaceSortMode.ACTIVITY }
        desc("Filter workspaces").click(); text("Custom Order").click()
        assertFalse(device.hasObject(By.text("Computer Order")))
        text("Edit Computer Order").click(); text("Computer Order")
        val first = desc("Drag to reorder Mac A").visibleCenter
        val second = desc("Drag to reorder Mac B").visibleCenter
        assertTrue(device.drag(first.x, first.y, second.x, second.y + 35, 50))
        until { sortStore.state.value.priority.firstOrNull() == workspaceMacFilterId("B", null) }
        until { desc("Drag to reorder Mac B").visibleCenter.y < desc("Drag to reorder Mac A").visibleCenter.y }
        capturePicker("routed-sidebar-computer-order")
        text("Done").click()
        text("Notifications").click()
        desc("Notification filter").click(); text("Unread").click()
        desc("Search").click()
        val notice = checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").text("notice")), 5_000))
        notice.text = "notice final"; device.pressEnter(); desc("Search")
        text("Workspaces").click()
        desc("Search").click()
        val workspace = checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").text("Alpha")), 5_000))
        workspace.text = "Beta"
        // Hide while still editing: this commits without requiring another visible feed poll.
        desc("Hide sidebar").click(); desc("Show sidebar"); device.waitForIdle()
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { sidebarReleases.get() == 1 }
        assertEquals(NativeSidebarPresentation(workspaceQuery = "Beta", notificationQuery = "notice final",
            workspaceUnread = true, notificationUnread = true,
            machines = setOf(workspaceMacFilterId("A", null)!!, workspaceMacFilterId("B", null)!!)), adoptedPresentation)
        assertEquals(NativeWorkspaceSortMode.PRIORITY, sortStore.state.value.mode)
        assertEquals(sortStore.state.value, NativeWorkspaceSortStore({ sortJson }, {}).state.value)
        assertEquals(false, sidebarActive.last())
    }
    @Test fun globalSidebarChangesSheetReadsExactMacAndRetainsBrowserThroughDismissalAndRevocation() = wideSidebar {
        fun sheetDesc(label: String): UiObject2 {
            compose.waitUntil(15_000) { device.hasObject(By.desc(label)) }
            return desc(label)
        }
        fun sheetText(label: String) {
            compose.waitUntil(15_000) { device.hasObject(By.text(label)) }
        }
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        desc("Choose terminal or pane"); text("Keep draft").click()
        sheetDesc("Choose terminal or pane")
        until { desc("Choose terminal or pane").text?.startsWith("Draft ") == true }
        val loads = paths.count { it == "/start" }
        desc("Changes: 2 files, +22, −2").click()
        sheetDesc("Changes in Changes workspace B"); sheetDesc("Open diff README.md").click(); sheetText("fresh-B")
        assertEquals(listOf("B:Files", "B:Diff"), changesReads.toList())
        val originalSheet = main {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<RoutedChangesActivity>().single()
        }
        val originalModel = main { androidx.lifecycle.ViewModelProvider(originalSheet)[RoutedChangesModel::class.java] }
        main { originalSheet.recreate() }
        compose.waitUntil(15_000) { main {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<RoutedChangesActivity>().any { it !== originalSheet }
        } }
        sheetText("fresh-B")
        try {
            device.setOrientationLeft(); until { device.displayRotation != 0 }; sheetText("fresh-B")
            main {
                val sheet = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<RoutedChangesActivity>().single()
                assertSame(originalModel, androidx.lifecycle.ViewModelProvider(sheet)[RoutedChangesModel::class.java])
            }
            capturePicker("browser-sidebar-changes-rotated")
            device.setOrientationNatural(); until { device.displayRotation == 0 }; sheetText("fresh-B")
        } finally { device.setOrientationNatural(); device.unfreezeRotation() }
        assertEquals(listOf("B:Files", "B:Diff"), changesReads.toList())
        assertEquals(0, sidebarReleases.get())
        capturePicker("browser-sidebar-changes-diff")
        device.pressBack(); sheetDesc("Close changes").click()
        sheetDesc("Choose terminal or pane")
        until { desc("Choose terminal or pane").text?.startsWith("Draft ") == true }
        assertEquals(loads, paths.count { it == "/start" }); until { sidebarReleases.get() == 1 }
        desc("Changes: 1 file, +11, −1").click(); sheetDesc("Changes in Changes workspace A"); sheetDesc("Open diff README.md")
        main { noticeSources = noticeSources.map { if (it.mac.deviceId == "A") it.copy(capabilities = emptySet()) else it } }
        sheetDesc("Choose terminal or pane")
        until { desc("Choose terminal or pane").text?.startsWith("Draft ") == true }
        until { !device.hasObject(By.desc("Changes: 1 file, +11, −1")) }
        assertEquals(loads, paths.count { it == "/start" }); assertNull(route); assertTrue(sidebarDestinations.isEmpty())
        capturePicker("browser-sidebar-changes-return")
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { sidebarReleases.get() == 3 }; assertEquals(0, holds.get())
    }

    @Test fun globalSidebarSelectionFollowsCapturedOwnerAndLiveAnchorWithoutReload() = wideSidebar {
        fun waitSelection(label: String, expected: Boolean) = until {
            try { selectedRow(label) == expected } catch (_: androidx.test.uiautomator.StaleObjectException) { false }
        }
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        desc("Choose terminal or pane"); text("Keep draft").click()
        until { desc("Choose terminal or pane").text?.startsWith("Draft ") == true }
        waitSelection("Selected on A", true); waitSelection("Selected on B", false)
        val loads = paths.count { it == "/start" }
        capturePicker("browser-sidebar-selected-workspace")
        main { noticeSources = noticeSources.map { source -> source.copy(workspaces = source.workspaces.map { it.copy(groupId = "group") }, groups = listOf(
            NativeGroup("group", "Anchor on ${source.mac.deviceId}", true, false, anchorWorkspaceId = workspace.id))) } }
        waitSelection("Anchor on A", true); waitSelection("Anchor on B", false)
        capturePicker("browser-sidebar-selected-anchor")
        val original = main { noticeSources }
        main { noticeSources = noticeSources.map { it.copy(mac = it.mac.copy(code = "replacement-${it.mac.deviceId}")) } }
        waitSelection("Anchor on A", false); waitSelection("Anchor on B", false)
        desc("Hide sidebar").click(); desc("Show sidebar")
        main { noticeSources = original }
        desc("Show sidebar").click(); waitSelection("Anchor on A", true)
        assertEquals(loads, paths.count { it == "/start" }); assertTrue(desc("Choose terminal or pane").text!!.startsWith("Draft "))
        assertFalse(compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { sidebarReleases.get() == 1 }; assertEquals(0, holds.get())
    }

    @Test fun globalSidebarDisplayPreferencesUpdateWhileParentPausedAndPreserveDraft() = wideSidebar {
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        desc("Choose terminal or pane"); text("Keep draft").click()
        until { desc("Choose terminal or pane").text?.startsWith("Draft ") == true }
        val preferences = context.getSharedPreferences(sidebarDisplayName, Context.MODE_PRIVATE)
        try {
            val loads = paths.count { it == "/start" }
            val titleHeight = text(sidebarDisplayTitle).visibleBounds.height()
            val twoLines = text(sidebarDisplayPreview).visibleBounds.height()
            main { preferences.edit().putBoolean(NativeDisplayPreferences.wrapKey, true).putInt(NativeDisplayPreferences.previewKey, 1).commit() }
            until { text(sidebarDisplayTitle).visibleBounds.height() > titleHeight && text(sidebarDisplayPreview).visibleBounds.height() < twoLines }
            assertFalse(compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            capturePicker("browser-sidebar-display-wrapped")
            desc("Hide sidebar").click(); desc("Show sidebar")
            main { preferences.edit().putBoolean(NativeDisplayPreferences.wrapKey, false).putInt(NativeDisplayPreferences.previewKey, 2).commit() }
            desc("Show sidebar").click()
            until { text(sidebarDisplayTitle).visibleBounds.height() == titleHeight && text(sidebarDisplayPreview).visibleBounds.height() == twoLines }
            assertEquals(loads, paths.count { it == "/start" }); assertTrue(desc("Choose terminal or pane").text!!.startsWith("Draft "))
            capturePicker("browser-sidebar-display-restored")
            device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
            until { sidebarReleases.get() == 1 }; assertEquals(0, holds.get())
        } finally { preferences.edit().clear().commit() }
    }

    @Test fun globalSidebarSshCloseConfirmsPersistentKindsAndClosesShellWithoutLosingDraft() = wideSidebar {
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        desc("Choose terminal or pane"); text("Keep draft")
        text("Keep draft").click(); until { desc("Choose terminal or pane").text?.startsWith("Draft ") == true }
        val loads = paths.count { it == "/start" }
        text("Shell SSH B").longClick(); text("Delete").click()
        until { sshCloseWrites.contains("Shell SSH B") }
        assertFalse(device.hasObject(By.text("Delete Workspace?"))); text("Shell SSH A")
        text("Tmux SSH B").longClick(); text("Delete").click()
        text("End “Tmux SSH B” on SSH B?"); text("Cancel").click()
        assertEquals(listOf("Shell SSH B"), sshCloseWrites.toList())
        text("Tmux SSH B").longClick(); text("Delete").click(); text("End Session").click()
        text("Fixture SSH close rejected"); text("Tmux SSH B")
        text("Tmux SSH B").longClick(); text("Delete").click(); text("End Session").click()
        until { sshCloseWrites.contains("Tmux SSH B") }; text("Tmux SSH A")
        text("Cmux SSH B").longClick(); text("Delete").click()
        text("End “Cmux SSH B” on SSH B?"); text("Close Workspace").click()
        until { sshCloseWrites.contains("Cmux SSH B") }; text("Cmux SSH A")
        assertEquals(listOf("Shell SSH B", "Tmux SSH B", "Cmux SSH B"), sshCloseWrites.toList())
        assertEquals(loads, paths.count { it == "/start" }); assertTrue(desc("Choose terminal or pane").text!!.startsWith("Draft "))
        capturePicker("browser-ssh-close-preserved")
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { sidebarReleases.get() == 1 }; assertEquals(0, holds.get())
    }
    @Test fun globalSidebarSshCloseRejectsReplacedEndpointWhileQueued() = wideSidebar {
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        desc("Choose terminal or pane"); text("Keep draft")
        main { sshCloseHold = true; sshCloseReject = false }
        text("Tmux SSH B").longClick(); text("Delete").click(); text("End Session").click()
        until { sshCloseStarted.isCompleted }
        main {
            sshCloseRows = sshCloseRows.map { if (it.host.name != "SSH B") it else it.copy(host = it.host.copy(endpoint = it.host.endpoint.copy(port = 2222))) }
            sshCloseContinue.complete(Unit)
        }
        text("SSH close target changed"); assertTrue(sshCloseWrites.isEmpty())
        main { sshCloseHold = false }
        text("Tmux SSH B").longClick(); text("Delete").click(); text("End Session").click()
        until { sshCloseWrites.contains("Tmux SSH B") }; text("Tmux SSH A")
        text("Shell SSH A").longClick(); text("Delete")
        main { sshCloseOnline = false }
        until { !device.hasObject(By.text("Delete")) }
        text("Shell SSH A").longClick(); assertFalse(device.hasObject(By.text("Delete")))
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { sidebarReleases.get() == 1 }; assertEquals(0, holds.get())
    }

    @Test fun globalSidebarNewGroupUsesCapturedMacAndPreservesPageThroughRetry() = wideSidebar {
        browser("Routed fixture ▾")
        text("Keep draft").click(); until { desc("Choose terminal or pane").text?.startsWith("Draft ") == true }
        val loads = paths.count { it == "/start" }
        desc("New Workspace").click(); text("New Workspace Group")
        main { groupForeground = "B" }
        until { !generateSequence(text("New Workspace Group")) { it.parent }.first { it.isClickable }.isEnabled }
        text("New Workspace Group").click(); assertTrue(groupAttempts.isEmpty())
        device.pressBack()
        desc("New Workspace").click(); text("New Workspace Group").click()
        text("Fixture group creation rejected")
        assertEquals(listOf("B"), groupAttempts.toList()); assertTrue(createdGroupOwners.isEmpty())
        desc("New Workspace").click(); text("New Workspace Group").click()
        text("New group B")
        assertEquals(listOf("B", "B"), groupAttempts.toList()); assertEquals(listOf("B"), createdGroupOwners.toList())
        assertTrue(main { noticeSources.first().groups.isEmpty() })
        text("All Computers").click(); text("Mac A").click()
        // A single Mac still creates workspaces on tap; the group option is on hold.
        desc("New Workspace").longClick(); text("New Workspace Group").click()
        text("New group A"); assertEquals(listOf("B", "A"), createdGroupOwners.toList())
        assertFalse(device.hasObject(By.text("Name (optional)")))
        assertEquals(loads, paths.count { it == "/start" }); assertTrue(desc("Choose terminal or pane").text!!.startsWith("Draft "))
        capturePicker("browser-new-group-created")
        desc("New Workspace").longClick(); text("New Workspace Group")
        main { noticeSources = noticeSources.map { it.copy(capabilities = emptySet()) } }
        until { !generateSequence(text("New Workspace Group")) { it.parent }.first { it.isClickable }.isEnabled }
        text("New Workspace Group").click(); device.pressBack()
        assertEquals(listOf("B", "B", "A"), groupAttempts.toList())
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { sidebarReleases.get() == 1 }; assertEquals(0, holds.get())
    }
    @Test fun globalSidebarNewGroupCancelsAQueuedCreateWhenComputerScopeChanges() = wideSidebar {
        browser("Routed fixture ▾")
        desc("New Workspace").click(); text("New Workspace Group").click()
        until { newGroupStarted.isCompleted }
        text("All Computers").click(); text("Mac B").click(); text("Mac B")
        main { newGroupContinue.complete(Unit) }
        text("Group creation is no longer active")
        assertEquals(listOf("A"), groupAttempts.toList()); assertTrue(createdGroupOwners.isEmpty())
        main { holdNewGroup = false }
        desc("New Workspace").longClick(); text("New Workspace Group").click(); text("New group B")
        assertEquals(listOf("B"), createdGroupOwners.toList())
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { sidebarReleases.get() == 1 }; assertEquals(0, holds.get())
    }

    @Test fun globalSidebarCreationChoosesMacAndGroupThenUsesTheSingleMacPrimaryAction() = wideSidebar {
        browser("Routed fixture ▾")
        text("Keep draft").click(); until { desc("Choose terminal or pane").text?.startsWith("Draft ") == true }
        val loads = paths.count { it == "/start" }
        desc("New Workspace").click(); text("Mac A"); text("Mac B")
        main { noticeSources = noticeSources.map { if (it.mac.deviceId == "B") it.copy(availability = NativeFeedAvailability.OFFLINE) else it } }
        until { generateSequence(text("Mac B")) { it.parent }.firstOrNull { it.isClickable }?.isEnabled == false }
        text("Mac B").click(); assertTrue(openedCreation.isEmpty())
        device.pressBack()
        assertEquals(loads, paths.count { it == "/start" }); assertTrue(desc("Choose terminal or pane").text!!.startsWith("Draft "))
        main { noticeSources = noticeSources.map { it.copy(availability = NativeFeedAvailability.CONNECTED) } }
        desc("New Workspace").click()
        until { generateSequence(text("Mac B")) { it.parent }.firstOrNull { it.isClickable }?.isEnabled == true }
        assertTrue(device.wait(Until.gone(By.text("Not connected")), 5_000))
        capturePicker("browser-sidebar-create-computers"); text("Mac B").click()
        compose.waitForIdle(); text("Reopen fixture"); until { sidebarReleases.get() == 1 }
        assertEquals(NativeSidebarTarget.CreateWorkspace(main { noticeSources[1].mac }), openedCreation.single())
        text("Reopen fixture").click(); browser("Routed fixture ▾")
        text("Group B").longClick(); text("New Workspace in Group")
        main { noticeSources = noticeSources.map { if (it.mac.deviceId == "B") it.copy(capabilities = emptySet()) else it } }
        assertTrue(device.wait(Until.gone(By.text("New Workspace in Group")), 5_000))
        assertEquals(1, openedCreation.size)
        main { noticeSources = noticeSources.map { it.copy(capabilities = setOf(WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY, "workspace.create_in_group.v1")) } }
        // Selecting the scope waits for a fresh page after capability restoration.
        text("All Computers").click(); text("Mac B").click()
        text("Group B").longClick(); text("New Workspace in Group").click()
        compose.waitForIdle(); text("Reopen fixture"); until { sidebarReleases.get() == 2 }
        assertEquals(NativeSidebarTarget.CreateWorkspace(main { noticeSources[1].mac }, "g"), openedCreation.last())
        main { noticeSources = noticeSources.take(1) }
        text("Reopen fixture").click(); browser("Routed fixture ▾")
        desc("New Workspace").click()
        compose.waitForIdle(); text("Reopen fixture"); until { sidebarReleases.get() == 3 }
        assertEquals(NativeSidebarTarget.CreateWorkspace(main { noticeSources[0].mac }), openedCreation.last())
        assertEquals(3, openedCreation.size); assertEquals(0, holds.get())
    }
    @Test fun globalSidebarCreationOffersSshKindsAndRejectsAChangedHost() = wideSidebar {
        browser("Routed fixture ▾")
        desc("New Workspace").click(); text("SSH fixture").click()
        text("New cmux-tui Workspace"); text("New Shell")
        assertFalse(generateSequence(text("New tmux Session")) { it.parent }.first { it.isClickable }.isEnabled)
        text("tmux is not installed on this computer.")
        capturePicker("browser-sidebar-create-ssh")
        main {
            val target = creationTargets.single()
            val changed = target.host.copy(endpoint = target.host.endpoint.copy(port = 2222))
            target.session.hosts.upsert(changed)
            creationTargets = listOf(target.copy(host = changed))
        }
        until { generateSequence(text("New Shell")) { it.parent }.first { it.isClickable }.isEnabled == false }
        text("New Shell").click(); assertTrue(openedCreation.isEmpty())
        device.pressBack()
        main {
            val target = creationTargets.single()
            creationTargets = listOf(target.copy(host = target.session.hosts.state.value.host(target.host.id)!!))
            noticeSources = emptyList()
        }
        // The empty workspace view proves the new snapshot arrived.
        text("No workspaces yet.")
        // One SSH computer still opens a kind menu, never guesses a default.
        desc("New Workspace").click(); text("New Shell").click()
        compose.waitForIdle(); text("Reopen fixture"); until { sidebarReleases.get() == 1 }
        val opened = openedCreation.single() as NativeSidebarTarget.CreateSsh
        assertEquals(SshWorkspaceKind.SHELL, opened.kind); assertEquals(2222, opened.target.host.endpoint.port)
        assertEquals(0, holds.get())
    }

    @Test fun globalSidebarOpensSharedSettingsComputersAndTaskFlows() = wideSidebar {
        val labels = listOf("cmux settings", "Manage computers", "New Task")
        val expected = listOf(RoutedSidebarActionKind.SETTINGS, RoutedSidebarActionKind.COMPUTERS, RoutedSidebarActionKind.NEW_TASK)
        labels.forEachIndexed { index, label ->
            browser("Routed fixture ▾")
            desc("cmux settings"); desc("Manage computers"); desc("New Task")
            if (index == 0) {
                text("Notifications").click(); desc("Notification filter")
                assertFalse(device.hasObject(By.desc("New Task")))
                desc("cmux settings"); desc("Manage computers")
                text("Workspaces").click(); desc("New Task")
                main { globalActions = globalActions - RoutedSidebarActionKind.NEW_TASK }
                assertTrue(device.wait(Until.gone(By.desc("New Task")), 5_000))
                main { globalActions = globalActions + RoutedSidebarActionKind.NEW_TASK }
                desc("New Task")
                capturePicker("browser-sidebar-global-actions")
            }
            desc(label).click()
            compose.waitForIdle(); text("Reopen fixture")
            until { sidebarReleases.get() == index + 1 }
            assertEquals(expected.take(index + 1), openedActions.toList())
            assertEquals(0, holds.get()); assertEquals(false, sidebarActive.last())
            assertTrue(creationRequests.isEmpty()) // Opening the composer is not workspace creation.
            if (index < labels.lastIndex) text("Reopen fixture").click()
        }
    }

    @Test fun globalSidebarNotificationsShareRowsMutateAndConfirmCapturedScopeWithoutReload() = wideSidebar {
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        val picker = desc("Choose terminal or pane")
        // UiObject2.text refreshes its node from the provider. A cold Android 17
        // accessibility cache can retain the initial Browser title after paint.
        until { picker.text == "Routed fixture ▾" }
        text("Keep draft").click()
        until { picker.text?.startsWith("Draft ") == true }
        val loads = paths.count { it == "/start" }
        text("All Computers").click(); text("Mac A").click()
        text("Latest A"); desc("Show earlier notifications").click(); text("Earlier A")
        capturePicker("browser-sidebar-notification-history")
        text("Latest A").longClick(); text("Mark as Read").click()
        text("Fixture notification update rejected")
        assertEquals(listOf("A:new:true"), noticeWrites.toList())
        text("Latest A").longClick(); text("Mark as Read").click()
        until { main { noticeSources.first().items.first().isRead } }
        // Wait for the authoritative read state to reach the rendered menu.
        text("Latest A").longClick(); text("Mark as Unread"); device.pressBack()
        val preview = text("Latest A")
        val row = generateSequence(preview) { it.parent }.first { it.isClickable }.visibleBounds
        // Begin inside the row: the screen edge belongs to Android's Back gesture.
        device.swipe(row.left + row.width() / 5, row.centerY(), row.right - 20, row.centerY(), 25)
        until { noticeWrites.lastOrNull() == "A:new:false" }
        desc("Search").click()
        checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).text = "not found"
        device.pressEnter(); text("No matches")
        desc("Mark All Read").click(); text("Mark all notifications as read?")
        assertTrue(device.hasObject(By.textContains("for Mac A as read")))
        text("Cancel").click(); assertTrue(noticeBulk.isEmpty())
        desc("Mark All Read").click(); text("Mark All Read").click()
        until { noticeBulk.size == 1 }
        assertEquals(listOf(listOf("A")), noticeBulk.toList())
        assertTrue(main { noticeSources.first().items.all { it.isRead } })
        assertTrue(main { noticeSources.last().items.none { it.isRead } })
        val empty = text("No matches").visibleBounds
        device.swipe(empty.centerX(), empty.bottom + 20, empty.centerX(), empty.bottom + 500, 35)
        until { noticeRefreshes.get() > 0 }
        assertEquals(loads, paths.count { it == "/start" })
        assertEquals(1, holds.get()); assertEquals(0, releases.get())
        assertTrue(picker.text?.startsWith("Draft ") == true)
        capturePicker("browser-sidebar-notification-after-actions")
        // Leave the search editor explicitly; Android Back may otherwise only hide its IME.
        device.findObject(By.desc("Cancel search"))?.click(); text("Workspaces")
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
    }

    @Test fun globalSidebarRestoresExpansionThroughRetentionAndReturnsGroupState() = wideSidebar {
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        val picker = desc("Choose terminal or pane")
        until { picker.text == "Routed fixture ▾" }
        text("Keep draft").click(); until { picker.text?.startsWith("Draft ") == true }
        val loads = paths.count { it == "/start" }
        text("Middle A"); text("Earlier A"); desc("Hide earlier notifications")
        main { noticeSources = noticeSources.map { it.copy(items = it.items.filter { item -> item.id != "old" }) } }
        until { !device.hasObject(By.text("Earlier A")) }
        text("Middle A"); desc("Hide earlier notifications")
        capturePicker("browser-sidebar-retained-expansion")
        desc("Hide earlier notifications").click()
        until { !device.hasObject(By.text("Middle A")) }
        desc("Show earlier notifications").click(); text("Middle A")
        text("Workspaces").click(); desc("Expand Tasks group").click(); desc("Collapse Tasks group")
        text("Notifications (2)").click(); text("Middle A"); desc("Hide earlier notifications")
        assertEquals(loads, paths.count { it == "/start" })
        assertTrue(picker.text?.startsWith("Draft ") == true)
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
        val result = main { checkNotNull(adoptedPresentation) }
        assertEquals(setOf("new", "middle"), result.projection.days.flatMap { it.groups }
            .filter { it.id in result.projection.expanded }.flatMap { it.entries }.map { it.notification.id }.toSet())
        assertEquals(listOf(false), result.collapsedGroups.values.toList())
        text("Reopen fixture").click()
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        text("Middle A"); desc("Hide earlier notifications")
        text("Workspaces").click(); desc("Collapse Tasks group")
        capturePicker("browser-sidebar-restored-workspace-group")
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
    }

    @Test fun globalSidebarWorkspaceAndGroupMutationsPreservePageAndRequireConfirmation() = wideSidebar {
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        val picker = desc("Choose terminal or pane"); until { picker.text == "Routed fixture ▾" }
        text("Keep draft").click(); until { picker.text?.startsWith("Draft ") == true }
        val loads = paths.count { it == "/start" }
        text("Action workspace").longClick(); text("Pin").click()
        until { main { noticeSources.single().workspaces.first { it.id == "w" }.isPinned } }
        text("Action workspace").longClick(); text("Unpin"); text("Rename").click()
        checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)).text = "Renamed workspace"
        text("Save").click(); text("Fixture workspace update rejected"); text("Action workspace")
        text("Action workspace").longClick(); text("Rename").click()
        checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)).text = "Renamed workspace"
        text("Save").click(); text("Renamed workspace")
        text("Renamed workspace").longClick(); text("Mark as Read").click()
        until { main { !noticeSources.single().workspaces.first { it.id == "w" }.hasUnread } }
        text("Renamed workspace").longClick(); text("Mark as Unread"); text("Delete").click()
        text("Delete Workspace?"); text("Cancel").click()
        assertTrue(workspaceWrites.none { it.startsWith("w:close:") })
        text("Renamed workspace").longClick(); text("Delete").click(); text("Delete Workspace?"); text("Delete").click()
        until { !device.hasObject(By.text("Renamed workspace")) }
        text("Action group").longClick(); text("Pin Group").click()
        until { main { noticeSources.single().groups.single().isPinned } }
        text("Action group").longClick(); text("Unpin Group")
        assertFalse(device.hasObject(By.text("Ungroup (Keep Workspaces)")))
        text("Rename Group").click()
        checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)).text = "Renamed group"
        text("Save").click(); text("Renamed group")
        text("Renamed group").longClick(); text("Unpin Group").click()
        until { main { !noticeSources.single().groups.single().isPinned } }
        text("Renamed group").longClick(); text("Ungroup (Keep Workspaces)").click(); text("Ungroup Group?")
        text("Cancel").click(); assertTrue(workspaceWrites.none { it.startsWith("g:ungroup:") })
        text("Renamed group").longClick(); text("Delete Group (Close Workspaces)").click(); text("Delete Group?")
        text("Cancel").click(); assertTrue(workspaceWrites.none { it.startsWith("g:delete:") })
        text("Renamed group").longClick(); text("Ungroup (Keep Workspaces)").click(); text("Ungroup Group?"); text("Ungroup").click()
        until { main { noticeSources.single().groups.isEmpty() } }
        text("Group anchor"); text("Grouped child")
        assertEquals(1, workspaceWrites.count { it.startsWith("w:close:") })
        assertEquals(1, workspaceWrites.count { it.startsWith("g:ungroup:") })
        assertEquals(loads, paths.count { it == "/start" }); assertTrue(picker.text?.startsWith("Draft ") == true)
        capturePicker("browser-sidebar-workspace-actions")
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
    }

    @Test fun globalSidebarDragOrdersGroupsAndKeepsBrowserDraft() = wideSidebar {
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        desc("Choose terminal or pane"); text("Keep draft").click()
        until { desc("Choose terminal or pane").text?.startsWith("Draft ") == true }
        val loads = paths.count { it == "/start" }
        fun drag(from: String, target: String, before: Boolean = false) {
            val start = text(from).visibleBounds; val end = text(target).visibleBounds
            val x = start.centerX().toFloat(); val startY = start.centerY().toFloat()
            val endY = (if (before) end.top else end.centerY()).toFloat()
            val down = android.os.SystemClock.uptimeMillis()
            fun inject(action: Int, y: Float) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            inject(android.view.MotionEvent.ACTION_DOWN, startY)
            try {
                android.os.SystemClock.sleep(700)
                for (step in 1..12) { inject(android.view.MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 12); android.os.SystemClock.sleep(20) }
                android.os.SystemClock.sleep(100)
            } finally { inject(android.view.MotionEvent.ACTION_UP, endY) }
        }
        drag("Move workspace", "Destination group")
        text("Fixture group move rejected"); assertEquals(1, groupMoves.size)
        assertNull(main { noticeSources.single().workspaces.first { it.id == "w" }.groupId })
        drag("Move workspace", "Destination group")
        until { main { noticeSources.single().workspaces.first { it.id == "w" }.groupId == "h" } }
        // Wait for the authoritative projection before starting the next gesture.
        until { text("Move workspace").visibleBounds.top > text("Destination group").visibleBounds.top }
        drag("Destination group", "Original group", before = true)
        until { main { noticeSources.single().workspaces.first().id == "target" } }
        assertEquals(3, groupMoves.size); assertTrue(groupMoves.last().movesGroup)
        assertEquals(loads, paths.count { it == "/start" })
        assertTrue(desc("Choose terminal or pane").text?.startsWith("Draft ") == true)
        capturePicker("browser-sidebar-drag")
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }; assertTrue(sidebarDestinations.isEmpty())
    }

    @Test fun globalSidebarGroupMovesStayAnchoredRejectStaleMenusAndPreserveDraft() = wideSidebar {
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        val picker = desc("Choose terminal or pane"); until { picker.text == "Routed fixture ▾" }
        text("Keep draft").click(); until { picker.text?.startsWith("Draft ") == true }
        val loads = paths.count { it == "/start" }
        fun menu() { text("Move workspace").longClick(); text("Move to Group").click(); desc("Back to workspace actions") }
        menu(); text("Destination group").click()
        text("Fixture group move rejected")
        assertNull(main { noticeSources.single().workspaces.first { it.id == "w" }.groupId })
        assertEquals(1, groupMoves.size)
        menu(); text("Destination group")
        main { noticeSources = noticeSources.map { source -> source.copy(groups = source.groups.map {
            if (it.id == "h") it.copy(isPinned = true) else it
        }) } }
        text("Destination group").click(); text("Group menu changed. Reopen Move to Group.")
        assertEquals(1, groupMoves.size)
        menu(); text("Destination group").click()
        until { main { noticeSources.single().workspaces.first { it.id == "w" }.groupId == "h" } }
        menu(); text("Remove from Group")
        capturePicker("browser-sidebar-group-move-menu")
        // Compose marks the menu item's parent disabled; its text child stays enabled.
        assertTrue(device.findObjects(By.text("Destination group")).any { label ->
            generateSequence(label) { it.parent }.any { !it.isEnabled }
        })
        desc("Back to workspace actions").click(); text("Move to Group").click(); text("Remove from Group").click()
        until { main { noticeSources.single().workspaces.first { it.id == "w" }.groupId == null } }
        assertEquals(3, groupMoves.size)
        assertEquals(listOf("h", "h", null), groupMoves.map { it.groupId })
        assertEquals(loads, paths.count { it == "/start" })
        assertTrue(desc("Choose terminal or pane").text?.startsWith("Draft ") == true)
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
    }

    @Test fun globalSidebarCustomizationRebasesPartialSaveAndPreservesBrowser() = wideSidebar {
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        val picker = desc("Choose terminal or pane"); until { picker.text == "Routed fixture ▾" }
        text("Keep draft").click(); until { picker.text?.startsWith("Draft ") == true }
        val loads = paths.count { it == "/start" }
        text("Custom B").longClick(); text("Customize").click(); text("Customize Workspace")
        checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").text("Custom B")), 5000)).text = "Customized B"
        val description = checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").text(sidebarDescription)), 5000))
        assertTrue(description.text.endsWith("end of Mac description"))
        description.text = "New browser description"
        text("Save").click(); text("Fixture description rejected"); text("OK").click()
        text("Customized B"); text("New browser description")
        capturePicker("browser-sidebar-customization-retry")
        text("Save").click()
        assertTrue(device.wait(Until.gone(By.text("Customize Workspace")), 5000))
        until { main { noticeSources.last().workspaces.single().description == "New browser description" } }
        assertEquals(listOf("B" to WorkspaceCustomizationField.NAME, "B" to WorkspaceCustomizationField.DESCRIPTION,
            "B" to WorkspaceCustomizationField.DESCRIPTION), sidebarCustomizationWrites.toList())
        assertEquals("Custom A", main { noticeSources.first().workspaces.single().title })
        assertEquals(sidebarDescription, main { noticeSources.first().workspaces.single().description })
        text("Customized B").longClick(); text("Customize").click(); text("New browser description")
        checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").text("Customized B")), 5000)).text = "Unsaved edit"
        main { noticeSources = noticeSources.map { if (it.mac.deviceId == "B") it.copy(capabilities = emptySet()) else it } }
        assertTrue(device.wait(Until.gone(By.text("Customize Workspace")), 10_000))
        assertEquals(3, sidebarCustomizationWrites.size)
        assertEquals(loads, paths.count { it == "/start" })
        assertTrue(desc("Choose terminal or pane").text?.startsWith("Draft ") == true)
        capturePicker("browser-sidebar-customization-page")
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture"); until { holds.get() == 0 }
    }

    @Test fun globalSidebarCustomizationRevocationCancelsSaveWithoutReload() = wideSidebar {
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        val picker = desc("Choose terminal or pane"); until { picker.text == "Routed fixture ▾" }
        val loads = paths.count { it == "/start" }
        text("Custom B").longClick(); text("Customize").click(); text("Customize Workspace")
        checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").text("Custom B")), 5000)).text = "Pending save"
        text("Save").click(); until { sidebarCustomizationStarted.isCompleted }; text("Saving…")
        main { noticeSources = noticeSources.map { if (it.mac.deviceId == "B") it.copy(capabilities = emptySet()) else it } }
        until { sidebarCustomizationCancelled.isCompleted }
        assertTrue(device.wait(Until.gone(By.text("Customize Workspace")), 5000))
        assertEquals("Custom B", main { noticeSources.last().workspaces.single().title })
        assertTrue(sidebarCustomizationWrites.isEmpty())
        assertEquals(loads, paths.count { it == "/start" })
        text("Routed fixture ▾")
        device.pressBack(); compose.waitForIdle(); text("Reopen fixture"); until { holds.get() == 0 }
    }

    @Test fun customizationSavesAndRetriesWithoutReloadingThePageOrReleasingItsHost() {
        main { menuCustomizationEnabled = true; failCustomization = true }
        browser("Routed fixture ▾")
        text("Keep draft").click(); text("Draft portrait ▾")
        val loads = paths.count { it == "/start" }
        text("Draft portrait ▾").click(); text("Customize Workspace").click()
        val name = checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").text("Fixture workspace")), 5_000))
        name.text = "Edited from browser"
        text("Save").click()
        text("Fixture save rejected"); text("OK").click()
        text("Edited from browser")
        capturePicker("browser-customization-retry")
        text("Save").click()
        assertTrue(device.wait(Until.gone(By.text("Customize Workspace")), 5_000))
        text("Draft portrait ▾")
        until { main { menuWorkspace.title == "Edited from browser" } }
        assertEquals(listOf("Edited from browser", "Edited from browser"), customizationRequests.map { it.name })
        assertEquals(loads, paths.count { it == "/start" })
        assertEquals(1, holds.get()); assertEquals(0, releases.get())
        capturePicker("browser-customization-page-preserved")
        // The new authoritative values reach the paused parent/child context.
        text("Draft portrait ▾").click(); text("Customize Workspace").click(); text("Edited from browser")
        text("Cancel").click(); text("Draft portrait ▾")
        assertEquals(2, customizationRequests.size)
        desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
    }

    @Test fun editorRemovalCancelsTheBoundServiceSaveWhileTheBrowserStaysOpen() {
        main { menuCustomizationEnabled = true; holdCustomization = true }
        browser("Routed fixture ▾")
        text("Routed fixture ▾").click(); text("Customize Workspace").click()
        checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").text("Fixture workspace")), 5_000)).text = "Pending save"
        text("Save").click()
        until { customizationStarted.isCompleted }
        text("Saving…")
        main { menuCustomizationEnabled = false }
        until { customizationCancelled.isCompleted }
        assertTrue(device.wait(Until.gone(By.text("Customize Workspace")), 5_000))
        text("Routed fixture ▾")
        assertEquals(1, customizationRequests.size)
        assertEquals("Fixture workspace", main { menuWorkspace.title })
        assertEquals(1, holds.get()); assertEquals(0, releases.get())
        desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
    }

    @Test fun liveCapabilityRevocationClosesTheEditorWithoutSubmitting() {
        main { menuCustomizationEnabled = true }
        browser("Routed fixture ▾")
        text("Routed fixture ▾").click(); text("Customize Workspace").click()
        text("Use Workspace Color")
        main { menuCustomizationEnabled = false }
        assertTrue(device.wait(Until.gone(By.text("Customize Workspace")), 5_000))
        text("Routed fixture ▾").click()
        text("New Workspace")
        assertFalse(device.hasObject(By.text("Customize Workspace")))
        assertTrue(customizationRequests.isEmpty())
        main { menuCustomizationEnabled = true }
        text("Customize Workspace").click(); text("Cancel").click()
        desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
    }

    @Test fun pausedParentPublishesRenamedRemovedAndNewPanesWithoutReopeningBrowser() {
        browser("Routed fixture ▾")
        assertFalse(compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        main { menuWorkspace = workspace.copy(terminals = listOf(NativeTerminal("terminal", "Renamed shell"),
            NativeTerminal("new-shell", "Added shell")), browsers = listOf(workspace.browsers.first())) }
        text("Routed fixture ▾").click()
        text("Renamed shell")
        text("Added shell")
        assertFalse(device.hasObject(By.text("Fixture shell")))
        assertFalse(device.hasObject(By.text("Second")))
        text("Added shell").click()
        compose.waitUntil(10_000) { route?.terminalId == "new-shell" }
        assertEquals("generated-mac", route?.origin)
        until { holds.get() == 0 }
    }

    @Test fun pausedParentRevokesAndRestoresCreationWithoutReopeningBrowser() {
        browser("Routed fixture ▾")
        text("Routed fixture ▾").click()
        main { menuCreationEnabled = false }
        fun disabled(node: UiObject2) = generateSequence(node) { it.parent }.any { !it.isEnabled }
        until { disabled(text("New Workspace")) }
        assertTrue(disabled(text("New Terminal")))
        text("New Workspace").click()
        assertTrue(creationRequests.isEmpty())
        main { menuCreationEnabled = true }
        until { !disabled(text("New Workspace")) }
        text("New Workspace").click()
        compose.waitUntil(10_000) { creationRequests == listOf("workspace") }
        until { holds.get() == 0 }
    }

    @Test fun replacementWorkspaceCannotPopulateOrNavigateTheOldBrowserMenu() {
        browser("Routed fixture ▾")
        main { menuWorkspace = workspace.copy(id = "unrelated-workspace") }
        compose.waitUntil(15_000) { navigation.state.value.local == null }
        assertNull(route)
        assertTrue(creationRequests.isEmpty())
        until { holds.get() == 0 }
    }

    @Test fun browserCapabilityUpdatesReachTheLiveSeparateProcessMenu() {
        browser("Routed fixture ▾")
        main { browserState = NativeBrowserPickerState(known = false, streaming = false) }
        text("Routed fixture ▾").click()
        text("Mac Surfaces")
        assertFalse(device.hasObject(By.text(NativeBrowserPickerState.UPDATE_HINT)))
        main { browserState = NativeBrowserPickerState(known = true, streaming = false) }
        val hint = text(NativeBrowserPickerState.UPDATE_HINT)
        capturePicker("legacy-browser-picker")
        // Compose exposes Text as an enabled child under the disabled menu item.
        fun disabled(node: UiObject2) = generateSequence(node) { it.parent }.any { !it.isEnabled }
        assertTrue(disabled(hint))
        device.pressBack()
        desc("Browser mode").click()
        assertTrue(disabled(text("Streamed")))
        device.pressBack()
        text("Routed fixture ▾").click()
        main { browserState = NativeBrowserPickerState(known = false, streaming = false) }
        assertTrue(device.wait(Until.gone(By.text(NativeBrowserPickerState.UPDATE_HINT)), 5000))
        main { browserState = NativeBrowserPickerState(known = true, streaming = true) }
        text("Mac Browsers")
        assertTrue(device.wait(Until.gone(By.text("Mac Surfaces")), 5000))
        text("First").click()
        compose.waitUntil(10_000) { route?.browserId == "first" }
        assertEquals("generated-mac", route?.origin)
    }

    @After fun cleanup() {
        if (::network.isInitialized) main { network.close(); navigation.clear() }
        if (::network.isInitialized) runBlocking { delay(300) }
        main { creationSsh?.close() }
        owner.cancel()
        creationVault?.deleteRecursively()
        if (::server.isInitialized) server.shutdown()
    }
    @Test fun browserCopiesMainAndBrowserDiagnosticsThroughTheBoundService() {
        browser("Routed fixture ▾")
        MobileDebugLog.finish(MobileDebugLog.begin(DebugOperation.RPC_HOST), DebugOutcome.SUCCESS)
        text("Routed fixture ▾").click()
        text("Copy Debug Logs").click()
        assertTrue(device.wait(Until.gone(By.text("Copy Debug Logs")), 5000))
        // Return focus to the main process before reading Android's clipboard.
        desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        val copied = main { context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString() }
        assertTrue(copied, copied.contains("RPC_HOST SUCCESS"))
        assertTrue(copied, copied.contains("Browser process"))
        assertTrue(copied, copied.contains("BROWSER_PREPARE SUCCESS"))
        assertTrue(copied, copied.contains("Process ${Process.myPid()}"))
        assertFalse(copied.contains("http://"))
        assertFalse(copied.contains("generated-account"))
        val archive = runBlocking { checkNotNull(MobileDiagnostics.recorder).export() }
        try {
            val recent = java.util.zip.ZipFile(archive).use { zip ->
                zip.entries().asSequence().flatMap { entry -> zip.getInputStream(entry).bufferedReader().use { it.readLines() }.asSequence() }
                    .filter { line -> runCatching { java.time.Instant.parse(line.substringBefore(' ')).toEpochMilli() >= beganAt }.getOrDefault(false) }.toList()
            }
            assertTrue(recent.toString(), recent.any { it.contains("APP RPC_HOST SUCCESS") })
            assertTrue(recent.toString(), recent.any { it.contains("BROWSER BROWSER_PREPARE SUCCESS") })
        } finally { archive.delete() }
        until { holds.get() == 0 }
    }
    @Test fun clearingMainDiagnosticsAlsoRetiresBrowserClipboardHistory() {
        browser("Routed fixture ▾")
        MobileDebugLog.finish(MobileDebugLog.begin(DebugOperation.RPC_HOST), DebugOutcome.SUCCESS)
        runBlocking { checkNotNull(MobileDiagnostics.recorder).clear() }
        MobileDebugLog.finish(MobileDebugLog.begin(DebugOperation.RPC_WORKSPACE), DebugOutcome.SUCCESS)
        text("Routed fixture ▾").click(); text("Copy Debug Logs").click()
        assertTrue(device.wait(Until.gone(By.text("Copy Debug Logs")), 5000))
        desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        val copied = main { context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString() }
        assertTrue(copied, copied.contains("RPC_WORKSPACE SUCCESS"))
        assertFalse(copied, copied.contains("RPC_HOST"))
        assertFalse(copied, copied.contains("BROWSER_PREPARE"))
        until { holds.get() == 0 }
    }
    @Test fun productionBrowserKeepsHostAndReturnsCommittedPageThenSelectsPane() {
        browser("Routed fixture ▾")
        assertEquals(1, holds.get())
        assertTrue(targets.contains("localhost:34876"))
        text("Open next page").click(); text("Next ▾")
        until { main { surface.state.value.url?.endsWith("/next") == true } }
        assertFalse(compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        val shots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(shots, "routed-browser-production.png")))
        desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
        assertEquals(1, releases.get()); assertTrue(probes.contains(true)); assertEquals(false, probes.last())
        assertFalse(main { surface.state.value.closed })
        compose.onNodeWithText("Reopen fixture").performClick(); browser("Next ▾")
        assertEquals(1, holds.get())
        text("Next ▾").click(); text("Fixture shell").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
        assertEquals("terminal", main { route?.terminalId })
        assertTrue(main { surface.state.value.closed })
        assertEquals(2, releases.get())
    }
    @Test fun macRoutedModeSwitchReturnsLinkedPanelAndForgetsItsPhonePage() {
        browser("Routed fixture ▾")
        desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
        main { navigation.openOnDevice(key, workspace, "second", "http://localhost:34876/next") }
        browser("Next ▾")
        assertTrue(main { navigation.prefersOnDevice(key, "second") })
        text("Next ▾").click()
        capturePicker("on-device-linked-picker"); assertTrue(selectedRow("Second"))
        assertFalse(selectedRow("First"))
        assertFalse(selectedRow("New Browser"))
        text("Mac Browsers")
        device.pressBack()
        desc("Browser mode").click(); text("Streamed").click()
        compose.waitUntil(15000) { route != null }
        assertEquals("second", main { route?.browserId })
        assertFalse(main { navigation.prefersOnDevice(key, "second") })
        assertNull(main { navigation.state.value.local })
        until { holds.get() == 0 }
    }
    @Test fun routedPickerReturnsCreationActionAndReleasesItsHost() {
        browser("Routed fixture ▾")
        text("Routed fixture ▾").click()
        capturePicker("on-device-local-picker"); assertTrue(selectedRow("New Browser"))
        text("New Terminal").click(); compose.waitForIdle()
        text("Reopen fixture")
        until { holds.get() == 0 }
        assertEquals(listOf("terminal"), creationRequests.toList())
        assertNull(main { route })
        assertTrue(main { surface.state.value.closed })
    }
    @Test fun linkedOnDevicePickerCanRequestANewBrowser() {
        browser("Routed fixture ▾"); desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
        main { navigation.openOnDevice(key, workspace, "second", "http://localhost:34876/next") }
        browser("Next ▾"); text("Next ▾").click()
        capturePicker("on-device-linked-picker"); assertTrue(selectedRow("Second"))
        text("New Browser").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
        assertEquals(listOf("browser"), creationRequests.toList())
    }
    @Test fun retiredOwnerClosesPresentationReleasesHostAndRemovesOnlyItsStorage() {
        browser("Routed fixture ▾")
        val storage = File(context.applicationInfo.dataDir, "app_webview_cmux_browser_${network.storageId}")
        until { storage.isDirectory }
        main { network.close() }
        compose.waitForIdle()
        text("Reopen fixture")
        until { holds.get() == 0 && !storage.exists() }
        assertEquals(1, releases.get())
        assertEquals(false, probes.last())
        assertTrue(network.retired.isCompleted)
    }
    private fun lateReturn(action: () -> Unit) {
        browser("Routed fixture ▾")
        val replacement = main {
            // Hold the old composition, as during a pending frame, while the
            // navigation owner has already installed another account/workspace.
            frozenDestination = navigation.state.value.local
            val nextKey = key.copy(accountId = "replacement-account", computerId = "replacement-computer", workspaceId = "replacement-workspace")
            navigation.restoreRemembered(nextKey, workspace.copy(id = nextKey.workspaceId))
            checkNotNull(navigation.state.value.local)
        }
        try {
            action()
            compose.waitUntil(15000) { compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            until { holds.get() == 0 }
            assertSame(replacement, main { navigation.state.value.local })
            assertFalse(main { replacement.surface.state.value.closed })
            assertNull(main { route }); assertTrue(creationRequests.isEmpty())
            assertEquals(1, releases.get())
        } finally { main { navigation.clear(); frozenDestination = null } }
    }
    @Test fun latePaneReturnCannotNavigateOrCloseReplacementDestination() = lateReturn {
        text("Routed fixture ▾").click(); text("Fixture shell").click()
    }
    @Test fun lateCreationReturnCannotCreateOrCloseReplacementDestination() = lateReturn {
        text("Routed fixture ▾").click(); text("New Terminal").click()
    }
    @Test fun lateBackReturnCannotCloseReplacementDestination() = lateReturn {
        desc("Back to workspaces").click()
    }
    @Test fun rotationKeepsUnsubmittedPageStateHistoryAndHostLease() {
        device.setOrientationNatural()
        try {
            browser("Routed fixture ▾")
            text("Open next page").click(); text("Next ▾")
            text("Keep draft").click(); text("Draft portrait ▾")
            val loaded = paths.count { it == "/next" }
            assertEquals(1, loaded)
            device.setOrientationLeft()
            until { device.displayRotation != 0 }
            text("Draft landscape ▾")
            val shots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(shots, "routed-browser-landscape.png")))
            assertEquals(loaded, paths.count { it == "/next" })
            assertEquals(1, holds.get()); assertEquals(0, releases.get())
            device.setOrientationNatural()
            until { device.displayRotation == 0 }
            text("Draft portrait ▾")
            assertEquals(loaded, paths.count { it == "/next" })
            desc("Browser Back").click(); text("Routed fixture ▾")
            desc("Browser Forward").click(); text("Next ▾")
            desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
            until { holds.get() == 0 }
            assertEquals(1, releases.get())
        } finally { device.setOrientationNatural(); device.unfreezeRotation() }
    }
    @Test fun addressEntryCommitsTheEditedUrl() {
        browser("Routed fixture ▾")
        // Compose exposes the description on a child of the actual editable node.
        val selector = By.clazz("android.widget.EditText").hasDescendant(By.desc("Browser address"))
        val address = checkNotNull(device.wait(Until.findObject(selector), 5_000))
        address.click()
        val focused = device.wait(Until.hasObject(By.copy(selector).focused(true)), 5_000)
        val shots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        device.dumpWindowHierarchy(File(shots, "routed-address-focused.xml"))
        assertTrue("Address field receives focus", focused)
        address.text = "http://localhost:34876/form"
        val changed = device.wait(Until.hasObject(By.copy(selector).text("http://localhost:34876/form")), 5_000)
        device.dumpWindowHierarchy(File(shots, "routed-address-edited.xml"))
        assertTrue("Edited URL reaches the field before submission", changed)
        device.pressEnter(); text("Upload form ▾")
        until { main { surface.state.value.url?.endsWith("/form") == true } }
        assertEquals(1, paths.count { it == "/form" })
        desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
    }
    @Test fun systemFilePickerCanCancelReopenAndUploadThroughTheRoutedProcess() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        val filename = "cmux-routed-${UUID.randomUUID()}.txt"
        val contents = "Routed browser upload fixture — 中 🚀\nSecond line.\n"
        val resolver = context.contentResolver
        val document = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        }))
        try {
            resolver.openOutputStream(document)!!.use { it.write(contents.toByteArray()) }
            browser("Routed fixture ▾")
            text("Open upload form").click(); text("Upload form ▾")
            fun picker() {
                text("Choose upload file").click()
                assertTrue("System document picker", device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 5_000) ||
                    device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 5_000))
                until { probes.lastOrNull() == false }
                assertEquals(1, holds.get()); assertEquals(0, releases.get())
            }
            picker()
            device.pressBack(); text("Upload form ▾")
            until { probes.lastOrNull() == true }
            assertTrue(uploads.isEmpty())
            picker()
            var row = device.wait(Until.findObject(By.text(filename)), 1_500)
            if (row == null) {
                desc("Show roots").click(); text("Downloads").click()
                row = device.wait(Until.findObject(By.text(filename)), 5_000)
            }
            checkNotNull(row) { "Generated upload file missing" }.click()
            text("Selected file ▾")
            until { probes.lastOrNull() == true }
            text("Upload selected file").click(); text("Upload received ▾")
            val request = uploads.single()
            assertEquals("POST", request.method)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
            val body = request.body.clone().readUtf8()
            assertTrue(body.contains("filename=\"$filename\""))
            val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
            assertEquals(contents, body.substringAfter("\r\n\r\n").substringBeforeLast("\r\n--$boundary"))
            assertTrue(targets.contains("localhost:34876"))
            assertEquals(1, holds.get()); assertEquals(0, releases.get())
            val shots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(shots, "routed-browser-uploaded.png")))
            desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
            until { holds.get() == 0 }
            assertEquals(1, releases.get())
        } finally { resolver.delete(document, null, null) }
    }
    @Test fun browserProcessDeathReturnsAndReopensCommittedPageWithItsOwnCookies() {
        fun browserPid(): Int? = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .runningAppProcesses.orEmpty().singleOrNull {
                it.uid == Process.myUid() && it.processName == context.packageName + RoutedBrowserEnvironment.PROCESS
            }?.pid
        browser("Routed fixture ▾")
        text("Open next page").click(); text("Next ▾")
        until { main { surface.state.value.url?.endsWith("/next") == true && !surface.state.value.loading } }
        assertTrue(visits.last { it.path == "/next" }.getHeader("Cookie").orEmpty().contains("presentation=kept"))
        val killed = checkNotNull(browserPid())
        assertNotEquals(Process.myPid(), killed)
        assertEquals(1, holds.get())
        Process.killProcess(killed)
        compose.waitUntil(15_000) { navigation.state.value.local == null }
        compose.waitForIdle()
        text("Reopen fixture")
        until { holds.get() == 0 && browserPid() == null }
        assertEquals(1, releases.get()); assertEquals(false, probes.last())
        assertFalse(main { surface.state.value.closed })
        val before = visits.count { it.path == "/next" }
        compose.onNodeWithText("Reopen fixture").performClick(); browser("Next ▾")
        assertNotEquals(killed, checkNotNull(browserPid()))
        until { visits.count { it.path == "/next" } > before }
        assertTrue(visits.last { it.path == "/next" }.getHeader("Cookie").orEmpty().contains("presentation=kept"))
        assertEquals(1, holds.get())
        desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
        assertEquals(2, releases.get())
    }
}
