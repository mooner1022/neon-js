package io.neonjs.runtime

enum class ErrorKind(val jsName: String) {
    ERROR("Error"), EVAL("EvalError"), RANGE("RangeError"), REFERENCE("ReferenceError"), SYNTAX("SyntaxError"),
    TYPE("TypeError"), URI("URIError"), AGGREGATE("AggregateError"), SUPPRESSED("SuppressedError"),
}

/** A thrown JS value. Stack traces of the Kotlin exception are disabled for performance. */
class JSException(@JvmField val value: Any?) : RuntimeException(null, null, false, false) {

    override val message: String get() = describe()

    fun describe(): String {
        val v = value
        if (v is JSObject) {
            try {
                val name = v.get("name", v)
                val msg = v.get("message", v)
                val n = if (name === Undefined) "Error" else Ops.toDisplayString(name)
                val m = if (msg === Undefined) "" else Ops.toDisplayString(msg)
                return if (m.isEmpty()) n else if (n.isEmpty()) m else "$n: $m"
            } catch (e: Throwable) {
                return "[object]"
            }
        }
        return try { Ops.toDisplayString(v) } catch (e: Throwable) { "<value>" }
    }

    /** JS stack trace string if the thrown value is an error object with a captured stack. */
    val jsStack: String?
        get() {
            val v = value
            if (v is JSErrorObject) return v.stackTrace
            return null
        }

    companion object {
        @JvmStatic
        fun create(kind: ErrorKind, message: String): JSException {
            val realm = Agent.currentRealmOrNull() ?: return JSException("${kind.jsName}: $message")
            val e = realm.newError(kind, message)
            realm.agent.topFrame?.let { top ->
                e.capturedAt = java.lang.ref.WeakReference(top)
                e.capturedPc = top.pc
            }
            return JSException(e)
        }

        @JvmStatic fun typeError(message: String) = create(ErrorKind.TYPE, message)
        @JvmStatic fun rangeError(message: String) = create(ErrorKind.RANGE, message)
        @JvmStatic fun referenceError(message: String) = create(ErrorKind.REFERENCE, message)
        @JvmStatic fun syntaxError(message: String) = create(ErrorKind.SYNTAX, message)
        @JvmStatic fun uriError(message: String) = create(ErrorKind.URI, message)
        @JvmStatic fun rangeErrorNoRealm(message: String) = create(ErrorKind.RANGE, message)
    }
}

/** Ordinary object with an [[ErrorData]] internal slot. */
open class JSErrorObject(proto: JSObject?) : JSObject(proto) {
    override val className: String get() = "Error"
    /** Captured JS stack trace (without the "Name: message" header). */
    @JvmField var stackTrace: String? = null
    /**
     * For errors raised by the engine itself (not by `new Error`): the JS frame that was on top when the stack was
     * captured (weakly) and its pc then. That pc may lag behind the failing instruction, so the stack is captured again
     * with the exact pc when the exception passes through that frame still at that pc (Rt.attachStack).
     */
    @JvmField var capturedAt: java.lang.ref.WeakReference<Any>? = null
    @JvmField var capturedPc: Int = -1
    /** Name and message kept in internal slots (DOMException) rather than in "name" / "message" data properties. */
    open val slotName: String? get() = null
    open val slotMessage: String? get() = null
}

/**
 * Thrown to terminate execution (sandbox limits, interrupts). Not catchable by JS code: catch/finally handlers
 * rethrow it without running.
 */
open class TerminationException(message: String) : RuntimeException(message)

class ExecutionTimeoutException(message: String) : TerminationException(message)
class ResourceLimitException(message: String) : TerminationException(message)
class InterruptedExecutionException(message: String) : TerminationException(message)
