package edu.illinois.cs.cs125.questioner.lib

import com.beyondgrader.resourceagent.Agent
import com.beyondgrader.resourceagent.AllocationLimiting
import com.beyondgrader.resourceagent.LineCounting
import com.beyondgrader.resourceagent.WarmupWrapping
import com.sun.management.ThreadMXBean
import edu.illinois.cs.cs125.jeed.core.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode
import java.lang.management.ManagementFactory
import java.util.Stack
import java.util.function.LongFunction
import kotlin.math.max

object ResourceMonitoring : SandboxPlugin<ResourceMonitoringArguments, ResourceMonitoringResults> {
    private val mxBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        ?: error("missing HotSpot-specific extension of ThreadMXBean")
    private val stackWalker = StackWalker.getInstance()
    private val threadData = ThreadLocal.withInitial {
        Sandbox.CurrentTask.getWorkingData<ResourceMonitoringWorkingData>(ResourceMonitoring)
    }
    private val RETURN_OPCODES = setOf(
        Opcodes.RETURN, Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.LRETURN, Opcodes.ARETURN
    )
    private const val CONSTITUTIVE_FRAME_SIZE = 16
    private const val BYTES_PER_FRAME_ELEMENT = 8
    private const val MAX_ALWAYS_PERMITTED_ALLOCATION = 512

    val countLibraryLines = System.getenv("QUESTIONER_COUNT_LIBRARY_LINES")?.let { it.toBoolean() } ?: true

    init {
        mxBean.isThreadAllocatedMemoryEnabled = true
        Sandbox.SandboxedClassLoader::class.java.toString() // Ensure loaded, to be instrumented
        Agent.activate(countLines = countLibraryLines)
        AllocationLimiting.arrayBodySizeValidator = LongFunction(ResourceMonitoring::checkArrayAllocation)
        WarmupWrapping.beforeWarmup = Runnable(ResourceMonitoring::beforeWarmup)
        WarmupWrapping.afterWarmup = Runnable(ResourceMonitoring::afterWarmup)
    }

    override fun createInstrumentationData(
        arguments: ResourceMonitoringArguments,
        classLoaderConfiguration: Sandbox.ClassLoaderConfiguration,
        allPlugins: List<ConfiguredSandboxPlugin<*, *>>
    ): Any {
        return ResourceMonitoringInstrumentationData(arguments)
    }

    override fun transformBeforeSandbox(
        bytecode: ByteArray,
        name: String,
        instrumentationData: Any?,
        context: RewritingContext
    ): ByteArray {
        if (context != RewritingContext.UNTRUSTED) return bytecode
        instrumentationData as ResourceMonitoringInstrumentationData
        val reader = ClassReader(bytecode)
        val classNode = ClassNode(Opcodes.ASM9)
        reader.accept(NewLabelSplittingClassVisitor(classNode), 0)
        val className = classNode.name.replace('/', '.')
        instrumentationData.knownClasses.add(className)
        classNode.methods.forEach { instrumentMethod(instrumentationData, className, it) }
        val writer = ClassWriter(reader, 0)
        classNode.accept(writer)
        return writer.toByteArray()
    }

    private fun instrumentMethod(
        instrumentationData: ResourceMonitoringInstrumentationData,
        className: String,
        method: MethodNode
    ) {
        if (method.instructions.size() == 0) return
        val methodId = instrumentationData.knownMethods.size
        val frameSize = CONSTITUTIVE_FRAME_SIZE + BYTES_PER_FRAME_ELEMENT * (method.maxStack + method.maxLocals)
        method.instructions.insert(InsnList().apply {
            add(IntInsnNode(Opcodes.SIPUSH, methodId))
            add(TracingSink::pushCallStack.asAsmMethodInsn())
        })
        method.instructions.filter { it.opcode in RETURN_OPCODES }.forEach {
            method.instructions.insertBefore(it, TracingSink::popCallStack.asAsmMethodInsn())
        }
        method.instructions.filterIsInstance<LineNumberNode>().forEach {
            method.instructions.insert(it.skipToBeforeRealInsnOrLabel(), TracingSink::lineStep.asAsmMethodInsn())
        }
        method.tryCatchBlocks.forEach {
            method.instructions.insert(
                it.handler.skipToBeforeRealInsnOrLabel(),
                TracingSink::adjustCallStackAfterException.asAsmMethodInsn()
            )
        }
        method.maxStack++
        AllocationLimiting.instrumentArrayAllocations(method)
        val methodInfo = ResourceMonitoringInstrumentationData.MethodInfo(className, method.name, method.desc, frameSize)
        instrumentationData.knownMethods.add(methodInfo)
    }

