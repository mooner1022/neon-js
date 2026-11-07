package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeUnit
import dev.mooner.neonjs.vm.VmFrame

/** A code object, what compile() gives: a module's (or an expression's, [mode] "eval") code, run by exec() or eval(). */
class PyCode(@JvmField val code: CodeUnit, @JvmField val mode: String, @JvmField val filename: String) {
    override fun toString() = "<code object ${code.name} at ${PyGenerators.addr(this)}, file \"$filename\", line 1>"
}

/**
 * compile(), exec() and eval(): source compiled at run time, its names read and written in the namespaces given
 * (LOAD_NAME's: the code a module's in name mode, so that separate locals are what a class body's are); a
 * compile error a SyntaxError object as CPython's, its location the parser's or the analysis's.
 */
object PyEval {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val CODE = PyTypes.newBuiltin("code", subclassable = false)

    init {
        PyTypes.FUNCTION.newImpl = { _, _, a, kw -> newFunction(a, kw?.takeIf { it.size > 0 }) }
        // (types.MethodType(function, instance): the function bound to it, as method_new makes one)
        PyTypes.METHOD.newImpl = { _, _, a, kw ->
            if (kw != null && kw.size > 0) throw PyRt.typeError("method() takes no keyword arguments")
            if (a.size != 2) throw PyRt.typeError("method expected 2 arguments, got ${a.size}")
            if (!PyCalls.isCallable(a[0])) throw PyRt.typeError("first argument must be callable")
            if (a[1] === PyNone) throw PyRt.typeError("instance must not be None")
            PyMethod(a[0], a[1])
        }
        // (types.CellType(contents): a cell, empty without them)
        PyTypes.CELL.newImpl = { _, _, a, kw ->
            if (kw != null && kw.size > 0) throw PyRt.typeError("cell() takes no keyword arguments")
            if (a.size > 1) throw PyRt.typeError("cell expected at most 1 argument, got ${a.size}")
            PyCell(a.getOrNull(0))
        }
    }

    /** compile()'s PyCF_ONLY_AST, and the future feature annotations' flag. */
    private const val ONLY_AST = 0x400
    private const val ALLOW_INCOMPLETE_INPUT = 0x4000
    private val NO_KW = arrayOfNulls<Any?>(0)

    /** PRINT_EXPR (mode 'single''s expression statements): the value to sys.displayhook as it is then. */
    @JvmField val PRINT_EXPR = PyBuiltin("print_expr") { c, a ->
        val hook = c.sysModule.dict[PyStr("displayhook")] ?: throw PyException("RuntimeError", "lost sys.displayhook")
        PyCalls.callWith(c, hook, arrayOf(a[0]), NO_KW, NO_KW)
    }
    private const val FUTURE_ANNOTATIONS = 0x1000000

    /** [source] (a str or UTF-8 bytes) compiled in [mode]; a compile error raised as SyntaxError. */
    @JvmStatic
    fun compile(source: Any?, filename: String, mode: String, flags: Int = 0): Any? {
        if (mode != "exec" && mode != "eval" && mode != "single") throw PyException("ValueError", "compile() mode must be 'exec', 'eval' or 'single'")
        val src = text(source, "compile() arg 1 must be a string, bytes or AST object")
        if (flags and ONLY_AST != 0) return PyAstObjects.parse(PyContext.current(), src, filename, mode)
        val incomplete = flags and ALLOW_INCOMPLETE_INPUT != 0
        val cb = try {
            PyCompiler.withFilename(PyCompiler.compileSource(src, mode, flags and FUTURE_ANNOTATIONS != 0, incomplete), filename)
        } catch (e: PySyntaxError) {
            if (incomplete && e.stage == 2 && (e.type == "_IncompleteInputError" || endedBy(e, src, mode))) throw incompleteInput(filename, src, mode)
            throw syntaxError(e, filename, src, mode)
        }
        return PyCode(cb, mode, filename).also { (cb.dialectData as PyCodeInfo).codeObject = it }
    }

    /**
     * _IncompleteInputError, where CPython's last token ends: the source's last line with one, after its newline
     * where it has one.
     */
    private fun incompleteInput(filename: String, src: String, mode: String): PyException {
        val lines = src.split('\n')
        val last = lines.indexOfLast { l -> l.isNotBlank() }
        val end = if (last < 0) -1 else lines[last].trimEnd('\r').length - (if (last == lines.size - 1) 1 else 0)
        return syntaxError(PySyntaxError("incomplete input", last + 1, end, "_IncompleteInputError").also { it.stage = 2 }, filename, src, mode)
    }

