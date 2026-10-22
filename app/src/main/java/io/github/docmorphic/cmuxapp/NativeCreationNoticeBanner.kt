package io.github.docmorphic.cmuxapp

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Creation uncertainty survives unrelated connection recovery until acknowledged. */
@Composable
internal fun NativeCreationNoticeBanner(notice: NativeCreationNotice?, onDismiss: () -> Unit) {
    if (notice == null) return
    Surface(color = NativeAppColors.panel, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(notice.message, Modifier.weight(1f).padding(vertical = 12.dp))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cmux_ui_mobile_terminal_outputrecovery_dismiss_c572c42a)) }
        }
    }
}
