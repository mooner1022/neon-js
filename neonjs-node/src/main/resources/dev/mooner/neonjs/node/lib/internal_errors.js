'use strict';
// Node's coded errors (ERR_*) with Node's messages, after Node's lib/internal/errors.js (MIT license, see NOTICE).
// codes.X makes the error, with or without new (codes.X.HideStackFramesError is the same, for the ported libraries);
// hideStackFrames hides a validator's own frames from the errors it throws. Libraries only: scripts cannot require
// this.

const kTypes = ['string', 'function', 'number', 'object', 'Function', 'Object', 'boolean', 'bigint', 'symbol'];
const classRegExp = /^([A-Z][a-z0-9]*)+$/;
const kIsNodeError = Symbol('kIsNodeError');

// internal/util/inspect's own inspect and format (not util's, which a script may replace); loaded on first use, as
// they need this module
let inspectModule;
function lazyInspect(v, opts) {
  if (inspectModule === undefined) inspectModule = require('internal/util/inspect');
  return inspectModule.inspect(v, opts);
}
function lazyFormat(...args) {
  if (inspectModule === undefined) inspectModule = require('internal/util/inspect');
  return inspectModule.format(...args);
}

function isErrorStackTraceLimitWritable() {
  const desc = Object.getOwnPropertyDescriptor(Error, 'stackTraceLimit');
  if (desc === undefined) return Object.isExtensible(Error);
  return Object.prototype.hasOwnProperty.call(desc, 'writable') ? desc.writable : desc.set !== undefined;
}

// the stack of a coded error again, from below stackStartFn, its first line still "TypeError [ERR_X]: message"
function captureStack(err, stackStartFn) {
  if (err !== null && typeof err === 'object' && err[kIsNodeError] === true && typeof err.code === 'string') {
    const name = err.name;
    err.name = `${name} [${err.code}]`;
    Error.captureStackTrace(err, stackStartFn);
    delete err.name;
    if (err.name !== name) err.name = name;
  } else {
    Error.captureStackTrace(err, stackStartFn);
  }
}

// an error of Base with Node's code: `code` is its own property, and its stack and toString() show the code
function makeError(Base, code, message, stackStartFn) {
  const err = new Base(message);
  Object.defineProperties(err, {
    [kIsNodeError]: { value: true, enumerable: false, writable: false, configurable: true },
    toString: {
      value() { return `${this.name} [${code}]: ${this.message}`; },
      enumerable: false,
      writable: true,
      configurable: true,
    },
  });
  err.code = code;
  captureStack(err, stackStartFn || makeError);
  return err;
}

function getMessage(code, message, args) {
  if (typeof message === 'function') return message(...args);
  if (args.length === 0) return message;
  return lazyFormat(message, ...args);
}

const codes = {};
function E(code, Base, message) {
  function NodeError(...args) {
    return makeError(Base, code, getMessage(code, message, args), NodeError);
  }
  Object.defineProperty(NodeError, 'name', { value: Base.name, configurable: true });
  NodeError.HideStackFramesError = NodeError;
  codes[code] = NodeError;
}

// a function whose own frames the errors it throws do not show (Node's validators are made so)
function hideStackFrames(fn) {
  function wrappedFn(...args) {
    try {
      return Reflect.apply(fn, this, args);
    } catch (error) {
      if (Error.stackTraceLimit) captureStack(error, wrappedFn);
      throw error;
    }
  }
  wrappedFn.withoutStackTrace = fn;
  return wrappedFn;
}

class AbortError extends Error {
  constructor(message = 'The operation was aborted', options = undefined) {
    if (options !== undefined && typeof options !== 'object') throw codes.ERR_INVALID_ARG_TYPE('options', 'Object', options);
    super(message, options);
    this.code = 'ABORT_ERR';
    this.name = 'AbortError';
  }
}

function addNumericalSeparator(val) {
  let res = '';
  let i = val.length;
  const start = val[0] === '-' ? 1 : 0;
  for (; i >= start + 4; i -= 3) res = `_${val.slice(i - 3, i)}${res}`;
  return `${val.slice(0, i)}${res}`;
}

function determineSpecificType(value) {
  if (value === null) return 'null';
  if (value === undefined) return 'undefined';
  const type = typeof value;
  switch (type) {
    case 'bigint':
      return `type bigint (${value}n)`;
    case 'number':
      if (value === 0) return 1 / value === -Infinity ? 'type number (-0)' : 'type number (0)';
      return `type number (${value})`;
    case 'boolean':
      return value ? 'type boolean (true)' : 'type boolean (false)';
    case 'symbol':
      return `type symbol (${String(value)})`;
    case 'function':
      return `function ${value.name}`;
    case 'object':
      if (value.constructor && 'name' in value.constructor) return `an instance of ${value.constructor.name}`;
      return `${lazyInspect(value, { depth: -1 })}`;
    case 'string':
      if (value.length > 28) value = `${value.slice(0, 25)}...`;
      if (value.indexOf("'") === -1) return `type string ('${value}')`;
      return `type string (${JSON.stringify(value)})`;
    default:
      value = lazyInspect(value, { colors: false });
      if (value.length > 28) value = `${value.slice(0, 25)}...`;
      return `type ${type} (${value})`;
  }
}

function listOf(items, prefix) {
  if (items.length > 2) return `${prefix}${items.slice(0, -1).join(', ')}, or ${items[items.length - 1]}`;
  if (items.length === 2) return `${prefix}${items[0]} or ${items[1]}`;
  return `${prefix}${items[0]}`;
}

