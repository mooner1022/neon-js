package dev.mooner.neonpython

/**
 * A generator, a coroutine or an asynchronous generator (docs/PYTHON_PLATFORM.md §11, M4 and M8): the frame of its
 * function's code, interpreted, kept between its suspensions as JavaScript keeps a generator's
 * (docs/IR_NEUTRALITY.md §9): YIELD writes the value handed over, the pc after it and the operand stack's depth to the
 * frame and returns SUSPENDED; resuming pushes the value sent at the frame's sp and sets its mode
 * ([PyFrame.resumeMode]: RESUME, after a yield, raises a thrown exception there; SEND, in a `yield from` or an await,
 * passes it on). Its exception handled is its own across its yields ([handled]); while it has none, the caller's is
 * the one a bare `raise` raises again and an exception raised in it gets as its context.
 */
abstract class PyGenBase(@JvmField val frame: PyFrame, @JvmField val fn: PyFunction) {
    @JvmField var state = CREATED
    @JvmField var handled: PyBaseException? = null
    /** Its function's return value, once it has returned. */
    @JvmField var returned: Any? = PyNone

    val name: String get() = fn.name
    val qualname: String get() = fn.qualname

    /** What CPython's messages call it: generator, coroutine, async generator. */
    abstract val what: String

    /**
     * Runs it on with [value] sent ([mode] NEXT) or thrown (THROW, THROW_ALL: an exception object): the value it
     * yields, or [PyIterator.STOP] where it returned ([returned]); what it raises, raised (a StopIteration as PEP 479's
     * RuntimeError, an async generator's StopAsyncIteration too).
     */
    fun resume(ctx: PyContext, value: Any?, mode: Int): Any? {
        when (state) {
            RUNNING -> throw PyException("ValueError", "$what already executing")
            FINISHED -> {
                // (an awaited coroutine is done with: resuming it again is the error)
                if (this is PyCoroutine) throw PyException("RuntimeError", "cannot reuse already awaited coroutine")
                if (mode != PyFrame.MODE_NEXT) throw PyGenerators.thrown(value as PyBaseException)
                returned = PyNone
                return PyIterator.STOP
            }
            CREATED -> {
                if (mode != PyFrame.MODE_NEXT) {
                    // (raised where it starts, before its first line runs)
                    state = FINISHED
                    val e = PyGenerators.thrown(value as PyBaseException)
                    if (e.line < 0) {
                        e.line = fn.info.firstLine
                        e.function = fn.code.name
                    }
                    throw e
                }
                if (value !== PyNone) throw PyRt.typeError("can't send non-None value to a just-started $what")
            }
            else -> {
                frame.slots[frame.sp++] = value
                frame.resumeMode = mode
            }
        }
        state = RUNNING
        ctx.handledStack.add(ctx.handled)
        ctx.handled = handled
        try {
            val r = PyInterpreter.runGenerator(frame)
            if (r === PyInterpreter.SUSPENDED) {
                state = SUSPENDED
                val v = frame.suspendValue
                frame.suspendValue = null
                return v
            }
            state = FINISHED
            returned = r
            return PyIterator.STOP
        } catch (e: PyException) {
            state = FINISHED
            val msg = when {
                e.matches(PyExceptions.type("StopIteration")) -> "$what raised StopIteration"
                this is PyAsyncGenerator && e.matches(PyExceptions.type("StopAsyncIteration")) -> "async generator raised StopAsyncIteration"
                else -> null
            }
            if (msg != null) {
                // PEP 479: a StopIteration out of a generator's body is a RuntimeError
                val stop = PyExceptions.objectOf(e, ctx)
                val err = PyException("RuntimeError", msg)
                val o = PyExceptions.objectOf(err, null)
                o.cause = stop
                o.context = stop
                o.suppressContext = true
                // (raised where it was resumed: its frame is gone)
                throw err
            }
            throw e
        } catch (t: Throwable) {
            state = FINISHED
            throw t
        } finally {
            handled = if (state == FINISHED) null else ctx.handled
            ctx.handled = ctx.handledStack.removeAt(ctx.handledStack.size - 1)
        }
    }

    /**
     * What its frame, suspended, delegates to (CPython's _PyGen_yf): the iterator of the `yield from` or the await it
     * is suspended in (its YIELD the one of a SEND's loop: a jump back to the SEND after it), or null.
     */
    fun yieldFrom(): Any? {
        if (state != SUSPENDED) return null
        val bc = frame.code.code
        val pc = frame.pc
        if (bc[pc] != PyOp.JUMP) return null
        val top = bc[pc + 1]
        return if (bc[top] == PyOp.SEND) frame.slots[bc[top + 1]] else null
    }

