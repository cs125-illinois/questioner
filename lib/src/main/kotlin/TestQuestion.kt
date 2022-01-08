package edu.illinois.cs.cs125.questioner.lib

import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.ComplexityFailed
import edu.illinois.cs.cs125.jeed.core.ConfiguredSandboxPlugin
import edu.illinois.cs.cs125.jeed.core.Jacoco
import edu.illinois.cs.cs125.jeed.core.KtLintFailed
import edu.illinois.cs.cs125.jeed.core.LineLimitExceeded
import edu.illinois.cs.cs125.jeed.core.LineTrace
import edu.illinois.cs.cs125.jeed.core.LineTraceArguments
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jenisol.core.Settings
import edu.illinois.cs.cs125.jenisol.core.SubmissionDesignError
import org.jacoco.core.analysis.ICounter
import java.lang.reflect.InvocationTargetException

@Suppress("ReturnCount", "LongMethod", "ComplexMethod", "LongParameterList")
suspend fun Question.test(
    contents: String,
    language: Question.Language,
    settings: Question.TestingSettings? = testingSettings
): TestResults {
    check(settings != null) { "No test settings provided" }

    val results = TestResults(language)

    @Suppress("SwallowedException")
    val compiledSubmission = try {
        when (language) {
            Question.Language.java ->
                compileSubmission(
                    contents,
                    InvertingClassLoader(setOf(klass)),
                    results
                )
            Question.Language.kotlin ->
                kompileSubmission(
                    contents,
                    InvertingClassLoader(setOf(klass, "${klass}Kt")),
                    results
                )
        }
    } catch (e: TemplatingFailed) {
        return results
    } catch (e: CheckstyleFailed) {
        return results
    } catch (e: CompilationFailed) {
        return results
    } catch (e: KtLintFailed) {
        return results
    }

    val klassName = checkCompiledSubmission(compiledSubmission, contents, results) ?: return results

    try {
        results.complete.complexity = computeComplexity(contents, language)
        results.completedSteps.add(TestResults.Step.complexity)
    } catch (e: ComplexityFailed) {
        results.failed.complexity = e
        results.failedSteps.add(TestResults.Step.complexity)
    }

    results.complete.lineCount = computeLineCounts(contents, language)
    results.completedSteps.add(TestResults.Step.lineCount)

    val classLoaderConfiguration = when (language) {
        Question.Language.java -> settings.javaWhitelist
        Question.Language.kotlin -> settings.kotlinWhitelist
    }?.let {
        Sandbox.ClassLoaderConfiguration(isWhiteList = true, whitelistedClasses = it)
    } ?: Sandbox.ClassLoaderConfiguration()

    val jenisolSettings = Settings(
        seed = settings.seed,
        shrink = settings.shrink,
        overrideTotalCount = settings.testCount,
        startMultipleCount = (settings.testCount / 2).coerceAtMost(
            Question.MAX_START_MULTIPLE_COUNT
        )
    )

    val executionArguments = Sandbox.ExecutionArguments(
        timeout = settings.timeout.toLong(),
        classLoaderConfiguration = classLoaderConfiguration,
        maxOutputLines = settings.outputLimit,
        permissions = Question.SAFE_PERMISSIONS,
        returnTimeout = Question.DEFAULT_RETURN_TIMEOUT
    )
    val lineCountLimit = when (language) {
        Question.Language.java -> settings.executionCountLimit.java
        Question.Language.kotlin -> settings.executionCountLimit.kotlin!!
    }
    val plugins = listOf(
        ConfiguredSandboxPlugin(Jacoco, Unit),
        ConfiguredSandboxPlugin(
            LineTrace,
            LineTraceArguments(
                runLineLimit = lineCountLimit,
                recordedLineLimit = 0,
                runLineLimitExceededAction = LineTraceArguments.RunLineLimitAction.THROW_ERROR
            )
        )
    )
    val taskResults = Sandbox.execute(
        compiledSubmission.classLoader,
        executionArguments,
        configuredPlugins = plugins
    ) { (classLoader, _) ->
        try {
            solution.submission(classLoader.loadClass(klassName)).test(jenisolSettings, ::captureJeedOutput)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    val threw = taskResults.returned?.threw ?: taskResults.threw
    val timeout = taskResults.timeout || threw is LineLimitExceeded
    results.taskResults = taskResults
    results.timeout = timeout

    if (!timeout && threw != null) {
        results.failedSteps.add(TestResults.Step.checkSubmission)
        when (threw) {
            is ClassNotFoundException -> results.failed.checkSubmission =
                "Class design error: could not find class $klass"
            is SubmissionDesignError -> results.failed.checkSubmission =
                "Class design error: ${threw.message}"
            is NoClassDefFoundError -> results.failed.checkSubmission =
                "Class design error: attempted to use unavailable class ${threw.message}"
            else -> {
                val actualException = when (threw) {
                    is InvocationTargetException -> threw.targetException ?: threw
                    else -> threw
                }
                results.failed.checkSubmission = "Testing generated an unexpected error: $actualException"
            }
        }
        return results
    }

    val submissionExecutionCount = taskResults.pluginResult(LineTrace).linesRun
    val solutionExecutionCount = if (language == Question.Language.java) {
        validationResults?.executionCounts?.java ?: settings.solutionExecutionCount?.java
    } else {
        validationResults?.executionCounts?.kotlin ?: settings.solutionExecutionCount?.kotlin
    } ?: submissionExecutionCount

    results.complete.executionCount = TestResults.ExecutionCountComparison(
        solutionExecutionCount,
        submissionExecutionCount,
        solutionExecutionCount * control.executionMultiplier!!
    )
    results.completedSteps.add(TestResults.Step.executioncount)

    if (!checkExecutedSubmission(taskResults, results, language)) {
        return results
    }

    if (taskResults.returned == null) {
        results.failedSteps.add(TestResults.Step.test)
        return results
    }

    results.addTestingResults(
        TestResults.TestingResult(
            taskResults.returned!!.map { it.asTestResult(compiledSubmission.source) },
            settings.testCount,
            taskResults.completed && !timeout
        )
    )

    val coverage = taskResults.pluginResult(Jacoco).classes.find { it.name == klassName }!!
    val missed = (coverage.firstLine..coverage.lastLine).toList().filter { line ->
        coverage.getLine(line).status == ICounter.NOT_COVERED || coverage.getLine(line).status == ICounter.PARTLY_COVERED
    }.map { line ->
        line - when (language) {
            Question.Language.java -> javaTemplateAddsLines
            Question.Language.kotlin -> kotlinTemplateAddsLines
        }
    }
    val submissionCoverage = TestResults.CoverageComparison.LineCoverage(
        coverage.lineCounter.totalCount - missed.size,
        coverage.lineCounter.totalCount
    )
    val solutionCoverage =
        validationResults?.solutionCoverage ?: settings.solutionCoverage ?: submissionCoverage

    results.complete.coverage =
        TestResults.CoverageComparison(solutionCoverage, submissionCoverage, missed, control.maxDeadCode!!)
    results.completedSteps.add(TestResults.Step.coverage)

    return results
}