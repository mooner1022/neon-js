package dev.mooner.neonwasm

import java.lang.Double.doubleToRawLongBits
import java.lang.Double.longBitsToDouble
import java.lang.Float.floatToRawIntBits
import java.lang.Float.intBitsToFloat

/**
 * Runs [Code]: one JVM call per wasm call, on the context's shared value stack. A frame's slots start at fp: its
 * locals, then its operand stack. Numbers are their bits in [WasmContext.stack] (i32 and f32 in the low 32);
 * references are in [WasmContext.refs] at the same index.
 *
 * The dispatch method stays under HotSpot's 8,000-byte limit on methods it compiles: the less common numeric
 * instructions are in [Numeric].
 */
internal object Interpreter {
    private const val MASK = 0xFFFFFFFFL

    fun run(ctx: WasmContext, f: ModuleFunction, fp: Int) {
        val code = f.code
        val c = code.code
        val inst = f.instance
        val agent = ctx.agent
        if (++agent.depth > agent.maxDepth) throw WasmTrap("call stack exhausted")
        if (--agent.callBudget < 0) {
            agent.callBudget = 1024
            agent.checkInterrupt()
        }
        ctx.ensure(fp + code.frameSize)
        var s = ctx.stack
        var r = ctx.refs
        val nl = code.nLocals
        if (nl > code.nParams) {
            java.util.Arrays.fill(s, fp + code.nParams, fp + nl, 0L)
            if (code.hasRefLocals) java.util.Arrays.fill(r, fp + code.nParams, fp + nl, null)
        }
        val mem = inst.memory
        var sp = fp + nl
        var pc = 0
        while (true) {
            when (val op = c[pc++]) {
                Op.UNREACHABLE -> throw WasmTrap("unreachable")
                Op.JUMP -> pc = c[pc]
                Op.BR -> {
                    val n = c[pc + 1]
                    val h = fp + c[pc + 2]
                    if (n == 1) {
                        s[h] = s[sp - 1]
                        r[h] = r[sp - 1]
                    } else if (n > 0) {
                        System.arraycopy(s, sp - n, s, h, n)
                        System.arraycopy(r, sp - n, r, h, n)
                    }
                    sp = h + n
                    pc = c[pc]
                }
                Op.IF -> pc = if (s[--sp].toInt() == 0) c[pc] else pc + 1
                Op.BR_IF_JUMP -> pc = if (s[--sp].toInt() != 0) c[pc] else pc + 1
                Op.BR_IF -> {
                    if (s[--sp].toInt() != 0) {
                        val n = c[pc + 1]
                        val h = fp + c[pc + 2]
                        if (n > 0) {
                            System.arraycopy(s, sp - n, s, h, n)
                            System.arraycopy(r, sp - n, r, h, n)
                        }
                        sp = h + n
                        pc = c[pc]
                    } else pc += 3
                }
                Op.BR_TABLE -> {
                    val n = c[pc]
                    val i = s[--sp].toInt()
                    val e = pc + 1 + 3 * (if (Integer.compareUnsigned(i, n) < 0) i else n)
                    val k = c[e + 1]
                    val h = fp + c[e + 2]
                    if (k > 0) {
                        System.arraycopy(s, sp - k, s, h, k)
                        System.arraycopy(r, sp - k, r, h, k)
                    }
                    sp = h + k
                    pc = c[e]
                }
                Op.LOOP -> if (--ctx.loopBudget < 0) {
                    ctx.loopBudget = WasmContext.LOOP_BUDGET
                    agent.checkInterrupt()
                }
                Op.RETURN -> {
                    val n = code.nResults
                    if (n == 1) {
                        s[fp] = s[sp - 1]
                        r[fp] = r[sp - 1]
                    } else if (n > 0) {
                        System.arraycopy(s, sp - n, s, fp, n)
                        System.arraycopy(r, sp - n, r, fp, n)
                    }
                    agent.depth--
                    return
                }
                Op.CALL -> {
                    sp = call(ctx, inst.funcs[c[pc++]], sp)
                    s = ctx.stack
                    r = ctx.refs
                }
                Op.CALL_INDIRECT -> {
                    val type = inst.module.types[c[pc]]
                    val table = inst.tables[c[pc + 1]]
                    pc += 2
                    val i = s[--sp].toInt()
                    if (Integer.compareUnsigned(i, table.size) >= 0) throw WasmTrap("undefined element")
                    val fn = table.elems[i] as WasmFunction? ?: throw WasmTrap("uninitialized element")
                    if (fn.type != type) throw WasmTrap("indirect call type mismatch")
                    sp = call(ctx, fn, sp)
                    s = ctx.stack
                    r = ctx.refs
                }
                Op.DROP -> sp--
                Op.SELECT -> {
                    sp -= 2
                    if (s[sp + 1].toInt() == 0) s[sp - 1] = s[sp]
                }
                Op.SELECT_REF -> {
                    sp -= 2
                    if (s[sp + 1].toInt() == 0) r[sp - 1] = r[sp]
                }
                Op.LOCAL_GET -> s[sp++] = s[fp + c[pc++]]
                Op.LOCAL_SET -> s[fp + c[pc++]] = s[--sp]
                Op.LOCAL_TEE -> s[fp + c[pc++]] = s[sp - 1]
                Op.LOCAL_GET_REF -> r[sp++] = r[fp + c[pc++]]
                Op.LOCAL_SET_REF -> r[fp + c[pc++]] = r[--sp]
                Op.LOCAL_TEE_REF -> r[fp + c[pc++]] = r[sp - 1]
                Op.GLOBAL_GET -> s[sp++] = inst.globals[c[pc++]].bits
                Op.GLOBAL_SET -> inst.globals[c[pc++]].bits = s[--sp]
                Op.GLOBAL_GET_REF -> r[sp++] = inst.globals[c[pc++]].ref
                Op.GLOBAL_SET_REF -> inst.globals[c[pc++]].ref = r[--sp]
                Op.TABLE_GET -> {
                    val t = inst.tables[c[pc++]]
                    val i = s[sp - 1].toInt()
                    if (Integer.compareUnsigned(i, t.size) >= 0) throw WasmTrap("out of bounds table access")
                    r[sp - 1] = t.elems[i]
                }
                Op.TABLE_SET -> {
                    val t = inst.tables[c[pc++]]
                    sp -= 2
                    val i = s[sp].toInt()
                    if (Integer.compareUnsigned(i, t.size) >= 0) throw WasmTrap("out of bounds table access")
                    t.elems[i] = r[sp + 1]
                }
                Op.I32_LOAD -> s[sp - 1] = LE.i32(mem!!.bytes, addr(mem, s[sp - 1], c[pc++], 4)).toLong()
                Op.I64_LOAD -> s[sp - 1] = LE.i64(mem!!.bytes, addr(mem, s[sp - 1], c[pc++], 8))
                Op.F32_LOAD -> s[sp - 1] = LE.i32(mem!!.bytes, addr(mem, s[sp - 1], c[pc++], 4)).toLong()
                Op.F64_LOAD -> s[sp - 1] = LE.i64(mem!!.bytes, addr(mem, s[sp - 1], c[pc++], 8))
                Op.I32_LOAD8_S, Op.I64_LOAD8_S -> s[sp - 1] = mem!!.bytes[addr(mem, s[sp - 1], c[pc++], 1)].toLong()
                Op.I32_LOAD8_U, Op.I64_LOAD8_U -> s[sp - 1] = mem!!.bytes[addr(mem, s[sp - 1], c[pc++], 1)].toLong() and 0xFF
                Op.I32_LOAD16_S, Op.I64_LOAD16_S -> s[sp - 1] = LE.i16(mem!!.bytes, addr(mem, s[sp - 1], c[pc++], 2)).toLong()
                Op.I32_LOAD16_U, Op.I64_LOAD16_U -> s[sp - 1] = LE.i16(mem!!.bytes, addr(mem, s[sp - 1], c[pc++], 2)).toLong() and 0xFFFF
                Op.I64_LOAD32_S -> s[sp - 1] = LE.i32(mem!!.bytes, addr(mem, s[sp - 1], c[pc++], 4)).toLong()
                Op.I64_LOAD32_U -> s[sp - 1] = LE.i32(mem!!.bytes, addr(mem, s[sp - 1], c[pc++], 4)).toLong() and MASK
                Op.I32_STORE, Op.F32_STORE, Op.I64_STORE32 -> {
                    sp -= 2
                    LE.set32(mem!!.bytes, addr(mem, s[sp], c[pc++], 4), s[sp + 1].toInt())
                }
                Op.I64_STORE, Op.F64_STORE -> {
                    sp -= 2
                    LE.set64(mem!!.bytes, addr(mem, s[sp], c[pc++], 8), s[sp + 1])
                }
                Op.I32_STORE8, Op.I64_STORE8 -> {
                    sp -= 2
                    mem!!.bytes[addr(mem, s[sp], c[pc++], 1)] = s[sp + 1].toByte()
                }
                Op.I32_STORE16, Op.I64_STORE16 -> {
                    sp -= 2
                    LE.set16(mem!!.bytes, addr(mem, s[sp], c[pc++], 2), s[sp + 1].toInt())
                }
                Op.MEMORY_SIZE -> s[sp++] = mem!!.pages.toLong()
                Op.MEMORY_GROW -> s[sp - 1] = mem!!.grow(s[sp - 1] and MASK, agent).toLong()
                Op.I32_CONST, Op.F32_CONST -> s[sp++] = c[pc++].toLong()
                Op.I64_CONST, Op.F64_CONST -> {
                    s[sp++] = (c[pc].toLong() and MASK) or (c[pc + 1].toLong() shl 32)
                    pc += 2
                }
                // the most common integer instructions here; the rest in Numeric
                0x45 -> s[sp - 1] = if (s[sp - 1].toInt() == 0) 1 else 0
                0x46 -> { sp--; s[sp - 1] = if (s[sp - 1].toInt() == s[sp].toInt()) 1 else 0 }
                0x47 -> { sp--; s[sp - 1] = if (s[sp - 1].toInt() != s[sp].toInt()) 1 else 0 }
                0x48 -> { sp--; s[sp - 1] = if (s[sp - 1].toInt() < s[sp].toInt()) 1 else 0 }
                0x49 -> { sp--; s[sp - 1] = if (Integer.compareUnsigned(s[sp - 1].toInt(), s[sp].toInt()) < 0) 1 else 0 }
                0x4A -> { sp--; s[sp - 1] = if (s[sp - 1].toInt() > s[sp].toInt()) 1 else 0 }
                0x4B -> { sp--; s[sp - 1] = if (Integer.compareUnsigned(s[sp - 1].toInt(), s[sp].toInt()) > 0) 1 else 0 }
                0x4C -> { sp--; s[sp - 1] = if (s[sp - 1].toInt() <= s[sp].toInt()) 1 else 0 }
                0x4D -> { sp--; s[sp - 1] = if (Integer.compareUnsigned(s[sp - 1].toInt(), s[sp].toInt()) <= 0) 1 else 0 }
                0x4E -> { sp--; s[sp - 1] = if (s[sp - 1].toInt() >= s[sp].toInt()) 1 else 0 }
                0x4F -> { sp--; s[sp - 1] = if (Integer.compareUnsigned(s[sp - 1].toInt(), s[sp].toInt()) >= 0) 1 else 0 }
                0x6A -> { sp--; s[sp - 1] = (s[sp - 1].toInt() + s[sp].toInt()).toLong() }
                0x6B -> { sp--; s[sp - 1] = (s[sp - 1].toInt() - s[sp].toInt()).toLong() }
                0x6C -> { sp--; s[sp - 1] = (s[sp - 1].toInt() * s[sp].toInt()).toLong() }
                0x71 -> { sp--; s[sp - 1] = s[sp - 1] and s[sp] }
                0x72 -> { sp--; s[sp - 1] = s[sp - 1] or s[sp] }
                0x73 -> { sp--; s[sp - 1] = s[sp - 1] xor s[sp] }
                0x74 -> { sp--; s[sp - 1] = (s[sp - 1].toInt() shl s[sp].toInt()).toLong() }
                0x75 -> { sp--; s[sp - 1] = (s[sp - 1].toInt() shr s[sp].toInt()).toLong() }
                0x76 -> { sp--; s[sp - 1] = (s[sp - 1].toInt() ushr s[sp].toInt()).toLong() }
                0x7C -> { sp--; s[sp - 1] = s[sp - 1] + s[sp] }
                0x7D -> { sp--; s[sp - 1] = s[sp - 1] - s[sp] }
                Op.REF_NULL -> r[sp++] = null
                Op.REF_IS_NULL -> {
                    s[sp - 1] = if (r[sp - 1] == null) 1 else 0
                }
                Op.REF_FUNC -> r[sp++] = inst.funcs[c[pc++]]
                Op.MEMORY_INIT -> {
                    sp -= 3
                    inst.memoryInit(inst.datas[c[pc++]], s[sp].toInt(), s[sp + 1].toInt(), s[sp + 2].toInt())
                }
                Op.DATA_DROP -> inst.datas[c[pc++]] = WasmInstance.NO_BYTES
                Op.MEMORY_COPY -> {
                    sp -= 3
                    Bulk.memoryCopy(mem!!, s[sp].toInt(), s[sp + 1].toInt(), s[sp + 2].toInt())
                }
                Op.MEMORY_FILL -> {
                    sp -= 3
                    Bulk.memoryFill(mem!!, s[sp].toInt(), s[sp + 1].toInt(), s[sp + 2].toInt())
                }
                Op.TABLE_INIT -> {
                    sp -= 3
                    inst.tableInit(inst.tables[c[pc + 1]], inst.elems[c[pc]], s[sp].toInt(), s[sp + 1].toInt(), s[sp + 2].toInt())
                    pc += 2
                }
                Op.ELEM_DROP -> inst.elems[c[pc++]] = WasmInstance.NO_REFS
                Op.TABLE_COPY -> {
                    sp -= 3
                    Bulk.tableCopy(inst.tables[c[pc]], inst.tables[c[pc + 1]], s[sp].toInt(), s[sp + 1].toInt(), s[sp + 2].toInt())
                    pc += 2
                }
                Op.TABLE_GROW -> {
                    sp--
                    s[sp - 1] = inst.tables[c[pc++]].grow(s[sp] and MASK, r[sp - 1]).toLong()
                }
                Op.TABLE_SIZE -> s[sp++] = inst.tables[c[pc++]].size.toLong()
                Op.TABLE_FILL -> {
                    sp -= 3
                    Bulk.tableFill(inst.tables[c[pc++]], s[sp].toInt(), r[sp + 1], s[sp + 2].toInt())
                }
                else -> sp = Numeric.exec(op, s, sp)
            }
        }
    }

