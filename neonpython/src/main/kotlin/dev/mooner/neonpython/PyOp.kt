package dev.mooner.neonpython

/**
 * Python's bytecode: a stack machine with registers (a function's locals), as JavaScript's is. Each instruction is an
 * opcode and a fixed number of int operands ([operands]). The opcodes are below 500, where the dialect's own graph
 * operations start ([PyIr]), and so below the optimizing tier's core's (1000, docs/IR_NEUTRALITY.md): a [PyDialect]
 * graph holds them as they are.
 *
 * A new instruction needs, or something breaks without a word:
 * - its operand count ([operands]) and its stack effect ([stackEffect]);
 * - where it can go, if it jumps: PyCompiler's successors (maxStack and the unbound locals' dataflow read them), the
 *   builder's targets ([PyBuilder]'s body), and the loop headers ([PyDialect.backEdgeTargets]: a JUMP backwards);
 * - the registers it reads and writes, if any (the builder's regsRead and regWritten);
 * - its case in [PyInterpreter.run] (rare ones in a helper: the dispatch stays under 8,000 bytes, PyMethodSizeTest);
 * - in the tier: a row of [PyGeneric] where its code is a runtime call, else its cases in the builder and PyCodegen;
 * - the operand stack empty at every statement (exception handlers start with an empty one), and constants read from
 *   the block's constants, never baked into code (blocks of one graph share a class).
 */
object PyOp {
    const val NOP = 0
    /** k ( -- constants[k]) */
    const val LOAD_CONST = 1
    /** r ( -- local r) */
    const val LOAD_FAST = 2
    /** r (v -- ) */
    const val STORE_FAST = 3
    /** k ( -- the global or builtin named constants[k]) */
    const val LOAD_GLOBAL = 4
    /** k (v -- ) */
    const val STORE_GLOBAL = 5
    const val POP = 6
    const val DUP = 7
    /** (a b -- b a) */
    const val SWAP = 8
    /** r ( -- local r), or an UnboundLocalError where it has no value (PyCompiler: where it may have none) */
    const val LOAD_FAST_CHECK = 9
    // binary operators (a b -- a op b)
    const val ADD = 10
    const val SUB = 11
    const val MUL = 12
    const val TRUEDIV = 13
    const val FLOORDIV = 14
    const val MOD = 15
    const val POW = 16
    const val LSHIFT = 17
    const val RSHIFT = 18
    const val BITAND = 19
    const val BITOR = 20
    const val BITXOR = 21
    // comparisons (a b -- bool)
    const val LT = 30
    const val LE = 31
    const val GT = 32
    const val GE = 33
    const val EQ = 34
    const val NE = 35
    const val IS = 36
    const val IS_NOT = 37
    const val IN = 38
    const val NOT_IN = 39
    // unary (a -- op a)
    const val NEG = 40
    const val POS = 41
    const val NOT = 42
    const val INVERT = 43
    /** t: to t */
    const val JUMP = 50
    /** t (v -- ): to t where v is false, true */
    const val POP_JUMP_IF_FALSE = 51
    const val POP_JUMP_IF_TRUE = 52
    /** argc (f a1..an -- r) */
    const val CALL = 60
    /** (v -- ) the function's result */
    const val RETURN = 61
    /** n (v1..vn -- list), (v1..vn -- tuple), (k1 v1..kn vn -- dict) */
    const val BUILD_LIST = 70
    const val BUILD_TUPLE = 71
    const val BUILD_MAP = 72
    /** (obj key -- obj[key]) */
    const val SUBSCR = 73
    /** (v obj key -- ) obj[key] = v */
    const val STORE_SUBSCR = 74
    /** k (obj -- obj.name), the name constants[k] */
    const val LOAD_ATTR = 75
    /** (obj -- iterator) */
    const val GET_ITER = 76
    /** r k t ( -- ) register k = the next value of the iterator in register r; to t where it is done (k unchanged) */
    const val FOR_ITER = 77
    /** n (seq -- v(n-1)..v0): its n items, the first on top */
    const val UNPACK = 78
    /** k ( -- function) of the code constants[k], in this module */
    const val MAKE_FUNCTION = 79

