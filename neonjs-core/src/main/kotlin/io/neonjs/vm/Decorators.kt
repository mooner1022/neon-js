package io.neonjs.vm

import io.neonjs.runtime.*

/**
 * Runtime of class definitions that use decorators or `accessor` fields (Stage 3 decorators). Such classes are
 * compiled to: CLASS_DEF_NEW, one CLASS_ELEMENT per element (key, function and evaluated decorators, in source
 * order), CLASS_FINISH (applies element decorators and defines the elements), CLASS_DECORATE (class decorators)
 * and, once the class binding is initialized, CLASS_STATIC_INIT (static elements and extra initializers).
 */
class ClassDef(@JvmField val ctor: JSClosure, @JvmField val proto: JSObject) {
    class Element(
        @JvmField val kind: Int,
        @JvmField val isStatic: Boolean,
        @JvmField val key: Any,
        @JvmField var fn: Any?,
        @JvmField val decorators: Array<Any?>?,
    ) {
        /** Auto-accessor parts. */
        @JvmField var getter: Any? = null
        @JvmField var setter: Any? = null
        @JvmField var storage: PrivateName? = null
        /** Field / accessor initializers returned by decorators. */
        @JvmField var initializers: ArrayList<JSObject>? = null
        /** addInitializer() of field and accessor decorators: run after the field / accessor is initialized. */
        @JvmField var extraInitializers: ArrayList<JSObject>? = null
    }

    @JvmField val elements = ArrayList<Element>()
    @JvmField val staticExtraInitializers = ArrayList<JSObject>()
    @JvmField val instanceExtraInitializers = ArrayList<JSObject>()
    @JvmField val classExtraInitializers = ArrayList<JSObject>()
    @JvmField var finalClass: Any? = null
    private var meta: JSObject? = null

    /**
     * context.metadata, shared by all decorators of the class; created before the first decorator is called. It
     * inherits from the superclass's Symbol.metadata. (The decorator metadata proposal has no normative text for
     * the details; this follows the TypeScript and Babel emit: `Object.create(superclass[Symbol.metadata] ?? null)`.)
     */
    val metadata: JSObject
        get() {
            meta?.let { return it }
            // a derived class whose [[Prototype]] is %Function.prototype% is `extends null`: no superclass
            val superclass = if (ctor.code.isDerived && ctor.proto !== ctor.realm.functionPrototype) ctor.proto else null
            val parent = when (val pm = superclass?.get(JSSymbol.metadata, superclass)) {
                null, Undefined, Null -> null
                is JSObject -> pm
                else -> throw JSException.typeError("The superclass's Symbol.metadata is not an object")
            }
            return JSObject(parent).also { meta = it }
        }

    companion object {
        const val METHOD = 0
        const val GETTER = 1
        const val SETTER = 2
        const val FIELD = 3
        const val ACCESSOR = 4
        const val BLOCK = 5
        const val STATIC = 8
    }
}

object Decorators {
    @JvmStatic
    fun newClassDef(ctor: Any?, proto: Any?): ClassDef = ClassDef(ctor as JSClosure, proto as JSObject)

    /** CLASS_ELEMENT: records an element. Methods and accessors are named here, before decorators see them. */
    @JvmStatic
    fun addElement(def: Any?, decorators: Any?, key: Any?, fn: Any?, flags: Int) {
        val d = def as ClassDef
        val kind = flags and 7
        val isStatic = flags and ClassDef.STATIC != 0
        val decs = (decorators as? JSArray)?.let { a -> Array(a.length.toInt()) { a.get(it, a) } }
        val e = ClassDef.Element(kind, isStatic, key!!, fn, decs)
        if (fn is JSFunction) {
            when (kind) {
                ClassDef.METHOD -> nameFunction(fn, key, null)
                ClassDef.GETTER -> nameFunction(fn, key, "get")
                ClassDef.SETTER -> nameFunction(fn, key, "set")
            }
        }
        if (kind == ClassDef.ACCESSOR) makeAutoAccessor(d, e)
        d.elements.add(e)
    }

    private fun nameFunction(fn: JSFunction, key: Any, prefix: String?) {
        if (key is PrivateName) fn.defineOwn("name", if (prefix != null) "$prefix ${key.description}" else key.description, Attr.CONFIGURABLE)
        else fn.setFunctionName(key, prefix)
    }

