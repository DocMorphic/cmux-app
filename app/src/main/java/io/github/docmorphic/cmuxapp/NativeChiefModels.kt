package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.math.BigDecimal
import java.time.Instant

/** Mac-owned Chief protocol at a5911a1; separate from terminal-backed Agent Feed. */
internal object NativeChiefCapability {
    val required = setOf("chief.conversations", "chief.read", "chief.mark_read", "chief.send", "chief.stop", "chief.approve", "chief.answer")
    val methods = required + setOf("chief.status", "chief.open")
    fun available(capabilities: Set<String>) = capabilities.containsAll(required)
}

internal data class NativeChiefStatus(
    val started: Boolean, val startFailure: String?, val home: String?, val harness: String,
    val harnessName: String, val model: String, val effort: String, val unread: Long,
    val conversationCount: Long, val working: List<String>, val defaultConversation: String
)
internal data class NativeChiefConversation(
    val id: String, val title: String, val preview: String, val unread: Long,
    val pinned: Boolean, val working: Boolean, val updatedAt: Instant
)
internal data class NativeChiefSnapshot(val conversations: List<NativeChiefConversation>, val changeCursor: String?)
internal data class NativeChiefWork(val session: String, val host: String?, val title: String, val status: String, val preview: String?)
internal data class NativeChiefApproval(val request: String, val prompt: String, val options: List<String>, val decided: String?)
internal data class NativeChiefPart(
    val kind: String, val work: NativeChiefWork?, val approval: NativeChiefApproval?, val question: NativeChiefQuestion?
)
internal data class NativeChiefMessage(
    val id: String, val seq: ULong, val authorId: String, val author: String, val authorKind: String,
    val text: String, val at: Instant, val parts: List<String>, val partPayloads: List<NativeChiefPart>,
    val threadRoot: String?, val clientMessageId: String?
)
internal data class NativeChiefRead(
    val conversation: String, val working: Boolean, val messages: List<NativeChiefMessage>, val hasMore: Boolean,
    val lastReadSeq: ULong?, val unreadCount: Long?, val headSeq: ULong?, val changeCursor: String?
)
internal data class NativeChiefSent(val conversation: String, val messageId: String?, val clientMessageId: String?, val queued: Boolean)
internal data class NativeChiefStopped(val conversation: String, val stopped: Boolean)
internal data class NativeChiefMarked(val conversation: String, val upToSeq: ULong, val marked: Boolean)
internal data class NativeChiefApproved(val conversation: String, val request: String, val choice: String, val decided: Boolean)
internal data class NativeChiefAnswered(val conversation: String, val message: String, val answer: List<String>)
internal data class NativeChiefOpened(val windowId: String?, val opened: Boolean)

/** Strict JSON types: optString/optBoolean must not silently coerce malformed replies. */
internal object NativeChiefJson {
    private fun value(row: JSONObject, key: String) = row.opt(key).takeUnless { it === JSONObject.NULL }
    fun string(row: JSONObject, key: String): String = value(row, key) as? String ?: error("Invalid Chief $key")
    fun optionalString(row: JSONObject, key: String): String? = value(row, key)?.let { it as? String ?: error("Invalid Chief $key") }
    fun string(row: JSONObject, key: String, default: String) = optionalString(row, key) ?: default
    fun boolean(row: JSONObject, key: String): Boolean = value(row, key) as? Boolean ?: error("Invalid Chief $key")
    fun boolean(row: JSONObject, key: String, default: Boolean) = value(row, key)?.let { it as? Boolean ?: error("Invalid Chief $key") } ?: default
    fun optionalObject(row: JSONObject, key: String): JSONObject? = value(row, key)?.let { it as? JSONObject ?: error("Invalid Chief $key") }
    fun objectValue(row: JSONObject, key: String) = optionalObject(row, key) ?: error("Missing Chief $key")
    fun array(row: JSONObject, key: String, optional: Boolean = false): List<Any> {
        val raw = value(row, key) ?: if (optional) return emptyList() else error("Missing Chief $key")
        val array = raw as? JSONArray ?: error("Invalid Chief $key")
        return (0 until array.length()).map(array::get)
    }
    fun objects(row: JSONObject, key: String, optional: Boolean = false) = array(row, key, optional).map {
        it as? JSONObject ?: error("Invalid Chief $key entry")
    }
    fun strings(row: JSONObject, key: String, optional: Boolean = false) = array(row, key, optional).map {
        it as? String ?: error("Invalid Chief $key entry")
    }
    private fun integer(row: JSONObject, key: String): String {
        val raw = value(row, key)
        return when (raw) {
            is Int, is Long, is BigInteger -> raw.toString()
            // Codable accepts integral decimal/exponent tokens through Double.
            // Android parses these as Double; JVM org.json uses BigDecimal.
            is Double, is Float, is BigDecimal -> {
                val number = (raw as Number).toDouble()
                require(number.isFinite()) { "Invalid Chief $key" }
                BigDecimal(number).toBigIntegerExact().toString()
            }
            else -> error("Invalid Chief $key")
        }
    }
    fun unsigned(row: JSONObject, key: String) = integer(row, key).toULongOrNull() ?: error("Invalid Chief $key")
    fun optionalUnsigned(row: JSONObject, key: String) = value(row, key)?.let { unsigned(row, key) }
    fun optionalLong(row: JSONObject, key: String) = value(row, key)?.let { integer(row, key).toLongOrNull() ?: error("Invalid Chief $key") }
    fun long(row: JSONObject, key: String, default: Long) = optionalLong(row, key) ?: default
    // Foundation's ISO coder uses its historical calendar for this distantPast
    // sentinel; java.time is proleptic Gregorian (two days different at year 1).
    private fun date(value: String) = if (value == "0001-01-01T00:00:00Z") Instant.ofEpochSecond(-62135769600L) else Instant.parse(value)
    fun date(row: JSONObject, key: String) = date(string(row, key))
    fun optionalDate(row: JSONObject, key: String) = optionalString(row, key)?.let(::date)
    fun unsigned(value: ULong): Number = if (value <= Long.MAX_VALUE.toULong()) value.toLong() else BigInteger(value.toString())
}

