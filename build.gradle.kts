import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.provider.Provider
import java.util.Locale
import java.util.regex.Pattern

plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.14.0" apply false
    id("org.jetbrains.dokka") version "1.9.20" apply false
}

// Published "maximum API level" from each AGP's compatibility table. AGP only
// emits a soft warning for a newer compileSdk; this check turns that mismatch
// into a configuration-time build failure. Unknown versions fail closed.
private val agpMaxSupportedSdk = mapOf(
    "8.2" to 34,
    "8.3" to 34,
    "8.5" to 34,
    "8.6" to 35,
    "8.7" to 35,
    "8.8" to 35,
    "8.9" to 35,
    "8.10" to 36,
    "8.11" to 36,
    "8.12" to 36,
    "8.13" to 36
)

private val agpJarVersion = Pattern.compile("gradle-(\\d+\\.\\d+(?:\\.\\d+)?(?:-[A-Za-z0-9.]+)?)\\.jar")

private fun agpVersionOf(project: org.gradle.api.Project): String? {
    for (id in listOf("com.android.application", "com.android.library")) {
        val plugin = project.pluginManager.findPlugin(id) ?: continue
        val location = plugin.javaClass.protectionDomain?.codeSource?.location?.path ?: continue
        val match = agpJarVersion.matcher(location)
        if (match.find()) return match.group(1)
    }
    return try {
        buildscript.configurations.findByName("classpath")
            ?.incoming?.artifacts?.resolvedArtifacts?.get()
            ?.firstOrNull {
                val id = it.id.componentIdentifier
                id is ModuleComponentIdentifier &&
                    id.group == "com.android.tools.build" && id.module == "gradle"
            }?.let { (it.id.componentIdentifier as ModuleComponentIdentifier).version }
    } catch (ignored: Exception) {
        null
    }
}

private fun getterValue(owner: Any, name: String): Any? {
    val getterName = "get" + name.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
    }
    val getter = owner.javaClass.methods.firstOrNull {
        it.name == getterName && it.parameterCount == 0
    } ?: return null
    return try {
        getter.invoke(owner)
    } catch (ignored: ReflectiveOperationException) {
        null
    }
}

private fun sdkLevel(value: Any?): Int? = when (value) {
    is Provider<*> -> sdkLevel(value.getOrNull())
    is Number -> value.toInt()
    is String -> Regex("(?:android-)?(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()
    else -> null
}

gradle.projectsEvaluated {
    val agpVersion = rootProject.allprojects.firstNotNullOfOrNull { agpVersionOf(it) }
        ?: throw GradleException(
            "Cannot determine the Android Gradle plugin version. " +
                "The compileSdk/targetSdk compatibility guard cannot run; refusing to build."
        )
    val series = Regex("(\\d+)\\.(\\d+)").find(agpVersion)?.let {
        "${it.groupValues[1]}.${it.groupValues[2]}"
    }
    val maxSupported = series?.let { agpMaxSupportedSdk[it] } ?: throw GradleException(
        "No AGP->max-SDK entry for AGP $agpVersion ($series). " +
            "Add the published maximum API level to agpMaxSupportedSdk in build.gradle.kts."
    )

    logger.lifecycle("AGP/SDK guard: AGP $agpVersion, max supported API $maxSupported")
    rootProject.allprojects.forEach { project ->
        val android = project.extensions.findByName("android") ?: return@forEach
        val defaultConfig = getterValue(android, "defaultConfig")
        val compileSdk = sdkLevel(getterValue(android, "compileSdk"))
        val targetSdk = if (defaultConfig != null) sdkLevel(getterValue(defaultConfig, "targetSdk")) else null
        logger.lifecycle("  ${project.path}: compileSdk=$compileSdk, targetSdk=$targetSdk")
        val configured = listOf("compileSdk" to compileSdk, "targetSdk" to targetSdk)
        configured.forEach { (property, level) ->
            if (level != null && level > maxSupported) {
                throw GradleException(
                    "$project: $property = $level exceeds the maximum API level $maxSupported " +
                        "supported by AGP $agpVersion. Upgrade AGP (and Gradle) to a version " +
                        "that officially supports API $level, or lower $property."
                )
            }
        }
    }
}
