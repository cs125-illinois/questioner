package edu.illinois.cs.cs125.questioner.lib

import java.io.File

@Suppress("unused")
class Validator(questionsFile: File, private val sourceDir: String, private val seed: Int) {
    private val unvalidatedQuestions = loadFromPath(questionsFile, sourceDir).also {
        assert(it.isNotEmpty())
    }
    private val questions = loadFromPath(questionsFile, sourceDir).also {
        assert(it.isNotEmpty())
    }

    suspend fun validate(name: String, verbose: Boolean = false, force: Boolean = false) {
        val question = if (force) {
            unvalidatedQuestions[name]
        } else {
            questions[name]
        } ?: error { "no question named $name " }
        if (question.validated && !force) {
            if (verbose) {
                println("$name: up-to-date")
            }
            return
        }
        question.validate(seed = seed).also { report ->
            println("$name: ${report.summary}")
            question.validationFile(sourceDir)
                .writeText(moshi.adapter(Question::class.java).indent("  ").toJson(question))
        }
    }
}
