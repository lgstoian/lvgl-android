// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.annotations

/**
 * Shared source scanner: extracts comments and strips them while preserving line
 * numbers. Single source of truth for both the annotation parser and the guard
 * rules so comment handling cannot drift between them.
 *
 * @breadcrumb BC-015
 */
object SourceComments {

    /** One comment unit: a `//` line or a whole `/* */` block. */
    data class CommentUnit(val startLine: Int, val text: String)

    private class Scan(val index: Int, val newlines: Int)

    /** Extract every comment unit, skipping string/char literals. */
    fun extractComments(text: String): List<CommentUnit> {
        val units = mutableListOf<CommentUnit>()
        var i = 0
        var line = 1
        val n = text.length
        var inUnit = false
        var unitIsLine = false
        var unitStartLine = 0
        val sb = StringBuilder()

        fun closeUnit() {
            if (inUnit) {
                units.add(CommentUnit(unitStartLine, sb.toString()))
                inUnit = false
                sb.setLength(0)
            }
        }

        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else ' '
            if (!inUnit) {
                when {
                    c == '/' && next == '/' -> {
                        inUnit = true; unitIsLine = true; unitStartLine = line; i += 2; continue
                    }
                    c == '/' && next == '*' -> {
                        inUnit = true; unitIsLine = false; unitStartLine = line; i += 2; continue
                    }
                    c == '"' -> { val s = skipString(text, i); i = s.index; line += s.newlines; continue }
                    c == '\'' -> { val s = skipChar(text, i); i = s.index; line += s.newlines; continue }
                    c == '\n' -> line++
                }
                i++
            } else {
                when {
                    c == '/' && next == '*' -> { sb.append(c); i++ }
                    c == '*' && next == '/' -> { closeUnit(); i += 2; continue }
                    c == '\n' -> {
                        if (unitIsLine) closeUnit() else sb.append('\n')
                        line++; i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }
        closeUnit()
        return units
    }

    /** Return the source with comments replaced by spaces; newlines preserved. */
    fun stripComments(text: String): String {
        val out = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else ' '
            when {
                c == '/' && next == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) {
                        if (text[i] == '\n') out.append('\n')
                        i++
                    }
                    if (i < n) i += 2
                }
                c == '"' -> i = copyString(text, i, out)
                c == '\'' -> i = copyChar(text, i, out)
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun copyString(text: String, start: Int, out: StringBuilder): Int {
        val n = text.length
        if (start + 2 < n && text[start + 1] == '"' && text[start + 2] == '"') {
            out.append("\"\"\"")
            var i = start + 3
            while (i < n) {
                if (i + 2 < n && text[i] == '"' && text[i + 1] == '"' && text[i + 2] == '"') {
                    out.append("\"\"\"")
                    return i + 3
                }
                out.append(text[i]); i++
            }
            return i
        }
        out.append('"')
        var i = start + 1
        while (i < n) {
            val ch = text[i]
            out.append(ch); i++
            if (ch == '\\' && i < n) { out.append(text[i]); i++ }
            if (ch == '"' || ch == '\n') break
        }
        return i
    }

    private fun copyChar(text: String, start: Int, out: StringBuilder): Int {
        val n = text.length
        out.append('\'')
        var i = start + 1
        while (i < n) {
            val ch = text[i]
            out.append(ch); i++
            if (ch == '\\' && i < n) { out.append(text[i]); i++ }
            if (ch == '\'' || ch == '\n') break
        }
        return i
    }

    private fun skipString(text: String, start: Int): Scan {
        val n = text.length
        if (start + 2 < n && text[start] == '"' && text[start + 1] == '"' && text[start + 2] == '"') {
            var i = start + 3
            var nl = 0
            while (i < n) {
                if (text[i] == '\n') nl++
                if (i + 2 < n && text[i] == '"' && text[i + 1] == '"' && text[i + 2] == '"') {
                    return Scan(i + 3, nl)
                }
                i++
            }
            return Scan(n, nl)
        }
        var i = start + 1
        var nl = 0
        while (i < n) {
            val c = text[i]
            if (c == '\\') { i += 2; continue }
            if (c == '\n') { nl++; return Scan(i, nl) }
            if (c == '"') return Scan(i + 1, nl)
            i++
        }
        return Scan(n, nl)
    }

    private fun skipChar(text: String, start: Int): Scan {
        val n = text.length
        var i = start + 1
        var nl = 0
        while (i < n) {
            val c = text[i]
            if (c == '\\') { i += 2; continue }
            if (c == '\n') { nl++; return Scan(i, nl) }
            if (c == '\'') return Scan(i + 1, nl)
            i++
        }
        return Scan(n, nl)
    }
}