    /** `accessor x = v`: private storage plus a getter and a setter for it. */
    private fun makeAutoAccessor(d: ClassDef, e: ClassDef.Element) {
        val realm = d.ctor.realm
        val name = if (e.key is PrivateName) (e.key as PrivateName).description else PK.toStringKey(e.key)
        val storage = PrivateName("$name accessor storage")
        e.storage = storage
        val g = NativeFunction(realm, "", 0, { _, t, _, _ -> Rt.privateGet(t, storage) })
        val s = NativeFunction(realm, "", 1, { _, t, a, _ -> Rt.privateSet(t, storage, a.arg(0)); Undefined })
        nameFunction(g, e.key, "get")
        nameFunction(s, e.key, "set")
        e.getter = g
        e.setter = s
    }

    // ------------------------------------------------------------------ CLASS_FINISH

    @JvmStatic
    fun finish(def: Any?) {
        val d = def as ClassDef
        val ctor = d.ctor
        // ClassDefinitionEvaluation: static methods and accessors are decorated (and defined) first, then instance
        // ones, then static fields, then instance fields
        for (e in d.elements) if (e.isStatic && e.kind != ClassDef.FIELD && e.kind != ClassDef.BLOCK) decorateAndDefine(d, e)
        for (e in d.elements) if (!e.isStatic && e.kind != ClassDef.FIELD) decorateAndDefine(d, e)
        for (e in d.elements) if (e.isStatic && e.kind == ClassDef.FIELD && !e.decorators.isNullOrEmpty()) applyElementDecorators(d, e)
        for (e in d.elements) if (!e.isStatic && e.kind == ClassDef.FIELD && !e.decorators.isNullOrEmpty()) applyElementDecorators(d, e)
        // instance fields and accessor storage, in source order
        for (e in d.elements) {
            if (e.isStatic) continue
            when (e.kind) {
                ClassDef.ACCESSOR -> addInstanceField(ctor, FieldRecord(e.storage!!, e.fn).also { it.decoratorInitializers = e.initializers; it.extraInitializers = e.extraInitializers })
                ClassDef.FIELD -> addInstanceField(ctor, FieldRecord(e.key, e.fn).also { it.decoratorInitializers = e.initializers; it.extraInitializers = e.extraInitializers })
            }
        }
        if (d.instanceExtraInitializers.isNotEmpty()) ctor.extraInitializers = ArrayList(d.instanceExtraInitializers)
        // InitializePrivateMethods(F, staticElements), before the class decorators run
        for (e in d.elements) {
            val key = e.key
            if (!e.isStatic || key !is PrivateName) continue
            when (e.kind) {
                ClassDef.METHOD, ClassDef.GETTER, ClassDef.SETTER -> Rt.staticPrivateMethod(ctor, key, e.fn, e.kind, rename = false)
                ClassDef.ACCESSOR -> {
                    Rt.staticPrivateMethod(ctor, key, e.getter, ClassDef.GETTER, rename = false)
                    Rt.staticPrivateMethod(ctor, key, e.setter, ClassDef.SETTER, rename = false)
                }
            }
        }
    }

    /** ApplyDecoratorsAndDefineMethod for a method, getter, setter or auto-accessor. */
    private fun decorateAndDefine(d: ClassDef, e: ClassDef.Element) {
        if (!e.decorators.isNullOrEmpty()) applyElementDecorators(d, e)
        val ctor = d.ctor
        val target: JSObject = if (e.isStatic) ctor else d.proto
        val key = e.key
        when (e.kind) {
            ClassDef.METHOD, ClassDef.GETTER, ClassDef.SETTER -> {
                if (key is PrivateName) {
                    // static private methods are added after all elements (InitializePrivateMethods)
                    if (!e.isStatic) Rt.addPrivateMethod(ctor, key, e.fn, e.kind, rename = false)
                } else {
                    val desc = when (e.kind) {
                        ClassDef.GETTER -> PropertyDescriptor().getter(e.fn).enumerable(false).configurable(true)
                        ClassDef.SETTER -> PropertyDescriptor().setter(e.fn).enumerable(false).configurable(true)
                        else -> PropertyDescriptor.data(e.fn, Attr.WC)
                    }
                    target.definePropertyOrThrow(key, desc)
                }
            }
            ClassDef.ACCESSOR -> {
                if (key is PrivateName) {
                    if (!e.isStatic) {
                        Rt.addPrivateMethod(ctor, key, e.getter, ClassDef.GETTER, rename = false)
                        Rt.addPrivateMethod(ctor, key, e.setter, ClassDef.SETTER, rename = false)
                    }
                } else {
                    target.definePropertyOrThrow(key, PropertyDescriptor().getter(e.getter).setter(e.setter).enumerable(false).configurable(true))
                }
            }
        }
    }

