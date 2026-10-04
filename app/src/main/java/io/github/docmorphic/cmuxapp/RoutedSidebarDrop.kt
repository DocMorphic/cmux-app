package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Only issued opaque row keys cross the browser boundary. */
internal enum class RoutedSidebarDropPlacement { BEFORE, AFTER, INTO, UP, DOWN }
internal data class RoutedSidebarDragRow(val up: Boolean = false, val down: Boolean = false)
internal data class RoutedSidebarDrop(val revision: String, val key: String,
    val placement: RoutedSidebarDropPlacement, val target: String? = null) {
    fun validate() {
        listOfNotNull(revision, key, target).forEach { require(it.length in 1..128 && it.none(Char::isISOControl)) }
        require((placement in setOf(RoutedSidebarDropPlacement.UP, RoutedSidebarDropPlacement.DOWN)) == (target == null))
    }
}
internal object RoutedSidebarDropWire {
    fun encode(value: RoutedSidebarDrop): String {
        value.validate()
        return JSONObject().put("revision", value.revision).put("key", value.key)
            .put("placement", value.placement.name).put("target", value.target).toString()
    }
    fun decode(value: String): RoutedSidebarDrop {
        require(value.toByteArray(Charsets.UTF_8).size <= 2048)
        val json = JSONObject(value)
        return RoutedSidebarDrop(json.getString("revision"), json.getString("key"),
            RoutedSidebarDropPlacement.valueOf(json.getString("placement")),
            if (json.isNull("target")) null else json.getString("target")).also { it.validate() }
    }
}
