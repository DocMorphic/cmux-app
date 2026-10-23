package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.util.Locale

/** Original SignInErrorPresentation + AuthError display-safe roles, never provider text. */
internal enum class NativeSignInError(val resource: Int) {
    INVALID_EMAIL(R.string.cmux_signin_error_invalid_email),
    EMAIL_REQUIRES_VERIFICATION(R.string.cmux_signin_error_email_requires_verification),
    EMAIL_EXISTS(R.string.cmux_signin_error_email_exists),
    INVALID_CODE(R.string.cmux_signin_error_invalid_code),
    CODE_EXPIRED(R.string.cmux_signin_error_code_expired),
    RATE_LIMIT(R.string.cmux_signin_error_rate_limit),
    WRONG_PASSWORD(R.string.cmux_signin_error_wrong_password),
    USER_NOT_FOUND(R.string.cmux_signin_error_user_not_found),
    PASSKEY_FAILED(R.string.cmux_signin_error_passkey_failed),
    INVALID_MFA(R.string.cmux_signin_error_invalid_mfa),
    CONFIG(R.string.cmux_signin_error_config),
    OAUTH_LINKED(R.string.cmux_signin_error_oauth_linked),
    APPLE_CONFIG(R.string.cmux_signin_error_apple_config),
    BROWSER_SIGN_IN_FAILED(R.string.cmux_signin_error_browser_sign_in_failed),
    NETWORK_ERROR(R.string.cmux_signin_error_network_error),
    SERVER_ERROR(R.string.cmux_signin_error_server_error),
    OFFLINE(R.string.cmux_signin_error_offline),
    TIMED_OUT(R.string.cmux_signin_error_timed_out),
    INVALID_CODE_SHORT(R.string.cmux_signin_error_invalid_code_short),
    INVALID_CALLBACK(R.string.cmux_signin_error_invalid_callback),
    UNAUTHORIZED(R.string.cmux_signin_error_unauthorized),
}

internal class NativeSignInFailure(val reason: NativeSignInError) : Exception(reason.name)

internal fun nativeSignInError(failure: Exception): NativeSignInError? = when (failure) {
    is TimeoutCancellationException -> NativeSignInError.TIMED_OUT
    is CancellationException -> null
    is NativeSignInFailure -> failure.reason
    is NativeAccountRequestFailure -> when (failure.code.uppercase(Locale.ROOT)) {
        "OAUTH_CANCELLED" -> null
        "SCHEMA_ERROR" -> NativeSignInError.INVALID_EMAIL
        "USER_EMAIL_ALREADY_EXISTS" -> if (failure.requiresEmailVerification) NativeSignInError.EMAIL_REQUIRES_VERIFICATION else NativeSignInError.EMAIL_EXISTS
        "VERIFICATION_CODE_ERROR", "INVALID_OTP" -> NativeSignInError.INVALID_CODE
        "OTP_EXPIRED" -> NativeSignInError.CODE_EXPIRED
        "RATE_LIMIT", "RATE_LIMITED" -> NativeSignInError.RATE_LIMIT
        "EMAIL_PASSWORD_MISMATCH" -> NativeSignInError.WRONG_PASSWORD
        "USER_NOT_FOUND" -> NativeSignInError.USER_NOT_FOUND
        "PASSKEY_AUTHENTICATION_FAILED", "PASSKEY_WEBAUTHN_ERROR" -> NativeSignInError.PASSKEY_FAILED
        "INVALID_TOTP_CODE" -> NativeSignInError.INVALID_MFA
        "REDIRECT_URL_NOT_WHITELISTED", "INVALID_URL" -> NativeSignInError.CONFIG
        "OAUTH_PROVIDER_ACCOUNT_ID_ALREADY_USED_FOR_SIGN_IN" -> NativeSignInError.OAUTH_LINKED
        "INVALID_APPLE_CREDENTIALS", "APPLE_SIGNIN_NOT_CONFIGURED", "APPLE_SIGNIN_NOT_HANDLED",
        "APPLE_SIGNIN_INVALID_RESPONSE", "APPLE_SIGNIN_FAILED", "APPLE_SIGNIN_NOT_INTERACTIVE", "APPLE_SIGNIN_ERROR" -> NativeSignInError.APPLE_CONFIG
        "OAUTH_ERROR", "MISSING_CODE", "PARSE_ERROR", "INVALID_RESPONSE" -> NativeSignInError.BROWSER_SIGN_IN_FAILED
        "UNAUTHORIZED", "INVALID_TOKEN", "TOKEN_EXPIRED" -> NativeSignInError.UNAUTHORIZED
        else -> NativeSignInError.SERVER_ERROR
    }
    is IOException -> NativeSignInError.NETWORK_ERROR
    else -> NativeSignInError.SERVER_ERROR
}
