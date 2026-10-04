/* Derived from cmux MobileWorkspacePreview+Display / WorkspaceRow,
 * revision 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

internal data class WorkspaceActivityStamp(val milliseconds: Long, val today: Boolean)

internal fun workspaceActivityStamp(workspace: NativeWorkspace, referenceMilliseconds: Long, zone: TimeZone): WorkspaceActivityStamp? {
    val seconds = workspace.lastActivityAt ?: workspace.previewAt ?: return null
    if (!seconds.isFinite() || seconds <= 1 || seconds >= Long.MAX_VALUE / 1000.0) return null
    val milliseconds = (seconds * 1000).toLong()
    val today = Instant.ofEpochMilli(milliseconds).atZone(zone.toZoneId()).toLocalDate() ==
        Instant.ofEpochMilli(referenceMilliseconds).atZone(zone.toZoneId()).toLocalDate()
    return WorkspaceActivityStamp(milliseconds, today)
}

internal fun workspaceConnectionLabel(availability: NativeFeedAvailability): String? = when (availability) {
    NativeFeedAvailability.CONNECTED -> null
    NativeFeedAvailability.CONNECTING -> "Reconnecting"
    NativeFeedAvailability.OFFLINE -> "Disconnected"
}

internal fun workspaceActivityLabel(workspace: NativeWorkspace, availability: NativeFeedAvailability,
    referenceMilliseconds: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
    workspaceConnectionLabel(availability)?.let { return it }
    val stamp = workspaceActivityStamp(workspace, referenceMilliseconds, zone) ?: return ""
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (stamp.today) "jm" else "Md")
    return SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(stamp.milliseconds))
}
