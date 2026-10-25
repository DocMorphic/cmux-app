package io.github.docmorphic.cmuxapp

import java.util.Locale

/** Positive Home counts follow the original helper's singular test and C %d. */
internal object NativeChiefUnreadFormat {
    fun positive(count: Long, one: String, other: String): String =
        if (count == 1L) one else String.format(Locale.ROOT, other, count.toInt())
}
