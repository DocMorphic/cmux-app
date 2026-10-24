package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.Base64

/** In-memory only; never log or persist the captured credentials. */
internal class NativeCapturedSession(val access: String?, val refresh: String?) {
    override fun toString() = "NativeCapturedSession(redacted)"
    companion object {
        fun from(root: JSONObject?) = NativeCapturedSession(root?.optString("access_token")?.takeIf(String::isNotBlank),
            root?.optString("refresh_token")?.takeIf(String::isNotBlank))
    }
}

/** The original ephemeral-token freshness predicate, including short-lived token lead limits. */
internal fun nativeCapturedAccessIsFresh(token: String?, nowMillis: Long, refreshLeadMillis: Long): Boolean = runCatching {
    val part = token?.split('.')?.filter(String::isNotEmpty)?.getOrNull(1) ?: return false
    val payload=JSONObject(String(Base64.getDecoder().decode(part.replace('-','+').replace('_','/')),Charsets.UTF_8))
    fun number(key:String):Double? = when(val value=payload.opt(key)) {
        is Number -> value.toDouble()
        is Boolean -> if(value)1.0 else 0.0 // Foundation NSNumber bridges Bool as TimeInterval.
        else -> null
    }
    val exp=number("exp") ?: return true
    val issued=number("iat")
    var lead=refreshLeadMillis
    if(issued!=null && exp>issued) lead=minOf(lead,((exp-issued)*1000).toLong()/2)
    lead=maxOf(20_000,lead)
    (exp*1000-nowMillis).toLong().coerceAtLeast(0)>lead
}.getOrDefault(false)

/** Cancellation is checked between every leg; no leg may fall back to a live account. */
internal suspend fun nativeSignOutTeardown(captured:NativeCapturedSession,
    freshAccess:suspend (String?,String)->String?,
    onSignedOut:suspend (String?,String?)->Unit,
    revoke:suspend (NativeCapturedSession)->Unit) {
    var access=captured.access
    captured.refresh?.let { refresh ->
        access=try { freshAccess(access,refresh) ?: access } catch(_:Exception) {
            currentCoroutineContext().ensureActive();access
        }
    }
    currentCoroutineContext().ensureActive()
    try { onSignedOut(access,captured.refresh) } catch(_:Exception) { currentCoroutineContext().ensureActive() }
    currentCoroutineContext().ensureActive()
    if(access!=null || captured.refresh!=null) revoke(NativeCapturedSession(access,captured.refresh))
}
