package dev.mooner.neonjs.vm

import dev.mooner.neonjs.compiler.*
import dev.mooner.neonjs.parser.FunctionKind
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.builtins.JSTypedArray

/** Runtime support routines shared by the interpreter and compiled code. */
object Rt {

    // ------------------------------------------------------------------ globals

    @JvmStatic
    fun loadGlobal(realm: Realm, name: String, forTypeof: Boolean): Any? {
        val ge = realm.globalEnv
        val b = ge.lexical[name]
        if (b != null) {
            if (b.value === Uninitialized) throw JSException.referenceError("Cannot access '$name' before initialization")
            return b.value
        }
        val g = ge.global
        val pm = g.props
        if (pm != null) {
            val i = pm.find(name)
            if (i >= 0 && pm.flags[i] and Attr.ACCESSOR == 0) return pm.values[i]
        }
        val v = g.getOwnValue(name, g)
        if (v !== NotFound) return v
        if (!g.hasProperty(name)) {
            if (forTypeof) return Undefined
            throw JSException.referenceError("$name is not defined")
        }
        return g.get(name, g)
    }

    @JvmStatic
    fun storeGlobal(realm: Realm, name: String, value: Any?, strict: Boolean) {
        val ge = realm.globalEnv
        val b = ge.lexical[name]
        if (b != null) {
            if (b.value === Uninitialized) throw JSException.referenceError("Cannot access '$name' before initialization")
            if (!b.mutable) throw JSException.typeError("Assignment to constant variable.")
            b.value = value
            return
        }
        val g = ge.global
        val pm = g.props
        if (pm != null) {
            val i = pm.find(name)
            if (i >= 0 && pm.flags[i] and (Attr.ACCESSOR or Attr.WRITABLE) == Attr.WRITABLE) {
                pm.values[i] = value
                return
            }
        }
        if (strict && !g.hasProperty(name)) throw JSException.referenceError("$name is not defined")
        if (!g.set(name, value, g) && strict) throw JSException.typeError("Cannot assign to read only property '$name' of object '#<Object>'")
    }

    @JvmStatic
    fun initGlobalLexical(realm: Realm, name: String, value: Any?) {
        val b = realm.globalEnv.lexical[name] ?: throw IllegalStateException("global lexical $name not declared")
        b.value = value
    }

    /** Annex B: assigns the var binding of a block-level function in global / eval var scopes. */
    @JvmStatic
    fun storeAnnexBVar(env: Env?, name: String, value: Any?, realm: Realm) {
        var e = env
        while (e != null) {
            if (e is DeclEnv && e.info.evalVarTarget) {
                val ext = e.extension
                if (ext != null && ext.containsKey(name)) {
                    ext[name] = value
                    return
                }
                val i = e.info.lookup(name)
                if (i >= 0) {
                    e.slots[i] = value
                    return
                }
            }
            if (e is GlobalEnv) {
                val g = e.global
                g.set(name, value, g)
                return
            }
            e = e.parent
        }
        val g = realm.globalEnv.global
        g.set(name, value, g)
    }

    @JvmStatic
    fun initThisDynamic(env: Env?, value: Any?) {
        var e = env
        while (e != null) {
            if (e is DeclEnv) {
                val i = e.info.lookup("this")
                if (i >= 0) {
                    if (e.slots[i] !== Uninitialized) throw JSException.referenceError("Super constructor may only be called once")
                    e.slots[i] = value
                    return
                }
            }
            e = e.parent
        }
        throw JSException.syntaxError("'super' keyword unexpected here")
    }

    // ------------------------------------------------------------------ properties

    @JvmStatic
    fun getPrimitiveProp(realm: Realm, o: Any?, key: Any): Any? = Ops.getV(realm, o, key)

    @JvmStatic
    fun readOnlyError(key: Any, o: Any?): JSException =
        JSException.typeError("Cannot assign to read only property '${Ops.describeKey(key)}' of ${Ops.describe(o)}")

    @JvmStatic
    fun putPrimitive(realm: Realm, o: Any?, key: Any, v: Any?, strict: Boolean) {
        if (o === Undefined || o === Null) throw JSException.typeError("Cannot set properties of ${Ops.toDisplayString(o)} (setting '${Ops.describeKey(key)}')")
        if (!Ops.putV(realm, o, key, v) && strict) throw JSException.typeError("Cannot create property '${Ops.describeKey(key)}' on ${Ops.typeOf(o)} '${Ops.toDisplayString(o)}'")
    }

