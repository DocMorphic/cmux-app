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
import java.io.File
import java.util.UUID

/** Debug-only isolated owner: real account UI/storage/HTTP, exclusively loopback fixtures. */
class NativeAccountProcessTestActivity : ComponentActivity() {
    private lateinit var teams: NativeAccountTeams
    override fun onCreate(savedInstanceState: Bundle?) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        super.onCreate(savedInstanceState)
        val id = checkNotNull(intent.getStringExtra("fixtureId"))
        check(UUID.fromString(id).toString() == id)
        val port = intent.getIntExtra("fixturePort", 0); check(port in 1024..65535)
        val origin = "http://127.0.0.1:$port/".toHttpUrl()
        val store = NativeCredentialStore(this, "account-process-$id")
        val seed = File(filesDir, "account-process-$id-seeded")
        if (!seed.exists()) {
            store.update { it.put("task_session", "fixture-login").put("refresh_token", "fixture-refresh") }
            seed.writeText("seeded")
        }
        val account = NativeAccount(store)
        teams = NativeAccountTeams({ "fixture-access" }, store::taskSession, origin.resolve("api/v1/")!!,
            cache = NativeAccountProfileCache(store::load, store::update))
        val deletion = NativeAccountDeletionController(lifecycleScope, store::load, store::update) { login ->
            NativeAccountDeletionClient({ NativeDeletionCredentials("fixture-access", "fixture-refresh") },
                { store.taskSession() == login }, origin).delete()
        }
        setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            val revision by store.revisions.collectAsState()
            val state by teams.state.collectAsState()
            val receipt by deletion.state.collectAsState()
            val login = remember(revision) { store.taskSession() }
            Column(Modifier.statusBarsPadding()) {
                Text(if (login == null) "Fixture signed out" else "Fixture signed in")
                Text(if (state.scope == null) "No live team authority" else "Verified team authority")
                NativeAccountTeamSection(state, onRefresh = {
                    lifecycleScope.launch { runCatching { teams.refresh() } }
                }, onSelect = { id -> lifecycleScope.launch { runCatching { teams.select(id) } } },
                    onCreate = { error("Not used by this fixture") })
                NativeAccountDeletionButton(login, receipt, deletion::begin)
                if (intent.getBooleanExtra("deferOutcome", false)) Text("Receipt: ${receipt?.result?.name ?: "none"}")
            }
            // Defer presentation only to test death after durable completion but before UI acknowledgement.
            if (!intent.getBooleanExtra("deferOutcome", false)) NativeAccountDeletionAlerts(receipt,
                onSignOut = { owner -> account.signOut(owner).also { if (it) { teams.reconcileLogin(); deletion.reconcile() } } },
                onAcknowledge = deletion::acknowledge)
        } } }
        File(filesDir, "account-process-$id-pid").writeText(Process.myPid().toString())
    }
    override fun onDestroy() { if (::teams.isInitialized) teams.close(); super.onDestroy() }
}
