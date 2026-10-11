'use strict';
// node:buffer. Buffer is a Uint8Array subclass (FastBuffer) as in Node: Buffer.prototype is FastBuffer.prototype, and
// Buffer itself only allocates. Every Buffer is allocated here, by the engine's typed arrays (within its limits);
// binding.buffer only encodes, decodes, fills and searches bytes a view already has. allocUnsafe gives zeroed memory.

const {
  codes: {
    ERR_INVALID_ARG_TYPE, ERR_INVALID_ARG_VALUE, ERR_OUT_OF_RANGE, ERR_UNKNOWN_ENCODING, ERR_BUFFER_OUT_OF_BOUNDS,
    ERR_INVALID_BUFFER_SIZE,
  },
  validateNumber, validateInteger, validateString, validateArray,
} = binding.internal('errors');
const native = binding.buffer;
const types = binding.types;

const kMaxLength = native.kMaxLength;
const kStringMaxLength = native.kStringMaxLength;
const customInspectSymbol = Symbol.for('nodejs.util.inspect.custom');
let INSPECT_MAX_BYTES = 50;

const TypedArrayPrototype = Object.getPrototypeOf(Uint8Array.prototype);
const uncurry = (fn) => Function.prototype.call.bind(fn);
const TypedArrayPrototypeFill = uncurry(TypedArrayPrototype.fill);
const TypedArrayPrototypeSet = uncurry(TypedArrayPrototype.set);
const TypedArrayPrototypeSlice = uncurry(TypedArrayPrototype.slice);
const getter = (name) => uncurry(Object.getOwnPropertyDescriptor(TypedArrayPrototype, name).get);
const TypedArrayPrototypeGetLength = getter('length');
const TypedArrayPrototypeGetByteLength = getter('byteLength');
const TypedArrayPrototypeGetByteOffset = getter('byteOffset');
const TypedArrayPrototypeGetBuffer = getter('buffer');
const isUint8Array = types.isUint8Array;
const isTypedArray = types.isTypedArray;
const isAnyArrayBuffer = types.isAnyArrayBuffer;
const isArrayBufferView = types.isArrayBufferView;

// encodings by binding index (Codecs.Enc order)
const UTF8 = 0;
const UTF16LE = 1;
const LATIN1 = 2;
const ASCII = 3;
const HEX = 4;
const BASE64 = 5;
const BASE64URL = 6;

/** The binding index of an encoding name, or -1 for one Node does not know. */
function encodingIndex(enc) {
  switch (enc) {
    case 'utf8': case 'utf-8': return UTF8;
    case 'hex': return HEX;
    case 'base64': return BASE64;
    case 'latin1': case 'binary': return LATIN1;
    case 'ucs2': case 'ucs-2': case 'utf16le': case 'utf-16le': return UTF16LE;
    case 'ascii': return ASCII;
    case 'base64url': return BASE64URL;
  }
  enc = `${enc}`.toLowerCase();
  switch (enc) {
    case 'utf8': case 'utf-8': return UTF8;
    case 'hex': return HEX;
    case 'base64': return BASE64;
    case 'latin1': case 'binary': return LATIN1;
    case 'ucs2': case 'ucs-2': case 'utf16le': case 'utf-16le': return UTF16LE;
    case 'ascii': return ASCII;
    case 'base64url': return BASE64URL;
  }
  return -1;
}

const ENCODING_NAMES = ['utf8', 'utf16le', 'latin1', 'ascii', 'hex', 'base64', 'base64url'];

/** Node's normalizeEncoding: the canonical name, or undefined. */
function normalizeEncoding(enc) {
  if (enc == null || enc === 'utf8' || enc === 'utf-8') return 'utf8';
  const i = encodingIndex(enc);
  return i < 0 ? undefined : ENCODING_NAMES[i];
}

class FastBuffer extends Uint8Array {
  // eslint-disable-next-line no-useless-constructor
  constructor(bufferOrLength, byteOffset, length) {
    super(bufferOrLength, byteOffset, length);
  }
}

let bufferWarned = false;
function Buffer(arg, encodingOrOffset, length) {
  if (!bufferWarned) {
    bufferWarned = true;
    require('process').emitWarning('Buffer() is deprecated due to security and usability issues. Please use the ' +
      'Buffer.alloc(), Buffer.allocUnsafe(), or Buffer.from() methods instead.', 'DeprecationWarning', 'DEP0005');
  }
  if (typeof arg === 'number') {
    if (typeof encodingOrOffset === 'string') throw ERR_INVALID_ARG_TYPE('string', 'string', arg);
    return Buffer.alloc(arg);
  }
  return Buffer.from(arg, encodingOrOffset, length);
}

FastBuffer.prototype.constructor = Buffer;
Buffer.prototype = FastBuffer.prototype;
Object.setPrototypeOf(Buffer, Uint8Array);
Object.defineProperty(Buffer, Symbol.species, { enumerable: false, configurable: true, get() { return FastBuffer; } });

Buffer.poolSize = 8 * 1024;

const validateOffset = (value, name, min = 0, max = kMaxLength) => validateInteger(value, name, min, max);

// ---------------------------------------------------------------- creating

