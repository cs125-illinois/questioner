@file:Suppress("unused")

package edu.illinois.cs.cs125.questioner.lib

import java.lang.RuntimeException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@Suppress("LongParameterList")
@Target(AnnotationTarget.CLASS)
annotation class Correct(
    val name: String,
    val version: String,
    val author: String,
    val path: String = "",
    val solutionThrows: Boolean = false,
    val focused: Boolean = false,
    val minTestCount: Int = -1,
    val maxTestCount: Int = -1,
    val minTimeout: Int = -1,
    val maxTimeout: Int = -1,
    val timeoutMultiplier: Int = -1,
    val minMutationCount: Int = -1,
    val maxMutationCount: Int = -1,
    val outputMultiplier: Int = -1,
    val maxExtraComplexity: Int = -1,
    val maxDeadCode: Int = -1,
    val maxExecutionCountMultiplier: Long = -1,
    val executionCountFailureMultiplier: Int = -1,
    val executionCountTimeoutMultiplier: Int = -1,
    val minExtraSourceLines: Int = -1,
    val sourceLinesMultiplier: Double = -1.0
)

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

