'use strict';
// What the libraries take from Node's lib/internal/abort_controller.js (MIT license, see NOTICE): AbortController
// and AbortSignal are the web globals; there are no workers to transfer a signal to, so transferable ones are the
// usual ones.

const { AbortController, AbortSignal } = globalThis;
const {
  codes: { ERR_INVALID_ARG_TYPE },
} = require('internal/errors');
const { validateAbortSignal, validateObject, kValidateObjectAllowObjects } = require('internal/validators');

function transferableAbortSignal(signal) {
  if (!(signal instanceof AbortSignal)) throw new ERR_INVALID_ARG_TYPE('signal', 'AbortSignal', signal);
  return signal;
}

function transferableAbortController() {
  return new AbortController();
}

// a promise settled once signal aborts (util.aborted)
async function aborted(signal, resource) {
  if (signal === undefined) throw new ERR_INVALID_ARG_TYPE('signal', 'AbortSignal', signal);
  validateAbortSignal(signal, 'signal');
  validateObject(resource, 'resource', kValidateObjectAllowObjects);
  if (signal.aborted) return;
  return new Promise((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }));
}

module.exports = {
  AbortController,
  AbortSignal,
  aborted,
  transferableAbortController,
  transferableAbortSignal,
};
