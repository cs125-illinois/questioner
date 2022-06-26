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
data class TestTestResults(
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

    @Suppress("EnumNaming", "EnumEntryName")
    enum class Step {
        templateSubmission,
        compileSubmission,
        checkstyle,
        ktlint,
        checkCompiledSubmission,
    }

    @JsonClass(generateAdapter = true)
    data class CompletedTasks(
        // templateSubmission doesn't complete
        var compileSubmission: CompiledSourceResult? = null,
        var checkstyle: CheckstyleResults? = null,
        var ktlint: KtLintResults? = null,
        // checkCompiledSubmission doesn't complete
    )

    @JsonClass(generateAdapter = true)
    data class FailedTasks(
        var templateSubmission: TemplatingFailed? = null,
        var compileSubmission: CompilationFailed? = null,
        var checkstyle: CheckstyleFailed? = null,
        var ktlint: KtLintFailed? = null,
        var checkCompiledSubmission: String? = null,
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

    val summary: String
        get() = if (failed.templateSubmission != null) {
            "Templating failed${failed.templateSubmission?.message?.let { ": $it" } ?: ""}"
        } else if (failed.compileSubmission != null) {
            "Compiling submission failed${failed.compileSubmission?.let { ": $it" } ?: ""}"
        } else if (failed.checkstyle != null) {
            "Checkstyle failed:${failed.checkstyle?.let { ": $it" } ?: ""}"
        } else if (failed.ktlint != null) {
            "Ktlint failed:${failed.ktlint?.let { ": $it" } ?: ""}"
        } else if (failed.checkCompiledSubmission != null) {
            "Checking submission failed: ${failed.checkCompiledSubmission}"
        } else if (timeout) {
            "Testing timed out"
        } else if (!completed) {
            "Didn't complete all required tests: $failedSteps"
        } else {
            check(succeeded)
            "Passed"
        }

    @Suppress("unused")
    fun toJson(): String = moshi.adapter(TestTestResults::class.java).toJson(this)
}