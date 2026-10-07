package dev.mooner.neonjs.parser

import dev.mooner.neonjs.unicode.UnicodeTables

/** Unicode ID_Start / ID_Continue classification (UAX #31, Unicode 17). */
object UnicodeId {
    fun isIdStart(c: Int): Boolean = UnicodeTables.isIdStart(c)

    fun isIdContinue(c: Int): Boolean = UnicodeTables.isIdContinue(c)
}