    override fun toString() = "<${PyTypes.typeOf(this).name} object $qualname at ${PyGenerators.addr(this)}>"

    companion object {
        const val CREATED = 0
        const val SUSPENDED = 1
        const val RUNNING = 2
        const val FINISHED = 3
    }
}

/** A generator function's generator: an iterator. */
class PyGenerator(frame: PyFrame, fn: PyFunction) : PyGenBase(frame, fn) {
    override val what get() = "generator"

    /** The next value, or [PyIterator.STOP] (`for`, next()). */
    fun next(): Any? = resume(PyContext.current(), PyNone, PyFrame.MODE_NEXT)
}

/** An `async def`'s coroutine (PEP 492): awaitable, stepped by send() and throw(), not an iterator. */
class PyCoroutine(frame: PyFrame, fn: PyFunction) : PyGenBase(frame, fn) {
    override val what get() = "coroutine"
}

/**
 * An `async def` with yields' asynchronous generator (PEP 525): stepped by the awaitables its __anext__, asend(),
 * athrow() and aclose() give ([PyAsyncGenASend], [PyAsyncGenAThrow]); its yields' values wrapped ([PyGenerators.Wrapped],
 * ASYNC_GEN_WRAP), an await's passing through unwrapped.
 */
class PyAsyncGenerator(frame: PyFrame, fn: PyFunction) : PyGenBase(frame, fn) {
    override val what get() = "async generator"
    /** Whether one of its awaitables is stepping it (CPython's ag_running_async). */
    @JvmField var runningAsync = false
    /** Whether it has been closed or has finished (ag_closed). */
    @JvmField var closed = false
}

/**
 * An awaitable's iterator of the runtime's (CPython's coroutine_wrapper, async_generator_asend and _athrow,
 * anext_awaitable): stepped by SEND as by its type's methods (send, throw, close; __next__ is send(None)); where it is
 * done, a [PyGenerators.Done] (its methods raise that StopIteration).
 */
abstract class PyAwaitIter(@JvmField val type: PyType) {
    abstract fun send(ctx: PyContext, v: Any?): Any?
    abstract fun throwIn(ctx: PyContext, exc: PyBaseException): Any?
    abstract fun close(ctx: PyContext): Any?

    override fun toString() = "<${type.name} object at ${PyGenerators.addr(this)}>"

    companion object {
        const val INIT = 0
        const val ITER = 1
        const val CLOSED = 2
    }
}

/** coro.__await__(): its coroutine stepped as an iterator. */
class PyCoroWrapper(@JvmField val coro: PyCoroutine) : PyAwaitIter(PyGenerators.CORO_WRAPPER) {
    override fun send(ctx: PyContext, v: Any?): Any? = PyGenerators.done(coro, coro.resume(ctx, v, PyFrame.MODE_NEXT))
    override fun throwIn(ctx: PyContext, exc: PyBaseException): Any? = PyGenerators.done(coro, coro.resume(ctx, exc, PyFrame.MODE_THROW))
    override fun close(ctx: PyContext): Any? = PyGenerators.close(ctx, coro)
}

/**
 * ag.__anext__(), ag.asend(v): one step of [gen], [sendval] sent where it is first sent None; done with the value it
 * yields, StopAsyncIteration where it returns (CPython's async_gen_asend_send and _throw).
 */
class PyAsyncGenASend(@JvmField val gen: PyAsyncGenerator, @JvmField val sendval: Any?) : PyAwaitIter(PyGenerators.ASEND) {
    @JvmField var state = INIT

    private fun begin() {
        if (state == CLOSED) throw PyException("RuntimeError", "cannot reuse already awaited __anext__()/asend()")
        if (state == INIT && gen.runningAsync) {
            state = CLOSED
            throw PyException("RuntimeError", "anext(): asynchronous generator is already running")
        }
    }

    private fun finish(ctx: PyContext, v: Any?, mode: Int): Any? {
        gen.runningAsync = true
        val r = try {
            PyGenerators.unwrap(gen, gen.resume(ctx, v, mode))
        } catch (e: PyException) {
            state = CLOSED
            PyGenerators.unwrapError(gen, e)
        }
        if (r is PyGenerators.Done) state = CLOSED
        return r
    }

    override fun send(ctx: PyContext, v: Any?): Any? {
        begin()
        var a = v
        if (state == INIT) {
            if (a === PyNone) a = sendval
            state = ITER
        }
        return finish(ctx, a, PyFrame.MODE_NEXT)
    }

    override fun throwIn(ctx: PyContext, exc: PyBaseException): Any? {
        begin()
        state = ITER
        return finish(ctx, exc, PyFrame.MODE_THROW)
    }

