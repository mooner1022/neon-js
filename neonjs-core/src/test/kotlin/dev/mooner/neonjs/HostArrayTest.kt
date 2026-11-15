package dev.mooner.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ArraySource {
    fun ints() = intArrayOf(3, 1, 2)
    fun strings() = arrayOf("b", "a", "c")
    fun nested() = arrayOf(intArrayOf(1, 2), intArrayOf(3))
    fun objects() = arrayOf<Any?>(1, "x", null)
    fun sum(xs: IntArray) = xs.sum()
}

/**
 * Java arrays and Lists returned to JS are JS arrays to scripts, as GraalJS's foreign arrays are (its
 * `js.foreign-object-prototype`, on by default since 22.2): the same host object, with `Array.prototype` behind it and
 * `Array.isArray` true of it. The expectations are GraalJS 25's answers to the same scripts, except where noted.
 */
class HostArrayTest {
    private fun ctx(access: HostAccess = HostAccess.ALL, mode: ExecutionMode = ExecutionMode.ADAPTIVE) =
        NeonEngine.builder().hostAccess(access).executionMode(mode).console(null).build().newContext()

    private fun NeonContext.str(code: String) = eval(code).asString()

    @Test
    fun javaArraysAreJsArrays() {
        ctx().use { c ->
            c["h"] = ArraySource()
            c.eval("var a = h.ints(), s = h.strings(), n = h.nested()")
            assertEquals("true,true,true,true,[object Array]",
                c.str("[Array.isArray(a), Object.getPrototypeOf(a) === Array.prototype, a instanceof Array, a.constructor === Array, Object.prototype.toString.call(a)].join()"))
            // converted to a string, a Java array is its elements joined; here GraalJS gives Java's "[I@..." for
            // a.toString() itself (and the elements everywhere else)
            assertEquals("3,1,2|3,1,2|3,1,21|true|3,1,2|b,a,c|1,2,3",
                c.str("[String(a), `\${a}`, a + 1, a == '3,1,2', a.toString(), String(s), String(n)].join('|')"))
            assertEquals("[3,1,2]|{\"a\":[3,1,2]}|[[1,2],[3]]|[1,\"x\",null]",
                c.str("[JSON.stringify(a), JSON.stringify({ a }), JSON.stringify(n), JSON.stringify(h.objects())].join('|')"))
            // Array.prototype's methods, those making arrays making JS arrays
            assertTrue(c.eval("a.map(x => x * 2)").isArray, "a JS array")
            assertEquals("6,2,4", c.str("String(a.map(x => x * 2))"))
            val r = c.str("""[
                a.filter(x => x > 1), a.join('-'), a.indexOf(1), a.includes(2), a.slice(1), a.at(-1),
                a.reduce((t, x) => t + x, 0), a.find(x => x < 3), a.findLast(x => x > 1), a.lastIndexOf(2),
                [...a.entries()].join(':'), [...a.keys()], a.toSorted(), a.with(0, 9), a.flatMap(x => [x, x]),
                [0].concat(a), a.concat([9]), [...a], Array.from(a), n.flat(), n[0].map(x => x + 1),
                s.map(x => x.toUpperCase()), Object.keys(a)
            ].map(String).join(' | ')""")
            assertEquals("3,2 | 3-1-2 | 1 | true | 1,2 | 2 | 6 | 1 | 2 | 2 | 0,3:1,1:2,2 | 0,1,2 | 1,2,3 | 9,1,2 | " +
                "3,3,1,1,2,2 | 0,3,1,2 | 3,1,2,9 | 3,1,2 | 3,1,2 | 1,2,3 | 2,3 | B,A,C | 0,1,2", r)
            assertEquals("true,true", c.str("[Array.isArray(n[0]), Array.isArray(new Proxy(a, {}))].join()"))
            assertEquals(60, c.eval("h.sum(a.map(x => x * 10))").asInt(), "a JS array made from it goes back as an int[]")
            assertEquals(6, c.eval("var t = 0; for (const x of a) t += x; t").asInt())
        }
    }

