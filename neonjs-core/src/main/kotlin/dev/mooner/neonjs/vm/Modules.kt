package dev.mooner.neonjs.vm

import dev.mooner.neonjs.compiler.*
import dev.mooner.neonjs.parser.JSSyntaxError
import dev.mooner.neonjs.parser.ParseOptions
import dev.mooner.neonjs.parser.Parser
import dev.mooner.neonjs.runtime.*

/** Live binding to an exported variable of another module (stored in import binding slots). */
class ImportRef(@JvmField val env: DeclEnv, @JvmField val slot: Int, @JvmField val name: String)

/** Source of a module provided by the host loader. */
class ModuleSource(val key: String, val text: String, val type: String = "javascript") {
    /** For host-defined modules: export name -> JS value (the source text is ignored). */
    var hostExports: Map<String, Any?>? = null
    /**
     * The realm [hostExports] were created in, if they are objects of a particular realm. Such a module cannot be
     * loaded into another realm (e.g. a ShadowRealm sharing the loader): its objects would cross the realm boundary.
     */
    var hostRealm: Realm? = null
    /**
     * For host modules that have a source-phase representation (`import source`): the class name reported by the
     * module source object's @@toStringTag (e.g. "WebAssembly.Module"). JS modules have none.
     */
    var sourceClassName: String? = null
    /** Raw contents for `with { type: "bytes" }` imports (loaders should set it for such requests). */
    var bytes: ByteArray? = null
}

/** Host hooks for resolving and loading modules. */
interface ModuleLoader {
    /** Resolves [specifier] relative to [referrerKey] (null for scripts / host) to a canonical key. */
    fun resolve(specifier: String, referrerKey: String?): String

    /** Loads module source for a key; throw to signal failure. */
    fun load(key: String, request: ModuleRequest): ModuleSource

    /** Properties added to import.meta (e.g. url). */
    fun importMetaProperties(key: String): Map<String, Any?> = mapOf("url" to key)
}

/**
 * Resolution result of ResolveExport. bindingName null = the module's namespace ([deferred]: its deferred namespace);
 * [dev.mooner.neonjs.compiler.ImportEntry.MODULE_SOURCE] = the module's source object.
 */
class ResolvedBinding(@JvmField val module: ModuleRecord, @JvmField val bindingName: String?, @JvmField val deferred: Boolean = false) {
    fun sameAs(o: ResolvedBinding) = module === o.module && bindingName == o.bindingName && deferred == o.deferred
}

/** Cyclic (source text or synthetic) module record. */
open class ModuleRecord(@JvmField val realm: Realm, @JvmField val key: String) {
    enum class Status { NEW, UNLINKED, LINKING, LINKED, EVALUATING, EVALUATING_ASYNC, EVALUATED }

    @JvmField var status = Status.UNLINKED
    @JvmField var env: DeclEnv? = null
    @JvmField var namespace: ModuleNamespace? = null
    @JvmField var deferredNamespace: ModuleNamespace? = null
    @JvmField var moduleSource: JSObject? = null
    @JvmField var evaluationError: Any? = null
    @JvmField var hasError = false
    @JvmField var dfsIndex = -1
    @JvmField var dfsAncestorIndex = -1
    @JvmField var cycleRoot: ModuleRecord? = null
    @JvmField var hasTLA = false
    @JvmField var asyncEvaluation = false
    @JvmField var asyncEvaluationOrder = 0L
    @JvmField var topLevelCapability: PromiseCapability? = null
    @JvmField val asyncParentModules = ArrayList<ModuleRecord>()
    @JvmField var pendingAsyncDependencies = 0
    @JvmField var importMeta: JSObject? = null

    open val requests: List<ModuleRequest> get() = emptyList()
    @JvmField val loaded = HashMap<Int, ModuleRecord>()
    open val localExports: List<ExportEntry> get() = emptyList()
    open val indirectExports: List<ExportEntry> get() = emptyList()
    open val starExports: List<ExportEntry> get() = emptyList()

    fun imported(request: Int): ModuleRecord = loaded[request] ?: throw IllegalStateException("module request not loaded")

    open fun initializeEnvironment() {}
    open fun executeModule(capability: PromiseCapability?) {}
    open fun slotOf(name: String): Int = -1

    /** GetModuleSource: the source-phase representation of this module. JS modules have none (SyntaxError). */
    open fun getModuleSource(): JSObject =
        throw JSException.syntaxError("Source phase import is not available for module '$key'")

    /** The phase of request [i] (null = evaluation). */
    fun phaseOf(i: Int): String? = requests.getOrNull(i)?.phase

    fun getExportedNames(exportStarSet: MutableSet<ModuleRecord> = HashSet()): List<String> {
        if (!exportStarSet.add(this)) return emptyList()
        val names = ArrayList<String>()
        for (e in localExports) names.add(e.exportName!!)
        for (e in indirectExports) names.add(e.exportName!!)
        for (e in starExports) {
            val m = imported(e.request)
            for (n in m.getExportedNames(exportStarSet)) if (n != "default" && n !in names) names.add(n)
        }
        return names
    }