    @JvmStatic
    fun keyForBase(o: Any?, key: Any?): Any {
        if (o === Undefined || o === Null) {
            throw JSException.typeError("Cannot read properties of ${Ops.toDisplayString(o)} (reading '${if (key is JSSymbol) key.toString() else Ops.toDisplayString(key)}')")
        }
        // ToPropertyKey of a number has no side effects: keep it a Double so the element fast paths apply (an
        // Int key would be boxed again on every `a[i] += x`)
        if (key is Double) return key
        return Ops.toPropertyKey(key)
    }

    @JvmStatic
    fun getElem(realm: Realm, o: Any?, key: Any?): Any? {
        // compound assignments (`a[i] += x`) pass the key already converted by TO_KEY_FOR_BASE: an Int index
        if (key is Int && key >= 0) {
            if (o is JSArray) return o.getIndexFast(key)
            if (o is JSTypedArray) return o.getIndex(key)
        }
        if (o is JSArray && key is Double) {
            val i = key.toInt()
            if (i >= 0 && i.toDouble() == key) return o.getIndexFast(i)
        }
        if (o is JSTypedArray && key is Double) {
            val i = key.toInt()
            if (i >= 0 && i.toDouble() == key) return o.getIndex(i)
        }
        if (o is JSObject) return o.get(Ops.toPropertyKey(key), o)
        if (o === Undefined || o === Null) {
            throw JSException.typeError("Cannot read properties of ${Ops.toDisplayString(o)} (reading '${if (key is JSSymbol) key.toString() else Ops.toDisplayString(key)}')")
        }
        if (o is CharSequence && key is Double) {
            val i = key.toInt()
            if (i >= 0 && i.toDouble() == key && i < o.length) return o[i].toString()
        }
        return Ops.getV(realm, o, Ops.toPropertyKey(key))
    }

    @JvmStatic
    fun putElem(realm: Realm, o: Any?, key: Any?, v: Any?, strict: Boolean) {
        if (key is Int && key >= 0) {
            if (o is JSArray && o.trySetIndexFast(key, v)) return
            if (o is JSTypedArray && !o.buffer.immutable) {
                o.setIndex(key, v)
                return
            }
        }
        if (o is JSArray && key is Double) {
            val i = key.toInt()
            if (i >= 0 && i.toDouble() == key && o.trySetIndexFast(i, v)) return
        }
        if (o is JSTypedArray && key is Double && !o.buffer.immutable) {
            val i = key.toInt()
            if (i >= 0 && i.toDouble() == key) {
                o.setIndex(i, v)
                return
            }
        }
        if (o is JSObject) {
            val pk = Ops.toPropertyKey(key)
            if (!o.set(pk, v, o) && strict) throw readOnlyError(pk, o)
            return
        }
        if (o === Undefined || o === Null) {
            throw JSException.typeError("Cannot set properties of ${Ops.toDisplayString(o)} (setting '${if (key is JSSymbol) key.toString() else Ops.toDisplayString(key)}')")
        }
        putPrimitive(realm, o, Ops.toPropertyKey(key), v, strict)
    }

    @JvmStatic
    fun deleteProp(realm: Realm, o: Any?, key: Any, strict: Boolean): Boolean {
        val obj = o as? JSObject ?: Ops.toObject(realm, o)
        val ok = obj.delete(key)
        if (!ok && strict) throw JSException.typeError("Cannot delete property '${Ops.describeKey(key)}' of ${Ops.describe(o)}")
        return ok
    }

    // ------------------------------------------------------------------ super

    @JvmStatic
    fun superBase(home: Any?): Any? {
        if (home !is JSObject) return Undefined
        return home.getPrototypeOf() ?: Null
    }

    @JvmStatic
    fun getSuper(realm: Realm, thisV: Any?, key: Any, base: Any?): Any? {
        if (base === Undefined || base === Null) throw JSException.typeError("Cannot read properties of ${Ops.toDisplayString(base)} (reading '${Ops.describeKey(key)}')")
        val b = Ops.toObject(realm, base)
        return b.get(key, thisV)
    }

