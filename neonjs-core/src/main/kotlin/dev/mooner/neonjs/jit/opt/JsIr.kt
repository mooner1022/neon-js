package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.Op

/**
 * The operations JavaScript's builder makes besides its bytecode's ([Op]): parameters, TDZ checks, what a call compiled
 * inlined binds (`this`, the environment, a construction's object and result), and the speculations (number checks,
 * shape-guarded slots, global cells, builtins computed in place). Numbered from 2000, past the core's ([Ir]).
 */
object JsIr {
    /** (no inputs) a parameter: [Inst.a] is its index. */
    const val PARAM = 2000
    /** (value) a TDZ check of a register's value: ReferenceError if uninitialized, [Inst.a] the name's constant. */
    const val CHECK_TDZ = 2001
    /**
     * (fn, this) `this` as a call of fn binds it (JitRt.inlineThis), [Inst.a] 1 where that is the value given or the
     * closure's own (strict code, arrows: nothing allocated, LICM may move it); (fn) its environment.
     */
    const val INLINE_THIS = 2002
    const val CLOSURE_ENV = 2003
    /**
     * (value) the value as a number (CHECK_NUMBER, a double) or an int32 (CHECK_INT32, not -0), where the profile only
     * saw such values: anything else exits to [Inst.state], after the instruction that produced it.
     */
    const val CHECK_NUMBER = 2004
    const val CHECK_INT32 = 2005
    /**
     * (obj) a property read through the shapes a monomorphic cache saw: [Inst.a] indexes the receiver's shape in the
     * graph's data, [Inst.c] the shapes of the prototypes up to the holder (or -1: an own property), [Inst.b] the slot.
     * (obj, value) STORE_SLOT writes an own property so. Another shape exits to [Inst.state], before the instruction.
     */
    const val LOAD_SLOT = 2006
    const val STORE_SLOT = 2007
    /**
     * (no inputs) a global variable read through the entry its site's cache held (GlobalCache.load): LOAD_GLOBAL_LEX
     * reads a lexical binding, [Inst.a] indexing the global environment's token in the graph's data and [Inst.b] being
     * the binding's index; LOAD_GLOBAL_SLOT reads a data property of the global object, [Inst.a] indexing its shape,
     * [Inst.b] being the slot and [Inst.c] the environment's lexical epoch. Anything else (another realm, a binding
     * not initialized, another shape or epoch) exits to [Inst.state], before the instruction.
     */
    const val LOAD_GLOBAL_LEX = 2008
    const val LOAD_GLOBAL_SLOT = 2009
    /**
     * (fn) the guard of a builtin computed in place: unless fn is a builtin of intrinsic [Inst.a] (NativeFunction.
     * intrinsic), an exit to [Inst.state], before the CALL. (args) MATH: that builtin's result, a double, as its Java
     * method computes it (MathIntrinsic), the arguments taken to numbers first.
     */
    const val CHECK_INTRINSIC = 2010
    const val MATH = 2011
    /**
     * (obj, value) a property added through the transition a monomorphic cache saw (PropCache.ADD): the receiver's shape
     * [Inst.a] (data index), the prototypes' [Inst.c] (or -1), then the map moves to shape [Inst.b] with the value
     * appended (PropertyMap.addTransition); another shape, a prototype's, or an object not extensible exits to
     * [Inst.state], before the instruction.
     */
    const val ADD_SLOT = 2012
    /**
     * (obj) `obj.length` where the profile saw int32s: an array's (its length an int32) or a string's; anything else
     * exits to [Inst.state], before the instruction.
     */
    const val LENGTH = 2013
    /**
     * (fn) the object a construction (`new`) of fn compiled inlined gives its body as `this` (JitRt.newThis), [Inst.a]
     * 1 for a class constructor (its fields are defined first: code runs); (value, obj) CONSTRUCT_RESULT, the result of
     * such a body's return: the value where it is an object, else obj.
     */
    const val NEW_THIS = 2014
    const val CONSTRUCT_RESULT = 2015
    /**
     * (pair) element [Inst.a] of the Object[] a helper returned for two values: MAKE_CLASS's (whose own result is that
     * pair) prototype, then constructor.
     */
    const val PART = 2016
    /**
     * (string, index) `string.charCodeAt(index)` behind CHECK_INTRINSIC (MathIntrinsic.CHAR_CODE_AT), a double: a JS
     * string's code unit at an int index in place, NaN past its ends; anything else through the builtin's steps
     * (StringBuiltins.charCodeAt: `this` made a string, which may run code).
     */
    const val CHAR_CODE = 2017

    /**
     * The arithmetic that gives an int of int inputs where the builder gave it a state (Kinds): an exit to that state,
     * before the instruction, where the result is not an int32 (or is -0).
     */
    fun intArith(op: Int) = op == Op.ADD || op == Op.SUB || op == Op.MUL || op == Op.MOD || op == Op.INC || op == Op.DEC || op == Op.NEG

    /** The name of a JavaScript operation: the bytecode's, the builder's, or the core's. */
    fun name(op: Int): String = when (op) {
        PARAM -> "Param"
        CHECK_TDZ -> "CheckTdz"
        INLINE_THIS -> "InlineThis"
        CLOSURE_ENV -> "ClosureEnv"
        CHECK_NUMBER -> "CheckNumber"
        CHECK_INT32 -> "CheckInt32"
        LOAD_SLOT -> "LoadSlot"
        STORE_SLOT -> "StoreSlot"
        LOAD_GLOBAL_LEX -> "LoadGlobalLex"
        LOAD_GLOBAL_SLOT -> "LoadGlobalSlot"
        CHECK_INTRINSIC -> "CheckIntrinsic"
        MATH -> "Math"
        ADD_SLOT -> "AddSlot"
        LENGTH -> "Length"
        NEW_THIS -> "NewThis"
        CONSTRUCT_RESULT -> "ConstructResult"
        PART -> "Part"
        CHAR_CODE -> "CharCode"
        else -> if (op in 0 until Op.COUNT) Op.names[op] else Ir.name(op)
    }
}