    /** ResolveExport: ResolvedBinding, [AMBIGUOUS] or null. */
    fun resolveExport(name: String, resolveSet: MutableList<Pair<ModuleRecord, String>> = ArrayList()): Any? {
        for ((m, n) in resolveSet) if (m === this && n == name) return null
        resolveSet.add(this to name)
        for (e in localExports) if (e.exportName == name) return ResolvedBinding(this, e.localName)
        for (e in indirectExports) {
            if (e.exportName == name) {
                val m = imported(e.request)
                when (e.importName) {
                    ImportEntry.NAMESPACE -> return ResolvedBinding(m, null)
                    ImportEntry.DEFERRED_NAMESPACE -> return ResolvedBinding(m, null, deferred = true)
                    ImportEntry.MODULE_SOURCE -> return ResolvedBinding(m, ImportEntry.MODULE_SOURCE)
                }
                return m.resolveExport(e.importName!!, resolveSet)
            }
        }
        if (name == "default") return null
        var star: ResolvedBinding? = null
        for (e in starExports) {
            val m = imported(e.request)
            val r = m.resolveExport(name, resolveSet)
            if (r === AMBIGUOUS) return AMBIGUOUS
            if (r != null) {
                r as ResolvedBinding
                if (star == null) star = r
                else if (!r.sameAs(star)) return AMBIGUOUS
            }
        }
        return star
    }

    companion object {
        @JvmField val AMBIGUOUS = Any()
    }
}

/** Module compiled from JS source text. */
class SourceTextModule(realm: Realm, key: String, @JvmField val compiled: CompiledModule) : ModuleRecord(realm, key) {
    override val requests get() = compiled.requests
    override val localExports get() = compiled.localExports
    override val indirectExports get() = compiled.indirectExports
    override val starExports get() = compiled.starExports

    init {
        hasTLA = compiled.hasTopLevelAwait
        compiled.source.owner = this
        env = DeclEnv(realm.globalEnv, compiled.scope)
    }

    override fun slotOf(name: String): Int = compiled.scope.lookup(name)

    override fun initializeEnvironment() {
        for (e in indirectExports) {
            val r = resolveExport(e.exportName!!)
            if (r == null || r === AMBIGUOUS) throw JSException.syntaxError("The requested module does not provide an export named '${e.exportName}'")
        }
        val e = env!!
        for (ie in compiled.importEntries) {
            val m = imported(ie.request)
            val slot = compiled.scope.lookup(ie.localName)
            if (ie.importName == ImportEntry.NAMESPACE) {
                e.slots[slot] = Modules.namespaceOf(m)
            } else if (ie.importName == ImportEntry.DEFERRED_NAMESPACE) {
                e.slots[slot] = Modules.namespaceOf(m, deferred = true)
            } else if (ie.importName == ImportEntry.MODULE_SOURCE) {
                e.slots[slot] = m.getModuleSource()
            } else {
                val r = m.resolveExport(ie.importName)
                if (r == null || r === AMBIGUOUS) {
                    throw JSException.syntaxError(
                        if (r == null) "The requested module '${requests[ie.request].specifier}' does not provide an export named '${ie.importName}'"
                        else "The requested module '${requests[ie.request].specifier}' contains conflicting star exports for name '${ie.importName}'"
                    )
                }
                r as ResolvedBinding
                e.slots[slot] = Modules.bindingValueOrRef(r)
            }
        }
        // hoisted function declarations
        val f = Frame(null, compiled.init, realm, Undefined, EMPTY_ARGS, Undefined, e)
        Interpreter.execute(f)
    }

    override fun executeModule(capability: PromiseCapability?) {
        val f = Frame(null, compiled.body, realm, Undefined, EMPTY_ARGS, Undefined, env)
        if (capability == null) {
            Interpreter.execute(f)
            return
        }
        Generators.runAsyncBody(f, realm, capability)
    }
}

/** Synthetic module with fixed exports (JSON modules: a single default export; host-defined modules). */
class SyntheticModule(realm: Realm, key: String, names: List<String>, values: Array<Any?>) : ModuleRecord(realm, key) {
    constructor(realm: Realm, key: String, value: Any?) : this(realm, key, listOf("default"), arrayOf(value))

    /** Set for host modules with a source-phase representation (see [ModuleSource.sourceClassName]). */
    @JvmField var sourceClassName: String? = null

    override fun getModuleSource(): JSObject {
        val cn = sourceClassName ?: return super.getModuleSource()
        moduleSource?.let { return it }
        // like WebAssembly.Module.prototype: a per-class prototype inheriting from %AbstractModuleSource%.prototype
        val protoKey = "%ModuleSource:$cn.prototype%"
        val proto = realm.intrinsics.getOrPut(protoKey) { JSObject(realm.intrinsics["%AbstractModuleSource.prototype%"]) }
        val o = JSModuleSource(proto, cn)
        moduleSource = o
        return o
    }

    private val slots = names.withIndex().associate { it.value to it.index }
    private val info = ScopeInfo(names.toTypedArray(), IntArray(names.size), ScopeKind.MODULE)
    private val exports = names.map { ExportEntry(it, -1, null, it) }
    override val localExports get() = exports
    override fun slotOf(name: String): Int = slots[name] ?: -1

