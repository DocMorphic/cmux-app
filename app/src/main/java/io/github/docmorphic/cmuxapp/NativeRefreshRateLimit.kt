package io.github.docmorphic.cmuxapp

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow

internal class NativeRefreshRateLimited(val retryAfterSeconds:Double?,val bodyKind:String):IOException("Token refresh rate limited")
internal class NativeRefreshCoolingDown:IOException("Token refresh is cooling down")
internal data class NativeRefreshRateLimitObservation(val bodyKind:String,val retryAfterSeconds:Double?,val backoffSeconds:Double,val attempt:Int) {
    val endpoint get()="/api/v1/auth/oauth/token"
}
/** In-memory producer contract only. No body, credentials, ids, URL query or automatic upload. */
internal object NativeRefreshReporting {
    interface Observer {
        fun limited(value:NativeRefreshRateLimitObservation)
        fun recovered()
    }
    @Volatile var observer:Observer?=null
    fun limited(value:NativeRefreshRateLimitObservation) { observer?.limited(value) }
    fun recovered() { observer?.recovered() }
}
internal fun nativeRefreshRetryAfter(value:String?,nowMillis:Long):Double? {
    val text=value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    text.toDoubleOrNull()?.takeIf { it.isFinite() && it>=0 }?.let { return it.coerceAtMost(86_400.0) }
    return runCatching { ((ZonedDateTime.parse(text,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-nowMillis)/1000.0).coerceIn(0.0,86_400.0) }.getOrNull()
}
internal fun nativeRefreshBackoffSeconds(retryAfter:Double?,streak:Int,jitter:Double):Double =
    retryAfter?.coerceIn(1.0,600.0) ?: ((5.0*2.0.pow((streak-1).coerceIn(0,6))).coerceAtMost(300.0)*jitter.coerceIn(.5,1.0))
internal fun nativeRefreshBodyKind(contentType:String?,bytes:ByteArray):String {
    val type=contentType?.lowercase(Locale.ROOT).orEmpty()
    if("json" in type)return "json"
    if("html" in type)return "html"
    // Swift Character(UnicodeScalar(byte)).isWhitespace differs from JVM Char for NEL and separators.
    return when(bytes.take(64).firstOrNull { val byte=it.toInt() and 255;byte !in 9..13 && byte !in setOf(32,133,160) }?.toInt()?.and(255)) {
        60 -> "html"
        123,91 -> "json"
        else -> "other"
    }
}
