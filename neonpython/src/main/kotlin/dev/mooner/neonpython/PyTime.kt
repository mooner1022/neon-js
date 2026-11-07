package dev.mooner.neonpython

import dev.mooner.neonjs.runtime.InterruptedExecutionException

/**
 * `time` (docs/PYTHON_PLATFORM.md §12, S2), as CPython 3.14 has it on Linux: Modules/timemodule.c and the parts of
 * Python/pytime.c it uses in Kotlin, their messages; what CPython takes from the C library, glibc's: strftime (glibc
 * 2.39's in the C locale: its conversions, flags, widths, a conversion it does not know copied as it is, and
 * CPython's buffer of 1024 characters doubled up to 256 times the format's length), mktime's normalising, gmtime's
 * range (a year that fits a C int) and the errors of clock_gettime, clock_settime and pthread_getcpuclockid.
 *
 * The sandbox's zone is UTC: localtime is gmtime (its tm_zone 'UTC', gmtime's 'GMT', as glibc names them), mktime
 * timegm, tzname ('UTC', 'UTC'), timezone, altzone and daylight 0; tzset() keeps it. The clocks are the JVM's:
 * time() Instant.now(), monotonic() and perf_counter() System.nanoTime(), process_time() and thread_time() the
 * thread's CPU time (ThreadMXBean, else the monotonic clock). sleep() waits in slices of 10 ms at most, the agent's
 * limits checked between them. strptime() is CPython's _strptime.py's.
 */
object PyTime {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    const val DOC = "This module provides various functions to manipulate time values.\n\n" +
        "There are two standard representations of time.  One is the number\n" +
        "of seconds since the Epoch, in UTC (a.k.a. GMT).  It may be an integer\n" +
        "or a floating-point number (to represent fractions of seconds).\n" +
        "The epoch is the point where the time starts, the return value of time.gmtime(0).\n" +
        "It is January 1, 1970, 00:00:00 (UTC) on all platforms.\n\n" +
        "The other representation is a tuple of 9 integers giving local time.\n" +
        "The tuple items are:\n" +
        "  year (including century, e.g. 1998)\n" +
        "  month (1-12)\n" +
        "  day (1-31)\n" +
        "  hours (0-23)\n" +
        "  minutes (0-59)\n" +
        "  seconds (0-59)\n" +
        "  weekday (0-6, Monday is 0)\n" +
        "  Julian day (day in the year, 1-366)\n" +
        "  DST (Daylight Savings Time) flag (-1, 0 or 1)\n" +
        "If the DST flag is 0, the time is given in the regular time zone;\n" +
        "if it is 1, the time is given in the DST time zone;\n" +
        "if it is -1, mktime() should guess based on the date and time.\n"

    private const val STRUCT_DOC = "The time value as returned by gmtime(), localtime(), and strptime(), and\n" +
        " accepted by asctime(), mktime() and strftime().  May be considered as a\n" +
        " sequence of 9 integers.\n\n" +
        " Note that several fields' values are not the same as those defined by\n" +
        " the C language standard for struct tm.  For example, the value of the\n" +
        " field tm_year is the actual year, not year - 1900.  See individual\n" +
        " fields' descriptions for details."

    private val FIELDS = listOf(
        "tm_year" to "year, for example, 1993", "tm_mon" to "month of year, range [1, 12]", "tm_mday" to "day of month, range [1, 31]",
        "tm_hour" to "hours, range [0, 23]", "tm_min" to "minutes, range [0, 59]", "tm_sec" to "seconds, range [0, 61])",
        "tm_wday" to "day of week, range [0, 6], Monday is 0", "tm_yday" to "day of year, range [1, 366]",
        "tm_isdst" to "1 if summer time is in effect, 0 if not, and -1 if unknown",
    )
    private val HIDDEN = listOf("tm_zone" to "abbreviation of timezone name", "tm_gmtoff" to "offset from UTC in seconds")

    private const val SEC_TO_NS = 1_000_000_000L
    private const val EINVAL = 22L
    private const val EOVERFLOW = 75L

    /** Linux's clock ids (the ones CPython's time module names, as Linux's headers number them). */
    private const val CLOCK_REALTIME = 0
    private const val CLOCK_MONOTONIC = 1
    private const val CLOCK_PROCESS_CPUTIME_ID = 2
    private const val CLOCK_THREAD_CPUTIME_ID = 3
    private const val CLOCK_MONOTONIC_RAW = 4
    private const val CLOCK_BOOTTIME = 7

    /** NeonPy's process and its one thread: os.getpid() and _thread.get_native_id() (pthread_getcpuclockid's thread). */
    private const val SELF_ID = 1L

    private fun valueError(msg: String) = PyException("ValueError", msg)
    private fun overflowError(msg: String) = PyException("OverflowError", msg)

    /** OSError (its subclass of [errno]: PermissionError, ProcessLookupError) as PyErr_SetFromErrno raises it. */
    private fun osError(errno: Long): PyException {
        val (type, msg) = when (errno) {
            1L -> "PermissionError" to "Operation not permitted"
            3L -> "ProcessLookupError" to "No such process"
            EINVAL -> "OSError" to "Invalid argument"
            else -> "OSError" to "Value too large for defined data type"
        }
        return PyException(type, "[Errno $errno] $msg").also { it.args = arrayOf(errno, PyStr(msg)) }
    }

    // ------------------------------------------------------------------ struct_time

    /** time.struct_time: a struct sequence of 9 fields, tm_zone and tm_gmtoff besides (by name only). */
    @JvmField val STRUCT_TIME: PyType = PySys.structType("struct_time", FIELDS.map { it.first }, HIDDEN.map { it.first }).also { defineStruct(it) }

