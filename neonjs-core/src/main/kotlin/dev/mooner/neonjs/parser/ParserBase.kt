package dev.mooner.neonjs.parser

/** Binding kinds used for early-error redeclaration checks. */
internal enum class Bind { NONE, VAR, LEXICAL, FUNCTION, SIMPLE_CATCH, OUTSIDE }

internal const val SCOPE_TOP = 1
internal const val SCOPE_FUNCTION = 2
internal const val SCOPE_ASYNC = 4
internal const val SCOPE_GENERATOR = 8
internal const val SCOPE_ARROW = 16
internal const val SCOPE_SIMPLE_CATCH = 32
internal const val SCOPE_SUPER = 64
internal const val SCOPE_DIRECT_SUPER = 128
internal const val SCOPE_CLASS_STATIC_BLOCK = 256
internal const val SCOPE_CLASS_FIELD_INIT = 512
internal const val SCOPE_VAR = SCOPE_TOP or SCOPE_FUNCTION or SCOPE_CLASS_STATIC_BLOCK

internal class PScope(val flags: Int) {
    val vars = HashSet<String>()
    val lexical = ArrayList<String>()
    val functions = HashSet<String>()
    /** First name declared via simple catch param (for Annex B var redeclaration). */
    var catchParam: String? = null
    var inClassFieldInit = false
}

internal class Label(val name: String?, var kind: Int /* 0 = other, 1 = loop, 2 = switch */, var statementStart: Int)

internal class PrivateNameScope(val parent: PrivateNameScope?) {
    /** name -> kind string: "field", "method", "get", "set", "accessor"; with static prefix "s" */
    val declared = HashMap<String, String>()
    val used = ArrayList<PrivateIdentifier>()
}

/** Destructuring error tracking for cover grammars. Positions are -1 when unset. */
internal class DestructuringErrors {
    var shorthandAssign = -1
    var trailingComma = -1
    var doubleProto = -1
}

/** Options for parsing. */
class ParseOptions(
    val isModule: Boolean = false,
    val strict: Boolean = false,
    /** Parsing code for a direct/indirect eval or Function constructor; enables context-dependent constructs. */
    val allowReturnOutsideFunction: Boolean = false,
    val allowNewTarget: Boolean = false,
    val allowSuperProperty: Boolean = false,
    val allowSuperCall: Boolean = false,
    val allowArguments: Boolean = true,
    /** In a class field initializer context (eval inside field initializer). */
    val inClassFieldInit: Boolean = false,
    /** Private names visible from the enclosing class(es) (direct eval inside class bodies). */
    val privateNames: Set<String> = emptySet(),
    val sourceName: String? = null,
    /** Allow top-level await in scripts (REPL convenience). */
    val topLevelAwait: Boolean = false,
    /** Allow html-like comments (Annex B). */
    val annexB: Boolean = true,
)

internal abstract class ParserBase(val src: String, val options: ParseOptions) {
    val lex = Lexer(src, options.sourceName, options.isModule)
    val inModule = options.isModule
    var strict = options.strict || options.isModule

    val scopeStack = ArrayList<PScope>()
    var labels = ArrayList<Label>()
    var privateNames: PrivateNameScope? = null

    /** Positions of yield/await expressions and 'await' identifiers (for arrow param validation). */
    var yieldPos = -1
    var awaitPos = -1
    var awaitIdentPos = -1

    /** Set when a direct eval call is parsed inside the current function. */
    val functionStack = ArrayList<FunctionInfo>()

    class FunctionInfo(val node: Node?) {
        var hasDirectEval = false
        var usesArguments = false
        var usesThis = false
        var usesSuperProperty = false
        var usesSuperCall = false
        var usesNewTarget = false
        var containsEval = false
    }

    val type: T get() = lex.type
    val value: Any? get() = lex.value

    fun next() {
        lex.next()
    }

    fun raise(pos: Int, msg: String): Nothing = lex.error(msg, pos)

    fun unexpected(pos: Int = lex.start): Nothing {
        if (lex.type == T.EOF && pos == lex.start) raise(pos, "Unexpected end of input")
        val tokText = when (lex.type) {
            T.NAME -> "identifier '${lex.value}'"
            T.STRING -> "string"
            T.NUM -> "number"
            T.EOF -> "end of input"
            else -> "token '${lex.type.text}'"
        }
        raise(pos, "Unexpected $tokText")
    }

    fun eat(t: T): Boolean {
        if (lex.type == t) { next(); return true }
        return false
    }

    fun expect(t: T) {
        if (!eat(t)) {
            if (lex.type == T.EOF) raise(lex.start, "Unexpected end of input")
            raise(lex.start, "Expected '${t.text}' but found ${describeToken()}")
        }
    }