    /**
     * Whether the source's end made [e] (CPython's _is_end_of_source, its tokenizer having reached the end): mode
     * 'exec' reads a source with a newline at its end, so that a string a line ends and the newline a source
     * without one ends with are not the end.
     */
    private fun endedBy(e: PySyntaxError, src: String, mode: String): Boolean = when {
        // (a backslash ending a source without a newline: 'exec''s line continued to the end, another's character after it)
        e.eof -> if (mode == "exec") !e.msg.startsWith("unterminated string literal") else !src.endsWith("\\")
        e.farEnd == 2 -> true
        else -> e.farEnd == 1 && mode != "exec"
    }

    private fun text(source: Any?, what: String): String = when (source) {
        is PyStr -> source.s
        is PyBytes -> String(source.b, Charsets.UTF_8)
        else -> throw PyRt.typeError(what)
    }

    /**
     * [e] as CPython's SyntaxError (or IndentationError, TabError): a parser's with its line's text and the
     * location in its args; the analysis's (the symbol table's, the compiler's) its message alone, text None.
     */
    @JvmStatic
    fun syntaxError(e: PySyntaxError, filename: String, src: String, mode: String): PyException {
        val o = PyBaseException(PyExceptions.type(e.type))
        val fn = PyStr(filename)
        val lineno = e.line.toLong()
        val offset = (e.col + 1).toLong()
        var text: Any? = PyNone
        if (e.stage == 2) {
            val line = src.split('\n').getOrNull(e.line - 1)?.removeSuffix("\r")
            if (line != null) text = PyStr(if (mode == "eval") line else line + "\n")
        }
        // (the symbol table's error its message alone, as CPython's PyErr_RangedSyntaxLocationObject makes it)
        o.args = if (e.stage == 0) PyTuple(arrayOf(PyStr(e.msg)))
            else PyTuple(arrayOf(PyStr(e.msg), PyTuple(arrayOf(fn, lineno, offset, text, lineno, PyNone))))
        o.setField("msg", PyStr(e.msg))
        o.setField("filename", fn)
        o.setField("lineno", lineno)
        o.setField("offset", offset)
        o.setField("text", text)
        o.setField("end_lineno", lineno)
        o.setField("end_offset", PyNone)
        return PyException(o).also { o.thrown = it }
    }

    /** The globals and locals exec() and eval() run in: given, else their caller's (a function's, a snapshot of its locals). */
    private fun namespaces(f: VmFrame?, a: Array<Any?>, what: String): Pair<PyDict, PyDict> {
        val g = a.getOrNull(1)
        val l = a.getOrNull(2)
        if (g != null && g !== PyNone && g !is PyDict) {
            throw PyRt.typeError(if (what == "eval") "globals must be a real dict; try eval(expr, {}, mapping)" else "exec() globals must be a dict, not ${PyTypes.name(g)}")
        }
        val frame = f as PyFrame?
        val globals = (g as? PyDict) ?: frame?.globals ?: throw PyRt.typeError("$what() needs its caller's frame")
        val locals = when {
            l != null && l !== PyNone -> l as? PyDict ?: throw PyRt.typeError("locals must be a mapping")
            g is PyDict -> g
            else -> frame?.locals ?: frame?.evalLocals ?: globals
        }
        // (the builtins the code sees, as CPython puts them in globals that have none)
        if (!globals.containsKey(PyStr("__builtins__"))) globals[PyStr("__builtins__")] = PyContext.current().builtins
        return globals to locals
    }

    private fun run(code: PyCode, globals: PyDict, locals: PyDict, what: String): Any? {
        // (a function's code that needs its closure)
        val frees = (code.code.dialectData as PyCodeInfo).freeNames.size
        if (frees > 0) {
            throw PyRt.typeError(if (what == "eval") "code object passed to eval() may not contain free variables"
                else "code object requires a closure of exactly length $frees")
        }
        return PyInterpreter.callWithLocals(PyContext.current(), PyFunction(code.code, globals, code.code.name), locals)
    }

    /** exec(source, globals=None, locals=None): None. */
    @JvmStatic
    fun exec(f: VmFrame?, a: Array<Any?>): Any? {
        if (a.isEmpty() || a.size > 3) throw PyRt.typeError(if (a.isEmpty()) "exec expected at least 1 argument, got 0" else "exec expected at most 3 arguments, got ${a.size}")
        val src = a[0]
        if (src !is PyCode && src !is PyStr && src !is PyBytes) throw PyRt.typeError("exec() arg 1 must be a string, bytes or code object")
        val (g, l) = namespaces(f, a, "exec")
        val code = src as? PyCode ?: compile(src, "<string>", "exec") as PyCode
        run(code, g, l, "exec")
        return PyNone
    }

