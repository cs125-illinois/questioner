package edu.illinois.cs.cs125.questioner.plugin

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import edu.illinois.cs.cs125.questioner.plugin.save.SaveQuestions
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import java.io.File
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuestionerConfig(val token: String?, val publish: Map<String, String> = mapOf()) {
    init {
        require(token == null || UUID.fromString(token) != null) { "Invalid UUID: $token" }
    }
}

fun Project.javaSourceDir(): File =
    convention.getPlugin(JavaPluginConvention::class.java)
        .sourceSets.getByName("main").java.srcDirs.let {
            check(it.size == 1) { "Found multiple source directories: ${it.joinToString(",")}" }
            it.first()!!
        }

@Suppress("unused")
class QuestionerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val configurationFile = project.file("questioner.yaml").also {
            require(it.exists()) { "Could not load questioner.yaml configuration file" }
        }
        val configuration = ObjectMapper(YAMLFactory()).apply { registerModule(KotlinModule()) }
            .readValue<QuestionerConfig>(configurationFile)

        val saveQuestions = project.tasks.register("saveQuestions", SaveQuestions::class.java).get()
        val generateMetatests = project.tasks.register("generateQuestionMetatests", GenerateMetatests::class.java).get()
        generateMetatests.dependsOn(project.tasks.getByName("processResources"))
        project.afterEvaluate {
            project.tasks.getByName("processResources").dependsOn(saveQuestions)
        }
        if (configuration.token != null) {
            val publishAll = project.tasks.create("publishQuestions")
            configuration.publish.entries.forEach { (name, url) ->
                val publishQuestions =
                    project.tasks.register("${name}PublishQuestions", PublishQuestions::class.java).get()
                publishQuestions.token = configuration.token
                publishQuestions.destination = url
                publishQuestions.dependsOn(saveQuestions)
                publishAll.dependsOn(publishQuestions)
            }
        }
        project.convention.getPlugin(JavaPluginConvention::class.java)
            .sourceSets.getByName("main").resources { it.srcDirs(File(project.buildDir, "questioner")) }
        project.tasks.register("questionerTesting", TestingTask::class.java)
        project.tasks.register("cleanQuestions", CleanQuestions::class.java)
    }
}
