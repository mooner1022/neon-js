'use strict';
// The helpers of Node's lib/internal/util.js the libraries use (after Node v26.11.1; MIT license, see NOTICE).

const { isNativeError, isPromise } = require('internal/util/types');

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

function getDeprecationWarningEmitter(code, msg, deprecated, useEmitSync, shouldEmitWarning = () => true) {
  let warned = false;
  return function emitDeprecationWarning(arg) {
    if (!warned && shouldEmitWarning(arg)) {
      warned = true;
      // node:process by require (not the libraries' process parameter): it needs this module to load
      const proc = require('process');
      if (code === 'ExperimentalWarning') {
        proc.emitWarning(msg, code, deprecated);
      } else if (code !== undefined) {
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

function deprecate(fn, msg, code, useEmitSync, modifyPrototype = true) {
  if (validateString === undefined) ({ validateString } = require('internal/validators'));
  if (code !== undefined) validateString(code, 'code');
  const emitDeprecationWarning = getDeprecationWarningEmitter(code, msg, deprecated, useEmitSync);
  function deprecated(...args) {
    if (!require('process').noDeprecation) emitDeprecationWarning();
    if (new.target) return Reflect.construct(fn, args, new.target);
    return Reflect.apply(fn, this, args);
  }
  if (modifyPrototype) {
    // the wrapper keeps fn's prototype chain, and instances of fn are instances of it
    Object.setPrototypeOf(deprecated, fn);
    if (fn.prototype) deprecated.prototype = fn.prototype;
    Object.defineProperty(deprecated, 'length', { __proto__: null, ...Object.getOwnPropertyDescriptor(fn, 'length') });
  }
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

// a data property as an assignment would make it, whatever the prototype chain has
function setOwnProperty(obj, key, value) {
  Object.defineProperty(obj, key, { __proto__: null, configurable: true, enumerable: true, value, writable: true });
  return value;
}

// names fn ([description] for a symbol), keeping the rest of its name's descriptor unless given
function assignFunctionName(name, fn, descriptor = kEmptyObject) {
  if (typeof name !== 'string') {
    const description = name.description;
    if (description === undefined) throw new Error('Attempted to name function after descriptionless Symbol');
    name = `[${description}]`;
  }
  return Object.defineProperty(fn, 'name', {
    __proto__: null,
    writable: false,
    enumerable: false,
    configurable: true,
    ...Object.getOwnPropertyDescriptor(fn, 'name'),
    ...descriptor,
    value: name,
  });
}

// a constructor called without new: deprecated (code), then constructed
function deprecateInstantiation(target, code, ...args) {
  getDeprecationWarningEmitter(code, `Instantiating ${target.name} without the 'new' keyword has been deprecated.`, target)();
  return Reflect.construct(target, args);
}

const kCustomPromisifiedSymbol = Symbol.for('nodejs.util.promisify.custom');
let validateFunction;

// a callback-taking function as one returning a promise (util.promisify)
function promisify(original) {
  if (validateFunction === undefined) ({ validateFunction } = require('internal/validators'));
  validateFunction(original, 'original');
  if (original[kCustomPromisifiedSymbol]) {
    const fn = original[kCustomPromisifiedSymbol];
    validateFunction(fn, 'util.promisify.custom');
    return Object.defineProperty(fn, kCustomPromisifiedSymbol, {
      __proto__: null, value: fn, enumerable: false, writable: false, configurable: true,
    });
  }
  // names for the values of a callback given several, e.g. ['bytesRead', 'buffer']
  const argumentNames = original[customPromisifyArgs];
  function fn(...args) {
    return new Promise((resolve, reject) => {
      args[args.length] = (err, ...values) => {
        if (err) return reject(err);
        if (argumentNames !== undefined && values.length > 1) {
          const obj = {};
          for (let i = 0; i < argumentNames.length; i++) obj[argumentNames[i]] = values[i];
          resolve(obj);
        } else {
          resolve(values[0]);
        }
      };
      if (isPromise(Reflect.apply(original, this, args))) {
        require('process').emitWarning('Calling promisify on a function that returns a Promise is likely a mistake.',
          'DeprecationWarning', 'DEP0174');
      }
    });
  }
  Object.setPrototypeOf(fn, Object.getPrototypeOf(original));
  Object.defineProperty(fn, kCustomPromisifiedSymbol, {
    __proto__: null, value: fn, enumerable: false, writable: false, configurable: true,
  });
  const descriptors = Object.getOwnPropertyDescriptors(original);
  const values = Object.values(descriptors);
  // null-prototype descriptors: Object.prototype may have been changed
  for (let i = 0; i < values.length; i++) Object.setPrototypeOf(values[i], null);
  return Object.defineProperties(fn, descriptors);
}
promisify.custom = kCustomPromisifiedSymbol;

// properties of target that load the module id when first read (Node's native defineLazyProperties)
function defineLazyProperties(target, id, keys, enumerable = true) {
  const descriptors = { __proto__: null };
  let mod;
  for (let i = 0; i < keys.length; i++) {
    const key = keys[i];
    let lazyLoadedValue;
    function set(value) {
      Object.defineProperty(target, key, { __proto__: null, writable: true, value });
    }
    Object.defineProperty(set, 'name', { __proto__: null, value: `set ${key}` });
    function get() {
      mod ??= require(id);
      if (lazyLoadedValue === undefined) {
        lazyLoadedValue = mod[key];
        set(lazyLoadedValue);
      }
      return lazyLoadedValue;
    }
    Object.defineProperty(get, 'name', { __proto__: null, value: `get ${key}` });
    descriptors[key] = { __proto__: null, configurable: true, enumerable, get, set };
  }
  Object.defineProperties(target, descriptors);
}

// libuv's names and messages of errors (internal/uv_errmap)
function getSystemErrorMap() {
  return require('internal/uv_errmap').getErrorMap();
}

function getSystemErrorName(err) {
  const entry = require('internal/errors').uvErrmapGet(err);
  return entry ? entry[0] : `Unknown system error ${err}`;
}

function getSystemErrorMessage(err) {
  return require('internal/uv_errmap').getErrorMessage(err);
}

let validateOneOf;

// the exit code of a process ended by the signal (POSIX: 128 + its number)
function convertProcessSignalToExitCode(signalCode) {
  if (validateOneOf === undefined) ({ validateOneOf } = require('internal/validators'));
  const { signals } = require('os').constants;
  validateOneOf(signalCode, 'signalCode', Object.keys(signals));
  return 128 + signals[signalCode];
}

function once(callback) {
  let called = false;
  return function (...args) {
    if (called) return;
    called = true;
    return Reflect.apply(callback, this, args);
  };
}

module.exports = {
  assignFunctionName,
  convertProcessSignalToExitCode,
  createDeferredPromise,
  customInspectSymbol,
  customPromisifyArgs,
  deprecate,
  defineLazyProperties,
  deprecateInstantiation,
  emitExperimentalWarning,
  encodingsMap,
  getDeprecationWarningEmitter,
  getLazy,
  getSystemErrorMap,
  getSystemErrorMessage,
  getSystemErrorName,
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
  setOwnProperty,
  spliceOne,
  SymbolAsyncDispose,
  SymbolDispose,
};
