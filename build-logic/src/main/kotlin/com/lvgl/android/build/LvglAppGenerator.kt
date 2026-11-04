// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.

package com.lvgl.android.build

import java.io.File

/**
 * Pure, Gradle-free generator for an LVGL app module.
 *
 * The Gradle task only writes the returned map to disk, so every template can
 * be unit-tested without a Gradle runtime.
 */
object LvglAppGenerator {

    const val DEFAULT_MODULE_NAME = "app"
    const val DEFAULT_PACKAGE_NAME = "com.example.lvglapp"
    const val DEFAULT_APP_NAME = "LVGL App"

    const val LIBRARY_DEPENDENCY = "com.lvgl.android:lvgl-android:1.0.0"

    /** LvglActivity extends AppCompatActivity, so appcompat must be on the compile classpath. */
    const val APPCOMPAT_DEPENDENCY = "androidx.appcompat:appcompat:1.6.1"

    const val ANDROID_GRADLE_PLUGIN_VERSION = "8.2.0"
    const val KOTLIN_VERSION = "1.9.22"

    const val COMPILE_SDK = 36
    const val MIN_SDK = 24
    const val TARGET_SDK = 36
    const val VERSION_CODE = 1
    const val VERSION_NAME = "1.0.0"
    const val JAVA_VERSION = 17

    const val MAIN_ACTIVITY = "MainActivity"
    const val HOME_SCREEN_ID = 0
    const val THEME_NAME = "Theme.LvglApp"

    data class Params(
        val rootDir: File = File("."),
        val moduleName: String = DEFAULT_MODULE_NAME,
        val packageName: String = DEFAULT_PACKAGE_NAME,
        val appName: String = DEFAULT_APP_NAME,
    )

    /** Directory the module is generated into. */
    fun moduleDir(p: Params): File = File(p.rootDir, p.moduleName)

    /**
     * Every generated file keyed by its path relative to [moduleDir]. Insertion
     * order is stable so the generated layout is predictable.
     */
    fun files(p: Params): Map<String, String> = linkedMapOf(
        "build.gradle.kts" to buildGradleFile(p),
        "src/main/AndroidManifest.xml" to manifest(p),
        mainActivityPath(p) to mainActivity(p),
        "src/main/res/values/strings.xml" to strings(p),
        "src/main/res/values/themes.xml" to themes(),
        "README.md" to readme(p),
    )

    /** `com.example.lvglapp` -> `com/example/lvglapp`; blank segments are dropped. */
    fun packagePath(packageName: String): String =
        packageName.split('.')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("/")

    fun mainActivityPath(p: Params): String =
        "src/main/java/${packagePath(p.packageName)}/$MAIN_ACTIVITY.kt"

    private fun buildGradleFile(p: Params): String = """
        plugins {
            id("com.android.application") version "$ANDROID_GRADLE_PLUGIN_VERSION"
            id("org.jetbrains.kotlin.android") version "$KOTLIN_VERSION"
        }

        android {
            namespace = "${p.packageName}"
            compileSdk = $COMPILE_SDK

            defaultConfig {
                applicationId = "${p.packageName}"
                minSdk = $MIN_SDK
                targetSdk = $TARGET_SDK
                versionCode = $VERSION_CODE
                versionName = "$VERSION_NAME"
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_$JAVA_VERSION
                targetCompatibility = JavaVersion.VERSION_$JAVA_VERSION
            }

            kotlinOptions {
                jvmTarget = "$JAVA_VERSION"
            }
        }

        dependencies {
            implementation("$LIBRARY_DEPENDENCY")
            implementation("$APPCOMPAT_DEPENDENCY")
        }
    """.trimIndent() + "\n"

    private fun manifest(p: Params): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application
                android:allowBackup="true"
                android:label="@string/app_name"
                android:supportsRtl="true"
                android:theme="@style/$THEME_NAME">
                <activity
                    android:name=".${MAIN_ACTIVITY}"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent() + "\n"

    private fun mainActivity(p: Params): String = """
        // Copyright (c) 2026 LVGL Android Framework Contributors
        // SPDX-License-Identifier: MIT
        //
        // This file is part of the LVGL Android Framework.
        // See LICENSE in the project root for full license information.
        package ${p.packageName}

        import com.lvgl.android.activity.LvglActivity
        import com.lvgl.android.engine.LvglEngine

        /**
         * Entry point of the generated LVGL app.
         *
         *  - LvglActivity subclasses must NOT replace the LVGL surface with native
         *    content (no setContentView).
         *  - Switch LVGL content via setLvglScreen(), not by replacing the surface.
         */
        class $MAIN_ACTIVITY : LvglActivity() {

            override fun onCreateLvgl() {
                LvglEngine.instance.setStatusText(getString(R.string.app_name))
                setLvglScreen(HOME_SCREEN_ID)
            }

            companion object {
                private const val HOME_SCREEN_ID = $HOME_SCREEN_ID
            }
        }
    """.trimIndent() + "\n"

    private fun strings(p: Params): String = """
        <resources>
            <string name="app_name">${xmlEscape(p.appName)}</string>
        </resources>
    """.trimIndent() + "\n"

    private fun themes(): String = """
        <resources>
            <style name="$THEME_NAME" parent="Theme.AppCompat.DayNight.NoActionBar" />
        </resources>
    """.trimIndent() + "\n"

    private fun readme(p: Params): String = """
        # ${p.appName}

        LVGL app module generated by the `com.lvgl.android.app` Gradle plugin.

        - Module: `${p.moduleName}`
        - Package: `${p.packageName}`
        - Activity: `${p.packageName}.$MAIN_ACTIVITY`

        ## Build

        ```bash
        ./gradlew :${p.moduleName}:assembleDebug
        ```

        ## Notes

        - `MainActivity` extends `LvglActivity`; it must not replace the LVGL surface.
        - The LVGL screen is selected in `onCreateLvgl()` with `setLvglScreen(...)`.
        - User-visible strings live in `src/main/res/values/strings.xml`.
    """.trimIndent() + "\n"

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
