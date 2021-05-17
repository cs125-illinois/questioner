package edu.illinois.cs.cs125.questioner.lib

import edu.illinois.cs.cs125.jeed.core.suppressionComment
import edu.illinois.cs.cs125.jenisol.core.ParameterGroup
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

sealed class ValidationFailed : Exception() {
    fun printContents(contents: String, path: String?) = """
        |${path?.let { "$path\n" } ?: ""}---
        |${contents}
        |---
    """.trimMargin()
}

class SolutionFailed(val solution: Question.FlatFile, val explanation: String) : ValidationFailed() {
    override val message = """
        |Solution failed the test suites:
        |${printContents(solution.contents, solution.path)}
        |${explanation}
    """.trimMargin()
}

class SolutionThrew(val solution: Question.FlatFile, val threw: Throwable, val parameters: ParameterGroup) :
    ValidationFailed() {
    override val message = """
        |Solution was not expected to throw, but threw $threw on parameters $parameters
        |${printContents(solution.contents, solution.path)}
        |If it should throw, allow it using @Correct(solutionThrows = true)
        |Otherwise filter the inputs using @FixedParameters, @RandomParameters, or @FilterParameters
    """.trimMargin()
}

class NoIncorrect : ValidationFailed() {
    override val message = """No incorrect examples found or generated through mutation
    |Please add some using the @Incorrect annotation
    """.trimMargin()
}

class TooMuchOutput(val contents: String, val path: String?, val size: Int, val maxSize: Int) : ValidationFailed() {
    override val message = """
        |Submission generated too much output ($size > $maxSize):
        |${printContents(contents, path)}
        |Maybe reduce the number of tests using @Correct(minTestCount = NUM)
    """.trimMargin()
}

class IncorrectPassed(
    val incorrect: Question.IncorrectFile, val explanation: String, val correct: Question.FlatFile
) : ValidationFailed() {
    override val message: String
        get() {
            val contents = incorrect.mutation?.marked()?.contents ?: incorrect.contents
            return """
        |Incorrect code passed the test suites:
        |${printContents(contents, incorrect.path ?: correct.path)}
        |If the code is incorrect, add an input to @FixedParameters to handle this case
        |${
                if (incorrect.mutation != null) {
                    "If the code is correct, you may need to disable this mutation using " +
                        "// ${incorrect.mutation.mutations.first().mutation.type.suppressionComment()}"
                } else {
                    ""
                }
            }""".trimMargin()
        }
}

class IncorrectTooManyTests(
    val incorrect: Question.IncorrectFile, val explanation: String, val correct: Question.FlatFile,
    val testsRequired: Int, val testsLimit: Int, val failingInput: String?
) : ValidationFailed() {
    override val message: String
        get() {
            val contents = incorrect.mutation?.marked()?.contents ?: incorrect.contents
            return """
        |Incorrect code eventually failed but required too many tests ($testsRequired > $testsLimit)
        |${failingInput?.let { "We found failing inputs $failingInput" } ?: "We were unable to find a failing input"}
        |${printContents(contents, incorrect.path ?: correct.path)}
        |If the code is incorrect, add an input to @FixedParameters to handle this case
        |${
                if (incorrect.mutation != null) {
                    "If the code is correct, you may need to disable this mutation using " +
                        "// ${incorrect.mutation.mutations.first().mutation.type.suppressionComment()}\n"
                } else {
                    ""
                }
            }You may also need to increase the test count using @Correct(maxTestCount = NUM)""".trimMargin()
        }
}

class IncorrectWrongReason(val incorrect: Question.IncorrectFile, val expected: String, val explanation: String) :
    ValidationFailed() {
    override val message: String
        get() {
            check(incorrect.mutation == null) { "Mutated sources failed for the wrong reason" }
            return """
        |Incorrect code failed but not for the reason we expected:
        |Expected: $expected
        |But Found: $explanation
        |${printContents(incorrect.contents, incorrect.path)}
        |Maybe check the argument to @Incorrect(reason = "reason")
    """.trimMargin()
        }
}