    /** A call from wasm code: the arguments are the top of the stack; the new top is past the results. */
    @JvmStatic
    private fun call(ctx: WasmContext, fn: WasmFunction, sp: Int): Int {
        val t = fn.type
        val base = sp - t.params.size
        if (fn is ModuleFunction) Interpreter.run(ctx, fn, base) else ctx.callHost(fn, base, sp)
        return base + t.results.size
    }

    /** The effective address of an access of [size] bytes, or the trap. */
    @JvmStatic
    private fun addr(mem: WasmMemory, base: Long, offset: Int, size: Int): Int {
        val ea = (base and MASK) + (offset.toLong() and MASK)
        if (ea + size > mem.bytes.size) throw WasmTrap("out of bounds memory access")
        return ea.toInt()
    }
}

/** memory.copy, memory.fill, table.copy, table.fill: bounds checked before anything is written. */
internal object Bulk {
    private const val MASK = 0xFFFFFFFFL

    @JvmStatic fun memoryCopy(mem: WasmMemory, d: Int, s: Int, n: Int) {
        val b = mem.bytes
        val nl = n.toLong() and MASK
        if ((d.toLong() and MASK) + nl > b.size || (s.toLong() and MASK) + nl > b.size) throw WasmTrap("out of bounds memory access")
        System.arraycopy(b, s, b, d, n)
    }