    private fun hiddenOf(v: PyTuple): Array<Any?> = (v.sub?.state as? Array<Any?>) ?: arrayOf(PyNone, PyNone)

    private fun struct(items: Array<Any?>, zone: Any?, gmtoff: Any?): PyTuple =
        PySys.struct(STRUCT_TIME, *items).also { it.sub!!.state = arrayOf(zone, gmtoff) }

    private fun defineStruct(t: PyType) {
        t.module = "time"
        t.tpName = "time.struct_time"
        t.def("__doc__", PyStr(STRUCT_DOC))
        for ((f, doc) in FIELDS + HIDDEN) (t.dict[PyStr(f)] as PyProperty).doc = PyStr(doc)
        t.newImpl = { ctx, _, a, kw -> newStruct(ctx, a, kw?.takeIf { it.size > 0 }) }
        fun new(ctx: PyContext, a: Array<Any?>, kw: PyDict?): Any? {
            val cls = a.getOrNull(0) as? PyType
                ?: throw PyRt.typeError("time.struct_time.__new__(X): X is not a type object (${PyTypes.name(a.getOrNull(0))})")
            if (!cls.isSubtype(t)) throw PyRt.typeError("time.struct_time.__new__(${cls.name}): ${cls.name} is not a subtype of time.struct_time")
            return newStruct(ctx, a.copyOfRange(1, a.size), kw?.takeIf { it.size > 0 })
        }
        t.def("__new__", PyBuiltin("__new__", { ctx, a -> new(ctx, a, null) }, { ctx, a, kw -> new(ctx, a, kw) }))
        t.def("__repr__", PyBuiltin("__repr__") { _, a ->
            val items = (a[0] as PyTuple).items
            PyStr(FIELDS.indices.joinToString(", ", "time.struct_time(", ")") { "${FIELDS[it].first}=${PyRt.repr(items[it])}" })
        })
        fun replace(v: PyTuple, kw: PyDict?): PyTuple {
            val items = v.items.copyOf()
            val hidden = hiddenOf(v).copyOf()
            if (kw == null) return struct(items, hidden[0], hidden[1])
            val left = LinkedHashMap<PyDict.Key, Any?>(kw.map)
            for ((i, f) in FIELDS.withIndex()) left.remove(PyDict.Key(PyStr(f.first)))?.let { items[i] = it }
            for ((i, f) in HIDDEN.withIndex()) if (left.containsKey(PyDict.Key(PyStr(f.first)))) hidden[i] = left.remove(PyDict.Key(PyStr(f.first)))
            if (left.isNotEmpty()) throw PyRt.typeError("Got unexpected field name(s): ${PyRt.repr(PyList(ArrayList(left.keys.map { it.v })))}")
            return struct(items, hidden[0], hidden[1])
        }
        t.def("__replace__", PyBuiltin("__replace__", { _, a ->
            if (a.size > 1) throw PyRt.typeError("__replace__() takes no positional arguments")
            replace(a[0] as PyTuple, null)
        }, { _, a, kw ->
            if (a.size > 1) throw PyRt.typeError("__replace__() takes no positional arguments")
            replace(a[0] as PyTuple, kw.takeIf { it.size > 0 })
        }))
    }

    /** struct_time(sequence, dict={}): structseq_new's checks and messages. */
    private fun newStruct(ctx: PyContext, a: Array<Any?>, kw: PyDict?): PyTuple {
        val p = PyMath.clinic("structseq", a, kw, listOf("sequence", "dict"), 0, 1, 2)
        val seq = p[0]
        val dict = p[1]
        val items: List<Any?> = when (seq) {
            is PyTuple -> seq.items.asList()
            is PyList -> ArrayList(seq.items)
            else -> {
                val it = try {
                    PyRt.iter(seq)
                } catch (e: PyException) {
                    if (e.matches(PyExceptions.type("TypeError"))) throw PyRt.typeError("constructor requires a sequence")
                    throw e
                }
                val out = ArrayList<Any?>()
                while (true) {
                    val x = it.next()
                    if (x === PyIterator.STOP) break
                    out.add(x)
                }
                out
            }
        }
        if (dict != null && dict !is PyDict) throw PyRt.typeError("time.struct_time() takes a dict as second arg, if any")
        val len = items.size
        if (len < FIELDS.size) throw PyRt.typeError("time.struct_time() takes an at least ${FIELDS.size}-sequence ($len-sequence given)")
        val max = FIELDS.size + HIDDEN.size
        if (len > max) throw PyRt.typeError("time.struct_time() takes an at most $max-sequence ($len-sequence given)")
        val hidden = arrayOf<Any?>(PyNone, PyNone)
        for (i in FIELDS.size until len) hidden[i - FIELDS.size] = items[i]
        if (dict is PyDict && dict.size > 0) {
            var found = 0
            for (i in len until max) {
                val v = dict[PyStr(HIDDEN[i - FIELDS.size].first)]
                if (v != null) {
                    found++
                    hidden[i - FIELDS.size] = v
                }
            }
            if (dict.size > found) throw PyRt.typeError("time.struct_time() got duplicate or unexpected field name(s)")
        }
        return struct(Array(FIELDS.size) { items[it] }, hidden[0], hidden[1])
    }

    // ------------------------------------------------------------------ clocks

    /** CLOCK_MONOTONIC: System.nanoTime() (on Linux and Android that clock itself, on Windows a count from boot too). */
    private fun monotonicNs(): Long = System.nanoTime()

    private fun realtimeNs(): Long {
        val now = java.time.Instant.now()
        return now.epochSecond * SEC_TO_NS + now.nano
    }

