package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeUnit

/**
 * What a Python code block keeps besides its bytecode ([CodeUnit.dialectData]): its function's name, the names of its
 * locals by register, and the source line of each statement's pc (pairs in [CodeUnit.lineTable]).
 */
class PyCodeInfo(@JvmField val name: String, @JvmField val localNames: Array<String>, @JvmField val module: Boolean) {
    /** Its function's qualified name (`f.<locals>.g`), which call errors give. */
    @JvmField var qualname: String = name
    /** Positional parameters (positional-only ones among them, first), keyword-only ones, *args, **kwargs. */
    @JvmField var argcount = 0
    @JvmField var posonlyCount = 0
    @JvmField var kwonlyCount = 0
    @JvmField var varargs = false
    @JvmField var varkw = false
    /** The parameters' names, in their registers' order. */
    @JvmField var paramNames: List<String> = emptyList()
    /** Its docstring. */
    @JvmField var doc: String? = null
    /** What calling it makes: its frame run (0), a generator, a coroutine, an async generator; and its def's line. */
    @JvmField var kind = 0
    @JvmField var firstLine = -1
    /** Its code object's co_varnames (parameters, then locals that are no cells) and co_flags. */
    @JvmField var varnames: List<String> = emptyList()
    @JvmField var coFlags = 0
    /** The names its closure gives it (co_freevars), and its locals its inner functions' closures take (co_cellvars). */
    @JvmField var freeNames: List<String> = emptyList()
    @JvmField var cellNames: List<String> = emptyList()
    /** Its function's `__code__`, made once. */
    @JvmField var codeObject: Any? = null
    /** The file it was compiled from (co_filename): an imported module's path, compile()'s filename, `<string>`. */
    @JvmField var filename: String = "<string>"

    /**
     * The operand types the interpreter saw at each arithmetic and comparison instruction, by pc ([PyProfile]): made
     * when the optimizing tier is on (VmConfig.tier2), read by its speculating graphs ([PyBuilder]). Written without
     * synchronization: a hint, the code checks what it speculates on.
     */
    @Volatile @JvmField var profile: ByteArray? = null

    fun profile(size: Int): ByteArray = profile ?: ByteArray(size).also { profile = it }

    /** A copy named [name] ([qualname]), of [filename] from [firstLine] on: code.replace()'s. */
    fun copyWith(name: String, qualname: String, filename: String, firstLine: Int): PyCodeInfo = PyCodeInfo(name, localNames, module).also { c ->
        c.qualname = qualname
        c.argcount = argcount
        c.posonlyCount = posonlyCount
        c.kwonlyCount = kwonlyCount
        c.varargs = varargs
        c.varkw = varkw
        c.paramNames = paramNames
        c.doc = doc
        c.kind = kind
        c.firstLine = firstLine
        c.varnames = varnames
        c.coFlags = coFlags
        c.freeNames = freeNames
        c.cellNames = cellNames
        c.filename = filename
    }

    /** The line of the statement at or before [pc] of [cb]. */
    fun lineAt(cb: CodeUnit, pc: Int): Int {
        val lt = cb.lineTable
        var line = -1
        var k = 0
        while (k < lt.size && lt[k] <= pc) {
            line = lt[k + 1]
            k += 2
        }
        return line
    }

    companion object {
        const val GENERATOR = 1
        const val COROUTINE = 2
        const val ASYNC_GENERATOR = 3
    }
}

/**
 * Compiles a module's syntax trees ([PyParser]) to code blocks of [PyOp]'s bytecode, of the [PyDialect], with the
 * scopes [PySymtable] found: a module's code, whose names are its globals; each function's, whose locals are
 * registers (its parameters first), whose cells (locals its nested functions read) and free variables (an enclosing
 * function's cells) are registers holding a cell, and whose other names are globals or builtins. Registers after the
 * names hold temporaries (an iterator, a comparison's middle operand); the operand stack is empty at every statement,
 * and so at every loop header.
 */
class PyCompiler private constructor(private val st: PySymtable, private val scope: Scope, private val name: String) {
    private val module = scope.type == Scope.Type.MODULE
    private val isClass = scope.type == Scope.Type.CLASS
    /** A module's or a class body's: names in a dict (the globals, the class's namespace), not registers. */
    private val namespace = module || isClass
    private val code = IntArrayList()
    private val constants = ArrayList<Any?>()
    private val constIndex = HashMap<Any, Int>()
    /** The register of each local, cell and free variable. */
    private val locals = LinkedHashMap<String, Int>()
    private var regs = 0
    /** The registers the code needs: the names', and the most temporaries alive at once ([temp]). */
    private var maxRegs = 0
    private val lines = IntArrayList()
    private var line = 0

    /** The exception handlers' table (CodeUnit.handlers): quadruples (start, end, handler, depth 0), inner ones first. */
    private val handlerTable = IntArrayList()

    /**
     * A range of code whose exceptions go to one handler: segments, since what leaving it runs (a return through a
     * finally, the finally body copied there) is not its own; the handler known after the code ([handle]).
     */
    private inner class Range {
        private val segs = IntArrayList()
        private var open = -1

        fun begin() {
            open = code.size
        }

        fun end() {
            if (open >= 0 && code.size > open) {
                segs.add(open)
                segs.add(code.size)
            }
            open = -1
        }

        fun handle(handler: Int) {
            for (k in 0 until segs.size step 2) {
                handlerTable.add(segs[k])
                handlerTable.add(segs[k + 1])
                handlerTable.add(handler)
                handlerTable.add(0)
            }
        }
    }

    /**
     * The blocks around the statement compiled (CPython's fblocks): what `return`, `break` and `continue` leave, and
     * what leaving each runs ([unwind]).
     */
    private abstract class FBlock(val range: Range?) {
        /** Whether leaving it runs code. */
        open val leaves: Boolean get() = true
    }
    /** A loop: where `continue` goes, and the `break`s (patched once its end is known). */
    private class Loop(val top: Int) : FBlock(null) {
        val breaks = ArrayList<Int>()
        override val leaves get() = false
    }
    /** A try's body, whose except clauses follow. */
    private class TryBody(range: Range) : FBlock(range) {
        override val leaves get() = false
    }
    /** A try's body, whose finally body runs leaving it. */
    private class Finally(range: Range, val body: List<Stmt>) : FBlock(range)
    /** A handler's body (an except clause's, a finally's for an exception): leaving it, the exception handled before. */
    private class Handler(range: Range, val prev: Int) : FBlock(range)
    /** An `except ... as name:` clause's body: leaving it, the name deleted. */
    private class HandlerName(range: Range, val name: String) : FBlock(range)
    /** A with's body: leaving it, its __exit__ (bound, in register [exit]) called with three Nones. */
    private class WithBody(range: Range, val exit: Int, val async: Boolean) : FBlock(range)
    /** An except* clause's body, which return, break and continue may not leave. */
    private class StarBody(range: Range) : FBlock(range) {
        override val leaves get() = false
    }

    private val fblocks = ArrayList<FBlock>()

    init {
        if (isClass) {
            for (n in scope.frees) locals[n] = regs++
            if (scope.classCell) locals["__class__"] = regs++
            if (scope.classDict) locals["__classdict__"] = regs++
            if (scope.conditionCell) locals["__conditional_annotations__"] = regs++
        } else if (!module) {
            for (p in scope.params) locals[p] = regs++
            for (n in scope.flags.keys) {
                val k = scope.kind(n)
                if ((k == Scope.Kind.LOCAL || k == Scope.Kind.CELL) && n !in locals) locals[n] = regs++
            }
            for (n in scope.frees) if (n !in locals) locals[n] = regs++
        }
    }

    companion object {
        /** An `__annotate__`'s parameters: (format, /); a lazily evaluated bound's, default's, alias value's: (.format=1, /). */
        private val ANNOTATE_ARGS = Arguments(listOf(Arg("format", null)), emptyList(), null, emptyList(), emptyList(), null, emptyList())
        private val LAZY_ARGS = Arguments(listOf(Arg(".format", null)), emptyList(), null, emptyList(), emptyList(), null, emptyList())

        /**
         * compile()'s code of [src] ([mode] exec, or eval: an expression's value returned): a module's in name mode
         * (LOAD_NAME, STORE_NAME: exec()'s locals may not be its globals); [future] the caller's
         * `from __future__ import annotations`.
         */
        @JvmStatic fun compileSource(src: String, mode: String, future: Boolean): CodeUnit {
            val m = try {
                if (mode == "eval") {
                    val e = PyParser(src).parseExpression().body
                    Module(listOf(ExprStmt(e).also { it.line = e.line; it.col = e.col })).also { it.line = 1 }
                } else PyParser(src).parseModule()
            } catch (e: PySyntaxError) {
                e.stage = 2
                throw e
            }
            val st = PySymtable.build(m, checkFutures(m) || future)
            val c = PyCompiler(st, st.scopes[m]!!, "<module>")
            c.nameMode = true
            c.evalMode = mode == "eval"
            try {
                return c.compileBody(m.body, null)
            } catch (e: PySyntaxError) {
                e.stage = 1
                throw e
            }
        }

        /** A module's `from __future__` imports checked (at its start only); whether annotations is one of them. */
        private fun checkFutures(m: Module): Boolean {
            var start = true
            for ((i, st) in m.body.withIndex()) {
                val future = st is ImportFrom && st.module == "__future__" && st.level == 0
                if (future && !start) throw PySyntaxError("from __future__ imports must occur at the beginning of the file", st.line, st.col)
                if (!future && !(i == 0 && st is ExprStmt && st.value is Constant && (st.value as Constant).value is PyStr)) start = false
            }
            return m.body.any { it is ImportFrom && it.module == "__future__" && it.level == 0 && it.names.any { a -> a.name == "annotations" } }
        }

        /** [cb] and every code block in it as compiled from [filename]; [cb]. */
        @JvmStatic fun withFilename(cb: CodeUnit, filename: String): CodeUnit {
            (cb.dialectData as? PyCodeInfo)?.filename = filename
            for (c in cb.constants) if (c is CodeUnit) withFilename(c, filename)
            return cb
        }

        /** The code block of module [src]. */
        @JvmStatic fun compileModule(src: String, name: String = "<module>"): CodeUnit {
            val m = PyParser(src).parseModule()
            var start = true
            for ((i, st) in m.body.withIndex()) {
                val future = st is ImportFrom && st.module == "__future__" && st.level == 0
                if (future && !start) throw PySyntaxError("from __future__ imports must occur at the beginning of the file", st.line, st.col)
                if (!future && !(i == 0 && st is ExprStmt && st.value is Constant && (st.value as Constant).value is PyStr)) start = false
            }
            // (from __future__ import annotations: annotations are their source, PEP 563)
            val future = m.body.any { it is ImportFrom && it.module == "__future__" && it.level == 0 && it.names.any { a -> a.name == "annotations" } }
            val st = PySymtable.build(m, future)
            return PyCompiler(st, st.scopes[m]!!, name).compileBody(m.body, null)
        }
    }

    /** A module's code compile() made: its names LOAD_NAME's ([nameMode]); an eval's, its expression's value returned. */
    private var nameMode = false
    private var evalMode = false

    /** Whether this is a generator expression's code, and the line of its def (or lambda, or comprehension). */
    private var generatorExpr = false
    private var firstLine = -1

    /** The class body's compiler this code is in (itself for a class), and a class's `self.x` stores' names. */
    private var outerClass: PyCompiler? = null
    private val staticAttributes = sortedSetOf<String>()

    /** A compiler for [s], a scope in this code's. */
    private fun child(s: Scope, name: String): PyCompiler = PyCompiler(st, s, name).also { it.outerClass = if (isClass) this else outerClass }

    /** [n] as this scope names it (its class's private names mangled). */
    private fun mangle(n: String): String = scope.mangle(n)

    /** CPython's errors for binding `__debug__`. */
    private fun forbidden(n: String, del: Boolean = false) {
        if (n == "__debug__") throw PySyntaxError(if (del) "cannot delete __debug__" else "cannot assign to __debug__", line, 0)
    }

    /** An annotation its `__annotate__` gives: [index] the conditional one's (its statement records it running), else -1. */
    private class Deferred(val name: String, val annotation: Expr, val index: Int, val line: Int)

    /** A module's or class body's annotated names, as compiled; the compound statements around (a class's are conditional). */
    private val deferred = ArrayList<Deferred>()
    private var conditionalDepth = 0
    private var nextConditional = 0

