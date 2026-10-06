package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Staged payloads never depend on a provider grant or the original file after import. */
class AttachmentFiles(private val context: Context, taskFiles: Boolean = false) {
    private val directory = File(context.noBackupFilesDir, if (taskFiles) "task-attachments" else "terminal-attachments").apply { mkdirs() }
    data class Prepared(val attachment: ComposerAttachment, val bytes: ByteArray)

    /** Photo pickers can return movies and opaque future media: only declared images are decoded. */
    suspend fun isPhotoImage(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.getType(uri)?.substringBefore(';')?.trim()?.startsWith("image/", ignoreCase = true) == true
    }

    suspend fun prepare(uri: Uri, image: Boolean, imageLimit: Int = ComposerAttachment.IMAGE_LIMIT, allowEmpty: Boolean = false): Prepared = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = if (image) "image.png" else "attachment"
        val limit = if (image) 60 * 1024 * 1024 else ComposerAttachment.FILE_LIMIT
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = it.getString(nameIndex)?.take(255) ?: name
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                require(sizeIndex < 0 || it.isNull(sizeIndex) || it.getLong(sizeIndex) <= limit) {
                    if (image) "Choose an image smaller than 60 MiB" else "Choose a file smaller than 32 MiB"
                }
            }
        }
        val raw = File.createTempFile("cmux-import-", ".tmp", context.cacheDir)
        try {
            var count = 0L
            resolver.openInputStream(uri)?.use { input -> raw.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= limit) { "The selected attachment is too large" }
                    output.write(buffer, 0, read)
                }
            } } ?: error("Could not open the selected attachment")
            require(allowEmpty || count > 0) { "The selected attachment is empty" }
            val (bytes, format) = if (image) prepareImage(raw, imageLimit) else raw.readBytes() to null
            coroutineContext.ensureActive()
            Prepared(ComposerAttachment(name = name, size = bytes.size, imageFormat = format), bytes)
        } finally { raw.delete() }
    }

    fun write(prepared: Prepared) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val file = file(prepared.attachment.id)
        try {
            file.outputStream().use { out ->
                out.write(cipher.iv.size); out.write(cipher.iv); out.write(cipher.doFinal(prepared.bytes))
            }
        } catch (failure: Exception) { file.delete(); throw failure }
    }

    fun read(attachment: ComposerAttachment): ByteArray {
        val bytes = file(attachment.id).readBytes()
        require(bytes.size == attachment.size + 29 && bytes[0].toInt() == 12) { "Attachment data is unavailable; remove it and attach it again" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        }
        return cipher.doFinal(bytes, 13, bytes.size - 13)
    }

    fun retain(ids: Set<String>) { directory.listFiles()?.filter { it.name !in ids }?.forEach { it.delete() } }
    fun delete(id: String) { file(id).delete() }
    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, id)
    }

    private fun prepareImage(file: File, imageLimit: Int): Pair<ByteArray, String> {
        var bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val scale = minOf(1.0, 2048.0 / max(info.size.width, info.size.height))
                decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
            val options = BitmapFactory.Options()
            while (max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
            val decoded = BitmapFactory.decodeFile(file.path, options) ?: error("Unsupported image")
            val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }
                    8 -> setRotate(270f)
                }
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        try {
            fun encode(format: Bitmap.CompressFormat, quality: Int): ByteArray = ByteArrayOutputStream().use {
                check(bitmap.compress(format, quality, it)) { "Could not prepare image" }; it.toByteArray()
            }
            val png = encode(Bitmap.CompressFormat.PNG, 100)
            if (png.size <= imageLimit) return png to "png"
            for (quality in listOf(80, 60, 40)) {
                val jpeg = encode(Bitmap.CompressFormat.JPEG, quality)
                if (jpeg.size <= imageLimit) return jpeg to "jpg"
            }
            for (maxSize in listOf(1536, 1024, 768)) {
                val scale = minOf(1f, maxSize.toFloat() / max(bitmap.width, bitmap.height))
                val smaller = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), true)
                if (smaller !== bitmap) { bitmap.recycle(); bitmap = smaller }
                val jpeg = encode(Bitmap.CompressFormat.JPEG, 50)
                if (jpeg.size <= imageLimit) return jpeg to "jpg"
            }
            error("Image is too large to send")
        } finally { bitmap.recycle() }
    }

    companion object {
        @Synchronized private fun key(): SecretKey {
            val name = "cmux_terminal_attachments_v1"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(name, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}