function fromString(string, encoding) {
  let e = UTF8;
  if (typeof encoding === 'string' && encoding.length !== 0) {
    e = encodingIndex(encoding);
    if (e < 0) throw ERR_UNKNOWN_ENCODING(encoding);
  }
  if (string.length === 0) return new FastBuffer();
  const length = native.byteLength(string, e);
  const buf = new FastBuffer(length);
  const written = native.write(buf, string, e, 0, length);
  return written === length ? buf : new FastBuffer(TypedArrayPrototypeGetBuffer(buf), 0, written);
}

function fromArrayBuffer(obj, byteOffset, length) {
  if (byteOffset === undefined) {
    byteOffset = 0;
  } else {
    byteOffset = +byteOffset;
    if (Number.isNaN(byteOffset)) byteOffset = 0;
  }
  const maxLength = obj.byteLength - byteOffset;
  if (maxLength < 0) throw ERR_BUFFER_OUT_OF_BOUNDS('offset');
  if (length === undefined) {
    length = maxLength;
  } else {
    length = +length;
    if (length > 0) {
      if (length > maxLength) throw ERR_BUFFER_OUT_OF_BOUNDS('length');
    } else {
      length = 0;
    }
  }
  return new FastBuffer(obj, byteOffset, length);
}

function fromArrayLike(obj) {
  if (obj.length <= 0) return new FastBuffer();
  return new FastBuffer(obj);
}

function fromObject(obj) {
  if (obj.length !== undefined || isAnyArrayBuffer(obj.buffer)) {
    if (typeof obj.length !== 'number') return new FastBuffer();
    return fromArrayLike(obj);
  }
  if (obj.type === 'Buffer' && Array.isArray(obj.data)) return fromArrayLike(obj.data);
}

Buffer.from = function from(value, encodingOrOffset, length) {
  if (typeof value === 'string') return fromString(value, encodingOrOffset);
  if (typeof value === 'object' && value !== null) {
    if (isAnyArrayBuffer(value)) return fromArrayBuffer(value, encodingOrOffset, length);
    const valueOf = value.valueOf && value.valueOf();
    if (valueOf != null && valueOf !== value && (typeof valueOf === 'string' || typeof valueOf === 'object')) {
      return from(valueOf, encodingOrOffset, length);
    }
    const b = fromObject(value);
    if (b) return b;
    if (typeof value[Symbol.toPrimitive] === 'function') {
      const primitive = value[Symbol.toPrimitive]('string');
      if (typeof primitive === 'string') return fromString(primitive, encodingOrOffset);
    }
  }
  throw ERR_INVALID_ARG_TYPE('first argument', ['string', 'Buffer', 'ArrayBuffer', 'Array', 'Array-like Object'], value);
};

Buffer.copyBytesFrom = function copyBytesFrom(view, offset, length) {
  if (!isTypedArray(view)) throw ERR_INVALID_ARG_TYPE('view', ['TypedArray'], view);
  const viewLength = TypedArrayPrototypeGetLength(view);
  if (viewLength === 0) return Buffer.alloc(0);
  if (offset !== undefined || length !== undefined) {
    if (offset !== undefined) {
      validateInteger(offset, 'offset', 0);
      if (offset >= viewLength) return Buffer.alloc(0);
    } else {
      offset = 0;
    }
    let end;
    if (length !== undefined) {
      validateInteger(length, 'length', 0);
      end = offset + length;
    } else {
      end = viewLength;
    }
    view = TypedArrayPrototypeSlice(view, offset, end);
  }
  return fromArrayLike(new Uint8Array(TypedArrayPrototypeGetBuffer(view), TypedArrayPrototypeGetByteOffset(view),
    TypedArrayPrototypeGetByteLength(view)));
};

Buffer.of = function of(...items) {
  const buf = new FastBuffer(items.length);
  for (let k = 0; k < items.length; k++) buf[k] = items[k];
  return buf;
};

Buffer.alloc = function alloc(size, fill, encoding) {
  validateNumber(size, 'size', 0, kMaxLength);
  if (fill !== undefined && fill !== 0 && size > 0) {
    const buf = new FastBuffer(size);
    return _fill(buf, fill, 0, buf.length, encoding);
  }
  return new FastBuffer(size);
};

// zeroed, unlike Node's: a sandbox hands out no stale memory
Buffer.allocUnsafe = function allocUnsafe(size) {
  validateNumber(size, 'size', 0, kMaxLength);
  return new FastBuffer(size);
};

Buffer.allocUnsafeSlow = function allocUnsafeSlow(size) {
  validateNumber(size, 'size', 0, kMaxLength);
  return new FastBuffer(size);
};

function SlowBuffer(size) {
  validateNumber(size, 'size', 0, kMaxLength);
  return new FastBuffer(size);
}
Object.setPrototypeOf(SlowBuffer.prototype, Uint8Array.prototype);
Object.setPrototypeOf(SlowBuffer, Uint8Array);

Buffer.isBuffer = function isBuffer(b) {
  return b instanceof Buffer;
};

Buffer.compare = function compare(buf1, buf2) {
  if (!isUint8Array(buf1)) throw ERR_INVALID_ARG_TYPE('buf1', ['Buffer', 'Uint8Array'], buf1);
  if (!isUint8Array(buf2)) throw ERR_INVALID_ARG_TYPE('buf2', ['Buffer', 'Uint8Array'], buf2);
  if (buf1 === buf2) return 0;
  return native.compare(buf1, 0, buf1.length, buf2, 0, buf2.length);
};

