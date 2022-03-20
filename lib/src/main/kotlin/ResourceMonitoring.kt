package edu.illinois.cs.cs125.questioner.lib

import com.beyondgrader.questioner.agent.Agent
import com.sun.management.ThreadMXBean
import edu.illinois.cs.cs125.jeed.core.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode
import java.lang.management.ManagementFactory
import kotlin.math.max

object ResourceMonitoring : SandboxPlugin<ResourceMonitoringArguments, ResourceMonitoringResults> {
    private val mxBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        ?: error("missing HotSpot-specific extension of ThreadMXBean")
    private val threadData = ThreadLocal.withInitial {
        Sandbox.CurrentTask.getWorkingData<ResourceMonitoringWorkingData>(ResourceMonitoring)
    }
    private val RETURN_OPCODES = setOf(
        Opcodes.RETURN, Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.LRETURN, Opcodes.ARETURN
    )
    private const val CONSTITUTIVE_FRAME_SIZE = 16
    private const val BYTES_PER_FRAME_ELEMENT = 8

    init {
        mxBean.isThreadAllocatedMemoryEnabled = true
        Agent.activate()
    }

    override fun createInstrumentationData(
        arguments: ResourceMonitoringArguments,
        classLoaderConfiguration: Sandbox.ClassLoaderConfiguration,
        allPlugins: List<ConfiguredSandboxPlugin<*, *>>
    ): Any {
        return arguments
    }

    override fun transformBeforeSandbox(
        bytecode: ByteArray,
        name: String,
        instrumentationData: Any?,
        context: RewritingContext
    ): ByteArray {
        if (context != RewritingContext.UNTRUSTED) return bytecode
        val reader = ClassReader(bytecode)
        val classNode = ClassNode(Opcodes.ASM9)
        reader.accept(NewLabelSplittingClassVisitor(classNode), 0)
        classNode.methods.forEach { instrumentMethod(it) }
        val writer = ClassWriter(reader, 0)
        classNode.accept(writer)
        return writer.toByteArray()
    }

    private fun instrumentMethod(method: MethodNode) {
        if (method.instructions.size() == 0) return
        val frameSize = CONSTITUTIVE_FRAME_SIZE + BYTES_PER_FRAME_ELEMENT * (method.maxStack + method.maxLocals)
        method.instructions.insert(InsnList().apply {
            add(LdcInsnNode(frameSize))
            add(TracingSink::pushCallStack.asAsmMethodInsn())
        })
        method.instructions.filter { it.opcode in RETURN_OPCODES }.forEach {
            method.instructions.insertBefore(it, InsnList().apply {
                add(LdcInsnNode(frameSize))
                add(TracingSink::popCallStack.asAsmMethodInsn())
            })
        }
        method.instructions.filterIsInstance<LineNumberNode>().forEach {
            val insertionPoint = it.skipToBeforeRealInsnOrLabel()
            method.instructions.insert(insertionPoint, TracingSink::lineStep.asAsmMethodInsn())
        }
        method.maxStack++
    }

    override val requiredClasses: Set<Class<*>>
        get() = setOf(TracingSink::class.java)

    override fun createInitialData(instrumentationData: Any?, executionArguments: Sandbox.ExecutionArguments): Any {
        require(executionArguments.maxExtraThreads == 0) { "only one thread is supported" }
        return ResourceMonitoringWorkingData(instrumentationData as ResourceMonitoringArguments)
    }

    private fun updateExternalMeasurements(data: ResourceMonitoringWorkingData) {
        data.allocatedMemory = mxBean.currentThreadAllocatedBytes - data.baseAllocatedMemory
        data.libraryLines = Agent.lines
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
            allocatedMemory = workingData.checkpointAllocatedMemory
        )
    }

    fun beginSubmissionCall() {
        threadData.get().pendingClear = true
    }

    fun finishSubmissionCall(): ResourceMonitoringCheckpoint {
        Agent.isCounting = false
        val data = threadData.get()
        updateExternalMeasurements(data)
        return data.checkpoint()
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
        fun pushCallStack(frameSize: Int) {
            val data = threadData.get()
            if (data.pendingClear) {
                data.baseAllocatedMemory = mxBean.currentThreadAllocatedBytes
                Agent.isCounting = true
                Agent.resetLines()
                data.pendingClear = false
            }
            data.callStackSize += frameSize
            data.maxCallStackSize = max(data.maxCallStackSize, data.callStackSize)
        }

        @JvmStatic
        fun popCallStack(frameSize: Int) {
            val data = threadData.get()
            data.callStackSize -= frameSize
        }
    }
}

data class ResourceMonitoringArguments(
    val submissionLineLimit: Long? = null,
    val totalLineLimit: Long? = null,
    val allocatedMemoryLimit: Long? = null
)

private class ResourceMonitoringWorkingData(
    val arguments: ResourceMonitoringArguments,
    var pendingClear: Boolean = true,
    var checkpointSubmissionLines: Long = 0,
    var submissionLines: Long = 0,
    var checkpointLibraryLines: Long = 0,
    var libraryLines: Long = 0,
    var baseAllocatedMemory: Long = 0,
    var checkpointAllocatedMemory: Long = 0,
    var allocatedMemory: Long = 0,
    var callStackSize: Long = 0,
    var maxCallStackSize: Long = 0
) {
    fun checkpoint(): ResourceMonitoringCheckpoint {
        val result = ResourceMonitoringCheckpoint(
            submissionLines = submissionLines,
            totalLines = submissionLines + libraryLines,
            allocatedMemory = allocatedMemory + maxCallStackSize
        )
        checkpointSubmissionLines += submissionLines
        checkpointLibraryLines += libraryLines
        checkpointAllocatedMemory += allocatedMemory
        submissionLines = 0
        libraryLines = 0
        allocatedMemory = 0
        callStackSize = 0
        maxCallStackSize = 0
        return result
    }
}

data class ResourceMonitoringCheckpoint(
    val submissionLines: Long,
    val totalLines: Long,
    val allocatedMemory: Long
)

data class ResourceMonitoringResults(
    val arguments: ResourceMonitoringArguments,
    val submissionLines: Long,
    val totalLines: Long,
    val allocatedMemory: Long
)

class AllocationLimitExceeded(limit: Long) : Error("allocated too much memory: more than $limit bytes")
