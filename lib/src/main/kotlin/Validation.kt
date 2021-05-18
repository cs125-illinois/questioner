@file:Suppress("MemberVisibilityCanBePrivate", "CanBeParameter")

package edu.illinois.cs.cs125.questioner.lib

import edu.illinois.cs.cs125.jeed.core.suppressionComment
import edu.illinois.cs.cs125.jenisol.core.ParameterGroup
import java.time.Instant
import kotlin.random.Random

@Suppress("LongMethod", "ComplexMethod")
suspend fun Question.validate(seed: Int = Random.nextInt()): ValidationReport {

    val javaClassWhitelist = mutableSetOf<String>().apply { addAll(defaultJavaClassWhitelist) }
    val kotlinClassWhitelist = mutableSetOf<String>().apply { addAll(defaultKotlinClassWhitelist) }

    fun TestResults.checkCorrect(file: Question.FlatFile) {
        if (!succeeded) {
            throw SolutionFailed(file, summary)
        }
        val solutionThrew = tests()?.firstOrNull { it.jenisol?.solution?.threw != null }
        if (!control.solutionThrows && solutionThrew != null) {
            throw SolutionThrew(file, solutionThrew.jenisol!!.solution.threw!!, solutionThrew.jenisol.parameters)
        }
        val size = toJson().length
        if (size > Question.DEFAULT_MAX_OUTPUT_SIZE || taskResults!!.truncatedLines > 0) {
            throw TooMuchOutput(file.contents, file.path, size, Question.DEFAULT_MAX_OUTPUT_SIZE)
        }
        when (file.language) {
            Question.Language.java -> {
                javaClassWhitelist.addAll(
                    taskResults!!.sandboxedClassLoader!!.loadedClasses.filter { klass ->
                        !klass.startsWith("edu.illinois.cs.cs125.jeed.core")
                    }
                )
            }
            Question.Language.kotlin -> {
                kotlinClassWhitelist.addAll(
                    taskResults!!.sandboxedClassLoader!!.loadedClasses.filter { klass ->
                        !klass.startsWith("edu.illinois.cs.cs125.jeed.core")
                    }
                )
            }
        }
    }

    fun TestResults.checkIncorrect(file: Question.IncorrectFile, mutated: Boolean) {
        if (succeeded) {
            throw IncorrectPassed(file, correct)
        }
        if (tests()?.size?.let { it > control.maxTestCount } == true) {
            val failingInput = tests()!!.find { !it.passed }?.arguments
            throw IncorrectTooManyTests(file, correct, tests()!!.size, control.maxTestCount, failingInput)
        }
        try {
            validate(file.reason, mutated)
        } catch (e: Exception) {
            throw IncorrectWrongReason(file, e.message!!, summary)
        }
        val solutionThrew = tests()?.firstOrNull { it.jenisol?.solution?.threw != null }
        if (!control.solutionThrows && solutionThrew != null) {
            throw SolutionThrew(correct, solutionThrew.jenisol!!.solution.threw!!, solutionThrew.jenisol.parameters)
        }
        val size = toJson().length
        if (toJson().length > Question.DEFAULT_MAX_OUTPUT_SIZE) {
            throw TooMuchOutput(file.contents, file.path, size, Question.DEFAULT_MAX_OUTPUT_SIZE)
        }
    }

    val bootStrapStart = Instant.now()
    // The solution and alternate solutions define what external classes can be used, so they need to be run first
    // Sets javaClassWhitelist and kotlinClassWhitelist
    val bootstrapSettings = Question.TestingSettings(
        seed = seed,
        testCount = control.minTestCount,
        timeout = control.maxTimeout,
        outputLimit = Question.UNLIMITED_OUTPUT_LINES,
        javaWhitelist = null,
        kotlinWhitelist = null,
        useCheckstyle = true,
        shrink = false
    )
    (setOf(correct) + alternativeSolutions).forEach { right ->
        test(right.contents, right.language, bootstrapSettings).also {
            it.checkCorrect(right)
        }
    }
    val bootstrapLength = Instant.now().toEpochMilli() - bootStrapStart.toEpochMilli()

    val mutationStart = Instant.now()
    val mutations = mutations(seed, control.maxMutationCount).also {
        if (it.size < control.minMutationCount) {
            throw TooFewMutations()
        }
    }
    val allIncorrect = (incorrect + mutations).also { allIncorrect ->
        check(allIncorrect.all { it.contents != correct.contents }) {
            "Incorrect solution identical to correct solution"
        }
        if (allIncorrect.isEmpty()) {
            throw NoIncorrect()
        }
    }
    val mutationLength = Instant.now().toEpochMilli() - mutationStart.toEpochMilli()

    // Next step is to figure out how many tests to run using mutations and @Incorrect annotations
    // Sets requiredTestCount
    val incorrectStart = Instant.now()

    data class IncorrectResults(val incorrect: Question.IncorrectFile, val results: TestResults)

    val incorrectSettings = Question.TestingSettings(
        seed = seed,
        testCount = control.maxTestCount,
        timeout = control.maxTimeout,
        outputLimit = Question.UNLIMITED_OUTPUT_LINES,
        javaWhitelist = javaClassWhitelist,
        kotlinWhitelist = kotlinClassWhitelist,
        useCheckstyle = true,
        shrink = false
    )
    val incorrectResults = allIncorrect.map { wrong ->
        test(
            wrong.contents,
            wrong.language,
            incorrectSettings.copy(useCheckstyle = wrong.mutation != null)
        ).let {
            it.checkIncorrect(wrong, wrong.mutation != null)
            IncorrectResults(wrong, it)
        }
    }
    val incorrectLength = Instant.now().toEpochMilli() - incorrectStart.toEpochMilli()

    val requiredTestCount =
        incorrectResults.mapNotNull { it.results.tests()?.size }.maxOrNull() ?: error("No incorrect results")
    val testCount = requiredTestCount.coerceAtLeast(control.minTestCount)

    // Rerun solutions to set timeouts and output limits
    // sets solutionRuntime and solutionOutputLines
    val calibrationStart = Instant.now()
    val calibrationSettings = Question.TestingSettings(
        seed = seed,
        testCount = testCount,
        timeout = control.maxTimeout,
        outputLimit = Question.UNLIMITED_OUTPUT_LINES,
        javaWhitelist = javaClassWhitelist,
        kotlinWhitelist = kotlinClassWhitelist,
        useCheckstyle = false,
        shrink = false
    )
    val calibrationResults = (setOf(correct) + alternativeSolutions).map { right ->
        test(
            right.contents,
            right.language,
            calibrationSettings
        ).also {
            it.checkCorrect(right)
        }
    }
    val calibrationLength = Instant.now().toEpochMilli() - calibrationStart.toEpochMilli()

    val solutionMaxRuntime = calibrationResults.maxOf { it.taskResults!!.interval.length.toInt() }
    val solutionMaxOutputLines = calibrationResults.maxOf { it.taskResults!!.outputLines.size }
    testingSettings = Question.TestingSettings(
        seed = seed,
        testCount = testCount,
        timeout = (solutionMaxRuntime * control.timeoutMultiplier).coerceAtLeast(control.minTimeout),
        outputLimit = solutionMaxOutputLines.coerceAtLeast(testCount * control.outputMultiplier),
        javaWhitelist = javaClassWhitelist,
        kotlinWhitelist = kotlinClassWhitelist,
        useCheckstyle = metadata.checkstyle,
        shrink = false
    )
    validationResults = Question.ValidationResults(
        seed = seed,
        requiredTestCount = requiredTestCount,
        mutationCount = mutations.size,
        solutionMaxRuntime = solutionMaxRuntime,
        bootstrapLength = bootstrapLength,
        mutationLength = mutationLength,
        incorrectLength = incorrectLength,
        calibrationLength = calibrationLength
    )

    return ValidationReport(allIncorrect, requiredTestCount, solutionMaxRuntime, hasKotlin)
}

data class ValidationReport(
    val incorrect: List<Question.IncorrectFile>,
    val requiredTestCount: Int,
    val requiredTime: Int,
    val hasKotlin: Boolean
) {
    data class Summary(
        val incorrect: Int,
        val requiredTestCount: Int,
        val requiredTime: Int,
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

class TooFewMutations : ValidationFailed() {
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
    val incorrect: Question.IncorrectFile, val correct: Question.FlatFile
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
    val incorrect: Question.IncorrectFile, val correct: Question.FlatFile,
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