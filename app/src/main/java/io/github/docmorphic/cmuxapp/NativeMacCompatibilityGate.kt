package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.WeakHashMap

internal data class MacCompatibilityKey(val owner: NativeTeamScope, val identity: NativeMacIdentity)

/** Tracks authenticated wires, including all pooled foreground and background borrowers. */
internal class NativeMacCompatibilityGate(
    private val isCurrent: (NativeTeamScope) -> Boolean,
    initial: NativeMacCompatibilityPolicy = NativeMacCompatibilityPolicy.baked,
    private val audience: NativeMacBuildAudience? = null,
    private val recordObservation: (NativeTeamScope, NativeMacIdentity, String?) -> Unit = { _, _, _ -> }
) {
    private data class Host(val key: MacCompatibilityKey, val version: String?)
    private val lock = Any()
    private var policy = initial
    private val wires = WeakHashMap<MobileRpcClient, Host>()
    private val observed = linkedMapOf<MacCompatibilityKey, Host>()
    private val restored = linkedMapOf<MacCompatibilityKey, Host>()
    private val mutableObservations = MutableStateFlow<Map<MacCompatibilityKey, String?>>(emptyMap())
    val observations = mutableObservations.asStateFlow()
    private val mutableWarnings = MutableStateFlow<Map<MacCompatibilityKey, MacCompatibilityViolation>>(emptyMap())
    val warnings = mutableWarnings.asStateFlow()

    /** Invoke only after transport/account authorization and exact expected-host checks. */
    fun admit(owner: NativeTeamScope, client: MobileRpcClient, status: JSONObject, locallyAuthorizedTailscale: Boolean = false) {
        check(isCurrent(owner) && !client.isClosed) { "Account or connection changed" }
        try { audience?.requireAuthenticated(status, locallyAuthorizedTailscale) }
        catch (failure: MacBuildNotSupported) { client.retireForCompatibility(failure); throw failure }
        val device = (status.opt("mac_device_id") as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
        require(device != null) { "The Mac did not provide its device identity." }
        val tag = (status.opt("mac_instance_tag") as? String)?.takeIf { it.isNotBlank() }
        val host = Host(MacCompatibilityKey(owner, NativeMacIdentity(canonicalMacDeviceId(device), tag)),
            (status.opt("mac_app_version") as? String)?.take(1024))
        val failure = synchronized(lock) {
            check(isCurrent(owner) && !client.isClosed) { "Account or connection changed" }
            prune()
            observed[host.key] = host
            while (observed.size > 256) observed.remove(observed.keys.first())
            publish()
            policy.violation(tag, host.version)?.let(::MacUpdateRequired).also {
                if (it == null) wires[client.compatibilityWire] = host
            }
        }
        // Never enter credential storage while holding the gate monitor: account guards use that lock.
        runCatching { recordObservation(owner, host.key.identity, host.version) }
        if (failure != null) {
            client.retireForCompatibility(failure)
            throw failure
        }
    }

    /** Warning-only restoration; never registers a wire or replaces a fresh authenticated observation. */
    fun restore(owner: NativeTeamScope, versions: Map<NativeMacIdentity, String?>) = synchronized(lock) {
        prune()
        restored.keys.removeAll { it.owner == owner }
        if (isCurrent(owner)) versions.entries.take(256).forEach { (identity, version) ->
            val key = MacCompatibilityKey(owner, identity)
            restored[key] = Host(key, version)
        }
        publish()
    }

    /** Remote refresh rechecks each wire, never a sibling Mac/build or a retired account. */
    fun replace(next: NativeMacCompatibilityPolicy) {
        val retired = synchronized(lock) {
            policy = next
            prune()
            publish()
            wires.entries.mapNotNull { (client, host) ->
                next.violation(host.key.identity.buildTag, host.version)?.let { client to MacUpdateRequired(it) }
            }.also { entries -> entries.forEach { wires.remove(it.first) } }
        }
        retired.forEach { (client, reason) -> client.retireForCompatibility(reason) }
    }

    fun reconcile() = synchronized(lock) { prune(); publish() }
    private fun prune() {
        wires.entries.removeAll { it.key.isClosed || !isCurrent(it.value.key.owner) }
        observed.keys.removeAll { !isCurrent(it.owner) }
        restored.keys.removeAll { !isCurrent(it.owner) }
    }
    private fun publish() {
        mutableObservations.value = observed.mapValues { it.value.version }
        mutableWarnings.value = (restored + observed).mapNotNull { (key, host) ->
            policy.violation(key.identity.buildTag, host.version)?.let { key to it }
        }.toMap()
    }
}

/** Cache decoding is identical to network decoding; a corrupt response never replaces good state. */
internal class NativeMacPolicyCache(private val read: () -> String?, private val write: (String) -> Unit) {
    var policy = runCatching { read()?.let(NativeMacCompatibilityPolicy::decode) }.getOrNull()
        ?: NativeMacCompatibilityPolicy.baked
        private set
    fun accept(payload: String): NativeMacCompatibilityPolicy? {
        val next = NativeMacCompatibilityPolicy.decode(payload) ?: return null
        policy = next
        runCatching { write(payload) }
        return next
    }
}
