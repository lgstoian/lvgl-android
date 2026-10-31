// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.annotations

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates every machine-readable comment annotation in the repo against the
 * schema in docs/agent-comment-schema.md and the trail in
 * docs/agent-breadcrumbs.json.
 *
 * This is the "false green" guard for the annotation system itself: it asserts
 * it actually found annotations and source files, so a broken repo-root path
 * fails loudly instead of validating nothing.
 *
 * @breadcrumb BC-015
 * @guard-rule rule=machine-comments test=MachineCommentValidationTest
 */
class MachineCommentValidationTest {

    private val repoRoot: File = locateRepoRoot()

    private fun locateRepoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: File("..").absoluteFile

    private val trailFile = File(repoRoot, "docs/agent-breadcrumbs.json")
    private val roadmapFile = File(repoRoot, "ROADMAP.md")

    private val requiredFields = listOf(
        "id", "title", "decision", "rationale", "consequences",
        "files", "symbols", "see_also", "do_not", "ai_hint"
    )

    @Test
    fun scannerCanSeeSourcesAndAnnotations() {
        assertTrue(
            "Cannot locate repo sources (looked for app/src/main/java under $repoRoot)",
            File(repoRoot, "app/src/main/java").isDirectory
        )
        val files = sourceFiles()
        assertTrue("Scanner found no source files under $repoRoot", files.isNotEmpty())
        val annotations = files.flatMap { MachineCommentParser.parse(it) }
        assertTrue(
            "Scanner found no @tag annotations; the parser or repo path is broken",
            annotations.isNotEmpty()
        )
    }

    @Test
    fun breadcrumbTrailIsWellFormed() {
        assertTrue("Breadcrumb trail missing: ${trailFile.path}", trailFile.isFile)
        val root = MiniJson.parse(trailFile.readText())
        assertTrue("Breadcrumb trail is not a JSON object", root is Map<*, *>)

        @Suppress("UNCHECKED_CAST")
        val entries = (root as Map<String, Any?>)["entries"] as? List<*>
        assertTrue("Breadcrumb trail has no entries array", entries != null && entries.isNotEmpty())

        val ids = mutableSetOf<String>()
        entries!!.forEachIndexed { idx, raw ->
            @Suppress("UNCHECKED_CAST")
            val entry = raw as? Map<String, Any?>
            assertTrue("entries[$idx] is not an object", entry != null)
            val id = entry!!["id"] as? String
            assertTrue("entries[$idx] has no id", id != null)
            assertTrue("Duplicate breadcrumb id $id", ids.add(id!!))
            requiredFields.forEach { field ->
                val v = entry[field]
                assertTrue("Breadcrumb $id missing field '$field'", v != null)
                if (v is String) {
                    assertTrue("Breadcrumb $id field '$field' is blank", v.isNotBlank())
                }
            }
            val files = entry["files"] as? List<*>
            assertTrue("Breadcrumb $id has no files array", files != null)
            files!!.forEach { rel ->
                val f = File(repoRoot, rel.toString())
                assertTrue("Breadcrumb $id references missing file $rel", f.isFile)
            }
        }
    }

    @Test
    fun annotationsAreWellFormedAndResolve() {
        val trail = loadTrailIds()
        val adrs = loadAdrIds()
        val testClassNames = testSourceFiles().map { it.name.removeSuffix(".kt") }.toSet()
        val annotations = sourceFiles().flatMap { MachineCommentParser.parse(it) }
        assertTrue("No annotations parsed; parser is broken", annotations.isNotEmpty())
        val errors = AnnotationValidator.validate(annotations, trail, adrs, testClassNames)
        assertTrue("Annotation validation failed:\n" + errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun noUnknownTagsInRepo() {
        val errors = mutableListOf<String>()
        sourceFiles().flatMap { MachineCommentParser.parse(it) }.forEach { a ->
            if (a.tag !in MachineCommentParser.KNOWN_TAGS) {
                errors.add("${a.file}:${a.line}: unknown tag '@${a.tag}'")
            }
        }
        assertTrue("Unknown annotation tags:\n" + errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun breadcrumbTrailIsNonEmpty() {
        assertTrue("Breadcrumb trail is empty", loadTrailIds().isNotEmpty())
    }

    @Test
    fun breadcrumbSeeAlsoResolves() {
        val trail = loadTrailIds()
        val adrs = loadAdrIds()
        val errors = mutableListOf<String>()
        val root = MiniJson.parse(trailFile.readText()) as? Map<*, *> ?: return
        val entries = root["entries"] as? List<*> ?: return
        entries.forEach { raw ->
            val entry = raw as? Map<*, *> ?: return@forEach
            val id = entry["id"] as? String ?: return@forEach
            (entry["see_also"] as? List<*>)?.forEach { ref ->
                val r = ref.toString()
                if (r !in trail && r !in adrs) {
                    errors.add("Breadcrumb $id see_also references unknown $r")
                }
            }
        }
        assertTrue("see_also validation failed:\n" + errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun roadmapStillHasAdrs() {
        assertTrue("ROADMAP.md missing: ${roadmapFile.path}", roadmapFile.isFile)
        assertTrue("No ADR headings found in ROADMAP.md", loadAdrIds().isNotEmpty())
    }

    private fun loadTrailIds(): Set<String> {
        val root = MiniJson.parse(trailFile.readText()) as? Map<*, *> ?: return emptySet()
        val entries = root["entries"] as? List<*> ?: return emptySet()
        return entries.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }.toSet()
    }

    private fun loadAdrIds(): Set<String> =
        Regex("""###\s+ADR-(\d+)""").findAll(roadmapFile.readText())
            .map { "ADR-" + it.groupValues[1] }
            .toSet()

    private fun sourceFiles(): List<File> {
        val roots = listOf("lvgl-android/src/main", "lvgl-android/src/test", "app/src/main")
        val out = mutableListOf<File>()
        roots.forEach { rel ->
            val dir = File(repoRoot, rel)
            if (dir.isDirectory) {
                dir.walkTopDown()
                    .filter { it.isFile && it.extension in setOf("kt", "cpp", "h") }
                    .forEach { out.add(it) }
            }
        }
        return out
    }

    private fun testSourceFiles(): List<File> {
        val dir = File(repoRoot, "lvgl-android/src/test")
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
