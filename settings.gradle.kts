pluginManagement {
    // Included build that provides the com.lvgl.android.app scaffolding plugin.
    includeBuild("build-logic")
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
    }
}

rootProject.name = "LvglAndroid"
include(":app")
include(":lvgl-android")