    init {
        env = DeclEnv(realm.globalEnv, info, values)
    }
}

/** A source-phase module representation (`import source`): an instance of %AbstractModuleSource%. */
class JSModuleSource(proto: JSObject?, @JvmField val moduleSourceClassName: String) : JSObject(proto)

/**
 * Module namespace exotic object. A *deferred* namespace (`import defer * as ns`) evaluates its module synchronously
 * the first time a string-keyed property other than "then" is inspected.
 */
class ModuleNamespace(@JvmField val module: ModuleRecord, private val exportList: List<String>, @JvmField val deferred: Boolean = false) : JSObject(null) {
    private val resolved = HashMap<String, Any>()

    init {
        special = special or SPECIAL_ALL
        extensible = false
        defineOwn(JSSymbol.toStringTag, if (deferred) "Deferred Module" else "Module", Attr.NONE)
    }

    override val className: String get() = "Module"

    /** GetModuleExportsList: evaluates a deferred module first. */
    val exports: List<String>
        get() {
            if (deferred) Modules.evaluateSync(module)
            return exportList
        }

    /** Keys handled like an ordinary object (symbols; "then" on deferred namespaces, which are never thenables). */
    private fun symbolLike(key: Any): Boolean = key is JSSymbol || (deferred && key == "then")

    private fun binding(name: String): Any {
        resolved[name]?.let { return it }
        val r = module.resolveExport(name) as ResolvedBinding
        val b: Any = Modules.bindingValueOrRefLazy(r)
        resolved[name] = b
        return b
    }

    private fun valueOf(name: String): Any? {
        val b = binding(name)
        if (b is ModuleNamespace) return b
        if (b is ResolvedBinding) {
            val env = b.module.env ?: throw JSException.referenceError("Cannot access '$name' before initialization")
            val v = env.slots[b.module.slotOf(b.bindingName!!)]
            if (v === Uninitialized) throw JSException.referenceError("Cannot access '$name' before initialization")
            return Modules.deref(v)
        }
        return Modules.deref(b)
    }

    override fun getOwnProperty(key: Any): PropertyDescriptor? {
        if (symbolLike(key)) return ordinaryGetOwnProperty(key)
        val n = PK.toStringKey(key)
        if (n !in exports) return null
        return PropertyDescriptor.data(valueOf(n), Attr.WRITABLE or Attr.ENUMERABLE)
    }

    override fun getOwnValue(key: Any, receiver: Any?): Any? {
        if (symbolLike(key)) return super.getOwnValue(key, receiver)
        val n = PK.toStringKey(key)
        if (n !in exports) return NotFound
        return valueOf(n)
    }

    override fun hasOwnProperty(key: Any): Boolean = getOwnProperty(key) != null
    override fun hasProperty(key: Any): Boolean = if (symbolLike(key)) super.hasOwnProperty(key) else PK.toStringKey(key) in exports

    override fun get(key: Any, receiver: Any?): Any? {
        if (symbolLike(key)) return ordinaryGetOwnProperty(key)?.value ?: Undefined
        val n = PK.toStringKey(key)
        if (n !in exports) return Undefined
        return valueOf(n)
    }

    override fun set(key: Any, value: Any?, receiver: Any?): Boolean = false

    override fun defineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean {
        if (symbolLike(key)) return ordinaryDefineOwnProperty(key, desc)
        val cur = getOwnProperty(key) ?: return false
        if (desc.hasConfigurable && desc.configurable) return false
        if (desc.hasEnumerable && !desc.enumerable) return false
        if (desc.isAccessor) return false
        if (desc.hasWritable && !desc.writable) return false
        if (desc.hasValue) return Ops.sameValue(desc.value, cur.value)
        return true
    }

    override fun delete(key: Any): Boolean {
        if (symbolLike(key)) return super.delete(key)
        return PK.toStringKey(key) !in exports
    }

    override fun ownPropertyKeys(): MutableList<Any> {
        val out = ArrayList<Any>()
        for (e in exports) out.add(PK.fromString(e))
        out.add(JSSymbol.toStringTag)
        return out
    }

    override fun getPrototypeOf(): JSObject? = null
    override fun setPrototypeOf(p: JSObject?): Boolean = p == null
    override fun isExtensible(): Boolean = false
    override fun preventExtensions(): Boolean = true
}

/** Module graph loading, linking and evaluation (ECMA-262 16.2.1.5). */
object Modules {
    private var asyncOrderCounter = 0L

    /** Module types accepted in `with { type: ... }` (JS modules have no type attribute). */
    private val SUPPORTED_TYPES = setOf("json", "text", "bytes")

    @JvmStatic
    fun deref(v: Any?): Any? {
        if (v is ImportRef) {
            val t = v.env.slots[v.slot]
            if (t === Uninitialized) throw JSException.referenceError("Cannot access '${v.name}' before initialization")
            return t
        }
        return v
    }

    fun importRef(r: ResolvedBinding): Any {
        val env = r.module.env ?: return r
        return ImportRef(env, r.module.slotOf(r.bindingName!!), r.bindingName)
    }

