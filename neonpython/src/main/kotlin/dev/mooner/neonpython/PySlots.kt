package dev.mooner.neonpython

/** A `__slots__` name's descriptor (member_descriptor): the value at [index] of an instance's slots. */
class PyMemberDescr(@JvmField val owner: PyType, @JvmField val name: String, @JvmField val index: Int) : PyObject(PySlots.MEMBER)

/**
 * `__slots__` as CPython's type_new makes them: each name (mangled) a member descriptor over the instance's slots
 * (PyObject.slots, a value subclass's PySub.slots; the bases' slots first), no `__dict__` (nor weak references) where
 * neither the bases nor the slots give one; CPython's errors. object.__getstate__'s slots part ((dict, slots)).
 */
object PySlots {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val MEMBER: PyType = PyTypes.newBuiltin("member_descriptor", subclassable = false).also { t ->
        t.dispatchBuiltins = true
        fun self(a: Array<Any?>) = a[0] as PyMemberDescr
        fun check(d: PyMemberDescr, obj: Any?) {
            if (!PyTypes.typeOf(obj).isSubtype(d.owner)) {
                throw PyRt.typeError("descriptor '${d.name}' for '${d.owner.name}' objects doesn't apply to a '${PyTypes.name(obj)}' object")
            }
        }
        t.def("__get__", PyBuiltin("__get__") { _, a ->
            val d = self(a)
            val obj = a.getOrNull(1)
            if (obj == null || obj === PyNone) return@PyBuiltin d
            check(d, obj)
            slotsOf(obj, false)?.getOrNull(d.index)
                ?: throw PyException.attributeError(obj, d.name, "'${PyTypes.typeOf(obj).name}' object has no attribute '${d.name}'")
        })
        t.def("__set__", PyBuiltin("__set__") { _, a ->
            val d = self(a)
            check(d, a[1])
            slotsOf(a[1], true)!![d.index] = a[2]
            PyNone
        })
        t.def("__delete__", PyBuiltin("__delete__") { _, a ->
            val d = self(a)
            check(d, a[1])
            val s = slotsOf(a[1], false)
            if (s == null || s[d.index] == null) throw PyException.attributeError(a[1], d.name, d.name)
            s[d.index] = null
            PyNone
        })
        t.def("__repr__", PyBuiltin("__repr__") { _, a -> PyStr("<member '${self(a).name}' of '${self(a).owner.name}' objects>") })
        t.def("__name__", PyProperty(PyBuiltin("__name__") { _, a -> PyStr(self(a).name) }, null, null, null).also { it.name = "__name__" })
        t.def("__qualname__", PyProperty(PyBuiltin("__qualname__") { _, a -> PyStr("${self(a).owner.qualname}.${self(a).name}") }, null, null, null).also { it.name = "__qualname__" })
        t.def("__objclass__", PyProperty(PyBuiltin("__objclass__") { _, a -> self(a).owner }, null, null, null).also { it.name = "__objclass__" })
        t.def("__doc__", PyNone)
    }

    /** The slots of [obj] (made where [make] and its class has any), or null. */
    @JvmStatic
    fun slotsOf(obj: Any?, make: Boolean): Array<Any?>? = when (obj) {
        is PyObject -> obj.slots ?: if (make && obj.type.slotCount > 0) arrayOfNulls<Any?>(obj.type.slotCount).also { obj.slots = it } else null
        is PyValue -> obj.sub?.let { s -> s.slots ?: if (make && s.cls.slotCount > 0) arrayOfNulls<Any?>(s.cls.slotCount).also { s.slots = it } else null }
        else -> null
    }

    /** Whether instances of [b] (a base) have a __dict__ in CPython: a class's own say, an exception's yes, a builtin value's no. */
    private fun dictLayout(b: PyType): Boolean = b.mro.any { it.heap && it.hasDict } || b.isSubtype(PyExceptions.BASE_EXCEPTION)

    private fun weakrefLayout(b: PyType): Boolean = b.mro.any { it.heap && it.hasWeakref } || b.isSubtype(PyExceptions.BASE_EXCEPTION)