    /** The thread's CPU time where the JVM measures it (not on Android: java.lang.management is not there). */
    private object Cpu {
        // (typed Any: the class is not there to resolve on Android)
        @JvmField val mx: Any? = try {
            java.lang.management.ManagementFactory.getThreadMXBean()?.takeIf { it.isCurrentThreadCpuTimeSupported }?.also {
                if (!it.isThreadCpuTimeEnabled) it.isThreadCpuTimeEnabled = true
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun cpuNs(): Long {
        val t = try {
            (Cpu.mx as java.lang.management.ThreadMXBean?)?.currentThreadCpuTime ?: -1L
        } catch (_: Throwable) {
            -1L
        }
        return if (t >= 0) t else monotonicNs()
    }

    /** PyTime_AsSecondsDouble: whole seconds exactly, else the nanoseconds divided. */
    private fun seconds(ns: Long): Double = if (ns % SEC_TO_NS == 0L) (ns / SEC_TO_NS).toDouble() else ns.toDouble() / 1e9

    /** The time of clock [id] as (seconds, nanoseconds), or null where Linux's clock_gettime fails with EINVAL. */
    private fun clockTime(id: Int): LongArray? {
        val ns = when (id) {
            CLOCK_REALTIME, 5, 8, 11 -> realtimeNs()
            CLOCK_MONOTONIC, CLOCK_MONOTONIC_RAW, 6, CLOCK_BOOTTIME, 9 -> monotonicNs()
            CLOCK_PROCESS_CPUTIME_ID, CLOCK_THREAD_CPUTIME_ID -> cpuNs()
            else -> if (cpuClock(id)) cpuNs() else return null
        }
        return longArrayOf(Math.floorDiv(ns, SEC_TO_NS), Math.floorMod(ns, SEC_TO_NS))
    }

    /**
     * Whether [id] is a CPU clock of a process or thread Linux's encoding names (~pid << 3 | per-thread 4 | which): one
     * of NeonPy's (pid 0, the caller, or [SELF_ID]), its which PROF, VIRT or SCHED.
     */
    private fun cpuClock(id: Int): Boolean {
        if (id >= 0 || id and 3 == 3) return false
        val pid = (id shr 3).inv().toLong()
        return pid == 0L || pid == SELF_ID
    }

    private fun clockRes(id: Int): Double? = when {
        id == 5 || id == 6 -> 4_000_000 * 1e-9
        clockTime(id) != null -> 1 * 1e-9
        else -> null
    }

    /** clock_settime: Linux refuses (EPERM) where the clock may be set by a privileged process, else EINVAL. */
    private fun clockSet(id: Int, ns: Long): Nothing {
        val sec = Math.floorDiv(ns, SEC_TO_NS)
        when {
            // (timespec64_valid_settod: not before the epoch, nor past KTIME_SEC_MAX less 30 years of uptime)
            id == CLOCK_REALTIME -> throw osError(if (sec < 0 || sec > 9223372036L - 946080000L) EINVAL else 1L)
            cpuClock(id) -> throw osError(1L)
            else -> throw osError(EINVAL)
        }
    }

    // ------------------------------------------------------------------ conversions (Python/pytime.c)

    private const val ROUND_FLOOR = 0
    private const val ROUND_UP = 3

    /** PyFloat_Check: a float, a float subclass's instance's value. */
    private fun floatOf(v: Any?): Double? = when {
        v is Double -> v
        v is PyNumSub && v.num is Double -> v.num as Double
        else -> null
    }

    private fun round(x: Double, mode: Int): Double = when (mode) {
        ROUND_FLOOR -> Math.floor(x)
        else -> if (x >= 0.0) Math.ceil(x) else Math.floor(x)
    }

    /** PyLong_AsLongLong's int (__index__ taken), null where [v] has none; past 64 bits: [overflow]. */
    private fun longLong(v: Any?, overflow: () -> PyException): Long? {
        val i = try {
            PyRt.indexValue(v)
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("TypeError"))) null else throw e
        } ?: return null
        return i as? Long ?: throw overflow()
    }

    /** _PyTime_FromSecondsObject with _PyTime_ROUND_TIMEOUT: a timeout's seconds as nanoseconds (_thread's locks'). */
    @JvmStatic internal fun timeoutNs(v: Any?): Long = fromSecondsObject(v, ROUND_UP)

    /** _PyTime_FromSecondsObject: seconds as nanoseconds, a float's rounded by [mode]. */
    private fun fromSecondsObject(v: Any?, mode: Int): Long {
        floatOf(v)?.let { d ->
            if (d.isNaN()) throw valueError("Invalid value NaN (not a number)")
            val x = round(d * 1e9, mode)
            if (!(-9.223372036854775808E18 <= x && x < 9.223372036854775808E18)) throw overflowError("timestamp out of range for platform time_t")
            return x.toLong()
        }
        val tooLarge = { overflowError("timestamp too large to convert to C PyTime_t") }
        val sec = longLong(v, tooLarge) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer or float")
        if (sec > Long.MAX_VALUE / SEC_TO_NS || sec < Long.MIN_VALUE / SEC_TO_NS) throw tooLarge()
        return sec * SEC_TO_NS
    }

    /** _PyTime_FromLong: an int of nanoseconds (clock_settime_ns). */
    private fun fromLong(v: Any?): Long {
        if (v !is Long && v !is java.math.BigInteger && v !is PyBool && !(v is PyNumSub && v.num !is Double)) {
            throw PyRt.typeError("expect int, got ${PyTypes.name(v)}")
        }
        return longLong(v) { overflowError("timestamp too large to convert to C PyTime_t") }!!
    }

    /** _PyTime_ObjectToTime_t, flooring a float. */
    private fun objectToTimeT(v: Any?): Long {
        floatOf(v)?.let { d ->
            if (d.isNaN()) throw valueError("Invalid value NaN (not a number)")
            val x = Math.floor(d)
            if (!(-9.223372036854775808E18 <= x && x < 9.223372036854775808E18)) throw overflowError("timestamp out of range for platform time_t")
            return x.toLong()
        }
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        return i as? Long ?: throw overflowError("timestamp out of range for platform time_t")
    }

