package io.github.docmorphic.cmuxapp

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class NativeMacIdentity(val deviceId: String, val buildTag: String?)
internal data class NativeMacAppearance(val name: String? = null, val color: String? = null, val icon: String? = null) {
    fun displayName(fallback: String) = name ?: fallback.ifBlank { "Mac" }
    companion object {
        val symbols = listOf("desktopcomputer", "macbook", "laptopcomputer", "server.rack", "terminal", "display",
            "bolt.fill", "star.fill", "heart.fill", "flame.fill")
        val emojis = listOf("💻", "🖥️", "⚡️", "🔥", "⭐️", "🚀", "🐧", "🍎", "🎮", "👾")
        val symbolLabels = symbols.zip(listOf("Desktop computer", "MacBook", "Laptop", "Server", "Terminal", "Display",
            "Lightning", "Star", "Heart", "Flame")).toMap()
        fun name(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.length <= 256 && it.none(Char::isISOControl)) { "Use a name of up to 256 characters." }
        }
        fun color(value: String?): String? = value?.let {
            require(it in (0..7).map { n -> "palette:$n" } || Regex("#[0-9a-fA-F]{6}").matches(it)) {
                "Use a color in #RRGGBB format."
            }
            it.uppercase().replace("PALETTE:", "palette:")
        }
        fun icon(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it in symbols || (it.length <= 64 && it.any { c -> c.code > 127 } && it.none(Char::isISOControl))) {
                "Choose an icon or enter an emoji (up to 64 characters)."
            }
        }
        /** Same wrapping djb2 scalar hash as MachineAvatarPalette; callers prefer distinct assigned slots. */
        fun paletteSlot(machineId: String): Int {
            var hash = 5381L
            machineId.codePoints().forEach { hash = hash * 33 + it }
            return (((hash % 8) + 8) % 8).toInt()
        }
    }
}

internal data class NativeMacAppearances(val values: Map<NativeMacIdentity, NativeMacAppearance> = emptyMap(),
    val error: Boolean = false) {
    fun get(deviceId: String, buildTag: String?) = values[NativeMacIdentity(deviceId, buildTag)] ?: values.entries.singleOrNull {
        canonicalMacDeviceId(it.key.deviceId) == canonicalMacDeviceId(deviceId) && it.key.buildTag == buildTag
    }?.value ?: NativeMacAppearance()
    fun get(mac: NativeCredentialStore.PairedMac) = get(mac.deviceId, mac.instanceTag)
    fun name(mac: NativeCredentialStore.PairedMac) = get(mac).displayName(mac.name)
}

