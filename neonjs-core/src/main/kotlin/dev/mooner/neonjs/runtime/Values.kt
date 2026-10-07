package dev.mooner.neonjs.runtime

/*
 * Value representation (invariant, used by both interpreter and compiled code):
 *   undefined -> [Undefined]
 *   null      -> [Null]
 *   boolean   -> java.lang.Boolean
 *   number    -> java.lang.Double (never Int/Long/Float; host values are converted at the boundary)
 *   string    -> java.lang.String or [Rope] (both CharSequence); never other CharSequence types
 *   symbol    -> [JSSymbol]
 *   bigint    -> java.math.BigInteger
 *   object    -> [JSObject]
 * Internal sentinels ([Uninitialized], [Hole], [NotFound]) never escape to user code.
 */

object Undefined {
    override fun toString() = "undefined"
}

object Null {
    override fun toString() = "null"
}

/** TDZ marker stored in uninitialized lexical bindings. */
object Uninitialized {
    override fun toString() = "<uninitialized>"
}

/** Array hole marker used in dense element storage. */
object Hole {
    override fun toString() = "<hole>"
}

/** Lookup miss marker. */
object NotFound {
    override fun toString() = "<not found>"
}

class JSSymbol(val description: String?, val registryKey: String? = null) {
    override fun toString(): String = "Symbol(${description ?: ""})"

    /** "[description]" name used for function names keyed by symbols. */
    fun functionName(): String = if (description == null) "" else "[$description]"

    companion object {
        @JvmField val asyncIterator = JSSymbol("Symbol.asyncIterator")
        @JvmField val hasInstance = JSSymbol("Symbol.hasInstance")
        @JvmField val isConcatSpreadable = JSSymbol("Symbol.isConcatSpreadable")
        @JvmField val iterator = JSSymbol("Symbol.iterator")
        @JvmField val match = JSSymbol("Symbol.match")
        @JvmField val matchAll = JSSymbol("Symbol.matchAll")
        @JvmField val replace = JSSymbol("Symbol.replace")
        @JvmField val search = JSSymbol("Symbol.search")
        @JvmField val species = JSSymbol("Symbol.species")
        @JvmField val split = JSSymbol("Symbol.split")
        @JvmField val toPrimitive = JSSymbol("Symbol.toPrimitive")
        @JvmField val toStringTag = JSSymbol("Symbol.toStringTag")
        @JvmField val unscopables = JSSymbol("Symbol.unscopables")
        @JvmField val dispose = JSSymbol("Symbol.dispose")
        @JvmField val asyncDispose = JSSymbol("Symbol.asyncDispose")
        /** Decorator metadata (Stage 3). */
        @JvmField val metadata = JSSymbol("Symbol.metadata")

        val wellKnown = listOf(
            "asyncIterator" to asyncIterator, "hasInstance" to hasInstance, "isConcatSpreadable" to isConcatSpreadable,
            "iterator" to iterator, "match" to match, "matchAll" to matchAll, "replace" to replace, "search" to search,
            "species" to species, "split" to split, "toPrimitive" to toPrimitive, "toStringTag" to toStringTag,
            "unscopables" to unscopables, "dispose" to dispose, "asyncDispose" to asyncDispose, "metadata" to metadata,
        )
    }
}

/**
 * Rope string produced by concatenation; flattened lazily. Implements CharSequence so it can be used as a JS string
 * value. Use [toString] to obtain the flat String.
 */
class Rope(left: CharSequence, right: CharSequence) : CharSequence {
    private var left: CharSequence? = left
    private var right: CharSequence? = right
    private var flat: String? = null
    override val length: Int = left.length + right.length
    @JvmField val depth: Int = maxOf(depthOf(left), depthOf(right)) + 1

    override fun get(index: Int): Char = toString()[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = toString().substring(startIndex, endIndex)

    override fun toString(): String {
        val f = flat
        if (f != null) return f
        val sb = StringBuilder(length)
        appendTo(sb)
        val s = sb.toString()
        flat = s
        left = null
        right = null
        return s
    }

    private fun appendTo(sb: StringBuilder) {
        // iterative traversal to avoid deep recursion on left-leaning ropes
        val stack = ArrayDeque<CharSequence>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            val cs = stack.removeLast()
            if (cs is Rope) {
                val f = cs.flat
                if (f != null) sb.append(f)
                else {
                    stack.addLast(cs.right!!)
                    stack.addLast(cs.left!!)
                }
            } else sb.append(cs)
        }
    }

    override fun equals(other: Any?): Boolean = other is CharSequence && toString() == other.toString()
    override fun hashCode(): Int = toString().hashCode()

    companion object {
        private fun depthOf(cs: CharSequence) = if (cs is Rope) cs.depth else 0

        /** Maximum string length (matches common engine limits, keeps memory bounded). */
        const val MAX_LENGTH = (1 shl 30) - 25

        @JvmStatic
        fun concat(a: CharSequence, b: CharSequence): CharSequence {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val len = a.length.toLong() + b.length
            if (len > MAX_LENGTH) throw JSException.rangeErrorNoRealm("Invalid string length")
            if (len > 65536) Agent.current.get()?.checkStringLength(len)
            if (len < 32 || (a is Rope && a.depth > 2000) || (b is Rope && b.depth > 2000)) {
                return a.toString() + b.toString()
            }
            return Rope(a, b)
        }
    }
}

/** Getter/setter pair stored in a property slot. Fields hold a callable JSObject or Undefined. */
class Accessor(@JvmField var getter: Any?, @JvmField var setter: Any?)

/** Helpers for argument access in builtins. */
@Suppress("NOTHING_TO_INLINE")
inline fun Array<Any?>.arg(i: Int): Any? = if (i < size) this[i] else Undefined

@JvmField
val EMPTY_ARGS = arrayOfNulls<Any?>(0)