    /** The code of [body] ([comprehension]'s instead, for one); [args], a function's parameters. */
    private fun compileBody(body: List<Stmt>, args: Arguments?, comprehension: Expr? = null): CodeUnit {
        prologue()
        if (comprehension != null) {
            comprehensionBody(comprehension)
            return unit(null, null)
        }
        val doc = docstring(body)
        val future = st.futureAnnotations
        // a module's __annotate__ (made first, its code after the body's: a constant filled then) and its record of
        // the annotations that ran
        var annotateSlot = -1
        if (module) {
            if (!future && scope.deferredAnnotations) {
                annotateSlot = constants.size
                constants.add(null)
                emit(PyOp.MAKE_FUNCTION, annotateSlot)
                storeName("__annotate__")
            }
            if (scope.conditionalAnnotations) {
                emit(PyOp.BUILD_SET, 0)
                storeName("__conditional_annotations__")
            }
            if (future && scope.annotationsUsed) emit(PyOp.SETUP_ANNOTATIONS)
        }
        if (isClass) {
            // a class's __module__, __qualname__ and __doc__, first in its namespace
            emit(PyOp.LOAD_GLOBAL, name("__name__"))
            emit(PyOp.STORE_NAME, name("__module__"))
            loadConst(PyStr(scope.qualname))
            emit(PyOp.STORE_NAME, name("__qualname__"))
            loadConst(firstLine.toLong())
            emit(PyOp.STORE_NAME, name("__firstlineno__"))
            // (a generic class's type parameters)
            if (".type_params" in scope.frees) {
                loadName(".type_params")
                emit(PyOp.STORE_NAME, name("__type_params__"))
            }
            // (the namespace its annotation scopes read, a cell; the record of its conditional annotations)
            if (scope.classDict) {
                emit(PyOp.LOAD_NAMESPACE)
                emit(PyOp.MAKE_CELL)
                emit(PyOp.STORE_FAST, locals["__classdict__"]!!)
            }
            if (scope.conditionCell) {
                emit(PyOp.BUILD_SET, 0)
                emit(PyOp.MAKE_CELL)
                emit(PyOp.STORE_FAST, locals["__conditional_annotations__"]!!)
            }
            if (doc != null) {
                loadConst(PyStr(doc))
                emit(PyOp.STORE_NAME, name("__doc__"))
            }
            if (future && scope.annotationsUsed) emit(PyOp.SETUP_ANNOTATIONS)
        }
        if (evalMode) {
            val e = (body[0] as ExprStmt).value
            lineHere(e.line)
            expr(e)
            emit(PyOp.RETURN)
        } else for (s in body) stmt(s)
        if (deferred.isNotEmpty()) {
            val ann = scope.annotationScope!!
            val cb = child(ann, "__annotate__").compileAnnotate(deferred, firstLineOf(body))
            if (module) constants[annotateSlot] = cb
            else {
                // a class's, at the end of its body: __annotate_func__, which C.__annotate__ gives
                closure(ann)
                emit(PyOp.MAKE_FUNCTION, const(cb))
                if (ann.frees.isNotEmpty()) emit(PyOp.SET_FUNCTION_ATTRIBUTE, 8)
                emit(PyOp.STORE_NAME, name("__annotate_func__"))
            }
        }
        // a class's `self.x` stores' names, sorted; its __class__ cell, which type.__new__ fills with the class; its
        // __classdict__'s, with its dict
        if (isClass) {
            loadConst(PyTuple(staticAttributes.map { PyStr(it) }.toTypedArray()))
            emit(PyOp.STORE_NAME, name("__static_attributes__"))
        }
        // (CPython's order: the __classdict__ cell's, then the __class__ cell's)
        if (isClass && scope.classDict) {
            emit(PyOp.LOAD_FAST, locals["__classdict__"]!!)
            emit(PyOp.STORE_NAME, name("__classdictcell__"))
        }
        if (isClass && scope.classCell) {
            emit(PyOp.LOAD_FAST, locals["__class__"]!!)
            emit(PyOp.STORE_NAME, name("__classcell__"))
        }
        return unit(doc, args)
    }

    private fun firstLineOf(body: List<Stmt>): Int = if (firstLine > 0) firstLine else body.firstOrNull()?.line ?: 1

    /** The cells of [child]'s free variables, a tuple (MAKE_FUNCTION's closure), where it has any. */
    private fun closure(child: Scope) {
        if (child.frees.isEmpty()) return
        for (n in child.frees) emit(PyOp.LOAD_FAST, locals[n] ?: throw IllegalStateException("no cell for $n in $name"))
        emit(PyOp.BUILD_TUPLE, child.frees.size)
    }

    /**
     * An `__annotate__`'s code (this compiler's scope an annotation scope): NotImplementedError for a format above 2,
     * then the dict of [entries], each evaluated at its line (a conditional one where its statement ran).
     */
    private fun compileAnnotate(entries: List<Deferred>, line: Int): CodeUnit {
        firstLine = line
        prologue()
        lineHere(line)
        emit(PyOp.LOAD_FAST, locals[".format"]!!)
        emit(PyOp.ANNOTATE_CHECK)
        emit(PyOp.BUILD_MAP, 0)
        for (d in entries) {
            lineHere(d.line)
            var skip = -1
            if (d.index >= 0) {
                loadConst(d.index.toLong())
                loadName("__conditional_annotations__")
                emit(PyOp.IN)
                skip = emit(PyOp.POP_JUMP_IF_FALSE, 0)
            }
            loadConst(PyStr(d.name))
            annotationValue(d.annotation)
            emit(PyOp.MAP_ADD)
            if (skip >= 0) patch(skip, code.size)
        }
        emit(PyOp.RETURN)
        val cb = unit(null, ANNOTATE_ARGS)
        // (its parameter is `.format` to its scope, `format` to its callers)
        (cb.dialectData as PyCodeInfo).paramNames = listOf("format")
        return cb
    }

    /** An annotation's value: evaluated (`*X` the one value X unpacks to, PEP 646), or its source (PEP 563). */
    private fun annotationValue(e: Expr) {
        if (st.futureAnnotations) loadConst(PyStr(PyUnparse.annotation(e)))
        else if (e is Starred) {
            expr(e.value)
            emit(PyOp.UNPACK, 1)
        } else expr(e)
    }

    /** The code unit of what was emitted: return None at its end, its registers, constants, handlers, lines. */
    private fun unit(doc: String?, args: Arguments?): CodeUnit {
        loadConst(PyNone)
        emit(PyOp.RETURN)
        val cb = CodeUnit(name)
        cb.code = code.toArray()
        cb.handlers = handlerTable.toArray()
        markUnbound(cb.code)
        cb.constants = constants.toTypedArray()
        cb.numRegs = maxOf(regs, maxRegs)
        cb.maxStack = maxStack(cb.code)
        cb.formalCount = scope.params.size
        cb.paramRegs = IntArray(scope.params.size) { it }
        cb.lineTable = lines.toArray()
        cb.dialect = PyDialect
        val names = Array(cb.numRegs) { "" }
        for ((n, r) in locals) names[r] = n
        val info = PyCodeInfo(name, names, module)
        info.qualname = scope.qualname
        if (!namespace) info.doc = doc
        if (args != null) {
            info.posonlyCount = args.posonlyargs.size
            info.argcount = args.posonlyargs.size + args.args.size
            info.kwonlyCount = args.kwonlyargs.size
            info.varargs = args.vararg != null
            info.varkw = args.kwarg != null
            info.paramNames = scope.params.toList()
        }
        // (a generator's, a coroutine's, an async generator's frame is kept between its suspensions: interpreted)
        val gen = scope.isGenerator || generatorExpr
        info.kind = when {
            scope.isCoroutine && gen -> PyCodeInfo.ASYNC_GENERATOR
            scope.isCoroutine -> PyCodeInfo.COROUTINE
            gen -> PyCodeInfo.GENERATOR
            else -> 0
        }
        if (info.kind != 0) cb.flags = cb.flags or CodeUnit.GENERATOR
        info.firstLine = firstLine
        info.freeNames = scope.frees.toList()
        if (!namespace) {
            info.varnames = scope.params + locals.keys.filter { it !in scope.params && scope.kind(it) == Scope.Kind.LOCAL && !it.startsWith(".") }
            info.cellNames = locals.keys.filter { scope.kind(it) == Scope.Kind.CELL && !it.startsWith(".") }
            var fl = 3 // CO_OPTIMIZED, CO_NEWLOCALS
            if (args?.vararg != null) fl = fl or 4
            if (args?.kwarg != null) fl = fl or 8
            var p = scope.parent
            while (p != null && p.type == Scope.Type.TYPE_PARAMS) p = p.parent
            if (p != null && p.isFunction) fl = fl or 16 // CO_NESTED
            if (p != null && p.type == Scope.Type.CLASS) fl = fl or 0x8000000 // CO_METHOD
            if (doc != null) fl = fl or 0x4000000 // CO_HAS_DOCSTRING
            fl = fl or when (info.kind) { PyCodeInfo.GENERATOR -> 32; PyCodeInfo.COROUTINE -> 128; PyCodeInfo.ASYNC_GENERATOR -> 512; else -> 0 }
            info.coFlags = fl
        }
        cb.dialectData = info
        return cb
    }

    /** A body's docstring: its first statement, where that is a string. */
    private fun docstring(body: List<Stmt>): String? = ((body.firstOrNull() as? ExprStmt)?.value as? Constant)?.value.let { (it as? PyStr)?.s }

    /** A function's start: its cells made (a parameter's holding its argument), its free variables' cells taken. */
    private fun prologue() {
        if (module) return
        if (isClass) {
            for ((k, n) in scope.frees.withIndex()) {
                emit(PyOp.LOAD_FREE, k)
                emit(PyOp.STORE_FAST, locals[n]!!)
            }
            if (scope.classCell) {
                emit(PyOp.NEW_CELL)
                emit(PyOp.STORE_FAST, locals["__class__"]!!)
            }
            return
        }
        for (n in scope.cells) {
            val r = locals[n]!!
            if (n in scope.params) {
                emit(PyOp.LOAD_FAST, r)
                emit(PyOp.MAKE_CELL)
            } else emit(PyOp.NEW_CELL)
            emit(PyOp.STORE_FAST, r)
        }
        for ((k, n) in scope.frees.withIndex()) {
            emit(PyOp.LOAD_FREE, k)
            emit(PyOp.STORE_FAST, locals[n]!!)
        }
    }

    // ------------------------------------------------------------------ emitting

    private fun emit(op: Int, vararg operands: Int): Int {
        val at = code.size
        code.add(op)
        for (o in operands) code.add(o)
        return at
    }

    /** Sets the target of the jump at [at] (its last operand) to [target]. */
    private fun patch(at: Int, target: Int) {
        code[at + PyOp.operands[code[at]]] = target
    }

    private fun const(v: Any?): Int {
        // (1, 1.0 and True are different constants: the key holds the class)
        val key: Any = if (v == null) PyNone else Pair(v.javaClass, if (v is Double) java.lang.Double.doubleToRawLongBits(v) else v)
        if (v is CodeUnit) return constants.size.also { constants.add(v) }
        return constIndex.getOrPut(key) { constants.size.also { constants.add(v) } }
    }

    private fun loadConst(v: Any?) = emit(PyOp.LOAD_CONST, const(v))

    private fun name(n: String): Int = const(PyStr(n))

    /**
     * A temporary register, until the statement that took it ends ([stmt] gives its temporaries back): a try's or a
     * with's, another's after (fewer registers kept for handlers, CodeUnit.handlers).
     */
    private fun temp(): Int {
        val r = regs++
        if (regs > maxRegs) maxRegs = regs
        return r
    }

    private fun unsupported(what: String, n: Node): Nothing = throw PySyntaxError("$what is not supported yet", n.line, n.col)

    // ------------------------------------------------------------------ names

