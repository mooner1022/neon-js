package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeUnit

/**
 * NeonPy's bytecode as Python sees it (docs/PYTHON_PLATFORM.md §12.2: `dis` is NeonPy's own): a code object's
 * co_code, co_consts, co_names, co_lines() and co_positions(), and `_dis`, the instructions `dis` lists. An instruction
 * is an opcode and its operands, one int each; Python sees each int as two bytes (an instruction's offset is twice its
 * pc, f_lasti and tb_lasti such offsets), so `co_positions()` gives one position for each int.
 */
object PyDis {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** The opcodes whose operand is a name, constants[k]. */
    private val NAME_OPS = setOf(PyOp.LOAD_GLOBAL, PyOp.STORE_GLOBAL, PyOp.DELETE_GLOBAL, PyOp.LOAD_ATTR, PyOp.STORE_ATTR,
        PyOp.DELETE_ATTR, PyOp.LOAD_NAME, PyOp.STORE_NAME, PyOp.DELETE_NAME, PyOp.IMPORT_NAME, PyOp.IMPORT_FROM,
        PyOp.LOAD_FROM_DICT_OR_GLOBALS, PyOp.LOAD_FROM_DICT_OR_CELL)

    /** The opcodes whose operand is a register (a local). */
    private val LOCAL_OPS = setOf(PyOp.LOAD_FAST, PyOp.STORE_FAST, PyOp.LOAD_FAST_CHECK, PyOp.DELETE_FAST, PyOp.LOAD_FAST_RAW)

    /** The opcodes whose (last) operand is a jump target. */
    private val JUMP_OPS = setOf(PyOp.JUMP, PyOp.POP_JUMP_IF_FALSE, PyOp.POP_JUMP_IF_TRUE, PyOp.FOR_ITER)

    private fun info(cb: CodeUnit) = cb.dialectData as PyCodeInfo

    /** Each instruction's pc, in order. */
    private fun pcs(cb: CodeUnit): List<Int> {
        val out = ArrayList<Int>()
        var pc = 0
        val code = cb.code
        while (pc < code.size) {
            out.add(pc)
            val op = code[pc]
            pc += if (op in 0 until PyOp.COUNT) PyOp.length(op) else 1
        }
        return out
    }

    private fun constant(cb: CodeUnit, k: Int): Any? {
        val v = cb.constants.getOrNull(k)
        return if (v is CodeUnit) PyEval.codeOf(v) else v
    }

    @JvmStatic
    fun codeAttr(c: PyCode, name: String): Any? {
        val cb = c.code
        val info = info(cb)
        return when (name) {
            "co_code" -> PyBytes(ByteArray(cb.code.size * 2).also { b ->
                for ((i, x) in cb.code.withIndex()) {
                    b[2 * i] = x.toByte()
                    b[2 * i + 1] = (x ushr 8).toByte()
                }
            })
            "co_consts" -> {
                val out = ArrayList<Any?>()
                info.doc?.let { out.add(PyStr(it)) }
                for (pc in pcs(cb)) {
                    val op = cb.code[pc]
                    if (op == PyOp.LOAD_CONST || op == PyOp.MAKE_FUNCTION) {
                        val v = constant(cb, cb.code[pc + 1])
                        if (out.none { it === v || it != null && v != null && it.javaClass == v.javaClass && PyRt.eq(it, v) }) out.add(v ?: PyNone)
                    }
                }
                PyTuple(out.toTypedArray())
            }
            "co_names" -> {
                val out = LinkedHashSet<String>()
                for (pc in pcs(cb)) {
                    val op = cb.code[pc]
                    if (op in NAME_OPS) (cb.constants.getOrNull(cb.code[pc + 1]) as? PyStr)?.let { out.add(it.s) }
                }
                PyTuple(out.map { PyStr(it) }.toTypedArray())
            }
            "co_cellvars" -> PyTuple(info.cellNames.map { PyStr(it) }.toTypedArray())
            "co_nlocals" -> info.varnames.size.toLong()
            "co_stacksize" -> cb.maxStack.toLong()
            "co_linetable", "co_exceptiontable", "co_lnotab" -> PyBytes(ByteArray(0))
            "co_lines" -> PyBuiltin("co_lines") { _, _ -> lines(cb) }
            "co_positions" -> PyBuiltin("co_positions") { _, _ -> positions(cb) }
            "replace", "__replace__" -> PyBuiltin(name, { _, _ -> c }, { _, _, kw -> replace(c, kw) })
            else -> throw PyException("AttributeError", "'code' object has no attribute '$name'")
        }
    }

