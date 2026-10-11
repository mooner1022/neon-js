'use strict';
// What the libraries take from Node's lib/internal/async_hooks.js (MIT license, see NOTICE): there are no async hooks
// here, so none is enabled.

function enabledHooksExist() {
  return false;
}

module.exports = {
  enabledHooksExist,
};
