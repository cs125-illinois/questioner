package edu.illinois.cs.cs125.questioner.lib

import com.squareup.moshi.Moshi
import java.io.File

private val moshi = Moshi.Builder().build()

@Suppress("unused")
class Validator(private val questionsPath: File, private val seed: Int) {
    private val questions = loadFromResources().also {
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
            val output = if (verbose) {
                report.toString()
            } else {
                report.summary()
            }
            println("$name: $output")
            File(questionsPath.toString(), "${question.metadata.contentHash}-validated.json")
                .writeText(moshi.adapter(Question::class.java).indent("  ").toJson(questions[name]))
        }
    }
}