Buffer.isEncoding = function isEncoding(encoding) {
  return typeof encoding === 'string' && encoding.length !== 0 && encodingIndex(encoding) >= 0;
};

Buffer.concat = function concat(list, length) {
  validateArray(list, 'list');
  if (list.length === 0) return new FastBuffer();
  if (length === undefined) {
    length = 0;
    for (let i = 0; i < list.length; i++) {
      if (list[i].length) length += list[i].length;
    }
  } else {
    validateOffset(length, 'length');
  }
  const buffer = Buffer.allocUnsafe(length);
  let pos = 0;
  for (let i = 0; i < list.length; i++) {
    const buf = list[i];
    if (!isUint8Array(buf)) throw ERR_INVALID_ARG_TYPE(`list[${i}]`, ['Buffer', 'Uint8Array'], list[i]);
    pos += _copyActual(buf, buffer, pos, 0, buf.length);
  }
  if (pos < length) TypedArrayPrototypeFill(buffer, 0, pos, length);
  return buffer;
};

function base64ByteLength(str, bytes) {
  if (str.charCodeAt(bytes - 1) === 0x3D) bytes--;
  if (bytes > 1 && str.charCodeAt(bytes - 1) === 0x3D) bytes--;
  return (bytes * 3) >>> 2;
}

function byteLength(string, encoding) {
  if (typeof string !== 'string') {
    if (isArrayBufferView(string) || isAnyArrayBuffer(string)) return string.byteLength;
    throw ERR_INVALID_ARG_TYPE('string', ['string', 'Buffer', 'ArrayBuffer'], string);
  }
  const len = string.length;
  if (len === 0) return 0;
  const e = !encoding ? UTF8 : encodingIndex(encoding);
  switch (e) {
    case UTF16LE: return len * 2;
    case LATIN1: case ASCII: return len;
    case HEX: return len >>> 1;
    case BASE64: case BASE64URL: return base64ByteLength(string, len);
    default: return native.byteLength(string, UTF8);
  }
}
Buffer.byteLength = byteLength;

// ---------------------------------------------------------------- copying, comparing, searching

function toInteger(n, defaultVal) {
  n = +n;
  if (!Number.isNaN(n) && n >= Number.MIN_SAFE_INTEGER && n <= Number.MAX_SAFE_INTEGER) {
    return n % 1 === 0 ? n : Math.floor(n);
  }
  return defaultVal;
}

function _copy(source, target, targetStart, sourceStart, sourceEnd) {
  if (!isUint8Array(source)) throw ERR_INVALID_ARG_TYPE('source', ['Buffer', 'Uint8Array'], source);
  if (!isUint8Array(target)) throw ERR_INVALID_ARG_TYPE('target', ['Buffer', 'Uint8Array'], target);
  if (targetStart === undefined) {
    targetStart = 0;
  } else {
    targetStart = Number.isInteger(targetStart) ? targetStart : toInteger(targetStart, 0);
    if (targetStart < 0) throw ERR_OUT_OF_RANGE('targetStart', '>= 0', targetStart);
  }
  if (sourceStart === undefined) {
    sourceStart = 0;
  } else {
    sourceStart = Number.isInteger(sourceStart) ? sourceStart : toInteger(sourceStart, 0);
    if (sourceStart < 0 || sourceStart > source.byteLength) {
      throw ERR_OUT_OF_RANGE('sourceStart', `>= 0 && <= ${source.byteLength}`, sourceStart);
    }
  }
  if (sourceEnd === undefined) {
    sourceEnd = source.byteLength;
  } else {
    sourceEnd = Number.isInteger(sourceEnd) ? sourceEnd : toInteger(sourceEnd, 0);
    if (sourceEnd < 0) throw ERR_OUT_OF_RANGE('sourceEnd', '>= 0', sourceEnd);
  }
  if (targetStart >= target.byteLength || sourceStart >= sourceEnd) return 0;
  return _copyActual(source, target, targetStart, sourceStart, sourceEnd);
}

function _copyActual(source, target, targetStart, sourceStart, sourceEnd) {
  if (sourceEnd - sourceStart > target.byteLength - targetStart) sourceEnd = sourceStart + target.byteLength - targetStart;
  let nb = sourceEnd - sourceStart;
  const sourceLen = source.byteLength - sourceStart;
  if (nb > sourceLen) nb = sourceLen;
  if (nb <= 0) return 0;
  // set() copies through a clone when both views share a buffer, so overlapping ranges move as memmove does
  TypedArrayPrototypeSet(target, new Uint8Array(TypedArrayPrototypeGetBuffer(source),
    TypedArrayPrototypeGetByteOffset(source) + sourceStart, nb), targetStart);
  return nb;
}

