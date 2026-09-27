/* Translated from cmux MobileTaskAgentProvider.swift at
 * 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

/** Edits only provider options in the first simple command; all other source is preserved. */
internal enum class TaskAgentCommand(val wireName: String, private val modelFlags: List<String>) {
    CLAUDE("claude", listOf("--model")), CODEX("codex", listOf("-m", "--model")),
    OPENCODE("opencode", listOf("--model", "-m"));

    fun apply(command: String, model: String? = null, effort: String? = null): String {
        val withModel = model?.let { option(command, it, modelFlags) } ?: command
        return effort?.let {
            when (this) {
                CLAUDE -> option(withModel, it, listOf("--effort"))
                CODEX -> codexEffort(withModel, it)
                OPENCODE -> option(withModel, it, listOf("--variant"))
            }
        } ?: withModel
    }

    private data class Word(val start: Int, val end: Int)
    private data class Edit(val start: Int, val end: Int, val value: String)
    private fun replace(command: String, edits: List<Edit>): String {
        val result = StringBuilder(command)
        edits.asReversed().forEach { result.replace(it.start, it.end, it.value) }
        return result.toString()
    }
    private fun option(command: String, value: String, flags: List<String>): String {
        val first = token(command, 0) ?: return command
        val quoted = quote(value)
        val edits = mutableListOf<Edit>()
        var from = first.end
        while (true) {
            val word = token(command, from) ?: break
            if (hasNewline(command.substring(from, word.start))) break
            from = word.end
            val text = command.substring(word.start, word.end)
            if (text == "--" || text.startsWith('#')) break
            val boundary = boundary(command, word)
            val end = boundary ?: word.end
            val prefix = command.substring(word.start, end)
            if (prefix in flags) {
                if (boundary != null) { edits += Edit(boundary, boundary, " $quoted"); break }
                val argument = token(command, word.end)
                if (argument != null && !hasNewline(command.substring(word.end, argument.start)) &&
                    command.substring(argument.start, argument.end) != "--") {
                    val argumentBoundary = boundary(command, argument)
                    edits += Edit(argument.start, argumentBoundary ?: argument.end, quoted)
                    if (argumentBoundary != null) break
                    from = argument.end
                } else edits += Edit(word.end, word.end, " $quoted")
                continue
            }
            flags.firstOrNull { prefix.startsWith("$it=") }?.let {
                edits += Edit(word.start, end, "$it=$quoted")
            }
            if (boundary != null) break
        }
        return if (edits.isNotEmpty()) replace(command, edits)
        else command.substring(0, first.end) + " ${flags.first()} $quoted" + command.substring(first.end)
    }
    private fun codexEffort(command: String, effort: String): String {
        val first = token(command, 0) ?: return command
        val replacement = "model_reasoning_effort=${quote(effort)}"
        val edits = mutableListOf<Edit>()
        var from = first.end
        while (true) {
            val word = token(command, from) ?: break
            if (hasNewline(command.substring(from, word.start))) break
            from = word.end
            val text = command.substring(word.start, word.end)
            if (text == "--" || text.startsWith('#')) break
            val boundary = boundary(command, word)
            val end = boundary ?: word.end
            if (command.substring(word.start, end).startsWith("--config=model_reasoning_effort="))
                edits += Edit(word.start, end, "--config=$replacement")
            if (boundary != null) break
            if (text != "-c" && text != "--config") continue
            val argument = token(command, word.end) ?: break
            if (hasNewline(command.substring(word.end, argument.start))) break
            val argumentBoundary = boundary(command, argument)
            val argumentEnd = argumentBoundary ?: argument.end
            if (command.substring(argument.start, argumentEnd).startsWith("model_reasoning_effort="))
                edits += Edit(argument.start, argumentEnd, replacement)
            from = argument.end
            if (argumentBoundary != null) break
        }
        return if (edits.isNotEmpty()) replace(command, edits)
        else command.substring(0, first.end) + " -c $replacement" + command.substring(first.end)
    }

    companion object {
        fun detect(command: String): TaskAgentCommand? {
            val first = token(command, 0) ?: return null
            val basename = command.substring(first.start, first.end).split('/').lastOrNull { it.isNotEmpty() }
            return entries.firstOrNull { it.wireName == basename }
        }
        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        private fun hasNewline(value: String) = value.any { it in "\n\r\u000B\u000C\u0085\u2028\u2029" }
        private fun token(command: String, from: Int): Word? {
            var index = from
            while (index < command.length && command[index].isWhitespace()) index++
            if (index == command.length) return null
            val start = index
            var single = false; var double = false
            while (index < command.length) {
                val c = command[index]
                if (c == '\\' && !single) { index = minOf(index + 2, command.length); continue }
                if (c == '\'' && !double) single = !single
                else if (c == '"' && !single) double = !double
                else if (c.isWhitespace() && !single && !double) break
                index++
            }
            return Word(start, index)
        }
        private fun boundary(command: String, word: Word): Int? {
            var single = false; var double = false; var previous: Char? = null
            var index = word.start
            while (index < word.end) {
                val c = command[index]
                if (c == '\\' && !single) {
                    index++
                    if (index < word.end) { previous = command[index]; index++ }
                    continue
                }
                if (c == '\'' && !double) single = !single
                else if (c == '"' && !single) double = !double
                else if (!single && !double && (c == ';' || (c == '|' && previous != '>') ||
                    (c == '&' && previous != '>' && command.getOrNull(index + 1) != '>'))) return index
                previous = c; index++
            }
            return null
        }
    }
}