    /** co_lines(): (start, end, line) for each run of instructions of one line, offsets in bytes. */
    private fun lines(cb: CodeUnit): PyIterator {
        val out = ArrayList<Any?>()
        val info = info(cb)
        val pcs = pcs(cb)
        var start = 0
        var line = -2
        for (pc in pcs) {
            val l = info.lineAt(cb, pc).let { if (it < 0) info.firstLine.coerceAtLeast(1) else it }
            if (l != line) {
                if (line != -2 && pc > start) out.add(PyTuple(arrayOf(2L * start, 2L * pc, line.toLong())))
                start = pc
                line = l
            }
        }
        if (line != -2) out.add(PyTuple(arrayOf(2L * start, 2L * cb.code.size, line.toLong())))
        val it = out.iterator()
        return PyIterator { if (it.hasNext()) it.next() else PyIterator.STOP }
    }

    /** co_positions(): each int's (line, end line, None, None): NeonPy keeps no columns of its instructions. */
    private fun positions(cb: CodeUnit): PyIterator {
        val info = info(cb)
        var i = 0
        return PyIterator {
            if (i >= cb.code.size) PyIterator.STOP else {
                val l = info.lineAt(cb, i).let { if (it < 0) PyNone else it.toLong() }
                i++
                PyTuple(arrayOf(l, l, PyNone, PyNone))
            }
        }
    }