    @JvmStatic fun memoryFill(mem: WasmMemory, d: Int, v: Int, n: Int) {
        val b = mem.bytes
        if ((d.toLong() and MASK) + (n.toLong() and MASK) > b.size) throw WasmTrap("out of bounds memory access")
        java.util.Arrays.fill(b, d, d + n, v.toByte())
    }

    @JvmStatic fun tableCopy(dst: WasmTable, src: WasmTable, d: Int, s: Int, n: Int) {
        val nl = n.toLong() and MASK
        if ((d.toLong() and MASK) + nl > dst.size || (s.toLong() and MASK) + nl > src.size) throw WasmTrap("out of bounds table access")
        System.arraycopy(src.elems, s, dst.elems, d, n)
    }

    @JvmStatic fun tableFill(t: WasmTable, d: Int, v: Any?, n: Int) {
        if ((d.toLong() and MASK) + (n.toLong() and MASK) > t.size) throw WasmTrap("out of bounds table access")
        java.util.Arrays.fill(t.elems, d, d + n, v)
    }
}

/**
 * The numeric instructions (0x45-0xC4, and 0xFC 0-7 as [Op.TRUNC_SAT_BASE] + n), on the stack's bits: each returns
 * the new top. Floats keep their bits exactly where wasm says so (neg, abs, copysign are bit operations; NaNs from
 * min, max and the roundings are quiet).
 */
