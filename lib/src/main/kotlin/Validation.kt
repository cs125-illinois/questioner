@file:Suppress("MemberVisibilityCanBePrivate", "CanBeParameter")

package edu.illinois.cs.cs125.questioner.lib

import edu.illinois.cs.cs125.jeed.core.suppressionComment
import edu.illinois.cs.cs125.jenisol.core.ParameterGroup
import edu.illinois.cs.cs125.jenisol.core.fullName
import edu.illinois.cs.cs125.jenisol.core.generators.Parameters
import java.lang.reflect.Executable
import java.time.Instant

data class CorrectResults(val incorrect: Question.FlatFile, val results: TestResults)
data class IncorrectResults(val incorrect: Question.IncorrectFile, val results: TestResults)

@Suppress("LongMethod", "ComplexMethod")
suspend fun Question.validate(seed: Int): ValidationReport {

    fauxStatic = solution.fauxStatic

    val javaClassWhitelist = mutableSetOf<String>().apply { addAll(defaultJavaClassWhitelist) }
    val kotlinClassWhitelist = mutableSetOf<String>().apply { addAll(defaultKotlinClassWhitelist) }

    fun TestResults.checkCorrect(file: Question.FlatFile) {
        if (!succeeded) {
            throw SolutionFailed(file, summary)
        }
        if (failedLinting!!) {
            throw SolutionFailedLinting(file)
        }
        val solutionThrew = tests()?.filter {
            it.jenisol!!.solution.threw != null
        }?.find {
            val exception = it.jenisol!!.solution.threw!!
            exception !is AssertionError && exception !is IllegalArgumentException && exception !is IllegalStateException
        }
        if (!control.solutionThrows!! && solutionThrew != null) {
            throw SolutionThrew(file, solutionThrew.jenisol!!.solution.threw!!, solutionThrew.jenisol.parameters)
        }
        tests()
            ?.filter {
                it.jenisol!!.solution.threw == null
                    && (it.jenisol.parameterType == Parameters.Type.RANDOM
                    || it.jenisol.parameterType == Parameters.Type.RANDOM_METHOD
                    )
            }
            ?.let { results ->
                val executableReturns = mutableMapOf<Executable, MutableList<Any>>()
                for (result in results) {
                    result.jenisol!!.solution.returned?.let {
                        executableReturns[result.jenisol.solutionExecutable] =
                            executableReturns[result.jenisol.solutionExecutable] ?: mutableListOf()
                        executableReturns[result.jenisol.solutionExecutable]!!.add(result.jenisol.solution.returned!!)
                    }
                }
                executableReturns.forEach { (executable, values) ->
                    if (executable.fullName() == "public boolean equals(java.lang.Object)") {
                        return@forEach
                    }
                    if (values.distinct().size == 1) {
                        throw SolutionLacksEntropy(
                            file,
                            values.size,
                            values.distinct().size,
                            executable,
                            solution.fauxStatic,
                            values.first()
                        )
                    }
                }
            }

        val size = toJson().length
        if (size > Question.DEFAULT_MAX_OUTPUT_SIZE || taskResults!!.truncatedLines > 0) {
            throw TooMuchOutput(file.contents, file.path, size, Question.DEFAULT_MAX_OUTPUT_SIZE, file.language)
        }

        val expectedDeadCode =
            file.expectedDeadCount ?: correct.expectedDeadCount ?: error("Couldn't load dead code count")
        val deadCodeLimit = control.maxDeadCode!! + expectedDeadCode

        if (complete.coverage!!.submission.missed > deadCodeLimit) {
            throw SolutionDeadCode(
                file,
                complete.coverage!!.submission.missed,
                deadCodeLimit,
                complete.coverage!!.missed
            )
        }
        when (file.language) {
            Question.Language.java -> {
                javaClassWhitelist.addAll(
                    taskResults!!.sandboxedClassLoader!!.loadedClasses.filter { klass ->
                        !klass.startsWith("edu.illinois.cs.cs125.jeed.core") &&
                            !klass.startsWith("java.lang.invoke.MethodHandles")
                    }
                )
            }
            Question.Language.kotlin -> {
                kotlinClassWhitelist.addAll(
                    taskResults!!.sandboxedClassLoader!!.loadedClasses.filter { klass ->
                        !klass.startsWith("edu.illinois.cs.cs125.jeed.core") &&
                            !klass.startsWith("java.lang.invoke.MethodHandles")
                    }
                )
            }
        }
    }

    fun TestResults.checkIncorrect(file: Question.IncorrectFile, mutated: Boolean) {
        if (!mutated && failedLinting == true) {
            throw IncorrectFailedLinting(file, correct)
        }
        if (!listOf(
                Question.IncorrectFile.Reason.DEADCODE,
                Question.IncorrectFile.Reason.LINECOUNT,
                Question.IncorrectFile.Reason.TOOLONG,
                Question.IncorrectFile.Reason.MEMORYLIMIT
            ).contains(file.reason)
        ) {
            if (succeeded) {
                throw IncorrectPassed(file, correct)
            }
            if (tests()?.size?.let { it > control.maxTestCount!! } == true) {
                val failingInput = tests()!!.find { !it.passed }?.arguments
                throw IncorrectTooManyTests(file, correct, tests()!!.size, control.maxTestCount!!, failingInput)
            }
        }
        try {
            validate(file.reason, mutated)
        } catch (e: Exception) {
            throw if (succeeded) {
                WrongReasonPassed(file, e.message!!)
            } else {
                IncorrectWrongReason(file, e.message!!, summary)
            }
        }
        val solutionThrew = tests()?.filter {
            it.jenisol!!.solution.threw != null
        }?.find {
            val exception = it.jenisol!!.solution.threw!!
            exception !is AssertionError && exception !is IllegalArgumentException && exception !is IllegalStateException
        }
        if (!control.solutionThrows!! && solutionThrew != null) {
            throw SolutionThrew(correct, solutionThrew.jenisol!!.solution.threw!!, solutionThrew.jenisol.parameters)
        }
        val size = toJson().length
        if (toJson().length > Question.DEFAULT_MAX_OUTPUT_SIZE) {
            throw TooMuchOutput(file.contents, file.path, size, Question.DEFAULT_MAX_OUTPUT_SIZE, file.language)
        }
    }

    val bootStrapStart = Instant.now()

    // The solution and alternate solutions define what external classes can be used, so they need to be run first
    // Sets javaClassWhitelist and kotlinClassWhitelist
    val bootstrapSettings = Question.TestingSettings(
        seed = seed,
        testCount = control.minTestCount!!,
        timeout = control.maxTimeout!!, // No timeout
        outputLimit = Question.UNLIMITED_OUTPUT_LINES, // No line limit
        javaWhitelist = null,
        kotlinWhitelist = null,
        shrink = false,
        checkBlacklist = false,
        executionCountLimit = Question.LanguagesResourceUsage(
            control.maxExecutionCountMultiplier!! * 1024,
            control.maxExecutionCountMultiplier!! * 1024
        ) // No execution count limit
        // No allocation limit
    )
    // Warm up allocation tracking for submission
    listOf(correct).first().also { right ->
        test(right.contents, right.language, bootstrapSettings).also { testResults ->
            testResults.checkCorrect(right)
        }
    }
    val firstCorrectResults = (setOf(correct) + alternativeSolutions).map { right ->
        test(right.contents, right.language, bootstrapSettings).also { testResults ->
            println(right.contents)
            println(testResults.complete.memoryAllocation?.submission)
            testResults.checkCorrect(right)
        }
    }
    println(firstCorrectResults.first().complete.testing?.tests?.map { it.runnerID }?.distinct()?.size)

    val bootstrapSolutionCoverage = firstCorrectResults
        .mapNotNull { it.complete.coverage }
        .minByOrNull {
            it.solution.covered / it.solution.total
        }!!.solution

    fun List<TestResults>.setResourceUsage(
        multiplier: Double = 1.0,
        aspect: (TestResults.CompletedTasks) -> TestResults.ResourceUsageComparison?
    ): Question.LanguagesResourceUsage {
        val javaSolutionExecutionCount = filter { it.language == Question.Language.java }
            .mapNotNull { aspect(it.complete) }
            .maxByOrNull {
                it.solution
            }!!.solution.times(multiplier).toLong()
        val kotlinSolutionExecutionCount = filter { it.language == Question.Language.kotlin }
            .mapNotNull { aspect(it.complete) }
            .maxByOrNull {
                it.solution
            }?.solution?.times(multiplier)?.toLong()
        return Question.LanguagesResourceUsage(javaSolutionExecutionCount, kotlinSolutionExecutionCount)
    }

    val bootstrapSolutionExecutionCount =
        firstCorrectResults.setResourceUsage(control.maxTestCount!! / control.minTestCount!!.toDouble()) {
            it.executionCount
        }

    val bootstrapSolutionAllocation =
        firstCorrectResults.setResourceUsage(1.0) {
            it.memoryAllocation
        }

    val bootstrapLength = Instant.now().toEpochMilli() - bootStrapStart.toEpochMilli()

    val mutationStart = Instant.now()
    val mutations = mutations(seed, control.maxMutationCount!!).also {
        if (it.size < control.minMutationCount!!) {
            throw TooFewMutations(correct, it.size, control.minMutationCount!!)
        }
    }
    val allIncorrect = (incorrect + mutations).also { allIncorrect ->
        check(allIncorrect.all { it.contents != correct.contents }) {
            "Incorrect solution identical to correct solution"
        }
        if (allIncorrect.isEmpty()) {
            throw NoIncorrect(correct)
        }
    }
    val mutationLength = Instant.now().toEpochMilli() - mutationStart.toEpochMilli()

    // Next step is to figure out how many tests to run using mutations and @Incorrect annotations
    // Sets requiredTestCount
    val incorrectStart = Instant.now()
    val incorrectSettings = Question.TestingSettings(
        seed = seed,
        testCount = control.maxTestCount!!,
        timeout = control.maxTimeout!!,
        outputLimit = Question.UNLIMITED_OUTPUT_LINES,
        javaWhitelist = javaClassWhitelist,
        kotlinWhitelist = kotlinClassWhitelist,
        shrink = false,
        solutionCoverage = bootstrapSolutionCoverage,
        solutionExecutionCount = bootstrapSolutionExecutionCount,
        executionCountLimit = Question.LanguagesResourceUsage(
            control.maxTestCount!! * control.maxExecutionCountMultiplier!! * 1024,
            control.maxTestCount!! * control.maxExecutionCountMultiplier!! * 1024
        ),
        solutionAllocation = bootstrapSolutionAllocation
    )
    val incorrectResults = allIncorrect.map { wrong ->
        test(
            wrong.contents,
            wrong.language,
            incorrectSettings
        ).let {
            println(it.complete.memoryAllocation?.submission)
            println(it.complete.testing?.tests?.map { it.runnerID }?.distinct()?.size)
            it.checkIncorrect(wrong, wrong.mutation != null)
            IncorrectResults(wrong, it)
        }
    }
    val incorrectLength = Instant.now().toEpochMilli() - incorrectStart.toEpochMilli()

    val requiredTestCount = incorrectResults
        .filter { !it.results.timeout && !it.results.succeeded }
        .mapNotNull { it.results.tests()?.size }
        .maxOrNull() ?: error("No incorrect results")
    val testCount = requiredTestCount.coerceAtLeast(control.minTestCount!!)

    // Rerun solutions to set timeouts and output limits
    // sets solution runtime, output lines, executed lines, and allocation
    val calibrationStart = Instant.now()
    val calibrationSettings = Question.TestingSettings(
        seed = seed,
        testCount = testCount,
        timeout = control.maxTimeout!!,
        outputLimit = Question.UNLIMITED_OUTPUT_LINES,
        javaWhitelist = javaClassWhitelist,
        kotlinWhitelist = kotlinClassWhitelist,
        shrink = false,
        executionCountLimit = Question.LanguagesResourceUsage(
            testCount * control.maxExecutionCountMultiplier!! * 1024,
            testCount * control.maxExecutionCountMultiplier!! * 1024
        )
    )
    val calibrationResults = (setOf(correct) + alternativeSolutions).map { right ->
        test(
            right.contents,
            right.language,
            calibrationSettings
        ).let {
            it.checkCorrect(right)
            CorrectResults(right, it)
        }
    }
    val calibrationLength = Instant.now().toEpochMilli() - calibrationStart.toEpochMilli()

    val solutionMaxRuntime = calibrationResults.maxOf { it.results.taskResults!!.interval.length.toInt() }
    val solutionMaxOutputLines = calibrationResults.maxOf { it.results.taskResults!!.outputLines.size }
    val solutionExecutionCounts = calibrationResults.map { it.results }.setResourceUsage { it.executionCount }
    val solutionAllocation = calibrationResults.map { it.results }.setResourceUsage { it.memoryAllocation }
    val solutionCoverage = calibrationResults
        .mapNotNull { it.results.complete.coverage }
        .minByOrNull {
            it.solution.covered / it.solution.total
        }!!.solution

    testingSettings = Question.TestingSettings(
        seed = seed,
        testCount = testCount,
        timeout = (solutionMaxRuntime * control.timeoutMultiplier!!).coerceAtLeast(control.minTimeout!!),
        outputLimit = solutionMaxOutputLines.coerceAtLeast(testCount * control.outputMultiplier!!),
        javaWhitelist = javaClassWhitelist,
        kotlinWhitelist = kotlinClassWhitelist,
        shrink = false,
        executionCountLimit = Question.LanguagesResourceUsage(
            solutionExecutionCounts.java * control.executionTimeoutMultiplier!!,
            solutionExecutionCounts.kotlin?.times(control.executionTimeoutMultiplier!!)
        ),
        allocationLimit = Question.LanguagesResourceUsage(
            solutionAllocation.java * control.allocationLimitMultiplier!!,
            solutionAllocation.kotlin?.times(control.allocationLimitMultiplier!!)
        )
    )
    validationResults = Question.ValidationResults(
        seed = seed,
        requiredTestCount = requiredTestCount,
        mutationCount = mutations.size,
        solutionMaxRuntime = solutionMaxRuntime,
        bootstrapLength = bootstrapLength,
        mutationLength = mutationLength,
        incorrectLength = incorrectLength,
        calibrationLength = calibrationLength,
        solutionCoverage = solutionCoverage,
        executionCounts = solutionExecutionCounts,
        memoryAllocation = solutionAllocation
    )

    return ValidationReport(
        this,
        calibrationResults,
        incorrectResults,
        requiredTestCount,
        solutionMaxRuntime,
        hasKotlin
    )
}

