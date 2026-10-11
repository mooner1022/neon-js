'use strict';
// What node:util takes from Node's lib/internal/util/trace_sigint.js (MIT license, see NOTICE): there are no signals
// here, so there is nothing to trace; the argument is checked as Node checks it.

const { validateBoolean } = require('internal/validators');

function setTraceSigInt(enable) {
  validateBoolean(enable, 'enable');
}

module.exports = {
  setTraceSigInt,
};
