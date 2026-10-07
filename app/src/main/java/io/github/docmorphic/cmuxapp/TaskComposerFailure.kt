package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

/** UI failures describe the failed stage, never arbitrary host exception text. */
internal data class TaskComposerFailure(val title: String, val message: String) {
    enum class Stage { SAVING, REFRESHING, VALIDATING_GROUP, UPLOADING, CREATING, READING_RESULT }

    companion object {
        const val FAILED = "Couldn’t start this task"
        const val UNCONFIRMED = "Task status unconfirmed"
        const val ACCEPTED = "Task already accepted"
        val restored = TaskComposerFailure(UNCONFIRMED,
            "Previous task status is unconfirmed. Check your workspace list before retrying.")

        fun from(failure: Exception, stage: Stage): TaskComposerFailure {
            if (stage == Stage.SAVING) return TaskComposerFailure(FAILED,
                "cmux couldn’t save this draft safely. Reopen the composer and try again.")
            if (stage == Stage.REFRESHING) return TaskComposerFailure(UNCONFIRMED,
                "Couldn’t refresh the accepted task. Reconnect to the Mac and try again.")
            if (stage == Stage.VALIDATING_GROUP) return TaskComposerFailure(FAILED,
                "The selected group is no longer available. Choose another group or None.")
            if (stage == Stage.READING_RESULT) return TaskComposerFailure(UNCONFIRMED,
                "The Mac did not confirm the created workspace. Check your workspace list before retrying.")

            val code = (failure as? MobileRpcException)?.code?.trim()?.lowercase(Locale.ROOT)
            val timeout = failure is TimeoutCancellationException || failure is SocketTimeoutException ||
                code in setOf("request_timeout", "request_timed_out")
            val disconnected = failure is IOException || code in setOf("unavailable", "mac_unreachable", "connection_recovering")
            val unauthorized = code in setOf("unauthorized", "forbidden", "invalid_token", "token_expired", "expired_token",
                "auth_required", "account_mismatch", "authorization_failed", "authentication_expired", "ticket_expired", "team_access_revoked")
            val unsupported = code in setOf("method_not_found", "capability_disabled", "unsupported")
            if (stage == Stage.UPLOADING) return TaskComposerFailure(FAILED, when {
                unauthorized -> "That Mac did not authorize the attachment upload."
                unsupported -> "That Mac does not support task attachments."
                timeout -> "The Mac did not finish uploading the attachments in time."
                disconnected -> "The attachments couldn’t be uploaded because that Mac is not connected."
                else -> "The attachments couldn’t be uploaded. Check the files and try again."
            })
            return when {
                unauthorized -> TaskComposerFailure(FAILED, "That Mac did not authorize the request.")
                timeout -> TaskComposerFailure(UNCONFIRMED, "The Mac did not respond in time. Check your workspace list before retrying.")
                failure is MobileRpcOutcomeUnknown -> TaskComposerFailure(UNCONFIRMED,
                    "The connection recovered, but this task’s status is unconfirmed. Check your workspace list before retrying.")
                disconnected -> TaskComposerFailure(UNCONFIRMED, "That Mac is not connected. Check your workspace list before retrying.")
                unsupported -> TaskComposerFailure(FAILED, "That Mac does not support this action.")
                code == "busy" -> TaskComposerFailure(FAILED, "Another workspace action is still finishing.")
                code == "invalid_working_directory" -> TaskComposerFailure(FAILED, "Choose an existing folder on that Mac.")
                code == "persistence_failed" -> TaskComposerFailure(FAILED, "The Mac could not safely reserve this task.")
                code == "already_completed" -> TaskComposerFailure(ACCEPTED, TaskCompletedRecovery.REFRESH_MESSAGE)
                failure is MobileRpcException && failure.fromHostResponse -> TaskComposerFailure(FAILED, "The Mac rejected the task.")
                else -> TaskComposerFailure(UNCONFIRMED, "Couldn’t confirm this task’s status. Check your workspace list before retrying.")
            }
        }
    }
}
