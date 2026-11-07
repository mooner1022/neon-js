package dev.mooner.neonpython

import java.math.BigInteger

/** A _multibytecodec.MultibyteCodec: a CJK codec's encode() and decode() (the object the codec modules' getcodec gives). */
internal class PyMbCodecObj(type: PyType, @JvmField internal val codec: MbCodec) : PyObject(type)

/**
 * A MultibyteIncrementalEncoder, MultibyteIncrementalDecoder, MultibyteStreamReader or MultibyteStreamWriter (a
 * subclass's instance too: encodings' IncrementalEncoder...): its codec (its class's `codec`), the codec's 8 bytes of
 * state, how it treats errors, what is pending (an encoder's characters, a decoder's bytes), its stream.
 */
internal class PyMbStatefulObj(type: PyType) : PyObject(type) {
    @JvmField internal var codec: MbCodec? = null
    @JvmField internal val state = ByteArray(8)
    /** ERROR_STRICT, ERROR_IGNORE or ERROR_REPLACE ([PyMultibytecodec]'s), else a handler's name. */
    @JvmField internal var errors: Any = PyMultibytecodec.STRICT
    @JvmField internal var pending: IntArray? = null
    @JvmField internal val pendingBytes = ByteArray(8)
    @JvmField internal var pendingSize = 0
    @JvmField internal var stream: Any? = null
}

/**
 * `_multibytecodec` and the CJK codecs' modules (`_codecs_cn`, `_codecs_jp`, `_codecs_kr`, `_codecs_tw`, `_codecs_hk`,
 * `_codecs_iso2022`), as CPython 3.14 has them: Modules/cjkcodecs/multibytecodec.c and cjkcodecs.h in Kotlin (the
 * codecs themselves are PyCjkCodecs.kt's and PyIso2022.kt's), which CPython's encodings/gbk.py and the others are
 * made of. The encode and decode loops, their error handling (strict, ignore and replace by name, any other handler
 * looked up at each error; the exception made once per call, its start, end and reason set again), the incremental
 * encoder and decoder with their pending characters and bytes and their states as ints, the stream reader and writer,
 * and their messages. The output buffer grows as CPython's does (twice the characters and 16 bytes, then by half),
 * where a codec's write that does not fit is retried: hz's `~~` loses its second byte there, as in CPython. The
 * types are each module's (each context's here). A codec's `getcodec` imports `_multibytecodec` and the modules
 * whose maps it imports (iso2022_jp_2: `_codecs_cn`, `_codecs_jp`, `_codecs_kr`), and the maps are capsules
 * (`__map_gb2312`...), as CPython's.
 *
 * A str is a Java String, its positions code points (PyCodecs), so the encoders are given code points: a surrogate
 * pair is one character, where CPython has two.
 */
object PyMultibytecodec {
    private val NO_ARGS = arrayOfNulls<Any?>(0)
    private const val MAXENCPENDING = 2
    private const val MAXDECPENDING = 8
    const val MAP_CAPSULE = "multibytecodec.map"
    const val CODEC_CAPSULE = "multibytecodec.codec"

    // multibytecodec.h's ERROR_STRICT, ERROR_IGNORE, ERROR_REPLACE
    internal val STRICT = Any()
    internal val IGNORE = Any()
    internal val REPLACE = Any()

    private fun typeError(msg: String) = PyRt.typeError(msg)

    /** The codecs of each module (its BEGIN_CODECS_LIST's, in order) and its maps (BEGIN_MAPPINGS_LIST's). */
    private fun codecsOf(loc: String): Pair<List<MbCodec>, List<String>> = when (loc) {
        "cn" -> CjkCn.CODECS to CjkCn.MAPS
        "jp" -> CjkJp.CODECS to CjkJp.MAPS
        "kr" -> CjkKr.CODECS to CjkKr.MAPS
        "tw" -> CjkTw.CODECS to CjkTw.MAPS
        "hk" -> CjkHk.CODECS to CjkHk.MAPS
        else -> CjkIso2022.CODECS to emptyList()
    }

    // ------------------------------------------------------------------ errors

    /** internal_error_callback: how [errors] (a C string: up to its NUL) is treated. */
    private fun errorCallback(errors: String?): Any {
        val e = errors?.substringBefore('\u0000')
        return when (e) {
            null, "strict" -> STRICT
            "ignore" -> IGNORE
            "replace" -> REPLACE
            else -> PyStr(e)
        }
    }

    /** call_error_callback: the handler registered as [errors], called on [exc]. */
    private fun callErrorCallback(ctx: PyContext, errors: PyStr, exc: PyBaseException): Any? {
        val cb = PyCodecs.lookupErrorHandler(ctx, errors.s)
        return PyCalls.callWith(ctx, cb, arrayOf(exc), NO_ARGS, NO_ARGS)
    }

    private fun raise(ctx: PyContext, e: PyBaseException): Nothing = throw PyExceptions.raiseObject(e, ctx)

    private fun isInt(v: Any?): Boolean = v is Long || v is BigInteger || v is PyBool ||
        v is PyNumSub && (v.num is Long || v.num is BigInteger)

    private fun bytesOf(v: Any?): ByteArray? = when (v) {
        is PyBytes -> v.b
        is PyNumSub -> (v.num as? PyBytes)?.b
        else -> null
    }

    /** PyLong_AsSsize_t of an int: null where it overflows (CPython's -1 with the error set). */
    private fun ssize(v: Any?): Long? = when (v) {
        is Long -> v
        is PyBool -> if (v.value) 1L else 0L
        is PyNumSub -> ssize(v.num)
        else -> null
    }

    /** The error handler's position: from the end where negative, IndexError where outside [0, len]. */
    private fun newPos(item: Any?, len: Int): Int {
        var newpos = ssize(item)
        if (newpos != null && newpos < 0) newpos += len
        if (newpos == null || newpos < 0 || newpos > len) {
            throw PyException("IndexError", "position ${newpos ?: -1} from error handler out of bounds")
        }
        return newpos.toInt()
    }

