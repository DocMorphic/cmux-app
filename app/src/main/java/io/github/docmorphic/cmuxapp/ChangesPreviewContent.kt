package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.sqrt
import kotlin.math.abs

@Composable
internal fun ChangesPreviewContent(artifact: ChangesPreviewArtifact) {
    FilePreviewContent(artifact.localPreview())
}

@Composable
internal fun FilePreviewContent(artifact: LocalFilePreview, remote: RemoteArtifactSource? = null,
    streaming: ArtifactStreamingText? = null, received: Long = artifact.size) {
    val context = LocalContext.current
    val state = rememberSaveable(artifact.file.absolutePath, saver = ArtifactViewerState.saver(context, artifact)) { ArtifactViewerState(context, artifact) }
    LaunchedEffect(state, streaming) {
        streaming?.let { state.document = it.document }
    }
    Column(Modifier.fillMaxSize()) {
        FilePreviewActions(artifact, state, remote, complete = streaming?.complete ?: true)
        if (streaming != null && !streaming.complete) Column(Modifier.padding(horizontal = 16.dp)) {
            LinearProgressIndicator(progress = { if (artifact.size > 0) (received.toFloat() / artifact.size).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
            Text("$received of ${artifact.size} bytes", fontSize = 12.sp)
        }
        Box(Modifier.weight(1f)) {
            key(artifact.file.absolutePath) {
                when (artifact.route) {
                    ChangesPreviewRoute.IMAGE -> ChangesImagePreview(artifact, remote)
                    ChangesPreviewRoute.PDF -> ChangesPdfPreview(artifact.file)
                    ChangesPreviewRoute.MEDIA -> ChangesMediaPreview(artifact.file)
                    ChangesPreviewRoute.TEXT -> ArtifactTextPreview(artifact, state, streaming)
                    ChangesPreviewRoute.EXTERNAL -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Preview unavailable", style = MaterialTheme.typography.titleMedium)
                        Text("Use Viewer actions to open, share or save this file.", color = changesMuted)
                    }
                }
            }
        }
    }
}

/** Parent paging remains available at minimum zoom. A zoomed page keeps one-finger pans locally. */
@Composable
internal fun PreviewZoom(modifier: Modifier = Modifier, onLongPress: ((Offset) -> Unit)? = null, doubleTapScale: Float = 3f,
    onContentTap: ((Offset) -> Unit)? = null, onContentLongPress: ((Offset) -> Unit)? = null, resetGeneration: Int = 0,
    initialTransform: PreviewZoomTransform = PreviewZoomTransform(), minimumScale: Float = 1f, onTransformChanged: ((PreviewZoomTransform) -> Unit)? = null,
    content: @Composable (Modifier) -> Unit) {
    var position by rememberSaveable(stateSaver = listSaver<PreviewZoomTransform, Float>(
        save = { listOf(it.scale, it.x, it.y) },
        restore = { PreviewZoomTransform(it[0], it[1], it[2]) }
    )) { mutableStateOf(initialTransform) }
    var lastReset by rememberSaveable { mutableIntStateOf(resetGeneration) }
    LaunchedEffect(resetGeneration) { if (lastReset != resetGeneration) { position = initialTransform; lastReset = resetGeneration } }
    SideEffect { onTransformChanged?.invoke(position) }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    val latestLongPress by rememberUpdatedState(onLongPress)
    val latestContentTap by rememberUpdatedState(onContentTap)
    val latestContentLongPress by rememberUpdatedState(onContentLongPress)
    fun contentPoint(point: Offset): Offset? = position.contentPoint(point.x, point.y, width, height)?.let { Offset(it.first, it.second) }
    fun applyTransform(zoom: Float, pan: Offset, centroid: Offset = Offset(width / 2f, height / 2f)) {
        if (width > 0 && height > 0) position = position.transform(zoom, pan.x / width, pan.y / height,
            centroid.x / width - .5f, centroid.y / height - .5f, minimumScale)
    }
    val transform = rememberTransformableState { zoom, pan, _ -> applyTransform(zoom, pan) }
    Box(modifier.clipToBounds().onSizeChanged { width = it.width; height = it.height }
        .pointerInput(Unit) {
            // Claim touch transforms before AndroidView/pager handlers consume the
            // movement. At minimum zoom, leave one-finger swipes to the parent.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var accumulatedPan = Offset.Zero
                var accumulatedZoom = 1f
                var claimed = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.isConsumed }) break
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    accumulatedPan += pan; accumulatedZoom *= zoom
                    val multiple = event.changes.count { it.pressed } > 1
                    val zoomDistance = abs(accumulatedZoom - 1) * event.calculateCentroidSize(useCurrent = false)
                    if (!claimed) claimed = (!position.atMinimum && accumulatedPan.getDistance() > viewConfiguration.touchSlop) ||
                        (multiple && zoomDistance > viewConfiguration.touchSlop)
                    if (claimed) {
                        applyTransform(zoom, pan, event.calculateCentroid(useCurrent = false))
                        event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        // Retain Foundation's non-touch (Ctrl+wheel) transform support.
        .transformable(transform, canPan = { !position.atMinimum })
        .pointerInput(onLongPress != null, onContentTap != null, onContentLongPress != null) { detectTapGestures(
            onTap = if (onContentTap != null) { point -> contentPoint(point)?.let { latestContentTap?.invoke(it) } } else null,
            onLongPress = if (onLongPress != null || onContentLongPress != null) { point ->
                latestLongPress?.invoke(point); contentPoint(point)?.let { latestContentLongPress?.invoke(it) }
            } else null,
            onDoubleTap = { point -> if (width > 0 && height > 0) position = position.doubleTap(point.x / width - .5f, point.y / height - .5f, doubleTapScale) }
        ) }) {
        content(Modifier.fillMaxSize().graphicsLayer { scaleX = position.scale; scaleY = position.scale; translationX = position.x * width; translationY = position.y * height })
    }
}

@Composable
private fun ChangesImagePreview(artifact: LocalFilePreview, remote: RemoteArtifactSource?) {
    val file = artifact.file
    val actions = filePreviewActionHandler(artifact, remote)
    var menuAnchor by remember(file) { mutableStateOf<Offset?>(null) }
    LaunchedEffect(actions.enabled) { if (!actions.enabled) menuAnchor = null }
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
        } catch (error: Exception) { if (error is CancellationException) throw error; failure = "This image can’t be read. Reopen its preview, or use Open to try another app." }
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
    else Box(Modifier.fillMaxSize()) {
        PreviewZoom(Modifier.fillMaxSize().semantics {
            contentDescription = "Image preview ${file.name}"
            if (actions.enabled) onLongClick(label = "Image actions") { menuAnchor = Offset.Zero; true }
        }, onLongPress = { point -> if (actions.enabled) menuAnchor = point }) { modifier ->
            AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } }, modifier = modifier,
                update = { view -> if (view.drawable !== drawable) { (view.drawable as? Animatable)?.stop(); view.setImageDrawable(drawable); (drawable as? Animatable)?.start() } },
                onRelease = { (it.drawable as? Animatable)?.stop(); it.setImageDrawable(null) })
        }
        val anchor = menuAnchor
        if (anchor != null) Box(Modifier.offset { IntOffset(anchor.x.toInt(), anchor.y.toInt()) }) {
            DropdownMenu(true, { menuAnchor = null }) {
                FilePreviewAction.imageMenu.forEach { action ->
                    DropdownMenuItem(text = { Text(action.label) }, enabled = actions.enabled,
                        onClick = { menuAnchor = null; actions.perform(action) })
                }
            }
        }
    }
}
