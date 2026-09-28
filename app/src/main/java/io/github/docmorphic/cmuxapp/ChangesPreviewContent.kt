package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.sqrt

@Composable
internal fun ChangesPreviewContent(artifact: ChangesPreviewArtifact) {
    FilePreviewContent(artifact.localPreview())
}

@Composable
internal fun FilePreviewContent(artifact: LocalFilePreview) {
    when (artifact.route) {
        ChangesPreviewRoute.IMAGE -> ChangesImagePreview(artifact.file)
        ChangesPreviewRoute.PDF -> ChangesPdfPreview(artifact.file)
        ChangesPreviewRoute.MEDIA -> ChangesMediaPreview(artifact.file)
        ChangesPreviewRoute.TEXT -> ChangesTextPreview(artifact.file)
        ChangesPreviewRoute.EXTERNAL -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Preview unavailable", style = MaterialTheme.typography.titleMedium)
            Text("Use File actions to open, share or save this file.", color = changesMuted)
        }
    }
}

/** Parent paging remains available at minimum zoom. A zoomed page keeps one-finger pans locally. */
@Composable
private fun PreviewZoom(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = Offset((offset.x + pan.x).coerceIn(-width * (scale - 1) / 2, width * (scale - 1) / 2),
            (offset.y + pan.y).coerceIn(-height * (scale - 1) / 2, height * (scale - 1) / 2))
        if (scale == 1f) offset = Offset.Zero
    }
    Box(modifier.clipToBounds().onSizeChanged { width = it.width; height = it.height }
        .transformable(transform, canPan = { scale > 1f })
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2f; offset = Offset.Zero }) }) {
        content(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
    }
}

@Composable
private fun ChangesImagePreview(file: File) {
    val resources = LocalContext.current.resources
    var failure by remember(file) { mutableStateOf<String?>(null) }
    val drawable by produceState<Drawable?>(null, file) {
        try {
            value = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val size = info.size
                    val factor = maxOf(1.0, sqrt(size.width.toDouble() * size.height / 16_000_000.0))
                    decoder.setTargetSize(maxOf(1, (size.width / factor).toInt()), maxOf(1, (size.height / factor).toInt()))
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inSampleSize = 1 }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    check(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image format." }
                    while (options.outWidth.toLong() / options.inSampleSize * (options.outHeight / options.inSampleSize) > 16_000_000) options.inSampleSize *= 2
                    options.inJustDecodeBounds = false
                    BitmapDrawable(resources, checkNotNull(BitmapFactory.decodeFile(file.absolutePath, options)) { "Could not decode image." })
                }
            }
        } catch (error: Exception) { if (error is CancellationException) throw error; failure = error.message ?: "Could not decode image." }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, drawable) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) (drawable as? Animatable)?.stop()
            if (event == Lifecycle.Event.ON_START) (drawable as? Animatable)?.start()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); (drawable as? Animatable)?.stop() }
    }
    if (failure != null) ChangesNotice("Preview unavailable", failure.orEmpty())
    else if (drawable == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else PreviewZoom(Modifier.fillMaxSize().semantics { contentDescription = "Image preview ${file.name}" }) { modifier ->
        AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } }, modifier = modifier,
            update = { view -> if (view.drawable !== drawable) { (view.drawable as? Animatable)?.stop(); view.setImageDrawable(drawable); (drawable as? Animatable)?.start() } },
            onRelease = { (it.drawable as? Animatable)?.stop(); it.setImageDrawable(null) })
    }
}

internal class ChangesPdfDocument(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
    private var closed = false
    val pageSizes: List<Pair<Int, Int>> = try { (0 until renderer.pageCount).map { index -> renderer.openPage(index).use { it.width to it.height } } }
        catch (error: Throwable) { renderer.close(); descriptor.close(); throw error }
    @Synchronized fun render(index: Int, width: Int): Bitmap {
        check(!closed)
        return renderer.openPage(index).use { page ->
            val scale = minOf(width.coerceIn(1, 2560).toDouble() / page.width,
                sqrt(8_000_000.0 / (page.width.toDouble() * page.height)))
            val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bitmap }
            catch (error: Throwable) { bitmap.recycle(); throw error }
        }
    }
    @Synchronized override fun close() { if (!closed) { closed = true; renderer.close(); descriptor.close() } }
}

