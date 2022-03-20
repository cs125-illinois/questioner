package edu.illinois.cs.cs125.questioner.lib

import com.beyondgrader.questioner.agent.Agent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.random.Random

@Suppress("UNUSED")
class TestAgent : StringSpec({
    "should activate successfully" {
        Agent.activate()
        Agent.isActivated shouldBe true
    }

    "should count lines in libraries" {
        val random = Random(1)
        val bytes = random.nextBytes(100)
        Agent.resetLines()
        Agent.isCounting = true
        bytes.sort()
        Agent.isCounting = false
        Agent.lines shouldBeGreaterThan 100
    }

    "should not count lines while disabled" {
        Agent.isCounting = false
        Agent.resetLines()
        Random(1).nextBytes(100).sort()
        Agent.lines shouldBe 0
    }

    "should not count lines outside the standard library" {
        Agent.resetLines()
        Agent.isCounting = true
        var x = 0
        repeat(500) {
            x += it
            x *= (it - 5)
            x = x shl 1
        }
        Agent.lines shouldBe 0
    }
})