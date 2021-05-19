package edu.illinois.cs.cs125.questioner.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

@Suppress("unused")
abstract class CleanQuestions : DefaultTask() {
    init {
        group = "Build"
        description = "Clean question validation results."
    }

    @InputFiles
    val inputFiles: FileCollection =
        project.convention.getPlugin(JavaPluginConvention::class.java).sourceSets.getByName("main").allSource
            .filter { it.name == ".validation.json" || it.name == "report.html" }

    @TaskAction
    fun clean() {
        inputFiles.forEach {
            it.delete()
        }
    }
}