    @JvmStatic
    fun putSuper(realm: Realm, thisV: Any?, key: Any, base: Any?, v: Any?, strict: Boolean) {
        if (base === Undefined || base === Null) throw JSException.typeError("Cannot set properties of ${Ops.toDisplayString(base)} (setting '${Ops.describeKey(key)}')")
        val b = Ops.toObject(realm, base)
        if (!b.set(key, v, thisV) && strict) throw JSException.typeError("Cannot assign to read only property '${Ops.describeKey(key)}' of object")
    }

    // ------------------------------------------------------------------ private names

    @JvmStatic
    fun privateGet(o: Any?, pn: PrivateName): Any? {
        if (o !is JSObject) throw JSException.typeError("Cannot read private member ${pn.description} from an object whose class did not declare it")
        val m = o.privateElements
        if (m == null || !m.containsKey(pn)) throw JSException.typeError("Cannot read private member ${pn.description} from an object whose class did not declare it")
        val v = m[pn]
        if (v is PrivateElement) {
            if (v.kind == PrivateElement.METHOD) return v.value
            val g = v.getter
            if (g !is JSObject) throw JSException.typeError("'${pn.description}' was defined without a getter")
            return g.call(o, EMPTY_ARGS)
        }
        return v
    }

    @JvmStatic
    fun privateSet(o: Any?, pn: PrivateName, value: Any?) {
        if (o !is JSObject) throw JSException.typeError("Cannot write private member ${pn.description} to an object whose class did not declare it")
        val m = o.privateElements
        if (m == null || !m.containsKey(pn)) throw JSException.typeError("Cannot write private member ${pn.description} to an object whose class did not declare it")
        val v = m[pn]
        if (v is PrivateElement) {
            if (v.kind == PrivateElement.METHOD) throw JSException.typeError("Private method ${pn.description} is not writable")
            val s = v.setter
            if (s !is JSObject) throw JSException.typeError("'${pn.description}' was defined without a setter")
            s.call(o, arrayOf(value))
            return
        }
        m[pn] = value
    }

    @JvmStatic
    fun privateIn(pn: PrivateName, o: Any?): Boolean {
        if (o !is JSObject) throw JSException.typeError("Cannot use 'in' operator to search for '${pn.description}' in ${Ops.toDisplayString(o)}")
        val m = o.privateElements ?: return false
        return m.containsKey(pn)
    }

    @JvmStatic
    fun privateFieldAdd(o: JSObject, pn: PrivateName, value: Any?) {
        var m = o.privateElements
        if (m == null) {
            m = java.util.IdentityHashMap()
            o.privateElements = m
        }
        if (m.containsKey(pn)) throw JSException.typeError("Cannot initialize ${pn.description} twice on the same object")
        if (!o.isExtensible()) throw JSException.typeError("Cannot define private field ${pn.description} on a non-extensible object")
        m[pn] = value
    }

    @JvmStatic
    fun privateMethodAdd(o: JSObject, el: PrivateElement) {
        var m = o.privateElements
        if (m == null) {
            m = java.util.IdentityHashMap()
            o.privateElements = m
        }
        if (m.containsKey(el.key)) throw JSException.typeError("Cannot initialize private methods of class twice on the same object")
        if (!o.isExtensible()) throw JSException.typeError("Cannot add private methods to a non-extensible object")
        m[el.key] = el
    }

    @JvmStatic
    fun addPrivateMethod(ctor: JSClosure, pn: PrivateName, fn: Any?, kind: Int, rename: Boolean = true) {
        var list = ctor.privateMethods
        if (list == null) {
            list = ArrayList()
            ctor.privateMethods = list
        }
        mergePrivate(list, pn, fn, kind, rename)
    }

    private fun mergePrivate(list: MutableList<PrivateElement>, pn: PrivateName, fn: Any?, kind: Int, rename: Boolean = true) {
        if (rename && fn is JSFunction) {
            val nm = when (kind) { 1 -> "get ${pn.description}"; 2 -> "set ${pn.description}"; else -> pn.description }
            fn.defineOwn("name", nm, Attr.CONFIGURABLE)
        }
        for (e in list) {
            if (e.key === pn) {
                if (kind == 1) e.getter = fn else if (kind == 2) e.setter = fn
                return
            }
        }
        list.add(
            when (kind) {
                0 -> PrivateElement(pn, PrivateElement.METHOD, fn, null, null)
                1 -> PrivateElement(pn, PrivateElement.ACCESSOR, null, fn, null)
                else -> PrivateElement(pn, PrivateElement.ACCESSOR, null, null, fn)
            }
        )
    }

