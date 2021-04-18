package edu.illinois.cs.cs125.questioner.plugin

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import edu.illinois.cs.cs125.questioner.lib.Question
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.File

private val moshi = Moshi.Builder().build()

open class PublishQuestions : DefaultTask() {
    @Input
    lateinit var token: String

    @Input
    lateinit var destination: String

    init {
        group = "Build"
        description = "Publish questions."
    }

    @TaskAction
    fun publish() {
        val questions = moshi.adapter<Map<String, Question>>(
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                Question::class.java
            )
        ).fromJson(File(project.buildDir, "questioner/questions.json").readText())?.toMutableMap()!!.also { questions ->
            questions.entries.forEach { (slug, question) ->
                File(project.buildDir, "resources/main/${question.metadata.contentHash}-validated.json").also {
                    if (it.exists()) {
                        questions[slug] = moshi.adapter(Question::class.java).fromJson(it.readText())!!
                    }
                }
            }
        }.values
        questions.forEach {
            println("${it.name} ${it.validated}")
        }
    }
}