package dev.mooner.neonpython

import dev.mooner.neonjs.vm.VmFrame

/** A cell: a variable that nested functions share (null while it has no value). */
class PyCell(@JvmField var value: Any?) {
    override fun toString() = "<cell>"
}

/**
 * Calls with keyword arguments and unpacking, the binding of arguments to a function's parameters (CPython's rules
 * and messages), and the runtime of closures, functions' attributes, unpacking displays and `del`: the instructions
 * of docs/PYTHON_PLATFORM.md §11's M1, for the interpreter and compiled code alike.
 */
object PyCalls {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    // ------------------------------------------------------------------ calls

    /** CALL_KW: [all] holds the positional arguments, the keyword arguments' values, then their names (a tuple). */
    @JvmStatic fun callKw(f: VmFrame, fn: Any?, all: Array<Any?>): Any? {
        val names = (all[all.size - 1] as PyTuple).items
        val np = all.size - 1 - names.size
        return callWith((f as PyFrame).ctx, fn, all.copyOfRange(0, np), names, all.copyOfRange(np, np + names.size))
    }

    /** A call's `*v` (WRAP_STAR), which [callEx] unpacks. */
    class Star(@JvmField val v: Any?)

    @JvmStatic fun wrapStar(v: Any?): Any? = Star(v)

    /**
     * CALL_EX: [rest] holds the positional arguments (a list, `*v` among them as [Star]s), and the keyword
     * arguments' mappings (a list) if any.
     */
    @JvmStatic fun callEx(f: VmFrame, fn: Any?, rest: Array<Any?>): Any? {
        val list = ArrayList<Any?>()
        for (x in (rest[0] as PyList).items) {
            if (x !is Star) { list.add(x); continue }
            val v = x.v
            when (v) {
                is PyTuple -> list.addAll(v.items)
                is PyList -> list.addAll(v.items)
                else -> {
                    val it = try {
                        PyRt.iter(v)
                    } catch (e: PyException) {
                        if (e.type == "TypeError") throw PyRt.typeError("${qualified(fn)} argument after * must be an iterable, not ${PyTypes.name(v)}") else throw e
                    }
                    while (true) {
                        val y = it.next()
                        if (y === PyIterator.STOP) break
                        list.add(y)
                    }
                }
            }
        }
        val pos = list.toTypedArray()
        if (rest.size < 2) return callWith((f as PyFrame).ctx, fn, pos, NO_ARGS, NO_ARGS)
        val names = ArrayList<Any?>()
        val values = ArrayList<Any?>()
        val seen = HashSet<String>()
        for (m in (rest[1] as PyList).items) {
            val d = m as? PyDict ?: throw PyRt.typeError("${qualified(fn)} argument after ** must be a mapping, not ${PyTypes.name(m)}")
            for ((k, v) in d.map) {
                val key = k.v as? PyStr ?: throw PyRt.typeError("keywords must be strings")
                if (!seen.add(key.s)) throw PyRt.typeError("${qualified(fn)} got multiple values for keyword argument '${key.s}'")
                names.add(key)
                values.add(v)
            }
        }
        return callWith((f as PyFrame).ctx, fn, pos, names.toTypedArray(), values.toTypedArray())
    }

    /** [fn] called with [pos] and the keyword arguments [kwNames] (PyStrs) = [kwValues]. */
    @JvmStatic fun callWith(ctx: PyContext, fn: Any?, pos: Array<Any?>, kwNames: Array<Any?>, kwValues: Array<Any?>): Any? = when (fn) {
        is PyFunction -> if (kwNames.isEmpty()) PyInterpreter.call(ctx, fn, pos) else PyInterpreter.callBound(ctx, fn, bind(fn, pos, kwNames, kwValues))
        is PyBuiltin -> if (kwNames.isEmpty()) fn.impl(ctx, pos) else {
            val kw = fn.kwImpl ?: throw PyRt.typeError("${fn.name}() takes no keyword arguments")
            val d = PyDict()
            for (i in kwNames.indices) d[kwNames[i]] = kwValues[i]
            kw(ctx, pos, d)
        }
        is PyBoundMethod -> {
            val all = arrayOfNulls<Any?>(pos.size + 1)
            all[0] = fn.self
            pos.copyInto(all, 1)
            callWith(ctx, fn.fn, all, kwNames, kwValues)
        }
        is PyType -> PyClasses.instantiate(ctx, fn, pos, kwNames, kwValues)
        is PyMethod -> {
            val all = arrayOfNulls<Any?>(pos.size + 1)
            all[0] = fn.self
            pos.copyInto(all, 1)
            callWith(ctx, fn.fn, all, kwNames, kwValues)
        }
        is PyStaticMethod -> callWith(ctx, fn.fn, pos, kwNames, kwValues)
        else -> {
            // an instance whose class has __call__
            val call = (if (fn is PyObject) fn.type.lookup("__call__") else null)
                ?: throw PyRt.typeError("'${PyTypes.name(fn)}' object is not callable")
            val all = arrayOfNulls<Any?>(pos.size + 1)
            all[0] = fn
            pos.copyInto(all, 1)
            callWith(ctx, call, all, kwNames, kwValues)
        }
    }

