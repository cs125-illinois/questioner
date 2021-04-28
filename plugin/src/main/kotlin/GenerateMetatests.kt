package edu.illinois.cs.cs125.questioner.plugin

import edu.illinois.cs.cs125.questioner.lib.Question
import edu.illinois.cs.cs125.questioner.lib.loadFromFiles
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import java.io.File

@Suppress("unused")
open class GenerateMetatests : DefaultTask() {
    init {
        group = "Build"
        description = "Generate question metatests from JSON."
    }

    @TaskAction
    fun generate() {
        val testRoot = project.file("src/test/kotlin")
        if (testRoot.exists()) {
            require(testRoot.isDirectory) { "test generation destination must be a directory" }
        } else {
            testRoot.mkdirs()
        }
        loadFromFiles(
            File(project.buildDir, "questioner/questions.json"),
            File(project.buildDir, "resources/main")
        ).values.organizeTests().first().let { (packageName, questions) ->
            val klass = if (questions.size == 1) {
                "Test${questions.first().klass}"
            } else {
                "TestQuestions"
            }
            val name = "$klass.kt"

            testRoot.resolve(packageName.replace(".", "/")).resolve(name).also {
                it.parentFile.mkdirs()
                it.writeText(questions.generateTest(packageName, klass))
            }
        }
    }
}

fun Collection<Question>.organizeTests() = map {
    it.metadata.packageName.packageNames()
}.flatten().distinct().sortedBy { it.split(".").size }.let { packageNames ->
    val byPackage: MutableMap<String, List<Question>> = mutableMapOf()
    packageNames.forEach { name ->
        val depth = name.split(".").size
        check(depth >= 1) { "Invalid depth when organizing questions" }
        val previous = if (depth == 1) {
            null
        } else {
            name.split(".").subList(0, depth - 1).joinToString(".")
        }
        val packageQuestions = filter { it.metadata.packageName.startsWith(name) }
        if (previous != null && byPackage[previous]?.size == packageQuestions.size) {
            byPackage.remove(previous)
        }
        byPackage[name] = packageQuestions
    }
    byPackage
}.entries.sortedBy { it.key.length }

fun List<Question>.generateTest(packageName: String, klass: String): String {
    val testBlock = filter { it.metadata.packageName.startsWith(packageName) }
        .sortedBy { it.name }
        .joinToString(separator = "\n") {
            """    "${it.name} (${it.metadata.packageName}) should validate" {
        validator.validate("${it.name}", verbose = false, force = testCase.isFocused())
    }"""
        }
    val packageNameBlock = if (packageName.isNotEmpty()) {
        "package $packageName\n\n"
    } else {
        ""
    }
    return """${packageNameBlock}import edu.illinois.cs.cs125.questioner.lib.Validator
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.isFocused
import java.nio.file.Path

/*
 * THIS FILE IS AUTOGENERATED. DO NOT EDIT BY HAND.
 *
 * It should be automatically regenerated each time you run the question test suite.
 */

/* ktlint-disable max-line-length */

private val validator =
    Validator(Path.of(object {}::class.java.getResource("/questions.json")!!.toURI()).parent.toFile(), seed = 124)

@Suppress("MaxLineLength", "LargeClass")
class $klass : StringSpec({
$testBlock
})

/* ktlint-enable max-line-length */
// AUTOGENERATED
"""
}

fun String.packageNames() = split(".").let {
    mutableSetOf<String>().also { all ->
        for (i in 0..it.size) {
            all.add(it.subList(0, i).joinToString("."))
        }
    }.toSet()
}
