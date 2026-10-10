package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*

/** `FormData` (XMLHttpRequest Standard), without form elements: a list of entries whose values are strings or files. */
internal object FormData {
    class JSFormData(proto: JSObject?) : JSObject(proto) {
        override val className: String get() = "FormData"
        /** The entry list: (name, value) with a String or a [Blob.JSFile] value. */
        @JvmField val entries = ArrayList<Pair<String, Any>>()
    }

    /**
     * "create an entry": [value] is a string or a blob; a blob becomes a File named [filename], "blob" for a blob
     * that is no file, or its own name.
     */
    fun entryValue(realm: Realm, value: Any, filename: String?): Any {
        if (value !is Blob.JSBlob) return value
        if (value is Blob.JSFile && filename == null) return value
        val name = filename ?: if (value is Blob.JSFile) value.name else "blob"
        val lastModified = if (value is Blob.JSFile) value.lastModified else Math.floor(realm.agent.currentTimeMillis())
        return Blob.JSFile(realm.intrinsic("%File.prototype%"), value.data, value.offset, value.size, value.type, name, lastModified)
    }

    /** The (name, value, filename) of `append` / `set`: the Blob overload with three arguments or a Blob value. */
    private fun entryArgs(realm: Realm, a: Array<Any?>, what: String): Pair<String, Any> {
        Idl.required(a, 2, what)
        val name = Idl.usvString(a[0])
        if (a.size >= 3 || a[1] is Blob.JSBlob) {
            val blob = a[1] as? Blob.JSBlob ?: typeErr("$what: the value is not a Blob")
            val filename = if (a.size >= 3 && a[2] !== Undefined) Idl.usvString(a[2]) else null
            return name to entryValue(realm, blob, filename)
        }
        return name to Idl.usvString(a[1])
    }

    fun install(realm: Realm) {
        val i = WebInterface.define(realm, "FormData", 0) { a, proto ->
            // no form elements here: a form or submitter argument can only be of a wrong type
            if (a.arg(0) !== Undefined) typeErr("FormData constructor: the argument is not an HTMLFormElement")
            JSFormData(proto)
        }
        fun fd(t: Any?, m: String) = Idl.self<JSFormData>(t, "FormData", m)
        i.operation("append", 2) { f, t, a, _ ->
            val x = fd(t, "append")
            x.entries.add(entryArgs(f.realm, a, "FormData.append"))
            Undefined
        }
        i.operation("delete", 1) { _, t, a, _ ->
            val x = fd(t, "delete")
            Idl.required(a, 1, "FormData.delete")
            val name = Idl.usvString(a[0])
            x.entries.removeIf { it.first == name }
            Undefined
        }
        i.operation("get", 1) { _, t, a, _ ->
            val x = fd(t, "get")
            Idl.required(a, 1, "FormData.get")
            val name = Idl.usvString(a[0])
            x.entries.firstOrNull { it.first == name }?.second ?: Null
        }
        i.operation("getAll", 1) { f, t, a, _ ->
            val x = fd(t, "getAll")
            Idl.required(a, 1, "FormData.getAll")
            val name = Idl.usvString(a[0])
            Builtins.arrayOf(f.realm, x.entries.filter { it.first == name }.map { it.second })
        }
        i.operation("has", 1) { _, t, a, _ ->
            val x = fd(t, "has")
            Idl.required(a, 1, "FormData.has")
            val name = Idl.usvString(a[0])
            x.entries.any { it.first == name }
        }
        i.operation("set", 2) { f, t, a, _ ->
            val x = fd(t, "set")
            val entry = entryArgs(f.realm, a, "FormData.set")
            // replace the first entry of that name, remove the others
            val first = x.entries.indexOfFirst { it.first == entry.first }
            if (first < 0) x.entries.add(entry) else {
                x.entries[first] = entry
                var k = x.entries.size - 1
                while (k > first) {
                    if (x.entries[k].first == entry.first) x.entries.removeAt(k)
                    k--
                }
            }
            Undefined
        }
        i.pairIterable(JSFormData::class.java) { x -> x.entries }
    }
}
