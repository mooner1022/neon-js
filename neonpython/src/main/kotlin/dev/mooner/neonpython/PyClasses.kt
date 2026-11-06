package dev.mooner.neonpython

import dev.mooner.neonjs.vm.VmFrame

/** A function bound to an object (`obj.method`): calling it calls the function with the object first. */
class PyMethod(@JvmField val fn: Any?, @JvmField val self: Any?) {
    override fun toString() = "<bound method ${(fn as? PyFunction)?.qualname ?: fn} of ${PyRt.repr(self)}>"
}

/** super(type, obj): attribute lookup in obj's MRO after [type]. */
class PySuper(@JvmField val type: PyType, @JvmField val obj: Any?) {
    override fun toString() = "<super: <class '${type.name}'>, <${PyTypes.name(obj)} object>>"
}

/**
 * Classes and their instances (docs/PYTHON_PLATFORM.md §11, M2): `class` statements (__build_class__ running the
 * body on a namespace, type.__new__ making the class), instantiation (__new__ and __init__, with object's rules
 * for arguments it does not take), the attribute protocol of objects, types and functions, super().
 */
object PyClasses {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** object.__new__(cls) and object.__init__(self): builtins of object's dict, which instantiation recognizes. */
    @JvmField val OBJECT_NEW = PyBuiltin("__new__") { _, a ->
        if (a.isEmpty() || a[0] !is PyType) throw PyRt.typeError("object.__new__(X): X is not a type object (${PyTypes.name(a.getOrNull(0))})")
        val cls = a[0] as PyType
        if (a.size > 1 && (cls.lookup("__new__") !== OBJECT_NEW_HOLDER || cls.lookup("__init__") === OBJECT_INIT_HOLDER)) {
            throw PyRt.typeError(if (cls.lookup("__new__") !== OBJECT_NEW_HOLDER) "object.__new__() takes exactly one argument (the type to instantiate)"
                else "${cls.name}() takes no arguments")
        }
        PyObject(cls)
    }
    @JvmField val OBJECT_INIT = PyBuiltin("__init__") { _, a ->
        if (a.size > 1) {
            val cls = PyTypes.typeOf(a[0])
            if (cls.lookup("__init__") !== OBJECT_INIT_HOLDER || cls.lookup("__new__") === OBJECT_NEW_HOLDER) {
                throw PyRt.typeError(if (cls.lookup("__init__") !== OBJECT_INIT_HOLDER) "object.__init__() takes exactly one argument (the instance to initialize)"
                    else "${cls.name}() takes no arguments")
            }
        }
        PyNone
    }
    // (object's __new__ is a staticmethod in its dict: what lookup finds)
    private val OBJECT_NEW_HOLDER: Any = OBJECT_NEW
    private val OBJECT_INIT_HOLDER: Any = OBJECT_INIT

    init {
        PyTypes.OBJECT.def("__new__", OBJECT_NEW)
        PyTypes.OBJECT.def("__init__", OBJECT_INIT)
        PyDunder.defineObjectMethods()
    }

    @JvmStatic fun ensure() {}

    // ------------------------------------------------------------------ class statements

    /** The builtin __build_class__(func, name, *bases, metaclass=None, **kwds). */
    @JvmStatic
    fun buildClass(ctx: PyContext, args: Array<Any?>, kw: PyDict?): Any? {
        if (args.size < 2) throw PyRt.typeError("__build_class__: not enough arguments")
        val body = args[0] as? PyFunction ?: throw PyRt.typeError("__build_class__: func must be a function")
        val name = args[1] as? PyStr ?: throw PyRt.typeError("__build_class__: name is not a string")
        val bases = args.copyOfRange(2, args.size)
        val kwds = PyDict()
        var meta: Any? = null
        if (kw != null) for ((k, v) in kw.map) if ((k.v as PyStr).s == "metaclass") meta = v else kwds.map[k] = v
        val typeBases = bases.map { it as? PyType ?: throw PyRt.typeError("bases must be types") }
        if (meta == null) meta = if (typeBases.isEmpty()) PyTypes.TYPE else PyTypes.typeOf(typeBases[0])
        if (meta is PyType) meta = winner(meta, typeBases)
        val ns = PyDict()
        PyInterpreter.callWithLocals(ctx, body, ns)
        val cls = PyCalls.callWith(ctx, meta, arrayOf(name, PyTuple(bases), ns),
            kwds.map.keys.map { it.v }.toTypedArray(), kwds.map.values.toTypedArray())
        return cls
    }

