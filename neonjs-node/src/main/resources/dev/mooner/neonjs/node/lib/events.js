'use strict';
// node:events: EventEmitter as Node.js has it (listener storage in _events, as some libraries expect), and the
// helpers once, on, getEventListeners, setMaxListeners, addAbortListener. A leaf module: it requires process only
// lazily, to emit the warning for too many listeners.

const kCapture = Symbol('kCapture');
const kRejection = Symbol.for('nodejs.rejection');
const errorMonitor = Symbol('events.errorMonitor');
let defaultMaxListeners = 10;
let captureRejections = false;

function checkListener(listener) {
  if (typeof listener !== 'function') {
    throw binding.error('TypeError', `The "listener" argument must be of type function. Received ${describe(listener)}`, 'ERR_INVALID_ARG_TYPE');
  }
}

function describe(v) {
  if (v === null) return 'null';
  if (typeof v === 'function') return `function ${v.name}`;
  if (typeof v === 'object') return 'an instance of ' + (v.constructor && v.constructor.name || 'Object');
  return `type ${typeof v} (${String(v)})`;
}

function checkCount(n, name) {
  if (typeof n !== 'number' || n < 0 || Number.isNaN(n)) {
    throw binding.error('RangeError', `The value of "${name}" is out of range. It must be a non-negative number. Received ${String(n)}`, 'ERR_OUT_OF_RANGE');
  }
}

function EventEmitter(opts) {
  EventEmitter.init.call(this, opts);
}

EventEmitter.prototype._events = undefined;
EventEmitter.prototype._eventsCount = 0;
EventEmitter.prototype._maxListeners = undefined;

EventEmitter.init = function init(opts) {
  if (this._events === undefined || this._events === Object.getPrototypeOf(this)._events) {
    this._events = { __proto__: null };
    this._eventsCount = 0;
  }
  this._maxListeners = this._maxListeners || undefined;
  if (opts && opts.captureRejections) {
    if (typeof opts.captureRejections !== 'boolean') {
      throw binding.error('TypeError', 'The "options.captureRejections" property must be of type boolean.', 'ERR_INVALID_ARG_TYPE');
    }
    this[kCapture] = true;
  } else {
    this[kCapture] = EventEmitter.prototype[kCapture];
  }
};
EventEmitter.prototype[kCapture] = false;

Object.defineProperty(EventEmitter, 'defaultMaxListeners', {
  enumerable: true,
  get() { return defaultMaxListeners; },
  set(n) {
    checkCount(n, 'defaultMaxListeners');
    defaultMaxListeners = n;
  },
});

Object.defineProperty(EventEmitter, 'captureRejections', {
  enumerable: true,
  get() { return captureRejections; },
  set(v) {
    if (typeof v !== 'boolean') throw binding.error('TypeError', 'The "EventEmitter.captureRejections" property must be of type boolean.', 'ERR_INVALID_ARG_TYPE');
    captureRejections = v;
    EventEmitter.prototype[kCapture] = v;
  },
});

EventEmitter.errorMonitor = errorMonitor;
EventEmitter.captureRejectionSymbol = kRejection;

EventEmitter.prototype.setMaxListeners = function setMaxListeners(n) {
  checkCount(n, 'n');
  this._maxListeners = n;
  return this;
};

function maxListenersOf(that) {
  return that._maxListeners === undefined ? defaultMaxListeners : that._maxListeners;
}

EventEmitter.prototype.getMaxListeners = function getMaxListeners() {
  return maxListenersOf(this);
};

function addCatch(that, promise, type, args) {
  if (!that[kCapture]) return;
  try {
    const then = promise.then;
    if (typeof then === 'function') {
      then.call(promise, undefined, (err) => {
        // after the current tick, as Node does, so that an error thrown here is not swallowed
        binding.nextTick(() => emitUnhandledRejectionOrErr(that, err, type, args), []);
      });
    }
  } catch (err) {
    that.emit('error', err);
  }
}

function emitUnhandledRejectionOrErr(that, err, type, args) {
  if (typeof that[kRejection] === 'function') {
    that[kRejection](err, type, ...args);
  } else {
    const prev = that[kCapture];
    try {
      that[kCapture] = false;
      that.emit('error', err);
    } finally {
      that[kCapture] = prev;
    }
  }
}

