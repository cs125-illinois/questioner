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
data class QuestionerConfig(val token: String? = null, val publish: Map<String, String>? = mapOf()) {
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
        val config = project.extensions.create("questioner", QuestionerConfigExtension::class.java)
        val configuration = project.file("questioner.yaml").let {
            if (it.exists()) {
                try {
                    ObjectMapper(YAMLFactory()).apply { registerModule(KotlinModule()) }.readValue(it)
                } catch (e: Exception) {
                    project.logger.warn("Invalid questioner.yaml file.")
                    QuestionerConfig()
                }
            } else {
                QuestionerConfig()
            }
        }

        val saveQuestions = project.tasks.register("saveQuestions", SaveQuestions::class.java).get()
        val generateMetatests = project.tasks.register("generateQuestionMetatests", GenerateMetatests::class.java).get()
        generateMetatests.dependsOn(project.tasks.getByName("processResources"))
        project.afterEvaluate {
            project.tasks.getByName("processResources").dependsOn(saveQuestions)
            generateMetatests.seed = config.seed
        }
        if (configuration.token != null) {
            val publishAll = project.tasks.register("publishQuestions").get()
            configuration.publish?.entries?.forEach { (name, url) ->
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
        project.tasks.register("cleanQuestions", CleanQuestions::class.java)

        val reconfigureTesting = project.tasks.register("reconfigureTesting", ReconfigureTesting::class.java).get()
        project.tasks.getByName("test").dependsOn(reconfigureTesting)
        project.tasks.getByName("test").mustRunAfter(reconfigureTesting)
        project.tasks.getByName("test").dependsOn(generateMetatests)
        project.tasks.getByName("compileTestKotlin").dependsOn(generateMetatests)
        try {
            project.tasks.getByName("formatKotlinTest").dependsOn(generateMetatests)
        } catch (e: Exception) {
        }
        try {
            project.tasks.getByName("lintKotlinTest").dependsOn(generateMetatests)
        } catch (e: Exception) {
        }
    }
}
