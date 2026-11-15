package dev.mooner.neonwasm

import java.io.ByteArrayOutputStream

/** Binary modules assembled by hand for tests: sections of bytes, as §5 lays them out. */
internal object WasmBytes {
    const val I32 = 0x7F
    const val I64 = 0x7E
    const val F32 = 0x7D
    const val F64 = 0x7C

    fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    fun cat(parts: List<ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        for (p in parts) o.write(p)
        return o.toByteArray()
    }

    fun cat(vararg parts: ByteArray) = cat(parts.toList())

    fun u32(v: Int): ByteArray {
        val o = ByteArrayOutputStream()
        var x = v.toLong() and 0xFFFFFFFFL
        do {
            var b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x != 0L) b = b or 0x80
            o.write(b)
        } while (x != 0L)
        return o.toByteArray()
    }

    fun s32(v: Int): ByteArray {
        val o = ByteArrayOutputStream()
        var x = v
        while (true) {
            val b = x and 0x7F
            x = x shr 7
            val done = x == 0 && b and 0x40 == 0 || x == -1 && b and 0x40 != 0
            o.write(if (done) b else b or 0x80)
            if (done) return o.toByteArray()
        }
    }

    fun name(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return cat(u32(b.size), b)
    }

    fun vec(items: List<ByteArray>) = cat(listOf(u32(items.size)) + items)

    fun section(id: Int, content: ByteArray) = cat(bytes(id), u32(content.size), content)

    fun module(vararg sections: ByteArray) = cat(listOf(bytes(0, 0x61, 0x73, 0x6D, 1, 0, 0, 0)) + sections)

    fun funcType(params: List<Int>, results: List<Int>) =
        cat(bytes(0x60), vec(params.map { bytes(it) }), vec(results.map { bytes(it) }))

    fun types(vararg t: ByteArray) = section(1, vec(t.toList()))

    fun importFunc(module: String, field: String, type: Int) = cat(name(module), name(field), bytes(0), u32(type))

    fun imports(vararg i: ByteArray) = section(2, vec(i.toList()))

    fun funcs(vararg typeIndices: Int) = section(3, vec(typeIndices.map { u32(it) }))

    /** A memory of [min] pages, at most [max] (-1: none). */
    fun memory(min: Int, max: Int = -1) =
        section(5, vec(listOf(if (max < 0) cat(bytes(0), u32(min)) else cat(bytes(1), u32(min), u32(max)))))

    fun export(field: String, kind: Int, index: Int) = cat(name(field), bytes(kind), u32(index))

    fun exports(vararg e: ByteArray) = section(7, vec(e.toList()))

    /** A function body: [body] is its instructions, `end` included. */
    fun body(locals: List<Pair<Int, Int>>, body: ByteArray): ByteArray {
        val f = cat(vec(locals.map { (n, t) -> cat(u32(n), bytes(t)) }), body)
        return cat(u32(f.size), f)
    }

    fun code(vararg bodies: ByteArray) = section(10, vec(bodies.toList()))

    /** An active data segment of memory 0 at [offset]. */
    fun data(offset: Int, bytes: ByteArray) = section(11, vec(listOf(cat(bytes(0, 0x41), s32(offset), bytes(0x0B), u32(bytes.size), bytes))))
}
