package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeBlock
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
        private val h = PyRt.hash(v)
        override fun hashCode() = h
        override fun equals(other: Any?) = other is Key && PyRt.eq(v, other.v)
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

/** A function: its code ([PyCompiler]), the globals of its module, its name. */
class PyFunction(@JvmField val code: CodeBlock, @JvmField val globals: PyDict, @JvmField val name: String) {
    @JvmField val params: Int = code.formalCount
    override fun toString() = "<function $name>"
}

/** A function of the runtime's (print, len...). */
class PyBuiltin(@JvmField val name: String, @JvmField val impl: (PyContext, Array<Any?>) -> Any?) {
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

/** The runtime's view of its values' types. */
object PyTypes {
    @JvmStatic fun name(v: Any?): String = when (v) {
        null, PyNone -> "NoneType"
        is PyBool -> "bool"
        is Long, is BigInteger -> "int"
        is Double -> "float"
        is PyStr -> "str"
        is PyList -> "list"
        is PyTuple -> "tuple"
        is PyDict -> "dict"
        is PyFunction -> "function"
        is PyBuiltin, is PyBoundMethod -> "builtin_function_or_method"
        is PyRange -> "range"
        is PyIterator -> "iterator"
        else -> v.javaClass.simpleName
    }
}