E('ERR_INVALID_ARG_TYPE', TypeError, (name, expected, actual) => {
  if (!Array.isArray(expected)) expected = [expected];
  let msg = 'The ';
  if (name.endsWith(' argument')) msg += `${name} `;
  else msg += `"${name}" ${name.includes('.') ? 'property' : 'argument'} `;
  msg += 'must be ';
  const types = [];
  const instances = [];
  const other = [];
  for (const value of expected) {
    if (kTypes.includes(value)) types.push(value.toLowerCase());
    else if (classRegExp.test(value)) instances.push(value);
    else other.push(value);
  }
  if (instances.length > 0) {
    const pos = types.indexOf('object');
    if (pos !== -1) {
      types.splice(pos, 1);
      instances.push('Object');
    }
  }
  if (types.length > 0) {
    msg += types.length === 1 ? `of type ${types[0]}` : listOf(types, 'one of type ');
    if (instances.length > 0 || other.length > 0) msg += ' or ';
  }
  if (instances.length > 0) {
    msg += listOf(instances, 'an instance of ');
    if (other.length > 0) msg += ' or ';
  }
  if (other.length > 0) {
    if (other.length > 1) msg += listOf(other, 'one of ');
    else msg += (other[0].toLowerCase() !== other[0] ? 'an ' : '') + other[0];
  }
  return `${msg}. Received ${determineSpecificType(actual)}`;
});
E('ERR_INVALID_ARG_VALUE', TypeError, (name, value, reason = 'is invalid') => {
  let inspected = lazyInspect(value);
  if (inspected.length > 128) inspected = `${inspected.slice(0, 128)}...`;
  return `The ${name.includes('.') ? 'property' : 'argument'} '${name}' ${reason}. Received ${inspected}`;
});
E('ERR_OUT_OF_RANGE', RangeError, (str, range, input, replaceDefaultBoolean = false) => {
  const msg = replaceDefaultBoolean ? str : `The value of "${str}" is out of range.`;
  let received;
  if (Number.isInteger(input) && Math.abs(input) > 2 ** 32) {
    received = addNumericalSeparator(String(input));
  } else if (typeof input === 'bigint') {
    received = String(input);
    if (input > 2n ** 32n || input < -(2n ** 32n)) received = addNumericalSeparator(received);
    received += 'n';
  } else {
    received = lazyInspect(input);
  }
  return `${msg} It must be ${range}. Received ${received}`;
});
E('ERR_AMBIGUOUS_ARGUMENT', TypeError, 'The "%s" argument is ambiguous. %s');
E('ERR_ASSERTION', Error, '%s');
E('ERR_BUFFER_OUT_OF_BOUNDS', RangeError, (name) =>
  name ? `"${name}" is outside of buffer bounds` : 'Attempt to access memory outside buffer bounds');
E('ERR_FALSY_VALUE_REJECTION', Error, 'Promise was rejected with falsy value');
E('ERR_ILLEGAL_CONSTRUCTOR', TypeError, 'Illegal constructor');
E('ERR_INTERNAL_ASSERTION', Error, (message) => {
  const suffix = 'This is caused by either a bug in NeonJS\'s Node.js libraries or incorrect usage of their internals.\n';
  return message === undefined ? suffix : `${message}\n${suffix}`;
});
E('ERR_INVALID_BUFFER_SIZE', RangeError, (size) => `Buffer size must be a multiple of ${size}`);
E('ERR_INVALID_RETURN_VALUE', TypeError, (input, name, value) => {
  const type = value && value.constructor && value.constructor.name ? `instance of ${value.constructor.name}` : `type ${typeof value}`;
  return `Expected ${input} to be returned from the "${name}" function but got ${type}.`;
});
E('ERR_INVALID_STATE', Error, 'Invalid state: %s');
E('ERR_INVALID_THIS', TypeError, (type) => `Value of "this" must be of type ${type}`);
E('ERR_METHOD_NOT_IMPLEMENTED', Error, (method) => `The ${method} method is not implemented`);
E('ERR_MISSING_ARGS', TypeError, (...args) => {
  const wrap = (a) => (Array.isArray(a) ? a.map((x) => `"${x}"`).join(' or ') : `"${a}"`);
  let msg = 'The ';
  if (args.length === 1) msg += `${wrap(args[0])} argument`;
  else if (args.length === 2) msg += `${wrap(args[0])} and ${wrap(args[1])} arguments`;
  else msg += `${args.slice(0, -1).map(wrap).join(', ')}, and ${wrap(args[args.length - 1])} arguments`;
  return `${msg} must be specified`;
});
E('ERR_SOCKET_BAD_PORT', RangeError, (name, port, allowZero = true) =>
  `${name} should be ${allowZero ? '>=' : '>'} 0 and < 65536. Received ${determineSpecificType(port)}.`);
E('ERR_STRING_TOO_LONG', Error, (max) => `Cannot create a string longer than 0x${max.toString(16)} characters`);
E('ERR_UNAVAILABLE', Error, (what) => `${what} is not available in NeonJS`);
E('ERR_UNKNOWN_ENCODING', TypeError, (enc) => `Unknown encoding: ${enc}`);
E('ERR_UNKNOWN_SIGNAL', TypeError, 'Unknown signal: %s');

module.exports = {
  AbortError,
  codes,
  determineSpecificType,
  addNumericalSeparator,
  hideStackFrames,
  isErrorStackTraceLimitWritable,
  kIsNodeError,
  makeError,
};