    /** The most derived metaclass of [meta] and the bases' types, or the conflict's TypeError. */
    private fun winner(meta: PyType, bases: List<PyType>): PyType {
        var w = meta
        for (b in bases) {
            val t = PyTypes.typeOf(b)
            if (w.isSubtype(t)) continue
            if (t.isSubtype(w)) { w = t; continue }
            throw PyRt.typeError("metaclass conflict: the metaclass of a derived class must be a (non-strict) subclass of the metaclasses of all its bases")
        }
        return w
    }

    /** type(name, bases, dict): a new class (type.__new__ and its __init__'s work). */
    @JvmStatic
    fun newClass(meta: PyType, name: PyStr, basesArg: Any?, nsArg: Any?): PyType {
        val bases = (basesArg as? PyTuple ?: throw PyRt.typeError("type.__new__() argument 2 must be tuple, not ${PyTypes.name(basesArg)}"))
            .items.map { it as? PyType ?: throw PyRt.typeError("type.__new__() argument 2 must be a tuple of types") }
        val ns = nsArg as? PyDict ?: throw PyRt.typeError("type.__new__() argument 3 must be dict, not ${PyTypes.name(nsArg)}")
        for (b in bases) if (!b.subclassable) throw PyRt.typeError("type '${b.name}' is not an acceptable base type")
        // (str, list, tuple and dict as bases: their instances are such values; int and float come later)
        for (b in bases) for (x in b.mro) if (x.newImpl != null && x !== PyTypes.OBJECT && x !== PyTypes.TYPE && x !in VALUE_BASES) {
            throw PyRt.typeError("subclassing '${x.name}' is not supported yet")
        }
        // (an instance is one kind of value: two of them among the bases cannot be)
        if (bases.mapNotNull { b -> b.mro.firstOrNull { it in VALUE_BASES || it === PyExceptions.BASE_EXCEPTION } }.distinct().size > 1) {
            throw PyRt.typeError("multiple bases have instance lay-out conflict")
        }
        val t = PyType(name.s, if (bases.isEmpty()) arrayOf(PyTypes.OBJECT) else bases.toTypedArray())
        t.mro = PyTypes.c3(t, t.bases)
        // (a class made by type() without __module__: its caller's, the main module's until import comes, M7)
        t.module = "__main__"
        if (meta !== PyTypes.TYPE) t.meta = meta
        var cell: PyCell? = null
        for ((k, v0) in ns.map) {
            // (__new__ is a staticmethod, __init_subclass__ and __class_getitem__ classmethods, without decorators)
            val v = when ((k.v as? PyStr)?.s) {
                "__new__" -> if (v0 is PyFunction) PyStaticMethod(v0) else v0
                "__init_subclass__", "__class_getitem__" -> if (v0 is PyFunction) PyClassMethod(v0) else v0
                else -> v0
            }
            when ((k.v as? PyStr)?.s) {
                "__qualname__" -> t.qualname = (v as? PyStr ?: throw PyRt.typeError("type __qualname__ must be a str, not ${PyTypes.name(v)}")).s
                "__classcell__" -> cell = v as? PyCell
                else -> {
                    if ((k.v as? PyStr)?.s == "__module__") t.module = (v as? PyStr)?.s ?: t.module
                    t.dict.map[k] = v
                }
            }
        }
        // (a class that defines __eq__ and not __hash__ is unhashable)
        if (t.dict.containsKey(PyStr("__eq__")) && !t.dict.containsKey(PyStr("__hash__"))) t.dict[PyStr("__hash__")] = PyNone
        t.hooks = hooksOf(t)
        cell?.value = t
        return t
    }

