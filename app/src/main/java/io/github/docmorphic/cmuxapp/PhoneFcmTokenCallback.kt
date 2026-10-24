package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Content-free wakeup marker. Callback token payloads are never stored or accepted here. */
internal object PhoneFcmTokenCallback {
    const val LATEST = "fcm_callback_latest"
    const val APPLIED = "fcm_callback_applied"
    private val lock = Any()
    private fun preferences(context:Context)=context.getSharedPreferences("phone_fcm_tokens",Context.MODE_PRIVATE)
    fun record(context:Context) = record(preferences(context))
    fun record(preferences:SharedPreferences) = synchronized(lock) {
        check(preferences.edit().putString(LATEST,next()).remove(APPLIED).commit()) { "Could not save push callback" }
    }
    fun pending(context:Context):String? = pending(preferences(context))
    fun pending(preferences:SharedPreferences):String? = synchronized(lock) {
        try {
            val values=preferences.all
            fun value(key:String):String? = values[key]?.let { require(it is String);checked(it) }
            val latest=value(LATEST);val applied=value(APPLIED)
            latest?.takeIf { it!=applied }
        } catch(_:Exception) { throw NativeCredentialReadException() }
    }
    /** Only metadata checks and the final preference commit hold this monitor, never Keystore IO. */
    fun <T> commit(preferences:SharedPreferences,expected:String?,operation:()->T):T = synchronized(lock) {
        if(pending(preferences)!=expected) throw NativeCredentialReadException()
        operation()
    }
    fun next(): String = UUID.randomUUID().toString()
    fun checked(value: String): String {
        require(UUID.fromString(value).toString() == value)
        return value
    }
}

internal class PhoneFcmTokenCheckPending : IllegalStateException("Push token verification is pending. Try again.")
