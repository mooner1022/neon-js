'use strict';
// Node's coded errors (ERR_*) with Node's messages, and the argument validators of the libraries. Libraries only:
// scripts cannot require this.

const kTypes = ['string', 'function', 'number', 'object', 'Function', 'Object', 'boolean', 'bigint', 'symbol'];
const classRegExp = /^([A-Z][a-z0-9]*)+$/;

let inspect;
function lazyInspect(v, opts) {
  if (inspect === undefined) inspect = require('util').inspect;
  return inspect(v, opts);
}

// an error of Base with Node's code: `code` is its own property, and its stack and toString() show the code
function makeError(Base, code, message, cause) {
  const options = cause === undefined ? undefined : { cause };
  const err = new Base(message, options);
  Object.defineProperty(err, 'toString', {
    value() { return `${this.name} [${code}]: ${this.message}`; },
    enumerable: false,
    writable: true,
    configurable: true,
  });
  // the stack's first line is "TypeError [ERR_X]: message", as in Node
  err.name = `${Base.name} [${code}]`;
  Error.captureStackTrace(err, makeError);
  delete err.name;
  err.code = code;
  return err;
}

const codes = {};
function E(code, Base, message) {
  codes[code] = function (...args) {
    return makeError(Base, code, typeof message === 'function' ? message(...args) : message);
  };
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
  let msg = replaceDefaultBoolean ? str : `The value of "${str}" is out of range.`;
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
E('ERR_UNKNOWN_ENCODING', TypeError, (enc) => `Unknown encoding: ${enc}`);
E('ERR_BUFFER_OUT_OF_BOUNDS', RangeError, (name) =>
  name ? `"${name}" is outside of buffer bounds` : 'Attempt to access memory outside buffer bounds');
E('ERR_INVALID_BUFFER_SIZE', RangeError, (size) => `Buffer size must be a multiple of ${size}`);
E('ERR_STRING_TOO_LONG', Error, (max) => `Cannot create a string longer than 0x${max.toString(16)} characters`);
E('ERR_MISSING_ARGS', TypeError, (...args) => {
  const wrap = (a) => (Array.isArray(a) ? a.map((x) => `"${x}"`).join(' or ') : `"${a}"`);
  let msg = 'The ';
  if (args.length === 1) msg += `${wrap(args[0])} argument`;
  else if (args.length === 2) msg += `${wrap(args[0])} and ${wrap(args[1])} arguments`;
  else msg += `${args.slice(0, -1).map(wrap).join(', ')}, and ${wrap(args[args.length - 1])} arguments`;
  return `${msg} must be specified`;
});
E('ERR_INVALID_THIS', TypeError, (type) => `Value of "this" must be of type ${type}`);
E('ERR_FALSY_VALUE_REJECTION', Error, 'Promise was rejected with falsy value');
E('ERR_METHOD_NOT_IMPLEMENTED', Error, (method) => `The ${method} method is not implemented`);
E('ERR_ILLEGAL_CONSTRUCTOR', TypeError, 'Illegal constructor');
E('ERR_INVALID_RETURN_VALUE', TypeError, (input, name, value) => {
  const type = value && value.constructor && value.constructor.name ? `instance of ${value.constructor.name}` : `type ${typeof value}`;
  return `Expected ${input} to be returned from the "${name}" function but got ${type}.`;
});
E('ERR_UNAVAILABLE', Error, (what) => `${what} is not available in NeonJS`);

// ---------------------------------------------------------------- validators

function validateString(value, name) {
  if (typeof value !== 'string') throw codes.ERR_INVALID_ARG_TYPE(name, 'string', value);
}

function validateFunction(value, name) {
  if (typeof value !== 'function') throw codes.ERR_INVALID_ARG_TYPE(name, 'Function', value);
}

function validateBoolean(value, name) {
  if (typeof value !== 'boolean') throw codes.ERR_INVALID_ARG_TYPE(name, 'boolean', value);
}

function validateObject(value, name, options) {
  const allowArray = options && options.allowArray;
  const allowFunction = options && options.allowFunction;
  const nullable = options && options.nullable;
  if ((!nullable && value === null) || (!allowArray && Array.isArray(value)) ||
      (typeof value !== 'object' && (!allowFunction || typeof value !== 'function'))) {
    throw codes.ERR_INVALID_ARG_TYPE(name, 'Object', value);
  }
}

function validateNumber(value, name, min, max) {
  if (typeof value !== 'number') throw codes.ERR_INVALID_ARG_TYPE(name, 'number', value);
  if ((min != null && value < min) || (max != null && value > max) || ((min != null || max != null) && Number.isNaN(value))) {
    throw codes.ERR_OUT_OF_RANGE(name,
      `${min != null ? `>= ${min}` : ''}${min != null && max != null ? ' && ' : ''}${max != null ? `<= ${max}` : ''}`, value);
  }
}

function validateInteger(value, name, min = Number.MIN_SAFE_INTEGER, max = Number.MAX_SAFE_INTEGER) {
  if (typeof value !== 'number') throw codes.ERR_INVALID_ARG_TYPE(name, 'number', value);
  if (!Number.isInteger(value)) throw codes.ERR_OUT_OF_RANGE(name, 'an integer', value);
  if (value < min || value > max) throw codes.ERR_OUT_OF_RANGE(name, `>= ${min} && <= ${max}`, value);
}

function validateInt32(value, name, min = -2147483648, max = 2147483647) {
  if (typeof value !== 'number') throw codes.ERR_INVALID_ARG_TYPE(name, 'number', value);
  if (!Number.isInteger(value)) throw codes.ERR_OUT_OF_RANGE(name, 'an integer', value);
  if (value < min || value > max) throw codes.ERR_OUT_OF_RANGE(name, `>= ${min} && <= ${max}`, value);
}

function validateArray(value, name, minLength = 0) {
  if (!Array.isArray(value)) throw codes.ERR_INVALID_ARG_TYPE(name, 'Array', value);
  if (value.length < minLength) throw codes.ERR_INVALID_ARG_VALUE(name, value, `must be longer than ${minLength}`);
}

function validateAbortSignal(signal, name) {
  if (signal !== undefined && (signal === null || typeof signal !== 'object' || !('aborted' in signal))) {
    throw codes.ERR_INVALID_ARG_TYPE(name, 'AbortSignal', signal);
  }
}

module.exports = {
  codes,
  makeError,
  determineSpecificType,
  addNumericalSeparator,
  validateString,
  validateFunction,
  validateBoolean,
  validateObject,
  validateNumber,
  validateInteger,
  validateInt32,
  validateArray,
  validateAbortSignal,
};
