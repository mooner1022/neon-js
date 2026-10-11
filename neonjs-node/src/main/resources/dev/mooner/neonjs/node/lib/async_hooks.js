'use strict';
// node:async_hooks: NeonJS tracks no asynchronous resources, so there are no hooks to run and no store to carry
// across them. AsyncResource is Node's own (lib/async_hooks.js, MIT license, see NOTICE) as it runs with no hook
// enabled: it calls what it is given. AsyncLocalStorage, createHook and the current resource's ids need the tracking
// and say so when used (ERR_UNAVAILABLE); the module loads, as do node:events' EventEmitterAsyncResource and the
// libraries that import them at their top.

const { codes: { ERR_INVALID_ASYNC_ID } } = require('internal/errors');
const { validateFunction, validateString } = require('internal/validators');
const { unavailableClass, unavailableFunction } = require('internal/unavailable');

const ReflectApply = Reflect.apply;
const async_id_symbol = Symbol('asyncId');
const trigger_async_id_symbol = Symbol('triggerAsyncId');
// the ids Node would give: 1 is the main script's
let lastAsyncId = 1;

class AsyncResource {
  constructor(type, opts = {}) {
    validateString(type, 'type');

    let triggerAsyncId = opts;
    if (typeof opts !== 'number') {
      triggerAsyncId = opts.triggerAsyncId === undefined ? 1 : opts.triggerAsyncId;
    }

    // Unlike emitInitScript, AsyncResource doesn't supports null as the
    // triggerAsyncId.
    if (!Number.isSafeInteger(triggerAsyncId) || triggerAsyncId < -1) {
      throw new ERR_INVALID_ASYNC_ID('triggerAsyncId', triggerAsyncId);
    }

    this[async_id_symbol] = ++lastAsyncId;
    this[trigger_async_id_symbol] = triggerAsyncId;
  }

  runInAsyncScope(fn, thisArg, ...args) {
    return ReflectApply(fn, thisArg, args);
  }

  emitDestroy() {
    return this;
  }

  asyncId() {
    return this[async_id_symbol];
  }

  triggerAsyncId() {
    return this[trigger_async_id_symbol];
  }

  bind(fn, thisArg) {
    validateFunction(fn, 'fn');
    let bound;
    if (thisArg === undefined) {
      const resource = this;
      bound = function(...args) {
        args.unshift(fn, this);
        return ReflectApply(resource.runInAsyncScope, resource, args);
      };
    } else {
      bound = this.runInAsyncScope.bind(this, fn, thisArg);
    }
    Object.defineProperties(bound, {
      'length': {
        __proto__: null,
        configurable: true,
        enumerable: false,
        value: fn.length,
        writable: false,
      },
    });
    return bound;
  }

  static bind(fn, type, thisArg) {
    type ||= fn.name;
    return (new AsyncResource(type || 'bound-anonymous-fn')).bind(fn, thisArg);
  }
}

const AsyncLocalStorage = unavailableClass('async_hooks.AsyncLocalStorage');

module.exports = {
  // Public API
  get AsyncLocalStorage() {
    return AsyncLocalStorage;
  },
  createHook: unavailableFunction('async_hooks.createHook'),
  executionAsyncId: unavailableFunction('async_hooks.executionAsyncId'),
  triggerAsyncId: unavailableFunction('async_hooks.triggerAsyncId'),
  executionAsyncResource: unavailableFunction('async_hooks.executionAsyncResource'),
  asyncWrapProviders: Object.freeze({ __proto__: null }),
  // Embedder API
  AsyncResource,
};