/** Appearance never modifies PairedMac, pairing origins, discovery records, or RPC destinations. */
internal class NativeMacAppearanceStore(private val read: () -> String?, private val write: (String) -> Unit,
    private val pendingUpgrades: () -> List<NativePairingAppearanceUpgrade> = { emptyList() }) {
    private val mutableState = MutableStateFlow(NativeMacAppearances())
    val state = mutableState.asStateFlow()
    init { reload() }

    fun reload() = synchronized(lock) {
        mutableState.value = try { NativeMacAppearances(load()) } catch (_: Exception) { NativeMacAppearances(error = true) }
    }

    fun update(identity: NativeMacIdentity, permits: () -> Boolean,
               transform: (NativeMacAppearance) -> NativeMacAppearance) = synchronized(lock) {
        check(permits()) { "Account or team changed. Reopen Computer Details." }
        // Finish a committed upgrade before applying an edit/reset. Otherwise a
        // delayed worker could restore the old name after the user cleared it.
        pendingUpgrades().forEach { move -> adoptLegacy(move) { permits() && move in pendingUpgrades() } }
        require(identity.deviceId.isNotBlank() && identity.deviceId.length <= 128 && (identity.buildTag?.length ?: 0) <= 64)
        val values = load().toMutableMap()
        val canonical = identity.copy(deviceId = canonicalMacDeviceId(identity.deviceId))
        val aliases = values.filterKeys { canonicalMacDeviceId(it.deviceId) == canonical.deviceId && it.buildTag == canonical.buildTag }
        check(aliases.values.distinct().size <= 1) { "Conflicting computer appearances" }
        val candidate = transform(aliases.values.firstOrNull() ?: NativeMacAppearance())
        val validated = NativeMacAppearance(NativeMacAppearance.name(candidate.name), NativeMacAppearance.color(candidate.color),
            NativeMacAppearance.icon(candidate.icon))
        aliases.keys.forEach(values::remove)
        if (validated != NativeMacAppearance()) values[canonical] = validated
        require(values.size <= 128) { "Too many saved computer appearances." }
        check(permits()) { "Account or team changed. Reopen Computer Details." }
        save(values)
    }

    fun removeComputer(target: NativeComputerTarget, permits: () -> Boolean) = removeComputer(target, permits, { false })

    fun removeComputer(target: NativeComputerTarget, permits: () -> Boolean, removeLegacy: () -> Boolean) = synchronized(lock) {
        check(permits()) { "Account session changed" }
        val legacy = removeLegacy()
        val values = load().filterKeys {
            canonicalMacDeviceId(it.deviceId) != canonicalMacDeviceId(target.deviceId) ||
                (it.buildTag != target.buildTag && !(legacy && it.buildTag == null))
        }
        check(permits()) { "Account session changed" }
        save(values)
    }

    fun adoptLegacy(move: NativePairingAppearanceUpgrade, permits: () -> Boolean) = synchronized(lock) {
        check(permits()) { "Computer upgrade changed" }
        val document = loadDocument()
        if (move.id in document.applied) return@synchronized
        val values = document.values.toMutableMap()
        val device = canonicalMacDeviceId(move.device)
        val sources = values.filterKeys { canonicalMacDeviceId(it.deviceId) == device && it.buildTag == null }
        // Duplicate UUID spellings may represent one value, but conflicting old
        // customizations have no ordering evidence and must not be chosen arbitrarily.
        check(sources.values.distinct().size <= 1) { "Conflicting older computer appearances" }
        val destinationExists = values.keys.any { canonicalMacDeviceId(it.deviceId) == device && it.buildTag == move.build }
        if (move.inherit && !destinationExists) sources.values.firstOrNull()?.let {
            values[NativeMacIdentity(move.device, move.build)] = it
        }
        sources.keys.forEach(values::remove)
        check(permits()) { "Computer upgrade changed" }
        save(values, document.applied + move.id)
    }

    fun retainUpgradeReceipts(pending: () -> Set<String>, permits: () -> Boolean) = synchronized(lock) {
        check(permits()) { "Account or team changed" }
        val document = loadDocument()
        val retained = document.applied.intersect(pending())
        check(permits()) { "Account or team changed" }
        if (retained != document.applied) save(document.values, retained)
        else mutableState.value = NativeMacAppearances(document.values)
    }

    fun reportUpgradeFailure() = synchronized(lock) { mutableState.value = state.value.copy(error = true) }

    private data class Document(val values: Map<NativeMacIdentity, NativeMacAppearance>, val applied: Set<String>)

    private fun save(values: Map<NativeMacIdentity, NativeMacAppearance>, applied: Set<String> = loadDocument().applied) {
        val array = JSONArray(values.map { (key, value) -> JSONObject().put("deviceId", key.deviceId)
            .put("buildTag", key.buildTag ?: JSONObject.NULL).put("name", value.name ?: JSONObject.NULL)
            .put("color", value.color ?: JSONObject.NULL).put("icon", value.icon ?: JSONObject.NULL) })
        write(if (applied.isEmpty()) array.toString() else JSONObject().put("version", 1).put("values", array)
            .put("appliedUpgrades", JSONArray(applied.sorted())).toString())
        mutableState.value = NativeMacAppearances(values)
    }

    private fun load() = loadDocument().values

    private fun loadDocument(): Document {
        val text = read() ?: "[]"
        val envelope = if (text.trimStart().startsWith("[")) null else JSONObject(text).also {
            require(it.getInt("version") == 1)
        }
        val applied = envelope?.getJSONArray("appliedUpgrades")?.let { ids ->
            (0 until ids.length()).map { ids.getString(it).also { id -> require(UUID.fromString(id).toString() == id) } }.toSet()
        }.orEmpty()
        val array = envelope?.getJSONArray("values") ?: JSONArray(text)
        require(array.length() <= 128)
        val result = mutableMapOf<NativeMacIdentity, NativeMacAppearance>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            fun text(key: String): String? = if (item.isNull(key)) null else item.get(key) as String
            val identity = NativeMacIdentity(item.get("deviceId") as String, text("buildTag"))
            require(identity.deviceId.isNotBlank() && identity.deviceId.length <= 128 && (identity.buildTag?.length ?: 0) <= 64)
            require(identity !in result)
            result[identity] = NativeMacAppearance(NativeMacAppearance.name(text("name")),
                NativeMacAppearance.color(text("color")), NativeMacAppearance.icon(text("icon")))
        }
        return Document(result, applied)
    }

    companion object {
        private val lock = Any()
        private val stores = mutableMapOf<String, NativeMacAppearanceStore>()
        internal fun scopeFile(app: String, project: String, user: String, team: String): String {
            require(listOf(app, project, user, team).all { it.isNotBlank() })
            return MessageDigest.getInstance("SHA-256").digest(JSONArray(listOf(app, project, user, team)).toString().toByteArray())
                .joinToString("") { "%02x".format(it) } + ".json"
        }
        fun create(context: Context, team: NativeTeamScope): NativeMacAppearanceStore = scoped(context, team.userId, team.teamId)
        fun display(context: Context, owner: NativeComputerDisplayOwner) = scoped(context, owner.user, owner.team).state
        private fun scoped(context: Context, user: String, team: String): NativeMacAppearanceStore = synchronized(lock) {
            val root = File(context.noBackupFilesDir, "computer-appearance")
            val file = AtomicFile(File(root, scopeFile(context.packageName, NativeAccount.PROJECT_ID, user, team)))
            stores.getOrPut(file.baseFile.absolutePath) {
                val credentials = NativeCredentialStore(context.applicationContext)
                NativeMacAppearanceStore(read = {
                    if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) file.openRead().use {
                        require(it.channel.size() <= 128 * 1024); it.readBytes().decodeToString()
                    } else null
                }, write = { text ->
                    check(root.isDirectory || root.mkdirs())
                    val output = file.startWrite()
                    try { output.write(text.toByteArray()); file.finishWrite(output) }
                    catch (failure: Throwable) { file.failWrite(output); throw failure }
                }, pendingUpgrades = {
                    val saved = credentials.load()
                    NativePairingAppearanceUpgrades.pending(saved, user, team).filter {
                        NativePairingAppearanceUpgrades.current(saved, it)
                    }
                })
            }
        }
    }
}