    @Test
    fun methodsChangeTheHostArrayInPlace() {
        ctx().use { c ->
            val a = intArrayOf(3, 1, 2)
            val s = arrayOf("b", "c", "a")
            c["a"] = a
            c["s"] = s
            assertTrue(c.eval("a.sort() === a").asBoolean(), "sort returns the array itself")
            assertEquals(listOf(1, 2, 3), a.toList())
            c.eval("a.reverse()")
            assertEquals(listOf(3, 2, 1), a.toList())
            c.eval("a.copyWithin(0, 1)")
            assertEquals(listOf(2, 1, 1), a.toList())
            c.eval("a.fill(7, 1)")
            assertEquals(listOf(2, 7, 7), a.toList())
            c.eval("s.sort((x, y) => x < y ? 1 : -1)")
            assertEquals(listOf("c", "b", "a"), s.toList())
            // a replacement keeps the length: it works
            assertEquals("7", c.str("String(a.splice(1, 1, 5))"))
            assertEquals(listOf(2, 5, 7), a.toList())
        }
    }

    @Test
    fun theLengthOfAJavaArrayCannotChange() {
        // push, pop, shift, unshift and splice fail before any element changes (the generic steps would move
        // elements first: GraalJS leaves [10, 20, 30, 40] shifted as [20, 30, 40, 40])
        ctx().use { c ->
            for (op in listOf("shift()", "splice(1, 2)", "unshift(1, 2)", "pop()", "push(50, 60)", "splice(1, 0, 7, 8)")) {
                val a = intArrayOf(10, 20, 30, 40)
                c["a"] = a
                assertEquals("TypeError: Cannot change the length of a Java array (int[])", c.str("try { a.$op; 'no' } catch (e) { String(e) }"), op)
                assertEquals(listOf(10, 20, 30, 40), a.toList(), op)
            }
            c["a"] = intArrayOf(1, 2)
            assertEquals("2,2,TypeError,TypeError", c.str("""
                var r = [];
                a.length = 1; r.push(a.length);
                a[5] = 1; r.push(a.length);
                try { (function () { 'use strict'; a.length = 1 })() } catch (e) { r.push(e.name) }
                try { (function () { 'use strict'; a[5] = 1 })() } catch (e) { r.push(e.name) }
                r.join()"""))
            // an empty one: nothing to remove
            c["e"] = IntArray(0)
            assertEquals("undefined,undefined,0,0", c.str("[e.pop(), e.shift(), e.push(), e.unshift()].map(String).join()"))
        }
    }

    @Test
    fun aListGrowsAndShrinks() {
        ctx().use { c ->
            val l = arrayListOf(10, 20, 30, 40)
            c["l"] = l
            fun step(code: String, result: String, after: List<Any?>) {
                assertEquals(result, c.str("String($code)"), code)
                assertEquals(after, l, code)
            }
            step("l.push(50, 60)", "6", listOf(10, 20, 30, 40, 50, 60))
            step("l.pop()", "60", listOf(10, 20, 30, 40, 50))
            step("l.shift()", "10", listOf(20, 30, 40, 50))
            step("l.unshift(1, 2)", "6", listOf(1, 2, 20, 30, 40, 50))
            step("l.splice(1, 2)", "2,20", listOf(1, 30, 40, 50))
            step("l.splice(1, 0, 7, 8)", "", listOf(1, 7, 8, 30, 40, 50))
            step("l.splice(-2, 1, 'x')", "40", listOf(1, 7, 8, 30, "x", 50))
            step("l[l.length] = 9", "9", listOf(1, 7, 8, 30, "x", 50, 9))
            step("l.length = 2", "2", listOf(1, 7))
            step("l.length = '4'", "4", listOf(1, 7, null, null))
            step("l[7] = 1", "1", listOf(1, 7, null, null))
            assertTrue(c.eval("Array.isArray(l.splice(0, 0)) && Object.getOwnPropertyDescriptor(l, 'length').writable").asBoolean())
            // as an array's: a length is a whole number below 2^32, or a RangeError (the List unchanged)
            for (bad in listOf("-1", "1.5", "2 ** 32")) {
                assertEquals("RangeError", c.str("try { l.length = $bad; 'no' } catch (e) { e.name }"), bad)
            }
            assertEquals(listOf(1, 7, null, null), l)
            assertEquals("TypeError", c.str("(function () { 'use strict'; try { l[9] = 1; return 'no' } catch (e) { return e.name } })()"))
            // iteration is by index, so elements pushed during a for-of are visited (an iterator of the List would throw
            // ConcurrentModificationException)
            c["g"] = arrayListOf(1, 2)
            assertEquals(5, c.eval("var n = 0; for (const x of g) { if (n++ < 3) g.push(x) } n").asInt())
            // Java's members come first
            c["m"] = arrayListOf(3, 1, 2)
            assertEquals("1,3,2,0", c.str("m.sort((x, y) => x - y); [m.indexOf(2), m[2], m.lastIndexOf(9) + m.size(), m.indexOf(1)].join()"))
        }
    }