    /** The builtin types whose values a class may derive from: their instances are such values (PyValue.sub). */
    private val VALUE_BASES = setOf(PyTypes.STR, PyTypes.LIST, PyTypes.TUPLE, PyTypes.DICT)

    /** The class of an instance of one (an object, or a builtin value of a subclass); null for a builtin value. */
    @JvmStatic
    fun classOf(obj: Any?): PyType? = when (obj) {
        is PyObject -> obj.type
        is PyValue -> obj.sub?.cls
        else -> null
    }

    /** Which of __getattribute__ (1), __setattr__ (2), __delattr__ (4) [t] overrides: what its instances' attributes go through. */
    @JvmStatic
    fun hooksOf(t: PyType): Int {
        var h = 0
        if (PyDunder.userMethod(t, "__getattribute__") != null) h = h or 1
        if (PyDunder.userMethod(t, "__setattr__") != null) h = h or 2
        if (PyDunder.userMethod(t, "__delattr__") != null) h = h or 4
        return h
    }

    /** A class called: __new__, then __init__ where __new__ gave an instance of it. */
    @JvmStatic
    fun instantiate(ctx: PyContext, cls: PyType, pos: Array<Any?>, kwNames: Array<Any?>, kwValues: Array<Any?>): Any? {
        // type(x): the type of x
        if (cls === PyTypes.TYPE && pos.size == 1 && kwNames.isEmpty()) return PyTypes.typeOf(pos[0])
        if (cls.isSubtype(PyTypes.TYPE)) return metaCall(ctx, cls, pos, kwNames, kwValues)
        cls.newImpl?.let { impl ->
            val kw = if (kwNames.isEmpty()) null else PyDict().also { d -> for (i in kwNames.indices) d[kwNames[i]] = kwValues[i] }
            return impl(ctx, cls, pos, kw)
        }
        val new = cls.lookup("__new__")
        val withCls = arrayOfNulls<Any?>(pos.size + 1)
        withCls[0] = cls
        pos.copyInto(withCls, 1)
        val obj = PyCalls.callWith(ctx, new, withCls, kwNames, kwValues)
        if (!PyTypes.typeOf(obj).isSubtype(cls)) return obj
        val init = PyTypes.typeOf(obj).lookup("__init__")
        val withSelf = arrayOfNulls<Any?>(pos.size + 1)
        withSelf[0] = obj
        pos.copyInto(withSelf, 1)
        val r = PyCalls.callWith(ctx, init, withSelf, kwNames, kwValues)
        if (r !== PyNone) throw PyRt.typeError("__init__() should return None, not '${PyTypes.name(r)}'")
        return obj
    }

    /** type or a metaclass called: type(name, bases, dict) makes a class; a metaclass's __new__ and __init__ do. */
    private fun metaCall(ctx: PyContext, meta: PyType, pos: Array<Any?>, kwNames: Array<Any?>, kwValues: Array<Any?>): Any? {
        val new = meta.lookup("__new__")
        if (new == null || new === TYPE_NEW) {
            if (pos.size != 3) throw PyRt.typeError("type() takes 1 or 3 arguments")
            val cls = newClass(meta, pos[0] as? PyStr ?: throw PyRt.typeError("type.__new__() argument 1 must be str, not ${PyTypes.name(pos[0])}"), pos[1], pos[2])
            initSubclass(ctx, cls, kwNames, kwValues)
            return cls
        }
        val withMeta = arrayOfNulls<Any?>(pos.size + 1)
        withMeta[0] = meta
        pos.copyInto(withMeta, 1)
        val cls = PyCalls.callWith(ctx, new, withMeta, kwNames, kwValues)
        val init = PyTypes.typeOf(cls).lookup("__init__")
        if (init != null && init !== OBJECT_INIT && cls is PyType) {
            val withCls = arrayOfNulls<Any?>(pos.size + 1)
            withCls[0] = cls
            pos.copyInto(withCls, 1)
            PyCalls.callWith(ctx, init, withCls, kwNames, kwValues)
        }
        return cls
    }

