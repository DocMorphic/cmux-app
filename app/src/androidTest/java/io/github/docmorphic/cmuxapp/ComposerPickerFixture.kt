package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.IntentFilter
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/** Match the installed AndroidX contract's real action, including its document-provider fallback. */
internal fun composerPickerFilter(context: Context, photos: Boolean): IntentFilter {
    val intent = if (photos) ActivityResultContracts.PickMultipleVisualMedia(10).createIntent(context,
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    else ActivityResultContracts.OpenMultipleDocuments().createIntent(context, arrayOf("*/*"))
    return IntentFilter(checkNotNull(intent.action)).apply {
        intent.type?.let(::addDataType)
        intent.categories.orEmpty().forEach(::addCategory)
    }
}

/** Deliberately opaque video bytes test file routing/preservation, not video decoding. */
internal class ComposerMediaFixture(private val context: Context) : AutoCloseable {
    private val root = java.io.File(context.cacheDir, "task-previews/media-picker-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    val video = java.io.File(root, "clip.mp4").apply { writeBytes(ByteArray(512) { (it % 251).toByte() }) }
    val photo = java.io.File(root, "photo.png").also { file ->
        android.graphics.Bitmap.createBitmap(48, 24, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.GREEN)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private val missing = java.io.File(root, "missing.png")
    private fun uri(file: java.io.File) = androidx.core.content.FileProvider.getUriForFile(context,
        "${context.packageName}.task-previews", file)
    fun result(): android.content.Intent {
        val clip = android.content.ClipData("Mixed media", arrayOf("image/png", "video/mp4"), android.content.ClipData.Item(uri(missing)))
        clip.addItem(android.content.ClipData.Item(uri(video))); clip.addItem(android.content.ClipData.Item(uri(photo)))
        return android.content.Intent().apply { clipData = clip; addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    override fun close() { root.deleteRecursively() }
}
