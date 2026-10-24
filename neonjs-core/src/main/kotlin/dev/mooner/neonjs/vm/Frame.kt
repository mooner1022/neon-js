package dev.mooner.neonjs.vm

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.runtime.*

/** Activation record of an interpreted function, script or eval. Registers and operand stack share [slots]. */
class Frame(
    @JvmField var fn: JSClosure?,
    @JvmField var code: CodeBlock,
    @JvmField var realm: Realm,
    @JvmField var thisValue: Any?,
    @JvmField var args: Array<Any?>,
    @JvmField var newTarget: Any?,
    @JvmField var env: Env?,
    /**
     * Compiled code to run this activation with (the code block's entry code), or null to interpret it. Not kept: a
     * frame run by compiled code has [NO_SLOTS] (see [isCompiled]), and every version of a block's code runs such a
     * frame.
     */
    compiled: dev.mooner.neonjs.jit.CompiledCode? = code.compiled as dev.mooner.neonjs.jit.CompiledCode?,
) {
    /**
     * Registers, then the operand stack, of an interpreted frame; [NO_SLOTS] for a frame compiled code runs, until a
     * deoptimization gives it slots and the interpreter continues it (dev.mooner.neonjs.jit.Deopt).
     */
    @JvmField var slots: Array<Any?> = if (compiled != null) NO_SLOTS else arrayOfNulls(code.numRegs + code.maxStack)
    @JvmField var sp: Int = code.numRegs
    @JvmField var pc: Int = 0

    /**
     * Generator / async and tail-call state, created on first use: most frames never need it, and every call allocates
     * a frame (a smaller one is cheaper, notably on ART).
     */
    @JvmField var ext: FrameExt? = null

    /**
     * Bumped each time the frame starts another activation (Agent.framePool): where an error was captured is a frame
     * and its activation (JSErrorObject.capturedAt).
     */
    @JvmField var activation = 0

    /**
     * The frame of a returned call of compiled code (from Agent.framePool) made the frame of a new one, as the
     * constructor makes it for compiled code (nothing else holds a frame once its call returned: the stack traces
     * captured are strings, error objects hold a frame weakly with its activation, arguments objects hold the
     * arguments and the environment).
     */
    fun reuse(fn: JSClosure, code: CodeBlock, realm: Realm, thisValue: Any?, args: Array<Any?>, newTarget: Any?, env: Env?) {
        this.fn = fn
        this.code = code
        this.realm = realm
        this.thisValue = thisValue
        this.args = args
        this.newTarget = newTarget
        this.env = env
        sp = code.numRegs
        pc = 0
        activation++
    }

    /** A returned frame going into Agent.framePool: it keeps none of the call's values alive there. */
    fun release() {
        fn = null
        thisValue = null
        args = NO_SLOTS
        env = null
        slots = NO_SLOTS
        parent = null
        inlineFn = null
        inlinePc = 0
    }

    private fun ext(): FrameExt = ext ?: FrameExt().also { ext = it }

    // generator / async state
    var resumeMode: Int
        get() = ext?.resumeMode ?: 0
        set(v) { ext().resumeMode = v }
    var suspendKind: Int
        get() = ext?.suspendKind ?: 0
        set(v) { ext().suspendKind = v }
    var suspendValue: Any?
        get() = ext?.suspendValue
        set(v) { ext().suspendValue = v }
    var generator: Any?
        get() = ext?.generator
        set(v) { ext().generator = v }
    /** pc of the await instruction the frame is suspended at. */
    var awaitPc: Int
        get() = ext?.awaitPc ?: 0
        set(v) { ext().awaitPc = v }

    /** Proper tail call request (see [Interpreter.TAIL]). */
    var tailFn: JSClosure?
        get() = ext?.tailFn
        set(v) { ext().tailFn = v }
    var tailThis: Any?
        get() = ext?.tailThis
        set(v) { ext().tailThis = v }
    var tailArgs: Array<Any?>?
        get() = ext?.tailArgs
        set(v) { ext().tailArgs = v }
    /** Set by instructions outside the main loop that requested a tail call. */
    var tailPending: Boolean
        get() = ext?.tailPending ?: false
        set(v) { ext().tailPending = v }

    /**
     * Deoptimization (dev.mooner.neonjs.jit.Deopt): a call that compiled code ran inlined and that this frame, now
     * interpreted, completes first when the interpreter continues it (a Deopt.Inlined); then an exception to throw at
     * [throwPc] before anything else (one that call threw).
     */
    var deoptCall: Any?
        get() = ext?.deoptCall
        set(v) { ext().deoptCall = v }
    var pendingThrow: Throwable?
        get() = ext?.pendingThrow
        set(v) { ext().pendingThrow = v }
    var throwPc: Int
        get() = ext?.throwPc ?: 0
        set(v) { ext().throwPc = v }

    /** Home object for scripts/eval executed in a method context (unused for functions: taken from fn). */
    var homeObject: JSObject?
        get() = ext?.homeObject
        set(v) { ext().homeObject = v }
    /** Active function for eval code inside functions. */
    @JvmField var parent: Frame? = null
    /**
     * The function whose body compiled code is running inlined in this frame (dev.mooner.neonjs.jit.Inlining), and the
     * pc in that body; [pc] stays at the call. Stack traces show the inlined call as a frame of its own. Calls inlined
     * into it, the optimizing tier's, make it the innermost one ([FrameExt.inlineFns] holds the others).
     */
    @JvmField var inlineFn: JSClosure? = null
    @JvmField var inlinePc: Int = 0

    init {
        if (compiled == null) initSlots()
    }

    /** Whether compiled code runs this frame: it was made for compiled code and not deoptimized since. */
    val isCompiled: Boolean get() = slots === NO_SLOTS

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

    /** The rarely used part of a [Frame]. */
    class FrameExt {
        @JvmField var resumeMode = 0
        @JvmField var suspendKind = 0
        @JvmField var suspendValue: Any? = null
        @JvmField var generator: Any? = null
        @JvmField var awaitPc = 0
        @JvmField var tailFn: JSClosure? = null
        @JvmField var tailThis: Any? = null
        @JvmField var tailArgs: Array<Any?>? = null
        @JvmField var tailPending = false
        @JvmField var homeObject: JSObject? = null
        @JvmField var deoptCall: Any? = null
        @JvmField var pendingThrow: Throwable? = null
        @JvmField var throwPc = 0
        /**
         * Calls compiled inlined into one another (the optimizing tier's), but the innermost ([inlineFn]): the function of
         * each, outermost first, and its position, the call of the next one; [inlineCount] of them.
         */
        @JvmField var inlineFns: Array<JSClosure?>? = null
        @JvmField var inlinePcs: IntArray? = null
        @JvmField var inlineCount = 0
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