    fun describeToken(): String = when (lex.type) {
        T.NAME -> "'${lex.value}'"
        T.EOF -> "end of input"
        T.STRING -> "string"
        T.NUM -> "number"
        else -> "'${lex.type.text}'"
    }

    /** Is the current token the (unescaped) contextual keyword [name]? */
    fun isContextual(name: String): Boolean = lex.type == T.NAME && lex.value == name && !lex.escaped

    fun eatContextual(name: String): Boolean {
        if (isContextual(name)) { next(); return true }
        return false
    }

    fun expectContextual(name: String) {
        if (!eatContextual(name)) unexpected()
    }

    /** Current token is a keyword [kw] (identifiers with escapes are never keywords). */
    fun isKw(kw: String): Boolean = lex.type == T.NAME && lex.value == kw && !lex.escaped

    fun eatKw(kw: String): Boolean {
        if (isKw(kw)) {
            next(); return true
        }
        return false
    }

    fun expectKw(kw: String) {
        if (!eatKw(kw)) raise(lex.start, "Expected '$kw' but found ${describeToken()}")
    }

    fun canInsertSemicolon(): Boolean = lex.type == T.EOF || lex.type == T.RBRACE || lex.nlBefore

    fun semicolon() {
        if (!eat(T.SEMI) && !canInsertSemicolon()) unexpected()
    }

    fun isLineTerminatorBefore(): Boolean = lex.nlBefore

    /** Peek at the next token type and value without consuming. */
    inline fun <R> lookahead(f: () -> R): R {
        val s = lex.save()
        try {
            next()
            return f()
        } finally {
            lex.restore(s)
        }
    }

    fun <N : Node> finish(node: N, start: Int, line: Int, col: Int): N {
        node.start = start
        node.end = lex.prevEnd
        node.line = line
        node.col = col
        return node
    }

    fun <N : Node> finishAt(node: N, startNode: Node): N {
        node.start = startNode.start
        node.end = lex.prevEnd
        node.line = startNode.line
        node.col = startNode.col
        return node
    }

    // ------------------------------------------------------------ scopes

    fun enterScope(flags: Int) {
        scopeStack.add(PScope(flags))
    }

    fun exitScope() {
        scopeStack.removeAt(scopeStack.size - 1)
    }

    val currentScope: PScope get() = scopeStack[scopeStack.size - 1]

    fun currentVarScope(): PScope {
        for (i in scopeStack.indices.reversed()) {
            val s = scopeStack[i]
            if (s.flags and SCOPE_VAR != 0) return s
        }
        throw IllegalStateException()
    }

    /** Nearest scope that provides `this` (non-arrow function, class field init, static block or top). */
    fun currentThisScope(): PScope {
        for (i in scopeStack.indices.reversed()) {
            val s = scopeStack[i]
            if (s.flags and SCOPE_VAR != 0 && s.flags and SCOPE_ARROW == 0) return s
        }
        throw IllegalStateException()
    }

    val inFunction: Boolean get() = currentVarScope().flags and SCOPE_FUNCTION != 0 || (scopeStack.size > 0 && functionStack.size > 1)

    val inGenerator: Boolean
        get() {
            val s = currentVarScope()
            return s.flags and SCOPE_GENERATOR != 0 && !s.inClassFieldInit
        }

    val inAsync: Boolean
        get() {
            val s = currentVarScope()
            if (s.inClassFieldInit) return false
            if (s.flags and SCOPE_TOP != 0) return (inModule || options.topLevelAwait)
            return s.flags and SCOPE_ASYNC != 0
        }

    /**
     * True when `await` is reserved here as an identifier (async function, module, static block). A class field
     * initializer is parsed with [~Await] whatever its context, so `await` is an identifier there in scripts.
     */
    val awaitReserved: Boolean
        get() {
            if (inModule) return true
            for (i in scopeStack.indices.reversed()) {
                val s = scopeStack[i]
                if (s.flags and SCOPE_CLASS_STATIC_BLOCK != 0) return true
                if (s.flags and SCOPE_VAR != 0) {
                    if (s.inClassFieldInit) return false
                    if (s.flags and SCOPE_ASYNC != 0) return true
                    if (s.flags and SCOPE_TOP != 0) return options.topLevelAwait
                    return false
                }
            }
            return false
        }

    val inStaticBlock: Boolean
        get() {
            for (i in scopeStack.indices.reversed()) {
                val s = scopeStack[i]
                if (s.flags and SCOPE_CLASS_STATIC_BLOCK != 0) return true
                if (s.flags and SCOPE_VAR != 0) return false
            }
            return false
        }

    val inClassFieldInit: Boolean
        get() {
            for (i in scopeStack.indices.reversed()) {
                val s = scopeStack[i]
                if (s.inClassFieldInit) return true
                if (s.flags and SCOPE_VAR != 0 && s.flags and SCOPE_ARROW == 0) return false
            }
            return false
        }