internal object Numeric {
    private const val MASK = 0xFFFFFFFFL
    private const val TWO_63 = 9.223372036854775808E18
    private const val TWO_64 = 1.8446744073709551616E19

    @JvmStatic
    fun exec(op: Int, s: LongArray, sp: Int): Int = when (op) {
        in 0x45..0x4F, in 0x67..0x78 -> i32(op, s, sp)
        in 0x50..0x5A, in 0x79..0x8A -> i64(op, s, sp)
        in 0x5B..0x60, in 0x8B..0x98 -> f32(op, s, sp)
        in 0x61..0x66, in 0x99..0xA6 -> f64(op, s, sp)
        else -> conv(op, s, sp)
    }

    private fun b(v: Boolean): Long = if (v) 1L else 0L
    private fun f(bits: Long) = intBitsToFloat(bits.toInt())
    private fun fb(v: Float) = floatToRawIntBits(v).toLong()
    private fun d(bits: Long) = longBitsToDouble(bits)
    private fun db(v: Double) = doubleToRawLongBits(v)

    @JvmStatic
    private fun i32(op: Int, s: LongArray, sp: Int): Int {
        if (op == 0x45 || op in 0x67..0x69) {
            val a = s[sp - 1].toInt()
            s[sp - 1] = when (op) {
                0x45 -> b(a == 0)
                0x67 -> Integer.numberOfLeadingZeros(a).toLong()
                0x68 -> Integer.numberOfTrailingZeros(a).toLong()
                else -> Integer.bitCount(a).toLong()
            }
            return sp
        }
        val a = s[sp - 2].toInt()
        val x = s[sp - 1].toInt()
        s[sp - 2] = when (op) {
            0x46 -> b(a == x)
            0x47 -> b(a != x)
            0x48 -> b(a < x)
            0x49 -> b(Integer.compareUnsigned(a, x) < 0)
            0x4A -> b(a > x)
            0x4B -> b(Integer.compareUnsigned(a, x) > 0)
            0x4C -> b(a <= x)
            0x4D -> b(Integer.compareUnsigned(a, x) <= 0)
            0x4E -> b(a >= x)
            0x4F -> b(Integer.compareUnsigned(a, x) >= 0)
            0x6A -> (a + x).toLong()
            0x6B -> (a - x).toLong()
            0x6C -> (a * x).toLong()
            0x6D -> {
                if (x == 0) throw WasmTrap("integer divide by zero")
                if (a == Int.MIN_VALUE && x == -1) throw WasmTrap("integer overflow")
                (a / x).toLong()
            }
            0x6E -> {
                if (x == 0) throw WasmTrap("integer divide by zero")
                Integer.divideUnsigned(a, x).toLong()
            }
            0x6F -> {
                if (x == 0) throw WasmTrap("integer divide by zero")
                (a % x).toLong()
            }
            0x70 -> {
                if (x == 0) throw WasmTrap("integer divide by zero")
                Integer.remainderUnsigned(a, x).toLong()
            }
            0x71 -> (a and x).toLong()
            0x72 -> (a or x).toLong()
            0x73 -> (a xor x).toLong()
            0x74 -> (a shl x).toLong()
            0x75 -> (a shr x).toLong()
            0x76 -> (a ushr x).toLong()
            0x77 -> Integer.rotateLeft(a, x).toLong()
            else -> Integer.rotateRight(a, x).toLong()
        }
        return sp - 1
    }