EventEmitter.prototype.emit = function emit(type, ...args) {
  let doError = type === 'error';
  const events = this._events;
  if (events !== undefined) {
    if (doError && events[errorMonitor] !== undefined) this.emit(errorMonitor, ...args);
    doError = doError && events.error === undefined;
  } else if (!doError) {
    return false;
  }
  if (doError) {
    let er = args.length > 0 ? args[0] : undefined;
    if (er instanceof Error) throw er;
    const err = binding.error('Error', 'Unhandled error.' + (er === undefined ? '' : ` (${typeof er === 'string' ? er : describe(er)})`), 'ERR_UNHANDLED_ERROR');
    err.context = er;
    throw err;
  }
  const handler = events[type];
  if (handler === undefined) return false;
  if (typeof handler === 'function') {
    const result = handler.apply(this, args);
    if (result !== undefined && result !== null) addCatch(this, result, type, args);
  } else {
    const listeners = handler.slice();
    for (let i = 0; i < listeners.length; ++i) {
      const result = listeners[i].apply(this, args);
      if (result !== undefined && result !== null) addCatch(this, result, type, args);
    }
  }
  return true;
};

function addListener(target, type, listener, prepend) {
  checkListener(listener);
  let events = target._events;
  if (events === undefined) {
    events = target._events = { __proto__: null };
    target._eventsCount = 0;
  } else {
    if (events.newListener !== undefined) {
      target.emit('newListener', type, listener.listener ? listener.listener : listener);
      events = target._events;
    }
  }
  let existing = events[type];
  if (existing === undefined) {
    events[type] = listener;
    ++target._eventsCount;
  } else {
    if (typeof existing === 'function') {
      existing = events[type] = prepend ? [listener, existing] : [existing, listener];
    } else if (prepend) {
      existing.unshift(listener);
    } else {
      existing.push(listener);
    }
    const m = maxListenersOf(target);
    if (m > 0 && existing.length > m && !existing.warned) {
      existing.warned = true;
      const name = target.constructor && target.constructor.name || 'EventEmitter';
      const w = new Error(`Possible EventEmitter memory leak detected. ${existing.length} ${String(type)} listeners added to [${name}]. ` +
        `MaxListeners is ${m}. Use emitter.setMaxListeners() to increase limit`);
      w.name = 'MaxListenersExceededWarning';
      w.emitter = target;
      w.type = type;
      w.count = existing.length;
      require('process').emitWarning(w);
    }
  }
  return target;
}

EventEmitter.prototype.addListener = function addListener_(type, listener) {
  return addListener(this, type, listener, false);
};
EventEmitter.prototype.on = EventEmitter.prototype.addListener;

EventEmitter.prototype.prependListener = function prependListener(type, listener) {
  return addListener(this, type, listener, true);
};

function onceWrapper() {
  if (!this.fired) {
    this.target.removeListener(this.type, this.wrapFn);
    this.fired = true;
    if (arguments.length === 0) return this.listener.call(this.target);
    return this.listener.apply(this.target, arguments);
  }
}

function onceWrap(target, type, listener) {
  const state = { fired: false, wrapFn: undefined, target, type, listener };
  const wrapped = onceWrapper.bind(state);
  wrapped.listener = listener;
  state.wrapFn = wrapped;
  return wrapped;
}

EventEmitter.prototype.once = function once(type, listener) {
  checkListener(listener);
  this.on(type, onceWrap(this, type, listener));
  return this;
};

EventEmitter.prototype.prependOnceListener = function prependOnceListener(type, listener) {
  checkListener(listener);
  this.prependListener(type, onceWrap(this, type, listener));
  return this;
};

