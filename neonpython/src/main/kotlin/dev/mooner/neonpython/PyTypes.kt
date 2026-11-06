package dev.mooner.neonpython

import java.math.BigInteger

/**
 * A type: a builtin one ([PyTypes]) or a class. Its attributes are in [dict], looked up through its method resolution
 * order ([mro], C3, the type itself first). Builtin types make their values with [newImpl]; a type may refuse to be a
 * base ([subclassable]). The type of a type is [meta] (`type` where null).
 */
class PyType(@JvmField var name: String, bases: Array<PyType>, @JvmField val dict: PyDict = PyDict()) {
    @JvmField var qualname: String = name
    @JvmField var module: String = "builtins"
    @JvmField var bases: Array<PyType> = bases
    @JvmField var mro: Array<PyType> = arrayOf(this)
    @JvmField var meta: PyType? = null
    @JvmField var subclassable = true
    /** The attribute hooks its instances go through (PyClasses.hooksOf): __getattribute__ 1, __setattr__ 2, __delattr__ 4. */
    @JvmField var hooks = 0
    /** Makes a value of this builtin type (or of a class deriving from it): (context, type, arguments, keywords). */
    @JvmField var newImpl: ((PyContext, PyType, Array<Any?>, PyDict?) -> Any?)? = null

    /** Attribute [n] of the first type of the MRO that has it, or null. */
    fun lookup(n: String): Any? {
        val k = PyDict.Key(PyStr(n))
        for (t in mro) t.dict.map[k]?.let { return it }
        return null
    }

    fun isSubtype(t: PyType): Boolean {
        if (t === this) return true
        for (x in mro) if (x === t) return true
        return false
    }

    /** Defines attribute [n] (a builtin's method, a constant). */
    fun def(n: String, v: Any?) {
        dict[PyStr(n)] = v
    }

    fun repr(): String = if (module == "builtins") "<class '$name'>" else "<class '$module.$qualname'>"

    override fun toString() = repr()
}

/** An instance of a class: its type and its attributes. (Exceptions' instances are of a subclass: PyBaseException.) */
open class PyObject(@JvmField var type: PyType) {
    @JvmField var dict: PyDict? = null

    override fun toString() = "<${type.module}.${type.qualname} object>"
}

/** NotImplemented: what a binary special method returns where it does not handle its operands. */
object PyNotImplemented {
    override fun toString() = "NotImplemented"
}

/** The builtin types, and the type of every value ([typeOf]). */
object PyTypes {
    private fun builtin(name: String, vararg bases: PyType, subclassable: Boolean = true): PyType {
        val t = PyType(name, if (bases.isEmpty() && name != "object") arrayOf(OBJECT) else arrayOf(*bases))
        t.mro = c3(t, t.bases)
        t.subclassable = subclassable
        return t
    }

    @JvmField val OBJECT = PyType("object", emptyArray())
    @JvmField val TYPE = builtin("type")
    @JvmField val NONE = builtin("NoneType", subclassable = false)
    @JvmField val INT = builtin("int")
    @JvmField val BOOL = builtin("bool", INT, subclassable = false)
    @JvmField val FLOAT = builtin("float")
    @JvmField val COMPLEX = builtin("complex")
    @JvmField val STR = builtin("str")
    @JvmField val BYTES = builtin("bytes")
    @JvmField val LIST = builtin("list")
    @JvmField val TUPLE = builtin("tuple")
    @JvmField val DICT = builtin("dict")
    @JvmField val RANGE = builtin("range", subclassable = false)
    @JvmField val FUNCTION = builtin("function", subclassable = false)
    @JvmField val BUILTIN_FUNCTION = builtin("builtin_function_or_method", subclassable = false)
    @JvmField val METHOD = builtin("method", subclassable = false)
    @JvmField val ITERATOR = builtin("iterator", subclassable = false)
    @JvmField val CELL = builtin("cell", subclassable = false)
    @JvmField val ELLIPSIS = builtin("ellipsis", subclassable = false)
    @JvmField val NOT_IMPLEMENTED = builtin("NotImplementedType", subclassable = false)
    @JvmField val SUPER = builtin("super")
    @JvmField val PROPERTY = builtin("property")
    @JvmField val GENERATOR = builtin("generator", subclassable = false)
    @JvmField val COROUTINE = builtin("coroutine", subclassable = false)
    @JvmField val ASYNC_GENERATOR = builtin("async_generator", subclassable = false)
    @JvmField val SET = builtin("set")
    @JvmField val FROZENSET = builtin("frozenset")
    @JvmField val SLICE = builtin("slice", subclassable = false)
    @JvmField val TEMPLATE = builtin("Template", subclassable = false).also { it.module = "string.templatelib" }
    @JvmField val INTERPOLATION = builtin("Interpolation", subclassable = false).also { it.module = "string.templatelib" }
    @JvmField val STATICMETHOD = builtin("staticmethod")
    @JvmField val CLASSMETHOD = builtin("classmethod")