    @JvmStatic
    private fun i64(op: Int, s: LongArray, sp: Int): Int {
        if (op == 0x50 || op in 0x79..0x7B) {
            val a = s[sp - 1]
            s[sp - 1] = when (op) {
                0x50 -> b(a == 0L)
                0x79 -> java.lang.Long.numberOfLeadingZeros(a).toLong()
                0x7A -> java.lang.Long.numberOfTrailingZeros(a).toLong()
                else -> java.lang.Long.bitCount(a).toLong()
            }
            return sp
        }
        val a = s[sp - 2]
        val x = s[sp - 1]
        s[sp - 2] = when (op) {
            0x51 -> b(a == x)
            0x52 -> b(a != x)
            0x53 -> b(a < x)
            0x54 -> b(java.lang.Long.compareUnsigned(a, x) < 0)
            0x55 -> b(a > x)
            0x56 -> b(java.lang.Long.compareUnsigned(a, x) > 0)
            0x57 -> b(a <= x)
            0x58 -> b(java.lang.Long.compareUnsigned(a, x) <= 0)
            0x59 -> b(a >= x)
            0x5A -> b(java.lang.Long.compareUnsigned(a, x) >= 0)
            0x7C -> a + x
            0x7D -> a - x
            0x7E -> a * x
            0x7F -> {
                if (x == 0L) throw WasmTrap("integer divide by zero")
                if (a == Long.MIN_VALUE && x == -1L) throw WasmTrap("integer overflow")
                a / x
            }
            0x80 -> {
                if (x == 0L) throw WasmTrap("integer divide by zero")
                java.lang.Long.divideUnsigned(a, x)
            }
            0x81 -> {
                if (x == 0L) throw WasmTrap("integer divide by zero")
                a % x
            }
            0x82 -> {
                if (x == 0L) throw WasmTrap("integer divide by zero")
                java.lang.Long.remainderUnsigned(a, x)
            }
            0x83 -> a and x
            0x84 -> a or x
            0x85 -> a xor x
            0x86 -> a shl x.toInt()
            0x87 -> a shr x.toInt()
            0x88 -> a ushr x.toInt()
            0x89 -> java.lang.Long.rotateLeft(a, x.toInt())
            else -> java.lang.Long.rotateRight(a, x.toInt())
        }
        return sp - 1
    }

