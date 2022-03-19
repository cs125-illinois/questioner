package com.beyondgrader.questioner.agent

import edu.illinois.cs.cs125.jeed.core.Sandbox
import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.security.ProtectionDomain

private const val warmupTransformName = "java/lang/UnknownError"
private lateinit var instrumentation: Instrumentation

@Suppress("UNUSED_PARAMETER")
fun premain(agentArgs: String?, inst: Instrumentation) {
    instrumentation = inst
    instrumentation.addTransformer(BaseLookupGetterTransformer, false)
    val warmupClass = Class.forName(warmupTransformName.replace('/', '.'))
    val lookup = warmupClass.getMethod(BaseLookupGetterTransformer.GETTER_METHOD).invoke(null) as MethodHandles.Lookup
    setOf("LineCountSink\$State", "LineCountSink").forEach {
        val classStream = Agent.javaClass.getResourceAsStream("/$it.class") ?: error("missing $it")
        lookup.defineClass(classStream.readAllBytes())
        classStream.close()
    }
    instrumentation.removeTransformer(BaseLookupGetterTransformer)
}

@Suppress("UNUSED")
object Agent {
    private var lineGetHandle: MethodHandle? = null
    private var lineResetHandle: MethodHandle? = null
    private var countingGetHandle: MethodHandle? = null
    private var countingSetHandle: MethodHandle? = null

    fun activate() {
        if (isActivated) return
        val sinkClass = Class.forName("java.lang.LineCountSink")
        val lookup = MethodHandles.lookup()
        lineGetHandle = lookup.unreflect(sinkClass.getMethod("getLines"))
        lineResetHandle = lookup.unreflect(sinkClass.getMethod("reset"))
        countingGetHandle = lookup.unreflect(sinkClass.getMethod("getCounting"))
        countingSetHandle = lookup.unreflect(sinkClass.getMethod("setCounting", Boolean::class.javaPrimitiveType))
        isCounting = false
        resetLines() // warmup
        instrumentation.addTransformer(LineCountTransformer, true)
        val needsInstrumenting = instrumentation.allLoadedClasses.filter {
            instrumentation.isModifiableClass(it) && !it.isHidden &&
                LineCountTransformer.INCLUDED.any { prefix -> it.name.startsWith(prefix) }
        }
        instrumentation.retransformClasses(*needsInstrumenting.toTypedArray())
        resetLines()
    }

    val lines: Long
        get() = lineGetHandle?.invoke() as? Long ?: 0

    fun resetLines() {
        lineResetHandle?.invoke()
    }

    val isActivated: Boolean
        get() = lineGetHandle != null

    var isCounting: Boolean
        get() = countingGetHandle?.invoke() as? Boolean ?: false
        set(value) = (countingSetHandle ?: error("agent not activated")).invoke(value) as Unit
}

private object BaseLookupGetterTransformer : ClassFileTransformer {
    const val GETTER_METHOD = "agent\$getLookup"
    private const val GOT_FIELD = "agent\$alreadyGotLookup"

    override fun transform(
        module: Module?,
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray? {
        if (className != warmupTransformName) return null
        val reader = ClassReader(classfileBuffer)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        val injectorVisitor = object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitEnd() {
                super.visitField(
                    Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
                    GOT_FIELD,
                    Type.BOOLEAN_TYPE.descriptor,
                    null,
                    false
                ).visitEnd()
                val mv = super.visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
                    GETTER_METHOD,
                    Type.getMethodDescriptor(Type.getType(MethodHandles.Lookup::class.java)),
                    null,
                    null
                )
                mv.visitCode()
                val alreadyUsedLabel = Label()
                mv.visitFieldInsn(
                    Opcodes.GETSTATIC,
                    warmupTransformName,
                    GOT_FIELD,
                    Type.BOOLEAN_TYPE.descriptor
                )
                mv.visitJumpInsn(Opcodes.IFNE, alreadyUsedLabel)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitFieldInsn(
                    Opcodes.PUTSTATIC,
                    warmupTransformName,
                    GOT_FIELD,
                    Type.BOOLEAN_TYPE.descriptor
                )
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    Type.getType(MethodHandles::class.java).descriptor,
                    "lookup",
                    Type.getMethodDescriptor(Type.getType(MethodHandles.Lookup::class.java)),
                    false
                )
                mv.visitInsn(Opcodes.ARETURN)
                mv.visitLabel(alreadyUsedLabel)
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitInsn(Opcodes.ARETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()
                super.visitEnd()
            }
        }
        reader.accept(injectorVisitor, 0)
        return writer.toByteArray()
    }
}

private object LineCountTransformer : ClassFileTransformer {
    val INCLUDED = setOf(
        "java/",
        "kotlin/"
    )

    private val EXCLUDED = setOf(
        "edu/illinois/", // Jeed etc.
        "java/io/", // involved in class loading
        "java/lang/instrument/", // agent
        "java/lang/invoke/", // method handles
        "java/lang/ref/", // GC-related
        "java/lang/reflect/", // reflection
        "java/lang/Class", // reflection
        "java/lang/IncompatibleClassChangeError", // method handles
        "java/lang/Integer",
        "java/lang/LineCountSink", // this agent
        "java/lang/Long",
        "java/lang/Module", // reflection
        "java/lang/NoClassDefFoundError", // thrown when this agent goes wrong
        "java/lang/NoSuchMethodError", // method handles
        "java/lang/Object", // sink creates objects
        "java/lang/SecurityManager", // sandbox
        "java/lang/StackOverflowError", // thrown when this agent goes wrong
        "java/lang/Thread", // used by the sink
        "java/nio/", // possibly involved in class loading
        "java/security/", // sandbox
        "java/util/concurrent", // application threading
        "java/util/zip/", // involved in class loading
        "jdk/internal/", // reflection
        "kotlin/concurrent/", // application threading
        "kotlin/coroutines/", // application threading
        "kotlin/reflect/", // reflection
        "org/jetbrains/", // Kotlin compiler
        "org/objectweb/", // ASM
    )

    override fun transform(
        module: Module?,
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray? {
        val originalCountStatus = Agent.isCounting
        Agent.isCounting = false
        try {
            if (INCLUDED.none { className.startsWith(it) } ||
                EXCLUDED.any { className.startsWith(it) } ||
                loader is Sandbox.SandboxedClassLoader) {
                return null
            }
            val reader = ClassReader(classfileBuffer)
            val writer = ClassWriter(reader, 0)
            val classNode = ClassNode()
            reader.accept(classNode, 0)
            classNode.methods.forEach { method ->
                if (className == "java/lang/String" && method.name == "<init>") {
                    // Called by native code
                    return@forEach
                }
                method.instructions.filterIsInstance<LineNumberNode>().map { it.start }.forEach {
                    var nextRealInsn = it.next
                    while (nextRealInsn != null && nextRealInsn.opcode < 0) {
                        nextRealInsn = nextRealInsn.next
                    }
                    if (nextRealInsn != null && nextRealInsn.opcode != Opcodes.NEW) {
                        val invokeInsn = MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/lang/LineCountSink",
                            "addLine",
                            "()V",
                            false
                        )
                        method.instructions.insertBefore(nextRealInsn, invokeInsn)
                    }
                }
            }
            classNode.accept(writer)
            return writer.toByteArray()
        } finally {
            Agent.isCounting = originalCountStatus
        }
    }
}
