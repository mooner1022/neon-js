package dev.mooner.neonjs.jit

import dev.mooner.neonjs.vm.VmFrame

/**
 * Entry point implemented by generated classes, whatever the language.
 *
 * [run] starts the code at its beginning when [f] is a frame made for compiled code ([VmFrame.isCompiled]). Given an
 * interpreted frame instead, it is an on-stack replacement ([Jit.osr]): the interpreter stopped at a loop header,
 * [VmFrame.pc], with an empty operand stack and the registers in [VmFrame.slots]; the code continues there and returns
 * the frame's result, or returns [VmRt.NO_OSR] before doing anything when it has no entry at that pc. Code whose block
 * has no backward jump may assume it is never given an interpreted frame. Any code generator (a later tier included)
 * follows this contract, whichever entries it supports.
 *
 * Code may also leave the frame to the interpreter midway (an exit): run then returns what the interpreter returns for
 * the frame, or, for a frame it was given interpreted, [VmRt.DEOPTED] (the interpreter continues it).
 *
 * (Part of the core's interface for languages: public for NeonJS's language modules, not a stable API.)
 */
interface CompiledCode {
    fun run(f: VmFrame): Any?
}