    private fun loadName(raw: String) {
        if (raw == "__debug__") {
            loadConst(PyBool.TRUE)
            return
        }
        val n = mangle(raw)
        val isClass = isClass || nameMode
        if (scope.canSeeClass && n != "__classdict__" && n != "__conditional_annotations__") {
            when (scope.kind(n)) {
                Scope.Kind.GLOBAL_IMPLICIT -> {
                    loadClassDict()
                    emit(PyOp.LOAD_FROM_DICT_OR_GLOBALS, name(n))
                    return
                }
                Scope.Kind.FREE -> {
                    loadClassDict()
                    emit(PyOp.LOAD_FAST, locals[n]!!)
                    emit(PyOp.LOAD_FROM_DICT_OR_CELL, name(n))
                    return
                }
                else -> {}
            }
        }
        if (isClass) {
            when (scope.kind(n)) {
                Scope.Kind.GLOBAL_EXPLICIT -> emit(PyOp.LOAD_GLOBAL, name(n))
                Scope.Kind.FREE, Scope.Kind.CELL -> {
                    emit(PyOp.LOAD_FAST, locals[n]!!)
                    emit(PyOp.LOAD_FROM_DICT_OR_DEREF, name(n))
                }
                else -> emit(PyOp.LOAD_NAME, name(n))
            }
            return
        }
        when (scope.kind(n)) {
            Scope.Kind.LOCAL -> if (module) emit(PyOp.LOAD_GLOBAL, name(n)) else emit(PyOp.LOAD_FAST, locals[n]!!)
            Scope.Kind.CELL, Scope.Kind.FREE -> {
                emit(PyOp.LOAD_FAST, locals[n]!!)
                emit(PyOp.CELL_GET, name(n), if (scope.kind(n) == Scope.Kind.FREE) 1 else 0)
            }
            else -> emit(PyOp.LOAD_GLOBAL, name(n))
        }
    }

    /** The namespace of the class an annotation scope is in (its `__classdict__` cell's). */
    private fun loadClassDict() {
        emit(PyOp.LOAD_FAST, locals["__classdict__"]!!)
        emit(PyOp.CELL_GET, name("__classdict__"), 1)
    }

    private fun storeName(raw: String) {
        forbidden(raw)
        val n = mangle(raw)
        val isClass = isClass || nameMode
        if (isClass) {
            when (scope.kind(n)) {
                Scope.Kind.GLOBAL_EXPLICIT -> emit(PyOp.STORE_GLOBAL, name(n))
                Scope.Kind.FREE -> if ((scope.flags[n] ?: 0) and Scope.DEF_NONLOCAL != 0) {
                    emit(PyOp.LOAD_FAST, locals[n]!!)
                    emit(PyOp.CELL_SET)
                } else emit(PyOp.STORE_NAME, name(n))
                else -> emit(PyOp.STORE_NAME, name(n))
            }
            return
        }
        when (scope.kind(n)) {
            Scope.Kind.LOCAL -> if (module) emit(PyOp.STORE_GLOBAL, name(n)) else emit(PyOp.STORE_FAST, locals[n]!!)
            Scope.Kind.CELL, Scope.Kind.FREE -> {
                emit(PyOp.LOAD_FAST, locals[n]!!)
                emit(PyOp.CELL_SET)
            }
            else -> emit(PyOp.STORE_GLOBAL, name(n))
        }
    }

    private fun deleteName(raw: String) {
        forbidden(raw, del = true)
        val n = mangle(raw)
        val isClass = isClass || nameMode
        if (isClass) {
            when (scope.kind(n)) {
                Scope.Kind.GLOBAL_EXPLICIT -> emit(PyOp.DELETE_GLOBAL, name(n))
                else -> emit(PyOp.DELETE_NAME, name(n))
            }
            return
        }
        when (scope.kind(n)) {
            Scope.Kind.LOCAL -> if (module) emit(PyOp.DELETE_GLOBAL, name(n)) else {
                // (a local without a value is an UnboundLocalError: loading it, as the check after this does)
                emit(PyOp.LOAD_FAST, locals[n]!!)
                emit(PyOp.POP)
                emit(PyOp.DELETE_FAST, locals[n]!!)
            }
            Scope.Kind.CELL, Scope.Kind.FREE -> {
                emit(PyOp.LOAD_FAST, locals[n]!!)
                emit(PyOp.CELL_DELETE, name(n), if (scope.kind(n) == Scope.Kind.FREE) 1 else 0)
            }
            else -> emit(PyOp.DELETE_GLOBAL, name(n))
        }
    }

    // ------------------------------------------------------------------ statements

    private fun stmt(s: Stmt) {
        val temps = regs
        val compound = s is For || s is While || s is If || s is With || s is Try || s is Match
        if (compound) conditionalDepth++
        stmtBody(s)
        if (compound) conditionalDepth--
        regs = temps
    }

    private fun stmtBody(s: Stmt) {
        // each statement's first pc and its line (the optimizing tier stores the pc where something can throw)
        line = s.line
        lines.add(code.size)
        lines.add(s.line)
        when (s) {
            is ExprStmt -> {
                expr(s.value)
                emit(PyOp.POP)
            }
            is Assign -> {
                expr(s.value)
                for ((k, t) in s.targets.withIndex()) {
                    if (k < s.targets.size - 1) emit(PyOp.DUP)
                    store(t)
                }
            }
            is AugAssign -> augAssign(s)
            is AnnAssign -> {
                if (s.value != null) {
                    expr(s.value)
                    store(s.target)
                } else when (val t = s.target) {
                    // (an annotation alone evaluates its target's parts, not the target)
                    is Attribute -> { forbidden(t.attr); expr(t.value); emit(PyOp.POP) }
                    is Subscript -> { expr(t.value); emit(PyOp.POP); expr(t.slice); emit(PyOp.POP) }
                    else -> {}
                }
                val t = s.target
                // a module's or class's simple name: its annotation its __annotate__'s (a function's is never evaluated)
                if (namespace && t is Name && s.simple == 1) {
                    if (st.futureAnnotations) {
                        loadConst(PyStr(PyUnparse.annotation(s.annotation)))
                        loadName("__annotations__")
                        loadConst(PyStr(mangle(t.id)))
                        emit(PyOp.STORE_SUBSCR)
                    } else {
                        val index = if (module || conditionalDepth > 0) nextConditional++ else -1
                        deferred.add(Deferred(mangle(t.id), s.annotation, index, s.line))
                        if (index >= 0) {
                            // (a module's global; a class's cell, which its annotation scope reads)
                            if (module) loadName("__conditional_annotations__") else {
                                emit(PyOp.LOAD_FAST, locals["__conditional_annotations__"]!!)
                                emit(PyOp.CELL_GET, name("__conditional_annotations__"), 0)
                            }
                            loadConst(index.toLong())
                            emit(PyOp.SET_ADD)
                            emit(PyOp.POP)
                        }
                    }
                }
            }
            is If -> {
                expr(s.test)
                val toElse = emit(PyOp.POP_JUMP_IF_FALSE, 0)
                block(s.body)
                if (s.orelse.isEmpty()) patch(toElse, code.size)
                else {
                    val toEnd = emit(PyOp.JUMP, 0)
                    patch(toElse, code.size)
                    block(s.orelse)
                    patch(toEnd, code.size)
                }
            }
            is While -> {
                val top = code.size
                expr(s.test)
                val exit = emit(PyOp.POP_JUMP_IF_FALSE, 0)
                val loop = Loop(top)
                fblocks.add(loop)
                block(s.body)
                fblocks.removeAt(fblocks.size - 1)
                emit(PyOp.JUMP, top)
                patch(exit, code.size)
                block(s.orelse)
                for (b in loop.breaks) patch(b, code.size)
            }
            is For -> if (s.isAsync) asyncFor(s) else {
                expr(s.iter)
                emit(PyOp.GET_ITER)
                val it = temp()
                val value = temp()
                emit(PyOp.STORE_FAST, it)
                val top = code.size
                val exit = emit(PyOp.FOR_ITER, it, value, 0)
                emit(PyOp.LOAD_FAST, value)
                store(s.target)
                val loop = Loop(top)
                fblocks.add(loop)
                block(s.body)
                fblocks.removeAt(fblocks.size - 1)
                emit(PyOp.JUMP, top)
                patch(exit, code.size)
                block(s.orelse)
                for (b in loop.breaks) patch(b, code.size)
            }
            is FunctionDef -> {
                for (d in s.decoratorList) expr(d)
                if (s.typeParams.isNotEmpty()) generic(s, s.name) else makeFunction(s, s.name, s.args, s.body)
                repeat(s.decoratorList.size) { emit(PyOp.CALL, 1) }
                storeName(s.name)
            }
            is Return -> {
                if (namespace) throw PySyntaxError("'return' outside function", s.line, s.col)
                if (fblocks.any { it is StarBody }) throw PySyntaxError("'break', 'continue' and 'return' cannot appear in an except* block", s.line, s.col)
                if (s.value != null && scope.isCoroutine && scope.isGenerator) throw PySyntaxError("'return' with value in async generator", s.line, s.col)
                if (s.value == null) loadConst(PyNone) else expr(s.value)
                val exited = fblocks.asReversed().toList()
                if (exited.none { it.leaves }) emit(PyOp.RETURN) else {
                    // (the value kept while finally bodies run)
                    val t = temp()
                    emit(PyOp.STORE_FAST, t)
                    unwind(exited) {
                        emit(PyOp.LOAD_FAST, t)
                        emit(PyOp.RETURN)
                    }
                }
            }
            is Delete -> for (t in s.targets) delete(t)
            is Assert -> {
                expr(s.test)
                val ok = emit(PyOp.POP_JUMP_IF_TRUE, 0)
                if (s.msg != null) {
                    expr(s.msg)
                    emit(PyOp.ASSERT_FAIL, 1)
                } else emit(PyOp.ASSERT_FAIL, 0)
                patch(ok, code.size)
            }
            is Global, is Nonlocal, is Pass -> {}
            is Break -> {
                val k = fblocks.indexOfLast { it is Loop }
                // (CPython unwinds to the loop, or all the blocks where none: an except* block on the way its error)
                if (k < 0 && fblocks.any { it is StarBody }) throw PySyntaxError("'break', 'continue' and 'return' cannot appear in an except* block", s.line, s.col)
                if (k < 0) throw PySyntaxError("'break' outside loop", s.line, s.col)
                val loop = fblocks[k] as Loop
                unwind(fblocks.subList(k + 1, fblocks.size).asReversed().toList()) { loop.breaks.add(emit(PyOp.JUMP, 0)) }
            }
            is Continue -> {
                val k = fblocks.indexOfLast { it is Loop }
                if (k < 0 && fblocks.any { it is StarBody }) throw PySyntaxError("'break', 'continue' and 'return' cannot appear in an except* block", s.line, s.col)
                if (k < 0) throw PySyntaxError("'continue' not properly in loop", s.line, s.col)
                val loop = fblocks[k] as Loop
                unwind(fblocks.subList(k + 1, fblocks.size).asReversed().toList()) { emit(PyOp.JUMP, loop.top) }
            }
            is ClassDef -> classDef(s)
            is Try -> tryStmt(s)
            is Raise -> {
                var n = 0
                if (s.exc != null) {
                    expr(s.exc)
                    n = 1
                    if (s.cause != null) {
                        expr(s.cause)
                        n = 2
                    }
                }
                emit(PyOp.RAISE, n)
            }
            is With -> withItems(s, 0)
            is Match -> matchStmt(s)
            is Import -> for (a in s.names) {
                loadConst(0L)
                loadConst(PyNone)
                emit(PyOp.IMPORT_NAME, name(a.name))
                if (a.asname != null) {
                    // (import a.b as x: a's attribute b, as 3.12 imports it)
                    for (part in a.name.split('.').drop(1)) emit(PyOp.IMPORT_FROM, name(part))
                    storeName(a.asname)
                } else storeName(a.name.substringBefore('.'))
            }
            is ImportFrom -> importFrom(s)
            is TypeAlias -> {
                if (s.typeParams.isNotEmpty()) generic(s, s.name.id) else {
                    loadConst(PyStr(s.name.id))
                    loadConst(PyNone)
                    lazyFunction(st.aliasScopes[s]!!, s.value, false)
                    emit(PyOp.MAKE_TYPEALIAS)
                }
                storeName(s.name.id)
            }
        }
    }

    private fun block(ss: List<Stmt>) {
        for (s in ss) stmt(s)
    }

    /**
     * Leaves [exited] (innermost first), as `return`, `break` and `continue` do: their ranges closed here (what leaving
     * runs is not theirs to handle), what leaving each runs (a finally body, the exception handled before, an except
     * clause's name deleted) from outside it, then [jump]; their ranges open again after (the code after is theirs).
     */
    private fun unwind(exited: List<FBlock>, jump: () -> Unit) {
        if (exited.any { it is StarBody }) throw PySyntaxError("'break', 'continue' and 'return' cannot appear in an except* block", line, 0)
        if (exited.none { it.leaves }) {
            jump()
            return
        }
        for (fb in exited) fb.range?.end()
        val saved = ArrayList(fblocks)
        for (fb in exited) {
            while (fblocks.isNotEmpty() && fblocks.last() !== fb) fblocks.removeAt(fblocks.size - 1)
            fblocks.removeAt(fblocks.size - 1)
            when (fb) {
                is Finally -> block(fb.body)
                is Handler -> {
                    emit(PyOp.LOAD_FAST, fb.prev)
                    emit(PyOp.POP_EXCEPT)
                }
                is HandlerName -> clearName(fb.name)
                is WithBody -> exitWith(fb.exit, fb.async)
            }
        }
        jump()
        fblocks.clear()
        fblocks.addAll(saved)
        for (fb in exited) fb.range?.begin()
    }

