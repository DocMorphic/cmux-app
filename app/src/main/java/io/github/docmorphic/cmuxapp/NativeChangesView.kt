package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Read-only repository changes and progressively bounded diffs from cmux. */
@Composable
fun NativeChangesView(client: MobileRpcClient, workspaceId: String, title: String, onBack: () -> Unit) {
    var listing by remember(workspaceId) { mutableStateOf<JSONObject?>(null) }
    var selectedPath by remember(workspaceId) { mutableStateOf<String?>(null) }
    var diff by remember(workspaceId) { mutableStateOf<JSONObject?>(null) }
    var lineLimit by remember(workspaceId) { mutableIntStateOf(400) }
    var error by remember(workspaceId) { mutableStateOf<String?>(null) }

    LaunchedEffect(client, workspaceId) {
        runCatching { client.changedFiles(workspaceId) }
            .onSuccess { listing = it; error = null }
            .onFailure { error = it.message ?: "Could not load changes" }
    }
    LaunchedEffect(client, workspaceId, selectedPath, lineLimit) {
        val path = selectedPath ?: return@LaunchedEffect
        diff = null
        runCatching { client.fileDiff(workspaceId, path, lineLimit) }
            .onSuccess { diff = it; error = null }
            .onFailure { error = it.message ?: "Could not load diff" }
    }
    BackHandler { if (selectedPath != null) selectedPath = null else onBack() }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))) {
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            TextButton(onClick = { if (selectedPath != null) selectedPath = null else onBack() }) {
                Text("‹  ${if (selectedPath == null) "Workspaces" else "Changes"}")
            }
            Text(if (selectedPath == null) title else selectedPath.orEmpty(),
                Modifier.weight(1f).padding(top = 16.dp), maxLines = 1,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (error != null) Text(error.orEmpty(), Modifier.padding(14.dp), color = Color(0xFFFF9999))
        if (selectedPath == null) {
            val value = listing
            if (value == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (value != null) {
                val files = value.optJSONArray("files")
                Text(value.optString("repo_root"), Modifier.padding(horizontal = 18.dp),
                    color = Color(0xFF9298A2), fontSize = 12.sp, maxLines = 1)
                Text("${value.optString("branch")} · ${value.optInt("files_changed")} files · " +
                    "+${value.optInt("additions")} −${value.optInt("deletions")}",
                    Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    color = Color(0xFFB2B8C1), fontSize = 13.sp)
                LazyColumn(Modifier.weight(1f)) {
                    items((0 until (files?.length() ?: 0)).mapNotNull { files?.optJSONObject(it) }
                        .filter { it.optString("path").isNotBlank() }, key = { it.optString("path") }) { file ->
                        Row(Modifier.fillMaxWidth().clickable {
                            lineLimit = 400; selectedPath = file.optString("path")
                        }.padding(horizontal = 18.dp, vertical = 13.dp)) {
                            Text(file.optString("status").take(1).uppercase(),
                                Modifier.width(26.dp), color = Color(0xFF76B9FF), fontWeight = FontWeight.Bold)
                            Column(Modifier.weight(1f)) {
                                Text(file.optString("path"), maxLines = 2)
                                file.optString("old_path").takeIf { it.isNotBlank() && it != "null" }?.let {
                                    Text("from $it", color = Color(0xFF9298A2), fontSize = 11.sp)
                                }
                            }
                            Text("+${file.optInt("additions")} −${file.optInt("deletions")}",
                                color = Color(0xFF9298A2), fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Color(0xFF292C31))
                    }
                    if ((files?.length() ?: 0) == 0) item {
                        Text("No changed files.", Modifier.padding(24.dp), color = Color(0xFF9298A2))
                    }
                    if (value.optBoolean("truncated")) item {
                        Text("The Mac limited this file list.", Modifier.padding(18.dp), color = Color(0xFF9298A2))
                    }
                }
            }
        } else {
            val current = diff
            if (current == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (current != null) {
                Text("+${current.optInt("additions")} −${current.optInt("deletions")}" +
                    if (current.optBoolean("is_binary")) " · binary file" else "",
                    Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color(0xFF9298A2))
                val lines = current.optString("unified_diff").lines()
                val horizontal = rememberScrollState()
                LazyColumn(Modifier.weight(1f)) {
                    items(lines) { line ->
                        Text(line.take(5000).ifEmpty { " " },
                            Modifier.fillMaxWidth().horizontalScroll(horizontal)
                                .padding(horizontal = 12.dp, vertical = 1.dp),
                            color = when {
                                line.startsWith("+++") || line.startsWith("---") -> Color(0xFFB0B7C2)
                                line.startsWith("+") -> Color(0xFF75D69B)
                                line.startsWith("-") -> Color(0xFFFF8F8F)
                                line.startsWith("@@") -> Color(0xFF76B9FF)
                                else -> Color(0xFFD5D9E0)
                            }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                    }
                    if (current.optBoolean("truncated") && lineLimit < 10_000) item {
                        TextButton(onClick = { lineLimit = (lineLimit * 2).coerceAtMost(10_000) }) {
                            Text("Show more lines")
                        }
                    }
                }
            }
        }
    }
}
