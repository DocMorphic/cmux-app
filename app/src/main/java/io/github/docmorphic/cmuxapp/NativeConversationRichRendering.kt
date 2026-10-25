package io.github.docmorphic.cmuxapp

import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import kotlin.math.floor

internal fun nativeConversationSpan(run: NativeConversationTextRun, base: TextStyle, density: Density,
    ink: Color, hideEffects: Boolean): SpanStyle = with(density) {
    val decorations = buildList {
        if (run.style and NativeConversationTextStyle.UNDERLINE.mask != 0L) add(TextDecoration.Underline)
        if (run.style and NativeConversationTextStyle.STRIKETHROUGH.mask != 0L) add(TextDecoration.LineThrough)
    }
    SpanStyle(fontSize = (base.fontSize.toPx() * (run.effect?.let(NativeConversationTextEffectMotion::fontScale) ?: 1.0).toFloat()).toSp(),
        fontWeight = if (run.style and NativeConversationTextStyle.BOLD.mask != 0L) FontWeight.Bold else base.fontWeight,
        fontStyle = if (run.style and NativeConversationTextStyle.ITALIC.mask != 0L) FontStyle.Italic else base.fontStyle,
        textDecoration = if (decorations.isEmpty()) TextDecoration.None else TextDecoration.combine(decorations),
        color = if (hideEffects && run.effect != null) Color.Transparent else ink)
}

internal fun nativeConversationStyled(text: AnnotatedString, runs: List<NativeConversationTextRun>, base: TextStyle,
    density: Density, ink: Color, hideEffects: Boolean): AnnotatedString = AnnotatedString.Builder(text).apply {
    NativeConversationRichText.normalized(runs, text.length).forEach { run ->
        addStyle(nativeConversationSpan(run, base, density, ink, hideEffects), run.location, run.location + run.length)
    }
}.toAnnotatedString()

@Composable internal fun nativeRichMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0
    var enabled by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

internal data class NativeEffectUnit(val start: Int, val end: Int, val effect: NativeConversationTextEffect, val index: Int, val count: Int)
internal fun nativeConversationEffectUnits(text: String, runs: List<NativeConversationTextRun>, locale: java.util.Locale): List<NativeEffectUnit> {
    val groups = mutableListOf<NativeConversationTextRun>()
    for (run in NativeConversationRichText.normalized(runs, text.length)) {
        if (run.effect == null) continue
        val last = groups.lastOrNull()
        if (last != null && last.effect == run.effect && last.location + last.length == run.location)
            groups[groups.lastIndex] = last.copy(length = last.length + run.length)
        else groups += run
    }
    return groups.flatMap { run ->
        val value = text.substring(run.location, run.location + run.length)
        val words = run.length > 80
        val iterator = if (words) android.icu.text.BreakIterator.getWordInstance(locale)
            else android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
        iterator.setText(value)
        val pieces = mutableListOf<Pair<Int, Int>>()
        var start = iterator.first(); var end = iterator.next()
        while (end != android.icu.text.BreakIterator.DONE) {
            if ((!words || iterator.ruleStatus >= android.icu.text.BreakIterator.WORD_NUMBER) &&
                NativeChiefTextPolicy.hasContent(value.substring(start, end))) pieces += start to end
            start = end; end = iterator.next()
        }
        pieces.mapIndexed { index, (a, b) -> NativeEffectUnit(a + run.location, b + run.location, checkNotNull(run.effect), index, pieces.size) }
    }
}

private data class NativeEffectGlyph(val bitmap: Bitmap, val box: Rect, val frames: NativeConversationTextEffectMotion.Keyframes)

/** Unit images retain the real field's full layout and only ink the unit being animated.
 * Offscreen images are prepared when scrolling exposes them; their shared phase is retained. */
