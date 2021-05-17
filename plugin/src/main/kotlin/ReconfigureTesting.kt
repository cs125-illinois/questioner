package edu.illinois.cs.cs125.questioner.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

abstract class ReconfigureTesting : DefaultTask() {
    @TaskAction
    fun run() {
        project.tasks.getByName("compileJava").enabled = false
        project.tasks.getByName("compileKotlin").enabled = false
        project.tasks.getByName("jar").enabled = false
    }
}
