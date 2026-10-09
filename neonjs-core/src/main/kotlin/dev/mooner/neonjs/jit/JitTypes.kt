package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.Op

/**
 * Value kinds of the type analysis that lets [JvmCompiler] keep numbers and booleans unboxed.
 *
 * Soundness rules (docs/ARCHITECTURE.md, section JIT, "Unboxed numbers"):
 *  - NUM is a JS number held as a JVM `double`; INT a JS number that is an int32 (never -0) held as a JVM `int`; BOOL a
 *    boolean held as a JVM `int`; ANY anything held as an Object. INT is a NUM: where they meet, the int is widened.
 *  - A value is NUM only when every way of producing it yields a number: number literals, constants that are numbers
 *    (their kinds are part of the class identity, as classes are shared by code blocks with equal code), and operators
 *    that return numbers by definition (`-`, `*`, `/`, `%`, `**`, bitwise operators and shifts as soon as one operand is
 *    a number, since a BigInt operand then throws; `+` only when both operands are numbers; unary `+`; `++`/`--`/
 *    unary `-`/`~` of a number).
 *  - A value is INT only when it is an int32 by definition: integer literals and constants that are int32s (not -0),
 *    and the results of `&`, `|`, `^`, `<<`, `>>` and `~` (ToInt32 of something). Arithmetic is never done on ints:
 *    `+`, `-`, `*`, `/`, `%`, `**`, `++`, `--` and unary `-` widen their int operands and give NUM (int arithmetic would
 *    wrap, lose -0 or throw), and `>>>` gives NUM (a uint32).
 *  - Registers are invisible outside their function (captured, eval-visible and mapped-argument bindings live in
 *    environment slots), so only stores and loads in the function itself define them. A register is kept as a `double`
 *    only when every load of it sees a number on every path, exception edges included, and as an `int` when every load
 *    sees an INT.
 *  - Nothing else is inferred, and no check (bounds, TDZ, interrupt, stack depth) is ever removed because of a type.
 */
internal object JT {
    const val ANY: Byte = 0
    const val NUM: Byte = 1
    const val BOOL: Byte = 2
    /** Registers only: still the initial `undefined`. */
    const val UNDEF: Byte = 3
    const val INT: Byte = 4

    fun isNum(k: Byte) = k == NUM || k == INT

    fun join(a: Byte, b: Byte): Byte = if (a == b) a else if (isNum(a) && isNum(b)) NUM else ANY

    // binary operator classes, shared by the analysis and the code generator so that they cannot disagree
    const val GENERIC = 0
    /** Both operands NUM: inline JVM arithmetic or comparison. */
    const val NN = 1
    /** Left NUM, right ANY: a helper taking (double, Object). */
    const val NA = 2
    /** Left ANY, right NUM: a helper taking (Object, double). */
    const val AN = 3

    fun isArith(op: Int) = op == Op.SUB || op == Op.MUL || op == Op.DIV || op == Op.MOD || op == Op.EXP
    fun isBitwise(op: Int) = op == Op.BAND || op == Op.BOR || op == Op.BXOR || op == Op.SHL || op == Op.SAR || op == Op.SHR
    fun isCompare(op: Int) = op == Op.LT || op == Op.GT || op == Op.LE || op == Op.GE ||
        op == Op.EQ || op == Op.NE || op == Op.SEQ || op == Op.SNE

    /**
     * How the binary operator [op] is compiled for operand kinds [a] (left) and [b] (right): N stands for both number
     * kinds; BOOL counts as ANY.
     */
    fun binaryClass(op: Int, a: Byte, b: Byte): Int {
        if (op != Op.ADD && !isArith(op) && !isBitwise(op) && !isCompare(op)) return GENERIC
        val an = isNum(a)
        val bn = isNum(b)
        return when {
            an && bn -> NN
            an -> NA
            bn -> AN
            else -> GENERIC
        }
    }

