'use strict';
// What Node's lib/zlib.js takes from its native side (MIT license, see NOTICE): the zlib constants, crc32, and the
// handle class whose write / writeSync advance a stream (binding.zlib, over java.util.zip). The native stream is kept
// out of reach of scripts, which see this class as Node's handle (zlibStream._handle). The asynchronous write does its
// work at once and calls back from a microtask (Node's runs on its thread pool). Brotli has no codec here.

const { codes: { ERR_UNAVAILABLE } } = require('internal/errors');

const native = binding.zlib;
// taken now: a script's later changes to the global do not reach the handles
const enqueueMicrotask = globalThis.queueMicrotask;

const constants = {
  __proto__: null,
  Z_NO_FLUSH: 0, Z_PARTIAL_FLUSH: 1, Z_SYNC_FLUSH: 2, Z_FULL_FLUSH: 3, Z_FINISH: 4, Z_BLOCK: 5,
  Z_OK: 0, Z_STREAM_END: 1, Z_NEED_DICT: 2, Z_ERRNO: -1, Z_STREAM_ERROR: -2, Z_DATA_ERROR: -3, Z_MEM_ERROR: -4,
  Z_BUF_ERROR: -5, Z_VERSION_ERROR: -6,
  Z_NO_COMPRESSION: 0, Z_BEST_SPEED: 1, Z_BEST_COMPRESSION: 9, Z_DEFAULT_COMPRESSION: -1,
  Z_FILTERED: 1, Z_HUFFMAN_ONLY: 2, Z_RLE: 3, Z_FIXED: 4, Z_DEFAULT_STRATEGY: 0, ZLIB_VERNUM: 4865,
  DEFLATE: 1, INFLATE: 2, GZIP: 3, GUNZIP: 4, DEFLATERAW: 5, INFLATERAW: 6, UNZIP: 7, BROTLI_DECODE: 8,
  BROTLI_ENCODE: 9,
  Z_MIN_WINDOWBITS: 8, Z_MAX_WINDOWBITS: 15, Z_DEFAULT_WINDOWBITS: 15, Z_MIN_CHUNK: 64, Z_MAX_CHUNK: Infinity,
  Z_DEFAULT_CHUNK: 16384, Z_MIN_MEMLEVEL: 1, Z_MAX_MEMLEVEL: 9, Z_DEFAULT_MEMLEVEL: 8, Z_MIN_LEVEL: -1,
  Z_MAX_LEVEL: 9, Z_DEFAULT_LEVEL: -1,
  BROTLI_OPERATION_PROCESS: 0, BROTLI_OPERATION_FLUSH: 1, BROTLI_OPERATION_FINISH: 2,
  BROTLI_OPERATION_EMIT_METADATA: 3, BROTLI_PARAM_MODE: 0, BROTLI_MODE_GENERIC: 0, BROTLI_MODE_TEXT: 1,
  BROTLI_MODE_FONT: 2, BROTLI_DEFAULT_MODE: 0, BROTLI_PARAM_QUALITY: 1, BROTLI_MIN_QUALITY: 0,
  BROTLI_MAX_QUALITY: 11, BROTLI_DEFAULT_QUALITY: 11, BROTLI_PARAM_LGWIN: 2, BROTLI_MIN_WINDOW_BITS: 10,
  BROTLI_MAX_WINDOW_BITS: 24, BROTLI_LARGE_MAX_WINDOW_BITS: 30, BROTLI_DEFAULT_WINDOW: 22, BROTLI_PARAM_LGBLOCK: 3,
  BROTLI_MIN_INPUT_BLOCK_BITS: 16, BROTLI_MAX_INPUT_BLOCK_BITS: 24, BROTLI_PARAM_DISABLE_LITERAL_CONTEXT_MODELING: 4,
  BROTLI_PARAM_SIZE_HINT: 5, BROTLI_PARAM_LARGE_WINDOW: 6, BROTLI_PARAM_NPOSTFIX: 7, BROTLI_PARAM_NDIRECT: 8,
  BROTLI_DECODER_RESULT_ERROR: 0, BROTLI_DECODER_RESULT_SUCCESS: 1, BROTLI_DECODER_RESULT_NEEDS_MORE_INPUT: 2,
  BROTLI_DECODER_RESULT_NEEDS_MORE_OUTPUT: 3, BROTLI_DECODER_PARAM_DISABLE_RING_BUFFER_REALLOCATION: 0,
  BROTLI_DECODER_PARAM_LARGE_WINDOW: 1,
};
Object.freeze(constants);

class Zlib {
  #stream;
  #writeState;
  #callback;

  constructor(mode) {
    this.#stream = native.create(mode);
  }

  init(windowBits, level, memLevel, strategy, writeState, processCallback, dictionary) {
    this.#writeState = writeState;
    this.#callback = processCallback;
    native.init(this.#stream, level, strategy, dictionary ?? null);
  }

  params(level, strategy) {
    native.params(this.#stream, level, strategy);
  }

  reset() {
    native.reset(this.#stream);
  }

  close() {
    native.close(this.#stream);
  }

  // zlib's error goes to onerror (node:zlib's), as the native handle's does; false then
  #run(flush, input, inOff, inLen, out, outOff, outLen) {
    const error = native.write(this.#stream, flush, input, inOff, inLen, out, outOff, outLen, this.#writeState);
    if (error === undefined) return true;
    this.onerror(error[0], error[1], error[2]);
    return false;
  }

  writeSync(flush, input, inOff, inLen, out, outOff, outLen) {
    this.#run(flush, input, inOff, inLen, out, outOff, outLen);
  }

  write(flush, input, inOff, inLen, out, outOff, outLen) {
    if (this.#run(flush, input, inOff, inLen, out, outOff, outLen)) enqueueMicrotask(() => this.#callback.call(this));
  }
}

function unavailableCodec() {
  throw new ERR_UNAVAILABLE('Brotli');
}

class BrotliEncoder {
  constructor() {
    unavailableCodec();
  }
}

class BrotliDecoder {
  constructor() {
    unavailableCodec();
  }
}

function crc32(data, value) {
  return native.crc32(typeof data === 'string' ? require('buffer').Buffer.from(data) : data, value);
}

module.exports = {
  BrotliDecoder,
  BrotliEncoder,
  constants,
  crc32,
  Zlib,
};
