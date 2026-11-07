package dev.mooner.neonpython

/**
 * `_signal` for a sandbox (docs/PYTHON_PLATFORM.md §3.8, §12.2), CPython's signal.py over it: Linux's signals and
 * numbers, their handlers recorded as CPython records them (SIG_DFL, SIG_IGN, a callable; SIGINT's
 * default_int_handler, SIGPIPE and SIGXFSZ ignored at startup). No process is there to be signalled, so a signal comes
 * only from the program: raise_signal delivers one at once as raise(3) does, a handler (its byte written to the wakeup
 * descriptor first, as asyncio's loop reads it) called with the current frame, a blocked one pending until unblocked
 * (pthread_sigmask); SIG_DFL's action ignores, stops nothing, or ends the program as os._exit does (SystemExit, the
 * status 128 + the signal's). Timers deliver nothing here: arming one (alarm, setitimer) is refused (EPERM), and pause
 * waits for a signal nothing else could send (the RuntimeError of a wait for ever, as `_thread`'s lock raises).
 */
object PySignal {
    private const val NSIG = 65
    private const val SIGINT = 2
    private const val SIGKILL = 9
    private const val SIGSTOP = 19
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** A context's: each signal's handler (null for 0), the wakeup descriptor, the blocked and the pending. */
    class State {
        @JvmField val handlers = arrayOfNulls<Any?>(NSIG)
        @JvmField var wakeupFd = -1
        @JvmField var warnOnFullBuffer = true
        @JvmField val mask = BooleanArray(NSIG)
        @JvmField val pending = BooleanArray(NSIG)
    }

    /** signal.default_int_handler: raises KeyboardInterrupt (SIGINT's handler). */
    @JvmField val DEFAULT_INT_HANDLER = PyBuiltin("default_int_handler", { _, a -> intHandler(a) }, { _, a, kw ->
        if (kw.size > 0) throw PyRt.typeError("_signal.default_int_handler() takes no keyword arguments")
        intHandler(a)
    })

    private fun intHandler(a: Array<Any?>): Any? {
        if (a.size != 2) throw PyRt.typeError("default_int_handler expected 2 arguments, got ${a.size}")
        throw PyException("KeyboardInterrupt", "")
    }

    @JvmStatic
    fun state(ctx: PyContext): State {
        val p = PyPosix.state(ctx)
        p.signals?.let { return it }
        val s = State()
        for (i in 1 until NSIG) s.handlers[i] = PyNone
        for (i in PySocketData.VALID_SIGNALS) s.handlers[i] = 0L
        for (i in PySocketData.IGNORED_SIGNALS) s.handlers[i] = 1L
        s.handlers[SIGINT] = DEFAULT_INT_HANDLER
        p.signals = s
        return s
    }

    /** An exception type of a module ([base]'s subclass, as PyErr_NewException makes one): socket.herror, signal.ItimerError. */
    @JvmStatic
    internal fun errorType(ctx: PyContext, name: String, module: String, base: String): PyType {
        val b = PyExceptions.type(base)
        val t = PyType(name, arrayOf(b), heap = true)
        t.mro = PyTypes.c3(t, t.bases)
        t.module = module
        t.dict[PyStr("__module__")] = PyStr(module)
        t.dict[PyStr("__doc__")] = PyNone
        b.addSubclass(ctx, t)
        t.hooks = PyClasses.hooksOf(t)
        return t
    }

    /** Whether [h] is the int [n] itself (compare_handler: an int's subclass, a bool, is not). */
    private fun isInt(h: Any?, n: Long): Boolean = h is Long && h == n

    /** A signal delivered: pending where blocked, else its handler's: ignored, its default action, or called. */
    @JvmStatic
    internal fun deliver(ctx: PyContext, st: State, n: Int) {
        if (st.mask[n]) {
            st.pending[n] = true
            return
        }
        val h = st.handlers[n]
        when {
            h == null || h === PyNone || isInt(h, 1) -> return
            isInt(h, 0) -> when (n) {
                // (SIGCHLD, SIGCONT, SIGURG, SIGWINCH: ignored; SIGSTOP, SIGTSTP, SIGTTIN, SIGTTOU: nothing stops the program)
                17, 18, 23, 28, 19, 20, 21, 22 -> return
                else -> throw PyException(PyCalls.callWith(ctx, PyExceptions.type("SystemExit"), arrayOf((128 + n).toLong()), NO_ARGS, NO_ARGS) as PyBaseException)
            }
            else -> {
                wakeup(ctx, st, n)
                val frame: Any? = try {
                    PyFrames.getFrame(ctx, 0L)
                } catch (e: PyException) {
                    PyNone
                }
                PyCalls.callWith(ctx, h, arrayOf(n.toLong(), frame), NO_ARGS, NO_ARGS)
            }
        }
    }

