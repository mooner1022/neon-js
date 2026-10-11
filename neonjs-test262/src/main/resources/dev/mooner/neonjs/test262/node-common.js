'use strict';
// test/common for Node's tests run on NeonJS: Node's own helpers (after Node v22.12.0 test/common/index.js, MIT
// license) for what the tests use, and the platform facts of the sandbox (a Linux-like 64-bit build without crypto,
// the inspector, child processes or a file system). common.skip ends the test as skipped.

const assert = require('assert');
const { inspect } = require('util');

const noop = () => {};
const isWindows = false;
const isMacOS = false;
const isLinux = true;
const isMainThread = true;

const mustCallChecks = [];

function runCallChecks(exitCode) {
  if (exitCode !== 0) return;
  const failed = mustCallChecks.filter(function (context) {
    if ('minimum' in context) {
      context.messageSegment = `at least ${context.minimum}`;
      return context.actual < context.minimum;
    }
    context.messageSegment = `exactly ${context.exact}`;
    return context.actual !== context.exact;
  });
  failed.forEach(function (context) {
    console.log('Mismatched %s function calls. Expected %s, actual %d.', context.name, context.messageSegment, context.actual);
    console.log(context.stack.split('\n').slice(2).join('\n'));
  });
  if (failed.length) process.exit(1);
}

function mustCall(fn, exact) {
  return _mustCallInner(fn, exact, 'exact');
}

function mustSucceed(fn, exact) {
  return mustCall(function (err, ...args) {
    assert.ifError(err);
    if (typeof fn === 'function') return fn.apply(this, args);
  }, exact);
}

function mustCallAtLeast(fn, minimum) {
  return _mustCallInner(fn, minimum, 'minimum');
}

function _mustCallInner(fn, criteria = 1, field) {
  if (process._exiting) throw new Error('Cannot use common.mustCall*() in process exit handler');
  if (typeof fn === 'number') {
    criteria = fn;
    fn = noop;
  } else if (fn === undefined) {
    fn = noop;
  }
  if (typeof criteria !== 'number') throw new TypeError(`Invalid ${field} value: ${criteria}`);
  const context = {
    [field]: criteria,
    actual: 0,
    stack: inspect(new Error()),
    name: fn.name || '<anonymous>',
  };
  if (mustCallChecks.length === 0) process.on('exit', runCallChecks);
  mustCallChecks.push(context);
  const _return = function () {
    context.actual++;
    return fn.apply(this, arguments);
  };
  Object.defineProperties(_return, {
    name: { value: fn.name, writable: false, enumerable: false, configurable: true },
    length: { value: fn.length, writable: false, enumerable: false, configurable: true },
  });
  return _return;
}

// the call sites of the caller, from the stack ("    at name (file:line:column)")
function getCallSites() {
  const lines = String(new Error().stack).split('\n').slice(2);
  return lines.map((line) => {
    const m = /^\s*at (?:(.*?) \()?(.*?):(\d+):(\d+)\)?$/.exec(line);
    return m ? { functionName: m[1] || '', scriptName: m[2], lineNumber: +m[3], column: +m[4] } :
      { functionName: '', scriptName: '<unknown>', lineNumber: 0, column: 0 };
  });
}

function mustNotCall(msg) {
  const callSite = getCallSites()[1] || { scriptName: '<unknown>', lineNumber: 0 };
  return function mustNotCall(...args) {
    const argsInfo = args.length > 0 ? `\ncalled with arguments: ${args.map((arg) => inspect(arg)).join(', ')}` : '';
    assert.fail(`${msg || 'function should not have been called'} at ${callSite.scriptName}:${callSite.lineNumber}` + argsInfo);
  };
}

const _mustNotMutateObjectDeepProxies = new WeakMap();

function mustNotMutateObjectDeep(original) {
  if (original === null || typeof original !== 'object') return original;
  const cachedProxy = _mustNotMutateObjectDeepProxies.get(original);
  if (cachedProxy) return cachedProxy;
  const handler = {
    __proto__: null,
    defineProperty(target, property) {
      assert.fail(`Expected no side effects, got ${inspect(property)} defined`);
    },
    deleteProperty(target, property) {
      assert.fail(`Expected no side effects, got ${inspect(property)} deleted`);
    },
    get(target, prop, receiver) {
      return mustNotMutateObjectDeep(Reflect.get(target, prop, receiver));
    },
    preventExtensions(target) {
      assert.fail('Expected no side effects, got extensions prevented on ' + inspect(target));
    },
    set(target, property, value) {
      assert.fail(`Expected no side effects, got ${inspect(value)} assigned to ${inspect(property)}`);
    },
    setPrototypeOf(target, prototype) {
      assert.fail(`Expected no side effects, got set prototype to ${prototype}`);
    },
  };
  const proxy = new Proxy(original, handler);
  _mustNotMutateObjectDeepProxies.set(original, proxy);
  return proxy;
}

function printSkipMessage(msg) {
  console.log(`1..0 # Skipped: ${msg}`);
}

function skip(msg) {
  printSkipMessage(msg);
  __nodeTestSkipped(String(msg));
  process.exit(0);
}