    override fun close(ctx: PyContext): Any? {
        if (state == CLOSED) return PyNone
        val r = try {
            throwIn(ctx, PyGenerators.exitObject())
        } catch (e: PyException) {
            if (PyGenerators.ends(e)) return PyNone
            throw e
        }
        if (r is PyGenerators.Done) return PyNone
        throw PyException("RuntimeError", "coroutine ignored GeneratorExit")
    }
}

/**
 * ag.athrow(...) ([args] its arguments, checked where it is first sent) and ag.aclose() ([args] null): an exception
 * thrown into [gen] (GeneratorExit for aclose, its awaits given a chance to finish), CPython's async_gen_athrow_send and
 * _throw.
 */
class PyAsyncGenAThrow(@JvmField val gen: PyAsyncGenerator, @JvmField val args: Array<Any?>?) : PyAwaitIter(PyGenerators.ATHROW) {
    @JvmField var state = INIT

    private val closing get() = args == null

    private fun reused(): PyException = PyException("RuntimeError", "cannot reuse already awaited aclose()/athrow()")

    private fun running(): PyException {
        state = CLOSED
        return PyException("RuntimeError", "${if (closing) "aclose" else "athrow"}(): asynchronous generator is already running")
    }

    /** check_error: it is done; aclose's StopAsyncIteration or GeneratorExit is its end. */
    private fun checkError(e: PyException): Any? {
        gen.runningAsync = false
        state = CLOSED
        if (closing && (e.matches(PyExceptions.type("StopAsyncIteration")) || e.matches(PyExceptions.type("GeneratorExit")))) return PyGenerators.Done(PyNone)
        throw e
    }

    private fun ignoredExit(): Nothing {
        gen.runningAsync = false
        state = CLOSED
        throw PyException("RuntimeError", "async generator ignored GeneratorExit")
    }

    /** What aclose makes of [r], what resuming the generator gave. */
    private fun closeResult(r: Any?): Any? = when {
        r === PyIterator.STOP -> checkError(PyException("StopAsyncIteration", ""))
        r is PyGenerators.Wrapped -> ignoredExit()
        else -> r
    }

    override fun send(ctx: PyContext, v: Any?): Any? {
        if (state == CLOSED) throw reused()
        if (gen.state == PyGenBase.FINISHED) {
            state = CLOSED
            return PyGenerators.Done(PyNone)
        }
        if (state == INIT) {
            if (gen.runningAsync) throw running()
            if (gen.closed) {
                state = CLOSED
                throw PyException("StopAsyncIteration", "")
            }
            if (v !== PyNone) throw PyException("RuntimeError", "can't send non-None value to a just-started coroutine")
            state = ITER
            gen.runningAsync = true
            if (closing) {
                gen.closed = true
                val r = try {
                    gen.resume(ctx, PyGenerators.exitObject(), PyFrame.MODE_THROW_ALL)
                } catch (e: PyException) {
                    return checkError(e)
                }
                return closeResult(r)
            }
            val exc = PyGenerators.throwArg(ctx, "athrow", args!!)
            val r = try {
                PyGenerators.unwrap(gen, gen.resume(ctx, exc, PyFrame.MODE_THROW_ALL))
            } catch (e: PyException) {
                PyGenerators.markClosed(gen, e)
                return checkError(e)
            }
            if (r is PyGenerators.Done) {
                gen.runningAsync = false
                state = CLOSED
            }
            return r
        }
        if (!closing) {
            return try {
                PyGenerators.unwrap(gen, gen.resume(ctx, v, PyFrame.MODE_NEXT))
            } catch (e: PyException) {
                PyGenerators.unwrapError(gen, e)
            }
        }
        val r = try {
            gen.resume(ctx, v, PyFrame.MODE_NEXT)
        } catch (e: PyException) {
            return checkError(e)
        }
        return closeResult(r)
    }

    override fun throwIn(ctx: PyContext, exc: PyBaseException): Any? {
        if (state == CLOSED) throw reused()
        if (state == INIT) {
            if (gen.runningAsync) throw running()
            state = ITER
            gen.runningAsync = true
        }
        if (!closing) {
            return try {
                PyGenerators.unwrap(gen, gen.resume(ctx, exc, PyFrame.MODE_THROW))
            } catch (e: PyException) {
                PyGenerators.unwrapError(gen, e)
            }
        }
        val r = try {
            gen.resume(ctx, exc, PyFrame.MODE_THROW)
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("StopAsyncIteration")) || e.matches(PyExceptions.type("GeneratorExit"))) {
                gen.runningAsync = false
                state = CLOSED
                return PyGenerators.Done(PyNone)
            }
            throw e
        }
        if (r is PyGenerators.Wrapped) ignoredExit()
        if (r === PyIterator.STOP) {
            gen.runningAsync = false
            state = CLOSED
            return PyGenerators.Done(PyNone)
        }
        return r
    }

    override fun close(ctx: PyContext): Any? {
        if (state == CLOSED) return PyNone
        val r = try {
            throwIn(ctx, PyGenerators.exitObject())
        } catch (e: PyException) {
            if (PyGenerators.ends(e)) return PyNone
            throw e
        }
        if (r is PyGenerators.Done) return PyNone
        throw PyException("RuntimeError", "coroutine ignored GeneratorExit")
    }
}

