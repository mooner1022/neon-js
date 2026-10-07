package dev.mooner.neonjs.builtins

import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.JSModuleSource

/** %AbstractModuleSource% (source phase imports): the abstract base of module source objects. Not a global. */
object ModuleSourceBuiltins {
    fun install(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        val ctor = makeCtor(realm, "AbstractModuleSource", 0, proto) { _, _, _, _ ->
            typeErr("AbstractModuleSource is an abstract class and cannot be constructed")
        }
        proto.getter(realm, JSSymbol.toStringTag) { _, t, _, _ -> (t as? JSModuleSource)?.moduleSourceClassName ?: Undefined }
        realm.intrinsics["%AbstractModuleSource%"] = ctor
        realm.intrinsics["%AbstractModuleSource.prototype%"] = proto
    }
}
