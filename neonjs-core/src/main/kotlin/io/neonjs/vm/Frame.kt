package io.neonjs.vm

import io.neonjs.compiler.CodeBlock
import io.neonjs.runtime.*

/** Activation record of an interpreted function, script or eval. Registers and operand stack share [slots]. */
class Frame(
    @JvmField val fn: JSClosure?,
    @JvmField val code: CodeBlock,
    @JvmField val realm: Realm,
    @JvmField var thisValue: Any?,
    @JvmField val args: Array<Any?>,
    @JvmField val newTarget: Any?,
    @JvmField var env: Env?,
    /** Compiled code for this activation (set when the code block was JIT-compiled before the call). */
    @JvmField val compiled: io.neonjs.jit.CompiledCode? = code.compiled as io.neonjs.jit.CompiledCode?,
) {
    @JvmField val slots: Array<Any?> = if (compiled != null) NO_SLOTS else arrayOfNulls(code.numRegs + code.maxStack)
    @JvmField var sp: Int = code.numRegs
    @JvmField var pc: Int = 0

    // generator / async state
    @JvmField var resumeMode = 0
    @JvmField var suspendKind = 0
    @JvmField var suspendValue: Any? = null
    @JvmField var generator: Any? = null
    /** pc of the await instruction the frame is suspended at. */
    /** Proper tail call request (see [Interpreter.TAIL]). */
    @JvmField var tailFn: JSClosure? = null
    @JvmField var tailThis: Any? = null
    @JvmField var tailArgs: Array<Any?>? = null
    /** Set by instructions outside the main loop that requested a tail call. */
    @JvmField var tailPending = false
    @JvmField var awaitPc = 0

    /** Home object for scripts/eval executed in a method context (unused for functions: taken from fn). */
    @JvmField var homeObject: JSObject? = null
    /** Active function for eval code inside functions. */
    @JvmField var parent: Frame? = null

    init {
        if (compiled == null) initSlots()
    }

    private fun initSlots() {
        val regs = code.numRegs
        for (i in 0 until regs) slots[i] = Undefined
        val pr = code.paramRegs
        if (pr != null) {
            val n = minOf(pr.size, args.size)
            for (i in 0 until n) {
                val r = pr[i]
                if (r >= 0) slots[r] = args[i]
            }
        }
    }

    companion object {
        @JvmField val NO_SLOTS = arrayOfNulls<Any?>(0)
        const val SUSPEND_YIELD = 1
        const val SUSPEND_AWAIT = 2
        const val SUSPEND_INITIAL = 3
        const val SUSPEND_YIELD_RAW = 4

        const val MODE_NEXT = 0
        const val MODE_THROW = 1
        const val MODE_RETURN = 2
    }
}

/** Interpreted (bytecode) function object. */
class JSClosure(realm: Realm, @JvmField val code: CodeBlock, @JvmField val env: Env?, proto: JSObject?) : JSFunction(realm, proto) {
    @JvmField var homeObject: JSObject? = null
    /**
     * For arrow functions: `this` of the frame that created the closure. Arrows nested in functions read `this` through
     * a captured binding; this covers arrows whose enclosing `this` is the script/module/eval top level.
     */
    @JvmField var lexicalThis: Any? = Undefined
    /** Class constructor data: instance field records and private methods. */
    @JvmField var fields: ArrayList<FieldRecord>? = null
    @JvmField var privateMethods: ArrayList<PrivateElement>? = null
    /** Initializers added by decorators of instance methods / accessors: run before fields are initialized. */
    @JvmField var extraInitializers: ArrayList<JSObject>? = null

    init {
        special = special or CALLABLE
        if (code.flags and CodeBlock.CONSTRUCTOR != 0) special = special or CONSTRUCTOR
        if (code.flags and CodeBlock.CLASS_CTOR != 0) special = special or CLASS_CONSTRUCTOR
    }

    override fun call(thisArg: Any?, args: Array<Any?>): Any? = Interpreter.callClosure(this, thisArg, args)

    override fun construct(args: Array<Any?>, newTarget: JSObject): Any? = Interpreter.constructClosure(this, args, newTarget)

    override fun sourceText(): String = code.sourceText() ?: "function ${debugName()}() { [native code] }"
}

/** Class field definition record. [key] is a property key or a PrivateName. */
class FieldRecord(@JvmField val key: Any, @JvmField val initializer: Any?) {
    /** Initializers returned by field / accessor decorators: each maps the value to the next one. */
    @JvmField var decoratorInitializers: List<JSObject>? = null
    /** Functions registered with context.addInitializer by field decorators: run after the field is defined. */
    @JvmField var extraInitializers: List<JSObject>? = null
}

/** Private name (unique per class evaluation). */
class PrivateName(@JvmField val description: String) {
    override fun toString() = description
}

/** Private method or accessor element. */
class PrivateElement(@JvmField val key: PrivateName, @JvmField val kind: Int, @JvmField var value: Any?, @JvmField var getter: Any?, @JvmField var setter: Any?) {
    companion object {
        const val FIELD = 0
        const val METHOD = 1
        const val ACCESSOR = 2
    }
}
