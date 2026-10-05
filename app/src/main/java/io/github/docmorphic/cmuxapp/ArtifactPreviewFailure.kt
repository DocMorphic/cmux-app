package io.github.docmorphic.cmuxapp

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

/** Structured failure survives view recreation without retaining an exception or host payload. */
internal data class ArtifactPreviewFailure(val kind: Kind, val actualSize: Long? = null, val limit: Long? = null) {
    enum class Kind {
        UNSUPPORTED, INVALID_PARAMS, SESSION_NOT_FOUND, SESSION_UNAVAILABLE,
        TERMINAL_NOT_FOUND, WORKSPACE_NOT_FOUND, NOT_REPOSITORY, FILE_NOT_FOUND,
        PERMISSION_DENIED, NOT_DIRECTORY, NOT_REGULAR_FILE, FILE_READ_FAILED,
        FILE_CHANGED, UNSUPPORTED_MEDIA, CORRUPT_MEDIA, PREVIEW_FAILED,
        UNAVAILABLE, INVALID_RESPONSE, TRANSFER_INTERRUPTED, REQUEST_TIMED_OUT,
        CONNECTION_RECOVERING, CONNECTION_NEEDS_RESTART, SECURE_CONNECTION_REQUIRED, AUTHENTICATION_EXPIRED,
        AUTHORIZATION_FAILED, ACCOUNT_MISMATCH, LOCAL_STORAGE_FULL, LOCAL_STORAGE_UNAVAILABLE,
        LOAD_FAILED, MAC_UNREACHABLE, FORBIDDEN, TOO_LARGE,
    }

    fun presentation(authorization: ArtifactAuthorization, markdownPanel: Boolean,
                     connection: NativeFeedAvailability, formatBytes: (Long) -> String = { "$it bytes" }): ArtifactFailureCopy {
        if (kind == Kind.TOO_LARGE) return ArtifactFailureCopy("File too large to preview",
            if (actualSize != null && limit != null) {
                val actualText = formatBytes(actualSize); val limitText = formatBytes(limit)
                if (actualSize > limit && actualText == limitText) {
                    val numbers = java.text.NumberFormat.getIntegerInstance()
                    "This file exceeds the $limitText preview limit (${numbers.format(actualSize)} bytes; limit ${numbers.format(limit)} bytes)."
                } else "This file is $actualText; previews are limited to $limitText."
            }
            else if (limit != null) "This preview is limited to ${formatBytes(limit)}." else "This file exceeds the preview size limit.", false)
        if (markdownPanel) return when (kind) {
            Kind.UNSUPPORTED -> ArtifactFailureCopy("Update cmux on your Mac", "The connected Mac's cmux version can't preview this file.", false)
            Kind.FILE_NOT_FOUND -> standard(authorization)
            Kind.FORBIDDEN, Kind.PERMISSION_DENIED, Kind.AUTHORIZATION_FAILED, Kind.SECURE_CONNECTION_REQUIRED,
            Kind.AUTHENTICATION_EXPIRED -> ArtifactFailureCopy("Preview unavailable", "This file isn't displayed by the selected panel.", false)
            Kind.MAC_UNREACHABLE, Kind.ACCOUNT_MISMATCH -> unreachable(connection)
            Kind.SESSION_NOT_FOUND, Kind.TERMINAL_NOT_FOUND, Kind.WORKSPACE_NOT_FOUND ->
                ArtifactFailureCopy("Panel closed", "That file panel is no longer open on your Mac.", false)
            Kind.SESSION_UNAVAILABLE, Kind.UNAVAILABLE, Kind.FILE_CHANGED, Kind.TRANSFER_INTERRUPTED,
            Kind.REQUEST_TIMED_OUT, Kind.CONNECTION_RECOVERING -> ArtifactFailureCopy("Transfer unavailable",
                "File transfer is temporarily unavailable on your Mac. Try again shortly.", true)
            Kind.INVALID_PARAMS -> ArtifactFailureCopy("Couldn't load file", "Something went wrong loading this file. (invalid_params)", true)
            else -> ArtifactFailureCopy("Couldn't load file", "Something went wrong loading this file.", true)
        }
        return if (kind == Kind.MAC_UNREACHABLE) unreachable(connection) else standard(authorization)
    }

    private fun unreachable(connection: NativeFeedAvailability) = when (connection) {
        NativeFeedAvailability.CONNECTED -> ArtifactFailureCopy("Mac unreachable", "Check the connection to your Mac and try again.", true)
        NativeFeedAvailability.CONNECTING -> ArtifactFailureCopy("Reconnecting…", "This phone's connection to the Mac dropped and is coming back. Retry in a moment.", true)
        NativeFeedAvailability.OFFLINE -> ArtifactFailureCopy("Not connected", "This phone isn't connected to the Mac right now. Reconnect, then retry.", true)
    }

