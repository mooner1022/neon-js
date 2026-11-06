package dev.mooner.neonpython

/** A frame object (types.FrameType): the view of frame [f], running, suspended or done; one per frame. */
class PyFrameObj(@JvmField val f: PyFrame) {
    override fun toString(): String {
        val info = f.code.dialectData as PyCodeInfo
        return "<frame at 0x${Integer.toHexString(System.identityHashCode(this))}, file '${info.filename}', line ${PyFrames.line(f)}, code ${info.name}>"
    }
}

/**
 * A traceback object (types.TracebackType): a frame the exception passed, its line and instruction then, and the
 * entry of the frame the exception came from ([next], a PyTraceback or None).
 */
class PyTraceback(@JvmField var next: Any?, @JvmField val f: PyFrame, @JvmField val lasti: Int, line: Int = -2) {
    /** Its line, found from [lasti] when first asked for (an exception's entries are made for each frame it passes). */
    private var line = line
    val lineno: Int get() {
        if (line == -2) line = PyFrames.lineAt(f, lasti)
        return line
    }
    val frame: PyFrameObj get() = PyFrames.frameOf(f)
}

/**
 * Frames and tracebacks (docs/PYTHON_PLATFORM.md §12, L3). The frames running are a chain ([PyContext.top],
 * [PyFrame.back]) that [PyInterpreter]'s calls keep. An exception gets a traceback entry in each frame it passes,
 * once: where the interpreter or a handler of compiled code catches it there, or where it leaves the frame; a bare
 * `raise` adds none in its own frame (CPython's RAISE_VARARGS 0 and RERAISE).
 */
object PyFrames {
    @JvmField val FRAME = PyTypes.newBuiltin("frame").also { it.subclassable = false }
    @JvmField val TRACEBACK = PyTypes.newBuiltin("traceback").also { it.subclassable = false }

    /** [f]'s frame object, made once. */
    @JvmStatic fun frameOf(f: PyFrame): PyFrameObj = f.obj ?: PyFrameObj(f).also { f.obj = it }

    /** The line [f] is at: of its pc (compiled code stores it before what may raise or call). */
    @JvmStatic fun line(f: PyFrame): Int = lineAt(f, f.pc)

    @JvmStatic fun lineAt(f: PyFrame, pc: Int): Int {
        val info = f.code.dialectData as PyCodeInfo
        val l = info.lineAt(f.code, pc)
        return if (l < 0) info.firstLine.coerceAtLeast(1) else l
    }

    /** [e]'s traceback entry for frame [f], at its pc: before the entries of the frames it came from. */
    @JvmStatic
    fun addTraceback(e: PyException, f: PyFrame) {
        if (e.tbFrame === f) return
        e.tbFrame = f
        val exc = e.exc
        val head = if (exc != null) exc.traceback else e.tb ?: PyNone
        val tb = PyTraceback(head, f, f.pc)
        if (exc != null) exc.traceback = tb else e.tb = tb
    }

    /** sys._getframe(depth): the frame [depth] calls out from the caller's. */
    @JvmStatic
    fun getFrame(ctx: PyContext, depth: Any?): PyFrameObj {
        var n = when (depth) {
            null -> 0L
            is Long -> depth
            is PyBool -> if (depth.value) 1L else 0L
            else -> PyRt.indexValue(depth) as? Long ?: throw PyRt.typeError("'${PyTypes.name(depth)}' object cannot be interpreted as an integer")
        }
        var f = ctx.top
        while (n > 0 && f != null) {
            f = f.back
            n--
        }
        if (f == null) throw PyException("ValueError", "call stack is not deep enough")
        return frameOf(f)
    }

    /** f_locals: a namespace's dict; a function's locals with values (a frame the tier runs: the snapshot exec() and the like took). */
    private fun locals(f: PyFrame): PyDict {
        f.locals?.let { return it }
        val info = f.code.dialectData as PyCodeInfo
        if (info.module) return f.globals
        if (f.isCompiled) return f.evalLocals ?: PyDict()
        val d = PyDict()
        for ((r, n) in info.localNames.withIndex()) {
            if (n.startsWith(".") || r >= f.slots.size) continue
            val v = f.slots[r] ?: continue
            d[PyStr(n)] = if (v is PyCell) (v.value ?: continue) else v
        }
        return d
    }

    @JvmStatic
    fun frameAttr(o: PyFrameObj, name: String): Any? {
        val f = o.f
        return when (name) {
            "f_code" -> PyEval.codeOf(f.code)
            "f_lineno" -> line(f).toLong()
            // (an instruction's offset is twice its pc: PyDis)
            "f_lasti" -> f.pc * 2L
            "f_globals" -> f.globals
            "f_builtins" -> f.ctx.builtins
            "f_locals" -> locals(f)
            "f_back" -> f.back?.let { frameOf(it) } ?: PyNone
            "f_trace" -> PyNone
            "f_trace_lines" -> PyBool.TRUE
            "f_trace_opcodes" -> PyBool.FALSE
            "clear" -> PyBuiltin("clear") { _, _ -> PyNone }
            "__class__" -> FRAME
            else -> PyClasses.genericGetAttr(o, name)
        }
    }