    // functions and scopes (docs/PYTHON_PLATFORM.md §11, M1)
    /** n (f a1..am v1..vk names -- r): a call with keyword arguments, n = m + k, names a tuple of the k names */
    const val CALL_KW = 80
    /** a (f args [kwargs] -- r): a call with unpacking: args a list; kwargs (a = 1) a list of mappings */
    const val CALL_EX = 81
    /** (v -- cell) */
    const val MAKE_CELL = 82
    /** ( -- cell) a cell without a value */
    const val NEW_CELL = 83
    /** k ( -- cell) the cell of the function's free variable k */
    const val LOAD_FREE = 84
    /** k free (cell -- v) its value, or the error naming constants[k] (a free variable's where free is 1) */
    const val CELL_GET = 85
    /** (v cell -- ) */
    const val CELL_SET = 86
    /** k free (cell -- ) */
    const val CELL_DELETE = 87
    /** flag (attr fn -- fn): its defaults (1), keyword-only defaults (2), closure (8) */
    const val SET_FUNCTION_ATTRIBUTE = 88
    /** (list v -- list) */
    const val LIST_APPEND = 89
    /** (list iterable -- list) */
    const val LIST_EXTEND = 90
    /** (list -- tuple) */
    const val LIST_TO_TUPLE = 91
    /** (dict k v -- dict) */
    const val MAP_ADD = 92
    /** (dict mapping -- dict) */
    const val DICT_UPDATE = 93
    /** before after (seq -- items): before items, a list of the rest but after, after items, the first on top */
    const val UNPACK_EX = 94
    /** r ( -- ) local r without a value */
    const val DELETE_FAST = 95
    /** k ( -- ) */
    const val DELETE_GLOBAL = 96
    /** (obj key -- ) */
    const val DELETE_SUBSCR = 97
    /** k (obj -- ) */
    const val DELETE_ATTR = 98
    /** a (msg if a -- ) an AssertionError */
    const val ASSERT_FAIL = 99
    /** (v -- starred) a call's `*v`, which CALL_EX unpacks (its errors name the function) */
    const val WRAP_STAR = 100

    const val COUNT = 200

    @JvmField val operands = IntArray(COUNT).also { o ->
        for (op in intArrayOf(LOAD_CONST, LOAD_FAST, LOAD_FAST_CHECK, STORE_FAST, LOAD_GLOBAL, STORE_GLOBAL, JUMP, POP_JUMP_IF_FALSE,
            POP_JUMP_IF_TRUE, CALL, BUILD_LIST, BUILD_TUPLE, BUILD_MAP, LOAD_ATTR, UNPACK, MAKE_FUNCTION, CALL_KW, CALL_EX, LOAD_FREE,
            SET_FUNCTION_ATTRIBUTE, DELETE_FAST, DELETE_GLOBAL, DELETE_ATTR, ASSERT_FAIL)) o[op] = 1
        for (op in intArrayOf(CELL_GET, CELL_DELETE, UNPACK_EX)) o[op] = 2
        o[FOR_ITER] = 3
    }

    @JvmField val names = arrayOfNulls<String>(COUNT).also { n ->
        for (f in PyOp::class.java.declaredFields) {
            if (f.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isStatic(f.modifiers) && f.name != "COUNT") {
                val v = f.getInt(null)
                if (v in 0 until COUNT) n[v] = f.name
            }
        }
    }

    fun length(op: Int) = 1 + operands[op]

    /** The change of the operand stack's depth the instruction [op] with operand [a] makes. */
    fun stackEffect(op: Int, a: Int, b: Int = 0): Int = when (op) {
        CALL_KW, CALL_EX -> -1 - a
        NEW_CELL, LOAD_FREE -> 1
        CELL_SET, DELETE_SUBSCR, MAP_ADD -> -2
        CELL_DELETE, SET_FUNCTION_ATTRIBUTE, LIST_APPEND, LIST_EXTEND, DICT_UPDATE, DELETE_ATTR -> -1
        UNPACK_EX -> a + b
        ASSERT_FAIL -> -a
        LOAD_CONST, LOAD_FAST, LOAD_FAST_CHECK, LOAD_GLOBAL, DUP, MAKE_FUNCTION -> 1
        STORE_FAST, STORE_GLOBAL, POP, POP_JUMP_IF_FALSE, POP_JUMP_IF_TRUE, RETURN -> -1
        in ADD..BITXOR, in LT..NOT_IN, SUBSCR -> -1
        CALL -> -a
        BUILD_LIST, BUILD_TUPLE -> 1 - a
        BUILD_MAP -> 1 - 2 * a
        STORE_SUBSCR -> -3
        UNPACK -> a - 1
        else -> 0 // NOP, SWAP, unary operators, JUMP, LOAD_ATTR, GET_ITER, FOR_ITER
    }
    fun name(op: Int) = names.getOrNull(op) ?: "op$op"
}
