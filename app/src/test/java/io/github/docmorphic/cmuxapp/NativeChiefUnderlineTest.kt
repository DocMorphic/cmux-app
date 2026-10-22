package io.github.docmorphic.cmuxapp

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeChiefUnderlineTest {
    @Test fun pinnedSourceWhiteAndDarkOrColoredExamples() {
        assertEquals(.46f, nativeChiefUnderlineAlpha(Color.White), 0f)
        assertEquals(.26f, nativeChiefUnderlineAlpha(Color.Black), 0f)
        assertEquals(.26f, nativeChiefUnderlineAlpha(Color(1f, .2f, .2f)), 0f)
        assertEquals(.26f, nativeChiefUnderlineAlpha(0f, .98f), 0f)
    }

    @Test fun strictSourceThresholdsAndIndependentTextAlpha() {
        assertEquals(.26f, nativeChiefUnderlineAlpha(Color(1f, .97f, .97f)), 0f)
        assertEquals(.46f, nativeChiefUnderlineAlpha(Color(1f, .99f, .99f)), 0f)
        assertEquals(.46f, nativeChiefUnderlineAlpha(Color.White.copy(alpha = .3f)), 0f)
    }
}
