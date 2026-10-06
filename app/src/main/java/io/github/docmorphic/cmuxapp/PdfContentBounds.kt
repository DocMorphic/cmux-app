package io.github.docmorphic.cmuxapp

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import com.tom_roush.harmony.awt.geom.AffineTransform
import java.io.IOException

/** Geometric marks, including white paint, vectors and images; never a text-only or pixel-color crop. */
internal class PdfContentBounds(page: PDPage) : PDFGraphicsStreamEngine(page) {
    private var path = Path()
    private val current = PointF()
    private val start = PointF()
    private var pendingClip: Path.FillType? = null
    private var textClip: Path? = null
    private var bounds: RectF? = null
    private var operators = 0
    private var glyphs = 0
    private var depth = 0

    fun read(geometry: PdfPageCoordinates): PdfTextBounds? {
        processPage(page)
        return bounds?.let { geometry.bounds(listOf(it.left to it.top, it.left to it.bottom,
            it.right to it.top, it.right to it.bottom)) }
    }
    override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
        if (++operators > 200_000 || Thread.currentThread().isInterrupted) throw IOException("PDF page content is too complex")
        super.processOperator(operator, operands)
    }
    // The default stream engine can swallow malformed-image/form failures. Partial bounds could clip real content.
    override fun operatorException(operator: Operator, operands: MutableList<COSBase>, error: IOException) { throw error }

    private fun record(shape: Path) {
        val originalBounds = RectF().also { shape.computeBounds(it, true) }
        if (!listOf(originalBounds.left, originalBounds.top, originalBounds.right, originalBounds.bottom).all(Float::isFinite))
            throw IOException("PDF content coordinates are invalid")
        val clipped = Path(shape)
        for (clip in graphicsState.currentClippingPaths) {
            if (!clipped.op(clip, Path.Op.INTERSECT)) throw IOException("PDF clipping could not be resolved")
        }
        if (clipped.isEmpty) return
        val rect = RectF().also { clipped.computeBounds(it, true) }
        if (!listOf(rect.left, rect.top, rect.right, rect.bottom).all(Float::isFinite))
            throw IOException("PDF content coordinates are invalid")
        if (!rect.isEmpty) { if (bounds == null) bounds = rect else bounds!!.union(rect) }
    }
    private fun stroke(shape: Path) {
        val state = graphicsState
        // Work in the stroke's user space so anisotropic CTMs also transform caps/joins correctly.
        val matrix = state.currentTransformationMatrix.createAffineTransform().toMatrix()
        val inverse = android.graphics.Matrix()
        if (!matrix.invert(inverse)) throw IOException("PDF stroke transform is singular")
        val source = Path(shape).apply { transform(inverse) }
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = state.lineWidth.coerceAtLeast(.25f)
            strokeCap = state.lineCap; strokeJoin = state.lineJoin; strokeMiter = state.miterLimit.coerceAtLeast(1f)
            val dash = state.lineDashPattern.dashArray
            if (dash.isNotEmpty()) {
                if (dash.any { !it.isFinite() || it < 0f } || dash.all { it == 0f }) throw IOException("PDF dash pattern is invalid")
                val intervals = if (dash.size % 2 == 0) dash else dash + dash
                pathEffect = DashPathEffect(intervals, state.lineDashPattern.phase.toFloat())
            }
        }
        val outline = Path()
        if (!paint.getFillPath(source, outline)) throw IOException("PDF stroke bounds could not be resolved")
        outline.transform(matrix); record(outline)
    }
    private fun finish() {
        pendingClip?.let { rule -> path.fillType = rule; graphicsState.intersectClippingPath(Path(path)) }
        pendingClip = null; path.reset()
    }
    override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
        moveTo(p0.x, p0.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); closePath()
    }
    override fun moveTo(x: Float, y: Float) { start.set(x, y); current.set(x, y); path.moveTo(x, y) }
    override fun lineTo(x: Float, y: Float) { current.set(x, y); path.lineTo(x, y) }
    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        current.set(x3, y3); path.cubicTo(x1, y1, x2, y2, x3, y3)
    }
    override fun getCurrentPoint() = current
    override fun closePath() { path.close(); current.set(start) }
    override fun clip(windingRule: Path.FillType) { pendingClip = windingRule }
    override fun endPath() = finish()
    override fun strokePath() { stroke(path); finish() }
    override fun fillPath(windingRule: Path.FillType) { path.fillType = windingRule; record(path); finish() }
    override fun fillAndStrokePath(windingRule: Path.FillType) {
        path.fillType = windingRule; record(path); stroke(path); finish()
    }
    override fun drawImage(image: PDImage) {
        val shape = Path().apply { addRect(0f, 0f, 1f, 1f, Path.Direction.CW) }
        shape.transform(graphicsState.currentTransformationMatrix.createAffineTransform().toMatrix())
        record(shape) // Image extent, without decoding or retaining its pixels.
    }
    override fun shadingFill(shadingName: COSName) {
        val shading = resources.getShading(shadingName) ?: throw IOException("PDF shading is missing")
        val area = shading.getBounds(AffineTransform(), graphicsState.currentTransformationMatrix)
        if (area != null) record(Path().apply { addRect(area, Path.Direction.CW) })
        else {
            val crop = page.cropBox
            record(Path().apply { addRect(crop.lowerLeftX, crop.lowerLeftY, crop.upperRightX, crop.upperRightY, Path.Direction.CW) })
        }
    }
    override fun beginText() { textClip = null }
    override fun endText() { textClip?.let { graphicsState.intersectClippingPath(it) }; textClip = null }
    override fun showFontGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        if (++glyphs > 100_000) throw IOException("PDF page has too many glyphs")
        val mode = graphicsState.textState.renderingMode
        if (!mode.isFill && !mode.isStroke && !mode.isClip) return
        val raw = when (font) {
            is PDVectorFont -> font.getPath(code)
            is PDSimpleFont -> font.getPath(font.encoding.getName(code))
            else -> throw IOException("PDF font bounds are unavailable")
        }
        val glyph = Path(raw)
        val transform = textRenderingMatrix.createAffineTransform().apply {
            concatenate(font.fontMatrix.createAffineTransform())
            if (!font.isEmbedded && !font.isVertical && !font.isStandard14 && font.hasExplicitWidth(code)) {
                val width = font.getWidthFromFont(code)
                if (width > 0f) scale((displacement.x * 1000f / width).toDouble(), 1.0)
            }
        }
        glyph.transform(transform.toMatrix())
        if (mode.isFill) record(glyph)
        if (mode.isStroke) stroke(glyph)
        if (mode.isClip) { if (textClip == null) textClip = Path(); textClip!!.addPath(glyph) }
    }
    private fun child(block: () -> Unit) {
        if (++depth > 32) throw IOException("PDF form nesting is too deep")
        val outerPath = path; val outerClip = pendingClip; val outerTextClip = textClip
        val outerCurrent = PointF(current.x, current.y); val outerStart = PointF(start.x, start.y)
        path = Path(); pendingClip = null; textClip = null
        try { block() } finally {
            path = outerPath; pendingClip = outerClip; textClip = outerTextClip
            current.set(outerCurrent); start.set(outerStart); depth--
        }
    }
    override fun showForm(form: PDFormXObject) = child { super.showForm(form) }
    override fun showTransparencyGroup(form: PDTransparencyGroup) = child { super.showTransparencyGroup(form) }
    override fun showType3Glyph(textRenderingMatrix: Matrix, font: PDType3Font, code: Int, displacement: Vector) {
        if (++glyphs > 100_000) throw IOException("PDF page has too many glyphs")
        val mode = graphicsState.textState.renderingMode
        if (mode.isFill || mode.isStroke) child { super.showType3Glyph(textRenderingMatrix, font, code, displacement) }
    }
}