    /** PyArg_ParseTuple's "i": a C int (__index__ taken). */
    private fun cInt(v: Any?): Int {
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        if (i !is Long) throw overflowError("Python int too large to convert to C long")
        if (i > Int.MAX_VALUE) throw overflowError("signed integer is greater than maximum")
        if (i < Int.MIN_VALUE) throw overflowError("signed integer is less than minimum")
        return i.toInt()
    }

    /** PyLong_AsLong. */
    private fun cLong(v: Any?): Long {
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        return i as? Long ?: throw overflowError("Python int too large to convert to C long")
    }

    /** PyUnicode_AsUTF8 (its UnicodeEncodeError for a lone surrogate), as the C string it gives: up to a NUL. */
    private fun utf8(s: PyStr): String {
        // (the codec's own error, its fields set, for a lone surrogate)
        PyCodecs.encodeUtf8(PyContext.current(), s, "strict")
        return s.s
    }

    // ------------------------------------------------------------------ broken-down time

    /** C's struct tm (tm_year from 1900, tm_mon from 0, tm_wday from Sunday, tm_yday from 0), and glibc's tm_zone, tm_gmtoff. */
    private class Tm(
        @JvmField var year: Int, @JvmField var mon: Int, @JvmField var mday: Int, @JvmField var hour: Int, @JvmField var min: Int,
        @JvmField var sec: Int, @JvmField var wday: Int, @JvmField var yday: Int, @JvmField var isdst: Int,
        @JvmField var zone: String?, @JvmField var gmtoff: Long,
    )

    private val MON_YDAY = arrayOf(
        intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334, 365),
        intArrayOf(0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335, 366),
    )

    private fun isLeap(y: Long): Boolean = y % 4 == 0L && (y % 100 != 0L || y % 400 == 0L)