    @JvmStatic
    fun tracebackAttr(t: PyTraceback, name: String): Any? = when (name) {
        "tb_frame" -> t.frame
        "tb_lineno" -> t.lineno.toLong()
        "tb_lasti" -> t.lasti * 2L
        "tb_next" -> t.next ?: PyNone
        "__class__" -> TRACEBACK
        else -> PyClasses.genericGetAttr(t, name)
    }

    /** tb.tb_next = v (a traceback or None, never making a loop); a frame's f_trace and the like taken and kept by none. */
    @JvmStatic
    fun setAttr(obj: Any?, name: String, v: Any?) {
        if (obj is PyTraceback && name == "tb_next") {
            if (v !== PyNone && v !is PyTraceback) throw PyRt.typeError("expected traceback object, got '${PyTypes.name(v)}'")
            var c: Any? = v
            while (c is PyTraceback) {
                if (c === obj) throw PyException("ValueError", "traceback loop detected")
                c = c.next
            }
            obj.next = v
            return
        }
        if (obj is PyFrameObj && name.startsWith("f_trace")) return
        throw PyException.attributeError(obj, name, "'${PyTypes.name(obj)}' object attribute '$name' is read-only")
    }

    /**
     * [e] as CPython prints an exception it does not catch (traceback.format_exception): its cause or context first
     * (with "The above exception was the direct cause of the following exception:" or "During handling of the above
     * exception, another exception occurred:"), each's entries outermost first, a line's source where [sources] has
     * the file's, then `Type: message` and its notes.
     */
    @JvmStatic
    fun format(e: PyBaseException, sources: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder()
        chain(e, sb, java.util.Collections.newSetFromMap(java.util.IdentityHashMap()), sources)
        return sb.toString()
    }

    private fun chain(e: PyBaseException, sb: StringBuilder, seen: MutableSet<PyBaseException>, sources: Map<String, String>) {
        seen.add(e)
        val cause = e.cause as? PyBaseException
        val context = e.context as? PyBaseException
        if (cause != null && cause !in seen) {
            chain(cause, sb, seen, sources)
            sb.append("\nThe above exception was the direct cause of the following exception:\n\n")
        } else if (context != null && !e.suppressContext && context !in seen) {
            chain(context, sb, seen, sources)
            sb.append("\nDuring handling of the above exception, another exception occurred:\n\n")
        }
        var t = e.traceback
        if (t is PyTraceback) sb.append("Traceback (most recent call last):\n")
        while (t is PyTraceback) {
            val info = t.f.code.dialectData as PyCodeInfo
            sb.append("  File \"").append(info.filename).append("\", line ").append(t.lineno).append(", in ").append(info.name).append('\n')
            sources[info.filename]?.lineSequence()?.drop(t.lineno - 1)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                sb.append("    ").append(it).append('\n')
            }
            t = t.next
        }
        val name = PyTypes.fullName(e.type)
        val msg = PyExceptions.strOf(e)
        sb.append(name)
        if (msg.isNotEmpty()) sb.append(": ").append(msg)
        sb.append('\n')
        (e.dict?.get(PyStr("__notes__")) as? PyList)?.items?.forEach { n -> sb.append(PyRt.str(n)).append('\n') }
    }

    /** types.TracebackType(tb_next, tb_frame, tb_lasti, tb_lineno). */
    @JvmStatic
    fun define() {
        TRACEBACK.newImpl = { _, _, a, kw ->
            fun arg(i: Int, n: String) = kw?.get(PyStr(n)) ?: a.getOrNull(i) ?: throw PyRt.typeError("TracebackType() missing required argument '$n' (pos ${i + 1})")
            val next = arg(0, "tb_next")
            val frame = arg(1, "tb_frame")
            if (next !== PyNone && next !is PyTraceback) throw PyRt.typeError("expected traceback object or None, got '${PyTypes.name(next)}'")
            if (frame !is PyFrameObj) throw PyRt.typeError("TracebackType() argument 'tb_frame' must be frame, not ${PyTypes.name(frame)}")
            val lasti = PyRt.indexValue(arg(2, "tb_lasti")) as? Long ?: throw PyRt.typeError("an integer is required")
            val lineno = PyRt.indexValue(arg(3, "tb_lineno")) as? Long ?: throw PyRt.typeError("an integer is required")
            PyTraceback(next, frame.f, (lasti / 2).toInt(), lineno.toInt())
        }
    }
}
