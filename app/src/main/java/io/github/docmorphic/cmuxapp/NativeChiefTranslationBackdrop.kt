package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.floor

internal data class NativeChiefTranslationBackdrop(val image: ImageBitmap, val frame: Rect)
internal fun Context.chiefTranslationActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.chiefTranslationActivity()
    else -> null
}

/** Copy only this Activity's surface. The separate popup surface is never sampled. No pixels
 * leave memory, survive this presentation or bypass a secure window; cancellation publishes nothing. */
internal suspend fun chiefTranslationBackdrop(context: Context, wanted: Rect): NativeChiefTranslationBackdrop? = withTimeoutOrNull(1_000) {
    val window = context.chiefTranslationActivity()?.window ?: return@withTimeoutOrNull null
    if (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return@withTimeoutOrNull null
    val decor = window.decorView
    val crop = android.graphics.Rect(floor(wanted.left).toInt().coerceAtLeast(0), floor(wanted.top).toInt().coerceAtLeast(0),
        ceil(wanted.right).toInt().coerceAtMost(decor.width), ceil(wanted.bottom).toInt().coerceAtMost(decor.height))
    if (crop.width() <= 0 || crop.height() <= 0) return@withTimeoutOrNull null
    val bitmap = Bitmap.createBitmap(crop.width(),crop.height(),Bitmap.Config.ARGB_8888)
    suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(window,crop,bitmap,{ result ->
                if (continuation.isActive && result == PixelCopy.SUCCESS) {
                    continuation.resume(NativeChiefTranslationBackdrop(bitmap.asImageBitmap(),Rect(crop.left.toFloat(),crop.top.toFloat(),crop.right.toFloat(),crop.bottom.toFloat())))
                } else {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resume(null)
                }
            },Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            bitmap.recycle(); if (continuation.isActive) continuation.resume(null)
        }
    }
}