    fun importRefLazy(r: ResolvedBinding): Any = if (r.module.env == null) r else importRef(r)

    /** Value (namespace / module source) or live reference for an import binding resolved to [r]. */
    fun bindingValueOrRef(r: ResolvedBinding): Any? = when {
        r.bindingName == null -> namespaceOf(r.module, r.deferred)
        r.bindingName == ImportEntry.MODULE_SOURCE -> r.module.getModuleSource()
        else -> importRef(r)
    }

    fun bindingValueOrRefLazy(r: ResolvedBinding): Any = when {
        r.bindingName == null -> namespaceOf(r.module, r.deferred)
        r.bindingName == ImportEntry.MODULE_SOURCE -> r.module.getModuleSource()
        else -> importRefLazy(r)
    }

    @JvmStatic
    fun namespaceOf(m: ModuleRecord, deferred: Boolean = false): ModuleNamespace {
        (if (deferred) m.deferredNamespace else m.namespace)?.let { return it }
        val names = m.getExportedNames().filter { val r = m.resolveExport(it); r is ResolvedBinding }.sortedWith { a, b -> compareUtf16(a, b) }
        val ns = ModuleNamespace(m, names, deferred)
        if (deferred) m.deferredNamespace = ns else m.namespace = ns
        return ns
    }

    // ------------------------------------------------------------------ deferred evaluation (import defer)

    /**
     * GatherAsynchronousTransitiveDependencies: the modules a deferred import of [m] must still evaluate eagerly — the
     * nearest modules with top-level await (or still-running asynchronous cycles) in its evaluation-phase graph.
     */
    fun gatherAsyncDependencies(m: ModuleRecord, seen: MutableSet<ModuleRecord> = HashSet(), out: MutableList<ModuleRecord> = ArrayList()): List<ModuleRecord> {
        if (!seen.add(m)) return out
        when (m.status) {
            ModuleRecord.Status.EVALUATING -> return out
            ModuleRecord.Status.EVALUATING_ASYNC, ModuleRecord.Status.EVALUATED -> {
                val root = m.cycleRoot ?: m
                if (root.asyncEvaluation && !root.hasError && m !in out) out.add(m)
                return out
            }
            else -> {}
        }
        if (m.hasTLA) {
            if (m !in out) out.add(m)
            return out
        }
        for (i in m.requests.indices) {
            if (m.phaseOf(i) != null) continue
            gatherAsyncDependencies(m.imported(i), seen, out)
        }
        return out
    }

    /** ReadyForSyncExecution */
    private fun readyForSyncExecution(m: ModuleRecord, seen: MutableSet<ModuleRecord> = HashSet()): Boolean {
        if (!seen.add(m)) return true
        if (m.status == ModuleRecord.Status.EVALUATED) {
            val root = m.cycleRoot ?: m
            return root.status == ModuleRecord.Status.EVALUATED
        }
        if (m.status == ModuleRecord.Status.EVALUATING || m.status == ModuleRecord.Status.EVALUATING_ASYNC) return false
        if (m.hasTLA) return false
        // deferred dependencies count too: evaluating m evaluates their asynchronous parts
        for (i in m.requests.indices) {
            if (m.phaseOf(i) == dev.mooner.neonjs.compiler.ModuleRequest.PHASE_SOURCE) continue
            if (!readyForSyncExecution(m.imported(i), seen)) return false
        }
        return true
    }

    /** EvaluateModuleSync: evaluation triggered by a deferred namespace access. */
    @JvmStatic
    fun evaluateSync(m: ModuleRecord) {
        if (m.status == ModuleRecord.Status.EVALUATED && !m.hasError && (m.cycleRoot ?: m).status == ModuleRecord.Status.EVALUATED) return
        if (!readyForSyncExecution(m)) throw JSException.typeError("Deferred module '${m.key}' cannot be evaluated synchronously now (it is evaluating or depends on top-level await)")
        val p = evaluate(m)
        when (p.state) {
            JSPromise.REJECTED -> {
                p.isHandled = true
                throw JSException(p.result)
            }
            JSPromise.PENDING -> throw JSException.typeError("Deferred module '${m.key}' did not evaluate synchronously")
            else -> {}
        }
    }

    private fun compareUtf16(a: String, b: String): Int = a.compareTo(b)

    // ------------------------------------------------------------------ loading

    /** Per-realm module map (key -> record). */
    @JvmStatic
    fun moduleMap(realm: Realm): HashMap<String, ModuleRecord> {
        @Suppress("UNCHECKED_CAST")
        var m = realm.intrinsicsAny["%ModuleMap%"] as HashMap<String, ModuleRecord>?
        if (m == null) {
            m = HashMap()
            realm.intrinsicsAny["%ModuleMap%"] = m
        }
        return m
    }

    // ------------------------------------------------------------------ host modules

    @Suppress("UNCHECKED_CAST")
    private fun hostModules(realm: Realm): HashMap<String, Map<String, Any?>> =
        realm.intrinsicsAny.getOrPut("%HostModules%") { HashMap<String, Map<String, Any?>>() } as HashMap<String, Map<String, Any?>>

