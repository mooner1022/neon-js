// Ported from Node.js v26.11.1 lib/internal/assert.js (MIT license, see NOTICE).
'use strict';

let error;
function lazyError() {
  return error ??= require('internal/errors').codes.ERR_INTERNAL_ASSERTION;
}

function assert(value, message) {
  if (!value) {
    const ERR_INTERNAL_ASSERTION = lazyError();
    throw new ERR_INTERNAL_ASSERTION(message);
  }
}

function fail(message) {
  const ERR_INTERNAL_ASSERTION = lazyError();
  throw new ERR_INTERNAL_ASSERTION(message);
}

assert.fail = fail;

module.exports = assert;