    override val requiredClasses: Set<Class<*>>
        get() = setOf(TracingSink::class.java, javaClass.classLoader.loadClass("java.lang.ResourceUsageSink"))

    override fun createInitialData(instrumentationData: Any?, executionArguments: Sandbox.ExecutionArguments): Any {
        require(executionArguments.maxExtraThreads == 0) { "only one thread is supported" }
        return ResourceMonitoringWorkingData(instrumentationData as ResourceMonitoringInstrumentationData)
    }

    override fun executionStartedInSandbox() {
        AllocationLimiting.isCheckingAllocations = true
        WarmupWrapping.isCallbackEnabled = true
    }

    private fun updateExternalMeasurements(data: ResourceMonitoringWorkingData) {
        data.allocatedMemory = mxBean.currentThreadAllocatedBytes - data.baseAllocatedMemory
        data.libraryLines = LineCounting.lines
    }

    private fun checkLimits(data: ResourceMonitoringWorkingData) {
        val taskSubmissionLines = data.checkpointSubmissionLines + data.submissionLines
        if (data.arguments.submissionLineLimit != null && data.submissionLines > data.arguments.submissionLineLimit) {
            throw LineLimitExceeded()
        }
        val taskTotalLines = taskSubmissionLines + data.checkpointLibraryLines + data.libraryLines
        if (data.arguments.totalLineLimit != null && taskTotalLines > data.arguments.totalLineLimit) {
            throw LineLimitExceeded()
        }
        val taskMemory = data.checkpointAllocatedMemory + data.allocatedMemory
        if (data.arguments.allocatedMemoryLimit != null && taskMemory > data.arguments.allocatedMemoryLimit) {
            throw AllocationLimitExceeded(data.arguments.allocatedMemoryLimit)
        }
    }

    override fun createFinalData(workingData: Any?): ResourceMonitoringResults {
        workingData as ResourceMonitoringWorkingData
        workingData.checkpoint()
        return ResourceMonitoringResults(
            arguments = workingData.arguments,
            submissionLines = workingData.checkpointSubmissionLines,
            totalLines = workingData.checkpointSubmissionLines + workingData.checkpointLibraryLines,
            allocatedMemory = workingData.checkpointAllocatedMemory,
            invokedRecursiveFunctions = workingData.checkpointRecursiveFunctions.map { it.toResult() }.toSet()
        )
    }

    private inline fun <T> ignoreUsage(data: ResourceMonitoringWorkingData, crossinline block: () -> T): T {
        val bytesBefore = mxBean.currentThreadAllocatedBytes
        val countingBefore = LineCounting.isCounting
        LineCounting.isCounting = false
        return try {
            block()
        } finally {
            LineCounting.isCounting = countingBefore
            val bytesAfter = mxBean.currentThreadAllocatedBytes
            data.baseAllocatedMemory += bytesAfter - bytesBefore
        }
    }

    fun beginSubmissionCall() {
        threadData.get().pendingClear = true
    }

    fun finishSubmissionCall(): ResourceMonitoringCheckpoint {
        LineCounting.isCounting = false
        val data = threadData.get()
        return ignoreUsage(data) {
            updateExternalMeasurements(data)
            data.checkpoint()
        }
    }

    @JvmStatic
    private fun checkArrayAllocation(bytes: Long): Boolean {
        if (bytes < MAX_ALWAYS_PERMITTED_ALLOCATION) return true // Allow error message construction
        val data = threadData.get()
        if (data.arguments.allocatedMemoryLimit == null) return true
        updateExternalMeasurements(data)
        return data.checkpointAllocatedMemory + data.allocatedMemory + bytes < data.arguments.allocatedMemoryLimit
    }

    @JvmStatic
    private fun beforeWarmup() {
        val data = threadData.get()
        data.preWarmupAllocatedMemory = mxBean.currentThreadAllocatedBytes
    }

    @JvmStatic
    private fun afterWarmup() {
        val data = threadData.get()
        val warmupAllocatedBytes = mxBean.currentThreadAllocatedBytes - data.preWarmupAllocatedMemory
        // Charge one-time warmup to the overall task, but not the individual submission call
        data.baseAllocatedMemory += warmupAllocatedBytes
        data.checkpointAllocatedMemory += warmupAllocatedBytes
        data.preWarmupAllocatedMemory = 0
        data.warmups++
    }

    object TracingSink {
        @JvmStatic
        fun lineStep() {
            val data = threadData.get()
            data.submissionLines++
            updateExternalMeasurements(data)
            checkLimits(data)
        }

