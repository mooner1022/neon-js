package io.neonjs.vm

import io.neonjs.compiler.*
import io.neonjs.parser.*
import io.neonjs.runtime.*

/** Script evaluation, eval, and declaration instantiation. */
object Evaluator {

    /** Converts a parser error into a JS SyntaxError in [realm]. */
    @JvmStatic
    fun syntaxError(realm: Realm, e: JSSyntaxError): JSException {
        val err = realm.newError(ErrorKind.SYNTAX, e.rawMessage)
        return JSException(err)
    }

    @JvmStatic
    fun compileScript(realm: Realm, source: String, name: String): CodeBlock {
        val prog = try {
            Parser.parse(source, ParseOptions(sourceName = name, annexB = realm.agent.config.annexB))
        } catch (e: JSSyntaxError) {
            throw syntaxError(realm, e)
        }
        return Compiler.compileScript(prog, Source(name, source))
    }

    /** ScriptEvaluation */
    @JvmStatic
    fun evaluateScript(realm: Realm, source: String, name: String): Any? {
        val cb = compileScript(realm, source, name)
        return runScript(realm, cb)
    }

    @JvmStatic
    fun runScript(realm: Realm, cb: CodeBlock): Any? {
        if (realm.agent.config.executionMode == io.neonjs.jit.Jit.MODE_COMPILED) io.neonjs.jit.Jit.prepare(cb, realm.agent)
        val ge = realm.globalEnv
        val frame = Frame(null, cb, realm, ge.thisValue, EMPTY_ARGS, Undefined, ge)
        return Interpreter.execute(frame)
    }

    // ------------------------------------------------------------------ eval

    /** PerformEval for indirect eval (the %eval% function). */
    private fun checkCodeGen(realm: Realm) {
        if (!realm.agent.config.allowCodeGeneration) throw JSException(realm.newError(ErrorKind.EVAL, "Code generation from strings is disallowed in this context"))
    }

    @JvmStatic
    fun indirectEval(realm: Realm, x: Any?): Any? {
        if (x !is CharSequence) return x
        checkCodeGen(realm)
        val text = x.toString()
        val prog = try {
            Parser.parse(text, ParseOptions(sourceName = "eval"))
        } catch (e: JSSyntaxError) {
            throw syntaxError(realm, e)
        }
        val cb = Compiler.compileEval(prog, Source("eval", text), direct = false)
        val ge = realm.globalEnv
        val frame = Frame(null, cb, realm, ge.thisValue, EMPTY_ARGS, Undefined, ge)
        return Interpreter.execute(frame)
    }

    @JvmStatic
    fun directEval(f: Frame, args: Array<Any?>, strictCaller: Boolean): Any? {
        val x = args.arg(0)
        if (x !is CharSequence) return x
        val realm = f.realm
        checkCodeGen(realm)
        val text = x.toString()
        val ctx = f.code.evalCtx
        val opts = ParseOptions(
            strict = strictCaller,
            allowNewTarget = ctx and CodeBlock.CTX_NEW_TARGET != 0,
            allowSuperProperty = ctx and CodeBlock.CTX_SUPER_PROP != 0,
            allowSuperCall = ctx and CodeBlock.CTX_SUPER_CALL != 0,
            inClassFieldInit = ctx and CodeBlock.CTX_FIELD_INIT != 0,
            allowArguments = ctx and CodeBlock.CTX_FIELD_INIT == 0,
            privateNames = f.code.privateNames ?: emptySet(),
            sourceName = "eval",
        )
        val prog = try {
            Parser.parse(text, opts)
        } catch (e: JSSyntaxError) {
            throw syntaxError(realm, e)
        }
        val cb = Compiler.compileEval(prog, Source("eval", text), direct = true)
        cb.evalCtx = ctx
        cb.privateNames = f.code.privateNames
        val frame = Frame(f.fn, cb, realm, f.thisValue, f.args, f.newTarget, f.env)
        frame.homeObject = f.fn?.homeObject ?: f.homeObject
        return Interpreter.execute(frame)
    }

    // ------------------------------------------------------------------ declaration instantiation

    private fun canDeclareGlobalFunction(g: JSObject, name: String): Boolean {
        val existing = g.getOwnProperty(name) ?: return g.isExtensible()
        return existing.configurable || existing.isData && existing.writable && existing.enumerable
    }

    private fun canDeclareGlobalVar(g: JSObject, name: String): Boolean {
        return g.hasOwnProperty(name) || g.isExtensible()
    }