    /** eval(source, globals=None, locals=None): the expression's value (a str's leading spaces and tabs dropped). */
    @JvmStatic
    fun eval(f: VmFrame?, a: Array<Any?>): Any? {
        if (a.isEmpty() || a.size > 3) throw PyRt.typeError(if (a.isEmpty()) "eval expected at least 1 argument, got 0" else "eval expected at most 3 arguments, got ${a.size}")
        val src = a[0]
        val text = if (src is PyCode) null else text(src, "eval() arg 1 must be a string, bytes or code object").trimStart(' ', '\t')
        val (g, l) = namespaces(f, a, "eval")
        val code = src as? PyCode ?: compile(PyStr(text!!), "<string>", "eval") as PyCode
        val r = run(code, g, l, "eval")
        return if (code.mode == "eval") r else PyNone
    }

    /** SET_EVAL_LOCALS: a function's locals, the snapshot exec() and eval() called from it take as theirs. */
    @JvmStatic
    fun setEvalLocals(f: VmFrame, d: Any?) {
        (f as PyFrame).evalLocals = d as PyDict
    }

    private fun builtin(name: String, impl: (VmFrame?, Array<Any?>) -> Any?): PyBuiltin =
        PyBuiltin(name) { _, a -> impl(null, a) }.also { b -> b.frameImpl = { f, a -> impl(f, a) } }

    /**
     * dir(): its caller's names (a function's its locals' snapshot); dir(o): o's __dir__'s, a module's names, a
     * class's and its bases' attributes, an instance's and its class's; sorted.
     */
    @JvmStatic
    fun dir(f: VmFrame?, a: Array<Any?>): Any? {
        if (a.size > 1) throw PyRt.typeError("dir expected at most 1 argument, got ${a.size}")
        val names: List<Any?> = if (a.isEmpty()) {
            val frame = f as PyFrame? ?: throw PyRt.typeError("dir() needs its caller's frame")
            (frame.locals ?: frame.evalLocals ?: frame.globals).map.keys.map { it.v }
        } else {
            val o = a[0]
            val t = PyTypes.typeOf(o)
            val user = t.lookup("__dir__")?.takeIf { it !is PyBuiltin }
            fun classNames(c: PyType): List<Any?> = c.mro.flatMap { m -> m.dict.map.keys.map { it.v } + PyAttrDescrs.names(m).map { PyStr(it) } }
            when {
                user != null -> {
                    val r = PyCalls.callWith(PyContext.current(), PyClasses.descrGet(null, user, o, t), NO_ARGS, NO_ARGS, NO_ARGS)
                    val out = ArrayList<Any?>()
                    val it = PyRt.iter(r)
                    while (true) { val x = it.next(); if (x === PyIterator.STOP) break; out.add(x) }
                    out
                }
                o is PyModule -> o.dict.map.keys.map { it.v }
                o is PyType -> classNames(o).distinct()
                else -> ((PyClasses.instanceDict(o)?.map?.keys?.map { it.v } ?: emptyList()) + classNames(t)).distinct()
            }
        }
        return PyList(ArrayList(names.sortedWith(compareBy { (it as? PyStr)?.s ?: PyRt.str(it) })))
    }

    @JvmStatic
    fun define(d: PyDict) {
        d[PyStr("dir")] = builtin("dir", ::dir)
        d[PyStr("compile")] = PyBuiltin("compile", { _, a -> compileArgs(a, null) }, { _, a, kw -> compileArgs(a, kw) })
        d[PyStr("exec")] = builtin("exec", ::exec).also { b -> b.kwImpl = { ctx, a, kw -> execEvalKw(ctx, a, kw, "exec") } }
        d[PyStr("eval")] = builtin("eval", ::eval).also { b -> b.kwImpl = { ctx, a, kw -> execEvalKw(ctx, a, kw, "eval") } }
    }