@Composable internal fun nativeConversationEffectOverlay(text: String, runs: List<NativeConversationTextRun>,
    base: TextStyle, ink: Color, layout: () -> TextLayoutResult?, animated: Boolean, seed: ULong = 0uL,
    origin: () -> Offset = { Offset.Zero }): Modifier {
    if (runs.none { it.effect != null }) return Modifier
    val density = LocalDensity.current
    val typeface by LocalFontFamilyResolver.current.resolve(base.fontFamily, base.fontWeight ?: FontWeight.Normal,
        base.fontStyle ?: FontStyle.Normal, FontSynthesis.All)
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val geometry = layout()?.size
    val phase = key(text, runs, base, ink, geometry, animated) {
        if (animated) {
            val transition = rememberInfiniteTransition(label = "cmuxTextEffects")
            transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "cmuxTextEffectCycle")
        } else remember { mutableFloatStateOf(1f) }
    }
    return Modifier.drawWithCache {
        val measured = layout()?.takeIf { it.layoutInput.text.text == text }
        val offset = origin()
        val glyphs = if (measured == null || measured.size.width <= 0) emptyList() else {
            nativeConversationEffectUnits(text, runs, locale).mapNotNull { unit ->
                val font = with(density) { base.fontSize.toPx() } * NativeConversationTextEffectMotion.fontScale(unit.effect).toFloat()
                val rect = measured.getPathForRange(unit.start, unit.end).getBounds()
                // Match the source's padded integral logical-point box, including italic overhang.
                val pad = ceil(font / density.density * .35f)
                val box = Rect(floor(rect.left / density.density - pad) * density.density,
                    floor(rect.top / density.density - pad) * density.density,
                    ceil(rect.right / density.density + pad) * density.density,
                    ceil(rect.bottom / density.density + pad) * density.density)
                if (box.width <= 0 || box.height <= 0 || box.bottom + offset.y + 2 * font < 0 || box.top + offset.y - 2 * font > size.height) return@mapNotNull null
                val inked = AnnotatedString.Builder(measured.layoutInput.text).apply {
                    addStyle(SpanStyle(color = Color.Transparent), 0, text.length)
                    addStyle(SpanStyle(color = ink), unit.start, unit.end)
                }.toAnnotatedString()
                val source = measured.layoutInput
                val rasterLayout = measurer.measure(inked, style = source.style, overflow = source.overflow, softWrap = source.softWrap,
                    maxLines = source.maxLines, placeholders = source.placeholders, constraints = source.constraints,
                    layoutDirection = source.layoutDirection, density = source.density, fontFamilyResolver = source.fontFamilyResolver, skipCache = true)
                val bitmap = Bitmap.createBitmap(ceil(box.width).toInt(), ceil(box.height).toInt(), Bitmap.Config.ARGB_8888)
                CanvasDrawScope().draw(density, source.layoutDirection, Canvas(bitmap.asImageBitmap()), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
                    translate(-box.left, -box.top) {
                        val underlines = inked.getStringAnnotations(ChiefDetectedTextRange, unit.start, unit.end)
                        if (underlines.isNotEmpty()) {
                            val underlinePaint = android.graphics.Paint().apply { this.typeface = typeface as android.graphics.Typeface; textSize = font }
                            val position = if (android.os.Build.VERSION.SDK_INT >= 29) underlinePaint.underlinePosition else underlinePaint.fontMetrics.descent / 2f
                            val thickness = (if (android.os.Build.VERSION.SDK_INT >= 29) underlinePaint.underlineThickness else font / 16f).coerceAtLeast(1f)
                            for (range in underlines) for (line in 0 until rasterLayout.lineCount) {
                                val start = maxOf(unit.start, range.start, rasterLayout.getLineStart(line))
                                val end = minOf(unit.end, range.end, rasterLayout.getLineEnd(line, visibleEnd = true))
                                if (end > start) clipPath(rasterLayout.getPathForRange(start, end)) {
                                    drawRect(ink.copy(alpha = nativeChiefUnderlineAlpha(ink)), Offset(0f, rasterLayout.getLineBaseline(line) + position), Size(rasterLayout.size.width.toFloat(), thickness))
                                }
                            }
                        }
                        drawText(rasterLayout)
                    }
                }
                NativeEffectGlyph(bitmap, box, NativeConversationTextEffectMotion.keyframes(unit.effect, unit.index, unit.count, font.toDouble(), seed))
            }
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val matrix = android.graphics.Matrix()
        onDrawWithContent {
            drawContent()
            clipRect {
                val canvas = drawContext.canvas.nativeCanvas
                for (glyph in glyphs) {
                    val pose = NativeConversationTextEffectMotion.sample(glyph.frames, phase.value.toDouble())
                    val center = glyph.box.center + offset
                    matrix.setValues(floatArrayOf(pose.a.toFloat(), pose.c.toFloat(), 0f,
                        pose.b.toFloat(), pose.d.toFloat(), 0f, 0f, 0f, 1f))
                    canvas.save()
                    canvas.translate(center.x + pose.dx.toFloat(), center.y + pose.dy.toFloat())
                    canvas.concat(matrix)
                    paint.alpha = (pose.opacity.coerceIn(0.0, 1.0) * 255).toInt()
                    canvas.drawBitmap(glyph.bitmap, null, android.graphics.RectF(-glyph.box.width / 2, -glyph.box.height / 2,
                        glyph.box.width / 2, glyph.box.height / 2), paint)
                    canvas.restore()
                }
            }
        }
    }
}
