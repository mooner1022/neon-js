package dev.mooner.neonpython

/**
 * A generator (docs/PYTHON_PLATFORM.md §11, M4): the frame of its function's code, interpreted, kept between its
 * yields as JavaScript keeps a generator's (docs/IR_NEUTRALITY.md §9): YIELD writes the value handed over, the pc after
 * it and the operand stack's depth to the frame and returns SUSPENDED; resuming pushes the value sent at the frame's
 * sp and sets its mode ([PyFrame.resumeMode]: RESUME, after a yield, raises a thrown exception there; SEND, in a
 * `yield from`, passes it on). Its exception handled is its own across its yields ([handled]); while it has none, the
 * caller's is the one a bare `raise` raises again and an exception raised in it gets as its context.
 */
class PyGenerator(@JvmField val frame: PyFrame, @JvmField val fn: PyFunction) {
    @JvmField var state = CREATED
    @JvmField var handled: PyBaseException? = null
    /** Its function's return value, once it has returned. */
    @JvmField var returned: Any? = PyNone

    val name: String get() = fn.name
    val qualname: String get() = fn.qualname

    /**
     * Runs it on with [value] sent ([mode] NEXT) or thrown (THROW, an exception object): the value it yields, or
     * [PyIterator.STOP] where it returned ([returned]); what it raises, raised (a StopIteration as PEP 479's
     * RuntimeError).
     */
    fun resume(ctx: PyContext, value: Any?, mode: Int): Any? {
        when (state) {
            RUNNING -> throw PyException("ValueError", "generator already executing")
            FINISHED -> {
                if (mode == PyFrame.MODE_THROW) throw PyGenerators.thrown(value as PyBaseException)
                returned = PyNone
                return PyIterator.STOP
            }
            CREATED -> {
                if (mode == PyFrame.MODE_THROW) {
                    // (raised where it starts, before its first line runs)
                    state = FINISHED
                    val e = PyGenerators.thrown(value as PyBaseException)
                    if (e.line < 0) {
                        e.line = fn.info.firstLine
                        e.function = fn.code.name
                    }
                    throw e
                }
                if (value !== PyNone) throw PyRt.typeError("can't send non-None value to a just-started generator")
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
            if (e.matches(PyExceptions.type("StopIteration"))) {
                // PEP 479: a StopIteration out of a generator's body is a RuntimeError
                val stop = PyExceptions.objectOf(e, ctx)
                val err = PyException("RuntimeError", "generator raised StopIteration")
                val o = PyExceptions.objectOf(err, null)
                o.cause = stop
                o.context = stop
                o.suppressContext = true
                // (raised where the generator was resumed: its frame is gone)
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

    /** The next value, or [PyIterator.STOP] (`for`, next()). */
    fun next(): Any? = resume(PyContext.current(), PyNone, PyFrame.MODE_NEXT)

    override fun toString() = "<generator object $qualname>"

    companion object {
        const val CREATED = 0
        const val SUSPENDED = 1
        const val RUNNING = 2
        const val FINISHED = 3
    }
}

/** The instructions of generators (YIELD's resumption, `yield from`) and the generator type's methods. */
object PyGenerators {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** What SEND gives where the iterator it steps is done: its value (a StopIteration's, a generator's return value). */
    class Done(@JvmField val value: Any?)

    /** The JVM exception raising [o]. */
    @JvmStatic fun thrown(o: PyBaseException): PyException = PyExceptions.reraise(o) as PyException

    /** StopIteration(value): a generator's end, its return value carried (none where it is None). */
    @JvmStatic fun stop(value: Any?): PyException {
        val e = PyException("StopIteration", "")
        if (value !== PyNone) e.args = arrayOf(value)
        return e
    }

    /** RESUME, after a YIELD: the value sent, or ([PyFrame.MODE_THROW]) the exception thrown in, raised here. */
    @JvmStatic
    fun resumeCheck(f: PyFrame, sent: Any?): Any? {
        if (f.resumeMode == PyFrame.MODE_NEXT) return sent
        f.resumeMode = PyFrame.MODE_NEXT
        throw thrown(sent as PyBaseException)
    }

    /** GET_YIELD_FROM_ITER: what `yield from v` steps: a generator itself, else iter(v). */
    @JvmStatic
    fun yieldFromIter(v: Any?): Any? = if (v is PyGenerator) v else PyDunder.iterObject(v)

    /** The value of the StopIteration [e] ends an iteration with. */
    private fun stopValue(e: PyException, ctx: PyContext): Any? = PyExceptions.objectOf(e, ctx).field("value")

    /**
     * SEND: one step of `yield from` over [sub], with what the frame was resumed with: the value sent (send, or next
     * for None), or an exception thrown in (passed to the iterator's throw where it has one; GeneratorExit closes it
     * and is raised here). The value it yields, or [Done] with its result.
     */
    @JvmStatic
    fun send(f: PyFrame, sub: Any?, sent: Any?): Any? {
        val ctx = f.ctx
        val mode = f.resumeMode
        f.resumeMode = PyFrame.MODE_NEXT
        try {
            if (mode == PyFrame.MODE_THROW) {
                val exc = sent as PyBaseException
                if (exc.type.isSubtype(PyExceptions.type("GeneratorExit"))) {
                    if (sub is PyGenerator) close(ctx, sub)
                    else attr(sub, "close")?.let { PyCalls.callWith(ctx, it, NO_ARGS, NO_ARGS, NO_ARGS) }
                    throw thrown(exc)
                }
                if (sub is PyGenerator) return step(sub, sub.resume(ctx, exc, PyFrame.MODE_THROW))
                val t = attr(sub, "throw") ?: throw thrown(exc)
                return PyCalls.callWith(ctx, t, arrayOf(exc), NO_ARGS, NO_ARGS)
            }
            if (sub is PyGenerator) return step(sub, sub.resume(ctx, sent, PyFrame.MODE_NEXT))
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

    private fun step(g: PyGenerator, r: Any?): Any? = if (r === PyIterator.STOP) Done(g.returned) else r

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
    fun close(ctx: PyContext, g: PyGenerator): Any? {
        if (g.state == PyGenerator.CREATED || g.state == PyGenerator.FINISHED) {
            g.state = PyGenerator.FINISHED
            return PyNone
        }
        val exit = PyExceptions.objectOf(PyException("GeneratorExit", ""), null)
        try {
            val r = g.resume(ctx, exit, PyFrame.MODE_THROW)
            if (r !== PyIterator.STOP) throw PyException("RuntimeError", "generator ignored GeneratorExit")
            return g.returned
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("GeneratorExit")) || e.matches(PyExceptions.type("StopIteration"))) return PyNone
            throw e
        }
    }

    /** The exception gen.throw(...) throws in: an instance, a class (called), or the old (type, value, tb). */
    private fun throwArg(ctx: PyContext, a: Array<Any?>): PyBaseException {
        val t = a.getOrNull(1)
        val v = a.getOrNull(2) ?: PyNone
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

    /** The generator type's methods. */
    @JvmStatic
    fun define() {
        val t = PyTypes.GENERATOR
        if (t.dict.containsKey(PyStr("send"))) return
        fun gen(a: Array<Any?>, n: String): PyGenerator =
            a.getOrNull(0) as? PyGenerator ?: throw PyRt.typeError("descriptor '$n' for 'generator' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")
        fun result(g: PyGenerator, r: Any?): Any? = if (r === PyIterator.STOP) throw stop(g.returned) else r
        t.def("__iter__", PyBuiltin("__iter__") { _, a -> gen(a, "__iter__") })
        t.def("__next__", PyBuiltin("__next__") { ctx, a -> val g = gen(a, "__next__"); result(g, g.resume(ctx, PyNone, PyFrame.MODE_NEXT)) })
        t.def("send", PyBuiltin("send") { ctx, a ->
            val g = gen(a, "send")
            if (a.size != 2) throw PyRt.typeError("generator.send() takes exactly one argument (${a.size - 1} given)")
            result(g, g.resume(ctx, a[1], PyFrame.MODE_NEXT))
        })
        t.def("throw", PyBuiltin("throw") { ctx, a ->
            val g = gen(a, "throw")
            if (a.size < 2 || a.size > 4) throw PyRt.typeError("throw expected at least 1 argument, got ${a.size - 1}")
            result(g, g.resume(ctx, throwArg(ctx, a), PyFrame.MODE_THROW))
        })
        t.def("close", PyBuiltin("close") { ctx, a -> close(ctx, gen(a, "close")) })
    }
}
