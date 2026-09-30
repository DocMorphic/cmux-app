package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Keep screen rendering out of the main state/effect method's JVM bytecode. */
@Composable
internal fun NativeScreenLayout(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, content = content)
}
