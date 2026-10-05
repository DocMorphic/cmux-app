package io.github.docmorphic.cmuxapp

/** Keep gesture targets in place, but refresh surviving models from the newest snapshot. */
internal fun <T> workspacePresentationRows(rendered: List<T>, target: List<T>, holdOrder: Boolean,
    key: (T) -> String): List<T> {
    if (!holdOrder) return target
    val latest = target.associateBy(key)
    return rendered.map { latest[key(it)] ?: it }
}