    /** type.__new__(meta, name, bases, ns, **kw), as a metaclass's __new__ calls it through super(). */
    @JvmField val TYPE_NEW = PyBuiltin("__new__", { _, a ->
        if (a.size != 4) throw PyRt.typeError("type.__new__() takes exactly 3 arguments (${a.size - 1} given)")
        newClass(a[0] as PyType, a[1] as PyStr, a[2], a[3])
    }, { ctx, a, kw ->
        val cls = newClass(a[0] as PyType, a[1] as PyStr, a[2], a[3])
        initSubclass(ctx, cls, kw.map.keys.map { it.v }.toTypedArray(), kw.map.values.toTypedArray())
        cls
    })

    init {
        PyTypes.TYPE.def("__new__", TYPE_NEW)
    }

    /** After a class is made: its descriptors' __set_name__, then its parent's __init_subclass__ (with the class's keywords). */
    @JvmStatic
    fun initSubclass(ctx: PyContext, cls: PyType, kwNames: Array<Any?>, kwValues: Array<Any?>) {
        for ((k, v) in ArrayList(cls.dict.map.entries)) {
            val setName = PyTypes.typeOf(v).lookup("__set_name__") ?: continue
            PyCalls.callWith(ctx, setName, arrayOf(v, cls, k.v), NO_ARGS, NO_ARGS)
        }
        // super(cls, cls).__init_subclass__(**kw): the first after cls in its MRO
        for (i in 1 until cls.mro.size) {
            val f = cls.mro[i].dict[PyStr("__init_subclass__")] ?: continue
            val fn = if (f is PyClassMethod) f.fn else f
            if (fn === OBJECT_INIT_SUBCLASS) {
                if (kwNames.isNotEmpty()) throw PyRt.typeError("${cls.name}.__init_subclass__() takes no keyword arguments")
                return
            }
            PyCalls.callWith(ctx, fn, arrayOf(cls), kwNames, kwValues)
            return
        }
    }

    @JvmField val OBJECT_INIT_SUBCLASS = PyBuiltin("__init_subclass__") { _, _ -> PyNone }

    init {
        PyTypes.OBJECT.def("__init_subclass__", PyClassMethod(OBJECT_INIT_SUBCLASS))
    }

    // ------------------------------------------------------------------ attributes

    /** Whether [d], found on a type, is a data descriptor (it decides before an instance's dict). */
    @JvmStatic
    fun isDataDescriptor(d: Any?): Boolean = when (d) {
        is PyProperty -> true
        is PyObject -> d.type.lookup("__set__") != null || d.type.lookup("__delete__") != null
        else -> false
    }

    /** What [d], found on [type]'s MRO, gives for [obj] (null: looked up on the type itself). */
    @JvmStatic
    fun descrGet(ctx: PyContext?, d: Any?, obj: Any?, type: PyType): Any? = when (d) {
        is PyFunction -> if (obj == null) d else PyMethod(d, obj)
        is PyBuiltin -> if (obj == null) d else PyBoundMethod(obj, d)
        is PyStaticMethod -> d.fn
        is PyClassMethod -> if (d.fn is PyBuiltin) PyBoundMethod(type, d.fn) else PyMethod(d.fn, type)
        is PyProperty -> if (obj == null) d else {
            val g = d.fget ?: throw PyException("AttributeError", "property ${d.nameFor()}of '${type.name}' object has no getter")
            PyCalls.callWith(ctx ?: PyContext.current(), g, arrayOf(obj), NO_ARGS, NO_ARGS)
        }
        is PyObject -> {
            val get = d.type.lookup("__get__")
            if (get == null) d else PyCalls.callWith(ctx ?: PyContext.current(), get, arrayOf(d, obj ?: PyNone, type), NO_ARGS, NO_ARGS)
        }
        else -> d
    }

