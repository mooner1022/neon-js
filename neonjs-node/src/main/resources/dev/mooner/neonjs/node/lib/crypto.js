'use strict';
// node:crypto, the part a sandbox can give without OpenSSL: hashes and HMACs as Node's Hash and Hmac have them
// (over the platform's digests through binding.crypto; also Transform streams, as in Node), random bytes, integers and
// UUIDs from the web crypto's generator, timingSafeEqual, and the web crypto itself (webcrypto, subtle). Ciphers,
// signatures and key pairs are not here; they load and say so when used.

const {
  codes: {
    ERR_CRYPTO_HASH_FINALIZED, ERR_CRYPTO_HASH_UPDATE_FAILED, ERR_CRYPTO_INVALID_DIGEST,
    ERR_CRYPTO_TIMING_SAFE_EQUAL_LENGTH, ERR_INVALID_ARG_TYPE, ERR_OUT_OF_RANGE,
  },
} = require('internal/errors');
const {
  validateBoolean, validateFunction, validateNumber, validateObject, validateString, validateUint32,
} = require('internal/validators');
const { isAnyArrayBuffer, isArrayBufferView } = require('internal/util/types');
const { unavailableModule } = require('internal/unavailable');
const { Buffer, kMaxLength } = require('buffer');

const native = binding.crypto;
const webcrypto = globalThis.crypto;
const kHandle = Symbol('kHandle');
const kFinalized = Symbol('kFinalized');

// ---------------------------------------------------------------- Hash and Hmac

function toBytes(data, encoding) {
  if (typeof data === 'string') return Buffer.from(data, encoding && encoding !== 'buffer' ? encoding : 'utf8');
  if (!isArrayBufferView(data)) throw new ERR_INVALID_ARG_TYPE('data', ['string', 'Buffer', 'TypedArray', 'DataView'], data);
  return data;
}

function digestOf(handle, outputEncoding) {
  const out = Buffer.alloc(native.length(handle));
  native.digest(handle, out);
  return outputEncoding && outputEncoding !== 'buffer' ? out.toString(`${outputEncoding}`) : out;
}

function update(data, encoding) {
  if (this[kFinalized]) throw new ERR_CRYPTO_HASH_FINALIZED();
  const bytes = toBytes(data, encoding);
  try {
    native.update(this[kHandle], bytes);
  } catch {
    throw new ERR_CRYPTO_HASH_UPDATE_FAILED();
  }
  return this;
}

// Hash and Hmac are LazyTransform streams, as in Node; node:stream loads with the first of them
let LazyTransform;
function lazyTransform(self, options) {
  if (LazyTransform === undefined) {
    LazyTransform = require('internal/streams/lazy_transform');
    for (const C of [Hash, Hmac]) {
      Object.setPrototypeOf(C.prototype, LazyTransform.prototype);
      Object.setPrototypeOf(C, LazyTransform);
    }
  }
  Reflect.apply(LazyTransform, self, [options]);
}

function transform(chunk, encoding, callback) {
  native.update(this[kHandle], toBytes(chunk, encoding));
  callback();
}

function flush(callback) {
  this.push(digestOf(this[kHandle]));
  callback();
}

function Hash(algorithm, options) {
  if (!new.target) return new Hash(algorithm, options);
  if (algorithm !== null && typeof algorithm === 'object' && algorithm[kHandle] !== undefined) {
    // a copy
    this[kHandle] = native.copy(algorithm[kHandle]);
  } else {
    validateString(algorithm, 'algorithm');
    if (options !== null && typeof options === 'object' && options.outputLength !== undefined) {
      validateUint32(options.outputLength, 'options.outputLength');
    }
    const handle = native.hashNew(algorithm);
    if (handle === null) throw new Error('Digest method not supported');
    this[kHandle] = handle;
  }
  this[kFinalized] = false;
  lazyTransform(this, options);
}

Hash.prototype.update = update;
Hash.prototype._transform = transform;
Hash.prototype._flush = flush;

Hash.prototype.digest = function digest(outputEncoding) {
  if (this[kFinalized]) throw new ERR_CRYPTO_HASH_FINALIZED();
  const ret = digestOf(this[kHandle], outputEncoding);
  this[kFinalized] = true;
  return ret;
};

Hash.prototype.copy = function copy(options) {
  if (this[kFinalized]) throw new ERR_CRYPTO_HASH_FINALIZED();
  return new Hash(this, options);
};

