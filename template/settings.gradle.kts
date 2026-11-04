// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "LvglApp"
include(":app")

// Composite build: substitute the published library coordinate with the local
// :lvgl-android project so this app builds before the library is published.
// `lvglAndroidPath` must point at the Gradle build root that contains the
// :lvgl-android module. In this repository that is the repository root (".."),
// not the lvgl-android module directory (which has no settings.gradle.kts).
// Once the library is on Maven, the same coordinate resolves from the
// repository and this includeBuild block can be removed.
val lvglAndroidPath = providers.gradleProperty("lvglAndroidPath").orNull ?: ".."

includeBuild(lvglAndroidPath) {
    // The framework checkout directory may contain spaces; give the included
    // build a stable path name for task addressing.
    name = "lvgl-android"
    dependencySubstitution {
        substitute(module("com.lvgl.android:lvgl-android"))
            .using(project(":lvgl-android"))
    }
}
