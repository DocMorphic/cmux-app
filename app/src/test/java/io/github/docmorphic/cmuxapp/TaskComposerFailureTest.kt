package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.TaskComposerFailure.Stage
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

class TaskComposerFailureTest {
    private fun host(code: String) = MobileRpcException(code, "Private host diagnostic /Users/fixture/task", true)

    @Test fun hostRejectionsHaveSpecificActionableMessages() {
        val messages = mapOf(
            "invalid_working_directory" to "Choose an existing folder on that Mac.",
            "persistence_failed" to "The Mac could not safely reserve this task.",
            "busy" to "Another workspace action is still finishing.",
            "unauthorized" to "That Mac did not authorize the request.",
            "method_not_found" to "That Mac does not support this action.",
            "unrecognized_host_rejection" to "The Mac rejected the task.")
        messages.forEach { (code, message) ->
            assertEquals(TaskComposerFailure(TaskComposerFailure.FAILED, message), TaskComposerFailure.from(host(code), Stage.CREATING))
        }
    }

    @Test fun timeoutAndDisconnectAreUncertainOnlyAfterCreation() {
        for (failure in listOf(host("request_timeout"), SocketTimeoutException("private"), IOException("private"), host("unavailable"))) {
            val create = TaskComposerFailure.from(failure, Stage.CREATING)
            assertEquals(TaskComposerFailure.UNCONFIRMED, create.title)
            assertTrue(create.message.contains("Check your workspace list"))
            val upload = TaskComposerFailure.from(failure, Stage.UPLOADING)
            assertEquals(TaskComposerFailure.FAILED, upload.title)
            assertTrue(upload.message.contains("attachments"))
            assertFalse(upload.message.contains("Check your workspace list"))
        }
    }

    @Test fun uploadAuthAndUnsupportedMessagesDescribeAttachments() {
        assertEquals("That Mac did not authorize the attachment upload.", TaskComposerFailure.from(host("forbidden"), Stage.UPLOADING).message)
        assertEquals("That Mac does not support task attachments.", TaskComposerFailure.from(host("capability_disabled"), Stage.UPLOADING).message)
        assertEquals("The attachments couldn’t be uploaded. Check the files and try again.",
            TaskComposerFailure.from(IllegalStateException("missing ciphertext"), Stage.UPLOADING).message)
    }

    @Test fun localSaveRefreshAndResponseFailuresDoNotReuseHostRejectionText() {
        val failure = host("already_completed")
        assertEquals(TaskComposerFailure.FAILED, TaskComposerFailure.from(failure, Stage.SAVING).title)
        assertTrue(TaskComposerFailure.from(failure, Stage.SAVING).message.contains("save this draft"))
        assertTrue(TaskComposerFailure.from(failure, Stage.REFRESHING).message.contains("refresh the accepted task"))
        assertEquals(TaskComposerFailure.UNCONFIRMED, TaskComposerFailure.from(failure, Stage.READING_RESULT).title)
        assertEquals(TaskComposerFailure(TaskComposerFailure.ACCEPTED, TaskCompletedRecovery.REFRESH_MESSAGE),
            TaskComposerFailure.from(failure, Stage.CREATING))
        Stage.entries.forEach { assertFalse(TaskComposerFailure.from(failure, it).message.contains("Private host")) }
    }

    @Test fun unknownLocalCreationFailureDoesNotClaimHostRejectedIt() {
        assertEquals(TaskComposerFailure.UNCONFIRMED, TaskComposerFailure.from(IllegalStateException("fixture"), Stage.CREATING).title)
        assertEquals(TaskComposerFailure.UNCONFIRMED, TaskComposerFailure.from(MobileRpcOutcomeUnknown(), Stage.CREATING).title)
        assertTrue(TaskComposerFailure.from(MobileRpcOutcomeUnknown(), Stage.CREATING).message.startsWith("The connection recovered"))
    }

    @Test fun normalizationUsesProtocolLocaleAndAllAuthAliases() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            for (code in listOf("INVALID_TOKEN", "TOKEN_EXPIRED", "EXPIRED_TOKEN", "AUTH_REQUIRED", "ACCOUNT_MISMATCH",
                "AUTHORIZATION_FAILED", "AUTHENTICATION_EXPIRED", "TICKET_EXPIRED", "TEAM_ACCESS_REVOKED")) {
                assertEquals("That Mac did not authorize the request.", TaskComposerFailure.from(host("  $code  "), Stage.CREATING).message)
            }
        } finally { Locale.setDefault(before) }
    }
}
