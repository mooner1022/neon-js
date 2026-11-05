package dev.mooner.neonpython

import dev.mooner.neonjs.jit.opt.OpInfo

/**
 * An instruction whose code, in the optimizing tier, is one call of the runtime ([PyRt]): the graph builder pops its
 * inputs, the code generator passes [args] to [method]. [args] says what each parameter is:
 * - `f` the frame (a VmFrame),
 * - `k` constant [operand a] of the code block (an Object, read from its constants, never baked into the code),
 * - `a`, `b` operand a or b as an int,
 * - `v` the next input (the deepest first), an Object,
 * - `n` the inputs left, an Object[].
 *
 * It pops [pops] values, and [popsPerA] more for each of operand a's count (a call's arguments, a list's items).
 */
internal class Generic(
    @JvmField val method: String,
    @JvmField val args: String,
    @JvmField val pops: Int,
    @JvmField val result: Boolean,
    /** whether it may raise: the code stores its pc first, for the error's line and the exit's state */
    @JvmField val throws: Boolean = true,
    @JvmField val popsPerA: Int = 0,
    @JvmField val flags: Int = OpInfo.ANYTHING,
) {
    /** Its JVM method descriptor. */
    val descriptor: String = buildString {
        append('(')
        for (c in args) append(when (c) {
            'f' -> "Ldev/mooner/neonjs/vm/VmFrame;"
            'k', 'v' -> "Ljava/lang/Object;"
            'a', 'b' -> "I"
            'n' -> "[Ljava/lang/Object;"
            else -> throw IllegalArgumentException("argument '$c'")
        })
        append(')')
        append(if (result) "Ljava/lang/Object;" else "V")
    }

    fun popped(a: Int) = pops + popsPerA * a
}

/**
 * The instructions that are runtime calls, by opcode (docs/PYTHON_PLATFORM.md §11.2). An instruction with a row
 * here needs no case in the builder or the code generator; control flow, and what the speculating tier specializes,
 * stays written by hand.
 */
internal object PyGeneric {
    @JvmField val table = arrayOfNulls<Generic>(PyOp.COUNT).also { t ->
        t[PyOp.LOAD_GLOBAL] = Generic("loadGlobal", "fk", pops = 0, result = true)
        t[PyOp.STORE_GLOBAL] = Generic("storeGlobal", "fkv", pops = 1, result = false, throws = false)
        t[PyOp.LOAD_ATTR] = Generic("loadAttr", "vk", pops = 1, result = true)
        t[PyOp.MAKE_FUNCTION] = Generic("makeFunction", "fk", pops = 0, result = true, throws = false,
            flags = OpInfo.REMOVABLE or OpInfo.ALLOCATES)
    }

    @JvmStatic fun of(op: Int): Generic? = table.getOrNull(op)
}
