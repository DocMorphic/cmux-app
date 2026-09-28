package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val licenses = remember(context) {
        listOf("NOTICE.txt", "GPL-3.0.txt", "Apache-2.0.txt", "Lucide.txt", "Markdown.txt", "RawCode.txt",
            "JNA.txt", "iroh/LICENSE-MIT", "iroh/LICENSE-APACHE").joinToString("\n\n") { name ->
            context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Open-source licenses") },
        text = { SelectionContainer {
            Text(licenses, Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp)
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
