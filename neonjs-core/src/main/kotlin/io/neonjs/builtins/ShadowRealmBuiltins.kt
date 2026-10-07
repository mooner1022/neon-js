package io.neonjs.builtins

import io.neonjs.runtime.*
import io.neonjs.vm.*

/** ShadowRealm instance: `[[ShadowRealm]]` slot. */
class JSShadowRealm(proto: JSObject?, @JvmField val shadowRealm: Realm) : JSObject(proto) {
    override val className: String get() = "ShadowRealm"
}

/**
 * Wrapped function exotic object: a callable from another realm. Only primitives and (wrapped) callables cross the
 * boundary; any exception becomes a TypeError of the caller's realm.
 */
class WrappedFunction(callerRealm: Realm, @JvmField val wrappedTarget: JSObject) : JSFunction(callerRealm, callerRealm.functionPrototype) {
    override fun call(thisArg: Any?, args: Array<Any?>): Any? {
        val callerRealm = realm
        val targetRealm = try {
            Ops.getFunctionRealm(wrappedTarget)
        } catch (t: Throwable) {
            throw ShadowRealms.typeError(callerRealm, t, "Wrapped function target is not available")
        }
        val wrappedArgs = Array(args.size) { ShadowRealms.wrap(targetRealm, args[it], callerRealm) }
        val wrappedThis = ShadowRealms.wrap(targetRealm, thisArg, callerRealm)
        val result = try {
            Ops.call(wrappedTarget, wrappedThis, wrappedArgs)
        } catch (t: Throwable) {
            throw ShadowRealms.typeError(callerRealm, t, "Wrapped function threw an exception")
        }
        return ShadowRealms.wrap(callerRealm, result)
    }

    override fun sourceText(): String = "function () { [native code] }"
}

object ShadowRealms {
    /** Converts any non-termination failure into a TypeError of [realm]. */
    fun typeError(realm: Realm, t: Throwable, message: String): Throwable {
        if (t is TerminationException || t is StackOverflowError) return t
        return JSException(realm.newError(ErrorKind.TYPE, message))
    }

    /** GetWrappedValue: wraps [v] for use in [targetRealm]; errors are created in [errorRealm] (the running realm). */
    fun wrap(targetRealm: Realm, v: Any?, errorRealm: Realm = targetRealm): Any? {
        if (v !is JSObject) return v
        if (!v.isCallable) throw JSException(errorRealm.newError(ErrorKind.TYPE, "Only primitives and callables can cross a ShadowRealm boundary"))
        return wrappedFunctionCreate(targetRealm, v, errorRealm)
    }

    /** WrappedFunctionCreate (with CopyNameAndLength). */
    fun wrappedFunctionCreate(callerRealm: Realm, target: JSObject, errorRealm: Realm = callerRealm): WrappedFunction {
        val w = WrappedFunction(callerRealm, target)
        try {
            var len = 0.0
            if (target.hasOwnProperty("length")) {
                val tl = target.get("length", target)
                if (tl is Double) {
                    len = when (tl) {
                        Double.POSITIVE_INFINITY -> tl
                        Double.NEGATIVE_INFINITY -> 0.0
                        else -> maxOf(Ops.toIntegerOrInfinity(tl), 0.0)
                    }
                }
            }
            w.defineOwn("length", len, Attr.CONFIGURABLE)
            val name = target.get("name", target)
            w.defineOwn("name", if (name is CharSequence) name.toString() else "", Attr.CONFIGURABLE)
        } catch (t: Throwable) {
            throw typeError(errorRealm, t, "Cannot copy name and length of the wrapped function")
        }
        return w
    }