    /** code.replace(**changes): co_name, co_qualname, co_filename and co_firstlineno changed; the bytecode is as it is. */
    private fun replace(c: PyCode, kw: PyDict): Any? {
        val cb = c.code
        val info = info(cb)
        var name = cb.name
        var qualname = info.qualname
        var filename = c.filename
        var first = info.firstLine
        var flags = info.coFlags
        for ((k, v) in kw.map) {
            val n = (k.v as PyStr).s
            when (n) {
                "co_name" -> name = (v as? PyStr ?: throw PyRt.typeError("replace() argument 'co_name' must be str, not ${PyTypes.name(v)}")).s
                "co_qualname" -> qualname = (v as? PyStr ?: throw PyRt.typeError("replace() argument 'co_qualname' must be str, not ${PyTypes.name(v)}")).s
                "co_filename" -> filename = (v as? PyStr ?: throw PyRt.typeError("replace() argument 'co_filename' must be str, not ${PyTypes.name(v)}")).s
                "co_firstlineno" -> first = (PyRt.indexValue(v) as? Long ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")).toInt()
                // (CO_ITERABLE_COROUTINE alone: what the bytecode does not say, types.coroutine's)
                "co_flags" -> {
                    val n2 = (PyRt.indexValue(v) as? Long ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")).toInt()
                    if ((n2 xor info.coFlags) and PyEval.CO_ITERABLE_COROUTINE.inv() != 0) {
                        throw PyException("ValueError", "code.replace(): NeonPy's code objects keep their bytecode (co_flags)")
                    }
                    flags = n2
                }
                "co_argcount", "co_posonlyargcount", "co_kwonlyargcount", "co_nlocals", "co_stacksize", "co_code",
                "co_consts", "co_names", "co_varnames", "co_freevars", "co_cellvars", "co_linetable", "co_exceptiontable" -> {
                    if (!PyRt.eq(PyEval.codeAttr(c, n), v) && n != "co_linetable" && n != "co_exceptiontable") {
                        throw PyException("ValueError", "code.replace(): NeonPy's code objects keep their bytecode ($n)")
                    }
                }
                else -> throw PyRt.typeError("replace() got an unexpected keyword argument '$n'")
            }
        }
        if (name == cb.name && qualname == info.qualname && filename == c.filename && first == info.firstLine && flags == info.coFlags) return c
        // (a copy of the unit sharing its bytecode, its own info and code object)
        val copy = CodeUnit(name)
        copy.flags = cb.flags
        copy.code = cb.code
        copy.constants = cb.constants
        copy.handlers = cb.handlers
        copy.numRegs = cb.numRegs
        copy.maxStack = cb.maxStack
        copy.formalCount = cb.formalCount
        copy.paramRegs = cb.paramRegs
        copy.lineTable = cb.lineTable
        copy.dialect = cb.dialect
        val ni = info.copyWith(name, qualname, filename, first)
        ni.coFlags = flags
        copy.dialectData = ni
        return PyCode(copy, c.mode, filename).also { ni.codeObject = it }
    }

    // ------------------------------------------------------------------ _dis

    @JvmStatic
    fun fill(d: PyDict) {
        d[PyStr("opname")] = PyList(ArrayList<Any?>((0 until PyOp.COUNT).map { PyStr(PyOp.names[it] ?: "<$it>") }))
        d[PyStr("operands")] = PyList(ArrayList<Any?>((0 until PyOp.COUNT).map { PyOp.operands[it].toLong() }))
        d[PyStr("hasname")] = PyList(ArrayList<Any?>(NAME_OPS.sorted().map { it.toLong() }))
        d[PyStr("haslocal")] = PyList(ArrayList<Any?>(LOCAL_OPS.sorted().map { it.toLong() }))
        d[PyStr("hasjump")] = PyList(ArrayList<Any?>(JUMP_OPS.sorted().map { it.toLong() }))
        d[PyStr("hasconst")] = PyList(arrayListOf<Any?>(PyOp.LOAD_CONST.toLong(), PyOp.MAKE_FUNCTION.toLong()))
        d[PyStr("instructions")] = PyBuiltin("instructions") { _, a ->
            val c = a.getOrNull(0) as? PyCode ?: throw PyRt.typeError("instructions() argument must be code, not ${PyTypes.name(a.getOrNull(0))}")
            instructions(c.code)
        }
        d[PyStr("stack_effect")] = PyBuiltin("stack_effect") { _, a ->
            val op = (a.getOrNull(0) as? Long)?.toInt() ?: throw PyRt.typeError("stack_effect() argument 1 must be int")
            if (op !in 0 until PyOp.COUNT || PyOp.names[op] == null) throw PyException("ValueError", "invalid opcode or oparg")
            PyOp.stackEffect(op, ((a.getOrNull(1) as? Long) ?: 0L).toInt()).toLong()
        }
    }

    /** The instructions of [cb]: (offset, opcode, opname, arg, argval, argrepr, line, operands) each. */
    private fun instructions(cb: CodeUnit): PyList {
        val out = ArrayList<Any?>()
        val info = info(cb)
        val code = cb.code
        for (pc in pcs(cb)) {
            val op = code[pc]
            val n = if (op in 0 until PyOp.COUNT) PyOp.operands[op] else 0
            val ops = (1..n).map { code.getOrElse(pc + it) { 0 } }
            val arg: Any? = ops.firstOrNull()?.toLong() ?: PyNone
            var argval: Any? = arg
            var argrepr = ""
            when {
                n == 0 -> {}
                op == PyOp.LOAD_CONST || op == PyOp.MAKE_FUNCTION -> {
                    argval = constant(cb, ops[0])
                    argrepr = PyRt.repr(argval)
                }
                op in NAME_OPS -> {
                    argval = cb.constants.getOrNull(ops[0])
                    argrepr = PyRt.str(argval)
                }
                op in LOCAL_OPS -> {
                    val nm = info.localNames.getOrNull(ops[0])
                    if (nm != null) {
                        argval = PyStr(nm)
                        argrepr = nm
                    }
                }
                op in JUMP_OPS -> {
                    argval = 2L * ops.last()
                    argrepr = "to ${2L * ops.last()}"
                }
                n > 1 -> {
                    argval = PyTuple(ops.map { it.toLong() }.toTypedArray())
                    argrepr = ops.joinToString(", ")
                }
            }
            val line = info.lineAt(cb, pc).let { if (it < 0) PyNone else it.toLong() }
            out.add(PyTuple(arrayOf(2L * pc, op.toLong(), PyStr(PyOp.name(op)), arg, argval, PyStr(argrepr), line,
                PyTuple(ops.map { it.toLong() }.toTypedArray()))))
        }
        return PyList(out)
    }
}