function bidirectionalIndexOf(buffer, val, byteOffset, encoding, dir) {
  if (!isUint8Array(buffer)) throw ERR_INVALID_ARG_TYPE('buffer', ['Buffer', 'Uint8Array'], buffer);
  if (typeof byteOffset === 'string') {
    encoding = byteOffset;
    byteOffset = undefined;
  } else if (byteOffset > 0x7fffffff) {
    byteOffset = 0x7fffffff;
  } else if (byteOffset < -0x80000000) {
    byteOffset = -0x80000000;
  }
  byteOffset = +byteOffset;
  if (Number.isNaN(byteOffset)) byteOffset = dir ? 0 : (buffer.length || buffer.byteLength);
  dir = !!dir;
  if (typeof val === 'number') return native.indexOf(buffer, (val >>> 0) & 0xFF, byteOffset, UTF8, dir);
  const e = encoding === undefined ? UTF8 : encodingIndex(encoding);
  if (typeof val === 'string') {
    if (e < 0) throw ERR_UNKNOWN_ENCODING(encoding);
    return native.indexOf(buffer, val, byteOffset, e, dir);
  }
  if (isUint8Array(val)) return native.indexOf(buffer, val, byteOffset, e < 0 ? UTF8 : e, dir);
  throw ERR_INVALID_ARG_TYPE('value', ['number', 'string', 'Buffer', 'Uint8Array'], val);
}

// ---------------------------------------------------------------- fill

function _fill(buf, value, offset, end, encoding) {
  let e = UTF8;
  if (typeof value === 'string') {
    if (offset === undefined || typeof offset === 'string') {
      encoding = offset;
      offset = 0;
      end = buf.length;
    } else if (typeof end === 'string') {
      encoding = end;
      end = buf.length;
    }
    e = encoding == null ? UTF8 : encodingIndex(encoding);
    if (e < 0) {
      validateString(encoding, 'encoding');
      throw ERR_UNKNOWN_ENCODING(encoding);
    }
    if (value.length === 0) {
      value = 0;
    } else if (value.length === 1) {
      if (e === UTF8) {
        const code = value.charCodeAt(0);
        if (code < 128) value = code;
      } else if (e === LATIN1) {
        value = value.charCodeAt(0);
      }
    }
  } else if (!isArrayBufferView(value) && typeof value !== 'number') {
    // anything else fills with its low byte, as Node's native fill does
    value = (value >>> 0) & 255;
  }
  if (offset === undefined) {
    offset = 0;
    end = buf.length;
  } else {
    validateOffset(offset, 'offset');
    if (end === undefined) end = buf.length;
    else validateOffset(end, 'end', 0, buf.length);
    if (offset >= end) return buf;
  }
  if (typeof value === 'number') {
    const byteLen = TypedArrayPrototypeGetByteLength(buf);
    const fillLength = end - offset;
    if (offset > end || fillLength + offset > byteLen) throw ERR_BUFFER_OUT_OF_BOUNDS();
    TypedArrayPrototypeFill(buf, value, offset, end);
  } else {
    if (types.isDataView(value)) value = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    if (native.fill(buf, value, e, offset, end) < 0) throw ERR_INVALID_ARG_VALUE('value', value);
  }
  return buf;
}

// ---------------------------------------------------------------- reading and writing numbers

function boundsError(value, length, type) {
  if (Math.floor(value) !== value) {
    validateNumber(value, type);
    throw ERR_OUT_OF_RANGE(type || 'offset', 'an integer', value);
  }
  if (length < 0) throw ERR_BUFFER_OUT_OF_BOUNDS();
  throw ERR_OUT_OF_RANGE(type || 'offset', `>= ${type ? 1 : 0} and <= ${length}`, value);
}

function checkBounds(buf, offset, byteLength) {
  validateNumber(offset, 'offset');
  if (buf[offset] === undefined || buf[offset + byteLength] === undefined) boundsError(offset, buf.length - (byteLength + 1));
}

function checkInt(value, min, max, buf, offset, byteLength) {
  if (value > max || value < min) {
    const n = typeof min === 'bigint' ? 'n' : '';
    let range;
    if (byteLength > 3) {
      if (min === 0 || min === 0n) range = `>= 0${n} and < 2${n} ** ${(byteLength + 1) * 8}${n}`;
      else range = `>= -(2${n} ** ${(byteLength + 1) * 8 - 1}${n}) and < 2${n} ** ${(byteLength + 1) * 8 - 1}${n}`;
    } else {
      range = `>= ${min}${n} and <= ${max}${n}`;
    }
    throw ERR_OUT_OF_RANGE('value', range, value);
  }
  checkBounds(buf, offset, byteLength);
}

/** The unsigned value of [n] bytes at [offset], little- or big-endian. */
function readUnsigned(buf, offset, n, le) {
  validateNumber(offset, 'offset');
  const first = buf[offset];
  const last = buf[offset + n - 1];
  if (first === undefined || last === undefined) boundsError(offset, buf.length - n);
  let v = 0;
  if (le) {
    for (let i = n - 1; i >= 0; i--) v = v * 256 + buf[offset + i];
  } else {
    for (let i = 0; i < n; i++) v = v * 256 + buf[offset + i];
  }
  return v;
}

function readSigned(buf, offset, n, le) {
  const v = readUnsigned(buf, offset, n, le);
  const limit = 2 ** (8 * n - 1);
  return v >= limit ? v - limit * 2 : v;
}

function checkByteLength(byteLength) {
  if (!(byteLength >= 1 && byteLength <= 6) || Math.floor(byteLength) !== byteLength) boundsError(byteLength, 6, 'byteLength');
}

