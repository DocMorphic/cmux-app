package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

/** Exercises the rebuilt library's JNI registration and native math, even on API 34+.
 * The ordinary PathIterator uses platform APIs there and would not test this binary.
 * Does not launch the app or alter any account state.
 */
class NativeGraphicsPathTest {
    @Test fun rebuiltGraphicsLibraryConvertsConicThroughOfficialJavaBinding() {
        System.loadLibrary("androidx.graphics.path")
        // AndroidX declares this helper Kotlin-internal; reflection preserves its
        // official binding while avoiding a copied implementation in the test.
        val type = Class.forName("androidx.graphics.path.ConicConverter")
        val converter = type.getConstructor().newInstance()
        val points = floatArrayOf(0f, 0f, 10f, 20f, 30f, 0f)
        type.getMethod("convert", FloatArray::class.java, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(converter, points, 1f, 0.25f, 0)
        assertEquals(1, type.getMethod("getQuadraticCount").invoke(converter))
        val quadratic = FloatArray(6)
        val next = type.getMethod("nextQuadratic", FloatArray::class.java, Int::class.javaPrimitiveType)
        assertEquals(true, next.invoke(converter, quadratic, 0))
        assertArrayEquals(points, quadratic, 0.001f)
        assertEquals(false, next.invoke(converter, quadratic, 0))
    }
}