@Suppress("LongMethod", "ComplexMethod")
suspend fun Question.validate(seed: Int = Random.nextInt()): ValidationReport {
    clearValidation()

    val jenisolSettings = Settings(shrink = false)

    fun TestResults.checkCorrect(file: Question.FlatFile) {
        if (!succeeded) {
            throw SolutionFailed(file, summary)
        }
        val solutionThrew = tests()?.firstOrNull { it.jenisol?.solution?.threw != null }
        if (!metadata.solutionThrows && solutionThrew != null) {
            throw SolutionThrew(file, solutionThrew.jenisol!!.solution.threw!!, solutionThrew.jenisol.parameters)
        }
        val size = toJson().length
        if (toJson().length > Question.DEFAULT_MAX_OUTPUT_SIZE) {
            throw TooMuchOutput(file.contents, file.path, size, Question.DEFAULT_MAX_OUTPUT_SIZE)
        }
    }

    fun TestResults.checkIncorrect(file: Question.IncorrectFile, mutated: Boolean) {
        if (succeeded) {
            throw IncorrectPassed(file, summary, correct)
        }
        if (tests()?.size?.let { it > metadata.maxTestCount } == true) {
            val failingInput = tests()!!.find { !it.passed }?.arguments
            throw IncorrectTooManyTests(file, summary, correct, tests()!!.size, metadata.maxTestCount, failingInput)
        }
        try {
            validate(file.reason, mutated)
        } catch (e: Exception) {
            throw IncorrectWrongReason(file, e.message!!, summary)
        }
        val solutionThrew = tests()?.firstOrNull { it.jenisol?.solution?.threw != null }
        if (!metadata.solutionThrows && solutionThrew != null) {
            throw SolutionThrew(correct, solutionThrew.jenisol!!.solution.threw!!, solutionThrew.jenisol.parameters)
        }
        val size = toJson().length
        if (toJson().length > Question.DEFAULT_MAX_OUTPUT_SIZE) {
            throw TooMuchOutput(file.contents, file.path, size, Question.DEFAULT_MAX_OUTPUT_SIZE)
        }
    }

    (setOf(correct) + alternativeSolutions).forEach { right ->
        test(
            right.contents,
            jenisolSettings,
            language = right.language,
            correct = true,
            seed = seed
        ).also { it.checkCorrect(right) }
    }

    val mutations = mutations(seed, 64)

    val allIncorrect = (incorrectWithStarter() + mutations).also { allIncorrect ->
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
        ).also { it.checkIncorrect(wrong, true) }
    }

    incorrect.forEach { wrong ->
        test(
            wrong.contents,
            jenisolSettings,
            correct = false,
            language = wrong.language,
            seed = seed
        ).also { it.checkIncorrect(wrong, false) }
    }

    check(requiredTestCount > 0)
    check(requiredTestCount <= metadata.maxTestCount)

    val report = ValidationReport(
        allIncorrect,
        requiredTestCount,
        submissionTimeout,
        hasKotlin,
    )

    validated = true
    validationSeed = seed
    submissionTimeout = (metadata.maxTimeout.toDouble() / metadata.timeoutMultiplier.toDouble()).toLong()

    test(
        correct.contents,
        jenisolSettings,
        seed = seed,
    ).also { results ->
        require(results.succeeded) {
            "Untimed solution did not pass the test suite after validation: ${results.summary} ${correct.contents}"
        }
        submissionTimeout =
            (results.taskResults!!.interval.length.toDouble() * metadata.timeoutMultiplier.toDouble()).toLong()
                .coerceAtLeast(Question.DEFAULT_MIN_TIMEOUT)
        solutionPrinted = results.taskResults!!.outputLines.size
    }

    check(submissionTimeout < metadata.maxTimeout) { "Submission timeout is too long: $submissionTimeout > ${metadata.maxTimeout}" }

    (setOf(correct) + alternativeSolutions).forEach { right ->
        test(
            right.contents,
            language = right.language,
            seed = seed
        ).also { results ->
            require(results.succeeded) {
                "Solution did not pass the test suite after validation: ${results.summary}\n${right.contents}"
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
            results.validate(wrong.reason, isMutated)
        }
    }

    return report
}
