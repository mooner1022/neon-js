package dev.mooner.neonpython

/**
 * compile(..., PyCF_ONLY_AST) (docs/PYTHON_PLATFORM.md §12, S5): NeonPy's syntax tree ([Node], which has `ast`'s
 * nodes, fields and order) made into instances of `_ast`'s classes (NeonPy's, generated from CPython's ASDL by
 * neonpython/tools/gen_ast.py): each node's fields converted, the fields the tree does not keep (a Module's
 * type_ignores, type comments) their defaults, positions as CPython gives them (columns in UTF-8 bytes); expression
 * contexts and operators shared, as CPython's singletons.
 */
object PyAstObjects {
    /** [n] (a Module, an Expression) as `_ast` objects; [source] for the columns' UTF-8 offsets. */
    @JvmStatic
    fun toPython(ctx: PyContext, n: Node, source: String): Any? = Converter(PyImport.importModule(ctx, "_ast"), source).node(n)

    private class Converter(val ast: Any?, source: String) {
        val classes = HashMap<String, PyType>()
        val lines = source.split('\n')
        val singletons = HashMap<String, PyObject>()
        val shared = PyClasses.attrOrNull(ast, "_singletons") as? PyDict

        fun cls(name: String): PyType = classes.getOrPut(name) {
            PyRt.getAttr(ast, name) as? PyType ?: throw PyException("SystemError", "_ast has no class $name")
        }

        /** A column of line [line] as a UTF-8 byte offset. */
        fun utf8Col(line: Int, col: Int): Long {
            val text = lines.getOrNull(line - 1) ?: return col.toLong()
            val upTo = text.substring(0, minOf(col, text.length))
            var bytes = 0L
            var i = 0
            while (i < upTo.length) {
                val cp = upTo.codePointAt(i)
                bytes += when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                i += Character.charCount(cp)
            }
            return bytes + maxOf(0, col - text.length)
        }

        fun obj(t: PyType): PyObject = PyObject(t).also { it.dict = PyDict() }

        fun convert(v: Any?): Any? = when (v) {
            null -> PyNone
            is Node -> node(v)
            is List<*> -> PyList(ArrayList<Any?>(v.size).also { l -> for (x in v) l.add(convert(x)) })
            // (_ast's one instance of an operator or context class, as CPython's parser gives its singletons)
            is Enum<*> -> singletons.getOrPut(v.name) { shared?.get(PyStr(v.name)) as? PyObject ?: obj(cls(v.name)) }
            is String -> PyStr(v)
            is ConstValue -> v.v ?: PyNone
            is Int -> v.toLong()
            is Boolean -> PyBool.of(v)
            else -> v
        }

        fun node(n: Node): PyObject {
            val t = cls(n.pyName)
            val o = obj(t)
            val d = o.dict!!
            for ((name, value) in n.fields()) d[PyStr(name)] = convert(value)
            // (fields NeonPy's tree has not: their defaults, a list's [], an optional's None)
            val fields = PyRt.getAttr(t, "_fields") as PyTuple
            val kinds = PyClasses.attrOrNull(t, "_field_kinds") as? PyDict
            for (f in fields.items) if (!d.containsKey(f)) {
                d[f] = if ((kinds?.get(f) as? PyStr)?.s == "*") PyList() else PyNone
            }
            val attrs = PyRt.getAttr(t, "_attributes") as PyTuple
            if (attrs.items.isNotEmpty() && n.line > 0) {
                val endLine = if (n.endLine > 0) n.endLine else n.line
                d[PyStr("lineno")] = n.line.toLong()
                d[PyStr("col_offset")] = utf8Col(n.line, n.col)
                d[PyStr("end_lineno")] = endLine.toLong()
                d[PyStr("end_col_offset")] = utf8Col(endLine, n.endCol)
            }
            return o
        }
    }

    /** compile(source, filename, mode, PyCF_ONLY_AST): the parsed tree, as `ast` objects (no symbol table: its errors are compile's). */
    @JvmStatic
    fun parse(ctx: PyContext, src: String, filename: String, mode: String): Any? {
        val tree: Node = try {
            when (mode) {
                "eval" -> PyParser(src).parseExpression()
                "exec" -> PyParser(src).parseModule()
                "single" -> PyParser(src).parseModule().let { m -> Interactive(m.body).also { it.line = m.line } }
                else -> throw PyException("ValueError", "compile() mode must be 'exec', 'eval', 'single' or 'func_type'")
            }
        } catch (e: PySyntaxError) {
            e.stage = 2
            throw PyEval.syntaxError(e, filename, src, mode)
        }
        return toPython(ctx, tree, src)
    }
}