    /** obj.name for an instance of a class (or a builtin value): its class's __getattribute__, else [genericGetAttr]; __getattr__. */
    @JvmStatic
    fun getAttr(obj: Any?, name: String): Any? {
        val cls = classOf(obj)
        if (cls != null && cls.hooks and 1 != 0) {
            val type = cls
            val gab = PyDunder.userMethod(type, "__getattribute__")!!
            try {
                return PyCalls.callWith(PyContext.current(), gab, arrayOf(obj, PyStr(name)), NO_ARGS, NO_ARGS)
            } catch (e: PyException) {
                val ga = type.lookup("__getattr__")
                if (!e.matches(PyExceptions.type("AttributeError")) || ga == null) throw e
                return PyCalls.callWith(PyContext.current(), ga, arrayOf(obj, PyStr(name)), NO_ARGS, NO_ARGS)
            }
        }
        return attr(obj, name, true)
    }

    /** object.__getattribute__: data descriptors, the instance's dict, other attributes. */
    @JvmStatic
    fun genericGetAttr(obj: Any?, name: String): Any? = attr(obj, name, false)

    private fun attr(obj: Any?, name: String, hook: Boolean): Any? {
        val type = PyTypes.typeOf(obj)
        val d = type.lookup(name)
        if (d != null && isDataDescriptor(d)) return descrGet(null, d, obj, type)
        val dict = instanceDict(obj)
        if (dict != null) dict[PyStr(name)]?.let { return it }
        if (d != null) return descrGet(null, d, obj, type)
        special(obj, name)?.let { return it }
        val ga = if (hook) type.lookup("__getattr__") else null
        if (ga != null) return PyCalls.callWith(PyContext.current(), ga, arrayOf(obj, PyStr(name)), NO_ARGS, NO_ARGS)
        throw PyException.attributeError(obj, name, "'${type.name}' object has no attribute '$name'")
    }

    /** The attributes every object of a kind has (not in a type's dict). */
    private fun special(obj: Any?, name: String): Any? {
        if (name == "__class__") return PyTypes.typeOf(obj)
        return when (obj) {
            is PyObject -> if (name == "__dict__") obj.dict ?: PyDict().also { obj.dict = it } else null
            is PyValue -> {
                val s = obj.sub
                if (name == "__dict__" && s != null) s.dict ?: PyDict().also { s.dict = it } else null
            }
            is PyFunction -> when (name) {
                "__name__" -> PyStr(obj.name)
                "__qualname__" -> PyStr(obj.qualname)
                "__doc__" -> obj.info.doc?.let { PyStr(it) } ?: PyNone
                "__module__" -> obj.globals[PyStr("__name__")] ?: PyNone
                "__defaults__" -> obj.defaults ?: PyNone
                "__kwdefaults__" -> obj.kwdefaults ?: PyNone
                "__closure__" -> obj.closure?.let { PyTuple(it) } ?: PyNone
                "__dict__" -> obj.dict ?: PyDict().also { obj.dict = it }
                "__globals__" -> obj.globals
                else -> null
            }
            is PyMethod -> when (name) {
                "__func__" -> obj.fn
                "__self__" -> obj.self
                else -> if (obj.fn is PyFunction) special(obj.fn, name) ?: (obj.fn as PyFunction).dict?.get(PyStr(name)) else null
            }
            is PyBuiltin -> when (name) {
                "__name__", "__qualname__" -> PyStr(obj.name)
                else -> null
            }
            is PyBoundMethod -> when (name) {
                "__name__", "__qualname__" -> PyStr(obj.fn.name)
                "__self__" -> obj.self
                else -> null
            }
            else -> null
        }
    }

    private fun instanceDict(obj: Any?): PyDict? = when (obj) {
        is PyObject -> obj.dict
        is PyValue -> obj.sub?.dict
        is PyFunction -> obj.dict
        else -> null
    }

    /** C.name: through C's MRO (a function as it is, a classmethod bound to C), then its metaclass's. */
    @JvmStatic
    fun typeAttr(t: PyType, name: String): Any? {
        val meta = PyTypes.typeOf(t)
        val md = if (meta !== PyTypes.TYPE) meta.lookup(name) else null
        if (md != null && isDataDescriptor(md)) return descrGet(null, md, t, meta)
        t.lookup(name)?.let { return descrGet(null, it, null, t) }
        if (md != null) return descrGet(null, md, t, meta)
        return when (name) {
            "__name__" -> PyStr(t.name)
            "__qualname__" -> PyStr(t.qualname)
            "__module__" -> PyStr(t.module)
            "__mro__" -> PyTuple(arrayOf(*t.mro))
            "__bases__" -> PyTuple(arrayOf(*t.bases))
            "__dict__" -> t.dict
            "__doc__" -> PyNone
            "__class__" -> meta
            else -> throw PyException("AttributeError", "type object '${t.name}' has no attribute '$name'")
        }
    }

