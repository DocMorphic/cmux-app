package io.github.docmorphic.cmuxapp

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Debug-only UI process: real account/cache/picker, generated records and loopback HTTP. */
class NativeCachedComputerProcessActivity : ComponentActivity() {
    private lateinit var teams: NativeAccountTeams
    override fun onCreate(savedInstanceState: Bundle?) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        super.onCreate(savedInstanceState)
        val id = checkNotNull(intent.getStringExtra("fixtureId"))
        check(UUID.fromString(id).toString() == id)
        val port = intent.getIntExtra("fixturePort", 0); check(port in 1024..65535)
        val origin = "http://127.0.0.1:$port/api/v1/".toHttpUrl()
        val store = NativeCredentialStore(this, "cached-process-$id")
        val seed = File(filesDir, "cached-process-$id-seeded")
        val owner = NativeTeamScope("fixture-login-$id", "fixture-user-$id", "two", 1)
        if (!seed.exists()) {
            fun row(device: String, name: String, team: NativeTeamScope): NativeCredentialStore.PairedMac {
                val computer = IrohV2Computer("record-$device", "ab".repeat(32), device, "default", name, emptyList())
                return NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
                    PairingCodeParser.computer(computer, team), device, name, "default"), team)
            }
            val studio = row("studio-$id", "Studio", owner)
            val hidden = row("hidden-$id", "Hidden Studio", owner)
            val other = row("other-$id", "Other Team Mac", owner.copy(teamId = "one"))
            store.update {
                it.put("task_session", owner.login).put("refresh_token", "fixture-refresh")
                    .put("pairings", JSONArray(listOf(studio, hidden, other).map(NativePairingRecords::encode)))
                NativeComputerVisibility.setVisible(it, owner.login, hidden, false) { true }
                NativeMacVersionHistory.record(it, owner, mapOf(NativeMacIdentity(studio.deviceId, "default") to "0.64.24")) { true }
            }
            NativeMacAppearanceStore.create(this, owner).update(NativeMacIdentity(studio.deviceId, "default"), { true }) {
                it.copy(name = "Offline Studio", icon = "💻")
            }
            seed.writeText("seeded")
        }
        teams = NativeAccountTeams({ "fixture-access" }, store::taskSession, origin,
            cache = NativeAccountProfileCache(store::load, store::update), lock = store.accountStateLock)
        setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            val account by teams.state.collectAsState()
            val revision by store.revisions.collectAsState()
            val snapshot = remember(account, revision) { store.load() }
            val cached = NativeCachedComputers.project(snapshot, store.taskSession(), account,
                "$origin#${NativeAccount.PROJECT_ID}")
            val grants = remember(snapshot) { TailscaleGrantStore({ snapshot }, { error("Read-only fixture") }) }
            val rows = cached?.macs ?: NativeComputerVisibility.saved(snapshot).filter { row ->
                account.scope?.let { teams.isCurrent(it) && NativePairingRecords.usable(row, it, grants) } == true
            }
            val hidden = NativeComputerVisibility.hiddenOrigins(snapshot)
            val appearances = cached?.owner?.let { NativeMacAppearanceStore.display(this, it).collectAsState().value }
                ?: nativeMacAppearances(account.scope)
            var selections by remember { mutableIntStateOf(0) }
            var visibilityChanges by remember { mutableIntStateOf(0) }
            Column(Modifier.safeDrawingPadding()) {
                Text(if (account.scope == null) "No live team authority" else "Verified team authority")
                Text("Selection callbacks: $selections")
                Text("Visibility callbacks: $visibilityChanges")
                Text("Stored pairings: ${NativeComputerVisibility.saved(snapshot).size}")
                CompositionLocalProvider(LocalMacCompatibilityWarnings provides
                    (cached?.warnings(NativeMacCompatibilityPolicy.baked) ?: emptyMap())) {
                    NativeComputerPicker(account, NativeComputersState(account.scope),
                        saved = rows.filterNot { NativeComputerVisibility.isHidden(hidden, it) },
                        hidden = rows.filter { NativeComputerVisibility.isHidden(hidden, it) },
                        cachedDisplay = cached != null, displayAppearances = appearances,
                        onVisibility = { _, _ -> visibilityChanges++ },
                        canSelectSaved = { row -> account.scope?.let {
                            teams.isCurrent(it) && NativePairingRecords.usable(row, it, grants)
                        } == true }, canSelectDiscovered = { false },
                        onSelectSaved = { selections++ }, onSelect = {}, onSettings = {},
                        onRefresh = { lifecycleScope.launch { runCatching { teams.refresh() } } },
                        onPairing = {}, onNewTask = {}, onUseHelper = {}, onLicenses = {}, onError = {})
                }
            }
        } } }
        File(filesDir, "cached-process-$id-pid").writeText(Process.myPid().toString())
    }
    override fun onDestroy() { if (::teams.isInitialized) teams.close(); super.onDestroy() }
}