    @JvmStatic
    private fun f32(op: Int, s: LongArray, sp: Int): Int {
        if (op in 0x8B..0x91) {
            val bits = s[sp - 1].toInt()
            val a = intBitsToFloat(bits)
            s[sp - 1] = when (op) {
                0x8B -> (bits and 0x7FFFFFFF).toLong()
                0x8C -> (bits xor Int.MIN_VALUE).toLong()
                // (a NaN quieted: Math's roundings return it as it is)
                else -> if (a.isNaN()) (bits or 0x00400000).toLong() else when (op) {
                    0x8D -> fb(Math.ceil(a.toDouble()).toFloat())
                    0x8E -> fb(Math.floor(a.toDouble()).toFloat())
                    0x8F -> fb((if (a < 0f) Math.ceil(a.toDouble()) else Math.floor(a.toDouble())).toFloat())
                    0x90 -> fb(Math.rint(a.toDouble()).toFloat())
                    else -> fb(Math.sqrt(a.toDouble()).toFloat())
                }
            }
            return sp
        }
        val a = f(s[sp - 2])
        val x = f(s[sp - 1])
        s[sp - 2] = when (op) {
            0x5B -> b(a == x)
            0x5C -> b(a != x)
            0x5D -> b(a < x)
            0x5E -> b(a > x)
            0x5F -> b(a <= x)
            0x60 -> b(a >= x)
            0x92 -> fb(a + x)
            0x93 -> fb(a - x)
            0x94 -> fb(a * x)
            0x95 -> fb(a / x)
            0x96 -> fb(if (a.isNaN() || x.isNaN()) a + x else Math.min(a, x))
            0x97 -> fb(if (a.isNaN() || x.isNaN()) a + x else Math.max(a, x))
            else -> ((s[sp - 2].toInt() and 0x7FFFFFFF) or (s[sp - 1].toInt() and Int.MIN_VALUE)).toLong()
        }
        return sp - 1
    }

