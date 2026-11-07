package dev.mooner.neonjs.jit.opt

/**
 * Calls compiled inlined that nothing can see from outside the frame (docs/OPTIMIZING_TIER.md, P4): their body (with
 * the calls inlined in it) neither throws nor calls nor loops nor catches, so no stack trace, depth limit or handler
 * looks for them in the frame's record of inlined calls (Frame.inlineFn), and their InlineEnter and InlineExit generate
 * nothing ([InlineSite.quiet]). Their exits stay: an exit gives the call a frame of its own, as at any exit (Deopt.resume
 * gives back the depth of the calls recorded, these not being), and the interpreter then makes it as a call, its depth
 * limit included. A loop's interrupt check does not depend on calls (IrCodegen counts down at every back edge).
 */
internal object QuietInlines {
    fun run(g: Graph, kinds: Kinds) {
        val blocks = HashMap<InlineSite, ArrayList<Block>>()
        for (b in g.blocks) {
            var s = b.site
            while (s != null) {
                blocks.getOrPut(s) { ArrayList() }.add(b)
                s = s.parent
            }
        }
        // the innermost first: a body is quiet where the calls inlined in it are
        for (s in blocks.keys.sortedByDescending { it.depth }) {
            s.quiet = blocks[s]!!.all { b -> !b.loopHeader && !b.catchEntry && b.insts.all { i -> i.callee?.quiet ?: g.ops.quiet(i, kinds) } }
        }
    }
}
