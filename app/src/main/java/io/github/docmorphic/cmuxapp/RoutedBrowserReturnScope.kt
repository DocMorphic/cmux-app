package io.github.docmorphic.cmuxapp

internal enum class RoutedBrowserReturnScope { IGNORE, LEAVE, APPLY }

internal fun ownsBrowserDestination(expected: LocalBrowserDestination, current: LocalBrowserDestination?) =
    current?.surface === expected.surface && current.key == expected.key

/** An old Activity may return before Compose has observed a replacement destination. */
internal fun routedBrowserReturnScope(expected: LocalBrowserDestination, current: LocalBrowserDestination?,
    registered: LocalBrowserDestination?, networkAlive: Boolean, workspaceId: String): RoutedBrowserReturnScope {
    if (!ownsBrowserDestination(expected, current)) return RoutedBrowserReturnScope.IGNORE
    if (!ownsBrowserDestination(expected, registered) || !networkAlive || expected.surface.state.value.closed ||
        workspaceId != expected.key.workspaceId) return RoutedBrowserReturnScope.LEAVE
    return RoutedBrowserReturnScope.APPLY
}
