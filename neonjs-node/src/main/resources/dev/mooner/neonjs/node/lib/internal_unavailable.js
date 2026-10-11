'use strict';
// The modules of Node that a sandbox does not give (a file system, processes, sockets): they load, so that a library
// importing them at its top still loads, and each function or class says what is missing when used
// (ERR_UNAVAILABLE).

const { codes: { ERR_UNAVAILABLE } } = require('internal/errors');

// a function that throws for what it would have done
function unavailableFunction(what) {
  const fn = function () {
    throw new ERR_UNAVAILABLE(what);
  };
  Object.defineProperty(fn, 'name', { value: what.slice(what.lastIndexOf('.') + 1), configurable: true });
  return fn;
}

// the same, a promise rejected with it
function unavailablePromise(what) {
  const fn = async function () {
    throw new ERR_UNAVAILABLE(what);
  };
  Object.defineProperty(fn, 'name', { value: what.slice(what.lastIndexOf('.') + 1), configurable: true });
  return fn;
}

// a function of Node's callback style: what is missing goes to its callback (on the next tick), or is thrown when
// there is none
function unavailableCallback(what) {
  const fn = function (...args) {
    const callback = args[args.length - 1];
    const err = new ERR_UNAVAILABLE(what);
    if (typeof callback !== 'function') throw err;
    require('process').nextTick(callback, err);
  };
  Object.defineProperty(fn, 'name', { value: what.slice(what.lastIndexOf('.') + 1), configurable: true });
  return fn;
}

// a class that cannot be made
function unavailableClass(what) {
  const name = what.slice(what.lastIndexOf('.') + 1);
  return {
    [name]: class {
      constructor() {
        throw new ERR_UNAVAILABLE(what);
      }
    },
  }[name];
}

// a module object of these: functions, async functions (promises) and classes, by name
function unavailableModule(module, { functions = [], promises = [], classes = [], values = {} }) {
  const exports = {};
  for (const name of functions) exports[name] = unavailableFunction(`${module}.${name}`);
  for (const name of promises) exports[name] = unavailablePromise(`${module}.${name}`);
  for (const name of classes) exports[name] = unavailableClass(`${module}.${name}`);
  return Object.assign(exports, values);
}

module.exports = {
  unavailableCallback,
  unavailableClass,
  unavailableFunction,
  unavailableModule,
  unavailablePromise,
};