function writeBytes(buf, value, offset, n, min, max, le) {
  value = +value;
  if (n === 1) {
    validateNumber(offset, 'offset');
    if (value > max || value < min) throw ERR_OUT_OF_RANGE('value', `>= ${min} and <= ${max}`, value);
    if (buf[offset] === undefined) boundsError(offset, buf.length - 1);
    buf[offset] = value;
    return offset + 1;
  }
  checkInt(value, min, max, buf, offset, n - 1);
  // the low 32 bits come from the value (ToUint32 wraps negatives), the bits above from its floor / 2^32
  const hi = Math.floor(value * 2 ** -32);
  for (let i = 0; i < n; i++) {
    const byte = i < 4 ? value >>> (8 * i) : hi >>> (8 * (i - 4));
    buf[le ? offset + i : offset + n - 1 - i] = byte;
  }
  return offset + n;
}

const UMAX = [0, 0xff, 0xffff, 0xffffff, 0xffffffff, 0xffffffffff, 0xffffffffffff];

const float32Array = new Float32Array(1);
const uInt8Float32Array = new Uint8Array(float32Array.buffer);
const float64Array = new Float64Array(1);
const uInt8Float64Array = new Uint8Array(float64Array.buffer);
float32Array[0] = -1;
const bigEndian = uInt8Float32Array[3] === 0;

function readFloatWith(buf, offset, scratch, bytes, n, le) {
  validateNumber(offset, 'offset');
  const first = buf[offset];
  const last = buf[offset + n - 1];
  if (first === undefined || last === undefined) boundsError(offset, buf.length - n);
  const reverse = le === bigEndian;
  for (let i = 0; i < n; i++) bytes[reverse ? n - 1 - i : i] = buf[offset + i];
  return scratch[0];
}

function writeFloatWith(buf, val, offset, scratch, bytes, n, le) {
  val = +val;
  checkBounds(buf, offset, n - 1);
  scratch[0] = val;
  const reverse = le === bigEndian;
  for (let i = 0; i < n; i++) buf[offset + i] = bytes[reverse ? n - 1 - i : i];
  return offset + n;
}

function readBig64(buf, offset, le, signed) {
  validateNumber(offset, 'offset');
  const first = buf[offset];
  const last = buf[offset + 7];
  if (first === undefined || last === undefined) boundsError(offset, buf.length - 8);
  const lo = readUnsigned(buf, le ? offset : offset + 4, 4, le);
  const hi = readUnsigned(buf, le ? offset + 4 : offset, 4, le);
  const v = (BigInt(hi) << 32n) + BigInt(lo);
  return signed ? BigInt.asIntN(64, v) : v;
}

function writeBig64(buf, value, offset, le, min, max) {
  checkInt(value, min, max, buf, offset, 7);
  const lo = Number(value & 0xffffffffn);
  const hi = Number((value >> 32n) & 0xffffffffn);
  for (let i = 0; i < 4; i++) {
    buf[le ? offset + i : offset + 7 - i] = lo >>> (8 * i);
    buf[le ? offset + 4 + i : offset + 3 - i] = hi >>> (8 * i);
  }
  return offset + 8;
}