    private fun addInstanceField(ctor: JSClosure, fr: FieldRecord) {
        var fl = ctor.fields
        if (fl == null) {
            fl = ArrayList()
            ctor.fields = fl
        }
        fl.add(fr)
    }

    private fun kindName(kind: Int) = when (kind) {
        ClassDef.METHOD -> "method"
        ClassDef.GETTER -> "getter"
        ClassDef.SETTER -> "setter"
        ClassDef.FIELD -> "field"
        ClassDef.ACCESSOR -> "accessor"
        else -> "class"
    }

    /** ApplyDecoratorsToElementDefinition: decorators are called innermost (last) first. */
    private fun applyElementDecorators(d: ClassDef, e: ClassDef.Element) {
        val realm = d.ctor.realm
        val decs = e.decorators!!
        for (i in decs.indices.reversed()) {
            val dec = decs[i]
            if (!Ops.isCallable(dec)) throw JSException.typeError("Decorator is not a function")
            val finished = booleanArrayOf(false)
            val ctx = elementContext(d, e, realm, finished)
            val value: Any? = when (e.kind) {
                ClassDef.FIELD -> Undefined
                ClassDef.ACCESSOR -> JSObject(realm.objectPrototype).also {
                    it.createDataProperty("get", e.getter)
                    it.createDataProperty("set", e.setter)
                }
                else -> e.fn
            }
            val r = try {
                Ops.call(dec, Undefined, arrayOf(value, ctx))
            } finally {
                finished[0] = true
            }
            if (r === Undefined) continue
            when (e.kind) {
                ClassDef.METHOD, ClassDef.GETTER, ClassDef.SETTER -> {
                    if (!Ops.isCallable(r)) throw JSException.typeError("Method decorators must return a function or undefined")
                    e.fn = r
                }
                ClassDef.FIELD -> {
                    if (!Ops.isCallable(r)) throw JSException.typeError("Field decorators must return a function or undefined")
                    (e.initializers ?: ArrayList<JSObject>().also { e.initializers = it }).add(r as JSObject)
                }
                ClassDef.ACCESSOR -> {
                    if (r !is JSObject) throw JSException.typeError("Accessor decorators must return an object or undefined")
                    val g = r.get("get", r)
                    if (g !== Undefined) {
                        if (!Ops.isCallable(g)) throw JSException.typeError("Accessor decorator 'get' must be a function")
                        e.getter = g
                    }
                    val s = r.get("set", r)
                    if (s !== Undefined) {
                        if (!Ops.isCallable(s)) throw JSException.typeError("Accessor decorator 'set' must be a function")
                        e.setter = s
                    }
                    val init = r.get("init", r)
                    if (init !== Undefined) {
                        if (!Ops.isCallable(init)) throw JSException.typeError("Accessor decorator 'init' must be a function")
                        (e.initializers ?: ArrayList<JSObject>().also { e.initializers = it }).add(init as JSObject)
                    }
                }
            }
        }
    }

    /** The decorator context object of a class element. */
    private fun elementContext(d: ClassDef, e: ClassDef.Element, realm: Realm, finished: BooleanArray): JSObject {
        val ctx = JSObject(realm.objectPrototype)
        val key = e.key
        val isPrivate = key is PrivateName
        ctx.createDataProperty("kind", kindName(e.kind))
        ctx.createDataProperty("name", if (key is PrivateName) key.description else PK.toValue(key))
        val access = JSObject(realm.objectPrototype)
        val canGet = e.kind != ClassDef.SETTER
        val canSet = e.kind == ClassDef.SETTER || e.kind == ClassDef.FIELD || e.kind == ClassDef.ACCESSOR
        access.createDataProperty("has", NativeFunction(realm, "has", 1, { _, _, a, _ ->
            val o = a.arg(0) as? JSObject ?: throw JSException.typeError("access.has requires an object")
            if (key is PrivateName) Rt.privateIn(key, o) else o.hasProperty(key)
        }))
        if (canGet) access.createDataProperty("get", NativeFunction(realm, "get", 1, { _, _, a, _ ->
            val o = a.arg(0)
            if (key is PrivateName) Rt.privateGet(o, key) else Ops.getV(o, key)
        }))
        if (canSet) access.createDataProperty("set", NativeFunction(realm, "set", 2, { f, _, a, _ ->
            val o = a.arg(0)
            if (key is PrivateName) Rt.privateSet(o, key, a.arg(1))
            else if (!Ops.putV(f.realm, o, key, a.arg(1))) throw JSException.typeError("Cannot assign to '${PK.toStringKey(key)}'")
            Undefined
        }))
        ctx.createDataProperty("access", access)
        ctx.createDataProperty("static", e.isStatic)
        ctx.createDataProperty("private", isPrivate)
        val list = when {
            e.kind == ClassDef.FIELD || e.kind == ClassDef.ACCESSOR -> e.extraInitializers ?: ArrayList<JSObject>().also { e.extraInitializers = it }
            e.isStatic -> d.staticExtraInitializers
            else -> d.instanceExtraInitializers
        }
        ctx.createDataProperty("addInitializer", addInitializerFunction(realm, list, finished))
        ctx.createDataProperty("metadata", d.metadata)
        return ctx
    }