function _expectWarning(name, expected, code) {
  if (typeof expected === 'string') {
    expected = [[expected, code]];
  } else if (!Array.isArray(expected)) {
    expected = Object.entries(expected).map(([a, b]) => [b, a]);
  } else if (expected.length !== 0 && !Array.isArray(expected[0])) {
    expected = [[expected[0], expected[1]]];
  }
  if (name === 'DeprecationWarning') {
    expected.forEach(([_, code]) => assert(code, `Missing deprecation code: ${expected}`));
  }
  return mustCall((warning) => {
    const expectedProperties = expected.shift();
    if (!expectedProperties) assert.fail(`Unexpected extra warning received: ${warning}`);
    const [message, code] = expectedProperties;
    assert.strictEqual(warning.name, name);
    if (typeof message === 'string') assert.strictEqual(warning.message, message);
    else assert.match(warning.message, message);
    assert.strictEqual(warning.code, code);
  }, expected.length);
}

let catchWarning;

function expectWarning(nameOrMap, expected, code) {
  if (catchWarning === undefined) {
    catchWarning = {};
    process.on('warning', (warning) => {
      if (!catchWarning[warning.name]) {
        throw new TypeError(`"${warning.name}" was triggered without being expected.\n` + inspect(warning));
      }
      catchWarning[warning.name](warning);
    });
  }
  if (typeof nameOrMap === 'string') {
    catchWarning[nameOrMap] = _expectWarning(nameOrMap, expected, code);
  } else {
    Object.keys(nameOrMap).forEach((name) => {
      catchWarning[name] = _expectWarning(name, nameOrMap[name]);
    });
  }
}

function expectsError(validator, exact) {
  return mustCall((...args) => {
    if (args.length !== 1) assert.fail(`Expected one argument, got ${inspect(args)}`);
    const error = args.pop();
    // the error message should be non-enumerable
    assert.strictEqual(Object.prototype.propertyIsEnumerable.call(error, 'message'), false);
    assert.throws(() => { throw error; }, validator);
    return true;
  }, exact);
}

function getArrayBufferViews(buf) {
  const { buffer, byteOffset, byteLength } = buf;
  const out = [];
  const arrayBufferViews = [
    Int8Array, Uint8Array, Uint8ClampedArray, Int16Array, Uint16Array, Int32Array, Uint32Array, Float32Array,
    Float64Array, BigInt64Array, BigUint64Array, DataView,
  ];
  for (const type of arrayBufferViews) {
    const { BYTES_PER_ELEMENT = 1 } = type;
    if (byteLength % BYTES_PER_ELEMENT === 0) out.push(new type(buffer, byteOffset, byteLength / BYTES_PER_ELEMENT));
  }
  return out;
}

function getBufferSources(buf) {
  return [...getArrayBufferViews(buf), new Uint8Array(buf).buffer];
}

function invalidArgTypeHelper(input) {
  if (input == null) return ` Received ${input}`;
  if (typeof input === 'function') return ` Received function ${input.name}`;
  if (typeof input === 'object') {
    if (input.constructor?.name) return ` Received an instance of ${input.constructor.name}`;
    return ` Received ${inspect(input, { depth: -1 })}`;
  }
  let inspected = inspect(input, { colors: false });
  if (inspected.length > 28) inspected = `${inspected.slice(inspected, 0, 25)}...`;
  return ` Received type ${typeof input} (${inspected})`;
}

function unsupported(what) {
  return function () {
    throw new Error(`${what} is not available when running Node's tests on NeonJS`);
  };
}

const common = {
  allowGlobals: noop,
  buildType: 'Release',
  canCreateSymLink: () => false,
  childShouldThrowAndAbort: unsupported('child processes'),
  createZeroFilledFile: unsupported('the file system'),
  defaultAutoSelectFamilyAttemptTimeout: 2500,
  enoughTestMem: true,
  escapePOSIXShell: unsupported('child processes'),
  expectsError,
  expectRequiredModule: unsupported('require(esm)'),
  expectWarning,
  getArrayBufferViews,
  getBufferSources,
  getCallSites,
  getTTYfd: () => -1,
  hasCrypto: false,
  hasIntl: typeof Intl === 'object',
  hasIPv6: false,
  hasMultiLocalhost: () => false,
  hasOpenSSL: () => false,
  hasOpenSSL3: false,
  hasQuic: false,
  hasSQLite: false,
  inFreeBSDJail: false,
  invalidArgTypeHelper,
  isAIX: false,
  isAlive: () => false,
  isDebug: false,
  isDumbTerminal: false,
  isFreeBSD: false,
  isIBMi: false,
  isInsideDirWithUnusualChars: false,
  isLinux,
  isLinuxPPCBE: false,
  isMacOS,
  isMainThread,
  isOpenBSD: false,
  isOSX: isMacOS,
  isPi: false,
  isSunOS: false,
  isWindows,
  localIPv6Hosts: [],
  localhostIPv4: '127.0.0.1',
  mustCall,
  mustCallAtLeast,
  mustNotCall,
  mustNotMutateObjectDeep,
  mustSucceed,
  nodeProcessAborted: () => false,
  opensslCli: false,
  parseTestFlags: () => [],
  PIPE: '/tmp/node-test.sock',
  platformTimeout: (ms) => ms,
  printSkipMessage,
  runWithInvalidFD: () => printSkipMessage('Could not generate an invalid fd'),
  skip,
  skipIf32Bits: noop,
  skipIfDumbTerminal: noop,
  skipIfEslintMissing: () => skip('missing ESLint'),
  skipIfInspectorDisabled: () => skip('V8 inspector is disabled'),
  skipIfWorker: noop,
  spawnPromisified: unsupported('child processes'),
};

module.exports = common;
