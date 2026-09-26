package io.github.docmorphic.cmuxapp

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AttachmentThumbnail(attachment: ComposerAttachment, repository: TerminalDraftRepository) {
    val thumbnail = produceState<ImageBitmap?>(null, attachment.id) {
        if (attachment.imageFormat != null) {
            try {
                val bytes = repository.read(attachment)
                value = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
            }
        }
    }.value
    if (thumbnail != null) Image(thumbnail, attachment.name, Modifier.size(36.dp), contentScale = ContentScale.Crop)
    else Text(if (attachment.imageFormat == null) "▤" else "▧")
}
