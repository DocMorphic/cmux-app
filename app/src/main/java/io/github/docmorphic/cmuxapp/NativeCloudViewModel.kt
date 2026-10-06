package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File

/** Activity-independent machine management. Opening Cloud activates reads; construction does not enroll a tunnel. */
internal class NativeCloudViewModel(context: Context, account: NativeAccount,
    store: NativeCredentialStore, private val teams: NativeAccountTeams) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<CloudMachinesController?>(null)
    val controller = mutable.asStateFlow()
    private var owner: NativeTeamScope? = null
    private var activated = false
    private var foreground = false
    init {
        val root = File(context.applicationContext.noBackupFilesDir, "cloud-creates")
        scope.launch {
            combine(teams.state, store.revisions) { state, _ -> state.scope?.takeIf(teams::isCurrent) }.collect { next ->
                if (next == owner) return@collect
                mutable.value?.close(); mutable.value = null; owner = next
                if (next != null) {
                    val capture = CloudAccountScope(next.login, next.userId, next.teamId, next.generation)
                    val controller = CloudMachinesController(scope, nativeCloudApi(account, teams, next),
                        CloudCreateFileJournal(root, capture), { teams.isCurrent(next) })
                    controller.setForeground(foreground)
                    mutable.value = controller
                    if (activated && foreground) controller.refresh()
                }
            }
        }
    }
    fun activate() {
        activated = true
        mutable.value?.takeIf { foreground && it.state.value.phase == CloudCatalogPhase.IDLE }?.refresh()
    }
    fun setForeground(value: Boolean) {
        foreground = value
        mutable.value?.setForeground(value)
        if (activated && value) activate()
    }
    override fun onCleared() { mutable.value?.close(); mutable.value = null; scope.cancel() }
    class Factory(private val context: Context, private val account: NativeAccount,
        private val store: NativeCredentialStore, private val teams: NativeAccountTeams) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeCloudViewModel::class.java)
            @Suppress("UNCHECKED_CAST") return NativeCloudViewModel(context, account, store, teams) as T
        }
    }
}