    /**
     * `with` (its items, from [k] on, each inside the one before): the manager's __exit__ looked up and kept, then its
     * __enter__ called; the target stored and the body in a range whose handler calls __exit__ with the exception and
     * raises it again unless that says to suppress it; __exit__ with three Nones on the way out. `async with`'s
     * __aenter__ and __aexit__, each call's result awaited.
     */
    private fun withItems(s: With, k: Int) {
        if (k == s.items.size) {
            block(s.body)
            return
        }
        val item = s.items[k]
        val async = s.isAsync
        val special = if (async) 2 else 0
        expr(item.contextExpr)
        val mgr = temp()
        val exit = temp()
        emit(PyOp.STORE_FAST, mgr)
        emit(PyOp.LOAD_FAST, mgr)
        emit(PyOp.LOAD_SPECIAL, special + 1)
        emit(PyOp.STORE_FAST, exit)
        emit(PyOp.LOAD_FAST, mgr)
        emit(PyOp.LOAD_SPECIAL, special)
        emit(PyOp.CALL, 0)
        if (async) await(1)
        val range = Range()
        fblocks.add(WithBody(range, exit, async))
        // (storing the target is the body's: an exception there calls __exit__ too)
        range.begin()
        if (item.optionalVars != null) store(item.optionalVars) else emit(PyOp.POP)
        withItems(s, k + 1)
        range.end()
        fblocks.removeAt(fblocks.size - 1)
        exitWith(exit, async)
        val toEnd = emit(PyOp.JUMP, 0)
        range.handle(code.size)
        var suppressed = -1
        handler(s.line) { exc, prev, _ ->
            emit(PyOp.LOAD_FAST, exit)
            emit(PyOp.LOAD_FAST, exc)
            emit(PyOp.WITH_EXCEPT)
            if (async) await(2)
            val sup = emit(PyOp.POP_JUMP_IF_TRUE, 0)
            emit(PyOp.LOAD_FAST, exc)
            emit(PyOp.RERAISE)
            patch(sup, code.size)
            emit(PyOp.LOAD_FAST, prev)
            emit(PyOp.POP_EXCEPT)
            suppressed = emit(PyOp.JUMP, 0)
        }
        patch(suppressed, code.size)
        patch(toEnd, code.size)
    }

    /**
     * A comprehension, a function of its own (as before Python 3.12): its first iterable's iterator made here, its
     * code called with it ([comprehensionBody]); a generator expression's code is a generator's.
     */
    private fun comprehension(e: Expr) {
        val child = st.scopes[e]!!
        val gens = generatorsOf(e)
        var flags = 0
        if (child.frees.isNotEmpty()) {
            for (n in child.frees) emit(PyOp.LOAD_FAST, locals[n] ?: throw IllegalStateException("no cell for $n in $name"))
            emit(PyOp.BUILD_TUPLE, child.frees.size)
            flags = 8
        }
        val c = child(child, child.name)
        c.generatorExpr = e is GeneratorExp
        c.firstLine = e.line
        val cb = c.compileBody(emptyList(), null, e)
        emit(PyOp.MAKE_FUNCTION, const(cb))
        if (flags != 0) emit(PyOp.SET_FUNCTION_ATTRIBUTE, flags)
        expr(gens[0].iter)
        emit(if (gens[0].isAsync == 1) PyOp.GET_AITER else PyOp.GET_ITER)
        emit(PyOp.CALL, 1)
        // (an asynchronous comprehension's code, other than a generator expression's, a coroutine: awaited here)
        if (child.isCoroutine && e !is GeneratorExp) await(0)
    }

    private fun generatorsOf(e: Expr): List<Comprehension> = when (e) {
        is ListComp -> e.generators
        is DictComp -> e.generators
        is GeneratorExp -> e.generators
        is SetComp -> e.generators
        else -> throw IllegalStateException("not a comprehension: $e")
    }

    /**
     * A comprehension's code: its loops, one in another (the first over its argument `.0`, the iterator), their
     * conditions, and at the innermost the element appended (a list's), the key and value added (a dict's) or yielded
     * (a generator expression's); then the list or dict returned.
     */
    private fun comprehensionBody(e: Expr) {
        lineHere(e.line)
        val gens = generatorsOf(e)
        val acc = if (e is GeneratorExp) -1 else temp().also {
            emit(when (e) { is ListComp -> PyOp.BUILD_LIST; is SetComp -> PyOp.BUILD_SET; else -> PyOp.BUILD_MAP }, 0)
            emit(PyOp.STORE_FAST, it)
        }
        fun level(k: Int) {
            val g = gens[k]
            val async = g.isAsync == 1
            val it = if (k == 0) locals[".0"]!! else {
                expr(g.iter)
                emit(if (async) PyOp.GET_AITER else PyOp.GET_ITER)
                temp().also { emit(PyOp.STORE_FAST, it) }
            }
            val top = code.size
            var exit = -1
            val next = if (async) asyncNext(it) else {
                val value = temp()
                exit = emit(PyOp.FOR_ITER, it, value, 0)
                emit(PyOp.LOAD_FAST, value)
                null
            }
            store(g.target)
            val skips = ArrayList<Int>()
            for (c in g.ifs) {
                expr(c)
                skips.add(emit(PyOp.POP_JUMP_IF_FALSE, 0))
            }
            if (k + 1 < gens.size) level(k + 1) else when (e) {
                is ListComp -> {
                    emit(PyOp.LOAD_FAST, acc)
                    expr(e.elt)
                    emit(PyOp.LIST_APPEND)
                    emit(PyOp.POP)
                }
                is DictComp -> {
                    emit(PyOp.LOAD_FAST, acc)
                    expr(e.key)
                    expr(e.value)
                    emit(PyOp.MAP_ADD)
                    emit(PyOp.POP)
                }
                is SetComp -> {
                    emit(PyOp.LOAD_FAST, acc)
                    expr(e.elt)
                    emit(PyOp.SET_ADD)
                    emit(PyOp.POP)
                }
                is GeneratorExp -> {
                    expr(e.elt)
                    if (scope.isCoroutine) emit(PyOp.ASYNC_GEN_WRAP)
                    emit(PyOp.YIELD)
                    emit(PyOp.RESUME)
                    emit(PyOp.POP)
                }
                else -> {}
            }
            // (a condition that fails goes on to the next value: through the loop's jump back, its checks)
            for (s in skips) patch(s, code.size)
            emit(PyOp.JUMP, top)
            if (next != null) asyncEnd(next, e.line) else patch(exit, code.size)
        }
        level(0)
        if (acc >= 0) {
            emit(PyOp.LOAD_FAST, acc)
            emit(PyOp.RETURN)
        }
    }

    /**
     * A t-string (PEP 750): its strings (a constant tuple, one more than its fields: empty ones between two fields
     * and at the ends) and its fields as Interpolations (the value, its source, its conversion, its spec formatted).
     */
    private fun template(e: TemplateStr) {
        val strings = ArrayList<Any?>()
        val fields = ArrayList<Interpolation>()
        var cur = StringBuilder()
        for (v in e.values) when (v) {
            is Interpolation -> {
                strings.add(PyStr(cur.toString()))
                cur = StringBuilder()
                fields.add(v)
            }
            is Constant -> cur.append((v.value as PyStr).s)
            else -> throw IllegalStateException("a t-string's part: $v")
        }
        strings.add(PyStr(cur.toString()))
        loadConst(PyTuple(strings.toTypedArray()))
        for (f in fields) {
            expr(f.value)
            loadConst(PyStr(f.str))
            loadConst(if (f.conversion < 0) PyNone else PyStr(f.conversion.toChar().toString()))
            if (f.formatSpec != null) expr(f.formatSpec) else loadConst(PyStr.EMPTY)
            emit(PyOp.BUILD_INTERPOLATION)
        }
        emit(PyOp.BUILD_TUPLE, fields.size)
        emit(PyOp.BUILD_TEMPLATE)
    }

    /** An f-string: its parts (strs, and fields formatted) joined; a part alone is the value. */
    private fun fstring(e: JoinedStr) {
        if (e.values.isEmpty()) {
            loadConst(PyStr.EMPTY)
            return
        }
        for (v in e.values) expr(v)
        if (e.values.size > 1) emit(PyOp.BUILD_STRING, e.values.size)
    }

    /** An f-string's field: its value, converted (!s, !r, !a) and formatted with its spec (an f-string itself). */
    private fun formatted(v: FormattedValue) {
        expr(v.value)
        val conv = when (v.conversion) {
            's'.code -> 1
            'r'.code -> 2
            'a'.code -> 3
            else -> 0
        }
        if (v.formatSpec != null) {
            expr(v.formatSpec)
            emit(PyOp.FORMAT_SPEC, conv)
        } else emit(PyOp.FORMAT_VALUE, conv)
    }

    /**
     * locals() (and vars()) in a function, the builtin's: a dict of its locals that have a value, made from its
     * registers (LOAD_FAST_RAW), a cell's contents for a cell or free variable's (LOAD_LOCALS).
     */
    private fun localsDict() {
        val names = ArrayList<Any?>()
        for ((n, r) in locals) {
            emit(PyOp.LOAD_FAST_RAW, r)
            val k = scope.kind(n)
            names.add(PyTuple(arrayOf(PyStr(n), PyBool.of(k == Scope.Kind.CELL || k == Scope.Kind.FREE))))
        }
        emit(PyOp.BUILD_TUPLE, names.size)
        emit(PyOp.LOAD_LOCALS, const(PyTuple(names.toTypedArray())))
    }

    // ------------------------------------------------------------------ match

    /**
     * `match` (PEP 634): the subject in a register, each case's pattern tested against it (a failing test to the next
     * case), its captures stored once the whole pattern matched, then its guard (the captures kept where it fails),
     * then its body. CPython's errors where the patterns cannot be compiled.
     */
    private fun matchStmt(s: Match) {
        for ((i, c) in s.cases.withIndex()) {
            checkPattern(c.pattern, HashSet())
            if (i < s.cases.size - 1 && c.guard == null) unreachable(c.pattern)?.let { throw PySyntaxError(it, c.pattern.line, c.pattern.col) }
        }
        expr(s.subject)
        val subject = temp()
        emit(PyOp.STORE_FAST, subject)
        val ends = ArrayList<Int>()
        for (c in s.cases) {
            lineHere(c.pattern.line)
            val fails = ArrayList<Int>()
            val caps = LinkedHashMap<String, Int>()
            pattern(c.pattern, subject, fails, caps)
            for ((n, r) in caps) {
                emit(PyOp.LOAD_FAST, r)
                storeName(n)
            }
            if (c.guard != null) {
                expr(c.guard)
                fails.add(emit(PyOp.POP_JUMP_IF_FALSE, 0))
            }
            block(c.body)
            ends.add(emit(PyOp.JUMP, 0))
            for (f in fails) patch(f, code.size)
        }
        for (e in ends) patch(e, code.size)
    }

    private fun irrefutable(p: Pattern): Boolean = when (p) {
        is MatchAs -> p.pattern == null || irrefutable(p.pattern)
        is MatchOr -> p.patterns.any { irrefutable(it) }
        else -> false
    }

    /** Why a pattern that always matches makes the cases after it unreachable (CPython's message), or null. */
    private fun unreachable(p: Pattern): String? = when {
        p is MatchAs && p.pattern == null ->
            if (p.name == null) "wildcard makes remaining patterns unreachable" else "name capture '${p.name}' makes remaining patterns unreachable"
        p is MatchAs -> unreachable(p.pattern!!)
        p is MatchOr -> p.patterns.firstOrNull { irrefutable(it) }?.let { unreachable(it) }
        else -> null
    }