    /** _Py_Mangle: `__x` in class C as `_C__x` (not dunders, not where C's name is all underscores). */
    @JvmStatic
    fun mangle(cls: String, name: String): String {
        if (!name.startsWith("__") || name.endsWith("__") || '.' in name) return name
        val stripped = cls.trimStart('_')
        return if (stripped.isEmpty()) name else "_$stripped$name"
    }

    private fun isIdentifier(s: String): Boolean = PyUnicodeType.isIdentifier(s)

    /** A new class [t]'s slots: from its dict's `__slots__` where it has one, else its bases' layout and a __dict__. */
    @JvmStatic
    fun process(t: PyType) {
        val bases = t.bases.toList()
        // (the bases' solid bases, the classes that added their slots: one of them must be a subclass of the others,
        // as CPython's best_base asks; a base adding no slots of its own lays out as its solid base)
        val owners = bases.mapNotNull { it.slotOwner }
        val solid = owners.maxByOrNull { it.slotCount }
        if (solid != null && owners.any { !solid.isSubtype(it) }) {
            throw PyRt.typeError("multiple bases have instance lay-out conflict")
        }
        val inherited = solid?.slotCount ?: 0
        t.slotOwner = solid
        val baseDict = bases.any { dictLayout(it) }
        val baseWeakref = bases.any { weakrefLayout(it) }
        val raw = t.dict[PyStr("__slots__")]
        if (raw == null) {
            t.hasDict = true
            t.hasWeakref = true
            t.slotCount = inherited
            return
        }
        val items: List<Any?> = if (raw is PyStr) listOf(raw) else PyBuiltins.toList(raw)
        var addDict = false
        var addWeakref = false
        val own = ArrayList<String>()
        for (x in items) {
            val s = (x as? PyStr ?: throw PyRt.typeError("__slots__ items must be strings, not '${PyTypes.name(x)}'")).s
            if (!isIdentifier(s)) throw PyRt.typeError("__slots__ must be identifiers")
            when (s) {
                "__dict__" -> {
                    if (baseDict || addDict) throw PyRt.typeError("__dict__ slot disallowed: we already got one")
                    addDict = true
                }
                "__weakref__" -> {
                    if (baseWeakref || addWeakref) throw PyRt.typeError("__weakref__ slot disallowed: we already got one")
                    addWeakref = true
                }
                else -> own.add(mangle(t.name, s))
            }
        }
        for (n in own) if (t.dict.containsKey(PyStr(n))) throw PyException("ValueError", "'$n' in __slots__ conflicts with class variable")
        if (own.isNotEmpty()) {
            val varSized = t.mro.firstOrNull { it === PyTypes.INT || it === PyTypes.BYTES || it === PyTypes.TUPLE }
            if (varSized != null) throw PyRt.typeError("nonempty __slots__ not supported for subtype of '${varSized.name}'")
        }
        for ((i, n) in own.withIndex()) t.dict[PyStr(n)] = PyMemberDescr(t, n, inherited + i)
        t.slotCount = inherited + own.size
        if (own.isNotEmpty()) t.slotOwner = t
        t.hasDict = baseDict || addDict
        t.hasWeakref = baseWeakref || addWeakref
    }

    /** object.__getstate__: its dict (None where empty or none), and where it has slots set, (that, {slot: value}). */
    @JvmStatic
    fun getState(v: Any?): Any? {
        val d = PyClasses.instanceDict(v)
        val state: Any? = if (d == null || d.size == 0) PyNone else d
        val type = PyTypes.typeOf(v)
        if (type.slotCount == 0) return state
        val slots = PyDict()
        val values = slotsOf(v, false)
        // (copyreg._slotnames' order: the class first, through its MRO)
        for (c in type.mro) {
            for ((k, x) in c.dict.map) {
                if (x is PyMemberDescr && x.owner === c) values?.getOrNull(x.index)?.let { slots[k.v] = it }
            }
        }
        return if (slots.size > 0) PyTuple(arrayOf(state, slots)) else state
    }
}