private fun TestResults.validate(reason: Question.IncorrectFile.Reason, isMutated: Boolean) {
    when (reason) {
        Question.IncorrectFile.Reason.COMPILE -> require(failed.compileSubmission != null) {
            "Expected submission not to compile"
        }
        Question.IncorrectFile.Reason.CHECKSTYLE -> require(failed.checkstyle != null) {
            "Expected submission to fail checkstyle"
        }
        Question.IncorrectFile.Reason.DESIGN -> require(failed.checkCompiledSubmission != null || failed.checkExecutedSubmission != null) {
            "Expected submission to fail design"
        }
        Question.IncorrectFile.Reason.TIMEOUT -> require(timeout || !succeeded) {
            "Expected submission to timeout"
        }
        Question.IncorrectFile.Reason.DEADCODE -> require(complete.coverage?.failed == true) {
            "Expected submission to contain dead code"
        }
        Question.IncorrectFile.Reason.LINECOUNT -> require(complete.executionCount?.failed == true) {
            "Expected submission to execute too many lines"
        }
        Question.IncorrectFile.Reason.TOOLONG -> require(complete.lineCount?.failed == true) {
            "Expected submission to contain too many lines"
        }
        Question.IncorrectFile.Reason.MEMORYLIMIT -> require(complete.memoryAllocation?.failed == true) {
            "Expected submission to allocate too much memory: ${complete.memoryAllocation}"
        }
        else -> require(isMutated || (!timeout && complete.testing?.passed == false)) {
            "Expected submission to fail tests"
        }
    }
}

