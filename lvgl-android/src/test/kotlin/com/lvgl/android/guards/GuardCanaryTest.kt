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
 * a guard that fails on valid code). Canaries call the exact public detector
 * functions the production guard tests call, so detection is proven wired.
 *
 * @breadcrumb BC-014
 * @breadcrumb BC-037
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
        val ids = """
            object Screen {
                private const val ID_COUNTER = "counter"
                private const val KEY_SLIDER = "slider_value"
            }
        """.trimIndent()
        assertTrue(
            "Canary failed: detector must ignore keys, comments and resources",
            GuardRules.detectHardcodedUserStrings("FooActivity.kt", source).isEmpty()
        )
        assertTrue(
            "Canary failed: DSL node ids are keys, not user-visible strings",
            GuardRules.detectHardcodedUserStrings("AppDemoScreen.kt", ids).isEmpty()
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

    @Test
    fun annotationArgumentKeyIsNotAUserVisibleString() {
        val clean = """
            @SuppressLint("MissingPermission")
            fun openCamera() = Unit
        """.trimIndent()
        assertTrue(
            "Canary failed: an annotation key must not be treated as user prose",
            GuardRules.detectHardcodedUserStrings("CameraSampleActivity.kt", clean).isEmpty(),
        )

        // Excluding the annotation must not blank out the whole line.
        val mixed = """@SuppressLint("MissingPermission") fun f() = setStatus("Welcome to the demo")"""
        val hits = GuardRules.detectHardcodedUserStrings("CameraSampleActivity.kt", mixed)
        assertTrue(
            "Canary failed: a user-visible literal after an annotation must still be caught",
            hits.any { "Welcome to the demo" in it.text },
        )
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

    // ---- fail-closed wiring canaries ---------------------------------------

    private data class PlayFixture(
        val build: String,
        val manifest: String,
        val strings: String,
        val files: Map<String, String>
    )

    private fun cleanPlayFixture(): PlayFixture {
        val build = """
            compileSdk = 36
            defaultConfig {
                targetSdk = 36
                ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            }
            splits { abi { include("arm64-v8a", "x86_64"); isUniversalApk = true } }
            buildTypes { release {
                isMinifyEnabled = true
                isShrinkResources = true
            } }
        """.trimIndent()
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <uses-permission android:name="android.permission.CAMERA" />
              <queries>
                <intent>
                  <action android:name="android.intent.action.MAIN" />
                  <category android:name="android.intent.category.LAUNCHER" />
                </intent>
              </queries>
              <application
                  android:dataExtractionRules="@xml/data_extraction_rules"
                  android:fullBackupContent="@xml/backup_rules"
                  android:enableOnBackInvokedCallback="true"
                  android:localeConfig="@xml/locales_config"
                  android:resizeableActivity="true">
                <activity android:name=".MainActivity" android:exported="true">
                  <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
                </activity>
                <activity android:name=".FooActivity" android:exported="false" />
              </application>
            </manifest>
        """.trimIndent()
        val strings = """<resources><string name="licenses_text">Licenses</string></resources>"""
        val files = mapOf(
            "app/src/main/res/xml/backup_rules.xml" to
                """<full-backup-content><exclude domain="root" path="." /></full-backup-content>""",
            "app/src/main/res/xml/data_extraction_rules.xml" to
                """<data-extraction-rules><cloud-backup><exclude domain="root" path="." /></cloud-backup>""" +
                """<device-transfer><exclude domain="root" path="." /></device-transfer></data-extraction-rules>""",
            "app/src/main/res/xml/locales_config.xml" to
                """<locale-config><locale android:name="en" /></locale-config>""",
            "PRIVACY.md" to "# Privacy",
            "docs/play-store.md" to "# Play",
            "app/src/main/java/com/lvgl/sample/LicensesActivity.kt" to "class LicensesActivity",
        )
        return PlayFixture(build, manifest, strings, files)
    }

    @Test
    fun playComplianceDetectorCatchesBrokenValues() {
        val clean = cleanPlayFixture()
        val broken = clean.copy(
            build = clean.build
                .replace("targetSdk = 36", "targetSdk = 35")
                .replace("\"arm64-v8a\"", "\"armeabi-v7a\"")
                .replace("isMinifyEnabled = true", "isMinifyEnabled = false"),
            manifest = clean.manifest
                .replace("enableOnBackInvokedCallback=\"true\"", "enableOnBackInvokedCallback=\"false\"")
                .replace("resizeableActivity=\"true\"", "resizeableActivity=\"false\"")
                .replace(
                    "<uses-permission android:name=\"android.permission.CAMERA\" />",
                    "<uses-permission android:name=\"android.permission.CAMERA\" />" +
                        "<uses-permission android:name=\"android.permission.INTERNET\" />"
                ),
            files = clean.files + ("app/src/main/res/xml/locales_config.xml" to ""),
        )
        val hits = GuardRules.detectPlayComplianceViolations(
            broken.build, broken.manifest, broken.strings, broken.files
        )
        listOf(
            "targetSdk", "64-bit ABI", "isMinifyEnabled", "enableOnBackInvokedCallback",
            "resizeableActivity", "permission.CAMERA", "locales_config.xml",
        ).forEach { part ->
            assertTrue(
                "Canary failed: detector must catch broken Play value '$part'",
                hits.any { part in it.reason },
            )
        }
    }

    @Test
    fun playComplianceDetectorCatchesWrongActivityExportedValues() {
        val clean = cleanPlayFixture()
        val noFilterExported = GuardRules.detectPlayComplianceViolations(
            clean.build,
            clean.manifest.replace(
                "android:name=\".FooActivity\" android:exported=\"false\"",
                "android:name=\".FooActivity\" android:exported=\"true\"",
            ),
            clean.strings,
            clean.files,
        )
        assertTrue(
            "Canary failed: an activity without an intent filter must not be exported",
            noFilterExported.any {
                ".FooActivity has no intent filter and must be exported=\"false\"" in it.reason
            },
        )

        val filterNotExported = GuardRules.detectPlayComplianceViolations(
            clean.build,
            clean.manifest.replace(
                "android:name=\".MainActivity\" android:exported=\"true\"",
                "android:name=\".MainActivity\" android:exported=\"false\"",
            ),
            clean.strings,
            clean.files,
        )
        assertTrue(
            "Canary failed: an activity with an intent filter must be exported",
            filterNotExported.any {
                "MainActivity has an intent filter and must be exported=\"true\"" in it.reason
            },
        )
    }

    @Test
    fun playComplianceDetectorIgnoresCleanFixture() {
        val clean = cleanPlayFixture()
        val hits = GuardRules.detectPlayComplianceViolations(
            clean.build, clean.manifest, clean.strings, clean.files
        )
        assertTrue(
            "Canary failed: clean Play fixture must pass:\n" + hits.joinToString("\n") { it.reason },
            hits.isEmpty(),
        )
    }

    @Test
    fun hardRuleScannerCatchesViolationInNonActivityFile() {
        val source = """
            object Helper {
                fun show() {
                    setContentView(root)
                    setStatus("Welcome to the demo")
                }
            }
        """.trimIndent()
        val hits = GuardRules.detectHardRuleViolations(listOf("Helper.kt" to source))
        assertTrue(
            "Canary failed: setContentView in a non-Activity file must be caught",
            hits.any { "setContentView" in it.reason },
        )
        assertTrue(
            "Canary failed: a user-visible literal in a non-Activity file must be caught",
            hits.any { "strings.xml" in it.reason },
        )
    }

    @Test
    fun hardRuleScannerIgnoresCleanNonActivityFile() {
        val hits = GuardRules.detectHardRuleViolations(
            listOf("Helper.kt" to "object Helper { fun compute() = 42 }")
        )
        assertTrue("Canary failed: clean helper file must pass", hits.isEmpty())
    }

    @Test
    fun layoutLintDetectorFailsClosedOnBrokenReport() {
        val hits = LayoutLintRules.validateJson("not json")
        assertTrue(
            "Canary failed: a malformed report must produce an invalid-report violation",
            hits.any { it.startsWith(LayoutLintRules.INVALID_REPORT_TYPE) },
        )
        assertTrue(
            "Canary failed: an invalid report must not be allow-listed",
            LayoutLintRules.validateJson(
                "not json", setOf(LayoutLintRules.INVALID_REPORT_TYPE)
            ).isNotEmpty(),
        )
    }

    @Test
    fun layoutLintDetectorIgnoresCleanReport() {
        assertTrue(
            "Canary failed: a clean report must pass",
            LayoutLintRules.validateJson("""{"issues":[]}""").isEmpty(),
        )
    }

    @Test
    fun sizeReportAbiCheckDetectorCatchesBrokenScript() {
        val broken = """
            ${'$'}RequiredAbis = @("arm64-v8a", "x86_64")
            if (${'$'}RequiredAbis.Count -lt 3) { Write-Host "incomplete" }
        """.trimIndent()
        val hits = GuardRules.detectMissingAbiPresenceCheck(broken)
        assertTrue(
            "Canary failed: a script missing the universal APK check must be caught",
            hits.any { "universal" in it.reason },
        )
        assertTrue(
            "Canary failed: a script without a failure path must be caught",
            hits.any { "fail clearly" in it.reason },
        )
    }

    @Test
    fun sizeReportAbiCheckDetectorIgnoresCleanScript() {
        val clean = """
            ${'$'}RequiredAbis = @("arm64-v8a", "x86_64", "universal")
            ${'$'}missingAbis = @(${'$'}RequiredAbis | Where-Object { ${'$'}_ -eq "universal" })
            if (${'$'}missingAbis.Count -gt 0) {
                Write-Error "Missing release APK(s) for: ${'$'}(${'$'}missingAbis -join ', ')"
                exit 1
            }
        """.trimIndent()
        assertTrue(
            "Canary failed: a script with an ABI presence check must pass",
            GuardRules.detectMissingAbiPresenceCheck(clean).isEmpty(),
        )
    }

    // ---- release workflow detector ----------------------------------------

    private fun cleanWorkflowFixture(): Pair<String, String> {
        val ci = """
            name: Android CI
            jobs:
              build:
                steps:
                - name: Report APK size
                  run: pwsh ./tools/size-report.ps1
                - name: Lint (debug + release vital)
                  run: ./gradlew lintDebug lintVitalRelease --no-daemon
              dynamic:
                steps:
                - name: Run dynamic tests
                  uses: reactivecircus/android-emulator-runner@v2
                  with:
                    api-level: 36
                    script: sh tools/ci-dynamic.sh
        """.trimIndent()
        val release = """
            name: Release
            jobs:
              build:
                steps:
                - name: Build release APKs and AAB
                  run: |
                    ./gradlew assembleRelease
                    ./gradlew bundleRelease -Plvgl.bundleNoSplits=true
                - name: Release smoke (blocks publishing on failure)
                  uses: reactivecircus/android-emulator-runner@v2
                  with:
                    api-level: 36
                    script: SMOKE_ONLY=1 sh tools/ci-dynamic.sh
        """.trimIndent()
        return ci to release
    }

    @Test
    fun releaseWorkflowDetectorCatchesMissingApiLevel() {
        val (ci, release) = cleanWorkflowFixture()
        val brokenCi = ci.replace("api-level: 36", "api-level: 34")
        val brokenRelease = release.replace("api-level: 36", "api-level: 34")
        val hits = GuardRules.detectReleaseWorkflowGaps(brokenCi, brokenRelease)
        assertTrue("Canary failed: a workflow without api-level 36 must fail", hits.isNotEmpty())
        assertTrue(
            "Canary failed: both workflows must require api-level 36",
            listOf("ci.yml", "release.yml").all { file ->
                hits.any { it.file == file && "api-level: 36" in it.reason }
            },
        )
    }

    @Test
    fun releaseWorkflowDetectorCatchesContinueOnErrorOnSmoke() {
        val (ci, release) = cleanWorkflowFixture()
        val brokenRelease = release.replace(
            "- name: Release smoke (blocks publishing on failure)",
            "- name: Release smoke (blocks publishing on failure)\n" +
                "                  continue-on-error: true",
        )
        val hits = GuardRules.detectReleaseWorkflowGaps(ci, brokenRelease)
        assertTrue(
            "Canary failed: continue-on-error on the smoke step must fail",
            hits.any { "continue-on-error" in it.reason },
        )
    }

    @Test
    fun releaseWorkflowDetectorCatchesMissingGates() {
        val (ci, release) = cleanWorkflowFixture()
        val brokenCi = ci
            .replace("pwsh ./tools/size-report.ps1", "pwsh ./tools/report.ps1")
            .replace("lintVitalRelease", "lintDebugOnly")
        val brokenRelease = release
            .replace("-Plvgl.bundleNoSplits=true", "")
            .replace("SMOKE_ONLY=1 sh tools/ci-dynamic.sh", "sh tools/dynamic-test.ps1")
        val hits = GuardRules.detectReleaseWorkflowGaps(brokenCi, brokenRelease)
        listOf("size-report.ps1", "lintVitalRelease", "bundleNoSplits", "SMOKE_ONLY=1", "ci-dynamic.sh")
            .forEach { part ->
                assertTrue(
                    "Canary failed: detector must catch missing gate '$part'",
                    hits.any { part in it.reason },
                )
            }
    }

    @Test
    fun releaseWorkflowDetectorIgnoresCleanFixtureAndDiagnosticFallback() {
        val (ci, release) = cleanWorkflowFixture()
        assertTrue(
            "Canary failed: a clean workflow fixture must pass",
            GuardRules.detectReleaseWorkflowGaps(ci, release).isEmpty(),
        )
        val withFallback = release.replace(
            "- name: Release smoke (blocks publishing on failure)",
            "# Fallback diagnostic only, NOT target-level coverage.\n" +
                "    - name: Smoke fallback (diagnostic only)\n" +
                "      uses: reactivecircus/android-emulator-runner@v2\n" +
                "      with:\n" +
                "        api-level: 34\n" +
                "        script: sh tools/ci-dynamic.sh\n" +
                "    - name: Release smoke (blocks publishing on failure)",
        )
        assertTrue(
            "Canary fixture must actually contain a fallback API-34 step",
            "api-level: 34" in withFallback,
        )
        assertTrue(
            "Canary failed: a named API-34 fallback must not fail the guard",
            GuardRules.detectReleaseWorkflowGaps(ci, withFallback).isEmpty(),
        )
    }

    // ---- feature-wiring detectors ------------------------------------------

    private fun featureMap(vararg entries: Pair<String, String>): Map<String, String> = mapOf(*entries)

    @Test
    fun multiWindowWiringDetectorCatchesUnwiredActivity() {
        val clean = """
            class LvglActivity {
                protected val multiWindowManager: MultiWindowManager by lazy {
                    MultiWindowManager(this)
                }
                override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
                    multiWindowManager.onMultiWindowModeChanged(isInMultiWindowMode)
                }
                override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
                    multiWindowManager.onPictureInPictureModeChanged(isInPictureInPictureMode)
                }
            }
        """.trimIndent()
        assertTrue(
            "Canary failed: clean multi-window wiring must pass",
            GuardRules.detectMultiWindowWiring(
                featureMap(GuardRules.FEATURE_ACTIVITY to clean)
            ).isEmpty(),
        )

        val broken = """
            class LvglActivity {
                override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
                    super.onMultiWindowModeChanged(isInMultiWindowMode)
                }
            }
        """.trimIndent()
        val hits = GuardRules.detectMultiWindowWiring(
            featureMap(GuardRules.FEATURE_ACTIVITY to broken)
        )
        listOf(
            "override onPictureInPictureModeChanged",
            "construct a MultiWindowManager",
            "onMultiWindowModeChanged must forward",
            "onPictureInPictureModeChanged must forward",
        ).forEach { part ->
            assertTrue(
                "Canary failed: detector must catch '$part' in an unwired activity",
                hits.any { part in it.reason },
            )
        }
    }

    @Test
    fun gestureResultWiringDetectorCatchesDroppedDirectResult() {
        val clean = """
            class LvglInputProcessor {
                private val recognizer = GestureRecognizer()
                fun process(frame: GestureFrame): Gestures? {
                    val direct = recognizer.handle(frame)
                    return direct ?: recognizer.consumeLastGesture()
                }
            }
        """.trimIndent()
        assertTrue(
            "Canary failed: clean gesture wiring must pass",
            GuardRules.detectGestureResultWiring(
                featureMap(GuardRules.FEATURE_INPUT_PROCESSOR to clean)
            ).isEmpty(),
        )

        val dropped = """
            class LvglInputProcessor {
                private val recognizer = GestureRecognizer()
                fun process(frame: GestureFrame): Gestures? {
                    val direct = recognizer.handle(frame)
                    return recognizer.consumeLastGesture()
                }
            }
        """.trimIndent()
        val hits = GuardRules.detectGestureResultWiring(
            featureMap(GuardRules.FEATURE_INPUT_PROCESSOR to dropped)
        )
        assertTrue(
            "Canary failed: a discarded handle() result must be caught",
            hits.any { "direct handle()" in it.reason },
        )

        val ignored = """
            class LvglInputProcessor {
                private val recognizer = GestureRecognizer()
                fun process(frame: GestureFrame): Gestures? {
                    recognizer.handle(frame)
                    return recognizer.consumeLastGesture()
                }
            }
        """.trimIndent()
        assertTrue(
            "Canary failed: calling handle() without capturing its result must be caught",
            GuardRules.detectGestureResultWiring(
                featureMap(GuardRules.FEATURE_INPUT_PROCESSOR to ignored)
            ).isNotEmpty(),
        )
    }

    @Test
    fun performanceSinkCallerDetectorCatchesMissingCaller() {
        val clean = """
            class GameSampleActivity : LvglGameActivity() {
                private val tick = Runnable {
                    onGameLoop(16L)
                    LvglPerformanceMonitor.onFrameRendered()
                }
            }
        """.trimIndent()
        assertTrue(
            "Canary failed: a real onFrameRendered() caller must pass",
            GuardRules.detectPerformanceSinkCaller(
                featureMap(GuardRules.FEATURE_GAME_SAMPLE to clean)
            ).isEmpty(),
        )

        val broken = """
            class GameSampleActivity : LvglGameActivity() {
                private val tick = Runnable { onGameLoop(16L) }
            }
        """.trimIndent()
        val hits = GuardRules.detectPerformanceSinkCaller(
            featureMap(GuardRules.FEATURE_GAME_SAMPLE to broken)
        )
        assertTrue(
            "Canary failed: a perf sink with no caller must be caught",
            hits.any { "onFrameRendered()" in it.reason },
        )
    }

    @Test
    fun launcherReceiverWiringDetectorCatchesUnwiredCallbacks() {
        val clean = """
            abstract class LvglLauncherActivity {
                private val packageFilter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                }
                private val packageReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        when (intent.action) {
                            Intent.ACTION_PACKAGE_ADDED -> {
                                onPackageAdded("pkg"); onAllAppsChanged()
                            }
                            Intent.ACTION_PACKAGE_REMOVED -> {
                                onPackageRemoved("pkg"); onAllAppsChanged()
                            }
                            Intent.ACTION_PACKAGE_CHANGED -> {
                                onPackageChanged("pkg"); onAllAppsChanged()
                            }
                        }
                    }
                }
                override fun onStart() {
                    super.onStart()
                    registerReceiver(packageReceiver, packageFilter)
                }
                override fun onStop() {
                    unregisterReceiver(packageReceiver)
                    super.onStop()
                }
                abstract fun onPackageAdded(p: String)
                abstract fun onPackageRemoved(p: String)
                abstract fun onPackageChanged(p: String)
                abstract fun onAllAppsChanged()
            }
        """.trimIndent()
        assertTrue(
            "Canary failed: clean launcher receiver wiring must pass",
            GuardRules.detectLauncherReceiverWiring(
                featureMap(GuardRules.FEATURE_LAUNCHER to clean)
            ).isEmpty(),
        )

        val broken = """
            abstract class LvglLauncherActivity {
                override fun onStart() { super.onStart() }
                override fun onStop() { super.onStop() }
            }
        """.trimIndent()
        val hits = GuardRules.detectLauncherReceiverWiring(
            featureMap(GuardRules.FEATURE_LAUNCHER to broken)
        )
        listOf("onStart", "onStop", "ACTION_PACKAGE_ADDED", "onPackageAdded").forEach { part ->
            assertTrue(
                "Canary failed: detector must catch unwired launcher '$part'",
                hits.any { part in it.reason },
            )
        }

        val noUnregister = clean.replace(
            "unregisterReceiver(packageReceiver)",
            "/* no unregister */",
        )
        assertTrue(
            "Canary failed: a receiver registered but never unregistered must be caught",
            GuardRules.detectLauncherReceiverWiring(
                featureMap(GuardRules.FEATURE_LAUNCHER to noUnregister)
            ).any { "onStop must unregister" in it.reason },
        )
    }

    @Test
    fun accessibilityAnnouncementWiringDetectorCatchesLogOnlyStub() {
        val helper = """
            object LvglAccessibilityHelper {
                fun announce(context: Context, text: String) {
                    LvglScreenReader.announce(context, text)
                }
            }
        """.trimIndent()
        val reader = """
            object LvglScreenReader {
                fun announce(activity: Activity, text: String) {
                    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
                    event.text.add(text)
                    view.sendAccessibilityEventUnchecked(event)
                }
            }
        """.trimIndent()
        val clean = featureMap(
            GuardRules.FEATURE_ACCESSIBILITY_HELPER to helper,
            GuardRules.FEATURE_SCREEN_READER to reader,
        )
        assertTrue(
            "Canary failed: clean accessibility announcements must pass",
            GuardRules.detectAccessibilityAnnouncementWiring(clean).isEmpty(),
        )

        val logOnlyHelper = helper.replace(
            "LvglScreenReader.announce(context, text)",
            "Log.d(TAG, text)",
        )
        val helperHits = GuardRules.detectAccessibilityAnnouncementWiring(
            clean + (GuardRules.FEATURE_ACCESSIBILITY_HELPER to logOnlyHelper)
        )
        assertTrue(
            "Canary failed: a log-only announce() stub must be caught",
            helperHits.any { "delegate to LvglScreenReader" in it.reason },
        )

        val logOnlyReader = reader
            .replace("AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)", "Log.d(TAG, text)")
            .replace("view.sendAccessibilityEventUnchecked(event)", "Log.d(TAG, event.toString())")
        val readerHits = GuardRules.detectAccessibilityAnnouncementWiring(
            clean + (GuardRules.FEATURE_SCREEN_READER to logOnlyReader)
        )
        assertTrue(
            "Canary failed: a log-only screen reader must be caught",
            readerHits.any { "real AccessibilityEvent" in it.reason },
        )
    }

    @Test
    fun cameraPermissionWiringDetectorCatchesOpenBeforeGrantCheck() {
        val clean = """
            class CameraSampleActivity {
                private fun checkCameraPermission() {
                    if (!permissionGranted()) {
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                    }
                }
                private fun openCurrentCamera() {
                    if (!permissionGranted()) return
                    preview.open(id)
                }
            }
        """.trimIndent()
        assertTrue(
            "Canary failed: a guarded camera open must pass",
            GuardRules.detectCameraPermissionWiring(
                featureMap(GuardRules.FEATURE_CAMERA_SAMPLE to clean)
            ).isEmpty(),
        )

        // The grant check exists elsewhere in the file but not before open():
        // a whole-file contains() check would be a false green here.
        val broken = """
            class CameraSampleActivity {
                private fun checkCameraPermission() {
                    if (!permissionGranted()) {
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                    }
                }
                private fun openCurrentCamera() {
                    preview.open(id)
                }
            }
        """.trimIndent()
        val hits = GuardRules.detectCameraPermissionWiring(
            featureMap(GuardRules.FEATURE_CAMERA_SAMPLE to broken)
        )
        assertTrue(
            "Canary failed: preview.open() without a grant check in its function must be caught",
            hits.any { "grant-check before preview.open()" in it.reason },
        )
    }

    @Test
    fun uiEventBridgeDetectorCatchesNoOpBridge() {
        val ui = """
            void on_event() {
                ui_emit_event(g_currentAppScreen, id, type, value);
            }
        """.trimIndent()
        val port = """
            void ui_emit_event(int screen_id, const char* node_id, const char* type, int value) {
                if (!cb || !cb->event) return;
                env->CallVoidMethod(ref, cb->event, screen_id, jid);
            }
        """.trimIndent()
        val clean = featureMap(
            GuardRules.FEATURE_UI_CPP to ui,
            GuardRules.FEATURE_PORT_CPP to port,
        )
        assertTrue(
            "Canary failed: a real JNI event bridge must pass",
            GuardRules.detectUiEventBridgeWiring(clean).isEmpty(),
        )

        val noEmit = clean + (GuardRules.FEATURE_UI_CPP to "void on_event() { /* no bridge */ }")
        assertTrue(
            "Canary failed: a builder that never emits an event must be caught",
            GuardRules.detectUiEventBridgeWiring(noEmit).any { "must emit node events" in it.reason },
        )

        val noOpPort = clean + (
            GuardRules.FEATURE_PORT_CPP to
                "void ui_emit_event(int screen_id, const char* node_id) { return; }"
            )
        assertTrue(
            "Canary failed: a no-op ui_emit_event() definition must be caught",
            GuardRules.detectUiEventBridgeWiring(noOpPort).any { "not be a no-op" in it.reason },
        )
    }

    @Test
    fun featureWiringDetectorsFailClosedOnMissingSources() {
        val helperOnly = featureMap(
            GuardRules.FEATURE_ACCESSIBILITY_HELPER to """
                object LvglAccessibilityHelper {
                    fun announce(context: Context, text: String) {
                        LvglScreenReader.announce(context, text)
                    }
                }
            """.trimIndent(),
        )
        val uiOnly = featureMap(
            GuardRules.FEATURE_UI_CPP to "void f() { ui_emit_event(id, type, value); }",
        )
        listOf(
            GuardRules.FEATURE_ACTIVITY to featureMap(),
            GuardRules.FEATURE_INPUT_PROCESSOR to featureMap(),
            GuardRules.FEATURE_GAME_SAMPLE to featureMap(),
            GuardRules.FEATURE_LAUNCHER to featureMap(),
            GuardRules.FEATURE_ACCESSIBILITY_HELPER to featureMap(),
            GuardRules.FEATURE_SCREEN_READER to helperOnly,
            GuardRules.FEATURE_CAMERA_SAMPLE to featureMap(),
            GuardRules.FEATURE_UI_CPP to featureMap(),
            GuardRules.FEATURE_PORT_CPP to uiOnly,
        ).forEach { (key, companions) ->
            val hits = GuardRules.detectUnwiredFeatures(companions + (key to "   "))
            assertTrue(
                "Canary failed: missing source ($key) must produce a fail-closed violation",
                hits.any { it.file == key && "missing or blank" in it.reason },
            )
        }
        assertTrue(
            "Canary failed: an empty source map must make every detector fail closed",
            GuardRules.detectUnwiredFeatures(emptyMap()).size >= 7,
        )
    }

    @Test
    fun aggregateFeatureWiringDetectorCallsEveryDetector() {
        val clean = featureMap(
            GuardRules.FEATURE_ACTIVITY to """
                class LvglActivity {
                    val multiWindowManager = MultiWindowManager(this)
                    override fun onMultiWindowModeChanged(b: Boolean) {
                        multiWindowManager.onMultiWindowModeChanged(b)
                    }
                    override fun onPictureInPictureModeChanged(b: Boolean) {
                        multiWindowManager.onPictureInPictureModeChanged(b)
                    }
                }
            """.trimIndent(),
            GuardRules.FEATURE_INPUT_PROCESSOR to """
                class P {
                    val recognizer = GestureRecognizer()
                    fun f(frame: GestureFrame): Gestures? {
                        val direct = recognizer.handle(frame)
                        return direct ?: recognizer.consumeLastGesture()
                    }
                }
            """.trimIndent(),
            GuardRules.FEATURE_GAME_SAMPLE to
                "class GameSampleActivity { fun tick() { LvglPerformanceMonitor.onFrameRendered() } }",
            GuardRules.FEATURE_LAUNCHER to """
                abstract class LvglLauncherActivity {
                    override fun onStart() { registerReceiver(packageReceiver, packageFilter) }
                    override fun onStop() { unregisterReceiver(packageReceiver) }
                    fun f() {
                        Intent.ACTION_PACKAGE_ADDED
                        Intent.ACTION_PACKAGE_REMOVED
                        Intent.ACTION_PACKAGE_CHANGED
                        onPackageAdded("p"); onPackageRemoved("p"); onPackageChanged("p"); onAllAppsChanged()
                    }
                }
            """.trimIndent(),
            GuardRules.FEATURE_ACCESSIBILITY_HELPER to """
                object LvglAccessibilityHelper {
                    fun announce(context: Context, text: String) {
                        LvglScreenReader.announce(context, text)
                    }
                }
            """.trimIndent(),
            GuardRules.FEATURE_SCREEN_READER to """
                object LvglScreenReader {
                    fun announce(activity: Activity, text: String) {
                        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
                        view.sendAccessibilityEventUnchecked(event)
                    }
                }
            """.trimIndent(),
            GuardRules.FEATURE_CAMERA_SAMPLE to """
                class CameraSampleActivity {
                    fun f() { if (!permissionGranted()) requestPermissions(a, 1) }
                    private fun openCurrentCamera() {
                        if (!permissionGranted()) return
                        preview.open(id)
                    }
                }
            """.trimIndent(),
            GuardRules.FEATURE_UI_CPP to "void f() { ui_emit_event(id, type, value); }",
            GuardRules.FEATURE_PORT_CPP to """
                void ui_emit_event(int screen_id) {
                    if (!cb->event) return;
                    env->CallVoidMethod(ref, cb->event, screen_id);
                }
            """.trimIndent(),
        )
        assertTrue(
            "Canary failed: clean aggregate fixture must pass:\n" +
                GuardRules.detectUnwiredFeatures(clean).joinToString("\n") { it.reason },
            GuardRules.detectUnwiredFeatures(clean).isEmpty(),
        )
    }
}
