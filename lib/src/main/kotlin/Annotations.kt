@file:Suppress("unused")

package edu.illinois.cs.cs125.questioner.lib

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CorrectData(
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val points: Int = Correct.DEFAULT_POINTS,
    val timeoutMultiplier: Double = Correct.DEFAULT_TIMEOUT_MULTIPLIER,
    val minTimeout: Long = Correct.DEFAULT_MIN_TIMEOUT,
    val mutate: Boolean = Correct.DEFAULT_MUTATE,
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
    val points: Int = DEFAULT_POINTS,
    val timeoutMultiplier: Double = DEFAULT_TIMEOUT_MULTIPLIER,
    val minTimeout: Long = DEFAULT_MIN_TIMEOUT,
    val mutate: Boolean = DEFAULT_MUTATE,
    val checkstyle: Boolean = DEFAULT_CHECKSTYLE,
    val solutionThrows: Boolean = DEFAULT_THROWS,
    val minTestCount: Int = DEFAULT_MIN_TEST_COUNT,
    val maxTestCount: Int = DEFAULT_MAX_TEST_COUNT
) {
    companion object {
        const val DEFAULT_POINTS = 100
        const val DEFAULT_TIMEOUT_MULTIPLIER = 4.0
        const val DEFAULT_MIN_TIMEOUT = 512L
        const val DEFAULT_MUTATE = true
        const val DEFAULT_CHECKSTYLE = true
        const val DEFAULT_THROWS = false
        const val DEFAULT_MIN_TEST_COUNT = 128
        const val DEFAULT_MAX_TEST_COUNT = 1024
    }
}

@Target(AnnotationTarget.FUNCTION)
annotation class Test(
    val points: Int = 100,
    val timeoutMultiplier: Double = DEFAULT_TRADITIONAL_TIMEOUT_MULTIPLIER,
    val minTimeout: Long = DEFAULT_MIN_TIMEOUT
) {
    companion object {
        const val DEFAULT_TRADITIONAL_TIMEOUT_MULTIPLIER = 4.0
        const val DEFAULT_MIN_TIMEOUT = 128L
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
annotation class WrapWith(val klass: String = "Question")

@Target(AnnotationTarget.CLASS)
annotation class Cite(val source: String, val link: String = "")

@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
annotation class Ignore