    val allowSuperProperty: Boolean
        get() {
            val s = currentThisScope()
            if (s.inClassFieldInit) return true
            if (s.flags and SCOPE_TOP != 0) return options.allowSuperProperty
            return s.flags and SCOPE_SUPER != 0 || s.flags and SCOPE_CLASS_STATIC_BLOCK != 0
        }

    val allowSuperCall: Boolean
        get() {
            val s = currentThisScope()
            if (s.inClassFieldInit) return false
            if (s.flags and SCOPE_TOP != 0) return options.allowSuperCall
            return s.flags and SCOPE_DIRECT_SUPER != 0
        }

    val allowNewTarget: Boolean
        get() {
            val s = currentThisScope()
            if (s.flags and SCOPE_TOP != 0) return options.allowNewTarget
            return true
        }

    val allowArguments: Boolean
        get() {
            for (i in scopeStack.indices.reversed()) {
                val s = scopeStack[i]
                if (s.inClassFieldInit) return false
                if (s.flags and SCOPE_CLASS_STATIC_BLOCK != 0) return false
                if (s.flags and SCOPE_VAR != 0 && s.flags and SCOPE_ARROW == 0) {
                    if (s.flags and SCOPE_TOP != 0) return options.allowArguments && !options.inClassFieldInit
                    return true
                }
            }
            return true
        }

    fun treatFunctionsAsVarInScope(s: PScope): Boolean =
        s.flags and SCOPE_FUNCTION != 0 || (!inModule && s.flags and SCOPE_TOP != 0) || s.flags and SCOPE_CLASS_STATIC_BLOCK != 0

    fun declareName(name: String, bind: Bind, pos: Int, fromForOf: Boolean = false) {
        var redeclared = false
        when (bind) {
            Bind.LEXICAL -> {
                val s = currentScope
                redeclared = s.lexical.contains(name) || s.functions.contains(name) || s.vars.contains(name)
                s.lexical.add(name)
            }
            Bind.SIMPLE_CATCH -> {
                val s = currentScope
                s.lexical.add(name)
                s.catchParam = name
            }
            Bind.FUNCTION -> {
                val s = currentScope
                redeclared = if (treatFunctionsAsVarInScope(s)) s.lexical.contains(name)
                else s.lexical.contains(name) || s.vars.contains(name)
                s.functions.add(name)
            }
            Bind.VAR -> {
                for (i in scopeStack.indices.reversed()) {
                    val s = scopeStack[i]
                    if ((s.lexical.contains(name) && !(s.flags and SCOPE_SIMPLE_CATCH != 0 && s.catchParam == name)) ||
                        (!treatFunctionsAsVarInScope(s) && s.functions.contains(name))
                    ) {
                        redeclared = true
                        break
                    }
                    s.vars.add(name)
                    if (s.flags and SCOPE_VAR != 0) break
                }
            }
            else -> {}
        }
        if (redeclared) raise(pos, "Identifier '$name' has already been declared")
    }

    // ------------------------------------------------------------ reserved words

    companion object {
        val keywords = hashSetOf(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else",
            "enum", "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof",
            "new", "null", "return", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void",
            "while", "with",
        )
        val strictReserved = hashSetOf(
            "implements", "interface", "let", "package", "private", "protected", "public", "static", "yield",
        )
    }

    fun isReservedWord(name: String): Boolean = keywords.contains(name)

    /** Validates an identifier used as IdentifierReference / BindingIdentifier / LabelIdentifier. */
    fun checkUnreserved(name: String, pos: Int, isBinding: Boolean = false) {
        if (inGenerator && name == "yield") raise(pos, "Cannot use 'yield' as identifier inside a generator")
        if (name == "await") {
            if (inAsync || awaitReserved) raise(pos, "Cannot use 'await' as identifier inside an async function or module")
        }
        if (name == "arguments" && !isBinding) {
            if (!allowArguments) raise(pos, "'arguments' is not allowed in class field initializer or static initialization block")
        }
        if (inStaticBlock && (name == "arguments" || name == "await")) raise(pos, "Cannot use '$name' in class static initialization block")
        if (keywords.contains(name)) raise(pos, "Unexpected reserved word '$name'")
        if (strict && strictReserved.contains(name)) raise(pos, "Unexpected strict mode reserved word '$name'")
    }

    // ------------------------------------------------------------ labels

    fun withFreshLabels(): ArrayList<Label> {
        val old = labels
        labels = ArrayList()
        return old
    }

    // ------------------------------------------------------------ function info

    val currentFunctionInfo: FunctionInfo get() = functionStack[functionStack.size - 1]

    /** The nearest non-arrow function info (where `this`/`arguments` usage is recorded). */
    fun thisFunctionInfo(): FunctionInfo = functionStack[functionStack.size - 1]
}
