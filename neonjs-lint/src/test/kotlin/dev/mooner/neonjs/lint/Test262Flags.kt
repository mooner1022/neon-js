package dev.mooner.neonjs.lint

/** The flags of a Test262 test's front matter (`flags: [a, b]` or `- a` lines), as neonjs-test262's TestMeta reads them. */
internal fun test262Flags(src: String): Set<String> {
    val s = src.indexOf("/*---")
    val e = src.indexOf("---*/", if (s < 0) 0 else s)
    if (s < 0 || e < 0) return emptySet()
    val out = HashSet<String>()
    var inFlags = false
    for (raw in src.substring(s + 5, e).lines()) {
        if (raw.isBlank()) continue
        val line = raw.trim()
        if (raw.length - raw.trimStart().length == 0) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            inFlags = line.substring(0, colon).trim() == "flags"
            val rest = line.substring(colon + 1).trim()
            if (inFlags && rest.startsWith("[")) rest.removePrefix("[").substringBefore("]").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
            else if (inFlags && rest.isNotEmpty() && rest != "|" && rest != ">") out.add(rest)
        } else if (inFlags && line.startsWith("- ")) out.add(line.substring(2).trim())
    }
    return out
}
