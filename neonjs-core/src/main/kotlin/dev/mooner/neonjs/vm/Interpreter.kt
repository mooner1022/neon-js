package dev.mooner.neonjs.vm

import dev.mooner.neonjs.compiler.CallFeedback
import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.compiler.ScopeInfo
import dev.mooner.neonjs.runtime.*

/** Return value of a derived constructor frame: the returned value and the final `this` binding. */
class DerivedResult(@JvmField val value: Any?, @JvmField val thisValue: Any?)

/** Bytecode interpreter. */
object Interpreter {
    /** Returned by [run] when the frame suspended (yield / await). */
    @JvmField val SUSPENDED = Any()

    /**
     * Returned by [run] / compiled code for a proper tail call: the frame is finished and its caller must call
     * [Frame.tailFn] with [Frame.tailThis] / [Frame.tailArgs] instead (see [callClosure]).
     */
    @JvmField val TAIL = Any()

    /** TAIL_CALL: requests a tail call when the callee is a plain closure, otherwise calls it directly. */
    @JvmStatic
    fun tailCall(f: Frame, fnv: Any?, thisV: Any?, args: Array<Any?>): Any? {
        if (fnv is JSClosure && fnv.code.flags and (CodeBlock.CLASS_CTOR or CodeBlock.GENERATOR or CodeBlock.ASYNC) == 0) {
            f.tailFn = fnv
            f.tailThis = thisV
            f.tailArgs = args
            return TAIL
        }
        // other callees run in the calling function's realm context (e.g. the TypeError of a revoked proxy)
        if (fnv is JSObject && fnv.special and JSObject.CALLABLE != 0) return fnv.call(thisV, args)
        throw Rt.notCallable(fnv, f.code, f.pc)
    }

    // ------------------------------------------------------------------ calls

    @JvmStatic
    fun callClosure(fn0: JSClosure, thisArg0: Any?, args0: Array<Any?>): Any? = invokeClosure(fn0, thisArg0, args0)

    /**
     * [callClosure]'s steps, inline: the calls of compiled code (JitRt.call0..call4) include them and [execute]'s, so a
     * call there is one helper call, not three. Ahead-of-time code on ART does not inline across the three, and HotSpot
     * gives up on them as a chain; with the smaller frames (Frame.FrameExt) that made calls 10-35% faster.
     */
    @Suppress("NOTHING_TO_INLINE")
    internal inline fun invokeClosure(fn0: JSClosure, thisArg0: Any?, args0: Array<Any?>): Any? {
        var fn = fn0
        var thisArg = thisArg0
        var args = args0
        // trampoline for proper tail calls: a frame ending in TAIL_CALL is replaced by its callee
        while (true) {
            val code = fn.code
            val flags = code.flags
            if (flags and CodeBlock.CLASS_CTOR != 0) {
                throw fn.realm.typeError("Class constructor ${fn.debugName()} cannot be invoked without 'new'")
            }
            val realm = fn.realm
            val compiled = if (flags and (CodeBlock.GENERATOR or CodeBlock.ASYNC) != 0) null else {
                // installed code, Jit.prepare's first case, without calling it (ahead-of-time code on ART does not
                // inline it: ~7% of a call there)
                val c = code.compiled
                if (c != null && code.childrenQueued) c as dev.mooner.neonjs.jit.CompiledCode else dev.mooner.neonjs.jit.Jit.prepare(code, realm.agent)
            }
            val thisValue = if (flags and CodeBlock.ARROW != 0) fn.lexicalThis
            else if (flags and CodeBlock.STRICT != 0) thisArg
            else if (thisArg === Undefined || thisArg === Null || thisArg == null) realm.globalEnv.thisValue
            else if (thisArg is JSObject) thisArg
            else if (flags and (CodeBlock.USES_THIS or CodeBlock.HAS_EVAL) != 0) Ops.toObject(realm, thisArg)
            else thisArg
            val frame = Frame(fn, code, realm, thisValue, args, Undefined, fn.env, compiled)
            if (flags and (CodeBlock.GENERATOR or CodeBlock.ASYNC) != 0) {
                return Generators.start(frame)
            }
            val r = runFrame(frame, compiled)
            if (r !== TAIL) return r
            fn = frame.tailFn!!
            thisArg = frame.tailThis
            args = frame.tailArgs!!
        }
    }

    @JvmStatic
    fun constructClosure(fn: JSClosure, args: Array<Any?>, newTarget: JSObject): Any? {
        val code = fn.code
        val realm = fn.realm
        if (code.flags and CodeBlock.DERIVED != 0) {
            if (code.flags and CodeBlock.DEFAULT_CTOR != 0) {
                val parent = fn.getPrototypeOf()
                if (!Ops.isConstructor(parent)) throw JSException.typeError("Super constructor ${Ops.describe(parent)} of anonymous class is not a constructor")
                val result = (parent as JSObject).construct(args, newTarget)
                Rt.initializeInstanceElements(result as JSObject, fn)
                return result
            }
            dev.mooner.neonjs.jit.Jit.prepare(code, realm.agent)
            val compiled = code.compiled as dev.mooner.neonjs.jit.CompiledCode?
            val frame = Frame(fn, code, realm, Uninitialized, args, newTarget, fn.env, compiled)
            // checks happen after the callee context is gone (errors come from the caller's realm)
            val r = execute(frame, compiled) as DerivedResult
            val v = r.value
            if (v is JSObject) return v
            if (v !== Undefined) throw JSException.typeError("Derived constructors may only return object or undefined")
            if (r.thisValue === Uninitialized) throw tdzError("this")
            return r.thisValue
        }
        dev.mooner.neonjs.jit.Jit.prepare(code, realm.agent)
        val proto = Ops.getPrototypeFromConstructor(newTarget) { it.objectPrototype }
        val obj = JSObject(proto)
        if (code.flags and CodeBlock.CLASS_CTOR != 0) Rt.initializeInstanceElements(obj, fn)
        if (code.flags and CodeBlock.DEFAULT_CTOR != 0) return obj
        val compiled = code.compiled as dev.mooner.neonjs.jit.CompiledCode?
        val frame = Frame(fn, code, realm, obj, args, newTarget, fn.env, compiled)
        var r = execute(frame, compiled)
        if (r === TAIL) r = callClosure(frame.tailFn!!, frame.tailThis, frame.tailArgs!!)
        return r as? JSObject ?: obj
    }

    /** Runs a frame with realm / depth bookkeeping. */
    @JvmStatic
    fun execute(f: Frame): Any? = execute(f, if (f.isCompiled) f.code.compiled as dev.mooner.neonjs.jit.CompiledCode else null)