    /**
     * For instructions compiled as Object code whose code consumes only the top operand stack values: how many (values
     * below them may stay unboxed). -1 for the others, before which every stack value is boxed.
     */
    fun pops(op: Int, a: Int): Int = when (op) {
        Op.PUSH_UNDEF, Op.PUSH_NULL, Op.PUSH_CONST, Op.LOAD_THIS, Op.LOAD_FUNCTION, Op.LOAD_NEW_TARGET, Op.LOAD_HOME,
        Op.LOAD_ARG, Op.LOAD_ENV, Op.LOAD_ENV_TDZ, Op.CHECK_ENV_TDZ, Op.LOAD_IMPORT, Op.LOAD_NAME, Op.LOAD_NAME_TYPEOF,
        Op.LOAD_GLOBAL, Op.LOAD_GLOBAL_TYPEOF, Op.LOAD_NAME_CALL, Op.GET_ENV, Op.CREATE_ARGUMENTS, Op.CREATE_REST,
        Op.NEW_OBJECT, Op.NEW_ARRAY, Op.MAKE_CLOSURE, Op.TEMPLATE_OBJECT, Op.NEW_REGEXP, Op.IMPORT_META, Op.THROW_ERROR,
        Op.LOAD_REG_TDZ, Op.CHECK_REG_TDZ -> 0
        Op.STORE_ENV, Op.STORE_GLOBAL, Op.STORE_NAME, Op.STORE_NAME_VAR, Op.INIT_NAME, Op.INIT_GLOBAL_LEX, Op.SET_ENV,
        Op.PUSH_WITH, Op.GET_PROP, Op.TO_STRING, Op.TYPEOF, Op.TO_OBJECT, Op.REQUIRE_COERCIBLE, Op.TO_PROPERTY_KEY,
        Op.RETURN, Op.THROW, Op.DELETE_PROP, Op.GET_ITERATOR, Op.CHECK_OBJECT, Op.GET_PROTO_OF, Op.GET_SUPER_BASE,
        Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC -> 1
        Op.PUT_PROP, Op.GET_ELEM, Op.CONCAT, Op.ARRAY_PUSH, Op.IN, Op.INSTANCEOF, Op.DELETE_ELEM, Op.GET_PRIVATE,
        Op.HAS_PRIVATE, Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.SHL, Op.SAR, Op.SHR, Op.BAND, Op.BOR, Op.BXOR,
        Op.EQ, Op.NE, Op.SEQ, Op.SNE, Op.LT, Op.GT, Op.LE, Op.GE -> 2
        Op.PUT_ELEM, Op.PUT_PRIVATE, Op.CALL_SPREAD -> 3
        Op.CALL, Op.TAIL_CALL -> a + 2
        Op.NEW -> a + 1
        else -> -1
    }

    /** Kind of the result of binary operator [op] compiled as [cls]; [int] is INT, or NUM where ints are not used. */
    fun binaryResult(op: Int, cls: Int, int: Byte): Byte = when {
        cls == GENERIC -> ANY
        isCompare(op) -> BOOL
        op == Op.ADD -> if (cls == NN) NUM else ANY // number + string is a concatenation
        isBitwise(op) && op != Op.SHR -> int
        else -> NUM
    }
}

/**
 * Forward data-flow analysis of a code block for [JvmCompiler]: the kind of every operand stack slot at every
 * instruction, the kind of every register, and which registers are kept as JVM `double` or `int` locals. Instructions
 * that are not reachable get no state (the generator emits nothing for them). Without [ints], nothing is INT (integers
 * are NUM, as before INT existed).
 */
internal class TypeAnalysis(private val input: JitInput, val ints: Boolean, val elems: Boolean) {
    private val code = input.code
    /** Kind of the values that are int32s by definition: INT, or NUM without [ints]. */
    val intKind = if (ints) JT.INT else JT.NUM
    /** Kinds of the operand stack before the instruction at each pc (null: unreachable). */
    val stackIn = arrayOfNulls<ByteArray>(code.size)
    private val regsIn = arrayOfNulls<ByteArray>(code.size)
    private val readKind = ByteArray(input.numRegs) { -1 }
    private val forceAny = BooleanArray(input.numRegs)
    /** How each register is held: NUM a JVM `double` local, INT an `int` local, ANY an Object local. */
    val regStorage = ByteArray(input.numRegs)

