package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.saveable.Saver

/** Public locators may be restored; unconfirmed ticket text must never enter a Bundle. */
internal val NativePairingDraftSaver = Saver<String, String>(
    save = { value -> value.takeIf { PairingCodeParser.parse(it).isSuccess } ?: "" },
    restore = { value -> value.takeIf { PairingCodeParser.parse(it).isSuccess } ?: "" }
)