    /** Defines a host module ([dev.mooner.neonjs.NeonContext.defineModule]): [exports] are JS values of [realm]. */
    @JvmStatic
    fun defineHostModule(realm: Realm, specifier: String, exports: Map<String, Any?>) {
        hostModules(realm)[specifier] = exports
        (realm.intrinsicsAny["%HostModuleObjects%"] as HashMap<*, *>?)?.remove(specifier)
    }

    /** The exports of the host module [specifier], or null. */
    @JvmStatic
    fun hostModule(realm: Realm, specifier: String): Map<String, Any?>? =
        (realm.intrinsicsAny["%HostModules%"] as HashMap<*, *>?)?.get(specifier) as Map<String, Any?>?

    /** What `require` gives for the host module [specifier]: an object with its exports as properties (made once), or null. */
    @JvmStatic
    fun hostModuleObject(realm: Realm, specifier: String): JSObject? {
        val exports = hostModule(realm, specifier) ?: return null
        @Suppress("UNCHECKED_CAST")
        val cache = realm.intrinsicsAny.getOrPut("%HostModuleObjects%") { HashMap<String, JSObject>() } as HashMap<String, JSObject>
        return cache.getOrPut(specifier) {
            val o = JSObject(realm.objectPrototype)
            for ((k, v) in exports) o.createDataProperty(PK.fromString(k), v)
            o
        }
    }

    // ------------------------------------------------------------------ built-in modules

    /**
     * A module that an engine module provides (`node:events`), under its canonical [name]: [create] makes its exports
     * (what `require` returns) the first time the realm imports or requires it.
     */
    class Builtin(@JvmField val name: String, private val create: () -> Any?) {
        private var state = 0
        private var value: Any? = null

        fun exports(): Any? {
            when (state) {
                2 -> return value
                1 -> throw JSException.typeError("Module '$name' requires itself while it is being created")
            }
            state = 1
            try {
                value = create()
                state = 2
            } finally {
                if (state == 1) state = 0
            }
            return value
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun builtins(realm: Realm): HashMap<String, Builtin> =
        realm.intrinsicsAny.getOrPut("%Builtins%") { HashMap<String, Builtin>() } as HashMap<String, Builtin>

    /** Registers [builtin] in [realm] under its name and [aliases] (`events` for `node:events`). */
    @JvmStatic
    fun defineBuiltin(realm: Realm, builtin: Builtin, vararg aliases: String) {
        val map = builtins(realm)
        map[builtin.name] = builtin
        for (a in aliases) map[a] = builtin
    }

    /** The built-in module [specifier] names in [realm], or null. */
    @JvmStatic
    fun builtin(realm: Realm, specifier: String): Builtin? = (realm.intrinsicsAny["%Builtins%"] as HashMap<*, *>?)?.get(specifier) as Builtin?

    @JvmStatic
    fun hasBuiltins(realm: Realm): Boolean = (realm.intrinsicsAny["%Builtins%"] as HashMap<*, *>?)?.isNotEmpty() == true

    /** The canonical names of the realm's built-in modules, sorted. */
    @JvmStatic
    fun builtinNames(realm: Realm): List<String> =
        (realm.intrinsicsAny["%Builtins%"] as HashMap<*, *>?)?.values?.map { (it as Builtin).name }?.distinct()?.sorted() ?: emptyList()

    /**
     * The ES module face of [builtin]: `default` is its exports, and the exports' own enumerable string-keyed
     * properties are named exports too (as Node's built-in modules have it). Bound to [realm].
     */
    @JvmStatic
    fun builtinSource(realm: Realm, builtin: Builtin): ModuleSource {
        val exports = builtin.exports()
        val named = LinkedHashMap<String, Any?>()
        named["default"] = exports
        if (exports is JSObject) {
            for (k in exports.ownPropertyKeys()) {
                if (k is JSSymbol) continue
                val key = PK.toStringKey(k)
                if (key == "default") continue
                val d = exports.getOwnProperty(k) ?: continue
                if (d.enumerable) named[key] = exports.get(k, exports)
            }
        }
        val src = ModuleSource(builtin.name, "")
        src.hostExports = named
        src.hostRealm = realm
        return src
    }

    @JvmStatic
    fun loaderOf(realm: Realm): ModuleLoader = realm.intrinsicsAny["%ModuleLoader%"] as ModuleLoader?
        ?: throw JSException.typeError("No module loader configured")

    @JvmStatic
    fun setLoader(realm: Realm, loader: ModuleLoader) {
        realm.intrinsicsAny["%ModuleLoader%"] = loader
    }

    /** Parses and creates a module record for source; throws a JS SyntaxError on parse errors. */
    @JvmStatic
    fun parseModule(realm: Realm, src: ModuleSource): ModuleRecord {
        src.hostExports?.let { ex ->
            val names = ex.keys.toList()
            return SyntheticModule(realm, src.key, names, Array(names.size) { ex[names[it]] }).also { it.sourceClassName = src.sourceClassName }
        }
        if (src.type == "json") {
            val v = dev.mooner.neonjs.builtins.JSONBuiltins.JsonParser(realm, src.text).parseRoot().value
            return SyntheticModule(realm, src.key, v)
        }
        if (src.type == "text") return SyntheticModule(realm, src.key, src.text)
        if (src.type == "bytes") return SyntheticModule(realm, src.key, dev.mooner.neonjs.builtins.BufferOps.immutableBytes(realm, src.bytes ?: src.text.toByteArray(Charsets.UTF_8)))
        val prog = try {
            Parser.parse(src.text, ParseOptions(isModule = true, sourceName = src.key))
        } catch (e: JSSyntaxError) {
            throw Evaluator.syntaxError(realm, e)
        }
        return SourceTextModule(realm, src.key, Compiler.compileModule(prog, Source(src.key, src.text)))
    }

    /** Loads [root] and all its dependencies (synchronously) into the realm's module map. */
    @JvmStatic
    fun loadGraph(realm: Realm, root: ModuleRecord) {
        val map = moduleMap(realm)
        map.putIfAbsent(root.key, root)
        // only needed for imports: a self-contained module evaluates without a loader
        val loader by lazy(LazyThreadSafetyMode.NONE) { loaderOf(realm) }
        val stack = ArrayDeque<ModuleRecord>()
        val seen = HashSet<ModuleRecord>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val m = stack.removeLast()
            if (!seen.add(m)) continue
            for ((i, req) in m.requests.withIndex()) {
                if (m.loaded.containsKey(i)) continue
                val dep = loadOne(realm, loader, map, req, m.key)
                m.loaded[i] = dep
                // a source-phase import only needs the module itself, not its graph
                if (req.phase != dev.mooner.neonjs.compiler.ModuleRequest.PHASE_SOURCE) stack.add(dep)
            }
        }
    }

