// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.lvglapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.lvglapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The composite build substitutes this coordinate with the local
    // :lvgl-android project. After the library is published to Maven, the
    // same coordinate resolves from the repository without the includeBuild.
    implementation("com.lvgl.android:lvgl-android:1.0.0")

    // LvglActivity extends AppCompatActivity and the manifest theme is an
    // AppCompat DayNight theme, so appcompat must be on the app classpath.
    implementation("androidx.appcompat:appcompat:1.6.1")
}
