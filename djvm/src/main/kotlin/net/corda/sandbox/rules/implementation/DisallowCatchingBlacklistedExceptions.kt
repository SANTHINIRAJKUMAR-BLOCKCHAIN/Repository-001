package net.corda.sandbox.rules.implementation

import net.corda.sandbox.code.Emitter
import net.corda.sandbox.code.EmitterContext
import net.corda.sandbox.code.Instruction
import net.corda.sandbox.code.instructions.CodeLabel
import net.corda.sandbox.code.instructions.TryCatchBlock
import net.corda.sandbox.costing.ThresholdViolationException
import net.corda.sandbox.rules.InstructionRule
import net.corda.sandbox.validation.RuleContext
import org.objectweb.asm.Label

/**
 * Rule that checks for attempted catches of [ThreadDeath], [ThresholdViolationException], [Error] or [Throwable].
 */
@Suppress("unused")
class DisallowCatchingBlacklistedExceptions : InstructionRule(), Emitter {

    override fun validate(context: RuleContext, instruction: Instruction) = context.validate {
        if (instruction is TryCatchBlock) {
            val typeName = context.classModule.getFormattedClassName(instruction.typeName)
            warn("Injected runtime check for catch-block for type $typeName") given
                    (instruction.typeName in disallowedExceptionTypes)
            fail("Disallowed catch of ThreadDeath exception") given
                    (instruction.typeName == threadDeathException)
            fail("Disallowed catch of threshold violation exception") given
                    (instruction.typeName.endsWith(ThresholdViolationException::class.java.simpleName))
        }
    }

    override fun emit(context: EmitterContext, instruction: Instruction) = context.emit {
        if (instruction is TryCatchBlock && instruction.typeName in disallowedExceptionTypes) {
            handlers.add(instruction.handler)
        } else if (instruction is CodeLabel && isExceptionHandler(instruction.label)) {
            duplicate()
            invokeInstrumenter("checkCatch", "(Ljava/lang/Throwable;)V")
        }
    }

    private val handlers = mutableSetOf<Label>()

    private fun isExceptionHandler(label: Label) = label in handlers

    companion object {

        private const val threadDeathException = "java/lang/ThreadDeath"

        // Any of [ThreadDeath]'s throwable super-classes need explicit checking.
        private val disallowedExceptionTypes = setOf(
                "java/lang/Throwable",
                "java/lang/Error"
        )

    }

}
