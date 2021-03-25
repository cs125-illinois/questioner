package edu.illinois.cs.cs125.questioner.plugin

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import edu.illinois.cs.cs125.questioner.plugin.save.SaveQuestions
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuestionerConfig(val email: String, val token: String) {
    init {
        require(UUID.fromString(token) != null) { "Invalid UUID: $token" }
    }
}

@Suppress("unused")
class QuestionerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val configurationFile = project.file("questioner.yaml").also {
            require(it.exists()) { "Could not load questioner.yaml configuration file" }
        }
        ObjectMapper(YAMLFactory()).apply { registerModule(KotlinModule()) }
            .readValue<QuestionerConfig>(configurationFile)

        val saveQuestions = project.tasks.register("saveQuestions", SaveQuestions::class.java).get()
        project.tasks.register("generateQuestionMetatests", GenerateMetatests::class.java)
        project.afterEvaluate {
            project.tasks.getByName("processResources").dependsOn(saveQuestions)
        }
    }
}