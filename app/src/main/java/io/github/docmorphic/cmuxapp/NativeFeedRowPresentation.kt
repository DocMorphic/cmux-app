package io.github.docmorphic.cmuxapp

import java.util.Locale

/** Display values only; shared by the main feed and the separate browser process. */
internal data class NativeFeedRowValue(val key: String, val presentation: NotificationPresentation,
    val computer: String, val availability: NativeFeedAvailability, val isRead: Boolean, val createdAt: Double?)
internal data class NativeFeedRowContext(val nested: Boolean = false, val hideHeadline: Boolean = false,
    val hideSource: Boolean = false, val hideComputer: Boolean = false)
internal fun NativeFeedEntry.rowValue(locale: Locale) = NativeFeedRowValue(id, presentation(locale), computer,
    source.availability, notification.isRead, notification.createdAt)
internal fun NativeFeedRowValue.nestedUnder(parent: NativeFeedRowValue?, locale: Locale): NativeFeedRowContext {
    if (parent == null) return NativeFeedRowContext()
    fun same(a: String?, b: String?) = a != null && b != null &&
        NativeSearchText.fold(a.trim(), locale, true) == NativeSearchText.fold(b.trim(), locale, true)
    return NativeFeedRowContext(true, same(presentation.headline, parent.presentation.headline),
        presentation.preview != null && same(presentation.source, parent.presentation.source),
        same(computer, parent.computer) && availability == parent.availability)
}