function prepareSecretKey(key, encoding) {
  if (typeof key === 'string') return Buffer.from(key, encoding && encoding !== 'buffer' ? encoding : 'utf8');
  if (isArrayBufferView(key)) return key;
  if (isAnyArrayBuffer(key)) return new Uint8Array(key);
  throw new ERR_INVALID_ARG_TYPE('key', ['ArrayBuffer', 'Buffer', 'TypedArray', 'DataView', 'string', 'KeyObject', 'CryptoKey'], key);
}

function Hmac(hmac, key, options) {
  if (!new.target) return new Hmac(hmac, key, options);
  validateString(hmac, 'hmac');
  const encoding = options !== null && typeof options === 'object' ? options.encoding : undefined;
  const handle = native.hmacNew(hmac, prepareSecretKey(key, encoding));
  if (handle === null) throw new ERR_CRYPTO_INVALID_DIGEST(hmac);
  this[kHandle] = handle;
  this[kFinalized] = false;
  lazyTransform(this, options);
}

Hmac.prototype.update = update;
Hmac.prototype._transform = transform;
Hmac.prototype._flush = flush;

Hmac.prototype.digest = function digest(outputEncoding) {
  if (this[kFinalized]) {
    const buf = Buffer.from('');
    return outputEncoding && outputEncoding !== 'buffer' ? buf.toString(outputEncoding) : buf;
  }
  const ret = digestOf(this[kHandle], outputEncoding);
  this[kFinalized] = true;
  return ret;
};

function createHash(algorithm, options) {
  return new Hash(algorithm, options);
}

function createHmac(hmac, key, options) {
  return new Hmac(hmac, key, options);
}

// one data at once (crypto.hash)
function hash(algorithm, input, outputEncoding = 'hex') {
  validateString(algorithm, 'algorithm');
  if (typeof input !== 'string' && !isArrayBufferView(input)) {
    throw new ERR_INVALID_ARG_TYPE('input', ['Buffer', 'TypedArray', 'DataView', 'string'], input);
  }
  return new Hash(algorithm).update(input).digest(outputEncoding);
}

function getHashes() {
  return native.hashes();
}

// ---------------------------------------------------------------- random

function fillRandom(view, offset, size) {
  const bytes = new Uint8Array(view.buffer, view.byteOffset + offset, size);
  // getRandomValues fills at most 65536 bytes at a time
  for (let i = 0; i < size; i += 65536) webcrypto.getRandomValues(bytes.subarray(i, Math.min(size, i + 65536)));
}

function randomBytes(size, callback) {
  validateNumber(size, 'size', 0, kMaxLength);
  if (callback !== undefined) validateFunction(callback, 'callback');
  const buf = Buffer.alloc(size);
  fillRandom(buf, 0, size);
  if (callback === undefined) return buf;
  require('process').nextTick(callback, null, buf);
}

function viewByteRange(buf, offset, size, name) {
  if (!isAnyArrayBuffer(buf) && !isArrayBufferView(buf)) {
    throw new ERR_INVALID_ARG_TYPE(name, ['ArrayBuffer', 'ArrayBufferView'], buf);
  }
  const view = isAnyArrayBuffer(buf) ? new Uint8Array(buf) : new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
  const elementSize = buf.BYTES_PER_ELEMENT || 1;
  offset = offset === undefined ? 0 : offset;
  validateNumber(offset, 'offset', 0, view.byteLength / elementSize);
  offset *= elementSize;
  const max = view.byteLength - offset;
  size = size === undefined ? max : size * elementSize;
  validateNumber(size / elementSize, 'size', 0, max / elementSize);
  return { view, offset, size };
}

function randomFillSync(buf, offset = 0, size) {
  const r = viewByteRange(buf, offset, size, 'buf');
  fillRandom(r.view, r.offset, r.size);
  return buf;
}

function randomFill(buf, offset, size, callback) {
  if (typeof offset === 'function') {
    callback = offset;
    offset = undefined;
  } else if (typeof size === 'function') {
    callback = size;
    size = undefined;
  }
  validateFunction(callback, 'callback');
  randomFillSync(buf, offset, size);
  require('process').nextTick(callback, null, buf);
}

// a uniform integer in [min, max), as Node's randomInt (rejection sampling on 48-bit draws)
const RAND_MAX = 0xFFFF_FFFF_FFFF;

