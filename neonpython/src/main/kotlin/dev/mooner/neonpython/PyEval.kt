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

    /** compile()'s PyCF_ONLY_AST, and the future feature annotations' flag. */
    private const val ONLY_AST = 0x400
    private const val FUTURE_ANNOTATIONS = 0x1000000

    /** [source] (a str or UTF-8 bytes) compiled in [mode]; a compile error raised as SyntaxError. */
    @JvmStatic
    fun compile(source: Any?, filename: String, mode: String, flags: Int = 0): PyCode {
        if (mode != "exec" && mode != "eval" && mode != "single") throw PyException("ValueError", "compile() mode must be 'exec', 'eval' or 'single'")
        if (flags and ONLY_AST != 0) throw PyException("NotImplementedError", "compile() to an AST is not supported")
        val src = text(source, "compile() arg 1 must be a string, bytes or AST object")
        try {
            val cb = PyCompiler.compileSource(src, if (mode == "eval") "eval" else "exec", flags and FUTURE_ANNOTATIONS != 0)
            return PyCode(cb, mode, filename)
        } catch (e: PySyntaxError) {
            throw syntaxError(e, filename, src, mode)
        }
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
        val code = src as? PyCode ?: compile(src, "<string>", "exec")
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
        val code = src as? PyCode ?: compile(PyStr(text!!), "<string>", "eval")
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
            fun classNames(c: PyType): List<Any?> = c.mro.flatMap { m -> m.dict.map.keys.map { it.v } }
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
        d[PyStr("exec")] = builtin("exec", ::exec)
        d[PyStr("eval")] = builtin("eval", ::eval)
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

    /** f.__code__: its function's code object (the same each time). */
    @JvmStatic
    fun functionCode(f: PyFunction): Any? = f.info.codeObject ?: PyCode(f.code, "function", "<string>").also { f.info.codeObject = it }

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
        else -> throw PyException("AttributeError", "'code' object has no attribute '$name'")
    }
}