    @JvmStatic
    fun staticPrivateMethod(obj: JSObject, pn: PrivateName, fn: Any?, kind: Int, rename: Boolean = true) {
        var m = obj.privateElements
        if (m == null) {
            m = java.util.IdentityHashMap()
            obj.privateElements = m
        }
        val existing = m[pn]
        if (existing is PrivateElement) {
            val tmp = arrayListOf(existing)
            mergePrivate(tmp, pn, fn, kind, rename)
            return
        }
        val list = ArrayList<PrivateElement>()
        mergePrivate(list, pn, fn, kind, rename)
        m[pn] = list[0]
    }

    // ------------------------------------------------------------------ class instances

    @JvmStatic
    fun initializeInstanceElements(o: JSObject, ctor: JSClosure) {
        val pm = ctor.privateMethods
        if (pm != null) for (e in pm) privateMethodAdd(o, e)
        val extra = ctor.extraInitializers
        if (extra != null) for (init in extra) init.call(o, EMPTY_ARGS)
        val fields = ctor.fields
        if (fields != null) for (fr in fields) defineField(o, fr)
    }

    @JvmStatic
    fun defineField(o: JSObject, fr: FieldRecord) {
        val init = fr.initializer
        val key = fr.key
        var v = if (init is JSObject) init.call(o, arrayOf(if (key is PrivateName) key.description else PK.toValue(key))) else Undefined
        fr.decoratorInitializers?.let { inits -> for (i in inits) v = i.call(o, arrayOf(v)) }
        if (key is PrivateName) privateFieldAdd(o, key, v)
        else o.createDataPropertyOrThrow(key, v)
        fr.extraInitializers?.let { inits -> for (i in inits) i.call(o, EMPTY_ARGS) }
    }

    @JvmStatic
    fun superCall(func: Any?, args: Array<Any?>, newTarget: Any?): Any? {
        if (!Ops.isConstructor(func)) throw JSException.typeError("Super constructor ${Ops.describe(func)} is not a constructor")
        return (func as JSObject).construct(args, newTarget as JSObject)
    }

    // ------------------------------------------------------------------ functions & classes

    /** MAKE_CLOSURE: a closure over [f]'s environment (arrows also capture [f]'s `this`). */
    @JvmStatic
    fun makeClosureIn(f: Frame, code: CodeBlock): JSClosure {
        val c = makeClosure(f.realm, code, f.env, null)
        if (code.flags and CodeBlock.ARROW != 0) c.lexicalThis = f.thisValue
        return c
    }

    @JvmStatic
    fun makeClosure(realm: Realm, code: CodeBlock, env: Env?, home: JSObject?): JSClosure {
        val proto = when {
            code.isGenerator && code.isAsync -> realm.asyncGeneratorFunctionPrototype
            code.isGenerator -> realm.generatorFunctionPrototype
            code.isAsync -> realm.asyncFunctionPrototype
            else -> realm.functionPrototype
        }
        val fn = JSClosure(realm, code, env, proto)
        fn.homeObject = home
        fn.defineOwn("length", code.length.toDouble(), Attr.CONFIGURABLE)
        if (code.kind != FunctionKind.CLASS_FIELD_INIT && code.kind != FunctionKind.STATIC_BLOCK) {
            val nm = when (code.kind) {
                FunctionKind.GETTER -> "get " + code.name
                FunctionKind.SETTER -> "set " + code.name
                else -> code.name
            }
            fn.defineOwn("name", nm, Attr.CONFIGURABLE)
        }
        if (code.kind == FunctionKind.NORMAL && code.flags and (CodeBlock.STRICT or CodeBlock.ARROW or CodeBlock.GENERATOR or CodeBlock.ASYNC) == 0) {
            // Own "arguments"/"caller" of sloppy functions as in other engines, so reading them does not hit the
            // %ThrowTypeError% accessors. Always undefined: callers and their arguments are never exposed.
            fn.defineOwn("arguments", Undefined, Attr.NONE)
            fn.defineOwn("caller", Undefined, Attr.NONE)
        }
        if (code.isGenerator) {
            val p = JSObject(if (code.isAsync) realm.asyncGeneratorPrototype else realm.generatorPrototype)
            fn.defineOwn("prototype", p, Attr.WRITABLE)
        } else if (code.flags and CodeBlock.CONSTRUCTOR != 0 && code.flags and CodeBlock.CLASS_CTOR == 0) {
            val p = JSObject(realm.objectPrototype)
            p.defineOwn("constructor", fn, Attr.WC)
            fn.defineOwn("prototype", p, Attr.WRITABLE)
        }
        return fn
    }