    /** As [execute] with [f]'s compiled code, the code it was made with (null: interpret it). */
    @JvmStatic
    fun execute(f: Frame, c: dev.mooner.neonjs.jit.CompiledCode?): Any? = runFrame(f, c)

    @Suppress("NOTHING_TO_INLINE")
    internal inline fun runFrame(f: Frame, c: dev.mooner.neonjs.jit.CompiledCode?): Any? {
        val agent = f.realm.agent
        val prevRealm = agent.currentRealm
        val prevTop = agent.topFrame
        if (++agent.depth > agent.maxDepth) {
            agent.depth--
            throw JSException.rangeError("Maximum call stack size exceeded")
        }
        if (--agent.callBudget < 0) {
            agent.callBudget = 1024
            try {
                agent.checkInterrupt()
            } catch (t: Throwable) {
                agent.depth--
                throw t
            }
        }
        agent.currentRealm = f.realm
        f.parent = prevTop
        agent.topFrame = f
        try {
            if (c == null) return run(f)
            try {
                return c.run(f)
            } catch (e: Throwable) {
                // (a helper: this is copied into every call helper)
                throw dev.mooner.neonjs.jit.JitRt.leaveCompiled(e, f)
            }
        } finally {
            agent.depth--
            agent.topFrame = prevTop
            agent.currentRealm = prevRealm
        }
    }

    // ------------------------------------------------------------------ main loop

    private fun findHandler(code: CodeBlock, pc: Int): Int {
        val h = code.handlers
        var i = 0
        while (i < h.size) {
            if (pc >= h[i] && pc < h[i + 1]) return i
            i += 4
        }
        return -1
    }

    /** Notes that the CALL at [pc] in [cb] called a closure of [callee] (feedback for the JIT's inlining). */
    private fun recordCall(cb: CodeBlock, pc: Int, callee: CodeBlock) {
        val fb = cb.callFeedback ?: arrayOfNulls<CallFeedback>(cb.code.size).also { cb.callFeedback = it }
        (fb[pc] ?: CallFeedback().also { fb[pc] = it }).record(callee)
    }

