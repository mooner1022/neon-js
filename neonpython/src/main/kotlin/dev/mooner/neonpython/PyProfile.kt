package dev.mooner.neonpython

/**
 * What the interpreter notes of the operands of an arithmetic or comparison instruction ([PyCodeInfo.profile]), as
 * bits: [INT] both ints that are Longs, [FLOAT] both floats, [OTHER] anything else (a bool, an int past 64 bits, an int
 * and a float, a str...). The speculating tier speculates where only one of [INT] and [FLOAT] was seen.
 */
object PyProfile {
    const val INT = 1
    const val FLOAT = 2
    const val OTHER = 4

    @JvmStatic fun of(a: Any?, b: Any?): Int = when {
        a is Long && b is Long -> INT
        a is Double && b is Double -> FLOAT
        else -> OTHER
    }

    @JvmStatic fun of(a: Any?): Int = when (a) {
        is Long -> INT
        is Double -> FLOAT
        else -> OTHER
    }

    @JvmStatic fun record(p: ByteArray, pc: Int, bits: Int) {
        val old = p[pc].toInt()
        if (old or bits != old) p[pc] = (old or bits).toByte()
    }
}