const proto = Buffer.prototype;
const methods = {
  readUInt8(offset = 0) { return readUnsigned(this, offset, 1, true); },
  readUInt16LE(offset = 0) { return readUnsigned(this, offset, 2, true); },
  readUInt16BE(offset = 0) { return readUnsigned(this, offset, 2, false); },
  readUInt32LE(offset = 0) { return readUnsigned(this, offset, 4, true); },
  readUInt32BE(offset = 0) { return readUnsigned(this, offset, 4, false); },
  readUIntLE(offset, byteLength) {
    if (offset === undefined) throw ERR_INVALID_ARG_TYPE('offset', 'number', offset);
    checkByteLength(byteLength);
    return readUnsigned(this, offset, byteLength, true);
  },
  readUIntBE(offset, byteLength) {
    if (offset === undefined) throw ERR_INVALID_ARG_TYPE('offset', 'number', offset);
    checkByteLength(byteLength);
    return readUnsigned(this, offset, byteLength, false);
  },
  readInt8(offset = 0) { return readSigned(this, offset, 1, true); },
  readInt16LE(offset = 0) { return readSigned(this, offset, 2, true); },
  readInt16BE(offset = 0) { return readSigned(this, offset, 2, false); },
  readInt32LE(offset = 0) { return readSigned(this, offset, 4, true); },
  readInt32BE(offset = 0) { return readSigned(this, offset, 4, false); },
  readIntLE(offset, byteLength) {
    if (offset === undefined) throw ERR_INVALID_ARG_TYPE('offset', 'number', offset);
    checkByteLength(byteLength);
    return readSigned(this, offset, byteLength, true);
  },
  readIntBE(offset, byteLength) {
    if (offset === undefined) throw ERR_INVALID_ARG_TYPE('offset', 'number', offset);
    checkByteLength(byteLength);
    return readSigned(this, offset, byteLength, false);
  },
  readBigUInt64LE(offset = 0) { return readBig64(this, offset, true, false); },
  readBigUInt64BE(offset = 0) { return readBig64(this, offset, false, false); },
  readBigInt64LE(offset = 0) { return readBig64(this, offset, true, true); },
  readBigInt64BE(offset = 0) { return readBig64(this, offset, false, true); },
  readFloatLE(offset = 0) { return readFloatWith(this, offset, float32Array, uInt8Float32Array, 4, true); },
  readFloatBE(offset = 0) { return readFloatWith(this, offset, float32Array, uInt8Float32Array, 4, false); },
  readDoubleLE(offset = 0) { return readFloatWith(this, offset, float64Array, uInt8Float64Array, 8, true); },
  readDoubleBE(offset = 0) { return readFloatWith(this, offset, float64Array, uInt8Float64Array, 8, false); },

  writeUInt8(value, offset = 0) { return writeBytes(this, value, offset, 1, 0, 0xff, true); },
  writeUInt16LE(value, offset = 0) { return writeBytes(this, value, offset, 2, 0, 0xffff, true); },
  writeUInt16BE(value, offset = 0) { return writeBytes(this, value, offset, 2, 0, 0xffff, false); },
  writeUInt32LE(value, offset = 0) { return writeBytes(this, value, offset, 4, 0, 0xffffffff, true); },
  writeUInt32BE(value, offset = 0) { return writeBytes(this, value, offset, 4, 0, 0xffffffff, false); },
  writeUIntLE(value, offset, byteLength) {
    checkByteLength(byteLength);
    return writeBytes(this, value, offset, byteLength, 0, UMAX[byteLength], true);
  },
  writeUIntBE(value, offset, byteLength) {
    checkByteLength(byteLength);
    return writeBytes(this, value, offset, byteLength, 0, UMAX[byteLength], false);
  },
  writeInt8(value, offset = 0) { return writeBytes(this, value, offset, 1, -0x80, 0x7f, true); },
  writeInt16LE(value, offset = 0) { return writeBytes(this, value, offset, 2, -0x8000, 0x7fff, true); },
  writeInt16BE(value, offset = 0) { return writeBytes(this, value, offset, 2, -0x8000, 0x7fff, false); },
  writeInt32LE(value, offset = 0) { return writeBytes(this, value, offset, 4, -0x80000000, 0x7fffffff, true); },
  writeInt32BE(value, offset = 0) { return writeBytes(this, value, offset, 4, -0x80000000, 0x7fffffff, false); },
  writeIntLE(value, offset, byteLength) {
    checkByteLength(byteLength);
    const limit = 2 ** (8 * byteLength - 1);
    return writeBytes(this, value, offset, byteLength, -limit, limit - 1, true);
  },
  writeIntBE(value, offset, byteLength) {
    checkByteLength(byteLength);
    const limit = 2 ** (8 * byteLength - 1);
    return writeBytes(this, value, offset, byteLength, -limit, limit - 1, false);
  },
  writeBigUInt64LE(value, offset = 0) { return writeBig64(this, value, offset, true, 0n, 0xffffffffffffffffn); },
  writeBigUInt64BE(value, offset = 0) { return writeBig64(this, value, offset, false, 0n, 0xffffffffffffffffn); },
  writeBigInt64LE(value, offset = 0) { return writeBig64(this, value, offset, true, -(2n ** 63n), 2n ** 63n - 1n); },
  writeBigInt64BE(value, offset = 0) { return writeBig64(this, value, offset, false, -(2n ** 63n), 2n ** 63n - 1n); },
  writeFloatLE(val, offset = 0) { return writeFloatWith(this, val, offset, float32Array, uInt8Float32Array, 4, true); },
  writeFloatBE(val, offset = 0) { return writeFloatWith(this, val, offset, float32Array, uInt8Float32Array, 4, false); },
  writeDoubleLE(val, offset = 0) { return writeFloatWith(this, val, offset, float64Array, uInt8Float64Array, 8, true); },
  writeDoubleBE(val, offset = 0) { return writeFloatWith(this, val, offset, float64Array, uInt8Float64Array, 8, false); },
};
for (const name of Object.keys(methods)) {
  Object.defineProperty(proto, name, { value: methods[name], writable: true, configurable: true, enumerable: false });
  // readUint8 for readUInt8 and so on, as Node has them
  if (name.includes('UInt')) {
    Object.defineProperty(proto, name.replace('UInt', 'Uint'), { value: methods[name], writable: true, configurable: true, enumerable: false });
  }
}

// asciiSlice, hexWrite...: the encodings' own methods
for (let e = 0; e < ENCODING_NAMES.length; e++) {
  const name = ENCODING_NAMES[e] === 'utf16le' ? 'ucs2' : ENCODING_NAMES[e];
  Object.defineProperty(proto, `${name}Slice`, {
    value: function slice(start, end) {
      return native.toString(this, e, start === undefined ? 0 : start, end === undefined ? this.length : end);
    },
    writable: true,
    configurable: true,
    enumerable: false,
  });
  Object.defineProperty(proto, `${name}Write`, {
    value: function write(string, offset = 0, length = this.length - offset) {
      validateString(string, 'argument');
      if (offset < 0 || offset > this.length) throw ERR_BUFFER_OUT_OF_BOUNDS('offset');
      return native.write(this, string, e, offset, length);
    },
    writable: true,
    configurable: true,
    enumerable: false,
  });
}

// ---------------------------------------------------------------- the rest of the prototype

