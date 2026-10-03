package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SshAttachmentThumbnail(draft: SshComposerPool.Draft, attachment: ComposerAttachment) {
    var thumbnail by remember(draft, attachment.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(draft, attachment.id) {
        val image = withContext(Dispatchers.IO) {
            val bytes = runCatching { draft.read(attachment) }.getOrNull() ?: return@withContext null
            try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 8 }) }
            finally { bytes.fill(0) }
        }
        thumbnail = image
    }
    // Do not recycle a Bitmap still referenced by an Android render display list.
    // Once this image leaves composition its small downsample is collected normally.
    thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.size(28.dp), contentScale = ContentScale.Crop) }
}