    private fun createGlobalFunctionBinding(ge: GlobalEnv, name: String, fo: Any?, deletable: Boolean) {
        val g = ge.global
        val existing = g.getOwnProperty(name)
        val desc = if (existing == null || existing.configurable) PropertyDescriptor.data(fo, Attr.WRITABLE or Attr.ENUMERABLE or (if (deletable) Attr.CONFIGURABLE else 0))
        else PropertyDescriptor().value(fo)
        g.definePropertyOrThrow(name, desc)
        if (!g.set(name, fo, g)) throw JSException.typeError("Cannot assign to read only property '$name'")
        ge.varNames.add(name)
    }

    private fun createGlobalVarBinding(ge: GlobalEnv, name: String, deletable: Boolean) {
        val g = ge.global
        val hasProperty = g.hasOwnProperty(name)
        val extensible = g.isExtensible()
        if (!hasProperty && extensible) {
            g.definePropertyOrThrow(name, PropertyDescriptor.data(Undefined, Attr.WRITABLE or Attr.ENUMERABLE or (if (deletable) Attr.CONFIGURABLE else 0)))
        }
        ge.varNames.add(name)
    }

    private fun hasRestrictedGlobalProperty(g: JSObject, name: String): Boolean {
        val d = g.getOwnProperty(name) ?: return false
        return !d.configurable
    }

    @JvmStatic
    fun globalDeclarationInstantiation(realm: Realm, info: DeclInfo, fns: Array<Any?>) {
        val ge = realm.globalEnv
        val g = ge.global
        for (name in info.lexNames) {
            // Script vars are non-configurable (restricted); vars created by sloppy direct eval may be shadowed.
            if (ge.lexical.containsKey(name)) throw JSException.syntaxError("Identifier '$name' has already been declared")
            if (hasRestrictedGlobalProperty(g, name)) throw JSException.syntaxError("Identifier '$name' has already been declared")
        }
        for (name in info.varNames) if (ge.lexical.containsKey(name)) throw JSException.syntaxError("Identifier '$name' has already been declared")
        for (name in info.functionNames) if (ge.lexical.containsKey(name)) throw JSException.syntaxError("Identifier '$name' has already been declared")
        for (i in info.functionNames.indices.reversed()) {
            val name = info.functionNames[i]
            if (!canDeclareGlobalFunction(g, name)) throw JSException.typeError("Cannot declare global function '$name'")
        }
        val fnSet = info.functionNames.toHashSet()
        for (name in info.varNames) {
            if (fnSet.contains(name)) continue
            if (!canDeclareGlobalVar(g, name)) throw JSException.typeError("Cannot declare global variable '$name'")
        }
        for (name in info.lexNames.indices) {
            val n = info.lexNames[name]
            ge.declareLexical(n, GlobalBinding(Uninitialized, !info.lexConst[name]))
        }
        for (i in info.functionNames.indices) createGlobalFunctionBinding(ge, info.functionNames[i], fns[i], false)
        for (name in info.varNames) {
            if (fnSet.contains(name)) continue
            if (name in info.annexBNames) {
                if (ge.lexical.containsKey(name) || !canDeclareGlobalVar(g, name)) continue
            }
            createGlobalVarBinding(ge, name, false)
        }
    }

    /** Finds the variable environment used by direct eval from [env]. */
    private fun varEnvOf(env: Env?): Env? {
        var e = env
        while (e != null) {
            if (e is DeclEnv && e.info.evalVarTarget) return e
            if (e is GlobalEnv) return e
            e = e.parent
        }
        return null
    }