function adjustOffset(offset, length) {
  offset = Math.trunc(offset);
  if (offset === 0 || offset !== offset) return 0;
  if (offset < 0) {
    offset += length;
    return offset > 0 ? offset : 0;
  }
  if (offset < length) return offset;
  return length;
}

function defineMethods(target, obj) {
  for (const name of Reflect.ownKeys(obj)) {
    Object.defineProperty(target, name, { value: obj[name], writable: true, configurable: true, enumerable: false });
  }
}

defineMethods(proto, {
  toString(encoding, start, end) {
    if (arguments.length === 0) return native.toString(this, UTF8, 0, this.length);
    const len = this.length;
    if (start <= 0) start = 0;
    else if (start >= len) return '';
    else start = Math.trunc(start) || 0;
    if (end === undefined || end > len) end = len;
    else end = Math.trunc(end) || 0;
    if (end <= start) return '';
    if (encoding === undefined) return native.toString(this, UTF8, start, end);
    const e = encodingIndex(encoding);
    if (e < 0) throw ERR_UNKNOWN_ENCODING(encoding);
    return native.toString(this, e, start, end);
  },

  equals(otherBuffer) {
    if (!isUint8Array(otherBuffer)) throw ERR_INVALID_ARG_TYPE('otherBuffer', ['Buffer', 'Uint8Array'], otherBuffer);
    if (this === otherBuffer) return true;
    const len = TypedArrayPrototypeGetByteLength(this);
    if (len !== TypedArrayPrototypeGetByteLength(otherBuffer)) return false;
    return len === 0 || native.compare(this, 0, len, otherBuffer, 0, len) === 0;
  },

  [customInspectSymbol](recurseTimes, ctx) {
    const max = INSPECT_MAX_BYTES;
    const actualMax = Math.min(max, this.length);
    const remaining = this.length - max;
    let str = native.toString(this, HEX, 0, actualMax).replace(/(.{2})/g, '$1 ').trim();
    if (remaining > 0) str += ` ... ${remaining} more byte${remaining > 1 ? 's' : ''}`;
    if (ctx) {
      let extras = false;
      const obj = { __proto__: null };
      for (const key of types.ownNonIndexKeys(this, ctx.showHidden)) {
        extras = true;
        obj[key] = this[key];
      }
      if (extras) {
        if (this.length !== 0) str += ', ';
        // '[Object: null prototype] {'.length === 26
        str += require('util').inspect(obj, { ...ctx, breakLength: Infinity, compact: true }).slice(27, -2);
      }
    }
    return `<${this.constructor.name} ${str}>`;
  },

  compare(target, targetStart, targetEnd, sourceStart, sourceEnd) {
    if (!isUint8Array(target)) throw ERR_INVALID_ARG_TYPE('target', ['Buffer', 'Uint8Array'], target);
    if (arguments.length === 1) return native.compare(this, 0, this.length, target, 0, target.length);
    if (targetStart === undefined) targetStart = 0;
    else validateOffset(targetStart, 'targetStart');
    if (targetEnd === undefined) targetEnd = target.length;
    else validateOffset(targetEnd, 'targetEnd', 0, target.length);
    if (sourceStart === undefined) sourceStart = 0;
    else validateOffset(sourceStart, 'sourceStart');
    if (sourceEnd === undefined) sourceEnd = this.length;
    else validateOffset(sourceEnd, 'sourceEnd', 0, this.length);
    if (sourceStart >= sourceEnd) return targetStart >= targetEnd ? 0 : -1;
    if (targetStart >= targetEnd) return 1;
    if (sourceStart > this.length) throw ERR_OUT_OF_RANGE('sourceStart', `<= ${this.length}`, sourceStart);
    if (targetStart > target.length) throw ERR_OUT_OF_RANGE('targetStart', `<= ${target.length}`, targetStart);
    return native.compare(this, sourceStart, sourceEnd, target, targetStart, targetEnd);
  },

  indexOf(val, byteOffset, encoding) {
    return bidirectionalIndexOf(this, val, byteOffset, encoding, true);
  },

  lastIndexOf(val, byteOffset, encoding) {
    return bidirectionalIndexOf(this, val, byteOffset, encoding, false);
  },

  includes(val, byteOffset, encoding) {
    return this.indexOf(val, byteOffset, encoding) !== -1;
  },

  fill(value, offset, end, encoding) {
    return _fill(this, value, offset, end, encoding);
  },

  write(string, offset, length, encoding) {
    if (offset === undefined) {
      validateString(string, 'argument');
      return native.write(this, string, UTF8, 0, this.length);
    }
    if (length === undefined && typeof offset === 'string') {
      encoding = offset;
      length = this.length;
      offset = 0;
    } else {
      validateOffset(offset, 'offset', 0, this.length);
      const remaining = this.length - offset;
      if (length === undefined) {
        length = remaining;
      } else if (typeof length === 'string') {
        encoding = length;
        length = remaining;
      } else {
        validateOffset(length, 'length', 0, this.length);
        if (length > remaining) length = remaining;
      }
    }
    validateString(string, 'argument');
    const e = !encoding ? UTF8 : encodingIndex(encoding);
    if (e < 0) throw ERR_UNKNOWN_ENCODING(encoding);
    return native.write(this, string, e, offset, length);
  },

  toJSON() {
    const data = new Array(this.length);
    for (let i = 0; i < this.length; ++i) data[i] = this[i];
    return { type: 'Buffer', data };
  },

  subarray(start, end) {
    const srcLength = this.length;
    start = adjustOffset(start, srcLength);
    end = end !== undefined ? adjustOffset(end, srcLength) : srcLength;
    const newLength = end > start ? end - start : 0;
    return new FastBuffer(TypedArrayPrototypeGetBuffer(this), TypedArrayPrototypeGetByteOffset(this) + start, newLength);
  },

  // a view of the same memory, as Node's (unlike Uint8Array's slice, which copies)
  slice(start, end) {
    return this.subarray(start, end);
  },

  swap16() {
    const len = this.length;
    if (len % 2 !== 0) throw ERR_INVALID_BUFFER_SIZE('16-bits');
    for (let i = 0; i < len; i += 2) swap(this, i, i + 1);
    return this;
  },

  swap32() {
    const len = this.length;
    if (len % 4 !== 0) throw ERR_INVALID_BUFFER_SIZE('32-bits');
    for (let i = 0; i < len; i += 4) {
      swap(this, i, i + 3);
      swap(this, i + 1, i + 2);
    }
    return this;
  },

  swap64() {
    const len = this.length;
    if (len % 8 !== 0) throw ERR_INVALID_BUFFER_SIZE('64-bits');
    for (let i = 0; i < len; i += 8) {
      swap(this, i, i + 7);
      swap(this, i + 1, i + 6);
      swap(this, i + 2, i + 5);
      swap(this, i + 3, i + 4);
    }
    return this;
  },

  copy(target, targetStart, sourceStart, sourceEnd) {
    return _copy(this, target, targetStart, sourceStart, sourceEnd);
  },
});
Object.defineProperty(proto, 'toLocaleString', { value: proto.toString, writable: true, configurable: true, enumerable: false });
Object.defineProperty(proto, 'inspect', { value: proto[customInspectSymbol], writable: true, configurable: true, enumerable: false });
Object.defineProperty(proto, 'parent', {
  enumerable: true,
  configurable: true,
  get() { return this instanceof Buffer ? this.buffer : undefined; },
});
Object.defineProperty(proto, 'offset', {
  enumerable: true,
  configurable: true,
  get() { return this instanceof Buffer ? this.byteOffset : undefined; },
});

