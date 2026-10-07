package io.neonjs

/**
 * Receives output of the JS `console` object. Messages are fully formatted (format specifiers applied, objects
 * inspected without running guest code, group indentation added).
 */
fun interface NeonConsole {
    enum class Level { DEBUG, LOG, INFO, WARN, ERROR }

    fun write(level: Level, message: String)

    companion object {
        /** Writes log/info/debug to stdout and warn/error to stderr. */
        @JvmField val STDIO = NeonConsole { level, message ->
            if (level >= Level.WARN) System.err.println(message) else println(message)
        }

        /** Discards all output (the `console` object still exists so scripts do not fail). */
        @JvmField val DISCARD = NeonConsole { _, _ -> }
    }
}
