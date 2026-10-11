'use strict';
// node:util. inspect and format are internal/inspect's; types are the engine's own checks (binding.types);
// TextEncoder and TextDecoder are the web globals. Not here yet: parseArgs, parseEnv, MIMEType, getCallSites.

const {
  codes: { ERR_INVALID_ARG_TYPE, ERR_INVALID_ARG_VALUE, ERR_FALSY_VALUE_REJECTION, ERR_OUT_OF_RANGE },
  validateFunction, validateString, validateObject, validateAbortSignal, validateNumber, validateBoolean,
} = binding.internal('errors');
const { inspect, format, formatWithOptions, stripVTControlCharacters, getStringWidth } = binding.internal('inspect');
const { isDeepStrictEqual } = binding.internal('comparisons');
const typeChecks = binding.types;

let processModule;
function lazyProcess() {
  if (processModule === undefined) processModule = require('process');
  return processModule;
}

// util.types: the is* checks of binding.types
const types = {};
for (const name of Object.getOwnPropertyNames(typeChecks)) {
  if (name.startsWith('is')) types[name] = typeChecks[name];
}
Object.freeze(types);

// ---------------------------------------------------------------- promisify / callbackify

const kCustomPromisifiedSymbol = Symbol.for('nodejs.util.promisify.custom');
const kCustomPromisifyArgsSymbol = Symbol('customPromisifyArgs');

function promisify(original) {
  validateFunction(original, 'original');
  if (original[kCustomPromisifiedSymbol]) {
    const fn = original[kCustomPromisifiedSymbol];
    validateFunction(fn, 'util.promisify.custom');
    return Object.defineProperty(fn, kCustomPromisifiedSymbol, { value: fn, enumerable: false, writable: false, configurable: true });
  }
  // names for the values of a callback given several, e.g. ['bytesRead', 'buffer']
  const argumentNames = original[kCustomPromisifyArgsSymbol];
  function fn(...args) {
    return new Promise((resolve, reject) => {
      args.push((err, ...values) => {
        if (err) return reject(err);
        if (argumentNames !== undefined && values.length > 1) {
          const obj = {};
          for (let i = 0; i < argumentNames.length; i++) obj[argumentNames[i]] = values[i];
          resolve(obj);
        } else {
          resolve(values[0]);
        }
      });
      if (typeChecks.isPromise(Reflect.apply(original, this, args))) {
        lazyProcess().emitWarning('Calling promisify on a function that returns a Promise is likely a mistake.',
          'DeprecationWarning', 'DEP0174');
      }
    });
  }
  Object.setPrototypeOf(fn, Object.getPrototypeOf(original));
  Object.defineProperty(fn, kCustomPromisifiedSymbol, { value: fn, enumerable: false, writable: false, configurable: true });
  const descriptors = Object.getOwnPropertyDescriptors(original);
  for (const d of Object.values(descriptors)) Object.setPrototypeOf(d, null);
  return Object.defineProperties(fn, descriptors);
}
promisify.custom = kCustomPromisifiedSymbol;

function callbackifyOnRejected(reason, cb) {
  // a falsy rejection is wrapped, so the callback can tell it from success
  if (!reason) {
    const err = ERR_FALSY_VALUE_REJECTION();
    err.reason = reason;
    reason = err;
  }
  return cb(reason);
}

function callbackify(original) {
  validateFunction(original, 'original');
  function callbackified(...args) {
    const maybeCb = args.pop();
    validateFunction(maybeCb, 'last argument');
    const cb = maybeCb.bind(this);
    const process = lazyProcess();
    Reflect.apply(original, this, args).then(
      (ret) => process.nextTick(cb, null, ret),
      (rej) => process.nextTick(callbackifyOnRejected, rej, cb),
    );
  }
  const descriptors = Object.getOwnPropertyDescriptors(original);
  if (typeof descriptors.length.value === 'number') descriptors.length.value++;
  if (typeof descriptors.name.value === 'string') descriptors.name.value += 'Callbackified';
  for (const d of Object.values(descriptors)) Object.setPrototypeOf(d, null);
  Object.defineProperties(callbackified, descriptors);
  return callbackified;
}

// ---------------------------------------------------------------- deprecate, debuglog

const codesWarned = new Set();

