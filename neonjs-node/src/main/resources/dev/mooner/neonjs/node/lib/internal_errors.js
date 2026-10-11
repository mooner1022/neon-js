'use strict';
// Node's coded errors (ERR_*), generated from Node.js v22.12.0 lib/internal/errors.js (MIT license, see NOTICE): the
// definitions of the codes the libraries use, with Node's messages. codes.X makes the error with or without new, with
// its .TypeError / .RangeError variants and .HideStackFramesError; hideStackFrames hides a validator's own frames from
// the errors it throws. Libraries only: scripts cannot require this. Made by neonjs-node/tools/gen_errors.py.

const {
  ArrayIsArray,
  ArrayPrototypeIncludes,
  ArrayPrototypeIndexOf,
  ArrayPrototypeJoin,
  ArrayPrototypeMap,
  ArrayPrototypePush,
  ArrayPrototypeSlice,
  ArrayPrototypeSplice,
  ErrorCaptureStackTrace,
  JSONStringify,
  MathAbs,
  NumberIsInteger,
  NumberPrototypeToString,
  ObjectAssign,
  ObjectDefineProperties,
  ObjectDefineProperty,
  ObjectGetOwnPropertyDescriptor,
  ObjectIsExtensible,
  ObjectPrototypeHasOwnProperty,
  ReflectApply,
  RegExpPrototypeExec,
  StringPrototypeEndsWith,
  StringPrototypeIncludes,
  StringPrototypeIndexOf,
  StringPrototypeSlice,
  StringPrototypeToLowerCase,
} = primordials;

const kIsNodeError = Symbol('kIsNodeError');
const messages = new Map();
const codes = {};

// internal/util/inspect, loaded on first use (it needs this module)
let internalUtilInspect = null;
function lazyInternalUtilInspect() {
  internalUtilInspect ??= require('internal/util/inspect');
  return internalUtilInspect;
}

let internalAssert;
function assert(value, message) {
  if (!value) {
    internalAssert ??= require('internal/assert');
    internalAssert(value, message);
  }
}

function isErrorStackTraceLimitWritable() {
  const desc = ObjectGetOwnPropertyDescriptor(Error, 'stackTraceLimit');
  if (desc === undefined) return ObjectIsExtensible(Error);
  return ObjectPrototypeHasOwnProperty(desc, 'writable') ? desc.writable : desc.set !== undefined;
}

// the stack of a coded error (again) from below stackStartFn, its first line "TypeError [ERR_X]: message" as in Node
function captureStack(err, stackStartFn) {
  if (err !== null && typeof err === 'object' && err[kIsNodeError] === true && typeof err.code === 'string') {
    const name = err.name;
    err.name = `${name} [${err.code}]`;
    ErrorCaptureStackTrace(err, stackStartFn);
    delete err.name;
    if (err.name !== name) err.name = name;
  } else {
    ErrorCaptureStackTrace(err, stackStartFn);
  }
}

function getExpectedArgumentLength(msg) {
  let expectedLength = 0;
  const regex = /%[dfijoOs]/g;
  while (RegExpPrototypeExec(regex, msg) !== null) expectedLength++;
  return expectedLength;
}

function getMessage(key, args, self) {
  const msg = messages.get(key);
  if (typeof msg === 'function') return ReflectApply(msg, self, args);
  if (args.length === 0 || getExpectedArgumentLength(msg) === 0) return msg;
  return ReflectApply(lazyInternalUtilInspect().format, null, [msg, ...args]);
}

// the factory of one code and base class: an error of Base with the code as its own property and Node's message
function makeNodeErrorWithCode(Base, key) {
  function NodeError(...args) {
    const err = new Base();
    ObjectDefineProperties(err, {
      [kIsNodeError]: { __proto__: null, value: true, enumerable: false, writable: false, configurable: true },
      message: { __proto__: null, value: getMessage(key, args, err), enumerable: false, writable: true, configurable: true },
      toString: {
        __proto__: null,
        value() { return `${this.name} [${key}]: ${this.message}`; },
        enumerable: false,
        writable: true,
        configurable: true,
      },
    });
    err.code = key;
    captureStack(err, new.target || NodeError);
    return err;
  }
  ObjectDefineProperty(NodeError, 'name', { __proto__: null, value: Base.name, configurable: true });
  return NodeError;
}

// a marker among the classes of E(): the code also has a .HideStackFramesError (the same factory here)
class HideStackFramesError extends Error {}

