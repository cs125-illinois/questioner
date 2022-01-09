package edu.illinois.cs.cs125.questioner.lib

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CheckstyleResults
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.ComplexityFailed
import edu.illinois.cs.cs125.jeed.core.KtLintFailed
import edu.illinois.cs.cs125.jeed.core.KtLintResults
import edu.illinois.cs.cs125.jeed.core.LineCounts
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jeed.core.getStackTraceForSource
import edu.illinois.cs.cs125.jeed.core.moshi.CompiledSourceResult
import edu.illinois.cs.cs125.jenisol.core.TestResult
import edu.illinois.cs.cs125.jenisol.core.safePrint
import edu.illinois.cs.cs125.jenisol.core.TestResult as JenisolTestResult

@JsonClass(generateAdapter = true)
data class TestResults(
    var language: Question.Language,
    val completedSteps: MutableSet<Step> = mutableSetOf(),
    val complete: CompletedTasks = CompletedTasks(),
    val failedSteps: MutableSet<Step> = mutableSetOf(),
    val failed: FailedTasks = FailedTasks(),
    val skippedSteps: MutableSet<Step> = mutableSetOf(),
    var timeout: Boolean = false,
    @Transient
    var taskResults: Sandbox.TaskResults<*>? = null
) {
    var completed: Boolean = false
    var succeeded: Boolean = false
    var failedLinting: Boolean? = null
    var failureCount: Int? = null

    fun tests() = complete.testing?.tests

    @Suppress("EnumNaming", "EnumEntryName")
    enum class Step {
        templateSubmission,
        checkstyle,
        ktlint,
        compileSubmission,
        checkSubmission,
        complexity,
        lineCount,
        coverage,
        executioncount,
        test,
    }

    @JsonClass(generateAdapter = true)
    data class CompletedTasks(
        var checkstyle: CheckstyleResults? = null,
        var ktlint: KtLintResults? = null,
        var compileSubmission: CompiledSourceResult? = null,
        var complexity: ComplexityComparison? = null,
        var lineCount: LineCountComparison? = null,
        var coverage: CoverageComparison? = null,
        var executionCount: ExecutionCountComparison? = null,
        var testing: TestingResult? = null,
    )

    @JsonClass(generateAdapter = true)
    data class ComplexityComparison(
        val solution: Int,
        val submission: Int,
        val limit: Int,
        val increase: Int = submission - solution,
        val failed: Boolean = increase > limit
    )

    @JsonClass(generateAdapter = true)
    data class LineCountComparison(
        val solution: LineCounts,
        val submission: LineCounts,
        val limit: Int,
        val allowance: Int,
        val increase: Int = submission.source - solution.source,
        val failed: Boolean = increase > allowance && submission.source > limit
    )

    @JsonClass(generateAdapter = true)
    data class CoverageComparison(
        val solution: LineCoverage,
        val submission: LineCoverage,
        val missed: List<Int>,
        val limit: Int,
        val increase: Int = submission.missed - solution.missed,
        val failed: Boolean = increase > limit
    ) {
        @JsonClass(generateAdapter = true)
        data class LineCoverage(val covered: Int, val total: Int, val missed: Int = total - covered) {
            init {
                check(covered <= total) { "Invalid coverage result" }
            }
        }
    }

    @JsonClass(generateAdapter = true)
    data class ExecutionCountComparison(
        val solution: Long,
        val submission: Long,
        val limit: Long,
        val increase: Long = submission - solution,
        val failed: Boolean = submission >= limit
    )

    @JsonClass(generateAdapter = true)
    data class TestingResult(
        val tests: List<TestResult>,
        val testCount: Int,
        val completed: Boolean,
        val passed: Boolean = completed && tests.none { !it.passed }
    ) {
        @JsonClass(generateAdapter = true)
        data class TestResult(
            val name: String,
            val passed: Boolean,
            val message: String? = null,
            val arguments: String? = null,
            val expected: String? = null,
            val found: String? = null,
            val explanation: String? = null,
            val output: String? = null,
            val complexity: Int? = null,
            @Transient val jenisol: JenisolTestResult<*, *>? = null,
            val submissionStackTrace: String? = null
        )
    }

    @JsonClass(generateAdapter = true)
    data class FailedTasks(
        var templateSubmission: TemplatingFailed? = null,
        var checkstyle: CheckstyleFailed? = null,
        var ktlint: KtLintFailed? = null,
        var compileSubmission: CompilationFailed? = null,
        var checkSubmission: String? = null,
        var complexity: ComplexityFailed? = null
    )

    fun addCheckstyleResults(checkstyle: CheckstyleResults) {
        completedSteps.add(Step.checkstyle)
        complete.checkstyle = checkstyle
        failedLinting = checkstyle.errors.isNotEmpty()
    }

    fun addKtlintResults(ktlint: KtLintResults) {
        completedSteps.add(Step.ktlint)
        complete.ktlint = ktlint
        failedLinting = ktlint.errors.isNotEmpty()
    }

    fun addTestingResults(testing: TestingResult) {
        completedSteps.add(Step.test)
        complete.testing = testing
        completed = true
        succeeded = !timeout && testing.passed == true && testing.completed == true
        failureCount = testing.tests.filter { !it.passed }.size
    }

    val summary: String
        get() = if (failed.templateSubmission != null) {
            "Templating failed${failed.templateSubmission?.message?.let { ": $it" } ?: ""}"
        } else if (failed.compileSubmission != null) {
            "Compiling submission failed${failed.compileSubmission?.let { ": $it" } ?: ""}"
        } else if (failed.checkstyle != null) {
            "Checkstyle failed:${failed.checkstyle?.let { ": $it" } ?: ""}"
        } else if (failed.ktlint != null) {
            "Ktlint failed:${failed.ktlint?.let { ": $it" } ?: ""}"
        } else if (failed.complexity != null) {
            "Computing complexity failed: ${failed.complexity!!.message ?: "unknown error"}"
        } else if (failed.checkSubmission != null) {
            "Checking submission failed: ${failed.checkSubmission}"
        } else if (timeout) {
            "Testing timed out"
        } else if (complete.testing?.passed == false) {
            "Testing failed: ${complete.testing!!.tests.find { !it.passed }!!.explanation}"
        } else if (!completed) {
            "Didn't complete all required tests"
        } else {
            check(succeeded)
            "Passed"
        }

    fun toJson(): String = moshi.adapter(TestResults::class.java).toJson(this)
}

fun TestResult<*, *>.asTestResult(source: Source) = TestResults.TestingResult.TestResult(
    solutionExecutable.name,
    succeeded,
    verifierThrew?.message,
    parameters.toString(),
    @Suppress("TooGenericExceptionCaught")
    solution.returned?.safePrint(),
    submission.returned?.safePrint(),
    if (!succeeded) {
        explain()
    } else {
        null
    },
    submission.stdout,
    complexity,
    this,
    submission.threw?.getStackTraceForSource(
        source,
        boundaries = listOf(
            "at edu.illinois.cs.cs125.jenisol.core.TestRunner",
            "at jdk.internal.reflect.",
            "at java.base"
        )
    )
)
