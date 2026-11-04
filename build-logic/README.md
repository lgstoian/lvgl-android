# build-logic

Included Gradle build that provides the `com.lvgl.android.app` plugin. The
plugin scaffolds a new LVGL app module: build file, manifest, activity, resources
and a README.

## Wire it up

Root `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("build-logic")
}
```

Then apply the plugin where the module should be scaffolded (usually the root
project's `build.gradle.kts`):

```kotlin
plugins {
    id("com.lvgl.android.app")
}

lvglApp {
    rootDir = layout.projectDirectory
    moduleName = "myapp"
    packageName = "com.example.myapp"
    appName = "My LVGL App"
}
```

## Generate the module

```bash
./gradlew createLvglApp
```

The task writes the module below `<rootDir>/<moduleName>`:

| File | Purpose |
| ---- | ------- |
| `build.gradle.kts` | `com.android.application` + `kotlin.android`, SDK 36 / minSdk 24, Java 17 |
| `src/main/AndroidManifest.xml` | Exported launcher `MainActivity`, DayNight `NoActionBar` theme |
| `src/main/java/<package path>/MainActivity.kt` | `LvglActivity` subclass that selects the built-in LVGL screen |
| `src/main/res/values/strings.xml` | `app_name` |
| `src/main/res/values/themes.xml` | `Theme.LvglApp` |
| `README.md` | Module notes |

Defaults: `rootDir` is the project directory, `moduleName = "app"`,
`packageName = "com.example.lvglapp"`, `appName = "LVGL App"`.

`androidx.appcompat:appcompat` is added next to the framework dependency because
`LvglActivity` extends `AppCompatActivity`, so appcompat has to be on the
generated module's compile classpath.

## Tests

Run the generator tests standalone:

```bash
./gradlew -p build-logic test
```

`LvglAppGenerator` is a pure, Gradle-free function
(`LvglAppGenerator.files(Params)`), so the templates are unit-tested directly
and the Gradle task only writes the returned map to disk.