    // ------------------------------------------------------------------ encoding

    /** A MultibyteEncodeBuffer: the str encoded ([inobj], for the error), its code points' buffer ([e]), the error made. */
    private class EncodeBuffer(@JvmField val inobj: PyStr, @JvmField val e: Enc) {
        @JvmField var exc: PyBaseException? = null
    }

    /** expand_encodebuffer: the output grown by half (and one), or by [esize] where that is more. */
    private fun expand(buf: EncodeBuffer, esize: Int) {
        val e = buf.e
        val orgsize = e.out.size
        val incsize = if (esize < (orgsize shr 1)) (orgsize shr 1) or 1 else esize
        if (orgsize.toLong() + incsize > Int.MAX_VALUE - 8) throw PyException("MemoryError", "")
        e.out = e.out.copyOf(orgsize + incsize)
        e.outend = e.out.size
    }

    /** REQUIRE_ENCODEBUFFER */
    private fun require(buf: EncodeBuffer, s: Int) {
        if (s < 0 || s > buf.e.outend - buf.e.outpos) expand(buf, s)
    }

    /** multibytecodec_encerror: what [errors] does with the error [r] the encoder returned (or raises). */
    private fun encError(ctx: PyContext, codec: MbCodec, st: ByteArray, buf: EncodeBuffer, errors: Any, r: Int) {
        val e = buf.e
        val reason: String
        val esize: Int
        if (r > 0) {
            reason = "illegal multibyte sequence"
            esize = r
        } else {
            when (r) {
                Mb.TOOSMALL -> {
                    require(buf, -1)
                    // (retried)
                    return
                }
                Mb.TOOFEW -> {
                    reason = "incomplete multibyte sequence"
                    esize = e.inpos
                }
                Mb.INTERNAL -> throw PyException("RuntimeError", "internal codec error")
                else -> throw PyException("RuntimeError", "unknown runtime error")
            }
        }
        if (errors === REPLACE) {
            // ('?' through the codec, in its state; else a '?' of its own)
            val inp = e.inp
            val inpos = e.inpos
            val inlen = e.inlen
            e.inp = intArrayOf('?'.code)
            e.inpos = 0
            e.inlen = 1
            var rr: Int
            try {
                while (true) {
                    rr = codec.encode(st, e, 0)
                    if (rr == Mb.TOOSMALL) {
                        require(buf, -1)
                        continue
                    }
                    break
                }
            } finally {
                e.inp = inp
                e.inpos = inpos
                e.inlen = inlen
            }
            if (rr != 0) {
                require(buf, 1)
                e.out[e.outpos++] = '?'.code.toByte()
            }
        }
        if (errors === IGNORE || errors === REPLACE) {
            e.inpos += esize
            return
        }
        val start = e.inpos
        val end = start + esize
        val exc = buf.exc?.also {
            it.setField("start", start.toLong())
            it.setField("end", end.toLong())
            it.setField("reason", PyStr(reason))
        } ?: PyCodecs.newError(PyExceptions.type("UnicodeEncodeError"), codec.encoding, buf.inobj, start, end, reason).also { buf.exc = it }
        if (errors === STRICT) raise(ctx, exc)
        val ret = callErrorCallback(ctx, errors as PyStr, exc)
        val tobj = (ret as? PyTuple)?.items?.takeIf { it.size == 2 }?.get(0)
        if (ret !is PyTuple || ret.items.size != 2 || (tobj !is PyStr && bytesOf(tobj) == null) || !isInt(ret.items[1])) {
            throw typeError("encoding error handler must return (str, int) tuple")
        }
        val retstr = if (tobj is PyStr) encode(ctx, codec, st, tobj, null, STRICT, Mb.FLUSH) else bytesOf(tobj)!!
        if (retstr.isNotEmpty()) {
            require(buf, retstr.size)
            retstr.copyInto(e.out, e.outpos)
            e.outpos += retstr.size
        }
        e.inpos = newPos(ret.items[1], e.inlen)
    }

    /**
     * multibytecodec_encode: [text] encoded by [codec] in its state [st], [errors] treating its errors, [flags] FLUSH
     * (all that can be encoded) and RESET (the reset sequence after); where it stopped in [inposOut]'s first.
     */
    private fun encode(ctx: PyContext, codec: MbCodec, st: ByteArray, text: PyStr, inposOut: IntArray?, errors: Any, flags: Int): ByteArray {
        val cps = text.s.codePoints().toArray()
        val datalen = cps.size
        if (datalen == 0 && flags and Mb.RESET == 0) {
            inposOut?.set(0, 0)
            return ByteArray(0)
        }
        if (datalen > (Int.MAX_VALUE - 16) / 2) throw PyException("MemoryError", "")
        val buf = EncodeBuffer(text, Enc(cps, datalen, ByteArray(datalen * 2 + 16)))
        val e = buf.e
        while (e.inpos < e.inlen) {
            // (the error handlers may put the position anywhere)
            val r = codec.encode(st, e, flags)
            if (r == 0 || (r == Mb.TOOFEW && flags and Mb.FLUSH == 0)) break
            encError(ctx, codec, st, buf, errors, r)
            if (r == Mb.TOOFEW) break
        }
        if (flags and Mb.RESET != 0) {
            while (true) {
                val r = codec.encReset(st, e)
                if (r == 0) break
                encError(ctx, codec, st, buf, errors, r)
            }
        }
        inposOut?.set(0, e.inpos)
        return e.out.copyOf(e.outpos)
    }

