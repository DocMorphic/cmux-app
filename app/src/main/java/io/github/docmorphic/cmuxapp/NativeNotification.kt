package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.Locale

internal data class NativeNotification(
    val id: String, val workspaceId: String, val surfaceId: String?,
    val title: String, val body: String, val isRead: Boolean,
    val subtitle: String? = null, val workspaceTitle: String? = null, val surfaceTitle: String? = null,
    val createdAt: Double? = null, val retargetsToLiveSurfaceOwner: Boolean = false
) {
    fun destination(workspaces: List<NativeWorkspace>): NativeWorkspace? =
        if (retargetsToLiveSurfaceOwner && surfaceId != null) {
            workspaces.singleOrNull { workspace -> workspace.terminals.any { it.id == surfaceId } || workspace.surfaces.any { it.id == surfaceId } || workspace.simulators.any { it.panelId == surfaceId } }
        } else workspaces.singleOrNull { it.id == workspaceId }

    fun searchFields(workspaces: List<NativeWorkspace>, computer: String): List<String?> {
        val target = destination(workspaces)
        return listOf(workspaceTitle, surfaceTitle, computer, title, subtitle, body,
            target?.title, target?.terminals?.firstOrNull { it.id == surfaceId }?.title, target?.surfaces?.firstOrNull { it.id == surfaceId }?.title,
            target?.simulators?.firstOrNull { it.panelId == surfaceId }?.title)
    }
    fun headline(workspaces: List<NativeWorkspace>) = workspaceTitle ?: destination(workspaces)?.title
        ?: title.takeIf { it.isNotBlank() } ?: "Unknown workspace"
    fun presentation(workspaces: List<NativeWorkspace>, computer: String, locale: Locale): NotificationPresentation {
        val headline = headline(workspaces).trim()
        fun normalized(text: String) = NativeSearchText.fold(text.trim(), locale, notification = true)
        val source = title.trim().takeIf { it.isNotEmpty() && normalized(it) != normalized(headline) }
        val redundant = listOf(headline, title, computer).map(::normalized).toSet()
        val preview = listOf(body, subtitle.orEmpty()).map { it.trim() }
            .firstOrNull { it.isNotEmpty() && normalized(it) !in redundant }
        return NotificationPresentation(headline, source, preview)
    }

}

internal data class NotificationPresentation(val headline: String, val source: String?, val preview: String?)

internal fun parseNotifications(value: JSONObject): List<NativeNotification> {
    val array = value.optJSONArray("notifications") ?: return emptyList()
    val ids = mutableSetOf<String>()
    return buildList {
        for (index in 0 until minOf(array.length(), 2_000)) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isBlank() || !ids.add(id)) continue
            fun field(key: String, limit: Int): String? = item.optString(key)
                .takeIf { !item.isNull(key) && it.isNotBlank() }
                ?.let { NativeSearchText.prefix(it, limit) }
            add(NativeNotification(id, item.optString("workspace_id"), field("surface_id", 512),
                field("title", 1024).orEmpty(), field("body", 8192).orEmpty(), item.optBoolean("is_read"),
                field("subtitle", 2048), field("workspace_title", 512), field("surface_title", 512),
                item.optDouble("created_at").takeIf { it.isFinite() && it > 0 },
                item.optBoolean("retargets_to_live_surface_owner")))
        }
    }
}
