package io.neonjs.test262

/** Parsed test262 YAML front matter. */
class TestMeta(
    val includes: List<String>,
    val flags: Set<String>,
    val features: List<String>,
    val negativePhase: String?,
    val negativeType: String?,
    val locale: List<String>,
) {
    val isModule get() = "module" in flags
    val isRaw get() = "raw" in flags
    val isAsync get() = "async" in flags
    val onlyStrict get() = "onlyStrict" in flags
    val noStrict get() = "noStrict" in flags

    companion object {
        fun parse(src: String): TestMeta {
            val s = src.indexOf("/*---")
            val e = src.indexOf("---*/", if (s < 0) 0 else s)
            if (s < 0 || e < 0) return TestMeta(emptyList(), emptySet(), emptyList(), null, null, emptyList())
            val yaml = src.substring(s + 5, e)
            val lines = yaml.lines()
            val map = HashMap<String, MutableList<String>>()
            var negPhase: String? = null
            var negType: String? = null
            var curKey: String? = null
            var inNegative = false
            for (raw in lines) {
                if (raw.isBlank()) continue
                val indent = raw.length - raw.trimStart().length
                val line = raw.trim()
                if (indent == 0) {
                    inNegative = false
                    val colon = line.indexOf(':')
                    if (colon < 0) continue
                    val key = line.substring(0, colon).trim()
                    val rest = line.substring(colon + 1).trim()
                    curKey = key
                    if (key == "negative") {
                        inNegative = true
                        continue
                    }
                    val list = map.getOrPut(key) { ArrayList() }
                    if (rest.startsWith("[")) {
                        val inner = rest.removePrefix("[").substringBefore("]")
                        inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { list.add(it) }
                    } else if (rest.isNotEmpty() && rest != "|" && rest != ">") {
                        list.add(rest)
                    }
                } else if (inNegative) {
                    val colon = line.indexOf(':')
                    if (colon < 0) continue
                    val k = line.substring(0, colon).trim()
                    val v = line.substring(colon + 1).trim()
                    if (k == "phase") negPhase = v
                    if (k == "type") negType = v
                } else if (line.startsWith("- ") && curKey != null) {
                    map.getOrPut(curKey) { ArrayList() }.add(line.substring(2).trim())
                }
            }
            return TestMeta(
                includes = map["includes"] ?: emptyList(),
                flags = (map["flags"] ?: emptyList()).toSet(),
                features = map["features"] ?: emptyList(),
                negativePhase = negPhase,
                negativeType = negType,
                locale = map["locale"] ?: emptyList(),
            )
        }
    }
}
