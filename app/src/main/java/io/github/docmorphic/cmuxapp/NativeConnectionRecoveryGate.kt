package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NativeConnectionRecoveryOwner(val login: String?, val code: String, val team: NativeTeamScope?) {
    override fun toString() = "NativeConnectionRecoveryOwner"
}
internal data class NativeConnectionRecoveryBlock(val owner: NativeConnectionRecoveryOwner,
    val generation: Int, val failure: Throwable, val requiresReauth: Boolean)

/** ViewModel-owned: directory refresh, foreground return and Activity recreation do not redial a rejected session. */
internal class NativeConnectionRecoveryGate {
    private var active: Pair<NativeConnectionRecoveryOwner, Int>? = null
    private val mutable = MutableStateFlow<NativeConnectionRecoveryBlock?>(null)
    val state = mutable.asStateFlow()
    fun begin(owner: NativeConnectionRecoveryOwner, generation: Int): Boolean {
        val held = mutable.value
        if (held?.owner == owner && held.generation == generation) return false
        active = owner to generation
        mutable.value = null
        return true
    }
    fun failed(owner: NativeConnectionRecoveryOwner, generation: Int, failure: Throwable) {
        if (active != (owner to generation)) return
        mutable.value = if (nativeAutomaticReconnectStops(failure))
            NativeConnectionRecoveryBlock(owner, generation, failure, nativeSetupRequiresReauth(failure)) else null
    }
    fun connected(owner: NativeConnectionRecoveryOwner, generation: Int) {
        if (active != (owner to generation)) return
        if (mutable.value?.owner == owner) mutable.value = null
    }
    fun clear() { active = null; mutable.value = null }
}