    @JvmStatic
    fun setFunctionName(fn: JSObject, key: Any, prefix: String?) {
        if (fn !is JSFunction) return
        // class constructors with a static "name" member keep it
        val pm = fn.props
        if (pm != null) {
            val i = pm.find("name")
            if (i >= 0 && fn is JSClosure && fn.code.isClassConstructor) return
        }
        fn.setFunctionName(key, prefix)
    }

    @JvmStatic
    fun defineMethod(obj: JSObject, key: Any, fn: JSFunction, op: Int, enumerable: Boolean) {
        val e = if (enumerable) Attr.ENUMERABLE else 0
        when (op) {
            Op.DEFINE_GETTER -> {
                fn.setFunctionName(key, "get")
                val d = PropertyDescriptor().getter(fn).enumerable(enumerable).configurable(true)
                obj.definePropertyOrThrow(key, d)
            }
            Op.DEFINE_SETTER -> {
                fn.setFunctionName(key, "set")
                val d = PropertyDescriptor().setter(fn).enumerable(enumerable).configurable(true)
                obj.definePropertyOrThrow(key, d)
            }
            else -> {
                fn.setFunctionName(key, null)
                obj.definePropertyOrThrow(key, PropertyDescriptor.data(fn, Attr.WRITABLE or Attr.CONFIGURABLE or e))
            }
        }
    }

    /** ClassDefinitionEvaluation part: creates prototype and constructor. Returns [proto, ctor]. */
    @JvmStatic
    fun makeClass(realm: Realm, ctorCode: CodeBlock, env: Env?, heritage: Any?, name: Any?): Array<Any?> {
        val protoParent: JSObject?
        val ctorParent: JSObject
        if (heritage === NotFound) {
            protoParent = realm.objectPrototype
            ctorParent = realm.functionPrototype
        } else if (heritage === Null) {
            protoParent = null
            ctorParent = realm.functionPrototype
        } else {
            if (!Ops.isConstructor(heritage)) throw JSException.typeError("Class extends value ${Ops.describe(heritage)} is not a constructor or null")
            val h = heritage as JSObject
            if (h is JSClosure && h.code.isGenerator) throw JSException.typeError("Class extends value is a generator")
            val pp = h.get("prototype", h)
            protoParent = when (pp) {
                is JSObject -> pp
                Null -> null
                else -> throw JSException.typeError("Class extends value does not have valid prototype property ${Ops.toDisplayString(pp)}")
            }
            ctorParent = h
        }
        val proto = JSObject(protoParent)
        val ctor = JSClosure(realm, ctorCode, env, ctorParent)
        ctor.homeObject = proto
        ctor.defineOwn("length", ctorCode.length.toDouble(), Attr.CONFIGURABLE)
        val nm = if (name != null) PK.functionName(name) else ctorCode.name
        ctor.defineOwn("name", nm, Attr.CONFIGURABLE)
        ctor.defineOwn("prototype", proto, Attr.NONE)
        proto.defineOwn("constructor", ctor, Attr.WC)
        return arrayOf(proto, ctor)
    }

    // ------------------------------------------------------------------ objects

    @JvmStatic
    fun copyDataProperties(target: JSObject, src: Any?, excluded: Set<Any>?) {
        if (src === Undefined || src === Null) return
        val from = Ops.toObject(src)
        val keys = from.ownPropertyKeys()
        for (k in keys) {
            if (excluded != null && (excluded.contains(k))) continue
            val d = from.getOwnProperty(k)
            if (d != null && d.enumerable) {
                target.createDataPropertyOrThrow(k, from.get(k, from))
            }
        }
    }

    @JvmStatic
    fun arraySpread(realm: Realm, arr: JSArray, v: Any?) {
        if (v is JSArray && v.special and JSObject.SPECIAL_ALL == JSObject.EXOTIC_OWN && !v.sparse && Iteration.isPristineArrayIteration(realm, v)) {
            val n = v.length.toInt()
            for (i in 0 until n) arr.pushInit(v.get(i, v))
            return
        }
        val rec = Iteration.getIterator(realm, v, false)
        while (true) {
            val x = Iteration.stepValue(rec)
            if (x === NotFound) break
            arr.pushInit(x)
        }
    }

