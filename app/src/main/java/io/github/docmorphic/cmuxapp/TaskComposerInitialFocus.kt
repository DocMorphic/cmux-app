/* Scoped port of cmux TaskComposerInitialFocusCoordinator.swift at f4b1509.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import android.view.ViewTreeObserver
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** One request belongs to this presentation, including across activity recreation. */
internal class TaskComposerInitialFocus(finished: Boolean = false) {
    var finished by mutableStateOf(finished); private set
    fun cancel() { finished = true }
    fun focused() { finished = true }
    fun ready(enabled: Boolean, visible: Boolean, resumed: Boolean, attached: Boolean, windowFocused: Boolean): Boolean {
        if (!enabled || !visible) cancel()
        return !finished && resumed && attached && windowFocused
    }
    companion object {
        val Saver = Saver<TaskComposerInitialFocus, Boolean>({ it.finished }, { TaskComposerInitialFocus(it) })
    }
}

@Composable
internal fun taskComposerInitialFocusModifier(presentation: Any, enabled: Boolean, visible: Boolean,
    current: () -> Boolean): Modifier {
    val request = rememberSaveable(presentation, saver = TaskComposerInitialFocus.Saver) { TaskComposerInitialFocus() }
    val requester = remember(presentation) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val admitted by rememberUpdatedState(enabled && visible)
    val guard by rememberUpdatedState(current)
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    var windowFocused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    var attached by remember(presentation) { mutableStateOf(false) }
    var layoutRevision by remember(presentation) { mutableIntStateOf(0) }
    DisposableEffect(request, view, lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState == Lifecycle.State.RESUMED }
        val tree = view.viewTreeObserver
        val window = ViewTreeObserver.OnWindowFocusChangeListener { windowFocused = it }
        lifecycle.addObserver(observer)
        tree.addOnWindowFocusChangeListener(window)
        windowFocused = view.hasWindowFocus()
        onDispose {
            request.cancel()
            lifecycle.removeObserver(observer)
            if (tree.isAlive) tree.removeOnWindowFocusChangeListener(window)
            else view.viewTreeObserver.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(window)
        }
    }
    LaunchedEffect(request, enabled, visible, resumed, windowFocused, attached, layoutRevision) {
        if (!request.ready(enabled && guard(), visible, resumed, attached, windowFocused)) return@LaunchedEffect
        // Placement and lifecycle can arrive before the editable input session.
        withFrameNanos { }
        if (request.ready(admitted && guard(), visible, lifecycle.currentState == Lifecycle.State.RESUMED,
                attached, view.hasWindowFocus()) && requester.requestFocus()) {
            request.focused()
            keyboard?.show()
        }
    }
    return Modifier.focusRequester(requester).onGloballyPositioned { coordinates ->
        attached = coordinates.isAttached && coordinates.size.width > 0 && coordinates.size.height > 0
        if (!request.finished) layoutRevision++
    }.onFocusChanged { if (it.isFocused) request.focused() }
}
