package dev.mooner.neonpython

import dev.mooner.neonjs.runtime.VmConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where array departs from CPython (docs/PYTHON_PLATFORM.md §12.2): an array stays resizable while memoryviews of it
 * live, as a bytearray does (no reference count tells when a view is gone), and a view the resize left past the end
 * raises where it is read; the one export NeonPy can tell, frombytes() of the array's own bytes, is refused as CPython
 * refuses it. What stays in the program is CPython's (programs/array_module.py).
 */
class PyArrayTest {
    private fun probe(src: String): String {
        val out = StringBuilder()
        val prelude = "def t(label, f):\n    try:\n        print(label, '->', f())\n    except BaseException as e:\n        print(label, '!!', type(e).__name__, e)\n"
        PyContext(out, VmConfig(), null, out).exec(prelude + src.trimIndent() + "\n")
        return out.toString()
    }

    private fun lines(vararg l: String) = l.joinToString("\n", postfix = "\n")

    @Test
    fun anArrayResizesWhileViewsOfItLive() {
        val out = probe("""
            from array import array
            a = array('h', [1, 2, 3])
            m = memoryview(a)
            t('append', lambda: a.append(4))
            t('view', lambda: (m.tolist(), len(m)))
            t('extend', lambda: a.extend([5, 6]))
            t('write through', lambda: (m.__setitem__(0, 9), a[0])[1])
            t('del', lambda: a.__delitem__(slice(1, None)))
            t('past the end', lambda: m[0])
            t('tolist', lambda: m.tolist())
            t('a', lambda: a)
            t('within again', lambda: (a.extend([7, 8]), m.tolist())[1])
            b = array('b', [1, 2])
            t('own bytes', lambda: b.frombytes(b))
            t('own view', lambda: b.frombytes(memoryview(b)))
            t('own nothing', lambda: b.frombytes(memoryview(b)[:0]))
            t('b', lambda: b)
        """)
        assertEquals(lines(
            "append -> None",
            "view -> ([1, 2, 3], 3)",
            "extend -> None",
            "write through -> 9",
            "del -> None",
            "past the end !! BufferError memoryview: the array it views was resized past it",
            "tolist !! BufferError memoryview: the array it views was resized past it",
            "a -> array('h', [9])",
            "within again -> [9, 7, 8]",
            "own bytes !! BufferError cannot resize an array that is exporting buffers",
            "own view !! BufferError cannot resize an array that is exporting buffers",
            "own nothing -> None",
            "b -> array('b', [1, 2])",
        ), out)
    }
}
