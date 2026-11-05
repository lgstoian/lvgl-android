// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.guards

import com.lvgl.android.annotations.AnnotationValidator
import com.lvgl.android.annotations.MachineCommentParser
import com.lvgl.android.annotations.MiniJson
import com.lvgl.android.annotations.SourceComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mutation-style self-checks for the guard and annotation infrastructure.
 *
 * Each detector is fed a deliberately broken fixture that MUST be detected
 * (protects against a false green: a guard that silently checks nothing) and a
 * clean fixture that MUST NOT be detected (protects against a false positive:
 * a guard that fails on valid code).
 *
 * @breadcrumb BC-014
 * @guard-rule rule=guard-canary test=GuardCanaryTest
 */
class GuardCanaryTest {

    // ---- setContentView detector -------------------------------------------

    @Test
    fun setContentViewDetectorCatchesRealViolation() {
        val source = """
            class FooActivity : LvglActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(rootLayout)
                }
            }
        """.trimIndent()
        val hits = GuardRules.detectSetContentView("FooActivity.kt", source)
        assertTrue("Canary failed: detector must catch a real setContentView() call", hits.isNotEmpty())
        assertEquals(1, hits.size)
    }

    @Test
    fun setContentViewDetectorIgnoresCommentsAndCleanCode() {
        val commented = """
            // setContentView() is forbidden here
            /* setContentView(rootLayout) */
            class FooActivity : LvglActivity()
        """.trimIndent()
        assertTrue(
            "Canary failed: detector must ignore setContentView() mentioned in comments",
            GuardRules.detectSetContentView("FooActivity.kt", commented).isEmpty()
        )

        val clean = "class FooActivity : LvglActivity() {\n    fun f() = 1\n}"
        assertTrue(
            "Canary failed: detector must ignore clean code",
            GuardRules.detectSetContentView("FooActivity.kt", clean).isEmpty()
        )
    }

    // ---- hardcoded string detector ----------------------------------------

    @Test
    fun hardcodedStringDetectorCatchesRealViolation() {
        val source = """
            class FooActivity : LvglActivity() {
                override fun onCreateLvgl() {
                    setStatus("Welcome to the demo")
                }
            }
        """.trimIndent()
        val hits = GuardRules.detectHardcodedUserStrings("FooActivity.kt", source)
        assertTrue("Canary failed: detector must catch a user-visible literal", hits.isNotEmpty())
    }

    @Test
    fun hardcodedStringDetectorIgnoresKeysCommentsAndResources() {
        val source = """
            class FooActivity : LvglActivity() {
                // "Welcome to the demo" is only a comment
                /* "Another commented string" */
                fun f() {
                    Log.d(TAG, "onCreate")
                    val name = "SCREEN_MENU"
                    val res = getString(R.string.app_name)
                    val cls = FooActivity::class.java
                }
            }
        """.trimIndent()
        assertTrue(
            "Canary failed: detector must ignore keys, comments and resources",
            GuardRules.detectHardcodedUserStrings("FooActivity.kt", source).isEmpty()
        )
    }

    @Test
    fun isUserVisibleClassifiesLiterals() {
        assertTrue(GuardRules.isUserVisible("Welcome to the demo"))
        assertTrue(GuardRules.isUserVisible("Tap to continue"))
        assertFalse(GuardRules.isUserVisible("SCREEN_MENU"))
        assertFalse(GuardRules.isUserVisible("com.lvgl.sample"))
        assertFalse(GuardRules.isUserVisible("LVGLPort"))
        assertFalse(GuardRules.isUserVisible("abc"))
        assertFalse(GuardRules.isUserVisible("12345"))
    }

    // ---- single-source-of-truth detector ----------------------------------

    @Test
    fun duplicateScreenBuilderDetectorCatchesRealViolation() {
        val missingImpl = GuardRules.detectDuplicateScreenBuilders(
            header = "void build_menu_screen(int dpi);\n",
            impl = "",
            showcase = "",
            port = ""
        )
        assertTrue("Canary failed: detector must catch an unimplemented builder", missingImpl.isNotEmpty())

        val portBuildsUi = GuardRules.detectDuplicateScreenBuilders(
            header = "",
            impl = "",
            showcase = "",
            port = "void f() { lv_obj_create(parent); }\n"
        )
        assertTrue("Canary failed: detector must catch UI built in the port", portBuildsUi.isNotEmpty())
    }

    @Test
    fun duplicateScreenBuilderDetectorIgnoresCleanSources() {
        val hits = GuardRules.detectDuplicateScreenBuilders(
            header = "void build_menu_screen(int dpi);\n",
            impl = "void build_menu_screen(int /*dpi*/) {\n}\n",
            showcase = "",
            port = "void f() { lv_screen_active(); }\n"
        )
        assertTrue("Canary failed: detector must ignore a clean split", hits.isEmpty())
    }

