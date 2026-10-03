/* Derived from cmux MobileWorkspaceUnreadState and WorkspaceUnreadDot,
 * revision 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

/** Null count means unknown, including an aggregate with an unknown contributor. */
internal data class NativeWorkspaceUnread(val isUnread: Boolean, val count: Long?) {
    val badgeCount: Long? get() = if (isUnread) maxOf(count ?: 1L, 1L) else null
    val accessibilityLabel: String get() = when {
        !isUnread -> ""
        count != null && count > 0 -> "$count unread"
        else -> "Unread"
    }

    fun merging(other: NativeWorkspaceUnread): NativeWorkspaceUnread {
        val ownCount = count
        val otherCount = other.count
        return NativeWorkspaceUnread(
            isUnread || other.isUnread,
            if (ownCount == null || otherCount == null) null
            else runCatching { Math.addExact(ownCount, otherCount) }.getOrNull()
        )
    }

    companion object { val Read = NativeWorkspaceUnread(false, 0) }
}
