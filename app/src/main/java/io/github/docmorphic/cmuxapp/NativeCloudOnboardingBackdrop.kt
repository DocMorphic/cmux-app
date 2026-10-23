package io.github.docmorphic.cmuxapp

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.roundToInt

/** Text-free debug observation; decorative cells never become TalkBack stops. */
internal val CloudOnboardingMotionProbe = SemanticsPropertyKey<() -> Long>("CloudOnboardingMotionProbe")

@Composable internal fun cloudOnboardingReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f
    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private data class CloudColonyFrame(val cells: BooleanArray, val time: Double)

@Composable internal fun NativeCloudOnboardingBackdrop(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val dark = NativeAppColors.dark
    val background = if (dark) Color.Black else Color.White
    val cell = if (dark) Color(0xFF3A3A3C) else Color(0xFFAEAEB2)
    val colony = remember { CloudOnboardingColony() }
    // The state is read inside onDraw, so a tick invalidates the Canvas rather
    // than recomposing the pager, text, VPN control and footer at 12.5 Hz.
    val frame = remember { mutableStateOf(CloudColonyFrame(colony.cells, 0.0)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, reducedMotion) {
        if (reducedMotion) frame.value = CloudColonyFrame(colony.cells, 0.0)
        else lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(CloudOnboardingColony.TICK_MILLIS)
                colony.advance()
                frame.value = CloudColonyFrame(colony.cells, System.currentTimeMillis() / 1000.0 - 978307200.0)
            }
        }
    }
    Box(modifier.background(background).clearAndSetSemantics {
        if (BuildConfig.DEBUG) this[CloudOnboardingMotionProbe] = { colony.advances }
    }) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val current = frame.value
            val cellWidth = size.width / colony.columns; val cellHeight = size.height / colony.rows
            val dimension = min(cellWidth, cellHeight) * .52f
            val snapped = dimension.roundToInt().toFloat()
            for (row in 0 until colony.rows) for (col in 0 until colony.columns) {
                if (!current.cells[row * colony.columns + col]) continue
                val x = (col * cellWidth + (cellWidth - dimension) * .5f).roundToInt().toFloat()
                val y = (row * cellHeight + (cellHeight - dimension) * .5f).roundToInt().toFloat()
                drawRoundRect(cell.copy(alpha = CloudOnboardingColony.opacity(dark, reducedMotion, current.time, row, col)),
                    Offset(x, y), Size(snapped, snapped), CornerRadius(snapped * .5f))
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(background.copy(alpha = 0f),
                background.copy(alpha = if (dark) .82f else .70f)), startY = size.height * .5f, endY = size.height))
        }
    }
}
