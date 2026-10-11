'use strict';
// node:timers/promises: setTimeout and setImmediate as promises, setInterval as an async iterator, and scheduler,
// with AbortSignal support (an aborted wait rejects with Node's AbortError).

const timers = require('timers');

function checkOptions(options) {
  if (options === null || typeof options !== 'object') {
    throw binding.error('TypeError', 'The "options" argument must be of type object.', 'ERR_INVALID_ARG_TYPE');
  }
  const { signal, ref = true } = options;
  if (signal !== undefined && (signal === null || typeof signal !== 'object' || !('aborted' in signal))) {
    throw binding.error('TypeError', 'The "options.signal" property must be an instance of AbortSignal.', 'ERR_INVALID_ARG_TYPE');
  }
  if (typeof ref !== 'boolean') {
    throw binding.error('TypeError', 'The "options.ref" property must be of type boolean.', 'ERR_INVALID_ARG_TYPE');
  }
  return { signal, ref };
}

function wait(start, clear, value, options) {
  let opts;
  try {
    opts = checkOptions(options);
  } catch (e) {
    return Promise.reject(e);
  }
  const { signal, ref } = opts;
  if (signal && signal.aborted) return Promise.reject(binding.abortError(signal.reason));
  return new Promise((resolve, reject) => {
    const onAbort = () => {
      clear(handle);
      reject(binding.abortError(signal.reason));
    };
    const handle = start(() => {
      if (signal) signal.removeEventListener('abort', onAbort);
      resolve(value);
    });
    if (!ref) handle.unref();
    if (signal) signal.addEventListener('abort', onAbort, { once: true });
  });
}

function setTimeout(delay, value, options = {}) {
  return wait((fn) => timers.setTimeout(fn, delay), timers.clearTimeout, value, options);
}

function setImmediate(value, options = {}) {
  return wait((fn) => timers.setImmediate(fn), timers.clearImmediate, value, options);
}

async function* setInterval(delay, value, options = {}) {
  const { signal, ref } = checkOptions(options);
  if (signal && signal.aborted) throw binding.abortError(signal.reason);
  let notYielded = 0;
  let wake;
  let aborted = false;
  const interval = timers.setInterval(() => {
    notYielded++;
    if (wake) {
      wake();
      wake = undefined;
    }
  }, delay);
  if (!ref) interval.unref();
  const onAbort = () => {
    aborted = true;
    if (wake) {
      wake();
      wake = undefined;
    }
  };
  if (signal) signal.addEventListener('abort', onAbort, { once: true });
  try {
    while (!aborted) {
      if (notYielded === 0) await new Promise((resolve) => { wake = resolve; });
      if (aborted) break;
      for (; notYielded > 0; notYielded--) yield value;
    }
    throw binding.abortError(signal.reason);
  } finally {
    timers.clearInterval(interval);
    if (signal) signal.removeEventListener('abort', onAbort);
  }
}

const scheduler = {
  wait(delay, options) {
    return setTimeout(delay, undefined, options);
  },
  yield() {
    return setImmediate();
  },
};

module.exports = { setTimeout, setImmediate, setInterval, scheduler };
