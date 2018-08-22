package net.corda.sandbox.rules

import net.corda.sandbox.code.Instruction
import net.corda.sandbox.references.Class
import net.corda.sandbox.references.Member
import net.corda.sandbox.validation.RuleContext

/**
 * Representation of a rule that applies to byte code instructions.
 */
abstract class InstructionRule : Rule {

    /**
     * Called when an instruction is visited.
     *
     * @param context The context in which the rule is to be validated.
     * @param instruction The instruction to apply and validate this rule against.
     */
    abstract fun validate(context: RuleContext, instruction: Instruction)

    override fun validate(context: RuleContext, clazz: Class?, member: Member?, instruction: Instruction?) {
        // Only run validation step if applied to the class member itself.
        if (clazz != null && member != null && instruction != null) {
            validate(context, instruction)
        }
    }

}