    fun reachable(pc: Int) = pc < code.size && stackIn[pc] != null

    /** Kind of register [r] before the instruction at [pc] (reachable). */
    fun regKind(pc: Int, r: Int): Byte = regsIn[pc]!![r]

    // the result kinds below are also what the code generator emits

    /** Kind of constant [k] when pushed. */
    fun constKind(k: Int): Byte = when (input.constKinds[k]) {
        JitInput.CONST_INT -> intKind
        JitInput.CONST_NUMBER -> JT.NUM
        else -> JT.ANY
    }

    /** Kind of the result of NEG, BNOT, INC, DEC or TO_NUMERIC on a value of kind [t]. */
    fun unaryResult(op: Int, t: Byte): Byte = when {
        !JT.isNum(t) -> JT.ANY
        op == Op.BNOT -> intKind
        op == Op.TO_NUMERIC -> t
        else -> JT.NUM // -0 is -0, and ++ / -- can leave the int32 range
    }

    /**
     * Kind of the result of PUT_ELEM, which is the value stored: unboxed (the value's kind) when the key and the value are
     * numbers, unless [elems] is off.
     */
    fun putElemResult(key: Byte, value: Byte): Byte = if (elems && JT.isNum(key) && JT.isNum(value)) value else JT.ANY

    /** Kind of the result of TO_NUMBER (unary plus) on a value of kind [t]. */
    fun toNumberResult(t: Byte): Byte = when (t) {
        JT.INT -> JT.INT
        JT.BOOL -> intKind
        else -> JT.NUM
    }

    fun run(): TypeAnalysis {
        // A handler starts with just the exception on the stack, as in the JVM, which empties the operand stack when it
        // catches; handlers whose code expects values below it (destructuring's iterator close) only throw. Code that
        // used such a value or merged back would be rejected below (underflow, or depths differing at a merge).
        val entryRegs = ByteArray(input.numRegs) { JT.UNDEF }
        input.paramRegs?.forEach { r -> if (r >= 0) entryRegs[r] = JT.ANY }
        val work = ArrayDeque<Int>()
        merge(0, entryRegs, ByteArray(0), work)
        while (work.isNotEmpty()) {
            val pc = work.removeFirst()
            step(pc, regsIn[pc]!!.copyOf(), stackIn[pc]!!, work)
        }
        for (r in 0 until input.numRegs) regStorage[r] = if (forceAny[r] || !JT.isNum(readKind[r])) JT.ANY else readKind[r]
        return this
    }

    private fun merge(pc: Int, regs: ByteArray, stack: ByteArray, work: ArrayDeque<Int>) {
        if (pc >= code.size) throw JitBailout("control flow leaves the code")
        val old = stackIn[pc]
        if (old == null) {
            stackIn[pc] = stack.copyOf()
            regsIn[pc] = regs.copyOf()
            work.addLast(pc)
            return
        }
        if (old.size != stack.size) throw JitBailout("operand stack depth differs at $pc")
        var changed = false
        for (k in old.indices) {
            val j = JT.join(old[k], stack[k])
            if (j != old[k]) { old[k] = j; changed = true }
        }
        val oldRegs = regsIn[pc]!!
        for (k in oldRegs.indices) {
            val j = JT.join(oldRegs[k], regs[k])
            if (j != oldRegs[k]) { oldRegs[k] = j; changed = true }
        }
        if (changed) work.addLast(pc)
    }

    private fun read(r: Int, kind: Byte) {
        val k = if (JT.isNum(kind)) kind else JT.ANY
        readKind[r] = if (readKind[r] < 0) k else JT.join(readKind[r], k)
    }