/**
 * anext(aiterator, default): [wrapped] (what its __anext__ gave) awaited, StopAsyncIteration out of it the end with
 * [default] (CPython's anextawaitable).
 */
class PyANextAwaitable(@JvmField val wrapped: Any?, @JvmField val default: Any?) : PyAwaitIter(PyGenerators.ANEXT_AWAITABLE) {
    private fun iter(ctx: PyContext): Any? {
        val a = PyGenerators.awaitableIter(ctx, wrapped)
        // (a coroutine, the one awaitable without __next__, through its __await__)
        return if (a is PyCoroutine) PyCoroWrapper(a) else a
    }

    private inline fun proxy(step: () -> Any?): Any? = try {
        step()
    } catch (e: PyException) {
        if (e.matches(PyExceptions.type("StopAsyncIteration"))) PyGenerators.Done(default, true) else throw e
    }

    override fun send(ctx: PyContext, v: Any?): Any? = proxy { PyGenerators.step(ctx, iter(ctx), v, PyFrame.MODE_NEXT) }
    override fun throwIn(ctx: PyContext, exc: PyBaseException): Any? = proxy { PyGenerators.step(ctx, iter(ctx), exc, PyFrame.MODE_THROW_ALL) }
    override fun close(ctx: PyContext): Any? = proxy { PyGenerators.closeIter(ctx, iter(ctx)) }
}

/**
 * The instructions of generators and coroutines (YIELD's resumption, `yield from`, await, `async for`), and the types
 * of generators, coroutines, asynchronous generators and their awaitables.
 */
object PyGenerators {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val CORO_WRAPPER = PyTypes.newBuiltin("coroutine_wrapper", subclassable = false)
    @JvmField val ASEND = PyTypes.newBuiltin("async_generator_asend", subclassable = false)
    @JvmField val ATHROW = PyTypes.newBuiltin("async_generator_athrow", subclassable = false)
    @JvmField val ANEXT_AWAITABLE = PyTypes.newBuiltin("anext_awaitable", subclassable = false)

    /**
     * What SEND gives where the iterator it steps is done: its value (a StopIteration's, a generator's return value);
     * [boxed], where a method raises it, StopIteration(value) even for None (CPython's _PyGen_SetStopIterationValue).
     */
    class Done(@JvmField val value: Any?, @JvmField val boxed: Boolean = false)

    /** An async generator's yielded value (ASYNC_GEN_WRAP): what its awaitables end with, where an await's pass on. */
    class Wrapped(@JvmField val value: Any?)

    @JvmStatic fun addr(v: Any): String = "0x" + Integer.toHexString(System.identityHashCode(v)).padStart(16, '0').uppercase()

    /** The JVM exception raising [o]. */
    @JvmStatic fun thrown(o: PyBaseException): PyException = PyExceptions.reraise(o) as PyException

    /** StopIteration(value): a generator's end, its return value carried (none where it is None). */
    @JvmStatic fun stop(value: Any?): PyException {
        val e = PyException("StopIteration", "")
        if (value !== PyNone) e.args = arrayOf(value)
        return e
    }

    /** The StopIteration a method raises for [d]. */
    @JvmStatic fun stopOf(d: Done): PyException = if (d.boxed) PyException("StopIteration", "").also { it.args = arrayOf(d.value) } else stop(d.value)

    /** A method's result: a step's value, its Done raised. */
    private fun out(r: Any?): Any? = if (r is Done) throw stopOf(r) else r

    /** [r], what resuming [g] gave, as a step: its return value Done. */
    @JvmStatic fun done(g: PyGenBase, r: Any?): Any? = if (r === PyIterator.STOP) Done(g.returned) else r

    @JvmStatic fun exitObject(): PyBaseException = PyExceptions.objectOf(PyException("GeneratorExit", ""), null)

    /** Whether [e] is what closing an awaitable ends with. */
    @JvmStatic fun ends(e: PyException): Boolean = e.matches(PyExceptions.type("StopIteration")) ||
        e.matches(PyExceptions.type("StopAsyncIteration")) || e.matches(PyExceptions.type("GeneratorExit"))