    /**
     * The exception classes (docs/PYTHON_PLATFORM.md §11, M3 gives them their behaviour): by name, the hierarchy of
     * CPython 3.14's builtins.
     */
    @JvmField val EXCEPTIONS = LinkedHashMap<String, PyType>()

    init {
        OBJECT.subclassable = true
        val tree = listOf(
            "BaseException" to null, "BaseExceptionGroup" to "BaseException", "GeneratorExit" to "BaseException",
            "KeyboardInterrupt" to "BaseException", "SystemExit" to "BaseException", "Exception" to "BaseException",
            "ArithmeticError" to "Exception", "FloatingPointError" to "ArithmeticError", "OverflowError" to "ArithmeticError",
            "ZeroDivisionError" to "ArithmeticError", "AssertionError" to "Exception", "AttributeError" to "Exception",
            "BufferError" to "Exception", "EOFError" to "Exception", "ExceptionGroup" to "BaseExceptionGroup",
            "ImportError" to "Exception", "ModuleNotFoundError" to "ImportError", "LookupError" to "Exception",
            "IndexError" to "LookupError", "KeyError" to "LookupError", "MemoryError" to "Exception", "NameError" to "Exception",
            "UnboundLocalError" to "NameError", "OSError" to "Exception", "BlockingIOError" to "OSError",
            "ChildProcessError" to "OSError", "ConnectionError" to "OSError", "BrokenPipeError" to "ConnectionError",
            "ConnectionAbortedError" to "ConnectionError", "ConnectionRefusedError" to "ConnectionError",
            "ConnectionResetError" to "ConnectionError", "FileExistsError" to "OSError", "FileNotFoundError" to "OSError",
            "InterruptedError" to "OSError", "IsADirectoryError" to "OSError", "NotADirectoryError" to "OSError",
            "PermissionError" to "OSError", "ProcessLookupError" to "OSError", "TimeoutError" to "OSError",
            "ReferenceError" to "Exception",
            "RuntimeError" to "Exception", "NotImplementedError" to "RuntimeError", "PythonFinalizationError" to "RuntimeError",
            "RecursionError" to "RuntimeError", "StopAsyncIteration" to "Exception", "StopIteration" to "Exception",
            "SyntaxError" to "Exception", "IndentationError" to "SyntaxError", "TabError" to "IndentationError",
            "SystemError" to "Exception", "TypeError" to "Exception", "ValueError" to "Exception", "UnicodeError" to "ValueError",
            "UnicodeDecodeError" to "UnicodeError", "UnicodeEncodeError" to "UnicodeError", "UnicodeTranslateError" to "UnicodeError",
            "Warning" to "Exception", "BytesWarning" to "Warning", "DeprecationWarning" to "Warning", "EncodingWarning" to "Warning",
            "FutureWarning" to "Warning", "ImportWarning" to "Warning", "PendingDeprecationWarning" to "Warning",
            "ResourceWarning" to "Warning", "RuntimeWarning" to "Warning", "SyntaxWarning" to "Warning",
            "UnicodeWarning" to "Warning", "UserWarning" to "Warning",
        )
        for ((n, base) in tree) EXCEPTIONS[n] = when {
            base == null -> builtin(n)
            // (ExceptionGroup derives from BaseExceptionGroup and Exception)
            n == "ExceptionGroup" -> builtin(n, EXCEPTIONS[base]!!, EXCEPTIONS["Exception"]!!)
            else -> builtin(n, EXCEPTIONS[base]!!)
        }
    }

    /** A builtin type made later than these (PyLibrary's iterators' types). */
    @JvmStatic fun newBuiltin(name: String, subclassable: Boolean = true): PyType = builtin(name, subclassable = subclassable)