    private fun step(pc: Int, regs: ByteArray, stackInPc: ByteArray, work: ArrayDeque<Int>) {
        // exception edges: any instruction in a protected range may throw with the registers it starts with
        val h = input.handlers
        var i = 0
        while (i < h.size) {
            if (pc >= h[i] && pc < h[i + 1]) merge(h[i + 2], regs, byteArrayOf(JT.ANY), work)
            i += 4
        }
        val op = code[pc]
        val len = Op.length(code, pc)
        val a = if (Op.operands[op] >= 1) code[pc + 1] else 0
        val b = if (Op.operands[op] >= 2) code[pc + 2] else 0
        val s = KindStack().also { it.set(stackInPc) }
        fun next() = merge(pc + len, regs, s.toArray(), work)
        when (op) {
            Op.PUSH_INT -> { s.push(intKind); next() }
            Op.PUSH_CONST -> { s.push(constKind(a)); next() }
            Op.PUSH_TRUE, Op.PUSH_FALSE -> { s.push(JT.BOOL); next() }
            Op.LOAD_REG -> {
                read(a, regs[a])
                s.push(if (JT.isNum(regs[a])) regs[a] else JT.ANY)
                next()
            }
            Op.STORE_REG -> {
                val k = s.pop()
                regs[a] = if (JT.isNum(k)) k else JT.ANY
                next()
            }
            Op.POP -> { s.pop(); next() }
            Op.DUP -> { s.push(s.peek(0)); next() }
            Op.DUP2 -> { s.permute(2, PERM_DUP2); next() }
            Op.DUP3 -> { s.permute(3, PERM_DUP3); next() }
            Op.SWAP -> { s.permute(2, PERM_SWAP); next() }
            Op.ROT3 -> { s.permute(3, PERM_ROT3); next() }
            Op.ROT4 -> { s.permute(4, PERM_ROT4); next() }
            Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR, Op.SHR,
            Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.SEQ, Op.SNE -> {
                val rb = s.pop()
                val ra = s.pop()
                s.push(JT.binaryResult(op, JT.binaryClass(op, ra, rb), intKind))
                next()
            }
            Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC -> {
                val t = s.pop()
                s.push(unaryResult(op, t))
                next()
            }
            Op.TO_NUMBER -> { s.push(toNumberResult(s.pop())); next() } // unary plus: a number, or a TypeError for BigInt
            Op.NOT -> { s.pop(); s.push(JT.BOOL); next() }
            Op.JUMP_IF_TRUE, Op.JUMP_IF_FALSE -> {
                s.pop()
                next()
                merge(a, regs, s.toArray(), work)
            }
            Op.JUMP -> merge(a, regs, s.toArray(), work)
            Op.GET_ELEM -> { s.pop(); s.pop(); s.push(JT.ANY); next() }
            Op.PUT_ELEM -> {
                val v = s.pop()
                val k = s.pop()
                s.pop()
                s.push(putElemResult(k, v))
                next()
            }
            Op.RETURN, Op.THROW, Op.THROW_ERROR -> {}
            else -> generic(pc, op, len, a, b, regs, s, work)
        }
    }

    /** Any other instruction: compiled as before, after boxing every stack slot; its results are ANY. */
    private fun generic(pc: Int, op: Int, len: Int, a: Int, b: Int, regs: ByteArray, s: KindStack, work: ArrayDeque<Int>) {
        when (op) {
            Op.LOAD_REG_TDZ, Op.CHECK_REG_TDZ, Op.ITER_STEP, Op.ITER_STEP_U, Op.ITER_REST, Op.ITER_CLOSE,
            Op.ITER_CLOSE_THROW, Op.FOR_IN_NEXT -> { read(a, JT.ANY); forceAny[a] = true }
            Op.INIT_THIS_REG -> { read(a, JT.ANY); forceAny[a] = true; regs[a] = JT.ANY }
            Op.MAKE_METHOD -> { read(b, JT.ANY); forceAny[b] = true }
            Op.JUMP_TABLE -> { val r = code[pc + 1]; read(r, JT.ANY); forceAny[r] = true } // variable operands: a is not set
        }
        val pops = JT.pops(op, a)
        if (pops >= 0) {
            // only the top values are consumed: the kinds below are kept
            if (pops > s.size) throw JitBailout("operand stack underflow at $pc")
            val pushes = pops + effect(op, a, b)
            repeat(pops) { s.pop() }
            repeat(pushes) { s.push(JT.ANY) }
            if (op != Op.RETURN && op != Op.THROW && op != Op.THROW_ERROR) merge(pc + len, regs, s.toArray(), work)
            return
        }
        val before = s.size
        val depth = before + effect(op, a, b)
        if (depth < 0) throw JitBailout("operand stack underflow at $pc")
        val all = ByteArray(depth) // ANY
        when {
            op == Op.JUMP_TABLE -> {
                val n = code[pc + 2]
                for (t in 0..n) merge(code[pc + 3 + t], regs, all, work)
            }
            op == Op.ITER_STEP || op == Op.FOR_IN_NEXT -> {
                // the value is pushed on the fall-through edge only
                merge(pc + len, regs, all, work)
                merge(code[pc + len - 1], regs, ByteArray(before), work)
            }
            Op.isJump(op) -> {
                merge(pc + len, regs, all, work)
                merge(code[pc + len - 1], regs, all, work)
            }
            else -> merge(pc + len, regs, all, work)
        }
    }

