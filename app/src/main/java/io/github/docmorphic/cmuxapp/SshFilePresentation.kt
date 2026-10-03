package io.github.docmorphic.cmuxapp

import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object SshFilePresentation {
    /** Locale-aware text ordering with numeric runs compared without integer overflow. */
    fun order(locale: Locale = Locale.getDefault()): Comparator<SshFileEntry> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val runs = Regex("[0-9]+|[^0-9]+")
        fun names(left: String, right: String): Int {
            val a = runs.findAll(left).iterator(); val b = runs.findAll(right).iterator()
            while (a.hasNext() && b.hasNext()) {
                val x = a.next().value; val y = b.next().value
                val result = if (x[0] in '0'..'9' && y[0] in '0'..'9') {
                    val nx = x.trimStart('0'); val ny = y.trimStart('0')
                    nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
                } else collator.compare(x, y)
                if (result != 0) return result
            }
            return a.hasNext().compareTo(b.hasNext()).takeIf { it != 0 } ?: left.compareTo(right)
        }
        return Comparator { left, right ->
            (!left.directory).compareTo(!right.directory).takeIf { it != 0 } ?: names(left.name, right.name)
        }
    }

    // Match the iOS generated photo basename; provider display names may be opaque IDs.
    fun photoName(index: Int, extension: String, date: Date, zone: TimeZone = TimeZone.getDefault()): String {
        require(index >= 0)
        require(extension.matches(Regex("[A-Za-z0-9]{1,16}")))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = zone }.format(date)
        return "Photo-$stamp${if (index == 0) "" else "-${index + 1}"}.${extension.lowercase(Locale.ROOT)}"
    }
}