EventEmitter.prototype.removeListener = function removeListener(type, listener) {
  checkListener(listener);
  const events = this._events;
  if (events === undefined) return this;
  const list = events[type];
  if (list === undefined) return this;
  if (list === listener || list.listener === listener) {
    this._eventsCount -= 1;
    if (this._eventsCount === 0) this._events = { __proto__: null };
    else delete events[type];
    if (events.removeListener !== undefined) this.emit('removeListener', type, list.listener || listener);
  } else if (typeof list !== 'function') {
    let position = -1;
    for (let i = list.length - 1; i >= 0; i--) {
      if (list[i] === listener || list[i].listener === listener) {
        position = i;
        break;
      }
    }
    if (position < 0) return this;
    if (position === 0) list.shift();
    else list.splice(position, 1);
    if (list.length === 1) events[type] = list[0];
    if (events.removeListener !== undefined) this.emit('removeListener', type, listener);
  }
  return this;
};
EventEmitter.prototype.off = EventEmitter.prototype.removeListener;

EventEmitter.prototype.removeAllListeners = function removeAllListeners(type) {
  const events = this._events;
  if (events === undefined) return this;
  if (events.removeListener === undefined) {
    if (arguments.length === 0) {
      this._events = { __proto__: null };
      this._eventsCount = 0;
    } else if (events[type] !== undefined) {
      if (--this._eventsCount === 0) this._events = { __proto__: null };
      else delete events[type];
    }
    return this;
  }
  if (arguments.length === 0) {
    for (const key of Reflect.ownKeys(events)) {
      if (key === 'removeListener') continue;
      this.removeAllListeners(key);
    }
    this.removeAllListeners('removeListener');
    this._events = { __proto__: null };
    this._eventsCount = 0;
    return this;
  }
  const listeners = events[type];
  if (typeof listeners === 'function') {
    this.removeListener(type, listeners);
  } else if (listeners !== undefined) {
    for (let i = listeners.length - 1; i >= 0; i--) this.removeListener(type, listeners[i]);
  }
  return this;
};

function listenersOf(target, type, unwrap) {
  const events = target._events;
  if (events === undefined) return [];
  const l = events[type];
  if (l === undefined) return [];
  if (typeof l === 'function') return unwrap ? [l.listener || l] : [l];
  return unwrap ? l.map((x) => x.listener || x) : l.slice();
}

EventEmitter.prototype.listeners = function listeners(type) {
  return listenersOf(this, type, true);
};

EventEmitter.prototype.rawListeners = function rawListeners(type) {
  return listenersOf(this, type, false);
};

function listenerCount(type, listener) {
  const events = this._events;
  if (events !== undefined) {
    const l = events[type];
    if (typeof l === 'function') return listener == null || listener === l || listener === l.listener ? 1 : 0;
    if (l !== undefined) {
      if (listener == null) return l.length;
      return l.filter((x) => x === listener || x.listener === listener).length;
    }
  }
  return 0;
}
EventEmitter.prototype.listenerCount = listenerCount;

EventEmitter.listenerCount = function (emitter, type) {
  if (typeof emitter.listenerCount === 'function') return emitter.listenerCount(type);
  return listenerCount.call(emitter, type);
};

EventEmitter.prototype.eventNames = function eventNames() {
  return this._eventsCount > 0 ? Reflect.ownKeys(this._events) : [];
};

// ------------------------------------------------------------------ helpers

function abortError(signal) {
  return binding.abortError(signal && signal.reason);
}

function checkSignal(signal) {
  if (signal !== undefined && (signal === null || typeof signal !== 'object' || !('aborted' in signal))) {
    throw binding.error('TypeError', 'The "options.signal" property must be an instance of AbortSignal.', 'ERR_INVALID_ARG_TYPE');
  }
}

function once(emitter, name, options = {}) {
  const signal = options ? options.signal : undefined;
  checkSignal(signal);
  if (signal && signal.aborted) return Promise.reject(abortError(signal));
  return new Promise((resolve, reject) => {
    const isTarget = typeof emitter.addEventListener === 'function' && typeof emitter.on !== 'function';
    let errorListener;
    const cleanup = () => {
      if (isTarget) emitter.removeEventListener(name, resolver);
      else {
        emitter.removeListener(name, resolver);
        if (errorListener !== undefined) emitter.removeListener('error', errorListener);
      }
      if (signal) signal.removeEventListener('abort', onAbort);
    };
    const resolver = (...args) => {
      cleanup();
      resolve(args);
    };
    const onAbort = () => {
      cleanup();
      reject(abortError(signal));
    };
    if (isTarget) {
      emitter.addEventListener(name, resolver, { once: true });
    } else {
      emitter.on(name, resolver);
      if (name !== 'error') {
        errorListener = (err) => {
          cleanup();
          reject(err);
        };
        emitter.on('error', errorListener);
      }
    }
    if (signal) signal.addEventListener('abort', onAbort, { once: true });
  });
}