    /** obj.name = v: its class's __setattr__, else [genericSetAttr]. */
    @JvmStatic
    fun setAttr(obj: Any?, name: String, v: Any?) {
        val cls = classOf(obj)
        if (cls != null && cls.hooks and 2 != 0) {
            PyCalls.callWith(PyContext.current(), PyDunder.userMethod(cls, "__setattr__")!!, arrayOf(obj, PyStr(name), v), NO_ARGS, NO_ARGS)
            return
        }
        genericSetAttr(obj, name, v)
    }

    /** object.__setattr__ (and type's): data descriptors, else the instance's dict. */
    @JvmStatic
    fun genericSetAttr(obj: Any?, name: String, v: Any?) {
        if (obj is PyType) {
            if (obj.module == "builtins") {
                throw PyRt.typeError("cannot set '$name' attribute of immutable type '${obj.name}'")
            }
            obj.dict[PyStr(name)] = v
            if (name == "__getattribute__" || name == "__setattr__" || name == "__delattr__") obj.hooks = hooksOf(obj)
            return
        }
        val type = PyTypes.typeOf(obj)
        val d = type.lookup(name)
        if (d is PyProperty) {
            val s = d.fset ?: throw PyException("AttributeError", "property ${d.nameFor()}of '${type.name}' object has no setter")
            PyCalls.callWith(PyContext.current(), s, arrayOf(obj, v), NO_ARGS, NO_ARGS)
            return
        }
        if (d is PyObject) {
            val set = d.type.lookup("__set__")
            if (set != null) {
                PyCalls.callWith(PyContext.current(), set, arrayOf(d, obj, v), NO_ARGS, NO_ARGS)
                return
            }
        }
        when (obj) {
            is PyObject -> (obj.dict ?: PyDict().also { obj.dict = it })[PyStr(name)] = v
            is PyFunction -> (obj.dict ?: PyDict().also { obj.dict = it })[PyStr(name)] = v
            is PyValue -> {
                val s = obj.sub ?: throw PyException("AttributeError", "'${type.name}' object has no attribute '$name' and no __dict__ for setting new attributes")
                (s.dict ?: PyDict().also { s.dict = it })[PyStr(name)] = v
            }
            else -> throw PyException("AttributeError", "'${type.name}' object has no attribute '$name' and no __dict__ for setting new attributes")
        }
    }

    /** del obj.name: its class's __delattr__, else [genericDelAttr]. */
    @JvmStatic
    fun delAttr(obj: Any?, name: String) {
        val cls = classOf(obj)
        if (cls != null && cls.hooks and 4 != 0) {
            PyCalls.callWith(PyContext.current(), PyDunder.userMethod(cls, "__delattr__")!!, arrayOf(obj, PyStr(name)), NO_ARGS, NO_ARGS)
            return
        }
        genericDelAttr(obj, name)
    }

    /** object.__delattr__: a property's deleter, else the instance's dict. */
    @JvmStatic
    fun genericDelAttr(obj: Any?, name: String) {
        if (obj is PyType) {
            if (obj.dict.map.remove(PyDict.Key(PyStr(name))) == null) throw PyException("AttributeError", "type object '${obj.name}' has no attribute '$name'")
            if (name == "__getattribute__" || name == "__setattr__" || name == "__delattr__") obj.hooks = hooksOf(obj)
            return
        }
        val type = PyTypes.typeOf(obj)
        val d = type.lookup(name)
        if (d is PyProperty) {
            val del = d.fdel ?: throw PyException("AttributeError", "property ${d.nameFor()}of '${type.name}' object has no deleter")
            PyCalls.callWith(PyContext.current(), del, arrayOf(obj), NO_ARGS, NO_ARGS)
            return
        }
        val dict = instanceDict(obj)
        if (dict == null || dict.map.remove(PyDict.Key(PyStr(name))) == null) {
            throw PyException("AttributeError", "'${type.name}' object has no attribute '$name'")
        }
    }