    private fun standard(authorization: ArtifactAuthorization): ArtifactFailureCopy = when (kind) {
        Kind.UNSUPPORTED -> ArtifactFailureCopy("File previews unavailable", "This Mac doesn't support file previews. Update cmux on the Mac.", false)
        Kind.INVALID_PARAMS -> ArtifactFailureCopy("Invalid file request", "The file request was invalid. Update cmux on both devices.", false)
        Kind.SESSION_NOT_FOUND -> ArtifactFailureCopy("Session not found", "The chat session for this file is no longer available.", false)
        Kind.SESSION_UNAVAILABLE -> ArtifactFailureCopy("Session unavailable", "The session exists, but its file history couldn't be read on the Mac.", true)
        Kind.TERMINAL_NOT_FOUND -> ArtifactFailureCopy("Terminal not found", "The terminal that authorized this file is no longer available.", false)
        Kind.WORKSPACE_NOT_FOUND -> ArtifactFailureCopy("Workspace not found", "The workspace for this file is no longer available.", false)
        Kind.NOT_REPOSITORY -> ArtifactFailureCopy("Repository unavailable", "The workspace folder is no longer a Git repository.", false)
        Kind.FILE_NOT_FOUND -> ArtifactFailureCopy("File not found", "The file is no longer available on your Mac.", false)
        Kind.PERMISSION_DENIED -> ArtifactFailureCopy("Permission denied", "cmux found the file, but the Mac doesn't allow cmux to read it. Check the file's permissions.", false)
        Kind.NOT_DIRECTORY -> ArtifactFailureCopy("Not a folder", "This path is a file, not a folder.", false)
        Kind.NOT_REGULAR_FILE -> ArtifactFailureCopy("Not a regular file", "This path is a folder or special filesystem item, so its bytes can't be previewed.", false)
        Kind.FILE_READ_FAILED -> ArtifactFailureCopy("Couldn't read file", "The Mac found the file but couldn't read its metadata or contents. Try again.", true)
        Kind.FILE_CHANGED -> ArtifactFailureCopy("File changed", "The file changed while it was loading. Try again to load the latest version.", true)
        Kind.UNSUPPORTED_MEDIA -> ArtifactFailureCopy("Preview unavailable", "This file can't be previewed.", false)
        Kind.CORRUPT_MEDIA -> ArtifactFailureCopy("File is damaged", "The file type is supported, but its media data couldn't be decoded.", false)
        Kind.PREVIEW_FAILED -> ArtifactFailureCopy("Couldn't create preview", "The file was read, but cmux couldn't create its preview. Try again.", true)
        Kind.UNAVAILABLE -> ArtifactFailureCopy("File service unavailable", "File transfer is temporarily unavailable on the Mac. Try again.", true)
        Kind.INVALID_RESPONSE -> ArtifactFailureCopy("Invalid file response", "The Mac sent inconsistent file data. Try again, then update cmux if it continues.", true)
        Kind.TRANSFER_INTERRUPTED -> ArtifactFailureCopy("Transfer interrupted", "The file transfer stopped before all bytes arrived. Try again.", true)
        Kind.REQUEST_TIMED_OUT -> ArtifactFailureCopy("Request timed out", "The file request didn't complete in time. Try again.", true)
        Kind.CONNECTION_RECOVERING -> ArtifactFailureCopy("Reconnecting to Mac", "A connection attempt is already in progress. Try again in a moment.", true)
        Kind.CONNECTION_NEEDS_RESTART -> ArtifactFailureCopy("Restart required", "Connection cleanup is stuck. Restart cmux on this device, reconnect, and try again.", false)
        Kind.SECURE_CONNECTION_REQUIRED -> ArtifactFailureCopy("Secure connection required", "This route can't securely transfer Mac files. Reconnect using a paired secure route.", false)
        Kind.AUTHENTICATION_EXPIRED -> ArtifactFailureCopy("Pairing expired", "Reconnect to the Mac to refresh authentication, then try again.", false)
        Kind.AUTHORIZATION_FAILED -> ArtifactFailureCopy("Connection not authorized", "This device isn't authorized to read files from the Mac. Pair it again.", false)
        Kind.ACCOUNT_MISMATCH -> ArtifactFailureCopy("Account mismatch", "This device and the Mac are signed in to different cmux accounts.", false)
        Kind.LOCAL_STORAGE_FULL -> ArtifactFailureCopy("Device storage full", "Free some storage on this device, then try again.", false)
        Kind.LOCAL_STORAGE_UNAVAILABLE -> ArtifactFailureCopy("Local storage unavailable", "The file arrived, but cmux couldn't create or read its temporary file on this device. Try again.", true)
        Kind.LOAD_FAILED -> ArtifactFailureCopy("Couldn't load file", "The file couldn't be loaded. Try again.", true)
        Kind.MAC_UNREACHABLE -> ArtifactFailureCopy("Mac unreachable", "Check the connection to your Mac and try again.", true)
        Kind.FORBIDDEN -> ArtifactFailureCopy("Preview unavailable", when (authorization) {
            is ArtifactAuthorization.Panel -> "That file panel is no longer open on your Mac."
            is ArtifactAuthorization.Terminal -> "This file isn't visible in the current terminal view."
            is ArtifactAuthorization.Session -> "This file was not referenced by the conversation."
        }, false)
        Kind.TOO_LARGE -> error("Size failures require size-aware presentation")
    }