    @JvmStatic
    private fun f64(op: Int, s: LongArray, sp: Int): Int {
        if (op in 0x99..0x9F) {
            val bits = s[sp - 1]
            val a = d(bits)
            s[sp - 1] = when (op) {
                0x99 -> bits and Long.MAX_VALUE
                0x9A -> bits xor Long.MIN_VALUE
                else -> if (a.isNaN()) bits or 0x0008000000000000L else when (op) {
                    0x9B -> db(Math.ceil(a))
                    0x9C -> db(Math.floor(a))
                    0x9D -> db(if (a < 0.0) Math.ceil(a) else Math.floor(a))
                    0x9E -> db(Math.rint(a))
                    else -> db(Math.sqrt(a))
                }
            }
            return sp
        }
        val a = d(s[sp - 2])
        val x = d(s[sp - 1])
        s[sp - 2] = when (op) {
            0x61 -> b(a == x)
            0x62 -> b(a != x)
            0x63 -> b(a < x)
            0x64 -> b(a > x)
            0x65 -> b(a <= x)
            0x66 -> b(a >= x)
            0xA0 -> db(a + x)
            0xA1 -> db(a - x)
            0xA2 -> db(a * x)
            0xA3 -> db(a / x)
            0xA4 -> db(if (a.isNaN() || x.isNaN()) a + x else Math.min(a, x))
            0xA5 -> db(if (a.isNaN() || x.isNaN()) a + x else Math.max(a, x))
            else -> (s[sp - 2] and Long.MAX_VALUE) or (s[sp - 1] and Long.MIN_VALUE)
        }
        return sp - 1
    }