function swap(b, n, m) {
  const i = b[n];
  b[n] = b[m];
  b[m] = i;
}

// ---------------------------------------------------------------- the module's other exports

function isUtf8(input) {
  if (isTypedArray(input)) return native.isUtf8(new Uint8Array(input.buffer, input.byteOffset, input.byteLength));
  if (isAnyArrayBuffer(input)) return native.isUtf8(new Uint8Array(input));
  throw ERR_INVALID_ARG_TYPE('input', ['ArrayBuffer', 'Buffer', 'TypedArray'], input);
}

function isAscii(input) {
  if (isTypedArray(input)) return native.isAscii(new Uint8Array(input.buffer, input.byteOffset, input.byteLength));
  if (isAnyArrayBuffer(input)) return native.isAscii(new Uint8Array(input));
  throw ERR_INVALID_ARG_TYPE('input', ['ArrayBuffer', 'Buffer', 'TypedArray'], input);
}

const TRANSCODE = ['ascii', 'latin1', 'utf16le', 'utf8'];
function transcode(source, fromEncoding, toEncoding) {
  if (!isUint8Array(source)) throw ERR_INVALID_ARG_TYPE('source', ['Buffer', 'Uint8Array'], source);
  if (source.length === 0) return Buffer.alloc(0);
  const from = normalizeEncoding(fromEncoding) || fromEncoding;
  const to = normalizeEncoding(toEncoding) || toEncoding;
  if (!TRANSCODE.includes(from) || !TRANSCODE.includes(to)) {
    const err = new Error(`Unable to transcode Buffer [U_ILLEGAL_ARGUMENT_ERROR]`);
    err.code = 'U_ILLEGAL_ARGUMENT_ERROR';
    err.errno = 1;
    throw err;
  }
  let text = Buffer.prototype.toString.call(source, from);
  // what the target cannot hold becomes '?', as ICU's converters do
  if (to === 'ascii') text = text.replace(/[^\x00-\x7f]/g, '?');
  else if (to === 'latin1') text = text.replace(/[^\x00-\xff]/gu, '?');
  return fromString(text, to);
}

module.exports = {
  Buffer,
  SlowBuffer,
  transcode,
  isUtf8,
  isAscii,
  kMaxLength,
  kStringMaxLength,
  btoa: globalThis.btoa,
  atob: globalThis.atob,
  Blob: globalThis.Blob,
  File: globalThis.File,
  resolveObjectURL() { return undefined; },
};
Object.defineProperties(module.exports, {
  constants: {
    configurable: false,
    enumerable: true,
    value: Object.freeze({ MAX_LENGTH: kMaxLength, MAX_STRING_LENGTH: kStringMaxLength }),
  },
  INSPECT_MAX_BYTES: {
    configurable: true,
    enumerable: true,
    get() { return INSPECT_MAX_BYTES; },
    set(val) {
      validateNumber(val, 'INSPECT_MAX_BYTES', 0);
      INSPECT_MAX_BYTES = val;
    },
  },
});