    @Test
    fun aListThatCannotChangeItsLength() {
        ctx().use { c ->
            val ops = listOf("shift()", "splice(1, 2)", "unshift(1, 2)", "pop()", "push(50, 60)", "splice(1, 0, 7, 8)", "length = 2", "length = 6")
            for ((name, make) in listOf<Pair<String, () -> List<Int>>>("Arrays.asList" to { java.util.Arrays.asList(10, 20, 30, 40) }, "List.of" to { java.util.List.of(10, 20, 30, 40) })) {
                for (op in ops) {
                    val l = make()
                    c["l"] = l
                    assertEquals("TypeError", c.str("(function () { 'use strict'; try { l.$op; return 'no' } catch (e) { return e.name } })()"), "$name $op")
                    assertEquals(listOf(10, 20, 30, 40), l, "$name $op")
                }
            }
            // Arrays.asList's elements can be set, List.of's cannot
            val fixed = java.util.Arrays.asList(10, 20, 30)
            c["f"] = fixed
            assertEquals("20", c.str("String(f.splice(1, 1, 5))"))
            assertEquals(listOf(10, 5, 30), fixed)
            c["i"] = java.util.List.of(1, 2)
            assertEquals("TypeError: Cannot assign to element 0 of java.util.ImmutableCollections\$List12", c.str("try { i.splice(0, 1, 5); 'no' } catch (e) { String(e) }"))
        }
    }

    @Test
    fun paddingAListIsUnderTheSandboxLimits() {
        val engine = NeonEngine.builder().hostAccess(HostAccess.ALL).console(null)
            .sandbox(SandboxPolicy.builder().maxAllocatedBytes(64L * 1024 * 1024).build()).build()
        engine.newContext().use { c ->
            val l = ArrayList<Any?>()
            c["l"] = l
            val e = org.junit.jupiter.api.assertThrows<NeonException> { c.eval("l.length = 2 ** 31 - 9") }
            assertTrue("Memory allocation limit" in e.message!!, e.message)
            assertEquals("RangeError", c.str("try { l.length = 2 ** 31; 'no' } catch (e) { e.name }"), "beyond what a List holds")
        }
    }

