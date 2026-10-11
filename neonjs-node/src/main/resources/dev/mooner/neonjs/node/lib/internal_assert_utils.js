'use strict';
// assert.ok's failure, after Node's lib/internal/assert/utils.js (MIT license, see NOTICE). Node quotes the failing
// expression from the caller's source file; scripts here have no files to read, so the message is the generated one
// ("false == true").

const AssertionError = require('internal/assert/assertion_error');
const { isError } = require('internal/util');

function innerOk(fn, argLen, value, message) {
  if (!value) {
    let generatedMessage = false;
    if (argLen === 0) {
      generatedMessage = true;
      message = 'No value argument passed to `assert.ok()`';
    } else if (message == null) {
      generatedMessage = true;
      message = undefined;
    } else if (isError(message)) {
      throw message;
    }
    const err = new AssertionError({
      actual: value,
      expected: true,
      message,
      operator: '==',
      stackStartFn: fn,
    });
    err.generatedMessage = generatedMessage;
    throw err;
  }
}

module.exports = {
  innerOk,
};