    /** A function's name as the messages about its unpacked arguments give it: `__main__.f()`. */
    private fun qualified(fn: Any?): String = when (fn) {
        is PyFunction -> (fn.globals[PyStr("__name__")]?.let { "$it." } ?: "") + fn.qualname + "()"
        is PyBuiltin -> fn.name + "()"
        is PyType -> fn.name + "()"
        else -> PyTypes.name(fn) + " object"
    }

    /**
     * The arguments of a call of [fn] bound to its parameters, in its registers' order (positional, keyword-only,
     * *args, **kwargs): defaults filled in, CPython's TypeError where they do not fit.
     */
    @JvmStatic
    fun bind(fn: PyFunction, pos: Array<Any?>, kwNames: Array<Any?>, kwValues: Array<Any?>): Array<Any?> {
        val info = fn.info
        val argc = info.argcount
        val posonly = info.posonlyCount
        val kwonly = info.kwonlyCount
        val names = info.paramNames
        val out = arrayOfNulls<Any?>(fn.params)
        val name = fn.qualname
        // positional
        val n = minOf(pos.size, argc)
        for (i in 0 until n) out[i] = pos[i]
        if (pos.size > argc && !info.varargs) {
            val given = kwNames.count { k -> val s = (k as PyStr).s; names.indexOf(s).let { it in argc until argc + kwonly } }
            val defaults = fn.defaults?.items?.size ?: 0
            val takes = if (defaults > 0) "from ${argc - defaults} to $argc positional arguments" else "$argc positional argument${if (argc == 1) "" else "s"}"
            val was = if (given > 0) "${pos.size} positional argument${if (pos.size == 1) "" else "s"} (and $given keyword-only argument${if (given == 1) "" else "s"}) were given"
                else "${pos.size} ${if (pos.size == 1) "was" else "were"} given"
            throw PyRt.typeError("$name() takes $takes but $was")
        }
        var r = argc + kwonly
        if (info.varargs) {
            out[r] = if (pos.size > argc) PyTuple(pos.copyOfRange(argc, pos.size)) else PyTuple.EMPTY
            r++
        }
        val kwDict = if (info.varkw) PyDict().also { out[r] = it } else null
        // keywords
        var posonlyAsKw: ArrayList<String>? = null
        for (i in kwNames.indices) {
            val k = (kwNames[i] as PyStr).s
            val at = names.indexOf(k)
            if (at in posonly until argc + kwonly) {
                if (out[at] != null) throw PyRt.typeError("$name() got multiple values for argument '$k'")
                out[at] = kwValues[i]
            } else if (kwDict != null) {
                kwDict[kwNames[i]] = kwValues[i]
            } else if (at in 0 until posonly) {
                (posonlyAsKw ?: ArrayList<String>().also { posonlyAsKw = it }).add(k)
            } else throw PyRt.typeError("$name() got an unexpected keyword argument '$k'")
        }
        posonlyAsKw?.let { throw PyRt.typeError("$name() got some positional-only arguments passed as keyword arguments: '${it.joinToString(", ")}'") }
        // defaults
        val defaults = fn.defaults?.items ?: NO_ARGS
        val firstDefault = argc - defaults.size
        val missing = ArrayList<String>()
        for (i in 0 until argc) {
            if (out[i] != null) continue
            if (i >= firstDefault) out[i] = defaults[i - firstDefault] else missing.add(names[i])
        }
        if (missing.isNotEmpty()) throw missingError(name, missing, "positional")
        for (i in argc until argc + kwonly) {
            if (out[i] != null) continue
            out[i] = fn.kwdefaults?.get(PyStr(names[i])) ?: run { missing.add(names[i]); null }
        }
        if (missing.isNotEmpty()) throw missingError(name, missing, "keyword-only")
        return out
    }

    private fun missingError(fn: String, names: List<String>, what: String): PyException {
        val list = when (names.size) {
            1 -> "'${names[0]}'"
            2 -> "'${names[0]}' and '${names[1]}'"
            else -> names.dropLast(1).joinToString(", ") { "'$it'" } + ", and '${names.last()}'"
        }
        return PyRt.typeError("$fn() missing ${names.size} required $what argument${if (names.size == 1) "" else "s"}: $list")
    }

    // ------------------------------------------------------------------ closures and functions

    @JvmStatic fun makeCell(v: Any?): Any? = PyCell(v)
    @JvmStatic fun newCell(): Any? = PyCell(null)

    /** Free variable [k] of [f]'s function: its cell. */
    @JvmStatic fun loadFree(f: VmFrame, k: Int): Any? = (f as PyFrame).closure!![k]

    @JvmStatic fun cellGet(cell: Any?, name: Any?, free: Int): Any? = (cell as PyCell).value ?: throw unbound(name, free)

    @JvmStatic fun cellSet(v: Any?, cell: Any?) {
        (cell as PyCell).value = v
    }

    @JvmStatic fun cellDelete(cell: Any?, name: Any?, free: Int) {
        val c = cell as PyCell
        if (c.value == null) throw unbound(name, free)
        c.value = null
    }

