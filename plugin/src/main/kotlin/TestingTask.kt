package edu.illinois.cs.cs125.questioner.plugin

import edu.illinois.cs.cs125.questioner.lib.loadFromPath
import org.gradle.api.DefaultTask
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.work.Incremental
import java.io.File

abstract class TestingTask : DefaultTask() {
    init {
        outputs.upToDateWhen { false }
    }

    @Incremental
    @InputFiles
    val files = project.convention.getPlugin(JavaPluginConvention::class.java)
        .sourceSets.getByName("main").allSource.files.filterNotNull()

    @TaskAction
    fun execute() {
        println(files.size)
        println(project.javaSourceDir())
        loadFromPath(File(project.buildDir, "questioner/questions.json"), project.javaSourceDir().path)
    }
}