    /** trip_signal's byte (the signal's number) to the wakeup descriptor; a failure reported, not raised. */
    private fun wakeup(ctx: PyContext, st: State, n: Int) {
        val fd = st.wakeupFd
        if (fd == -1) return
        try {
            PyPosix.write(ctx, PyPosix.file(PyPosix.state(ctx), fd), byteArrayOf(n.toByte()))
        } catch (e: PyException) {
            if (st.warnOnFullBuffer || !e.matches(PyExceptions.type("BlockingIOError"))) {
                ctx.err.append("Exception ignored while trying to write to the signal wakeup fd:\n").append(ctx.formatException(e))
            }
        }
    }

    /** The pending signals not blocked now, delivered (the lowest first, as Linux does). */
    private fun deliverPending(ctx: PyContext, st: State) {
        for (n in 1 until NSIG) if (st.pending[n] && !st.mask[n]) {
            st.pending[n] = false
            deliver(ctx, st, n)
        }
    }

    private fun range(n: Int) {
        if (n < 1 || n >= NSIG) throw PyException("ValueError", "signal number out of range")
    }

    /** sigaction's EINVAL: SIGKILL's and SIGSTOP's actions cannot change, nor glibc's own 32 and 33. */
    private fun settable(n: Int) {
        if (n == SIGKILL || n == SIGSTOP || n == 32 || n == 33) throw PyPosix.error(PyErrno.EINVAL)
    }