    // ---- dynamic harness crash pattern detector ---------------------------

    @Test
    fun unscopedCrashPatternDetectorCatchesBroadMarkers() {
        val broad = "\$crashPattern = 'FATAL|SIGSEGV|start timeout|ANR in com\\.lvgl'"
        assertTrue(
            "Canary failed: detector must catch a broad crash pattern",
            GuardRules.detectUnscopedCrashPattern(broad).isNotEmpty()
        )
    }

    @Test
    fun unscopedCrashPatternDetectorIgnoresAppScopedPattern() {
        val appScoped = "\$crashPattern = 'ANR in com\\.lvgl\\.sample|>>> com\\.lvgl\\.sample <<<'"
        assertTrue(
            "Canary failed: detector must ignore an app-scoped crash pattern",
            GuardRules.detectUnscopedCrashPattern(appScoped).isEmpty()
        )
    }

    @Test
    fun dynamicHarnessCrashPatternRequiresDefinition() {
        assertNull(
            "Canary failed: a missing pattern must extract as null",
            GuardRules.dynamicHarnessCrashPattern("# no pattern here")
        )
        assertTrue(
            "Canary failed: a missing pattern must be reported",
            GuardRules.detectUnscopedCrashPattern("# no pattern here").isNotEmpty()
        )
    }

    // ---- annotation parser ------------------------------------------------

    @Test
    fun annotationParserReadsAllTagsAndPayloads() {
        val source = """
            /**
             * @breadcrumb BC-001
             * @decision ADR-002
             * @guard-rule rule=no-set-content-view test=HardRuleGuardTest
             * @machine-json {"owner_scope":"activity","rebinding":true}
             */
        """.trimIndent()
        val anns = MachineCommentParser.parse(source, "Test.kt")
        assertEquals(4, anns.size)
        assertEquals("BC-001", anns[0].refId)
        assertEquals("ADR-002", anns[1].refId)
        assertEquals("no-set-content-view", anns[2].pairs["rule"])
        assertEquals("HardRuleGuardTest", anns[2].pairs["test"])
        assertTrue(MiniJson.isValid(anns[3].pairs["json"] ?: ""))
    }

    @Test
    fun annotationParserReportsUnknownTags() {
        val source = "// @unknown-tag foo\nval x = 1"
        val anns = MachineCommentParser.parse(source, "Test.kt")
        assertEquals(1, anns.size)
        assertEquals("unknown-tag", anns[0].tag)
        assertFalse(anns[0].tag in MachineCommentParser.KNOWN_TAGS)
    }

    @Test
    fun annotationParserIgnoresAnnotationsInsideStringLiterals() {
        val source = "val doc = \"@breadcrumb BC-999\"\n// @breadcrumb BC-001\n"
        val anns = MachineCommentParser.parse(source, "Test.kt")
        assertEquals("Parser must skip string literals", 1, anns.size)
        assertEquals("BC-001", anns[0].refId)
    }

    @Test
    fun annotationParserNormalizesMultiLineJson() {
        val source = "/*\n * @machine-json {\"a\":1,\n *   \"b\":2}\n */"
        val anns = MachineCommentParser.parse(source, "Test.kt")
        assertEquals(1, anns.size)
        assertTrue(MiniJson.isValid(anns[0].pairs["json"] ?: ""))
    }

    // ---- annotation validator canaries -----------------------------------

    @Test
    fun validatorCatchesDanglingBreadcrumb() {
        val anns = MachineCommentParser.parse("// @breadcrumb BC-999", "X.kt")
        val errors = AnnotationValidator.validate(anns, setOf("BC-001"), emptySet(), emptySet())
        assertTrue(
            "Canary failed: validator must catch a dangling @breadcrumb",
            errors.any { it.contains("dangling @breadcrumb BC-999") }
        )
    }

    @Test
    fun validatorCatchesOrphanBreadcrumb() {
        val anns = MachineCommentParser.parse("// @breadcrumb BC-001", "X.kt")
        val errors = AnnotationValidator.validate(anns, setOf("BC-001", "BC-002"), emptySet(), emptySet())
        assertTrue(
            "Canary failed: validator must catch an orphaned breadcrumb",
            errors.any { it.contains("BC-002") }
        )
    }

    @Test
    fun validatorCatchesUnknownTag() {
        val anns = MachineCommentParser.parse("// @bogus x", "X.kt")
        val errors = AnnotationValidator.validate(anns, emptySet(), emptySet(), emptySet())
        assertTrue(
            "Canary failed: validator must catch an unknown tag",
            errors.any { it.contains("unknown tag") }
        )
    }

