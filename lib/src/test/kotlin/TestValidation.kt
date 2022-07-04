package edu.illinois.cs.cs125.questioner.lib

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path

private val validator = Validator(
    Path.of(object {}::class.java.getResource("/questions.json")!!.toURI()).toFile(),
    "",
    seed = 124
)

class TestValidation : StringSpec({
    "it should validate a question" {
        validator.validate("Equals 88", force = true, testing = true).also { (question, report) ->
            question.validated shouldBe true
            report shouldNotBe null
            report!!.requiredTestCount shouldBeGreaterThan 0
        }
    }
    "it should validate a recursive question" {
        validator.validate("Recursive Factorial", force = true, testing = true).also { (question, report) ->
            question.validated shouldBe true
            report shouldNotBe null
            report!!.requiredTestCount shouldBeGreaterThan 0
        }
    }
})