    @JvmStatic
    fun arrayToArgs(arr: JSArray): Array<Any?> {
        val n = arr.length.toInt()
        if (n == 0) return EMPTY_ARGS
        val out = arrayOfNulls<Any?>(n)
        for (i in 0 until n) out[i] = arr.get(i, arr)
        return out
    }

    // ------------------------------------------------------------------ arguments objects

    @JvmStatic
    fun createUnmappedArguments(realm: Realm, args: Array<Any?>): JSObject {
        val o = JSArgumentsObject(realm.objectPrototype)
        o.defineOwn("length", args.size.toDouble(), Attr.WC)
        for (i in args.indices) o.defineOwn(i, args[i], Attr.ALL)
        o.defineOwn(JSSymbol.iterator, realm.arrayProtoValues, Attr.WC)
        o.defineAccessor("callee", realm.throwTypeError, realm.throwTypeError, Attr.NONE)
        return o
    }

    @JvmStatic
    fun createMappedArguments(fn: JSClosure, args: Array<Any?>, env: DeclEnv?, slots: IntArray): JSObject {
        val realm = fn.realm
        if (env == null || slots.isEmpty()) {
            // no parameters: nothing to map, but callee is the function
            val o = JSArgumentsObject(realm.objectPrototype)
            o.defineOwn("length", args.size.toDouble(), Attr.WC)
            for (i in args.indices) o.defineOwn(i, args[i], Attr.ALL)
            o.defineOwn(JSSymbol.iterator, realm.arrayProtoValues, Attr.WC)
            o.defineOwn("callee", fn, Attr.WC)
            return o
        }
        val o = JSMappedArguments(realm.objectPrototype, env)
        o.defineOwn("length", args.size.toDouble(), Attr.WC)
        for (i in args.indices) o.defineOwn(i, args[i], Attr.ALL)
        // map parameters (later duplicates win)
        val mappedNames = HashSet<String>()
        for (i in slots.indices.reversed()) {
            val slot = slots[i]
            val name = env.info.names[slot]
            if (mappedNames.add(name) && i < args.size) o.map(i, slot)
        }
        o.defineOwn(JSSymbol.iterator, realm.arrayProtoValues, Attr.WC)
        o.defineOwn("callee", fn, Attr.WC)
        return o
    }

    // ------------------------------------------------------------------ errors

    /**
     * How an error message names the callee of the call or `new` instruction at [pc]: its source text with the
     * arguments of inner calls shown as `(...)`, as V8 does (`o.f(...).g is not a function`), else a description of
     * the value [v].
     */
    @JvmStatic
    fun calleeText(cb: CodeBlock, pc: Int, v: Any?): String {
        val src = cb.source
        val sites = cb.callSites
        var lo = 0
        var hi = sites.size / 3 - 1
        while (src != null && lo <= hi) {
            val mid = (lo + hi) ushr 1
            val at = sites[3 * mid]
            if (at < pc) lo = mid + 1
            else if (at > pc) hi = mid - 1
            else {
                val start = sites[3 * mid + 1]
                val end = sites[3 * mid + 2]
                if (start in 0..<end && end <= src.text.length) {
                    val t = compactCallee(src.text, start, end)
                    if (t.isNotEmpty() && t.length <= 120) return t
                }
                return Ops.describe(v)
            }
        }
        // a call without a recorded callee: the text from its position up to the first '(' (best effort)
        val pos = cb.positionAt(pc)
        if (src != null && pos >= 0 && pos < src.text.length) {
            // take the callee expression text up to the '(' (best effort)
            val text = src.text
            var end = pos
            var depth = 0
            while (end < text.length) {
                val c = text[end]
                if (c == '(' && depth == 0) break
                if (c == '[' || c == '{') depth++
                if (c == ']' || c == '}') depth--
                if (c == '\n' || c == ';') break
                end++
            }
            val t = text.substring(pos, end).trim()
            if (t.isNotEmpty() && t.length < 80 && !t.startsWith("new ")) return t
            if (t.startsWith("new ")) return t.substring(4).trim()
        }
        return Ops.describe(v)
    }