    @JvmStatic
    fun run(f: Frame): Any? {
        val cb = f.code
        val code = cb.code
        val k = cb.constants
        val s = f.slots
        val realm = f.realm
        // feedback for code that the adaptive mode may compile later, with calls inlined
        val recordCalls = dev.mooner.neonjs.jit.Inlining.ENABLED && realm.agent.config.executionMode == dev.mooner.neonjs.jit.Jit.MODE_ADAPTIVE
        val strict = cb.flags and CodeBlock.STRICT != 0
        val base = cb.numRegs
        var sp = f.sp
        var pc = f.pc
        var opStart = pc
        var budget = 4096
        var pendingThrow: Any? = null
        if (f.resumeMode == Frame.MODE_THROW && f.suspendKind == Frame.SUSPEND_AWAIT) {
            // resumed from await with a rejection: throw at the await instruction
            f.resumeMode = Frame.MODE_NEXT
            pendingThrow = s[--sp]
            opStart = f.awaitPc
        }
        while (true) {
            try {
                if (pendingThrow != null) {
                    val t = pendingThrow
                    pendingThrow = null
                    throw JSException(t)
                }
                while (true) {
                    opStart = pc
                    when (code[pc++]) {
                        Op.NOP -> {}
                        Op.PUSH_UNDEF -> s[sp++] = Undefined
                        Op.PUSH_NULL -> s[sp++] = Null
                        Op.PUSH_TRUE -> s[sp++] = true
                        Op.PUSH_FALSE -> s[sp++] = false
                        Op.PUSH_CONST -> s[sp++] = k[code[pc++]]
                        Op.PUSH_INT -> s[sp++] = Ops.num(code[pc++])
                        Op.POP -> s[--sp] = null
                        Op.DUP -> { s[sp] = s[sp - 1]; sp++ }
                        Op.DUP2 -> { s[sp] = s[sp - 2]; s[sp + 1] = s[sp - 1]; sp += 2 }
                        Op.SWAP -> { val t = s[sp - 1]; s[sp - 1] = s[sp - 2]; s[sp - 2] = t }
                        Op.LOAD_REG -> s[sp++] = s[code[pc++]]
                        Op.STORE_REG -> { s[code[pc++]] = s[--sp]; s[sp] = null }
                        Op.LOAD_REG_TDZ -> {
                            val v = s[code[pc++]]
                            val nk = code[pc++]
                            if (v === Uninitialized) throw tdzError(k[nk] as String)
                            s[sp++] = v
                        }
                        Op.CHECK_REG_TDZ -> {
                            val v = s[code[pc++]]
                            val nk = code[pc++]
                            if (v === Uninitialized) throw tdzError(k[nk] as String)
                        }
                        Op.PUSH_SCOPE -> f.env = DeclEnv(f.env, k[code[pc++]] as ScopeInfo)
                        Op.POP_SCOPE -> f.env = f.env!!.parent
                        Op.COPY_SCOPE -> f.env = (f.env as DeclEnv).copy()
                        Op.LOAD_ENV -> {
                            var e = f.env
                            var h = code[pc++]
                            while (h-- > 0) e = e!!.parent
                            s[sp++] = (e as DeclEnv).slots[code[pc++]]
                        }
                        Op.STORE_ENV -> {
                            var e = f.env
                            var h = code[pc++]
                            while (h-- > 0) e = e!!.parent
                            (e as DeclEnv).slots[code[pc++]] = s[--sp]
                            s[sp] = null
                        }
                        Op.LOAD_IMPORT -> {
                            var e = f.env
                            var h = code[pc++]
                            while (h-- > 0) e = e!!.parent
                            s[sp++] = Modules.deref((e as DeclEnv).slots[code[pc++]])
                        }
                        Op.LOAD_ENV_TDZ -> {
                            var e = f.env
                            var h = code[pc++]
                            while (h-- > 0) e = e!!.parent
                            val v = (e as DeclEnv).slots[code[pc++]]
                            val nk = code[pc++]
                            if (v === Uninitialized) throw tdzError(k[nk] as String)
                            s[sp++] = v
                        }
                        Op.LOAD_GLOBAL -> s[sp++] = GlobalCache.load(realm, k[code[pc++]] as GlobalSite, false)
                        Op.LOAD_THIS -> s[sp++] = f.thisValue
                        Op.LOAD_FUNCTION -> s[sp++] = f.fn ?: Undefined
                        Op.LOAD_ARG -> {
                            val i = code[pc++]
                            val a = f.args
                            s[sp++] = if (i < a.size) a[i] else Undefined
                        }
                        Op.THROW -> throw JSException(s[--sp])
                        Op.GET_PROP -> {
                            val o = s[sp - 1]
                            val site = k[code[pc++]] as PropSite
                            s[sp - 1] = if (o is JSObject) PropCache.get(o, site) else Rt.getPrimitiveProp(realm, o, site.key)
                        }
                        Op.PUT_PROP -> {
                            val v = s[--sp]
                            s[sp] = null
                            val o = s[sp - 1]
                            val site = k[code[pc++]] as PropSite
                            if (o is JSObject) {
                                if (!PropCache.put(o, site, v) && (strict || cb.strictAt(opStart))) throw Rt.readOnlyError(site.key, o)
                            } else Rt.putPrimitive(realm, o, site.key, v, strict || cb.strictAt(opStart))
                            s[sp - 1] = v
                        }
                        Op.GET_ELEM -> {
                            val key = s[--sp]
                            s[sp] = null
                            val o = s[sp - 1]
                            s[sp - 1] = Rt.getElem(realm, o, key)
                        }
                        Op.PUT_ELEM -> {
                            val v = s[--sp]
                            val key = s[--sp]
                            s[sp] = null; s[sp + 1] = null
                            val o = s[sp - 1]
                            Rt.putElem(realm, o, key, v, strict || cb.strictAt(opStart))
                            s[sp - 1] = v
                        }
                        Op.STORE_GLOBAL -> {
                            GlobalCache.store(realm, k[code[pc++]] as GlobalSite, s[--sp], strict || cb.strictAt(opStart))
                            s[sp] = null
                        }
                        Op.SHL -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.shl(s[sp - 1], b) }
                        Op.SAR -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.sar(s[sp - 1], b) }
                        Op.SHR -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.shr(s[sp - 1], b) }
                        Op.BAND -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.bitAnd(s[sp - 1], b) }
                        Op.BOR -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.bitOr(s[sp - 1], b) }
                        Op.BXOR -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.bitXor(s[sp - 1], b) }
                        Op.ADD -> {
                            val b = s[--sp]
                            s[sp] = null
                            val a = s[sp - 1]
                            s[sp - 1] = if (a is Double && b is Double) a + b else Ops.add(a, b)
                        }
                        Op.SUB -> {
                            val b = s[--sp]
                            s[sp] = null
                            val a = s[sp - 1]
                            s[sp - 1] = if (a is Double && b is Double) a - b else Ops.sub(a, b)
                        }
                        Op.MUL -> {
                            val b = s[--sp]
                            s[sp] = null
                            val a = s[sp - 1]
                            s[sp - 1] = if (a is Double && b is Double) a * b else Ops.mul(a, b)
                        }
                        Op.DIV -> {
                            val b = s[--sp]
                            s[sp] = null
                            val a = s[sp - 1]
                            s[sp - 1] = if (a is Double && b is Double) a / b else Ops.div(a, b)
                        }
                        Op.SEQ -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.strictEquals(s[sp - 1], b) }
                        Op.SNE -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = !Ops.strictEquals(s[sp - 1], b) }
                        Op.LT -> {
                            val b = s[--sp]
                            s[sp] = null
                            val a = s[sp - 1]
                            s[sp - 1] = if (a is Double && b is Double) a < b else Ops.lt(a, b)
                        }
                        Op.GT -> {
                            val b = s[--sp]
                            s[sp] = null
                            val a = s[sp - 1]
                            s[sp - 1] = if (a is Double && b is Double) a > b else Ops.gt(a, b)
                        }
                        Op.LE -> {
                            val b = s[--sp]
                            s[sp] = null
                            val a = s[sp - 1]
                            s[sp - 1] = if (a is Double && b is Double) a <= b else Ops.le(a, b)
                        }
                        Op.GE -> {
                            val b = s[--sp]
                            s[sp] = null
                            val a = s[sp - 1]
                            s[sp - 1] = if (a is Double && b is Double) a >= b else Ops.ge(a, b)
                        }
                        Op.NOT -> s[sp - 1] = !Ops.toBoolean(s[sp - 1])
                        Op.TO_NUMERIC -> { val v = s[sp - 1]; if (v !is Double) s[sp - 1] = Ops.toNumeric(v) }
                        Op.INC -> { val v = s[sp - 1]; s[sp - 1] = if (v is Double) v + 1.0 else Ops.inc(v) }
                        Op.DEC -> { val v = s[sp - 1]; s[sp - 1] = if (v is Double) v - 1.0 else Ops.dec(v) }
                        Op.JUMP -> {
                            val t = code[pc]
                            if (t < pc && --budget <= 0) {
                                budget = 4096
                                backEdge(realm.agent, cb)
                            }
                            pc = t
                        }
                        Op.JUMP_IF_TRUE -> {
                            val v = s[--sp]
                            s[sp] = null
                            if (if (v is Boolean) v else Ops.toBoolean(v)) {
                                val t = code[pc]
                                if (t < pc && --budget <= 0) { budget = 4096; backEdge(realm.agent, cb) }
                                pc = t
                            } else pc++
                        }
                        Op.JUMP_IF_FALSE -> {
                            val v = s[--sp]
                            s[sp] = null
                            if (!(if (v is Boolean) v else Ops.toBoolean(v))) {
                                val t = code[pc]
                                if (t < pc && --budget <= 0) { budget = 4096; backEdge(realm.agent, cb) }
                                pc = t
                            } else pc++
                        }
                        Op.JUMP_IF_NULLISH -> {
                            val v = s[--sp]
                            s[sp] = null
                            if (v === Undefined || v === Null) pc = code[pc] else pc++
                        }
                        Op.JUMP_IF_NOT_NULLISH -> {
                            val v = s[--sp]
                            s[sp] = null
                            if (v !== Undefined && v !== Null) pc = code[pc] else pc++
                        }
                        Op.JUMP_IF_UNDEFINED -> {
                            val v = s[--sp]
                            s[sp] = null
                            if (v === Undefined) pc = code[pc] else pc++
                        }
                        Op.JUMP_IF_NOT_UNDEFINED -> {
                            val v = s[--sp]
                            s[sp] = null
                            if (v !== Undefined) pc = code[pc] else pc++
                        }
                        Op.JUMP_TABLE -> {
                            val r = code[pc++]
                            val n = code[pc++]
                            val idx = (s[r] as Double).toInt()
                            pc = if (idx in 0 until n) code[pc + idx] else code[pc + n]
                        }
                        Op.RETURN -> {
                            val v = s[--sp]
                            f.sp = sp
                            return v
                        }
                        Op.CALL -> {
                            val argc = code[pc++]
                            val args = if (argc == 0) EMPTY_ARGS else arrayOfNulls<Any?>(argc)
                            for (i in argc - 1 downTo 0) { args[i] = s[--sp]; s[sp] = null }
                            val thisV = s[--sp]
                            s[sp] = null
                            val fnv = s[sp - 1]
                            f.pc = opStart
                            if (recordCalls && fnv is JSClosure) recordCall(cb, opStart, fnv.code)
                            s[sp - 1] = if (fnv is JSObject && fnv.special and JSObject.CALLABLE != 0) fnv.call(thisV, args)
                            else throw Rt.notCallable(fnv, cb, opStart)
                        }
                        Op.TAIL_CALL -> {
                            val argc = code[pc++]
                            val args = if (argc == 0) EMPTY_ARGS else arrayOfNulls<Any?>(argc)
                            for (i in argc - 1 downTo 0) { args[i] = s[--sp]; s[sp] = null }
                            val thisV = s[--sp]
                            s[sp] = null
                            val fnv = s[sp - 1]
                            f.pc = opStart
                            val r = tailCall(f, fnv, thisV, args)
                            if (r === TAIL) return r
                            s[sp - 1] = r
                        }
                        Op.NEW -> {
                            val argc = code[pc++]
                            val args = if (argc == 0) EMPTY_ARGS else arrayOfNulls<Any?>(argc)
                            for (i in argc - 1 downTo 0) { args[i] = s[--sp]; s[sp] = null }
                            val fnv = s[sp - 1]
                            f.pc = opStart
                            if (fnv !is JSObject || fnv.special and JSObject.CONSTRUCTOR == 0) throw JSException.typeError("${Rt.calleeText(cb, opStart, fnv)} is not a constructor")
                            s[sp - 1] = fnv.construct(args, fnv)
                        }
                        Op.NEW_OBJECT -> s[sp++] = JSObject(realm.objectPrototype)
                        Op.NEW_OBJECT_LITERAL -> {
                            val site = k[code[pc++]] as ObjectLiteralSite
                            val n = code[pc++]
                            sp -= n
                            val vals = s.copyOfRange(sp, sp + n)
                            for (i in sp until sp + n) s[i] = null
                            s[sp++] = site.create(realm.objectPrototype, vals)
                        }
                        Op.NEW_ARRAY -> s[sp++] = JSArray(realm.arrayPrototype)
                        Op.ARRAY_PUSH -> {
                            val v = s[--sp]
                            s[sp] = null
                            (s[sp - 1] as JSArray).pushInit(v)
                        }
                        Op.DEFINE_FIELD -> {
                            val v = s[--sp]
                            s[sp] = null
                            (s[sp - 1] as JSObject).createDataPropertyOrThrow(k[code[pc++]]!!, v)
                        }
                        Op.MAKE_CLOSURE -> s[sp++] = Rt.makeClosureIn(f, k[code[pc++]] as CodeBlock)
                        Op.ITER_STEP -> {
                            val rec = s[code[pc++]] as IteratorRecord
                            val t = code[pc++]
                            val v = Iteration.stepValue(rec)
                            if (v === NotFound) {
                                pc = t
                                if (--budget <= 0) { budget = 4096; backEdge(realm.agent, cb) }
                            } else s[sp++] = v
                        }
                        Op.FOR_IN_NEXT -> {
                            val it = s[code[pc++]] as ForInIterator
                            val t = code[pc++]
                            val key = it.next()
                            if (key == null) pc = t else s[sp++] = key
                            if (--budget <= 0) { budget = 4096; backEdge(realm.agent, cb) }
                        }
                        Op.GENERATOR_INIT -> {
                            f.pc = pc
                            f.sp = sp
                            f.suspendKind = Frame.SUSPEND_INITIAL
                            return SUSPENDED
                        }
                        Op.YIELD -> {
                            f.suspendValue = s[--sp]
                            s[sp] = null
                            f.pc = pc
                            f.sp = sp
                            f.suspendKind = Frame.SUSPEND_YIELD
                            return SUSPENDED
                        }
                        Op.YIELD_RAW -> {
                            f.suspendValue = s[--sp]
                            s[sp] = null
                            f.pc = pc
                            f.sp = sp
                            f.suspendKind = Frame.SUSPEND_YIELD_RAW
                            return SUSPENDED
                        }
                        Op.AWAIT, Op.AWAIT_IGNORE, Op.AWAIT_CATCH -> {
                            val op = code[opStart]
                            if (op == Op.AWAIT_CATCH) pc++
                            val v = s[--sp]
                            s[sp] = null
                            f.pc = opStart
                            val p = if (op == Op.AWAIT_IGNORE) Rt.promiseResolveOrNull(realm, v) else Rt.promiseResolveForAwait(realm, v)
                            f.suspendValue = p
                            f.pc = pc
                            f.sp = sp
                            f.suspendKind = Frame.SUSPEND_AWAIT
                            f.awaitPc = opStart
                            return SUSPENDED
                        }
                        else -> {
                            f.sp = sp
                            f.pc = pc
                            slowOp(f, code[opStart], opStart)
                            if (f.tailPending) return TAIL
                            sp = f.sp
                            pc = f.pc
                        }
                    }
                }
            } catch (t: Throwable) {
                val hi = findHandler(cb, opStart)
                if (hi < 0) {
                    if (t is JSException) Rt.attachStack(t, f, opStart)
                    f.sp = sp
                    throw t
                }
                val v = Rt.catchValue(t, realm, f, opStart)
                val h = cb.handlers
                val depth = h[hi + 3]
                for (i in base + depth until s.size) s[i] = null
                sp = base + depth
                s[sp++] = v
                pc = h[hi + 2]
            }
        }
    }

    /**
     * Every 4096 loop iterations of interpreted code: the agent's limits, and (adaptive mode) credit toward compiling
     * this code block at its next call, so a function whose time goes into long loops is compiled even when it is
     * called only a few times (about 64K iterations reach the default threshold of 1000 calls).
     */
    @JvmStatic
    private fun backEdge(agent: Agent, cb: CodeBlock) {
        agent.checkInterrupt()
        if (cb.compiled == null) cb.invocationCount += 64
    }

    /** Less frequent instructions, kept out of [run] so the hot loop stays small enough for the JVM JIT. */
    @JvmStatic
    private fun slowOp(f: Frame, op: Int, opStart: Int) {
        val cb = f.code
        val code = cb.code
        val k = cb.constants
        val s = f.slots
        val realm = f.realm
        val strict = cb.flags and CodeBlock.STRICT != 0 || cb.strictAt(opStart)
        var sp = f.sp
        var pc = opStart + 1
        when (op) {
                Op.DUP3 -> { s[sp] = s[sp - 3]; s[sp + 1] = s[sp - 2]; s[sp + 2] = s[sp - 1]; sp += 3 }
                Op.ROT3 -> { val c = s[sp - 1]; s[sp - 1] = s[sp - 2]; s[sp - 2] = s[sp - 3]; s[sp - 3] = c }
                Op.ROT4 -> { val d = s[sp - 1]; s[sp - 1] = s[sp - 2]; s[sp - 2] = s[sp - 3]; s[sp - 3] = s[sp - 4]; s[sp - 4] = d }
                Op.GET_ENV -> s[sp++] = f.env
                Op.SET_ENV -> { f.env = s[--sp] as Env?; s[sp] = null }
                Op.PUSH_WITH -> { f.env = ObjectEnv(f.env, s[--sp] as JSObject, true); s[sp] = null }
                Op.CHECK_ENV_TDZ -> {
                    var e = f.env
                    var h = code[pc++]
                    while (h-- > 0) e = e!!.parent
                    val v = (e as DeclEnv).slots[code[pc++]]
                    val nk = code[pc++]
                    if (v === Uninitialized) throw tdzError(k[nk] as String)
                }
                Op.LOAD_NAME -> s[sp++] = Names.load(f.env, k[code[pc++]] as String, strict, false)
                Op.LOAD_NAME_TYPEOF -> s[sp++] = Names.load(f.env, k[code[pc++]] as String, strict, true)
                Op.LOAD_NAME_CALL -> {
                    val (v, t) = Names.loadForCall(f.env, k[code[pc++]] as String, strict)
                    s[sp++] = v
                    s[sp++] = t
                }
                Op.STORE_NAME -> {
                    Names.store(f.env, k[code[pc++]] as String, s[--sp], strict, realm.globalEnv)
                    s[sp] = null
                }
                Op.STORE_NAME_VAR -> {
                    Rt.storeAnnexBVar(f.env, k[code[pc++]] as String, s[--sp], realm)
                    s[sp] = null
                }
                Op.DELETE_NAME -> s[sp++] = Names.delete(f.env, k[code[pc++]] as String)
                Op.INIT_NAME -> {
                    Names.initialize(f.env, k[code[pc++]] as String, s[--sp])
                    s[sp] = null
                }
                Op.LOAD_GLOBAL_TYPEOF -> s[sp++] = GlobalCache.load(realm, k[code[pc++]] as GlobalSite, true)
                Op.INIT_GLOBAL_LEX -> {
                    Rt.initGlobalLexical(realm, k[code[pc++]] as String, s[--sp])
                    s[sp] = null
                }
                Op.LOAD_NEW_TARGET -> s[sp++] = f.newTarget
                Op.LOAD_HOME -> s[sp++] = f.fn?.homeObject ?: f.homeObject ?: Undefined
                Op.CREATE_ARGUMENTS -> {
                    val mapped = code[pc++] == 1
                    s[sp++] = if (mapped) Rt.createMappedArguments(f.fn!!, f.args, f.env as? DeclEnv, cb.mappedSlots!!)
                    else Rt.createUnmappedArguments(realm, f.args)
                }
                Op.CREATE_REST -> {
                    val start = code[pc++]
                    val a = f.args
                    val arr = JSArray(realm.arrayPrototype)
                    for (i in start until a.size) arr.pushInit(a[i])
                    s[sp++] = arr
                }
                Op.INIT_THIS_REG -> {
                    val r = code[pc++]
                    if (s[r] !== Uninitialized) throw JSException.referenceError("Super constructor may only be called once")
                    s[r] = s[--sp]
                    s[sp] = null
                    f.thisValue = s[r]
                }
                Op.INIT_THIS_ENV -> {
                    var e = f.env
                    var h = code[pc++]
                    while (h-- > 0) e = e!!.parent
                    val slot = code[pc++]
                    val de = e as DeclEnv
                    if (de.slots[slot] !== Uninitialized) throw JSException.referenceError("Super constructor may only be called once")
                    de.slots[slot] = s[--sp]
                    s[sp] = null
                }
                Op.INIT_THIS_NAME -> {
                    Rt.initThisDynamic(f.env, s[--sp])
                    s[sp] = null
                }
                Op.THROW_ERROR -> {
                    val kind = code[pc++]
                    val msg = k[code[pc++]] as String
                    throw when (kind) {
                        1 -> JSException.referenceError(msg)
                        2 -> JSException.syntaxError(msg)
                        3 -> JSException.rangeError(msg)
                        else -> JSException.typeError(msg)
                    }
                }
                Op.DELETE_PROP -> {
                    val o = s[sp - 1]
                    s[sp - 1] = Rt.deleteProp(realm, o, k[code[pc++]]!!, strict)
                }
                Op.DELETE_ELEM -> {
                    val key = s[--sp]
                    s[sp] = null
                    val o = s[sp - 1]
                    s[sp - 1] = Rt.deleteProp(realm, o, Ops.toPropertyKey(Rt.keyForBase(o, key)), strict)
                }
                Op.TO_PROPERTY_KEY -> s[sp - 1] = Ops.toPropertyKey(s[sp - 1])
                Op.TO_KEY_FOR_BASE -> s[sp - 1] = Rt.keyForBase(s[sp - 2], s[sp - 1])
                Op.GET_SUPER -> {
                    val baseObj = s[--sp]
                    val key = s[--sp]
                    s[sp] = null; s[sp + 1] = null
                    val thisV = s[sp - 1]
                    s[sp - 1] = Rt.getSuper(realm, thisV, Ops.toPropertyKey(key), baseObj)
                }
                Op.PUT_SUPER -> {
                    val v = s[--sp]
                    val baseObj = s[--sp]
                    val key = s[--sp]
                    s[sp] = null; s[sp + 1] = null; s[sp + 2] = null
                    val thisV = s[sp - 1]
                    Rt.putSuper(realm, thisV, Ops.toPropertyKey(key), baseObj, v, strict)
                    s[sp - 1] = v
                }
                Op.GET_SUPER_BASE -> s[sp - 1] = Rt.superBase(s[sp - 1])
                Op.GET_PRIVATE -> {
                    val pn = s[--sp]
                    s[sp] = null
                    s[sp - 1] = Rt.privateGet(s[sp - 1], pn as PrivateName)
                }
                Op.PUT_PRIVATE -> {
                    val v = s[--sp]
                    val pn = s[--sp]
                    s[sp] = null; s[sp + 1] = null
                    Rt.privateSet(s[sp - 1], pn as PrivateName, v)
                    s[sp - 1] = v
                }
                Op.HAS_PRIVATE -> {
                    val o = s[--sp]
                    s[sp] = null
                    s[sp - 1] = Rt.privateIn(s[sp - 1] as PrivateName, o)
                }
                Op.MOD -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.mod(s[sp - 1], b) }
                Op.EXP -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.exp(s[sp - 1], b) }
                Op.EQ -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.looseEquals(s[sp - 1], b) }
                Op.NE -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = !Ops.looseEquals(s[sp - 1], b) }
                Op.INSTANCEOF -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.instanceOf(s[sp - 1], b) }
                Op.IN -> { val b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.hasPropertyOp(s[sp - 1], b) }
                Op.NEG -> s[sp - 1] = Ops.neg(s[sp - 1])
                Op.TO_NUMBER -> { val v = s[sp - 1]; if (v !is Double) s[sp - 1] = Ops.toNumber(v) }
                Op.BNOT -> s[sp - 1] = Ops.bitNot(s[sp - 1])
                Op.TYPEOF -> s[sp - 1] = Ops.typeOf(s[sp - 1])
                Op.TO_STRING -> { val v = s[sp - 1]; if (v !is CharSequence) s[sp - 1] = Ops.toString(v) }
                Op.CONCAT -> {
                    val b = s[--sp]
                    s[sp] = null
                    s[sp - 1] = Rope.concat(s[sp - 1] as CharSequence, b as CharSequence)
                }
                Op.TO_OBJECT -> s[sp - 1] = Ops.toObject(realm, s[sp - 1])
                Op.REQUIRE_COERCIBLE -> {
                    val v = s[sp - 1]
                    if (v === Undefined || v === Null) throw JSException.typeError("Cannot destructure '${Ops.toDisplayString(v)}' as it is ${Ops.toDisplayString(v)}.")
                }
                Op.CHECK_OBJECT -> {
                    val m = code[pc++]
                    if (s[sp - 1] !is JSObject) throw JSException.typeError(k[m] as String)
                }
                Op.CALL_SPREAD -> {
                    val arr = s[--sp] as JSArray
                    val thisV = s[--sp]
                    s[sp] = null; s[sp + 1] = null
                    val fnv = s[sp - 1]
                    f.pc = opStart
                    val args = Rt.arrayToArgs(arr)
                    s[sp - 1] = if (fnv is JSObject && fnv.special and JSObject.CALLABLE != 0) fnv.call(thisV, args)
                    else throw Rt.notCallable(fnv, cb, opStart)
                }
                Op.NEW_SPREAD -> {
                    val arr = s[--sp] as JSArray
                    s[sp] = null
                    val fnv = s[sp - 1]
                    f.pc = opStart
                    if (fnv !is JSObject || fnv.special and JSObject.CONSTRUCTOR == 0) throw JSException.typeError("${Rt.calleeText(cb, opStart, fnv)} is not a constructor")
                    s[sp - 1] = fnv.construct(Rt.arrayToArgs(arr), fnv)
                }
                Op.CALL_EVAL -> {
                    val argc = code[pc++]
                    val flags = code[pc++]
                    val args = if (argc == 0) EMPTY_ARGS else arrayOfNulls<Any?>(argc)
                    for (i in argc - 1 downTo 0) { args[i] = s[--sp]; s[sp] = null }
                    val thisV = s[--sp]
                    s[sp] = null
                    val fnv = s[sp - 1]
                    f.pc = opStart
                    if (fnv === realm.evalFunction) s[sp - 1] = Rt.directEval(f, args, flags and 1 != 0)
                    else if (flags and 2 != 0) {
                        // a call through a binding named "eval" that is not %eval%, in tail position
                        val r = tailCall(f, fnv, thisV, args)
                        if (r === TAIL) f.tailPending = true else s[sp - 1] = r
                    } else if (fnv is JSObject && fnv.isCallable) s[sp - 1] = fnv.call(thisV, args)
                    else throw Rt.notCallable(fnv, cb, opStart)
                }
                Op.CALL_EVAL_SPREAD -> {
                    val flags = code[pc++]
                    val arr = s[--sp] as JSArray
                    val thisV = s[--sp]
                    s[sp] = null; s[sp + 1] = null
                    val fnv = s[sp - 1]
                    val args = Rt.arrayToArgs(arr)
                    f.pc = opStart
                    s[sp - 1] = if (fnv === realm.evalFunction) Rt.directEval(f, args, flags and 1 != 0)
                    else if (fnv is JSObject && fnv.isCallable) fnv.call(thisV, args)
                    else throw Rt.notCallable(fnv, cb, opStart)
                }
                Op.SUPER_CALL -> {
                    val argc = code[pc++]
                    val args = if (argc == 0) EMPTY_ARGS else arrayOfNulls<Any?>(argc)
                    for (i in argc - 1 downTo 0) { args[i] = s[--sp]; s[sp] = null }
                    val nt = s[--sp]
                    s[sp] = null
                    val func = s[sp - 1]
                    f.pc = opStart
                    s[sp - 1] = Rt.superCall(func, args, nt)
                }
                Op.SUPER_CALL_SPREAD -> {
                    val arr = s[--sp] as JSArray
                    val nt = s[--sp]
                    s[sp] = null; s[sp + 1] = null
                    val func = s[sp - 1]
                    f.pc = opStart
                    s[sp - 1] = Rt.superCall(func, Rt.arrayToArgs(arr), nt)
                }
                Op.GET_PROTO_OF -> s[sp - 1] = (s[sp - 1] as JSObject).getPrototypeOf() ?: Null
                Op.INIT_INSTANCE -> {
                    val fnv = s[--sp]
                    s[sp] = null
                    Rt.initializeInstanceElements(s[sp - 1] as JSObject, fnv as JSClosure)
                }
                Op.ARRAY_HOLE -> (s[sp - 1] as JSArray).pushHoleInit()
                Op.ARRAY_SPREAD -> {
                    val v = s[--sp]
                    s[sp] = null
                    Rt.arraySpread(realm, s[sp - 1] as JSArray, v)
                }
                Op.DEFINE_FIELD_ELEM -> {
                    val v = s[--sp]
                    val key = s[--sp]
                    s[sp] = null; s[sp + 1] = null
                    (s[sp - 1] as JSObject).createDataPropertyOrThrow(key!!, v)
                }
                Op.DEFINE_GETTER, Op.DEFINE_SETTER, Op.DEFINE_METHOD -> {
                    val op = code[opStart]
                    val enumerable = code[pc++] == 1
                    val fnv = s[--sp]
                    val key = s[--sp]
                    s[sp] = null; s[sp + 1] = null
                    Rt.defineMethod(s[sp - 1] as JSObject, key!!, fnv as JSFunction, op, enumerable)
                }
                Op.COPY_DATA_PROPS -> {
                    val src = s[--sp]
                    s[sp] = null
                    Rt.copyDataProperties(s[sp - 1] as JSObject, src, null)
                }
                Op.COPY_DATA_PROPS_EXCL -> {
                    val n = code[pc++]
                    val excl = HashSet<Any>()
                    repeat(n) { excl.add(s[--sp]!!); s[sp] = null }
                    val src = s[--sp]
                    s[sp] = null
                    Rt.copyDataProperties(s[sp - 1] as JSObject, src, excl)
                }
                else -> {
                    rareOp(f, op, opStart)
                    return
                }
        }
        f.sp = sp
        f.pc = pc
    }

    /** Rarely executed instructions (class definition, generators, modules, disposal, ...). */
    @JvmStatic
    private fun rareOp(f: Frame, op: Int, opStart: Int) {
        val cb = f.code
        val code = cb.code
        val k = cb.constants
        val s = f.slots
        val realm = f.realm
        val strict = cb.flags and CodeBlock.STRICT != 0 || cb.strictAt(opStart)
        var sp = f.sp
        var pc = opStart + 1
        when (op) {
                Op.SET_PROTO -> {
                    val v = s[--sp]
                    s[sp] = null
                    if (v is JSObject || v === Null) (s[sp - 1] as JSObject).setPrototypeOf(v as? JSObject)
                }
                Op.SET_FUNCTION_NAME -> {
                    val pk = code[pc++]
                    Rt.setFunctionName(s[sp - 1] as JSObject, s[sp - 2]!!, if (pk >= 0) k[pk] as String else null)
                }
                Op.MAKE_METHOD -> {
                    val t = k[code[pc++]] as CodeBlock
                    val home = s[code[pc++]] as JSObject
                    s[sp++] = Rt.makeClosure(realm, t, f.env, home)
                }
                Op.MAKE_CLASS -> {
                    val t = k[code[pc++]] as CodeBlock
                    val flags = code[pc++]
                    var name: Any? = null
                    var heritage: Any? = NotFound
                    if (flags and 2 != 0) { name = s[--sp]; s[sp] = null }
                    if (flags and 1 != 0) { heritage = s[--sp]; s[sp] = null }
                    val pair = Rt.makeClass(realm, t, f.env, heritage, name)
                    s[sp++] = pair[0]
                    s[sp++] = pair[1]
                }
                Op.NEW_PRIVATE_NAME -> s[sp++] = PrivateName(k[code[pc++]] as String)
                Op.ADD_FIELD -> {
                    val init = s[--sp]
                    val key = s[--sp]
                    val ctor = s[--sp] as JSClosure
                    s[sp] = null; s[sp + 1] = null; s[sp + 2] = null
                    var fl = ctor.fields
                    if (fl == null) { fl = ArrayList(); ctor.fields = fl }
                    fl.add(FieldRecord(key!!, init))
                }
                Op.ADD_PRIVATE_METHOD -> {
                    val kind = code[pc++]
                    val fnv = s[--sp]
                    val pn = s[--sp] as PrivateName
                    val ctor = s[--sp] as JSClosure
                    s[sp] = null; s[sp + 1] = null; s[sp + 2] = null
                    Rt.addPrivateMethod(ctor, pn, fnv, kind)
                }
                Op.STATIC_PRIVATE_METHOD -> {
                    val kind = code[pc++]
                    val fnv = s[--sp]
                    val pn = s[--sp] as PrivateName
                    val ctor = s[--sp] as JSObject
                    s[sp] = null; s[sp + 1] = null; s[sp + 2] = null
                    Rt.staticPrivateMethod(ctor, pn, fnv, kind)
                }
                Op.RUN_FIELD -> {
                    val fnv = s[--sp]
                    val key = s[--sp]
                    val obj = s[--sp] as JSObject
                    s[sp] = null; s[sp + 1] = null; s[sp + 2] = null
                    f.pc = opStart
                    Rt.defineField(obj, FieldRecord(key!!, fnv))
                }
                Op.GET_ITERATOR -> s[sp - 1] = Iteration.getIterator(realm, s[sp - 1], false)
                Op.GET_ASYNC_ITERATOR -> s[sp - 1] = Iteration.getIterator(realm, s[sp - 1], true)
                Op.ITER_STEP_U -> {
                    val rec = s[code[pc++]] as IteratorRecord
                    if (rec.done) s[sp++] = Undefined
                    else {
                        val v = Iteration.stepValue(rec)
                        s[sp++] = if (v === NotFound) Undefined else v
                    }
                }
                Op.ITER_REST -> s[sp++] = Iteration.rest(realm, s[code[pc++]] as IteratorRecord)
                Op.ITER_CLOSE -> Iteration.closeNormal(s[code[pc++]] as IteratorRecord)
                Op.ITER_CLOSE_THROW -> Iteration.closeOnThrow(s[code[pc++]] as IteratorRecord)
                Op.ITER_NEXT_CALL -> s[sp++] = Iteration.callNext(s[code[pc++]] as IteratorRecord, Undefined)
                Op.ITER_RESULT_STEP -> {
                    val rec = s[code[pc++]] as IteratorRecord
                    val t = code[pc++]
                    val r = s[--sp]
                    s[sp] = null
                    if (r !is JSObject) {
                        rec.done = true
                        throw JSException.typeError("Iterator result ${Ops.toDisplayString(r)} is not an object")
                    }
                    if (Iteration.complete(rec, r)) {
                        rec.done = true
                        pc = t
                    } else s[sp++] = Iteration.value(rec, r)
                }
                Op.ASYNC_ITER_CLOSE -> {
                    val rec = s[code[pc++]] as IteratorRecord
                    val mode = code[pc++]
                    val t = code[pc++]
                    val r = Iteration.asyncCloseCall(rec, mode == 1)
                    if (r === NotFound) pc = t else s[sp++] = r
                }
                Op.FOR_IN_START -> s[sp - 1] = ForInIterator(Ops.toObject(realm, s[sp - 1]))
                Op.RESUME_DISPATCH -> {
                    val t = code[pc++]
                    when (f.resumeMode) {
                        Frame.MODE_THROW -> {
                            f.resumeMode = Frame.MODE_NEXT
                            val v = s[--sp]
                            s[sp] = null
                            throw JSException(v)
                        }
                        Frame.MODE_RETURN -> {
                            f.resumeMode = Frame.MODE_NEXT
                            pc = t
                        }
                    }
                }
                Op.RESUME_MODE -> {
                    s[sp++] = Ops.num(f.resumeMode)
                    f.resumeMode = Frame.MODE_NEXT
                }
                Op.YSTAR -> {
                    val rec = s[code[pc++]] as IteratorRecord
                    val modeReg = code[pc++]
                    val t = code[pc++]
                    val received = s[sp - 1]
                    val mode = (s[modeReg] as Double).toInt()
                    val r = Iteration.yieldStarStep(rec, received, mode)
                    if (r is Iteration.YStarDone) {
                        s[modeReg] = Ops.num(if (r.isReturn) 2 else 0)
                        s[sp - 1] = r.value
                        pc = t
                    } else s[sp - 1] = r
                }
                Op.YSTAR_ASYNC_CALL -> {
                    val rec = s[code[pc++]] as IteratorRecord
                    val modeReg = code[pc++]
                    val t = code[pc++]
                    val received = s[sp - 1]
                    val mode = (s[modeReg] as Double).toInt()
                    val r = Iteration.yieldStarAsyncCall(rec, received, mode)
                    if (r is Iteration.YStarDone) {
                        s[modeReg] = Ops.num(if (r.isReturn) 2 else 3)
                        s[sp - 1] = r.value
                        pc = t
                    } else s[sp - 1] = r
                }
                Op.YSTAR_ASYNC_RESULT -> {
                    val rec = s[code[pc++]] as IteratorRecord
                    val modeReg = code[pc++]
                    val t = code[pc++]
                    val r = s[sp - 1]
                    val mode = (s[modeReg] as Double).toInt()
                    if (r !is JSObject) throw JSException.typeError("Iterator result ${Ops.toDisplayString(r)} is not an object")
                    if (Iteration.complete(rec, r)) {
                        s[sp - 1] = Iteration.value(rec, r)
                        s[modeReg] = Ops.num(if (mode == 2) 2 else 0)
                        pc = t
                    } else s[sp - 1] = Iteration.value(rec, r)
                }
                Op.DEBUGGER -> realm.agent.onDebugger(f)
                Op.TEMPLATE_OBJECT -> s[sp++] = Rt.templateObject(realm, k[code[pc++]] as dev.mooner.neonjs.compiler.TemplateSite)
                Op.NEW_REGEXP -> s[sp++] = Rt.newRegExp(realm, k[code[pc++]] as dev.mooner.neonjs.compiler.RegExpSite)
                Op.IMPORT_META -> s[sp++] = Rt.importMeta(f)
                Op.CLASS_DEF_NEW -> {
                    val p = s[--sp]
                    s[sp] = null
                    s[sp - 1] = Decorators.newClassDef(s[sp - 1], p)
                }
                Op.CLASS_ELEMENT -> {
                    val flags = code[pc++]
                    val fn = s[--sp]
                    val key = s[--sp]
                    val decs = s[--sp]
                    val def = s[--sp]
                    s[sp] = null; s[sp + 1] = null; s[sp + 2] = null; s[sp + 3] = null
                    Decorators.addElement(def, decs, key, fn, flags)
                }
                Op.CLASS_FINISH -> {
                    val def = s[--sp]
                    s[sp] = null
                    Decorators.finish(def)
                }
                Op.CLASS_DECORATE -> {
                    val decs = s[--sp]
                    s[sp] = null
                    s[sp - 1] = Decorators.decorateClass(s[sp - 1], decs)
                }
                Op.CLASS_STATIC_INIT -> {
                    val def = s[--sp]
                    s[sp] = null
                    Decorators.staticInit(def)
                }
                Op.DYNAMIC_IMPORT_PHASE -> {
                    val phase = k[code[pc++]] as String
                    val opts = s[--sp]
                    s[sp] = null
                    s[sp - 1] = Modules.dynamicImport(f, s[sp - 1], opts, phase)
                }
                Op.DYNAMIC_IMPORT -> {
                    val opts = s[--sp]
                    s[sp] = null
                    s[sp - 1] = Rt.dynamicImport(f, s[sp - 1], opts)
                }
                Op.DECLARE_GLOBALS, Op.DECLARE_EVAL -> {
                    val op = code[opStart]
                    val info = k[code[pc++]] as dev.mooner.neonjs.compiler.DeclInfo
                    val n = code[pc++]
                    val fns = arrayOfNulls<Any?>(n)
                    for (i in n - 1 downTo 0) { fns[i] = s[--sp]; s[sp] = null }
                    f.pc = opStart
                    if (op == Op.DECLARE_GLOBALS) Rt.declareGlobals(realm, info, fns)
                    else Rt.declareEval(f, info, fns)
                }
                Op.DERIVED_RETURN -> {
                    val thisV = s[--sp]
                    s[sp] = null
                    s[sp - 1] = DerivedResult(s[sp - 1], thisV)
                }
                Op.RESOLVE_NAME -> {
                    val name = k[code[pc++]] as String
                    s[sp++] = Names.resolve(f.env, name) ?: NameRef(null, name, -1, strict)
                }
                Op.GET_REF -> {
                    val ref = s[sp - 1] as NameRef
                    s[sp - 1] = Names.getValue(if (ref.env == null) null else ref, ref.name, strict, false)
                }
                Op.PUT_REF -> {
                    val v = s[--sp]
                    s[sp] = null
                    val ref = s[sp - 1] as NameRef
                    Names.putValue(if (ref.env == null) null else ref, ref.name, v, strict, realm.globalEnv)
                    s[sp - 1] = v
                }
                Op.ADD_DISPOSABLE -> {
                    val hint = code[pc++]
                    val cap = s[--sp]
                    val v = s[--sp]
                    s[sp] = null; s[sp + 1] = null
                    Disposal.add(realm, cap, v, hint)
                }
                Op.DISPOSE_NEW -> s[sp++] = Disposal.newCapability()
                Op.DISPOSE_SYNC -> {
                    val value = s[--sp]
                    val kind = s[--sp]
                    val cap = s[--sp]
                    s[sp] = null; s[sp + 1] = null; s[sp + 2] = null
                    Disposal.disposeSync(realm, cap, kind, value)
                }
                Op.DISPOSE_BEGIN -> {
                    val value = s[--sp]
                    val kind = s[--sp]
                    s[sp] = null; s[sp + 1] = null
                    s[sp - 1] = Disposal.begin(realm, s[sp - 1], kind, value)
                }
                Op.DISPOSE_STEP -> {
                    val d = s[sp - 1]
                    val more = Disposal.step(d)
                    s[sp - 1] = if (more) Disposal.awaitValue(d) else Undefined
                    s[sp++] = more
                }
                Op.DISPOSE_AWAITED -> {
                    val mode = s[--sp]
                    val d = s[--sp]
                    val result = s[--sp]
                    s[sp] = null; s[sp + 1] = null; s[sp + 2] = null
                    Disposal.awaited(result, d, mode)
                }
                Op.DISPOSE_END -> {
                    val d = s[--sp]
                    s[sp] = null
                    Disposal.end(d)
                }
                else -> throw IllegalStateException("bad opcode $op at $opStart")
        }
        f.sp = sp
        f.pc = pc
    }

    private fun tdzError(name: String) = JSException.referenceError(
        if (name == "this") "Must call super constructor in derived class before accessing 'this' or returning from derived constructor"
        else "Cannot access '$name' before initialization"
    )
}