    // ------------------------------------------------------------------ super

    /** super(type, obj). */
    @JvmStatic
    fun newSuper(args: Array<Any?>): Any? {
        if (args.isEmpty()) throw PyException("RuntimeError", "super(): no arguments")
        val t = args[0] as? PyType ?: throw PyRt.typeError("super() argument 1 must be a type, not ${PyTypes.name(args[0])}")
        if (args.size == 1) throw PyRt.typeError("super() with one argument is not supported yet")
        val obj = args[1]
        if (!(obj is PyType && obj.isSubtype(t)) && !PyTypes.typeOf(obj).isSubtype(t)) {
            throw PyRt.typeError("super(type, obj): obj (instance of ${PyTypes.name(obj)}) is not an instance or subtype of type (${t.name}).")
        }
        return PySuper(t, obj)
    }

    @JvmStatic
    fun superAttr(s: PySuper, name: String): Any? {
        val objType = if (s.obj is PyType && s.obj.isSubtype(s.type)) s.obj else PyTypes.typeOf(s.obj)
        val mro = objType.mro
        var i = mro.indexOf(s.type) + 1
        val key = PyDict.Key(PyStr(name))
        while (i < mro.size) {
            val d = mro[i].dict.map[key]
            if (d != null) return descrGet(null, d, if (s.obj === objType) null else s.obj, objType)
            i++
        }
        if (name == "__class__") return PyTypes.typeOf(s)
        throw PyException("AttributeError", "'super' object has no attribute '$name'")
    }

    // ------------------------------------------------------------------ the instructions

    /** LOAD_NAME in a class body: its namespace, the module's globals, the builtins. */
    @JvmStatic fun loadName(f: VmFrame, name: Any?): Any? {
        val pf = f as PyFrame
        return pf.locals?.get(name) ?: pf.globals[name] ?: pf.ctx.builtins[name] ?: throw PyException.nameError(name)
    }

    @JvmStatic fun storeName(f: VmFrame, name: Any?, v: Any?) {
        (f as PyFrame).locals!![name] = v
    }

    @JvmStatic fun deleteName(f: VmFrame, name: Any?) {
        if ((f as PyFrame).locals!!.map.remove(PyDict.Key(name)) == null) throw PyException.nameError(name)
    }

    /** A class body's free variable: its namespace's where it has one, else the cell's. */
    @JvmStatic fun loadFromDictOrDeref(f: VmFrame, cell: Any?, name: Any?): Any? =
        (f as PyFrame).locals?.get(name) ?: PyCalls.cellGet(cell, name, 1)

    /** LOAD_BUILD_CLASS: the builtin __build_class__. */
    @JvmStatic fun loadBuildClass(f: VmFrame): Any? = (f as PyFrame).ctx.builtins[PyStr("__build_class__")]
        ?: throw PyException("NameError", "__build_class__ not found")

    /** STORE_ATTR: (v obj). */
    @JvmStatic fun storeAttr(v: Any?, obj: Any?, name: Any?) = setAttr(obj, (name as PyStr).s, v)
}

/** staticmethod(f): a function a class gives as it is. */
class PyStaticMethod(@JvmField val fn: Any?)

/** classmethod(f): a function a class binds to itself (or to an instance's class). */
class PyClassMethod(@JvmField val fn: Any?)

/** property(fget, fset, fdel, doc). */
class PyProperty(@JvmField val fget: Any?, @JvmField val fset: Any?, @JvmField val fdel: Any?, @JvmField val doc: Any?) {
    /** The name its class gave it (__set_name__). */
    @JvmField var name: String? = null

    /** Its name in messages: `'p' ` where it has one. */
    fun nameFor(): String = (name ?: (fget as? PyFunction)?.name ?: (fset as? PyFunction)?.name)?.let { "'$it' " } ?: ""
}
