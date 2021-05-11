package edu.illinois.cs.cs125.questioner.lib

import com.squareup.moshi.Moshi
import java.io.File

private val moshi = Moshi.Builder().build()

@Suppress("unused")
class Validator(questionsFile: File, private val sourceDir: String, private val seed: Int) {
    private val questions = loadFromPath(questionsFile, sourceDir).also {
        assert(it.isNotEmpty())
    }

    suspend fun validate(name: String, verbose: Boolean = false, force: Boolean = false) {
        val question = questions[name] ?: error { "no question named $name " }
        if (question.validated && !force) {
            if (verbose) {
                println("$name: up-to-date")
            }
            return
        }
        question.initialize(seed = seed).also { report ->
            println("$name: $report")
            question.validationFile(sourceDir)
                .writeText(moshi.adapter(Question::class.java).indent("  ").toJson(questions[name]))
        }
    }
}