    /**
     * The callee source [text] from [start] to [end] on one line, with the argument lists of calls in it shortened to
     * `(...)`: `a.b(x, y).c` becomes `a.b(...).c`. String and template literals are kept as they are.
     */
    private fun compactCallee(text: String, start: Int, end: Int): String {
        val sb = StringBuilder()
        var i = start
        fun copyLiteral() {
            // a quoted literal from text[i], copied up to its closing quote
            val q = text[i]
            sb.append(q)
            i++
            while (i < end) {
                val c = text[i]
                sb.append(c)
                i++
                if (c == '\\' && i < end) {
                    sb.append(text[i])
                    i++
                } else if (c == q) return
            }
        }
        while (i < end) {
            val c = text[i]
            when {
                c == '"' || c == '\'' || c == '`' -> copyLiteral()
                c.isWhitespace() -> {
                    // runs of white space become one space, none around '.'
                    while (i < end && text[i].isWhitespace()) i++
                    val prev = sb.lastOrNull()
                    if (prev != null && prev != '.' && i < end && text[i] != '.' && text[i] != '?') sb.append(' ')
                }
                c == '(' && sb.isNotEmpty() && (sb.last().isLetterOrDigit() || sb.last() in "_$)]`") -> {
                    // the arguments of a call: skip to the matching ')'
                    var depth = 0
                    while (i < end) {
                        val d = text[i]
                        if (d == '"' || d == '\'' || d == '`') {
                            val mark = sb.length
                            copyLiteral()
                            sb.setLength(mark)
                            continue
                        }
                        i++
                        if (d == '(') depth++ else if (d == ')' && --depth == 0) break
                    }
                    sb.append("(...)")
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString().trim()
    }

    @JvmStatic
    fun notCallable(v: Any?, cb: CodeBlock, pc: Int): JSException = JSException.typeError("${calleeText(cb, pc, v)} is not a function")

    /** Converts a caught Throwable into the JS value seen by catch clauses. */
    @JvmStatic
    fun catchValue(t: Throwable, realm: Realm, f: Frame?, pc: Int): Any? {
        when (t) {
            is JSException -> {
                if (f != null) attachStack(t, f, pc)
                return t.value
            }
            is TerminationException -> throw t
            is StackOverflowError -> return realm.newError(ErrorKind.RANGE, "Maximum call stack size exceeded")
            is OutOfMemoryError -> throw ResourceLimitException("Out of memory")
            is dev.mooner.neonjs.parser.JSSyntaxError -> return realm.newError(ErrorKind.SYNTAX, t.rawMessage)
            is HostException -> return realm.agent.hostExceptionToJS(t, realm)
            is InterruptedException -> throw InterruptedExecutionException("interrupted")
            else -> {
                if (realm.agent.config.propagateInternalErrors) throw t
                return realm.newError(ErrorKind.ERROR, "Internal error: $t").also { it.defineOwn("name", "InternalError", Attr.WC) }
            }
        }
    }

    /**
     * Gives an error thrown in frame [f] at [pc] its stack trace: errors without one get it now; errors the engine
     * raised in the current step of [f] (see JSErrorObject.capturedAt) get it again with the exact pc. An error that
     * was created earlier, caught by native code and rethrown later (a rejected promise awaited) keeps its stack.
     */
    @JvmStatic
    fun attachStack(t: JSException, f: Frame, pc: Int) {
        val v = t.value as? JSErrorObject ?: return
        val sameStep = v.capturedAt?.get() === f && v.capturedPc == f.pc
        if (v.stackTrace == null || sameStep) {
            f.pc = pc
            v.stackTrace = f.realm.agent.captureStack()
        }
        v.capturedAt = null
    }

    // ------------------------------------------------------------------ misc

    @JvmStatic
    fun templateObject(realm: Realm, site: TemplateSite): JSArray {
        val cached = realm.templateMap[site]
        if (cached != null) return cached
        val n = site.raw.size
        val rawArr = JSArray(realm.arrayPrototype)
        val arr = JSArray(realm.arrayPrototype)
        for (i in 0 until n) {
            arr.pushInit(site.cooked[i] ?: Undefined)
            rawArr.pushInit(site.raw[i])
        }
        Builtins.freeze(rawArr)
        arr.defineOwn("raw", rawArr, Attr.NONE)
        Builtins.freeze(arr)
        realm.templateMap[site] = arr
        return arr
    }

    @JvmStatic
    fun newRegExp(realm: Realm, site: RegExpSite): JSObject = realm.agent.regexpFactory(realm, site)

    @JvmStatic
    fun importMeta(f: Frame): JSObject = realm(f).agent.moduleLoader.importMeta(f)

    private fun realm(f: Frame) = f.realm

    @JvmStatic
    fun dynamicImport(f: Frame, spec: Any?, opts: Any?): Any? = f.realm.agent.moduleLoader.dynamicImport(f, spec, opts)

    @JvmStatic
    fun promiseResolveForAwait(realm: Realm, v: Any?): Any? = Promises.promiseResolve(realm, realm.promiseConstructor, v)

    @JvmStatic
    fun promiseResolveOrNull(realm: Realm, v: Any?): Any? = try {
        Promises.promiseResolve(realm, realm.promiseConstructor, v)
    } catch (e: JSException) {
        Promises.rejected(realm, e.value)
    }

    @JvmStatic
    fun directEval(f: Frame, args: Array<Any?>, strictCaller: Boolean): Any? = Evaluator.directEval(f, args, strictCaller)

    @JvmStatic
    fun declareGlobals(realm: Realm, info: DeclInfo, fns: Array<Any?>) = Evaluator.globalDeclarationInstantiation(realm, info, fns)

    @JvmStatic
    fun declareEval(f: Frame, info: DeclInfo, fns: Array<Any?>) = Evaluator.evalDeclarationInstantiation(f, info, fns)
}

/** Unmapped arguments exotic (ordinary object with `[[ParameterMap]]` undefined, tagged for toString). */
open class JSArgumentsObject(proto: JSObject?) : JSObject(proto) {
    override val className: String get() = "Arguments"
}

/** Mapped arguments exotic object: indices alias parameter bindings in [env]. */
class JSMappedArguments(proto: JSObject?, @JvmField val env: DeclEnv) : JSArgumentsObject(proto) {
    private var mapped: HashMap<Int, Int>? = null

    init {
        special = special or EXOTIC_OWN or SPECIAL_GET or SPECIAL_SET
    }

    fun map(index: Int, slot: Int) {
        var m = mapped
        if (m == null) {
            m = HashMap()
            mapped = m
        }
        m[index] = slot
    }

    private fun slotOf(key: Any): Int {
        val m = mapped ?: return -1
        if (key !is Int) return -1
        return m[key] ?: -1
    }

    override fun getOwnProperty(key: Any): PropertyDescriptor? {
        val d = ordinaryGetOwnProperty(key) ?: return null
        val s = slotOf(key)
        if (s >= 0) d.value = env.slots[s]
        return d
    }

    override fun getOwnValue(key: Any, receiver: Any?): Any? {
        val s = slotOf(key)
        if (s >= 0) return env.slots[s]
        return super.getOwnValue(key, receiver)
    }

    override fun get(key: Any, receiver: Any?): Any? {
        val s = slotOf(key)
        if (s >= 0) return env.slots[s]
        return super.get(key, receiver)
    }

    override fun set(key: Any, value: Any?, receiver: Any?): Boolean {
        val s = slotOf(key)
        if (s >= 0 && receiver === this) {
            // OrdinarySet on own data property, then map
            val ok = ordinarySet(key, value, receiver)
            if (ok) env.slots[s] = value
            return ok
        }
        return ordinarySet(key, value, receiver)
    }

    override fun defineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean {
        val s = slotOf(key)
        var newDesc = desc
        if (s >= 0 && desc.isData && !desc.hasValue && desc.hasWritable && !desc.writable) {
            newDesc = PropertyDescriptor()
            newDesc.present = desc.present
            newDesc.writable = desc.writable; newDesc.enumerable = desc.enumerable; newDesc.configurable = desc.configurable
            newDesc.value(env.slots[s])
        }
        if (!ordinaryDefineOwnProperty(key, newDesc)) return false
        if (s >= 0) {
            if (desc.isAccessor) mapped!!.remove(key as Int)
            else {
                if (desc.hasValue) env.slots[s] = desc.value
                if (desc.hasWritable && !desc.writable) mapped!!.remove(key as Int)
            }
        }
        return true
    }

    override fun delete(key: Any): Boolean {
        val ok = super.delete(key)
        if (ok && slotOf(key) >= 0) mapped!!.remove(key as Int)
        return ok
    }
}

/** Thrown by host (interop) code; converted to a JS error when caught by JS. */
class HostException(val hostCause: Throwable) : RuntimeException(hostCause.toString(), hostCause)
