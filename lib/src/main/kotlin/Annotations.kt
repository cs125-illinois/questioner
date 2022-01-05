@file:Suppress("unused")

package edu.illinois.cs.cs125.questioner.lib

import com.squareup.moshi.JsonClass
import java.lang.RuntimeException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@JsonClass(generateAdapter = true)
data class CorrectData(
    val name: String,
    val version: String,
    val author: String,
    val path: String?,
    val description: String,
    val solutionThrows: Boolean,
    val focused: Boolean,
    val minTestCount: Int,
    val maxTestCount: Int,
    val minTimeout: Int,
    val maxTimeout: Int,
    val timeoutMultiplier: Int,
    val minMutationCount: Int,
    val maxMutationCount: Int,
    val outputMultiplier: Int,
    val maxExtraComplexity: Int,
    val maxDeadCode: Int
)

@Suppress("LongParameterList")
@Target(AnnotationTarget.CLASS)
annotation class Correct(
    val name: String,
    val version: String,
    val author: String,
    val path: String = "",
    val solutionThrows: Boolean = DEFAULT_SOLUTION_THROWS,
    val focused: Boolean = DEFAULT_FOCUSED,
    val minTestCount: Int = DEFAULT_MIN_TEST_COUNT,
    val maxTestCount: Int = DEFAULT_MAX_TEST_COUNT,
    val minTimeout: Int = DEFAULT_MIN_TIMEOUT,
    val maxTimeout: Int = DEFAULT_MAX_TIMEOUT,
    val timeoutMultiplier: Int = DEFAULT_TIMEOUT_MULTIPLIER,
    val minMutationCount: Int = DEFAULT_MIN_MUTATION_COUNT,
    val maxMutationCount: Int = DEFAULT_MAX_MUTATION_COUNT,
    val outputMultiplier: Int = DEFAULT_OUTPUT_MULTIPLIER,
    val maxExtraComplexity: Int = DEFAULT_MAX_EXTRA_COMPLEXITY,
    val maxDeadCode: Int = DEFAULT_MAX_DEAD_CODE
) {
    companion object {
        const val DEFAULT_SOLUTION_THROWS = false
        const val DEFAULT_FOCUSED = false
        const val DEFAULT_MIN_TEST_COUNT = 128
        const val DEFAULT_MAX_TEST_COUNT = 1024
        const val DEFAULT_MIN_TIMEOUT = 128
        const val DEFAULT_MAX_TIMEOUT = 2048
        const val DEFAULT_TIMEOUT_MULTIPLIER = 8
        const val DEFAULT_MIN_MUTATION_COUNT = 0
        const val DEFAULT_MAX_MUTATION_COUNT = 32
        const val DEFAULT_OUTPUT_MULTIPLIER = 8
        const val DEFAULT_MAX_EXTRA_COMPLEXITY = 2
        const val DEFAULT_MAX_DEAD_CODE = 0
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
annotation class Wrap(val autoStarter: Boolean = false)

@Target(AnnotationTarget.CLASS)
annotation class Cite(val source: String, val link: String = "")

@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
annotation class Ignore

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class CheckSource {
    companion object {
        val name: String = CheckSource::class.java.simpleName
        fun validate(method: Method) {
            check(Modifier.isStatic(method.modifiers)) { "@$name methods must be static" }
            check(method.returnType.name == "void") {
                "@$name method return values will not be used and should be void"
            }
            check(method.parameterTypes.size == 1 && method.parameterTypes[0] == String::class.java) {
                "@$name methods must accept parameters (String source)"
            }
            method.isAccessible = true
        }
    }
}

fun Method.isCheckSource() = isAnnotationPresent(CheckSource::class.java)

class SourceCheckException(message: String) : RuntimeException(message)

