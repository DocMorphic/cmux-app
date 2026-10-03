package io.github.docmorphic.cmuxapp

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class NativeMacConnectionMethod { IROH, TAILSCALE, DIRECT }
internal data class NativeDirectAddress(val address: String, val label: String? = null, val enabled: Boolean = true)
internal data class NativeMacConnectionPreference(
    val method: NativeMacConnectionMethod = NativeMacConnectionMethod.IROH,
    val addresses: List<NativeDirectAddress> = emptyList()
) {
    fun coordinates() = if (method == NativeMacConnectionMethod.DIRECT) addresses.filter { it.enabled }.map { it.address } else emptyList()
}

/** A captured routing choice, including an epoch so A→B→A cannot revive an old lease. */
internal data class NativeMacDialIntent(val method: NativeMacConnectionMethod = NativeMacConnectionMethod.IROH,
    val addresses: List<String> = emptyList(), val revision: Long = 0, val recovery: Long = 0,
    val tailscale: List<TailscaleSavedGrant> = emptyList()) {
    val dialable get() = when (method) {
        NativeMacConnectionMethod.IROH -> true
        NativeMacConnectionMethod.DIRECT -> addresses.isNotEmpty()
        NativeMacConnectionMethod.TAILSCALE -> tailscale.isNotEmpty()
    }
    fun key() = JSONArray(listOf(method.name, JSONArray(addresses), revision, recovery,
        JSONArray(tailscale.map { JSONArray(listOf(it.id, it.route.host, it.route.port)) }))).toString()
    override fun toString() = "NativeMacDialIntent(method=$method, addressCount=${addresses.size})"
}

internal data class NativeMacConnectionPreferences(
    val values: Map<NativeMacIdentity, NativeMacConnectionPreference> = emptyMap(),
    val revisions: Map<NativeMacIdentity, Long> = emptyMap(), val recovery: Long = 0, val error: Boolean = false
) {
    fun get(target: NativeComputerTarget) = values[identity(target.deviceId, target.buildTag)] ?: NativeMacConnectionPreference()
    fun intent(mac: IrohV2Computer): NativeMacDialIntent {
        check(!error) { "Could not read this phone’s connection settings. Reopen Computer Details and retry." }
        val id = identity(mac.deviceId, mac.buildTag)
        val preference = values[id] ?: NativeMacConnectionPreference()
        return NativeMacDialIntent(preference.method, preference.coordinates(), revisions[id] ?: 0, recovery)
    }
    companion object {
        fun identity(device: String, build: String) = NativeMacIdentity(canonicalMacDeviceId(device), build)
    }
}

/** Local per-account/team settings. Read failures fail closed instead of silently enabling relays. */
internal class NativeMacConnectionStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val mutableState = MutableStateFlow(NativeMacConnectionPreferences())
    val state = mutableState.asStateFlow()
    private var revision = 0L
    init { reload() }

    fun reload() = synchronized(lock) {
        try { publish(load()) }
        catch (_: Exception) { mutableState.value = state.value.copy(error = true, recovery = ++revision) }
    }

    fun update(target: NativeComputerTarget, permits: () -> Boolean,
        change: (NativeMacConnectionPreference) -> NativeMacConnectionPreference) = synchronized(lock) {
        check(permits()) { "Account or team changed. Reopen Computer Details." }
        val id = NativeMacConnectionPreferences.identity(target.deviceId, target.buildTag)
        validateIdentity(id)
        val values = load().toMutableMap()
        val next = validate(change(values[id] ?: NativeMacConnectionPreference()))
        if (next == NativeMacConnectionPreference()) values.remove(id) else values[id] = next
        require(values.size <= 128)
        check(permits()) { "Account or team changed. Reopen Computer Details." }
        save(values)
    }

    fun removeComputer(target: NativeComputerTarget, permits: () -> Boolean) = update(target, permits) { NativeMacConnectionPreference() }

    private fun save(values: Map<NativeMacIdentity, NativeMacConnectionPreference>) {
        val json = JSONArray(values.map { (id, pref) -> JSONObject().put("deviceId", id.deviceId)
            .put("buildTag", id.buildTag).put("method", pref.method.name)
            .put("addresses", JSONArray(pref.addresses.map { entry -> JSONObject().put("address", entry.address)
                .put("label", entry.label ?: JSONObject.NULL).put("enabled", entry.enabled) })) })
        write(json.toString())
        publish(values)
    }

    private fun publish(values: Map<NativeMacIdentity, NativeMacConnectionPreference>) {
        val previous = state.value
        val epochs = previous.revisions.toMutableMap()
        for (id in previous.values.keys + values.keys) {
            val before = previous.values[id] ?: NativeMacConnectionPreference()
            val after = values[id] ?: NativeMacConnectionPreference()
            if (before.method != after.method || before.coordinates() != after.coordinates()) epochs[id] = ++revision
        }
        mutableState.value = NativeMacConnectionPreferences(values, epochs,
            if (previous.error) ++revision else previous.recovery)
    }

    private fun load(): Map<NativeMacIdentity, NativeMacConnectionPreference> {
        val array = JSONArray(read() ?: "[]")
        require(array.length() <= 128)
        return buildMap {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = NativeMacConnectionPreferences.identity(item.get("deviceId") as String, item.get("buildTag") as String)
                validateIdentity(id); require(id !in this)
                val method = NativeMacConnectionMethod.valueOf(item.get("method") as String)
                val entries = item.getJSONArray("addresses")
                require(entries.length() <= 16)
                put(id, validate(NativeMacConnectionPreference(method, (0 until entries.length()).map {
                    val entry = entries.getJSONObject(it)
                    NativeDirectAddress(entry.get("address") as String,
                        if (entry.isNull("label")) null else entry.get("label") as String, entry.get("enabled") as Boolean)
                })))
            }
        }
    }

    companion object {
        private val lock = Any()
        private val stores = mutableMapOf<String, NativeMacConnectionStore>()
        private fun validateIdentity(id: NativeMacIdentity) {
            require(id.deviceId.isNotBlank() && id.deviceId.length <= 128 && !id.buildTag.isNullOrBlank() && id.buildTag.length <= 64)
        }
        private fun validate(preference: NativeMacConnectionPreference): NativeMacConnectionPreference {
            require(preference.addresses.size <= 16)
            val entries = preference.addresses.map { entry ->
                val label = entry.label?.trim()?.takeIf { it.isNotEmpty() }
                require(label == null || (label.length <= 80 && label.none { it.isISOControl() }))
                entry.copy(address = NativePrivateAddress.parse(entry.address), label = label)
            }
            require(entries.map { it.address }.distinct().size == entries.size) { "This address is already saved" }
            return preference.copy(addresses = entries)
        }
        fun create(context: Context, team: NativeTeamScope): NativeMacConnectionStore = synchronized(lock) {
            val root = File(context.noBackupFilesDir, "computer-connections")
            val file = AtomicFile(File(root, NativeMacAppearanceStore.scopeFile(context.packageName, NativeAccount.PROJECT_ID, team.userId, team.teamId)))
            stores.getOrPut(file.baseFile.absolutePath) {
                NativeMacConnectionStore(read = {
                    if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) file.openRead().use {
                        require(it.channel.size() <= 1024 * 1024); it.readBytes().decodeToString()
                    } else null
                }, write = { text ->
                    check(root.isDirectory || root.mkdirs())
                    val output = file.startWrite()
                    try { output.write(text.toByteArray()); file.finishWrite(output) }
                    catch (failure: Throwable) { file.failWrite(output); throw failure }
                })
            }
        }
    }
}
