package dev.mooner.neonjs.compiler

/**
 * Stack bytecode opcodes shared by the interpreter and the JVM bytecode backend. Each instruction is an opcode
 * followed by a fixed number of int operands (except JUMP_TABLE: reg count t0..t(count-1) default).
 * GENERATED from the table at the bottom of this file; keep [names], [operands] and [effects] in sync.
 */
object Op {
    const val VAR = Int.MIN_VALUE

    const val NOP = 0
    const val PUSH_UNDEF = 1
    const val PUSH_NULL = 2
    const val PUSH_TRUE = 3
    const val PUSH_FALSE = 4
    const val PUSH_CONST = 5
    const val PUSH_INT = 6
    const val POP = 7
    const val DUP = 8
    const val DUP2 = 9
    const val DUP3 = 10
    const val SWAP = 11
    const val ROT3 = 12  // (a b c -- c a b)
    const val ROT4 = 13  // (a b c d -- d a b c)
    const val LOAD_REG = 14
    const val STORE_REG = 15
    const val LOAD_REG_TDZ = 16
    const val CHECK_REG_TDZ = 17
    const val PUSH_SCOPE = 18
    const val POP_SCOPE = 19
    const val COPY_SCOPE = 20
    const val GET_ENV = 21
    const val SET_ENV = 22
    const val PUSH_WITH = 23
    const val LOAD_ENV = 24
    const val STORE_ENV = 25
    const val LOAD_ENV_TDZ = 26
    const val CHECK_ENV_TDZ = 27
    const val LOAD_NAME = 28
    const val LOAD_NAME_TYPEOF = 29
    const val LOAD_NAME_CALL = 30
    const val STORE_NAME = 31
    const val STORE_NAME_VAR = 32
    const val DELETE_NAME = 33
    const val INIT_NAME = 34
    const val LOAD_GLOBAL = 35
    const val LOAD_GLOBAL_TYPEOF = 36
    const val STORE_GLOBAL = 37
    const val INIT_GLOBAL_LEX = 38
    const val LOAD_THIS = 39
    const val LOAD_FUNCTION = 40
    const val LOAD_NEW_TARGET = 41
    const val LOAD_HOME = 42
    const val CREATE_ARGUMENTS = 43
    const val CREATE_REST = 44
    const val LOAD_ARG = 45
    const val INIT_THIS_REG = 46
    const val INIT_THIS_ENV = 47
    const val INIT_THIS_NAME = 48
    const val THROW = 49
    const val THROW_ERROR = 50  // kind msgK; kind: 0 type, 1 reference, 2 syntax, 3 range
    const val GET_PROP = 51  // (obj -- v)
    const val PUT_PROP = 52  // (obj v -- v)
    const val GET_ELEM = 53  // (obj key -- v)
    const val PUT_ELEM = 54  // (obj key v -- v)
    const val DELETE_PROP = 55
    const val DELETE_ELEM = 56
    const val TO_PROPERTY_KEY = 57
    const val TO_KEY_FOR_BASE = 58  // (obj key -- obj key') TypeError on nullish base
    const val GET_SUPER = 59  // (this key base -- v)
    const val PUT_SUPER = 60  // (this key base v -- v)
    const val GET_SUPER_BASE = 61  // (home -- base)
    const val GET_PRIVATE = 62  // (obj pn -- v)
    const val PUT_PRIVATE = 63  // (obj pn v -- v)
    const val HAS_PRIVATE = 64  // (pn obj -- bool)
    const val ADD = 65
    const val SUB = 66
    const val MUL = 67
    const val DIV = 68
    const val MOD = 69
    const val EXP = 70
    const val SHL = 71
    const val SAR = 72
    const val SHR = 73
    const val BAND = 74
    const val BOR = 75
    const val BXOR = 76
    const val EQ = 77
    const val NE = 78
    const val SEQ = 79
    const val SNE = 80
    const val LT = 81
    const val GT = 82
    const val LE = 83
    const val GE = 84
    const val INSTANCEOF = 85
    const val IN = 86
    const val NEG = 87
    const val TO_NUMBER = 88
    const val NOT = 89
    const val BNOT = 90
    const val TYPEOF = 91
    const val TO_NUMERIC = 92
    const val INC = 93
    const val DEC = 94
    const val TO_STRING = 95
    const val CONCAT = 96
    const val TO_OBJECT = 97
    const val REQUIRE_COERCIBLE = 98
    const val CHECK_OBJECT = 99
    const val JUMP = 100
    const val JUMP_IF_TRUE = 101
    const val JUMP_IF_FALSE = 102
    const val JUMP_IF_NULLISH = 103
    const val JUMP_IF_NOT_NULLISH = 104
    const val JUMP_IF_UNDEFINED = 105
    const val JUMP_IF_NOT_UNDEFINED = 106
    const val JUMP_TABLE = 107  // reg count t0..t(count-1) default
    const val RETURN = 108
    const val CALL = 109  // argc (f this a1..an -- r)
    const val CALL_SPREAD = 110  // (f this arr -- r)
    const val NEW = 111  // argc (f a1..an -- r)
    const val NEW_SPREAD = 112  // (f arr -- r)
    const val CALL_EVAL = 113  // argc flags
    const val CALL_EVAL_SPREAD = 114
    const val SUPER_CALL = 115  // argc (func newTarget a1..an -- r)
    const val SUPER_CALL_SPREAD = 116
    const val GET_PROTO_OF = 117
    const val INIT_INSTANCE = 118  // (obj fn -- obj)
    const val NEW_OBJECT = 119
    const val NEW_ARRAY = 120
    const val ARRAY_PUSH = 121
    const val ARRAY_HOLE = 122
    const val ARRAY_SPREAD = 123
    const val DEFINE_FIELD = 124  // keyK (obj v -- obj)
    const val DEFINE_FIELD_ELEM = 125  // (obj key v -- obj)
    const val DEFINE_GETTER = 126  // enumerable (obj key fn -- obj)
    const val DEFINE_SETTER = 127
    const val DEFINE_METHOD = 128
    const val COPY_DATA_PROPS = 129  // (target src -- target)
    const val COPY_DATA_PROPS_EXCL = 130  // n (target src k1..kn -- target)
    const val SET_PROTO = 131  // (obj v -- obj)
    const val SET_FUNCTION_NAME = 132  // prefixK (key fn -- key fn)
    const val MAKE_CLOSURE = 133
    const val MAKE_METHOD = 134  // templateK homeReg
    const val MAKE_CLASS = 135  // templateK flags ([heritage] [name] -- proto ctor)
    const val NEW_PRIVATE_NAME = 136
    const val ADD_FIELD = 137  // (ctor key fn -- )
    const val ADD_PRIVATE_METHOD = 138  // kind (ctor pn fn -- )
    const val STATIC_PRIVATE_METHOD = 139
    const val RUN_FIELD = 140  // (obj key fn -- )
    const val GET_ITERATOR = 141
    const val GET_ASYNC_ITERATOR = 142
    const val ITER_STEP = 143  // r t ( -- v) jumps to t when done
    const val ITER_STEP_U = 144  // r ( -- v|undefined)
    const val ITER_REST = 145  // r ( -- array)
    const val ITER_CLOSE = 146
    const val ITER_CLOSE_THROW = 147
    const val ITER_NEXT_CALL = 148  // r ( -- result)
    const val ITER_RESULT_STEP = 149  // r t (result -- value) jumps when done (pops)
    const val ASYNC_ITER_CLOSE = 150  // r mode t ( -- result) jumps when no return method
    const val FOR_IN_START = 151
    const val FOR_IN_NEXT = 152  // r t ( -- key)
    const val GENERATOR_INIT = 153
    const val YIELD = 154  // (v -- received)
    const val YIELD_RAW = 155  // (iterResult -- received)
    const val RESUME_DISPATCH = 156  // tReturn
    const val RESUME_MODE = 157
    const val AWAIT = 158
    const val AWAIT_IGNORE = 159
    const val AWAIT_CATCH = 160  // modeReg
    const val YSTAR = 161  // itReg modeReg tDone
    const val YSTAR_ASYNC_CALL = 162
    const val YSTAR_ASYNC_RESULT = 163
    const val DEBUGGER = 164
    const val TEMPLATE_OBJECT = 165
    const val NEW_REGEXP = 166
    const val IMPORT_META = 167
    const val DYNAMIC_IMPORT = 168  // (spec opts -- promise)
    const val DECLARE_GLOBALS = 169  // infoK n (fn1..fnn -- )
    const val DECLARE_EVAL = 170
    const val ADD_DISPOSABLE = 171  // hint (cap v -- )
    const val DERIVED_RETURN = 172
    const val RESOLVE_NAME = 173
    const val GET_REF = 174
    const val PUT_REF = 175
    const val LOAD_IMPORT = 176
    const val DISPOSE_NEW = 177  // ( -- cap)
    const val DISPOSE_SYNC = 178  // (cap kind value -- ) DisposeResources, throws if abrupt
    const val DISPOSE_BEGIN = 179  // (cap kind value -- disposal)
    const val DISPOSE_STEP = 180  // (disposal -- awaitValue more)
    const val DISPOSE_AWAITED = 181  // (result disposal mode -- )
    const val DISPOSE_END = 182  // (disposal -- ) throws if abrupt
    const val TAIL_CALL = 183  // argc (fn this args -- v): call in tail position (frame replaced for closures)
    const val DYNAMIC_IMPORT_PHASE = 184  // phaseK (spec opts -- promise): import.defer() / import.source()
    const val CLASS_DEF_NEW = 185
    const val CLASS_ELEMENT = 186
    const val CLASS_FINISH = 187
    const val CLASS_DECORATE = 188
    const val CLASS_STATIC_INIT = 189
    const val NEW_OBJECT_LITERAL = 190  // site n (v1..vn -- obj)
    const val COUNT = 191

