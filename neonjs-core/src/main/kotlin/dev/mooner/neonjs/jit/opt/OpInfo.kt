package dev.mooner.neonjs.jit.opt

/**
 * What the optimizing tier's passes know of an operation (docs/IR_NEUTRALITY.md, "OpInfo"): its effects as flags, and
 * how loop-invariant code motion may move it. A language's operations come from its [OpTable]; the passes ask these
 * questions of the table and list no operations of their own.
 *
 * The flags say what an operation may do. An exit (leaving for the interpreter where a speculation fails) is none of
 * them: it is the instruction's state ([Inst.state]), which every pass keeps.
 */
open class OpInfo(@JvmField val name: String, @JvmField val flags: Int, @JvmField val hoist: Int = HOIST_NEVER) {
    /** The flags of [i]: [flags], unless what the operation does depends on its operands. */
    open fun flagsOf(i: Inst): Int = flags

    /** How LICM may move [i] out of a loop: [hoist], unless that depends on its operands. */
    open fun hoistOf(i: Inst): Int = hoist

    /**
     * For a load moved as [HOIST_SLOT_LOAD] and a store with [WRITES_SLOT]: the location it reads or writes, a token
     * (the shape it is guarded by) and a slot. A loop's store to a location keeps the loads of that location in it.
     */
    open fun location(i: Inst, g: Graph): Pair<Any?, Int>? = null

    override fun toString() = name

    companion object {
        /** The result is all it computes: unused, it may be removed (an exit it has goes with it). */
        const val REMOVABLE = 1
        /**
         * With every input a number (the kind system's), the operation computes its result and nothing else: as
         * [REMOVABLE], and none of the effects below. Its other flags say what it may do otherwise.
         */
        const val NUMERIC_PURE = 2
        /** It takes every operand as an Object, whatever its kind: a number checked only for it would be boxed again. */
        const val OBJECT_OPERANDS = 4
        /** It may run code of the program (a call, a getter, a conversion calling a method). */
        const val RUNS_CODE = 8
        /** It may throw. */
        const val THROWS = 16
        /** It may write anything in the heap but environments, objects' shapes included. */
        const val WRITES_HEAP = 32
        /** It writes one slot of an object of a known layout, given by [location]. */
        const val WRITES_SLOT = 64
        /** It writes a variable of an environment. */
        const val WRITES_ENV = 128
        /** It makes an object. */
        const val ALLOCATES = 256
        /**
         * LICM moves no load out of a loop that runs it, as if it wrote anything: what it does was not looked at closely
         * enough to say (no more than that; it may be cleared once it has been).
         */
        const val LICM_BARRIER = 512

        /** What an operation of no table may do: anything. */
        const val ANYTHING = RUNS_CODE or THROWS or WRITES_HEAP

        /** LICM moves it: never; always (its inputs invariant); with numbers for inputs; with them and no exit. */
        const val HOIST_NEVER = 0
        const val HOIST_ALWAYS = 1
        const val HOIST_NUMERIC = 2
        const val HOIST_NUMERIC_NO_EXIT = 3
        /** A load: out of a loop that writes nothing ([OpTable.clean]). */
        const val HOIST_LOAD = 4
        /** A load of a [location]: out of a loop that writes nothing but other locations. */
        const val HOIST_SLOT_LOAD = 5
        /** A load of a variable of the body's environment: out of a loop that writes no environment. */
        const val HOIST_ENV_LOAD = 6
    }
}

/**
 * The operations of a language's IR: those of its bytecode and those its builder makes. (Internal until the kind system
 * it asks about is an interface of its own: docs/IR_NEUTRALITY.md, step 2.)
 */
internal interface OpTable {
    fun info(op: Int): OpInfo

    /** Whether every input of [i] is a number. */
    fun numeric(i: Inst, kinds: Kinds): Boolean = i.inputs.all { kinds.system.isNumeric(kinds.of(it)) }

    private fun flags(i: Inst, kinds: Kinds): Int {
        val info = info(i.op)
        val f = info.flagsOf(i)
        return if (f and OpInfo.NUMERIC_PURE != 0 && numeric(i, kinds)) OpInfo.REMOVABLE else f
    }

    /** Whether [i] only computes its result (or exits): unused, it may go. */
    fun removable(i: Inst, kinds: Kinds): Boolean = flags(i, kinds) and OpInfo.REMOVABLE != 0

    /** Whether [i] neither runs code nor throws (an exit is not a throw). */
    fun quiet(i: Inst, kinds: Kinds): Boolean = flags(i, kinds) and (OpInfo.RUNS_CODE or OpInfo.THROWS) == 0

    /** Whether [i] neither runs code nor writes anything but a slot of a known layout or an environment variable. */
    fun clean(i: Inst, kinds: Kinds): Boolean =
        flags(i, kinds) and (OpInfo.RUNS_CODE or OpInfo.WRITES_HEAP or OpInfo.LICM_BARRIER) == 0

    /** Whether [i] neither runs code nor writes an object (its shape or its slots). */
    fun keepsObjects(i: Inst, kinds: Kinds): Boolean =
        flags(i, kinds) and (OpInfo.RUNS_CODE or OpInfo.WRITES_HEAP or OpInfo.WRITES_SLOT) == 0

    /** Whether [i] takes its operands as Objects whatever their kinds. */
    fun objectOperands(i: Inst): Boolean = info(i.op).flagsOf(i) and OpInfo.OBJECT_OPERANDS != 0
}