    private fun addInitializerFunction(realm: Realm, list: MutableList<JSObject>, finished: BooleanArray) =
        NativeFunction(realm, "addInitializer", 1, { _, _, a, _ ->
            if (finished[0]) throw JSException.typeError("addInitializer can only be called while the decorator runs")
            val f = a.arg(0)
            if (!Ops.isCallable(f)) throw JSException.typeError("Initializers must be functions")
            list.add(f as JSObject)
            Undefined
        })

    // ------------------------------------------------------------------ CLASS_DECORATE

    /** Applies class decorators (innermost first); returns the final class value. */
    @JvmStatic
    fun decorateClass(def: Any?, decorators: Any?): Any? {
        val d = def as ClassDef
        var value: Any? = d.ctor
        val decs = decorators as? JSArray
        if (decs != null) {
            val realm = d.ctor.realm
            val nameProp = d.ctor.getOwnProperty("name")
            val name: Any? = if (nameProp != null && !nameProp.isAccessor && nameProp.value is CharSequence && (nameProp.value as CharSequence).isNotEmpty()) nameProp.value else Undefined
            for (i in decs.length.toInt() - 1 downTo 0) {
                val dec = decs.get(i, decs)
                if (!Ops.isCallable(dec)) throw JSException.typeError("Decorator is not a function")
                val finished = booleanArrayOf(false)
                val ctx = JSObject(realm.objectPrototype)
                ctx.createDataProperty("kind", "class")
                ctx.createDataProperty("name", name)
                ctx.createDataProperty("addInitializer", addInitializerFunction(realm, d.classExtraInitializers, finished))
                ctx.createDataProperty("metadata", d.metadata)
                val r = try {
                    Ops.call(dec, Undefined, arrayOf(value, ctx))
                } finally {
                    finished[0] = true
                }
                if (r !== Undefined) {
                    if (!Ops.isCallable(r)) throw JSException.typeError("Class decorators must return a function or undefined")
                    value = r
                }
            }
        }
        // decorator metadata, published on the final class of decorated classes only (as TypeScript and Babel do,
        // writable, enumerable and configurable)
        if ((decs != null && decs.length > 0) || d.elements.any { !it.decorators.isNullOrEmpty() }) {
            (value as JSObject).definePropertyOrThrow(JSSymbol.metadata, PropertyDescriptor.data(d.metadata, Attr.ALL))
        }
        d.finalClass = value
        return value
    }

    // ------------------------------------------------------------------ CLASS_STATIC_INIT

    /**
     * Static method extra initializers, static fields / accessors / blocks in order, then class extra initializers.
     * All of them see the class returned by the class decorators (F is replaced before these steps).
     */
    @JvmStatic
    fun staticInit(def: Any?) {
        val d = def as ClassDef
        val c = (d.finalClass ?: d.ctor) as JSObject
        for (init in d.staticExtraInitializers) init.call(c, EMPTY_ARGS)
        for (e in d.elements) {
            if (!e.isStatic) continue
            when (e.kind) {
                ClassDef.FIELD -> Rt.defineField(c, FieldRecord(e.key, e.fn).also { it.decoratorInitializers = e.initializers; it.extraInitializers = e.extraInitializers })
                ClassDef.ACCESSOR -> Rt.defineField(c, FieldRecord(e.storage!!, e.fn).also { it.decoratorInitializers = e.initializers; it.extraInitializers = e.extraInitializers })
                ClassDef.BLOCK -> (e.fn as JSObject).call(c, EMPTY_ARGS)
            }
        }
        for (init in d.classExtraInitializers) init.call(c, EMPTY_ARGS)
    }
}
