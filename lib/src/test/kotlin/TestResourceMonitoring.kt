package edu.illinois.cs.cs125.questioner.lib

import edu.illinois.cs.cs125.jeed.core.*
import edu.illinois.cs.cs125.jenisol.core.unwrap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.beInstanceOf
import java.lang.reflect.Method
import kotlin.random.Random

class TestResourceMonitoring : StringSpec({
    "should report checkpoints and total line counts" {
        val callLines = mutableListOf<Long>()
        val result = runJava("""
            public static void test(int times) {
                for (int i = 0; i < times; i++) {
                    System.out.println(i);
                }
            }
        """.trimIndent(), ResourceMonitoringArguments()) { m ->
            println("Warmup")
            listOf(2, 1, 3).forEach {
                ResourceMonitoring.beginSubmissionCall()
                m(null, it)
                callLines.add(ResourceMonitoring.finishSubmissionCall().totalLines)
            }
        }
        result.threw should beNull()
        result.completed shouldBe true
        callLines[0] shouldBeLessThan 100
        callLines[0] shouldBeGreaterThan 20
        callLines[1] shouldBeLessThan callLines[0]
        callLines[1] shouldBeGreaterThan 5
        callLines[2] shouldBeGreaterThan callLines[0]
        callLines[2] shouldBeGreaterThan callLines[1]
        val resourceResult = result.pluginResult(ResourceMonitoring)
        resourceResult.totalLines shouldBe callLines.sum()
    }

    "should limit total lines executed" {
        val result = runJava("""
            public static void test(byte[] values) {
                System.out.println("Start");
                java.util.Arrays.sort(values);
                System.out.println("Finish");
            }
        """.trimIndent(), ResourceMonitoringArguments(totalLineLimit = 1000)) { m ->
            val bytes = Random(1).nextBytes(500)
            unwrap { m(null, bytes) }
        }
        result.stdout shouldBe "Start"
        result.threw should beInstanceOf<LineLimitExceeded>()
    }

    "should track array allocation" {
        val callAllocs = mutableListOf<Long>()
        val result = runJava("""
            public static int[] test(int size) {
                return new int[size];
            }
        """.trimIndent(), ResourceMonitoringArguments()) { m ->
            listOf(0, 20, 10, 50).forEach {
                ResourceMonitoring.beginSubmissionCall()
                m(null, it)
                callAllocs.add(ResourceMonitoring.finishSubmissionCall().allocatedMemory)
            }
        }
        result.threw should beNull()
        callAllocs[1] shouldBeGreaterThan 20
        callAllocs[1] shouldBeLessThan 200
        callAllocs[2] shouldBeGreaterThan 40
        callAllocs[2] shouldBeLessThan callAllocs[0]
        callAllocs[3] shouldBeGreaterThan 200
        val resourceResult = result.pluginResult(ResourceMonitoring)
        resourceResult.allocatedMemory shouldBeGreaterThan callAllocs.maxOf { it }
    }

    "should track string allocation" {
        val callAllocs = mutableListOf<Long>()
        val result = runJava("""
            public static String test(String input) {
                return input + input;
            }
        """.trimIndent(), ResourceMonitoringArguments()) { m ->
            listOf("", "a", "this is a test", "test!").forEach {
                ResourceMonitoring.beginSubmissionCall()
                m(null, it)
                callAllocs.add(ResourceMonitoring.finishSubmissionCall().allocatedMemory)
            }
        }
        result.threw should beNull()
        callAllocs[1] shouldBeGreaterThan 20
        callAllocs[1] shouldBeLessThan 200
        callAllocs[2] shouldBeGreaterThan callAllocs[1]
        callAllocs[2] shouldBeLessThan 400
        callAllocs[3] shouldBeGreaterThan callAllocs[1]
        callAllocs[3] shouldBeLessThan callAllocs[2]
    }

    "should limit allocated memory" {
        val result = runJava("""
            public static void test() {
                System.out.println("Start");
                for (int i = 0; ; i++) {
                    int[] ints = new int[i];
                    System.out.println(i);
                }
            }
        """.trimIndent(), ResourceMonitoringArguments(allocatedMemoryLimit = 500000)) { m ->
            unwrap { m(null) }
        }
        result.stdout shouldStartWith "Start"
        result.stdout shouldContain "\n20\n"
        result.threw should beInstanceOf<AllocationLimitExceeded>()
    }

    "should reflect max stack depth in memory estimate" {
        val iterativeAllocs = mutableListOf<Long>()
        runJava("""
            public static long test(long i) {
                long result = 1;
                while (i > 0) {
                    result *= i;
                    i--;
                }
                return result;
            }
        """.trimIndent(), ResourceMonitoringArguments()) { m ->
            listOf(0, 1, 5, 15).forEach {
                ResourceMonitoring.beginSubmissionCall()
                m(null, it.toLong())
                iterativeAllocs.add(ResourceMonitoring.finishSubmissionCall().allocatedMemory)
            }
        }
        iterativeAllocs[1] shouldBeLessThan 300
        iterativeAllocs[1] shouldBeGreaterThan 20
        iterativeAllocs[2] shouldBeLessThan 3 * iterativeAllocs[1]
        iterativeAllocs[3] shouldBeLessThan 3 * iterativeAllocs[2]
        val recursiveAllocs = mutableListOf<Long>()
        runJava("""
            public static long test(long i) {
                if (i <= 1) {
                    return i;
                } else {
                    return i * test(i - 1);
                }
            }
        """.trimIndent(), ResourceMonitoringArguments()) { m ->
            listOf(0, 1, 5, 16).forEach {
                ResourceMonitoring.beginSubmissionCall()
                m(null, it.toLong())
                recursiveAllocs.add(ResourceMonitoring.finishSubmissionCall().allocatedMemory)
            }
        }
        recursiveAllocs[1] shouldBeLessThan 300
        recursiveAllocs[1] shouldBeGreaterThan 20
        recursiveAllocs[2] shouldBeGreaterThan 4 * recursiveAllocs[1]
        recursiveAllocs[3] shouldBeGreaterThan 3 * recursiveAllocs[2]
        val nonrecursiveCallAllocs = mutableListOf<Long>()
        runJava("""
            public static long test(long i) {
                long result = 1;
                while (i > 0) {
                    result = multiply(result, i);
                    i--;
                }
                return result;
            }
            private static long multiply(long a, long b) {
                return a * b;
            }
        """.trimIndent(), ResourceMonitoringArguments()) { m ->
            listOf(0, 1, 5, 15).forEach {
                ResourceMonitoring.beginSubmissionCall()
                m(null, it.toLong())
                nonrecursiveCallAllocs.add(ResourceMonitoring.finishSubmissionCall().allocatedMemory)
            }
        }
        nonrecursiveCallAllocs[1] shouldBeLessThan 400
        nonrecursiveCallAllocs[1] shouldBeGreaterThan 20
        nonrecursiveCallAllocs[2] shouldBeLessThan 3 * nonrecursiveCallAllocs[1]
        nonrecursiveCallAllocs[3] shouldBeLessThan 3 * nonrecursiveCallAllocs[2]
    }
})

private suspend fun runJava(
    code: String,
    args: ResourceMonitoringArguments,
    test: (Method) -> Unit
): Sandbox.TaskResults<*> {
    val compiledSource = Source.fromJava("public class Test {\n$code\n}").compile()
    val plugins = listOf(
        ConfiguredSandboxPlugin(ResourceMonitoring, args),
        ConfiguredSandboxPlugin(LineTrace, LineTraceArguments(recordedLineLimit = 0, coalesceDuplicates = false))
    )
    return Sandbox.execute(compiledSource.classLoader, configuredPlugins = plugins) { (cl, _) ->
        val method = cl.loadClass("Test").methods.find { it.name == "test" }!!
        test(method)
    }
}
