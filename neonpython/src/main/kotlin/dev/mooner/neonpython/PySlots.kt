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

    /**
     * Whether instances of [b] (a base) have a __dict__ in CPython: a class's own say, else its builtin base's layout's
     * (an exception's and a module's yes, a builtin value's no).
     */
    private fun dictLayout(b: PyType): Boolean = b.mro.any { isClass(it) && it.hasDict } || layout(builtinBase(b))[DICTOFFSET] != 0L

    /** Whether instances of [b] have weak references: a class's own say, else its builtin base's (a set's yes, an exception's no). */
    private fun weakrefLayout(b: PyType): Boolean = b.mro.any { isClass(it) && it.hasWeakref } || layout(builtinBase(b))[WEAKREFOFFSET] != 0L

    // CPython 3.14's type flags (Include/object.h) and the layout's parts, in [layout]'s order
    private const val INLINE_VALUES = 1L shl 2
    private const val MANAGED_WEAKREF = 1L shl 3
    private const val MANAGED_DICT = 1L shl 4
    const val SEQUENCE = 1 shl 5
    const val MAPPING = 1 shl 6
    private const val IMMUTABLETYPE = 1L shl 8
    private const val HEAPTYPE = 1L shl 9
    private const val BASETYPE = 1L shl 10
    private const val HAVE_VECTORCALL = 1L shl 11
    private const val READY = 1L shl 12
    private const val HAVE_GC = 1L shl 14
    private const val IS_ABSTRACT = 1L shl 20
    private const val MATCH_SELF = 1L shl 22
    private const val ITEMS_AT_END = 1L shl 23
    private const val SUBCLASSES = 0xFF000000L
    private const val STATIC_BUILTIN = 1L shl 1
    const val FLAGS = 0
    const val WEAKREFOFFSET = 1
    const val DICTOFFSET = 2
    const val BASICSIZE = 3
    const val ITEMSIZE = 4

    /** Whether [t] is a class (made by a class statement or type()), not a builtin type nor a module's C type (a tpName's). */
    private fun isClass(t: PyType): Boolean = t.heap && t.tpName == null

    /** The builtin type whose layout [t]'s extends: [t] where it is one, else its MRO's first (object at last). */
    @JvmStatic
    fun builtinBase(t: PyType): PyType = t.mro.firstOrNull { !isClass(it) } ?: PyTypes.OBJECT

    /** Whether instances of [t] have CPython's pre-header of a managed dict's and weak references' pointers. */
    @JvmStatic
    fun preheader(t: PyType): Boolean = isClass(t) && layout(t)[FLAGS] and (MANAGED_DICT or MANAGED_WEAKREF) != 0L

    /** Whether instances of [t] are the collector's (CPython's Py_TPFLAGS_HAVE_GC): a GC header before each. */
    @JvmStatic
    fun collected(t: PyType): Boolean = layout(t)[FLAGS] and HAVE_GC != 0L

    /** CPython's layout of builtin type [t] (PyLayouts), by its module and name as CPython's are; null where CPython has none. */
    private fun table(t: PyType): LongArray? {
        val n = t.tpName
        return PyLayouts.MAP[if (n != null && '.' in n) n else "${t.module}.${n ?: t.name}"]
    }

    /**
     * [t]'s __flags__, __weakrefoffset__, __dictoffset__, __basicsize__ and __itemsize__, as Linux CPython 3.14 (64
     * bits) lays it out: a builtin type's CPython's (PyLayouts; one CPython has not, its nearest base's sizes), a
     * class's worked out from its builtin base's as type_new does. A managed dict (-1) where it adds a dict to a base
     * without one, with inline values where its items are not in the object; managed weak references (-32) where it
     * adds them to a base without, and not to a base of items (int, bytes, tuple); a slot 8 bytes; the base's subclass
     * flags, MATCH_SELF and vectorcall (where no class overrides __call__), its MRO's collection flags, abstractness.
     */
    @JvmStatic
    fun layout(t: PyType): LongArray {
        if (!isClass(t)) {
            table(t)?.let { return it }
            val b = t.mro.firstNotNullOfOrNull { if (it === t) null else table(it) } ?: table(PyTypes.OBJECT)!!
            val inherited = b[FLAGS] and (SUBCLASSES or MATCH_SELF or SEQUENCE.toLong() or MAPPING.toLong())
            val f = inherited or READY or IMMUTABLETYPE or STATIC_BUILTIN or if (t.subclassable) BASETYPE else 0L
            return longArrayOf(f, 0L, 0L, b[BASICSIZE], b[ITEMSIZE])
        }
        val base = builtinBase(t)
        val b = layout(base)
        var f = HEAPTYPE or BASETYPE or READY or HAVE_GC or (b[FLAGS] and (SUBCLASSES or MATCH_SELF or ITEMS_AT_END)) or collection(t).toLong()
        if (b[FLAGS] and HAVE_VECTORCALL != 0L && t.mro.takeWhile { it !== base }.none { it.dict.containsKey(PyStr("__call__")) }) {
            f = f or HAVE_VECTORCALL
        }
        if (t.isAbstract) f = f or IS_ABSTRACT
        var dict = b[DICTOFFSET]
        var weak = b[WEAKREFOFFSET]
        if (dict == 0L && t.hasDict) {
            dict = -1L
            f = f or MANAGED_DICT or if (b[ITEMSIZE] == 0L) INLINE_VALUES else 0L
        }
        if (weak == 0L && t.hasWeakref && b[ITEMSIZE] == 0L) {
            weak = -32L
            f = f or MANAGED_WEAKREF
        }
        return longArrayOf(f, weak, dict, b[BASICSIZE] + 8L * t.slotCount, b[ITEMSIZE])
    }

    /**
     * CPython's compatible_for_assignment: whether an instance of class [from] may become one of class [to]
     * (`obj.__class__ = to`). Each is walked down its bases while its layout is its base's; the two classes reached
     * must be one, or siblings over one base that add the same slots (in any order: [moveSlots] puts an instance's
     * where its new class keeps them), with their inline values and pre-headers alike.
     */
    @JvmStatic
    fun compatibleForAssignment(to: PyType, from: PyType): Boolean {
        val lt = layout(to)
        val lf = layout(from)
        if (lt[FLAGS] and INLINE_VALUES != lf[FLAGS] and INLINE_VALUES) return false
        var nb = to
        while (sameAsBase(nb)) nb = tpBase(nb)!!
        var ob = from
        while (sameAsBase(ob)) ob = tpBase(ob)!!
        if (nb !== ob && (tpBase(nb) !== tpBase(ob) || !sameSlotsAdded(nb, ob))) return false
        return lt[FLAGS] and (MANAGED_DICT or MANAGED_WEAKREF) == lf[FLAGS] and (MANAGED_DICT or MANAGED_WEAKREF)
    }

    /** [t]'s tp_base: a class's base whose solid base the others' derive from (CPython's best_base), a builtin type's first. */
    private fun tpBase(t: PyType): PyType? {
        if (!isClass(t)) return t.bases.firstOrNull()
        var base: PyType? = null
        var winner: PyType? = null
        for (b in t.bases) {
            val c = solidBase(b)
            if (winner == null || (!winner.isSubtype(c) && c.isSubtype(winner))) {
                winner = c
                base = b
            }
        }
        return base
    }

    /** CPython's solid_base: the type in [t]'s line of bases that last made its instances bigger. */
    private fun solidBase(t: PyType): PyType {
        val base = tpBase(t)?.let { solidBase(it) } ?: PyTypes.OBJECT
        val lt = layout(t)
        val lb = layout(base)
        return if (lt[BASICSIZE] != lb[BASICSIZE] || lt[ITEMSIZE] != lb[ITEMSIZE]) t else base
    }

    /** CPython's compatible_with_tp_base, for a class: its layout its base's (sizes, offsets, collection by the GC). */
    private fun sameAsBase(c: PyType): Boolean {
        if (!isClass(c)) return false
        val b = tpBase(c) ?: return false
        val lc = layout(c)
        val lb = layout(b)
        return lc[BASICSIZE] == lb[BASICSIZE] && lc[ITEMSIZE] == lb[ITEMSIZE] && lc[DICTOFFSET] == lb[DICTOFFSET] &&
            lc[WEAKREFOFFSET] == lb[WEAKREFOFFSET] && lc[FLAGS] and HAVE_GC == lb[FLAGS] and HAVE_GC
    }

    /** CPython's same_slots_added: classes [a] and [b] over one base adding the same slots and nothing more. */
    private fun sameSlotsAdded(a: PyType, b: PyType): Boolean {
        val base = tpBase(a) ?: return false
        val la = layout(a)
        val lb = layout(b)
        var size = layout(base)[BASICSIZE]
        if (la[DICTOFFSET] == size && lb[DICTOFFSET] == size) size += 8
        if (la[WEAKREFOFFSET] == size && lb[WEAKREFOFFSET] == size) size += 8
        if (!isClass(a) || !isClass(b)) return false
        val sa = ownSlots(a)
        val sb = ownSlots(b)
        if (sa != null && sb != null) {
            if (sa.sorted() != sb.sorted()) return false
            size += 8L * sa.size
        }
        return size == la[BASICSIZE] && size == lb[BASICSIZE]
    }

    /**
     * An instance's slots moved from where class [from] keeps them to where [to] does (by name: classes adding the same
     * slots in another order), once its `__class__` is [to].
     */
    @JvmStatic
    fun moveSlots(obj: Any, from: PyType, to: PyType) {
        fun indexes(t: PyType): Map<String, Int> {
            val m = HashMap<String, Int>()
            for (c in t.mro) for (d in c.dict.map.values) if (d is PyMemberDescr && d.owner === c) m.putIfAbsent(d.name, d.index)
            return m
        }
        val fi = indexes(from)
        val ti = indexes(to)
        if (fi == ti) return
        val old = slotsOf(obj, false) ?: return
        val moved = arrayOfNulls<Any?>(to.slotCount)
        for ((n, i) in ti) fi[n]?.let { if (it < old.size && i < moved.size) moved[i] = old[it] }
        when (obj) {
            is PyObject -> obj.slots = moved
            is PyValue -> obj.sub?.slots = moved
        }
    }

    /** The slots class [t] adds (its ht_slots, in their order here), or null where it has no `__slots__`. */
    private fun ownSlots(t: PyType): List<String>? {
        if (!t.dict.containsKey(PyStr("__slots__"))) return null
        return t.dict.map.values.filterIsInstance<PyMemberDescr>().filter { it.owner === t }.sortedBy { it.index }.map { it.name }
    }

    /** Whether CPython takes weak references to instances of class [t] (a value's subclass, an exception's): its layout's say. */
    @JvmStatic
    fun weakrefable(t: PyType): Boolean = layout(t)[WEAKREFOFFSET] != 0L

    /**
     * [t]'s Py_TPFLAGS_SEQUENCE or Py_TPFLAGS_MAPPING (or 0), what MATCH_SEQUENCE and MATCH_MAPPING test: a builtin
     * type's CPython's, a class's its MRO's first set one (inherit_patma_flags) unless abc's [abcInit] or
     * [abcRegister] set it.
     */
    @JvmStatic
    fun collection(t: PyType): Int {
        val c = t.collection
        if (c >= 0) return c
        val r = if (isClass(t)) inheritedCollection(t)
            else table(t)?.let { (it[FLAGS] and (SEQUENCE or MAPPING).toLong()).toInt() } ?: inheritedCollection(t)
        t.collection = r
        return r
    }

    private fun inheritedCollection(t: PyType): Int {
        for (b in t.mro) if (b !== t) collection(b).let { if (it != 0) return it }
        return 0
    }

    /**
     * C _abc's _abc_init's part NeonPy's pure abc (_py_abc) lacks: a new ABC's `__abc_tpflags__` taken from its dict,
     * its collection flags added to the class's (collections.abc's Sequence and Mapping: what `match` takes them as).
     */
    @JvmStatic
    fun abcInit(cls: Any?) {
        val t = cls as? PyType ?: return
        val v = t.dict.map.remove(PyDict.Key(PyStr("__abc_tpflags__")))
        // (an exact int only, as PyLong_CheckExact asks)
        if (v !is Long && v !is java.math.BigInteger) return
        val n = v as? Long ?: throw PyException("OverflowError", "Python int too large to convert to C long")
        val flags = n.toInt() and (SEQUENCE or MAPPING)
        if (flags == SEQUENCE or MAPPING) throw PyRt.typeError("__abc_tpflags__ cannot be both Py_TPFLAGS_SEQUENCE and Py_TPFLAGS_MAPPING")
        t.collection = collection(t) or flags
    }

    /**
     * C _abc's _abc_register's part: a class registered with an ABC that is a sequence or a mapping becomes one, its
     * subclasses too (set_flags_recursive: not a builtin type's, as CPython's immutable types are not).
     */
    @JvmStatic
    fun abcRegister(ctx: PyContext, abc: Any?, sub: Any?) {
        val flags = (abc as? PyType)?.let { collection(it) } ?: return
        if (flags != 0 && sub is PyType) setCollection(ctx, sub, flags)
    }

    private fun setCollection(ctx: PyContext, t: PyType, flags: Int) {
        if (!isClass(t) || collection(t) == flags) return
        t.collection = flags
        for (s in t.subclasses(ctx)) setCollection(ctx, s, flags)
    }

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
        t.collection = inheritedCollection(t)
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
        // (where it adds them, its own __dict__ and __weakref__ descriptors: weak references never over a base of items)
        val items0 = layout(builtinBase(t))[ITEMSIZE] == 0L
        if (raw == null) {
            t.hasDict = true
            t.hasWeakref = true
            t.slotCount = inherited
            PyAttrDescrs.defineInstanceDescriptors(t, !baseDict, !baseWeakref && items0)
            return
        }
        val items: List<Any?> = if (raw is PyStr) listOf(raw) else PyBuiltins.toList(raw)
        // (a base of items, int's, bytes's, tuple's, type's, has no room for any, a __dict__ or __weakref__ either:
        // CPython's first check)
        if (items.isNotEmpty()) {
            val varBase = bases.firstOrNull { layout(builtinBase(it))[ITEMSIZE] != 0L }
            if (varBase != null) {
                throw PyRt.typeError("nonempty __slots__ not supported for subtype of '${if (isClass(varBase)) varBase.name else varBase.tpName ?: varBase.name}'")
            }
        }
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
        for ((i, n) in own.withIndex()) t.dict[PyStr(n)] = PyMemberDescr(t, n, inherited + i)
        t.slotCount = inherited + own.size
        if (own.isNotEmpty()) t.slotOwner = t
        t.hasDict = baseDict || addDict
        t.hasWeakref = baseWeakref || addWeakref
        PyAttrDescrs.defineInstanceDescriptors(t, addDict, addWeakref)
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
