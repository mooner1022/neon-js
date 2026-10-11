"""Generates neonjs-node's lib/internal_errors.js from Node v22.12.0's lib/internal/errors.js: the E() definitions of
the codes the libraries use, Node's helpers for their messages, and NeonJS's own error factory (codes.X callable with
or without new).

Usage (from the repository root, with Node's sources in third_party/node): python neonjs-node/tools/gen_errors.py
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
src = open(f'{ROOT}/third_party/node/lib/internal/errors.js', encoding='utf-8').read().replace('\r\n', '\n')
lines = src.split('\n')

CODES = """
ERR_ACCESS_DENIED ERR_AMBIGUOUS_ARGUMENT ERR_ARG_NOT_ITERABLE ERR_ASSERTION ERR_BUFFER_OUT_OF_BOUNDS ERR_BUFFER_TOO_LARGE
ERR_ENCODING_INVALID_ENCODED_DATA ERR_ENCODING_NOT_SUPPORTED ERR_EVENT_RECURSION ERR_FALSY_VALUE_REJECTION
ERR_FEATURE_UNAVAILABLE_ON_PLATFORM ERR_HTTP_INVALID_HEADER_VALUE ERR_HTTP_INVALID_STATUS_CODE ERR_ILLEGAL_CONSTRUCTOR
ERR_INCOMPATIBLE_OPTION_PAIR ERR_INTERNAL_ASSERTION ERR_INVALID_ARG_TYPE ERR_INVALID_ARG_VALUE ERR_INVALID_BUFFER_SIZE
ERR_INVALID_CHAR ERR_INVALID_FILE_URL_HOST ERR_INVALID_FILE_URL_PATH ERR_INVALID_HTTP_TOKEN ERR_INVALID_MIME_SYNTAX
ERR_INVALID_RETURN_PROPERTY ERR_INVALID_RETURN_PROPERTY_VALUE ERR_INVALID_RETURN_VALUE ERR_INVALID_STATE ERR_INVALID_THIS
ERR_INVALID_TUPLE ERR_INVALID_URI ERR_INVALID_URL ERR_INVALID_URL_SCHEME ERR_METHOD_NOT_IMPLEMENTED ERR_MISSING_ARGS
ERR_MISSING_OPTION ERR_MULTIPLE_CALLBACK ERR_OPERATION_FAILED ERR_OUT_OF_RANGE ERR_PARSE_ARGS_INVALID_OPTION_VALUE
ERR_PARSE_ARGS_UNEXPECTED_POSITIONAL ERR_PARSE_ARGS_UNKNOWN_OPTION ERR_SOCKET_BAD_PORT ERR_STREAM_ALREADY_FINISHED
ERR_STREAM_CANNOT_PIPE ERR_STREAM_DESTROYED ERR_STREAM_NULL_VALUES ERR_STREAM_PREMATURE_CLOSE ERR_STREAM_PUSH_AFTER_EOF
ERR_STREAM_UNSHIFT_AFTER_END_EVENT ERR_STREAM_WRAP ERR_STREAM_WRITE_AFTER_END
ERR_UNAVAILABLE_DURING_EXIT ERR_UNHANDLED_ERROR ERR_UNKNOWN_BUILTIN_MODULE ERR_UNKNOWN_ENCODING
ERR_UNKNOWN_SIGNAL ERR_USE_AFTER_CLOSE
""".split()

# each E( block: from its line to the line before the next top-level statement
starts = [i for i, l in enumerate(lines) if l.startswith('E(')]
blocks = {}
for n, i in enumerate(starts):
    j = i + 1
    while j < len(lines) and not (lines[j] and not lines[j].startswith((' ', '\t', ')', '}', ']', '//'))):
        j += 1
    m = re.match(r"E\('(\w+)'", lines[i])
    if m:
        blocks[m.group(1)] = '\n'.join(lines[i:j]).rstrip()
missing = [c for c in CODES if c not in blocks]
if missing:
    raise SystemExit(f'missing codes: {missing}')
defs = '\n'.join(blocks[c] for c in CODES)
# SystemError-based codes are not among these; HideStackFramesError stays as a marker
assert 'SystemError' not in defs, 'a SystemError code was picked'


def function_source(name):
    i = next(k for k, l in enumerate(lines) if l.startswith(f'function {name}('))
    j = i + 1
    while not lines[j].startswith('}'):
        j += 1
    return '\n'.join(lines[i:j + 1])


def const_source(name):
    i = next(k for k, l in enumerate(lines) if l.startswith(f'const {name} ='))
    j = i
    while not lines[j].rstrip().endswith(';'):
        j += 1
    return '\n'.join(lines[i:j + 1])


helpers = '\n\n'.join([
    const_source('classRegExp'), const_source('kTypes'),
    function_source('addNumericalSeparator'), function_source('determineSpecificType'), function_source('formatList'),
])

body = helpers + '\n\n' + defs
# the primordials the messages use (by Node's naming), and those of the factory below
used = re.findall(r'\b((?:Array|String|Object|Number|RegExp|JSON|Math|Reflect|Symbol|Error|Function|BigInt|Safe)'
                  r'(?:Prototype)?[A-Z]\w*)\b', body)
prim = {n for n in used if not n.startswith('StringDecoder')} | {
    'ArrayIsArray', 'ErrorCaptureStackTrace', 'NumberPrototypeToString', 'ObjectAssign', 'ObjectDefineProperties', 'ObjectDefineProperty',
    'ObjectGetOwnPropertyDescriptor', 'ObjectIsExtensible', 'ObjectPrototypeHasOwnProperty', 'ReflectApply',
    'RegExpPrototypeExec',
}

out = f"""'use strict';
// Node's coded errors (ERR_*), generated from Node.js v22.12.0 lib/internal/errors.js (MIT license, see NOTICE): the
// definitions of the codes the libraries use, with Node's messages. codes.X makes the error with or without new, with
// its .TypeError / .RangeError variants and .HideStackFramesError; hideStackFrames hides a validator's own frames from
// the errors it throws. Libraries only: scripts cannot require this. Made by neonjs-node/tools/gen_errors.py.

