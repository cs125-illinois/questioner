@file:Suppress("unused")

package edu.illinois.cs.cs125.questioner.lib

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CorrectData(
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val checkstyle: Boolean = Correct.DEFAULT_CHECKSTYLE,
    val solutionThrows: Boolean = Correct.DEFAULT_THROWS,
    val maxTestCount: Int = Correct.DEFAULT_MAX_TEST_COUNT,
    val minTestCount: Int = Correct.DEFAULT_MIN_TEST_COUNT
)

@Suppress("LongParameterList")
@Target(AnnotationTarget.CLASS)
annotation class Correct(
    val name: String,
    val version: String,
    val author: String,
    val checkstyle: Boolean = DEFAULT_CHECKSTYLE,
    val solutionThrows: Boolean = DEFAULT_THROWS,
    val minTestCount: Int = DEFAULT_MIN_TEST_COUNT,
    val maxTestCount: Int = DEFAULT_MAX_TEST_COUNT,
    val minMutationCount: Int = DEFAULT_MIN_MUTATION_COUNT,
    val maxMutationCount: Int = DEFAULT_MAX_MUTATION_COUNT,
    val minIncorrectCount: Int = DEFAULT_MIN_INCORRECT_COUNT
) {
    companion object {
        const val DEFAULT_CHECKSTYLE = true
        const val DEFAULT_THROWS = false
        const val DEFAULT_MIN_TEST_COUNT = 128
        const val DEFAULT_MAX_TEST_COUNT = 1024
        const val DEFAULT_MIN_MUTATION_COUNT = 0
        const val DEFAULT_MAX_MUTATION_COUNT = 32
        const val DEFAULT_MIN_INCORRECT_COUNT = 4
    }
}

@Target(AnnotationTarget.CLASS, AnnotationTarget.FILE)
annotation class Incorrect(val reason: String = "test")

@Target(AnnotationTarget.CLASS, AnnotationTarget.FILE)
annotation class Starter

@Target(AnnotationTarget.CLASS, AnnotationTarget.FILE)
annotation class AlsoCorrect

@Target(AnnotationTarget.CLASS)
annotation class Whitelist(val paths: String)

@Target(AnnotationTarget.CLASS)
annotation class Blacklist(val paths: String)

@Target(AnnotationTarget.CLASS)
annotation class Wrap

@Target(AnnotationTarget.CLASS)
annotation class Cite(val source: String, val link: String = "")

@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
annotation class Ignore
