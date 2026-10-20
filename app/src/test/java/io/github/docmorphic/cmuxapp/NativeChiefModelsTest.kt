package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NativeChiefModelsTest {
    private val vectors = javaClass.getResourceAsStream("/parity/chief-wire.json")!!.bufferedReader().use { MobileJson.objectValue(it.readText()) }
    private fun input(name: String): JSONObject {
        val rows = vectors.getJSONArray("models")
        return (0 until rows.length()).map(rows::getJSONObject).single { it.getString("name") == name }
            .getString("input").let { MobileJson.objectValue(it, true) }
    }
    private fun decode(kind: String, row: JSONObject): Any = when (kind) {
        "status" -> NativeChiefWire.status(row); "conversation" -> NativeChiefWire.conversation(row)
        "message" -> NativeChiefWire.message(row); "read" -> NativeChiefWire.read(row)
        "question" -> NativeChiefQuestion.decode(row); "part" -> NativeChiefWire.part(row)
        else -> error("Unknown reference kind")
    }

    @Test fun actualSwiftReferenceCasesAgreeOnAcceptanceDefaultsAndDecodedValues() {
        val rows = vectors.getJSONArray("models")
        assertEquals(21, rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index); val name = row.getString("name")
            val actual = runCatching { decode(row.getString("kind"), MobileJson.objectValue(row.getString("input"), true)) }
            if (row.isNull("decoded")) assertTrue(name, actual.isFailure)
            else {
                assertTrue("$name: ${actual.exceptionOrNull()}", actual.isSuccess)
                val expected = decode(row.getString("kind"), MobileJson.objectValue(row.getString("decoded"), true))
                assertEquals(name, expected, actual.getOrThrow())
            }
        }
    }

    @Test fun completeCapabilitySetIsRequiredBeforeExposingChief() {
        assertTrue(NativeChiefCapability.available(NativeChiefCapability.required))
        assertFalse(NativeChiefCapability.available(setOf("chief.status", "chief.open")))
        NativeChiefCapability.required.forEach { missing -> assertFalse(missing,
            NativeChiefCapability.available(NativeChiefCapability.methods - missing)) }
    }

    @Test fun transcriptPreservesFullWidthSequenceAndKnownOrUnknownParts() {
        val value = NativeChiefWire.message(input("message-wide-cursor"))
        assertEquals("m-1", value.id); assertEquals(ULong.MAX_VALUE, value.seq)
        assertEquals("agent_mux", value.authorId); assertEquals("Chief", value.author); assertEquals("chief", value.authorKind)
        assertEquals("I am on it.", value.text); assertEquals(Instant.parse("2026-10-16T12:30:45Z"), value.at)
        assertEquals(listOf("text", "work"), value.parts)
        assertEquals(NativeChiefWork("acp-1", "builder", "Ship it", "running", "Building"), value.partPayloads.first().work)
        assertEquals(NativeChiefPart("future", null, null, null), value.partPayloads.last())
        assertEquals("m-0", value.threadRoot); assertEquals("client-id", value.clientMessageId)
        val approval = NativeChiefWire.part(input("approval"))
        assertEquals(NativeChiefApproval("r-1", "Run deploy?", listOf("allow", "deny"), "allow"), approval.approval)
    }

    @Test fun omittedDefaultsRetainConservativeReadStateAndFoundationDistantPast() {
        val status = NativeChiefWire.status(input("status-defaults"))
        assertFalse(status.started); assertEquals(0L, status.unread); assertNull(status.startFailure)
        assertEquals("", status.harness); assertEquals("", status.defaultConversation); assertTrue(status.working.isEmpty())
        val conversation = NativeChiefWire.conversation(input("conversation-defaults"))
        assertEquals("chief-main", conversation.title); assertEquals(-62135769600L, conversation.updatedAt.epochSecond)
        assertFalse(conversation.pinned); assertEquals(0L, conversation.unread)
        val read = NativeChiefWire.read(input("read-defaults"))
        assertNull(read.lastReadSeq); assertNull(read.unreadCount); assertNull(read.headSeq); assertNull(read.changeCursor)
        assertTrue(read.messages.isEmpty()); assertFalse(read.hasMore)
        val legacy = NativeChiefWire.message(input("message-legacy"))
        assertTrue(legacy.partPayloads.isEmpty()); assertTrue(legacy.parts.isEmpty()); assertNull(legacy.clientMessageId)
    }

    @Test fun countsRemainSigned64BitAndReadCursorsRemainUnsigned64Bit() {
        val status = NativeChiefWire.status(input("status-large-count"))
        assertEquals(2147483648L, status.unread); assertEquals(listOf("chief-main"), status.working)
        val read = NativeChiefWire.read(input("read-cursors"))
        assertEquals(2147483648L, read.unreadCount); assertEquals(0uL, read.lastReadSeq); assertEquals(ULong.MAX_VALUE, read.headSeq)
        assertEquals("opaque", read.changeCursor); assertTrue(read.hasMore); assertTrue(read.working)
    }

    @Test fun sharedQuestionKeepsPreviewDefaultsAndOwnerStampedAnswer() {
        val pending = NativeChiefQuestion.decode(input("question-defaults"))
        assertEquals("Chief", pending.source.agentName); assertEquals(NativeChiefQuestion.Harness.CHIEF, pending.source.harness)
        val item = pending.items.single()
        assertEquals("Which region?", item.prompt); assertFalse(item.multiSelect); assertTrue(item.allowsOther)
        assertEquals(NativeChiefQuestion.Preview("**US**", NativeChiefQuestion.Preview.Format.MARKDOWN), item.options.single().preview)
        val answered = NativeChiefQuestion.decode(input("question-answered"))
        assertEquals(NativeChiefQuestion.State.ANSWERED, answered.state)
        val answer = checkNotNull(answered.answer)
        assertEquals(NativeChiefQuestion.Selection(listOf("us"), "custom"), answer.selections["q0"])
        assertEquals(NativeChiefQuestion.Respondent("user_local", "Me", "Phone", true), answer.respondent)
        assertEquals(1792495435000L, answer.answeredAtMs)
    }

    @Test fun answerValidationMatchesActualUpstreamQuestionPolicy() {
        val rows = vectors.getJSONArray("questions")
        assertEquals(8, rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val original = MobileJson.objectValue(row.getString("question"), true)
            val question = NativeChiefQuestion.decode(original)
            val answer = NativeChiefQuestion.decode(MobileJson.objectValue(original.toString()).put("state", JSONObject()
                .put("kind", "answered").put("answer", MobileJson.objectValue(row.getString("answer"), true)))).answer!!
            val expected = row.getJSONArray("problems").let { problems -> (0 until problems.length()).map { position ->
                val problem = problems.getJSONObject(position)
                NativeChiefQuestion.Problem(NativeChiefQuestion.Problem.Kind.valueOf(problem.getString("kind")),
                    NativeChiefJson.optionalString(problem, "item"), NativeChiefJson.optionalString(problem, "option"))
            } }
            assertEquals(row.getString("name"), expected, question.problems(answer.selections))
        }
    }
}
