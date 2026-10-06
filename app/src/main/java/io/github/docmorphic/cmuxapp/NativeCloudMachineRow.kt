/* Cloud machine actions follow cmux CloudSectionView at c2715faa.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal class CloudMachineSwipeCoordinator { var opened by mutableStateOf<String?>(null) }

/** A swipe reveals buttons only; even a full swipe never mutates a machine.
 * Delete always calls the parent's confirmation flow. Only one row stays open. */
@Composable internal fun NativeCloudMachineRow(machine: CloudMachine, busy: Boolean,
    actionFailure: CloudMachineActionFailure?, connectionFailure: CloudSessionFailure?,
    swipes: CloudMachineSwipeCoordinator, onRetry: () -> Unit, onAction: (CloudMachineAction) -> Unit,
    automaticallyRetrying: Boolean = false) {
    var menu by remember(machine.id) { mutableStateOf(false) }
    var dragged by remember(machine.id) { mutableStateOf<Float?>(null) }
    val currentMachine by rememberUpdatedState(machine)
    val currentBusy by rememberUpdatedState(busy)
    val currentAction by rememberUpdatedState(onAction)
    val currentRetry by rememberUpdatedState(onRetry)
    val currentFailure by rememberUpdatedState(connectionFailure)
    val actions = CloudMachineAction.entries.filter { it.allows(machine) }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val revealWidth = with(LocalDensity.current) { (96.dp * actions.size).toPx() }
    val opened = swipes.opened == machine.id
    fun dismiss() { menu = false; dragged = null; if (swipes.opened == machine.id) swipes.opened = null }
    fun action(value: CloudMachineAction): Boolean {
        if (currentBusy || !value.allows(currentMachine)) return false
        dismiss(); currentAction(value); return true
    }
    fun retry(): Boolean {
        if (currentBusy || currentFailure == null || currentMachine.lifecycle != CloudMachineLifecycle.RUNNING) return false
        dismiss(); currentRetry(); return true
    }
    DisposableEffect(swipes, machine.id) { onDispose { if (swipes.opened == machine.id) swipes.opened = null } }
    LaunchedEffect(busy, actions, opened) {
        if (busy || actions.isEmpty()) dismiss()
        else if (!opened) dragged = null
    }
    BackHandler(opened || menu) { dismiss() }
    val offset by animateFloatAsState(dragged ?: if (opened) -revealWidth else 0f,
        if (dragged != null) snap() else tween(160), label = "Cloud machine swipe")
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().clip(shape).testTag("cloud.machine.${machine.id}")
        .pointerInput(machine.id, actions, busy, direction, revealWidth) {
            if (actions.isNotEmpty() && !busy) detectHorizontalDragGestures(
                onDragStart = { dragged = if (swipes.opened == machine.id) -revealWidth else 0f; swipes.opened = machine.id },
                onHorizontalDrag = { change, amount ->
                    change.consume(); dragged = ((dragged ?: 0f) + amount * direction).coerceIn(-revealWidth, 0f)
                },
                onDragCancel = { dismiss() },
                onDragEnd = {
                    val keepOpen = (dragged ?: 0f) < -revealWidth / 3f
                    dragged = null; swipes.opened = machine.id.takeIf { keepOpen }
                })
        }) {
        if (offset < 0f) Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            actions.forEach { item ->
                TextButton(onClick = { action(item) }, enabled = !busy,
                    modifier = Modifier.width(96.dp).fillMaxHeight().background(when (item) {
                        CloudMachineAction.DELETE -> Color(0xFF9C293C)
                        CloudMachineAction.PAUSE -> Color(0xFF86520A)
                        CloudMachineAction.RESUME -> Color(0xFF216B46)
                    }).testTag("cloud.swipe.${machine.id}.${item.name}")) {
                    Text(item.label, color = Color.White)
                }
            }
        }
        Column(Modifier.fillMaxWidth().absoluteOffset { IntOffset((offset * direction).roundToInt(), 0) }
            .background(Color(0xFF191B1F))
            .combinedClickable(onClick = { if (opened) dismiss() }, onLongClick = { if (!currentBusy && (actions.isNotEmpty() || currentFailure != null)) menu = true })
            .semantics { customActions = if (busy) emptyList() else buildList {
                if (connectionFailure != null) add(CustomAccessibilityAction("Try Again Now", ::retry))
                actions.forEach { value -> add(CustomAccessibilityAction(value.label) { action(value) }) }
            } }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_workspace_cloud), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(machine.preferredName, fontWeight = FontWeight.SemiBold)
                    Text(when (machine.lifecycle) {
                        CloudMachineLifecycle.PROVISIONING -> "Starting"
                        CloudMachineLifecycle.RUNNING -> "Running"
                        CloudMachineLifecycle.PAUSED -> "Paused"
                        CloudMachineLifecycle.FAILED -> "Failed"
                        else -> machine.status
                    }, color = when (machine.lifecycle) {
                        CloudMachineLifecycle.RUNNING -> Color(0xFF70D59B)
                        CloudMachineLifecycle.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }, fontSize = 13.sp)
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp).testTag("cloud.busy.${machine.id}"))
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy && (actions.isNotEmpty() || connectionFailure != null)) {
                        Icon(painterResource(R.drawable.ic_ssh_file_more), "Actions for ${machine.preferredName}")
                    }
                    DropdownMenu(expanded = menu && !busy, onDismissRequest = { menu = false }) {
                        if (connectionFailure != null) DropdownMenuItem(text = { Text("Try Again Now") }, onClick = { retry() })
                        actions.forEach { value -> DropdownMenuItem(text = {
                            Text(value.label, color = if (value == CloudMachineAction.DELETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }, onClick = { action(value) }) }
                    }
                }
            }
            machine.resources?.let { Text("${it.vcpus} vCPUs · ${it.memoryMb / 1024} GB RAM", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            actionFailure?.let {
                Text("Couldn't ${it.action.name.lowercase(java.util.Locale.ROOT)}: ${it.failure.userReason}",
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            connectionFailure?.let {
                Text(if (automaticallyRetrying) "Couldn't connect. Retrying automatically." else "Couldn't connect.", color = Color(0xFFFFC071), fontSize = 13.sp,
                    modifier = Modifier.testTag("cloud.connection.failure.${machine.id}"))
                Text(it.userReason, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

private val CloudMachineAction.label get() = when (this) {
    CloudMachineAction.PAUSE -> "Pause"
    CloudMachineAction.RESUME -> "Resume"
    CloudMachineAction.DELETE -> "Delete"
}
