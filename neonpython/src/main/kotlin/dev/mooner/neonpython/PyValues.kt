package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeUnit
import java.math.BigInteger

/*
 * Python's values on the JVM (docs/PYTHON_PLATFORM.md §10): int is a Long, or a BigInteger past 64 bits; float a
 * Double; bool, None, str, list, tuple, dict, functions are the classes below. Nothing of JavaScript's (no
 * java.lang.Boolean, no bare String) stands for a Python value.
 */

/** None. */
object PyNone {
    override fun toString() = "None"
}

/** True and False: a subclass of int (`True + 1 == 2`). */
class PyBool private constructor(@JvmField val value: Boolean) {
    override fun toString() = if (value) "True" else "False"

    companion object {
        @JvmField val TRUE = PyBool(true)
        @JvmField val FALSE = PyBool(false)

        @JvmStatic fun of(b: Boolean): PyBool = if (b) TRUE else FALSE
    }
}

/**
 * A str: its text and whether every character is in the Basic Multilingual Plane, where a code point is a UTF-16
 * unit and indexing by code point is indexing the String.
 */
class PyStr(@JvmField val s: String) : Comparable<PyStr> {
    @JvmField val bmp: Boolean = s.none { Character.isSurrogate(it) }

    /** The number of code points. */
    val length: Int get() = if (bmp) s.length else s.codePointCount(0, s.length)

    /** The code point at index [i] (0 <= i < length), as a str. */
    fun at(i: Int): PyStr {
        if (bmp) return PyStr(s[i].toString())
        val k = s.offsetByCodePoints(0, i)
        return PyStr(String(Character.toChars(s.codePointAt(k))))
    }

    override fun equals(other: Any?) = other is PyStr && other.s == s
    override fun hashCode() = s.hashCode()
    override fun toString() = s

    /** By code point, as Python compares (String.compareTo compares UTF-16 units, which differ past the BMP). */
    override fun compareTo(other: PyStr): Int {
        if (bmp && other.bmp) return s.compareTo(other.s)
        var i = 0
        var j = 0
        while (i < s.length && j < other.s.length) {
            val a = s.codePointAt(i)
            val b = other.s.codePointAt(j)
            if (a != b) return a.compareTo(b)
            i += Character.charCount(a)
            j += Character.charCount(b)
        }
        return (s.length - i).compareTo(other.s.length - j)
    }

    companion object {
        @JvmField val EMPTY = PyStr("")
    }
}

/** `...` (Ellipsis). */
object PyEllipsis {
    override fun toString() = "Ellipsis"
}

/** bytes: immutable, compared and hashed by content. */
class PyBytes(@JvmField val b: ByteArray) {
    override fun equals(other: Any?) = other is PyBytes && other.b.contentEquals(b)
    override fun hashCode() = b.contentHashCode()

    /** As Python writes it: b'...', printable ASCII as it is, the rest escaped. */
    fun repr(): String {
        val q = if (b.contains('\''.code.toByte()) && !b.contains('"'.code.toByte())) '"' else '\''
        val sb = StringBuilder("b").append(q)
        for (x in b) {
            val c = x.toInt() and 0xFF
            when {
                c == q.code || c == '\\'.code -> sb.append('\\').append(c.toChar())
                c == '\t'.code -> sb.append("\\t")
                c == '\n'.code -> sb.append("\\n")
                c == '\r'.code -> sb.append("\\r")
                c < 32 || c >= 127 -> sb.append("\\x").append(Integer.toHexString(c).padStart(2, '0'))
                else -> sb.append(c.toChar())
            }
        }
        return sb.append(q).toString()
    }

    override fun toString() = repr()
}

/** A complex number. */
class PyComplex(@JvmField val real: Double, @JvmField val imag: Double) {
    override fun equals(other: Any?) = other is PyComplex && other.real == real && other.imag == imag
    override fun hashCode() = real.hashCode() * 31 + imag.hashCode()

    /** As Python writes it: `2j`, `(1+2j)`, `(-0-1j)`. */
    fun repr(): String {
        fun part(d: Double): String {
            val s = PyRt.floatRepr(d)
            return if (s.endsWith(".0")) s.dropLast(2) else s
        }
        if (real == 0.0 && 1.0 / real > 0) return part(imag) + "j"
        val sign = if (imag < 0 || imag == 0.0 && 1.0 / imag < 0) "-" else "+"
        val im = part(Math.abs(imag))
        return "(" + part(real) + sign + im + "j)"
    }