    private fun unbound(name: Any?, free: Int): PyException =
        if (free != 0) PyException("NameError", "cannot access free variable '$name' where it is not associated with a value in enclosing scope")
        else PyException("UnboundLocalError", "cannot access local variable '$name' where it is not associated with a value")

    /** SET_FUNCTION_ATTRIBUTE: [attr] as [fn]'s defaults (1), keyword-only defaults (2) or closure (8). */
    @JvmStatic fun setFunctionAttribute(attr: Any?, fn: Any?, flag: Int): Any? {
        val f = fn as PyFunction
        when (flag) {
            1 -> f.defaults = attr as PyTuple
            2 -> f.kwdefaults = attr as PyDict
            8 -> f.closure = (attr as PyTuple).items
        }
        return f
    }

    // ------------------------------------------------------------------ displays with unpacking

    @JvmStatic fun listAppend(list: Any?, v: Any?): Any? {
        (list as PyList).items.add(v)
        return list
    }

    /** `*it` in a display or a call's arguments. */
    @JvmStatic fun listExtend(list: Any?, it: Any?): Any? {
        val l = (list as PyList).items
        when (it) {
            is PyList -> l.addAll(it.items)
            is PyTuple -> l.addAll(it.items)
            else -> {
                val i = try {
                    PyRt.iter(it)
                } catch (e: PyException) {
                    if (e.type == "TypeError") throw PyRt.typeError("Value after * must be an iterable, not ${PyTypes.name(it)}") else throw e
                }
                while (true) {
                    val x = i.next()
                    if (x === PyIterator.STOP) break
                    l.add(x)
                }
            }
        }
        return list
    }

    @JvmStatic fun listToTuple(list: Any?): Any? = PyTuple((list as PyList).items.toTypedArray())

    @JvmStatic fun mapAdd(dict: Any?, k: Any?, v: Any?): Any? {
        (dict as PyDict)[k] = v
        return dict
    }

    /** `**m` in a dict display. */
    @JvmStatic fun dictUpdate(dict: Any?, m: Any?): Any? {
        val d = dict as PyDict
        val src = m as? PyDict ?: throw PyRt.typeError("'${PyTypes.name(m)}' object is not a mapping")
        for ((k, v) in src.map) d.map[k] = v
        return dict
    }

    /** `a, *b, c = seq`: [before] items, a list of the middle ones, [after] items, the first on top. */
    @JvmStatic fun unpackEx(seq: Any?, before: Int, after: Int): Array<Any?> {
        val items = ArrayList<Any?>()
        when (seq) {
            is PyList -> items.addAll(seq.items)
            is PyTuple -> items.addAll(seq.items)
            else -> {
                val it = try {
                    PyRt.iter(seq)
                } catch (e: PyException) {
                    if (e.type == "TypeError") throw PyRt.typeError("cannot unpack non-iterable ${PyTypes.name(seq)} object") else throw e
                }
                while (true) {
                    val x = it.next()
                    if (x === PyIterator.STOP) break
                    items.add(x)
                }
            }
        }
        if (items.size < before + after) {
            throw PyException("ValueError", "not enough values to unpack (expected at least ${before + after}, got ${items.size})")
        }
        val out = arrayOfNulls<Any?>(before + 1 + after)
        for (i in 0 until before) out[i] = items[i]
        out[before] = PyList(ArrayList(items.subList(before, items.size - after)))
        for (i in 0 until after) out[before + 1 + i] = items[items.size - after + i]
        return out
    }

    // ------------------------------------------------------------------ del, assert

    @JvmStatic fun deleteGlobal(f: VmFrame, name: Any?) {
        if ((f as PyFrame).globals.map.remove(PyDict.Key(name)) == null) throw PyException("NameError", "name '$name' is not defined")
    }

    @JvmStatic fun deleteSubscr(obj: Any?, key: Any?) {
        when (obj) {
            is PyList -> {
                val n = obj.items.size
                val i = (key as? Long ?: (if (key is PyBool) (if (key.value) 1L else 0L) else throw PyRt.typeError("list indices must be integers or slices, not ${PyTypes.name(key)}")))
                val k = if (i < 0) i + n else i
                if (k < 0 || k >= n) throw PyException("IndexError", "list assignment index out of range")
                obj.items.removeAt(k.toInt())
            }
            is PyDict -> if (obj.map.remove(PyDict.Key(key)) == null) throw PyException("KeyError", PyRt.repr(key))
            is PyObject -> PyDunder.delItem(obj, key)
            else -> throw PyRt.typeError("'${PyTypes.name(obj)}' object doesn't support item deletion")
        }
    }

    @JvmStatic fun deleteAttr(obj: Any?, name: Any?) = PyClasses.delAttr(obj, (name as PyStr).s)

    /** `assert` failing: an AssertionError, with the message where there is one. */
    @JvmStatic fun assertFail(msg: Array<Any?>) {
        throw PyException("AssertionError", if (msg.isEmpty()) "" else PyRt.str(msg[0]))
    }
}