    /** Conversions, sign extensions and the saturating truncations: all one operand. */
    @JvmStatic
    private fun conv(op: Int, s: LongArray, sp: Int): Int {
        val v = s[sp - 1]
        s[sp - 1] = when (op) {
            0xA8 -> fb32ToI32(f(v).toDouble(), true)
            0xA9 -> fb32ToI32(f(v).toDouble(), false)
            0xAA -> fb32ToI32(d(v), true)
            0xAB -> fb32ToI32(d(v), false)
            0xAC -> v.toInt().toLong()
            0xAD -> v and MASK
            0xAE -> toI64(f(v).toDouble(), true)
            0xAF -> toI64(f(v).toDouble(), false)
            0xB0 -> toI64(d(v), true)
            0xB1 -> toI64(d(v), false)
            0xB2 -> fb(v.toInt().toFloat())
            0xB3 -> fb((v and MASK).toFloat())
            0xB4 -> fb(v.toFloat())
            0xB5 -> fb(if (v >= 0) v.toFloat() else ((v ushr 1) or (v and 1)).toFloat() * 2f)
            0xB6 -> fb(d(v).toFloat())
            0xB7 -> db(v.toInt().toDouble())
            0xB8 -> db((v and MASK).toDouble())
            0xB9 -> db(v.toDouble())
            0xBA -> db(if (v >= 0) v.toDouble() else ((v ushr 1) or (v and 1)).toDouble() * 2.0)
            0xBB -> db(f(v).toDouble())
            0xC0 -> v.toByte().toLong()
            0xC1 -> v.toShort().toLong()
            0xC2 -> v.toByte().toLong()
            0xC3 -> v.toShort().toLong()
            0xC4 -> v.toInt().toLong()
            Op.TRUNC_SAT_BASE -> f(v).toInt().toLong()
            Op.TRUNC_SAT_BASE + 1 -> satU32(f(v).toDouble())
            Op.TRUNC_SAT_BASE + 2 -> d(v).toInt().toLong()
            Op.TRUNC_SAT_BASE + 3 -> satU32(d(v))
            Op.TRUNC_SAT_BASE + 4 -> f(v).toLong()
            Op.TRUNC_SAT_BASE + 5 -> satU64(f(v).toDouble())
            Op.TRUNC_SAT_BASE + 6 -> d(v).toLong()
            Op.TRUNC_SAT_BASE + 7 -> satU64(d(v))
            else -> throw IllegalStateException("opcode $op")
        }
        return sp
    }

    /** i32.trunc_*: the trap where the value is NaN or out of range. */
    private fun fb32ToI32(x: Double, signed: Boolean): Long {
        if (x.isNaN()) throw WasmTrap("invalid conversion to integer")
        if (signed) {
            if (!(x > -2147483649.0 && x < 2147483648.0)) throw WasmTrap("integer overflow")
            return x.toInt().toLong()
        }
        if (!(x > -1.0 && x < 4294967296.0)) throw WasmTrap("integer overflow")
        return x.toLong().toInt().toLong()
    }

    private fun toI64(x: Double, signed: Boolean): Long {
        if (x.isNaN()) throw WasmTrap("invalid conversion to integer")
        if (signed) {
            if (!(x >= -TWO_63 && x < TWO_63)) throw WasmTrap("integer overflow")
            return x.toLong()
        }
        if (!(x > -1.0 && x < TWO_64)) throw WasmTrap("integer overflow")
        return if (x < TWO_63) x.toLong() else (x - TWO_63).toLong() + Long.MIN_VALUE
    }

    private fun satU32(x: Double): Long = when {
        !(x > 0.0) -> 0L
        x >= 4294967296.0 -> -1L
        else -> x.toLong().toInt().toLong()
    }

    private fun satU64(x: Double): Long = when {
        !(x > 0.0) -> 0L
        x >= TWO_64 -> -1L
        x < TWO_63 -> x.toLong()
        else -> (x - TWO_63).toLong() + Long.MIN_VALUE
    }
}
