'use strict';
// node:test for Node's tests run on NeonJS (part of the test harness, not of neonjs-node): test / describe / it /
// suite with before / after / beforeEach / afterEach hooks, subtests (t.test), skip / todo / only options and
// mock.fn / mock.method. Top-level tests run one after another once the module has run; subtests run when called
// and are waited for before their parent ends. A failure is printed and makes process.exitCode 1.

const { inspect } = require('util');
const assert = require('assert');

let failures = 0;

class Suite {
  constructor(name, parent) {
    this.name = name;
    this.parent = parent;
    this.items = [];
    this.before = [];
    this.after = [];
    this.beforeEach = [];
    this.afterEach = [];
  }

  fullName() {
    return this.parent && this.parent.parent ? `${this.parent.fullName()} > ${this.name}` : this.name;
  }
}

const root = new Suite('<root>', null);
let collecting = root;
let started = false;

function args(name, options, fn) {
  if (typeof name === 'function') {
    fn = name;
    options = undefined;
    name = fn.name || '<anonymous>';
  } else if (typeof name === 'object' && name !== null) {
    fn = options;
    options = name;
    name = typeof fn === 'function' && fn.name ? fn.name : '<anonymous>';
  } else if (typeof options === 'function') {
    fn = options;
    options = undefined;
  }
  return { name: name === undefined ? '<anonymous>' : String(name), options: options ?? {}, fn };
}

function report(name, err) {
  failures++;
  process.exitCode = 1;
  console.error(`not ok - ${name}\n${inspect(err)}`);
}

function skipped(options) {
  return Boolean(options.skip) || Boolean(options.todo);
}

// a function of a test or hook, with or without a done callback, as a promise
function invoke(fn, self, arg) {
  if (fn.length >= 2) {
    return new Promise((resolve, reject) => {
      let finished = false;
      const done = (err) => {
        if (finished) return;
        finished = true;
        if (err) reject(err);
        else resolve();
      };
      try {
        const r = fn.call(self, arg, done);
        if (r && typeof r.then === 'function') r.then(() => {}, done);
      } catch (err) {
        done(err);
      }
    });
  }
  try {
    return Promise.resolve(fn.call(self, arg));
  } catch (err) {
    return Promise.reject(err);
  }
}

class TestContext {
  constructor(name, suite) {
    this.name = name;
    this.fullName = name;
    this.suite = suite;
    this.subtests = [];
    this.assert = assert;
    this.mock = new MockTracker();
    this.signal = new AbortController().signal;
    this.hooks = { before: [], after: [], beforeEach: [], afterEach: [] };
  }

  test(name, options, fn) {
    const a = args(name, options, fn);
    const p = runTest(`${this.name} > ${a.name}`, a.options, a.fn, this.hooks);
    this.subtests.push(p);
    return p;
  }

  skip() {}

  todo() {}

  diagnostic() {}

  plan() {}

  runOnly() {}

  before(fn) {
    this.hooks.before.push(fn);
  }

  after(fn) {
    this.hooks.after.push(fn);
  }

  beforeEach(fn) {
    this.hooks.beforeEach.push(fn);
  }

  afterEach(fn) {
    this.hooks.afterEach.push(fn);
  }
}

async function runHooks(list, self, arg) {
  for (const h of list) await invoke(h, self, arg);
}

async function runTest(name, options, fn, parentHooks, eachBefore = [], eachAfter = []) {
  if (skipped(options) || typeof fn !== 'function') return;
  const t = new TestContext(name, null);
  try {
    if (parentHooks) await runHooks(parentHooks.beforeEach, t, t);
    for (const h of eachBefore) await invoke(h, t, t);
    await invoke(fn, t, t);
    await Promise.all(t.subtests);
    for (const h of eachAfter) await invoke(h, t, t);
    if (parentHooks) await runHooks(parentHooks.afterEach, t, t);
    await runHooks(t.hooks.after, t, t);
  } catch (err) {
    report(name, err);
  } finally {
    t.mock.restoreAll();
  }
}

// the beforeEach / afterEach hooks of a suite and those around it, outermost first
function eachHooks(suite) {
  const before = [];
  const after = [];
  for (let s = suite; s; s = s.parent) {
    before.unshift(...s.beforeEach);
    after.push(...s.afterEach);
  }
  return { before, after };
}

async function runSuite(suite) {
  try {
    for (const h of suite.before) await invoke(h, undefined, undefined);
    for (const item of suite.items) {
      if (item instanceof Suite) {
        await runSuite(item);
      } else {
        const { before, after } = eachHooks(suite);
        await runTest(item.name, item.options, item.fn, null, before, after);
      }
    }
    for (const h of suite.after) await invoke(h, undefined, undefined);
  } catch (err) {
    report(suite.fullName(), err);
  }
}