const {{
  {(','+chr(10)+'  ').join(sorted(set(prim)))},
}} = primordials;

const kIsNodeError = Symbol('kIsNodeError');
const messages = new Map();
const codes = {{}};

// internal/util/inspect, loaded on first use (it needs this module)
let internalUtilInspect = null;
function lazyInternalUtilInspect() {{
  internalUtilInspect ??= require('internal/util/inspect');
  return internalUtilInspect;
}}

let internalAssert;
function assert(value, message) {{
  if (!value) {{
    internalAssert ??= require('internal/assert');
    internalAssert(value, message);
  }}
}}

function isErrorStackTraceLimitWritable() {{
  const desc = ObjectGetOwnPropertyDescriptor(Error, 'stackTraceLimit');
  if (desc === undefined) return ObjectIsExtensible(Error);
  return ObjectPrototypeHasOwnProperty(desc, 'writable') ? desc.writable : desc.set !== undefined;
}}

// the stack of a coded error (again) from below stackStartFn, its first line "TypeError [ERR_X]: message" as in Node
function captureStack(err, stackStartFn) {{
  if (err !== null && typeof err === 'object' && err[kIsNodeError] === true && typeof err.code === 'string') {{
    const name = err.name;
    err.name = `${{name}} [${{err.code}}]`;
    ErrorCaptureStackTrace(err, stackStartFn);
    delete err.name;
    if (err.name !== name) err.name = name;
  }} else {{
    ErrorCaptureStackTrace(err, stackStartFn);
  }}
}}

function getExpectedArgumentLength(msg) {{
  let expectedLength = 0;
  const regex = /%[dfijoOs]/g;
  while (RegExpPrototypeExec(regex, msg) !== null) expectedLength++;
  return expectedLength;
}}

function getMessage(key, args, self) {{
  const msg = messages.get(key);
  if (typeof msg === 'function') return ReflectApply(msg, self, args);
  if (args.length === 0 || getExpectedArgumentLength(msg) === 0) return msg;
  return ReflectApply(lazyInternalUtilInspect().format, null, [msg, ...args]);
}}