    /** A pattern's names, in order. */
    private fun boundNames(p: Pattern, out: MutableList<String> = ArrayList()): MutableList<String> {
        when (p) {
            is MatchAs -> {
                p.pattern?.let { boundNames(it, out) }
                p.name?.let { out.add(it) }
            }
            is MatchStar -> p.name?.let { out.add(it) }
            is MatchSequence -> p.patterns.forEach { boundNames(it, out) }
            is MatchMapping -> {
                p.patterns.forEach { boundNames(it, out) }
                p.rest?.let { out.add(it) }
            }
            is MatchClass -> {
                p.patterns.forEach { boundNames(it, out) }
                p.kwdPatterns.forEach { boundNames(it, out) }
            }
            is MatchOr -> boundNames(p.patterns[0], out)
            else -> {}
        }
        return out
    }

    /** CPython's compile-time checks of a pattern: names bound once, one star, or-patterns' names, mappings' keys. */
    private fun checkPattern(p: Pattern, names: MutableSet<String>) {
        fun bind(n: String?, at: Node) {
            if (n == null) return
            if (!names.add(n)) throw PySyntaxError("multiple assignments to name '$n' in pattern", at.line, at.col)
        }
        when (p) {
            is MatchAs -> {
                p.pattern?.let { checkPattern(it, names) }
                bind(p.name, p)
            }
            is MatchStar -> bind(p.name, p)
            is MatchSequence -> {
                if (p.patterns.count { it is MatchStar } > 1) throw PySyntaxError("multiple starred names in sequence pattern", p.line, p.col)
                p.patterns.forEach { checkPattern(it, names) }
            }
            is MatchMapping -> {
                val seen = HashSet<String>()
                for (k in p.keys) {
                    val r = constantKey(k) ?: continue
                    if (!seen.add(r)) throw PySyntaxError("mapping pattern checks duplicate key ($r)", p.line, p.col)
                }
                p.patterns.forEach { checkPattern(it, names) }
                bind(p.rest, p)
            }
            is MatchClass -> {
                for ((i, k) in p.kwdAttrs.withIndex()) {
                    if (k == "__debug__") throw PySyntaxError("cannot assign to __debug__", p.kwdPatterns[i].line, p.kwdPatterns[i].col)
                }
                p.patterns.forEach { checkPattern(it, names) }
                p.kwdPatterns.forEach { checkPattern(it, names) }
            }
            is MatchOr -> {
                var first: Set<String>? = null
                for ((i, alt) in p.patterns.withIndex()) {
                    if (i < p.patterns.size - 1 && irrefutable(alt)) throw PySyntaxError(unreachable(alt)!!, alt.line, alt.col)
                    val mine = HashSet(names)
                    checkPattern(alt, mine)
                    val bound = mine - names
                    if (first == null) first = bound else if (first != bound) throw PySyntaxError("alternative patterns bind different names", p.line, p.col)
                }
                names.addAll(first ?: emptySet())
            }
            else -> {}
        }
    }

    /** A mapping pattern's key as CPython's duplicate check writes it, where it is a literal. */
    private fun constantKey(k: Expr): String? = when {
        k is Constant -> PyRt.repr(k.value)
        k is UnaryOp && k.op == UnaryOpKind.USub && k.operand is Constant -> PyRt.repr(PyRt.neg((k.operand as Constant).value))
        else -> null
    }

    /** Tests [p] against the value in register [v]: a failing test's jump in [fails], the captures' registers in [caps]. */
    private fun pattern(p: Pattern, v: Int, fails: MutableList<Int>, caps: MutableMap<String, Int>) {
        fun unpack(n: Int, star: Int = -1): IntArray {
            if (star < 0) emit(PyOp.UNPACK, n) else emit(PyOp.UNPACK_EX, star, n - star - 1)
            return IntArray(n) { temp().also { r -> emit(PyOp.STORE_FAST, r) } }
        }
        when (p) {
            is MatchAs -> {
                if (p.pattern != null) pattern(p.pattern, v, fails, caps)
                if (p.name != null) caps[p.name] = v
            }
            is MatchValue -> {
                emit(PyOp.LOAD_FAST, v)
                expr(p.value)
                emit(PyOp.EQ)
                fails.add(emit(PyOp.POP_JUMP_IF_FALSE, 0))
            }
            is MatchSingleton -> {
                emit(PyOp.LOAD_FAST, v)
                loadConst(p.value)
                emit(PyOp.IS)
                fails.add(emit(PyOp.POP_JUMP_IF_FALSE, 0))
            }
            is MatchSequence -> {
                emit(PyOp.LOAD_FAST, v)
                emit(PyOp.MATCH_SEQUENCE)
                fails.add(emit(PyOp.POP_JUMP_IF_FALSE, 0))
                val star = p.patterns.indexOfFirst { it is MatchStar }
                val n = p.patterns.size
                emit(PyOp.LOAD_FAST, v)
                emit(PyOp.GET_LEN)
                loadConst((if (star < 0) n else n - 1).toLong())
                emit(if (star < 0) PyOp.EQ else PyOp.GE)
                fails.add(emit(PyOp.POP_JUMP_IF_FALSE, 0))
                fun wildcard(x: Pattern) = x is MatchAs && x.pattern == null && x.name == null || x is MatchStar && x.name == null
                if (p.patterns.all { wildcard(it) }) return
                emit(PyOp.LOAD_FAST, v)
                val regs = unpack(n, star)
                for (i in 0 until n) {
                    val sp = p.patterns[i]
                    if (sp is MatchStar) sp.name?.let { caps[it] = regs[i] } else pattern(sp, regs[i], fails, caps)
                }
            }
            is MatchMapping -> {
                emit(PyOp.LOAD_FAST, v)
                emit(PyOp.MATCH_MAPPING)
                fails.add(emit(PyOp.POP_JUMP_IF_FALSE, 0))
                val n = p.keys.size
                if (n > 0) {
                    emit(PyOp.LOAD_FAST, v)
                    emit(PyOp.GET_LEN)
                    loadConst(n.toLong())
                    emit(PyOp.GE)
                    fails.add(emit(PyOp.POP_JUMP_IF_FALSE, 0))
                }
                val keys = temp()
                for (k in p.keys) expr(k)
                emit(PyOp.BUILD_TUPLE, n)
                emit(PyOp.STORE_FAST, keys)
                if (n > 0) {
                    val vals = temp()
                    emit(PyOp.LOAD_FAST, v)
                    emit(PyOp.LOAD_FAST, keys)
                    emit(PyOp.MATCH_KEYS)
                    emit(PyOp.STORE_FAST, vals)
                    emit(PyOp.LOAD_FAST, vals)
                    loadConst(PyNone)
                    emit(PyOp.IS)
                    fails.add(emit(PyOp.POP_JUMP_IF_TRUE, 0))
                    emit(PyOp.LOAD_FAST, vals)
                    val regs = unpack(n)
                    for (i in 0 until n) pattern(p.patterns[i], regs[i], fails, caps)
                }
                if (p.rest != null) {
                    val r = temp()
                    emit(PyOp.LOAD_FAST, v)
                    emit(PyOp.LOAD_FAST, keys)
                    emit(PyOp.COPY_DICT_WITHOUT_KEYS)
                    emit(PyOp.STORE_FAST, r)
                    caps[p.rest] = r
                }
            }
            is MatchClass -> {
                val attrs = temp()
                emit(PyOp.LOAD_FAST, v)
                expr(p.cls)
                // (its keywords are attribute names as they are: never mangled)
                loadConst(PyTuple(p.kwdAttrs.map { PyStr(it) }.toTypedArray()))
                emit(PyOp.MATCH_CLASS, p.patterns.size)
                emit(PyOp.STORE_FAST, attrs)
                emit(PyOp.LOAD_FAST, attrs)
                loadConst(PyNone)
                emit(PyOp.IS)
                fails.add(emit(PyOp.POP_JUMP_IF_TRUE, 0))
                val subs = p.patterns + p.kwdPatterns
                if (subs.isNotEmpty()) {
                    emit(PyOp.LOAD_FAST, attrs)
                    val regs = unpack(subs.size)
                    for (i in subs.indices) pattern(subs[i], regs[i], fails, caps)
                }
            }
            is MatchOr -> {
                val names = boundNames(p.patterns[0])
                val shared = names.associateWith { temp() }
                val succ = ArrayList<Int>()
                for ((i, alt) in p.patterns.withIndex()) {
                    val last = i == p.patterns.size - 1
                    val altFails = if (last) fails else ArrayList()
                    val altCaps = LinkedHashMap<String, Int>()
                    pattern(alt, v, altFails, altCaps)
                    for (n in names) {
                        emit(PyOp.LOAD_FAST, altCaps[n]!!)
                        emit(PyOp.STORE_FAST, shared[n]!!)
                    }
                    if (!last) {
                        succ.add(emit(PyOp.JUMP, 0))
                        for (f in altFails) patch(f, code.size)
                    }
                }
                for (s in succ) patch(s, code.size)
                caps.putAll(shared)
            }
            is MatchStar -> throw PySyntaxError("can't use starred expression here", p.line, p.col)
        }
    }

    /**
     * `from m import a, b as c` (and `*`, at a module's level only): the module once (its fromlist the names), each
     * name its attribute or its submodule. `from __future__` only at the start, of the features CPython knows.
     */
    private fun importFrom(s: ImportFrom) {
        if (s.module == "__future__" && s.level == 0) {
            for (a in s.names) {
                if (a.name == "braces") throw PySyntaxError("not a chance", s.line, s.col)
                if (a.name !in PyImport.FUTURES) throw PySyntaxError("future feature ${a.name} is not defined", s.line, s.col)
            }
        }
        val star = s.names.size == 1 && s.names[0].name == "*"
        if (star && !module) throw PySyntaxError("import * only allowed at module level", s.line, s.col)
        loadConst(s.level.toLong())
        loadConst(PyTuple(s.names.map { PyStr(it.name) }.toTypedArray()))
        emit(PyOp.IMPORT_NAME, name(s.module ?: ""))
        if (star) {
            emit(PyOp.IMPORT_STAR)
            return
        }
        val m = temp()
        emit(PyOp.STORE_FAST, m)
        for (a in s.names) {
            emit(PyOp.LOAD_FAST, m)
            emit(PyOp.IMPORT_FROM, name(a.name))
            storeName(a.asname ?: a.name)
        }
    }

    /** A with's __exit__ (bound, in register [exit]) called with three Nones (an async with's awaited), its result dropped. */
    private fun exitWith(exit: Int, async: Boolean) {
        emit(PyOp.LOAD_FAST, exit)
        loadConst(PyNone)
        loadConst(PyNone)
        loadConst(PyNone)
        emit(PyOp.CALL, 3)
        if (async) await(2)
        emit(PyOp.POP)
    }

    /**
     * `await` of the value on the stack ([k] GET_AWAITABLE's: 1, 2 an async with's __aenter__'s, __aexit__'s): its
     * iterator stepped ([sendLoop]), its result left.
     */
    private fun await(k: Int) {
        emit(PyOp.GET_AWAITABLE, k)
        sendLoop()
    }

    /**
     * The loop of `yield from` and await over the iterator on the stack: stepped with what the frame is resumed with
     * (SEND), each value it yields yielded on; its result left on the stack.
     */
    private fun sendLoop() {
        val it = temp()
        emit(PyOp.STORE_FAST, it)
        loadConst(PyNone)
        val top = code.size
        val send = emit(PyOp.SEND, it, 0)
        emit(PyOp.YIELD)
        emit(PyOp.JUMP, top)
        patch(send, code.size)
    }

    /**
     * `async for`: its iterable's __aiter__, then each value its __anext__ gives awaited ([asyncNext]) until that
     * raises StopAsyncIteration ([asyncEnd]); the target, the body and the else clause as a for's.
     */
    private fun asyncFor(s: For) {
        expr(s.iter)
        emit(PyOp.GET_AITER)
        val it = temp()
        emit(PyOp.STORE_FAST, it)
        val top = code.size
        val next = asyncNext(it)
        store(s.target)
        val loop = Loop(top)
        fblocks.add(loop)
        block(s.body)
        fblocks.removeAt(fblocks.size - 1)
        emit(PyOp.JUMP, top)
        asyncEnd(next, s.iter.line)
        block(s.orelse)
        for (b in loop.breaks) patch(b, code.size)
    }

    /**
     * An async for's next value, of the asynchronous iterator in register [it], awaited: in a range (only that, not the
     * target or the body) whose handler, after the loop's jump back ([asyncEnd]), ends the loop at StopAsyncIteration.
     */
    private fun asyncNext(it: Int): Range {
        val range = Range()
        range.begin()
        emit(PyOp.LOAD_FAST, it)
        emit(PyOp.GET_ANEXT)
        sendLoop()
        range.end()
        return range
    }

