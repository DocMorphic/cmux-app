package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChangesModelsTest {
    private fun file(path: String) = ChangedFile(path, null, ChangeKind.MODIFIED, 1, 1, false, false)
    private fun diff(raw: String, binary: Boolean = false) = ChangesDiffDocument.read(JSONObject().put("path", "a")
        .put("unified_diff", raw).put("is_binary", binary), "a")

    @Test fun listingPreservesValidSiblingsAndDeduplicatesStablePaths() {
        val files = JSONArray().put(JSONObject().put("path", "z.txt").put("status", "future"))
            .put(JSONObject().put("path", 42)).put(JSONObject().put("path", ""))
            .put(JSONObject().put("path", "a.txt").put("status", "renamed").put("old_path", "old.txt").put("is_approximate", true))
            .put(JSONObject().put("path", "z.txt"))
        val result = ChangesSnapshot.read(JSONObject().put("files", files).put("branch", JSONObject.NULL), "workspace")
        assertEquals(listOf("a.txt", "z.txt"), result.files.map { it.path })
        assertEquals(ChangeKind.UNKNOWN, result.files.last().kind)
        assertEquals("old.txt", result.files.first().oldPath); assertTrue(result.files.first().approximate)
        assertNull(result.branch)
    }
    @Test(expected = IllegalArgumentException::class) fun foreignWorkspaceCannotSeedListing() {
        ChangesSnapshot.read(JSONObject().put("workspace_id", "other"), "workspace")
    }
    @Test fun directoryChainsCollapseAndDirectoriesPrecedeFiles() {
        val tree = ChangedFilesTree(listOf(file("z.txt"), file("src/ui/A.kt"), file("src/ui/a.kt"), file("lib/x.kt")))
        val rows = tree.rows(emptySet())
        assertEquals(listOf("directory:lib", "file:lib/x.kt", "directory:src/ui", "file:src/ui/A.kt", "file:src/ui/a.kt", "file:z.txt"), rows.map { it.id })
        val src = rows[2] as ChangesTreeRow.Directory
        assertEquals("src/ui", src.name); assertEquals(2, src.count); assertEquals(0, src.depth)
        assertEquals(1, rows[3].depth)
    }
    @Test fun foldingAParentDoesNotLoseChildIdentityOrSnapshotPaths() {
        val tree = ChangedFilesTree(listOf(file("src/a.kt"), file("src/ui/b.kt"), file("root.kt")))
        assertEquals(listOf("directory:src", "file:root.kt"), tree.rows(setOf("src")).map { it.id })
        val open = tree.rows(setOf("src/ui"))
        assertEquals(listOf("directory:src", "directory:src/ui", "file:src/a.kt", "file:root.kt"), open.map { it.id })
    }
    @Test fun parserKeepsCrLfAndMetadataMarkersWithoutIncorrectLineNumbers() {
        val doc = diff("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -4,2 +8,2 @@ title\r\n old\r\n-before\r\n\\ No newline at end of file\r\n+after\r\n")
        val hunk = doc.hunks.single()
        assertEquals(4, hunk.lines[0].oldNumber); assertEquals(8, hunk.lines[0].newNumber)
        assertEquals(5, hunk.lines[1].oldNumber); assertNull(hunk.lines[1].newNumber)
        assertEquals(DiffKind.NO_NEWLINE, hunk.lines[2].kind)
        assertEquals(9, hunk.lines[3].newNumber)
        assertEquals("after\r", hunk.lines[3].text)
        assertFalse(hunk.copyText.contains("No newline")); assertTrue(hunk.copyText.contains("+after\r"))
        assertEquals(9, doc.rawLineCount)
    }
    @Test fun zeroCountAndOmittedCountHeadersHaveCorrectCoordinates() {
        val doc = diff("@@ -0,0 +1,2 @@\n+first\n+second\n@@ -20 +22,0 @@\n-removed")
        assertEquals(listOf(1, 2), doc.hunks.first().lines.map { it.newNumber })
        assertEquals(0, doc.hunks.first().oldCount)
        assertEquals(1, doc.hunks.last().oldCount); assertEquals(20, doc.hunks.last().lines.single().oldNumber)
    }
    @Test fun emptyRenameAndBinaryDiffsHaveNoFakeHunks() {
        assertTrue(diff("").hunks.isEmpty())
        assertTrue(diff("similarity index 100%\nrename from a\nrename to b\n").hunks.isEmpty())
        val binary = diff("@@ -1 +1 @@\n-text\n+bytes", binary = true)
        assertTrue(binary.hunks.isEmpty()); assertTrue(binary.binary)
    }
    @Test fun unicodeEmphasisDoesNotSplitAnEmojiOrCombiningSequence() {
        val hunk = diff("@@ -1 +1 @@\n-let astronaut = 👩‍🚀\n+let astronaut = 👩‍🔬").hunks.single()
        val old = hunk.lines[0]; val new = hunk.lines[1]
        assertEquals("👩‍🚀", old.text.substring(checkNotNull(old.emphasis)))
        assertEquals("👩‍🔬", new.text.substring(checkNotNull(new.emphasis)))
        val accented = diff("@@ -1 +1 @@\n-const word = café\n+const word = cafè").hunks.single().lines
        assertEquals("é", accented[0].text.substring(checkNotNull(accented[0].emphasis)))
    }
    @Test fun newlineMarkersDoNotBreakReplacementEmphasisAndLargeEditsStayPlain() {
        val small = diff("@@ -1 +1 @@\n-const value = old\n\\ No newline at end of file\n+const value = new").hunks.single()
        assertNotNull(small.lines[0].emphasis); assertNotNull(small.lines[2].emphasis)
        assertNull(diff("@@ -1 +1 @@\n-abc\n+xyz").hunks.single().lines[0].emphasis)
    }
    @Test fun continuationUsesIosBudgetsAndNeverClaimsUnloadedLines() {
        val doc = diff("@@ -1 +1 @@\n-a\n+b").copy(truncated = true, totalLines = 2)
        val initial = DiffContinuation(6_000, doc)
        assertEquals(24_000, initial.nextBudget); assertEquals(2, initial.shownLines); assertTrue(initial.canGrow)
        assertEquals(96_000, DiffContinuation(24_000, doc).nextBudget)
        assertFalse(DiffContinuation(96_000, doc).canGrow)
        assertFalse(DiffContinuation(24_000, doc, ceiling = true).canGrow)
        assertEquals(12f, clampDiffFont(Float.NaN)); assertEquals(9f, clampDiffFont(2f)); assertEquals(22f, clampDiffFont(100f))
    }
    @Test(expected = IllegalArgumentException::class) fun foreignFileCannotSeedDiffPage() {
        ChangesDiffDocument.read(JSONObject().put("path", "another"), "a")
    }
    @Test fun fileHeaderLookingCodeInsideAHunkIsStillCode() {
        val lines = diff("@@ -1 +1 @@\n--- old code\n+++ new code\n").hunks.single().lines
        assertEquals("-- old code", lines[0].text); assertEquals("++ new code", lines[1].text)
    }
}
