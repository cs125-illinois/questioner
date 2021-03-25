package edu.illinois.cs.cs125.questioner.lib

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File

private val moshi = Moshi.Builder().build()

@Suppress("unused")
class Validator(private val questionsPath: File, private val seed: Int) {
    private val questions: Map<String, Question> = moshi.adapter<Map<String, Question>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Question::class.java
        )
    )
        .fromJson(this::class.java.getResource("/questions.json").readText())
        ?: error("can't load questions")

    init {
        assert(questions.isNotEmpty())
    }

    suspend fun validate(name: String, verbose: Boolean = false, force: Boolean = false) {
        val question = questions[name] ?: error { "no question named $name " }
        val path = File(questionsPath.toString(), "${question.metadata.contentHash}-validated.json").also {
            if (it.exists() && !force) {
                if (verbose) {
                    println("$name: up-to-date")
                }
                return
            }
        }
        question.initialize(seed = seed).also { report ->
            val output = if (verbose) {
                report.toString()
            } else {
                report.summary()
            }
            println("$name: $output")
            path.writeText(moshi.adapter(Question::class.java).indent("  ").toJson(questions[name]))
        }
    }
}