    /** encoder_encode_stateful: [unistr] (its str() where it is none) after what is pending, the rest pending again. */
    private fun encodeStateful(ctx: PyContext, o: PyMbStatefulObj, unistr0: Any?, final: Boolean): PyBytes {
        val codec = o.codec!!
        val unistr = unistr0 as? PyStr ?: PyStr(PyRt.str(unistr0))
        val origpending = o.pending
        val inbuf = if (origpending != null) {
            o.pending = null
            PyStr(String(origpending, 0, origpending.size) + unistr.s)
        } else {
            unistr
        }
        val datalen = inbuf.length
        val inpos = IntArray(1)
        val r = try {
            encode(ctx, codec, o.state, inbuf, inpos, o.errors, if (final) Mb.FLUSH or Mb.RESET else 0)
        } catch (t: Throwable) {
            // (the pending characters as they were)
            o.pending = origpending
            throw t
        }
        if (inpos[0] < datalen) {
            if (datalen - inpos[0] > MAXENCPENDING) {
                // (no codec gets here)
                raise(ctx, PyCodecs.newError(PyExceptions.type("UnicodeEncodeError"), codec.encoding, inbuf, inpos[0], datalen, "pending buffer overflow"))
            }
            o.pending = inbuf.s.codePoints().toArray().copyOfRange(inpos[0], datalen)
        }
        return PyBytes(r)
    }

    // ------------------------------------------------------------------ decoding

    /** multibytecodec_decerror: what [errors] does with the error [r] the decoder returned (or raises). */
    private fun decError(ctx: PyContext, codec: MbCodec, buf: Dec, errors: Any, r: Int) {
        val reason: String
        val esize: Int
        if (r > 0) {
            reason = "illegal multibyte sequence"
            esize = r
        } else {
            when (r) {
                // (retried)
                Mb.TOOSMALL -> return
                Mb.TOOFEW -> {
                    reason = "incomplete multibyte sequence"
                    esize = buf.end - buf.pos
                }
                Mb.INTERNAL -> throw PyException("RuntimeError", "internal codec error")
                else -> throw PyException("RuntimeError", "unknown runtime error")
            }
        }
        if (errors === REPLACE) buf.out(0xFFFD)
        if (errors === IGNORE || errors === REPLACE) {
            buf.pos += esize
            return
        }
        val start = buf.pos
        val end = start + esize
        val exc = buf.exc?.also {
            it.setField("start", start.toLong())
            it.setField("end", end.toLong())
            it.setField("reason", PyStr(reason))
        } ?: PyCodecs.newError(PyExceptions.type("UnicodeDecodeError"), codec.encoding, PyBytes(buf.b.copyOf(buf.end)), start, end, reason).also { buf.exc = it }
        if (errors === STRICT) raise(ctx, exc)
        val ret = callErrorCallback(ctx, errors as PyStr, exc)
        val retuni = (ret as? PyTuple)?.items?.takeIf { it.size == 2 }?.get(0)
        if (ret !is PyTuple || ret.items.size != 2 || retuni !is PyStr || !isInt(ret.items[1])) {
            throw typeError("decoding error handler must return (str, int) tuple")
        }
        buf.w.append(retuni.s)
        buf.pos = newPos(ret.items[1], buf.end)
    }

    /** decoder_feed_buffer: the buffer decoded up to an incomplete sequence at its end. */
    private fun feed(ctx: PyContext, o: PyMbStatefulObj, buf: Dec) {
        val codec = o.codec!!
        while (buf.pos < buf.end) {
            val r = codec.decode(o.state, buf)
            if (r == 0 || r == Mb.TOOFEW) break
            decError(ctx, codec, buf, o.errors, r)
        }
    }

    /** decoder_append_pending: the bytes left kept for the next call, at most MAXDECPENDING. */
    private fun appendPending(ctx: PyContext, o: PyMbStatefulObj, buf: Dec) {
        val npendings = buf.end - buf.pos
        if (npendings + o.pendingSize > MAXDECPENDING) {
            raise(ctx, PyCodecs.newError(PyExceptions.type("UnicodeDecodeError"), o.codec!!.encoding, PyBytes(buf.b.copyOf(buf.end)), 0, buf.end, "pending buffer overflow"))
        }
        buf.b.copyInto(o.pendingBytes, o.pendingSize, buf.pos, buf.end)
        o.pendingSize += npendings
    }

    /** decoder_prepare_buffer: [data] the buffer's (its writer and its error kept). */
    private fun prepare(buf: Dec, data: ByteArray) {
        buf.b = data
        buf.pos = 0
        buf.end = data.size
    }

    // ------------------------------------------------------------------ MultibyteCodec

    internal fun codecEncode(ctx: PyContext, self: PyMbCodecObj, input0: Any?, errors: String?): PyTuple {
        val input = input0 as? PyStr ?: PyStr(PyRt.str(input0))
        val datalen = input.length
        val errorcb = errorCallback(errors)
        val st = ByteArray(8)
        self.codec.encInit(st)
        val r = encode(ctx, self.codec, st, input, null, errorcb, Mb.FLUSH or Mb.RESET)
        return PyTuple(arrayOf(PyBytes(r), datalen.toLong()))
    }

    internal fun codecDecode(ctx: PyContext, self: PyMbCodecObj, data: ByteArray, errors: String?): PyTuple {
        val errorcb = errorCallback(errors)
        if (data.isEmpty()) return PyTuple(arrayOf(PyStr.EMPTY, 0L))
        val codec = self.codec
        val st = ByteArray(8)
        val buf = Dec(data, 0, data.size)
        codec.decInit(st)
        while (buf.pos < buf.end) {
            val r = codec.decode(st, buf)
            if (r == 0) break
            decError(ctx, codec, buf, errorcb, r)
        }
        return PyTuple(arrayOf(PyStr(buf.w.toString()), data.size.toLong()))
    }

    // ------------------------------------------------------------------ the stateful objects