// the factory of one code and base class: an error of Base with the code as its own property and Node's message
function makeNodeErrorWithCode(Base, key) {{
  function NodeError(...args) {{
    const err = new Base();
    ObjectDefineProperties(err, {{
      [kIsNodeError]: {{ __proto__: null, value: true, enumerable: false, writable: false, configurable: true }},
      message: {{ __proto__: null, value: getMessage(key, args, err), enumerable: false, writable: true, configurable: true }},
      toString: {{
        __proto__: null,
        value() {{ return `${{this.name}} [${{key}}]: ${{this.message}}`; }},
        enumerable: false,
        writable: true,
        configurable: true,
      }},
    }});
    err.code = key;
    captureStack(err, new.target || NodeError);
    return err;
  }}
  ObjectDefineProperty(NodeError, 'name', {{ __proto__: null, value: Base.name, configurable: true }});
  return NodeError;
}}

// a marker among the classes of E(): the code also has a .HideStackFramesError (the same factory here)
class HideStackFramesError extends Error {{}}

function E(sym, val, def, ...otherClasses) {{
  messages.set(sym, val);
  const ErrClass = makeNodeErrorWithCode(def, sym);
  for (const clazz of otherClasses) {{
    if (clazz === HideStackFramesError) continue;
    ErrClass[clazz.name] = makeNodeErrorWithCode(clazz, sym);
    ErrClass[clazz.name].HideStackFramesError = ErrClass[clazz.name];
  }}
  ErrClass.HideStackFramesError = ErrClass;
  codes[sym] = ErrClass;
}}

// a function whose own frames the errors it throws do not show (Node's validators are made so)
function hideStackFrames(fn) {{
  function wrappedFn(...args) {{
    try {{
      return ReflectApply(fn, this, args);
    }} catch (error) {{
      if (Error.stackTraceLimit) captureStack(error, wrappedFn);
      throw error;
    }}
  }}
  wrappedFn.withoutStackTrace = fn;
  return wrappedFn;
}}

class AbortError extends Error {{
  constructor(message = 'The operation was aborted', options = undefined) {{
    if (options !== undefined && typeof options !== 'object') {{
      throw new codes.ERR_INVALID_ARG_TYPE('options', 'Object', options);
    }}
    super(message, options);
    this.code = 'ABORT_ERR';
    this.name = 'AbortError';
  }}
}}

// a plain Error with properties, its own frames hidden
const genericNodeError = hideStackFrames(function genericNodeError(message, errorProperties) {{
  // eslint-disable-next-line no-restricted-syntax
  const err = new Error(message);
  ObjectAssign(err, errorProperties);
  return err;
}});

const kEnhanceStackBeforeInspector = Symbol('kEnhanceStackBeforeInspector');

// a coded error with a message of its own, as Node's native code throws them ("Index out of range")
function makeError(Base, code, message) {{
  const err = new Base(message);
  ObjectDefineProperties(err, {{
    [kIsNodeError]: {{ __proto__: null, value: true, enumerable: false, writable: false, configurable: true }},
    toString: {{
      __proto__: null,
      value() {{ return `${{this.name}} [${{code}}]: ${{this.message}}`; }},
      enumerable: false,
      writable: true,
      configurable: true,
    }},
  }});
  err.code = code;
  captureStack(err, makeError);
  return err;
}}

{body}

// the codes Node defines in C++ (src/node_errors.h)
E('ERR_STRING_TOO_LONG', (max) => `Cannot create a string longer than 0x${{NumberPrototypeToString(max, 16)}} characters`, Error);
E('ERR_ZLIB_INITIALIZATION_FAILED', 'Initialization failed', Error);

// NeonJS's own: what the sandbox does not provide
E('ERR_UNAVAILABLE', (what) => `${{what}} is not available in NeonJS`, Error);

module.exports = {{
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
}};
"""
open(f'{ROOT}/neonjs-node/src/main/resources/dev/mooner/neonjs/node/lib/internal_errors.js', 'w', encoding='utf-8',
     newline='\n').write(out)
print('codes', len(CODES), 'primordials', len(set(prim)))
