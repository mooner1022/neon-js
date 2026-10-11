'use strict';
// What the libraries take from Node's lib/internal/abort_controller.js (MIT license, see NOTICE): AbortController
// and AbortSignal are the web globals.

module.exports = {
  AbortController: globalThis.AbortController,
  AbortSignal: globalThis.AbortSignal,
};
