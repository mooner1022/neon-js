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
    // (keyword arguments are excess arguments as positional ones are)
    @JvmField val OBJECT_NEW = PyBuiltin("__new__", { _, a -> objectNew(a, false) }, { _, a, kw -> objectNew(a, kw.size > 0) })
    @JvmField val OBJECT_INIT = PyBuiltin("__init__", { _, a -> objectInit(a, false) }, { _, a, kw -> objectInit(a, kw.size > 0) })

    private fun objectNew(a: Array<Any?>, kw: Boolean): Any? {
        if (a.isEmpty() || a[0] !is PyType) throw PyRt.typeError("object.__new__(X): X is not a type object (${PyTypes.name(a.getOrNull(0))})")
        val cls = a[0] as PyType
        if (cls.isAbstract) abstractError(cls)
        if ((a.size > 1 || kw) && (cls.lookup("__new__") !== OBJECT_NEW_HOLDER || cls.lookup("__init__") === OBJECT_INIT_HOLDER)) {
            throw PyRt.typeError(if (cls.lookup("__new__") !== OBJECT_NEW_HOLDER) "object.__new__() takes exactly one argument (the type to instantiate)"
                else "${cls.name}() takes no arguments")
        }
        return PyObject(cls).also { if (cls.heap) PyWeakrefs.made(it) }
    }

    /** "Can't instantiate abstract class A without an implementation for abstract methods 'a', 'b'". */
    private fun abstractError(cls: PyType): Nothing {
        val names = cls.dict[PyStr("__abstractmethods__")]?.let { PyBuiltins.toList(it) } ?: emptyList()
        val sorted = ArrayList(names).also { PyLibrary.sort(it, null, false) }
        val list = sorted.joinToString(", ") { PyRt.repr(it) }
        throw PyRt.typeError("Can't instantiate abstract class ${cls.name} without an implementation for abstract method${if (sorted.size == 1) "" else "s"} $list")
    }

    private fun objectInit(a: Array<Any?>, kw: Boolean): Any? {
        if (a.size > 1 || kw) {
            val cls = PyTypes.typeOf(a[0])
            if (cls.lookup("__init__") !== OBJECT_INIT_HOLDER || cls.lookup("__new__") === OBJECT_NEW_HOLDER) {
                throw PyRt.typeError(if (cls.lookup("__init__") !== OBJECT_INIT_HOLDER) "object.__init__() takes exactly one argument (the instance to initialize)"
                    else "${cls.name}() takes no arguments")
            }
        }
        return PyNone
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
        val origBases = args.copyOfRange(2, args.size)
        // (PEP 560: a base that is no type, list[int], gives the classes its __mro_entries__ says)
        val resolved = PyTyping.mroEntries(ctx, origBases)
        val bases = resolved ?: origBases
        val kwds = PyDict()
        var meta: Any? = null
        if (kw != null) for ((k, v) in kw.map) if ((k.v as PyStr).s == "metaclass") meta = v else kwds.map[k] = v
        val typeBases = bases.map { it as? PyType ?: throw PyRt.typeError("bases must be types") }
        if (meta == null) meta = if (typeBases.isEmpty()) PyTypes.TYPE else PyTypes.typeOf(typeBases[0])
        if (meta is PyType) meta = winner(meta, typeBases)
        // (the namespace the metaclass's __prepare__ gives, with the class's keywords: enum's _EnumDict)
        val ns = if (meta === PyTypes.TYPE) PyDict() else {
            val prepare = attrOrNull(meta, "__prepare__")
            val made = if (prepare == null) PyDict() else PyCalls.callWith(ctx, prepare, arrayOf(name, PyTuple(bases)),
                kwds.map.keys.map { it.v }.toTypedArray(), kwds.map.values.toTypedArray())
            made as? PyDict ?: throw PyRt.typeError(
                if (made is PyObject && made.type.lookup("__getitem__") != null) "${(meta as? PyType)?.name ?: PyTypes.name(meta)}.__prepare__() must return a dict or a dict's subclass here (a class body's namespace)"
                else "${(meta as? PyType)?.name ?: PyTypes.name(meta)}.__prepare__() must return a mapping, not ${PyTypes.name(made)}")
        }
        PyInterpreter.callWithLocals(ctx, body, ns)
        if (resolved != null) ns[PyStr("__orig_bases__")] = PyTuple(origBases)
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
        for (b in bases) if (!b.subclassable) throw PyRt.typeError("type '${b.tpName ?: b.name}' is not an acceptable base type")
        for ((i, b) in bases.withIndex()) if (bases.subList(0, i).any { it === b }) throw PyRt.typeError("duplicate base class ${b.name}")
        // (str, list, tuple, dict, int and float as bases: their instances are such values (an int's, a float's a PyNumSub))
        for (b in bases) for (x in b.mro) if (x.newImpl != null && x !== PyTypes.OBJECT && x !== PyTypes.TYPE && x !in VALUE_BASES) {
            throw PyRt.typeError("subclassing '${x.name}' is not supported yet")
        }
        // (an instance is one kind of value: two of them among the bases cannot be)
        if (bases.mapNotNull { b -> b.mro.firstOrNull { it in VALUE_BASES || it === PyExceptions.BASE_EXCEPTION } }.distinct().size > 1) {
            throw PyRt.typeError("multiple bases have instance lay-out conflict")
        }
        val t = PyType(name.s, if (bases.isEmpty()) arrayOf(PyTypes.OBJECT) else bases.toTypedArray(), heap = true)
        t.mro = PyTypes.c3(t, t.bases)
        PyContext.current().let { ctx -> for (b in t.bases) b.addSubclass(ctx, t) }
        // (a class made by type() without __module__: its caller's, the main module's until import comes, M7)
        t.module = "__main__"
        if (meta !== PyTypes.TYPE) t.meta = meta
        t.heap = true
        t.dispatchBuiltins = bases.any { it.dispatchBuiltins }
        var cell: PyCell? = null
        var dictCell: PyCell? = null
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
                "__classdictcell__" -> dictCell = v as? PyCell
                else -> {
                    if ((k.v as? PyStr)?.s == "__module__") t.module = (v as? PyStr)?.s ?: t.module
                    t.dict.map[k] = v
                }
            }
        }
        // (its __slots__: member descriptors, and whether its instances have a __dict__)
        PySlots.process(t)
        // (a class that defines __eq__ and not __hash__ is unhashable)
        if (t.dict.containsKey(PyStr("__eq__")) && !t.dict.containsKey(PyStr("__hash__"))) t.dict[PyStr("__hash__")] = PyNone
        t.hooks = hooksOf(t)
        cell?.value = t
        // (its annotation scopes read its dict from now on, not the namespace its body ran in)
        dictCell?.value = t.dict
        return t
    }

    /** The builtin types whose values a class may derive from: their instances are such values (PyValue.sub). */
    private val VALUE_BASES = setOf(PyTypes.STR, PyTypes.LIST, PyTypes.TUPLE, PyTypes.DICT, PyTypes.SET, PyTypes.FROZENSET, PyTypes.INT, PyTypes.FLOAT, PyTypes.COMPLEX, PyTypes.BYTES,
        PyTypes.STATICMETHOD, PyTypes.CLASSMETHOD, PyTypes.PROPERTY, PyTypes.BYTEARRAY, PyTyping.GENERIC_ALIAS)

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

    /** A class called: its metaclass's own __call__ where it has one (enum's EnumType), else [construct]. */
    @JvmStatic
    fun instantiate(ctx: PyContext, cls: PyType, pos: Array<Any?>, kwNames: Array<Any?>, kwValues: Array<Any?>): Any? {
        val meta = cls.meta
        if (meta != null) PyDunder.userMethod(meta, "__call__")?.let { call ->
            // (a __call__ not a method, as _TypedDictMeta's `__call__ = dict`: what its __get__ gives, called as it is)
            if (!(call is PyFunction || call is PyBuiltin && call.method)) {
                return PyCalls.callWith(ctx, descrGet(ctx, call, cls, meta), pos, kwNames, kwValues)
            }
            val all = arrayOfNulls<Any?>(pos.size + 1)
            all[0] = cls
            pos.copyInto(all, 1)
            return PyCalls.callWith(ctx, call, all, kwNames, kwValues)
        }
        return construct(ctx, cls, pos, kwNames, kwValues)
    }

    /** type.__call__: __new__, then __init__ where __new__ gave an instance of the class. */
    @JvmStatic
    fun construct(ctx: PyContext, cls: PyType, pos: Array<Any?>, kwNames: Array<Any?>, kwValues: Array<Any?>): Any? {
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
            return typeNew(ctx, meta, pos, kwNames, kwValues)
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

    /**
     * type.__new__'s work, as CPython's type_new does it: the most derived metaclass of [meta] and the bases' types
     * makes the class, its own __new__ where it has one (`type(name, (Enum,), ns, boundary=b)` is EnumType's), else
     * the class made, its descriptors' __set_name__ and its parent's __init_subclass__ called (with the keywords).
     */
    private fun typeNew(ctx: PyContext, meta: PyType, pos: Array<Any?>, kwNames: Array<Any?>, kwValues: Array<Any?>): Any? {
        val name = pos[0] as? PyStr ?: throw PyRt.typeError("type.__new__() argument 1 must be str, not ${PyTypes.name(pos[0])}")
        val bases = pos[1] as? PyTuple ?: throw PyRt.typeError("type.__new__() argument 2 must be tuple, not ${PyTypes.name(pos[1])}")
        val w = winner(meta, bases.items.map { it as? PyType ?: throw PyRt.typeError("type.__new__() argument 2 must be a tuple of types") })
        if (w !== meta) {
            val wn = w.lookup("__new__")
            if (wn != null && wn !== TYPE_NEW) {
                val withMeta = arrayOfNulls<Any?>(pos.size + 1)
                withMeta[0] = w
                pos.copyInto(withMeta, 1)
                return PyCalls.callWith(ctx, wn, withMeta, kwNames, kwValues)
            }
        }
        val cls = newClass(w, name, bases, pos[2])
        initSubclass(ctx, cls, kwNames, kwValues)
        return cls
    }

    /** type.__new__(meta, name, bases, ns, **kw), as a metaclass's __new__ calls it through super(). */
    @JvmField val TYPE_NEW = PyBuiltin("__new__", { ctx, a ->
        if (a.size != 4) throw PyRt.typeError("type.__new__() takes exactly 3 arguments (${a.size - 1} given)")
        // (its descriptors' __set_name__ and its parent's __init_subclass__ whether keywords came or not: enum's members)
        typeNew(ctx, a[0] as? PyType ?: throw PyRt.typeError("type.__new__(X): X is not a type object (${PyTypes.name(a[0])})"),
            a.copyOfRange(1, 4), NO_ARGS, NO_ARGS)
    }, { ctx, a, kw ->
        if (a.size != 4) throw PyRt.typeError("type.__new__() takes exactly 3 arguments (${a.size - 1} given)")
        typeNew(ctx, a[0] as? PyType ?: throw PyRt.typeError("type.__new__(X): X is not a type object (${PyTypes.name(a[0])})"),
            a.copyOfRange(1, 4), kw.map.keys.map { it.v }.toTypedArray(), kw.map.values.toTypedArray())
    })

    init {
        PyTypes.TYPE.def("__new__", TYPE_NEW)
        // (type.__init__: its arguments checked, nothing done, as CPython's type_init)
        fun typeInit(a: Array<Any?>, kw: PyDict?): Any? {
            val n = a.size - 1
            if (kw != null && kw.size > 0 && n == 1) throw PyRt.typeError("type.__init__() takes no keyword arguments")
            if (n != 1 && n != 3) throw PyRt.typeError("type.__init__() takes 1 or 3 arguments")
            return PyNone
        }
        PyTypes.TYPE.def("__init__", PyBuiltin("__init__", { _, a -> typeInit(a, null) }, { _, a, kw -> typeInit(a, kw) }))
        PyAttrDescrs.defineTypeDescriptors()
        fun self(a: Array<Any?>, n: String): PyType = a.getOrNull(0) as? PyType
            ?: throw PyRt.typeError("descriptor '$n' for 'type' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")
        PyTypes.TYPE.def("__subclasses__", PyBuiltin("__subclasses__") { ctx, a -> PyList(ArrayList<Any?>(self(a, "__subclasses__").subclasses(ctx))) })
        PyTypes.TYPE.def("mro", PyBuiltin("mro") { _, a -> val t = self(a, "mro"); PyList(ArrayList<Any?>(PyTypes.c3(t, t.bases).toList())) })
        PyTypes.TYPE.def("__instancecheck__", PyBuiltin("__instancecheck__") { _, a -> PyBool.of(PyTypes.typeOf(a[1]).isSubtype(self(a, "__instancecheck__"))) })
        PyTypes.TYPE.def("__subclasscheck__", PyBuiltin("__subclasscheck__") { _, a ->
            val c = a[1] as? PyType ?: throw PyRt.typeError("issubclass() arg 1 must be a class")
            PyBool.of(c.isSubtype(self(a, "__subclasscheck__")))
        })
        fun kwOf(kw: PyDict?): Pair<Array<Any?>, Array<Any?>> =
            if (kw == null || kw.size == 0) NO_ARGS to NO_ARGS else kw.map.keys.map { it.v }.toTypedArray() to kw.map.values.toTypedArray()
        fun typeCall(ctx: PyContext, a: Array<Any?>, kw: PyDict?): Any? {
            val (n, v) = kwOf(kw)
            return construct(ctx, self(a, "__call__"), a.copyOfRange(1, a.size), n, v)
        }
        PyTypes.TYPE.def("__call__", PyBuiltin("__call__", { ctx, a -> typeCall(ctx, a, null) }, { ctx, a, kw -> typeCall(ctx, a, kw) }))
        // (what calling a function, a builtin, a method, an alias is, in their types' dicts: collections.abc's Callable looks there)
        for (t in listOf(PyTypes.FUNCTION, PyTypes.BUILTIN_FUNCTION, PyTypes.METHOD, PyTypes.STATICMETHOD, PyTyping.GENERIC_ALIAS)) {
            fun call(ctx: PyContext, a: Array<Any?>, kw: PyDict?): Any? {
                val (n, v) = kwOf(kw)
                return PyCalls.callWith(ctx, a[0], a.copyOfRange(1, a.size), n, v)
            }
            t.def("__call__", PyBuiltin("__call__", { ctx, a -> call(ctx, a, null) }, { ctx, a, kw -> call(ctx, a, kw) }))
        }
        // (a function is a descriptor: f.__get__(obj) a method, partialmethod's way)
        PyTypes.FUNCTION.def("__get__", PyBuiltin("__get__") { _, a ->
            val obj = a.getOrNull(1)
            if (obj == null || obj === PyNone) a[0] else PyMethod(a[0] as PyFunction, obj)
        })
        // (a class body's namespace: a dict, whatever the arguments)
        PyTypes.TYPE.def("__prepare__", PyClassMethod(PyBuiltin("__prepare__", { _, _ -> PyDict() }, { _, _, _ -> PyDict() })))
        PyTypes.OBJECT.def("__subclasshook__", PyClassMethod(PyBuiltin("__subclasshook__") { _, _ -> PyNotImplemented }))
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
        is PyValue -> d.sub != null && (ownDescr(d, "__set__") != null || ownDescr(d, "__delete__") != null)
        else -> false
    }

    /** The __get__ (__set__, __delete__) of [d]'s class where it is a builtin value's subclass that has one of its own. */
    private fun ownDescr(d: PyValue, name: String): Any? {
        val cls = d.sub?.cls ?: return null
        return PyDunder.userMethod(cls, name)
    }

    /** What [d], found on [type]'s MRO, gives for [obj] (null: looked up on the type itself). */
    @JvmStatic
    fun descrGet(ctx: PyContext?, d: Any?, obj: Any?, type: PyType): Any? {
        if (d is PyValue && d.sub != null) {
            ownDescr(d, "__get__")?.let { return PyCalls.callWith(ctx ?: PyContext.current(), it, arrayOf(d, obj ?: PyNone, type), NO_ARGS, NO_ARGS) }
        }
        return builtinGet(ctx, d, obj, type)
    }

    /** What a builtin descriptor gives (its __get__). */
    @JvmStatic
    fun builtinGet(ctx: PyContext?, d: Any?, obj: Any?, type: PyType): Any? = when (d) {
        is PyFunction -> if (obj == null) d else PyMethod(d, obj)
        is PyBuiltin -> if (obj == null || !d.method) d else PyBoundMethod(obj, d)
        is PyStaticMethod -> d.fn
        is PyClassMethod -> d.fn.let { fn -> if (fn is PyBuiltin) PyBoundMethod(type, fn) else PyMethod(fn, type) }
        is PyProperty -> if (obj == null) d else {
            val g = d.fget ?: throw PyException("AttributeError", "property ${d.nameFor()}of '${type.name}' object has no getter")
            PyCalls.callWith(ctx ?: PyContext.current(), g, arrayOf(if (obj is PyNumSub && g is PyBuiltin && g.numSelf) obj.num else obj), NO_ARGS, NO_ARGS)
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
        if (obj is PyModule) return moduleAttr(obj, name)
        if (obj is PyGenericAlias) {
            // (a subclass's instance: its classes' attributes and its own first)
            obj.sub?.let { s ->
                for (c in s.cls.mro) {
                    if (c === PyTyping.GENERIC_ALIAS) break
                    c.dict[PyStr(name)]?.let { d -> return descrGet(null, d, obj, s.cls) }
                }
                s.dict?.get(PyStr(name))?.let { return it }
            }
            return PyTyping.aliasAttr(obj, name)
        }
        if (obj is PyCode) return PyEval.codeAttr(obj, name)
        if (obj is PyFrameObj) return PyFrames.frameAttr(obj, name)
        // (a proxy: its referent's attributes, its class too)
        if (obj is PyWeakProxy) return getAttr(obj.get(), name)
        if (obj is PyTraceback) return PyFrames.tracebackAttr(obj, name)
        if (obj is PyUnion) return PyTyping.unionAttr(obj, name)
        if (obj is PyTypeParam) return PyTyping.typeParamAttr(obj, name)
        if (obj is PyParamSpecPart) return PyTyping.partAttr(obj, name)
        if (obj is PyTypeAlias) return PyTyping.aliasTypeAttr(obj, name)
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

    /** A module's attribute: its dict's, then its module-level __getattr__ (PEP 562). */
    private fun moduleAttr(m: PyModule, name: String): Any? {
        if (PyAnnotations.isName(name)) return PyAnnotations.moduleGet(m, name)
        m.dict[PyStr(name)]?.let { return it }
        when (name) {
            "__dict__" -> return m.dict
            "__class__" -> return PyImport.MODULE_TYPE
        }
        PyImport.MODULE_TYPE.lookup(name)?.let { return descrGet(null, it, m, PyImport.MODULE_TYPE) }
        m.dict[PyStr("__getattr__")]?.let { return PyCalls.callWith(PyContext.current(), it, arrayOf(PyStr(name)), NO_ARGS, NO_ARGS) }
        throw PyException.attributeError(m, name, "module '${m.name}' has no attribute '$name'")
    }

    /** object.__getattribute__: data descriptors, the instance's dict, other attributes. */
    @JvmStatic
    fun genericGetAttr(obj: Any?, name: String): Any? = attr(obj, name, false)

    private fun attr(obj: Any?, name: String, hook: Boolean): Any? {
        // (a function's __annotations__ and __annotate__: the function type's data descriptors; its __type_params__)
        if (obj is PyFunction && PyAnnotations.isName(name)) return PyAnnotations.functionGet(obj, name)
        if (obj is PyFunction && name == "__type_params__") return obj.typeParams
        if (obj is PyFunction && name == "__code__") return PyEval.functionCode(obj)
        // (a function's, a builtin's, a method's __call__: what calling it does)
        if (name == "__call__" && (obj is PyFunction || obj is PyBuiltin || obj is PyBoundMethod || obj is PyMethod)) {
            return PyBuiltin("__call__", { ctx, a -> PyCalls.callWith(ctx, obj, a, NO_ARGS, NO_ARGS) },
                { ctx, a, kw -> PyCalls.callWith(ctx, obj, a, kw.map.keys.map { it.v }.toTypedArray(), kw.map.values.toTypedArray()) })
        }
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
    private fun wrapperAttr(fn: Any?, name: String): Any? = when (name) {
        "__func__", "__wrapped__" -> fn
        "__module__", "__qualname__", "__name__", "__doc__", "__annotations__" -> getAttr(fn, name)
        "__isabstractmethod__" -> PyBool.of(isAbstract(fn))
        else -> null
    }

    /** getattr(obj, name, None) (null where it has none). */
    @JvmStatic
    fun attrOrNull(obj: Any?, name: String): Any? = try {
        PyRt.getAttr(obj, name)
    } catch (e: PyException) {
        if (!e.matches(PyExceptions.type("AttributeError"))) throw e
        null
    }

    /** Whether [f]'s __isabstractmethod__ is true (abc's abstractmethod marks it). */
    private fun isAbstract(f: Any?): Boolean {
        val v = attrOrNull(f, "__isabstractmethod__") ?: return false
        return PyRt.truthy(v)
    }

    private fun special(obj: Any?, name: String): Any? {
        if (name == "__class__") return PyTypes.typeOf(obj)
        if (obj is PyValue && name == "__dict__") obj.sub?.let { s -> if (s.cls.hasDict) return s.dict ?: PyDict().also { s.dict = it } }
        return when (obj) {
            is PyObject -> if (name == "__dict__" && obj.type.hasDict) obj.dict ?: PyDict().also { obj.dict = it } else null
            is PyFunction -> when (name) {
                "__name__" -> obj.nameObj ?: PyStr(obj.name).also { obj.nameObj = it }
                "__qualname__" -> obj.qualnameObj ?: PyStr(obj.qualname).also { obj.qualnameObj = it }
                "__doc__" -> obj.docObj ?: (obj.info.doc?.let { PyStr(it) } ?: PyNone).also { obj.docObj = it }
                "__module__" -> obj.globals[PyStr("__name__")] ?: PyNone
                "__defaults__" -> obj.defaults ?: PyNone
                "__kwdefaults__" -> obj.kwdefaults ?: PyNone
                "__closure__" -> obj.closure?.let { PyTuple(it) } ?: PyNone
                "__dict__" -> obj.dict ?: PyDict().also { obj.dict = it }
                "__globals__" -> obj.globals
                // (the builtins its code reads: its globals' __builtins__ (a module's dict), else the interpreter's)
                "__builtins__" -> when (val b = obj.globals[PyStr("__builtins__")]) {
                    is PyDict -> b
                    is PyModule -> b.dict
                    else -> PyContext.current().builtins
                }
                else -> null
            }
            is PyMethod -> when (name) {
                "__func__" -> obj.fn
                "__self__" -> obj.self
                else -> if (obj.fn is PyFunction) special(obj.fn, name) ?: (obj.fn as PyFunction).dict?.get(PyStr(name)) else null
            }
            is PyBuiltin -> when (name) {
                "__name__" -> PyStr(obj.name)
                "__qualname__" -> PyStr(obj.ownerType?.let { "${it.qualname}.${obj.name}" } ?: obj.name)
                // (CPython's, for inspect.signature: a function's of its module, a method's of its type)
                "__text_signature__" -> {
                    val key = obj.ownerType?.let { "${it.module}.${it.name}.${obj.name}" } ?: obj.ownerModule?.let { "$it.${obj.name}" }
                    key?.let { PySignatures.MAP[it] }?.let { PyStr(it) } ?: PyNone
                }
                "__module__" -> obj.ownerModule?.let { PyStr(it) } ?: PyNone
                "__self__" -> obj.ownerModule?.let { PyImport.importModule(PyContext.current(), it) } ?: PyNone
                else -> null
            }
            // (a staticmethod's or classmethod's, the function's: CPython copies its attributes, and __wrapped__)
            is PyStaticMethod -> wrapperAttr(obj.fn, name)
            is PyClassMethod -> wrapperAttr(obj.fn, name)
            is PyProperty -> when (name) {
                "fget" -> obj.fget ?: PyNone
                "fset" -> obj.fset ?: PyNone
                "fdel" -> obj.fdel ?: PyNone
                "__doc__" -> obj.doc ?: obj.fget?.let { attrOrNull(it, "__doc__") } ?: PyNone
                "__name__" -> obj.name?.let { PyStr(it) } ?: obj.fget?.let { PyRt.getAttr(it, "__name__") }
                    ?: throw PyException("AttributeError", "'property' object has no attribute '__name__'")
                "__isabstractmethod__" -> PyBool.of(listOf(obj.fget, obj.fset, obj.fdel).any { it != null && isAbstract(it) })
                else -> null
            }
            is PyTemplate -> when (name) {
                "strings" -> PyTuple(obj.strings)
                "interpolations" -> PyTuple(obj.interpolations)
                "values" -> PyTuple(Array(obj.interpolations.size) { (obj.interpolations[it] as PyInterpolation).value })
                else -> null
            }
            is PyInterpolation -> when (name) {
                "value" -> obj.value
                "expression" -> obj.expression
                "conversion" -> obj.conversion
                "format_spec" -> obj.formatSpec
                else -> null
            }
            // (a cell's contents; empty: CPython's ValueError)
            is PyCell -> if (name == "cell_contents") obj.value ?: throw PyException("ValueError", "Cell is empty") else null
            is PyRange -> when (name) {
                "start" -> PyRanges.start(obj)
                "stop" -> PyRanges.stop(obj)
                "step" -> PyRanges.step(obj)
                else -> null
            }
            is PySlice -> when (name) {
                "start" -> obj.start
                "stop" -> obj.stop
                "step" -> obj.step
                else -> null
            }
            is PyGenBase -> when (name) {
                "__name__" -> PyStr(obj.name)
                "__qualname__" -> PyStr(obj.qualname)
                else -> {
                    // gi_ a generator's, cr_ a coroutine's, ag_ an async generator's (its running is its awaitables')
                    val p = when (obj) { is PyCoroutine -> "cr_"; is PyAsyncGenerator -> "ag_"; else -> "gi_" }
                    if (!name.startsWith(p)) null else when (name.substring(3)) {
                        "running" -> PyBool.of(if (obj is PyAsyncGenerator) obj.runningAsync else obj.state == PyGenBase.RUNNING)
                        "suspended" -> PyBool.of(obj.state == PyGenBase.SUSPENDED)
                        "frame" -> PyNone
                        "yieldfrom" -> if (p == "gi_") obj.yieldFrom() ?: PyNone else null
                        "await" -> if (p != "gi_") obj.yieldFrom() ?: PyNone else null
                        else -> null
                    }
                }
            }
            is PyBoundMethod -> when (name) {
                "__name__" -> PyStr(obj.fn.name)
                // (its function's: a method's of its type)
                "__qualname__", "__text_signature__" -> special(obj.fn, name)
                "__self__" -> obj.self
                else -> null
            }
            else -> null
        }
    }

    @JvmStatic fun instanceDict(obj: Any?): PyDict? = when (obj) {
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
        if (PyAnnotations.isName(name)) return PyAnnotations.typeGet(t, name)
        // (a class's own type parameters, not a base's)
        if (name == "__type_params__") return t.dict[PyStr(name)] ?: PyTuple.EMPTY
        // (type's data descriptors, for a class whose metaclass is type: what the class's dict holds of the names does
        // not hide them, as the metatype's data descriptors come first in CPython)
        when (name) {
            "__name__" -> return PyStr(t.name)
            "__qualname__" -> return PyStr(t.qualname)
            "__mro__" -> return PyTuple(arrayOf(*t.mro))
            "__bases__" -> return PyTuple(arrayOf(*t.bases))
        }
        if (meta === PyTypes.TYPE) (PyTypes.TYPE.dict[PyStr(name)] as? PyAttrDescr)?.getter?.let { return it(t) }
        // (its own abstract methods, where they were set: not a base's, not type's)
        if (name == "__abstractmethods__") {
            return (if (t === PyTypes.TYPE) null else t.dict[PyStr(name)]) ?: throw PyException("AttributeError", "__abstractmethods__")
        }
        t.lookup(name)?.let { return descrGet(null, it, null, t) }
        if (md != null) return descrGet(null, md, t, meta)
        // (type's own methods, for a class whose metaclass is type: C.mro(), C.__subclasses__())
        if (meta === PyTypes.TYPE) PyTypes.TYPE.dict[PyStr(name)]?.let { return descrGet(null, it, t, meta) }
        return when (name) {
            "__name__" -> PyStr(t.name)
            "__qualname__" -> PyStr(t.qualname)
            "__module__" -> PyStr(t.module)
            "__mro__" -> PyTuple(arrayOf(*t.mro))
            "__bases__" -> PyTuple(arrayOf(*t.bases))
            "__dict__" -> PyMappingProxies.of(t.dict)
            "__doc__" -> PyNone
            "__type_params__" -> PyTuple.EMPTY
            "__class__" -> meta
            // (a builtin type's attributes its instances have: their descriptors, FunctionType.__code__)
            else -> PyAttrDescrs.of(t, name) ?: throw PyException("AttributeError", "type object '${t.name}' has no attribute '$name'")
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
        if (obj is PyModule) {
            if (PyAnnotations.isName(name)) PyAnnotations.moduleSet(obj, name, v) else obj.dict[PyStr(name)] = v
            return
        }
        if (obj is PyFunction && PyAnnotations.isName(name)) {
            PyAnnotations.functionSet(obj, name, v)
            return
        }
        if (obj is PyFunction && name == "__type_params__") {
            PyTyping.setFunctionTypeParams(obj, v)
            return
        }
        if (obj is PyFunction && (name == "__defaults__" || name == "__kwdefaults__")) {
            setDefaults(obj, name, v)
            return
        }
        if (obj is PyTypeParam) {
            PyTyping.typeParamSet(obj, name, v)
            return
        }
        if (obj is PyFrameObj || obj is PyTraceback) {
            PyFrames.setAttr(obj, name, v)
            return
        }
        if (obj is PyWeakProxy) {
            setAttr(obj.get(), name, v)
            return
        }
        if (obj is PyTypeAlias) PyTyping.aliasTypeSet(name)
        if (obj is PyType) {
            if (obj.module == "builtins") {
                throw PyRt.typeError("cannot set '$name' attribute of immutable type '${obj.name}'")
            }
            if (PyAnnotations.isName(name)) {
                PyAnnotations.typeSet(obj, name, v)
                return
            }
            if (name == "__abstractmethods__") obj.isAbstract = PyRt.truthy(v)
            // (the type's names, not its dict's)
            if (name == "__name__" || name == "__qualname__") {
                val s = v as? PyStr ?: throw PyRt.typeError("can only assign string to ${obj.name}.$name, not '${PyTypes.name(v)}'")
                if (name == "__name__") obj.name = s.s else obj.qualname = s.s
                return
            }
            obj.dict[PyStr(name)] = v
            if (name == "__getattribute__" || name == "__setattr__" || name == "__delattr__") obj.hooks = hooksOf(obj)
            return
        }
        val type = PyTypes.typeOf(obj)
        val d = type.lookup(name)
        if (d is PyValue && d.sub != null) ownDescr(d, "__set__")?.let {
            PyCalls.callWith(PyContext.current(), it, arrayOf(d, obj, v), NO_ARGS, NO_ARGS)
            return
        }
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
        if (obj is PyCell && name == "cell_contents") {
            obj.value = v
            return
        }
        when (obj) {
            is PyObject -> {
                if (!obj.type.hasDict && obj.dict == null) {
                    throw PyException.attributeError(obj, name, "'${obj.type.tpName ?: obj.type.name}' object has no attribute '$name' and no __dict__ for setting new attributes")
                }
                (obj.dict ?: PyDict().also { obj.dict = it })[PyStr(name)] = v
            }
            is PyFunction -> when (name) {
                // (its names: str only, as func_set_name and func_set_qualname take them)
                "__name__" -> {
                    obj.name = (v as? PyStr ?: throw PyRt.typeError("__name__ must be set to a string object")).s
                    obj.nameObj = v
                }
                "__qualname__" -> {
                    obj.qualname = (v as? PyStr ?: throw PyRt.typeError("__qualname__ must be set to a string object")).s
                    obj.qualnameObj = v
                }
                else -> (obj.dict ?: PyDict().also { obj.dict = it })[PyStr(name)] = v
            }
            is PyValue -> {
                val s = obj.sub ?: throw PyException("AttributeError", "'${type.tpName ?: type.name}' object has no attribute '$name' and no __dict__ for setting new attributes")
                if (!s.cls.hasDict) throw PyException("AttributeError", "'${s.cls.tpName ?: s.cls.name}' object has no attribute '$name' and no __dict__ for setting new attributes")
                (s.dict ?: PyDict().also { s.dict = it })[PyStr(name)] = v
            }
            else -> throw PyException("AttributeError", "'${PyTypes.name(obj)}' object has no attribute '$name' and no __dict__ for setting new attributes")
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

    /** f.__defaults__ = (a tuple or None), f.__kwdefaults__ = (a dict or None): what its calls fill in then. */
    private fun setDefaults(f: PyFunction, name: String, v: Any?) {
        if (name == "__defaults__") {
            if (v !== PyNone && v !is PyTuple) throw PyRt.typeError("__defaults__ must be set to a tuple object")
            f.defaults = v as? PyTuple
        } else {
            if (v !== PyNone && v !is PyDict) throw PyRt.typeError("__kwdefaults__ must be set to a dict object")
            f.kwdefaults = v as? PyDict
        }
    }

    /** object.__delattr__: a property's deleter, else the instance's dict. */
    @JvmStatic
    fun genericDelAttr(obj: Any?, name: String) {
        if (obj is PyModule) {
            if (PyAnnotations.isName(name)) PyAnnotations.moduleSet(obj, name, null)
            else if (obj.dict.map.remove(PyDict.Key(PyStr(name))) == null) throw PyException.attributeError(obj, name, "module '${obj.name}' has no attribute '$name'")
            return
        }
        if (obj is PyFunction && PyAnnotations.isName(name)) {
            PyAnnotations.functionSet(obj, name, null)
            return
        }
        if (obj is PyFunction && (name == "__name__" || name == "__qualname__")) throw PyRt.typeError("$name must be set to a string object")
        if (obj is PyFunction && (name == "__defaults__" || name == "__kwdefaults__")) {
            setDefaults(obj, name, PyNone)
            return
        }
        if (obj is PyFunction && name == "__type_params__") {
            PyTyping.setFunctionTypeParams(obj, null)
            return
        }
        if (obj is PyType) {
            if (obj.heap && PyAnnotations.isName(name)) {
                PyAnnotations.typeSet(obj, name, null)
                return
            }
            if (name == "__abstractmethods__") {
                if (obj.dict.map.remove(PyDict.Key(PyStr(name))) == null) throw PyException("AttributeError", "__abstractmethods__")
                obj.isAbstract = false
                return
            }
            if (obj.dict.map.remove(PyDict.Key(PyStr(name))) == null) throw PyException("AttributeError", "type object '${obj.name}' has no attribute '$name'")
            if (name == "__getattribute__" || name == "__setattr__" || name == "__delattr__") obj.hooks = hooksOf(obj)
            return
        }
        if (obj is PyCell && name == "cell_contents") {
            if (obj.value == null) throw PyException("ValueError", "Cell is empty")
            obj.value = null
            return
        }
        val type = PyTypes.typeOf(obj)
        val d = type.lookup(name)
        if (d is PyValue && d.sub != null) ownDescr(d, "__delete__")?.let {
            PyCalls.callWith(PyContext.current(), it, arrayOf(d, obj), NO_ARGS, NO_ARGS)
            return
        }
        if (d is PyProperty) {
            val del = d.fdel ?: throw PyException("AttributeError", "property ${d.nameFor()}of '${type.name}' object has no deleter")
            PyCalls.callWith(PyContext.current(), del, arrayOf(obj), NO_ARGS, NO_ARGS)
            return
        }
        // (a descriptor object's __delete__: a slot's, one of a class's; a data descriptor without one refuses)
        if (d is PyObject) {
            val del = d.type.lookup("__delete__")
            if (del != null) {
                PyCalls.callWith(PyContext.current(), del, arrayOf(d, obj), NO_ARGS, NO_ARGS)
                return
            }
            if (d.type.lookup("__set__") != null) throw PyException("AttributeError", "__delete__")
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
        return namespaceGet(pf.locals, name) ?: pf.globals[name] ?: pf.ctx.builtins[name] ?: throw PyException.nameError(name)
    }

    /**
     * [name] in a class body's namespace, or null: a dict's subclass (a metaclass's __prepare__'s) through its own
     * __getitem__, a KeyError none.
     */
    @JvmStatic
    fun namespaceGet(ns: PyDict?, name: Any?): Any? {
        if (ns == null) return null
        val s = ns.sub ?: return ns[name]
        if (PyDunder.userMethod(s.cls, "__getitem__") == null && PyDunder.userMethod(s.cls, "__missing__") == null) return ns[name]
        return try {
            PyRt.getItem(ns, name)
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("KeyError"))) null else throw e
        }
    }

    @JvmStatic fun storeName(f: VmFrame, name: Any?, v: Any?) {
        val ns = (f as PyFrame).locals!!
        if (ns.sub == null) ns[name] = v else PyRt.setItem(ns, name, v)
    }

    @JvmStatic fun deleteName(f: VmFrame, name: Any?) {
        val ns = (f as PyFrame).locals!!
        if (ns.sub != null) {
            try {
                PyCalls.deleteSubscr(ns, name)
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("KeyError"))) throw PyException.nameError(name) else throw e
            }
            return
        }
        if (ns.map.remove(PyDict.Key(name)) == null) throw PyException.nameError(name)
    }

    /** A class body's free variable: its namespace's where it has one, else the cell's. */
    @JvmStatic fun loadFromDictOrDeref(f: VmFrame, cell: Any?, name: Any?): Any? =
        namespaceGet((f as PyFrame).locals, name) ?: PyCalls.cellGet(cell, name, 1)

    /** LOAD_BUILD_CLASS: the builtin __build_class__. */
    @JvmStatic fun loadBuildClass(f: VmFrame): Any? = (f as PyFrame).ctx.builtins[PyStr("__build_class__")]
        ?: throw PyException("NameError", "__build_class__ not found")

    /** STORE_ATTR: (v obj). */
    @JvmStatic fun storeAttr(v: Any?, obj: Any?, name: Any?) = setAttr(obj, (name as PyStr).s, v)
}

/** staticmethod(f): a function a class gives as it is (a subclass's instance: one with its class, [sub]). */
class PyStaticMethod(@JvmField var fn: Any?) : PyValue() {
    override fun toString() = "<staticmethod(${PyRt.repr(fn)})>"
}

/** classmethod(f): a function a class binds to itself (or to an instance's class). */
class PyClassMethod(@JvmField var fn: Any?) : PyValue() {
    override fun toString() = "<classmethod(${PyRt.repr(fn)})>"
}

/** property(fget, fset, fdel, doc). */
class PyProperty(@JvmField var fget: Any?, @JvmField var fset: Any?, @JvmField var fdel: Any?, @JvmField var doc: Any?) : PyValue() {
    /** The name its class gave it (__set_name__). */
    @JvmField var name: String? = null

    /** Its name in messages: `'p' ` where it has one. */
    fun nameFor(): String = (name ?: (fget as? PyFunction)?.name ?: (fset as? PyFunction)?.name)?.let { "'$it' " } ?: ""
}
