package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigInteger

/** Android JSONTokener rounds integers outside Long through Double. Preserve wire cursors. */
internal object MobileJson {
    fun objectValue(text: String): JSONObject = JSONObject(ExactIntegers(text))

    private class ExactIntegers(text: String) : JSONTokener(text) {
        override fun nextValue(): Any {
            val first = nextClean()
            back()
            if (first == '-' || first in '0'..'9') {
                val token = nextTo("{}[]/\\:,=;# \t\u000c")
                // Larger tokens cannot be a UInt64 cursor; leave ordinary parser validation
                // and decimal/exponent behavior unchanged instead of allocating huge integers.
                if (token.length in 19..20 && INTEGER.matches(token)) {
                    val integer = BigInteger(token)
                    if (integer > MAX_LONG || integer < MIN_LONG) return integer
                }
                return JSONTokener(token).nextValue()
            }
            return super.nextValue()
        }
    }
    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
    private val MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE)
    private val MIN_LONG = BigInteger.valueOf(Long.MIN_VALUE)
}
