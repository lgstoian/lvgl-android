# LVGL app template

A minimal, standalone Gradle project for a new app built on the
[LVGL Android Framework](../README.md). It uses a Gradle **composite build**
so it compiles the local `lvgl-android` library before the library is published
to Maven.

## 1. Copy the template

Copy this directory to wherever you want your app to live. It can live inside
the framework repository (as a sibling of `lvgl-android`) or anywhere else.

## 2. Copy the Gradle wrapper

The wrapper is not duplicated here. If the template is still inside the
framework repository, copy it from the repository root:

```powershell
copy ..\gradlew .
copy ..\gradlew.bat .
xcopy /E /I ..\gradle\wrapper gradle\wrapper
```

If you copied the template elsewhere, copy the same files from any Gradle 8.11.1
project, or run `gradle wrapper --gradle-version 8.11.1` in this directory once
to generate them. The needed files are `gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar` and
`gradle/wrapper/gradle-wrapper.properties`.

## 3. Point at the library

`gradle.properties` contains:

```properties
lvglAndroidPath=..
```

`lvglAndroidPath` must point at the **Gradle build root** that contains the
`:lvgl-android` module — in this repository that is the repository root, not
the `lvgl-android` module directory (the module directory has no
`settings.gradle.kts`). If you copied the template elsewhere, set it to the
absolute or relative path of the framework repository root, for example:

```properties
lvglAndroidPath=D:/Code/android lvgl
```

## 4. Rename the package

The template uses `com.example.lvglapp`. To rename it:

1. Rename the directory
   `app/src/main/java/com/example/lvglapp` to your package path.
2. Change `package` in `MainActivity.kt`.
3. Change `namespace` and `applicationId` in `app/build.gradle.kts`.
4. Update `android:name` in `app/src/main/AndroidManifest.xml`
   (`.MainActivity` stays relative to the namespace).

## 5. Build and run

```powershell
# Set the toolchain (see BUILDING.md in the framework repository)
$env:JAVA_HOME = "C:\Users\lgstoian\jdk-17\jdk-17.0.12"
$env:ANDROID_HOME = "D:\ASDK"

.\gradlew.bat assembleDebug --no-daemon
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.lvglapp/.MainActivity
```

Prerequisites: JDK 17, Android SDK API 24+ (compiled against API 36), Android
NDK r26b and CMake 3.22.1 (needed to build the library's native code).

## What's inside

| File | Purpose |
|------|---------|
| `settings.gradle.kts` | Repositories plus the `includeBuild` composite build |
| `gradle.properties` | `lvglAndroidPath`, AndroidX and JVM settings |
| `build.gradle.kts` | AGP 8.10.1 / Kotlin 1.9.22 plugin versions, `apply false` |
| `app/build.gradle.kts` | App module, API 24–36, Java 17, library + appcompat |
| `app/src/main/java/.../MainActivity.kt` | `LvglActivity` subclass showing an LVGL screen |
| `app/src/main/res/values/themes.xml` | AppCompat DayNight NoActionBar theme |

## After the library is published

The dependency `com.lvgl.android:lvgl-android:1.2.0` is the same coordinate the
composite build substitutes locally. Once the library is published to a Maven
repository, add that repository to `dependencyResolutionManagement` and remove
the `includeBuild` block — the app keeps building unchanged.

## Mixing LVGL with a normal Android app

The template's `MainActivity` is an `LvglActivity` (a whole LVGL screen). If
your app is mostly Compose/XML, you do not have to change that: add an
`LvglSurfaceView` to a plain Activity/Fragment layout or a Compose
`AndroidView` for just one feature, and keep your existing navigation. Native
Android views can also be composited above the LVGL surface — the sample's
camera preview is the working proof. See [docs/interop.md](../docs/interop.md).

Adopting or exiting is incremental; see [docs/migration.md](../docs/migration.md).
TalkBack coverage is limited to `LvglUi` app screens — see
[docs/accessibility.md](../docs/accessibility.md) before committing a whole
screen to LVGL.

## Next steps

- [Quickstart](../docs/quickstart.md) — your first screen, input, theme, build.
- [Public API reference](../docs/public-api.md) — every class in `com.lvgl.android`.
- [Support policy](../SUPPORT.md) and [security reporting](../SECURITY.md).