    private fun loadOne(realm: Realm, loader: ModuleLoader, map: HashMap<String, ModuleRecord>, req: ModuleRequest, referrer: String?): ModuleRecord {
        for ((k, _) in req.attributes) if (k != "type") throw JSException.syntaxError("Unsupported import attribute '$k'")
        val type = req.type
        if (type != null && type !in SUPPORTED_TYPES) throw JSException.typeError("Unsupported module type '$type'")
        val key = loader.resolve(req.specifier, referrer)
        // the module map is keyed by (resolved specifier, module type): a file may be both JS and text
        val mapKey = if (type == null) key else "$key\u0000$type"
        val existing = map[mapKey]
        if (existing != null) return existing
        val src = try {
            loader.load(key, req)
        } catch (e: JSException) {
            throw e
        } catch (e: Exception) {
            throw JSException.typeError("Cannot load module '${req.specifier}': ${e.message}")
        }
        val hr = src.hostRealm
        if (src.hostExports != null && hr != null && hr !== realm) throw JSException.typeError("Host module '${req.specifier}' is not available in this realm")
        val rec = parseModule(realm, if (type != null && src.hostExports == null) ModuleSource(src.key, src.text, type).also { it.bytes = src.bytes } else src)
        map[mapKey] = rec
        return rec
    }

    // ------------------------------------------------------------------ linking

    @JvmStatic
    fun link(m: ModuleRecord) {
        val stack = ArrayList<ModuleRecord>()
        try {
            innerLink(m, stack, 0)
        } catch (t: Throwable) {
            for (x in stack) x.status = ModuleRecord.Status.UNLINKED
            m.status = ModuleRecord.Status.UNLINKED
            throw t
        }
    }

    private fun innerLink(m: ModuleRecord, stack: MutableList<ModuleRecord>, index0: Int): Int {
        var index = index0
        when (m.status) {
            ModuleRecord.Status.LINKING, ModuleRecord.Status.LINKED, ModuleRecord.Status.EVALUATING_ASYNC, ModuleRecord.Status.EVALUATED -> return index
            else -> {}
        }
        m.status = ModuleRecord.Status.LINKING
        m.dfsIndex = index
        m.dfsAncestorIndex = index
        index++
        stack.add(m)
        for (i in m.requests.indices) {
            if (m.phaseOf(i) == dev.mooner.neonjs.compiler.ModuleRequest.PHASE_SOURCE) continue
            val r = m.imported(i)
            index = innerLink(r, stack, index)
            if (r.status == ModuleRecord.Status.LINKING) m.dfsAncestorIndex = minOf(m.dfsAncestorIndex, r.dfsAncestorIndex)
        }
        m.initializeEnvironment()
        if (m.dfsAncestorIndex == m.dfsIndex) {
            while (true) {
                val r = stack.removeAt(stack.size - 1)
                r.status = ModuleRecord.Status.LINKED
                if (r === m) break
            }
        }
        return index
    }

    // ------------------------------------------------------------------ evaluation

