package edu.illinois.cs.cs125.questioner.lib

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path
import kotlin.system.measureTimeMillis

private val validator = Validator(
    Path.of(object {}::class.java.getResource("/questions.json")!!.toURI()).toFile(),
    "",
    seed = 124
)

const val EMPTY_SUITE = """
public class TestQuestion {
  public static void test() {
  }
}
"""
class TestTestTesting : StringSpec({
    "should test test suites for classes" {
        val (question) = validator.validate("Add One Class", force = true, testing = true).also { (question, report) ->
            question.validated shouldBe true
            report shouldNotBe null
        }
        question.testTests(EMPTY_SUITE, Question.Language.java).also { results ->
            results.failedSteps.size shouldBe 0
        }
        question.testTests("""
public class TestQuestion {
  public static void test() {
    assert(Question.addOne(0) == 1);
  }
}""", Question.Language.java).also { results ->
            results.failedSteps.size shouldBe 0
        }
    }
    "original incorrect examples should recover and fail" {
        val (question) = validator.validate("Add One Class", force = true, testing = true).also { (question, report) ->
            question.validated shouldBe true
            report shouldNotBe null
        }

        val compileTime = measureTimeMillis {
            question.compileAllValidatedSubmissions()
        }
        val recompileTime = measureTimeMillis {
            question.compileAllValidatedSubmissions()
        }
        recompileTime * 10 shouldBeLessThan compileTime

        question.validationSubmissions shouldNotBe null
        question.validationSubmissions!!.size shouldBeGreaterThan 0
        question.validationSubmissions!!.forEach { incorrect ->
            incorrect.language shouldBe Question.Language.java
            incorrect.compiled(question).also {
                it.classLoader.definedClasses shouldContain question.klass
            }
            question.test(incorrect.contents(question), incorrect.language).also {
                it.complete.testing?.passed shouldBe false
                it.tests()?.size shouldBe incorrect.testCount
            }
        }
    }
})