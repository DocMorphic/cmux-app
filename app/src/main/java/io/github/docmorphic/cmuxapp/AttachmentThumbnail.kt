package io.github.docmorphic.cmuxapp

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AttachmentThumbnail(attachment: ComposerAttachment, repository: TerminalDraftRepository) =
    AttachmentThumbnail(attachment, read = repository::read)

@Composable
fun AttachmentThumbnail(attachment: ComposerAttachment, read: suspend (ComposerAttachment) -> ByteArray,
    modifier: Modifier = Modifier.size(36.dp), contentScale: ContentScale = ContentScale.Crop, owner: Any? = null) {
    val thumbnail = key(owner, attachment) { produceState<ImageBitmap?>(null) {
        if (attachment.imageFormat != null) {
            try {
                val bytes = read(attachment)
                value = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
            }
        }
    }.value }
    if (thumbnail != null) Image(thumbnail, attachment.name, modifier, contentScale = contentScale)
    else androidx.compose.foundation.layout.Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) { Text(if (attachment.imageFormat == null) "▤" else "▧") }
}
