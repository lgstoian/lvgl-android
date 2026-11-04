// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.annotations

import java.io.File

/**
 * Parser for the machine-readable comment schema described in
 * docs/agent-comment-schema.md. Pure JVM: no Android dependencies, so it runs
 * under plain JUnit.
 *
 * @breadcrumb BC-015
 * @breadcrumb BC-034
 * @guard-rule rule=machine-comments test=MachineCommentValidationTest
 */
object MachineCommentParser {

    /** Standard KDoc documentation tags. Allowed in source, but never treated as
     *  machine annotations (they carry prose, not `key=value` pairs). */
    val KDOC_TAGS: Set<String> = setOf(
        "param", "return", "property", "throws", "exception", "see", "since",
        "deprecated", "author", "sample", "suppress"
    )

    val KNOWN_TAGS: Set<String> = setOf(
        "guard-rule", "invariant", "decision", "breadcrumb", "machine-json",
        "contract", "ai-hint", "why", "do-not", "trap"
    ) + KDOC_TAGS

    /** A single parsed annotation. */
    data class Annotation(
        val file: String,
        val line: Int,
        val tag: String,
        val payload: String,
        val pairs: Map<String, String>
    ) {
        /** The BC-NNN / ADR-NNN reference in the payload, if any. */
        val refId: String? =
            pairs["breadcrumb"] ?: pairs["decision"] ?: REF_REGEX.find(payload)?.value
    }

    private val TAG_REGEX =
        Regex("""(?:^|[\s*])@([a-z][a-z0-9-]*)""", RegexOption.MULTILINE)
    private val PAIR_REGEX =
        Regex("""([A-Za-z_][A-Za-z0-9_-]*)=("([^"]*)"|[^\s"@]+)""")
    private val REF_REGEX = Regex("""[A-Z]{2,}-\d+""")

    fun parse(text: String, file: String): List<Annotation> {
        val out = mutableListOf<Annotation>()
        for (u in SourceComments.extractComments(text)) {
            val matches = TAG_REGEX.findAll(u.text).toList()
            matches.forEachIndexed { idx, m ->
                val tag = m.groupValues[1]
                val payloadStart = m.range.last + 1
                val payloadEnd =
                    if (idx + 1 < matches.size) matches[idx + 1].range.first else u.text.length
                val raw = u.text.substring(payloadStart, payloadEnd)
                val payload = if (tag == "machine-json") normalizeBlockPayload(raw) else raw.trim()
                val line = u.startLine + countNewlines(u.text.substring(0, m.range.first))
                val pairs = if (tag == "machine-json") {
                    val json = extractJsonObject(payload)
                    if (json != null) mapOf("json" to json) else emptyMap()
                } else {
                    PAIR_REGEX.findAll(payload).associate { match ->
                        val rawValue = match.groupValues[2]
                        val value = if (rawValue.length >= 2 && rawValue.startsWith("\"") &&
                            rawValue.endsWith("\"")
                        ) {
                            rawValue.substring(1, rawValue.length - 1)
                        } else {
                            rawValue
                        }
                        match.groupValues[1] to value
                    }
                }
                out.add(Annotation(file, line, tag, payload, pairs))
            }
        }
        return out
    }

    fun parse(file: File): List<Annotation> =
        if (file.isFile) parse(file.readText(), file.path) else emptyList()

    /** Strip block-comment `*` prefixes so multi-line JSON stays valid. */
    private fun normalizeBlockPayload(raw: String): String =
        raw.lines().joinToString(" ") { it.trim().removePrefix("*").trim() }.trim()

    /** Return the first balanced `{...}` substring, or null. */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun countNewlines(s: String): Int = s.count { it == '\n' }
}

/** Sentinel for a failed parse, distinct from a valid JSON null. */
private object JsonFail

/**
 * Minimal JSON validator used to check `@machine-json` payloads. Returns null
 * for malformed input so a bad annotation fails the build instead of throwing.
 *
 * @breadcrumb BC-015
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.value()
        if (value === JsonFail) return null
        p.skipWs()
        return if (p.atEnd()) value else null
    }

    fun isValid(text: String): Boolean = parse(text) != null

    private class Parser(val s: String) {
        var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        /** Returns JsonFail on failure, or the parsed value (which may be null). */
        fun value(): Any? {
            if (atEnd()) return JsonFail
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) return JsonFail
            i += word.length
            return result
        }

        private fun obj(): Map<String, Any?>? {
            if (s[i] != '{') return null
            i++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = str() as? String ?: return null
                skipWs()
                if (atEnd() || s[i] != ':') return null
                i++
                skipWs()
                val v = value()
                if (v === JsonFail) return null
                map[key] = v
                skipWs()
                if (atEnd()) return null
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> return null
                }
            }
        }

        private fun arr(): List<Any?>? {
            if (s[i] != '[') return null
            i++
            val list = mutableListOf<Any?>()
            skipWs()
            if (!atEnd() && s[i] == ']') { i++; return list }
            while (true) {
                skipWs()
                val v = value()
                if (v === JsonFail) return null
                list.add(v)
                skipWs()
                if (atEnd()) return null
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> return null
                }
            }
        }

        private fun str(): String? {
            if (atEnd() || s[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) return null
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) return null
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return null
        }

        private fun number(): Any? {
            val start = i
            if (!atEnd() && s[i] == '-') i++
            var digits = 0
            while (!atEnd() && s[i].isDigit()) { i++; digits++ }
            if (digits == 0) return JsonFail
            if (!atEnd() && s[i] == '.') {
                i++
                var frac = 0
                while (!atEnd() && s[i].isDigit()) { i++; frac++ }
                if (frac == 0) return JsonFail
            }
            if (!atEnd() && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (!atEnd() && (s[i] == '+' || s[i] == '-')) i++
                var exp = 0
                while (!atEnd() && s[i].isDigit()) { i++; exp++ }
                if (exp == 0) return JsonFail
            }
            val token = s.substring(start, i)
            return token.toLongOrNull() ?: token.toDoubleOrNull() ?: JsonFail
        }
    }
}
