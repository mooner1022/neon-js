'use strict';
// node:worker_threads: a script runs on its own thread here, the main one. It loads; a Worker says what is missing
// when made (ERR_UNAVAILABLE); the message channels are the web globals' when there are some.

const { unavailableClass, unavailableFunction } = require('internal/unavailable');

const environmentData = new Map();

module.exports = {
  BroadcastChannel: globalThis.BroadcastChannel ?? unavailableClass('worker_threads.BroadcastChannel'),
  MessageChannel: globalThis.MessageChannel ?? unavailableClass('worker_threads.MessageChannel'),
  MessagePort: globalThis.MessagePort ?? unavailableClass('worker_threads.MessagePort'),
  SHARE_ENV: Symbol.for('nodejs.worker_threads.SHARE_ENV'),
  Worker: unavailableClass('worker_threads.Worker'),
  getEnvironmentData: (key) => environmentData.get(key),
  // Web Locks are not here
  locks: Object.freeze({
    __proto__: null,
    query: unavailableFunction('worker_threads.locks.query'),
    request: unavailableFunction('worker_threads.locks.request'),
  }),
  isInternalThread: false,
  isMainThread: true,
  isMarkedAsUntransferable: () => false,
  markAsUncloneable: () => {},
  markAsUntransferable: () => {},
  moveMessagePortToContext: unavailableFunction('worker_threads.moveMessagePortToContext'),
  parentPort: null,
  postMessageToThread: unavailableFunction('worker_threads.postMessageToThread'),
  receiveMessageOnPort: () => undefined,
  resourceLimits: {},
  setEnvironmentData: (key, value) => {
    if (value === undefined) environmentData.delete(key);
    else environmentData.set(key, value);
  },
  threadId: 0,
  threadName: '',
  workerData: null,
};