    fun install(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        val ctor = makeCtor(realm, "ShadowRealm", 0, proto) { f, _, _, nt ->
            if (nt == null) typeErr("Constructor ShadowRealm requires 'new'")
            val p = Ops.getPrototypeFromConstructor(nt) { it.intrinsics["%ShadowRealm.prototype%"]!! }
            JSShadowRealm(p, createShadowRealm(f.realm))
        }
        realm.intrinsics["%ShadowRealm.prototype%"] = proto
        proto.defineOwn(JSSymbol.toStringTag, "ShadowRealm", Attr.CONFIGURABLE)
        proto.method(realm, "evaluate", 1) { f, t, args, _ ->
            val o = t as? JSShadowRealm ?: typeErr("ShadowRealm.prototype.evaluate called on incompatible receiver")
            val src = args.arg(0) as? CharSequence ?: typeErr("ShadowRealm.prototype.evaluate requires a string")
            performEval(src.toString(), f.realm, o.shadowRealm)
        }
        proto.method(realm, "importValue", 2) { f, t, args, _ ->
            val o = t as? JSShadowRealm ?: typeErr("ShadowRealm.prototype.importValue called on incompatible receiver")
            val specifier = Ops.toString(args.arg(0))
            val exportName = args.arg(1) as? CharSequence ?: typeErr("ShadowRealm.prototype.importValue requires a string export name")
            importValue(specifier, exportName.toString(), f.realm, o.shadowRealm)
        }
        realm.global("ShadowRealm", ctor)
    }

    /** CreateRealm + SetDefaultGlobalBindings + HostInitializeShadowRealm (shares the caller's module loader). */
    private fun createShadowRealm(caller: Realm): Realm {
        val r = Realm(caller.agent)
        Builtins.install(r)
        (caller.intrinsicsAny["%ModuleLoader%"] as? ModuleLoader)?.let { Modules.setLoader(r, it) }
        return r
    }

    /** PerformShadowRealmEval */
    private fun performEval(source: String, callerRealm: Realm, evalRealm: Realm): Any? {
        if (!callerRealm.agent.config.allowCodeGeneration) {
            throw JSException(callerRealm.newError(ErrorKind.EVAL, "Code generation from strings is disallowed in this context"))
        }
        // like an indirect eval: lexical declarations go into a fresh environment per evaluation
        val prog = try {
            io.neonjs.parser.Parser.parse(source, io.neonjs.parser.ParseOptions(sourceName = "<ShadowRealm>"))
        } catch (e: io.neonjs.parser.JSSyntaxError) {
            // early errors are reported as a SyntaxError of the caller's realm
            throw JSException(callerRealm.newError(ErrorKind.SYNTAX, e.rawMessage))
        }
        val cb = io.neonjs.compiler.Compiler.compileEval(prog, io.neonjs.compiler.Source("<ShadowRealm>", source), direct = false)
        val result = try {
            val ge = evalRealm.globalEnv
            Interpreter.execute(Frame(null, cb, evalRealm, ge.thisValue, EMPTY_ARGS, Undefined, ge))
        } catch (t: Throwable) {
            throw typeError(callerRealm, t, "ShadowRealm evaluation threw an exception")
        }
        return wrap(callerRealm, result)
    }

    /** ShadowRealmImportValue */
    private fun importValue(specifier: String, exportName: String, callerRealm: Realm, evalRealm: Realm): Any? {
        val cap = Promises.newPromiseCapability(callerRealm, callerRealm.promiseConstructor)
        val frame = Frame(null, Evaluator.compileScript(evalRealm, "", "<ShadowRealm>"), evalRealm, Undefined, EMPTY_ARGS, Undefined, evalRealm.globalEnv)
        val inner = evalRealm.enter { Modules.dynamicImport(frame, specifier, Undefined) } as JSPromise
        Promises.thenHost(callerRealm, inner, { ns ->
            try {
                val o = ns as JSObject
                if (!o.hasOwnProperty(PK.fromString(exportName))) {
                    throw JSException(callerRealm.newError(ErrorKind.TYPE, "Module '$specifier' has no export named '$exportName'"))
                }
                Ops.call(cap.resolve, Undefined, arrayOf(wrap(callerRealm, o.get(PK.fromString(exportName), o))))
            } catch (t: Throwable) {
                if (t is TerminationException) throw t
                val err = if (t is JSException) t.value else callerRealm.newError(ErrorKind.TYPE, "importValue failed")
                Ops.call(cap.reject, Undefined, arrayOf(err))
            }
            Undefined
        }, { _ ->
            Ops.call(cap.reject, Undefined, arrayOf(callerRealm.newError(ErrorKind.TYPE, "Cannot import '$specifier' into the ShadowRealm")))
            Undefined
        })
        return cap.promise
    }
}
