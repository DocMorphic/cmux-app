package io.github.docmorphic.cmuxapp

import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.pow

/** Only source-sanitized paths, response kind and bounded timing enter reporting. */
internal class NativeAccountRateLimited(retryAfterSeconds:Double?,endpoint:String,bodyKind:String,requestId:String?=null) :
    NativeSdkRequestFailure("RATE_LIMITED","Too many requests, please try again later",
        JSONObject().put("httpStatus",429).put("endpoint",nativeSdkEndpoint(endpoint))
            .put("body_kind",bodyKind.takeIf{it in setOf("json","html","other")} ?: "other").apply {
            requestId?.let{put("requestId",it)}
            retryAfterSeconds?.takeIf{it.isFinite() && it>=0}?.let{put("retry_after_seconds",it.coerceAtMost(86_400.0))}
        },429L,null) {
    val retryAfterSeconds=retryAfterSeconds?.takeIf {it.isFinite() && it>=0}?.coerceAtMost(86_400.0)
    val endpoint=nativeSdkEndpoint(endpoint)
    val bodyKind=bodyKind.takeIf {it in setOf("json","html","other")} ?: "other"
    override fun toString()="NativeAccountRateLimited(endpoint=$endpoint, bodyKind=$bodyKind)"
}
internal data class NativeSessionCheckLimit(val retryAfterSeconds:Double?,val endpoint:String,val bodyKind:String)
internal fun nativeSessionCheckLimit(error:Exception):NativeSessionCheckLimit? = when {
    error is NativeAccountRateLimited -> NativeSessionCheckLimit(error.retryAfterSeconds,error.endpoint,error.bodyKind)
    error is NativeAccountRequestFailure && error.code=="RATE_LIMITED" ->
        NativeSessionCheckLimit(null,"/unknown","other")
    else -> null
}
internal fun nativeAccountRateLimit(response:Response,raw:ByteArray?=null):NativeAccountRateLimited {
    val type=response.header("Content-Type");val prefix=ByteArrayOutputStream()
    if(raw!=null)prefix.write(raw,0,minOf(raw.size,64))
    else if(nativeRefreshBodyKind(type,byteArrayOf())=="other")try {
        val stream=response.body?.byteStream()
        while(prefix.size()<64) {val next=stream?.read() ?: -1;if(next<0)break;prefix.write(next)}
    } catch(_:IOException) { /* Status/headers still identify the throttle. Never retain the body. */ }
    return NativeAccountRateLimited(nativeRefreshRetryAfter(response.header("Retry-After"),System.currentTimeMillis()),
        "/"+response.request.url.pathSegments.joinToString("/"),nativeRefreshBodyKind(type,prefix.toByteArray()),nativeSdkHeader(response,"request-id"))
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
