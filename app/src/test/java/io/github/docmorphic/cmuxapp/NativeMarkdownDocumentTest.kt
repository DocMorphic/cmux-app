package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMarkdownDocumentTest {
    private val numbers = NativeMarkdownNumbers { text ->
        val iterator = com.ibm.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT).apply { setText(text) }
        var start = iterator.first()
        while (start < text.length && com.ibm.icu.lang.UCharacter.getIntPropertyValue(text.codePointAt(start), com.ibm.icu.lang.UProperty.NUMERIC_TYPE) != 0)
            start = iterator.next()
        start
    }
    private fun reference() = JSONObject(checkNotNull(javaClass.getResource("/parity/native-markdown.json")).readText())

    @Test fun allBlockValuesMatchFiveExecutedUnchangedSwiftSourceFiles() {
        val fixture = reference()
        assertEquals("a5911a1f92f3eeb11c4d7f3e2ef89204718c5009", fixture.getString("upstream"))
        val cases = fixture.getJSONArray("documents")
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index); val raw = row.getString("input")
            val expected = row.getJSONArray("blocks")
            val actual = NativeMarkdownDocument.parse(raw, numbers)
            assertEquals(raw, expected.length(), actual.size)
            for (i in actual.indices) {
                val value = expected.getJSONObject(i)
                assertEquals(raw, NativeMarkdownBlock(value.getInt("index"), NativeMarkdownBlock.Kind.valueOf(value.getString("kind")),
                    value.getString("text"), value.optInt("level"), value.optInt("indent"), value.optString("marker"),
                    value.optString("language").takeUnless { value.isNull("language") || !value.has("language") }, value.optBoolean("header")), actual[i])
            }
        }
    }

    private fun merge(runs: List<AgentMarkdownRun>): List<AgentMarkdownRun> {
        val result = ArrayList<AgentMarkdownRun>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val last = result.lastOrNull()
            if (last?.style == run.style) result[result.lastIndex] = last.copy(text = last.text + run.text) else result.add(run)
        }
        return result
    }

    @Test fun fullInlineRunsMatchExecutedOriginalFoundationMethodWithinAndroidLinkPolicy() {
        val cases = reference().getJSONArray("inline")
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index); val raw = row.getString("input"); val runs = row.getJSONArray("runs")
            val expected = (0 until runs.length()).map { i ->
                val run = runs.getJSONObject(i)
                AgentMarkdownRun(run.getString("text"), AgentMarkdownStyle(run.getBoolean("bold"), run.getBoolean("italic"),
                    run.getBoolean("code"), run.getBoolean("strike"), run.optString("link").takeUnless { run.isNull("link") }?.takeIf(MarkdownPreviewPolicy::external)))
            }
            assertEquals(raw, merge(expected), merge(AgentFeedMarkdown.parseFull(raw)))
        }
    }

    @Test fun fullTransportSizedParagraphAndUnclosedCodeAreNotPreviewLimited() {
        val text = "word ".repeat(1_677_718) + "TAIL"
        assertEquals(text, NativeMarkdownDocument.parse(text, numbers).single().text)
        val code = NativeMarkdownDocument.parse("```text\n$text", numbers).single()
        assertEquals(NativeMarkdownBlock.Kind.CODE, code.kind); assertEquals(text, code.text)
    }

    @Test fun cancellationDoesNotPublishAnIncompleteDocument() {
        val stopped = IllegalStateException("cancelled")
        var lines = 0
        try {
            NativeMarkdownDocument.parse((1..100).joinToString("\n") { "- item $it" }, numbers) { if (++lines == 12) throw stopped }
            fail("Partial parsing must not return")
        } catch (error: IllegalStateException) { assertSame(stopped, error) }
    }
}