    /** Evaluate(): returns the top-level promise. */
    @JvmStatic
    fun evaluate(module0: ModuleRecord): JSPromise {
        var module = module0
        val realm = module.realm
        if (module.status == ModuleRecord.Status.EVALUATING_ASYNC || module.status == ModuleRecord.Status.EVALUATED) {
            module = module.cycleRoot ?: module
        }
        module.topLevelCapability?.let { return it.promise as JSPromise }
        val stack = ArrayList<ModuleRecord>()
        val cap = Promises.newPromiseCapability(realm, realm.promiseConstructor)
        module.topLevelCapability = cap
        try {
            innerEvaluate(module, stack, 0)
            if (!module.asyncEvaluation) Ops.call(cap.resolve, Undefined, arrayOf(Undefined))
        } catch (t: Throwable) {
            if (t is TerminationException) throw t
            val v = if (t is JSException) t.value else Rt.catchValue(t, realm, null, 0)
            for (x in stack) {
                x.status = ModuleRecord.Status.EVALUATED
                x.hasError = true
                x.evaluationError = v
            }
            module.status = ModuleRecord.Status.EVALUATED
            if (!module.hasError) {
                module.hasError = true
                module.evaluationError = v
            }
            Ops.call(cap.reject, Undefined, arrayOf(v))
        }
        return cap.promise as JSPromise
    }

    private fun innerEvaluate(m: ModuleRecord, stack: MutableList<ModuleRecord>, index0: Int): Int {
        var index = index0
        if (m.status == ModuleRecord.Status.EVALUATING_ASYNC || m.status == ModuleRecord.Status.EVALUATED) {
            if (!m.hasError) return index
            throw JSException(m.evaluationError)
        }
        if (m.status == ModuleRecord.Status.EVALUATING) return index
        m.status = ModuleRecord.Status.EVALUATING
        m.dfsIndex = index
        m.dfsAncestorIndex = index
        m.pendingAsyncDependencies = 0
        index++
        stack.add(m)
        for (i in m.requests.indices) {
            val deps = when (m.phaseOf(i)) {
                dev.mooner.neonjs.compiler.ModuleRequest.PHASE_SOURCE -> continue
                // deferred: only the asynchronous parts of its graph are evaluated now
                dev.mooner.neonjs.compiler.ModuleRequest.PHASE_DEFER -> gatherAsyncDependencies(m.imported(i))
                else -> listOf(m.imported(i))
            }
            for (d in deps) {
                var r = d
                index = innerEvaluate(r, stack, index)
                if (r.status == ModuleRecord.Status.EVALUATING) {
                    m.dfsAncestorIndex = minOf(m.dfsAncestorIndex, r.dfsAncestorIndex)
                } else {
                    r = r.cycleRoot ?: r
                    if (r.hasError) throw JSException(r.evaluationError)
                }
                if (r.asyncEvaluation && m !in r.asyncParentModules) {
                    m.pendingAsyncDependencies++
                    r.asyncParentModules.add(m)
                }
            }
        }
        if (m.pendingAsyncDependencies > 0 || m.hasTLA) {
            m.asyncEvaluation = true
            m.asyncEvaluationOrder = ++asyncOrderCounter
            if (m.pendingAsyncDependencies == 0) executeAsync(m)
        } else {
            m.executeModule(null)
        }
        if (m.dfsAncestorIndex == m.dfsIndex) {
            while (true) {
                val r = stack.removeAt(stack.size - 1)
                r.status = if (!r.asyncEvaluation) ModuleRecord.Status.EVALUATED else ModuleRecord.Status.EVALUATING_ASYNC
                r.cycleRoot = m
                if (r === m) break
            }
        }
        return index
    }

    private fun executeAsync(m: ModuleRecord) {
        val realm = m.realm
        val p = Promises.newPromise(realm)
        val (res, rej) = Promises.createResolvingFunctions(realm, p)
        Promises.thenHost(realm, p, { asyncFulfilled(m); Undefined }, { e -> asyncRejected(m, e); Undefined })
        m.executeModule(PromiseCapability(p, res, rej))
    }

    private fun gatherAncestors(m: ModuleRecord, execList: MutableList<ModuleRecord>) {
        for (p in m.asyncParentModules) {
            if (p !in execList && !(p.cycleRoot ?: p).hasError) {
                p.pendingAsyncDependencies--
                if (p.pendingAsyncDependencies == 0) {
                    execList.add(p)
                    if (!p.hasTLA) gatherAncestors(p, execList)
                }
            }
        }
    }

    private fun asyncFulfilled(m: ModuleRecord) {
        if (m.status == ModuleRecord.Status.EVALUATED) return
        m.asyncEvaluation = false
        m.status = ModuleRecord.Status.EVALUATED
        m.topLevelCapability?.let { Ops.call(it.resolve, Undefined, arrayOf(Undefined)) }
        val execList = ArrayList<ModuleRecord>()
        gatherAncestors(m, execList)
        execList.sortBy { it.asyncEvaluationOrder }
        for (x in execList) {
            if (x.status == ModuleRecord.Status.EVALUATED) continue
            if (x.hasTLA) executeAsync(x)
            else {
                try {
                    x.executeModule(null)
                } catch (t: Throwable) {
                    if (t is TerminationException) throw t
                    asyncRejected(x, if (t is JSException) t.value else Rt.catchValue(t, x.realm, null, 0))
                    continue
                }
                x.asyncEvaluation = false
                x.status = ModuleRecord.Status.EVALUATED
                x.topLevelCapability?.let { Ops.call(it.resolve, Undefined, arrayOf(Undefined)) }
            }
        }
    }