    @JvmField val names: Array<String> = arrayOf("NOP", "PUSH_UNDEF", "PUSH_NULL", "PUSH_TRUE", "PUSH_FALSE", "PUSH_CONST", "PUSH_INT", "POP", "DUP", "DUP2", "DUP3", "SWAP", "ROT3", "ROT4", "LOAD_REG", "STORE_REG", "LOAD_REG_TDZ", "CHECK_REG_TDZ", "PUSH_SCOPE", "POP_SCOPE", "COPY_SCOPE", "GET_ENV", "SET_ENV", "PUSH_WITH", "LOAD_ENV", "STORE_ENV", "LOAD_ENV_TDZ", "CHECK_ENV_TDZ", "LOAD_NAME", "LOAD_NAME_TYPEOF", "LOAD_NAME_CALL", "STORE_NAME", "STORE_NAME_VAR", "DELETE_NAME", "INIT_NAME", "LOAD_GLOBAL", "LOAD_GLOBAL_TYPEOF", "STORE_GLOBAL", "INIT_GLOBAL_LEX", "LOAD_THIS", "LOAD_FUNCTION", "LOAD_NEW_TARGET", "LOAD_HOME", "CREATE_ARGUMENTS", "CREATE_REST", "LOAD_ARG", "INIT_THIS_REG", "INIT_THIS_ENV", "INIT_THIS_NAME", "THROW", "THROW_ERROR", "GET_PROP", "PUT_PROP", "GET_ELEM", "PUT_ELEM", "DELETE_PROP", "DELETE_ELEM", "TO_PROPERTY_KEY", "TO_KEY_FOR_BASE", "GET_SUPER", "PUT_SUPER", "GET_SUPER_BASE", "GET_PRIVATE", "PUT_PRIVATE", "HAS_PRIVATE", "ADD", "SUB", "MUL", "DIV", "MOD", "EXP", "SHL", "SAR", "SHR", "BAND", "BOR", "BXOR", "EQ", "NE", "SEQ", "SNE", "LT", "GT", "LE", "GE", "INSTANCEOF", "IN", "NEG", "TO_NUMBER", "NOT", "BNOT", "TYPEOF", "TO_NUMERIC", "INC", "DEC", "TO_STRING", "CONCAT", "TO_OBJECT", "REQUIRE_COERCIBLE", "CHECK_OBJECT", "JUMP", "JUMP_IF_TRUE", "JUMP_IF_FALSE", "JUMP_IF_NULLISH", "JUMP_IF_NOT_NULLISH", "JUMP_IF_UNDEFINED", "JUMP_IF_NOT_UNDEFINED", "JUMP_TABLE", "RETURN", "CALL", "CALL_SPREAD", "NEW", "NEW_SPREAD", "CALL_EVAL", "CALL_EVAL_SPREAD", "SUPER_CALL", "SUPER_CALL_SPREAD", "GET_PROTO_OF", "INIT_INSTANCE", "NEW_OBJECT", "NEW_ARRAY", "ARRAY_PUSH", "ARRAY_HOLE", "ARRAY_SPREAD", "DEFINE_FIELD", "DEFINE_FIELD_ELEM", "DEFINE_GETTER", "DEFINE_SETTER", "DEFINE_METHOD", "COPY_DATA_PROPS", "COPY_DATA_PROPS_EXCL", "SET_PROTO", "SET_FUNCTION_NAME", "MAKE_CLOSURE", "MAKE_METHOD", "MAKE_CLASS", "NEW_PRIVATE_NAME", "ADD_FIELD", "ADD_PRIVATE_METHOD", "STATIC_PRIVATE_METHOD", "RUN_FIELD", "GET_ITERATOR", "GET_ASYNC_ITERATOR", "ITER_STEP", "ITER_STEP_U", "ITER_REST", "ITER_CLOSE", "ITER_CLOSE_THROW", "ITER_NEXT_CALL", "ITER_RESULT_STEP", "ASYNC_ITER_CLOSE", "FOR_IN_START", "FOR_IN_NEXT", "GENERATOR_INIT", "YIELD", "YIELD_RAW", "RESUME_DISPATCH", "RESUME_MODE", "AWAIT", "AWAIT_IGNORE", "AWAIT_CATCH", "YSTAR", "YSTAR_ASYNC_CALL", "YSTAR_ASYNC_RESULT", "DEBUGGER", "TEMPLATE_OBJECT", "NEW_REGEXP", "IMPORT_META", "DYNAMIC_IMPORT", "DECLARE_GLOBALS", "DECLARE_EVAL", "ADD_DISPOSABLE", "DERIVED_RETURN", "RESOLVE_NAME", "GET_REF", "PUT_REF", "LOAD_IMPORT", "DISPOSE_NEW", "DISPOSE_SYNC", "DISPOSE_BEGIN", "DISPOSE_STEP", "DISPOSE_AWAITED", "DISPOSE_END", "TAIL_CALL", "DYNAMIC_IMPORT_PHASE", "CLASS_DEF_NEW", "CLASS_ELEMENT", "CLASS_FINISH", "CLASS_DECORATE", "CLASS_STATIC_INIT", "NEW_OBJECT_LITERAL")
    @JvmField val operands: IntArray = intArrayOf(0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 1, 0, 0, 0, 0, 0, 2, 2, 3, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 2, 0, 0, 2, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, -1, 0, 1, 0, 1, 0, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 1, 2, 2, 1, 0, 1, 1, 0, 0, 0, 2, 1, 1, 1, 1, 1, 2, 3, 0, 2, 0, 0, 0, 1, 0, 0, 0, 1, 3, 3, 3, 0, 1, 1, 0, 0, 2, 2, 1, 0, 1, 0, 0, 2, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 0, 0, 0, 2)
    @JvmField val effects: IntArray = intArrayOf(0, 1, 1, 1, 1, 1, 1, -1, 1, 2, 3, 0, 0, 0, 1, -1, 1, 0, 0, 0, 0, 1, -1, -1, 1, -1, 1, 0, 1, 1, 2, -1, -1, 1, -1, 1, 1, -1, -1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, 0, 0, -1, -1, -2, 0, -1, 0, 0, -2, -3, 0, -1, -2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, -1, -1, -1, -1, -1, 0, -1, VAR, -2, VAR, -1, VAR, -2, VAR, -2, 0, -1, 1, 1, -1, 0, -1, -1, -2, -2, -2, -2, -1, VAR, -1, 0, 1, 1, VAR, 1, -3, -3, -3, -3, 0, 0, 1, 1, 1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, -1, VAR, VAR, -2, -1, 1, 0, -1, 1, 1, -3, -2, 1, -3, -1, VAR, -1, -1, -4, -1, -1, -1, VAR)

    fun length(code: IntArray, pc: Int): Int {
        val op = code[pc]
        if (op == JUMP_TABLE) return 3 + code[pc + 2] + 1
        return 1 + operands[op]
    }

    /** Ops whose last operand is a jump target. */
    fun isJump(op: Int): Boolean = op == JUMP || op == JUMP_IF_TRUE || op == JUMP_IF_FALSE || op == JUMP_IF_NULLISH ||
            op == JUMP_IF_NOT_NULLISH || op == JUMP_IF_UNDEFINED || op == JUMP_IF_NOT_UNDEFINED || op == ITER_STEP ||
            op == ITER_RESULT_STEP || op == ASYNC_ITER_CLOSE || op == FOR_IN_NEXT || op == RESUME_DISPATCH ||
            op == YSTAR || op == YSTAR_ASYNC_CALL || op == YSTAR_ASYNC_RESULT
}