    override fun toString() = repr()
}

/** A list. */
class PyList(@JvmField val items: ArrayList<Any?> = ArrayList()) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** A tuple. */
class PyTuple(@JvmField val items: Array<Any?>) {
    override fun equals(other: Any?) = other is PyTuple && PyRt.seqEquals(items.asList(), other.items.asList())
    override fun hashCode(): Int {
        var h = 0x345678
        for (v in items) h = h * 1000003 xor PyRt.hash(v)
        return h
    }

    companion object {
        @JvmField val EMPTY = PyTuple(arrayOfNulls(0))
    }
}

/**
 * A dict: insertion-ordered, its keys compared as Python compares them (`1`, `1.0` and `True` are one key), by
 * wrapping each key in a [Key].
 */
class PyDict {
    @JvmField val map = LinkedHashMap<Key, Any?>()

    /** A key as Python hashes and compares it. */
    class Key(@JvmField val v: Any?) {
        private val h = PyRt.keyHash(v)
        override fun hashCode() = h
        override fun equals(other: Any?) = other is Key && (v === other.v || PyRt.eq(v, other.v))
    }

    operator fun get(k: Any?): Any? = map[Key(k)]
    fun containsKey(k: Any?) = map.containsKey(Key(k))
    operator fun set(k: Any?, v: Any?) {
        map[Key(k)] = v
    }
    val size: Int get() = map.size

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * A function: its code ([PyCompiler]), the globals of its module, its name; its defaults, keyword-only defaults and
 * closure (the cells of its free variables) as SET_FUNCTION_ATTRIBUTE gave them.
 */
class PyFunction(@JvmField val code: CodeUnit, @JvmField val globals: PyDict, @JvmField val name: String) {
    @JvmField val info: PyCodeInfo = code.dialectData as PyCodeInfo
    /** Its parameters' count: the registers a call's arguments fill. */
    @JvmField val params: Int = code.formalCount
    @JvmField val qualname: String = info.qualname
    @JvmField var defaults: PyTuple? = null
    @JvmField var kwdefaults: PyDict? = null
    @JvmField var closure: Array<Any?>? = null
    /** Its attributes (`f.attr = v`). */
    @JvmField var dict: PyDict? = null

    /** Whether a call of exactly [params] positional arguments needs no binding (no keyword-only, *args, **kwargs). */
    @JvmField val simple: Boolean = info.kwonlyCount == 0 && !info.varargs && !info.varkw

    override fun toString() = "<function $qualname>"
}

/**
 * A function of the runtime's (print, len...): [impl] with positional arguments, [kwImpl] (where it takes any) with
 * keyword arguments too.
 */
class PyBuiltin(@JvmField val name: String, @JvmField val impl: (PyContext, Array<Any?>) -> Any?) {
    @JvmField var kwImpl: ((PyContext, Array<Any?>, PyDict) -> Any?)? = null

    constructor(name: String, impl: (PyContext, Array<Any?>) -> Any?, kwImpl: (PyContext, Array<Any?>, PyDict) -> Any?) : this(name, impl) {
        this.kwImpl = kwImpl
    }

    override fun toString() = "<built-in function $name>"
}

/** A method bound to its object (`lst.append`). */
class PyBoundMethod(@JvmField val self: Any?, @JvmField val fn: PyBuiltin) {
    override fun toString() = "<built-in method ${fn.name}>"
}

/** range(start, stop, step). */
class PyRange(@JvmField val start: Long, @JvmField val stop: Long, @JvmField val step: Long) {
    val length: Long
        get() = when {
            step > 0 && start < stop -> (stop - start + step - 1) / step
            step < 0 && start > stop -> (start - stop - step - 1) / -step
            else -> 0
        }
}

/** An iterator of the runtime's: over a range, a list, a tuple, a str or a dict's keys. */
class PyIterator(@JvmField val next: () -> Any?) {
    companion object {
        /** What [next] gives where the iteration is done. */
        @JvmField val STOP = Any()
    }
}

/**
 * A Python exception: its type's name and its message (the slice's exceptions are the runtime's, by name; classes
 * and `try` come later). Carries no JVM stack trace.
 */
class PyException(@JvmField val type: String, message: String) : RuntimeException(message, null, false, false) {
    /** Where it was raised: the line (-1 before a frame noted it) and the function. */
    @JvmField var line = -1
    @JvmField var function: String? = null

    override fun toString() = "$type: $message"
}