@Composable
private fun ChangesPdfPreview(file: File) {
    var failure by remember(file) { mutableStateOf<String?>(null) }
    val document by produceState<ChangesPdfDocument?>(null, file) {
        var owned: ChangesPdfDocument? = null
        try {
            withContext(Dispatchers.IO) { owned = ChangesPdfDocument(file) }
            value = owned
            awaitCancellation()
        } catch (error: Exception) { currentCoroutineContext().ensureActive(); failure = error.message ?: "Could not read PDF." }
        finally { withContext(NonCancellable + Dispatchers.IO) { owned?.close() } }
    }
    val pdf = document
    if (failure != null) ChangesNotice("Preview unavailable", failure.orEmpty())
    else if (pdf == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else {
        val scroll = rememberLazyListState()
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                TextButton(enabled = scroll.firstVisibleItemIndex > 0, onClick = { scope.launch { scroll.animateScrollToItem(scroll.firstVisibleItemIndex - 1) } }) { Text("Previous page") }
                Text("${scroll.firstVisibleItemIndex + 1} / ${pdf.pageSizes.size}", fontSize = 12.sp)
                TextButton(enabled = scroll.firstVisibleItemIndex < pdf.pageSizes.lastIndex, onClick = { scope.launch { scroll.animateScrollToItem(scroll.firstVisibleItemIndex + 1) } }) { Text("Next page") }
            }
            LazyColumn(Modifier.weight(1f), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pdf.pageSizes.size, key = { it }) { index ->
                    val size = pdf.pageSizes[index]
                    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(size.first.toFloat() / size.second)
                        .semantics { contentDescription = "PDF page ${index + 1} of ${pdf.pageSizes.size}" }) {
                        val width = with(LocalDensity.current) { maxWidth.roundToPx() }
                        var pageFailure by remember { mutableStateOf<String?>(null) }
                        val bitmap by produceState<Bitmap?>(null, pdf, index, width) {
                            try { value = withContext(Dispatchers.IO) { pdf.render(index, width * 2) } }
                            catch (error: Exception) { if (error is CancellationException) throw error; pageFailure = "Could not render this PDF page." }
                        }
                        if (bitmap != null) PreviewZoom(Modifier.fillMaxSize()) { Image(bitmap!!.asImageBitmap(), null, it) }
                        else if (pageFailure != null) Text(pageFailure!!)
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangesMediaPreview(file: File) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<VideoView?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(view) { while (isActive) { playing = view?.isPlaying == true; delay(250) } }
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { view?.pause(); playing = false } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize()) {
        if (failure != null) ChangesNotice("Preview unavailable", failure.orEmpty())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(enabled = prepared, onClick = { if (view?.isPlaying == true) view?.pause() else view?.start(); playing = view?.isPlaying == true }) { Text(if (playing) "Pause" else "Play") }
            TextButton(enabled = prepared, onClick = { view?.seekTo(0); view?.pause(); playing = false }) { Text("Restart") }
        }
        AndroidView(factory = { context -> VideoView(context).apply {
            view = this
            setMediaController(MediaController(context).also { it.setAnchorView(this) })
            setOnPreparedListener { prepared = true }
            setOnCompletionListener { playing = false }
            setOnErrorListener { _, _, _ -> failure = "Android could not play this media format. Use Open in File actions to choose another player."; true }
            setVideoURI(Uri.fromFile(file))
        } }, modifier = Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "Media preview ${file.name}" },
            onRelease = { it.stopPlayback(); view = null })
    }
}

@Composable
private fun ChangesTextPreview(file: File) {
    var failure by remember(file) { mutableStateOf<String?>(null) }
    val lines by produceState<List<String>?>(null, file) {
        try { value = withContext(Dispatchers.IO) { file.bufferedReader().use { it.readLines() } } }
        catch (error: Exception) { if (error is CancellationException) throw error; failure = error.message ?: "Could not read text." }
    }
    if (failure != null) ChangesNotice("Preview unavailable", failure.orEmpty())
    else if (lines == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(lines!!.size) { index -> Row {
            Text("${index + 1}", Modifier.width(48.dp), color = changesMuted, fontSize = 12.sp)
            SelectionContainer { Text(lines!![index], fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        } }
    }
}
