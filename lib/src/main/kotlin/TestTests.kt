package edu.illinois.cs.cs125.questioner.lib

import edu.illinois.cs.cs125.jeed.core.*
import edu.illinois.cs.cs125.jeed.core.moshi.CompiledSourceResult
import edu.illinois.cs.cs125.jenisol.core.isPackagePrivate
import edu.illinois.cs.cs125.jenisol.core.isPrivate
import edu.illinois.cs.cs125.jenisol.core.isPublic
import edu.illinois.cs.cs125.jenisol.core.isStatic
import org.objectweb.asm.*
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
            setOf(testKlass), compiledSolutionForTesting
        )
        Question.Language.kotlin -> InvertingClassLoader(
            setOf(testKlass, "${testKlass}Kt"), compiledSolutionForTesting
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
        setOf(compiledSolutionForTesting) + validationSubmissions!!.map { it.compiled(this) }
            .toSet()
    val executionArguments = Sandbox.ExecutionArguments(
        timeout = testingSettings!!.timeout.toLong(),
        maxOutputLines = testingSettings!!.outputLimit,
        permissions = Question.SAFE_PERMISSIONS,
        returnTimeout = Question.DEFAULT_RETURN_TIMEOUT
    )

    val testingClass = compiledSubmission.classLoader.loadClass(klassName)
    val staticTestingMethod = testingClass.getTestingMethod()!!.isStatic()
    if (staticTestingMethod) {
        check(testingClass.declaredConstructors.find { it.parameters.isEmpty() } != null) {
            "Non-static testing method needs an empty constructor"
        }
    }

    for (testingLoader in testingLoaders) {
        val testingSuiteLoader = CopyableClassLoader.copy(compiledSubmission.classLoader, testingLoader)
        val taskResults = Sandbox.execute(
            testingSuiteLoader,
            executionArguments
        ) { (classLoader, _) ->
            return@execute try {
                classLoader.loadClass(klassName).getTestingMethod()!!.also { method ->
                    method.isAccessible = true
                    if (staticTestingMethod) {
                        method.invoke(null)
                    } else {
                        method.invoke(
                            classLoader.loadClass(klassName).declaredConstructors.find { it.parameters.isEmpty() }!!
                                .newInstance()
                        )
                    }
                }
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }
        val succeeded = taskResults.threw == null
        println("$succeeded ${taskResults.threw}")
    }
    return results
}

fun Question.templateTestSuites(contents: String, language: Question.Language = Question.Language.java): Source {
    val template = when (type) {
        Question.Type.KLASS -> null
        Question.Type.METHOD -> {
            when (language) {
                Question.Language.java -> {
                    """public class Test${klass} extends $klass {
  {{{ contents }}}
}
"""
                }
                Question.Language.kotlin -> {
                    """class Test${klass} : $klass {
  {{{ contents }}}
}"""
                }
            }
        }
        Question.Type.SNIPPET -> error("Testing not supported for snippets")
    }

    val fileName = "Test${klass}.${language.extension()}"
    return if (template == null) {
        Source(mapOf(fileName to contents))
    } else {
        Source.fromTemplates(
            mapOf(fileName to contents.trimEnd()),
            mapOf("$fileName.hbs" to template)
        )
    }
}

@Suppress("ThrowsCount")
suspend fun Question.compileTestSuites(
    contents: String,
    parentClassLoader: ClassLoader,
    testResults: TestResults
): CompiledSource {
    return try {
        val source = templateTestSuites(contents).also {
            if (getTemplate(Question.Language.java) != null) {
                testResults.completedSteps.add(TestResults.Step.templateSubmission)
            }
        }
        println(source.contents)
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
    testingMethod.name == "test" && testingMethod.parameters.isEmpty() && !testingMethod.isPrivate()
}

fun Question.checkCompiledTestSuite(
    compiledTestSuite: CompiledSource,
    testResults: TestResults
): String? = compiledTestSuite.classLoader.definedClasses.topLevelClasses().let {
    val testKlass = "Test$klass"

    when {
        it.isEmpty() -> {
            testResults.failed.checkCompiledSubmission = "Test suite defined no classes"
            testResults.failedSteps.add(TestResults.Step.checkCompiledSubmission)
            return null
        }
        it.size > 1 -> {
            testResults.failed.checkCompiledSubmission = "Test suite defined multiple classes"
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
                "Test suite defines incorrect class: ${it.first()} != $testKlass"
            testResults.failedSteps.add(TestResults.Step.checkCompiledSubmission)
            return null
        }
    }
    compiledTestSuite.classLoader.loadClass(klass).also { testingKlass ->
        testingKlass.getTestingMethod() ?: run {
            testResults.failed.checkCompiledSubmission =
                "Test suite does not define a non-private static testing method named test accepting no arguments"
            testResults.failedSteps.add(TestResults.Step.checkCompiledSubmission)
            return null
        }
    }
    return klass
}

class CopyableClassLoader(override val bytecodeForClasses: Map<String, ByteArray>, parent: ClassLoader) :
    ClassLoader(parent), Sandbox.SandboxableClassLoader {
    override val classLoader: ClassLoader = this

    override fun findClass(name: String): Class<*> {
        return if (name in bytecodeForClasses) {
            return defineClass(name, bytecodeForClasses[name]!!, 0, bytecodeForClasses[name]!!.size)
        } else {
            super.findClass(name)
        }
    }

    companion object {
        fun copy(classLoader: JeedClassLoader, parent: ClassLoader) =
            CopyableClassLoader(classLoader.bytecodeForClasses, parent)
    }
}

fun Question.fixTestingMethods(classLoader: JeedClassLoader): ClassLoader {
    val methodsToOpen = classLoader.loadClass(klass).declaredMethods.filter { it.isPackagePrivate() }.map { it.name }
    val classReader = ClassReader(classLoader.bytecodeForClasses[klass])
    val classWriter = ClassWriter(classReader, 0)
    val openingVisitor = object : ClassVisitor(Opcodes.ASM8, classWriter) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            return if (name in methodsToOpen) {
                super.visitMethod(access + Opcodes.ACC_PUBLIC, name, descriptor, signature, exceptions)
            } else {
                super.visitMethod(access, name, descriptor, signature, exceptions)
            }
        }
    }
    classReader.accept(openingVisitor, 0)
    return CopyableClassLoader(mapOf(klass to classWriter.toByteArray()), classLoader.parent)
}
