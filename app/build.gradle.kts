plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release keystore: provided via -Plvgl.keystore=... / -Plvgl.key.alias=...
// or the LVGL_KEYSTORE / LVGL_KEYSTORE_PASSWORD / LVGL_KEY_ALIAS /
// LVGL_KEY_PASSWORD environment variables (used by the release workflow).
val lvglKeystore = project.findProperty("lvgl.keystore") as? String
    ?: System.getenv("LVGL_KEYSTORE")
val lvglKeystorePassword = project.findProperty("lvgl.keystore.password") as? String
    ?: System.getenv("LVGL_KEYSTORE_PASSWORD")
val lvglKeyAlias = project.findProperty("lvgl.key.alias") as? String
    ?: System.getenv("LVGL_KEY_ALIAS")
val lvglKeyPassword = project.findProperty("lvgl.key.password") as? String
    ?: System.getenv("LVGL_KEY_PASSWORD")
val keystoreFile = if (lvglKeystore.isNullOrBlank()) null else file(lvglKeystore)
val hasReleaseKeystore = keystoreFile != null && keystoreFile.exists()

android {
    namespace = "com.lvgl.sample"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.lvgl.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = (project.findProperty("lvgl.versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = project.findProperty("lvgl.versionName") as? String ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // The sample ships English only; drop the ~1 MB of appcompat locales.
        resourceConfigurations += "en"
    }

    // Per-ABI APKs: each download carries a single native library, which is the
    // single biggest win for APK size. The universal APK is still produced for
    // local installs and testing.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    // Release signing. The keystore is supplied by CI secrets (or a local
    // lvgl.keystore property); without it the release build falls back to the
    // debug key so a plain `assembleRelease` still works for local testing.
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = lvglKeystorePassword
                keyAlias = lvglKeyAlias
                keyPassword = lvglKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }

    packaging {
        jniLibs {
            // Compress the native libraries inside the APK: a smaller download
            // at the cost of a slightly larger installed footprint.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":lvgl-android"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