    /** Days from 1970-01-01 to [y]-[m]-[d] (the proleptic Gregorian calendar). */
    private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val doy = (153 * (m + if (m > 2) -3 else 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** What gmtime_r converts: years whose tm_year is a C int (EOVERFLOW past them). */
    private val TIME_MIN = daysFromCivil(Int.MIN_VALUE + 1900L, 1, 1) * 86400
    private val TIME_MAX = daysFromCivil(Int.MAX_VALUE + 1901L, 1, 1) * 86400 - 1

    /** gmtime_r (localtime_r too: the zone is UTC); [zone] its tm_zone. */
    private fun gmtime(t: Long, zone: String): Tm {
        if (t < TIME_MIN || t > TIME_MAX) throw osError(EOVERFLOW)
        val days = Math.floorDiv(t, 86400L)
        val secs = Math.floorMod(t, 86400L).toInt()
        val z = days + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        val yday = (days - daysFromCivil(y, 1, 1)).toInt()
        return Tm((y - 1900).toInt(), m - 1, d, secs / 3600, secs / 60 % 60, secs % 60, Math.floorMod(days + 4, 7L).toInt(), yday, 0, zone, 0L)
    }

    private fun localtime(t: Long): Tm = gmtime(t, "UTC")

    /** mktime as glibc's makes it in UTC: the fields normalised, tm_isdst > 0 an hour back; null where it overflows. */
    private fun mktime(tm: Tm): Long? {
        val rem0 = tm.mon % 12
        val neg = rem0 < 0
        val year = tm.year.toLong() + tm.mon / 12 - (if (neg) 1 else 0) + 1900
        val rem = rem0 + if (neg) 12 else 0
        val secRequested = tm.sec
        val sec = secRequested.coerceIn(0, 59)
        val yday = MON_YDAY[if (isLeap(year)) 1 else 0][rem] + tm.mday.toLong() - 1
        var t = (daysFromCivil(year, 1, 1) + yday) * 86400 + tm.hour.toLong() * 3600 + tm.min.toLong() * 60 + sec
        if (t < TIME_MIN || t > TIME_MAX) return null
        if (tm.isdst > 0) {
            t -= 3600
            if (t < TIME_MIN) return null
        }
        if (secRequested != sec) {
            t += secRequested.toLong() - sec
            if (t < TIME_MIN || t > TIME_MAX) return null
        }
        return t
    }

    /** tmtotuple: the struct_time of [tm] (its year tm_year + 1900 as C adds them: in an int). */
    private fun tmToTuple(tm: Tm): PyTuple = struct(arrayOf(
        (tm.year + 1900).toLong(), (tm.mon + 1).toLong(), tm.mday.toLong(), tm.hour.toLong(), tm.min.toLong(), tm.sec.toLong(),
        ((tm.wday + 6) % 7).toLong(), (tm.yday + 1).toLong(), tm.isdst.toLong(),
    ), PyStr(tm.zone!!), tm.gmtoff)

    /** gettmarg: a tuple of 9 C ints (struct_time's tm_zone and tm_gmtoff too) as a struct tm. */
    private fun gettmarg(v: Any?, fn: String): Tm {
        if (v !is PyTuple) throw PyRt.typeError("Tuple or struct_time argument required")
        val items = v.items
        if (items.size != 9) throw PyRt.typeError("$fn(): illegal time tuple argument")
        val n = IntArray(9) { cInt(items[it]) }
        if (n[0] < Int.MIN_VALUE + 1900) throw overflowError("year out of range")
        val tm = Tm(n[0] - 1900, n[1] - 1, n[2], n[3], n[4], n[5], (n[6] + 1) % 7, n[7] - 1, n[8], null, 0L)
        if (v.sub?.cls === STRUCT_TIME) {
            val hidden = hiddenOf(v)
            val zone = hidden[0]
            if (zone !== PyNone) {
                if (zone !is PyStr) throw PyRt.typeError("bad argument type for built-in operation")
                tm.zone = utf8(zone).substringBefore('\u0000')
            }
            if (hidden[1] !== PyNone) tm.gmtoff = cLong(hidden[1])
        }
        return tm
    }

    /** checktm: the fields strftime and asctime index by in range (a month, day or day of the year of 0 taken as the first). */
    private fun checktm(tm: Tm) {
        if (tm.mon == -1) tm.mon = 0
        else if (tm.mon < 0 || tm.mon > 11) throw valueError("month out of range")
        if (tm.mday == 0) tm.mday = 1
        else if (tm.mday < 0 || tm.mday > 31) throw valueError("day of month out of range")
        if (tm.hour < 0 || tm.hour > 23) throw valueError("hour out of range")
        if (tm.min < 0 || tm.min > 59) throw valueError("minute out of range")
        if (tm.sec < 0 || tm.sec > 61) throw valueError("seconds out of range")
        if (tm.wday < 0) throw valueError("day of week out of range")
        if (tm.yday == -1) tm.yday = 0
        else if (tm.yday < 0 || tm.yday > 365) throw valueError("day of year out of range")
    }

    private val WDAY = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MON = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val WEEKDAY = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val MONTH = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** _asctime: "%s %s%3d %.2d:%.2d:%.2d %d". */
    private fun asctime(tm: Tm): PyStr =
        PyStr("${WDAY[tm.wday]} ${MON[tm.mon]}${tm.mday.toString().padStart(3)} ${two(tm.hour)}:${two(tm.min)}:${two(tm.sec)} ${tm.year + 1900}")

    private fun two(n: Int): String = if (n in 0..9) "0$n" else n.toString()

    // ------------------------------------------------------------------ strftime (glibc's, C locale)

    /** glibc's strftime finding its buffer too small (it returns 0). */
    private object Full : RuntimeException(null, null, false, false) {
        private fun readResolve(): Any = Full
    }

    /** One run of glibc 2.39's __strftime_internal over [tm] into a buffer of [maxsize] characters (its NUL among them). */
    private class Strftime(private val tm: Tm, private val maxsize: Long) {
        private val out = StringBuilder()
        private var i = 0L
        private var pad = '\u0000'
        private var width = -1
        private var toLow = false
        private var toUp = false

        /** add(): [s] (of [n] characters) after the width's padding, zeros where the flag is 0. */
        private fun add(s: String, n: Int = s.length) {
            val delta = width.toLong() - n
            val incr = n + maxOf(delta, 0L)
            if (incr >= maxsize - i) throw Full
            if (delta > 0) repeat(delta.toInt()) { out.append(if (pad == '0') '0' else ' ') }
            out.append(s)
            i += incr
        }

        /** cpy(): add(), its case changed by ^ (or #). */
        private fun cpy(s: String) = add(if (toLow) s.lowercase() else if (toUp) s.uppercase() else s)

        private fun number(digits0: Int, value: Int, spacePad: Boolean = false) {
            val digits = if (digits0 > width) digits0 else width
            if (spacePad && pad != '0' && pad != '-') pad = '_'
            val neg = value < 0
            signAndPadding((if (neg) -value.toLong() else value.toLong()).toString(), neg, digits)
        }

        /** do_number_sign_and_padding. */
        private fun signAndPadding(digitString: String, neg: Boolean, digits: Int) {
            var buf = if (neg) "-$digitString" else digitString
            if (pad != '-') {
                val padding = digits - buf.length
                if (padding > 0) {
                    if (pad == '_') {
                        if (padding >= maxsize - i) throw Full
                        repeat(padding) { out.append(' ') }
                        i += padding
                        width = if (width > padding) width - padding else 0
                    } else {
                        if (digits >= maxsize - i) throw Full
                        if (neg) {
                            buf = buf.substring(1)
                            out.append('-')
                            i++
                        }
                        repeat(padding) { out.append('0') }
                        i += padding
                        width = 0
                    }
                }
            }
            cpy(buf)
        }

        private fun subformat(f: String) {
            val s = Strftime(tm, Long.MAX_VALUE).format(f)
            add(if (toUp) s.uppercase() else s)
        }

        private fun isoWeekDays(yday: Int, wday: Int): Int = yday - (yday - wday + 4 + 378) % 7 + 4 - 1

        fun format(fmt: String): String {
            val n = fmt.length
            var f = 0
            while (f < n) {
                pad = '\u0000'
                width = -1
                toLow = false
                toUp = false
                if (fmt[f] != '%') {
                    add(fmt[f].toString())
                    f++
                    continue
                }
                val start = f
                var changeCase = false
                // (flags: the last of _ - 0 counts)
                while (true) {
                    f++
                    val c = if (f < n) fmt[f] else '\u0000'
                    when (c) {
                        '_', '-', '0' -> pad = c
                        '^' -> toUp = true
                        '#' -> changeCase = true
                        else -> break
                    }
                }
                if (f < n && fmt[f] in '0'..'9') {
                    width = 0
                    while (f < n && fmt[f] in '0'..'9') {
                        val d = fmt[f] - '0'
                        width = if (width > Int.MAX_VALUE / 10 || width == Int.MAX_VALUE / 10 && d > Int.MAX_VALUE % 10) Int.MAX_VALUE else width * 10 + d
                        f++
                    }
                }
                var modifier = '\u0000'
                if (f < n && (fmt[f] == 'E' || fmt[f] == 'O')) modifier = fmt[f++]
                val fc = if (f < n) fmt[f] else '\u0000'
                conversion(fmt, start, f, fc, modifier, changeCase)
                if (fc != '\u0000') f++
            }
            return out.toString()
        }

        private fun conversion(fmt: String, start: Int, f: Int, fc: Char, modifier: Char, changeCase: Boolean) {
            // (an unknown conversion, or a modifier it does not take: the directive as written, % to it)
            fun bad() = cpy(fmt.substring(start, if (fc != '\u0000') f + 1 else f))
            fun upper() {
                if (changeCase) {
                    toUp = true
                    toLow = false
                }
            }
            val year = tm.year + 1900
            val hour12 = (tm.hour % 12).let { if (it == 0) 12 else it }
            when (fc) {
                '%' -> add("%")
                'a' -> if (modifier != '\u0000') bad() else { upper(); cpy(WDAY[tm.wday]) }
                'A' -> if (modifier != '\u0000') bad() else { upper(); cpy(WEEKDAY[tm.wday]) }
                'b', 'h' -> { upper(); if (modifier == 'E') bad() else cpy(MON[tm.mon]) }
                'B' -> if (modifier == 'E') bad() else { upper(); cpy(MONTH[tm.mon]) }
                'c' -> if (modifier == 'O') bad() else subformat("%a %b %e %H:%M:%S %Y")
                'C' -> number(1, year / 100 - if (year % 100 < 0) 1 else 0)
                'x' -> if (modifier == 'O') bad() else subformat("%m/%d/%y")
                'D' -> if (modifier != '\u0000') bad() else subformat("%m/%d/%y")
                'd' -> if (modifier == 'E') bad() else number(2, tm.mday)
                'e' -> if (modifier == 'E') bad() else number(2, tm.mday, true)
                'F' -> if (modifier != '\u0000') bad() else subformat("%Y-%m-%d")
                'H' -> if (modifier == 'E') bad() else number(2, tm.hour)
                'I' -> if (modifier == 'E') bad() else number(2, hour12)
                'k' -> if (modifier == 'E') bad() else number(2, tm.hour, true)
                'l' -> if (modifier == 'E') bad() else number(2, hour12, true)
                'j' -> if (modifier == 'E') bad() else number(3, 1 + tm.yday)
                'M' -> if (modifier == 'E') bad() else number(2, tm.min)
                'm' -> if (modifier == 'E') bad() else number(2, tm.mon + 1)
                'n' -> add("\n")
                't' -> add("\t")
                'P', 'p' -> {
                    if (fc == 'P') toLow = true
                    if (changeCase) {
                        toUp = false
                        toLow = true
                    }
                    cpy(if (tm.hour > 11) "PM" else "AM")
                }
                'R' -> subformat("%H:%M")
                'r' -> subformat("%I:%M:%S %p")
                'S' -> if (modifier == 'E') bad() else number(2, tm.sec)
                's' -> {
                    val t = mktime(tm) ?: -1L
                    signAndPadding(Math.abs(t).toString(), t < 0, 1)
                }
                'X' -> if (modifier == 'O') bad() else subformat("%H:%M:%S")
                'T' -> subformat("%H:%M:%S")
                'u' -> number(1, (tm.wday - 1 + 7) % 7 + 1)
                'U' -> if (modifier == 'E') bad() else number(2, (tm.yday - tm.wday + 7) / 7)
                'V', 'g', 'G' -> if (modifier == 'E') bad() else {
                    var adjust = 0
                    var days = isoWeekDays(tm.yday, tm.wday)
                    if (days < 0) {
                        // (the ISO week the previous year's)
                        adjust = -1
                        days = isoWeekDays(tm.yday + 365 + (if (isLeap(tm.year + 1899L)) 1 else 0), tm.wday)
                    } else {
                        val d = isoWeekDays(tm.yday - 365 - (if (isLeap(tm.year + 1900L)) 1 else 0), tm.wday)
                        if (d >= 0) {
                            adjust = 1
                            days = d
                        }
                    }
                    when (fc) {
                        'g' -> number(2, ((year + adjust) % 100).let { if (it < 0) it + 100 else it })
                        'G' -> number(1, year + adjust)
                        else -> number(2, days / 7 + 1)
                    }
                }
                'W' -> if (modifier == 'E') bad() else number(2, (tm.yday - (tm.wday - 1 + 7) % 7 + 7) / 7)
                'w' -> if (modifier == 'E') bad() else number(1, tm.wday)
                'Y' -> if (modifier == 'O') bad() else number(1, year)
                'y' -> number(2, (tm.year % 100).let { if (it < 0) it + 100 else it })
                'Z' -> {
                    // (wcsftime's zone: not case-changed, no width where the flag is -)
                    toUp = false
                    toLow = false
                    if (pad == '-') width = -1
                    var zone = tm.zone
                    if (zone.isNullOrEmpty() && tm.isdst >= 0) zone = if (tm.isdst <= 1) "UTC" else "?"
                    val z = zone ?: ""
                    add(z, z.codePointCount(0, z.length))
                }
                'z' -> if (tm.isdst >= 0) {
                    val diff = tm.gmtoff.toInt()
                    var d: Long
                    if (diff < 0) {
                        add("-")
                        d = -diff.toLong()
                    } else {
                        add("+")
                        d = diff.toLong()
                    }
                    d /= 60
                    number(4, (d / 60 * 100 + d % 60).toInt())
                }
                else -> bad()
            }
        }
    }

    /**
     * time_strftime: the format's runs of ASCII (not NUL) given to the C library's strftime with a buffer of 1024
     * characters, doubled while its result is empty and it is less than 256 times the run's length (an empty result
     * after that is the run's); the other characters as they are, up to the next %.
     */
    private fun strftime(fmt: String, tm: Tm): String {
        val sb = StringBuilder()
        var bufsize = 1024L
        var i = 0
        while (i < fmt.length) {
            val start = i
            while (i < fmt.length && fmt[i].code in 1..127) i++
            val fmtlen = i - start
            if (fmtlen > 0) {
                val run = fmt.substring(start, i)
                while (true) {
                    val r = try {
                        Strftime(tm, bufsize).format(run)
                    } catch (_: Full) {
                        ""
                    }
                    if (r.isEmpty() && bufsize < 256L * fmtlen) {
                        bufsize += bufsize
                        continue
                    }
                    sb.append(r)
                    break
                }
            }
            val rest = i
            while (i < fmt.length && fmt[i] != '%') i++
            sb.append(fmt, rest, i)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ the module

    /** The current time's whole seconds (time(NULL)). */
    private fun now(): Long = Math.floorDiv(realtimeNs(), SEC_TO_NS)

    /** parse_time_t_args: an optional float or int of seconds, None or none the current time. */
    private fun whenOf(name: String, a: Array<Any?>): Long {
        if (a.size > 1) throw PyRt.typeError("$name() takes at most 1 argument (${a.size} given)")
        val v = a.getOrNull(0)
        return if (v == null || v === PyNone) now() else objectToTimeT(v)
    }

    private fun sleep(ctx: PyContext, audit: Any?, v: Any?) {
        if (audit != null) PyCalls.callWith(ctx, audit, arrayOf(PyStr("time.sleep"), v), NO_ARGS, NO_ARGS)
        val timeout = fromSecondsObject(v, ROUND_UP)
        if (timeout < 0) throw valueError("sleep length must be non-negative")
        val mono = monotonicNs()
        // (clock_nanosleep's deadline past the clock's range: EINVAL)
        if (timeout > Long.MAX_VALUE - mono) throw osError(EINVAL)
        waitUntil(ctx, mono + timeout)
    }

    /** Waits [timeout] nanoseconds ([waitUntil]); a deadline past the clock's range is its last. */
    @JvmStatic
    internal fun waitFor(ctx: PyContext, timeout: Long) {
        val mono = monotonicNs()
        val deadline = mono + timeout
        waitUntil(ctx, if (timeout > 0 && deadline < mono) Long.MAX_VALUE else deadline)
    }

    /**
     * Waits until the monotonic clock's [deadline] (nanoseconds), in slices of 10 ms at most, the agent's limits and
     * interrupts checked between them (time.sleep's wait, and a lock's that only times out: _thread's).
     */
    @JvmStatic
    internal fun waitUntil(ctx: PyContext, deadline: Long) {
        val agent = ctx.agent
        while (true) {
            agent.checkWaitLimits()
            val left = deadline - monotonicNs()
            if (left <= 0) return
            val slice = minOf(left, 10_000_000L)
            try {
                Thread.sleep(slice / 1_000_000, (slice % 1_000_000).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedExecutionException("Execution interrupted")
            }
        }
    }

    /** "i" of PyArg_ParseTuple for a clock id ("iO" and "i" formats: their count checked by [name]'s caller). */
    private fun clockId(v: Any?): Int = try {
        cInt(v)
    } catch (e: PyException) {
        if (e.matches(PyExceptions.type("TypeError")) || e.matches(PyExceptions.type("OverflowError"))) {
            throw PyRt.typeError("clk_id should be integer, not ${PyTypes.name(v)}")
        }
        throw e
    }

    private fun exactly(name: String, n: Int, a: Array<Any?>) {
        if (a.size != n) throw PyRt.typeError("$name() takes exactly $n argument${if (n == 1) "" else "s"} (${a.size} given)")
    }

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        // (METH_NOARGS)
        fun noArgs(name: String, f: () -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a ->
                if (a.isNotEmpty()) throw PyRt.typeError("time.$name() takes no arguments (${a.size} given)")
                f()
            }, { _, a, kw ->
                if (kw.size > 0) throw PyRt.typeError("time.$name() takes no keyword arguments")
                if (a.isNotEmpty()) throw PyRt.typeError("time.$name() takes no arguments (${a.size} given)")
                f()
            })
        }
        // (METH_O)
        fun oneArg(name: String, f: (Any?) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a ->
                if (a.size != 1) throw PyRt.typeError("time.$name() takes exactly one argument (${a.size} given)")
                f(a[0])
            }, { _, a, kw ->
                if (kw.size > 0) throw PyRt.typeError("time.$name() takes no keyword arguments")
                if (a.size != 1) throw PyRt.typeError("time.$name() takes exactly one argument (${a.size} given)")
                f(a[0])
            })
        }
        // (METH_VARARGS)
        fun varArgs(name: String, f: (PyContext, Array<Any?>) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { c, a -> f(c, a) }, { c, a, kw ->
                if (kw.size > 0) throw PyRt.typeError("$name() takes no keyword arguments")
                f(c, a)
            })
        }