    /** The handler of [next] ([asyncNext]'s): END_ASYNC_FOR, raising again what is not a StopAsyncIteration. */
    private fun asyncEnd(next: Range, l: Int) {
        next.handle(code.size)
        lineHere(l)
        emit(PyOp.END_ASYNC_FOR)
    }

    /** `name = None; del name`: an except clause's name, after its body (the exception's traceback not kept alive). */
    private fun clearName(n: String) {
        loadConst(PyNone)
        storeName(n)
        deleteName(n)
    }

    /** A line for the code from here (the handlers' code: an except clause's). */
    private fun lineHere(l: Int) {
        line = l
        lines.add(code.size)
        lines.add(l)
    }

    /**
     * `try`: its body in a range whose handler is the except clauses' (or the finally's); with a finally, the
     * try-except-else inside a range whose handler runs the finally body and raises again, the body copied for the
     * normal way out and for each return, break and continue through it ([unwind]).
     */
    private fun tryStmt(s: Try) {
        if (s.finalbody.isEmpty()) {
            if (s.star) tryStar(s) else tryExcept(s)
            return
        }
        val range = Range()
        fblocks.add(Finally(range, s.finalbody))
        range.begin()
        if (s.handlers.isEmpty()) block(s.body) else if (s.star) tryStar(s) else tryExcept(s)
        range.end()
        fblocks.removeAt(fblocks.size - 1)
        block(s.finalbody)
        val toEnd = emit(PyOp.JUMP, 0)
        range.handle(code.size)
        handler(s.finalbody.first().line) { exc, prev, cleanup ->
            fblocks.add(Handler(cleanup, prev))
            block(s.finalbody)
            fblocks.removeAt(fblocks.size - 1)
            emit(PyOp.LOAD_FAST, prev)
            emit(PyOp.POP_EXCEPT)
            emit(PyOp.LOAD_FAST, exc)
            emit(PyOp.RERAISE)
        }
        patch(toEnd, code.size)
    }

    /** try-except-else: the body's exceptions to the clauses, tried in order; none matching, raised again. */
    private fun tryExcept(s: Try) {
        for ((k, h) in s.handlers.withIndex()) {
            if (h.type == null && k < s.handlers.size - 1) throw PySyntaxError("default 'except:' must be last", h.line, h.col)
        }
        val range = Range()
        fblocks.add(TryBody(range))
        range.begin()
        block(s.body)
        range.end()
        fblocks.removeAt(fblocks.size - 1)
        block(s.orelse)
        val toEnd = emit(PyOp.JUMP, 0)
        range.handle(code.size)
        val ends = ArrayList<Int>()
        handler(s.handlers.first().line) { exc, prev, cleanup ->
            for (h in s.handlers) {
                lineHere(h.line)
                var next = -1
                if (h.type != null) {
                    emit(PyOp.LOAD_FAST, exc)
                    expr(h.type)
                    emit(PyOp.CHECK_EXC_MATCH)
                    next = emit(PyOp.POP_JUMP_IF_FALSE, 0)
                }
                if (h.name != null) {
                    emit(PyOp.LOAD_FAST, exc)
                    storeName(h.name)
                    val named = Range()
                    fblocks.add(Handler(cleanup, prev))
                    fblocks.add(HandlerName(named, h.name))
                    named.begin()
                    block(h.body)
                    named.end()
                    fblocks.removeAt(fblocks.size - 1)
                    fblocks.removeAt(fblocks.size - 1)
                    clearName(h.name)
                    emit(PyOp.LOAD_FAST, prev)
                    emit(PyOp.POP_EXCEPT)
                    ends.add(emit(PyOp.JUMP, 0))
                    // (leaving the body by an exception: the name deleted too, then on to the cleanup)
                    named.handle(code.size)
                    clearName(h.name)
                    emit(PyOp.RERAISE)
                } else {
                    fblocks.add(Handler(cleanup, prev))
                    block(h.body)
                    fblocks.removeAt(fblocks.size - 1)
                    emit(PyOp.LOAD_FAST, prev)
                    emit(PyOp.POP_EXCEPT)
                    ends.add(emit(PyOp.JUMP, 0))
                }
                if (next >= 0) patch(next, code.size)
            }
            // no clause took it
            emit(PyOp.LOAD_FAST, exc)
            emit(PyOp.RERAISE)
        }
        for (e in ends) patch(e, code.size)
        patch(toEnd, code.size)
    }

    /**
     * try-except* (PEP 654): each clause takes the part of the exception (a group, or a naked one in a group of its
     * own) its types match, from what the clauses before left (EXC_STAR_MATCH); what its body raises is noted (raised
     * again or anew: EXC_STAR_RAISED) and the next clause tried; after the last, what is left and what they raised is
     * raised (EXC_STAR_END), or nothing.
     */
    private fun tryStar(s: Try) {
        val range = Range()
        fblocks.add(TryBody(range))
        range.begin()
        block(s.body)
        range.end()
        fblocks.removeAt(fblocks.size - 1)
        block(s.orelse)
        val toEnd = emit(PyOp.JUMP, 0)
        range.handle(code.size)
        var handled = -1
        handler(s.handlers.first().line) { exc, prev, _ ->
            val st = temp()
            emit(PyOp.LOAD_FAST, exc)
            emit(PyOp.EXC_STAR_BEGIN)
            emit(PyOp.STORE_FAST, st)
            for (h in s.handlers) {
                lineHere(h.line)
                val m = temp()
                val before = temp()
                emit(PyOp.LOAD_FAST, st)
                expr(h.type!!)
                emit(PyOp.EXC_STAR_MATCH)
                emit(PyOp.STORE_FAST, m)
                emit(PyOp.LOAD_FAST, m)
                loadConst(PyNone)
                emit(PyOp.IS)
                val next = emit(PyOp.POP_JUMP_IF_TRUE, 0)
                if (h.name != null) {
                    emit(PyOp.LOAD_FAST, m)
                    storeName(h.name)
                }
                // (the match is the exception handled in the body)
                emit(PyOp.LOAD_FAST, m)
                emit(PyOp.PUSH_EXC_INFO)
                emit(PyOp.STORE_FAST, before)
                val body = Range()
                fblocks.add(StarBody(body))
                body.begin()
                block(h.body)
                body.end()
                fblocks.removeAt(fblocks.size - 1)
                emit(PyOp.LOAD_FAST, before)
                emit(PyOp.POP_EXCEPT)
                if (h.name != null) clearName(h.name)
                val done = emit(PyOp.JUMP, 0)
                // (an exception left the body: noted, on to the next clause)
                body.handle(code.size)
                emit(PyOp.LOAD_FAST, before)
                emit(PyOp.POP_EXCEPT)
                emit(PyOp.LOAD_FAST, st)
                emit(PyOp.SWAP)
                emit(PyOp.EXC_STAR_RAISED)
                if (h.name != null) clearName(h.name)
                patch(done, code.size)
                patch(next, code.size)
            }
            emit(PyOp.LOAD_FAST, st)
            emit(PyOp.EXC_STAR_END)
            emit(PyOp.DUP)
            loadConst(PyNone)
            emit(PyOp.IS)
            val none = emit(PyOp.POP_JUMP_IF_TRUE, 0)
            emit(PyOp.RERAISE)
            patch(none, code.size)
            emit(PyOp.POP)
            emit(PyOp.LOAD_FAST, prev)
            emit(PyOp.POP_EXCEPT)
            handled = emit(PyOp.JUMP, 0)
        }
        patch(handled, code.size)
        patch(toEnd, code.size)
    }

    /**
     * A handler's code, entered with the exception on the stack: kept in a register ([body]'s exc), made the exception
     * handled (the one before in [body]'s prev), [body] in a range whose handler (the cleanup) puts the one before
     * back and raises what left it again.
     */
    private fun handler(l: Int, body: (exc: Int, prev: Int, cleanup: Range) -> Unit) {
        lineHere(l)
        val exc = temp()
        val prev = temp()
        emit(PyOp.STORE_FAST, exc)
        emit(PyOp.LOAD_FAST, exc)
        emit(PyOp.PUSH_EXC_INFO)
        emit(PyOp.STORE_FAST, prev)
        val cleanup = Range()
        cleanup.begin()
        body(exc, prev, cleanup)
        cleanup.end()
        cleanup.handle(code.size)
        // (the exception on the stack; the one before handled again, it is raised again)
        emit(PyOp.LOAD_FAST, prev)
        emit(PyOp.POP_EXCEPT)
        emit(PyOp.RERAISE)
    }

    /**
     * A function (or a lambda, [body] a return of its expression): its defaults, keyword-only defaults and the cells
     * of its free variables evaluated here, then the function made of its code.
     */
    private fun makeFunction(node: Node, fname: String, args: Arguments, body: List<Stmt>, passed: Boolean = false) {
        val child = st.scopes[node]!!
        for (a in args.all()) forbidden(a.arg)
        var flags = 0
        if (args.defaults.isNotEmpty()) {
            if (passed) emit(PyOp.LOAD_FAST, locals[".defaults"]!!) else defaultsTuple(args)
            flags = flags or 1
        }
        if (args.kwDefaults.any { it != null }) {
            if (passed) emit(PyOp.LOAD_FAST, locals[".kwdefaults"]!!) else kwDefaultsDict(args)
            flags = flags or 2
        }
        if (child.frees.isNotEmpty()) {
            closure(child)
            flags = flags or 8
        }
        // its annotations' __annotate__ (CPython's order: positional, positional-only, *args, keyword-only, **kwargs, the return)
        val ann = st.functionAnnotations[node]
        if (ann != null) {
            val f = node as FunctionDef
            val entries = (args.args + args.posonlyargs + listOfNotNull(args.vararg) + args.kwonlyargs + listOfNotNull(args.kwarg))
                .mapNotNull { p -> p.annotation?.let { Deferred(mangle(p.arg), it, -1, it.line) } } +
                listOfNotNull(f.returns?.let { Deferred("return", it, -1, it.line) })
            val acb = child(ann, "__annotate__").compileAnnotate(entries, node.line)
            closure(ann)
            emit(PyOp.MAKE_FUNCTION, const(acb))
            if (ann.frees.isNotEmpty()) emit(PyOp.SET_FUNCTION_ATTRIBUTE, 8)
            flags = flags or 16
        }
        val cb = child(child, fname).also { it.firstLine = node.line }.compileBody(body, args)
        emit(PyOp.MAKE_FUNCTION, const(cb))
        for (flag in intArrayOf(16, 8, 2, 1)) if (flags and flag != 0) emit(PyOp.SET_FUNCTION_ATTRIBUTE, flag)
    }

    private fun defaultsTuple(args: Arguments) {
        for (d in args.defaults) expr(d)
        emit(PyOp.BUILD_TUPLE, args.defaults.size)
    }

    private fun kwDefaultsDict(args: Arguments) {
        val kwd = args.kwonlyargs.indices.filter { args.kwDefaults[it] != null }
        for (i in kwd) {
            // (its parameter's name, mangled as the parameter is)
            loadConst(PyStr(mangle(args.kwonlyargs[i].arg)))
            expr(args.kwDefaults[i]!!)
        }
        emit(PyOp.BUILD_MAP, kwd.size)
    }

    /**
     * A generic def or `type` alias (PEP 695): its `<generic parameters of [name]>` function made and called (a def's
     * defaults evaluated here, its arguments), which makes the type parameters and then the function or alias.
     */
    private fun generic(node: Stmt, name: String) {
        val tps = st.typeParamScopes[node]!!
        val c = child(tps, "<generic parameters of $name>")
        c.firstLine = node.line
        val cb = c.compileGeneric(node)
        closure(tps)
        emit(PyOp.MAKE_FUNCTION, const(cb))
        if (tps.frees.isNotEmpty()) emit(PyOp.SET_FUNCTION_ATTRIBUTE, 8)
        var n = 0
        if (node is FunctionDef) {
            if (node.args.defaults.isNotEmpty()) {
                defaultsTuple(node.args)
                n++
            }
            if (node.args.kwDefaults.any { it != null }) {
                kwDefaultsDict(node.args)
                n++
            }
        }
        emit(PyOp.CALL, n)
    }

