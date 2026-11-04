// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.

package com.lvgl.android.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LvglAppGeneratorTest {

    private val defaults = LvglAppGenerator.Params()

    private val generated = LvglAppGenerator.files(defaults)

    @Test
    fun defaultParamsAreSensible() {
        assertEquals("app", defaults.moduleName)
        assertEquals("com.example.lvglapp", defaults.packageName)
        assertEquals("LVGL App", defaults.appName)
    }

    @Test
    fun customModuleAndPackageNamesAreUsed() {
        val params = LvglAppGenerator.Params(
            rootDir = File("build"),
            moduleName = "game",
            packageName = "org.acme.game",
            appName = "Acme Game",
        )

        val files = LvglAppGenerator.files(params)

        assertEquals(File("build", "game"), LvglAppGenerator.moduleDir(params))
        assertTrue(files.containsKey("src/main/java/org/acme/game/MainActivity.kt"))
        assertTrue(files.getValue("build.gradle.kts").contains("namespace = \"org.acme.game\""))
        assertTrue(files.getValue("src/main/res/values/strings.xml").contains("Acme Game"))
        assertTrue(files.getValue("README.md").contains("Acme Game"))
    }

    @Test
    fun generatedFileSetIsExact() {
        assertEquals(
            setOf(
                "build.gradle.kts",
                "src/main/AndroidManifest.xml",
                "src/main/java/com/example/lvglapp/MainActivity.kt",
                "src/main/res/values/strings.xml",
                "src/main/res/values/themes.xml",
                "README.md",
            ),
            generated.keys,
        )
    }

    @Test
    fun packagePathHasNoEmptyOrDuplicateSegments() {
        assertEquals("com/example/lvglapp", LvglAppGenerator.packagePath("com.example.lvglapp"))
        assertEquals("org/acme/game", LvglAppGenerator.packagePath("org.acme.game"))
        assertEquals("com/example", LvglAppGenerator.packagePath("com..example"))
        assertEquals("com/example", LvglAppGenerator.packagePath(".com.example."))
        assertEquals("com/example", LvglAppGenerator.packagePath(" com . example "))

        listOf("com..example", ".com.example.", "com...example").forEach { raw ->
            val path = LvglAppGenerator.packagePath(raw)
            assertFalse("empty segment in '$path'", path.contains("//"))
            assertFalse("blank segment in '$path'", path.split('/').any { it.isBlank() })
        }
    }

    @Test
    fun manifestDeclaresExportedLauncherActivity() {
        val manifest = generated.getValue("src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("android:name=\".MainActivity\""))
        assertTrue(manifest.contains("android:exported=\"true\""))
        assertTrue(manifest.contains("android.intent.action.MAIN"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
        assertTrue(manifest.contains("android:theme=\"@style/Theme.LvglApp\""))
        assertTrue(manifest.contains("@string/app_name"))
    }

    @Test
    fun buildFileDeclaresLibraryDependency() {
        val build = generated.getValue("build.gradle.kts")

        assertTrue(build.contains("implementation(\"com.lvgl.android:lvgl-android:1.0.0\")"))
        assertTrue(build.contains("id(\"com.android.application\") version \"8.2.0\""))
        assertTrue(build.contains("id(\"org.jetbrains.kotlin.android\") version \"1.9.22\""))
        assertTrue(build.contains("namespace = \"com.example.lvglapp\""))
        assertTrue(build.contains("compileSdk = 36"))
        assertTrue(build.contains("minSdk = 24"))
        assertTrue(build.contains("targetSdk = 36"))
        assertTrue(build.contains("versionCode = 1"))
        assertTrue(build.contains("versionName = \"1.0.0\""))
        assertTrue(build.contains("JavaVersion.VERSION_17"))
    }

    @Test
    fun mainActivityExtendsLvglActivityAndBuildsUi() {
        val activity = generated.getValue("src/main/java/com/example/lvglapp/MainActivity.kt")

        assertTrue(activity.startsWith("// Copyright (c) 2026 LVGL Android Framework Contributors"))
        assertTrue(activity.contains("package com.example.lvglapp"))
        assertTrue(activity.contains("class MainActivity : LvglActivity()"))
        assertTrue(activity.contains("override fun onCreateLvgl()"))
        assertTrue(activity.contains("setLvglScreen("))
        assertFalse(activity.contains("setContentView("))
    }

    @Test
    fun mainActivityPathFollowsPackageName() {
        val params = LvglAppGenerator.Params(packageName = "org.acme.game")

        assertEquals(
            "src/main/java/org/acme/game/MainActivity.kt",
            LvglAppGenerator.mainActivityPath(params),
        )
        assertTrue(
            LvglAppGenerator.files(params)
                .getValue("src/main/java/org/acme/game/MainActivity.kt")
                .contains("package org.acme.game"),
        )
    }

    @Test
    fun appNameIsXmlEscaped() {
        val params = LvglAppGenerator.Params(appName = "Tom & Jerry <LVGL>")

        assertTrue(
            LvglAppGenerator.files(params)
                .getValue("src/main/res/values/strings.xml")
                .contains("Tom &amp; Jerry &lt;LVGL&gt;"),
        )
    }
}
