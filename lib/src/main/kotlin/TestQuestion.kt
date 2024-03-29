package edu.illinois.cs.cs125.questioner.lib

import com.beyondgrader.resourceagent.StaticFailureDetection
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.ComplexityFailed
import edu.illinois.cs.cs125.jeed.core.ConfiguredSandboxPlugin
import edu.illinois.cs.cs125.jeed.core.Jacoco
import edu.illinois.cs.cs125.jeed.core.KtLintFailed
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.SnippetTransformationFailed
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jenisol.core.Settings
import edu.illinois.cs.cs125.jenisol.core.SubmissionDesignError
import org.jacoco.core.analysis.ICounter
import org.objectweb.asm.Type
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class CachePoisonedException(message: String) : RuntimeException(message)

private const val MAX_INDIVIDUAL_ALLOCATION_BYTES: Long = 1024 * 1024
private const val MIN_ALLOCATION_FAILURE_BYTES: Long = 2 * 1024 // Account for nondeterminism due to JIT
private const val MIN_ALLOCATION_LIMIT_BYTES: Long = 1024 * 1024 // Leave room for string concat in println debugging

@Suppress("ReturnCount", "LongMethod", "ComplexMethod", "LongParameterList")
suspend fun Question.test(
    contents: String,
    language: Question.Language,
    settings: Question.TestingSettings? = testingSettings,
    isSolution: Boolean = false
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
    val klassName = checkCompiledSubmission(compiledSubmission, results) ?: return results

    // complexity
    try {
        results.complete.complexity = computeComplexity(contents, language)
        results.completedSteps.add(TestResults.Step.complexity)
    } catch (e: SnippetTransformationFailed) {
        // Special case when snippet transformation fails
        results.failed.checkCompiledSubmission = "Do not use return statements for this problem"
        results.failedSteps.add(TestResults.Step.checkCompiledSubmission)
        return results
    } catch (e: MaxComplexityExceeded) {
        results.failed.complexity = e.message
        results.failedSteps.add(TestResults.Step.complexity)
        return results
    } catch (e: ComplexityFailed) {
        results.failed.complexity = "Unable to compute complexity for this submission:\n" + e.errors.joinToString(", ")
        results.failedSteps.add(TestResults.Step.complexity)
        return results
    }

    // features
    try {
        results.complete.features = computeFeatures(contents, klassName, language)
        results.completedSteps.add(TestResults.Step.features)
    } catch (e: FeatureCheckException) {
        results.failed.features = e.message!!
        results.failedSteps.add(TestResults.Step.features)
        return results
    } catch (e: Exception) {
        results.failed.features = e.message ?: "Unknown features failure"
        results.failedSteps.add(TestResults.Step.features)
        return results
    }

    // linecount
    try {
        results.complete.lineCount = computeLineCounts(contents, language)
        results.completedSteps.add(TestResults.Step.lineCount)
    } catch (e: MaxLineCountExceeded) {
        results.failed.lineCount = e.message!!
        results.failedSteps.add(TestResults.Step.lineCount)
        return results
    } catch (e: Exception) {
        results.failed.lineCount = e.message ?: "Unknown line count failure"
        results.failedSteps.add(TestResults.Step.lineCount)
        return results
    }

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
        testCount = settings.testCount,
        minTestCount = settings.minTestCount,
        maxTestCount = settings.maxTestCount
    )
    val systemInStream = BumpingInputStream()

    val executionArguments = Sandbox.ExecutionArguments(
        timeout = settings.timeout.toLong(),
        classLoaderConfiguration = classLoaderConfiguration,
        maxOutputLines = settings.outputLimit,
        permissions = Question.SAFE_PERMISSIONS,
        returnTimeout = Question.DEFAULT_RETURN_TIMEOUT,
        systemInStream = systemInStream
    )
    val lineCountLimit = when (language) {
        Question.Language.java -> settings.executionCountLimit.java
        Question.Language.kotlin -> settings.executionCountLimit.kotlin!!
    }.takeIf { !settings.disableLineCountLimit }
    val allocationLimit = when (language) {
        Question.Language.java -> settings.allocationLimit?.java
        Question.Language.kotlin -> settings.allocationLimit?.kotlin
    }?.takeIf { !settings.disableAllocationLimit }?.coerceAtLeast(MIN_ALLOCATION_LIMIT_BYTES)
    val plugins = listOf(
        ConfiguredSandboxPlugin(Jacoco, Unit),
        ConfiguredSandboxPlugin(
            ResourceMonitoring,
            ResourceMonitoringArguments(
                submissionLineLimit = lineCountLimit,
                allocatedMemoryLimit = allocationLimit,
                individualAllocationLimit = MAX_INDIVIDUAL_ALLOCATION_BYTES
            )
        )
    )

    val captureOutputControlInput = bindJeedCaptureOutputControlInput(systemInStream, settings.perTestOutputLimit)
    val taskResults = Sandbox.execute(
        compiledSubmission.classLoader,
        executionArguments,
        configuredPlugins = plugins
    ) { (classLoader, _) ->
        try {
            solution.submission(classLoader.loadClass(klassName)).test(jenisolSettings, captureOutputControlInput)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }
    val failedClassInitializers = StaticFailureDetection.pollStaticInitializationFailures()
    if (failedClassInitializers.isNotEmpty()) {
        throw CachePoisonedException(failedClassInitializers.joinToString(", ") { it.clazz.name })
    }

    val threw = taskResults.returned?.threw ?: taskResults.threw
    val timeout = taskResults.timeout
    if (!timeout && threw is ThreadDeath) {
        throw CachePoisonedException("ThreadDeath")
    }

    results.taskResults = taskResults
    results.timeout = timeout
    val resourceUsage = taskResults.pluginResult(ResourceMonitoring)
    results.resourceMonitoringResults = resourceUsage

    // checkExecutedSubmission
    if (!timeout && threw != null) {
        results.failedSteps.add(TestResults.Step.checkExecutedSubmission)
        when (threw) {
            is ClassNotFoundException -> results.failed.checkExecutedSubmission =
                "Class design error:\n  Could not find class $klass"
            is SubmissionDesignError -> results.failed.checkExecutedSubmission =
                "Class design error:\n  ${threw.message}"
            is NoClassDefFoundError -> results.failed.checkExecutedSubmission =
                "Class design error:\n  Attempted to use unavailable class ${threw.message}"
            is OutOfMemoryError -> results.failed.checkExecutedSubmission =
                "Allocated too much memory: ${threw.message}, already used ${resourceUsage.allocatedMemory} bytes"
            // TODO: Adjust Jenisol to let OutOfMemoryError escape the testing loop or remove this case
            // (currently it will never be reached)
            else -> {
                val actualException = when (threw) {
                    is InvocationTargetException -> threw.targetException ?: threw
                    else -> threw
                }
                results.failed.checkExecutedSubmission = "Testing generated an unexpected error: $actualException\n${actualException.stackTraceToString()}"
            }
        }
        return results
    }

    if (!checkExecutedSubmission(taskResults, results, language)) {
        return results
    }

    // testing
    if (taskResults.returned == null) {
        results.failedSteps.add(TestResults.Step.testing)
        return results
    }

    val testingResults = taskResults.returned!!.map { it.asTestResult(compiledSubmission.source) }
    results.addTestingResults(
        TestResults.TestingResult(
            testingResults,
            taskResults.returned!!.settings.testCount,
            taskResults.completed && !timeout,
            !taskResults.returned!!.finishedReceivers
        )
    )

    fun List<TestResults.TestingResult.TestResult>.recursiveMethods() = asSequence().filter {
        it.submissionResourceUsage!!.invokedRecursiveFunctions.isNotEmpty()
    }.map {
        it.jenisol!!.solutionExecutable
    }.filterIsInstance<Method>().map {
        ResourceMonitoringResults.MethodInfo(klassName, it.name, Type.getMethodDescriptor(it))
    }.toSet()

    val expectedRecursiveMethods = if (isSolution) {
        testingResults.recursiveMethods()
    } else {
        if (language == Question.Language.java) {
            validationResults?.solutionRecursiveMethods?.java
                ?: settings.solutionRecursiveMethods?.java
        } else {
            validationResults?.solutionRecursiveMethods?.kotlin
                ?: settings.solutionRecursiveMethods?.kotlin
        }
    }

    check(expectedRecursiveMethods != null)

    if (isSolution) {
        results.foundRecursiveMethods = expectedRecursiveMethods
    }

    val missingRecursiveMethods = expectedRecursiveMethods - testingResults.recursiveMethods()
    if (missingRecursiveMethods.isNotEmpty()) {
        results.failed.checkExecutedSubmission =
            "Method ${missingRecursiveMethods.first().methodName} was not implemented recursively"
        results.failedSteps.add(TestResults.Step.checkExecutedSubmission)
        return results
    }

    results.completedSteps.add(TestResults.Step.checkExecutedSubmission)

    // executioncount
    val submissionExecutionCount = resourceUsage.submissionLines
    val solutionExecutionCount = if (language == Question.Language.java) {
        validationResults?.executionCounts?.java ?: settings.solutionExecutionCount?.java
    } else {
        validationResults?.executionCounts?.kotlin ?: settings.solutionExecutionCount?.kotlin
    } ?: submissionExecutionCount

    results.complete.executionCount = TestResults.ResourceUsageComparison(
        solutionExecutionCount,
        submissionExecutionCount,
        solutionExecutionCount * control.executionFailureMultiplier!!
    )
    results.completedSteps.add(TestResults.Step.executioncount)

    // memoryAllocation
    val submissionAllocation = resourceUsage.allocatedMemory
    val solutionAllocation = if (language == Question.Language.java) {
        validationResults?.memoryAllocation?.java ?: settings.solutionAllocation?.java
    } else {
        validationResults?.memoryAllocation?.kotlin ?: settings.solutionAllocation?.kotlin
    } ?: submissionAllocation

    results.complete.memoryAllocation = TestResults.ResourceUsageComparison(
        solutionAllocation,
        submissionAllocation,
        (solutionAllocation * control.allocationFailureMultiplier!!).coerceAtLeast(MIN_ALLOCATION_FAILURE_BYTES)
    )
    results.completedSteps.add(TestResults.Step.memoryAllocation)


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
