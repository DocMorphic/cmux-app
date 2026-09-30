package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SimVideoPresenterTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private data class Fixture(val config: SimMessage.Config, val frames: List<SimMessage.Frame>)
    private fun fixture(codec: String): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().context
        val json = JSONObject(context.assets.open("simulator/$codec.json").bufferedReader().use { it.readText() })
        fun bytes(value: String) = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
        val sets = json.getJSONArray("parameter_sets")
        val frames = json.getJSONArray("frames")
        return Fixture(SimMessage.Config(if (codec == "h264") SimCodec.H264 else SimCodec.HEVC,
            json.getLong("width"), json.getLong("height"), 2f, SimOrientation.PORTRAIT,
            json.getInt("nal_header_length"), List(sets.length()) { bytes(sets.getString(it)) }),
            List(frames.length()) { i -> frames.getJSONObject(i).let {
                SimMessage.Frame(it.getLong("sequence").toULong(), if (it.getBoolean("keyframe")) 1 else 0,
                    (i * 100000).toULong(), bytes(it.getString("payload"))) } })
    }
    private fun surface(): CompletableDeferred<Surface> {
        val ready = CompletableDeferred<Surface>()
        compose.setContent { AndroidView(factory = { context -> SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { ready.complete(holder.surface) }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }
                override fun surfaceDestroyed(holder: SurfaceHolder) { }
            })
        } }, modifier = Modifier.size(192.dp, 288.dp)) }
        return ready
    }
    private suspend fun pixelCheck(surface: Surface, green: Boolean, name: String) {
        withTimeout(4000) {
            while (true) {
                val image = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
                val copied = CompletableDeferred<Int>()
                PixelCopy.request(surface, image, { copied.complete(it) }, Handler(Looper.getMainLooper()))
                if (copied.await() == PixelCopy.SUCCESS) {
                    var matched = 0; var samples = 0
                    for (y in 8 until 88 step 4) for (x in 8 until 56 step 4) {
                        val color = image.getPixel(x, y); samples++
                        if (if (green) Color.green(color) > 180 && Color.red(color) < 70 && Color.blue(color) < 70
                            else Color.red(color) > 180 && Color.green(color) < 70 && Color.blue(color) < 70) matched++
                    }
                    if (matched > samples * .9) {
                        val context = InstrumentationRegistry.getInstrumentation().targetContext
                        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle(); break
                    }
                }
                image.recycle(); delay(30)
            }
        }
    }

    @Test fun avcAndHevcKeyframesAndDependentFramesReachRealSurfacePixels() = runBlocking<Unit> {
        val output = withTimeout(5000) { surface().await() }
        val presenter = SimVideoPresenter(output)
        try {
            val available = SimVideoPresenter.availableCodecs()
            assertTrue("AVC missing", SimCodec.H264 in available); assertTrue("HEVC missing", SimCodec.HEVC in available)
            for (name in listOf("h264", "hevc")) {
                val data = fixture(name); presenter.configure(data.config)
                // New configurations must not decode a dependent frame against a previous decoder.
                assertFalse(presenter.present(data.frames[1]))
                assertTrue("$name keyframe failed", presenter.present(data.frames[0]))
                pixelCheck(output, false, "simulator-$name-red")
                assertTrue("$name dependent frame failed", presenter.present(data.frames[1]))
                pixelCheck(output, true, "simulator-$name-green")
                presenter.reset()
                assertFalse(presenter.present(data.frames[1]))
                assertTrue("$name reset keyframe failed", presenter.present(data.frames[0]))
                pixelCheck(output, false, "simulator-$name-reset")
            }
        } finally { presenter.awaitClosed() }
        assertTrue("Presenter released a view-owned Surface", output.isValid)
        assertFalse(presenter.present(fixture("h264").frames[0]))
    }

    @Test fun malformedFrameAndDestroyedSurfaceDoNotEarnPresentationCredit() = runBlocking<Unit> {
        val output = withTimeout(5000) { surface().await() }
        val presenter = SimVideoPresenter(output)
        try {
            val data = fixture("h264"); presenter.configure(data.config)
            assertFalse(presenter.present(data.frames[0].copy(payload = byteArrayOf(0, 0, 0, 4, 0x65))))
            assertFalse(presenter.present(data.frames[1]))
            assertTrue(presenter.present(data.frames[0]))
            pixelCheck(output, false, "simulator-recovered")
            compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
            withTimeout(4000) { while (output.isValid) delay(10) }
            assertFalse(presenter.present(data.frames[1]))
        } finally { presenter.awaitClosed() }
    }

    @Test fun nativeHandlesAreBoundedNonPointerIdsAndSafeAfterClose() = runBlocking<Unit> {
        val output = withTimeout(5000) { surface().await() }
        val data = fixture("h264")
        val format = SimVideoFormat.from(data.config)
        val csd = format.codecSpecificData().fold(byteArrayOf()) { all, bytes -> all + bytes }
        val handles = mutableListOf<Long>()
        try {
            repeat(4) { handles += SimVideoNative.create(1, 64, 96, csd).also { assertTrue(it > 0) } }
            assertEquals(0L, SimVideoNative.create(1, 64, 96, csd))
            val stale = handles.removeAt(0)
            SimVideoNative.destroy(stale); SimVideoNative.destroy(stale)
            assertFalse(SimVideoNative.render(stale, format.accessUnit(data.frames[0].payload), output, 1))
            val replacement = SimVideoNative.create(1, 64, 96, csd)
            assertTrue(replacement > stale); handles += replacement
            assertFalse(SimVideoNative.render(Long.MAX_VALUE, byteArrayOf(1), output, 1))
            assertEquals(0L, SimVideoNative.create(99, 64, 96, csd))
            assertEquals(0L, SimVideoNative.create(1, 8192, 8192, csd))
        } finally { handles.forEach(SimVideoNative::destroy) }
    }

    @Test fun framedSessionDisplaysAndAcknowledgesFirstFrameBeforeHostSendsAnother() = runBlocking<Unit> {
        val output = withTimeout(5000) { surface().await() }
        val presenter = SimVideoPresenter(output)
        val inbound = Channel<ByteArray>(4)
        val outbound = Channel<SimMessage>(4)
        var retired = false
        val lane = object : SimStreamLane {
            override suspend fun read() = inbound.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) { outbound.send(SimStreamWire.decode(bytes.copyOfRange(4, bytes.size))) }
            override fun close() { retired = true; inbound.close() }
        }
        val session = SimStreamSession(presenter, 1u, 2000, SimVideoPresenter.availableCodecs()) { }
        val run = launch { session.run(lane) }
        try {
            withTimeout(5000) {
                assertTrue(outbound.receive() is SimMessage.Start)
                val data = fixture("hevc")
                val first = SimStreamWire.encode(data.config) + SimStreamWire.encode(data.frames[0])
                // Include an arbitrary partial wire header, exercising the production reassembler.
                inbound.send(first.copyOfRange(0, 3)); inbound.send(first.copyOfRange(3, first.size))
                assertEquals(1uL, (outbound.receive() as SimMessage.Ack).sequence)
                pixelCheck(output, false, "simulator-session-first-frame")
                inbound.send(SimStreamWire.encode(data.frames[1]))
                assertEquals(2uL, (outbound.receive() as SimMessage.Ack).sequence)
                pixelCheck(output, true, "simulator-session-dependent-frame")
                inbound.close(); run.join(); assertTrue(retired)
            }
        } finally { run.cancelAndJoin(); presenter.awaitClosed() }
    }

    @Test fun controllerBackgroundReconnectAndLiveQualityDriveRealDecoderPixels() = runBlocking<Unit> {
        val output = withTimeout(5000) { surface().await() }
        val presenter = SimVideoPresenter(output)
        class Lane : SimStreamLane {
            val inbound = Channel<ByteArray>(Channel.UNLIMITED)
            val outbound = Channel<SimMessage>(Channel.UNLIMITED)
            var closed = false
            override suspend fun read() = inbound.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) {
                outbound.send(SimStreamWire.decode(bytes.copyOfRange(4, bytes.size)))
            }
            override fun close() { closed = true; inbound.close() }
            suspend fun host(message: SimMessage) { inbound.send(SimStreamWire.encode(message)) }
        }
        val opened = Channel<Lane>(Channel.UNLIMITED)
        val source = SimLaneSource { block ->
            val lane = Lane(); opened.send(lane)
            try { block(lane); true } finally { lane.close() }
        }
        val owner = SimViewerController(this, presenter)
        try {
            withTimeout(15000) {
                owner.bindSource(source); owner.activate()
                val first = opened.receive()
                val firstStart = first.outbound.receive() as SimMessage.Start
                assertEquals(2000, firstStart.maximumLongSide)
                val hevc = fixture("hevc")
                first.host(hevc.config); first.host(hevc.frames[0])
                assertEquals(1uL, (first.outbound.receive() as SimMessage.Ack).sequence)
                pixelCheck(output, false, "simulator-owner-first")
                owner.background()
                assertFalse(owner.input(SimInput.Button(SimButton.HOME)))
                owner.setQuality(SimQuality.DATA_SAVER); owner.foreground()
                val second = opened.receive() // New attach waits for the previous worker's reset.
                assertTrue(first.closed)
                assertEquals(SimMessage.Stop, first.outbound.receive())
                val secondStart = second.outbound.receive() as SimMessage.Start
                assertEquals(firstStart.epoch + 1u, secondStart.epoch)
                assertEquals(800, secondStart.maximumLongSide)
                second.host(hevc.config); second.host(hevc.frames[0]); second.host(hevc.frames[1])
                assertEquals(1uL, (second.outbound.receive() as SimMessage.Ack).sequence)
                assertEquals(2uL, (second.outbound.receive() as SimMessage.Ack).sequence)
                pixelCheck(output, true, "simulator-owner-reconnected")
                owner.setQuality(SimQuality.BALANCED)
                val quality = second.outbound.receive() as SimMessage.Start
                assertEquals(secondStart.epoch, quality.epoch); assertEquals(1280, quality.maximumLongSide)
                val avc = fixture("h264")
                second.host(avc.config); second.host(avc.frames[0])
                assertEquals(1uL, (second.outbound.receive() as SimMessage.Ack).sequence)
                pixelCheck(output, false, "simulator-owner-renegotiated")
                assertTrue(opened.tryReceive().isFailure) // Quality did not open another lane.
                assertEquals(SimViewerLifecycle.Phase.STREAMING, owner.state.value.phase)
                assertEquals(4uL, owner.state.value.presentedFrames)
            }
        } finally { owner.awaitClosed(); presenter.awaitClosed() }
        assertTrue(output.isValid)
    }
}
