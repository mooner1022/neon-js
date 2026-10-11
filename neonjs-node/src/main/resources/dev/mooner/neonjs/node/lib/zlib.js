'use strict';
// node:zlib's one-shot functions (deflateSync, gunzip...) and constants, over java.util.zip (binding.zlib):
// decompression stops at options.maxOutputLength (ERR_BUFFER_TOO_LARGE) and counts against the engine's limits.
// The callback forms run at once and call back on the next tick. The streams (createDeflate...) need node:stream and
// Brotli a codec the platform lacks; they load and say they are not available. Options and errors follow Node
// (after Node v22.12.0 lib/zlib.js, MIT license, see NOTICE); windowBits and memLevel are checked but zlib's
// defaults are used, and the RLE and FIXED strategies compress as the default one does.

const {
  codes: { ERR_INVALID_ARG_TYPE },
} = require('internal/errors');
const { checkRangesOrGetDefault, validateFunction } = require('internal/validators');
const { isAnyArrayBuffer, isArrayBufferView } = require('internal/util/types');
const { unavailableClass, unavailableFunction } = require('internal/unavailable');
const { Buffer, kMaxLength } = require('buffer');

const native = binding.zlib;

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

// zlib's return codes both ways (zlib.codes)
const codes = {
  Z_OK: constants.Z_OK,
  Z_STREAM_END: constants.Z_STREAM_END,
  Z_NEED_DICT: constants.Z_NEED_DICT,
  Z_ERRNO: constants.Z_ERRNO,
  Z_STREAM_ERROR: constants.Z_STREAM_ERROR,
  Z_DATA_ERROR: constants.Z_DATA_ERROR,
  Z_MEM_ERROR: constants.Z_MEM_ERROR,
  Z_BUF_ERROR: constants.Z_BUF_ERROR,
  Z_VERSION_ERROR: constants.Z_VERSION_ERROR,
};
for (const ckey of Object.keys(codes)) codes[codes[ckey]] = ckey;
Object.freeze(codes);

function toBuffer(buffer) {
  if (typeof buffer === 'string') return Buffer.from(buffer);
  if (isAnyArrayBuffer(buffer)) return Buffer.from(buffer);
  if (!isArrayBufferView(buffer)) {
    throw new ERR_INVALID_ARG_TYPE('buffer', ['string', 'Buffer', 'TypedArray', 'DataView', 'ArrayBuffer'], buffer);
  }
  return buffer;
}

function zlibBufferSync(mode, buffer, opts) {
  buffer = toBuffer(buffer);
  opts = opts ?? {};
  const level = checkRangesOrGetDefault(opts.level, 'options.level', constants.Z_MIN_LEVEL, constants.Z_MAX_LEVEL,
    constants.Z_DEFAULT_COMPRESSION);
  const strategy = checkRangesOrGetDefault(opts.strategy, 'options.strategy', constants.Z_DEFAULT_STRATEGY,
    constants.Z_FIXED, constants.Z_DEFAULT_STRATEGY);
  checkRangesOrGetDefault(opts.windowBits, 'options.windowBits', constants.Z_MIN_WINDOWBITS, constants.Z_MAX_WINDOWBITS,
    constants.Z_DEFAULT_WINDOWBITS);
  checkRangesOrGetDefault(opts.memLevel, 'options.memLevel', constants.Z_MIN_MEMLEVEL, constants.Z_MAX_MEMLEVEL,
    constants.Z_DEFAULT_MEMLEVEL);
  checkRangesOrGetDefault(opts.chunkSize, 'options.chunkSize', constants.Z_MIN_CHUNK, constants.Z_MAX_CHUNK,
    constants.Z_DEFAULT_CHUNK);
  const maxOutputLength = checkRangesOrGetDefault(opts.maxOutputLength, 'options.maxOutputLength', 1, kMaxLength,
    kMaxLength);
  let dictionary = opts.dictionary;
  if (dictionary !== undefined) {
    if (isAnyArrayBuffer(dictionary)) dictionary = Buffer.from(dictionary);
    else if (!isArrayBufferView(dictionary)) {
      throw new ERR_INVALID_ARG_TYPE('options.dictionary', ['Buffer', 'TypedArray', 'DataView', 'ArrayBuffer'], dictionary);
    }
  }
  const out = native.run(mode, buffer, level, strategy, maxOutputLength, dictionary ?? null);
  const result = Buffer.allocUnsafe(native.length(out));
  native.take(out, result);
  return opts.info ? { buffer: result, engine: undefined } : result;
}

function syncFn(mode) {
  return function (buffer, opts) {
    return zlibBufferSync(mode, buffer, opts);
  };
}

// the callback form: done at once, called back on the next tick
function asyncFn(mode) {
  return function (buffer, opts, callback) {
    if (typeof opts === 'function') {
      callback = opts;
      opts = {};
    }
    validateFunction(callback, 'callback');
    let result;
    let error = null;
    try {
      result = zlibBufferSync(mode, buffer, opts);
    } catch (err) {
      if (err?.code === 'ERR_INVALID_ARG_TYPE' || err?.code === 'ERR_OUT_OF_RANGE') throw err;
      error = err;
    }
    require('process').nextTick(callback, error, result);
  };
}

function crc32(data, value = 0) {
  if (typeof data === 'string') data = Buffer.from(data);
  else if (!isArrayBufferView(data)) throw new ERR_INVALID_ARG_TYPE('data', ['Buffer', 'TypedArray', 'DataView', 'string'], data);
  if (typeof value !== 'number') throw new ERR_INVALID_ARG_TYPE('value', 'number', value);
  return native.crc32(data, value >>> 0);
}

const streams = ['Deflate', 'Inflate', 'Gzip', 'Gunzip', 'DeflateRaw', 'InflateRaw', 'Unzip', 'BrotliCompress',
  'BrotliDecompress'];

module.exports = {
  crc32,
  deflate: asyncFn(constants.DEFLATE),
  deflateSync: syncFn(constants.DEFLATE),
  gzip: asyncFn(constants.GZIP),
  gzipSync: syncFn(constants.GZIP),
  deflateRaw: asyncFn(constants.DEFLATERAW),
  deflateRawSync: syncFn(constants.DEFLATERAW),
  unzip: asyncFn(constants.UNZIP),
  unzipSync: syncFn(constants.UNZIP),
  inflate: asyncFn(constants.INFLATE),
  inflateSync: syncFn(constants.INFLATE),
  gunzip: asyncFn(constants.GUNZIP),
  gunzipSync: syncFn(constants.GUNZIP),
  inflateRaw: asyncFn(constants.INFLATERAW),
  inflateRawSync: syncFn(constants.INFLATERAW),
  brotliCompress: unavailableFunction('zlib.brotliCompress'),
  brotliCompressSync: unavailableFunction('zlib.brotliCompressSync'),
  brotliDecompress: unavailableFunction('zlib.brotliDecompress'),
  brotliDecompressSync: unavailableFunction('zlib.brotliDecompressSync'),
  codes,
};
for (const name of streams) {
  module.exports[name] = unavailableClass(`zlib.${name}`);
  module.exports[`create${name}`] = unavailableFunction(`zlib.create${name}`);
}
Object.defineProperty(module.exports, 'constants', { configurable: false, enumerable: true, value: constants });
// the legacy names (zlib.Z_NO_FLUSH...)
for (const key of Object.keys(constants)) {
  if (key.startsWith('BROTLI')) continue;
  Object.defineProperty(module.exports, key, { enumerable: false, value: constants[key], writable: false });
}
