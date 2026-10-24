package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** A source SDK error, distinguished from a failed socket so it cannot be replayed as network IO. */
internal open class NativeSdkRequestFailure(
    code:String, message:String, val details:JSONObject?, val status:Long?, val knownError:String?,
) : NativeAccountRequestFailure(code, code=="USER_EMAIL_ALREADY_EXISTS" && when(val flag=details?.opt("would_work_if_email_was_verified")) {
    is Boolean->flag
    is Number->flag.toDouble()==1.0
    else->false
}, message) {
    override fun toString()="NativeSdkRequestFailure(code=$code)"
}

internal fun nativeSdkEndpoint(path:String):String = if(!path.startsWith('/'))"/unknown" else path.split('/').joinToString("/") { segment->
    if(segment.isEmpty() || segment.matches(Regex("v[0-9]{1,2}")) || segment.matches(Regex("[a-z][a-z_-]{0,23}")))segment else "ID"
}.take(200)

internal fun nativeSdkHeader(response:Response,suffix:String):String? =
    response.header("x-hexclave-$suffix") ?: response.header("x-stack-$suffix")

internal fun nativeSdkStatus(response:Response):Long {
    // Swift Int accepts a sign and ASCII digits, but no surrounding whitespace or overflow.
    val raw=nativeSdkHeader(response,"actual-status")
    return raw?.takeIf{it.matches(Regex("[+-]?[0-9]+"))}?.toLongOrNull() ?: response.code.toLong()
}

internal fun nativeSdkRequest(url:HttpUrl,project:String,key:String,method:String="GET",body:JSONObject?=null,
    access:String?=null,refresh:String?=null):Request {
    val builder=Request.Builder().url(url)
        .header("x-hexclave-project-id",project).header("x-hexclave-publishable-client-key",key)
        .header("x-hexclave-access-type","client").header("x-hexclave-client-version","cmux-android@0.2.0")
        .header("x-hexclave-override-error-status","true")
        .header("x-hexclave-random-nonce",UUID.randomUUID().toString().uppercase(Locale.ROOT))
    access?.let{builder.header("x-hexclave-access-token",it)}
    refresh?.let{builder.header("x-hexclave-refresh-token",it)}
    val payload=body?.toString() ?: "{}".takeIf{method in setOf("POST","PATCH","PUT")}
    if(payload!=null)builder.header("Content-Type","application/json")
    return builder.method(method,payload?.toRequestBody("application/json".toMediaType())).build()
}

/** Check before decoding a success payload: even an empty known-error header is an error. */
internal fun nativeSdkCheckResponse(response:Response,raw:ByteArray) {
    val status=nativeSdkStatus(response)
    if(status==429L)throw nativeAccountRateLimit(response,raw)
    val diagnostics=JSONObject().put("httpStatus",status)
    nativeSdkHeader(response,"request-id")?.let{diagnostics.put("requestId",it)}
    val known=nativeSdkHeader(response,"known-error")
    if(known!=null) {
        val body=runCatching{NativeAuthJson.objectValue(raw)}.getOrNull()
        val message=(body?.opt("message") as? String) ?: (body?.opt("error") as? String) ?: "Unknown error"
        body?.optJSONObject("details")?.let {details->details.keys().forEach{key->diagnostics.put(key,details.opt(key))}}
        val fixed=when(known) {
            "EMAIL_PASSWORD_MISMATCH"->"The email and password combination is incorrect."
            "PASSWORD_REQUIREMENTS_NOT_MET"->"The password does not meet the project's requirements."
            "USER_NOT_FOUND"->"No user with this email address was found."
            "VERIFICATION_CODE_ERROR"->"The verification code is invalid or expired."
            "INVALID_TOTP_CODE"->"The MFA code is incorrect."
            "REDIRECT_URL_NOT_WHITELISTED"->"The callback URL is not in the project's trusted domains list."
            "PASSKEY_AUTHENTICATION_FAILED"->"Passkey authentication failed. Please try again."
            "PASSWORD_CONFIRMATION_MISMATCH"->"The current password is incorrect."
            "OAUTH_PROVIDER_ACCOUNT_ID_ALREADY_USED_FOR_SIGN_IN"->"This OAuth account is already linked to another user for sign-in."
            else->null
        }
        val attempt=diagnostics.opt("attempt_code") as? String
        if(known=="MULTI_FACTOR_AUTHENTICATION_REQUIRED" && attempt!=null)
            throw NativeSdkRequestFailure(known,"Multi-factor authentication is required.",JSONObject().put("attempt_code",attempt),status,known)
        throw NativeSdkRequestFailure(known,fixed ?: message,if(fixed!=null)null else diagnostics,status,known)
    }
    if(status !in 200L..299L)throw NativeSdkRequestFailure("http_error","HTTP $status",diagnostics,status,null)
}

/** Logical request owns its nonce/body/refresh header across both retry kinds. */
internal val nativeSdkRetrySleep:suspend (Long)->Unit={delay(it)}
internal suspend fun <T> nativeSdkSend(initial:Request,authenticated:Boolean,
    refreshRejected:(suspend (String?)->String?)?=null,checkCurrent:()->Unit={},
    sleep:suspend (Long)->Unit=nativeSdkRetrySleep,exchange:suspend (Request)->T):T {
    var request=initial;var attempt=0;var didRefresh=false
    while(true) {
        currentCoroutineContext().ensureActive();checkCurrent()
        try {
            val result=exchange(request)
            currentCoroutineContext().ensureActive();checkCurrent()
            return result
        } catch(failure:NativeSdkRequestFailure) {
            if(failure.status!=401L || failure.knownError!="invalid_access_token" || !authenticated || didRefresh)throw failure
            currentCoroutineContext().ensureActive();checkCurrent()
            val access=refreshRejected?.invoke(request.header("x-hexclave-access-token")) ?: throw failure
            currentCoroutineContext().ensureActive();checkCurrent()
            request=request.newBuilder().header("x-hexclave-access-token",access).build()
            didRefresh=true;attempt=0
        } catch(failure:NativeAccountRequestFailure) {throw failure}
        catch(failure:NativeSignInFailure) {throw failure}
        catch(failure:IOException) {
            currentCoroutineContext().ensureActive();checkCurrent()
            if(request.method !in setOf("GET","HEAD","OPTIONS","PUT","DELETE") || attempt>=5)
                throw NativeSdkRequestFailure("network_error",failure.localizedMessage ?: "Network error",null,null,null)
            sleep((1L shl attempt++)*1000)
        }
    }
}
