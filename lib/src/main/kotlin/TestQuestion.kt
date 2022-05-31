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
import edu.illinois.cs.cs125.jeed.core.SnippetTransformationFailed
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jenisol.core.Settings
import edu.illinois.cs.cs125.jenisol.core.SubmissionDesignError
import org.jacoco.core.analysis.ICounter
import java.lang.reflect.InvocationTargetException

class CachePoisonedException(message: String) : RuntimeException(message)

@Suppress("ReturnCount", "LongMethod", "ComplexMethod", "LongParameterList")
suspend fun Question.test(
    contents: String,
    language: Question.Language,
    settings: Question.TestingSettings? = testingSettings
): TestResults {
    check(settings != null) { "No test settings provided" }

    val results = TestResults(language)

    // templateSubmission
    // compileSubmission
    // checkstyle || ktlint
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
    } catch (e: CompilationFailed) {
        return results
    } catch (e: CheckstyleFailed) {
        return results
    } catch (e: KtLintFailed) {
        return results
    }

    // checkCompiledSubmission
    val klassName = checkCompiledSubmission(compiledSubmission, contents, results) ?: return results

    // complexity
    try {
        results.complete.complexity = computeComplexity(contents, language)
        results.completedSteps.add(TestResults.Step.complexity)
    } catch (e: SnippetTransformationFailed) {
        // Special case when snippet transformation fails
        results.failed.checkCompiledSubmission = "Do not use return statements for this problem"
        results.failedSteps.add(TestResults.Step.checkCompiledSubmission)
    } catch (e: ComplexityFailed) {
        results.failed.complexity = e
        results.failedSteps.add(TestResults.Step.complexity)
    }

    // linecount
    results.complete.lineCount = computeLineCounts(contents, language)
    results.completedSteps.add(TestResults.Step.lineCount)

    // execution
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
    }.let {
        if (settings.disableLineCountLimit) {
            Integer.MAX_VALUE.toLong()
        } else {
            it
        }
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
    if (taskResults.killedClassInitializers.isNotEmpty()) {
        throw CachePoisonedException(taskResults.killedClassInitializers.joinToString(", "))
    }

    val threw = taskResults.returned?.threw ?: taskResults.threw
    val timeout = taskResults.timeout || threw is LineLimitExceeded
    results.taskResults = taskResults
    results.timeout = timeout

    if (!timeout && threw is ThreadDeath) {
        throw CachePoisonedException("ThreadDeath")
    }

    // checkExecutedSubmission
    if (!timeout && threw != null) {
        results.failedSteps.add(TestResults.Step.checkExecutedSubmission)
        when (threw) {
            is ClassNotFoundException -> results.failed.checkExecutedSubmission =
                "Class design error: could not find class $klass"
            is SubmissionDesignError -> results.failed.checkExecutedSubmission =
                "Class design error: ${threw.message}"
            is NoClassDefFoundError -> results.failed.checkExecutedSubmission =
                "Class design error: attempted to use unavailable class ${threw.message}"
            else -> {
                val actualException = when (threw) {
                    is InvocationTargetException -> threw.targetException ?: threw
                    else -> threw
                }
                results.failed.checkExecutedSubmission = "Testing generated an unexpected error: $actualException"
            }
        }
        return results
    }

    if (!checkExecutedSubmission(taskResults, results, language)) {
        return results
    }

    // executioncount
    val submissionExecutionCount = taskResults.pluginResult(LineTrace).linesRun
    val solutionExecutionCount = if (language == Question.Language.java) {
        validationResults?.executionCounts?.java ?: settings.solutionExecutionCount?.java
    } else {
        validationResults?.executionCounts?.kotlin ?: settings.solutionExecutionCount?.kotlin
    } ?: submissionExecutionCount

    results.complete.executionCount = TestResults.ExecutionCountComparison(
        solutionExecutionCount,
        submissionExecutionCount,
        solutionExecutionCount * control.executionFailureMultiplier!!
    )
    results.completedSteps.add(TestResults.Step.executioncount)

    // testing
    if (taskResults.returned == null) {
        results.failedSteps.add(TestResults.Step.testing)
        return results
    }

    results.addTestingResults(
        TestResults.TestingResult(
            taskResults.returned!!.map { it.asTestResult(compiledSubmission.source) },
            settings.testCount,
            taskResults.completed && !timeout
        )
    )

    // coverage
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