    /** RESUME, after a YIELD: the value sent, or (a throw mode) the exception thrown in, raised here. */
    @JvmStatic
    fun resumeCheck(vf: dev.mooner.neonjs.vm.VmFrame, sent: Any?): Any? {
        val f = vf as PyFrame
        if (f.resumeMode == PyFrame.MODE_NEXT) return sent
        f.resumeMode = PyFrame.MODE_NEXT
        throw thrown(sent as PyBaseException)
    }

    /**
     * A YIELD in the tier's code: the frame left as the interpreter's YIELD leaves it (its slots, registers then the
     * operand stack, written by the code up to [sp]; [pc] the RESUME after it), [value] what it yields.
     */
    @JvmStatic
    fun suspend(f: dev.mooner.neonjs.vm.VmFrame, pc: Int, sp: Int, value: Any?): Any? {
        val s = f.slots
        java.util.Arrays.fill(s, sp, s.size, null)
        f.sp = sp
        f.pc = pc
        (f as PyFrame).suspendValue = value
        return PyInterpreter.SUSPENDED
    }

    /** After SEND's step [r]: what it yields, or its result where it is done (the value on the stack). */
    @JvmStatic fun sendValue(r: Any?): Any? = if (r is Done) r.value else r

    /** GET_YIELD_FROM_ITER: what `yield from v` steps: a generator itself, else iter(v); never a coroutine. */
    @JvmStatic
    fun yieldFromIter(v: Any?): Any? {
        if (v is PyGenerator) return v
        if (v is PyCoroutine) throw PyRt.typeError("cannot 'yield from' a coroutine object in a non-coroutine generator")
        return PyDunder.iterObject(v)
    }

    /** The value of the StopIteration [e] ends an iteration with. */
    private fun stopValue(e: PyException, ctx: PyContext): Any? = PyExceptions.objectOf(e, ctx).field("value")

    /**
     * SEND: one step of `yield from` or await over [sub], with what the frame was resumed with ([step]). The value it
     * yields, or [Done] with its result.
     */
    @JvmStatic
    fun send(vf: dev.mooner.neonjs.vm.VmFrame, sub: Any?, sent: Any?): Any? {
        val f = vf as PyFrame
        val mode = f.resumeMode
        f.resumeMode = PyFrame.MODE_NEXT
        return step(f.ctx, sub, sent, mode)
    }