    /** A `<generic parameters of X>`'s code (this compiler's scope its TYPE_PARAMS scope). */
    private fun compileGeneric(node: Stmt): CodeUnit {
        prologue()
        lineHere(node.line)
        when (node) {
            is FunctionDef -> {
                val params = typeParams(node.typeParams)
                makeFunction(node, node.name, node.args, node.body, passed = true)
                emit(PyOp.LOAD_FAST, params)
                emit(PyOp.SWAP)
                emit(PyOp.SET_FUNCTION_ATTRIBUTE, 32)
            }
            is ClassDef -> {
                val params = typeParams(node.typeParams)
                emit(PyOp.LOAD_FAST, params)
                storeName(".type_params")
                emit(PyOp.LOAD_FAST, params)
                emit(PyOp.SUBSCRIPT_GENERIC)
                storeName(".generic_base")
                buildClass(node, true)
            }
            is TypeAlias -> {
                loadConst(PyStr(node.name.id))
                emit(PyOp.LOAD_FAST, typeParams(node.typeParams))
                lazyFunction(st.aliasScopes[node]!!, node.value, false)
                emit(PyOp.MAKE_TYPEALIAS)
            }
            else -> throw IllegalStateException("not a generic: $node")
        }
        emit(PyOp.RETURN)
        val a = scope.params.map { Arg(it, null) }
        return unit(null, Arguments(a, emptyList(), null, emptyList(), emptyList(), null, emptyList()))
    }

    /**
     * The type parameters made, each stored in its name, their tuple in a register (returned): a bound,
     * constraints or default each a lazily evaluated function; CPython's error for a default missing after one.
     */
    private fun typeParams(tps: List<TypeParam>): Int {
        var seenDefault = false
        for (tp in tps) {
            lineHere(tp.line)
            loadConst(PyStr(tp.name))
            when (tp) {
                is TypeVar -> if (tp.bound != null) {
                    lazyFunction(st.boundScopes[tp]!!, tp.bound, false)
                    emit(PyOp.MAKE_TYPEVAR_BOUND, if (tp.bound is TupleExpr) 2 else 1)
                } else emit(PyOp.MAKE_TYPEVAR, 0)
                is TypeVarTuple -> emit(PyOp.MAKE_TYPEVAR, 1)
                is ParamSpec -> emit(PyOp.MAKE_TYPEVAR, 2)
            }
            val dflt = when (tp) {
                is TypeVar -> tp.defaultValue
                is TypeVarTuple -> tp.defaultValue
                is ParamSpec -> tp.defaultValue
            }
            if (dflt != null) {
                seenDefault = true
                lazyFunction(st.defaultScopes[tp]!!, dflt, tp is TypeVarTuple)
                emit(PyOp.SET_TYPEPARAM_DEFAULT)
            } else if (seenDefault) {
                throw PySyntaxError("non-default type parameter '${tp.name}' follows default type parameter", tp.line, tp.col)
            }
            emit(PyOp.DUP)
            storeName(tp.name)
        }
        emit(PyOp.BUILD_TUPLE, tps.size)
        val r = temp()
        emit(PyOp.STORE_FAST, r)
        return r
    }

    /** A lazily evaluated expression's function (a bound, a default, an alias's value), its format defaulting to 1. */
    private fun lazyFunction(s: Scope, e: Expr, starred: Boolean) {
        loadConst(PyTuple(arrayOf(1L)))
        val cb = child(s, s.name).compileLazy(e, starred)
        closure(s)
        emit(PyOp.MAKE_FUNCTION, const(cb))
        if (s.frees.isNotEmpty()) emit(PyOp.SET_FUNCTION_ATTRIBUTE, 8)
        emit(PyOp.SET_FUNCTION_ATTRIBUTE, 1)
    }

    private fun compileLazy(e: Expr, starred: Boolean): CodeUnit {
        firstLine = e.line
        prologue()
        lineHere(e.line)
        emit(PyOp.LOAD_FAST, locals[".format"]!!)
        emit(PyOp.ANNOTATE_CHECK)
        if (starred && e is Starred) {
            expr(e.value)
            emit(PyOp.UNPACK, 1)
        } else expr(e)
        emit(PyOp.RETURN)
        return unit(null, LAZY_ARGS)
    }

    /** Stores the value on top of the stack to [t]. */
    private fun store(t: Expr) {
        when (t) {
            is Name -> storeName(t.id)
            is Subscript -> {
                expr(t.value)
                expr(t.slice)
                emit(PyOp.STORE_SUBSCR)
            }
            is TupleExpr, is ListExpr -> {
                val elts = if (t is TupleExpr) t.elts else (t as ListExpr).elts
                val star = elts.indexOfFirst { it is Starred }
                if (star < 0) emit(PyOp.UNPACK, elts.size)
                else emit(PyOp.UNPACK_EX, star, elts.size - star - 1)
                for (e in elts) store(if (e is Starred) e.value else e)
            }
            is Attribute -> {
                forbidden(t.attr)
                if ((t.value as? Name)?.id == "self") (if (isClass) this else outerClass)?.staticAttributes?.add(t.attr)
                expr(t.value)
                emit(PyOp.STORE_ATTR, name(mangle(t.attr)))
            }
            is Starred -> throw PySyntaxError("starred assignment target must be in a list or tuple", t.line, t.col)
            else -> unsupported("assignment to this target", t)
        }
    }

    private fun delete(t: Expr) {
        when (t) {
            is Name -> deleteName(t.id)
            is Subscript -> {
                expr(t.value)
                expr(t.slice)
                emit(PyOp.DELETE_SUBSCR)
            }
            is Attribute -> {
                forbidden(t.attr, del = true)
                expr(t.value)
                emit(PyOp.DELETE_ATTR, name(mangle(t.attr)))
            }
            is TupleExpr -> t.elts.forEach { delete(it) }
            is ListExpr -> t.elts.forEach { delete(it) }
            else -> throw PySyntaxError("cannot delete expression", t.line, t.col)
        }
    }

    private fun augAssign(s: AugAssign) {
        val op = binaryOp(s.op, s)
        when (val t = s.target) {
            is Name -> {
                loadName(t.id)
                expr(s.value)
                emit(PyOp.INPLACE, op)
                storeName(t.id)
            }
            is Subscript -> {
                // obj and key once: in temporaries
                val o = temp()
                val k = temp()
                expr(t.value)
                emit(PyOp.STORE_FAST, o)
                expr(t.slice)
                emit(PyOp.STORE_FAST, k)
                emit(PyOp.LOAD_FAST, o)
                emit(PyOp.LOAD_FAST, k)
                emit(PyOp.SUBSCR)
                expr(s.value)
                emit(PyOp.INPLACE, op)
                emit(PyOp.LOAD_FAST, o)
                emit(PyOp.LOAD_FAST, k)
                emit(PyOp.STORE_SUBSCR)
            }
            is Attribute -> {
                // obj once: in a temporary
                val o = temp()
                expr(t.value)
                emit(PyOp.STORE_FAST, o)
                forbidden(t.attr)
                emit(PyOp.LOAD_FAST, o)
                emit(PyOp.LOAD_ATTR, name(mangle(t.attr)))
                expr(s.value)
                emit(PyOp.INPLACE, op)
                emit(PyOp.LOAD_FAST, o)
                emit(PyOp.STORE_ATTR, name(mangle(t.attr)))
            }
            else -> unsupported("augmented assignment to this target", s)
        }
    }

    /**
     * `class`: __build_class__(the body as a function, its name, *bases, **keywords), the decorators applied, the
     * class stored under its name.
     */
    private fun classDef(s: ClassDef) {
        for (d in s.decoratorList) expr(d)
        if (s.typeParams.isNotEmpty()) generic(s, s.name) else buildClass(s, false)
        repeat(s.decoratorList.size) { emit(PyOp.CALL, 1) }
        storeName(s.name)
    }

    /** __build_class__(the body as a function, its name, *bases, **keywords); a generic class's Generic[...] base last. */
    private fun buildClass(s: ClassDef, genericBase: Boolean) {
        emit(PyOp.LOAD_BUILD_CLASS)
        val child = st.scopes[s]!!
        if (child.frees.isNotEmpty()) {
            for (n in child.frees) emit(PyOp.LOAD_FAST, locals[n] ?: throw IllegalStateException("no cell for $n in $name"))
            emit(PyOp.BUILD_TUPLE, child.frees.size)
        }
        val cb = child(child, s.name).also { it.firstLine = s.decoratorList.firstOrNull()?.line ?: s.line }.compileBody(s.body, null)
        emit(PyOp.MAKE_FUNCTION, const(cb))
        if (child.frees.isNotEmpty()) emit(PyOp.SET_FUNCTION_ATTRIBUTE, 8)
        loadConst(PyStr(s.name))
        val bases = if (!genericBase) s.bases else s.bases + Name(".generic_base").also { it.line = s.line; it.col = s.col }
        callArgs(2, bases, s.keywords)
    }

    // ------------------------------------------------------------------ expressions

    private fun binaryOp(op: OpKind, n: Node): Int = when (op) {
        OpKind.Add -> PyOp.ADD
        OpKind.Sub -> PyOp.SUB
        OpKind.Mult -> PyOp.MUL
        OpKind.Div -> PyOp.TRUEDIV
        OpKind.FloorDiv -> PyOp.FLOORDIV
        OpKind.Mod -> PyOp.MOD
        OpKind.Pow -> PyOp.POW
        OpKind.LShift -> PyOp.LSHIFT
        OpKind.RShift -> PyOp.RSHIFT
        OpKind.BitAnd -> PyOp.BITAND
        OpKind.BitOr -> PyOp.BITOR
        OpKind.BitXor -> PyOp.BITXOR
        OpKind.MatMult -> PyOp.MATMUL
    }

    private fun compareOp(op: CmpOpKind): Int = when (op) {
        CmpOpKind.Lt -> PyOp.LT
        CmpOpKind.LtE -> PyOp.LE
        CmpOpKind.Gt -> PyOp.GT
        CmpOpKind.GtE -> PyOp.GE
        CmpOpKind.Eq -> PyOp.EQ
        CmpOpKind.NotEq -> PyOp.NE
        CmpOpKind.Is -> PyOp.IS
        CmpOpKind.IsNot -> PyOp.IS_NOT
        CmpOpKind.In -> PyOp.IN
        CmpOpKind.NotIn -> PyOp.NOT_IN
    }