    companion object {
        fun from(error: Throwable, authorization: ArtifactAuthorization? = null): ArtifactPreviewFailure {
            if (error is ArtifactPreviewException) return error.failure
            val kind = when (error) {
                is MobileRpcException -> when (error.code?.trim()?.lowercase(java.util.Locale.ROOT)) {
                    "invalid_params" -> Kind.INVALID_PARAMS
                    "session_not_found" -> Kind.SESSION_NOT_FOUND
                    "session_unavailable" -> Kind.SESSION_UNAVAILABLE
                    "terminal_not_found" -> Kind.TERMINAL_NOT_FOUND
                    "workspace_not_found" -> Kind.WORKSPACE_NOT_FOUND
                    "not_found" -> if (authorization is ArtifactAuthorization.Terminal) Kind.TERMINAL_NOT_FOUND else Kind.SESSION_NOT_FOUND
                    "not_a_repo" -> Kind.NOT_REPOSITORY
                    "forbidden" -> Kind.FORBIDDEN
                    "file_not_found" -> Kind.FILE_NOT_FOUND
                    "permission_denied" -> Kind.PERMISSION_DENIED
                    "not_directory" -> Kind.NOT_DIRECTORY
                    "not_regular_file" -> Kind.NOT_REGULAR_FILE
                    "read_failed" -> Kind.FILE_READ_FAILED
                    "file_changed" -> Kind.FILE_CHANGED
                    "unsupported_media" -> Kind.UNSUPPORTED_MEDIA
                    "corrupt_media" -> Kind.CORRUPT_MEDIA
                    "preview_failed" -> Kind.PREVIEW_FAILED
                    "unavailable" -> Kind.UNAVAILABLE
                    "invalid_response" -> Kind.INVALID_RESPONSE
                    "transfer_interrupted" -> Kind.TRANSFER_INTERRUPTED
                    "request_timed_out" -> Kind.REQUEST_TIMED_OUT
                    "request_timeout" -> Kind.REQUEST_TIMED_OUT
                    "unsupported_transport" -> Kind.SECURE_CONNECTION_REQUIRED
                    "method_not_found" -> Kind.UNSUPPORTED
                    "capability_disabled" -> Kind.UNSUPPORTED
                    "connection_recovering" -> Kind.CONNECTION_RECOVERING
                    "authentication_expired" -> Kind.AUTHENTICATION_EXPIRED
                    "ticket_expired" -> Kind.AUTHENTICATION_EXPIRED
                    "unauthorized" -> Kind.AUTHORIZATION_FAILED
                    "authorization_failed" -> Kind.AUTHORIZATION_FAILED
                    "team_access_revoked" -> Kind.AUTHORIZATION_FAILED
                    "account_mismatch" -> Kind.ACCOUNT_MISMATCH
                    "mac_unreachable" -> Kind.MAC_UNREACHABLE
                    "too_large" -> Kind.TOO_LARGE
                    else -> Kind.LOAD_FAILED
                }
                is ArtifactLaneTransfer.BeforeData, is ArtifactLaneTransfer.Interrupted -> Kind.TRANSFER_INTERRUPTED
                is SocketTimeoutException, is TimeoutCancellationException -> Kind.REQUEST_TIMED_OUT
                is EOFException, is SocketException -> Kind.MAC_UNREACHABLE
                is org.json.JSONException -> Kind.INVALID_RESPONSE
                else -> Kind.LOAD_FAILED
            }
            return ArtifactPreviewFailure(kind)
        }
    }
}

internal data class ArtifactFailureCopy(val title: String, val message: String, val retry: Boolean)
internal class ArtifactPreviewException(val failure: ArtifactPreviewFailure, message: String) : IllegalStateException(message)