function start() {
  if (started) return;
  started = true;
  // once the test module has run (and declared its tests)
  setImmediate(() => {
    runSuite(root).catch((err) => report('<root>', err));
  });
}

function test(name, options, fn) {
  const a = args(name, options, fn);
  const item = { name: collecting === root ? a.name : `${collecting.fullName()} > ${a.name}`, options: a.options, fn: a.fn };
  collecting.items.push(item);
  start();
  return Promise.resolve();
}

function describe(name, options, fn) {
  const a = args(name, options, fn);
  if (skipped(a.options) || typeof a.fn !== 'function') return Promise.resolve();
  const suite = new Suite(a.name, collecting);
  collecting.items.push(suite);
  const outer = collecting;
  collecting = suite;
  try {
    const r = a.fn.call(undefined, suite);
    if (r && typeof r.then === 'function') r.catch((err) => report(suite.fullName(), err));
  } catch (err) {
    report(suite.fullName(), err);
  } finally {
    collecting = outer;
  }
  start();
  return Promise.resolve();
}

function hook(kind) {
  return function (fn) {
    collecting[kind].push(fn);
  };
}

function variant(base, change) {
  return (name, options, fn) => {
    const a = args(name, options, fn);
    return base(a.name, { ...a.options, ...change }, a.fn);
  };
}

for (const fn of [test, describe]) {
  fn.skip = variant(fn, { skip: true });
  fn.todo = variant(fn, { todo: true });
  fn.only = variant(fn, {});
}

// ---------------------------------------------------------------- mocks

class MockFunctionContext {
  constructor(implementation, original) {
    this.calls = [];
    this.implementation = implementation;
    this.original = original;
    this.once = new Map();
    this.restore = () => {};
  }

  callCount() {
    return this.calls.length;
  }

  mockImplementation(fn) {
    this.implementation = fn;
  }

  mockImplementationOnce(fn, onCall) {
    this.once.set(onCall ?? this.calls.length, fn);
  }

  resetCalls() {
    this.calls = [];
  }
}

class MockTracker {
  constructor() {
    this.mocks = [];
  }

  fn(original = function () {}, implementation = original, options = {}) {
    if (typeof implementation === 'object' && implementation !== null) {
      options = implementation;
      implementation = original;
    }
    const ctx = new MockFunctionContext(implementation, original);
    const times = options.times ?? Infinity;
    const mock = function (...args) {
      const once = ctx.once.get(ctx.calls.length);
      ctx.once.delete(ctx.calls.length);
      const impl = once ?? (ctx.calls.length < times ? ctx.implementation : ctx.original);
      const call = { arguments: args, error: undefined, result: undefined, this: this, target: new.target, stack: new Error() };
      ctx.calls.push(call);
      try {
        call.result = new.target ? Reflect.construct(impl, args, new.target) : Reflect.apply(impl, this, args);
        return call.result;
      } catch (err) {
        call.error = err;
        throw err;
      }
    };
    Object.defineProperty(mock, 'mock', { value: ctx, enumerable: false });
    Object.defineProperty(mock, 'name', { value: original.name, configurable: true });
    Object.defineProperty(mock, 'length', { value: original.length, configurable: true });
    this.mocks.push(ctx);
    return mock;
  }

  method(object, methodName, implementation, options = {}) {
    if (typeof implementation === 'object' && implementation !== null) {
      options = implementation;
      implementation = undefined;
    }
    const descriptor = Object.getOwnPropertyDescriptor(object, methodName);
    const original = descriptor ? (options.getter ? descriptor.get : options.setter ? descriptor.set : descriptor.value) : object[methodName];
    const mock = this.fn(original, implementation ?? original, options);
    const key = options.getter ? 'get' : options.setter ? 'set' : 'value';
    Object.defineProperty(object, methodName, { configurable: true, enumerable: descriptor?.enumerable ?? true, [key]: mock, ...(key === 'value' ? { writable: true } : {}) });
    mock.mock.restore = () => {
      if (descriptor) Object.defineProperty(object, methodName, descriptor);
      else delete object[methodName];
    };
    return mock;
  }

  getter(object, methodName, implementation, options = {}) {
    return this.method(object, methodName, implementation, { ...options, getter: true });
  }

  setter(object, methodName, implementation, options = {}) {
    return this.method(object, methodName, implementation, { ...options, setter: true });
  }

  reset() {
    this.restoreAll();
    this.mocks = [];
  }

  restoreAll() {
    for (const ctx of this.mocks) ctx.restore();
  }
}

const mock = new MockTracker();

module.exports = test;
Object.assign(module.exports, {
  test,
  describe,
  suite: describe,
  it: test,
  before: hook('before'),
  after: hook('after'),
  beforeEach: hook('beforeEach'),
  afterEach: hook('afterEach'),
  mock,
  run() {
    throw new Error('run() is not available in the test harness');
  },
});
module.exports.it.skip = test.skip;
module.exports.it.todo = test.todo;