function on(emitter, event, options = {}) {
  const signal = options ? options.signal : undefined;
  checkSignal(signal);
  if (signal && signal.aborted) throw abortError(signal);
  const unconsumed = [];
  const pending = [];
  let error = null;
  let finished = false;
  const close = options && options.close || [];
  const eventHandler = (...args) => {
    const p = pending.shift();
    if (p) p.resolve({ value: args, done: false });
    else unconsumed.push(args);
  };
  const errorHandler = (err) => {
    finished = true;
    const p = pending.shift();
    if (p) p.reject(err);
    else error = err;
    iterator.return();
  };
  const closeHandler = () => {
    const p = pending.shift();
    if (p) p.resolve({ value: undefined, done: true });
    iterator.return();
  };
  const onAbort = () => errorHandler(abortError(signal));
  const removeAll = () => {
    emitter.removeListener(event, eventHandler);
    emitter.removeListener('error', errorHandler);
    for (const c of close) emitter.removeListener(c, closeHandler);
    if (signal) signal.removeEventListener('abort', onAbort);
  };
  const iterator = Object.setPrototypeOf({
    next() {
      const value = unconsumed.shift();
      if (value) return Promise.resolve({ value, done: false });
      if (error) {
        const p = Promise.reject(error);
        error = null;
        return p;
      }
      if (finished) return Promise.resolve({ value: undefined, done: true });
      return new Promise((resolve, reject) => pending.push({ resolve, reject }));
    },
    return() {
      removeAll();
      finished = true;
      for (const p of pending) p.resolve({ value: undefined, done: true });
      pending.length = 0;
      return Promise.resolve({ value: undefined, done: true });
    },
    throw(err) {
      error = err;
      removeAll();
      return Promise.reject(err);
    },
    [Symbol.asyncIterator]() { return this; },
  }, Object.getPrototypeOf(Object.getPrototypeOf(async function* () {}).prototype));
  emitter.on(event, eventHandler);
  if (event !== 'error') emitter.on('error', errorHandler);
  for (const c of close) emitter.on(c, closeHandler);
  if (signal) signal.addEventListener('abort', onAbort, { once: true });
  return iterator;
}

function getEventListeners(emitterOrTarget, type) {
  if (typeof emitterOrTarget.listeners === 'function') return emitterOrTarget.listeners(type);
  throw binding.error('TypeError', 'getEventListeners supports EventEmitters only here', 'ERR_INVALID_ARG_TYPE');
}

function getMaxListeners(emitter) {
  if (typeof emitter.getMaxListeners === 'function') return emitter.getMaxListeners();
  return defaultMaxListeners;
}

function setMaxListeners(n = defaultMaxListeners, ...targets) {
  checkCount(n, 'n');
  if (targets.length === 0) {
    defaultMaxListeners = n;
  } else {
    for (const t of targets) {
      if (typeof t.setMaxListeners === 'function') t.setMaxListeners(n);
    }
  }
}

function addAbortListener(signal, listener) {
  checkSignal(signal);
  checkListener(listener);
  if (signal.aborted) {
    binding.nextTick(() => listener(), []);
    return { [Symbol.dispose || Symbol.for('Symbol.dispose')]() {} };
  }
  signal.addEventListener('abort', listener, { once: true });
  return { [Symbol.dispose || Symbol.for('Symbol.dispose')]() { signal.removeEventListener('abort', listener); } };
}

module.exports = EventEmitter;
EventEmitter.EventEmitter = EventEmitter;
EventEmitter.usingDomains = false;
EventEmitter.once = once;
EventEmitter.on = on;
EventEmitter.getEventListeners = getEventListeners;
EventEmitter.getMaxListeners = getMaxListeners;
EventEmitter.setMaxListeners = setMaxListeners;
EventEmitter.addAbortListener = addAbortListener;
EventEmitter.EventTarget = globalThis.EventTarget;
EventEmitter.Event = globalThis.Event;