    private fun expr(e: Expr) {
        when (e) {
            is Constant -> loadConst(e.value)
            is Name -> loadName(e.id)
            is BinOp -> {
                expr(e.left)
                expr(e.right)
                emit(binaryOp(e.op, e))
            }
            is UnaryOp -> {
                expr(e.operand)
                emit(when (e.op) {
                    UnaryOpKind.Not -> PyOp.NOT
                    UnaryOpKind.USub -> PyOp.NEG
                    UnaryOpKind.UAdd -> PyOp.POS
                    UnaryOpKind.Invert -> PyOp.INVERT
                })
            }
            is BoolOp -> {
                // a or b: a where it is true, else b (a and b: a where it is false)
                val jumps = ArrayList<Int>()
                for ((k, v) in e.values.withIndex()) {
                    expr(v)
                    if (k < e.values.size - 1) {
                        emit(PyOp.DUP)
                        jumps.add(emit(if (e.op == BoolOpKind.Or) PyOp.POP_JUMP_IF_TRUE else PyOp.POP_JUMP_IF_FALSE, 0))
                        emit(PyOp.POP)
                    }
                }
                for (j in jumps) patch(j, code.size)
            }
            is Compare -> {
                expr(e.left)
                if (e.ops.size == 1) {
                    expr(e.comparators[0])
                    emit(compareOp(e.ops[0]))
                    return
                }
                // a < b < c: b once, in a temporary; the first false comparison is the result
                val mid = temp()
                val jumps = ArrayList<Int>()
                for ((k, op) in e.ops.withIndex()) {
                    expr(e.comparators[k])
                    val last = k == e.ops.size - 1
                    if (!last) {
                        emit(PyOp.STORE_FAST, mid)
                        emit(PyOp.LOAD_FAST, mid)
                    }
                    emit(compareOp(op))
                    if (!last) {
                        emit(PyOp.DUP)
                        jumps.add(emit(PyOp.POP_JUMP_IF_FALSE, 0))
                        emit(PyOp.POP)
                        emit(PyOp.LOAD_FAST, mid)
                    }
                }
                for (j in jumps) patch(j, code.size)
            }
            is IfExp -> {
                expr(e.test)
                val toElse = emit(PyOp.POP_JUMP_IF_FALSE, 0)
                expr(e.body)
                val toEnd = emit(PyOp.JUMP, 0)
                patch(toElse, code.size)
                expr(e.orelse)
                patch(toEnd, code.size)
            }
            is NamedExpr -> {
                expr(e.value)
                emit(PyOp.DUP)
                storeName(e.target.id)
            }
            is Lambda -> makeFunction(e, "<lambda>", e.args, listOf(Return(e.body).also { it.line = e.line; it.col = e.col }))
            is Call -> call(e)
            is Attribute -> {
                expr(e.value)
                emit(PyOp.LOAD_ATTR, name(mangle(e.attr)))
            }
            is Subscript -> {
                expr(e.value)
                expr(e.slice)
                emit(PyOp.SUBSCR)
            }
            is ListExpr -> {
                if (e.elts.none { it is Starred }) {
                    for (x in e.elts) expr(x)
                    emit(PyOp.BUILD_LIST, e.elts.size)
                } else unpackingList(e.elts)
            }
            is TupleExpr -> {
                if (e.elts.none { it is Starred }) {
                    for (x in e.elts) expr(x)
                    emit(PyOp.BUILD_TUPLE, e.elts.size)
                } else {
                    unpackingList(e.elts)
                    emit(PyOp.LIST_TO_TUPLE)
                }
            }
            is DictExpr -> {
                val first = e.keys.indexOfFirst { it == null }.let { if (it < 0) e.keys.size else it }
                for (k in 0 until first) {
                    expr(e.keys[k]!!)
                    expr(e.values[k])
                }
                emit(PyOp.BUILD_MAP, first)
                for (k in first until e.keys.size) {
                    val key = e.keys[k]
                    if (key == null) {
                        expr(e.values[k])
                        emit(PyOp.DICT_UPDATE)
                    } else {
                        expr(key)
                        expr(e.values[k])
                        emit(PyOp.MAP_ADD)
                    }
                }
            }
            is Starred -> throw PySyntaxError("can't use starred expression here", e.line, e.col)
            is SetExpr -> if (e.elts.any { it is Starred }) {
                emit(PyOp.BUILD_SET, 0)
                for (x in e.elts) if (x is Starred) {
                    expr(x.value)
                    emit(PyOp.SET_UPDATE)
                } else {
                    expr(x)
                    emit(PyOp.SET_ADD)
                }
            } else {
                for (x in e.elts) expr(x)
                emit(PyOp.BUILD_SET, e.elts.size)
            }
            is SetComp -> comprehension(e)
            is ListComp, is DictComp, is GeneratorExp -> comprehension(e)
            is Yield -> {
                if (e.value == null) loadConst(PyNone) else expr(e.value)
                // (an async generator's yielded value told from what its awaits pass on)
                if (scope.isCoroutine) emit(PyOp.ASYNC_GEN_WRAP)
                emit(PyOp.YIELD)
                emit(PyOp.RESUME)
            }
            is YieldFrom -> {
                if (scope.isCoroutine) throw PySyntaxError("'yield from' inside async function", e.line, e.col)
                expr(e.value)
                emit(PyOp.GET_YIELD_FROM_ITER)
                sendLoop()
            }
            is Await -> {
                expr(e.value)
                await(0)
            }
            is JoinedStr -> fstring(e)
            is FormattedValue -> formatted(e)
            is TemplateStr -> template(e)
            is Interpolation -> throw IllegalStateException("an interpolation outside a t-string")
            is Slice -> {
                if (e.lower == null) loadConst(PyNone) else expr(e.lower)
                if (e.upper == null) loadConst(PyNone) else expr(e.upper)
                if (e.step != null) {
                    expr(e.step)
                    emit(PyOp.BUILD_SLICE, 3)
                } else emit(PyOp.BUILD_SLICE, 2)
            }
        }
    }

    /** A list of [elts], `*x` among them: the items before the first `*x` built, the rest appended or extended. */
    private fun unpackingList(elts: List<Expr>) {
        val first = elts.indexOfFirst { it is Starred }.let { if (it < 0) elts.size else it }
        for (k in 0 until first) expr(elts[k])
        emit(PyOp.BUILD_LIST, first)
        for (k in first until elts.size) {
            val x = elts[k]
            if (x is Starred) {
                expr(x.value)
                emit(PyOp.LIST_EXTEND)
            } else {
                expr(x)
                emit(PyOp.LIST_APPEND)
            }
        }
    }

    /**
     * A call: CALL with positional arguments, CALL_KW with keyword arguments too (their names a tuple constant),
     * CALL_EX with `*` or `**` (the positional arguments a list, the keyword arguments a list of dicts).
     */
    private fun call(e: Call) {
        // super() in a method: super(__class__, its first parameter)
        if (e.func is Name && (e.func as Name).id == "super" && e.args.isEmpty() && e.keywords.isEmpty() &&
            scope.kind("super") == Scope.Kind.GLOBAL_IMPLICIT && scope.type == Scope.Type.FUNCTION &&
            scope.params.isNotEmpty() && scope.kind("__class__") == Scope.Kind.FREE) {
            emit(PyOp.LOAD_GLOBAL, name("super"))
            loadName("__class__")
            loadName(scope.params[0])
            emit(PyOp.CALL, 2)
            return
        }
        // locals() and vars() in a function, the builtins: its registers' values (a frame of compiled code has none to read)
        val fname = (e.func as? Name)?.id
        if ((fname == "locals" || fname == "vars") && e.args.isEmpty() && e.keywords.isEmpty() && !namespace &&
            scope.kind(fname) == Scope.Kind.GLOBAL_IMPLICIT) {
            localsDict()
            return
        }
        // (exec(), eval() and dir() in a function, the builtins: its locals' snapshot theirs where they are given none)
        if ((fname == "exec" || fname == "eval" || fname == "dir" && e.args.isEmpty()) && !namespace && scope.kind(fname) == Scope.Kind.GLOBAL_IMPLICIT) {
            localsDict()
            emit(PyOp.SET_EVAL_LOCALS)
        }
        expr(e.func)
        callArgs(0, e.args, e.keywords)
    }

    /** A call's arguments, after the callable and [pushed] arguments already on the stack, and the call. */
    private fun callArgs(pushed: Int, args: List<Expr>, keywords: List<Keyword>) {
        val seen = HashSet<String>()
        for (k in keywords) if (k.arg != null && !seen.add(k.arg)) throw PySyntaxError("keyword argument repeated: ${k.arg}", k.line, k.col)
        for (k in keywords) k.arg?.let { forbidden(it) }
        val unpacking = args.any { it is Starred } || keywords.any { it.arg == null }
        if (!unpacking) {
            for (a in args) expr(a)
            if (keywords.isEmpty()) {
                emit(PyOp.CALL, pushed + args.size)
                return
            }
            for (k in keywords) expr(k.value)
            loadConst(PyTuple(Array(keywords.size) { PyStr(keywords[it].arg!!) }))
            emit(PyOp.CALL_KW, pushed + args.size + keywords.size)
            return
        }
        val first = args.indexOfFirst { it is Starred }.let { if (it < 0) args.size else it }
        for (k in 0 until first) expr(args[k])
        emit(PyOp.BUILD_LIST, pushed + first)
        for (k in first until args.size) {
            val x = args[k]
            if (x is Starred) {
                expr(x.value)
                emit(PyOp.WRAP_STAR)
            } else expr(x)
            emit(PyOp.LIST_APPEND)
        }
        if (keywords.isEmpty()) {
            emit(PyOp.CALL_EX, 0)
            return
        }
        emit(PyOp.BUILD_LIST, 0)
        var k = 0
        while (k < keywords.size) {
            val kw = keywords[k]
            if (kw.arg == null) {
                expr(kw.value)
                k++
            } else {
                var n = 0
                while (k < keywords.size && keywords[k].arg != null) {
                    loadConst(PyStr(keywords[k].arg!!))
                    expr(keywords[k].value)
                    k++
                    n++
                }
                emit(PyOp.BUILD_MAP, n)
            }
            emit(PyOp.LIST_APPEND)
        }
        emit(PyOp.CALL_EX, 1)
    }

    // ------------------------------------------------------------------ after emitting

    /** The successors of the instruction at [pc] of [bc]. */
    private fun successors(bc: IntArray, pc: Int): IntArray {
        val op = bc[pc]
        val next = pc + PyOp.length(op)
        return when (op) {
            PyOp.JUMP -> intArrayOf(bc[pc + 1])
            PyOp.POP_JUMP_IF_FALSE, PyOp.POP_JUMP_IF_TRUE -> intArrayOf(bc[pc + 1], next)
            PyOp.FOR_ITER -> intArrayOf(bc[pc + 3], next)
            PyOp.SEND -> intArrayOf(bc[pc + 2], next)
            PyOp.RETURN, PyOp.RAISE, PyOp.RERAISE -> IntArray(0)
            else -> intArrayOf(next)
        }
    }

    /** The innermost handler whose range covers [pc], or -1. */
    private fun handlerOf(pc: Int): Int {
        for (k in 0 until handlerTable.size step 4) if (pc >= handlerTable[k] && pc < handlerTable[k + 1]) return handlerTable[k + 2]
        return -1
    }

    /** The deepest the operand stack gets (every path reaches an instruction at one depth). */
    private fun maxStack(bc: IntArray): Int {
        val depth = IntArray(bc.size) { -1 }
        val work = ArrayDeque<Int>()
        depth[0] = 0
        work.add(0)
        var max = 0
        // (a handler starts with its depth and the exception)
        for (k in 0 until handlerTable.size step 4) {
            val h = handlerTable[k + 2]
            val d = handlerTable[k + 3] + 1
            if (depth[h] < 0) {
                depth[h] = d
                work.add(h)
                if (d > max) max = d
            } else if (depth[h] != d) throw IllegalStateException("handler depth differs at $h in $name")
        }
        while (work.isNotEmpty()) {
            val pc = work.removeLast()
            val op = bc[pc]
            val n = PyOp.operands[op]
            val d = depth[pc] + PyOp.stackEffect(op, if (n > 0) bc[pc + 1] else 0, if (n > 1) bc[pc + 2] else 0)
            if (d < 0) throw IllegalStateException("operand stack underflow at $pc in $name")
            if (d > max) max = d
            for (s in successors(bc, pc)) {
                if (depth[s] < 0) {
                    depth[s] = d
                    work.add(s)
                } else if (depth[s] != d) throw IllegalStateException("operand stack depth differs at $s in $name")
            }
        }
        return max
    }

    /**
     * Each LOAD_FAST of a local that may have no value there (on some path from the start, nothing stored it, or a
     * del took it) becomes a LOAD_FAST_CHECK, which raises the UnboundLocalError (CPython's compiler does the same): a
     * forward dataflow of the registers that may be unbound, parameters bound at the start.
     */
    private fun markUnbound(bc: IntArray) {
        if (namespace) return
        val n = maxOf(regs, maxRegs)
        val maybe = arrayOfNulls<java.util.BitSet>(bc.size)
        val start = java.util.BitSet(n)
        start.set(scope.params.size, n)
        maybe[0] = start
        val work = ArrayDeque<Int>()
        work.add(0)
        while (work.isNotEmpty()) {
            val pc = work.removeLast()
            // (an exception here reaches the handler with the registers as they are before)
            val hp = handlerOf(pc)
            if (hp >= 0) {
                val cur = maybe[hp]
                if (cur == null) {
                    maybe[hp] = maybe[pc]!!.clone() as java.util.BitSet
                    work.add(hp)
                } else {
                    val before = cur.cardinality()
                    cur.or(maybe[pc]!!)
                    if (cur.cardinality() != before) work.add(hp)
                }
            }
            val out = maybe[pc]!!.clone() as java.util.BitSet
            val op = bc[pc]
            if (op == PyOp.STORE_FAST) out.clear(bc[pc + 1])
            if (op == PyOp.DELETE_FAST) out.set(bc[pc + 1])
            for (s in successors(bc, pc)) {
                // FOR_ITER writes its value register only on the way into the loop's body
                val o = if (op == PyOp.FOR_ITER && s == pc + PyOp.length(op)) (out.clone() as java.util.BitSet).also { it.clear(bc[pc + 2]) } else out
                val cur = maybe[s]
                if (cur == null) {
                    maybe[s] = o.clone() as java.util.BitSet
                    work.add(s)
                } else {
                    val before = cur.cardinality()
                    cur.or(o)
                    if (cur.cardinality() != before) work.add(s)
                }
            }
        }
        var pc = 0
        while (pc < bc.size) {
            val m = maybe[pc]
            if (bc[pc] == PyOp.LOAD_FAST && m != null && m[bc[pc + 1]]) bc[pc] = PyOp.LOAD_FAST_CHECK
            pc += PyOp.length(bc[pc])
        }
    }
}

/** A growable array of ints. */
internal class IntArrayList {
    private var a = IntArray(64)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Int) {
        a[i] = v
    }

    fun toArray(): IntArray = a.copyOf(size)
}