    private fun incEncode(ctx: PyContext, o: PyMbStatefulObj, input: Any?, final: Boolean): PyBytes = encodeStateful(ctx, o, input, final)

    private fun incEncoderGetstate(ctx: PyContext, o: PyMbStatefulObj): Any {
        // (1 byte the pending UTF-8's size, at most MAXENCPENDING*4 bytes of it, the 8 of the codec's state)
        val out = java.io.ByteArrayOutputStream()
        val p = o.pending
        if (p != null) {
            val s = PyStr(String(p, 0, p.size))
            val utf8 = PyCodecs.encodeUtf8(ctx, s, null)
            if (utf8.size > MAXENCPENDING * 4) {
                raise(ctx, PyCodecs.newError(PyExceptions.type("UnicodeEncodeError"), o.codec!!.encoding, s, 0, p.size, "pending buffer too large"))
            }
            out.write(utf8.size)
            out.write(utf8)
        } else {
            out.write(0)
        }
        out.write(o.state)
        return fromLittleEndian(out.toByteArray())
    }

    private fun fromLittleEndian(b: ByteArray): Any {
        val be = ByteArray(b.size + 1)
        for (i in b.indices) be[b.size - i] = b[i]
        return PyRt.norm(BigInteger(be))
    }

    /** _PyLong_AsByteArray(v, n bytes, little-endian, unsigned): its OverflowErrors. */
    private fun toLittleEndian(v: Any?, n: Int): ByteArray {
        val x: BigInteger = when (v) {
            is Long -> BigInteger.valueOf(v)
            is BigInteger -> v
            is PyBool -> if (v.value) BigInteger.ONE else BigInteger.ZERO
            is PyNumSub -> return toLittleEndian(v.num, n)
            else -> throw IllegalStateException()
        }
        if (x.signum() < 0) throw PyException("OverflowError", "can't convert negative int to unsigned")
        if (x.bitLength() > n * 8) throw PyException("OverflowError", "int too big to convert")
        val be = x.toByteArray()
        val out = ByteArray(n)
        for (i in 0 until minOf(n, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    private fun incEncoderSetstate(ctx: PyContext, o: PyMbStatefulObj, statelong: Any?) {
        val sb = toLittleEndian(statelong, 1 + MAXENCPENDING * 4 + 8)
        val n = sb[0].toInt() and 0xFF
        if (n > MAXENCPENDING * 4) throw PyException("UnicodeError", "pending buffer too large")
        val pending = PyCodecs.decodeUtf8(ctx, sb.copyOfRange(1, 1 + n), "strict", true).text
        o.pending = pending.codePoints().toArray()
        sb.copyInto(o.state, 0, 1 + n, 1 + n + 8)
    }

    private fun incEncoderReset(o: PyMbStatefulObj) {
        // (the longest output 4 bytes, ISO 2022's SI ESC ( B; not kept)
        val e = Enc(IntArray(0), 0, ByteArray(4))
        val r = o.codec!!.encReset(o.state, e)
        if (r != 0) throw PyException("SystemError", "<method 'reset' of '_multibytecodec.MultibyteIncrementalEncoder' objects> returned NULL without setting an exception")
        o.pending = null
    }

    private fun incDecode(ctx: PyContext, o: PyMbStatefulObj, data: ByteArray, final: Boolean): PyStr {
        val origpending = o.pendingSize
        val wdata = if (o.pendingSize == 0) data else {
            val w = ByteArray(data.size + o.pendingSize)
            o.pendingBytes.copyInto(w, 0, 0, o.pendingSize)
            data.copyInto(w, o.pendingSize)
            o.pendingSize = 0
            w
        }
        val buf = Dec(wdata, 0, wdata.size)
        feed(ctx, o, buf)
        if (final && buf.pos < buf.end) {
            try {
                decError(ctx, o.codec!!, buf, o.errors, Mb.TOOFEW)
            } catch (t: Throwable) {
                // (the pending bytes as they were)
                wdata.copyInto(o.pendingBytes, 0, 0, origpending)
                o.pendingSize = origpending
                throw t
            }
        }
        // (a sequence still pending)
        if (buf.pos < buf.end) appendPending(ctx, o, buf)
        return PyStr(buf.w.toString())
    }

    private fun incDecoderGetstate(o: PyMbStatefulObj): PyTuple =
        PyTuple(arrayOf(PyBytes(o.pendingBytes.copyOf(o.pendingSize)), fromLittleEndian(o.state)))

    private fun incDecoderSetstate(ctx: PyContext, o: PyMbStatefulObj, state: PyTuple) {
        val items = state.items
        val buffer = if (items.size == 2) bytesOf(items[0]) else null
        if (buffer == null || !isInt(items[1])) throw typeError("setstate(): illegal state argument")
        val sb = toLittleEndian(items[1], 8)
        if (buffer.size > MAXDECPENDING) {
            raise(ctx, PyCodecs.newError(PyExceptions.type("UnicodeDecodeError"), o.codec!!.encoding, PyBytes(buffer.copyOf()), 0, buffer.size, "pending buffer too large"))
        }
        o.pendingSize = buffer.size
        buffer.copyInto(o.pendingBytes)
        sb.copyInto(o.state)
    }

    private fun decoderReset(o: PyMbStatefulObj) {
        o.codec!!.decReset(o.state)
        o.pendingSize = 0
    }

    /** mbstreamreader_iread: the stream's [method] (read or readline) called until something decodes. */
    private fun streamRead(ctx: PyContext, o: PyMbStatefulObj, method: String, sizehint0: Long): PyStr {
        var sizehint = sizehint0
        if (sizehint == 0L) return PyStr.EMPTY
        val buf = Dec(ByteArray(0), 0, 0)
        while (true) {
            val f = PyRt.getAttr(o.stream, method)
            // (the size a C int, as PyObject_CallMethod's "i" takes it)
            val cres = if (sizehint < 0) PyCalls.callWith(ctx, f, NO_ARGS, NO_ARGS, NO_ARGS)
                else PyCalls.callWith(ctx, f, arrayOf(sizehint.toInt().toLong()), NO_ARGS, NO_ARGS)
            var bytes = bytesOf(cres) ?: throw typeError("stream function returned a non-bytes object (${PyTypes.name(cres)})")
            val endoffile = bytes.isEmpty()
            if (o.pendingSize > 0) {
                val ctr = ByteArray(bytes.size + o.pendingSize)
                o.pendingBytes.copyInto(ctr, 0, 0, o.pendingSize)
                bytes.copyInto(ctr, o.pendingSize)
                bytes = ctr
                o.pendingSize = 0
            }
            val rsize = bytes.size
            prepare(buf, bytes)
            if (rsize > 0) feed(ctx, o, buf)
            if (endoffile || sizehint < 0) {
                if (buf.pos < buf.end) decError(ctx, o.codec!!, buf, o.errors, Mb.TOOFEW)
            }
            // (a sequence pending)
            if (buf.pos < buf.end) appendPending(ctx, o, buf)
            if (sizehint < 0 || buf.w.isNotEmpty() || rsize == 0) break
            // (one more byte read, and again)
            sizehint = 1
        }
        return PyStr(buf.w.toString())
    }

    /** The size argument of read, readline and readlines: None -1, an int (by PyLong_AsSsize_t). */
    private fun sizeArg(v: Any?): Long {
        if (v == null || v === PyNone) return -1
        if (!isInt(v)) throw typeError("arg 1 must be an integer")
        return ssize(v) ?: throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
    }

    private fun streamWrite(ctx: PyContext, o: PyMbStatefulObj, unistr: Any?) {
        val str = encodeStateful(ctx, o, unistr, false)
        PyCalls.callWith(ctx, PyRt.getAttr(o.stream, "write"), arrayOf(str), NO_ARGS, NO_ARGS)
    }

    /** PySequence_Check: what has __getitem__ (an item by index), not a dict. */
    private fun isSequence(v: Any?): Boolean = when (v) {
        is PyList, is PyTuple, is PyStr, is PyBytes, is PyByteArray -> true
        is PyDict -> false
        else -> PyTypes.typeOf(v).lookup("__getitem__") != null
    }

    private fun streamWritelines(ctx: PyContext, o: PyMbStatefulObj, lines: Any?) {
        if (!isSequence(lines)) throw typeError("arg must be a sequence object")
        // (its length looked at again each time: it may change)
        var i = 0L
        while (i < PyRt.len(lines)) {
            streamWrite(ctx, o, PyRt.getItem(lines, i))
            i++
        }
    }

    private fun streamWriterReset(ctx: PyContext, o: PyMbStatefulObj) {
        val p = o.pending ?: return
        val text = PyStr(String(p, 0, p.size))
        // (what is pending dropped even where it fails: reset() resets)
        val pwrt = try {
            encode(ctx, o.codec!!, o.state, text, null, o.errors, Mb.FLUSH or Mb.RESET)
        } finally {
            o.pending = null
        }
        if (pwrt.isNotEmpty()) PyCalls.callWith(ctx, PyRt.getAttr(o.stream, "write"), arrayOf(PyBytes(pwrt)), NO_ARGS, NO_ARGS)
    }

    // ------------------------------------------------------------------ the types

    private class State(@JvmField val ctx: PyContext) {
        @JvmField val codecType = moduleType(ctx, "MultibyteCodec", false)
        @JvmField val encoderType = moduleType(ctx, "MultibyteIncrementalEncoder", true)
        @JvmField val decoderType = moduleType(ctx, "MultibyteIncrementalDecoder", true)
        @JvmField val readerType = moduleType(ctx, "MultibyteStreamReader", true)
        @JvmField val writerType = moduleType(ctx, "MultibyteStreamWriter", true)
    }

    /** A type of the module: a heap type, as CPython's made from a spec are, each module's (each context's here). */
    private fun moduleType(ctx: PyContext, name: String, subclassable: Boolean): PyType {
        val t = PyType(name, arrayOf(PyTypes.OBJECT), heap = true)
        t.mro = PyTypes.c3(t, t.bases)
        t.module = "_multibytecodec"
        t.tpName = "_multibytecodec.$name"
        t.subclassable = subclassable
        PyTypes.OBJECT.addSubclass(ctx, t)
        return t
    }

    private fun rest(a: Array<Any?>): Array<Any?> = if (a.size <= 1) NO_ARGS else a.copyOfRange(1, a.size)

    private fun self(t: PyType, a: Array<Any?>, n: String): Any {
        val o = a.getOrNull(0)
        if (!PyTypes.typeOf(o).isSubtype(t)) {
            if (a.isEmpty()) throw typeError("unbound method ${t.name}.$n() needs an argument")
            throw typeError("descriptor '$n' for '${t.tpName}' objects doesn't apply to a '${PyTypes.name(o)}' object")
        }
        return o!!
    }

    /**
     * Who a method's messages name ("takes no arguments"): its type's, as a method descriptor called gives; its
     * instance's type where that has `__getattr__` (codecs.StreamReader's), which makes CPython call the method bound.
     */
    private fun owner(t: PyType, o: Any): String {
        val ot = PyTypes.typeOf(o)
        return if (ot.lookup("__getattr__") != null) ot.qualname else t.name
    }

    /** A method of METH_NOARGS. */
    private fun noArgs(t: PyType, n: String, f: (PyMbStatefulObj) -> Any?) {
        fun call(a: Array<Any?>, kw: PyDict?): Any? {
            val o = self(t, a, n)
            if (kw != null && kw.size > 0) throw typeError("${owner(t, o)}.$n() takes no keyword arguments")
            if (a.size != 1) throw typeError("${owner(t, o)}.$n() takes no arguments (${a.size - 1} given)")
            return f(o as PyMbStatefulObj)
        }
        t.def(n, PyBuiltin(n, { _, a -> call(a, null) }, { _, a, kw -> call(a, kw) }))
    }

    /** A method of METH_O. */
    private fun oneArg(t: PyType, n: String, f: (PyMbStatefulObj, Any?) -> Any?) {
        fun call(a: Array<Any?>, kw: PyDict?): Any? {
            val o = self(t, a, n)
            if (kw != null && kw.size > 0) throw typeError("${owner(t, o)}.$n() takes no keyword arguments")
            if (a.size != 2) throw typeError("${owner(t, o)}.$n() takes exactly one argument (${a.size - 1} given)")
            return f(o as PyMbStatefulObj, a[1])
        }
        t.def(n, PyBuiltin(n, { _, a -> call(a, null) }, { _, a, kw -> call(a, kw) }))
    }

    /** A method of METH_FASTCALL (by position only: _PyArg_CheckPositional's messages). */
    private fun positional(t: PyType, n: String, max: Int, f: (PyMbStatefulObj, Array<Any?>) -> Any?) {
        fun call(a: Array<Any?>, kw: PyDict?): Any? {
            val o = self(t, a, n)
            if (kw != null && kw.size > 0) throw typeError("${owner(t, o)}.$n() takes no keyword arguments")
            PyMath.checkPositional(n, a.size - 1, 0, max)
            return f(o as PyMbStatefulObj, rest(a))
        }
        t.def(n, PyBuiltin(n, { _, a -> call(a, null) }, { _, a, kw -> call(a, kw) }))
    }

    /** A method by Argument Clinic: [names], the first [posOnly] by position only, [minPos] required. */
    private fun clinic(t: PyType, n: String, names: List<String>, posOnly: Int, minPos: Int, f: (Any, Array<Any?>) -> Any?) {
        fun call(a: Array<Any?>, kw: PyDict?): Any? {
            val o = self(t, a, n)
            return f(o, PyMath.clinic(n, rest(a), kw, names, posOnly, minPos, names.size))
        }
        t.def(n, PyBuiltin(n, { _, a -> call(a, null) }, { _, a, kw -> call(a, kw) }))
    }

    /** Argument Clinic's `str(accept={str, NoneType})`: the errors argument. */
    private fun errorsArg(fn: String, v: Any?): String? {
        if (v == null || v === PyNone) return null
        if (v !is PyStr) throw typeError("$fn() argument 'errors' must be str or None, not ${PyTypes.name(v)}")
        return PyCodecs.cstr(v, "$fn() argument 'errors'")
    }

    /** No __dict__ for attributes of a type's own instances (a subclass's have one). */
    private fun noDict(t: PyType) {
        t.def("__setattr__", PyBuiltin("__setattr__") { _, a ->
            val n = (a[1] as? PyStr ?: throw typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s
            val d = t.lookup(n)
            if (PyTypes.typeOf(a[0]) !== t || d is PyProperty) PyClasses.genericSetAttr(a[0], n, a[2])
            else if (d != null) throw PyException("AttributeError", "'${t.tpName}' object attribute '$n' is read-only")
            else throw PyException("AttributeError", "'${t.tpName}' object has no attribute '$n' and no __dict__ for setting new attributes")
            PyNone
        })
        t.def("__delattr__", PyBuiltin("__delattr__") { _, a ->
            val n = (a[1] as? PyStr ?: throw typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s
            val d = t.lookup(n)
            if (PyTypes.typeOf(a[0]) !== t || d is PyProperty) PyClasses.genericDelAttr(a[0], n)
            else if (d != null) throw PyException("AttributeError", "'${t.tpName}' object attribute '$n' is read-only")
            else throw PyException("AttributeError", "'${t.tpName}' object has no attribute '$n'")
            PyNone
        })
        t.hooks = 2 or 4
    }

    /**
     * object.__reduce_ex__ as CPython's is for the codec and the incremental coders: from protocol 2, the state a C
     * struct holds, which nothing gives (no __reduce__, no __getnewargs__), "cannot pickle"; else object's. (The stream
     * reader and writer have codecs.StreamReader's and StreamWriter's.)
     */
    private val REDUCE_EX = PyBuiltin("__reduce_ex__") { ctx, a ->
        val v = a.getOrNull(0)
        val proto = PyRt.indexValue(a.getOrNull(1)) as? Long ?: throw typeError("__reduce_ex__() missing required argument 'protocol' (pos 1)")
        val t = PyTypes.typeOf(v)
        val reduce = t.lookup("__reduce__")
        if (proto >= 2 && (reduce == null || reduce === PyTypes.OBJECT.dict[PyStr("__reduce__")]) && t.lookup("__getnewargs_ex__") == null &&
            t.lookup("__getnewargs__") == null) {
            throw typeError("cannot pickle '${PyTypes.name(v)}' object")
        }
        PyDunder.reduceEx(ctx, v, proto.toInt())
    }

    private fun common(t: PyType) {
        t.def("__module__", PyStr("_multibytecodec"))
        t.def("__doc__", PyNone)
        t.dispatchBuiltins = true
    }

    /** codecctx_getsets: `errors`, "strict", "ignore", "replace" or the handler's name. */
    private fun errorsProperty(t: PyType) {
        val n = "errors"
        t.def(n, PyProperty(PyBuiltin(n) { _, a ->
            when (val e = (self(t, a, n) as PyMbStatefulObj).errors) {
                STRICT -> PyStr("strict")
                IGNORE -> PyStr("ignore")
                REPLACE -> PyStr("replace")
                else -> e
            }
        }, PyBuiltin(n) { _, a ->
            val o = self(t, a, n) as PyMbStatefulObj
            val v = a[1] as? PyStr ?: throw typeError("errors must be a string")
            if (!v.bmp) PyCodecs.encodeUtf8(PyContext.current(), v, null)
            o.errors = errorCallback(v.s)
            PyNone
        }, PyBuiltin(n) { _, _ -> throw PyException("AttributeError", "cannot delete attribute") }, PyStr("how to treat errors")).also { it.name = n })
    }

    /** The `stream` member, read-only. */
    private fun streamMember(t: PyType) {
        val n = "stream"
        val readonly = PyBuiltin(n) { _, _ -> throw PyException("AttributeError", "readonly attribute") }
        t.def(n, PyProperty(PyBuiltin(n) { _, a -> (self(t, a, n) as PyMbStatefulObj).stream ?: PyNone }, readonly, readonly, null).also { it.name = n })
    }

    /**
     * PyArg_ParseTupleAndKeywords of `__new__`'s arguments ("|s:IncrementalEncoder", "O|s:StreamReader"): [names]
     * in order, the first [min] required; an 's' a str (as a C string), an 'O' anything. vgetargskeywords' messages.
     */
    private fun parseNew(fname: String, a: Array<Any?>, kw: PyDict?, names: List<String>, min: Int): Array<Any?> {
        val len = names.size
        val nargs = a.size
        var nkwargs = kw?.size ?: 0
        if (nargs + nkwargs > len) {
            throw typeError("$fname() takes at most $len ${if (nargs == 0) "keyword " else ""}argument${if (len == 1) "" else "s"} (${nargs + nkwargs} given)")
        }
        val out = arrayOfNulls<Any?>(len)
        for (i in 0 until len) {
            val cur = if (i < nargs) a[i] else if (nkwargs > 0) kw!![PyStr(names[i])]?.also { nkwargs-- } else null
            if (cur != null) {
                // (the errors an 's': a str, as a C string)
                if (names[i] == "errors") PyCodecs.cstr(cur, "$fname() argument ${i + 1}")
                out[i] = cur
                continue
            }
            if (i < min) throw typeError("$fname() missing required argument '${names[i]}' (pos ${i + 1})")
            if (nkwargs == 0) return out
        }
        if (nkwargs > 0) {
            for (i in 0 until nargs) {
                if (kw!!.containsKey(PyStr(names[i]))) throw typeError("argument for $fname() given by name ('${names[i]}') and position (${i + 1})")
            }
            for (k in kw!!.map.keys) {
                val key = k.v as? PyStr ?: throw typeError("keywords must be strings")
                if (key.s in names) continue
                val hint = PyMath.suggestion(names, key.s)
                throw typeError("$fname() got an unexpected keyword argument '${key.s}'" + if (hint != null) ". Did you mean '$hint'?" else "")
            }
        }
        return out
    }

    /** mbiencoder_new and the others: the arguments, the class's `codec` (a MultibyteCodec of the module), its init. */
    private fun newStateful(st: State, base: PyType, fname: String, stream: Boolean, a: Array<Any?>, kw: PyDict?): PyMbStatefulObj {
        if (a.isEmpty()) throw typeError("${base.tpName}.__new__(): not enough arguments")
        val cls = a[0] as? PyType ?: throw typeError("${base.tpName}.__new__(X): X is not a type object (${PyTypes.name(a[0])})")
        if (!cls.isSubtype(base)) throw typeError("${base.tpName}.__new__(${cls.tpName ?: cls.name}): ${cls.tpName ?: cls.name} is not a subtype of ${base.tpName}")
        val names = if (stream) listOf("stream", "errors") else listOf("errors")
        val p = parseNew(fname, rest(a), kw?.takeIf { it.size > 0 }, names, if (stream) 1 else 0)
        val o = PyMbStatefulObj(cls)
        val codec = PyRt.getAttr(cls, "codec")
        if (codec !is PyMbCodecObj || codec.type !== st.codecType) throw typeError("codec is unexpected type")
        o.codec = codec.codec
        if (stream) o.stream = p[0]
        val errors = p[names.size - 1]
        o.errors = errorCallback((errors as PyStr?)?.s)
        if (base === st.encoderType || base === st.writerType) codec.codec.encInit(o.state) else codec.codec.decInit(o.state)
        if (cls.heap) PyWeakrefs.made(o)
        return o
    }

    private fun defineStateful(st: State, t: PyType, fname: String, stream: Boolean) {
        common(t)
        t.def("__new__", PyBuiltin("__new__", { _, a -> newStateful(st, t, fname, stream, a, null) }, { _, a, kw -> newStateful(st, t, fname, stream, a, kw) }))
        // (an __init__ that takes anything and does nothing: the codecs' classes' other bases' not called)
        t.def("__init__", PyBuiltin("__init__", { _, _ -> PyNone }, { _, _, _ -> PyNone }))
        errorsProperty(t)
        if (stream) streamMember(t)
    }

    private fun defineTypes(st: State) {
        val ctx = st.ctx
        val c = st.codecType
        common(c)
        noDict(c)
        c.hasDict = false
        c.hasWeakref = false
        c.def("__reduce_ex__", REDUCE_EX)
        c.newImpl = { _, _, _, _ -> throw typeError("cannot create '_multibytecodec.MultibyteCodec' instances") }
        clinic(c, "encode", listOf("input", "errors"), 0, 1) { o, p -> codecEncode(ctx, o as PyMbCodecObj, p[0], errorsArg("encode", p[1])) }
        clinic(c, "decode", listOf("input", "errors"), 0, 1) { o, p ->
            val data = PyBinascii.buffer(p[0])
            codecDecode(ctx, o as PyMbCodecObj, data, errorsArg("decode", p[1]))
        }

        val e = st.encoderType
        defineStateful(st, e, "IncrementalEncoder", false)
        e.def("__reduce_ex__", REDUCE_EX)
        clinic(e, "encode", listOf("input", "final"), 0, 1) { o, p -> incEncode(ctx, o as PyMbStatefulObj, p[0], p[1] != null && PyRt.truthy(p[1])) }
        noArgs(e, "getstate") { incEncoderGetstate(ctx, it) }
        oneArg(e, "setstate") { o, v ->
            if (!isInt(v)) throw typeError("setstate() argument must be int, not ${PyTypes.name(v)}")
            incEncoderSetstate(ctx, o, v)
            PyNone
        }
        noArgs(e, "reset") {
            incEncoderReset(it)
            PyNone
        }

        val d = st.decoderType
        defineStateful(st, d, "IncrementalDecoder", false)
        d.def("__reduce_ex__", REDUCE_EX)
        clinic(d, "decode", listOf("input", "final"), 0, 1) { o, p ->
            val data = PyBinascii.buffer(p[0])
            incDecode(ctx, o as PyMbStatefulObj, data, p[1] != null && PyRt.truthy(p[1]))
        }
        noArgs(d, "getstate") { incDecoderGetstate(it) }
        oneArg(d, "setstate") { o, v ->
            if (v !is PyTuple) throw typeError("setstate() argument must be tuple, not ${PyTypes.name(v)}")
            incDecoderSetstate(ctx, o, v)
            PyNone
        }
        noArgs(d, "reset") {
            decoderReset(it)
            PyNone
        }

        val r = st.readerType
        defineStateful(st, r, "StreamReader", true)
        positional(r, "read", 1) { o, a -> streamRead(ctx, o, "read", sizeArg(a.getOrNull(0))) }
        positional(r, "readline", 1) { o, a -> streamRead(ctx, o, "readline", sizeArg(a.getOrNull(0))) }
        positional(r, "readlines", 1) { o, a -> PyStrings.splitlines(streamRead(ctx, o, "read", sizeArg(a.getOrNull(0))).s, true) }
        noArgs(r, "reset") {
            decoderReset(it)
            PyNone
        }

        val w = st.writerType
        defineStateful(st, w, "StreamWriter", true)
        clinic(w, "write", listOf(""), 1, 1) { o, p ->
            streamWrite(ctx, o as PyMbStatefulObj, p[0])
            PyNone
        }
        clinic(w, "writelines", listOf(""), 1, 1) { o, p ->
            streamWritelines(ctx, o as PyMbStatefulObj, p[0])
            PyNone
        }
        fun reset(a: Array<Any?>, kw: PyDict?): Any? {
            val o = self(w, a, "reset") as PyMbStatefulObj
            if (a.size > 1 || (kw != null && kw.size > 0)) throw typeError("reset() takes no arguments")
            streamWriterReset(ctx, o)
            return PyNone
        }
        w.def("reset", PyBuiltin("reset", { _, a -> reset(a, null) }, { _, a, kw -> reset(a, kw) }))
    }

    // ------------------------------------------------------------------ the modules

    /** `_multibytecodec`: the four stateful types, and __create_codec (which the codec modules' getcodec calls). */
    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val st = State(ctx)
        defineTypes(st)
        d[PyStr("MultibyteIncrementalEncoder")] = st.encoderType
        d[PyStr("MultibyteIncrementalDecoder")] = st.decoderType
        d[PyStr("MultibyteStreamReader")] = st.readerType
        d[PyStr("MultibyteStreamWriter")] = st.writerType
        val n = "__create_codec"
        d[PyStr(n)] = PyBuiltin(n, { _, a ->
            if (a.size != 1) throw typeError("_multibytecodec.$n() takes exactly one argument (${a.size} given)")
            val cap = a[0] as? PyCapsuleObj
            val codec = cap?.takeIf { it.name == CODEC_CAPSULE }?.pointer as? MbCodec ?: throw PyException("ValueError", "argument type invalid")
            codec.init(ctx)
            PyMbCodecObj(st.codecType, codec)
        }, { _, _, _ -> throw typeError("_multibytecodec.$n() takes no keyword arguments") })
    }

    /** `_codecs_[loc]`: its maps (capsules, `__map_gb2312`...) and getcodec. */
    @JvmStatic
    fun fillCodecs(ctx: PyContext, d: PyDict, loc: String) {
        val (codecs, maps) = codecsOf(loc)
        for (m in maps) d[PyStr("__map_$m")] = PyCapsuleObj(MAP_CAPSULE, m)
        val fn = "_codecs_$loc.getcodec"
        d[PyStr("getcodec")] = PyBuiltin("getcodec", { _, a ->
            if (a.size != 1) throw typeError("$fn() takes exactly one argument (${a.size} given)")
            val encoding = a[0] as? PyStr ?: throw typeError("encoding name must be a string.")
            if (!encoding.bmp) PyCodecs.encodeUtf8(ctx, encoding, null)
            // (a C string: up to its NUL)
            val enc = encoding.s.substringBefore('\u0000')
            val codec = codecs.firstOrNull { it.encoding == enc } ?: throw PyException("LookupError", "no such codec is supported.")
            val create = PyRt.getAttr(PyImport.importModule(ctx, "_multibytecodec"), "__create_codec")
            PyCalls.callWith(ctx, create, arrayOf(PyCapsuleObj(CODEC_CAPSULE, codec)), NO_ARGS, NO_ARGS)
        }, { _, _, _ -> throw typeError("$fn() takes no keyword arguments") })
    }

    /** IMPORT_MAP: `_codecs_[loc]` imported, its `__map_[charset]` a map's capsule (the map itself is CjkTables'). */
    internal fun importMap(ctx: PyContext, loc: String, charset: String) {
        val mod = PyImport.importModule(ctx, "_codecs_$loc")
        val o = PyRt.getAttr(mod, "__map_$charset")
        if (o !is PyCapsuleObj || o.name != MAP_CAPSULE) throw PyException("ValueError", "map data must be a Capsule.")
    }
}
