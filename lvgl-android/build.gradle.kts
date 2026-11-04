plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
    id("org.jetbrains.dokka")
}

group = "com.lvgl.android"
version = project.findProperty("lvgl.versionName") as? String ?: "1.0.0"

android {
    namespace = "com.lvgl.android"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Shipped to every consumer: keeps the JNI entry points and the public
        // API classes that apps subclass or that native code calls by name.
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments(
                    // Static STL: libc++_shared.so (~1.3 MB per ABI) is linked
                    // into liblvgl_android.so instead of shipped separately.
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-24"
                )
                abiFilters("arm64-v8a", "x86_64")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        resourceConfigurations += "en"
    }

    buildTypes {
        release {
            /* Libraries are not minified in place: the consumer app's R8 pass
             * tree-shakes the whole graph. Enabling minification here makes
             * lintVitalRelease unable to resolve activities hosted in the
             * library (false-positive "must extend android.app.Activity"). */
            isMinifyEnabled = false
            ndk {
                debugSymbolLevel = "none"
            }
        }
        debug {
            ndk {
                debugSymbolLevel = "full"
            }
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Public API documentation. Dokka's Android integration exposes the library
// sources through the "main" source set; the per-variant "release" set only
// holds variant-specific sources. Both get the same reporting settings, so
// undocumented declarations are reported without failing the build.
tasks.dokkaHtml.configure {
    dokkaSourceSets {
        listOf("main", "release").forEach { sourceSetName ->
            named(sourceSetName) {
                reportUndocumented.set(true)
                failOnWarning.set(false)
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.lvgl.android"
                artifactId = "lvgl-android"
                version = project.findProperty("lvgl.versionName") as? String ?: "1.0.0"

                pom {
                    name.set("LVGL Android")
                    description.set(
                        "A Kotlin-first Android framework for building apps with LVGL 9.6.0, " +
                            "with Material 3 theming and a native UI kit."
                    )
                    url.set("https://github.com/lvgl-android/lvgl-android")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("lvgl-android")
                            name.set("LVGL Android Framework Contributors")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/lvgl-android/lvgl-android.git")
                        developerConnection.set("scm:git:ssh://github.com/lvgl-android/lvgl-android.git")
                        url.set("https://github.com/lvgl-android/lvgl-android")
                    }
                }
            }
        }
    }
}
