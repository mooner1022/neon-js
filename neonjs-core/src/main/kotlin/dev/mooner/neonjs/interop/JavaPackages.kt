package dev.mooner.neonjs.interop

import dev.mooner.neonjs.runtime.*

/**
 * A Java package as GraalJS shows it ([dev.mooner.neonjs.SandboxPolicy.exposeJavaPackages]): `java.util`,
 * `Packages.org.example`. Reading a name gives the class of that name where JS may load it ([resolve]: the policy's
 * lookup filter and class checks, as `Java.type`), else the subpackage of that name, so `java.util.ArrayList` is
 * `Java.type('java.util.ArrayList')` and a class JS may not load looks like a package. A package has no keys and takes no
 * writes; it converts to `[JavaPackage java.util]`.
 */
class JavaPackageObject(
    private val bridge: HostBridge,
    /** The package's name, "" for `Packages` itself. */
    @JvmField val packageName: String,
    proto: JSObject,
    private val resolve: (String) -> Class<*>?,
) : JSObject(proto) {
    /** Names read so far: the class or subpackage each gave (so `java.util === java.util`). */
    private val children = HashMap<String, Any>()

    init {
        special = special or SPECIAL_ALL
        extensible = false
    }

    override val className: String get() = "JavaPackage"

    private fun child(name: String): Any = children.getOrPut(name) {
        val full = if (packageName.isEmpty()) name else "$packageName.$name"
        resolve(full)?.let { bridge.classObject(it) } ?: JavaPackageObject(bridge, full, proto!!, resolve)
    }

    // every name is a class or a subpackage; symbols are the prototype's (Symbol.toPrimitive, Symbol.toStringTag)
    override fun getOwnValue(key: Any, receiver: Any?): Any? = if (key is String) child(key) else NotFound

    override fun get(key: Any, receiver: Any?): Any? = if (key is String) child(key) else proto?.get(key, receiver) ?: Undefined

    override fun getOwnProperty(key: Any): PropertyDescriptor? = null

    override fun hasOwnProperty(key: Any): Boolean = false

    override fun hasProperty(key: Any): Boolean = key is String || (proto?.hasProperty(key) ?: false)

    override fun set(key: Any, value: Any?, receiver: Any?): Boolean = false

    override fun defineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean = false

    override fun delete(key: Any): Boolean = true

    override fun ownPropertyKeys(): MutableList<Any> = ArrayList()

    override fun setPrototypeOf(p: JSObject?): Boolean = p === proto

    override fun preventExtensions(): Boolean = true

    override fun isExtensible(): Boolean = false

    override fun toString(): String = "JavaPackage[$packageName]"

    companion object {
        /** The top-level packages GraalJS makes globals, besides `Packages`. */
        @JvmField val GLOBALS = listOf("java", "javax", "com", "org", "edu")

        /**
         * Installs `Packages` and [GLOBALS] in [realm], classes resolved through [resolve]. They are writable and
         * configurable, as GraalJS's are, so a script's own `var com` still works.
         */
        fun install(realm: Realm, bridge: HostBridge, resolve: (String) -> Class<*>?) {
            val proto = JSObject(realm.objectPrototype)
            proto.defineOwn(JSSymbol.toPrimitive, NativeFunction(realm, "[Symbol.toPrimitive]", 1, { _, t, _, _ ->
                "[JavaPackage ${(t as? JavaPackageObject)?.packageName ?: ""}]"
            }), Attr.CONFIGURABLE)
            proto.defineOwn(JSSymbol.toStringTag, "JavaPackage", Attr.CONFIGURABLE)
            val root = JavaPackageObject(bridge, "", proto, resolve)
            realm.globalObject.defineOwn("Packages", root, Attr.WC)
            for (name in GLOBALS) realm.globalObject.defineOwn(name, root.get(name, root), Attr.WC)
        }
    }
}
