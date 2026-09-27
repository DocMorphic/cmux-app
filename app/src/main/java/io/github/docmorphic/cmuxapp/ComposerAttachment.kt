package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.UUID

/** The payload is an app-owned file; only this metadata is kept in draft state. */
data class ComposerAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val size: Int,
    val imageFormat: String? = null
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("size", size)
        .put("image_format", imageFormat ?: JSONObject.NULL)

    companion object {
        const val FILE_LIMIT = 32 * 1024 * 1024
        // Leave room for base64 and RPC metadata inside the 8 MiB control frame.
        const val IMAGE_LIMIT = 5 * 1024 * 1024
        const val TERMINAL_BYTES = 32 * 1024 * 1024
        const val TOTAL_BYTES = 64 * 1024 * 1024
        const val FILE_CAPABILITY = "task.attachments.v1"
        fun read(value: JSONObject, imageLimit: Int = IMAGE_LIMIT, allowEmpty: Boolean = false): ComposerAttachment? {
            val id = value.optString("id")
            if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) return null
            val format = value.optString("image_format").takeIf { it == "png" || it == "jpg" }
            val size = value.optInt("size")
            if (size !in (if (allowEmpty && format == null) 0 else 1)..(if (format == null) FILE_LIMIT else imageLimit)) return null
            return ComposerAttachment(id, value.optString("name").take(255), size, format)
        }
        fun withPaths(paths: List<String>, text: String): String = buildString {
            paths.forEach { path ->
                require(path.startsWith('/') && '\u0000' !in path) { "Invalid attachment path from Mac" }
                append("'").append(path.replace("'", "'\\''")).append("' ")
            }
            append(text)
        }
    }
}
