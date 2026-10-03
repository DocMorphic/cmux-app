package io.github.docmorphic.cmuxapp.ghostty

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

/** Synthetic repeatable workload; measurements are evidence, not timing gates. */
class GhosttyGraphicsProfileTest {
    @Test fun unchangedOneMegabyteImageDuringTextUpdates() {
        GhosttyTerminal(80, 24).use { terminal ->
            terminal.resize(80, 24, 10, 20)
            val pixels = ByteArray(512 * 512 * 4) { if (it % 4 == 3) -1 else (it % 251).toByte() }
            val chunks = Base64.getEncoder().encodeToString(pixels).chunked(4096)
            chunks.forEachIndexed { index, chunk ->
                val header = if (index == 0) "a=T,f=32,s=512,v=512,i=1,p=1,c=4,r=4,C=1," else ""
                terminal.append("\u001b_G${header}m=${if (index == chunks.lastIndex) 0 else 1};$chunk\u001b\\".toByteArray())
            }
            val initial = terminal.graphicsSnapshot()
            assertArrayEquals(pixels, initial.images.getValue(1).pixels)
            repeat(20) { terminal.append("x".toByteArray()); terminal.graphicsSnapshot() }
            fun allocated() = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            fun collections() = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            val allocatedBefore = allocated(); val gcBefore = collections()
            val cpuBefore = Debug.threadCpuTimeNanos()
            val elapsed = LongArray(200)
            var sharedImages = 0
            for (index in elapsed.indices) {
                val start = System.nanoTime()
                terminal.append("x".toByteArray())
                val frame = terminal.graphicsSnapshot()
                elapsed[index] = System.nanoTime() - start
                if (frame.images.getValue(1) === initial.images.getValue(1)) sharedImages++
                assertEquals(initial.images.getValue(1).generation, frame.images.getValue(1).generation)
            }
            val cpuNanos = Debug.threadCpuTimeNanos() - cpuBefore
            val allocatedAfter = allocated(); val gcAfter = collections()
            elapsed.sort()
            val label = InstrumentationRegistry.getArguments().getString("profile_label", "current")
            require(label.matches(Regex("[a-z0-9-]+")))
            val report = JSONObject().put("imageBytes", pixels.size).put("updates", elapsed.size)
                .put("medianNanos", elapsed[elapsed.size / 2]).put("p95Nanos", elapsed[elapsed.size * 95 / 100])
                .put("cpuNanos", cpuNanos).put("sharedImageObjects", sharedImages)
                .put("allocatedBytes", if (allocatedBefore != null && allocatedAfter != null) allocatedAfter - allocatedBefore else JSONObject.NULL)
                .put("gcCount", if (gcBefore != null && gcAfter != null) gcAfter - gcBefore else JSONObject.NULL)
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "profiles").apply { mkdirs() }
            File(directory, "graphics-$label.json").writeText(report.toString(2))
            assertArrayEquals(pixels, terminal.graphicsSnapshot().images.getValue(1).pixels)
        }
    }
}
