package dev.mooner.neonjs.compiler

import dev.mooner.neonjs.parser.*

/**
 * A module request: specifier plus import attributes and import phase ([phase] is null for ordinary evaluation-phase
 * imports, [PHASE_DEFER] for `import defer`, [PHASE_SOURCE] for `import source`).
 */
class ModuleRequest(val specifier: String, val attributes: List<Pair<String, String>>, val phase: String? = null) {
    override fun equals(other: Any?) = other is ModuleRequest && other.specifier == specifier && other.phase == phase &&
        other.attributes.sortedBy { it.first } == attributes.sortedBy { it.first }
    override fun hashCode() = specifier.hashCode()
    val type: String? get() = attributes.firstOrNull { it.first == "type" }?.second

    companion object {
        const val PHASE_DEFER = "defer"
        const val PHASE_SOURCE = "source"
    }
}

/**
 * ImportEntry: importName is [NAMESPACE] for namespace imports, [DEFERRED_NAMESPACE] for `import defer * as ns` and
 * [MODULE_SOURCE] for `import source x`.
 */
class ImportEntry(val request: Int, val importName: String, val localName: String) {
    companion object {
        const val NAMESPACE = ModuleNames.NAMESPACE
        const val DEFERRED_NAMESPACE = ModuleNames.DEFERRED_NAMESPACE
        const val MODULE_SOURCE = ModuleNames.MODULE_SOURCE
    }
}

/**
 * ExportEntry. [request] is -1 for local exports; [importName] is null for local exports, [ImportEntry.NAMESPACE] for `export * as ns`
 * and star exports (exportName null).
 */
class ExportEntry(val exportName: String?, val request: Int, val importName: String?, val localName: String?)

/** Compiled module: init code (function hoisting), body code, environment layout and import/export tables. */
class CompiledModule(
    val init: CodeBlock,
    val body: CodeBlock,
    val scope: ScopeInfo,
    val requests: List<ModuleRequest>,
    val importEntries: List<ImportEntry>,
    val localExports: List<ExportEntry>,
    val indirectExports: List<ExportEntry>,
    val starExports: List<ExportEntry>,
    val hasTopLevelAwait: Boolean,
    val source: Source,
)

object ModuleInfo {
    fun requestsAndEntries(prog: Program): Triple<List<ModuleRequest>, List<ImportEntry>, List<ExportEntry>> {
        val requests = ArrayList<ModuleRequest>()
        fun req(spec: String, attrs: List<ImportAttribute>, phase: String? = null): Int {
            val r = ModuleRequest(spec, attrs.map { it.key to it.value }, phase)
            val i = requests.indexOf(r)
            if (i >= 0) return i
            requests.add(r)
            return requests.size - 1
        }
        val imports = ArrayList<ImportEntry>()
        val exports = ArrayList<ExportEntry>()
        for (st in prog.body) {
            when (st) {
                is ImportDeclaration -> {
                    val r = req(st.source, st.attributes, st.phase)
                    for (sp in st.specifiers) {
                        val name = when (st.phase) {
                            ModuleRequest.PHASE_DEFER -> ImportEntry.DEFERRED_NAMESPACE
                            ModuleRequest.PHASE_SOURCE -> ImportEntry.MODULE_SOURCE
                            else -> sp.imported
                        }
                        imports.add(ImportEntry(r, name, sp.local.name))
                    }
                }
                is ExportNamedDeclaration -> {
                    val d = st.declaration
                    if (d != null) {
                        when (d) {
                            is VariableDeclaration -> for (decl in d.declarations) {
                                val ids = ArrayList<Identifier>()
                                collect(decl.id, ids)
                                for (id in ids) exports.add(ExportEntry(id.name, -1, null, id.name))
                            }
                            is FunctionDeclaration -> d.function.id?.let { exports.add(ExportEntry(it.name, -1, null, it.name)) }
                            is ClassDeclaration -> d.cls.id?.let { exports.add(ExportEntry(it.name, -1, null, it.name)) }
                        }
                    } else if (st.source != null) {
                        val r = req(st.source, st.attributes)
                        for (sp in st.specifiers) exports.add(ExportEntry(sp.exported, r, sp.local, null))
                    } else {
                        for (sp in st.specifiers) exports.add(ExportEntry(sp.exported, -1, null, sp.local))
                    }
                }
                is ExportDefaultDeclaration -> {
                    val local = when (val d = st.declaration) {
                        is FunctionDeclaration -> d.function.id?.name ?: "*default*"
                        is ClassDeclaration -> d.cls.id?.name ?: "*default*"
                        else -> "*default*"
                    }
                    exports.add(ExportEntry("default", -1, null, local))
                }
                is ExportAllDeclaration -> {
                    val r = req(st.source, st.attributes)
                    exports.add(ExportEntry(st.exported, r, ImportEntry.NAMESPACE, null))
                }
                else -> {}
            }
        }
        return Triple(requests, imports, exports)
    }

    private fun collect(n: Node?, out: MutableList<Identifier>) {
        when (n) {
            is Identifier -> out.add(n)
            is AssignmentPattern -> collect(n.left, out)
            is RestElement -> collect(n.argument, out)
            is ArrayPattern -> n.elements.forEach { collect(it, out) }
            is ObjectPattern -> n.properties.forEach { if (it is Property) collect(it.value, out) else collect(it, out) }
            else -> {}
        }
    }

    /** True if the module body (outside functions) contains `await` / `for await` / `await using`. */
    fun hasTopLevelAwait(prog: Program): Boolean {
        var found = false
        fun walk(x: Any?) {
            if (found || x == null) return
            when (x) {
                is FunctionNode -> return
                is ClassNode -> {
                    walk(x.superClass)
                    for (el in x.body) {
                        if (el is MethodDefinition && el.computed) walk(el.key)
                        if (el is PropertyDefinition && el.computed) walk(el.key)
                    }
                    return
                }
                is AwaitExpression -> { found = true; return }
                is ForOfStatement -> if (x.isAwait) { found = true; return }
                is VariableDeclaration -> if (x.kind == VarKind.AWAIT_USING) { found = true; return }
            }
            if (x is Node) AstWalk.forEachChild(x) { walk(it) }
        }
        for (st in prog.body) walk(st)
        return found
    }
}

/** Generic AST child traversal (reflection-based, cached per node class). */
object AstWalk {
    private val cache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<java.lang.reflect.Field>>()

    private fun fields(c: Class<*>): List<java.lang.reflect.Field> = cache.getOrPut(c) {
        val out = ArrayList<java.lang.reflect.Field>()
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.name == "scope" || f.name == "ref") continue
                if (Node::class.java.isAssignableFrom(f.type) || List::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    out.add(f)
                }
            }
            k = k.superclass
        }
        out
    }

    fun forEachChild(n: Node, f: (Node) -> Unit) {
        for (fld in fields(n.javaClass)) {
            when (val v = fld.get(n)) {
                is Node -> f(v)
                is List<*> -> for (e in v) if (e is Node) f(e)
            }
        }
    }
}
