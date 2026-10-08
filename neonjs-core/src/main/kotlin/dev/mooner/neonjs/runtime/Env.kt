package dev.mooner.neonjs.runtime

import dev.mooner.neonjs.compiler.ScopeInfo
import dev.mooner.neonjs.vm.ImportRef
import dev.mooner.neonjs.vm.Modules

/** Runtime environment record (scope chain link). */
abstract class Env(@JvmField val parent: Env?)

/** Declarative environment with fixed slots described by [info]; [extension] holds sloppy-eval var bindings. */
class DeclEnv(parent: Env?, @JvmField val info: ScopeInfo, @JvmField val slots: Array<Any?>) : Env(parent) {
    @JvmField var extension: LinkedHashMap<String, Any?>? = null

    constructor(parent: Env?, info: ScopeInfo) : this(parent, info, info.newSlots())

    fun copy(): DeclEnv {
        val e = DeclEnv(parent, info, slots.copyOf())
        e.extension = extension
        return e
    }
}

/** Object environment (with statement or global object). */
class ObjectEnv(parent: Env?, @JvmField val obj: JSObject, @JvmField val isWith: Boolean) : Env(parent)

/** Mutable/immutable binding of the global declarative record. */
class GlobalBinding(@JvmField var value: Any?, @JvmField val mutable: Boolean)

/** Global environment: declarative record (let/const/class) + object record (global object). */
class GlobalEnv(@JvmField val realm: Realm, @JvmField val global: JSObject, @JvmField val thisValue: Any?) : Env(null) {
    /** Lexical bindings by name; never removed. Add them only through [declareLexical]. */
    @JvmField val lexical = HashMap<String, GlobalBinding>()
    /** The same bindings in declaration order (indexed by global-access inline caches). */
    @JvmField val lexicalList = ArrayList<GlobalBinding>()
    /** Incremented whenever a lexical binding is added (it may shadow a cached global object property). */
    @JvmField var lexicalEpoch = 0
    /** Identity of this environment for inline caches (holds no references, so caches retain nothing). */
    @JvmField val token = Any()
    @JvmField val varNames = HashSet<String>()

    fun declareLexical(name: String, b: GlobalBinding) {
        val old = lexical.put(name, b)
        if (old != null) lexicalList[lexicalList.indexOf(old)] = b else lexicalList.add(b)
        lexicalEpoch++
    }
}

/** Reference to a binding resolved dynamically (for assignments that must resolve before evaluating the RHS). */
class NameRef(@JvmField val env: Env?, @JvmField val name: String, @JvmField val slot: Int, @JvmField val strict: Boolean)

/** Dynamic name resolution and access (with / eval / global code). */
object Names {
    private val PSEUDO = setOf("this", "new.target", "%home", "%fn")

    /** Finds the environment that has a binding for [name]; returns null if unresolvable (global missing). */
    @JvmStatic
    fun resolve(env: Env?, name: String): NameRef? {
        var e = env
        while (e != null) {
            when (e) {
                is DeclEnv -> {
                    val i = e.info.lookup(name)
                    if (i >= 0) return NameRef(e, name, i, false)
                    val ext = e.extension
                    if (ext != null && ext.containsKey(name)) return NameRef(e, name, -1, false)
                }
                is ObjectEnv -> {
                    if (name !in PSEUDO && hasObjectBinding(e, name)) return NameRef(e, name, -1, false)
                }
                is GlobalEnv -> {
                    if (name in PSEUDO) return NameRef(e, name, -2, false)
                    if (e.lexical.containsKey(name)) return NameRef(e, name, -3, false)
                    if (e.global.hasProperty(name)) return NameRef(e, name, -4, false)
                    return null
                }
            }
            e = e.parent
        }
        return null
    }

    private fun hasObjectBinding(e: ObjectEnv, name: String): Boolean {
        val o = e.obj
        if (!o.hasProperty(name)) return false
        if (!e.isWith) return true
        val uns = o.get(JSSymbol.unscopables, o)
        if (uns is JSObject) {
            if (Ops.toBoolean(uns.get(name, uns))) return false
        }
        return true
    }

    @JvmStatic
    fun getValue(ref: NameRef?, name: String, strict: Boolean, forTypeof: Boolean): Any? {
        if (ref == null) {
            if (forTypeof) return Undefined
            throw JSException.referenceError("$name is not defined")
        }
        when (val e = ref.env) {
            is DeclEnv -> {
                val v = if (ref.slot >= 0) e.slots[ref.slot] else e.extension!![name]
                if (v === Uninitialized) throw JSException.referenceError("Cannot access '$name' before initialization")
                // an import binding of a module holds a live reference to the exporting module's variable (code
                // compiled in the module reads it with LOAD_IMPORT; a direct eval's code gets here)
                if (v is ImportRef) return Modules.deref(v)
                return v
            }
            is ObjectEnv -> {
                val o = e.obj
                if (!o.hasProperty(name)) {
                    if (!strict) return Undefined
                    throw JSException.referenceError("$name is not defined")
                }
                return o.get(name, o)
            }
            is GlobalEnv -> {
                when (ref.slot) {
                    -2 -> return when (name) {
                        "this" -> e.thisValue
                        else -> Undefined
                    }
                    -3 -> {
                        val b = e.lexical[name]!!
                        if (b.value === Uninitialized) throw JSException.referenceError("Cannot access '$name' before initialization")
                        return b.value
                    }
                    else -> {
                        val g = e.global
                        if (!g.hasProperty(name)) {
                            if (forTypeof) return Undefined
                            throw JSException.referenceError("$name is not defined")
                        }
                        return g.get(name, g)
                    }
                }
            }
            else -> throw IllegalStateException()
        }
    }