        noArgs("time") { seconds(realtimeNs()) }
        noArgs("time_ns") { realtimeNs() }
        oneArg("clock_gettime") { v ->
            val ts = clockTime(clockId(v)) ?: throw osError(EINVAL)
            ts[0].toDouble() + ts[1].toDouble() * 1e-9
        }
        oneArg("clock_gettime_ns") { v ->
            val ts = clockTime(clockId(v)) ?: throw osError(EINVAL)
            ts[0] * SEC_TO_NS + ts[1]
        }
        varArgs("clock_settime") { _, a ->
            exactly("clock_settime", 2, a)
            val id = cInt(a[0])
            clockSet(id, fromSecondsObject(a[1], ROUND_FLOOR))
        }
        varArgs("clock_settime_ns") { _, a ->
            exactly("clock_settime", 2, a)
            val id = cInt(a[0])
            clockSet(id, fromLong(a[1]))
        }
        varArgs("clock_getres") { _, a ->
            exactly("clock_getres", 1, a)
            clockRes(cInt(a[0])) ?: throw osError(EINVAL)
        }
        varArgs("pthread_getcpuclockid") { _, a ->
            exactly("pthread_getcpuclockid", 1, a)
            val v = a[0]
            val id = when {
                v is Long -> v
                v is java.math.BigInteger -> v.toLong()
                v is PyBool -> if (v.value) 1L else 0L
                v is PyNumSub && v.num !is Double -> (v.num as? Long) ?: (v.num as java.math.BigInteger).toLong()
                else -> throw PyRt.typeError("pthread_getcpuclockid() argument 1 must be int, not ${PyTypes.name(v)}")
            }
            // (glibc's MAKE_THREAD_CPUCLOCK(tid, CPUCLOCK_SCHED) of NeonPy's one thread; another is no thread: ESRCH)
            if (id != SELF_ID) throw osError(3L)
            (SELF_ID.inv() shl 3) or 6L
        }
        val audit = ctx.sysModule.dict[PyStr("audit")]
        oneArg("sleep") { v -> sleep(ctx, audit, v); PyNone }
        varArgs("gmtime") { _, a -> tmToTuple(gmtime(whenOf("gmtime", a), "GMT")) }
        varArgs("localtime") { _, a -> tmToTuple(localtime(whenOf("localtime", a))) }
        varArgs("asctime") { _, a ->
            if (a.size > 1) throw PyRt.typeError("asctime expected at most 1 argument, got ${a.size}")
            val tm = if (a.isEmpty()) localtime(now()) else gettmarg(a[0], "asctime").also { checktm(it) }
            asctime(tm)
        }
        varArgs("ctime") { _, a -> asctime(localtime(whenOf("ctime", a))) }
        oneArg("mktime") { v ->
            val tm = gettmarg(v, "mktime")
            (mktime(tm) ?: throw overflowError("mktime argument out of range")).toDouble()
        }
        varArgs("strftime") { _, a ->
            if (a.isEmpty()) throw PyRt.typeError("strftime() takes at least 1 argument (0 given)")
            if (a.size > 2) throw PyRt.typeError("strftime() takes at most 2 arguments (${a.size} given)")
            val fmt = a[0] as? PyStr ?: throw PyRt.typeError("strftime() argument 1 must be str, not ${PyTypes.name(a[0])}")
            val tm = if (a.size == 1) localtime(now()) else gettmarg(a[1], "strftime").also { checktm(it) }
            tm.isdst = tm.isdst.coerceIn(-1, 1)
            PyStr(strftime(fmt.s, tm))
        }
        varArgs("strptime") { c, a ->
            val f = PyRt.getAttr(PyImport.importModule(c, "_strptime"), "_strptime_time")
            PyCalls.callWith(c, f, a, NO_ARGS, NO_ARGS)
        }
        fun initTimezone() {
            d[PyStr("timezone")] = 0L
            d[PyStr("altzone")] = 0L
            d[PyStr("daylight")] = 0L
            d[PyStr("tzname")] = PyTuple(arrayOf(PyStr("UTC"), PyStr("UTC")))
        }
        noArgs("tzset") { initTimezone(); PyNone }
        noArgs("monotonic") { seconds(monotonicNs()) }
        noArgs("monotonic_ns") { monotonicNs() }
        noArgs("process_time") { seconds(cpuNs()) }
        noArgs("process_time_ns") { cpuNs() }
        noArgs("thread_time") { seconds(cpuNs()) }
        noArgs("thread_time_ns") { cpuNs() }
        noArgs("perf_counter") { seconds(monotonicNs()) }
        noArgs("perf_counter_ns") { monotonicNs() }
        varArgs("get_clock_info") { _, a ->
            exactly("get_clock_info", 1, a)
            val v = a[0] as? PyStr ?: throw PyRt.typeError("get_clock_info() argument 1 must be str, not ${PyTypes.name(a[0])}")
            val name = utf8(v)
            if ('\u0000' in name) throw valueError("embedded null character")
            val (impl, monotonic, adjustable) = when (name) {
                "time" -> Triple("clock_gettime(CLOCK_REALTIME)", false, true)
                "monotonic", "perf_counter" -> Triple("clock_gettime(CLOCK_MONOTONIC)", true, false)
                "process_time" -> Triple("clock_gettime(CLOCK_PROCESS_CPUTIME_ID)", true, false)
                "thread_time" -> Triple("clock_gettime(CLOCK_THREAD_CPUTIME_ID)", true, false)
                else -> throw valueError("unknown clock")
            }
            PySys.namespace("implementation" to PyStr(impl), "monotonic" to PyBool.of(monotonic), "adjustable" to PyBool.of(adjustable),
                "resolution" to 1e-09)
        }
        initTimezone()
        d[PyStr("CLOCK_REALTIME")] = CLOCK_REALTIME.toLong()
        d[PyStr("CLOCK_MONOTONIC")] = CLOCK_MONOTONIC.toLong()
        d[PyStr("CLOCK_MONOTONIC_RAW")] = CLOCK_MONOTONIC_RAW.toLong()
        d[PyStr("CLOCK_PROCESS_CPUTIME_ID")] = CLOCK_PROCESS_CPUTIME_ID.toLong()
        d[PyStr("CLOCK_THREAD_CPUTIME_ID")] = CLOCK_THREAD_CPUTIME_ID.toLong()
        d[PyStr("CLOCK_BOOTTIME")] = CLOCK_BOOTTIME.toLong()
        d[PyStr("_STRUCT_TM_ITEMS")] = 11L
        d[PyStr("struct_time")] = STRUCT_TIME
    }
}