    private fun sigSet(st: State, which: BooleanArray): PySet = PySet(false).also { s -> for (i in 1 until NSIG) if (which[i]) s.addKey(i.toLong()) }

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val st = state(ctx)
        val itimerError = errorType(ctx, "ItimerError", "signal", "OSError")
        fun itimer(errno: Int): PyException =
            PyException(PyCalls.callWith(ctx, itimerError, arrayOf(errno.toLong(), PyStr(PyErrno.strerror(errno))), NO_ARGS, NO_ARGS) as PyBaseException)
        // (Argument Clinic's positional-only functions: METH_O, METH_NOARGS, or of n arguments)
        fun def(name: String, impl: (Array<Any?>) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a -> impl(a) }, { _, a, kw ->
                if (kw.size > 0) throw PyRt.typeError("_signal.$name() takes no keyword arguments")
                impl(a)
            })
        }
        fun oneArg(name: String, impl: (Any?) -> Any?) = def(name) { a ->
            if (a.size != 1) throw PyRt.typeError("_signal.$name() takes exactly one argument (${a.size} given)")
            impl(a[0])
        }
        fun noArgs(name: String, impl: () -> Any?) = def(name) { a ->
            if (a.isNotEmpty()) throw PyRt.typeError("_signal.$name() takes no arguments (${a.size} given)")
            impl()
        }
        fun args(name: String, min: Int, max: Int, impl: (Array<Any?>) -> Any?) = def(name) { a ->
            if (a.size < min || a.size > max) {
                throw PyRt.typeError(when {
                    min == max -> "$name expected $min argument${if (min == 1) "" else "s"}, got ${a.size}"
                    a.size < min -> "$name expected at least $min arguments, got ${a.size}"
                    else -> "$name expected at most $max arguments, got ${a.size}"
                })
            }
            impl(a)
        }
        d[PyStr("__doc__")] = PyStr(PySocketData.SIGNAL_DOC)
        for ((n, v) in PySocketData.ints(PySocketData.SIGNAL_INTS)) d[PyStr(n)] = v
        d[PyStr("default_int_handler")] = DEFAULT_INT_HANDLER
        d[PyStr("ItimerError")] = itimerError

        args("signal", 2, 2) { a ->
            val n = PySocket.asInt(a[0])
            val h = a[1]
            range(n)
            if (!PyCalls.isCallable(h) && !isInt(h, 0) && !isInt(h, 1)) {
                throw PyRt.typeError("signal handler must be signal.SIG_IGN, signal.SIG_DFL, or a callable object")
            }
            settable(n)
            val old = st.handlers[n]
            st.handlers[n] = h
            old ?: PyNone
        }
        oneArg("getsignal") { v ->
            val n = PySocket.asInt(v)
            range(n)
            st.handlers[n] ?: PyNone
        }
        oneArg("raise_signal") { v ->
            val n = PySocket.asInt(v)
            if (n < 0 || n >= NSIG) throw PyPosix.error(PyErrno.EINVAL)
            if (n != 0) deliver(ctx, st, n)
            PyNone
        }
        oneArg("strsignal") { v ->
            val n = PySocket.asInt(v)
            range(n)
            PySocketData.STRSIGNAL[n - 1]?.let { PyStr(it) } ?: PyNone
        }
        noArgs("valid_signals") { PySet(false).also { s -> for (i in PySocketData.VALID_SIGNALS) s.addKey(i.toLong()) } }
        args("siginterrupt", 2, 2) { a ->
            val n = PySocket.asInt(a[0])
            PySocket.asInt(a[1])
            range(n)
            settable(n)
            PyNone
        }
        d[PyStr("set_wakeup_fd")] = PyBuiltin("set_wakeup_fd", { _, a -> setWakeupFd(ctx, st, a, null) }, { _, a, kw -> setWakeupFd(ctx, st, a, kw) })
        args("pthread_sigmask", 2, 2) { a ->
            val how = PySocket.asInt(a[0])
            val set = BooleanArray(NSIG)
            val it = PyRt.iter(a[1])
            while (true) {
                val x = it.next()
                if (x === PyIterator.STOP) break
                val n = PyRt.indexValue(x) ?: throw PyRt.typeError("'${PyTypes.name(x)}' object cannot be interpreted as an integer")
                val k = n as? Long ?: -1L
                if (k < 1 || k >= NSIG) throw PyException("ValueError", "signal number $n out of range [1; ${NSIG - 1}]")
                set[k.toInt()] = true
            }
            if (how < 0 || how > 2) throw PyPosix.error(PyErrno.EINVAL)
            val old = sigSet(st, st.mask)
            for (i in 1 until NSIG) {
                st.mask[i] = when (how) {
                    0 -> st.mask[i] || set[i]
                    1 -> st.mask[i] && !set[i]
                    else -> set[i]
                }
            }
            // (none blocks SIGKILL or SIGSTOP; glibc keeps its own 32 and 33 out)
            for (i in intArrayOf(SIGKILL, SIGSTOP, 32, 33)) st.mask[i] = false
            deliverPending(ctx, st)
            old
        }
        noArgs("sigpending") { sigSet(st, st.pending) }
        oneArg("alarm") { v ->
            if (PySocket.asInt(v) != 0) throw PyPosix.error(PyErrno.EPERM)
            0L
        }
        noArgs("pause") { throw PySocket.forEver("cannot wait for a signal nothing else can send") }
        oneArg("getitimer") { v ->
            val which = PySocket.asInt(v)
            if (which < 0 || which > 2) throw itimer(PyErrno.EINVAL)
            PyTuple(arrayOf(0.0, 0.0))
        }
        args("setitimer", 2, 3) { a ->
            val which = PySocket.asInt(a[0])
            val value = PyTime.fromSecondsObject(a[1], PyTime.ROUND_UP)
            val interval = if (a.size > 2) PyTime.fromSecondsObject(a[2], PyTime.ROUND_UP) else 0L
            if (which < 0 || which > 2 || value < 0 || interval < 0) throw itimer(PyErrno.EINVAL)
            // (a timer set to nothing: disarmed, as none is armed)
            if (value != 0L) throw itimer(PyErrno.EPERM)
            PyTuple(arrayOf(0.0, 0.0))
        }
    }

    /** set_wakeup_fd(fd, /, *, warn_on_full_buffer=True): the descriptor before (-1: none); one that blocks refused. */
    private fun setWakeupFd(ctx: PyContext, st: State, a: Array<Any?>, kw: PyDict?): Any? {
        val v = PyMath.clinic("set_wakeup_fd", a, kw?.takeIf { it.size > 0 }, listOf("fd", "warn_on_full_buffer"), 1, 1, 1)
        val fd = PySocket.asInt(v[0])
        val warn = v[1]?.let { PyRt.truthy(it) } ?: true
        if (fd != -1) {
            val f = PyPosix.state(ctx).fds[fd] ?: throw PyPosix.error(PyErrno.EBADF)
            if (PyPosix.blocking(f)) throw PyException("ValueError", "the fd $fd must be in non-blocking mode")
        }
        val old = st.wakeupFd
        st.wakeupFd = fd
        st.warnOnFullBuffer = warn
        return old.toLong()
    }

    /** `_posixsubprocess`: fork_exec refused (EPERM: no process is started), so subprocess.Popen fails with that. */
    @JvmStatic
    fun fillPosixSubprocess(d: PyDict) {
        d[PyStr("__doc__")] = PyStr(PySocketData.POSIXSUBPROCESS_DOC)
        d[PyStr("fork_exec")] = PyBuiltin("fork_exec", { _, a ->
            if (a.size != 22) throw PyRt.typeError("fork_exec expected 22 arguments, got ${a.size}")
            throw PyPosix.error(PyErrno.EPERM)
        }, { _, a, kw ->
            if (kw.size > 0) throw PyRt.typeError("_posixsubprocess.fork_exec() takes no keyword arguments")
            if (a.size != 22) throw PyRt.typeError("fork_exec expected 22 arguments, got ${a.size}")
            throw PyPosix.error(PyErrno.EPERM)
        })
    }
}
