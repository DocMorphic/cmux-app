package io.github.docmorphic.cmuxapp

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A server operation's retryable flag does not decide whether a new session can recover. */
internal object IrohV2Recovery {
    val terminalCodes = setOf("team_access_revoked", "device_revoked", "identity_mismatch",
        "environment_mismatch", "key_replacement_required", "endpoint_already_owned", "invalid_device_proof")
    val authenticationCodes = setOf("unauthorized", "ticket_expired")

    // Authentication has already had one forced-refresh attempt inside the control session.
    fun stops(error: Throwable?) = error?.let(IrohV2Wire::isAuthenticationFailure) == true ||
        (error is IrohV2ServerFailure && error.code in terminalCodes) ||
        (error is IrohV2HttpFailure && error.status in setOf(400, 403, 404, 405, 413, 415))

    fun delayMillis(error: Throwable?, attempt: Int, base: Long, nowMillis: Long): Long {
        val backoff = (base.coerceIn(1, 60_000) * (1L shl attempt.coerceIn(0, 6))).coerceAtMost(60_000)
        val serverDelay = when (error) {
            is IrohV2ServerFailure -> error.retryAfterMs ?: if (error.code == "rate_limited") 60_000 else 0
            is IrohV2HttpFailure -> if (error.status == 429) retryAfterMillis(error.retryAfter, nowMillis) ?: 60_000 else 0
            else -> 0
        }
        return maxOf(backoff, serverDelay)
    }

    private fun retryAfterMillis(value: String?, nowMillis: Long): Long? {
        val raw = value?.trim() ?: return null
        raw.toLongOrNull()?.let { return if (it < 0) null else it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
        return runCatching {
            (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0)
        }.getOrNull()
    }
}
