package edu.illinois.cs.cs125.questioner.lib

import edu.illinois.cs.cs125.jeed.core.*
import edu.illinois.cs.cs125.jeed.core.moshi.CompiledSourceResult
import edu.illinois.cs.cs125.jenisol.core.isPrivate
import edu.illinois.cs.cs125.jenisol.core.isStatic
import java.lang.reflect.InvocationTargetException

suspend fun Question.testTests(
    contents: String,
    language: Question.Language
): TestResults {
    compileAllValidatedSubmissions()

    val testKlass = "Test$klass"
    val results = TestResults(language)

    val compilationClassLoader = when (language) {
        Question.Language.java -> InvertingClassLoader(
            setOf(testKlass), compiledSolution.classLoader
        )
        Question.Language.kotlin -> InvertingClassLoader(
            setOf(testKlass, "${testKlass}Kt"), compiledSolution.classLoader
        )
    }
    val compiledSubmission = try {
        when (language) {
            Question.Language.java ->
                compileTestSuites(contents, compilationClassLoader, results)
            Question.Language.kotlin ->
                kompileSubmission(
                    contents,
                    InvertingClassLoader(setOf(testKlass, "${testKlass}Kt")),
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
    val klassName = checkCompiledTestSuite(compiledSubmission, results) ?: return results

    val testingLoaders =
        setOf(compiledSolution.classLoader) + validationSubmissions!!.map { it.compiled(this).classLoader }.toSet()
    val executionArguments = Sandbox.ExecutionArguments(
        timeout = testingSettings!!.timeout.toLong(),
        maxOutputLines = testingSettings!!.outputLimit,
        permissions = Question.SAFE_PERMISSIONS,
        returnTimeout = Question.DEFAULT_RETURN_TIMEOUT
    )
    for (testingLoader in testingLoaders) {
        val testingSuiteLoader = CopyableClassLoader.copy(compiledSubmission.classLoader, testingLoader)
        val taskResults = Sandbox.execute(
            testingSuiteLoader,
            executionArguments
        ) { (classLoader, _) ->
            return@execute try {
                classLoader.loadClass(klassName).getTestingMethod()!!.invoke(null)
                false
            } catch (e: InvocationTargetException) {
                true
            }
        }
    }
    return results
}

@Suppress("ThrowsCount")
suspend fun Question.compileTestSuites(
    contents: String,
    parentClassLoader: ClassLoader,
    testResults: TestResults
): CompiledSource {
    return try {
        val source = templateSubmission(contents).also {
            if (getTemplate(Question.Language.java) != null) {
                testResults.completedSteps.add(TestResults.Step.templateSubmission)
            }
        }
        val compiledSource = source.compile(
            CompilationArguments(
                parentClassLoader = parentClassLoader,
                parentFileManager = compiledSolution.fileManager,
                parameters = true
            )
        ).also {
            testResults.complete.compileSubmission = CompiledSourceResult(it)
            testResults.completedSteps.add(TestResults.Step.compileSubmission)
        }
        testResults.addCheckstyleResults(source.checkstyle(CheckstyleArguments(failOnError = false)))
        compiledSource
    } catch (e: TemplatingFailed) {
        testResults.failed.templateSubmission = e
        testResults.failedSteps.add(TestResults.Step.templateSubmission)
        throw e
    } catch (e: CheckstyleFailed) {
        testResults.failed.checkstyle = e
        testResults.failedSteps.add(TestResults.Step.checkstyle)
        throw e
    } catch (e: CompilationFailed) {
        testResults.failed.compileSubmission = e
        testResults.failedSteps.add(TestResults.Step.compileSubmission)
        throw e
    }
}

private fun Class<*>.getTestingMethod() = declaredMethods.find { testingMethod ->
    testingMethod.name == "test" && testingMethod.parameters.isEmpty() && testingMethod.isStatic() && !testingMethod.isPrivate()
}

fun Question.checkCompiledTestSuite(
    compiledTestSuite: CompiledSource,
    testResults: TestResults
): String? = compiledTestSuite.classLoader.definedClasses.topLevelClasses().let {
    val testKlass = "Test$klass"

    when {
        it.isEmpty() -> {
            testResults.failed.checkCompiledSubmission = "Test suites defined no classes"
            testResults.failedSteps.add(TestResults.Step.checkCompiledSubmission)
            return null
        }
        it.size > 1 -> {
            testResults.failed.checkCompiledSubmission = "Test suites defined multiple classes"
            testResults.failedSteps.add(TestResults.Step.checkCompiledSubmission)
            return null
        }
    }
    var klass = it.first()
    if (compiledTestSuite.source.type == Source.FileType.KOTLIN &&
        (solution.skipReceiver || solution.fauxStatic) &&
        klass == "${testKlass}Kt"
    ) {
        klass = "${testKlass}Kt"
    } else {
        if (klass != testKlass) {
            testResults.failed.checkCompiledSubmission =
                "Submission defines incorrect class: ${it.first()} != $compilationDefinedClass"
            testResults.failedSteps.add(TestResults.Step.checkCompiledSubmission)
            return null
        }
    }
    compiledTestSuite.classLoader.loadClass(klass).also { testingKlass ->
        testingKlass.getTestingMethod() ?: run {
            testResults.failed.checkCompiledSubmission =
                "Submission does not define a non-private static testing method name test accepting no arguments"
            testResults.failedSteps.add(TestResults.Step.checkCompiledSubmission)
            return null
        }
    }
    return klass
}

class CopyableClassLoader(override val bytecodeForClasses: Map<String, ByteArray>, parent: ClassLoader) :
    ClassLoader(parent), Sandbox.SandboxableClassLoader {
    override val classLoader: ClassLoader = this

    override fun loadClass(name: String): Class<*> {
        return if (name in bytecodeForClasses) {
            return defineClass(name, bytecodeForClasses[name]!!, 0, bytecodeForClasses[name]!!.size)
        } else {
            super.loadClass(name)
        }
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        return if (name in bytecodeForClasses) {
            return defineClass(name, bytecodeForClasses[name]!!, 0, bytecodeForClasses[name]!!.size)
        } else {
            super.loadClass(name, resolve)
        }
    }

    companion object {
        fun copy(classLoader: JeedClassLoader, parent: ClassLoader) =
            CopyableClassLoader(classLoader.bytecodeForClasses, parent)
    }
}
