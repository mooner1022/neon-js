'use strict';
// The helpers of Node's lib/internal/util.js the libraries use (after it; MIT license, see NOTICE).

const { isNativeError } = require('internal/util/types');

const kEmptyObject = Object.freeze({ __proto__: null });
const kEnumerableProperty = { __proto__: null };
kEnumerableProperty.enumerable = true;
Object.freeze(kEnumerableProperty);

const customInspectSymbol = Symbol.for('nodejs.util.inspect.custom');
const SymbolDispose = Symbol.dispose ?? Symbol.for('nodejs.dispose');
const SymbolAsyncDispose = Symbol.asyncDispose ?? Symbol.for('nodejs.asyncDispose');

// removes list[index], faster than splice for one element
function spliceOne(list, index) {
  for (; index + 1 < list.length; index++) list[index] = list[index + 1];
  list.pop();
}
const customPromisifyArgs = Symbol('customPromisifyArgs');
const kIsEncodingSymbol = Symbol('kIsEncodingSymbol');

// the encodings by the number the native string decoder keeps them as
const encodingsMap = { __proto__: null };
for (let i = 0; i < binding.stringDecoder.encodings.length; ++i) encodingsMap[binding.stringDecoder.encodings[i]] = i;

// the platform NodeOptions names (process.platform)
const isWindows = binding.platform === 'win32';
const isMacOS = binding.platform === 'darwin';

// a function that makes its value once, when first called
function getLazy(initializer) {
  let value;
  let initialized = false;
  return function () {
    if (initialized === false) {
      value = initializer();
      initialized = true;
    }
    return value;
  };
}

const experimentalWarnings = new Set();

function emitExperimentalWarning(feature, messagePrefix, code, ctor) {
  if (experimentalWarnings.has(feature)) return;
  experimentalWarnings.add(feature);
  let msg = `${feature} is an experimental feature and might change at any time`;
  if (messagePrefix) msg = messagePrefix + msg;
  require('process').emitWarning(msg, 'ExperimentalWarning', code, ctor);
}

// output joined by separator (Node's join)
function join(output, separator) {
  let str = '';
  if (output.length !== 0) {
    const lastIndex = output.length - 1;
    for (let i = 0; i < lastIndex; i++) {
      str += output[i];
      str += separator;
    }
    str += output[lastIndex];
  }
  return str;
}

const colorRegExp = /\u001b\[\d\d?m/g;

function removeColors(str) {
  return String.prototype.replace.call(str, colorRegExp, '');
}

function isError(e) {
  // an Error of another realm is still a native error
  return isNativeError(e) || e instanceof Error;
}

// the canonical name of an encoding, or undefined
function normalizeEncoding(enc) {
  if (enc == null || enc === 'utf8' || enc === 'utf-8') return 'utf8';
  switch (`${enc}`.toLowerCase()) {
    case 'utf8': case 'utf-8': return 'utf8';
    case 'ucs2': case 'ucs-2': case 'utf16le': case 'utf-16le': return 'utf16le';
    case 'latin1': case 'binary': return 'latin1';
    case 'base64': return 'base64';
    case 'base64url': return 'base64url';
    case 'hex': return 'hex';
    case 'ascii': return 'ascii';
    case '': return 'utf8';
  }
}

// each deprecation code warns once
const codesWarned = new Set();
let validateString;

function getDeprecationWarningEmitter(code, msg, deprecated) {
  let warned = false;
  return function emitDeprecationWarning() {
    if (!warned) {
      warned = true;
      // node:process by require (not the libraries' process parameter): it needs this module to load
      const proc = require('process');
      if (code !== undefined) {
        if (!codesWarned.has(code)) {
          proc.emitWarning(msg, 'DeprecationWarning', code, deprecated);
          codesWarned.add(code);
        }
      } else {
        proc.emitWarning(msg, 'DeprecationWarning', deprecated);
      }
    }
  };
}

function deprecate(fn, msg, code) {
  if (validateString === undefined) ({ validateString } = require('internal/validators'));
  if (code !== undefined) validateString(code, 'code');
  const emitDeprecationWarning = getDeprecationWarningEmitter(code, msg, deprecated);
  function deprecated(...args) {
    if (!require('process').noDeprecation) emitDeprecationWarning();
    if (new.target) return Reflect.construct(fn, args, new.target);
    return Reflect.apply(fn, this, args);
  }
  // the wrapper keeps fn's prototype chain, and instances of fn are instances of it
  Object.setPrototypeOf(deprecated, fn);
  if (fn.prototype) deprecated.prototype = fn.prototype;
  return deprecated;
}

function createDeferredPromise() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

// node:util's promisify (loaded when called), with its symbol
function promisify(original) {
  return require('util').promisify(original);
}
promisify.custom = Symbol.for('nodejs.util.promisify.custom');

function once(callback) {
  let called = false;
  return function (...args) {
    if (called) return;
    called = true;
    return Reflect.apply(callback, this, args);
  };
}

module.exports = {
  createDeferredPromise,
  customInspectSymbol,
  customPromisifyArgs,
  deprecate,
  emitExperimentalWarning,
  encodingsMap,
  getDeprecationWarningEmitter,
  getLazy,
  isError,
  isMacOS,
  isWindows,
  join,
  kEmptyObject,
  kEnumerableProperty,
  kIsEncodingSymbol,
  normalizeEncoding,
  once,
  promisify,
  removeColors,
  spliceOne,
  SymbolAsyncDispose,
  SymbolDispose,
};