    /**
     * One step of [sub] with [sent] sent ([mode] NEXT: send, or next for None) or thrown in (THROW: passed to its
     * throw where it has one, GeneratorExit closing it and raised here; THROW_ALL: GeneratorExit thrown in as any
     * other, an async generator's aclose letting its awaits finish). The value it yields, or [Done] with its result
     * (a StopIteration's value: one raised here too, CPython's CLEANUP_THROW).
     */
    @JvmStatic
    fun step(ctx: PyContext, sub: Any?, sent: Any?, mode: Int): Any? {
        try {
            if (mode != PyFrame.MODE_NEXT) {
                val exc = sent as PyBaseException
                if (mode == PyFrame.MODE_THROW && exc.type.isSubtype(PyExceptions.type("GeneratorExit"))) {
                    closeIter(ctx, sub)
                    throw thrown(exc)
                }
                if (sub is PyGenBase) return done(sub, sub.resume(ctx, exc, mode))
                if (sub is PyAwaitIter) return sub.throwIn(ctx, exc)
                val t = attr(sub, "throw") ?: throw thrown(exc)
                return PyCalls.callWith(ctx, t, arrayOf(exc), NO_ARGS, NO_ARGS)
            }
            if (sub is PyGenBase) return done(sub, sub.resume(ctx, sent, PyFrame.MODE_NEXT))
            if (sub is PyAwaitIter) return sub.send(ctx, sent)
            if (sent === PyNone) {
                if (sub is PyIterator) {
                    val v = sub.next()
                    return if (v === PyIterator.STOP) Done(PyNone) else v
                }
                // (its __next__ itself: the StopIteration's value is the result)
                return PyCalls.callWith(ctx, PyRt.getAttr(sub, "__next__"), NO_ARGS, NO_ARGS, NO_ARGS)
            }
            val s = PyRt.getAttr(sub, "send")
            return PyCalls.callWith(ctx, s, arrayOf(sent), NO_ARGS, NO_ARGS)
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("StopIteration"))) return Done(stopValue(e, ctx))
            throw e
        }
    }

    /** gen_close_iter: [sub] closed (a generator's or coroutine's close, an awaitable's, else its close method if any). */
    @JvmStatic
    fun closeIter(ctx: PyContext, sub: Any?): Any? = when (sub) {
        is PyGenBase -> close(ctx, sub)
        is PyAwaitIter -> sub.close(ctx)
        else -> attr(sub, "close")?.let { PyCalls.callWith(ctx, it, NO_ARGS, NO_ARGS, NO_ARGS) } ?: PyNone
    }

    private fun attr(v: Any?, n: String): Any? = try {
        PyRt.getAttr(v, n)
    } catch (e: PyException) {
        if (e.matches(PyExceptions.type("AttributeError"))) null else throw e
    }

    /**
     * g.close(): GeneratorExit thrown in; its return value where it returns (3.13's), nothing where it raises
     * GeneratorExit or StopIteration, CPython's RuntimeError where it yields.
     */
    @JvmStatic
    fun close(ctx: PyContext, g: PyGenBase): Any? {
        if (g.state == PyGenBase.CREATED || g.state == PyGenBase.FINISHED) {
            g.state = PyGenBase.FINISHED
            return PyNone
        }
        try {
            val r = g.resume(ctx, exitObject(), PyFrame.MODE_THROW)
            if (r !== PyIterator.STOP) throw PyException("RuntimeError", "${g.what} ignored GeneratorExit")
            return g.returned
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("GeneratorExit")) || e.matches(PyExceptions.type("StopIteration"))) return PyNone
            throw e
        }
    }

    /** The exception throw(...) ([name]'s) throws in, from [a]: an instance, a class (called), or the old (type, value, tb). */
    @JvmStatic
    fun throwArg(ctx: PyContext, name: String, a: Array<Any?>): PyBaseException {
        if (a.isEmpty()) throw PyRt.typeError("$name expected at least 1 argument, got 0")
        if (a.size > 3) throw PyRt.typeError("$name expected at most 3 arguments, got ${a.size}")
        val t = a[0]
        val v = a.getOrNull(1) ?: PyNone
        if (t is PyBaseException) {
            if (v !== PyNone) throw PyRt.typeError("instance exception may not have a separate value")
            return t
        }
        if (t is PyType && t.isSubtype(PyExceptions.BASE_EXCEPTION)) {
            if (v is PyBaseException && v.type.isSubtype(t)) return v
            val args: Array<Any?> = if (v === PyNone) NO_ARGS else if (v is PyTuple) v.items else arrayOf<Any?>(v)
            return PyClasses.instantiate(ctx, t, args, NO_ARGS, NO_ARGS) as PyBaseException
        }
        throw PyRt.typeError("exceptions must be classes or instances deriving from BaseException, not ${PyTypes.name(t)}")
    }

    // ------------------------------------------------------------------ await, async for (M8)

    /** Special method [m] (looked up on [v]'s type) called on [v]. */
    private fun callSpecial(ctx: PyContext, m: Any?, v: Any?): Any? =
        PyCalls.callWith(ctx, PyClasses.descrGet(ctx, m, v, PyTypes.typeOf(v)), NO_ARGS, NO_ARGS, NO_ARGS)

    /** Whether [v] is an iterator (has __next__). */
    private fun isIterator(v: Any?): Boolean =
        v is PyIterator || v is PyGenerator || v is PyAwaitIter || PyRt.special(v) && PyTypes.typeOf(v).lookup("__next__") != null

    /** Whether [v] is an asynchronous iterator (has __anext__). */
    private fun isAsyncIter(v: Any?): Boolean = v is PyAsyncGenerator || PyTypes.typeOf(v).lookup("__anext__") != null

    /**
     * What awaiting [v] steps (CPython's _PyCoro_GetAwaitableIter): a coroutine itself, else the iterator its type's
     * __await__ gives.
     */
    @JvmStatic
    fun awaitableIter(ctx: PyContext, v: Any?): Any? {
        if (v is PyCoroutine) return v
        val t = PyTypes.typeOf(v)
        val m = t.lookup("__await__") ?: throw PyRt.typeError("'${t.name}' object can't be awaited")
        val r = callSpecial(ctx, m, v)
        if (r is PyCoroutine) throw PyRt.typeError("__await__() returned a coroutine")
        if (!isIterator(r)) throw PyRt.typeError("__await__() returned non-iterator of type '${PyTypes.name(r)}'")
        return r
    }

    /**
     * GET_AWAITABLE [k]: what `await v` steps; [k] 1 and 2 an `async with`'s __aenter__'s and __aexit__'s result, whose
     * messages say so. A coroutine another await is stepping is the error.
     */
    @JvmStatic
    fun getAwaitable(vf: dev.mooner.neonjs.vm.VmFrame, v: Any?, k: Int): Any? {
        val f = vf as PyFrame
        val it = try {
            awaitableIter(f.ctx, v)
        } catch (e: PyException) {
            if (k != 0 && PyTypes.typeOf(v).lookup("__await__") == null) {
                throw PyRt.typeError("'async with' received an object from ${if (k == 1) "__aenter__" else "__aexit__"} that does not implement __await__: ${PyTypes.name(v)}")
            }
            throw e
        }
        if (it is PyCoroutine && it.yieldFrom() != null) throw PyException("RuntimeError", "coroutine is being awaited already")
        return it
    }

    /** GET_AITER: what `async for` over [v] steps, its __aiter__'s. */
    @JvmStatic
    fun getAIter(f: dev.mooner.neonjs.vm.VmFrame, v: Any?): Any? {
        if (v is PyAsyncGenerator) return v
        val t = PyTypes.typeOf(v)
        val m = t.lookup("__aiter__") ?: throw PyRt.typeError("'async for' requires an object with __aiter__ method, got ${t.name}")
        val it = callSpecial((f as PyFrame).ctx, m, v)
        if (!isAsyncIter(it)) throw PyRt.typeError("'async for' received an object from __aiter__ that does not implement __anext__: ${PyTypes.name(it)}")
        return it
    }

    /** GET_ANEXT: the awaitable of [aiter]'s next value (an async generator's asend; else its __anext__'s, awaitable). */
    @JvmStatic
    fun getANext(f: dev.mooner.neonjs.vm.VmFrame, aiter: Any?): Any? {
        if (aiter is PyAsyncGenerator) return PyAsyncGenASend(aiter, PyNone)
        val ctx = (f as PyFrame).ctx
        val t = PyTypes.typeOf(aiter)
        val m = t.lookup("__anext__") ?: throw PyRt.typeError("'async for' requires an iterator with __anext__ method, got ${t.name}")
        val next = callSpecial(ctx, m, aiter)
        try {
            return awaitableIter(ctx, next)
        } catch (e: PyException) {
            val cause = PyExceptions.objectOf(e, ctx)
            val err = PyRt.typeError("'async for' received an invalid object from __anext__: ${PyTypes.name(next)}")
            val o = PyExceptions.objectOf(err, null)
            o.cause = cause
            o.context = cause
            o.suppressContext = true
            throw err
        }
    }

    /** END_ASYNC_FOR: [exc], what awaiting the next value raised: the loop's end where a StopAsyncIteration, else raised again. */
    @JvmStatic
    fun endAsyncFor(exc: Any?) {
        val e = exc as PyBaseException
        if (!e.type.isSubtype(PyExceptions.type("StopAsyncIteration"))) throw thrown(e)
    }

    /** ASYNC_GEN_WRAP: an async generator's yielded value. */
    @JvmStatic fun asyncGenWrap(v: Any?): Any? = Wrapped(v)

    // ------------------------------------------------------------------ an async generator's awaitables

    /** async_gen_unwrap_value: [r], what resuming [g] gave, as its awaitable's step: a yielded value Done, its return StopAsyncIteration. */
    @JvmStatic
    fun unwrap(g: PyAsyncGenerator, r: Any?): Any? {
        if (r === PyIterator.STOP) {
            g.closed = true
            g.runningAsync = false
            throw PyException("StopAsyncIteration", "")
        }
        if (r is Wrapped) {
            g.runningAsync = false
            return Done(r.value, true)
        }
        return r
    }

    @JvmStatic
    fun markClosed(g: PyAsyncGenerator, e: PyException) {
        if (e.matches(PyExceptions.type("StopAsyncIteration")) || e.matches(PyExceptions.type("GeneratorExit"))) g.closed = true
        g.runningAsync = false
    }

    /** async_gen_unwrap_value of an exception: [e] raised on, [g] closed by its end. */
    @JvmStatic
    fun unwrapError(g: PyAsyncGenerator, e: PyException): Nothing {
        markClosed(g, e)
        throw e
    }

    // ------------------------------------------------------------------ the types

    private inline fun <reified T> self(a: Array<Any?>, n: String, t: PyType): T =
        a.getOrNull(0) as? T ?: throw PyRt.typeError("descriptor '$n' for '${t.name}' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")

    private fun one(a: Array<Any?>, n: String, t: PyType) {
        if (a.size != 2) throw PyRt.typeError("${t.name}.$n() takes exactly one argument (${a.size - 1} given)")
    }

    private fun rest(a: Array<Any?>): Array<Any?> = if (a.size <= 1) NO_ARGS else a.copyOfRange(1, a.size)

    /** The types' methods (generator, coroutine, async_generator, the awaitables), and aiter() and anext() into [d]. */
    @JvmStatic
    fun define(d: PyDict) {
        d[PyStr("aiter")] = PyBuiltin("aiter") { ctx, a ->
            if (a.size != 1) throw PyRt.typeError("aiter() takes exactly one argument (${a.size} given)")
            val v = a[0]
            if (v is PyAsyncGenerator) v else {
                val t = PyTypes.typeOf(v)
                val m = t.lookup("__aiter__") ?: throw PyRt.typeError("'${t.name}' object is not an async iterable")
                val it = callSpecial(ctx, m, v)
                if (!isAsyncIter(it)) throw PyRt.typeError("aiter() returned not an async iterator of type '${PyTypes.name(it)}'")
                it
            }
        }
        d[PyStr("anext")] = PyBuiltin("anext") { ctx, a ->
            if (a.isEmpty()) throw PyRt.typeError("anext expected at least 1 argument, got 0")
            if (a.size > 2) throw PyRt.typeError("anext expected at most 2 arguments, got ${a.size}")
            val v = a[0]
            if (!isAsyncIter(v)) throw PyRt.typeError("'${PyTypes.name(v)}' object is not an async iterator")
            val aw = if (v is PyAsyncGenerator) PyAsyncGenASend(v, PyNone) else callSpecial(ctx, PyTypes.typeOf(v).lookup("__anext__"), v)
            if (a.size == 1) aw else PyANextAwaitable(aw, a[1])
        }
        val t = PyTypes.GENERATOR
        if (t.dict.containsKey(PyStr("send"))) return
        fun result(g: PyGenBase, r: Any?): Any? = if (r === PyIterator.STOP) throw stop(g.returned) else r
        t.def("__iter__", PyBuiltin("__iter__") { _, a -> self<PyGenerator>(a, "__iter__", t) })
        t.def("__next__", PyBuiltin("__next__") { ctx, a -> val g = self<PyGenerator>(a, "__next__", t); result(g, g.resume(ctx, PyNone, PyFrame.MODE_NEXT)) })
        // a generator's and a coroutine's send, throw and close
        for (gt in arrayOf(t, PyTypes.COROUTINE)) {
            gt.def("send", PyBuiltin("send") { ctx, a ->
                val g = self<PyGenBase>(a, "send", gt)
                one(a, "send", gt)
                result(g, g.resume(ctx, a[1], PyFrame.MODE_NEXT))
            })
            gt.def("throw", PyBuiltin("throw") { ctx, a ->
                val g = self<PyGenBase>(a, "throw", gt)
                result(g, g.resume(ctx, throwArg(ctx, "throw", rest(a)), PyFrame.MODE_THROW))
            })
            gt.def("close", PyBuiltin("close") { ctx, a -> close(ctx, self<PyGenBase>(a, "close", gt)) })
        }
        val c = PyTypes.COROUTINE
        c.def("__await__", PyBuiltin("__await__") { _, a -> PyCoroWrapper(self<PyCoroutine>(a, "__await__", c)) })
        val ag = PyTypes.ASYNC_GENERATOR
        ag.def("__aiter__", PyBuiltin("__aiter__") { _, a -> self<PyAsyncGenerator>(a, "__aiter__", ag) })
        ag.def("__anext__", PyBuiltin("__anext__") { _, a -> PyAsyncGenASend(self(a, "__anext__", ag), PyNone) })
        ag.def("asend", PyBuiltin("asend") { _, a ->
            val g = self<PyAsyncGenerator>(a, "asend", ag)
            one(a, "asend", ag)
            PyAsyncGenASend(g, a[1])
        })
        ag.def("athrow", PyBuiltin("athrow") { _, a -> PyAsyncGenAThrow(self(a, "athrow", ag), rest(a)) })
        ag.def("aclose", PyBuiltin("aclose") { _, a -> PyAsyncGenAThrow(self(a, "aclose", ag), null) })
        // the awaitables' iterators: themselves their __await__'s (all but coroutine_wrapper)
        for (at in arrayOf(CORO_WRAPPER, ASEND, ATHROW, ANEXT_AWAITABLE)) {
            at.def("__iter__", PyBuiltin("__iter__") { _, a -> self<PyAwaitIter>(a, "__iter__", at) })
            at.def("__next__", PyBuiltin("__next__") { ctx, a -> out(self<PyAwaitIter>(a, "__next__", at).send(ctx, PyNone)) })
            at.def("send", PyBuiltin("send") { ctx, a ->
                val it = self<PyAwaitIter>(a, "send", at)
                one(a, "send", at)
                out(it.send(ctx, a[1]))
            })
            at.def("throw", PyBuiltin("throw") { ctx, a ->
                val it = self<PyAwaitIter>(a, "throw", at)
                out(it.throwIn(ctx, throwArg(ctx, "throw", rest(a))))
            })
            at.def("close", PyBuiltin("close") { ctx, a -> out(self<PyAwaitIter>(a, "close", at).close(ctx)) })
            if (at !== CORO_WRAPPER) at.def("__await__", PyBuiltin("__await__") { _, a -> self<PyAwaitIter>(a, "__await__", at) })
        }
    }
}
