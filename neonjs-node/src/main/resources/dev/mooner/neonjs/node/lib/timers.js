'use strict';
// node:timers, which are also the global setTimeout family when the module is installed: Timeout and Immediate
// objects (ref, unref, hasRef, refresh, a number as their primitive) over the realm's timers, within maxTimers.

const TIMEOUT_MAX = 2 ** 31 - 1;
const kRefed = Symbol('refed');
const byId = new Map();
let nextId = 1;

function checkCallback(callback) {
  if (typeof callback !== 'function') {
    throw binding.error('TypeError', `The "callback" argument must be of type function. Received ${typeof callback}`, 'ERR_INVALID_ARG_TYPE');
  }
}

function delayOf(after) {
  after = Number(after);
  if (!(after >= 1 && after <= TIMEOUT_MAX)) {
    if (after > TIMEOUT_MAX) {
      require('process').emitWarning(`${after} does not fit into a 32-bit signed integer.\nTimeout duration was set to 1.`, 'TimeoutOverflowWarning');
    }
    after = 1;
  }
  return after;
}

class Timeout {
  constructor(callback, after, args, isRepeat) {
    this._idleTimeout = after;
    this._onTimeout = callback;
    this._timerArgs = args;
    this._repeat = isRepeat ? after : null;
    this._destroyed = false;
    this[kRefed] = true;
    this._id = nextId++;
    this._handle = undefined;
    this._schedule();
  }

  _schedule() {
    this._handle = binding.schedule(() => this._fire(), Math.floor(this._idleTimeout), this[kRefed]);
    byId.set(this._id, this);
  }

  _fire() {
    if (this._destroyed) return;
    this._handle = undefined;
    if (this._repeat === null) {
      this._destroyed = true;
      byId.delete(this._id);
    }
    try {
      this._onTimeout.apply(this, this._timerArgs);
    } finally {
      // an interval goes on after an exception in its callback, as in Node
      if (this._repeat !== null && !this._destroyed) {
        try {
          this._schedule();
        } catch (e) {
          // no slot left (maxTimers): the interval ends, and says why
          this._destroyed = true;
          byId.delete(this._id);
          throw e;
        }
      }
    }
  }

  refresh() {
    if (!this._destroyed) {
      if (this._handle !== undefined) binding.cancel(this._handle);
      this._schedule();
    }
    return this;
  }

  unref() {
    this[kRefed] = false;
    if (this._handle !== undefined) binding.setRef(this._handle, false);
    return this;
  }

  ref() {
    this[kRefed] = true;
    if (this._handle !== undefined) binding.setRef(this._handle, true);
    return this;
  }

  hasRef() {
    return this[kRefed];
  }

  close() {
    clearTimeout(this);
    return this;
  }

  [Symbol.toPrimitive]() {
    return this._id;
  }
}

class Immediate {
  constructor(callback, args) {
    this._onImmediate = callback;
    this._argv = args;
    this._destroyed = false;
    this[kRefed] = true;
    this._handle = binding.schedule(() => this._run(), 0, true);
  }

  _run() {
    if (this._destroyed) return;
    this._destroyed = true;
    this._handle = undefined;
    this._onImmediate.apply(this, this._argv);
  }

  ref() {
    this[kRefed] = true;
    if (this._handle !== undefined) binding.setRef(this._handle, true);
    return this;
  }

  unref() {
    this[kRefed] = false;
    if (this._handle !== undefined) binding.setRef(this._handle, false);
    return this;
  }

  hasRef() {
    return this[kRefed];
  }
}

if (Symbol.dispose) {
  Timeout.prototype[Symbol.dispose] = function () { clearTimeout(this); };
  Immediate.prototype[Symbol.dispose] = function () { clearImmediate(this); };
}

function setTimeout(callback, after, ...args) {
  checkCallback(callback);
  return new Timeout(callback, delayOf(after), args, false);
}

function setInterval(callback, repeat, ...args) {
  checkCallback(callback);
  return new Timeout(callback, delayOf(repeat), args, true);
}

function clearTimeout(timer) {
  if (timer instanceof Timeout) {
    timer._destroyed = true;
    if (timer._handle !== undefined) {
      binding.cancel(timer._handle);
      timer._handle = undefined;
    }
    byId.delete(timer._id);
    return;
  }
  if (typeof timer === 'number' || typeof timer === 'string') {
    const t = byId.get(Number(timer));
    if (t !== undefined) clearTimeout(t);
  }
}

function setImmediate(callback, ...args) {
  checkCallback(callback);
  return new Immediate(callback, args);
}

function clearImmediate(immediate) {
  if (!(immediate instanceof Immediate) || immediate._destroyed) return;
  immediate._destroyed = true;
  if (immediate._handle !== undefined) {
    binding.cancel(immediate._handle);
    immediate._handle = undefined;
  }
}

// util.promisify(setTimeout) is timers/promises' setTimeout, as in Node
const customPromisify = Symbol.for('nodejs.util.promisify.custom');
Object.defineProperty(setTimeout, customPromisify, {
  enumerable: true,
  get() { return require('timers/promises').setTimeout; },
});
Object.defineProperty(setImmediate, customPromisify, {
  enumerable: true,
  get() { return require('timers/promises').setImmediate; },
});

module.exports = {
  setTimeout,
  clearTimeout,
  setInterval,
  clearInterval: clearTimeout,
  setImmediate,
  clearImmediate,
};
Object.defineProperty(module.exports, 'promises', {
  enumerable: true,
  configurable: true,
  get() { return require('timers/promises'); },
});