        @JvmStatic
        fun pushCallStack(methodId: Int) {
            val data = threadData.get()
            if (data.pendingClear) {
                data.callStack.clear()
                data.baseAllocatedMemory = mxBean.currentThreadAllocatedBytes
                LineCounting.isCounting = true
                LineCounting.reset()
                data.pendingClear = false
            }
            ignoreUsage(data) {
                val methodInfo = data.instrumentationData.knownMethods[methodId]
                val caller = if (data.callStack.isEmpty()) null else data.callStack.peek()
                if (methodInfo == caller) {
                    data.recursiveFunctions.add(methodInfo)
                }
                data.callStack.push(methodInfo)
                data.callStackSize += methodInfo.frameSize
                data.maxCallStackSize = max(data.maxCallStackSize, data.callStackSize)
            }
        }

        @JvmStatic
        fun popCallStack() {
            val data = threadData.get()
            ignoreUsage(data) {
                val methodInfo = data.callStack.pop()
                data.callStackSize -= methodInfo.frameSize
            }
        }

        @JvmStatic
        fun adjustCallStackAfterException() {
            val data = threadData.get()
            ignoreUsage(data) {
                val currentFrameCount = stackWalker.walk { stream ->
                    stream.filter { it.className in data.instrumentationData.knownClasses }.count()
                }
                while (data.callStack.size > currentFrameCount) {
                    data.callStackSize -= data.callStack.pop().frameSize
                }
            }
        }
    }
}

data class ResourceMonitoringArguments(
    val submissionLineLimit: Long? = null,
    val totalLineLimit: Long? = null,
    val allocatedMemoryLimit: Long? = null
)

private class ResourceMonitoringInstrumentationData(
    val arguments: ResourceMonitoringArguments,
    val knownClasses: MutableSet<String> = mutableSetOf(),
    val knownMethods: MutableList<MethodInfo> = mutableListOf()
) {
    class MethodInfo(val className: String, val name: String, val descriptor: String, val frameSize: Int) {
        fun toResult(): ResourceMonitoringResults.MethodInfo {
            return ResourceMonitoringResults.MethodInfo(className, name, descriptor)
        }
    }
}

private class ResourceMonitoringWorkingData(
    val instrumentationData: ResourceMonitoringInstrumentationData,
    val arguments: ResourceMonitoringArguments = instrumentationData.arguments,
    val callStack: Stack<ResourceMonitoringInstrumentationData.MethodInfo> = Stack(),
    var pendingClear: Boolean = true,
    var checkpointSubmissionLines: Long = 0,
    var submissionLines: Long = 0,
    var checkpointLibraryLines: Long = 0,
    var libraryLines: Long = 0,
    var preWarmupAllocatedMemory: Long = 0,
    var baseAllocatedMemory: Long = 0,
    var checkpointAllocatedMemory: Long = 0,
    var allocatedMemory: Long = 0,
    var callStackSize: Long = 0,
    var maxCallStackSize: Long = 0,
    var warmups: Int = 0,
    val checkpointRecursiveFunctions: MutableSet<ResourceMonitoringInstrumentationData.MethodInfo> = mutableSetOf(),
    val recursiveFunctions: MutableSet<ResourceMonitoringInstrumentationData.MethodInfo> = mutableSetOf()
) {
    fun checkpoint(): ResourceMonitoringCheckpoint {
        val result = ResourceMonitoringCheckpoint(
            submissionLines = submissionLines,
            totalLines = submissionLines + libraryLines,
            maxCallStackSize = maxCallStackSize,
            allocatedMemory = allocatedMemory + maxCallStackSize,
            invokedRecursiveFunctions = recursiveFunctions.map { it.toResult() }.toSet(),
            warmups = warmups
        )
        checkpointSubmissionLines += submissionLines
        checkpointLibraryLines += libraryLines
        checkpointAllocatedMemory += allocatedMemory
        checkpointRecursiveFunctions.addAll(recursiveFunctions)
        submissionLines = 0
        libraryLines = 0
        allocatedMemory = 0
        recursiveFunctions.clear()
        callStack.clear()
        callStackSize = 0
        maxCallStackSize = 0
        warmups = 0
        return result
    }
}

data class ResourceMonitoringCheckpoint(
    val submissionLines: Long,
    val totalLines: Long,
    val maxCallStackSize: Long,
    val allocatedMemory: Long,
    val invokedRecursiveFunctions: Set<ResourceMonitoringResults.MethodInfo>,
    val warmups: Int
)

data class ResourceMonitoringResults(
    val arguments: ResourceMonitoringArguments,
    val submissionLines: Long,
    val totalLines: Long,
    val allocatedMemory: Long,
    val invokedRecursiveFunctions: Set<MethodInfo>
) {
    data class MethodInfo(val className: String, val methodName: String, val descriptor: String)
}

class AllocationLimitExceeded(limit: Long) : OutOfMemoryError("allocated too much memory: more than $limit bytes")