    @JvmStatic
    fun load(env: Env?, name: String, strict: Boolean, forTypeof: Boolean): Any? = getValue(resolve(env, name), name, strict, forTypeof)

    /** PutValue on a resolved reference. */
    @JvmStatic
    fun putValue(ref: NameRef?, name: String, value: Any?, strict: Boolean, globalEnv: GlobalEnv) {
        if (ref == null) {
            if (strict) throw JSException.referenceError("$name is not defined")
            val g = globalEnv.global
            g.set(name, value, g)
            return
        }
        when (val e = ref.env) {
            is DeclEnv -> {
                if (ref.slot >= 0) {
                    val f = e.info.flags[ref.slot]
                    if (e.slots[ref.slot] === Uninitialized) throw JSException.referenceError("Cannot access '$name' before initialization")
                    if (f and ScopeInfo.F_CONST != 0) throw JSException.typeError("Assignment to constant variable.")
                    if (f and ScopeInfo.F_CALLEE != 0) {
                        if (strict) throw JSException.typeError("Assignment to constant variable.")
                        return
                    }
                    e.slots[ref.slot] = value
                } else {
                    val ext = e.extension!!
                    if (!ext.containsKey(name)) {
                        // binding was deleted meanwhile
                        if (strict) throw JSException.referenceError("$name is not defined")
                        val g = globalEnv.global
                        g.set(name, value, g)
                        return
                    }
                    ext[name] = value
                }
            }
            is ObjectEnv -> {
                val o = e.obj
                val stillExists = o.hasProperty(name)
                if (!stillExists && strict) throw JSException.referenceError("$name is not defined")
                if (!o.set(name, value, o) && strict) throw JSException.typeError("Cannot assign to read only property '$name'")
            }
            is GlobalEnv -> {
                if (ref.slot == -3) {
                    val b = e.lexical[name]!!
                    if (b.value === Uninitialized) throw JSException.referenceError("Cannot access '$name' before initialization")
                    if (!b.mutable) throw JSException.typeError("Assignment to constant variable.")
                    b.value = value
                } else {
                    val g = e.global
                    if (strict && !g.hasProperty(name)) throw JSException.referenceError("$name is not defined")
                    if (!g.set(name, value, g) && strict) throw JSException.typeError("Cannot assign to read only property '$name' of object")
                }
            }
            else -> throw IllegalStateException()
        }
    }

    @JvmStatic
    fun store(env: Env?, name: String, value: Any?, strict: Boolean, globalEnv: GlobalEnv) =
        putValue(resolve(env, name), name, value, strict, globalEnv)

    /** InitializeBinding for a dynamically found binding (eval lexical / class declarations). */
    @JvmStatic
    fun initialize(env: Env?, name: String, value: Any?) {
        var e = env
        while (e != null) {
            if (e is DeclEnv) {
                val i = e.info.lookup(name)
                if (i >= 0) {
                    e.slots[i] = value
                    return
                }
            } else if (e is GlobalEnv) {
                val b = e.lexical[name]
                if (b != null) {
                    b.value = value
                    return
                }
                val g = e.global
                g.set(name, value, g)
                return
            }
            e = e.parent
        }
    }

    /** delete identifier (sloppy mode). */
    @JvmStatic
    fun delete(env: Env?, name: String): Boolean {
        val ref = resolve(env, name) ?: return true
        return when (val e = ref.env) {
            is DeclEnv -> {
                if (ref.slot >= 0) false
                else {
                    e.extension!!.remove(name)
                    true
                }
            }
            is ObjectEnv -> e.obj.delete(name)
            is GlobalEnv -> {
                if (ref.slot == -3 || ref.slot == -2) false
                else {
                    val ok = e.global.delete(name)
                    if (ok) e.varNames.remove(name)
                    ok
                }
            }
            else -> false
        }
    }

    /** Value and this-value for calls through dynamic references. */
    @JvmStatic
    fun loadForCall(env: Env?, name: String, strict: Boolean): Pair<Any?, Any?> {
        val ref = resolve(env, name)
        val v = getValue(ref, name, strict, false)
        val thisV = if (ref != null && ref.env is ObjectEnv && ref.env.isWith) ref.env.obj else Undefined
        return v to thisV
    }
}
