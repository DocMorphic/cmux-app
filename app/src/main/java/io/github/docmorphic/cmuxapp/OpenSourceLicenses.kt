package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val licenseAssets = listOf(
    "NOTICE.txt", "GeckoView.txt", "GPL-3.0.txt", "Apache-2.0.txt", "Lucide.txt", "Markdown.txt", "RawCode.txt", "Docx.txt", "SheetJS.txt",
    "JNA.txt", "JSch.txt", "JSch-jBCrypt.txt", "JSch-JZlib.txt", "BouncyCastle.txt", "PdfBox-Android.txt",
    "AndroidX-Graphics-Path.txt", "Ghostty.txt", "CloudTerminal.txt", "WireGuard.txt", "iroh/LICENSE-MIT", "iroh/LICENSE-APACHE",
    "simulator-video/COPYING.LGPLv2.1", "simulator-video/LICENSE.md",
)

internal data class LicenseDocument(val name: String, val sections: List<String>)

/** Keep every character, but bound individual Text layout even for licenses with very long lines. */
internal fun licenseSections(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 2048, text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= start) end = newline + 1
            else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        add(text.substring(start, end))
        start = end
    }
}

@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    val load = remember(assets) {
        suspend {
            withContext(Dispatchers.IO) {
                licenseAssets.map { name ->
                    val text = assets.open("licenses/$name").bufferedReader().use { it.readText() }
                    LicenseDocument(name, licenseSections(text))
                }
            }
        }
    }
    OpenSourceLicensesContent(load, onDismiss)
}

@Composable
internal fun OpenSourceLicensesContent(
    load: suspend () -> List<LicenseDocument>,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<LicenseDocument>>?>(null, load, attempt) {
        value = null
        value = try {
            Result.success(load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Could not load licenses"))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licenses") },
        text = {
            // Bound the lazy viewport so AlertDialog never requests intrinsic measurements
            // from a lazy layout or measures the full legal text during its first frame.
            Box(Modifier.fillMaxWidth().height(400.dp)) {
                val state = result
                when {
                    state == null -> Text("Loading licenses…")
                    state.isFailure -> Column {
                        Text("Licenses could not be loaded.")
                        TextButton(onClick = { attempt++ }) { Text("Retry") }
                    }
                    else -> LazyColumn(Modifier.fillMaxWidth().testTag("license-list")) {
                        state.getOrThrow().forEach { document ->
                            item("heading:${document.name}") {
                                Text(document.name, style = MaterialTheme.typography.titleSmall)
                            }
                            items(document.sections.size, key = { "${document.name}:$it" }) { index ->
                                SelectionContainer {
                                    Text(document.sections[index], fontSize = 12.sp)
                                }
                            }
                        }
                        item("end") { Text("End of licenses") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