    /** Stack effect of an instruction (the emitter's rule, EmitterBase.effect). */
    private fun effect(op: Int, a: Int, b: Int): Int {
        val e = Op.effects[op]
        if (e != Op.VAR) return e
        return when (op) {
            Op.CALL, Op.TAIL_CALL, Op.CALL_EVAL, Op.SUPER_CALL, Op.COPY_DATA_PROPS_EXCL -> -(a + 1)
            Op.NEW -> -a
            Op.NEW_OBJECT_LITERAL -> 1 - b
            Op.MAKE_CLASS -> 2 - ((if (b and 1 != 0) 1 else 0) + (if (b and 2 != 0) 1 else 0))
            Op.DECLARE_GLOBALS, Op.DECLARE_EVAL -> -b
            else -> throw JitBailout("no stack effect for ${Op.names[op]}")
        }
    }

    companion object {
        // permutations of the top n slots (0 = deepest of them), as pushed afterwards
        @JvmField val PERM_DUP2 = intArrayOf(0, 1, 0, 1)
        @JvmField val PERM_DUP3 = intArrayOf(0, 1, 2, 0, 1, 2)
        @JvmField val PERM_SWAP = intArrayOf(1, 0)
        @JvmField val PERM_ROT3 = intArrayOf(2, 0, 1)
        @JvmField val PERM_ROT4 = intArrayOf(3, 0, 1, 2)
    }
}

/** A stack of value kinds (the operand stack of the analysis and of the code generator). */
internal class KindStack {
    private var a = ByteArray(16)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (n > a.size) a = a.copyOf(maxOf(n, a.size * 2))
    }

    fun set(kinds: ByteArray) {
        ensure(kinds.size)
        kinds.copyInto(a)
        size = kinds.size
    }

    fun push(b: Byte) {
        ensure(size + 1)
        a[size++] = b
    }

    fun pop(): Byte {
        if (size == 0) throw JitBailout("operand stack underflow")
        return a[--size]
    }

    fun peek(depth: Int): Byte {
        if (depth >= size) throw JitBailout("operand stack underflow")
        return a[size - 1 - depth]
    }

    operator fun get(i: Int) = a[i]

    fun setKind(i: Int, k: Byte) {
        a[i] = k
    }

    /** Replaces the top [n] kinds by [order] (indices into those n, 0 = deepest). */
    fun permute(n: Int, order: IntArray) {
        if (n > size) throw JitBailout("operand stack underflow")
        val top = a.copyOfRange(size - n, size)
        size -= n
        for (k in order) push(top[k])
    }

    fun toArray(): ByteArray = a.copyOf(size)

    fun matches(kinds: ByteArray): Boolean {
        if (kinds.size != size) return false
        for (i in 0 until size) if (a[i] != kinds[i]) return false
        return true
    }

    fun allAny(n: Int): Boolean {
        for (i in size - n until size) if (a[i] != JT.ANY) return false
        return true
    }

    fun setAllAny(depth: Int) {
        ensure(depth)
        a.fill(JT.ANY, 0, depth)
        size = depth
    }
}