data class ValidationReport(
    val question: Question,
    val correct: List<CorrectResults>,
    val incorrect: List<IncorrectResults>,
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

class SolutionFailedLinting(val solution: Question.FlatFile) : ValidationFailed() {
    override val message = """
        |Solution failed linting with ${
        if (solution.language == Question.Language.kotlin) {
            "ktlint"
        } else {
            "checkstyle"
        }
    }
        |${printContents(solution.contents, solution.path)}
    """.trimMargin()
}

class SolutionThrew(val solution: Question.FlatFile, val threw: Throwable, val parameters: ParameterGroup) :
    ValidationFailed() {
    override val message = """
        |Solution was not expected to throw an unusual exception, but threw $threw on parameters $parameters
        |${printContents(solution.contents, solution.path)}
        |If it should throw, allow it using @Correct(solutionThrows = true)
        |Otherwise filter the inputs using @FixedParameters, @RandomParameters, or @FilterParameters
    """.trimMargin()
}

class SolutionLacksEntropy(
    val solution: Question.FlatFile,
    val count: Int,
    val amount: Int,
    val executable: Executable,
    val fauxStatic: Boolean,
    val result: Any?
) :
    ValidationFailed() {
    override val message = """
        |$count random inputs to the solution method ${executable.fullName()} only generated $amount distinct results: $result
        |${
        if (fauxStatic) {
            "Note that the solution is being tested as a faux static method, which may cause problems"
        } else {
            ""
        }
    }
        |${printContents(solution.contents, solution.path)}
        |You may need to add or adjust the @RandomParameters method
        """.trimMargin()
}

class SolutionDeadCode(
    val solution: Question.FlatFile,
    val amount: Int,
    val maximum: Int,
    val dead: List<Int>
) :
    ValidationFailed() {
    override val message = """
        |Solution contains $amount lines of dead code, more than the maximum of $maximum
        |Dead lines: ${dead.joinToString(", ")}
        |You may need to add or adjust the @RandomParameters method
        |${printContents(solution.contents, solution.path)}
        """.trimMargin()
}

class NoIncorrect(val solution: Question.FlatFile) : ValidationFailed() {
    override val message = """ No incorrect examples found or generated through mutation
        |Please add some using the @Incorrect annotation
        |${printContents(solution.contents, solution.path)}
        """.trimMargin()
}

class TooFewMutations(val solution: Question.FlatFile, val found: Int, val needed: Int) : ValidationFailed() {
    override val message = """ Too few incorrect mutations generated : found $found, needed $needed
        |Please reduce the required number or remove mutation suppressions
        |${printContents(solution.contents, solution.path)}
        """.trimMargin()
}

class TooMuchOutput(
    val contents: String,
    val path: String?,
    val size: Int,
    val maxSize: Int,
    val language: Question.Language
) : ValidationFailed() {
    override val message = """
        |Submission generated too much output($size > $maxSize):
        |${printContents(contents, path)}
        |Maybe reduce the number of tests using @Correct(minTestCount = NUM)
        """.trimMargin()
}

class IncorrectFailedLinting(
    val incorrect: Question.IncorrectFile, val correct: Question.FlatFile
) : ValidationFailed() {
    override val message: String
        get() {
            val contents = incorrect.mutation?.marked()?.contents ?: incorrect.contents
            return """
        |Incorrect code failed linting with ${'$'}{
        if (solution.language == Question.Language.kotlin) {
            "ktlint"
        } else {
            "checkstyle"
        }
        |${printContents(contents, incorrect.path ?: correct.path)}""".trimMargin()
        }
}

class IncorrectPassed(
    val incorrect: Question.IncorrectFile, val correct: Question.FlatFile
) : ValidationFailed() {
    override val message: String
        get() {
            val contents = incorrect.mutation?.marked()?.contents ?: incorrect.contents
            return """
        |Incorrect code passed the test suites :
        |${printContents(contents, incorrect.path ?: correct.path)}
        |If the code is incorrect, add an input to @FixedParameters to handle this case
        |${
                if (incorrect.mutation != null) {
                    "If the code is correct, you may need to disable this mutation using " +
                        "// ${incorrect.mutation.mutations.first().mutation.mutationType.suppressionComment()}"
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
        |Incorrect code eventually failed but required too many tests($testsRequired > $testsLimit)
        |${failingInput?.let { "We found failing inputs $failingInput" } ?: "We were unable to find a failing input"}
        |${printContents(contents, incorrect.path ?: correct.path)}
        |If the code is incorrect, add an input to @FixedParameters to handle this case
        |${
                if (incorrect.mutation != null) {
                    "If the code is correct, you may need to disable this mutation using " +
                        "// ${incorrect.mutation.mutations.first().mutation.mutationType.suppressionComment()}\n"
                } else {
                    ""
                }
            } You may also need to increase the test count using @Correct(maxTestCount = NUM) """.trimMargin()
        }
}

class IncorrectWrongReason(val incorrect: Question.IncorrectFile, val expected: String, val explanation: String) :
    ValidationFailed() {
    override val message: String
        get() {
            check(incorrect.mutation == null) { "Mutated sources failed for the wrong reason" }
            return """
        |Incorrect code failed but not for the reason we expected :
        |Expected: $expected
        |But Found : $explanation
        |${printContents(incorrect.contents, incorrect.path)}
        |Maybe check the argument to @Incorrect(reason = "reason")
        """.trimMargin()
        }
}

class WrongReasonPassed(val incorrect: Question.IncorrectFile, val expected: String) :
    ValidationFailed() {
    override val message: String
        get() {
            check(incorrect.mutation == null) { "Mutated sources failed for the wrong reason" }
            return """
        |Code expected to fail passed the test suite:
        |Expected: $expected
        |${printContents(incorrect.contents, incorrect.path)}
        |Maybe check the argument to @Incorrect(reason = "reason")
        """.trimMargin()
        }
}