function deprecate(fn, msg, code) {
  const process = lazyProcess();
  if (process.noDeprecation === true) return fn;
  if (code !== undefined) validateString(code, 'code');
  let warned = false;
  function deprecated(...args) {
    if (!warned && !process.noDeprecation) {
      warned = true;
      if (code !== undefined) {
        if (!codesWarned.has(code)) {
          process.emitWarning(msg, 'DeprecationWarning', code, deprecated);
          codesWarned.add(code);
        }
      } else {
        process.emitWarning(msg, 'DeprecationWarning', deprecated);
      }
    }
    if (new.target) return Reflect.construct(fn, args, new.target);
    return Reflect.apply(fn, this, args);
  }
  Object.setPrototypeOf(deprecated, fn);
  if (fn.prototype) deprecated.prototype = fn.prototype;
  return deprecated;
}

let debugImpls;
let testEnabled;
function initializeDebugEnv() {
  debugImpls = { __proto__: null };
  let debugEnv = lazyProcess().env.NODE_DEBUG;
  if (debugEnv) {
    debugEnv = debugEnv.replace(/[|\\{}()[\]^$+?.]/g, '\\$&').replaceAll('*', '.*').replaceAll(',', '$|^');
    const debugEnvRegex = new RegExp(`^${debugEnv}$`, 'i');
    testEnabled = (str) => debugEnvRegex.exec(str) !== null;
  } else {
    testEnabled = () => false;
  }
}

function debuglogImpl(enabled, set) {
  if (debugImpls[set] === undefined) {
    if (enabled) {
      const process = lazyProcess();
      debugImpls[set] = function debug(...args) {
        process.stderr.write(format('%s %s: %s\n', set, process.pid, formatWithOptions({}, ...args)));
      };
    } else {
      debugImpls[set] = function debug() {};
    }
  }
  return debugImpls[set];
}

// a logger that writes to stderr when NODE_DEBUG (in NodeOptions.env) names its section
function debuglog(set, cb) {
  if (debugImpls === undefined) initializeDebugEnv();
  let enabled;
  function init() {
    set = set.toUpperCase();
    enabled = testEnabled(set);
  }
  let debug = (...args) => {
    init();
    debug = debuglogImpl(enabled, set);
    if (typeof cb === 'function') cb(debug);
    debug(...args);
  };
  let test = () => {
    init();
    test = () => enabled;
    return enabled;
  };
  const logger = (...args) => debug(...args);
  Object.defineProperty(logger, 'enabled', {
    get() {
      return test();
    },
    configurable: true,
    enumerable: true,
  });
  return logger;
}

// ---------------------------------------------------------------- the rest

function inherits(ctor, superCtor) {
  if (ctor === undefined || ctor === null) throw ERR_INVALID_ARG_TYPE('ctor', 'Function', ctor);
  if (superCtor === undefined || superCtor === null) throw ERR_INVALID_ARG_TYPE('superCtor', 'Function', superCtor);
  if (superCtor.prototype === undefined) throw ERR_INVALID_ARG_TYPE('superCtor.prototype', 'Object', superCtor.prototype);
  Object.defineProperty(ctor, 'super_', { value: superCtor, writable: true, configurable: true });
  Object.setPrototypeOf(ctor.prototype, superCtor.prototype);
}

function _extend(target, source) {
  if (source === null || typeof source !== 'object') return target;
  const keys = Object.keys(source);
  let i = keys.length;
  while (i--) target[keys[i]] = source[keys[i]];
  return target;
}

function toUSVString(input) {
  return `${input}`.toWellFormed();
}

function styleText(format, text, { validateStream = true, stream } = {}) {
  validateString(text, 'text');
  validateBoolean(validateStream, 'options.validateStream');
  let skipColorize = false;
  if (validateStream) {
    // a stream that is no terminal gets the text as it is (unless FORCE_COLOR is set), as in Node
    const process = lazyProcess();
    const s = stream === undefined ? process.stdout : stream;
    const force = process.env.FORCE_COLOR;
    skipColorize = force !== undefined ? force === '0' || force === 'false' : !(s && s.isTTY);
  }
  const formats = Array.isArray(format) ? format : [format];
  let left = '';
  let right = '';
  for (const key of formats) {
    const code = inspect.colors[key];
    if (code == null) {
      const allowed = Object.keys(inspect.colors).map((v) => `'${v}'`).join(', ');
      throw ERR_INVALID_ARG_VALUE('format', key, `must be one of: ${allowed}`);
    }
    if (skipColorize) continue;
    left += `\u001b[${code[0]}m`;
    right = `\u001b[${code[1]}m${right}`;
  }
  return skipColorize ? text : `${left}${text}${right}`;
}