function randomInt(min, max, callback) {
  const minNotSpecified = typeof max === 'undefined' || typeof max === 'function';
  if (minNotSpecified) {
    callback = max;
    max = min;
    min = 0;
  }
  const isSync = typeof callback === 'undefined';
  if (!isSync) validateFunction(callback, 'callback');
  if (!Number.isSafeInteger(min)) throw new ERR_INVALID_ARG_TYPE('min', 'a safe integer', min);
  if (!Number.isSafeInteger(max)) throw new ERR_INVALID_ARG_TYPE('max', 'a safe integer', max);
  if (max <= min) {
    throw new ERR_OUT_OF_RANGE('max', `greater than the value of "min" (${min})`, max);
  }
  const range = max - min;
  if (!(range <= RAND_MAX)) {
    throw new ERR_OUT_OF_RANGE(`max${minNotSpecified ? '' : ' - min'}`, `<= ${RAND_MAX}`, range);
  }
  const randLimit = RAND_MAX - (RAND_MAX % range);
  const six = new Uint8Array(6);
  let n;
  do {
    webcrypto.getRandomValues(six);
    n = six[0] * 2 ** 40 + six[1] * 2 ** 32 + six[2] * 2 ** 24 + six[3] * 2 ** 16 + six[4] * 2 ** 8 + six[5];
  } while (n >= randLimit);
  const value = min + (n % range);
  if (isSync) return value;
  require('process').nextTick(callback, undefined, value);
}

function randomUUID(options) {
  if (options !== undefined) validateObject(options, 'options');
  const { disableEntropyCache = false } = options || {};
  validateBoolean(disableEntropyCache, 'options.disableEntropyCache');
  return webcrypto.randomUUID();
}

function getRandomValues(data) {
  return webcrypto.getRandomValues(data);
}

// ---------------------------------------------------------------- the rest

function timingSafeEqual(buf1, buf2) {
  if (!isAnyArrayBuffer(buf1) && !isArrayBufferView(buf1)) {
    throw new ERR_INVALID_ARG_TYPE('buf1', ['ArrayBuffer', 'Buffer', 'TypedArray', 'DataView'], buf1);
  }
  if (!isAnyArrayBuffer(buf2) && !isArrayBufferView(buf2)) {
    throw new ERR_INVALID_ARG_TYPE('buf2', ['ArrayBuffer', 'Buffer', 'TypedArray', 'DataView'], buf2);
  }
  const a = isAnyArrayBuffer(buf1) ? new Uint8Array(buf1) : new Uint8Array(buf1.buffer, buf1.byteOffset, buf1.byteLength);
  const b = isAnyArrayBuffer(buf2) ? new Uint8Array(buf2) : new Uint8Array(buf2.buffer, buf2.byteOffset, buf2.byteLength);
  if (a.length !== b.length) throw new ERR_CRYPTO_TIMING_SAFE_EQUAL_LENGTH();
  // every byte is looked at, whatever the first difference
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

const unavailable = unavailableModule('crypto', {
  functions: [
    'checkPrime', 'checkPrimeSync', 'createCipheriv', 'createDecipheriv', 'createDiffieHellman',
    'createDiffieHellmanGroup', 'createECDH', 'createPrivateKey', 'createPublicKey', 'createSecretKey', 'createSign',
    'createVerify', 'diffieHellman', 'generateKey', 'generateKeyPair', 'generateKeyPairSync', 'generateKeySync',
    'generatePrime', 'generatePrimeSync', 'hkdf', 'hkdfSync', 'pbkdf2', 'pbkdf2Sync', 'privateDecrypt',
    'privateEncrypt', 'publicDecrypt', 'publicEncrypt', 'scrypt', 'scryptSync', 'secureHeapUsed', 'setEngine', 'sign',
    'verify',
  ],
  classes: ['Certificate', 'Cipheriv', 'Decipheriv', 'DiffieHellman', 'DiffieHellmanGroup', 'ECDH', 'KeyObject', 'Sign',
    'Verify', 'X509Certificate'],
});

module.exports = {
  ...unavailable,
  Hash,
  Hmac,
  constants: Object.freeze({ __proto__: null }),
  createHash,
  createHmac,
  getCiphers: () => [],
  getCurves: () => [],
  getFips: () => 0,
  getHashes,
  getRandomValues,
  hash,
  randomBytes,
  randomFill,
  randomFillSync,
  randomInt,
  randomUUID,
  setFips: () => {},
  subtle: webcrypto?.subtle,
  timingSafeEqual,
  webcrypto,
  pseudoRandomBytes: randomBytes,
  prng: randomBytes,
  rng: randomBytes,
};