    private fun asyncRejected(m: ModuleRecord, error: Any?) {
        if (m.status == ModuleRecord.Status.EVALUATED) return
        m.hasError = true
        m.evaluationError = error
        m.status = ModuleRecord.Status.EVALUATED
        m.asyncEvaluation = false
        // the module's own promise is rejected before those of its async parents (test262 rejection-order.js)
        m.topLevelCapability?.let { Ops.call(it.reject, Undefined, arrayOf(error)) }
        for (p in ArrayList(m.asyncParentModules)) asyncRejected(p, error)
    }

    // ------------------------------------------------------------------ host entry points

    /** Loads, links and evaluates the module at [key]; returns the evaluation promise. */
    @JvmStatic
    fun runModule(realm: Realm, src: ModuleSource): Pair<ModuleRecord, JSPromise> {
        val map = moduleMap(realm)
        val rec = map[src.key] ?: parseModule(realm, src).also { map[src.key] = it }
        loadGraph(realm, rec)
        link(rec)
        return rec to evaluate(rec)
    }

    /** import.meta for the module owning the executing code. */
    @JvmStatic
    fun importMeta(f: Frame): JSObject {
        val m = f.code.source?.owner as? ModuleRecord ?: throw JSException.syntaxError("import.meta is only valid in module code")
        m.importMeta?.let { return it }
        val o = JSObject(null)
        try {
            for ((k, v) in loaderOf(m.realm).importMetaProperties(m.key)) o.createDataProperty(PK.fromString(k), v)
        } catch (e: JSException) {
            // no loader: empty import.meta
        }
        m.importMeta = o
        return o
    }

    /** import(specifier, options) / import.defer(...) / import.source(...) ([phase] null = evaluation). */
    @JvmStatic
    fun dynamicImport(f: Frame, specifier: Any?, options: Any?, phase: String? = null): Any? {
        val realm = f.realm
        val cap = Promises.newPromiseCapability(realm, realm.promiseConstructor)
        val referrer = (f.code.source?.owner as? ModuleRecord)?.key ?: (f.code.source?.owner as? String)
        try {
            val spec = Ops.toString(specifier)
            val attrs = ArrayList<Pair<String, String>>()
            if (options !== Undefined) {
                if (options !is JSObject) throw JSException.typeError("The second argument of import() must be an object")
                val w = options.get("with", options)
                if (w !== Undefined) {
                    if (w !is JSObject) throw JSException.typeError("The 'with' option must be an object")
                    for (k in w.ownPropertyKeys()) {
                        if (k is JSSymbol) continue
                        val d = w.getOwnProperty(k) ?: continue
                        if (!d.enumerable) continue
                        val v = w.get(k, w)
                        if (v !is CharSequence) throw JSException.typeError("Import attribute values must be strings")
                        attrs.add(PK.toStringKey(k) to v.toString())
                    }
                }
            }
            val req = ModuleRequest(spec, attrs, phase)
            // loading completes asynchronously (in a later job), as hosts do
            realm.agent.enqueueJob {
                try {
                    val loader = loaderOf(realm)
                    val map = moduleMap(realm)
                    val m = loadOne(realm, loader, map, req, referrer)
                    if (phase == ModuleRequest.PHASE_SOURCE) {
                        Ops.call(cap.resolve, Undefined, arrayOf(m.getModuleSource()))
                        return@enqueueJob
                    }
                    loadGraph(realm, m)
                    link(m)
                    if (phase == ModuleRequest.PHASE_DEFER) {
                        finishDeferredImport(realm, m, cap)
                        return@enqueueJob
                    }
                    val p = evaluate(m)
                    Promises.thenHost(realm, p, {
                        Ops.call(cap.resolve, Undefined, arrayOf(namespaceOf(m)))
                        Undefined
                    }, { e ->
                        Ops.call(cap.reject, Undefined, arrayOf(e))
                        Undefined
                    })
                } catch (e: JSException) {
                    Ops.call(cap.reject, Undefined, arrayOf(e.value))
                }
            }
        } catch (e: JSException) {
            Ops.call(cap.reject, Undefined, arrayOf(e.value))
        }
        return cap.promise
    }

    /** import.defer(): evaluate the asynchronous dependencies, then resolve with the deferred namespace. */
    private fun finishDeferredImport(realm: Realm, m: ModuleRecord, cap: PromiseCapability) {
        val deps = gatherAsyncDependencies(m)
        if (deps.isEmpty()) {
            Ops.call(cap.resolve, Undefined, arrayOf(namespaceOf(m, deferred = true)))
            return
        }
        var remaining = deps.size
        var settled = false
        for (d in deps) {
            Promises.thenHost(realm, evaluate(d), {
                if (!settled && --remaining == 0) {
                    settled = true
                    Ops.call(cap.resolve, Undefined, arrayOf(namespaceOf(m, deferred = true)))
                }
                Undefined
            }, { e ->
                if (!settled) {
                    settled = true
                    Ops.call(cap.reject, Undefined, arrayOf(e))
                }
                Undefined
            })
        }
    }
}