function aborted(signal, resource) {
  if (signal === undefined) return Promise.reject(ERR_INVALID_ARG_TYPE('signal', 'AbortSignal', signal));
  try {
    validateAbortSignal(signal, 'signal');
    validateObject(resource, 'resource', { nullable: false, allowFunction: true, allowArray: true });
  } catch (e) {
    return Promise.reject(e);
  }
  if (signal.aborted) return Promise.resolve();
  return new Promise((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }));
}

// the errno names of libuv (Linux numbering), for getSystemErrorName / getSystemErrorMap
const ERRNO = [
  [-1, 'EPERM', 'operation not permitted'], [-2, 'ENOENT', 'no such file or directory'],
  [-4, 'EINTR', 'interrupted system call'], [-5, 'EIO', 'i/o error'], [-9, 'EBADF', 'bad file descriptor'],
  [-11, 'EAGAIN', 'resource temporarily unavailable'], [-12, 'ENOMEM', 'not enough memory'],
  [-13, 'EACCES', 'permission denied'], [-16, 'EBUSY', 'resource busy or locked'], [-17, 'EEXIST', 'file already exists'],
  [-20, 'ENOTDIR', 'not a directory'], [-21, 'EISDIR', 'illegal operation on a directory'],
  [-22, 'EINVAL', 'invalid argument'], [-24, 'EMFILE', 'too many open files'], [-28, 'ENOSPC', 'no space left on device'],
  [-32, 'EPIPE', 'broken pipe'], [-38, 'ENOSYS', 'function not implemented'], [-39, 'ENOTEMPTY', 'directory not empty'],
  [-98, 'EADDRINUSE', 'address already in use'], [-99, 'EADDRNOTAVAIL', 'address not available'],
  [-101, 'ENETUNREACH', 'network is unreachable'], [-103, 'ECONNABORTED', 'software caused connection abort'],
  [-104, 'ECONNRESET', 'connection reset by peer'], [-107, 'ENOTCONN', 'socket is not connected'],
  [-110, 'ETIMEDOUT', 'connection timed out'], [-111, 'ECONNREFUSED', 'connection refused'],
  [-113, 'EHOSTUNREACH', 'host is unreachable'], [-125, 'ECANCELED', 'operation canceled'],
  [-3008, 'ENOTFOUND', 'getaddrinfo ENOTFOUND'], [-4095, 'EOF', 'end of file'],
];

function getSystemErrorMap() {
  return new Map(ERRNO.map(([errno, name, message]) => [errno, [name, message]]));
}

function getSystemErrorName(err) {
  validateNumber(err, 'err');
  if (err >= 0 || !Number.isSafeInteger(err)) throw ERR_OUT_OF_RANGE('err', 'a negative integer', err);
  const entry = ERRNO.find((e) => e[0] === err);
  return entry ? entry[1] : `Unknown system error ${err}`;
}

function getSystemErrorMessage(err) {
  validateNumber(err, 'err');
  if (err >= 0 || !Number.isSafeInteger(err)) throw ERR_OUT_OF_RANGE('err', 'a negative integer', err);
  const entry = ERRNO.find((e) => e[0] === err);
  return entry ? entry[2] : `Unknown system error ${err}`;
}

module.exports = {
  _extend,
  aborted,
  callbackify,
  debug: debuglog,
  debuglog,
  deprecate,
  format,
  formatWithOptions,
  getStringWidth,
  getSystemErrorMap,
  getSystemErrorMessage,
  getSystemErrorName,
  inherits,
  inspect,
  isArray: Array.isArray,
  isDeepStrictEqual,
  promisify,
  stripVTControlCharacters,
  styleText,
  toUSVString,
  TextDecoder: globalThis.TextDecoder,
  TextEncoder: globalThis.TextEncoder,
  types,
  // deprecated since long, still in Node 22
  isBoolean: (arg) => typeof arg === 'boolean',
  isBuffer: (arg) => require('buffer').Buffer.isBuffer(arg),
  isDate: (arg) => typeChecks.isDate(arg),
  isError: (arg) => typeChecks.isNativeError(arg) || arg instanceof Error,
  isFunction: (arg) => typeof arg === 'function',
  isNull: (arg) => arg === null,
  isNullOrUndefined: (arg) => arg === null || arg === undefined,
  isNumber: (arg) => typeof arg === 'number',
  isObject: (arg) => arg !== null && typeof arg === 'object',
  isPrimitive: (arg) => arg === null || (typeof arg !== 'object' && typeof arg !== 'function'),
  isRegExp: (arg) => typeChecks.isRegExp(arg),
  isString: (arg) => typeof arg === 'string',
  isSymbol: (arg) => typeof arg === 'symbol',
  isUndefined: (arg) => arg === undefined,
};
