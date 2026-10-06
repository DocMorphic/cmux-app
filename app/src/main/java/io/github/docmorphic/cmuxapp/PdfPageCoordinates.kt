package io.github.docmorphic.cmuxapp

/** PDF user space has its origin at the crop box's bottom left; the renderer uses rotated top left. */
internal data class PdfPageCoordinates(val left: Float, val bottom: Float, val width: Float, val height: Float,
    val rotation: Int, val renderedWidth: Int, val renderedHeight: Int) {
    fun point(x: Float, y: Float): Pair<Float, Float>? {
        if (!listOf(left, bottom, width, height, x, y).all(Float::isFinite) || width <= 0 || height <= 0 ||
            renderedWidth <= 0 || renderedHeight <= 0) return null
        val localX = x - left
        val localY = y - bottom
        val r = Math.floorMod(rotation, 360)
        val (tx, ty) = when (r) {
            0 -> localX to height - localY
            90 -> localY to localX
            180 -> width - localX to localY
            270 -> height - localY to width - localX
            else -> return null
        }
        val (w, h) = if (r == 90 || r == 270) height to width else width to height
        return (tx * renderedWidth / w to ty * renderedHeight / h).takeIf { it.first.isFinite() && it.second.isFinite() }
    }
    fun bounds(points: List<Pair<Float, Float>>): PdfTextBounds? {
        val mapped = points.map { point(it.first, it.second) ?: return null }
        if (mapped.isEmpty()) return null
        val result = PdfTextBounds(mapped.minOf { it.first }.coerceAtLeast(0f), mapped.minOf { it.second }.coerceAtLeast(0f),
            mapped.maxOf { it.first }.coerceAtMost(renderedWidth.toFloat()), mapped.maxOf { it.second }.coerceAtMost(renderedHeight.toFloat()))
        return result.takeIf { it.right > it.left && it.bottom > it.top }
    }
    fun userPoint(x: Float, y: Float): Pair<Float, Float>? {
        if (point(left, bottom) == null || !x.isFinite() || !y.isFinite()) return null
        val quarterTurn = Math.floorMod(rotation, 180) == 90
        val tx = x * (if (quarterTurn) height else width) / renderedWidth
        val ty = y * (if (quarterTurn) width else height) / renderedHeight
        val local = when (Math.floorMod(rotation, 360)) {
            0 -> tx to height - ty
            90 -> ty to tx
            180 -> width - tx to ty
            270 -> width - ty to height - tx
            else -> return null
        }
        return (local.first + left to local.second + bottom).takeIf { it.first.isFinite() && it.second.isFinite() }
    }
}
