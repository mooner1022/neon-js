package dev.mooner.neonwasm.wasi

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.Random

/** Where a WASI program's clocks read time: the host's, or fixed (reproducible runs, no timing channel). */
interface WasiClock {
    /** Nanoseconds since the Unix epoch. */
    fun realtimeNanos(): Long

    /** Nanoseconds from an arbitrary start, never decreasing. */
    fun monotonicNanos(): Long

    companion object {
        @JvmField val SYSTEM: WasiClock = object : WasiClock {
            override fun realtimeNanos(): Long {
                val t = Instant.now()
                return t.epochSecond * 1_000_000_000L + t.nano
            }

            override fun monotonicNanos(): Long = System.nanoTime()
        }

        /** Realtime fixed at [epochNanos]; the monotonic clock advancing a microsecond per read. */
        @JvmStatic
        fun fixed(epochNanos: Long): WasiClock = object : WasiClock {
            private var mono = 0L
            override fun realtimeNanos() = epochNanos
            override fun monotonicNanos(): Long {
                mono += 1000
                return mono
            }
        }
    }
}

/**
 * What a WASI program sees of the world: its arguments and environment, its standard streams, the directories it
 * may open (each preopened under a name, in an in-memory file system: [WasiFs]), its clocks and its randomness.
 * Nothing else: no host file, no socket, no process.
 */
class WasiConfig private constructor(b: Builder) {
    @JvmField val args: List<String> = b.args.toList()
    @JvmField val env: List<Pair<String, String>> = b.env.toList()
    @JvmField val stdin: InputStream? = b.stdin
    @JvmField val stdout: OutputStream? = b.stdout
    @JvmField val stderr: OutputStream? = b.stderr
    @JvmField val preopens: List<Pair<String, WasiFs.Dir>> = b.preopens.toList()
    @JvmField val clock: WasiClock = b.clock
    @JvmField val random: Random = b.random

    class Builder {
        internal val args = ArrayList<String>()
        internal val env = ArrayList<Pair<String, String>>()
        internal var stdin: InputStream? = null
        internal var stdout: OutputStream? = null
        internal var stderr: OutputStream? = null
        internal val preopens = ArrayList<Pair<String, WasiFs.Dir>>()
        internal var clock: WasiClock = WasiClock.SYSTEM
        internal var random: Random = SecureRandom()

        /** The program's arguments, its name first (argv). */
        fun args(vararg a: String) = apply { args.addAll(a) }
        fun args(a: List<String>) = apply { args.addAll(a) }
        fun env(name: String, value: String) = apply { env.add(name to value) }
        fun stdin(s: InputStream) = apply { stdin = s }
        fun stdout(s: OutputStream) = apply { stdout = s }
        fun stderr(s: OutputStream) = apply { stderr = s }

        /** A directory the program may open, as [guestPath] (fds 3 and up, in the order given). */
        fun preopen(guestPath: String, dir: WasiFs.Dir) = apply { preopens.add(guestPath to dir) }
        fun clock(c: WasiClock) = apply { clock = c }
        fun random(r: Random) = apply { random = r }

        /** A fixed time and seeded randomness: the same run every time. */
        fun deterministic(seed: Long, epochNanos: Long) = apply {
            random = Random(seed)
            clock = WasiClock.fixed(epochNanos)
        }

        fun build() = WasiConfig(this)
    }

    companion object {
        @JvmStatic fun builder() = Builder()
    }
}
