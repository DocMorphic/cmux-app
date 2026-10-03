package io.github.docmorphic.cmuxapp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the decoder, not the view's Surface. All codec calls execute on one private worker. */
internal class SimVideoPresenter(private val surface: Surface, private val frameTimeoutMillis: Long = 2000) : SimFramePresenter, AutoCloseable {
    private val thread = HandlerThread("cmux-simulator-video").apply { start() }
    private val dispatcher = Handler(thread.looper).asCoroutineDispatcher("cmux-simulator-video")
    private val cleanup = CoroutineScope(SupervisorJob() + dispatcher)
    private val serial = Mutex()
    private val closed = AtomicBoolean()
    private val released = CompletableDeferred<Unit>()
    private var codec: MediaCodec? = null
    private var software: SimSoftwareDecoder? = null
    private var preferSoftware = false
    private var format: SimVideoFormat? = null
    private var needsKeyframe = true
    private var nextPresentationTime = 1L

    override suspend fun configure(config: SimMessage.Config) = withContext(dispatcher) { serial.withLock {
        requireSurface()
        // A config boundary (rotation, device switch, codec change) owns a fresh decoder.
        val validated = SimVideoFormat.from(config)
        releaseDecoder(); format = validated; needsKeyframe = true; preferSoftware = false
        try { initializeDecoder(validated) }
        catch (failure: Throwable) { releaseDecoder(); throw failure }
    } }

    override suspend fun present(frame: SimMessage.Frame): Boolean = if (closed.get()) false else withContext(dispatcher) { serial.withLock {
        if (closed.get() || !surface.isValid) return@withLock false
        val current = format ?: return@withLock false
        if (needsKeyframe && !frame.keyframe) return@withLock false
        try {
            val bytes = current.accessUnit(frame.payload)
            withTimeout(frameTimeoutMillis) {
                requireSurface()
                if (codec == null && software == null) initializeDecoder(current)
                val fallback = software
                if (fallback != null) {
                    val shown = fallback.render(bytes, surface, nextPresentationTime++)
                    requireSurface()
                    if (shown) needsKeyframe = false
                    else { releaseDecoder(); needsKeyframe = true }
                    return@withTimeout shown
                }
                val decoder = checkNotNull(codec)
                val input = awaitIndex { decoder.dequeueInputBuffer(0) }
                requireSurface()
                val buffer = decoder.getInputBuffer(input) ?: throw IOException("Simulator decoder input unavailable")
                if (buffer.capacity() < bytes.size) throw IOException("Simulator frame exceeds decoder input capacity")
                buffer.clear(); buffer.put(bytes)
                // Display immediately. The host's clock and UInt64 sequence are not Android presentation timestamps.
                val pts = nextPresentationTime++
                decoder.queueInputBuffer(input, 0, bytes.size, pts, if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    requireSurface(); ensureActive()
                    val output = decoder.dequeueOutputBuffer(info, 0)
                    when {
                        output >= 0 -> {
                            val frameOutput = info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
                            val matches = frameOutput && info.presentationTimeUs == pts
                            requireSurface()
                            decoder.releaseOutputBuffer(output, matches)
                            if (matches) {
                                // Like iOS's healthy display-layer enqueue, this is presentation admission.
                                // OnFrameRendered is informational and can be delayed/batched, so it cannot gate host credit.
                                requireSurface(); needsKeyframe = false
                                return@withTimeout true
                            }
                        }
                        output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> validateOutput(decoder.outputFormat)
                        else -> delay(2)
                    }
                }
                @Suppress("UNREACHABLE_CODE") false
            }
        } catch (failure: Throwable) {
            val failedHardware = codec != null
            releaseDecoder(); needsKeyframe = true
            if (failure is CancellationException) {
                currentCoroutineContext().ensureActive()
                if (failure !is TimeoutCancellationException) throw failure
            }
            if (failure is Error) throw failure
            if (failedHardware && !closed.get() && surface.isValid) {
                preferSoftware = true
                // Video replay is local: the frame has not earned an acknowledgement or sent any user input.
                if (frame.keyframe) try {
                    val fallback = SimSoftwareDecoder(current).also { software = it }
                    val shown = fallback.render(current.accessUnit(frame.payload), surface, nextPresentationTime++)
                    requireSurface(); needsKeyframe = !shown
                    return@withLock shown
                } catch (_: Exception) { releaseDecoder() }
            }
            false
        }
    } }

    override suspend fun reset() = withContext(dispatcher) { serial.withLock {
        requireSurface(); releaseDecoder(); needsKeyframe = true
    } }

    private suspend fun awaitIndex(get: () -> Int): Int {
        while (true) { requireSurface(); currentCoroutineContext().ensureActive(); val index = get(); if (index >= 0) return index; delay(2) }
    }

    private fun initializeDecoder(video: SimVideoFormat) {
        codec = if (preferSoftware) null else createDecoder(video)
        if (codec == null) { preferSoftware = true; software = SimSoftwareDecoder(video) }
    }

    private fun createDecoder(video: SimVideoFormat): MediaCodec? {
        requireSurface()
        val base = MediaFormat.createVideoFormat(video.mime, video.width, video.height)
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            Build.VERSION.SDK_INT >= 30 && !info.isEncoder && info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals(video.mime, true) } && runCatching {
                    val caps = info.getCapabilitiesForType(video.mime)
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) && caps.isFormatSupported(base)
                }.getOrDefault(false)
        }
        for (candidate in candidates) {
            var decoder: MediaCodec? = null
            try {
                requireSurface()
                val media = MediaFormat.createVideoFormat(video.mime, video.width, video.height).apply {
                    video.codecSpecificData().forEachIndexed { index, data -> setByteBuffer("csd-$index", ByteBuffer.wrap(data)) }
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SimVideoFormat.MAX_ACCESS_UNIT)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= 30 && candidate.getCapabilitiesForType(video.mime)
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                decoder = MediaCodec.createByCodecName(candidate.name)
                decoder.configure(media, surface, null, 0)
                decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                decoder.start(); requireSurface()
                return decoder
            } catch (failure: Exception) {
                runCatching { decoder?.release() }
                requireSurface()
            }
        }
        return null
    }

    private fun validateOutput(output: MediaFormat) {
        val width = output.getInteger(MediaFormat.KEY_WIDTH)
        val height = output.getInteger(MediaFormat.KEY_HEIGHT)
        if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 16_777_216)
            throw IOException("Invalid decoded simulator dimensions")
    }
    private fun requireSurface() { check(!closed.get() && surface.isValid) { "Simulator surface closed" } }
    private fun releaseDecoder() {
        val old = codec; codec = null
        if (old != null) { runCatching { old.stop() }; runCatching { old.release() } }
        software?.close(); software = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cleanup.launch {
            try { serial.withLock { releaseDecoder(); format = null } }
            finally { released.complete(Unit); thread.quitSafely(); cleanup.cancel() }
        }
    }
    suspend fun awaitClosed() { close(); released.await() }

    companion object {
        fun availableCodecs(): List<SimCodec> {
            // Both codecs have a packaged software implementation; hardware support is negotiated per format.
            return listOf(SimCodec.HEVC, SimCodec.H264)
        }
    }
}
