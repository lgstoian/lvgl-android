// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.

package com.lvgl.android.build

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

/** DSL surface of the `com.lvgl.android.app` plugin. */
open class LvglAppExtension(objects: ObjectFactory) {

    /** Directory the module is created in. Defaults to the project directory. */
    val rootDir: DirectoryProperty = objects.directoryProperty()

    val moduleName: Property<String> = objects.property(String::class.java)
    val packageName: Property<String> = objects.property(String::class.java)
    val appName: Property<String> = objects.property(String::class.java)

    init {
        moduleName.convention(LvglAppGenerator.DEFAULT_MODULE_NAME)
        packageName.convention(LvglAppGenerator.DEFAULT_PACKAGE_NAME)
        appName.convention(LvglAppGenerator.DEFAULT_APP_NAME)
    }
}

/** Writes the files produced by [LvglAppGenerator] below `rootDir/moduleName`. */
abstract class CreateLvglAppTask : DefaultTask() {

    @get:Internal
    abstract val rootDir: DirectoryProperty

    @get:Input
    abstract val moduleName: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val appName: Property<String>

    @TaskAction
    fun generate() {
        val params = LvglAppGenerator.Params(
            rootDir = rootDir.get().asFile,
            moduleName = moduleName.get(),
            packageName = packageName.get(),
            appName = appName.get(),
        )
        require(params.moduleName.isNotBlank()) { "lvglApp.moduleName must not be blank" }
        require(params.packageName.isNotBlank()) { "lvglApp.packageName must not be blank" }

        val moduleDir = LvglAppGenerator.moduleDir(params)
        LvglAppGenerator.files(params).forEach { (path, content) ->
            val target = File(moduleDir, path)
            target.parentFile.mkdirs()
            target.writeText(content)
        }
        logger.lifecycle("Created LVGL app module '{}' in {}", params.moduleName, moduleDir)
    }
}

/** Adds the `lvglApp` extension and the `createLvglApp` task to a project. */
class LvglAppPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "lvglApp",
            LvglAppExtension::class.java,
            project.objects,
        )
        extension.rootDir.convention(project.layout.projectDirectory)

        project.tasks.register(
            "createLvglApp",
            CreateLvglAppTask::class.java,
            object : org.gradle.api.Action<CreateLvglAppTask> {
                override fun execute(task: CreateLvglAppTask) {
                    task.rootDir.set(extension.rootDir)
                    task.moduleName.set(extension.moduleName)
                    task.packageName.set(extension.packageName)
                    task.appName.set(extension.appName)
                }
            },
        )
    }
}
