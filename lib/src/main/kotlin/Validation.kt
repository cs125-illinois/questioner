package edu.illinois.cs.cs125.questioner.lib

import edu.illinois.cs.cs125.jenisol.core.Settings
import kotlin.random.Random

data class ValidationReport(
    val incorrect: List<Question.IncorrectFile>,
    val requiredTestCount: Int,
    val requiredTime: Long,
    val hasKotlin: Boolean
) {
    data class Summary(
        val incorrect: Int,
        val requiredTestCount: Int,
        val requiredTime: Long,
        val kotlin: Boolean
    )

    val summary = Summary(incorrect.size, requiredTestCount, requiredTime, hasKotlin)
}

sealed class ValidationFailed: Exception()

class SolutionFailed(val solution: Question.FlatFile) : ValidationFailed()
class SolutionThrew(val solution: Question.FlatFile, val threw: Throwable): ValidationFailed()
class NoIncorrect: ValidationFailed()
class IncorrectPassed(val incorrect: Question.IncorrectFile): ValidationFailed()
class IncorrectWrongReason(val incorrect: Question.IncorrectFile, val explanation: String): ValidationFailed()

@Suppress("LongMethod", "ComplexMethod")
suspend fun Question.validate(seed: Int = Random.nextInt()): ValidationReport {
    clearValidation()

    val jenisolSettings = Settings(shrink = false)
    var tooLarge = false

    (setOf(correct) + alternativeSolutions).forEach { right ->
        test(
            right.contents,
            jenisolSettings,
            language = right.language,
            correct = true,
            seed = seed
        ).also { results ->
            if (!results.succeeded) {
                throw SolutionFailed(right)
            }
            val solutionThrew = results.tests?.mapNotNull { it.jenisol?.solution?.threw }?.firstOrNull()
            if (!metadata.solutionThrows && solutionThrew != null) {
                throw SolutionThrew(right, solutionThrew)
            }
        }
    }

    val mutations = mutations(seed, 64)

    val allIncorrect = (incorrect + mutations).toMutableList().also {
        if (javaStarter != null) {
            it.add(
                Question.IncorrectFile(
                    javaStarter.klass, javaStarter.contents, Question.IncorrectFile.Reason.TEST, javaStarter.language,
                    null, true
                )
            )
        }
        if (kotlinStarter != null) {
            it.add(
                Question.IncorrectFile(
                    kotlinStarter.klass,
                    kotlinStarter.contents,
                    Question.IncorrectFile.Reason.TEST,
                    kotlinStarter.language,
                    null,
                    true
                )
            )
        }
    }.also { allIncorrect ->
        check(allIncorrect.all { it.contents != correct.contents }) {
            "Incorrect solution identical to correct solution"
        }
        if (allIncorrect.isEmpty()) {
            throw NoIncorrect()
        }
    }

    mutations.forEach { wrong ->
        test(
            wrong.contents,
            jenisolSettings,
            correct = false,
            language = wrong.language,
            seed = seed
        ).also {
            if (it.succeeded) {
                throw IncorrectPassed(wrong)
            }
            try {
                it.validate(wrong.reason, wrong.contents, true)
            } catch (e: Exception) {
                throw IncorrectWrongReason(wrong, e.message!!)
            }
        }
    }

    incorrect.forEach { wrong ->
        test(
            wrong.contents,
            jenisolSettings,
            correct = false,
            language = wrong.language,
            seed = seed
        ).also {
            if (it.succeeded) {
                throw IncorrectPassed(wrong)
            }
            try {
                it.validate(wrong.reason, wrong.contents, true)
            } catch (e: Exception) {
                throw IncorrectWrongReason(wrong, e.message!!)
            }
        }
    }

    check(requiredTestCount > 0)
    check(submissionTimeout >= 0)
    submissionTimeout = submissionTimeout.coerceAtLeast(1)

    if (requiredTestCount > metadata.maxTestCount) {
        if (slowestFailingFailed) {
            error(
                """Found incorrect input $slowestFailingInputs for the following incorrect code,
                        |but it took too many tests (${requiredTestCount} > ${metadata.maxTestCount}):
                        |---
                        |$slowestFailingContent
                        |---
                        |Perhaps add this input to @FixedParameters?
                        |${correct.path}
                        """.trimMargin()
            )
        } else {
            error(
                """Unable to find a failing input for the following incorrect code:
                        |---
                        |$slowestFailingContent
                        |---
                        |Perhaps add an input to @FixedParameters, or disable this mutation using // mutate-disable?
                        |${correct.path}
                        """.trimMargin()
            )
        }
    }

    val report = ValidationReport(
        allIncorrect,
        requiredTestCount,
        submissionTimeout,
        hasKotlin,
    )

    validated = true
    validationSeed = seed

    test(
        correct.contents,
        jenisolSettings,
        seed = seed,
        timeoutAdjustment = 1024.0
    ).also { results ->
        require(results.succeeded) {
            "Untimed solution did not pass the test suite after validation: ${results.summary} ${correct.contents}"
        }
        submissionTimeout =
            (results.taskResults!!.interval.length.toDouble() * Question.DEFAULT_TIMEOUT_MULTIPLIER).toLong()
                .coerceAtLeast(Question.DEFAULT_MIN_TIMEOUT)
        solutionPrinted = results.taskResults!!.outputLines.size
        results.isTooLarge().also {
            if (it) {
                println("WARN: submission produced too much output\n---\n${correct.contents}---")
            }
            tooLarge = it
        }
    }

    check(submissionTimeout > 0)

    (setOf(correct) + alternativeSolutions).forEach { right ->
        test(
            right.contents,
            language = right.language,
            seed = seed
        ).also { results ->
            require(results.succeeded) {
                "Solution did not pass the test suite after validation: ${results.summary}\n${right.contents}"
            }
            results.isTooLarge().also {
                if (it) {
                    println("WARN: submission produced too much output\n---\n${right.contents}---")
                }
                tooLarge = it
            }
        }
    }

    (incorrect + mutations).forEachIndexed { index, wrong ->
        val isMutated = index >= incorrect.size
        test(
            wrong.contents,
            jenisolSettings,
            language = wrong.language,
            seed = seed
        ).also { results ->
            require(!results.succeeded) { "Incorrect submission was not rejected:\n${wrong.contents}" }
            results.validate(wrong.reason, wrong.contents, isMutated)
            results.isTooLarge().also {
                if (it) {
                    println("WARN: submission produced too much output\n---\n${wrong.contents}---")
                }
                tooLarge = it
            }
        }
    }

    check(!tooLarge) { "Testing produced too much output." }
    return report
}
