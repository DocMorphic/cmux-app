package io.github.docmorphic.cmuxapp

import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

/** Only fixed path labels, response kind and bounded timing may leave the HTTP owner. */
internal class NativeAccountRateLimited(retryAfterSeconds:Double?,endpoint:String,bodyKind:String) :
    NativeAccountRequestFailure("RATE_LIMITED",false,"Too many requests. Please wait a moment and try again.") {
    val retryAfterSeconds=retryAfterSeconds?.takeIf {it.isFinite() && it>=0}?.let {floor(it.coerceAtMost(86_400.0)*1000)/1000}
    val endpoint=endpoint.takeIf {it in ENDPOINTS} ?: "/unknown"
    val bodyKind=bodyKind.takeIf {it in setOf("json","html","other")} ?: "other"
    override fun toString()="NativeAccountRateLimited(endpoint=$endpoint, bodyKind=$bodyKind)"
    companion object {private val ENDPOINTS=setOf("/api/v1/users/me","/api/v1/teams","/api/v1/auth/oauth/token")}
}
internal data class NativeSessionCheckLimit(val retryAfterSeconds:Double?,val endpoint:String,val bodyKind:String)
internal fun nativeSessionCheckLimit(error:Exception):NativeSessionCheckLimit? = when {
    error is NativeAccountRateLimited -> NativeSessionCheckLimit(error.retryAfterSeconds,error.endpoint,error.bodyKind)
    error is NativeAccountRequestFailure && error.code.uppercase(Locale.ROOT) in setOf("RATE_LIMITED","RATE_LIMIT") ->
        NativeSessionCheckLimit(null,"/unknown","other")
    else -> null
}
internal fun nativeAccountRateLimit(response:Response):NativeAccountRateLimited {
    val type=response.header("Content-Type");val prefix=ByteArrayOutputStream()
    if(nativeRefreshBodyKind(type,byteArrayOf())=="other")try {
        val stream=response.body?.byteStream()
        while(prefix.size()<64) {val next=stream?.read() ?: -1;if(next<0)break;prefix.write(next)}
    } catch(_:IOException) { /* Status/headers still identify the throttle. Never retain the body. */ }
    return NativeAccountRateLimited(nativeRefreshRetryAfter(response.header("Retry-After"),System.currentTimeMillis()),
        response.request.url.encodedPath,nativeRefreshBodyKind(type,prefix.toByteArray()))
}
internal fun nativeTeamRecoveryDelayMillis(attempt:Int):Long=(2_000L shl attempt.coerceIn(0,5)).coerceAtMost(60_000)
internal fun nativeTeamRateLimitDelaySeconds(retryAfter:Double?,streak:Int,jitter:Double):Double {
    val spread=jitter.coerceIn(.5,1.0)
    val header=retryAfter?.takeIf {it.isFinite() && it>=0}?.coerceAtMost(86_400.0)
    if(header!=null)return (header.coerceIn(1.0,900.0)*(.5+spread)).coerceAtMost(900.0)
    return floor((30.0*2.0.pow((streak-1).coerceIn(0,5))).coerceAtMost(900.0)*spread*1000)/1000
}
internal data class NativeSessionCheckObservation(val endpoint:String,val bodyKind:String,val retryAfterSeconds:Double?,
    val backoffSeconds:Double,val attempt:Int)
/** Safe in-memory producer only. No user/account data, response body, URL query or automatic upload. */
internal object NativeSessionCheckReporting {
    interface Observer {fun limited(value:NativeSessionCheckObservation);fun succeeded()}
    @Volatile var observer:Observer?=null
    fun limited(value:NativeSessionCheckObservation) {observer?.limited(value)}
    fun succeeded() {observer?.succeeded()}
}
