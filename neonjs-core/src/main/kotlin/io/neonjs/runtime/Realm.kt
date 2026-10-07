package io.neonjs.runtime

import io.neonjs.compiler.TemplateSite

/** A realm: set of intrinsics, global object and global environment. */
class Realm(@JvmField val agent: Agent) {
    lateinit var objectPrototype: JSObject
    lateinit var functionPrototype: JSObject
    lateinit var arrayPrototype: JSObject
    lateinit var stringPrototype: JSObject
    lateinit var numberPrototype: JSObject
    lateinit var booleanPrototype: JSObject
    lateinit var symbolPrototype: JSObject
    lateinit var bigintPrototype: JSObject
    lateinit var errorPrototype: JSObject
    val errorPrototypes = HashMap<ErrorKind, JSObject>()
    val errorConstructors = HashMap<ErrorKind, JSObject>()

    lateinit var iteratorPrototype: JSObject
    lateinit var asyncIteratorPrototype: JSObject
    lateinit var arrayIteratorPrototype: JSObject
    lateinit var arrayIteratorNext: JSObject
    lateinit var arrayProtoValues: JSObject
    lateinit var generatorFunctionPrototype: JSObject
    lateinit var generatorPrototype: JSObject
    lateinit var asyncFunctionPrototype: JSObject
    lateinit var asyncGeneratorFunctionPrototype: JSObject
    lateinit var asyncGeneratorPrototype: JSObject
    lateinit var asyncFromSyncIteratorPrototype: JSObject
    lateinit var promisePrototype: JSObject
    lateinit var promiseConstructor: JSObject

    lateinit var objectConstructor: JSObject
    lateinit var functionConstructor: JSObject
    lateinit var arrayConstructor: JSObject
    lateinit var throwTypeError: JSObject
    lateinit var evalFunction: JSObject

    lateinit var globalObject: JSObject
    lateinit var globalEnv: GlobalEnv

    /** Additional intrinsics by name (e.g. "%Map.prototype%"). */
    val intrinsics = HashMap<String, JSObject>()

    /** Realm-level host/runtime state (module map, loader, ...). */
    val intrinsicsAny = HashMap<String, Any?>()

    /** Template objects cached per site. */
    val templateMap = java.util.WeakHashMap<TemplateSite, JSArray>()

    /** Host-defined data attached to this realm. */
    @JvmField var hostData: Any? = null

    fun intrinsic(name: String): JSObject = intrinsics[name] ?: throw IllegalStateException("missing intrinsic $name")

    fun newError(kind: ErrorKind, message: String): JSErrorObject {
        val o = JSErrorObject(errorPrototypes[kind] ?: errorPrototype)
        o.defineOwn("message", message, Attr.WC)
        if (agent.topFrame != null) o.stackTrace = agent.captureStack()
        return o
    }

    fun typeError(message: String) = JSException(newError(ErrorKind.TYPE, message))
    fun rangeError(message: String) = JSException(newError(ErrorKind.RANGE, message))
    fun syntaxError(message: String) = JSException(newError(ErrorKind.SYNTAX, message))

    /** Runs [block] with this realm as the current realm and the agent bound to the thread. */
    inline fun <R> enter(block: () -> R): R = agent.enter {
        val prev = agent.currentRealm
        agent.currentRealm = this
        try {
            block()
        } finally {
            agent.currentRealm = prev
        }
    }
}