    /**
     * exec(source, /, globals=None, locals=None, *, closure=None) and eval(source, /, globals=None, locals=None) called
     * with keywords: those in their places, in the frame calling them (the innermost running).
     */
    private fun execEvalKw(ctx: PyContext, a: Array<Any?>, kw: PyDict, what: String): Any? {
        if (a.size > 3) throw PyRt.typeError("$what expected at most 3 arguments, got ${a.size}")
        val v = arrayOfNulls<Any?>(3)
        a.copyInto(v)
        var closure: Any? = null
        for ((k, x) in kw.map) {
            val n = (k.v as PyStr).s
            val i = when (n) {
                "globals" -> 1
                "locals" -> 2
                "closure" -> if (what == "exec") -1 else throw PyRt.typeError("$what() got an unexpected keyword argument '$n'")
                else -> throw PyRt.typeError("$what() got an unexpected keyword argument '$n'")
            }
            if (i < 0) { closure = x; continue }
            if (i < a.size) throw PyRt.typeError("argument for $what() given by name ('$n') and position (${i + 1})")
            v[i] = x
        }
        if (v[0] == null) throw PyRt.typeError("$what expected at least 1 argument, got 0")
        // (the places before one given by name: None, as if they were)
        val n = if (v[2] != null) 3 else if (v[1] != null) 2 else 1
        val args = Array<Any?>(n) { v[it] ?: PyNone }
        if (closure != null && closure !== PyNone) return execClosure(ctx.top, args, closure)
        return if (what == "exec") exec(ctx.top, args) else eval(ctx.top, args)
    }

    /** exec(code, globals, locals, closure=cells): a function's code run with the cells of its free variables. */
    private fun execClosure(f: VmFrame?, a: Array<Any?>, closure: Any?): Any? {
        val code = a[0] as? PyCode ?: throw PyRt.typeError("closure can only be used when source is a code object")
        val frees = (code.code.dialectData as PyCodeInfo).freeNames.size
        val cells = (closure as? PyTuple)?.items
        if (cells == null || cells.size != frees) throw PyRt.typeError("code object requires a closure of exactly length $frees")
        if (cells.any { it !is PyCell }) throw PyRt.typeError("closure can only contain cells")
        val (g, l) = namespaces(f, a, "exec")
        val fn = PyFunction(code.code, g, code.code.name)
        fn.closure = cells.copyOf()
        PyInterpreter.callWithLocals(PyContext.current(), fn, l)
        return PyNone
    }

    /** compile(source, filename, mode, flags=0, dont_inherit=False, optimize=-1). */
    private fun compileArgs(a: Array<Any?>, kw: PyDict?): Any? {
        val names = listOf("source", "filename", "mode", "flags", "dont_inherit", "optimize")
        val v = arrayOfNulls<Any?>(names.size)
        if (a.size > names.size) throw PyRt.typeError("compile() takes at most 6 arguments (${a.size} given)")
        a.copyInto(v)
        if (kw != null) for ((k, x) in kw.map) {
            val i = names.indexOf((k.v as PyStr).s)
            if (i < 0 && (k.v as PyStr).s != "_feature_version") throw PyRt.typeError("compile() got an unexpected keyword argument '${k.v}'")
            if (i >= 0) v[i] = x
        }
        for (i in 0..2) if (v[i] == null) throw PyRt.typeError("compile() missing required argument '${names[i]}' (pos ${i + 1})")
        val filename = when (val fnv = v[1]) {
            is PyStr -> fnv.s
            is PyBytes -> String(fnv.b, Charsets.UTF_8)
            else -> throw PyRt.typeError("compile() argument 'filename' must be str, not ${PyTypes.name(fnv)}")
        }
        val mode = (v[2] as? PyStr)?.s ?: throw PyRt.typeError("compile() argument 'mode' must be str, not ${PyTypes.name(v[2])}")
        val flags = (v[3] as? Long)?.toInt() ?: 0
        return compile(v[0], filename, mode, flags)
    }

