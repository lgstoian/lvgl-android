// `kotlin-dsl` compiles this build logic with the Kotlin version embedded in
// Gradle 8.5 (1.9.20); generated modules pin Kotlin 1.9.22 to match the root
// build.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("lvglApp") {
            id = "com.lvgl.android.app"
            implementationClass = "com.lvgl.android.build.LvglAppPlugin"
        }
    }
}
