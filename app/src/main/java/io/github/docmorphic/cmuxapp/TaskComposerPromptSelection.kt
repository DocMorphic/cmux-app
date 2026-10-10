/* Selection preservation follows cmux TaskComposerPromptEditor.swift at f4b1509.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Unchanged text retains caret/composition; external replacements clamp UTF-16 endpoints. */
internal fun taskComposerPromptValue(previous: TextFieldValue, text: String): TextFieldValue =
    if (previous.text == text) previous else TextFieldValue(text,
        TextRange(previous.selection.start.coerceIn(0, text.length), previous.selection.end.coerceIn(0, text.length)))
