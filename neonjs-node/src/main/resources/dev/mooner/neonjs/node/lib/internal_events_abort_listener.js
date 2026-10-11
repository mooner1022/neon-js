// Ported from Node.js v22.12.0 lib/internal/events/abort_listener.js (MIT license, see NOTICE); queueMicrotask is the global one.
'use strict';

const {
  SymbolDispose,
} = require('internal/util');
const {
  validateAbortSignal,
  validateFunction,
} = require('internal/validators');
const {
  codes: {
    ERR_INVALID_ARG_TYPE,
  },
} = require('internal/errors');

let queueMicrotask;
let kResistStopPropagation;

/**
 * @param {AbortSignal} signal
 * @param {EventListener} listener
 * @returns {Disposable}
 */
function addAbortListener(signal, listener) {
  if (signal === undefined) {
    throw new ERR_INVALID_ARG_TYPE('signal', 'AbortSignal', signal);
  }
  validateAbortSignal(signal, 'signal');
  validateFunction(listener, 'listener');

  let removeEventListener;
  if (signal.aborted) {
    queueMicrotask ??= globalThis.queueMicrotask;
    queueMicrotask(() => listener());
  } else {
    kResistStopPropagation ??= require('internal/event_target').kResistStopPropagation;
    // TODO(atlowChemi) add { subscription: true } and return directly
    signal.addEventListener('abort', listener, { __proto__: null, once: true, [kResistStopPropagation]: true });
    removeEventListener = () => {
      signal.removeEventListener('abort', listener);
    };
  }
  return {
    __proto__: null,
    [SymbolDispose]() {
      removeEventListener?.();
    },
  };
}

module.exports = {
  __proto__: null,
  addAbortListener,
};