internal object NativeChiefWire {
    private val distantPast = Instant.ofEpochSecond(-62135769600L)
    fun status(row: JSONObject) = with(NativeChiefJson) {
        NativeChiefStatus(boolean(row, "started", false), optionalString(row, "start_failure"), optionalString(row, "home"),
            string(row, "harness", ""), string(row, "harness_name", ""), string(row, "model", ""), string(row, "effort", ""),
            long(row, "unread", 0), long(row, "conversation_count", 0), strings(row, "working", true), string(row, "default_conversation", ""))
    }
    fun conversation(row: JSONObject) = with(NativeChiefJson) {
        val id = string(row, "id")
        NativeChiefConversation(id, string(row, "title", id), string(row, "preview", ""), long(row, "unread", 0),
            boolean(row, "pinned", false), boolean(row, "working", false), optionalDate(row, "updated_at") ?: distantPast)
    }
    fun snapshot(row: JSONObject) = with(NativeChiefJson) {
        NativeChiefSnapshot(objects(row, "conversations", true).map(::conversation), optionalString(row, "change_cursor"))
    }
    fun part(row: JSONObject): NativeChiefPart = with(NativeChiefJson) {
        NativeChiefPart(string(row, "kind"), optionalObject(row, "work")?.let {
            NativeChiefWork(string(it, "session"), optionalString(it, "host"), string(it, "title"), string(it, "status"), optionalString(it, "preview"))
        }, optionalObject(row, "approval")?.let {
            NativeChiefApproval(string(it, "request"), string(it, "prompt"), strings(it, "options"), optionalString(it, "decided"))
        }, optionalObject(row, "question")?.let(NativeChiefQuestion::decode))
    }
    fun message(row: JSONObject) = with(NativeChiefJson) {
        NativeChiefMessage(string(row, "id"), unsigned(row, "seq"), string(row, "author_id"), string(row, "author"),
            string(row, "author_kind"), string(row, "text"), date(row, "at"), strings(row, "parts", true),
            objects(row, "part_payloads", true).map(::part), optionalString(row, "thread_root"), optionalString(row, "client_message_id"))
    }
    fun read(row: JSONObject) = with(NativeChiefJson) {
        NativeChiefRead(string(row, "conversation"), boolean(row, "working", false), objects(row, "messages", true).map(::message),
            boolean(row, "has_more", false), optionalUnsigned(row, "last_read_seq"), optionalLong(row, "unread_count"),
            optionalUnsigned(row, "head_seq"), optionalString(row, "change_cursor"))
    }
    fun sent(row: JSONObject) = with(NativeChiefJson) {
        NativeChiefSent(string(row, "conversation"), optionalString(row, "message_id"), optionalString(row, "client_message_id"), boolean(row, "queued"))
    }
    fun stopped(row: JSONObject) = with(NativeChiefJson) { NativeChiefStopped(string(row, "conversation"), boolean(row, "stopped")) }
    fun marked(row: JSONObject) = with(NativeChiefJson) { NativeChiefMarked(string(row, "conversation"), unsigned(row, "up_to_seq"), boolean(row, "marked")) }
    fun approved(row: JSONObject) = with(NativeChiefJson) {
        NativeChiefApproved(string(row, "conversation"), string(row, "request"), string(row, "choice"), boolean(row, "decided"))
    }
    fun answered(row: JSONObject) = with(NativeChiefJson) {
        NativeChiefAnswered(string(row, "conversation"), string(row, "message"), strings(row, "answer"))
    }
    fun opened(row: JSONObject) = with(NativeChiefJson) { NativeChiefOpened(optionalString(row, "window_id"), boolean(row, "opened")) }
}