    /** The type of [v]. */
    @JvmStatic fun typeOf(v: Any?): PyType = when (v) {
        null, PyNone -> NONE
        is PyBool -> BOOL
        is Long, is BigInteger -> INT
        is Double -> FLOAT
        is PyStr -> v.sub?.cls ?: STR
        is PyList -> v.sub?.cls ?: LIST
        is PyTuple -> v.sub?.cls ?: TUPLE
        is PyDict -> v.sub?.cls ?: DICT
        is PyObject -> v.type
        is PyType -> v.meta ?: TYPE
        is PyFunction -> FUNCTION
        is PyMethod -> METHOD
        is PyBuiltin, is PyBoundMethod -> BUILTIN_FUNCTION
        is PySuper -> SUPER
        is PyProperty -> PROPERTY
        is PyStaticMethod -> STATICMETHOD
        is PyClassMethod -> CLASSMETHOD
        is PyRange -> RANGE
        is PyIterator -> v.type ?: ITERATOR
        is PyGenerator -> GENERATOR
        is PyCoroutine -> COROUTINE
        is PyAsyncGenerator -> ASYNC_GENERATOR
        is PyAwaitIter -> v.type
        is PySet -> v.sub?.cls ?: if (v.frozen) FROZENSET else SET
        is PySlice -> SLICE
        is PyTemplate -> TEMPLATE
        is PyDictView -> PyContainers.typeOf(v)
        is PyModule -> PyImport.MODULE_TYPE
        is PyStream -> PyImport.STREAM_TYPE
        is PyInterpolation -> INTERPOLATION
        is PyComplex -> COMPLEX
        is PyBytes -> BYTES
        is PyCell -> CELL
        PyEllipsis -> ELLIPSIS
        PyNotImplemented -> NOT_IMPLEMENTED
        else -> OBJECT
    }

    /** [t]'s name with its module (none for builtins' and `__main__`'s), as CPython's %T gives it. */
    @JvmStatic fun fullName(t: PyType): String = if (t.module == "builtins" || t.module == "__main__") t.qualname else "${t.module}.${t.qualname}"

    /** The name of [v]'s type, as messages give it. */
    @JvmStatic fun name(v: Any?): String = typeOf(v).name

    /**
     * The C3 linearization of a type with [bases]: itself, then its bases' orders merged so that each keeps its
     * order and every type comes before its bases; a TypeError where no such order exists.
     */
    @JvmStatic
    fun c3(t: PyType, bases: Array<PyType>): Array<PyType> {
        val seqs = ArrayList<ArrayList<PyType>>()
        for (b in bases) seqs.add(ArrayList(b.mro.asList()))
        seqs.add(ArrayList(bases.asList()))
        val out = arrayListOf(t)
        while (true) {
            seqs.removeAll { it.isEmpty() }
            if (seqs.isEmpty()) return out.toTypedArray()
            val head = seqs.firstNotNullOfOrNull { s -> s[0].takeIf { h -> seqs.none { o -> o.indexOf(h) > 0 } } }
                ?: throw PyRt.typeError("Cannot create a consistent method resolution order (MRO) for bases " +
                    seqs.map { it[0] }.distinct().joinToString(", ") { it.name })
            out.add(head)
            for (s in seqs) if (s[0] === head) s.removeAt(0)
        }
    }

    /** isinstance(v, cls), cls a type or a tuple of them. */
    @JvmStatic
    fun isInstance(v: Any?, cls: Any?): Boolean = when (cls) {
        is PyType -> typeOf(v).isSubtype(cls)
        is PyTuple -> cls.items.any { isInstance(v, it) }
        else -> throw PyRt.typeError("isinstance() arg 2 must be a type, a tuple of types, or a union")
    }

    /** issubclass(c, cls). */
    @JvmStatic
    fun isSubclass(c: Any?, cls: Any?): Boolean {
        if (c !is PyType) throw PyRt.typeError("issubclass() arg 1 must be a class")
        return when (cls) {
            is PyType -> c.isSubtype(cls)
            is PyTuple -> cls.items.any { isSubclass(c, it) }
            else -> throw PyRt.typeError("issubclass() arg 2 must be a class, a tuple of classes, or a union")
        }
    }

    /** A type called (a builtin one or a class): the instance it makes. */
    @JvmStatic
    fun call(ctx: PyContext, t: PyType, args: Array<Any?>, kw: PyDict?): Any? =
        if (kw == null || kw.size == 0) PyClasses.instantiate(ctx, t, args, NO_ARGS, NO_ARGS)
        else PyClasses.instantiate(ctx, t, args, kw.map.keys.map { it.v }.toTypedArray(), kw.map.values.toTypedArray())

    private val NO_ARGS = arrayOfNulls<Any?>(0)
}
