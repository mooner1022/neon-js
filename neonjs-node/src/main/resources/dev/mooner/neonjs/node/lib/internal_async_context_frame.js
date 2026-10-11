'use strict';
// What the libraries take from Node's lib/internal/async_context_frame.js (MIT license, see NOTICE): there is no
// AsyncLocalStorage here, so never a current frame.

class AsyncContextFrame {
  static current() {
    return undefined;
  }
}

module.exports = AsyncContextFrame;