    @JvmStatic
    fun evalDeclarationInstantiation(f: Frame, info: DeclInfo, fns: Array<Any?>) {
        val realm = f.realm
        // the eval frame's env at this point is its own lexical env (or the caller env when no lexical decls)
        val lexEnv = f.env
        val varEnv = varEnvOf(lexEnv) ?: realm.globalEnv
        val allNames = LinkedHashSet<String>()
        allNames.addAll(info.functionNames)
        allNames.addAll(info.varNames)
        if (!info.strict) {
            if (varEnv is GlobalEnv) {
                for (name in allNames) if (varEnv.lexical.containsKey(name)) throw JSException.syntaxError("Identifier '$name' has already been declared")
            }
            var e = lexEnv
            while (e != null) {
                if (e is DeclEnv && !(e === f.env && e.info.kind == ScopeKind.EVAL)) {
                    for (name in allNames) {
                        val i = e.info.lookup(name)
                        if (i >= 0) {
                            val fl = e.info.flags[i]
                            if (fl and ScopeInfo.F_CATCH != 0) continue
                            if (e === varEnv && fl and ScopeInfo.F_LEXICAL == 0) continue
                            if (e !== varEnv || fl and ScopeInfo.F_LEXICAL != 0) {
                                if (name in info.annexBNames && !info.functionNames.contains(name) && !info.varNames.contains(name)) continue
                                throw JSException.syntaxError("Identifier '$name' has already been declared")
                            }
                        }
                    }
                }
                if (e === varEnv) break
                e = e.parent
            }
        }
        if (varEnv is GlobalEnv) {
            val g = varEnv.global
            for (i in info.functionNames.indices.reversed()) {
                if (!canDeclareGlobalFunction(g, info.functionNames[i])) throw JSException.typeError("Cannot declare global function '${info.functionNames[i]}'")
            }
            for (name in info.varNames) {
                if (info.functionNames.contains(name)) continue
                if (name in info.annexBNames) continue
                if (!canDeclareGlobalVar(g, name)) throw JSException.typeError("Cannot declare global variable '$name'")
            }
            for (i in info.functionNames.indices) createGlobalFunctionBinding(varEnv, info.functionNames[i], fns[i], true)
            for (name in info.varNames) {
                if (info.functionNames.contains(name)) continue
                if (name in info.annexBNames && (varEnv.lexical.containsKey(name) || !canDeclareGlobalVar(g, name))) continue
                createGlobalVarBinding(varEnv, name, true)
            }
            return
        }
        val de = varEnv as DeclEnv
        for (i in info.functionNames.indices) {
            val name = info.functionNames[i]
            setOrCreateVar(de, name, fns[i])
        }
        for (name in info.varNames) {
            if (info.functionNames.contains(name)) continue
            if (de.info.lookup(name) >= 0) continue
            var ext = de.extension
            if (ext == null) {
                ext = LinkedHashMap()
                de.extension = ext
            }
            if (!ext.containsKey(name)) ext[name] = Undefined
        }
    }

    private fun setOrCreateVar(de: DeclEnv, name: String, value: Any?) {
        val i = de.info.lookup(name)
        if (i >= 0) {
            de.slots[i] = value
            return
        }
        var ext = de.extension
        if (ext == null) {
            ext = LinkedHashMap()
            de.extension = ext
        }
        ext[name] = value
    }

    // ------------------------------------------------------------------ Function constructor

    /** CreateDynamicFunction */
    @JvmStatic
    fun createDynamicFunction(realm: Realm, newTarget: JSObject?, kind: String, args: Array<Any?>): JSObject {
        checkCodeGen(realm)
        val argCount = args.size
        val sb = StringBuilder()
        for (i in 0 until argCount - 1) {
            if (i > 0) sb.append(',')
            sb.append(Ops.toString(args[i]))
        }
        val bodyText = if (argCount > 0) Ops.toString(args[argCount - 1]) else ""
        val isAsync = kind == "async" || kind == "asyncGenerator"
        val isGen = kind == "generator" || kind == "asyncGenerator"
        val (fnNode, full) = try {
            Parser.parseDynamicFunction(sb.toString(), bodyText, isAsync, isGen)
        } catch (e: JSSyntaxError) {
            throw syntaxError(realm, e)
        }
        val src = Source("anonymous", full)
        fnNode.srcStart = 0
        fnNode.srcEnd = full.length
        val cb = Compiler.compileFunction(fnNode, src)
        cb.srcStart = 0
        cb.srcEnd = full.length
        io.neonjs.jit.Jit.prefetch(cb, realm.agent)
        val fallback = when (kind) {
            "generator" -> realm.generatorFunctionPrototype
            "async" -> realm.asyncFunctionPrototype
            "asyncGenerator" -> realm.asyncGeneratorFunctionPrototype
            else -> realm.functionPrototype
        }
        val proto = if (newTarget == null) fallback else Ops.getPrototypeFromConstructor(newTarget) {
            when (kind) {
                "generator" -> it.generatorFunctionPrototype
                "async" -> it.asyncFunctionPrototype
                "asyncGenerator" -> it.asyncGeneratorFunctionPrototype
                else -> it.functionPrototype
            }
        }
        val fn = Rt.makeClosure(realm, cb, realm.globalEnv, null)
        fn.changeProto(proto)
        return fn
    }
}