    /**
     * types.FunctionType(code, globals, name=None, argdefs=None, closure=None, kwdefaults=None): a function of
     * [code] in other globals (annotationlib's way to run an __annotate__ with fake globals).
     */
    @JvmStatic
    fun newFunction(a: Array<Any?>, kw: PyDict?): PyFunction {
        val names = listOf("code", "globals", "name", "argdefs", "closure", "kwdefaults")
        val v = arrayOfNulls<Any?>(names.size)
        if (a.size > names.size) throw PyRt.typeError("function() takes at most 6 arguments (${a.size} given)")
        a.copyInto(v)
        kw?.map?.forEach { (k, x) ->
            val i = names.indexOf((k.v as PyStr).s)
            if (i < 0) throw PyRt.typeError("function() got an unexpected keyword argument '${k.v}'")
            v[i] = x
        }
        val code = v[0] as? PyCode ?: throw PyRt.typeError("function() argument 'code' must be code, not ${PyTypes.name(v[0])}")
        val globals = v[1] as? PyDict ?: throw PyRt.typeError("function() argument 'globals' must be dict, not ${PyTypes.name(v[1])}")
        val name = v[2]?.takeIf { it !== PyNone }?.let { (it as? PyStr ?: throw PyRt.typeError("arg 3 (name) must be None or string")).s }
            ?: (code.code.dialectData as PyCodeInfo).name
        val f = PyFunction(code.code, globals, name)
        v[3]?.takeIf { it !== PyNone }?.let { f.defaults = it as? PyTuple ?: throw PyRt.typeError("arg 4 (defaults) must be None or tuple") }
        val free = (code.code.dialectData as PyCodeInfo).freeNames.size
        val closure = v[4]?.takeIf { it !== PyNone }?.let { it as? PyTuple ?: throw PyRt.typeError("arg 5 (closure) must be tuple") }
        if (closure == null && free > 0) throw PyRt.typeError("arg 5 (closure) must be tuple")
        if ((closure?.items?.size ?: 0) != free) {
            throw PyException("ValueError", "${(code.code.dialectData as PyCodeInfo).name} requires closure of length $free, not ${closure?.items?.size ?: 0}")
        }
        closure?.items?.forEach { if (it !is PyCell) throw PyRt.typeError("arg 5 (closure) expected cell, found ${PyTypes.name(it)}") }
        if (closure != null) f.closure = closure.items.copyOf()
        v[5]?.takeIf { it !== PyNone }?.let { f.kwdefaults = it as? PyDict ?: throw PyRt.typeError("arg 6 (kwdefaults) must be None or dict") }
        if (!globals.containsKey(PyStr("__builtins__"))) globals[PyStr("__builtins__")] = PyContext.current().builtins
        return f
    }

    /** f.__code__: its function's code object (the same each time), or the one assigned to it. */
    @JvmStatic
    fun functionCode(f: PyFunction): Any? = f.codeObject ?: codeOf(f.code)

    /**
     * f.__code__ = v: a code object with the function's bytecode (code.replace()'s of its own, types.coroutine's
     * with CO_ITERABLE_COROUTINE); NeonPy's functions keep theirs (docs/PYTHON_PLATFORM.md §12.2).
     */
    @JvmStatic
    fun setFunctionCode(f: PyFunction, v: Any?) {
        val c = v as? PyCode ?: throw PyRt.typeError("__code__ must be set to a code object")
        val info = c.code.dialectData as PyCodeInfo
        if (info.freeNames.size != f.info.freeNames.size) {
            throw PyException("ValueError", "${f.name}() requires a code object with ${f.info.freeNames.size} free vars, not ${info.freeNames.size}")
        }
        if (c.code.code !== f.code.code) throw PyException("ValueError", "__code__: NeonPy's functions keep their bytecode, a code object replace() made of theirs given")
        f.codeObject = if (c.code === f.code) null else c
        f.iterableCoroutine = info.coFlags and CO_ITERABLE_COROUTINE != 0
    }

    const val CO_ITERABLE_COROUTINE = 0x100

    /** The code object of [cb], made once (a function's __code__, a frame's f_code). */
    @JvmStatic
    fun codeOf(cb: CodeUnit): PyCode {
        val info = cb.dialectData as PyCodeInfo
        return info.codeObject as PyCode? ?: PyCode(cb, if (info.module) "exec" else "function", info.filename).also { info.codeObject = it }
    }

    @JvmStatic
    fun codeAttr(c: PyCode, name: String): Any? = when (name) {
        "co_name" -> PyStr(c.code.name)
        "co_qualname" -> PyStr((c.code.dialectData as PyCodeInfo).qualname)
        "co_filename" -> PyStr(c.filename)
        "co_firstlineno" -> (c.code.dialectData as PyCodeInfo).firstLine.toLong().takeIf { it > 0 } ?: 1L
        "co_varnames" -> PyTuple((c.code.dialectData as PyCodeInfo).varnames.map { PyStr(it) }.toTypedArray())
        "co_flags" -> (c.code.dialectData as PyCodeInfo).coFlags.toLong()
        "co_argcount" -> (c.code.dialectData as PyCodeInfo).argcount.toLong()
        "co_posonlyargcount" -> (c.code.dialectData as PyCodeInfo).posonlyCount.toLong()
        "co_kwonlyargcount" -> (c.code.dialectData as PyCodeInfo).kwonlyCount.toLong()
        "co_freevars" -> PyTuple((c.code.dialectData as PyCodeInfo).freeNames.map { PyStr(it) }.toTypedArray())
        "__class__" -> CODE
        // (the rest: NeonPy's bytecode as Python sees it)
        else -> PyDis.codeAttr(c, name)
    }
}