function E(sym, val, def, ...otherClasses) {
  messages.set(sym, val);
  const ErrClass = makeNodeErrorWithCode(def, sym);
  for (const clazz of otherClasses) {
    if (clazz === HideStackFramesError) continue;
    ErrClass[clazz.name] = makeNodeErrorWithCode(clazz, sym);
    ErrClass[clazz.name].HideStackFramesError = ErrClass[clazz.name];
  }
  ErrClass.HideStackFramesError = ErrClass;
  codes[sym] = ErrClass;
}

// a function whose own frames the errors it throws do not show (Node's validators are made so)
function hideStackFrames(fn) {
  function wrappedFn(...args) {
    try {
      return ReflectApply(fn, this, args);
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
    if (options !== undefined && typeof options !== 'object') {
      throw new codes.ERR_INVALID_ARG_TYPE('options', 'Object', options);
    }
    super(message, options);
    this.code = 'ABORT_ERR';
    this.name = 'AbortError';
  }
}

// a plain Error with properties, its own frames hidden
const genericNodeError = hideStackFrames(function genericNodeError(message, errorProperties) {
  // eslint-disable-next-line no-restricted-syntax
  const err = new Error(message);
  ObjectAssign(err, errorProperties);
  return err;
});

const kEnhanceStackBeforeInspector = Symbol('kEnhanceStackBeforeInspector');

// a coded error with a message of its own, as Node's native code throws them ("Index out of range")
function makeError(Base, code, message) {
  const err = new Base(message);
  ObjectDefineProperties(err, {
    [kIsNodeError]: { __proto__: null, value: true, enumerable: false, writable: false, configurable: true },
    toString: {
      __proto__: null,
      value() { return `${this.name} [${code}]: ${this.message}`; },
      enumerable: false,
      writable: true,
      configurable: true,
    },
  });
  err.code = code;
  captureStack(err, makeError);
  return err;
}

const classRegExp = /^[A-Z][a-zA-Z0-9]*$/;

const kTypes = [
  'string',
  'function',
  'number',
  'object',
  // Accept 'Function' and 'Object' as alternative to the lower cased version.
  'Function',
  'Object',
  'boolean',
  'bigint',
  'symbol',
];

function addNumericalSeparator(val) {
  let res = '';
  let i = val.length;
  const start = val[0] === '-' ? 1 : 0;
  for (; i >= start + 4; i -= 3) {
    res = `_${StringPrototypeSlice(val, i - 3, i)}${res}`;
  }
  return `${StringPrototypeSlice(val, 0, i)}${res}`;
}

function determineSpecificType(value) {
  if (value === null) {
    return 'null';
  } else if (value === undefined) {
    return 'undefined';
  }

  const type = typeof value;

  switch (type) {
    case 'bigint':
      return `type bigint (${value}n)`;
    case 'number':
      if (value === 0) {
        return 1 / value === -Infinity ? 'type number (-0)' : 'type number (0)';
      } else if (value !== value) { // eslint-disable-line no-self-compare
        return 'type number (NaN)';
      } else if (value === Infinity) {
        return 'type number (Infinity)';
      } else if (value === -Infinity) {
        return 'type number (-Infinity)';
      }
      return `type number (${value})`;
    case 'boolean':
      return value ? 'type boolean (true)' : 'type boolean (false)';
    case 'symbol':
      return `type symbol (${String(value)})`;
    case 'function':
      return `function ${value.name}`;
    case 'object':
      if (value.constructor && 'name' in value.constructor) {
        return `an instance of ${value.constructor.name}`;
      }
      return `${lazyInternalUtilInspect().inspect(value, { depth: -1 })}`;
    case 'string':
      value.length > 28 && (value = `${StringPrototypeSlice(value, 0, 25)}...`);
      if (StringPrototypeIndexOf(value, "'") === -1) {
        return `type string ('${value}')`;
      }
      return `type string (${JSONStringify(value)})`;
    default:
      value = lazyInternalUtilInspect().inspect(value, { colors: false });
      if (value.length > 28) {
        value = `${StringPrototypeSlice(value, 0, 25)}...`;
      }

      return `type ${type} (${value})`;
  }
}

function formatList(array, type = 'and') {
  switch (array.length) {
    case 0: return '';
    case 1: return `${array[0]}`;
    case 2: return `${array[0]} ${type} ${array[1]}`;
    case 3: return `${array[0]}, ${array[1]}, ${type} ${array[2]}`;
    default:
      return `${ArrayPrototypeJoin(ArrayPrototypeSlice(array, 0, -1), ', ')}, ${type} ${array[array.length - 1]}`;
  }
}

E('ERR_ACCESS_DENIED',
  function(msg, permission = '', resource = '') {
    this.permission = permission;
    this.resource = resource;
    return msg;
  },
  Error);
E('ERR_AMBIGUOUS_ARGUMENT', 'The "%s" argument is ambiguous. %s', TypeError);
E('ERR_ARG_NOT_ITERABLE', '%s must be iterable', TypeError);
E('ERR_ASSERTION', '%s', Error);
E('ERR_BUFFER_OUT_OF_BOUNDS',
  // Using a default argument here is important so the argument is not counted
  // towards `Function#length`.
  (name = undefined) => {
    if (name) {
      return `"${name}" is outside of buffer bounds`;
    }
    return 'Attempt to access memory outside buffer bounds';
  }, RangeError);
E('ERR_BUFFER_TOO_LARGE',
  'Cannot create a Buffer larger than %s bytes',
  RangeError);
E('ERR_ENCODING_INVALID_ENCODED_DATA', function(encoding, ret) {
  this.errno = ret;
  return `The encoded data was not valid for encoding ${encoding}`;
}, TypeError);
E('ERR_ENCODING_NOT_SUPPORTED', 'The "%s" encoding is not supported',
  RangeError);
E('ERR_EVENT_RECURSION', 'The event "%s" is already being dispatched', Error);
E('ERR_FALSY_VALUE_REJECTION', function(reason) {
  this.reason = reason;
  return 'Promise was rejected with falsy value';
}, Error, HideStackFramesError);
E('ERR_FEATURE_UNAVAILABLE_ON_PLATFORM',
  'The feature %s is unavailable on the current platform' +
  ', which is being used to run Node.js',
  TypeError);
E('ERR_HTTP_INVALID_HEADER_VALUE',
  'Invalid value "%s" for header "%s"', TypeError, HideStackFramesError);
E('ERR_HTTP_INVALID_STATUS_CODE', 'Invalid status code: %s', RangeError);
E('ERR_ILLEGAL_CONSTRUCTOR', 'Illegal constructor', TypeError);
E('ERR_INCOMPATIBLE_OPTION_PAIR',
  'Option "%s" cannot be used in combination with option "%s"', TypeError, HideStackFramesError);
E('ERR_INTERNAL_ASSERTION', (message) => {
  const suffix = 'This is caused by either a bug in Node.js ' +
    'or incorrect usage of Node.js internals.\n' +
    'Please open an issue with this stack trace at ' +
    'https://github.com/nodejs/node/issues\n';
  return message === undefined ? suffix : `${message}\n${suffix}`;
}, Error);
E('ERR_INVALID_ARG_TYPE',
  (name, expected, actual) => {
    assert(typeof name === 'string', "'name' must be a string");
    if (!ArrayIsArray(expected)) {
      expected = [expected];
    }

    let msg = 'The ';
    if (StringPrototypeEndsWith(name, ' argument')) {
      // For cases like 'first argument'
      msg += `${name} `;
    } else {
      const type = StringPrototypeIncludes(name, '.') ? 'property' : 'argument';
      msg += `"${name}" ${type} `;
    }
    msg += 'must be ';

    const types = [];
    const instances = [];
    const other = [];

    for (const value of expected) {
      assert(typeof value === 'string',
             'All expected entries have to be of type string');
      if (ArrayPrototypeIncludes(kTypes, value)) {
        ArrayPrototypePush(types, StringPrototypeToLowerCase(value));
      } else if (RegExpPrototypeExec(classRegExp, value) !== null) {
        ArrayPrototypePush(instances, value);
      } else {
        assert(value !== 'object',
               'The value "object" should be written as "Object"');
        ArrayPrototypePush(other, value);
      }
    }

    // Special handle `object` in case other instances are allowed to outline
    // the differences between each other.
    if (instances.length > 0) {
      const pos = ArrayPrototypeIndexOf(types, 'object');
      if (pos !== -1) {
        ArrayPrototypeSplice(types, pos, 1);
        ArrayPrototypePush(instances, 'Object');
      }
    }

    if (types.length > 0) {
      msg += `${types.length > 1 ? 'one of type' : 'of type'} ${formatList(types, 'or')}`;
      if (instances.length > 0 || other.length > 0)
        msg += ' or ';
    }

    if (instances.length > 0) {
      msg += `an instance of ${formatList(instances, 'or')}`;
      if (other.length > 0)
        msg += ' or ';
    }

    if (other.length > 0) {
      if (other.length > 1) {
        msg += `one of ${formatList(other, 'or')}`;
      } else {
        if (StringPrototypeToLowerCase(other[0]) !== other[0])
          msg += 'an ';
        msg += `${other[0]}`;
      }
    }

    msg += `. Received ${determineSpecificType(actual)}`;

    return msg;
  }, TypeError, HideStackFramesError);
E('ERR_INVALID_ARG_VALUE', (name, value, reason = 'is invalid') => {
  let inspected = lazyInternalUtilInspect().inspect(value);
  if (inspected.length > 128) {
    inspected = `${StringPrototypeSlice(inspected, 0, 128)}...`;
  }
  const type = StringPrototypeIncludes(name, '.') ? 'property' : 'argument';
  return `The ${type} '${name}' ${reason}. Received ${inspected}`;
}, TypeError, RangeError, HideStackFramesError);
E('ERR_INVALID_BUFFER_SIZE',
  'Buffer size must be a multiple of %s', RangeError);
E('ERR_INVALID_CHAR',
  // Using a default argument here is important so the argument is not counted
  // towards `Function#length`.
  (name, field = undefined) => {
    let msg = `Invalid character in ${name}`;
    if (field !== undefined) {
      msg += ` ["${field}"]`;
    }
    return msg;
  }, TypeError, HideStackFramesError);
E('ERR_INVALID_FILE_URL_HOST',
  'File URL host must be "localhost" or empty on %s', TypeError);
E('ERR_INVALID_FILE_URL_PATH', 'File URL path %s', TypeError);
E('ERR_INVALID_HTTP_TOKEN', '%s must be a valid HTTP token ["%s"]', TypeError, HideStackFramesError);
E('ERR_INVALID_MIME_SYNTAX', (production, str, invalidIndex) => {
  const msg = invalidIndex !== -1 ? ` at ${invalidIndex}` : '';
  return `The MIME syntax for a ${production} in "${str}" is invalid` + msg;
}, TypeError);
E('ERR_INVALID_RETURN_PROPERTY', (input, name, prop, value) => {
  return `Expected a valid ${input} to be returned for the "${prop}" from the` +
         ` "${name}" hook but got ${determineSpecificType(value)}.`;
}, TypeError);
E('ERR_INVALID_RETURN_PROPERTY_VALUE', (input, name, prop, value) => {
  return `Expected ${input} to be returned for the "${prop}" from the` +
         ` "${name}" hook but got ${determineSpecificType(value)}.`;
}, TypeError);
E('ERR_INVALID_RETURN_VALUE', (input, name, value) => {
  const type = determineSpecificType(value);

  return `Expected ${input} to be returned from the "${name}"` +
         ` function but got ${type}.`;
}, TypeError, RangeError);
E('ERR_INVALID_STATE', 'Invalid state: %s', Error, TypeError, RangeError);
E('ERR_INVALID_THIS', 'Value of "this" must be of type %s', TypeError);
E('ERR_INVALID_TUPLE', '%s must be an iterable %s tuple', TypeError);
E('ERR_INVALID_URI', 'URI malformed', URIError);
E('ERR_INVALID_URL', function(input, base = null) {
  this.input = input;

  if (base != null) {
    this.base = base;
  }

  // Don't include URL in message.
  // (See https://github.com/nodejs/node/pull/38614)
  return 'Invalid URL';
}, TypeError);
E('ERR_INVALID_URL_SCHEME',
  (expected) => {
    if (typeof expected === 'string')
      expected = [expected];
    assert(expected.length <= 2);
    const res = expected.length === 2 ?
      `one of scheme ${expected[0]} or ${expected[1]}` :
      `of scheme ${expected[0]}`;
    return `The URL must be ${res}`;
  }, TypeError);
E('ERR_METHOD_NOT_IMPLEMENTED', 'The %s method is not implemented', Error);
E('ERR_MISSING_ARGS',
  (...args) => {
    assert(args.length > 0, 'At least one arg needs to be specified');
    let msg = 'The ';
    const len = args.length;
    const wrap = (a) => `"${a}"`;
    args = ArrayPrototypeMap(
      args,
      (a) => (ArrayIsArray(a) ?
        ArrayPrototypeJoin(ArrayPrototypeMap(a, wrap), ' or ') :
        wrap(a)),
    );
    msg += `${formatList(args)} argument${len > 1 ? 's' : ''}`;
    return `${msg} must be specified`;
  }, TypeError);
E('ERR_MISSING_OPTION', '%s is required', TypeError);
E('ERR_MULTIPLE_CALLBACK', 'Callback called multiple times', Error);
E('ERR_OPERATION_FAILED', 'Operation failed: %s', Error, TypeError);
E('ERR_OUT_OF_RANGE',
  (str, range, input, replaceDefaultBoolean = false) => {
    assert(range, 'Missing "range" argument');
    let msg = replaceDefaultBoolean ? str :
      `The value of "${str}" is out of range.`;
    let received;
    if (NumberIsInteger(input) && MathAbs(input) > 2 ** 32) {
      received = addNumericalSeparator(String(input));
    } else if (typeof input === 'bigint') {
      received = String(input);
      if (input > 2n ** 32n || input < -(2n ** 32n)) {
        received = addNumericalSeparator(received);
      }
      received += 'n';
    } else {
      received = lazyInternalUtilInspect().inspect(input);
    }
    msg += ` It must be ${range}. Received ${received}`;
    return msg;
  }, RangeError, HideStackFramesError);
E('ERR_PARSE_ARGS_INVALID_OPTION_VALUE', '%s', TypeError);
E('ERR_PARSE_ARGS_UNEXPECTED_POSITIONAL', "Unexpected argument '%s'. This " +
  'command does not take positional arguments', TypeError);
E('ERR_PARSE_ARGS_UNKNOWN_OPTION', (option, allowPositionals) => {
  const suggestDashDash = allowPositionals ? '. To specify a positional ' +
    "argument starting with a '-', place it at the end of the command after " +
    `'--', as in '-- ${JSONStringify(option)}` : '';
  return `Unknown option '${option}'${suggestDashDash}`;
}, TypeError);
E('ERR_SOCKET_BAD_PORT', (name, port, allowZero = true) => {
  assert(typeof allowZero === 'boolean',
         "The 'allowZero' argument must be of type boolean.");
  const operator = allowZero ? '>=' : '>';
  return `${name} should be ${operator} 0 and < 65536. Received ${determineSpecificType(port)}.`;
}, RangeError, HideStackFramesError);
E('ERR_STREAM_ALREADY_FINISHED',
  'Cannot call %s after a stream was finished',
  Error);
E('ERR_STREAM_CANNOT_PIPE', 'Cannot pipe, not readable', Error);
E('ERR_STREAM_DESTROYED', 'Cannot call %s after a stream was destroyed', Error);
E('ERR_STREAM_NULL_VALUES', 'May not write null values to stream', TypeError);
E('ERR_STREAM_PREMATURE_CLOSE', 'Premature close', Error);
E('ERR_STREAM_PUSH_AFTER_EOF', 'stream.push() after EOF', Error);
E('ERR_STREAM_UNSHIFT_AFTER_END_EVENT',
  'stream.unshift() after end event', Error);
E('ERR_STREAM_WRAP', 'Stream has StringDecoder set or is in objectMode', Error);
E('ERR_STREAM_WRITE_AFTER_END', 'write after end', Error);
E('ERR_UNAVAILABLE_DURING_EXIT', 'Cannot call function in process exit ' +
  'handler', Error);
E('ERR_UNHANDLED_ERROR',
  // Using a default argument here is important so the argument is not counted
  // towards `Function#length`.
  (err = undefined) => {
    const msg = 'Unhandled error.';
    if (err === undefined) return msg;
    return `${msg} (${err})`;
  }, Error);
E('ERR_UNKNOWN_BUILTIN_MODULE', 'No such built-in module: %s', Error);
E('ERR_UNKNOWN_ENCODING', 'Unknown encoding: %s', TypeError);
E('ERR_UNKNOWN_SIGNAL', 'Unknown signal: %s', TypeError, HideStackFramesError);
E('ERR_USE_AFTER_CLOSE', '%s was closed', Error);

// This should probably be a `TypeError`.

// the codes Node defines in C++ (src/node_errors.h)
E('ERR_STRING_TOO_LONG', (max) => `Cannot create a string longer than 0x${NumberPrototypeToString(max, 16)} characters`, Error);
E('ERR_ZLIB_INITIALIZATION_FAILED', 'Initialization failed', Error);

// NeonJS's own: what the sandbox does not provide
E('ERR_UNAVAILABLE', (what) => `${what} is not available in NeonJS`, Error);

module.exports = {
  AbortError,
  addNumericalSeparator,
  codes,
  determineSpecificType,
  formatList,
  genericNodeError,
  hideStackFrames,
  isErrorStackTraceLimitWritable,
  kEnhanceStackBeforeInspector,
  kIsNodeError,
  makeError,
};