    @Test
    fun validatorCatchesUnimplementedRule() {
        val anns = MachineCommentParser.parse(
            "// @guard-rule rule=no-such-rule test=HardRuleGuardTest", "X.kt"
        )
        val errors = AnnotationValidator.validate(anns, emptySet(), emptySet(), setOf("HardRuleGuardTest"))
        assertTrue(
            "Canary failed: validator must catch an unimplemented guard rule",
            errors.any { it.contains("unimplemented rule") }
        )
    }

    @Test
    fun validatorAcceptsCleanAnnotations() {
        val anns = MachineCommentParser.parse(
            "/*\n * @breadcrumb BC-001\n * @decision ADR-002\n" +
                " * @guard-rule rule=no-set-content-view test=HardRuleGuardTest\n" +
                " * @machine-json {\"ok\":true}\n */",
            "X.kt"
        )
        val errors = AnnotationValidator.validate(
            anns, setOf("BC-001"), setOf("ADR-002"), setOf("HardRuleGuardTest")
        )
        assertTrue(
            "Canary failed: validator must accept clean annotations:\n" + errors.joinToString("\n"),
            errors.isEmpty()
        )
    }

    // ---- guard-rule registry ----------------------------------------------

    @Test
    fun requiredFeaturesCoverAppTypes() {
        val features = GuardRules.requiredFeatures
        listOf("IME service", "Launcher activity", "Game activity", "Camera preview").forEach {
            assertTrue("Canary failed: requiredFeatures missing '$it'", features.containsKey(it))
        }
        assertTrue("Canary failed: requiredFeatures is too small", features.size >= 10)
    }

    @Test
    fun ignoredLineDoesNotHideRealViolations() {
        // A line that merely mentions an API must not hide a user-visible literal.
        val source = "fun f() {\n    setScreen(4)\n    val label = \"Welcome to the demo\"\n}"
        val hits = GuardRules.detectHardcodedUserStrings("XActivity.kt", source)
        assertTrue("Canary failed: detector must flag a literal after an API call", hits.isNotEmpty())
        // ...but a log call must not be flagged.
        val logOnly = "fun f() {\n    Log.d(TAG, \"onCreate\")\n}"
        assertTrue(
            "Canary failed: detector must ignore log lines",
            GuardRules.detectHardcodedUserStrings("XActivity.kt", logOnly).isEmpty()
        )
    }

    @Test
    fun duplicateScreenBuilderDetectorCatchesShowcaseDrift() {
        // Declared in demo_ui.h, missing from both demo_ui.cpp and demo_showcase.cpp.
        val hits = GuardRules.detectDuplicateScreenBuilders(
            header = "void build_menu_screen(int dpi);\n",
            impl = "",
            showcase = "",
            port = ""
        )
        assertTrue("Canary failed: detector must catch an unimplemented builder", hits.isNotEmpty())

        // UI built in the port must be caught.
        val portHits = GuardRules.detectDuplicateScreenBuilders("", "", "", "void f() { lv_label_create(p); }\n")
        assertTrue("Canary failed: detector must catch UI built in the port", portHits.isNotEmpty())
    }

    // ---- MiniJson ---------------------------------------------------------

    @Test
    fun miniJsonAcceptsValidPayloads() {
        assertNotNull(MiniJson.parse("""{"a":1,"b":[1,2,3],"c":"x","d":true,"e":null,"f":-1.5e2}"""))
        assertNotNull(MiniJson.parse("[]"))
        assertNotNull(MiniJson.parse("{}"))
        assertNotNull(MiniJson.parse("\"hello\""))
    }

    @Test
    fun miniJsonRejectsInvalidPayloads() {
        assertNull(MiniJson.parse(""))
        assertNull(MiniJson.parse("{"))
        assertNull(MiniJson.parse("""{"a":}"""))
        assertNull(MiniJson.parse("""{"a" 1}"""))
        assertNull(MiniJson.parse("[1,]"))
        assertNull(MiniJson.parse("""{"a":1,}"""))
        assertNull(MiniJson.parse("nul"))
        assertNull(MiniJson.parse("""{"a":1} trailing"""))
    }

    // ---- shared scanner ---------------------------------------------------

    @Test
    fun stripCommentsPreservesLineNumbers() {
        val source = "line1\n// comment\nline3\n/* block\nspanning */\nline6"
        val stripped = SourceComments.stripComments(source)
        assertEquals(source.lines().size, stripped.lines().size)
        assertFalse(stripped.contains("comment"))
        assertTrue(stripped.contains("line3"))
        assertTrue(stripped.contains("line6"))
    }
}