    @Test
    fun thePolicyDecides() {
        // without array access (EXPLICIT's default) a Java array is no JS array; with it, it is one, as under ALL
        ctx(HostAccess.EXPLICIT).use { c ->
            c["a"] = intArrayOf(3, 1, 2)
            assertEquals("false,true,undefined,[object Object]",
                c.str("[Array.isArray(a), Object.getPrototypeOf(a) === Object.prototype, typeof a.map, Object.prototype.toString.call(a)].join()"))
        }
        val arrays = HostAccess.builder(HostAccess.Level.EXPLICIT).allowArrayAccess(true).build()
        ctx(arrays).use { c ->
            c["a"] = intArrayOf(3, 1, 2)
            c["l"] = arrayListOf(1, 2)
            assertEquals("true,6,4,3,false",
                c.str("[Array.isArray(a), a.map(x => x * 2)[0], a.reduce((t, x) => t + x, -2), a.length, Array.isArray(l)].join()"),
                "Lists need allowListAccess")
        }
        // with List access, a List's length changes even where its Java methods are hidden (as in GraalJS)
        val lists = HostAccess.builder(HostAccess.Level.EXPLICIT).allowListAccess(true).build()
        ctx(lists).use { c ->
            val l = arrayListOf(1, 2)
            c["l"] = l
            assertEquals("undefined,3,1", c.str("[typeof l.add, l.push(3), l.shift()].join()"))
            assertEquals(listOf(2, 3), l)
            c.eval("l.length = 0")
            assertEquals(emptyList<Int>(), l)
        }
    }

    @Test
    fun conversionsTakeAHostArray() {
        // a host array is a JS array to the conversions too: Java.to copies it into another array type, Java.from into
        // a JS array, and a parameter of its own type gets the array itself
        val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.build()
        NeonEngine.builder().hostAccess(access).console(null).sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).build())
            .build().newContext().use { c ->
                c["h"] = ArraySource()
                assertEquals(listOf(3L, 1L, 2L), c.eval("Java.to(h.ints(), 'long[]')").asHostObject<LongArray>().toList())
                assertEquals(listOf("3", "1", "2"), c.eval("Java.to(h.ints(), 'java.lang.String[]')").asHostObject<Array<String>>().toList())
                assertTrue(c.eval("Java.from(h.ints())").isArray)
                val a = intArrayOf(5, 6)
                c["a"] = a
                assertSame(a, c.eval("Java.to(a, 'int[]')").asHostObject<IntArray>())
                assertEquals(11, c.eval("h.sum(a)").asInt())
            }
    }

    @Test
    fun theConsoleShowsTheElements() {
        val out = ArrayList<String>()
        NeonEngine.builder().hostAccess(HostAccess.ALL).console { _, m -> out.add(m) }.build().newContext().use { c ->
            c["h"] = ArraySource()
            c["l"] = arrayListOf(1, 2)
            c["big"] = IntArray(1000) { it }
            c["deep"] = arrayOf(arrayOf(arrayOf(arrayOf(1))))
            c.eval("console.log(h.ints(), h.nested(), h.objects(), l); console.log(big); console.log(deep)")
        }
        assertEquals("[Host int[]: [3, 1, 2]] [Host int[][]: [[1, 2], [3]]] [Host Object[]: [1, x, null]] [Host ArrayList: [1, 2]]", out[0])
        assertTrue(out[1].startsWith("[Host int[]: [0, 1, 2, ") && out[1].endsWith(" more]]") && out[1].length < 260, out[1])
        assertEquals("[Host Integer[][][][]: [[[[...]]]]]", out[2])
    }

    @Test
    fun aSiteSeesJsArraysThenHostArrays() {
        // one function's property sites, calls and for-of first warmed by JS arrays, then given host arrays (and back)
        for (mode in ExecutionMode.entries) {
            ctx(mode = mode).use { c ->
                c["h"] = ArraySource()
                c.eval("""
                    function f(x) {
                        var t = x.length + x.map(v => v + 1)[0] + [...x].length + (Array.isArray(x) ? 100 : 0);
                        for (const v of x) t += v;
                        return t + x.indexOf(2) + x.join('').length;
                    }
                    var js = [3, 1, 2], host = h.ints(), sum = 0;
                    for (var i = 0; i < 3000; i++) sum += f(js);
                    for (var i = 0; i < 3000; i++) sum += f(host);
                    for (var i = 0; i < 3000; i++) sum += f(i % 2 ? js : host);
                """)
                // f gives 3 + 4 + 3 + 100 + 6 + 2 + 3 = 121 for both
                assertEquals(121 * 9000, c.eval("sum").asInt(), mode.name)
            }
        }
    }
}
